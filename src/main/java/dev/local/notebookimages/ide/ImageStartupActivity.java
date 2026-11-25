package dev.local.notebookimages.ide;

import com.intellij.openapi.project.*;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

public final class ImageStartupActivity implements StartupActivity.DumbAware {
    @Override public void runActivity(@NotNull Project project) { ImageAssetService.get(project).start(); NotebookFormattingService.get(project).start(); }
}
