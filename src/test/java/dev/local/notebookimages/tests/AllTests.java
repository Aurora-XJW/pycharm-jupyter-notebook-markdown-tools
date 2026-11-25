package dev.local.notebookimages.tests;

import com.google.gson.*;
import dev.local.notebookimages.core.*;
import dev.local.notebookimages.ide.*;
import dev.local.notebookimages.ui.ImageOptionsPanel;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.options.Configurable;
import com.intellij.notebooks.visualization.NotebookCellLines;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real filesystem regression tests and an offscreen render; never loads a plugin into an IDE. */
public final class AllTests {
    private static Path output;
    private static final List<String> results = new ArrayList<>();
    private static int count;
    private static final Gson JSON = new Gson();

    @FunctionalInterface private interface Test { void run() throws Exception; }

    public static void main(String[] arguments) throws Exception {
        output = Path.of(arguments[0]).toAbsolutePath();
        Files.createDirectories(output);
        try {
            run("Sequential numbering survives repository restart and recycling", AllTests::numbering);
            run("PNG and original pixels survive clipboard conversion", AllTests::pixels);
            run("Existing files and existing ownership records are never overwritten", AllTests::noOverwrite);
            run("Delete, save, undo and redo preserve recoverable image bytes", AllTests::undoRedo);
            run("Shared references in another notebook preserve the file", AllTests::sharedReferences);
            run("Unsaved notebook overlays can restore a recycled image", AllTests::unsavedRestore);
            run("Unreadable or malformed notebooks suspend all automatic removals", AllTests::invalidNotebook);
            run("Externally modified managed images are preserved", AllTests::changedFile);
            run("Only plugin-owned files are eligible for cleanup", AllTests::unmanagedFiles);
            run("Manual recovery remains until the image is referenced again", AllTests::manualRecovery);
            run("Markdown, reference-style, HTML and encoded paths are recognized", AllTests::referenceSyntax);
            run("Notebook source arrays and strings are parsed without scanning outputs", AllTests::notebookSources);
            run("Traversal, external directories and reserved file names are rejected", AllTests::invalidPaths);
            run("Corrupt ownership inventory never resets or deletes images", AllTests::corruptInventory);
            run("Tampered inventory cannot move files outside the project", AllTests::tamperedInventory);
            run("A stale scan cannot recycle an image after a document change", AllTests::staleScan);
            run("Concurrent requests for one filename cannot overwrite each other", AllTests::concurrentWrites);
            run("An interrupted recycle operation recovers after restart", AllTests::interruptedMove);
            run("Valid JSON with missing inventory fields is rejected", AllTests::incompleteInventory);
            run("Saved cell attachments export without changing original JSON", AllTests::attachmentExport);
            run("Attachment export rejects stale cells and missing attachment data", AllTests::attachmentFailures);
            run("Cancelled paste preparation creates no project files", AllTests::cancelledPaste);
            run("Pixel and percent controls preserve aspect ratio and original mode", AllTests::sizeControls);
            run("Clipboard text is not intercepted; one image file is accepted", AllTests::clipboardTypes);
            run("Target SDK supplies all registered extension classes", AllTests::sdkAndDescriptor);
            run("Actual SDK cell types use the shared compatibility adapter", NotebookCompatTests::run);
            run("Actual paste panel renders without clipping", AllTests::renderPanel);
            run("Double Alt requires two standalone taps and cancels chords or holds", EditorGestureTests::run);
            run("Markdown formatting, table editing and actual UI preview regressions", () -> FormattingTests.run(output));
            results.add("PASS: " + count + " regression scenarios");
            results.add("SDK: " + arguments[1] + " / Java " + System.getProperty("java.version"));
            results.add("No IDE process was started; no plugin was installed.");
        } finally {
            Files.write(output.resolve("test-results.txt"), results, java.nio.charset.StandardCharsets.UTF_8);
        }
        System.out.println("PASS: " + count + " regression scenarios");
        System.out.println("Report: " + output.resolve("test-results.txt"));
    }

