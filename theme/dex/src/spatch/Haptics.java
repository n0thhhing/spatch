package spatch;

import android.os.Build;
import android.view.View;

/**
 * The one place Spatch asks for touch feedback. performHapticFeedback without the ignore flag follows
 * Android's touch-feedback setting and needs no VIBRATE permission. Independent of reduced motion.
 */
final class Haptics {
    static final int PLAYBACK = 18, NAVIGATION = 19, SLIDERS = 20;
    private static final int KEYBOARD_TAP = 3, CLOCK_TICK = 4, GESTURE_END = 13, CONFIRM = 16;
    private static final int TOGGLE_ON = 21, TOGGLE_OFF = 22, SEGMENT_TICK = 27, SEGMENT_FREQUENT_TICK = 28;

    private Haptics() {}

    private static boolean allowed(int category) {
        return Settings.get(Settings.HAPTICS) && Settings.get(category);
    }

    private static int tick() {
        return Build.VERSION.SDK_INT >= 34 ? SEGMENT_TICK : CLOCK_TICK;
    }

    static void playback(View v) {
        if (allowed(PLAYBACK)) v.performHapticFeedback(KEYBOARD_TAP);
    }

    static void navigation(View v) {
        if (allowed(NAVIGATION)) v.performHapticFeedback(tick());
    }

    /** A sparse slider detent, never one per pixel. */
    static void detent(View v) {
        if (allowed(SLIDERS)) v.performHapticFeedback(Build.VERSION.SDK_INT >= 34 ? SEGMENT_FREQUENT_TICK : CLOCK_TICK);
    }

    /** One light end to a finished seek. */
    static void seekDone(View v) {
        if (allowed(SLIDERS)) v.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? GESTURE_END : CLOCK_TICK);
    }

    static void toggle(View v, boolean on) {
        if (!allowed(SLIDERS)) return;
        v.performHapticFeedback(Build.VERSION.SDK_INT >= 34 ? (on ? TOGGLE_ON : TOGGLE_OFF) : tick());
    }

    /** A completed save: playback category, one light confirm. */
    static void saved(View v) {
        if (allowed(PLAYBACK)) v.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? CONFIRM : KEYBOARD_TAP);
    }

    /** Settings' Try feedback: master and system setting apply, categories do not. */
    static boolean preview(View v) {
        return Settings.get(Settings.HAPTICS) && v.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? CONFIRM : KEYBOARD_TAP);
    }
}
