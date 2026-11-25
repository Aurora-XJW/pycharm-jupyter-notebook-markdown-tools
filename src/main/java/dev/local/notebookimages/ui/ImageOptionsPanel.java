package dev.local.notebookimages.ui;

import dev.local.notebookimages.core.ImageData;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.text.NumberFormat;
import java.util.Locale;

/** Plain Swing content so preview and size calculations can be tested without loading an IDE. */
public final class ImageOptionsPanel extends JPanel {
    public final JTextField directory = new JTextField(32);
    public final JTextField fileName = new JTextField(24);
    public final JButton browse = new JButton("Browse…");
    public final JSpinner width;
    public final JSpinner height;
    public final JSpinner percent;
    public final JCheckBox keepAspect = new JCheckBox("Keep aspect ratio", true);
    public final JCheckBox original = new JCheckBox("Original size");
    public final JCheckBox remember = new JCheckBox("Remember folder and display width", true);
    public final JCheckBox alwaysAsk = new JCheckBox("Show this dialog on every paste", true);
    private final JLabel dimensions = new JLabel();
    private final Preview preview;
    private final ImageData image;
    private boolean adjusting;

    public ImageOptionsPanel(ImageData image, String directory, String fileName, int initialWidth, boolean originalSize) {
        super(new BorderLayout(0, 16));
        this.image = image;
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(650, 510));
        this.directory.setText(directory);
        this.fileName.setText(fileName);
        int w = clamp(initialWidth > 0 ? Math.min(initialWidth, image.width()) : image.width());
        int h = clamp((int) Math.round((double) w * image.height() / image.width()));
        width = new JSpinner(new SpinnerNumberModel(w, 1, 100_000, 10));
        height = new JSpinner(new SpinnerNumberModel(h, 1, 100_000, 10));
        percent = new JSpinner(new SpinnerNumberModel(100.0 * w / image.width(), 0.01, 10_000.0, 5.0));
        percent.setEditor(new JSpinner.NumberEditor(percent, "0.##"));
        original.setSelected(originalSize);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 10);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        row(fields, c, 0, "Save folder", this.directory, browse);
        row(fields, c, 1, "File name", this.fileName, new JLabel("PNG"));
        JPanel sizes = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sizes.add(width); sizes.add(new JLabel("×")); sizes.add(height); sizes.add(new JLabel("px"));
        sizes.add(Box.createHorizontalStrut(12)); sizes.add(percent); sizes.add(new JLabel("%"));
        row(fields, c, 2, "Display size", sizes, null);
        JPanel sizingOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        sizingOptions.add(keepAspect); sizingOptions.add(Box.createHorizontalStrut(18)); sizingOptions.add(original);
        row(fields, c, 3, "", sizingOptions, null);
        add(fields, BorderLayout.NORTH);

        preview = new Preview(image.preview());
        preview.setPreferredSize(new Dimension(600, 230));
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(preview, BorderLayout.CENTER);
        dimensions.setHorizontalAlignment(SwingConstants.CENTER);
        center.add(dimensions, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new GridLayout(0, 1, 0, 4));
        bottom.add(new JLabel("Save at original resolution; sizes above only affect notebook display."));
        bottom.add(remember);
        bottom.add(alwaysAsk);
        add(bottom, BorderLayout.SOUTH);
        width.addChangeListener(e -> changed(0));
        height.addChangeListener(e -> changed(1));
        percent.addChangeListener(e -> changed(2));
        keepAspect.addActionListener(e -> changed(0));
        original.addActionListener(e -> updatePreview());
        updatePreview();
    }

    private static void row(JPanel panel, GridBagConstraints c, int y, String label, JComponent field, JComponent trailing) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; c.gridwidth = 1;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(field, c);
        if (trailing != null) { c.gridx = 2; c.weightx = 0; panel.add(trailing, c); }
    }

    private void changed(int source) {
        if (adjusting) return;
        adjusting = true;
        try {
            if (source == 2) {
                double factor = ((Number) percent.getValue()).doubleValue() / 100;
                width.setValue(clamp((int) Math.round(image.width() * factor)));
                height.setValue(clamp((int) Math.round(image.height() * factor)));
            } else if (source == 0) {
                double factor = ((Number) width.getValue()).doubleValue() / image.width();
                if (keepAspect.isSelected()) height.setValue(clamp((int) Math.round(image.height() * factor)));
                percent.setValue(Math.min(10_000.0, Math.max(0.01, factor * 100)));
            } else {
                double factor = ((Number) height.getValue()).doubleValue() / image.height();
                if (keepAspect.isSelected()) width.setValue(clamp((int) Math.round(image.width() * factor)));
                percent.setValue(Math.min(10_000.0, Math.max(0.01, ((Number) width.getValue()).doubleValue() / image.width() * 100)));
            }
        } finally { adjusting = false; }
        updatePreview();
    }

    private static int clamp(int value) { return Math.max(1, Math.min(100_000, value)); }

    private void updatePreview() {
        boolean sized = !original.isSelected();
        width.setEnabled(sized); height.setEnabled(sized); percent.setEnabled(sized); keepAspect.setEnabled(sized);
        int w = sized ? ((Number) width.getValue()).intValue() : image.width();
        int h = sized ? ((Number) height.getValue()).intValue() : image.height();
        preview.displayWidth = w; preview.displayHeight = h; preview.repaint();
        dimensions.setText(String.format(Locale.ROOT, "Original %d × %d px    →    Display %d × %d px",
            image.width(), image.height(), w, h));
    }

    public int displayWidth() { return original.isSelected() ? 0 : ((Number) width.getValue()).intValue(); }
    public int displayHeight() {
        return original.isSelected() || keepAspect.isSelected() ? 0 : ((Number) height.getValue()).intValue();
    }

    public void commitNumbers() throws java.text.ParseException {
        width.commitEdit(); height.commitEdit(); percent.commitEdit();
    }

    private static final class Preview extends JComponent {
        private final BufferedImage image;
        private int displayWidth, displayHeight;
        Preview(BufferedImage image) { this.image = image; setOpaque(true); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                Color background = UIManager.getColor("Panel.background");
                if (background == null) background = Color.WHITE;
                Color tile = new Color((background.getRed() + 128) / 2, (background.getGreen() + 128) / 2,
                    (background.getBlue() + 128) / 2, 55);
                g.setColor(background); g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(tile);
                for (int y = 0; y < getHeight(); y += 16)
                    for (int x = 0; x < getWidth(); x += 16)
                        if ((x / 16 + y / 16) % 2 == 0) g.fillRect(x, y, 16, 16);
                double scale = Math.min(1.0, Math.min((getWidth() - 24.0) / displayWidth, (getHeight() - 24.0) / displayHeight));
                int w = Math.max(1, (int) (displayWidth * scale)), h = Math.max(1, (int) (displayHeight * scale));
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            } finally { g.dispose(); }
        }
    }
}
