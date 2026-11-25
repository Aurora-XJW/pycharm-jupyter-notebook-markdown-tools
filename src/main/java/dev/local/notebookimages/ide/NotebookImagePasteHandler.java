package dev.local.notebookimages.ide;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import java.awt.datatransfer.Transferable;
import org.jetbrains.annotations.*;

/** Installed through the platform extension point; ordinary text always delegates to the prior handler. */
public final class NotebookImagePasteHandler extends EditorActionHandler {
    private final EditorActionHandler original;
    public NotebookImagePasteHandler(EditorActionHandler original) { super(false); this.original = original; }

    @Override protected void doExecute(@NotNull Editor editor, @Nullable Caret caret, DataContext dataContext) {
        if (NotebookContext.find(editor).isEmpty()) { original.execute(editor, caret, dataContext); return; }
        Transferable clipboard = ClipboardImages.contents();
        if (PasteController.canHandle(editor, clipboard)) PasteController.paste(editor, clipboard);
        else original.execute(editor, caret, dataContext);
    }
    @Override protected boolean isEnabledForCaret(@NotNull Editor editor, @NotNull Caret caret, DataContext dataContext) {
        return NotebookContext.find(editor).isPresent() || original.isEnabled(editor, caret, dataContext);
    }
    @Override public boolean executeInCommand(@NotNull Editor editor, DataContext context) {
        // The asynchronous image workflow opens its own single undoable write command.
        if (NotebookContext.find(editor).isEmpty()) return original.executeInCommand(editor, context);
        return ClipboardImages.available(ClipboardImages.contents()) ? false : original.executeInCommand(editor, context);
    }
}
