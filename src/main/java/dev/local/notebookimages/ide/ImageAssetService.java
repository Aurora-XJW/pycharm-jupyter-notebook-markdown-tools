package dev.local.notebookimages.ide;

import com.intellij.notification.*;
import com.intellij.openapi.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.newvfs.*;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.notebooks.visualization.NotebookCellLines;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import dev.local.notebookimages.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

@Service(Service.Level.PROJECT)
public final class ImageAssetService implements Disposable {
    private final Project project;
    private final ExecutorService io = AppExecutorUtil.createBoundedApplicationPoolExecutor("Notebook image files", 1);
    private final Alarm debounce = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final AtomicLong revision = new AtomicLong();
    private final AtomicInteger insertions = new AtomicInteger();
    private volatile boolean disposed;
    private boolean started;
    private boolean cleanupPending;
    private boolean manualPending;
    private String lastProblem = "";
    private volatile AssetRepository repository;

    public ImageAssetService(Project project) { this.project = project; }
    public static ImageAssetService get(Project project) { return project.getService(ImageAssetService.class); }

    public synchronized AssetRepository repository() throws IOException {
        if (repository == null) {
            if (project.getBasePath() == null) throw new IOException("Open a local project first.");
            repository = new AssetRepository(Path.of(project.getBasePath()));
        }
        return repository;
    }

