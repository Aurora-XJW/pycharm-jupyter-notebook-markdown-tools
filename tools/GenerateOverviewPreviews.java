import dev.local.notebookimages.core.ImageData;
import dev.local.notebookimages.core.MarkdownFormatting;
import dev.local.notebookimages.core.PathPolicy;
import dev.local.notebookimages.ui.BlankLinesDialog;
import dev.local.notebookimages.ui.ImageOptionsPanel;
import dev.local.notebookimages.ui.MarkdownTablePanel;
import dev.local.notebookimages.ui.TextColorDialog;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Repaints actual plugin components at 1x and 2x; never scales an existing bitmap. */
public final class GenerateOverviewPreviews {
    private static final int COMPACT_WIDTH = 560;
    private static final int FULL_WIDTH = 1040;
    private static final Font BODY = new Font("Microsoft YaHei UI", Font.PLAIN, 15);
    private static final Color PAPER = Color.WHITE;
    private static Path output;
    private static boolean include1x;

    public static void main(String[] args) throws Exception {
        String destination = null;
        for (String argument : args) {
            if (argument.equals("--include-1x")) include1x = true;
            else if (destination == null) destination = argument;
            else throw new IllegalArgumentException("Usage: GenerateOverviewPreviews [output-directory] [--include-1x]");
        }
        output = Path.of(destination == null ? ".build/overview-component-previews" : destination).toAbsolutePath();
        Files.createDirectories(output);
        Files.createDirectories(output.resolve("full"));
        if (include1x) Files.createDirectories(output.resolve("1x"));
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        for (Enumeration<Object> keys = UIManager.getDefaults().keys(); keys.hasMoreElements();) {
            Object key = keys.nextElement();
            if (UIManager.get(key) instanceof FontUIResource) UIManager.put(key, new FontUIResource(BODY));
        }
        SwingUtilities.invokeAndWait(() -> {
            try { generate(); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        Files.writeString(output.resolve("preview-sources.txt"), """
                These are offscreen renders of actual plugin Swing components, not IDE screenshots.
                No IDE was launched, no plugin was installed, and no installed files were changed.
                Compact previews use the real controls, grouped vertically for readable documentation.
                Overview captions identify these as component detail views, not complete dialog layouts.
                Compact logical width: 560 px. Minimum control font: 15 px; color preview: 18 px.
                Root PNGs: repainted at 2x (1120 pixels wide, 30-pixel body glyphs).
                full/: complete panels repainted at 2x (2080 pixels wide).
                Optional --include-1x writes native 560-pixel QA renders under 1x/.
                This is vector/component repainting at each resolution, not bitmap enlargement.

                image-paste.png: ImageOptionsPanel path/size fields and actual checkboxes.
                text-color.png: TextColorDialog.ColorPanel common swatches, hex field and text preview.
                blank-lines.png: Actual count row from BlankLinesDialog.OptionsPanel, set to 3.
                markdown-table.png: MarkdownTablePanel controls and JTable; generated code is supplied as text.
                Full image fixture is a locally drawn example. All Markdown comes from plugin methods.
                """, StandardCharsets.UTF_8);
        System.out.println("Readable 1x/2x component details and full 2x panels generated in " + output);
    }

    private static void generate() throws Exception {
        ImageData sample = ImageData.fromImage(sampleImage());
        ImageOptionsPanel image = imagePanel(sample);
        minimumFonts(image);
        ((JSpinner.DefaultEditor) image.width.getEditor()).getTextField().setColumns(4);
        ((JSpinner.DefaultEditor) image.height.getEditor()).getTextField().setColumns(4);
        ((JSpinner.DefaultEditor) image.percent.getEditor()).getTextField().setColumns(4);
        JPanel fields = (JPanel) ((BorderLayout) image.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        fields.setPreferredSize(new Dimension(COMPACT_WIDTH - 32, fields.getPreferredSize().height));
        JPanel imageOptions = new JPanel(new GridLayout(0, 1, 0, 3));
        imageOptions.add(image.remember); imageOptions.add(image.alwaysAsk);
        compact("image-paste.png", stack(fields, imageOptions));

        TextColorDialog.ColorPanel color = new TextColorDialog.ColorPanel();
        color.chooser.setColor(new Color(0xE53935));
        minimumFonts(color);
        BorderLayout colorLayout = (BorderLayout) color.getLayout();
        JComponent palette = (JComponent) colorLayout.getLayoutComponent(BorderLayout.NORTH);
        JComponent values = (JComponent) colorLayout.getLayoutComponent(BorderLayout.SOUTH);
        compact("text-color.png", stack(palette, values));

        BlankLinesDialog.OptionsPanel blank = new BlankLinesDialog.OptionsPanel();
        blank.lines.setValue(3);
        minimumFonts(blank);
        JComponent countField = (JComponent) ((BorderLayout) blank.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        compact("blank-lines.png", stack(countField));

        MarkdownTablePanel table = tablePanel();
        minimumFonts(table);
        JPanel toolbar = (JPanel) ((BorderLayout) table.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        Component[] actions = toolbar.getComponents();
        JPanel dimensions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JPanel operations = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (int index = 0; index < actions.length; index++) (index < 5 ? dimensions : operations).add(actions[index]);
        int inner = COMPACT_WIDTH - 32;
        for (int index = 0; index < table.grid.getColumnCount(); index++) {
            TableColumn column = table.grid.getColumnModel().getColumn(index);
            column.setPreferredWidth(inner / table.grid.getColumnCount());
            column.setWidth(inner / table.grid.getColumnCount());
        }
        table.grid.setPreferredSize(new Dimension(inner, table.grid.getRowCount() * table.grid.getRowHeight()));
        compact("markdown-table.png", stack(dimensions, operations, table.grid));
        Files.writeString(output.resolve("generated-table-markdown.txt"), table.modelMarkdown(), StandardCharsets.UTF_8);

        Path notebook = Path.of("overview-fixture", "example.ipynb").toAbsolutePath();
        Path saved = notebook.getParent().resolve("notebook_images/img_0001.png");
        full("image-paste.png", "图片粘贴与显示尺寸", imagePanel(sample), 510,
            PathPolicy.imageTag(notebook, saved, 600, 300));
        full("text-color.png", "Markdown 文字颜色", new TextColorDialog.ColorPanel(), 490,
            MarkdownFormatting.color("需要强调的文字", "#E53935"));
        BlankLinesDialog.OptionsPanel fullBlank = new BlankLinesDialog.OptionsPanel();
        fullBlank.lines.setValue(3);
        full("blank-lines.png", "在 Markdown 预览中插入可见空行", fullBlank, 130,
            MarkdownFormatting.blankLines(3).strip());
        full("markdown-table.png", "可视化表格编辑与 Markdown 转换", tablePanel(), 510, null);
    }

    private static ImageOptionsPanel imagePanel(ImageData image) {
        return new ImageOptionsPanel(image, "notebook_images", "img_0001.png", 600, false);
    }

    private static MarkdownTablePanel tablePanel() {
        MarkdownTablePanel table = new MarkdownTablePanel();
        table.pasteTsv("项目\t说明\t数量\n图片\t粘贴并设置尺寸\t2\n文字\t选择显示颜色\t1\n表格\t生成 Markdown\t3", 0, 0);
        table.grid.changeSelection(1, 1, false, false);
        return table;
    }

    private static JPanel stack(JComponent... parts) {
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(new EmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int index = 0; index < parts.length; index++) {
            c.gridy = index;
            c.insets = new Insets(index == 0 ? 0 : 10, 0, 0, 0);
            content.add(parts[index], c);
        }
        return content;
    }

    private static void compact(String name, JComponent content) throws Exception {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(PAPER);
        page.setBorder(new EmptyBorder(12, 8, 10, 8));
        page.add(content, BorderLayout.CENTER);
        page.setSize(COMPACT_WIDTH, 22 + content.getPreferredSize().height);
        layout(page);
        if (include1x) paint(page, 1, output.resolve("1x").resolve(name));
        paint(page, 2, output.resolve(name));
    }

    private static void full(String name, String title, JComponent content, int panelHeight, String markdown) throws Exception {
        minimumFonts(content);
        JPanel page = new JPanel(new BorderLayout(0, 14));
        page.setBackground(PAPER);
        page.setBorder(new EmptyBorder(20, 20, 18, 20));
        JPanel heading = new JPanel(new GridLayout(0, 1, 0, 7));
        heading.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setFont(BODY.deriveFont(Font.BOLD, 22));
        heading.add(label);
        JLabel kind = new JLabel("实际完整面板离屏预览 / Actual full panel, rendered offscreen");
        kind.setFont(BODY.deriveFont(13f));
        heading.add(kind);
        page.add(heading, BorderLayout.NORTH);
        content.setPreferredSize(new Dimension(FULL_WIDTH - 40, panelHeight));
        page.add(content, BorderLayout.CENTER);
        int footer = 0;
        if (markdown != null) {
            JPanel code = new JPanel(new BorderLayout(0, 7));
            code.setOpaque(false);
            code.add(new JLabel("插件实际生成的 Markdown"), BorderLayout.NORTH);
            JTextArea text = new JTextArea(markdown.strip());
            text.setFont(BODY);
            text.setEditable(false);
            text.setBackground(new Color(0xF3F3F3));
            text.setBorder(new EmptyBorder(8, 10, 8, 10));
            text.setLineWrap(true);
            int lines = markdown.strip().split("\\R", -1).length;
            int codeHeight = Math.max(60, lines * 22 + 16);
            text.setPreferredSize(new Dimension(FULL_WIDTH - 40, codeHeight));
            code.add(text, BorderLayout.CENTER);
            page.add(code, BorderLayout.SOUTH);
            footer = 44 + codeHeight;
        }
        page.setSize(FULL_WIDTH, 38 + 66 + 14 + panelHeight + footer);
        layout(page);
        paint(page, 2, output.resolve("full").resolve(name));
    }

    private static void paint(JComponent page, int scale, Path destination) throws Exception {
        BufferedImage result = new BufferedImage(page.getWidth() * scale, page.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.scale(scale, scale);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(PAPER);
        graphics.fillRect(0, 0, page.getWidth(), page.getHeight());
        page.printAll(graphics);
        graphics.dispose();
        ImageIO.write(result, "png", destination.toFile());
        System.out.println(destination.getParent().getFileName() + "/" + destination.getFileName() + " " + result.getWidth() + "x" + result.getHeight());
    }

    private static void layout(Container component) {
        component.doLayout();
        for (Component child : component.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static void minimumFonts(Container component) {
        for (Component child : component.getComponents()) {
            Font font = child.getFont();
            if (font != null && font.getSize2D() < BODY.getSize2D()) child.setFont(font.deriveFont(BODY.getSize2D()));
            if (child instanceof Container nested) minimumFonts(nested);
        }
    }

    private static JTextArea findTextArea(Container component) {
        for (Component child : component.getComponents()) {
            if (child instanceof JTextArea text) return text;
            if (child instanceof Container nested) {
                JTextArea found = findTextArea(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static BufferedImage sampleImage() {
        BufferedImage fixture = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = fixture.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xF5F6F8)); g.fillRect(0, 0, 800, 400);
        g.setColor(PAPER); g.fillRoundRect(48, 35, 704, 330, 16, 16);
        g.setColor(new Color(0xEB7B2F)); g.setStroke(new BasicStroke(5));
        g.drawArc(92, 76, 72, 64, 10, 160); g.drawArc(92, 98, 72, 64, 190, 160);
        g.setColor(new Color(0x353535)); g.setFont(BODY.deriveFont(Font.BOLD, 28));
        g.drawString("Jupyter Notebook", 190, 123);
        g.setFont(BODY.deriveFont(23f)); g.drawString("Markdown 图片示例", 92, 210);
        g.setColor(new Color(0x606060)); g.setFont(BODY.deriveFont(20f));
        g.drawString("原图 800 × 400 px", 92, 262); g.drawString("显示 600 × 300 px", 92, 302);
        g.dispose();
        return fixture;
    }
}
