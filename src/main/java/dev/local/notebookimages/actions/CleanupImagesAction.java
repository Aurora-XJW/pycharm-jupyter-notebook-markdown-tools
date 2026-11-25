package dev.local.notebookimages.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import dev.local.notebookimages.ide.ImageAssetService;
import org.jetbrains.annotations.NotNull;

public final class CleanupImagesAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) ImageAssetService.get(event.getProject()).manualCleanup();
    }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null && event.getProject().getBasePath() != null);
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
