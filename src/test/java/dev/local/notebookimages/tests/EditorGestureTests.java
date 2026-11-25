package dev.local.notebookimages.tests;

import dev.local.notebookimages.core.AltTapTracker;

public final class EditorGestureTests {
    public static void run() {
        AltTapTracker keys = new AltTapTracker();
        keys.pressed(100); check(!keys.released(150), "A single Alt tap must not trigger");
        keys.pressed(260); check(keys.released(300), "Two standalone Alt taps should trigger");
        check(!keys.released(301), "Stray release must not trigger");
        keys.pressed(1000); check(!keys.released(1010), "First tap");
        keys.pressed(1700); check(!keys.released(1710), "A slow second tap must not trigger");
        keys.reset(); keys.pressed(2000); keys.pressed(2020); keys.pressed(2040);
        check(!keys.released(2050), "Key autorepeat must count as one tap");
        keys.pressed(2100); keys.reset();
        check(!keys.released(2120), "An Alt chord cancels the gesture");
        keys.pressed(2200); check(!keys.released(2900), "Holding Alt must not count as a tap");
        keys.pressed(3000); check(!keys.released(3010), "Held key must clear previous tap");
        keys.reset(); keys.pressed(4000); check(!keys.released(3900), "Clock reversal must reset state");
        keys.pressed(4100); check(!keys.released(4110), "No stale tap after clock reversal");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
