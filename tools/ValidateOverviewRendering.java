import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Runs the real PyCharm Plugins Overview HTML factory headlessly.
 * No IDE installation, project startup, descriptor replacement or network access.
 * Arguments: plugin.xml, output-directory, optional output scale. Use the matching IDE lib/*.jar classpath.
 * Required JBR VM options:
 * --add-opens=java.desktop/javax.swing.text.html.parser=ALL-UNNAMED
 * --add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED
 * --add-exports=java.desktop/sun.awt.image=ALL-UNNAMED
 * -Djava.awt.headless=true -Didea.home.path=IDE_PATH
 * -Didea.system.path=WORKSPACE_PATH -Didea.config.path=WORKSPACE_PATH
 */
public final class ValidateOverviewRendering {
    private static final int PANE_WIDTH = 640;
    private static final int TILE_HEIGHT = 900;
    private static double outputScale = 1.0;
    private record Picture(ImageView view, Rectangle bounds) {}
    private record Section(String heading, List<String> images) {}

    private static final List<Section> SECTIONS = List.of(
        new Section("1. Paste and manage images", List.of("Save Notebook Image dialog")),
        new Section("2. Text color", List.of("Text Color action in the selection toolbar",
            "Text Color dialog with preset swatches", "HTML color markup in the Markdown cell")),
        new Section("3. Visible blank lines", List.of("Double-Alt Markdown insertion menu",
            "Insert Visible Blank Lines dialog")),
        new Section("4. Visual table editor", List.of("Insert Markdown Table dialog and editable grid"))
    );

