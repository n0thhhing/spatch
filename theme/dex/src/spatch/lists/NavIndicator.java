package spatch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import java.lang.ref.WeakReference;

/**
 * The floating bar's selected-tab indicator, drawn over the Compose tabs at 25% alpha so it can cross
 * tab cells. Moving, the leading edge runs ahead and the trailing edge follows, so it stretches toward
 * the destination and settles around that icon. Pill reports selection. Touches are forwarded to the
 * tabs until a sideways drag starts; the pill then follows the finger and release taps the tab under it.
 */
public final class NavIndicator extends View implements ValueAnimator.AnimatorUpdateListener, Runnable {
    private static final long MILLIS = 340, TAP_MILLIS = 40, CONFIRM_MILLIS = 700;
    private static final float BAR_LIFT = 1.03f, FOLLOW_MS = 45;
    private static final float LIFT_WIDTH = 8, LIFT_HEIGHT = 6, STRETCH = 40, SQUASH = 4; // dp
    private static final int ALPHA = 0x40, LIFT_ALPHA = 0x30, PRESS_ALPHA = 0x24;
    private static final float WIDTH = 64, HEIGHT = 32, TOP = 14; // dp, Pill's padding
    private static WeakReference<NavIndicator> view;
    private static int selected = -1, count = 1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final OvershootInterpolator settle = new OvershootInterpolator(1.4f);
    private ValueAnimator animator;
    private float from, to, t = 1;
    private boolean dragging, forwarding, pressing;
    private int pressTab;
    private float press;
    private float downX, downY, finger, shown, lift;
    private int hover;
    private long drawn;
    private View nav;
    private MotionEvent tapUp;

    public NavIndicator(Context c, AttributeSet a) {
        super(c, a);
        view = new WeakReference<NavIndicator>(this);
        from = to = selected;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** A setting changed while the bar sits behind the settings page. */
    static void refresh() {
        NavIndicator v = view == null ? null : view.get();
        if (v != null) v.invalidate();
    }

    /** The tabs the bar shows, from Spotify's own list each time it is built (hidden ones left out). */
    static void tabs(int total) {
        if (total < 1 || total == count) return;
        count = total;
        refresh();
    }

    /** Called once per real selection change; the first call only records where the app started. */
    static void select(int tab) {
        int was = selected;
        selected = tab;
        Flourish.tab(tab);
        NavIndicator v = view == null ? null : view.get();
        if (v == null || v.dragging) return;
        if (v.animator != null && v.animator.isRunning() && v.to == tab) return; // a drag's release already moved there
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

    /** Fractional tab under x, in selection order; the ends clamp to the first and last tabs. */
    static float dragPosition(float x, float width, int tabs, boolean rtl) {
        float cell = width / tabs, at = (rtl ? width - x : x) / cell - .5f;
        return Math.max(0, Math.min(tabs - 1, at));
    }

    private boolean calm() {
        return !Wave.animationsEnabled() || Settings.reduceMotion();
    }

    private boolean rail() {
        return getWidth() > getResources().getDisplayMetrics().heightPixels;
    }

    private boolean rtl() {
        return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private void forward(MotionEvent e, int action) {
        MotionEvent copy = MotionEvent.obtain(e);
        copy.setAction(action);
        nav.dispatchTouchEvent(copy);
        copy.recycle();
    }

    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            nav = ((View) getParent()).findViewById(getResources().getIdentifier("navigation_bar", "id", getContext().getPackageName()));
            if (nav == null || selected < 0 || rail() || !Settings.navStyle()) return false;
            downX = e.getX();
            downY = e.getY();
            dragging = false;
            forwarding = pressing = true;
            pressTab = Math.round(dragPosition(downX, getWidth(), count, rtl()));
            invalidate();
            forward(e, action);
            return true;
        }
        if (nav == null) return false;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pressing = false;
            invalidate();
        }
        if (action == MotionEvent.ACTION_MOVE && !dragging) {
            float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
            if (dx <= ViewConfiguration.get(getContext()).getScaledTouchSlop() || dx <= dy || !Settings.navDrag()) {
                forward(e, action);
                return true;
            }
            forward(e, MotionEvent.ACTION_CANCEL);
            forwarding = false;
            startDrag();
        } else if (dragging) {
            if (action == MotionEvent.ACTION_UP) release(Math.round(dragPosition(e.getX(), getWidth(), count, rtl())));
            else if (action == MotionEvent.ACTION_CANCEL) release(selected);
        } else if (forwarding) {
            forward(e, action);
            return true;
        }
        if (dragging && action == MotionEvent.ACTION_MOVE) {
            finger = dragPosition(e.getX(), getWidth(), count, rtl());
            int over = Math.round(finger);
            if (over != hover) {
                hover = over;
                Haptics.navigation(this);
            }
            invalidate();
        }
        return true;
    }

    private void startDrag() {
        shown = position();
        if (animator != null) animator.cancel();
        removeCallbacks(this);
        dragging = true;
        pressing = false;
        hover = Math.round(shown);
        drawn = SystemClock.uptimeMillis();
        getParent().requestDisallowInterceptTouchEvent(true);
        scaleBar(BAR_LIFT);
    }

