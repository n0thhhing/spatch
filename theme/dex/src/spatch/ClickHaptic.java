package spatch;

import android.view.View;
import android.view.ViewGroup;

/**
 * performClick reports every real click through the accessibility delegate, whatever listener Spotify
 * set: taps that were canceled or disabled never get here. Only joins views with no delegate of their own.
 */
final class ClickHaptic extends View.AccessibilityDelegate {
    private static final int CLICKED = 1; // AccessibilityEvent.TYPE_VIEW_CLICKED
    private static final ClickHaptic ONE = new ClickHaptic();

    public void sendAccessibilityEvent(View host, int type) {
        if (type == CLICKED) Haptics.playback(host);
        super.sendAccessibilityEvent(host, type);
    }

    /** Every clickable view under root, skipping save buttons (SaveBurst owns their feedback). */
    static void attach(View root) {
        if (root == null) return;
        if (root.isClickable() && !root.getClass().getName().endsWith("AddToButtonView") && root.getAccessibilityDelegate() == null) {
            root.setAccessibilityDelegate(ONE);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) attach(group.getChildAt(i));
        }
    }
}
