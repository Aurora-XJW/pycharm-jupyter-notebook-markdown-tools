import com.intellij.util.SVGLoader;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Render the packaged vectors with JetBrains SVGLoader, without starting an IDE. */
public final class RenderPluginIcon {
    public static void main(String[] args) throws Exception {
        Path resources = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        BufferedImage preview = new BufferedImage(480, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = preview.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, 240, 300);
        g.setColor(new Color(0x1E1F22)); g.fillRect(240, 0, 240, 300);
        g.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 14));
        for (int theme = 0; theme < 2; theme++) {
            String filename = theme == 0 ? "pluginIcon.svg" : "pluginIcon_dark.svg";
            int base = theme * 240;
            for (int size : new int[]{40, 80}) {
                Image source = SVGLoader.load(resources.resolve(filename).toUri().toURL(), size / 40f);
                if (source.getWidth(null) != size || source.getHeight(null) != size)
                    throw new AssertionError("Wrong SVG image size");
                BufferedImage png = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D ig = png.createGraphics(); ig.drawImage(source, 0, 0, null); ig.dispose();
                String suffix = (theme == 0 ? "light" : "dark") + "-" + size + ".png";
                ImageIO.write(png, "png", output.resolve("plugin-icon-" + suffix).toFile());
                int x = base + (240 - size) / 2;
                int y = size == 80 ? 65 : 195;
                g.drawImage(source, x, y, null);
            }
            g.setColor(theme == 0 ? new Color(0x2B3545) : new Color(0xDFE3EA));
            g.drawString(theme == 0 ? "Light theme" : "Dark theme", base + 79, 35);
            g.drawString("80 px", base + 100, 168);
            g.drawString("40 px", base + 100, 260);
        }
        g.dispose();
        ImageIO.write(preview, "png", output.resolve("plugin-icon-preview.png").toFile());
        System.out.println("PASS: JetBrains SVGLoader rendered both themes at 40 and 80px. No IDE launched.");
    }
}
