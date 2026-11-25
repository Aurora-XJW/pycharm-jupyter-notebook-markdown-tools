package dev.local.notebookimages.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.DialogWrapper;
import dev.local.notebookimages.core.AssetRepository;
import dev.local.notebookimages.ide.ImageAssetService;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public final class RestoreImagesAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() == null) return;
        ImageAssetService service = ImageAssetService.get(event.getProject());
        service.submit(() -> {
            AssetRepository store = service.repository();
            List<AssetRepository.Asset> assets = store.assets().stream().filter(AssetRepository.Asset::trashed).toList();
            service.later(() -> {
                if (assets.isEmpty()) { service.info("There are no images in the recycle area."); return; }
                JList<AssetRepository.Asset> list = new JList<>(assets.toArray(AssetRepository.Asset[]::new));
                list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                list.setCellRenderer(new DefaultListCellRenderer() {
                    @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                        return super.getListCellRendererComponent(l, ((AssetRepository.Asset) value).path(), index, selected, focus);
                    }
                });
                list.setSelectedIndex(0);
                DialogWrapper dialog = new DialogWrapper(event.getProject(), true) {
                    { setTitle("Restore Recycled Images"); setOKButtonText("Restore Selected Images"); setCancelButtonText("Cancel"); init(); }
                    @Override protected JComponent createCenterPanel() {
                        JPanel panel = new JPanel(new BorderLayout(0, 12));
                        panel.add(new JLabel("Select images to restore. Hold Ctrl to select multiple images."), BorderLayout.NORTH);
                        JScrollPane scroll = new JScrollPane(list); scroll.setPreferredSize(new Dimension(610, 260));
                        panel.add(scroll, BorderLayout.CENTER);
                        panel.add(new JLabel("Images return to their original paths. Manually restored images are kept until referenced again."), BorderLayout.SOUTH);
                        return panel;
                    }
                };
                if (!dialog.showAndGet()) return;
                List<AssetRepository.Asset> selected = list.getSelectedValuesList();
                service.submit(() -> {
                    int count = 0;
                    for (AssetRepository.Asset asset : selected) {
                        try { if (store.restore(asset.id(), true)) count++; }
                        catch (Exception error) { service.error(error); }
                    }
                    service.refresh(); service.info("Images restored: " + count + ".");
                });
            });
        });
    }
    @Override public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabled(event.getProject() != null); }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
