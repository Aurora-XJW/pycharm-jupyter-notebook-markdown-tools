package dev.local.notebookimages.ide;

import com.intellij.openapi.command.WriteCommandAction;
import dev.local.notebookimages.core.MarkdownFormatting;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.util.TextRange;
import java.io.IOException;

/** A captured cell-local edit, checked again after a modal dialog closes. */
public final class MarkdownEditTarget implements AutoCloseable {
    private final NotebookContext context;
    private final RangeMarker marker;
    private final String original;
    private final boolean blank;
    private MarkdownEditTarget(NotebookContext context, int start, int end, boolean blank) {
        this.context = context; this.blank = blank;
        this.marker = context.editor().getDocument().createRangeMarker(start, end);
        this.original = context.editor().getDocument().getText(new TextRange(start, end));
    }
    public static MarkdownEditTarget capture(Editor input, boolean selected) throws IOException {
        if (input == null) throw new IOException("Enter a Markdown edit cell in the notebook first.");
        Editor editor = NotebookContext.host(input);
        if (editor.getCaretModel().getCaretCount() != 1) throw new IOException("Keep a single caret.");
        SelectionModel selection = editor.getSelectionModel();
        if (selected && !selection.hasSelection()) throw new IOException("Select the text to color first.");
        if (!selected && selection.hasSelection()) throw new IOException("Clear the selection and move the caret to a blank line.");
        int start = selected ? selection.getSelectionStart() : editor.getCaretModel().getOffset();
        NotebookContext context = NotebookContext.find(editor, start).orElseThrow(
            () -> new IOException("This feature is available only in Markdown cells of local notebooks."));
        int end = selected ? selection.getSelectionEnd() : start;
        if (!selected) {
            Document document = editor.getDocument();
            int line = document.getLineNumber(start);
            TextRange content = context.contentRange();
            start = Math.max(document.getLineStartOffset(line), content.getStartOffset());
            end = Math.min(document.getLineEndOffset(line), content.getEndOffset());
            if (end < start || !document.getText(new TextRange(start, end)).isBlank())
                throw new IOException("Use the insertion menu on a blank line.");
        }
        if (!context.acceptsSelection(start, end)) throw new IOException("The selection cannot span multiple Markdown cells.");
        TextRange content = context.contentRange();
        MarkdownFormatting.validateContext(editor.getDocument().getText(content), start - content.getStartOffset(), end - content.getStartOffset());
        return new MarkdownEditTarget(context, start, end, !selected);
    }
    public Editor editor() { return context.editor(); }
    public com.intellij.openapi.project.Project project() { return context.project(); }
    public String original() { return original; }
    public void apply(String replacement, String command) throws IOException {
        validate();
        if (!EditorModificationUtil.requestWriting(editor())) throw new IOException("The current document is not writable.");
        validate(); // A VCS/read-only permission dialog may have pumped document changes.
        WriteCommandAction.runWriteCommandAction(project(), command, null, () -> {
            int start = marker.getStartOffset();
            editor().getDocument().replaceString(start, marker.getEndOffset(), replacement);
            editor().getCaretModel().moveToOffset(start + replacement.length());
            editor().getSelectionModel().removeSelection();
        });
        editor().getContentComponent().requestFocusInWindow();
    }
    private void validate() throws IOException {
        if (project().isDisposed() || editor().isDisposed() || !marker.isValid())
            throw new IOException("The notebook was closed or the insertion point is no longer valid. Try again.");
        NotebookContext current = NotebookContext.find(editor(), marker.getStartOffset()).orElse(null);
        if (current == null || !current.notebook().equals(context.notebook()) ||
            !current.acceptsSelection(marker.getStartOffset(), marker.getEndOffset()) ||
            !original.equals(editor().getDocument().getText(marker.getTextRange())))
            throw new IOException("The target Markdown cell has changed. No changes were made; try again.");
        TextRange content = current.contentRange();
        Document document = editor().getDocument();
        MarkdownFormatting.validateContext(document.getText(content),
            marker.getStartOffset() - content.getStartOffset(), marker.getEndOffset() - content.getStartOffset());
        if (blank) {
            int line = document.getLineNumber(marker.getStartOffset());
            int from = Math.max(document.getLineStartOffset(line), content.getStartOffset());
            int to = Math.min(document.getLineEndOffset(line), content.getEndOffset());
            if (from > to || !document.getText(new TextRange(from, to)).isBlank())
                throw new IOException("The insertion point is no longer on a blank line. No changes were made.");
        }
    }
    @Override public void close() { marker.dispose(); }
}
