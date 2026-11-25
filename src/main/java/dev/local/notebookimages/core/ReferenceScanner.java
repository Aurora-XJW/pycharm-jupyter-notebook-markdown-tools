package dev.local.notebookimages.core;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.*;

/** Conservative project-wide image reference scanning; any unreadable document blocks cleanup. */
public final class ReferenceScanner {
    private static final Set<String> SKIP = Set.of(".git", ".hg", ".svn", ".idea", ".gradle", ".build",
        ".venv", "venv", "node_modules", "__pycache__", PathPolicy.STATE_DIRECTORY);
    private static final Pattern HTML_ATTRIBUTE = Pattern.compile(
        "(?i)\\b(?:src|href|poster)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern DEFINITION = Pattern.compile("(?m)^\\s{0,3}\\[[^\\]\\n]+\\]:\\s*(?:<([^>]+)>|(\\S+))");
    private static final long MAX_DOCUMENT_BYTES = 128L * 1024 * 1024;

    public record Snapshot(Path file, String text, boolean notebookJson) {}
    public record Result(Set<Path> references, List<String> problems) {
        public boolean complete() { return problems.isEmpty(); }
    }

    public static Result scan(Path root, Collection<Snapshot> overlays) throws IOException {
        Map<Path, Snapshot> replacements = new HashMap<>();
        for (Snapshot snapshot : overlays) replacements.put(snapshot.file().toAbsolutePath().normalize(), snapshot);
        Set<Path> references = new HashSet<>();
        List<String> problems = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && (SKIP.contains(dir.getFileName().toString()) || Files.isSymbolicLink(dir))
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!isDocument(file) || attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                Path key = file.toAbsolutePath().normalize();
                Snapshot snapshot = replacements.remove(key);
                try {
                    if (snapshot == null) {
                        if (attrs.size() > MAX_DOCUMENT_BYTES) throw new IOException("The document is too large; automatic cleanup has been paused.");
                        snapshot = new Snapshot(key, Files.readString(file), isNotebook(file));
                    }
                    references.addAll(references(snapshot));
                } catch (IOException | RuntimeException e) { problems.add(file + ": " + e.getMessage()); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                problems.add(file + ": " + error.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
        for (Snapshot snapshot : replacements.values()) {
            try { references.addAll(references(snapshot)); }
            catch (IOException | RuntimeException e) { problems.add(snapshot.file() + ": " + e.getMessage()); }
        }
        return new Result(Set.copyOf(references), List.copyOf(problems));
    }

    public static Set<Path> references(Snapshot snapshot) throws IOException {
        String text = snapshot.notebookJson() ? notebookSource(snapshot.text()) : snapshot.text();
        Set<Path> output = new HashSet<>();
        Matcher html = HTML_ATTRIBUTE.matcher(text);
        while (html.find()) add(snapshot.file(), first(html.group(1), html.group(2), html.group(3)), output);
        Matcher definitions = DEFINITION.matcher(text);
        while (definitions.find()) add(snapshot.file(), first(definitions.group(1), definitions.group(2)), output);
        // Parse balanced Markdown destinations, including escaped and nested parentheses.
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) != ']' || text.charAt(i + 1) != '(') continue;
            int start = i + 2;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
            if (start >= text.length()) continue;
            if (text.charAt(start) == '<') {
                int end = text.indexOf('>', start + 1);
                if (end >= 0) add(snapshot.file(), text.substring(start + 1, end), output);
                continue;
            }
            StringBuilder destination = new StringBuilder();
            int depth = 0;
            for (int j = start; j < text.length(); j++) {
                char c = text.charAt(j);
                if (c == '\\' && j + 1 < text.length() && "()\\ ".indexOf(text.charAt(j + 1)) >= 0) {
                    destination.append(text.charAt(++j)); continue;
                }
                if (c == ')' && depth == 0 || Character.isWhitespace(c) && depth == 0) break;
                if (c == '(') depth++;
                if (c == ')') depth--;
                destination.append(c);
            }
            add(snapshot.file(), destination.toString(), output);
        }
        return output;
    }

    public static String notebookSource(String json) throws IOException {
        try {
            JsonObject notebook = JsonParser.parseString(json).getAsJsonObject();
            JsonArray cells = notebook.getAsJsonArray("cells");
            if (cells == null) throw new IOException("The notebook has no cells array; cleanup has been paused.");
            StringBuilder text = new StringBuilder();
            for (JsonElement element : cells) {
                JsonObject cell = element.getAsJsonObject();
                JsonElement source = cell.get("source");
                if (source != null && !source.isJsonNull()) {
                    if (source.isJsonArray()) for (JsonElement line : source.getAsJsonArray()) text.append(line.getAsString());
                    else text.append(source.getAsString());
                }
                text.append('\n');
            }
            return text.toString();
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            throw new IOException("Invalid notebook format; cleanup has been paused.", e);
        }
    }

    private static void add(Path document, String target, Set<Path> output) {
        if (target == null || target.isBlank()) return;
        String decoded = target.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"");
        try {
            if (decoded.matches("(?i)^(https?|data|attachment|ftp|mailto):.*")) return;
            if (decoded.startsWith("file:")) { output.add(Path.of(URI.create(decoded)).toAbsolutePath().normalize()); return; }
            int query = decoded.indexOf('?'), fragment = decoded.indexOf('#');
            int end = Math.min(query < 0 ? decoded.length() : query, fragment < 0 ? decoded.length() : fragment);
            decoded = URLDecoder.decode(decoded.substring(0, end).replace("+", "%2B"), StandardCharsets.UTF_8);
            Path path = Path.of(decoded);
            output.add((path.isAbsolute() ? path : document.toAbsolutePath().getParent().resolve(path)).normalize());
        } catch (IllegalArgumentException ignored) {
            // A malformed URL cannot refer to one of the plugin's valid, URI-encoded paths.
        }
    }

    public static boolean isNotebook(Path file) { return file.toString().toLowerCase(Locale.ROOT).endsWith(".ipynb"); }
    public static boolean isDocument(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ipynb") || name.endsWith(".md") || name.endsWith(".markdown") ||
            name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".mdx") || name.endsWith(".rmd");
    }
    private static String first(String... values) { for (String value : values) if (value != null) return value; return ""; }
}
