package dev.local.notebookimages.ide;

import com.intellij.openapi.actionSystem.*;
import dev.local.notebookimages.actions.PasteImageAction;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Resolves Ctrl+V before Jupyter's own attachment paste action can consume the image. */
public final class NotebookImageActionPromoter implements ActionPromoter {
    private boolean applies(List<? extends AnAction> actions, DataContext context) {
        return actions.stream().anyMatch(PasteImageAction.class::isInstance) &&
            NotebookContext.find(CommonDataKeys.EDITOR.getData(context)).isPresent() &&
            ClipboardImages.available(ClipboardImages.contents());
    }
    @Override public List<AnAction> promote(@NotNull List<? extends AnAction> actions, @NotNull DataContext context) {
        return applies(actions, context) ? actions.stream().filter(PasteImageAction.class::isInstance).map(a -> (AnAction) a).toList() : List.of();
    }
    @Override public List<AnAction> suppress(@NotNull List<? extends AnAction> actions, @NotNull DataContext context) {
        return applies(actions, context) ? actions.stream().filter(a -> !(a instanceof PasteImageAction)).map(a -> (AnAction) a).toList() : List.of();
    }
}