    private static void run(String name, Test test) throws Exception {
        try { test.run(); count++; results.add("PASS " + name); System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) {
            results.add("FAIL " + name + ": " + error);
            throw error;
        }
    }
    private static Path project(String name) throws IOException {
        Path root = output.resolve("cases").resolve(name);
        Files.createDirectories(root); return root;
    }
    private static Path notebook(Path root, String name, String source) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        JsonObject cell = new JsonObject(); cell.addProperty("cell_type", "markdown"); cell.addProperty("source", source);
        cell.add("metadata", new JsonObject());
        JsonArray cells = new JsonArray(); cells.add(cell);
        JsonObject notebook = new JsonObject(); notebook.add("cells", cells);
        notebook.addProperty("nbformat", 4); notebook.addProperty("nbformat_minor", 5); notebook.add("metadata", new JsonObject());
        Files.writeString(path, JSON.toJson(notebook)); return path;
    }
    private static ImageData image() throws IOException {
        BufferedImage bitmap = new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bitmap.createGraphics();
        try {
            g.setColor(new Color(28, 69, 116)); g.fillRoundRect(0, 0, 800, 400, 28, 28);
            g.setColor(new Color(97, 218, 171)); g.fillRoundRect(48, 72, 704, 90, 18, 18);
            g.setColor(new Color(227, 237, 246)); g.fillRoundRect(48, 192, 480, 25, 10, 10);
            g.fillRoundRect(48, 240, 600, 25, 10, 10); g.fillRoundRect(48, 288, 390, 25, 10, 10);
            g.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 31)); g.setColor(new Color(28, 69, 116));
            g.drawString("Notebook image preview", 76, 131);
        } finally { g.dispose(); }
        bitmap.setRGB(0, 0, 0x00775533);
        bitmap.setRGB(1, 1, 0x80336699);
        return ImageData.fromImage(bitmap);
    }
    private static AssetRepository.Asset save(AssetRepository repository, Path notebook) throws IOException {
        return repository.save(image(), notebook, "notebook_images/demo", repository.nextName("notebook_images/demo"));
    }
    private static ReferenceScanner.Result scan(AssetRepository repository) throws IOException {
        return ReferenceScanner.scan(repository.paths().root(), List.of());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) { check(Objects.equals(expected, actual), "Expected " + expected + ", got " + actual); }
    private static void fails(Test test) throws Exception {
        try { test.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected an IOException");
    }

    private static void numbering() throws Exception {
        Path root = project("numbering"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root);
        AssetRepository.Asset first = save(repository, nb);
        equal("img_0001.png", repository.path(first).getFileName().toString());
        repository.trash(first.id());
        AssetRepository restarted = new AssetRepository(root);
        equal("img_0002.png", restarted.nextName("notebook_images/demo"));
        AssetRepository.Asset second = save(restarted, nb);
        equal("img_0002.png", restarted.path(second).getFileName().toString());
        equal(2, restarted.assets().size());
    }
    private static void pixels() throws Exception {
        ImageData data = image();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data.png()));
        equal(800, decoded.getWidth()); equal(400, decoded.getHeight());
        equal(0, decoded.getRGB(0, 0) >>> 24); equal(0x80336699, decoded.getRGB(1, 1));
        equal(AssetRepository.digest(data.png()), AssetRepository.digest(ImageData.fromPngBytes(data.png()).png()));
    }
    private static void noOverwrite() throws Exception {
        Path root = project("overwrite"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root);
        AssetRepository.Asset asset = save(repository, nb);
        byte[] before = Files.readAllBytes(repository.path(asset));
        fails(() -> repository.save(image(), nb, "notebook_images/demo", "img_0001.png"));
        check(Arrays.equals(before, Files.readAllBytes(repository.path(asset))), "Existing file changed");
        repository.trash(asset.id());
        fails(() -> repository.save(image(), nb, "notebook_images/demo", "img_0001.png"));
        equal(1, repository.assets().size());
        Files.writeString(root.resolve("notebook_images/demo/img_0099.png"), "unmanaged");
        equal("img_0100.png", repository.nextName("notebook_images/demo"));
    }
    private static void undoRedo() throws Exception {
        Path root = project("undo"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        Path file = repository.path(asset);
        String tag = PathPolicy.imageTag(nb, file, 600, 0);
        notebook(root, "demo.ipynb", tag);
        equal(0, repository.reconcile(scan(repository), true, () -> true).trashed());
        notebook(root, "demo.ipynb", "");
        equal(1, repository.reconcile(scan(repository), true, () -> true).trashed());
        check(!Files.exists(file) && Files.exists(repository.trashPath(asset)), "Image was not recycled");
        notebook(root, "demo.ipynb", tag);
        equal(1, repository.reconcile(scan(repository), false, () -> true).restored());
        equal(asset.sha256(), AssetRepository.digest(Files.readAllBytes(file)));
        notebook(root, "demo.ipynb", "");
        equal(1, repository.reconcile(scan(repository), true, () -> true).trashed());
    }
    private static void sharedReferences() throws Exception {
        Path root = project("shared"); Path nb = notebook(root, "a.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        Path other = notebook(root, "notes/b.ipynb", "");
        notebook(root, "notes/b.ipynb", PathPolicy.imageTag(other, repository.path(asset), 450, 0));
        equal(0, repository.reconcile(scan(repository), true, () -> true).trashed());
        notebook(root, "notes/b.ipynb", "");
        equal(1, repository.reconcile(scan(repository), true, () -> true).trashed());
    }
    private static void unsavedRestore() throws Exception {
        Path root = project("unsaved"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        repository.trash(asset.id());
        ReferenceScanner.Result refs = ReferenceScanner.scan(root, List.of(new ReferenceScanner.Snapshot(nb,
            PathPolicy.imageTag(nb, repository.path(asset), 600, 0), false)));
        equal(1, repository.reconcile(refs, false, () -> true).restored());
        check(Files.exists(repository.path(asset)), "Unsaved undo did not restore image");
    }
    private static void invalidNotebook() throws Exception {
        Path root = project("bad-notebook"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        Files.writeString(root.resolve("broken.ipynb"), "{not json");
        ReferenceScanner.Result refs = scan(repository);
        check(!refs.complete(), "Malformed notebook was silently skipped");
        equal(0, repository.reconcile(refs, true, () -> true).trashed());
        check(Files.exists(repository.path(asset)), "Image removed after incomplete scan");
    }
    private static void changedFile() throws Exception {
        Path root = project("modified"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        Files.writeString(repository.path(asset), "modified by another tool");
        AssetRepository.Report report = repository.reconcile(scan(repository), true, () -> true);
        equal(0, report.trashed()); check(!report.problems().isEmpty(), "Changed file was not reported");
        equal("modified by another tool", Files.readString(repository.path(asset)));
    }
    private static void unmanagedFiles() throws Exception {
        Path root = project("unmanaged"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); save(repository, nb);
        Path external = root.resolve("notebook_images/demo/manual.png"); Files.writeString(external, "mine");
        equal(1, repository.reconcile(scan(repository), true, () -> true).trashed());
        equal("mine", Files.readString(external));
    }
    private static void manualRecovery() throws Exception {
        Path root = project("manual"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        repository.trash(asset.id()); repository.restore(asset.id(), true);
        equal(0, repository.reconcile(scan(repository), true, () -> true).trashed());
        notebook(root, "demo.ipynb", PathPolicy.imageTag(nb, repository.path(asset), 400, 0));
        repository.reconcile(scan(repository), true, () -> true);
        check(!repository.assets().getFirst().keepUntilReferenced(), "Manual hold did not release");
        notebook(root, "demo.ipynb", "");
        equal(1, repository.reconcile(scan(repository), true, () -> true).trashed());
    }
    private static void referenceSyntax() throws Exception {
        Path root = project("references"); Path nb = root.resolve("notes/demo.ipynb");
        Path file = root.resolve("图片空 格/a+(b)#&.png").toAbsolutePath();
        String url = PathPolicy.relativeUrl(nb, file);
        String text = "![](" + url + " \"title\")\n<img alt='hi' src=\"" + PathPolicy.html(url) + "\" width='600'>\n" +
            "[picture]: <" + url + ">\n![nested](../one(two).png)\n<img src='../literal+plus.png'>\n" +
            "![](https://example.com/foreign.png)\n![](attachment:something.png)";
        Set<Path> references = ReferenceScanner.references(new ReferenceScanner.Snapshot(nb, text, false));
        check(references.contains(file), "Encoded Chinese path did not resolve");
        check(references.contains(root.resolve("one(two).png")), "Balanced Markdown destination was not recognized");
        check(references.contains(root.resolve("literal+plus.png")), "Plus sign was decoded as a space");
        equal(3, references.size());
    }
    private static void notebookSources() throws Exception {
        Path root = project("sources"); Path nb = root.resolve("demo.ipynb");
        String json = """
            {"cells":[{"cell_type":"markdown","source":["![a](","one.png)"]},
            {"cell_type":"markdown","source":"<img src=\\"two.png\\">"},
            {"cell_type":"code","source":"pass","outputs":[{"data":{"text/html":"<img src='not-an-asset.png'>"}}]}]}
            """;
        Set<Path> references = ReferenceScanner.references(new ReferenceScanner.Snapshot(nb, json, true));
        equal(Set.of(root.resolve("one.png"), root.resolve("two.png")), references);
    }
    private static void invalidPaths() throws Exception {
        Path root = project("paths"); PathPolicy paths = new PathPolicy(root);
        fails(() -> paths.imageDirectory("../outside"));
        fails(() -> paths.imageDirectory(root.getParent().toString()));
        fails(() -> paths.imageDirectory(".notebook-image-manager/trash"));
        fails(() -> paths.imageDirectory("."));
        for (String name : List.of("../bad.png", "bad/name.png", "CON.png", "LPT1.png", "bad?.png", "bad.png ")) {
            fails(() -> PathPolicy.fileName(name));
        }
        equal("图片.png", PathPolicy.fileName("图片"));
    }
    private static void corruptInventory() throws Exception {
        Path root = project("corrupt"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        Files.writeString(root.resolve(".notebook-image-manager/manifest.json"), "{broken");
        fails(repository::assets); fails(() -> repository.save(image(), nb, "notebook_images/demo", "new.png"));
        check(Files.exists(repository.path(asset)), "Corrupt inventory deleted an image");
        equal("{broken", Files.readString(root.resolve(".notebook-image-manager/manifest.json")));
    }
    private static void tamperedInventory() throws Exception {
        Path root = project("tampered"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        Path manifest = root.resolve(".notebook-image-manager/manifest.json");
        JsonObject inventory = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        inventory.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("path", "../outside.png");
        Files.writeString(manifest, JSON.toJson(inventory));
        fails(() -> repository.trash(asset.id()));
        check(Files.exists(repository.path(asset)), "Tampered inventory touched the real image");
    }
    private static void staleScan() throws Exception {
        Path root = project("stale"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        equal(0, repository.reconcile(scan(repository), true, () -> false).trashed());
        check(Files.exists(repository.path(asset)), "A stale scan moved the image");
    }
    private static void concurrentWrites() throws Exception {
        Path root = project("concurrent"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); ImageData data = image();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger(), conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                try { repository.save(data, nb, "notebook_images/demo", "img_0001.png"); successes.incrementAndGet(); }
                catch (IOException expected) { conflicts.incrementAndGet(); }
            }));
            for (Future<?> future : futures) future.get();
        } finally { pool.shutdownNow(); }
        equal(1, successes.get()); equal(1, conflicts.get()); equal(1, repository.assets().size());
        equal(AssetRepository.digest(data.png()), AssetRepository.digest(Files.readAllBytes(root.resolve("notebook_images/demo/img_0001.png"))));
    }
    private static void cancelledPaste() throws Exception {
        Path root = project("cancel"); AssetRepository repository = new AssetRepository(root);
        equal("img_0001.png", repository.nextName("notebook_images/demo"));
        SwingUtilities.invokeAndWait(() -> {
            try { new ImageOptionsPanel(image(), "notebook_images/demo", "img_0001.png", 600, false); }
            catch (IOException error) { throw new RuntimeException(error); }
        });
        try (var entries = Files.list(root)) { equal(0L, entries.count()); }
    }
    private static void interruptedMove() throws Exception {
        Path root = project("interrupted"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        Path backup = repository.trashPath(asset); Files.createDirectories(backup.getParent());
        Files.move(repository.path(asset), backup);
        AssetRepository restarted = new AssetRepository(root);
        check(restarted.assets().getFirst().trashed(), "Interrupted move was not discovered");
        notebook(root, "demo.ipynb", PathPolicy.imageTag(nb, repository.path(asset), 500, 0));
        equal(1, restarted.reconcile(scan(restarted), false, () -> true).restored());
        check(Files.exists(restarted.path(asset)), "Interrupted operation was not recovered");
    }
    private static void incompleteInventory() throws Exception {
        Path root = project("incomplete"); Path nb = notebook(root, "demo.ipynb", "");
        AssetRepository repository = new AssetRepository(root); AssetRepository.Asset asset = save(repository, nb);
        Files.writeString(root.resolve(".notebook-image-manager/manifest.json"), "{}");
        fails(repository::assets); check(Files.exists(repository.path(asset)), "Invalid manifest changed an image");
    }
    private static Path attachmentNotebook(Path root, String source) throws IOException {
        Path nb = notebook(root, "demo.ipynb", source);
        JsonObject json = JsonParser.parseString(Files.readString(nb)).getAsJsonObject();
        JsonObject bundle = new JsonObject(); bundle.addProperty("image/png", Base64.getEncoder().encodeToString(image().png()));
        JsonObject attachments = new JsonObject(); attachments.add("test.png", bundle);
        json.getAsJsonArray("cells").get(0).getAsJsonObject().add("attachments", attachments);
        Files.writeString(nb, JSON.toJson(json)); return nb;
    }
    private static void attachmentExport() throws Exception {
        Path root = project("attachments");
        String source = "Before\n![image.png](attachment:test.png)\n<img src='attachment:test.png'>\nAfter";
        Path nb = attachmentNotebook(root, source); String before = Files.readString(nb);
        AttachmentExporter.Plan plan = AttachmentExporter.read(nb, 0, source);
        equal(1, plan.attachments().size());
        equal(800, plan.attachments().getFirst().image().width());
        AssetRepository repository = new AssetRepository(root);
        AssetRepository.Asset asset = repository.save(plan.attachments().getFirst().image(), nb, "notebook_images/demo", "img_0001.png");
        String tag = PathPolicy.imageTag(nb, repository.path(asset), 500, 0);
        equal("Before\n" + tag + "\n" + tag + "\nAfter", plan.rewrite(Map.of("test.png", tag)));
        equal(before, Files.readString(nb));
    }
    private static void attachmentFailures() throws Exception {
        Path root = project("bad-attachments"); String source = "![](attachment:test.png)";
        Path nb = attachmentNotebook(root, source);
        fails(() -> AttachmentExporter.read(nb, 0, source + "\nunsaved text"));
        fails(() -> AttachmentExporter.read(nb, 1, source));
        notebook(root, "demo.ipynb", source);
        fails(() -> AttachmentExporter.read(nb, 0, source));
        try (var files = Files.list(root)) { equal(1L, files.count()); }
    }
    private static void sizeControls() throws Exception {
        ImageData image = image();
        SwingUtilities.invokeAndWait(() -> {
            ImageOptionsPanel panel = new ImageOptionsPanel(image, "notebook_images/demo", "img_0001.png", 600, false);
            panel.width.setValue(400); equal(200, ((Number) panel.height.getValue()).intValue());
            panel.percent.setValue(25.0); equal(200, panel.displayWidth()); equal(100, ((Number) panel.height.getValue()).intValue());
            equal(0, panel.displayHeight());
            panel.keepAspect.setSelected(false); panel.height.setValue(220); equal(220, panel.displayHeight());
            panel.original.doClick(); equal(0, panel.displayWidth()); equal(0, panel.displayHeight());
        });
    }
    private static void clipboardTypes() throws Exception {
        check(!ClipboardImages.available(new StringSelection("plain text")), "Text would be intercepted");
        ImageData image = image();
        Transferable transferable = new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
            @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(DataFlavor.imageFlavor); }
            @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
                return image.preview();
            }
        };
        check(ClipboardImages.available(transferable), "Image not recognized");
        equal(800, ClipboardImages.read(transferable).width());
        Path root = project("clipboard"); Path file = root.resolve("test.png"); Files.write(file, image.png());
        Transferable files = new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
            @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(DataFlavor.javaFileListFlavor); }
            @Override public Object getTransferData(DataFlavor flavor) { return List.of(file.toFile()); }
        };
        check(ClipboardImages.available(files), "Image file not recognized");
        equal(400, ClipboardImages.read(files).height());
    }
    private static void sdkAndDescriptor() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (InputStream stream = AllTests.class.getClassLoader().getResourceAsStream("META-INF/plugin.xml")) {
            check(stream != null, "Plugin descriptor missing");
            org.w3c.dom.Document xml = factory.newDocumentBuilder().parse(stream);
            var versions = xml.getElementsByTagName("idea-version");
            check(((org.w3c.dom.Element) versions.item(0)).getAttribute("since-build").matches("(?:243|253)(?:\\.[0-9]+)*"), "Unexpected compatibility floor");
            equal("263.*", ((org.w3c.dom.Element) versions.item(0)).getAttribute("until-build"));
            var extensions = xml.getElementsByTagName("extensions").item(0).getChildNodes();
            for (int i = 0; i < extensions.getLength(); i++) {
                if (!(extensions.item(i) instanceof org.w3c.dom.Element element)) continue;
                String type = element.getAttribute("implementationClass");
                if (type.isEmpty()) type = element.getAttribute("implementation");
                if (type.isEmpty()) type = element.getAttribute("instance");
                if (type.isEmpty()) continue;
                Class<?> implementation = Class.forName(type, false, AllTests.class.getClassLoader());
                implementation.getDeclaredMethods();
                if (element.getTagName().equals("editorActionHandler")) {
                    check(EditorActionHandler.class.isAssignableFrom(implementation), "Invalid paste handler type");
                    implementation.getConstructor(EditorActionHandler.class);
                }
                if (element.getTagName().equals("postStartupActivity")) {
                    check(StartupActivity.class.isAssignableFrom(implementation), "Invalid startup activity");
                }
                if (element.getTagName().equals("projectConfigurable")) {
                    check(Configurable.class.isAssignableFrom(implementation), "Invalid settings component");
                }
            }
            var actions = xml.getElementsByTagName("action");
            for (int i = 0; i < actions.getLength(); i++) {
                Class<?> implementation = Class.forName(((org.w3c.dom.Element) actions.item(i)).getAttribute("class"),
                    false, AllTests.class.getClassLoader());
                check(AnAction.class.isAssignableFrom(implementation), "Invalid action class");
                implementation.getConstructor(); implementation.getDeclaredMethods();
            }
        }
        NotebookCellLines.class.getMethod("getIntervals");
        NotebookCellLines.Interval.class.getMethod("getContentRange", com.intellij.openapi.editor.Editor.class);
        com.intellij.openapi.fileEditor.FileDocumentManagerListener.class.getMethod("beforeDocumentSaving", com.intellij.openapi.editor.Document.class);
    }
    private static void renderPanel() throws Exception {
        ImageData image = image();
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                java.util.Enumeration<?> keys = UIManager.getDefaults().keys();
                Font font = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
                while (keys.hasMoreElements()) {
                    Object key = keys.nextElement();
                    if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) UIManager.put(key, new javax.swing.plaf.FontUIResource(font));
                }
                ImageOptionsPanel panel = new ImageOptionsPanel(image, "notebook_images/实验记录", "img_0001.png", 600, false);
                panel.setSize(680, 540); layout(panel);
                BufferedImage render = new BufferedImage(680, 540, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = render.createGraphics();
                panel.printAll(graphics); graphics.dispose();
                ImageIO.write(render, "png", output.resolve("paste-dialog-preview.png").toFile());
                check(panel.directory.getWidth() > 200 && panel.width.getHeight() > 15, "Controls collapsed");
                check(panel.alwaysAsk.getY() + panel.alwaysAsk.getHeight() <= panel.alwaysAsk.getParent().getHeight(), "Bottom controls clipped");
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
