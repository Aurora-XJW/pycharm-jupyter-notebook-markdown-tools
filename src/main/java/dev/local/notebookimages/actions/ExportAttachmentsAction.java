package dev.local.notebookimages.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.DumbAwareAction;
import dev.local.notebookimages.core.*;
import dev.local.notebookimages.ide.*;
import dev.local.notebookimages.ui.ImagePasteDialog;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class ExportAttachmentsAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        NotebookContext context = NotebookContext.find(event.getData(CommonDataKeys.EDITOR)).orElse(null);
        if (context == null) return;
        ImageAssetService service = ImageAssetService.get(context.project());
        String source = context.cell().getContentText(context.editor());
        RangeMarker target = context.editor().getDocument().createRangeMarker(context.contentRange());
        service.submit(() -> {
            try {
                AttachmentExporter.Plan plan = AttachmentExporter.read(context.notebook(), context.cell().getOrdinal(), source);
                service.later(() -> choose(context, target, plan, service));
            } catch (Exception error) { service.later(target::dispose); service.error(error); }
        });
    }

    private void choose(NotebookContext context, RangeMarker target, AttachmentExporter.Plan plan, ImageAssetService service) {
        try {
            PasteController.validateTarget(context, target, plan.source());
            AssetRepository store = service.repository();
            ImageSettings.Preferences settings = ImageSettings.get(context.project()).getState();
            ImagePasteDialog dialog = new ImagePasteDialog(context.project(), store, plan.attachments().getFirst().image(),
                context.defaultDirectory(settings), settings.defaultWidth, settings.originalSize);
            dialog.setTitle("Export Cell Attachment Images (" + plan.attachments().size() + ")");
            if (!dialog.showAndGet()) { target.dispose(); return; }
            ImagePasteDialog.Choice choice = dialog.choice();
            service.beginInsertion();
            service.submit(() -> {
                List<AssetRepository.Asset> saved = new ArrayList<>();
                try {
                    Map<String, String> tags = new LinkedHashMap<>();
                    for (AttachmentExporter.Attachment attachment : plan.attachments()) {
                        String fileName = saved.isEmpty() ? choice.fileName() : store.nextName(choice.directory());
                        AssetRepository.Asset asset = store.save(attachment.image(), context.notebook(), choice.directory(), fileName);
                        saved.add(asset);
                        tags.put(attachment.name(), PathPolicy.imageTag(context.notebook(), store.path(asset), choice.width(), choice.height()));
                    }
                    String rewritten = plan.rewrite(tags);
                    service.deliver(() -> {
                        try {
                            PasteController.validateTarget(context, target, plan.source());
                            WriteCommandAction.runWriteCommandAction(context.project(), "Export Notebook Attachment Images", null,
                                () -> context.editor().getDocument().replaceString(target.getStartOffset(), target.getEndOffset(), rewritten));
                            target.dispose(); service.endInsertion(); service.refresh();
                            service.info("Images exported: " + saved.size() + ". The original attachment data is preserved. Press Ctrl+Z to undo the reference replacements.");
                        } catch (Exception error) {
                            target.dispose(); rollback(saved, store, service); service.error(error);
                        }
                    }, () -> {
                        try { for (AssetRepository.Asset asset : saved) store.discardNewAsset(asset.id()); }
                        finally { service.endInsertion(); }
                    });
                } catch (Exception error) { rollback(saved, store, service); service.later(target::dispose); service.error(error); }
            });
        } catch (Exception error) { target.dispose(); service.error(error); }
    }

    private void rollback(List<AssetRepository.Asset> saved, AssetRepository store, ImageAssetService service) {
        service.submit(() -> {
            try {
                for (AssetRepository.Asset asset : saved) {
                    try { store.discardNewAsset(asset.id()); } catch (Exception error) { service.error(error); }
                }
            } finally { service.endInsertion(); service.refresh(); }
        });
    }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(NotebookContext.find(event.getData(CommonDataKeys.EDITOR)).isPresent());
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
