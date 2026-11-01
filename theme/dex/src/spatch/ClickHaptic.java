package spatch;

import android.view.View;
import android.view.ViewGroup;

/**
 * performClick reports every real click through the accessibility delegate, whatever listener Spotify
 * set: taps that were canceled or disabled never get here. Only joins views with no delegate of their own.
 */
final class ClickHaptic extends View.AccessibilityDelegate {
    private static final int CLICKED = 1; // AccessibilityEvent.TYPE_VIEW_CLICKED
    private static final int PLAYER_BUTTON = 0x7f0b0b46, MINI_BUTTON = 0x7f0b0c19; // R.id.nowplaying_elements_playpause_button, play_pause_button
    private static final ClickHaptic ONE = new ClickHaptic();

    public void sendAccessibilityEvent(View host, int type) {
        if (type == CLICKED) {
            Haptics.playback(host);
            burst(host);
        }
        super.sendAccessibilityEvent(host, type);
    }

    /** The label still names what the click does: "Pause" means it was playing. */
    private static void burst(View host) {
        boolean player = host.getId() == PLAYER_BUTTON;
        if (!player && host.getId() != MINI_BUTTON) return;
        CharSequence label = host.getContentDescription();
        boolean wasPlaying = label != null && label.toString().toLowerCase(java.util.Locale.ROOT).contains("pause");
        Confetti.clicked(host, wasPlaying, player ? Settings.BURST_PLAYER : Settings.BURST_MINI);
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
