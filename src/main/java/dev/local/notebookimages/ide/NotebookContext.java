package dev.local.notebookimages.ide;

import com.intellij.notebooks.visualization.NotebookCellLines;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.openapi.util.TextRange;
import java.nio.file.*;
import java.util.*;

/** Uses the IDE notebook cell model, never treats .ipynb JSON as ordinary text. */
public record NotebookContext(Project project, Editor editor, Path notebook,
                              NotebookCellLines.Interval cell) {
    public static Editor host(Editor editor) {
        while (editor instanceof EditorWindow window) editor = window.getDelegate();
        return editor;
    }

    public static Path notebookFile(Document document) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !file.isInLocalFileSystem() || !"ipynb".equalsIgnoreCase(file.getExtension())) return null;
        try { return file.toNioPath().toAbsolutePath().normalize(); }
        catch (UnsupportedOperationException | InvalidPathException ignored) { return null; }
    }

    public static Optional<NotebookContext> find(Editor input) {
        if (input == null) return Optional.empty();
        Editor editor = host(input);
        return find(editor, editor.getCaretModel().getOffset());
    }

    public static Optional<NotebookContext> find(Editor input, int offset) {
        if (input == null) return Optional.empty();
        Editor editor = host(input);
        Project project = editor.getProject();
        if (project == null || project.isDisposed() || editor.isDisposed() || editor.isViewer() ||
            project.getBasePath() == null || offset < 0 || offset > editor.getDocument().getTextLength()) return Optional.empty();
        Path notebook = notebookFile(editor.getDocument());
        if (notebook == null || !notebook.startsWith(Path.of(project.getBasePath()).toAbsolutePath().normalize()) ||
            !NotebookCellLines.Companion.hasSupport(editor)) return Optional.empty();
        int line = editor.getDocument().getLineNumber(offset);
        for (NotebookCellLines.Interval cell : NotebookCellLines.Companion.get(editor).getIntervals()) {
            if (NotebookCellTypeCompat.isMarkdown(cell) && cell.getContentLines().contains(line)) {
                return Optional.of(new NotebookContext(project, editor, notebook, cell));
            }
        }
        return Optional.empty();
    }

    public TextRange contentRange() { return cell.getContentRange(editor); }

    public boolean acceptsSelection(int start, int end) {
        TextRange range = contentRange();
        return start >= range.getStartOffset() && end >= start && end <= range.getEndOffset();
    }

    public String defaultDirectory(ImageSettings.Preferences settings) {
        if (!settings.perNotebook) return settings.imageRoot;
        Path root = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        String relative = root.relativize(notebook).toString().replace('\\', '/');
        relative = relative.substring(0, relative.length() - ".ipynb".length());
        return settings.imageRoot.replace('\\', '/').replaceAll("/+$", "") + "/" + relative;
    }
}
