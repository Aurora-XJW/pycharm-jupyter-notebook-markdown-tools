package dev.local.notebookimages.ide;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.*;

@Service(Service.Level.PROJECT)
@State(name = "NotebookImageManagerSettings", storages = @Storage("notebook-image-manager.xml"))
public final class ImageSettings implements PersistentStateComponent<ImageSettings.Preferences> {
    public static final class Preferences {
        public String imageRoot = "notebook_images";
        public boolean perNotebook = true;
        public boolean showDialog = true;
        public boolean autoCleanup = true;
        public int defaultWidth = 600;
        public boolean originalSize = false;
        public String lastDirectory = "";
        public String lastNotebook = "";
    }
    private Preferences state = new Preferences();
    public static ImageSettings get(Project project) { return project.getService(ImageSettings.class); }
    @Override public @NotNull Preferences getState() { return state; }
    @Override public void loadState(@NotNull Preferences state) { this.state = state; }
}
