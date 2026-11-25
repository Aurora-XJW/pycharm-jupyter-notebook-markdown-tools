package dev.local.notebookimages.core;

import java.util.*;
import java.util.regex.Pattern;

/** Pure Markdown transformations shared by the editor actions and headless tests. */
public final class MarkdownFormatting {
    public static final int MAX_BLANK_LINES = 100;
    public static final int MAX_TABLE_ROWS = 500;
    public static final int MAX_TABLE_COLUMNS = 50;
    public static final int MAX_CELL_CHARACTERS = 100_000;
    private static final Pattern HTML = Pattern.compile("(?is)<\\s*(?:/?[a-z][^>]*|!--.*?--|![A-Z][^>]*|\\?[^>]*)>");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern TAG_START = Pattern.compile("(?is)<\\s*(?:/?[a-z]|!--|![A-Z]|\\?)");
    private static final Pattern BLOCK_PREFIX = Pattern.compile("^ {0,3}(?:(?:>[ \\t]*)|(?:#{1,6}[ \\t]+)|(?:[-+*][ \\t]+)|(?:[0-9]{1,9}[.)][ \\t]+))*");
    private static final Pattern BLOCK_DELIMITER = Pattern.compile("^ {0,3}(?:=+|-+|(?:\\*[ \\t]*){3,}|(?:_[ \\t]*){3,})[ \\t]*$");
    private MarkdownFormatting() {}

    private record ProtectedRange(int start, int end, boolean includeStart, String reason) {
        boolean overlaps(int a, int b) {
            if (a != b) return a < end && b > start;
            return (includeStart ? a >= start : a > start) && a < end;
        }
    }

    /** Protect code and HTML tokens even when the selection includes only an attribute value. */
    public static void validateContext(String cellSource, int start, int end) {
        if (cellSource == null || start < 0 || end < start || end > cellSource.length()) {
            throw new IllegalArgumentException("The Markdown selection has changed. Select the text again.");
        }
        List<ProtectedRange> ranges = new ArrayList<>();
        int opening = -1, openingLength = 0;
        char openingChar = 0;
        for (int offset = 0; offset < cellSource.length();) {
            int newline = cellSource.indexOf('\n', offset);
            int lineEnd = newline < 0 ? cellSource.length() : newline;
            int afterLine = newline < 0 ? cellSource.length() : newline + 1;
            String line = cellSource.substring(offset, lineEnd).replaceFirst("\\r$", "");
            var fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marks = fence.group(1), info = fence.group(2);
                if (opening < 0 && (marks.charAt(0) != '`' || !info.contains("`"))) {
                    opening = offset; openingLength = marks.length(); openingChar = marks.charAt(0);
                } else if (opening >= 0 && marks.charAt(0) == openingChar && marks.length() >= openingLength && info.isBlank()) {
                    ranges.add(new ProtectedRange(opening, afterLine, true, "Content inside code blocks is not rendered as Markdown. Choose text outside the code block."));
                    opening = -1;
                }
            }
            offset = afterLine;
        }
        if (opening >= 0) ranges.add(new ProtectedRange(opening, cellSource.length() + 1, true,
            "The current position is inside an unclosed code block. Choose a position outside the code block."));

