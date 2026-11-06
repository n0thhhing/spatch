package spatch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.animation.OvershootInterpolator;
import android.view.View;

/** Material 3 switch: the thumb grows and springs across, and shows a check while on. */
final class M3Switch extends View implements ValueAnimator.AnimatorUpdateListener {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent = Palette.color(0xffd0bcff), track = Palette.color(0xff2b2640),
        outline = Palette.color(0xffbcb3dc), ink = Palette.color(0xff14121c);
    private boolean checked;
    private float shown;
    private ValueAnimator animator;

    M3Switch(Context c) {
        super(c, null);
    }

    boolean isChecked() {
        return checked;
    }

    /** Without animating; the row uses toggle(). */
    void setChecked(boolean value) {
        if (animator != null) animator.cancel();
        checked = value;
        shown = value ? 1f : 0f;
        invalidate();
    }

    void toggle() {
        checked = !checked;
        if (animator != null) animator.cancel();
        if (!Wave.animationsEnabled()) {
            shown = checked ? 1f : 0f;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(shown, checked ? 1f : 0f);
        animator.setDuration(280);
        animator.setInterpolator(new OvershootInterpolator(1.8f));
        animator.addUpdateListener(this);
        animator.start();
    }

    private long movedAt;
    private float speed; // shown per second, for the jelly stretch

    @Override
    public void onAnimationUpdate(ValueAnimator a) {
        float next = ((Float) a.getAnimatedValue()).floatValue();
        long now = System.nanoTime();
        if (movedAt != 0) speed = (next - shown) / Math.max(0.004f, (now - movedAt) / 1e9f);
        movedAt = a.isRunning() && a.getAnimatedFraction() < 1f ? now : 0;
        if (movedAt == 0) speed = 0;
        shown = next;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float dp = getResources().getDisplayMetrics().density, h = getHeight(), w = getWidth(), t = Math.max(0, Math.min(1, shown));
        float inset = dp;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((int) (Pill.mix((long) track << 32, (long) accent << 32, t) >>> 32));
        c.drawRoundRect(inset, inset, w - inset, h - inset, h / 2, h / 2, paint);
        if (t < 1) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * dp);
            paint.setColor(outline);
            paint.setAlpha(Math.round(255 * (1 - t)));
            c.drawRoundRect(inset, inset, w - inset, h - inset, h / 2, h / 2, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        float cx = h / 2 + (w - h) * shown, cy = h / 2, r = (8 + 4 * t) * dp;
        paint.setColor(t > 0.5f ? ink : outline);
        if (Settings.get(Settings.JELLY_SWITCHES) && Wave.animationsEnabled() && speed != 0) {
            // jelly: the thumb stretches along its path with speed and thins to keep its volume
            float stretch = Math.min(1.7f, 1f + Math.abs(speed) * 0.09f), rx = r * stretch, ry = r / (float) Math.sqrt(stretch);
            float lead = (rx - r) * Math.signum(speed) * 0.5f;
            c.drawRoundRect(cx - rx + lead, cy - ry, cx + rx + lead, cy + ry, ry, ry, paint);
        } else c.drawCircle(cx, cy, r, paint);
        if (t > 0.5f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * dp);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(accent);
            paint.setAlpha(Math.round(255 * (t - 0.5f) * 2));
            c.drawLine(cx - 4.5f * dp, cy, cx - 1.5f * dp, cy + 3 * dp, paint);
            c.drawLine(cx - 1.5f * dp, cy + 3 * dp, cx + 4.5f * dp, cy - 3 * dp, paint);
        }
    }
}
