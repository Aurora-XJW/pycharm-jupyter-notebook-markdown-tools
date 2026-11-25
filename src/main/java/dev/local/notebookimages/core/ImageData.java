package dev.local.notebookimages.core;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.swing.ImageIcon;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Iterator;

public record ImageData(BufferedImage preview, byte[] png, int width, int height) {
    public static final long MAX_PIXELS = 40_000_000L;
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    public static ImageData fromImage(Image image) throws IOException {
        if (image == null) throw new IOException("The clipboard does not contain a readable image.");
        if (image.getWidth(null) < 0 || image.getHeight(null) < 0) image = new ImageIcon(image).getImage();
        checkDimensions(image.getWidth(null), image.getHeight(null));
        BufferedImage buffered;
        if (image instanceof BufferedImage source) {
            // Drawing a BufferedImage through SrcOver can round semi-transparent RGB values.
            var model = source.getColorModel();
            buffered = new BufferedImage(model, source.copyData(null), model.isAlphaPremultiplied(), null);
        } else {
            buffered = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = buffered.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(image, 0, 0, null);
            } finally { graphics.dispose(); }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(buffered, "png", output)) throw new IOException("Cannot encode the PNG image.");
        return new ImageData(buffered, output.toByteArray(), buffered.getWidth(), buffered.getHeight());
    }

    public static ImageData fromFile(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("The image file exceeds 64 MB.");
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) throw new IOException("Cannot read the image.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("This image format is not supported.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                return fromImage(reader.read(0));
            } finally { reader.dispose(); }
        }
    }

    public static ImageData fromPngBytes(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("The attachment exceeds 64 MB.");
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("The attachment is not a readable image.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                checkDimensions(reader.getWidth(0), reader.getHeight(0));
                return fromImage(reader.read(0));
            } finally { reader.dispose(); }
        }
    }

    private static void checkDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw new IOException("Image dimensions are invalid or exceed 40 million pixels.");
        }
    }
}
