package dev.local.notebookimages.core;

import com.google.gson.*;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Reads saved cell attachments without rewriting notebook JSON or its execution outputs. */
public final class AttachmentExporter {
    private static final Pattern MARKDOWN = Pattern.compile(
        "!\\[(?:\\\\.|[^\\]\\\\])*\\]\\(\\s*(?:<attachment:([^>]+)>|attachment:([^\\s)]+))" +
        "(?:\\s+(?:\"[^\"]*\"|'[^']*'))?\\s*\\)");
    private static final Pattern HTML = Pattern.compile(
        "(?i)<img\\b[^>]*?\\bsrc\\s*=\\s*(?:\"attachment:([^\"]+)\"|'attachment:([^']+)')[^>]*>");

    public record Attachment(String name, ImageData image) {}
    public record Plan(String source, List<Attachment> attachments) {
        public String rewrite(Map<String, String> imageTags) throws IOException {
            return replace(replace(source, MARKDOWN, imageTags), HTML, imageTags);
        }
    }

    public static Plan read(Path notebook, int cellIndex, String currentSource) throws IOException {
        if (Files.size(notebook) > 128L * 1024 * 1024) throw new IOException("The notebook file is too large to export attachments.");
        try {
            JsonObject document = JsonParser.parseString(Files.readString(notebook)).getAsJsonObject();
            JsonArray cells = document.getAsJsonArray("cells");
            if (cells == null || cellIndex < 0 || cellIndex >= cells.size()) throw new IOException("Save the notebook before exporting attachments from the current cell.");
            JsonObject cell = cells.get(cellIndex).getAsJsonObject();
            if (!"markdown".equals(cell.get("cell_type").getAsString())) throw new IOException("The current cell is not a Markdown cell.");
            String savedSource = source(cell.get("source"));
            if (!savedSource.replace("\r\n", "\n").stripTrailing().equals(currentSource.replace("\r\n", "\n").stripTrailing())) {
                throw new IOException("The current cell has unsaved changes. Save the notebook before exporting attachments.");
            }
            LinkedHashSet<String> names = new LinkedHashSet<>();
            collect(currentSource, MARKDOWN, names); collect(currentSource, HTML, names);
            if (names.isEmpty()) throw new IOException("The current cell has no attachment images to export.");
            JsonObject attachments = cell.getAsJsonObject("attachments");
            if (attachments == null) throw new IOException("The notebook is missing attachment data. An attachment link alone cannot recover the image.");
            List<Attachment> output = new ArrayList<>();
            for (String name : names) {
                JsonObject bundle = attachments.getAsJsonObject(name);
                if (bundle == null) throw new IOException("Attachment data was not found: " + name);
                JsonElement encoded = null;
                for (String mime : List.of("image/png", "image/jpeg", "image/bmp")) {
                    if (bundle.has(mime)) { encoded = bundle.get(mime); break; }
                }
                if (encoded == null) throw new IOException("The attachment is not a supported PNG, JPEG or BMP bitmap: " + name);
                byte[] bytes = Base64.getMimeDecoder().decode(source(encoded));
                output.add(new Attachment(name, ImageData.fromPngBytes(bytes)));
            }
            return new Plan(currentSource, List.copyOf(output));
        } catch (JsonParseException | IllegalStateException | NullPointerException | IllegalArgumentException e) {
            throw new IOException("Invalid notebook attachment data. The original file was not changed.", e);
        }
    }

    private static void collect(String text, Pattern pattern, Set<String> names) throws IOException {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) names.add(name(matcher));
    }
    private static String replace(String source, Pattern pattern, Map<String, String> imageTags) throws IOException {
        Matcher matcher = pattern.matcher(source);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String tag = imageTags.get(name(matcher));
            if (tag == null) throw new IOException("The export plan is missing an image.");
            matcher.appendReplacement(output, Matcher.quoteReplacement(tag));
        }
        matcher.appendTail(output);
        return output.toString();
    }
    private static String name(Matcher matcher) throws IOException {
        try {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8).replace("&amp;", "&");
        } catch (IllegalArgumentException e) { throw new IOException("The URL encoding of the attachment name is invalid.", e); }
    }
    private static String source(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (!value.isJsonArray()) return value.getAsString();
        StringBuilder output = new StringBuilder();
        for (JsonElement line : value.getAsJsonArray()) output.append(line.getAsString());
        return output.toString();
    }
}