    private void scaleBar(float scale) {
        if (calm() || !Settings.navLift()) return;
        nav.animate().scaleX(scale).scaleY(scale).setDuration(180).start();
        animate().scaleX(scale).scaleY(scale).setDuration(180).start();
    }

    private void release(int tab) {
        dragging = false;
        scaleBar(1);
        from = to = shown;
        t = 1;
        if (calm()) snap(tab);
        else move(tab);
        if (tab == selected) return;
        float x = (tab + .5f) * getWidth() / count, y = getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, rtl() ? getWidth() - x : x, y, 0);
        nav.dispatchTouchEvent(down);
        down.recycle();
        tapUp = MotionEvent.obtain(now, now + TAP_MILLIS, MotionEvent.ACTION_UP, rtl() ? getWidth() - x : x, y, 0);
        postDelayed(this, TAP_MILLIS);
    }

    /** Finishes a release's tap, then returns home if that tab doesn't hold selection (Create opens a sheet). */
    public void run() {
        if (tapUp != null) {
            nav.dispatchTouchEvent(tapUp);
            tapUp.recycle();
            tapUp = null;
            postDelayed(this, CONFIRM_MILLIS);
        } else if (!dragging && to != selected) {
            move(selected);
        }
    }

    /** Interrupted moves restart from wherever the centre is now. */
    private float position() {
        return from + (to - from) * t;
    }

    public void onAnimationUpdate(ValueAnimator a) {
        t = ((Float) a.getAnimatedValue()).floatValue();
        invalidate();
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Flourish.attach(this);
    }

    static int tab() {
        return selected;
    }

    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
        animator = null;
        removeCallbacks(this);
        if (tapUp != null) tapUp.recycle();
        tapUp = null;
        dragging = forwarding = pressing = false;
        lift = press = 0;
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

    /** Keeps the pill's width: both edges follow the centre at tab position at. */
    private static float[] edges(float from, float to, float t, float cell, float width, float at) {
        float centre = (at + .5f) * cell;
        return new float[] {centre - width / 2, centre + width / 2};
    }

    static float settleCurve(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    protected void onDraw(Canvas canvas) {
        if (selected < 0 || rail() || !Settings.navStyle()) return; // landscape rail: Pill draws it
        paint.setColor(Tint.color(Settings.NAV_COLOR));
        float d = getResources().getDisplayMetrics().density, cell = getWidth() / (float) count;
        float width = Math.min(WIDTH * d, cell - 2 * TOP * d); // five tabs (free accounts) leave narrow slots: keep off the bar's ends
        boolean rtl = rtl();
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(48, now - drawn), stretch = 0, left, right;
        drawn = now;
        boolean calm = calm();
        if (dragging) {
            shown = calm ? finger : shown + (finger - shown) * (1 - (float) Math.exp(-dt / FOLLOW_MS));
            float lag = (finger - shown) * cell * (rtl ? -1 : 1);
            stretch = Math.min(1, Math.abs(lag) / (STRETCH * d));
            float centre = (rtl ? getWidth() - (shown + .5f) * cell : (shown + .5f) * cell);
            left = centre - width / 2 - (lag < 0 ? stretch * STRETCH * d : 0);
            right = centre + width / 2 + (lag > 0 ? stretch * STRETCH * d : 0);
            if (Math.abs(finger - shown) > .002f) invalidate();
        } else {
            float eased = settle.getInterpolation(t);
            float[] edge = Settings.navStretch() ? edges(from, to, eased, cell, width) : edges(from, to, 1, cell, width, from + (to - from) * eased);
            left = rtl ? getWidth() - edge[1] : edge[0];
            right = rtl ? getWidth() - edge[0] : edge[1];
        }
        float aim = dragging && !calm && Settings.navLift() ? 1 : 0;
        lift += (aim - lift) * (1 - (float) Math.exp(-dt / 70f));
        if (Math.abs(aim - lift) > .01f) invalidate();
        else lift = aim;
        float pressAim = pressing && Settings.navPress() ? 1 : 0;
        press = calm ? pressAim : press + (pressAim - press) * (1 - (float) Math.exp(-dt / 60f));
        if (Math.abs(pressAim - press) > .01f) invalidate();
        else press = pressAim;
        boolean onPill = !dragging && pressTab == Math.round(position());
        if (press > 0 && !onPill) { // state layer on a tab without the pill, growing from its icon
            float half = width * (.4f + .1f * press), centre = (pressTab + .5f) * cell;
            if (rtl) centre = getWidth() - centre;
            paint.setAlpha(Math.round(PRESS_ALPHA * press));
            rect.set(centre - half, TOP * d, centre + half, (TOP + HEIGHT) * d);
            canvas.drawRoundRect(rect, HEIGHT * d / 2, HEIGHT * d / 2, paint);
        }
        float height = (HEIGHT + lift * LIFT_HEIGHT - stretch * SQUASH) * d, middle = (TOP + HEIGHT / 2) * d;
        paint.setAlpha(ALPHA + Math.round(lift * LIFT_ALPHA + (onPill ? press * PRESS_ALPHA : 0)));
        rect.set(left - lift * LIFT_WIDTH * d / 2, middle - height / 2, right + lift * LIFT_WIDTH * d / 2, middle + height / 2);
        canvas.drawRoundRect(rect, height / 2, height / 2, paint);
    }
}