    public void start() {
        if (started || disposed || project.getBasePath() == null) return;
        started = true;
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) {
                if (relevant(event.getDocument())) {
                    revision.incrementAndGet();
                    schedule(false, false);
                }
            }
        }, this);
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(FileDocumentManagerListener.TOPIC, new FileDocumentManagerListener() {
            // Added by the IDE in 253; older IDEs use the VFS-after-save listener below.
            public void afterDocumentSaved(Document document) {
                if (relevant(document)) {
                    revision.incrementAndGet();
                    schedule(true, false);
                }
            }
        });
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void after(List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    try {
                        Path path = Path.of(event.getPath());
                        if (ReferenceScanner.isDocument(path) && underProject(path)) {
                            revision.incrementAndGet();
                            schedule(true, false);
                            break;
                        }
                    } catch (InvalidPathException ignored) {}
                }
            }
        });
        // Restore references on project reopen; opening a project never initiates deletion.
        schedule(false, false);
    }

    private boolean relevant(Document document) {
        if (disposed || project.isDisposed()) return false;
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isInLocalFileSystem()) return false;
        try { return underProject(file.toNioPath()) && ReferenceScanner.isDocument(file.toNioPath()); }
        catch (UnsupportedOperationException ignored) { return false; }
    }
    private boolean underProject(Path path) {
        return project.getBasePath() != null && path.toAbsolutePath().normalize()
            .startsWith(Path.of(project.getBasePath()).toAbsolutePath().normalize());
    }

    public void beginInsertion() { insertions.incrementAndGet(); revision.incrementAndGet(); }
    public void endInsertion() { insertions.decrementAndGet(); revision.incrementAndGet(); schedule(false, false); }
    public void manualCleanup() { revision.incrementAndGet(); schedule(true, true); }
    public void restoreSoon() { revision.incrementAndGet(); schedule(false, false); }

    private void schedule(boolean cleanup, boolean manual) {
        if (disposed || project.isDisposed()) return;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || project.isDisposed()) return;
            cleanupPending |= cleanup; manualPending |= manual;
            debounce.cancelAllRequests();
            debounce.addRequest(this::captureAndScan, 450, ModalityState.nonModal());
        }, ModalityState.any());
    }

    private void captureAndScan() {
        if (disposed || project.isDisposed() || insertions.get() > 0) return;
        try {
            AssetRepository store = repository();
            if (!store.exists()) {
                if (manualPending) info("This project has no images managed by the plugin yet.");
                cleanupPending = false; manualPending = false;
                return;
            }
            List<ReferenceScanner.Snapshot> snapshots = new ArrayList<>();
            Set<Document> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            boolean unsavedNotebook = false;
            FileDocumentManager documents = FileDocumentManager.getInstance();
            List<Document> candidates = new ArrayList<>(Arrays.asList(documents.getUnsavedDocuments()));
            for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
                if (editor.getProject() == project) candidates.add(NotebookContext.host(editor).getDocument());
            }
            for (Document document : candidates) {
                if (!seen.add(document) || !relevant(document)) continue;
                VirtualFile file = documents.getFile(document);
                Path path = file.toNioPath().toAbsolutePath().normalize();
                boolean notebook = ReferenceScanner.isNotebook(path);
                boolean json = notebook && !NotebookCellLines.Companion.hasSupport(document);
                snapshots.add(new ReferenceScanner.Snapshot(path, document.getText(), json));
                if (notebook && documents.isDocumentUnsaved(document)) unsavedNotebook = true;
            }
            boolean manual = manualPending;
            boolean clean = cleanupPending && !unsavedNotebook &&
                (manual || ImageSettings.get(project).getState().autoCleanup);
            if (unsavedNotebook && manual) {
                info("Some notebooks have unsaved changes. Only image recovery was checked; save them before running cleanup.");
                manualPending = false;
            }
            boolean canClearPending = !unsavedNotebook;
            long version = revision.get();
            submit(() -> {
                ReferenceScanner.Result references = ReferenceScanner.scan(store.paths().root(), snapshots);
                if (disposed || revision.get() != version || insertions.get() != 0) return;
                AssetRepository.Report report = store.reconcile(references, clean,
                    () -> !disposed && revision.get() == version && insertions.get() == 0);
                if (canClearPending) later(() -> {
                    if (revision.get() == version) { cleanupPending = false; manualPending = false; }
                });
                if (report.restored() > 0 || report.trashed() > 0) refresh();
                String problem = report.problems().isEmpty() ? "" : report.problems().getFirst();
                if (!problem.isEmpty() && !problem.equals(lastProblem)) {
                    warn("Image cleanup kept the affected files: " + problem);
                }
                lastProblem = problem;
                if (manual && clean) {
                    info(String.format("Restored images: %d. Recycled unused images: %d.%s", report.restored(), report.trashed(),
                        report.problems().isEmpty() ? "" : " Some files were kept; see the notifications."));
                }
            });
        } catch (IOException | RuntimeException error) { error(error); }
    }

    public void submit(ThrowingTask task) {
        if (disposed || project.isDisposed()) return;
        try { io.execute(() -> { if (!disposed) try { task.run(); } catch (Exception error) { error(error); } }); }
        catch (RejectedExecutionException ignored) {}
    }
    @FunctionalInterface public interface ThrowingTask { void run() throws Exception; }
    public void later(Runnable task) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !project.isDisposed()) task.run();
        }, ModalityState.nonModal());
    }
    public void deliver(Runnable task, ThrowingTask abandoned) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !project.isDisposed()) task.run();
            else AppExecutorUtil.getAppExecutorService().execute(() -> {
                try { abandoned.run(); }
                catch (Exception error) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(ImageAssetService.class).warn("Image rollback on project close", error);
                }
            });
        }, ModalityState.nonModal());
    }
    public void refresh() {
        if (project.getBasePath() == null || disposed) return;
        LocalFileSystem.getInstance().refreshIoFiles(List.of(Path.of(project.getBasePath()).toFile()), true, true, null);
    }
    public void info(String message) { notify(message, NotificationType.INFORMATION); }
    public void warn(String message) { notify(message, NotificationType.WARNING); }
    public void error(Throwable error) {
        com.intellij.openapi.diagnostic.Logger.getInstance(ImageAssetService.class).warn("Notebook image operation failed", error);
        notify(error.getMessage() == null ? error.toString() : error.getMessage(), NotificationType.ERROR);
    }
    private void notify(String message, NotificationType type) {
        if (disposed || project.isDisposed()) return;
        NotificationGroupManager.getInstance().getNotificationGroup("Notebook Image Manager")
            .createNotification("Jupyter Notebook Markdown Tools", message, type).notify(project);
    }
    @Override public void dispose() { disposed = true; revision.incrementAndGet(); io.shutdown(); }
}
