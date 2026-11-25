package dev.local.notebookimages.ide;

import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.*;
import com.intellij.ui.awt.RelativePoint;
import dev.local.notebookimages.core.AltTapTracker;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

@Service(Service.Level.PROJECT)
public final class NotebookFormattingService implements Disposable {
    private final Project project;
    private final AltTapTracker gesture = new AltTapTracker();
    private final KeyEventDispatcher dispatcher = this::dispatch;
    private boolean started;
    private boolean disposed;
    private Editor gestureEditor;
    private JBPopup popup;
    private JBPopup selectionPopup;
    private Editor pendingSelection;
    private int gestureOffset = -1;
    private final Timer selectionDelay = new Timer(260, event -> showSelectionColor());
    public NotebookFormattingService(Project project) { this.project = project; }
    public static NotebookFormattingService get(Project project) { return project.getService(NotebookFormattingService.class); }
    public void start() {
        if (started || disposed) return;
        started = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
        selectionDelay.setRepeats(false);
        // Older Notebook SDKs do not create the native Markdown floating toolbar.
        // Keep the same selection-triggered workflow without depending on newer SDK classes.
        if (!hasNativeNotebookToolbar()) {
            EditorFactory.getInstance().getEventMulticaster().addSelectionListener(new SelectionListener() {
                @Override public void selectionChanged(SelectionEvent event) {
                    Editor editor = NotebookContext.host(event.getEditor());
                    if (editor.getProject() != project) return;
                    pendingSelection = editor;
                    selectionDelay.restart();
                }
            }, this);
        }
    }
    private static boolean hasNativeNotebookToolbar() {
        try {
            Class.forName("com.intellij.notebooks.visualization.ui.MarkdownCellsFloatingToolbar", false,
                NotebookFormattingService.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) { return false; }
    }
    private void showSelectionColor() {
        if (selectionPopup != null && !selectionPopup.isDisposed()) selectionPopup.cancel();
        if (disposed || project.isDisposed()) return;
        Editor editor = pendingSelection;
        if (editor == null || editor.isDisposed() || !editor.getContentComponent().isShowing() ||
            !editor.getContentComponent().isFocusOwner()) return;
        try (MarkdownEditTarget ignored = MarkdownEditTarget.capture(editor, true)) {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
            JButton color = new JButton("Text Color…");
            panel.add(color);
            JBPopup menu = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, color)
                .setFocusable(false).setRequestFocus(false).setMovable(false).setResizable(false)
                .setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true).createPopup();
            selectionPopup = menu;
            color.addActionListener(event -> { menu.cancel(); MarkdownFormattingController.color(editor); });
            Point at = editor.offsetToXY(editor.getSelectionModel().getSelectionStart());
            at.y = Math.max(editor.getScrollingModel().getVisibleArea().y, at.y - panel.getPreferredSize().height - 6);
            menu.show(new RelativePoint(editor.getContentComponent(), at));
        } catch (IOException | IllegalArgumentException ignored) { /* No popup for invalid selections. */ }
    }
    private void resetGesture() { gesture.reset(); gestureEditor = null; gestureOffset = -1; }
    private boolean dispatch(KeyEvent event) {
        if (disposed || project.isDisposed()) return false;
        if (event.getID() != KeyEvent.KEY_PRESSED && event.getID() != KeyEvent.KEY_RELEASED) return false;
        if (event.getKeyCode() != KeyEvent.VK_ALT) {
            if (event.getID() == KeyEvent.KEY_PRESSED) resetGesture();
            return false;
        }
        if (event.isControlDown() || event.isShiftDown() || event.isMetaDown() || event.isAltGraphDown()) {
            resetGesture(); return false;
        }
        Component component = event.getComponent();
        if (component == null) { resetGesture(); return false; }
        Editor input = CommonDataKeys.EDITOR.getData(DataManager.getInstance().getDataContext(component));
        Editor editor = input == null ? null : NotebookContext.host(input);
        if (editor == null || editor.getProject() != project || editor.isDisposed() ||
            !SwingUtilities.isDescendingFrom(component, editor.getContentComponent())) {
            resetGesture(); return false;
        }
        try (MarkdownEditTarget ignored = MarkdownEditTarget.capture(editor, false)) {
            int offset = editor.getCaretModel().getOffset();
            if (gestureEditor != editor || gestureOffset != offset) {
                resetGesture(); gestureEditor = editor; gestureOffset = offset;
            }
            if (event.getID() == KeyEvent.KEY_PRESSED) gesture.pressed(event.getWhen());
            else if (gesture.released(event.getWhen())) {
                // Run after the physical Alt release, avoiding the native menu focus transition.
                SwingUtilities.invokeLater(() -> {
                    if (!disposed && !editor.isDisposed()) showInsertMenu(editor);
                });
            }
            // Only the standalone Alt events in a blank Markdown line are consumed.
            event.consume(); return true;
        } catch (IOException | IllegalArgumentException ignored) { resetGesture(); return false; }
    }
    public void showInsertMenu(Editor input) {
        if (disposed || project.isDisposed()) return;
        MarkdownEditTarget target;
        try { target = MarkdownEditTarget.capture(input, false); }
        catch (IOException | IllegalArgumentException error) { MarkdownFormattingController.report(input, error); return; }
        if (popup != null && !popup.isDisposed()) popup.cancel();
        if (!target.editor().getContentComponent().isShowing()) { target.close(); return; }
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 4));
        JButton lines = new JButton("Insert Blank Lines…");
        JButton table = new JButton("Add Table…");
        panel.add(lines); panel.add(table);
        JBPopup menu = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, lines)
            .setFocusable(true).setRequestFocus(true).setResizable(false).setMovable(false)
            .setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true).setCancelKeyEnabled(true)
            .createPopup();
        popup = menu;
        AtomicBoolean handedOff = new AtomicBoolean();
        menu.addListener(new JBPopupListener() {
            @Override public void onClosed(LightweightWindowEvent event) {
                if (!handedOff.get()) target.close();
                if (popup == menu) popup = null;
            }
        });
        lines.addActionListener(event -> {
            handedOff.set(true); menu.cancel(); MarkdownFormattingController.blankLines(target);
        });
        table.addActionListener(event -> {
            handedOff.set(true); menu.cancel(); MarkdownFormattingController.table(target);
        });
        Point at = target.editor().offsetToXY(target.editor().getCaretModel().getOffset());
        at.y += target.editor().getLineHeight() + 4;
        menu.show(new RelativePoint(target.editor().getContentComponent(), at));
    }
    @Override public void dispose() {
        disposed = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
        resetGesture();
        selectionDelay.stop(); pendingSelection = null;
        if (selectionPopup != null && !selectionPopup.isDisposed()) selectionPopup.cancel();
        if (popup != null && !popup.isDisposed()) popup.cancel();
    }
}
