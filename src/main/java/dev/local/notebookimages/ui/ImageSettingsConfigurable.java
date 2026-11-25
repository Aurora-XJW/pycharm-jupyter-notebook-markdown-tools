package dev.local.notebookimages.ui;

import com.intellij.openapi.options.*;
import com.intellij.openapi.project.Project;
import dev.local.notebookimages.core.*;
import dev.local.notebookimages.ide.ImageSettings;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public final class ImageSettingsConfigurable implements Configurable {
    private final Project project;
    private JPanel panel;
    private JTextField root;
    private JSpinner width;
    private JCheckBox perNotebook, showDialog, cleanup, original;

    public ImageSettingsConfigurable(Project project) { this.project = project; }
    @Override public String getDisplayName() { return "Jupyter Notebook Markdown Tools"; }
    @Override public JComponent createComponent() {
        panel = new JPanel(new BorderLayout());
        JPanel fields = new JPanel();
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        root = new JTextField(35);
        width = new JSpinner(new SpinnerNumberModel(600, 1, 100_000, 10));
        perNotebook = new JCheckBox("Create a separate subfolder for each notebook");
        showDialog = new JCheckBox("Confirm save path and display size on paste");
        cleanup = new JCheckBox("Recycle unused images automatically after saving notebooks");
        original = new JCheckBox("Display images at original size by default");
        JPanel directoryRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        directoryRow.add(new JLabel("Image folder (project-relative):")); directoryRow.add(root);
        JPanel widthRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        widthRow.add(new JLabel("Default display width:")); widthRow.add(width); widthRow.add(new JLabel("px"));
        for (JComponent component : new JComponent[]{directoryRow, perNotebook, showDialog, widthRow, original, cleanup}) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT); fields.add(component);
        }
        fields.add(Box.createVerticalStrut(18));
        fields.add(new JLabel("Only unused images created by this plugin are recycled."));
        fields.add(new JLabel("<html>Restore files via Tools →<br>"
            + "Jupyter Notebook Markdown Tools → Restore Recycled Images…</html>"));
        panel.add(fields, BorderLayout.NORTH);
        reset();
        return panel;
    }

    @Override public boolean isModified() {
        if (panel == null) return false;
        ImageSettings.Preferences state = ImageSettings.get(project).getState();
        return !root.getText().equals(state.imageRoot) || !width.getValue().equals(state.defaultWidth) ||
            perNotebook.isSelected() != state.perNotebook || showDialog.isSelected() != state.showDialog ||
            cleanup.isSelected() != state.autoCleanup || original.isSelected() != state.originalSize;
    }
    @Override public void apply() throws ConfigurationException {
        try {
            width.commitEdit();
            PathPolicy paths = new PathPolicy(java.nio.file.Path.of(project.getBasePath()));
            String imageRoot = paths.relative(paths.imageDirectory(root.getText().strip()));
            ImageSettings.Preferences state = ImageSettings.get(project).getState();
            boolean changedDirectory = !imageRoot.equals(state.imageRoot) || perNotebook.isSelected() != state.perNotebook;
            state.imageRoot = imageRoot;
            state.defaultWidth = ((Number) width.getValue()).intValue();
            state.perNotebook = perNotebook.isSelected(); state.showDialog = showDialog.isSelected();
            state.autoCleanup = cleanup.isSelected(); state.originalSize = original.isSelected();
            if (changedDirectory) { state.lastDirectory = ""; state.lastNotebook = ""; }
        } catch (IOException | java.text.ParseException | RuntimeException error) {
            throw new ConfigurationException(error.getMessage() == null ? "Invalid settings." : error.getMessage());
        }
    }
    @Override public void reset() {
        if (panel == null) return;
        ImageSettings.Preferences state = ImageSettings.get(project).getState();
        root.setText(state.imageRoot); width.setValue(Math.max(1, Math.min(100_000, state.defaultWidth)));
        perNotebook.setSelected(state.perNotebook); showDialog.setSelected(state.showDialog);
        cleanup.setSelected(state.autoCleanup); original.setSelected(state.originalSize);
    }
    @Override public void disposeUIResources() { panel = null; }
}
