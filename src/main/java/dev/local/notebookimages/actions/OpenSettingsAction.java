package dev.local.notebookimages.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import dev.local.notebookimages.ui.ImageSettingsConfigurable;
import org.jetbrains.annotations.NotNull;

public final class OpenSettingsAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) ShowSettingsUtil.getInstance().showSettingsDialog(event.getProject(), ImageSettingsConfigurable.class);
    }
    @Override public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabled(event.getProject() != null); }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
