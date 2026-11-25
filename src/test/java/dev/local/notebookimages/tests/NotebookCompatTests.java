package dev.local.notebookimages.tests;

import com.intellij.notebooks.visualization.NotebookCellLines;
import dev.local.notebookimages.ide.NotebookCellTypeCompat;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import com.intellij.lang.Language;
import com.intellij.util.keyFMap.KeyFMap;

/** Exercises the cell-type enum adapter against the actual SDK on the classpath. */
public final class NotebookCompatTests {
    private NotebookCompatTests() {}
    public static void run() throws Exception {
        if (NotebookCellTypeCompat.isMarkdown(null)) throw new AssertionError("Null cell enabled formatting");
        Method accessor = NotebookCellLines.Interval.class.getMethod("getType");
        Class<?> enumType = accessor.getReturnType();
        if (!enumType.isEnum()) throw new AssertionError("Unsupported cell type: " + enumType);
        boolean markdownSeen = false, nonMarkdownSeen = false;
        for (Object constant : enumType.getEnumConstants()) {
            NotebookCellLines.Interval interval = construct(constant, enumType);
            boolean expected = ((Enum<?>) constant).name().equals("MARKDOWN");
            if (NotebookCellTypeCompat.isMarkdown(interval) != expected) {
                throw new AssertionError("Cell type adapter mismatch for " + constant + " / " + enumType.getName());
            }
            markdownSeen |= expected; nonMarkdownSeen |= !expected;
        }
        if (!markdownSeen || !nonMarkdownSeen) throw new AssertionError("SDK cell-type enum was not exercised");
    }
    private static NotebookCellLines.Interval construct(Object type, Class<?> enumType) throws Exception {
        for (Constructor<?> constructor : NotebookCellLines.Interval.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 5 || parameters[1] != enumType) continue;
            Object[] arguments = new Object[5];
            for (int i = 0; i < parameters.length; i++) {
                Class<?> p = parameters[i];
                if (p == int.class) arguments[i] = 0;
                else if (p == enumType) arguments[i] = type;
                else if (p.isEnum()) arguments[i] = p.getEnumConstants()[0];
                else if (p.getName().equals("kotlin.ranges.IntRange")) arguments[i] = p.getConstructor(int.class, int.class).newInstance(0, 1);
                else if (p.getName().equals("com.intellij.util.keyFMap.KeyFMap")) arguments[i] = KeyFMap.EMPTY_MAP.plus(NotebookCellLines.Companion.getINTERVAL_LANGUAGE_KEY(), Language.ANY);
                else throw new AssertionError("Unsupported interval parameter " + p);
            }
            return (NotebookCellLines.Interval) constructor.newInstance(arguments);
        }
        throw new AssertionError("No supported cell interval constructor in current SDK");
    }
    public static void main(String[] args) throws Exception { run(); System.out.println("PASS: actual SDK cell-type compatibility adapter"); }
}
