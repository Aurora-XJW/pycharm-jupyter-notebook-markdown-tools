package dev.local.notebookimages.tests;

import dev.local.notebookimages.core.MarkdownFormatting;
import dev.local.notebookimages.ui.MarkdownTablePanel;
import dev.local.notebookimages.ui.TextColorDialog;
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor;
import org.intellij.markdown.html.HtmlGenerator;
import org.intellij.markdown.parser.MarkdownParser;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Content and Swing-grid regression cases; no IDE application or clipboard access is needed. */
public final class FormattingTests {
    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static final List<String> results = new ArrayList<>();

    public static int run(Path output) throws Exception {
        Files.createDirectories(output);
        results.clear();
        check("Color preserves selected Unicode and Markdown emphasis", () -> {
            equal("<span style=\"color: #12ABEF;\">中文 **强调** & text</span>", MarkdownFormatting.color("中文 **强调** & text", "#12abef"));
        });
        check("Coloring multiline selections preserves empty paragraphs", () -> {
            equal("<span style=\"color: #123456;\">one</span>\n\n<span style=\"color: #123456;\">two</span>\n", MarkdownFormatting.color("one\r\n\r\ntwo\r\n", "#123456"));
        });
        check("Coloring whole block lines preserves heading list and quote markers", () -> {
            equal("# <span style=\"color: #123456;\">Heading</span>\n- <span style=\"color: #123456;\">Item</span>\n> <span style=\"color: #123456;\">Quote</span>\n1. <span style=\"color: #123456;\">First</span>", MarkdownFormatting.color("# Heading\n- Item\n> Quote\n1. First", "#123456"));
            equal("<span style=\"color: #123456;\">Heading</span>\n===", MarkdownFormatting.color("Heading\n===", "#123456"));
            rejects(() -> MarkdownFormatting.color("| A | B |", "#123456"));
        });
        check("HTML tags, comments and selected image syntax are not rewritten", () -> {
            for (String selection : List.of("<img src=\"image.png\" width=\"600\">", "<SPAN>text</SPAN>", "text <!--hidden-->", "<a href='x'>text</a>")) {
                rejects(() -> MarkdownFormatting.color(selection, "#123456"));
            }
        });
        check("Invalid or injected color values and fenced code are rejected", () -> {
            for (String color : List.of("red", "#fff", "#123456; background:red", "#12345G", "")) rejects(() -> MarkdownFormatting.color("hello", color));
            rejects(() -> MarkdownFormatting.color(" ", "#123456"));
            rejects(() -> MarkdownFormatting.color("```java\ncode\n```", "#123456"));
        });
        check("Context validation protects partial image attributes and quoted greater-than signs", () -> {
            String source = "before <img src=\"a>b.png\" width=\"700\"> after";
            int width = source.indexOf("700");
            rejects(() -> MarkdownFormatting.validateContext(source, width, width + 3));
            rejects(() -> MarkdownFormatting.validateContext(source, width, width));
            rejects(() -> MarkdownFormatting.validateContext(source, source.indexOf("b.png"), source.indexOf("b.png") + 1));
            MarkdownFormatting.validateContext(source, 0, 6);
            int after = source.indexOf("after"); MarkdownFormatting.validateContext(source, after, after + 5);
            String unfinished = "hello <img src=\"open";
            rejects(() -> MarkdownFormatting.validateContext(unfinished, unfinished.length(), unfinished.length()));
        });
        check("Context validation protects backtick and tilde fenced code including blank lines", () -> {
            for (String marker : List.of("```", "~~~~")) {
                String source = "before\n" + marker + "python\ncode\n\n" + marker + "\nafter";
                int code = source.indexOf("code");
                rejects(() -> MarkdownFormatting.validateContext(source, code, code + 4));
                rejects(() -> MarkdownFormatting.validateContext(source, code + 5, code + 5));
                int after = source.indexOf("after"); MarkdownFormatting.validateContext(source, after, after + 5);
            }
            String unclosed = "```\n\n";
            rejects(() -> MarkdownFormatting.validateContext(unclosed, unclosed.length(), unclosed.length()));
        });
        check("Context validation protects inline code and comments while allowing ordinary comparisons", () -> {
            String source = "text ``a ` b`` and <!-- hidden --> after";
            int code = source.indexOf("a ` b"), hidden = source.indexOf("hidden");
            rejects(() -> MarkdownFormatting.validateContext(source, code, code + 1));
            rejects(() -> MarkdownFormatting.validateContext(source, hidden, hidden + 6));
            String comparison = "A < 2 and B > 1";
            MarkdownFormatting.validateContext(comparison, 0, comparison.length());
            String ordinary = "<span>plain text</span>";
            MarkdownFormatting.validateContext(ordinary, ordinary.indexOf("plain"), ordinary.indexOf("</"));
        });
        check("Visible blank lines have exact br counts and paragraph separation", () -> {
            for (int count : new int[]{1, 2, 10, 100}) {
                String spacing = MarkdownFormatting.blankLines(count);
                require(spacing.startsWith("\n\n") && spacing.endsWith("\n\n"), "paragraph spacing missing");
                equal(count, occurrences(spacing, "<br>"));
            }
            rejects(() -> MarkdownFormatting.blankLines(0)); rejects(() -> MarkdownFormatting.blankLines(101));
        });
        check("Table serialization produces a header separator and fixed-width rows", () -> {
            equal("\n\n| 姓名 | 数量 |\n| --- | --- |\n| 苹果 | 3 |\n| 茶 | 2 |\n\n", MarkdownFormatting.table(List.of("姓名", "数量"), List.of(List.of("苹果", "3"), List.of("茶", "2"))));
        });
        check("Literal pipes, HTML and Markdown metacharacters cannot break cells", () -> {
            String value = MarkdownFormatting.escapeCell("A|B & <img src=x> **raw** `x` [link] \\path");
            require(!value.contains("|") && !value.contains("<img"), "unsafe cell content");
            require(value.contains("&#124;") && value.contains("&amp;") && value.contains("&lt;img"), "escaping missing");
            require(value.contains("\\*\\*raw\\*\\*") && value.contains("\\`x\\`"), "literal Markdown lost");
        });
        check("Mixed CRLF and LF inside one table cell become inline br tags", () -> {
            equal("one<br>two<br>three", MarkdownFormatting.escapeCell("one\r\ntwo\nthree"));
            equal("&lt;br&gt;", MarkdownFormatting.escapeCell("<br>"));
        });
        check("Ragged tables, invalid dimensions and oversized content are rejected", () -> {
            rejects(() -> MarkdownFormatting.table(List.of(), List.of()));
            rejects(() -> MarkdownFormatting.table(List.of("A", "B"), List.of(List.of("one"))));
            rejects(() -> MarkdownFormatting.table(java.util.Collections.nCopies(51, "A"), List.of()));
            rejects(() -> MarkdownFormatting.table(List.of("A"), java.util.Collections.nCopies(501, List.of("x"))));
            rejects(() -> MarkdownFormatting.table(List.of("A"), List.of(List.of("x".repeat(100_001)))));
            rejects(() -> MarkdownFormatting.table(List.of("A"), java.util.Collections.nCopies(20, List.of("x".repeat(60_000)))));
        });
        check("Bundled GFM renderer preserves colors emphasis headings blank br and table cells", () -> {
            String colored = renderMarkdown(MarkdownFormatting.color("# Heading **bold**", "#123456"));
            require(colored.contains("<h1") && colored.contains("<strong>bold</strong>") && colored.contains("color: #123456;"), colored);
            String spaced = renderMarkdown("before" + MarkdownFormatting.blankLines(3) + "after");
            equal(3, occurrences(spaced, "<br>"));
            String table = renderMarkdown(MarkdownFormatting.table(List.of("Name", "Value"), List.of(List.of("A|B", "one\ntwo"))));
            require(table.contains("<table>") && table.contains("<td>A|B</td>") && table.contains("<td>one<br>two</td>"), table);
            Files.writeString(output.resolve("formatting-rendered-samples.html"), colored + "\n" + spaced + "\n" + table, StandardCharsets.UTF_8);
        });
        check("TSV import preserves empty cells and omits the terminal record separator", () -> {
            equal(List.of(List.of("姓名", "数量", ""), List.of("苹果", "3", "")), MarkdownTablePanel.parseTsv("姓名\t数量\t\r\n苹果\t3\t\r\n"));
        });
        check("Excel quoted cells retain tabs, newlines and doubled quotes", () -> {
            equal(List.of(List.of("A\tB", "one\ntwo", "say \"hello\"")), MarkdownTablePanel.parseTsv("\"A\tB\"\t\"one\ntwo\"\t\"say \"\"hello\"\"\""));
        });
        check("Malformed or excessive TSV is rejected before any grid changes", () -> {
            rejects(() -> MarkdownTablePanel.parseTsv("\"unclosed"));
            rejects(() -> MarkdownTablePanel.parseTsv("\"closed\"garbage"));
            rejects(() -> MarkdownTablePanel.parseTsv("a\t".repeat(51)));
            rejects(() -> MarkdownTablePanel.parseTsv("a\n".repeat(502)));
            rejects(() -> MarkdownTablePanel.parseTsv("x".repeat(1_000_001)));
        });
        check("Grid resize retains existing cells and creates editable header cells", () -> onEdt(() -> {
            MarkdownTablePanel panel = new MarkdownTablePanel();
            panel.grid.setValueAt("保留", 1, 1);
            panel.resizeGrid(5, 5);
            equal("保留", panel.grid.getValueAt(1, 1));
            equal("Column 5", panel.grid.getValueAt(0, 4));
            String before = panel.markdown(); rejects(() -> panel.resizeGrid(501, 5)); equal(before, panel.markdown());
        }));
        check("Grid paste grows atomically and serializes current in-place edits", () -> onEdt(() -> {
            MarkdownTablePanel panel = new MarkdownTablePanel();
            panel.pasteTsv("Header\tValue\n中文\t2\n", 0, 0);
            panel.pasteTsv("tail", 4, 3);
            equal(5, panel.grid.getRowCount()); equal(4, panel.grid.getColumnCount());
            equal("tail", panel.grid.getValueAt(4, 3));
            String before = panel.markdown();
            rejects(() -> panel.pasteTsv("x\ty", 1, 49)); equal(before, panel.markdown());
            require(panel.grid.editCellAt(1, 0), "cell editor unavailable");
            ((JTextField) panel.grid.getEditorComponent()).setText("尚未离开单元格");
            require(!panel.modelMarkdown().contains("尚未离开单元格"), "periodic validation must inspect committed data");
            require(panel.grid.isEditing(), "validation interrupted the active cell editor");
            require(panel.markdown().contains("尚未离开单元格"), "active edit was not committed");
        }));
        check("Color validation, English UI locale isolation and actual panel rendering", () -> onEdt(() -> {
            TextColorDialog.ColorPanel color = colorPanelOnChineseSystem();
            require(color.validColor(), "initial color invalid");
            color.hex.setText("#0102ab"); require(color.validColor(), "valid typed color rejected");
            color.hex.setText("#nothex"); require(!color.validColor(), "invalid color accepted");
            color.hex.setText("#1E88E5");
            render(color, output.resolve("text-color-panel-preview.png"), 700, 540);
            MarkdownTablePanel table = new MarkdownTablePanel();
            table.pasteTsv("实验项目\t数值\t备注\n样本 A\t12.5\t通过\n样本 B\t8.3\t待复查", 0, 0);
            render(table, output.resolve("markdown-table-panel-preview.png"), 820, 570);
        }));
        Files.write(output.resolve("formatting-test-results.txt"), results, StandardCharsets.UTF_8);
        return results.size();
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? ".build/formatting-tests" : args[0]);
        System.out.println("PASS: " + run(output) + " Markdown formatting scenarios");
    }
    private static void check(String name, Check check) throws Exception { check.run(); results.add("PASS " + name); System.out.println("PASS " + name); }
    private static void equal(Object wanted, Object actual) { if (!wanted.equals(actual)) throw new AssertionError("Wanted " + wanted + "; got " + actual); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Check check) throws Exception {
        try { check.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid input was accepted");
    }
    private static int occurrences(String text, String needle) { return (text.length() - text.replace(needle, "").length()) / needle.length(); }
    private static String renderMarkdown(String source) {
        GFMFlavourDescriptor flavour = new GFMFlavourDescriptor();
        var root = new MarkdownParser(flavour).buildMarkdownTreeFromString(source);
        return new HtmlGenerator(source, root, flavour, false).generateHtml(new HtmlGenerator.DefaultTagRenderer((node, tag, attributes) -> attributes, false));
    }
    private static void onEdt(Check check) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> { try { check.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] instanceof Exception error) throw error;
        if (failure[0] instanceof Error error) throw error;
    }
    private static TextColorDialog.ColorPanel colorPanelOnChineseSystem() {
        Locale previous = Locale.getDefault();
        Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        Locale previousSwing = JComponent.getDefaultLocale();
        Locale previousUiDefaults = UIManager.getDefaults().getDefaultLocale();
        Locale chinese = Locale.SIMPLIFIED_CHINESE;
        try {
            Locale.setDefault(chinese);
            Locale.setDefault(Locale.Category.DISPLAY, chinese);
            Locale.setDefault(Locale.Category.FORMAT, chinese);
            JComponent.setDefaultLocale(chinese);
            TextColorDialog.ColorPanel panel = new TextColorDialog.ColorPanel();
            equal(chinese, Locale.getDefault());
            equal(chinese, Locale.getDefault(Locale.Category.DISPLAY));
            equal(chinese, Locale.getDefault(Locale.Category.FORMAT));
            equal(chinese, JComponent.getDefaultLocale());
            equal(previousUiDefaults, UIManager.getDefaults().getDefaultLocale());
            require(panel.chooser.getChooserPanels().length >= 3, "standard color chooser panels were removed");
            for (var chooserPanel : panel.chooser.getChooserPanels()) requireEnglishUiText(chooserPanel.getDisplayName());
            requireEnglishUi(panel);
            return panel;
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
            JComponent.setDefaultLocale(previousSwing);
            UIManager.getDefaults().setDefaultLocale(previousUiDefaults);
        }
    }
    private static void requireEnglishUi(Component component) {
        if (component instanceof JLabel label) requireEnglishUiText(label.getText());
        if (component instanceof AbstractButton button) requireEnglishUiText(button.getText());
        if (component instanceof JTabbedPane tabs) {
            for (int index = 0; index < tabs.getTabCount(); index++) requireEnglishUiText(tabs.getTitleAt(index));
        }
        if (component instanceof JComponent swing) requireEnglishUiText(swing.getToolTipText());
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) requireEnglishUi(child);
        }
    }
    private static void requireEnglishUiText(String text) {
        require(text == null || text.codePoints().noneMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN),
            "English UI contains Chinese text: " + text);
    }
    private static void render(JComponent component, Path path, int width, int height) throws Exception {
        component.setSize(width, height); layout(component);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { component.printAll(graphics); } finally { graphics.dispose(); }
        ImageIO.write(image, "png", path.toFile());
        require(Files.size(path) > 5_000, "empty UI preview");
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container next) layout(next);
    }
}
