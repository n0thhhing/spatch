package spatch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import java.lang.ref.WeakReference;

/**
 * The floating bar's selected-tab indicator, drawn over the Compose tabs at 25% alpha so it can cross
 * tab cells. Moving, the leading edge runs ahead and the trailing edge follows, so it stretches toward
 * the destination and settles around that icon. Pill reports selection; touches pass through.
 */
public final class NavIndicator extends View implements ValueAnimator.AnimatorUpdateListener {
    private static final long MILLIS = 340;
    private static final float WIDTH = 64, HEIGHT = 32, TOP = 14; // dp, Pill's padding
    private static WeakReference<NavIndicator> view;
    private static int selected = -1, count = 1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final OvershootInterpolator settle = new OvershootInterpolator(1.4f);
    private ValueAnimator animator;
    private float from, to, t = 1;

    public NavIndicator(Context c, AttributeSet a) {
        super(c, a);
        paint.setColor(Palette.color(0x40d0bcff));
        view = new WeakReference<NavIndicator>(this);
        from = to = selected;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    static void tabs(int total) {
        count = Math.max(count, total);
    }

    /** Called once per real selection change; the first call only records where the app started. */
    static void select(int tab) {
        int was = selected;
        selected = tab;
        NavIndicator v = view == null ? null : view.get();
        if (v == null) return;
        if (was < 0 || !Wave.animationsEnabled() || !v.isAttachedToWindow()) v.snap(tab);
        else {
            Haptics.navigation(v);
            v.move(tab);
        }
    }

    private void snap(float tab) {
        if (animator != null) animator.cancel();
        from = to = tab;
        t = 1;
        invalidate();
    }

    private void move(float tab) {
        from = position();
        if (animator != null) animator.cancel();
        to = tab;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(MILLIS);
        animator.addUpdateListener(this);
        animator.start();
    }

    /** Interrupted moves restart from wherever the centre is now. */
    private float position() {
        return from + (to - from) * t;
    }

    public void onAnimationUpdate(ValueAnimator a) {
        t = ((Float) a.getAnimatedValue()).floatValue();
        invalidate();
    }

    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
        animator = null;
        from = to = selected;
        t = 1;
    }

    static float[] edges(float from, float to, float t, float cell, float width) {
        float lead = settleCurve(Math.min(1, t * 1.7f)), trail = settleCurve(Math.max(0, t * 1.7f - 0.7f));
        boolean right = to >= from;
        float a = (from + (to - from) * (right ? trail : lead) + .5f) * cell - width / 2;
        float b = (from + (to - from) * (right ? lead : trail) + .5f) * cell + width / 2;
        return new float[] {a, b};
    }

    private static float settleCurve(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    protected void onDraw(Canvas canvas) {
        if (selected < 0 || getWidth() > getResources().getDisplayMetrics().heightPixels) return; // landscape rail: Pill draws it
        float d = getResources().getDisplayMetrics().density, cell = getWidth() / (float) count;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float[] edge = edges(from, to, settle.getInterpolation(t), cell, WIDTH * d);
        float left = rtl ? getWidth() - edge[1] : edge[0], right = rtl ? getWidth() - edge[0] : edge[1];
        rect.set(left, TOP * d, right, (TOP + HEIGHT) * d);
        canvas.drawRoundRect(rect, HEIGHT * d / 2, HEIGHT * d / 2, paint);
    }
}
