package dev.local.notebookimages.ide;

import com.intellij.openapi.ide.CopyPasteManager;
import dev.local.notebookimages.core.ImageData;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

public final class ClipboardImages {
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "bmp");
    private ClipboardImages() {}

    public static boolean available(Transferable value) {
        if (value == null) return false;
        try {
            if (value.isDataFlavorSupported(DataFlavor.imageFlavor)) return true;
            if (value.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                Object data = value.getTransferData(DataFlavor.javaFileListFlavor);
                return data instanceof java.util.List<?> files && files.size() == 1 &&
                    files.getFirst() instanceof File file && supported(file);
            }
        } catch (IOException | UnsupportedFlavorException | IllegalStateException ignored) {}
        return false;
    }

    public static Transferable contents() { return CopyPasteManager.getInstance().getContents(); }

    public static ImageData read(Transferable value) throws IOException {
        try {
            if (value.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Object data = value.getTransferData(DataFlavor.imageFlavor);
                if (data instanceof Image image) return ImageData.fromImage(image);
            }
            if (value.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                Object data = value.getTransferData(DataFlavor.javaFileListFlavor);
                if (data instanceof java.util.List<?> files && files.size() == 1 && files.getFirst() instanceof File file && supported(file)) {
                    return ImageData.fromFile(file.toPath());
                }
            }
        } catch (UnsupportedFlavorException e) { throw new IOException("Cannot read the clipboard image.", e); }
        throw new IOException("Copy a PNG, JPEG or BMP image, or take a screenshot first.");
    }

    private static boolean supported(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return file.isFile() && dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
