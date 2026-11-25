package dev.local.notebookimages.ide;

import com.intellij.openapi.editor.Editor;
import dev.local.notebookimages.core.MarkdownFormatting;
import dev.local.notebookimages.ui.*;
import java.io.IOException;

public final class MarkdownFormattingController {
    private MarkdownFormattingController() {}
    public static void color(Editor editor) {
        try (MarkdownEditTarget target = MarkdownEditTarget.capture(editor, true)) {
            MarkdownFormatting.color(target.original(), "#000000");
            TextColorDialog dialog = new TextColorDialog(target.project());
            if (dialog.showAndGet()) target.apply(MarkdownFormatting.color(target.original(), dialog.colorHex()), "Set Markdown Text Color");
        } catch (IOException | IllegalArgumentException error) { report(editor, error); }
    }
    public static void blankLines(MarkdownEditTarget target) {
        try (target) {
            BlankLinesDialog dialog = new BlankLinesDialog(target.project());
            if (dialog.showAndGet()) target.apply(MarkdownFormatting.blankLines(dialog.count()), "Insert Markdown Blank Lines");
        } catch (IOException | IllegalArgumentException error) { report(target.editor(), error); }
    }
    public static void table(MarkdownEditTarget target) {
        try (target) {
            MarkdownTableDialog dialog = new MarkdownTableDialog(target.project());
            if (dialog.showAndGet()) target.apply("\n" + dialog.markdown() + "\n", "Insert Markdown Table");
        } catch (IOException | IllegalArgumentException error) { report(target.editor(), error); }
    }
    public static void report(Editor editor, Exception error) {
        if (editor != null && editor.getProject() != null && !editor.getProject().isDisposed())
            ImageAssetService.get(editor.getProject()).warn(error.getMessage());
    }
}
