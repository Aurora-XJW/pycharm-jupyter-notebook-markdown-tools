package dev.local.notebookimages.ide;

import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.util.TextRange;
import dev.local.notebookimages.core.*;
import dev.local.notebookimages.ui.ImagePasteDialog;
import java.awt.datatransfer.Transferable;
import java.io.IOException;

public final class PasteController {
    private PasteController() {}

    public static boolean canHandle(Editor editor, Transferable clipboard) {
        return NotebookContext.find(editor).isPresent() && ClipboardImages.available(clipboard);
    }

    public static void paste(Editor input, Transferable clipboard) {
        NotebookContext context = NotebookContext.find(input).orElse(null);
        if (context == null) return;
        Editor editor = context.editor();
        ImageAssetService service = ImageAssetService.get(context.project());
        service.start();
        if (editor.getCaretModel().getCaretCount() != 1) {
            service.warn("Keep a single caret when pasting an image."); return;
        }
        if (!ClipboardImages.available(clipboard)) {
            service.warn("Copy an image or take a screenshot first."); return;
        }
        int start = editor.getSelectionModel().hasSelection() ? editor.getSelectionModel().getSelectionStart() : editor.getCaretModel().getOffset();
        int end = editor.getSelectionModel().hasSelection() ? editor.getSelectionModel().getSelectionEnd() : start;
        if (!context.acceptsSelection(start, end)) {
            service.warn("Choose an insertion point within a single Markdown cell."); return;
        }
        if (!EditorModificationUtil.requestWriting(editor)) return;
        RangeMarker target = editor.getDocument().createRangeMarker(start, end);
        String originalText = editor.getDocument().getText(new TextRange(start, end));
        service.submit(() -> {
            try {
                ImageData image = ClipboardImages.read(clipboard);
                service.later(() -> choose(context, target, originalText, image, service));
            } catch (Exception error) {
                service.later(target::dispose); service.error(error);
            }
        });
    }

    private static void choose(NotebookContext context, RangeMarker target, String originalText,
                               ImageData image, ImageAssetService service) {
        try {
            validateTarget(context, target, originalText);
            AssetRepository store = service.repository();
            ImageSettings.Preferences settings = ImageSettings.get(context.project()).getState();
            String notebook = store.paths().relative(context.notebook());
            String directory = settings.lastNotebook.equals(notebook) && !settings.lastDirectory.isBlank()
                ? settings.lastDirectory : context.defaultDirectory(settings);
            ImagePasteDialog.Choice choice;
            if (settings.showDialog) {
                ImagePasteDialog dialog = new ImagePasteDialog(context.project(), store, image, directory,
                    settings.defaultWidth, settings.originalSize);
                if (!dialog.showAndGet()) { target.dispose(); return; }
                choice = dialog.choice();
            } else {
                choice = new ImagePasteDialog.Choice(directory, store.nextName(directory),
                    settings.originalSize ? 0 : Math.min(settings.defaultWidth, image.width()),
                    0, false, false, settings.originalSize);
            }
            saveAndInsert(context, target, originalText, image, choice, service, store);
        } catch (Exception error) { target.dispose(); service.error(error); }
    }

    public static void validateTarget(NotebookContext original, RangeMarker target, String originalText) throws IOException {
        if (original.project().isDisposed() || original.editor().isDisposed() || !target.isValid()) {
            throw new IOException("The notebook was closed or the insertion point is no longer valid. No image was inserted.");
        }
        NotebookContext current = NotebookContext.find(original.editor(), target.getStartOffset()).orElse(null);
        if (current == null || !current.acceptsSelection(target.getStartOffset(), target.getEndOffset()) ||
            !target.getDocument().getText(target.getTextRange()).equals(originalText)) {
            throw new IOException("The target Markdown cell has changed. Paste the image again.");
        }
    }

    private static void saveAndInsert(NotebookContext context, RangeMarker target, String originalText,
                                      ImageData image, ImagePasteDialog.Choice choice,
                                      ImageAssetService service, AssetRepository store) {
        service.beginInsertion();
        service.submit(() -> {
            AssetRepository.Asset asset;
            try {
                asset = store.save(image, context.notebook(), choice.directory(), choice.fileName());
            } catch (Exception error) {
                service.endInsertion(); service.later(target::dispose); service.error(error); return;
            }
            if (context.project().isDisposed()) {
                store.discardNewAsset(asset.id()); service.endInsertion(); return;
            }
            service.deliver(() -> {
                try {
                    validateTarget(context, target, originalText);
                    String tag = PathPolicy.imageTag(context.notebook(), store.path(asset), choice.width(), choice.height());
                    WriteCommandAction.runWriteCommandAction(context.project(), "Paste Notebook Image", null, () -> {
                        int position = target.getStartOffset();
                        context.editor().getDocument().replaceString(position, target.getEndOffset(), tag);
                        context.editor().getCaretModel().moveToOffset(position + tag.length());
                        context.editor().getSelectionModel().removeSelection();
                    });
                    ImageSettings.Preferences settings = ImageSettings.get(context.project()).getState();
                    settings.showDialog = choice.alwaysAsk();
                    if (choice.remember()) {
                        settings.lastDirectory = store.paths().relative(store.paths().imageDirectory(choice.directory()));
                        settings.lastNotebook = store.paths().relative(context.notebook());
                        settings.originalSize = choice.originalSize();
                        if (choice.width() > 0) settings.defaultWidth = choice.width();
                    }
                    service.refresh();
                    target.dispose(); service.endInsertion();
                } catch (Exception error) {
                    target.dispose();
                    service.submit(() -> {
                        try { store.discardNewAsset(asset.id()); }
                        finally { service.endInsertion(); }
                    });
                    service.error(error);
                }
            }, () -> {
                try { store.discardNewAsset(asset.id()); } finally { service.endInsertion(); }
            });
        });
    }
}