        for (int at = cellSource.indexOf('<'); at >= 0; at = cellSource.indexOf('<', at + 1)) {
            if (covered(ranges, at)) continue;
            var tag = TAG_START.matcher(cellSource);
            tag.region(at, cellSource.length());
            if (!tag.lookingAt()) continue;
            int limit;
            if (cellSource.startsWith("<!--", at)) {
                int close = cellSource.indexOf("-->", at + 4);
                limit = close < 0 ? cellSource.length() + 1 : close + 3;
            } else {
                char quote = 0;
                limit = cellSource.length() + 1;
                for (int i = tag.end(); i < cellSource.length(); i++) {
                    char ch = cellSource.charAt(i);
                    if (quote != 0) { if (ch == quote) quote = 0; }
                    else if (ch == '\'' || ch == '"') quote = ch;
                    else if (ch == '>') { limit = i + 1; break; }
                }
            }
            ranges.add(new ProtectedRange(at, limit, false, "The selection is inside an HTML tag or attribute. Select text outside the tag to protect images and links."));
            at = Math.min(limit - 1, cellSource.length());
        }
        // Equal-length backtick runs delimit inline code; unmatched runs are ordinary text.
        for (int at = cellSource.indexOf('`'); at >= 0; at = cellSource.indexOf('`', at + 1)) {
            if (covered(ranges, at) || escaped(cellSource, at)) continue;
            int runEnd = at + 1;
            while (runEnd < cellSource.length() && cellSource.charAt(runEnd) == '`') runEnd++;
            int runLength = runEnd - at;
            for (int candidate = cellSource.indexOf('`', runEnd); candidate >= 0; candidate = cellSource.indexOf('`', candidate + 1)) {
                int candidateEnd = candidate + 1;
                while (candidateEnd < cellSource.length() && cellSource.charAt(candidateEnd) == '`') candidateEnd++;
                if (candidateEnd - candidate == runLength && !covered(ranges, candidate)) {
                    ranges.add(new ProtectedRange(at, candidateEnd, false, "Inline code does not use Markdown text formatting. Select text outside the code."));
                    at = candidateEnd - 1;
                    break;
                }
                candidate = candidateEnd - 1;
            }
            if (at < runEnd - 1) at = runEnd - 1;
        }
        for (ProtectedRange range : ranges) if (range.overlaps(start, end)) throw new IllegalArgumentException(range.reason());
    }

    private static boolean covered(List<ProtectedRange> ranges, int offset) {
        for (ProtectedRange range : ranges) if (offset >= range.start() && offset < range.end()) return true;
        return false;
    }
    private static boolean escaped(String text, int offset) {
        int slashes = 0;
        for (int i = offset - 1; i >= 0 && text.charAt(i) == '\\'; i--) slashes++;
        return slashes % 2 != 0;
    }

    /** Each line gets a separate span so selections crossing paragraphs remain valid. */
    public static String color(String selected, String hex) {
        if (selected == null || selected.isBlank()) throw new IllegalArgumentException("Select the text to color first.");
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Use a #RRGGBB color value, such as #E53935.");
        }
        if (HTML.matcher(selected).find()) {
            throw new IllegalArgumentException("The selection contains HTML tags. Select text outside tags to avoid changing image or tag attributes.");
        }
        if (selected.contains("```") || selected.contains("~~~")) {
            throw new IllegalArgumentException("Select regular Markdown text. HTML inside code blocks does not render as text color.");
        }
        String normalized = selected.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean colored = false;
        for (int i = 0; i < lines.length; i++) {
            if (i != 0) result.append('\n');
            if (lines[i].stripLeading().startsWith("|")) {
                throw new IllegalArgumentException("Select text inside a table cell to keep the Markdown row structure intact.");
            }
            if (lines[i].isBlank() || BLOCK_DELIMITER.matcher(lines[i]).matches()) result.append(lines[i]);
            else {
                var prefix = BLOCK_PREFIX.matcher(lines[i]);
                prefix.find();
                String content = lines[i].substring(prefix.end());
                result.append(lines[i], 0, prefix.end());
                if (!content.isBlank()) {
                    result.append("<span style=\"color: ").append(hex.toUpperCase(Locale.ROOT))
                        .append(";\">").append(content).append("</span>");
                    colored = true;
                } else result.append(content);
            }
        }
        if (!colored) throw new IllegalArgumentException("The selection contains only whitespace or Markdown markers. Select the text to color.");
        return result.toString();
    }

    /** A separate HTML line-break block survives Markdown's collapsing of empty source lines. */
    public static String blankLines(int count) {
        if (count < 1 || count > MAX_BLANK_LINES) {
            throw new IllegalArgumentException("The number of blank lines must be between 1 and " + MAX_BLANK_LINES + ".");
        }
        return "\n\n" + "<br>\n".repeat(count) + "\n";
    }

    /** Plain grid values become literal table content; line breaks inside a cell use br tags. */
    public static String table(List<String> headers, List<List<String>> rows) {
        if (headers == null || headers.isEmpty() || headers.size() > MAX_TABLE_COLUMNS) {
            throw new IllegalArgumentException("The number of table columns must be between 1 and " + MAX_TABLE_COLUMNS + ".");
        }
        if (rows == null || rows.size() > MAX_TABLE_ROWS) {
            throw new IllegalArgumentException("Tables support up to " + MAX_TABLE_ROWS + " data rows.");
        }
        long characters = 0;
        List<List<String>> allRows = new ArrayList<>();
        allRows.add(headers); allRows.addAll(rows);
        for (List<String> row : allRows) {
            if (row == null || row.size() != headers.size()) throw new IllegalArgumentException("Each row must have the same number of cells as the header.");
            for (String cell : row) {
                if (cell != null) characters += cell.length();
                if (characters > 1_000_000) throw new IllegalArgumentException("The table contains too much text. Reduce its content.");
            }
        }
        StringBuilder result = new StringBuilder("\n\n");
        appendRow(result, headers);
        result.append('|');
        for (int column = 0; column < headers.size(); column++) result.append(" --- |");
        result.append('\n');
        for (List<String> row : rows) {
            if (row == null || row.size() != headers.size()) {
                throw new IllegalArgumentException("Each row must have the same number of cells as the header.");
            }
            appendRow(result, row);
        }
        return result.append('\n').toString();
    }

    private static void appendRow(StringBuilder result, List<String> cells) {
        result.append('|');
        for (String cell : cells) result.append(' ').append(escapeCell(cell)).append(" |");
        result.append('\n');
    }

    public static String escapeCell(String value) {
        String text = value == null ? "" : value;
        if (text.length() > MAX_CELL_CHARACTERS) throw new IllegalArgumentException("The cell content is too long.");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&' -> result.append("&amp;");
                case '<' -> result.append("&lt;");
                case '>' -> result.append("&gt;");
                // Entity encoding remains safe even inside Markdown code spans.
                case '|' -> result.append("&#124;");
                case '\\', '`', '*', '_', '[', ']', '!' -> result.append('\\').append(ch);
                case '\r' -> {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    result.append("<br>");
                }
                case '\n' -> result.append("<br>");
                case '\t' -> result.append("    ");
                default -> result.append(ch);
            }
        }
        return result.toString();
    }
}
