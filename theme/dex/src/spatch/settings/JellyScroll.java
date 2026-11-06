package spatch;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * Jelly scrolling for a settings page: each row trails the scroll by how far it sits from the finger
 * and springs back, so the list stretches and settles like jelly. Rows inside a group card move one by
 * one; the page itself never moves, so taps land where rows are drawn once they settle.
 */
final class JellyScroll implements ViewTreeObserver.OnScrollChangedListener, View.OnTouchListener, Runnable {
    private static final float K = 380f, ZETA = 0.6f, LAG = 0.16f;
    private final ScrollView scroll;
    private final LinearLayout page;
    private final java.util.HashMap<View, float[]> rows = new java.util.HashMap<View, float[]>(); // {offset, velocity}
    private int lastY;
    private float anchor = -1;
    private long last;
    private boolean running;

    private JellyScroll(ScrollView scroll, LinearLayout page) {
        this.scroll = scroll;
        this.page = page;
    }

    static void attach(ScrollView scroll, LinearLayout page) {
        JellyScroll j = new JellyScroll(scroll, page);
        scroll.getViewTreeObserver().addOnScrollChangedListener(j);
        scroll.setOnTouchListener(j);
    }

    private static boolean on() {
        return Settings.get(Settings.JELLY_SCROLL) && Wave.animationsEnabled();
    }

    public boolean onTouch(View v, MotionEvent e) {
        anchor = e.getY();
        return false; // the scroll view still scrolls
    }

    public void onScrollChanged() {
        int y = scroll.getScrollY(), d = y - lastY;
        lastY = y;
        if (d == 0 || !scroll.isShown() || !on()) return;
        float height = scroll.getHeight(), at = anchor < 0 ? height / 2 : anchor, cap = height * 0.05f;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child instanceof LinearLayout && ((LinearLayout) child).getOrientation() == LinearLayout.VERTICAL && ((ViewGroup) child).getChildCount() > 1) {
                ViewGroup group = (ViewGroup) child;
                for (int j = 0; j < group.getChildCount(); j++) nudge(group.getChildAt(j), child.getTop() - y, at, height, d, cap);
            } else nudge(child, 0 - y, at, height, d, cap);
        }
        if (!running) {
            running = true;
            last = 0;
            scroll.postOnAnimation(this);
        }
    }

    private void nudge(View row, int parentTop, float at, float height, int d, float cap) {
        float centre = parentTop + row.getTop() + row.getHeight() / 2f;
        if (centre < -height || centre > 2 * height) return; // well off screen
        float[] s = rows.get(row);
        if (s == null) rows.put(row, s = new float[2]);
        s[0] = Math.max(-cap, Math.min(cap, s[0] + d * LAG * Math.min(1f, Math.abs(centre - at) / height)));
    }

    public void run() {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.032f, (now - last) / 1e9f);
        last = now;
        boolean moving = false;
        float c = 2f * ZETA * (float) Math.sqrt(K);
        for (java.util.Map.Entry<View, float[]> e : rows.entrySet()) {
            float[] s = e.getValue();
            s[1] += (-K * s[0] - c * s[1]) * dt;
            s[0] += s[1] * dt;
            if (Math.abs(s[0]) < 0.3f && Math.abs(s[1]) < 3f) s[0] = s[1] = 0;
            else moving = true;
            e.getKey().setTranslationY(s[0]);
        }
        if (moving && on()) scroll.postOnAnimation(this);
        else {
            for (View v : rows.keySet()) v.setTranslationY(0f);
            rows.clear();
            running = false;
        }
    }
}
