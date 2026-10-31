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
        if (type == CLICKED) {
            Haptics.playback(host);
            burst(host);
        }
        super.sendAccessibilityEvent(host, type);
    }

    /** The label still names what the click does: "Pause" means it was playing. */
    private static void burst(View host) {
        String name = host.getId() > 0 ? host.getResources().getResourceEntryName(host.getId()) : "";
        boolean player = name.equals("nowplaying_elements_playpause_button");
        if (!player && !name.equals("play_pause_button")) return;
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