    private static void installMinimalApplication() throws Exception {
        Object listener = Class.forName("com.intellij.ui.HelpIdAwareLinkListener").getConstructor().newInstance();
        Class<?> api = Class.forName("com.intellij.openapi.application.Application");
        Object app = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (p, m, a) -> {
            if (m.getName().equals("getService") && a[0].equals(listener.getClass())) return listener;
            if (m.getName().equals("isHeadlessEnvironment") || m.getName().equals("isUnitTestMode")) return true;
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == int.class) return 0;
            return null;
        });
        Class.forName("com.intellij.openapi.application.ApplicationManager")
            .getMethod("setApplication", api).invoke(null, app);
    }

    private static JEditorPane actualOverviewPane() throws Exception {
        Class<?> page = Class.forName("com.intellij.ide.plugins.newui.PluginDetailsPageComponent");
        Object companion = page.getField("Companion").get(null);
        Consumer<View> callback = v -> { if (v instanceof ImageView image) image.setLoadsSynchronously(true); };
        return (JEditorPane) companion.getClass()
            .getMethod("createDescriptionComponent", Consumer.class).invoke(companion, callback);
    }

    private static void collect(View view, Shape allocation, List<Picture> pictures) {
        if (view instanceof ImageView image) pictures.add(new Picture(image, allocation.getBounds()));
        for (int index = 0; index < view.getViewCount(); index++) {
            Shape child = view.getChildAllocation(index, allocation);
            if (child != null) collect(view.getView(index), child, pictures);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void verify(String description, Path output, String descriptorSha256) throws Exception {
        JEditorPane pane = actualOverviewPane();
        pane.setOpaque(true);
        pane.setBackground(Color.WHITE);
        pane.setForeground(Color.BLACK);
        pane.setText("<html><body>" + description + "</body></html>");
        pane.setSize(PANE_WIDTH, 100_000);
        int height = pane.getPreferredSize().height + 12;
        pane.setSize(PANE_WIDTH, height);
        pane.doLayout();
        View rootView = pane.getUI().getRootView(pane);
        rootView.setSize(PANE_WIDTH, height);
        List<Picture> pending = new ArrayList<>();
        collect(rootView, new Rectangle(0, 0, PANE_WIDTH, height), pending);
        MediaTracker tracker = new MediaTracker(pane);
        for (int index = 0; index < pending.size(); index++) {
            ImageView imageView = pending.get(index).view();
            imageView.setLoadsSynchronously(true);
            Image image = imageView.getImage();
            require(image != null, "Image loading did not start");
            tracker.addImage(image, index);
        }
        tracker.waitForAll(10_000);
        require(!tracker.isErrorAny() && tracker.checkAll(), "Screenshot load failed or timed out");
        for (Picture picture : pending) picture.view().preferenceChanged(null, true, true);
        rootView.setSize(PANE_WIDTH, 100_000);
        height = pane.getPreferredSize().height + 12;
        pane.setSize(PANE_WIDTH, height);
        rootView.setSize(PANE_WIDTH, height);
        BufferedImage painted = new BufferedImage((int)Math.round(PANE_WIDTH * outputScale), (int)Math.round(height * outputScale), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = painted.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, painted.getWidth(), painted.getHeight());
        g.scale(outputScale, outputScale);
        pane.paint(g);
        g.dispose();
        ImageIO.write(painted, "png", output.resolve("overview-full.png").toFile());

        List<Picture> pictures = new ArrayList<>();
        collect(pane.getUI().getRootView(pane), new Rectangle(0, 0, PANE_WIDTH, height), pictures);
        int expectedImages = SECTIONS.stream().mapToInt(section -> section.images().size()).sum();
        require(pictures.size() == expectedImages,
            "Expected " + expectedImages + " screenshots, got " + pictures.size());
        HTMLDocument document = (HTMLDocument) pane.getDocument();
        String plain = document.getText(0, document.getLength());
        int english = plain.indexOf("English");
        int chinese = plain.indexOf("中文");
        require(english >= 0 && chinese > pictures.getLast().view().getStartOffset(),
            "The English section must precede the screenshots and the Chinese section");
        int imageIndex = 0;
        for (int index = 0; index < SECTIONS.size(); index++) {
            Section section = SECTIONS.get(index);
            int start = plain.indexOf(section.heading(), english);
            String followingHeading = index + 1 < SECTIONS.size() ? SECTIONS.get(index + 1).heading() : "Requirements";
            int end = plain.indexOf(followingHeading, start + 1);
            require(start > english && end > start && end < chinese, "Missing section: " + section.heading());
            for (String expectedAlt : section.images()) {
                ImageView view = pictures.get(imageIndex++).view();
                require(view.getStartOffset() > start && view.getStartOffset() < end,
                    "Screenshot is outside its feature section: " + expectedAlt);
                require(expectedAlt.equals(view.getAltText()), "Wrong screenshot in section: " + section.heading());
            }
        }

        StringBuilder report = new StringBuilder("{\n  \"factory\": \"PluginDetailsPageComponent.Companion.createDescriptionComponent\",\n");
        report.append("  \"descriptorSHA256\": ").append(quote(descriptorSha256)).append(",\n");
        report.append("  \"editorKit\": ").append(quote(pane.getEditorKit().getClass().getName())).append(",\n");
        report.append("  \"outputScale\": ").append(outputScale).append(",\n");
        report.append("  \"baseUrl\": ").append(document.getBase() == null ? "null" : quote(document.getBase().toString())).append(",\n");
        report.append("  \"descriptionCharacters\": ").append(description.length()).append(",\n");
        report.append("  \"pageWidth\": ").append(PANE_WIDTH).append(",\n  \"pageHeight\": ").append(height).append(",\n");
        report.append("  \"englishTextOffset\": ").append(english).append(",\n  \"images\": [\n");
        for (int index = 0; index < pictures.size(); index++) {
            Picture picture = pictures.get(index);
            ImageView view = picture.view();
            String source = String.valueOf(view.getElement().getAttributes().getAttribute(HTML.Attribute.SRC));
            require(source.startsWith("data:image/png;base64,"), "Screenshot is not an offline PNG data URI");
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(source.substring(source.indexOf(',') + 1))));
            require(decoded != null, "PNG decoding failed");
            require(view.getImageURL() != null && view.getImage() != null, "Broken image URL or unloaded image");
            require(view.getImage().getWidth(null) > 0 && view.getImage().getHeight(null) > 0, "Image did not load");
            Rectangle logicalBounds = picture.bounds().intersection(new Rectangle(0, 0, PANE_WIDTH, height));
            Rectangle bounds = new Rectangle((int)Math.round(logicalBounds.x * outputScale), (int)Math.round(logicalBounds.y * outputScale),
                (int)Math.round(logicalBounds.width * outputScale), (int)Math.round(logicalBounds.height * outputScale));
            require(logicalBounds.width == decoded.getWidth() && logicalBounds.height == decoded.getHeight(),
                "Unexpected scaling or clipping: " + view.getAltText());
            require(bounds.width > 0 && bounds.height > 0, "No image layout bounds");
            Set<Integer> colors = new HashSet<>();
            long nonWhite = 0;
            for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    int rgb = painted.getRGB(x, y) & 0xffffff;
                    colors.add(rgb);
                    if (rgb != 0xffffff) nonWhite++;
                }
            }
            System.out.println("IMAGE="+(index+1)+" BOUNDS="+bounds+" COLORS="+colors.size()+" NONWHITE="+nonWhite+" LOADED="+view.getImage().getWidth(null)+"x"+view.getImage().getHeight(null));
            require(colors.size() >= 8 && nonWhite > 1000, "Image area has no rendered screenshot content");
            ImageIO.write(painted.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height), "png",
                output.resolve("overview-image-" + (index + 1) + ".png").toFile());
            if (index > 0) report.append(",\n");
            report.append("    {\"index\": ").append(index + 1)
                .append(", \"alt\": ").append(quote(view.getAltText()))
                .append(", \"view\": ").append(quote(view.getClass().getName()))
                .append(", \"sourceWidth\": ").append(decoded.getWidth())
                .append(", \"sourceHeight\": ").append(decoded.getHeight())
                .append(", \"displayWidth\": ").append(view.getPreferredSpan(View.X_AXIS))
                .append(", \"displayHeight\": ").append(view.getPreferredSpan(View.Y_AXIS))
                .append(", \"layoutX\": ").append(bounds.x).append(", \"layoutY\": ").append(bounds.y)
                .append(", \"colorsRendered\": ").append(colors.size())
                .append(", \"nonWhitePixels\": ").append(nonWhite).append("}");
        }
        report.append("\n  ],\n  \"status\": \"PASS\"\n}\n");
        int physicalTile = (int)Math.round(TILE_HEIGHT * outputScale);
        for (int top = 0, tile = 1; top < painted.getHeight(); top += physicalTile, tile++) {
            int sliceHeight = Math.min(physicalTile, painted.getHeight() - top);
            ImageIO.write(painted.getSubimage(0, top, painted.getWidth(), sliceHeight), "png",
                output.resolve(String.format(Locale.ROOT, "overview-page-%02d.png", tile)).toFile());
        }
        Files.writeString(output.resolve("overview-render-report.json"), report, StandardCharsets.UTF_8);
        System.out.println(report);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2 || args.length == 3, "Usage: ValidateOverviewRendering plugin.xml output-directory [output-scale]");
        if (args.length == 3) outputScale = Double.parseDouble(args[2]);
        require(outputScale >= 1.0 && outputScale <= 3.0, "Output scale must be 1 through 3");
        Path descriptor = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var xml = factory.newDocumentBuilder().parse(descriptor.toFile());
        String description = xml.getElementsByTagName("description").item(0).getTextContent();
        String descriptorSha256 = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(descriptor)));
        installMinimalApplication();
        SwingUtilities.invokeAndWait(() -> {
            try { verify(description, output, descriptorSha256); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        });
    }
}
