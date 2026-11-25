package dev.local.notebookimages.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import dev.local.notebookimages.ide.*;
import org.jetbrains.annotations.NotNull;

public final class TextColorAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        MarkdownFormattingController.color(event.getData(CommonDataKeys.EDITOR));
    }
    @Override public void update(@NotNull AnActionEvent event) {
        Editor input = event.getData(CommonDataKeys.EDITOR);
        boolean enabled = false;
        if (input != null) {
            Editor editor = NotebookContext.host(input);
            int start = editor.getSelectionModel().getSelectionStart();
            int end = editor.getSelectionModel().getSelectionEnd();
            enabled = editor.getCaretModel().getCaretCount() == 1 && start < end &&
                NotebookContext.find(editor, start).filter(c -> c.acceptsSelection(start, end)).isPresent();
        }
        event.getPresentation().setEnabledAndVisible(enabled);
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
