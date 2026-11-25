package dev.local.notebookimages.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import dev.local.notebookimages.ide.*;
import org.jetbrains.annotations.NotNull;

/** Discoverable fallback when the OS reserves Alt taps. */
public final class MarkdownInsertMenuAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null)
            NotebookFormattingService.get(event.getProject()).showInsertMenu(event.getData(CommonDataKeys.EDITOR));
    }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(NotebookContext.find(event.getData(CommonDataKeys.EDITOR)).isPresent());
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
