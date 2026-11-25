package dev.local.notebookimages.ui;

import dev.local.notebookimages.core.MarkdownFormatting;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** A spreadsheet-style editor. Row 0 is the editable Markdown header, not data. */
public final class MarkdownTablePanel extends JPanel {
    public final JTable grid;
    public final JSpinner rows = new JSpinner(new SpinnerNumberModel(3, 1, MarkdownFormatting.MAX_TABLE_ROWS, 1));
    public final JSpinner columns = new JSpinner(new SpinnerNumberModel(3, 1, MarkdownFormatting.MAX_TABLE_COLUMNS, 1));
    private final DefaultTableModel model;
    private final JTextArea preview = new JTextArea(5, 60);
    private final JLabel status = new JLabel("The first row is the header. Use Tab/Enter to move; Ctrl+V pastes Excel or TSV data.");

    public MarkdownTablePanel() {
        super(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(790, 540));
        model = new DefaultTableModel(4, 3);
        grid = new JTable(model);
        grid.setCellSelectionEnabled(true);
        grid.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        grid.setRowHeight(30);
        grid.setSurrendersFocusOnKeystroke(true);
        grid.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        grid.getTableHeader().setReorderingAllowed(false);
        grid.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                     boolean focused, int row, int column) {
                Component component = super.getTableCellRendererComponent(table, value, selected, focused, row, column);
                if (!selected) component.setBackground(row == 0 ? new Color(0xE4EDF6) : table.getBackground());
                component.setFont(table.getFont().deriveFont(row == 0 ? Font.BOLD : Font.PLAIN));
                if (component instanceof JComponent label) {
                    label.setToolTipText(value == null ? "" : value.toString());
                }
                return component;
            }
        });
        for (int c = 0; c < 3; c++) model.setValueAt("Column " + (c + 1), 0, c);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toolbar.add(new JLabel("Data rows")); toolbar.add(rows);
        toolbar.add(new JLabel("Columns")); toolbar.add(columns);
        button(toolbar, "Resize", this::resizeFromControls);
        button(toolbar, "+ Row", () -> resizeGrid(model.getRowCount(), model.getColumnCount()));
        button(toolbar, "- Row", this::removeSelectedRow);
        button(toolbar, "+ Column", () -> resizeGrid(model.getRowCount() - 1, model.getColumnCount() + 1));
        button(toolbar, "- Column", this::removeSelectedColumn);
        button(toolbar, "Paste Data", this::pasteFromClipboard);
        button(toolbar, "Multiline…", this::editMultiline);
        // English labels can wrap; reserve space for both toolbar rows.
        toolbar.setPreferredSize(new Dimension(750, toolbar.getPreferredSize().height * 2));
        add(toolbar, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setPreferredSize(new Dimension(750, 275));
        JPanel center = new JPanel(new BorderLayout(0, 7));
        center.add(status, BorderLayout.NORTH);
        center.add(scroll, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(new JLabel("Markdown preview (cells are treated as text; first row is the header)"), BorderLayout.NORTH);
        preview.setEditable(false);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        preview.setLineWrap(false);
        bottom.add(new JScrollPane(preview), BorderLayout.CENTER);
        bottom.add(new JLabel("Up to 500 data rows and 50 columns. Paste multiline text; Cancel leaves the notebook unchanged."), BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        Action paste = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { pasteFromClipboard(); }
        };
        grid.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "paste-grid-data");
        grid.getActionMap().put("paste-grid-data", paste);
        model.addTableModelListener(event -> { updateColumns(); updatePreview(); });
        updateColumns();
        updatePreview();
        grid.changeSelection(0, 0, false, false);
    }

    private void button(JPanel toolbar, String label, Runnable callback) {
        JButton button = new JButton(label);
        button.addActionListener(event -> {
            try { callback.run(); }
            catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
        });
        toolbar.add(button);
    }

    private void resizeFromControls() {
        try {
            rows.commitEdit(); columns.commitEdit();
            int wantedRows = ((Number) rows.getValue()).intValue();
            int wantedColumns = ((Number) columns.getValue()).intValue();
            if ((wantedRows + 1 < model.getRowCount() || wantedColumns < model.getColumnCount()) &&
                JOptionPane.showOptionDialog(this, "Shrinking the table will remove cells outside the new range. Continue?", "Resize Table",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, new Object[]{"OK", "Cancel"}, "OK") != JOptionPane.OK_OPTION) return;
            resizeGrid(wantedRows, wantedColumns);
        } catch (java.text.ParseException | IllegalArgumentException error) { status.setText("Enter valid row and column counts."); }
    }

    public void resizeGrid(int dataRows, int columnCount) {
        requireSize(dataRows, columnCount);
        finishEditing();
        int previousColumns = model.getColumnCount();
        model.setRowCount(dataRows + 1);
        model.setColumnCount(columnCount);
        for (int c = previousColumns; c < columnCount; c++) model.setValueAt("Column " + (c + 1), 0, c);
        rows.setValue(dataRows); columns.setValue(columnCount);
        updateColumns(); updatePreview();
    }

    private static void requireSize(int dataRows, int columns) {
        if (dataRows < 1 || dataRows > MarkdownFormatting.MAX_TABLE_ROWS || columns < 1 || columns > MarkdownFormatting.MAX_TABLE_COLUMNS) {
            throw new IllegalArgumentException("Use 1–500 data rows and 1–50 columns.");
        }
    }

    private void removeSelectedRow() {
        try {
            finishEditing();
            if (model.getRowCount() <= 2) { status.setText("Keep at least one data row."); return; }
            int selected = grid.getSelectedRow();
            model.removeRow(selected > 0 ? selected : model.getRowCount() - 1);
            rows.setValue(model.getRowCount() - 1);
            updatePreview();
        } catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
    }

    private void removeSelectedColumn() {
        try {
            finishEditing();
            int count = model.getColumnCount();
            if (count <= 1) { status.setText("Keep at least one column."); return; }
            int selected = grid.getSelectedColumn();
            int removed = selected >= 0 ? selected : count - 1;
            Object[][] values = new Object[model.getRowCount()][count - 1];
            for (int r = 0; r < model.getRowCount(); r++) {
                for (int c = 0, target = 0; c < count; c++) if (c != removed) values[r][target++] = model.getValueAt(r, c);
            }
            Object[] labels = new Object[count - 1];
            for (int c = 0; c < labels.length; c++) labels[c] = columnName(c);
            model.setDataVector(values, labels);
            columns.setValue(count - 1); updateColumns(); updatePreview();
        } catch (IllegalArgumentException error) { status.setText(error.getMessage()); }
    }

    private void pasteFromClipboard() {
        try {
            Object value = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (!(value instanceof String text)) throw new IllegalArgumentException("The clipboard does not contain text.");
            pasteTsv(text, Math.max(0, grid.getSelectedRow()), Math.max(0, grid.getSelectedColumn()));
        } catch (Exception error) { status.setText(error.getMessage() == null ? "Cannot read table text from the clipboard." : error.getMessage()); }
    }

    private void editMultiline() {
        finishEditing();
        int row = Math.max(0, grid.getSelectedRow()), column = Math.max(0, grid.getSelectedColumn());
        JTextArea editor = new JTextArea(value(row, column), 8, 45);
        editor.setLineWrap(true); editor.setWrapStyleWord(true);
        int answer = JOptionPane.showOptionDialog(this, new JScrollPane(editor), "Edit Cell Text (Enter adds a line break)",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[]{"OK", "Cancel"}, "OK");
        if (answer == JOptionPane.OK_OPTION) {
            if (editor.getText().length() > MarkdownFormatting.MAX_CELL_CHARACTERS) throw new IllegalArgumentException("The cell text is too long.");
            model.setValueAt(editor.getText(), row, column);
        }
    }

    /** Atomic import: dimensions and content are validated before the model is changed. */
    public void pasteTsv(String text, int startRow, int startColumn) {
        List<List<String>> parsed = parseTsv(text);
        if (startRow < 0 || startColumn < 0) throw new IllegalArgumentException("Invalid paste position.");
        int pastedColumns = parsed.stream().mapToInt(List::size).max().orElse(0);
        long neededRows = Math.max(model.getRowCount(), (long) startRow + parsed.size());
        long neededColumns = Math.max(model.getColumnCount(), (long) startColumn + pastedColumns);
        if (neededRows > MarkdownFormatting.MAX_TABLE_ROWS + 1L || neededColumns > MarkdownFormatting.MAX_TABLE_COLUMNS) {
            throw new IllegalArgumentException("Pasting would exceed 500 data rows or 50 columns. Paste a smaller range.");
        }
        resizeGrid((int) neededRows - 1, (int) neededColumns);
        for (int r = 0; r < parsed.size(); r++) for (int c = 0; c < parsed.get(r).size(); c++) {
            model.setValueAt(parsed.get(r).get(c), startRow + r, startColumn + c);
        }
        grid.changeSelection(startRow, startColumn, false, false);
        status.setText("Pasted " + parsed.size() + " rows and " + pastedColumns + " columns; the first row remains the header.");
        updatePreview();
    }

    /** Excel-style TSV with quoted tabs/newlines and doubled quote escaping. */
    public static List<List<String>> parseTsv(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("The clipboard is empty.");
        if (text.length() > 1_000_000) throw new IllegalArgumentException("The pasted data is too large. Paste it in smaller batches.");
        List<List<String>> result = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, afterQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; afterQuote = true; }
                } else cell.append(ch);
            } else if (ch == '"' && cell.length() == 0 && !afterQuote) quoted = true;
            else if (ch == '\t') {
                addCell(row, cell); afterQuote = false;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                addCell(row, cell); result.add(row); row = new ArrayList<>(); afterQuote = false;
                if (result.size() > MarkdownFormatting.MAX_TABLE_ROWS + 1) throw new IllegalArgumentException("Too many pasted rows.");
            } else {
                if (afterQuote) throw new IllegalArgumentException("A quoted cell must be followed by a tab or line break.");
                cell.append(ch);
            }
            if (cell.length() > MarkdownFormatting.MAX_CELL_CHARACTERS) throw new IllegalArgumentException("The cell text is too long.");
        }
        if (quoted) throw new IllegalArgumentException("The pasted text contains an unclosed double quote.");
        if (!row.isEmpty() || cell.length() > 0 || afterQuote || text.endsWith("\t")) {
            addCell(row, cell); result.add(row);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("The clipboard is empty.");
        if (result.size() > MarkdownFormatting.MAX_TABLE_ROWS + 1) throw new IllegalArgumentException("Too many pasted rows.");
        return result;
    }

    private static void addCell(List<String> row, StringBuilder cell) {
        if (row.size() >= MarkdownFormatting.MAX_TABLE_COLUMNS) throw new IllegalArgumentException("The pasted data exceeds 50 columns.");
        row.add(cell.toString()); cell.setLength(0);
    }

    private void finishEditing() {
        if (grid.isEditing() && !grid.getCellEditor().stopCellEditing()) throw new IllegalArgumentException("Finish editing the current cell first.");
    }

    public String markdown() {
        finishEditing();
        return modelMarkdown();
    }

    /** Validation timers may inspect the model without committing or interrupting typed text. */
    public String modelMarkdown() {
        List<String> headers = new ArrayList<>();
        List<List<String>> values = new ArrayList<>();
        for (int c = 0; c < model.getColumnCount(); c++) headers.add(value(0, c));
        for (int r = 1; r < model.getRowCount(); r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < model.getColumnCount(); c++) row.add(value(r, c));
            values.add(row);
        }
        return MarkdownFormatting.table(headers, values);
    }
    private String value(int row, int column) { Object value = model.getValueAt(row, column); return value == null ? "" : value.toString(); }
    private void updatePreview() {
        try {
            // Do not stop an active cell editor from its own model event.
            List<String> headers = new ArrayList<>(); List<List<String>> data = new ArrayList<>();
            for (int c = 0; c < model.getColumnCount(); c++) headers.add(value(0, c));
            for (int r = 1; r < Math.min(model.getRowCount(), 12); r++) {
                List<String> row = new ArrayList<>();
                for (int c = 0; c < model.getColumnCount(); c++) row.add(value(r, c));
                data.add(row);
            }
            String content = MarkdownFormatting.table(headers, data).strip();
            preview.setText(content + (model.getRowCount() > 12 ? "\n… (Preview shows the first 11 data rows; generated Markdown includes all rows.)" : ""));
            preview.setCaretPosition(0);
        } catch (IllegalArgumentException error) { preview.setText(error.getMessage()); }
    }
    private void updateColumns() {
        for (int c = 0; c < grid.getColumnModel().getColumnCount(); c++) {
            TableColumn column = grid.getColumnModel().getColumn(c);
            column.setHeaderValue(columnName(c)); column.setMinWidth(80); column.setPreferredWidth(160);
        }
        grid.getTableHeader().repaint();
    }
    private static String columnName(int index) {
        return index < 26 ? Character.toString('A' + index) : "A" + (char) ('A' + index - 26);
    }
}
