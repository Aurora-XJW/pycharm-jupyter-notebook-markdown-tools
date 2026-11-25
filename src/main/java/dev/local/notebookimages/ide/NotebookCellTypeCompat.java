package dev.local.notebookimages.ide;

import com.intellij.notebooks.visualization.NotebookCellLines;
import java.lang.reflect.Method;

/** The cell-type enum moved between 243 and 253. Its enum names remain stable. */
public final class NotebookCellTypeCompat {
    private NotebookCellTypeCompat() {}
    private static final Method GET_TYPE = typeAccessor();
    private static Method typeAccessor() {
        try { return NotebookCellLines.Interval.class.getMethod("getType"); }
        catch (ReflectiveOperationException | LinkageError unsupported) { return null; }
    }
    public static boolean isMarkdown(NotebookCellLines.Interval cell) {
        if (cell == null || GET_TYPE == null) return false;
        try {
            Object type = GET_TYPE.invoke(cell);
            return type instanceof Enum<?> value && value.name().equals("MARKDOWN");
        } catch (ReflectiveOperationException | LinkageError unsupported) {
            // Unknown notebook implementations must never enable document modification.
            return false;
        }
    }
}
