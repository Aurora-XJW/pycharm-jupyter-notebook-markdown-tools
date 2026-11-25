package dev.local.notebookimages.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import javax.swing.*;

public final class MarkdownTableDialog extends DialogWrapper {
    private final MarkdownTablePanel options = new MarkdownTablePanel();

    public MarkdownTableDialog(Project project) {
        super(project, true);
        setTitle("Insert Markdown Table");
        setOKButtonText("Generate and Insert");
        setCancelButtonText("Cancel");
        setResizable(true);
        init();
        initValidation();
    }
    @Override protected JComponent createCenterPanel() { return options; }
    @Override public JComponent getPreferredFocusedComponent() { return options.grid; }
    @Override protected String getDimensionServiceKey() { return "NotebookImageManager.MarkdownTable"; }
    @Override protected ValidationInfo doValidate() {
        try { options.modelMarkdown(); return null; }
        catch (IllegalArgumentException error) { return new ValidationInfo(error.getMessage(), options.grid); }
    }
    @Override protected void doOKAction() {
        try { options.markdown(); }
        catch (IllegalArgumentException error) { setErrorText(error.getMessage()); return; }
        super.doOKAction();
    }
    public String markdown() { return options.markdown(); }
}
