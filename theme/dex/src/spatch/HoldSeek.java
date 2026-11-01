package spatch;

import android.media.session.MediaController;
import android.view.MotionEvent;
import android.view.View;

/** Holding Next or Previous runs through the song instead; a normal tap still skips. */
final class HoldSeek implements View.OnTouchListener, Runnable {
    private static final long HOLD = 420, REPEAT = 260, JUMP = 10000;
    private final View button;
    private final long jump;
    private boolean held;

    HoldSeek(View button, boolean forward) {
        this.button = button;
        jump = forward ? JUMP : -JUMP;
    }

    static long landing(long position, long jump, long duration) {
        return Math.max(0, duration > 0 ? Math.min(duration - 1000, position + jump) : position + jump);
    }

    public boolean onTouch(View v, MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            held = false;
            if (Settings.get(Settings.HOLD_SEEK)) v.postDelayed(this, HOLD);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            v.removeCallbacks(this);
            if (held) {
                held = false;
                v.setPressed(false);
                return true; // a hold isn't also a skip
            }
        }
        return false;
    }

    public void run() {
        MediaController controller = SpatchWidget.controller();
        if (controller == null) return;
        held = true;
        android.media.MediaMetadata metadata = controller.getMetadata();
        long duration = metadata == null ? 0 : metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        controller.getTransportControls().seekTo(landing(SpatchWidget.position(), jump, duration));
        Haptics.detent(button);
        button.postDelayed(this, REPEAT);
    }
}
