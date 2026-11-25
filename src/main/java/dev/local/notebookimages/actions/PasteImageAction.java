package dev.local.notebookimages.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import dev.local.notebookimages.ide.*;
import org.jetbrains.annotations.NotNull;

public final class PasteImageAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        PasteController.paste(event.getData(CommonDataKeys.EDITOR), ClipboardImages.contents());
    }
    @Override public void update(@NotNull AnActionEvent event) {
        boolean markdown = NotebookContext.find(event.getData(CommonDataKeys.EDITOR)).isPresent();
        event.getPresentation().setVisible(markdown);
        event.getPresentation().setEnabled(markdown && ClipboardImages.available(ClipboardImages.contents()));
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
