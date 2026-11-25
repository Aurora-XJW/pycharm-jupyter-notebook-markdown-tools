package dev.local.notebookimages.core;

/** Recognises two complete, unmodified Alt taps; key repeats and chords never count. */
public final class AltTapTracker {
    private static final long GAP_MS = 500;
    private long firstRelease = -1;
    private long pressedAt = -1;
    private boolean pressed;
    public void reset() { firstRelease = -1; pressedAt = -1; pressed = false; }
    public void pressed(long time) {
        if (!pressed) { pressed = true; pressedAt = time; }
    }
    public boolean released(long time) {
        if (!pressed) return false;
        pressed = false;
        if (time < pressedAt || time - pressedAt > GAP_MS) { reset(); return false; }
        if (firstRelease >= 0 && time >= firstRelease && time - firstRelease <= GAP_MS) {
            reset(); return true;
        }
        firstRelease = time;
        return false;
    }
}
