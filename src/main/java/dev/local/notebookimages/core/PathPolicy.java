package dev.local.notebookimages.core;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Locale;
import java.util.Set;

/** All managed files remain inside the real project root. */
public final class PathPolicy {
    public static final String STATE_DIRECTORY = ".notebook-image-manager";
    private final Path root;

    public PathPolicy(Path root) throws IOException {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) throw new IOException("The project root directory does not exist.");
    }

    public Path root() { return root; }

    public Path resolve(String input) throws IOException {
        if (input == null || input.isBlank()) throw new IOException("The path cannot be empty.");
        Path supplied;
        try {
            supplied = Path.of(input.replace('/', java.io.File.separatorChar));
        } catch (InvalidPathException e) {
            throw new IOException("Invalid path: " + input, e);
        }
        return validate(supplied.isAbsolute() ? supplied : root.resolve(supplied));
    }

    public Path stored(String relative) throws IOException {
        if (relative == null || relative.isBlank() || Path.of(relative).isAbsolute()) {
            throw new IOException("A path in the management records is invalid.");
        }
        return resolve(relative);
    }

    public Path validate(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new IOException("Choose a path inside the current project.");
        Path current = root;
        for (Path segment : root.relativize(path)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException("Image paths cannot pass through symbolic links.");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Path real = current.toRealPath();
                if (!real.startsWith(root) || !real.equals(current)) {
                    throw new IOException("Image paths cannot pass through directory junctions or links.");
                }
            }
        }
        return path;
    }

    public Path imageDirectory(String input) throws IOException {
        Path path = resolve(input);
        Path relative = root.relativize(path);
        if (path.equals(root) || path.startsWith(root.resolve(STATE_DIRECTORY)) ||
            relative.getNameCount() > 0 && Set.of(".git", ".hg", ".svn", ".idea").contains(relative.getName(0).toString())) {
            throw new IOException("Choose a separate image folder inside the project.");
        }
        if (Files.exists(path) && !Files.isDirectory(path)) throw new IOException("The save path is not a folder.");
        return path;
    }

    public String relative(Path path) throws IOException {
        return root.relativize(validate(path)).toString().replace('\\', '/');
    }

    public static String fileName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("The filename cannot be empty.");
        if (!name.equals(name.strip()) || name.length() > 160 ||
            name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*") || name.endsWith(".") ||
            name.equals(".") || name.equals("..")) throw new IOException("The filename contains unsupported characters.");
        String base = name.replaceFirst("(?i)\\.png$", "");
        if (base.isBlank() || base.endsWith(".") || base.endsWith(" ") ||
            base.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) {
            throw new IOException("This filename cannot be used on Windows.");
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".png") ? name : name + ".png";
    }

    public static String relativeUrl(Path notebook, Path image) {
        String raw = notebook.toAbsolutePath().normalize().getParent()
            .relativize(image.toAbsolutePath().normalize()).toString().replace('\\', '/');
        try {
            return new URI(null, null, raw, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot create a relative image path.", e);
        }
    }

    public static String html(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String imageTag(Path notebook, Path image, int width, int height) {
        String size = width > 0 ? " width=\"" + width + "\"" : "";
        if (height > 0) size += " height=\"" + height + "\"";
        return "<img src=\"" + html(relativeUrl(notebook, image)) + "\"" + size +
            " alt=\"" + html(image.getFileName().toString()) + "\">";
    }
}
