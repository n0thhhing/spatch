package spatch;

import android.view.MotionEvent;
import android.view.View;

/** The album art's press spring for ordinary views: scales without touching their click handling. */
final class Springy implements View.OnTouchListener, Runnable {
    private static final float PRESSED = 0.96f;
    private final View view;
    private final float[] state = {1, 0};
    private float target = 1;
    private long drawn;
    private boolean running;

    Springy(View view) {
        this.view = view;
    }

    static void attach(View view) {
        view.setOnTouchListener(new Springy(view));
    }

    public boolean onTouch(View v, MotionEvent e) {
        int action = e.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return false;
        if (!Settings.springPress()) return false;
        target = action == MotionEvent.ACTION_DOWN ? PRESSED : 1;
        if (action == MotionEvent.ACTION_UP) state[0] = Math.min(state[0], PRESSED); // a tap shorter than a frame still bounces
        if (!running) {
            running = true;
            drawn = android.os.SystemClock.uptimeMillis();
            view.postDelayed(this, 16);
        }
        return false;
    }

    public void run() {
        long now = android.os.SystemClock.uptimeMillis();
        PlayerArt.spring(state, target, now - drawn);
        drawn = now;
        view.setScaleX(state[0]);
        view.setScaleY(state[0]);
        running = state[0] != target || state[1] != 0;
        if (running) view.postDelayed(this, 16);
    }
}
