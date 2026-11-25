package dev.local.notebookimages.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import javax.swing.*;
import javax.swing.colorchooser.AbstractColorChooserPanel;
import javax.swing.colorchooser.ColorChooserComponentFactory;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.Locale;

/** A local color picker. The notebook is changed only after the caller confirms this dialog. */
public final class TextColorDialog extends DialogWrapper {
    private final ColorPanel options = new ColorPanel();

    public TextColorDialog(Project project) {
        super(project, true);
        setTitle("Text Color");
        setOKButtonText("Apply Color");
        setCancelButtonText("Cancel");
        init();
        initValidation();
    }

    @Override protected JComponent createCenterPanel() { return options; }
    @Override public JComponent getPreferredFocusedComponent() { return options.hex; }
    @Override protected String getDimensionServiceKey() { return "NotebookImageManager.TextColor"; }
    @Override protected ValidationInfo doValidate() {
        return options.validColor() ? null : new ValidationInfo("Enter a color in #RRGGBB format, for example #E53935.", options.hex);
    }
    public String colorHex() { return options.hex.getText().strip().toUpperCase(Locale.ROOT); }

    /** Plain Swing content also used for the honest offscreen preview and tests. */
    public static final class ColorPanel extends JPanel {
        public final JTextField hex = new JTextField("#E53935", 10);
        public final JColorChooser chooser = englishColorChooser();
        private final JLabel preview = new JLabel("Text color preview");

        public ColorPanel() {
            super(new BorderLayout(0, 12));
            setBorder(new EmptyBorder(10, 10, 10, 10));
            setPreferredSize(new Dimension(680, 490));
            JPanel top = new JPanel(new BorderLayout(0, 10));
            top.add(new JLabel("Choose a color, or enter #RRGGBB."), BorderLayout.NORTH);
            JPanel palette = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            for (String value : new String[]{"#E53935", "#FB8C00", "#FBC02D", "#43A047", "#00897B", "#1E88E5", "#3949AB", "#8E24AA", "#D81B60", "#6D4C41", "#212121", "#FFFFFF"}) {
                JButton swatch = new JButton();
                swatch.setBackground(Color.decode(value));
                swatch.setOpaque(true);
                swatch.setPreferredSize(new Dimension(34, 28));
                swatch.setToolTipText(value);
                swatch.getAccessibleContext().setAccessibleName("Color " + value);
                swatch.addActionListener(event -> chooser.setColor(Color.decode(value)));
                palette.add(swatch);
            }
            top.add(palette, BorderLayout.CENTER);
            add(top, BorderLayout.NORTH);
            chooser.setPreviewPanel(new JPanel());
            add(chooser, BorderLayout.CENTER);
            JPanel bottom = new JPanel(new BorderLayout(12, 10));
            JPanel input = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            input.add(new JLabel("Hex")); input.add(hex);
            JButton useValue = new JButton("Use Hex Value");
            useValue.addActionListener(event -> { if (validColor()) chooser.setColor(Color.decode(hex.getText().strip())); });
            input.add(useValue);
            bottom.add(input, BorderLayout.NORTH);
            preview.setFont(preview.getFont().deriveFont(Font.BOLD, 18));
            preview.setOpaque(true);
            preview.setBackground(new Color(0xF4F4F4));
            preview.setBorder(new EmptyBorder(12, 12, 12, 12));
            bottom.add(preview, BorderLayout.CENTER);
            add(bottom, BorderLayout.SOUTH);
            chooser.getSelectionModel().addChangeListener(event -> {
                Color color = chooser.getColor();
                hex.setText(String.format(Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue()));
            });
            hex.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent event) { updatePreview(); }
                public void removeUpdate(DocumentEvent event) { updatePreview(); }
                public void changedUpdate(DocumentEvent event) { updatePreview(); }
            });
            updatePreview();
        }

        private static JColorChooser englishColorChooser() {
            JColorChooser chooser = new JColorChooser(new Color(0xE53935));
            // Swing root resources are English; Locale.ENGLISH can fall back to the system language.
            chooser.setLocale(Locale.ROOT);
            AbstractColorChooserPanel[] panels = ColorChooserComponentFactory.getDefaultChooserPanels();
            for (AbstractColorChooserPanel panel : panels) setEnglishLocale(panel);
            chooser.setChooserPanels(panels);
            // Numeric color panels attach their inner controls only during the first install.
            for (AbstractColorChooserPanel panel : panels) setEnglishLocale(panel);
            chooser.setChooserPanels(new AbstractColorChooserPanel[0]);
            chooser.setChooserPanels(panels);
            return chooser;
        }

        private static void setEnglishLocale(Component component) {
            component.setLocale(Locale.ROOT);
            if (component instanceof Container container) {
                for (Component child : container.getComponents()) setEnglishLocale(child);
            }
        }

        public boolean validColor() { return hex.getText().strip().matches("#[0-9a-fA-F]{6}"); }
        private void updatePreview() {
            if (validColor()) preview.setForeground(Color.decode(hex.getText().strip()));
        }
    }
}
