package spatch;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lists built as RecyclerViews (search results, albums, artist pages) hold one view per row, so each
 * row can wear the same card as Library rows. The card measures itself: headings and shelves are the
 * wrong height and draw nothing, so it can sit behind every child.
 */
final class RowCards extends Drawable implements ViewGroup.OnHierarchyChangeListener {
    private static final float MIN = 48, MAX = 100, SIDES = 8, ENDS = 3, RADIUS = 20; // dp
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private long fillingUntil;
    private int filled;
    private static final long FILL_MILLIS = 350;
    private static final int RISE_ROWS = 10;
    private static final float RISE = 22; // dp

    private RowCards(float dp) {
        this.dp = dp;
    }

    /** A row's height decides whether it gets a card, and it must span most of its list. */
    static boolean fits(float heightDp, float width, float listWidth) {
        return heightDp >= MIN && heightDp <= MAX && width >= listWidth * .9f;
    }

    /** Pages open in several activities, so every window gets watched for lists as they appear. */
    static void watch(android.app.Application app) {
        app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(android.app.Activity activity) {
                join(activity.getWindow().getDecorView());
            }

            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {}

            public void onActivityStarted(android.app.Activity a) {}

            public void onActivityPaused(android.app.Activity a) {}

            public void onActivityStopped(android.app.Activity a) {}

            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}

            public void onActivityDestroyed(android.app.Activity a) {}
        });
    }

    /** Rows arrive with layouts: each window looks for new lists at most twice a second. */
    static void join(final View decor) {
        if (JOINED.containsKey(decor)) return;
        JOINED.put(decor, Boolean.TRUE);
        final Runnable look = new Runnable() {
            public void run() {
                looking = false;
                if (Settings.get(Settings.LIST_CARDS)) scan(decor);
            }
        };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                if (looking) return;
                looking = true;
                decor.postDelayed(look, 500);
            }
        });
    }

    private static boolean looking;

    /** Joins every list under a view; each list is only joined once. */
    static void scan(View view) {
        if (view.getVisibility() != View.VISIBLE || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (view.getClass().getName().endsWith("RecyclerView") && !JOINED.containsKey(view)) {
            JOINED.put(view, Boolean.TRUE);
            RowCards listener = new RowCards(view.getResources().getDisplayMetrics().density);
            group.setOnHierarchyChangeListener(listener);
            for (int i = 0; i < group.getChildCount(); i++) listener.onChildViewAdded(view, group.getChildAt(i));
        }
        for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i));
    }

    private static final java.util.WeakHashMap<View, Boolean> JOINED = new java.util.WeakHashMap<View, Boolean>();

    /** Rows filling an empty list rise in turn; a row scrolled into view just appears. */
    private void rise(View parent, View child) {
        if (!Settings.get(Settings.LIST_RISE) || !Wave.animationsEnabled()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (parent instanceof ViewGroup && ((ViewGroup) parent).getChildCount() == 1) {
            fillingUntil = now + FILL_MILLIS;
            filled = 0;
        }
        if (now > fillingUntil || filled >= RISE_ROWS) return;
        child.setAlpha(0f);
        child.setTranslationY(RISE * dp);
        child.animate().alpha(1f).translationY(0f).setStartDelay(filled++ * 35L).setDuration(300).start();
    }

    public void onChildViewAdded(View parent, View child) {
        rise(parent, child);
        Drawable was = child.getBackground();
        if (was == null) child.setBackground(new RowCards(dp));
        else if (!(was instanceof android.graphics.drawable.LayerDrawable)) child.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[] {new RowCards(dp), was})); // rows keep their press ripple
    }

    public void onChildViewRemoved(View parent, View child) {}

    public void draw(Canvas canvas) {
        Rect b = getBounds();
        Object owner = getCallback();
        while (owner instanceof Drawable) owner = ((Drawable) owner).getCallback();
        View parent = owner instanceof View && ((View) owner).getParent() instanceof View ? (View) ((View) owner).getParent() : null;
        if (!Settings.get(Settings.LIST_CARDS) || parent == null || !fits(b.height() / dp, b.width(), parent.getWidth())) return;
        paint.setColor(Palette.color(0xff1d1a2a));
        card.set(b.left + SIDES * dp, b.top + ENDS * dp, b.right - SIDES * dp, b.bottom - ENDS * dp);
        canvas.drawRoundRect(card, RADIUS * dp, RADIUS * dp, paint);
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
