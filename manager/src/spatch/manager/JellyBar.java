package spatch.manager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;

/**
 * The navigation bar's indicator, moving like the one in patched Spotify's bottom bar: a 340ms slide
 * whose leading edge runs ahead and trailing edge follows, so it stretches toward the chosen page,
 * pinches at the waist mid-flight and settles with a small overshoot. Dragging across the bar pulls
 * the pill along (stretched toward the finger); letting go picks the page under it. Without motion it jumps.
 */
final class JellyBar extends LinearLayout {
    private static final long MILLIS = 340;
    private static final float FOLLOW_MS = 45, STRETCH = 40, SQUASH = 4; // ms, dp, dp: Spotify's NavIndicator

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final Path path = new Path();
    private final OvershootInterpolator settle = new OvershootInterpolator(1.4f);
    private final int slop;
    private final Look.Pick pick;
    private float fromL, fromR, toL, toR, left, right, top, bottom, finger, shown;
    private boolean placed, dragging;
    private long started, drawn;
    private float downX;
    int selected = -1;

    JellyBar(Context context, int color, Look.Pick pick) {
        super(context);
        this.pick = pick;
        paint.setColor(color);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setWillNotDraw(false);
    }

    /** The pill's resting place under one page's icon. */
    private float[] slot(int index) {
        View item = getChildAt(index), icon = ((LinearLayout) item).getChildAt(0);
        float l = item.getLeft() + icon.getLeft(), t = item.getTop() + icon.getTop();
        return new float[] {l, l + icon.getWidth(), t, t + icon.getHeight()};
    }

    void moveTo(int index, boolean animate) {
        selected = index;
        if (getWidth() == 0) return; // placed in onLayout
        float[] s = slot(index);
        fromL = left;
        fromR = right;
        toL = s[0];
        toR = s[1];
        top = s[2];
        bottom = s[3];
        started = SystemClock.uptimeMillis();
        if (!animate || !placed || !Look.motion(getContext())) {
            fromL = left = toL;
            fromR = right = toR;
            started = 0;
            placed = true;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (selected >= 0 && (changed || !placed)) {
            placed = false;
            moveTo(selected, false);
        }
    }

    private static float settleCurve(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!placed) return;
        float d = getResources().getDisplayMetrics().density, width = toR - toL, stretch = 0;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(48, now - drawn);
        drawn = now;
        if (dragging) {
            shown += (finger - shown) * (1 - (float) Math.exp(-dt / FOLLOW_MS));
            float lag = finger - shown;
            stretch = Math.min(1, Math.abs(lag) / (STRETCH * d));
            left = shown - width / 2 - (lag < 0 ? stretch * STRETCH * d : 0);
            right = shown + width / 2 + (lag > 0 ? stretch * STRETCH * d : 0);
            if (Math.abs(lag) > 0.5f) postInvalidateOnAnimation();
        } else if (started != 0) {
            float t = Math.min(1, (now - started) / (float) MILLIS), eased = settle.getInterpolation(t);
            float lead = settleCurve(Math.min(1, eased * 1.7f)), trail = settleCurve(Math.max(0, eased * 1.7f - 0.7f));
            boolean rightward = toL + toR >= fromL + fromR;
            left = fromL + (toL - fromL) * (rightward ? trail : lead);
            right = fromR + (toR - fromR) * (rightward ? lead : trail);
            if (t < 1) postInvalidateOnAnimation();
            else { left = toL; right = toR; started = 0; }
        }
        float height = (bottom - top) - stretch * SQUASH * d, mid = (top + bottom) / 2f;
        pill.set(left, mid - height / 2, right, mid + height / 2);
        float span = right - left, pinch = span <= width ? 0 : Math.min(1, (span - width) / Math.max(1, width * 0.85f));
        if (pinch > 0.01f) {
            gooey(pill.left, pill.top, pill.right, pill.bottom, pinch);
            canvas.drawPath(path, paint);
        } else canvas.drawRoundRect(pill, height / 2, height / 2, paint);
    }

    /** Two rounded ends joined by a waist that pinches while stretched, as in Spotify's bar. */
    private void gooey(float left, float top, float right, float bottom, float pinch) {
        float r = (bottom - top) / 2f, yM = (top + bottom) / 2f;
        float xL = left + r, xR = Math.max(xL, right - r), xM = (xL + xR) / 2f, dip = r * 0.62f * pinch;
        path.reset();
        path.moveTo(xL, top);
        path.quadTo(xM, top + dip, xR, top);
        path.quadTo(right, top, right, yM);
        path.quadTo(right, bottom, xR, bottom);
        path.quadTo(xM, bottom - dip, xL, bottom);
        path.quadTo(left, bottom, left, yM);
        path.quadTo(left, top, xL, top);
        path.close();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            dragging = false;
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(e.getX() - downX) > slop && Look.motion(getContext())) {
            dragging = true;
            started = 0;
            shown = (left + right) / 2f;
            drawn = SystemClock.uptimeMillis();
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        float half = (toR - toL) / 2f;
        finger = Math.max(half, Math.min(getWidth() - half, e.getX()));
        postInvalidateOnAnimation();
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            int index = Math.max(0, Math.min(getChildCount() - 1, (int) (e.getX() / getWidth() * getChildCount())));
            if (index == selected) moveTo(index, true); // slides back home from wherever the drag left it
            getChildAt(index).performClick();
        }
        return true;
    }
}
