package dev.local.notebookimages.ui;

import com.intellij.openapi.fileChooser.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.vfs.*;
import dev.local.notebookimages.core.*;
import javax.swing.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.*;

public final class ImagePasteDialog extends DialogWrapper {
    private final AssetRepository repository;
    private final ImageOptionsPanel options;
    private String lastSuggestedName;

    public record Choice(String directory, String fileName, int width, int height,
                         boolean remember, boolean alwaysAsk, boolean originalSize) {}

    public ImagePasteDialog(Project project, AssetRepository repository, ImageData image,
                            String directory, int initialWidth, boolean originalSize) throws IOException {
        super(project, true);
        this.repository = repository;
        lastSuggestedName = repository.nextName(directory);
        options = new ImageOptionsPanel(image, directory, lastSuggestedName, initialWidth, originalSize);
        setTitle("Save Notebook Image");
        setOKButtonText("Save and Insert");
        setCancelButtonText("Cancel");
        options.browse.addActionListener(e -> {
            FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
            descriptor.setTitle("Choose an Image Folder in the Project");
            VirtualFile initial = LocalFileSystem.getInstance().findFileByNioFile(repository.paths().root());
            VirtualFile selected = FileChooser.chooseFile(descriptor, project, initial);
            if (selected != null) {
                try { options.directory.setText(repository.paths().relative(selected.toNioPath())); }
                catch (IOException error) { options.directory.setText(selected.getPath()); }
                suggestName();
            }
        });
        options.directory.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { suggestName(); }
        });
        init();
        initValidation();
    }

    private void suggestName() {
        if (!options.fileName.getText().equals(lastSuggestedName)) return;
        try {
            lastSuggestedName = repository.nextName(options.directory.getText().strip());
            options.fileName.setText(lastSuggestedName);
        } catch (IOException ignored) {}
    }

    @Override protected JComponent createCenterPanel() { return options; }
    @Override public JComponent getPreferredFocusedComponent() { return options.width; }
    @Override protected String getDimensionServiceKey() { return "NotebookImageManager.PasteDialog"; }

    @Override protected ValidationInfo doValidate() {
        try {
            options.commitNumbers();
            Path directory = repository.paths().imageDirectory(options.directory.getText().strip());
            Path destination = directory.resolve(PathPolicy.fileName(options.fileName.getText()));
            if (Files.exists(destination)) return new ValidationInfo("A file with this name already exists. Choose another name.", options.fileName);
            if (options.remember.isSelected() && options.directory.getText().isBlank()) {
                return new ValidationInfo("Choose a save folder.", options.directory);
            }
        } catch (IOException | java.text.ParseException | IllegalArgumentException error) {
            return new ValidationInfo(error.getMessage() == null ? "Enter valid image dimensions." : error.getMessage());
        }
        return null;
    }

    public Choice choice() {
        return new Choice(options.directory.getText().strip(), options.fileName.getText(),
            options.displayWidth(), options.displayHeight(), options.remember.isSelected(),
            options.alwaysAsk.isSelected(), options.original.isSelected());
    }
}
