package spatch.manager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * The navigation bar's indicator as a jelly pill: its two edges run on separate springs, the leading
 * one quicker, so it stretches towards the chosen page and settles with a small wobble. Dragging
 * across the bar pulls the pill along; letting go picks the page under it. Without motion it jumps.
 */
final class JellyBar extends LinearLayout {
    private static final float LEAD = 700f, TRAIL = 240f, ZETA = 0.55f; // spring stiffness (1/s²) and damping ratio

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final int slop;
    private final Look.Pick pick;
    private float left, right, leftV, rightV, leftTo, rightTo, top, bottom;
    private boolean placed, dragging;
    private long last;
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
        leftTo = s[0];
        rightTo = s[1];
        top = s[2];
        bottom = s[3];
        if (!animate || !placed || !Look.motion(getContext())) {
            left = leftTo;
            right = rightTo;
            leftV = rightV = 0;
            placed = true;
        }
        last = 0;
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

    @Override
    protected void onDraw(Canvas canvas) {
        if (!placed) return;
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.032f, (now - last) / 1e9f);
        last = now;
        boolean right = rightTo + leftTo > this.right + this.left; // heading right: the right edge leads
        float[] l = step(left, leftV, leftTo, right ? TRAIL : LEAD, dt), r = step(this.right, rightV, rightTo, right ? LEAD : TRAIL, dt);
        left = l[0];
        leftV = l[1];
        this.right = r[0];
        rightV = r[1];
        float rest = rightTo - leftTo, width = Math.max(this.right - left, 1f);
        float squash = (float) Math.min(1f, Math.sqrt(rest / width)); // a stretched pill thins, like jelly
        float mid = (top + bottom) / 2f, half = (bottom - top) / 2f * (0.75f + 0.25f * squash);
        pill.set(left, mid - half, this.right, mid + half);
        canvas.drawRoundRect(pill, half, half, paint);
        boolean moving = Math.abs(leftV) + Math.abs(rightV) > 1f || Math.abs(left - leftTo) + Math.abs(this.right - rightTo) > 0.5f;
        if (moving) postInvalidateOnAnimation();
        else {
            left = leftTo;
            this.right = rightTo;
            last = 0;
        }
    }

    /** One step of a damped spring, in semi-implicit Euler. */
    private static float[] step(float x, float v, float to, float k, float dt) {
        float c = 2f * ZETA * (float) Math.sqrt(k);
        v += (k * (to - x) - c * v) * dt;
        return new float[] {x + v * dt, v};
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            dragging = false;
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(e.getX() - downX) > slop && Look.motion(getContext())) {
            dragging = true;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        float[] s = slot(selected);
        float half = (s[1] - s[0]) / 2f, x = Math.max(half, Math.min(getWidth() - half, e.getX()));
        leftTo = x - half;
        rightTo = x + half;
        postInvalidateOnAnimation();
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            int index = Math.max(0, Math.min(getChildCount() - 1, (int) (e.getX() / getWidth() * getChildCount())));
            getChildAt(index).performClick();
        }
        return true;
    }
}
