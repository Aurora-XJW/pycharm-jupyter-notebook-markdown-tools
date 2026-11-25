package dev.local.notebookimages.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import dev.local.notebookimages.core.MarkdownFormatting;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.ParseException;

public final class BlankLinesDialog extends DialogWrapper {
    private final OptionsPanel panel = new OptionsPanel();
    private final JSpinner lines = panel.lines;

    public BlankLinesDialog(Project project) {
        super(project, true);
        setTitle("Insert Visible Blank Lines");
        setOKButtonText("Insert");
        setCancelButtonText("Cancel");
        init();
        initValidation();
    }

    @Override protected JComponent createCenterPanel() { return panel; }
    @Override public JComponent getPreferredFocusedComponent() { return lines; }
    @Override protected ValidationInfo doValidate() {
        try {
            String typed = ((JSpinner.DefaultEditor) lines.getEditor()).getTextField().getText().strip();
            MarkdownFormatting.blankLines(Integer.parseInt(typed));
        } catch (IllegalArgumentException error) { return new ValidationInfo("Enter a whole number from 1 to " + MarkdownFormatting.MAX_BLANK_LINES + ".", lines); }
        return null;
    }
    @Override protected void doOKAction() {
        try { lines.commitEdit(); MarkdownFormatting.blankLines(count()); }
        catch (ParseException | IllegalArgumentException error) { setErrorText("Enter a valid number of blank lines."); return; }
        super.doOKAction();
    }
    public int count() { return ((Number) lines.getValue()).intValue(); }

    /** Actual dialog content, shared with honest offscreen documentation previews. */
    public static final class OptionsPanel extends JPanel {
        public final JSpinner lines = new JSpinner(new SpinnerNumberModel(1, 1, MarkdownFormatting.MAX_BLANK_LINES, 1));

        public OptionsPanel() {
            super(new BorderLayout(0, 14));
            lines.setEditor(new JSpinner.NumberEditor(lines, "0"));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            JPanel field = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            field.add(new JLabel("Blank lines")); field.add(lines);
            field.add(new JLabel("(1 - " + MarkdownFormatting.MAX_BLANK_LINES + ")"));
            add(field, BorderLayout.NORTH);
            add(new JLabel("<html>Add visible spacing to the Markdown preview.<br>Line height depends on the notebook theme and font.</html>"), BorderLayout.CENTER);
            setPreferredSize(new Dimension(450, 115));
        }
    }
}
