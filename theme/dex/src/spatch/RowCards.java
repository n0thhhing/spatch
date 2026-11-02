package spatch;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lists built as RecyclerViews (search results, albums, artist pages) hold one view per row, so each
 * row can wear the same card as Library rows. The card measures itself: headings and shelves are the
 * wrong height and draw nothing, so it can sit behind every child.
 */
public final class RowCards extends Drawable implements ViewGroup.OnHierarchyChangeListener {
    private static final float MIN = 48, MAX = 100, SIDES = 8, ENDS = 3, RADIUS = 20; // dp
    private final float dp;
    private final boolean queue;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private long fillingUntil;
    private int filled;
    private static final long FILL_MILLIS = 350;
    private static final int RISE_ROWS = 10;
    private static final float RISE = 22; // dp
    private static final long SHIMMER_MILLIS = 2800;

    private RowCards(float dp, boolean queue) {
        this.dp = dp;
        this.queue = queue;
    }

    /** A row's height decides whether it gets a card, and it must span most of its list. */
    static boolean fits(float heightDp, float width, float listWidth) {
        return heightDp >= MIN && heightDp <= MAX && width >= listWidth * .9f;
    }

    private static boolean watching;

    /** Pages open in several activities, so every window gets watched for lists as they appear. */
    static void watch(android.app.Application app) {
        if (watching) return;
        watching = true;
        app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(android.app.Activity activity) {
                join(activity.getWindow().getDecorView());
                Palette.resumed(activity);
            }

            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
                Palette.created(a);
            }

            public void onActivityStarted(android.app.Activity a) {}

            public void onActivityPaused(android.app.Activity a) {}

            public void onActivityStopped(android.app.Activity a) {}

            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}

            public void onActivityDestroyed(android.app.Activity a) {
                Palette.destroyed(a);
            }
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

    /** The queue's layout manager, on each layout: the queue is a dialog window, which the watcher above can't see. */
    public static void queue(ViewGroup list) {
        if (list != null && !JOINED.containsKey(list)) list(list, true);
    }

    private static void list(ViewGroup list, boolean queue) {
        JOINED.put(list, Boolean.TRUE);
        RowCards listener = new RowCards(list.getResources().getDisplayMetrics().density, queue);
        list.setOnHierarchyChangeListener(listener);
        for (int i = 0; i < list.getChildCount(); i++) listener.onChildViewAdded(list, list.getChildAt(i));
    }

    /** Joins every list under a view; each list is only joined once. */
    static void scan(View view) {
        if (view.getVisibility() != View.VISIBLE || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (view.getClass().getName().endsWith("RecyclerView") && !JOINED.containsKey(view)) {
            list(group, false);
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
        if (was == null) child.setBackground(new RowCards(dp, queue));
        else if (!(was instanceof android.graphics.drawable.LayerDrawable)) child.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[] {new RowCards(dp, queue), was})); // rows keep their press ripple
    }

    public void onChildViewRemoved(View parent, View child) {}

    public void draw(Canvas canvas) {
        Rect b = getBounds();
        Object owner = getCallback();
        while (owner instanceof Drawable) owner = ((Drawable) owner).getCallback();
        View parent = owner instanceof View && ((View) owner).getParent() instanceof View ? (View) ((View) owner).getParent() : null;
        if (parent == null || !fits(b.height() / dp, b.width(), parent.getWidth())) return;
        card.set(b.left + SIDES * dp, b.top + ENDS * dp, b.right - SIDES * dp, b.bottom - ENDS * dp);
        if (Settings.get(Settings.LIST_CARDS)) {
            paint.setShader(null);
            paint.setColor(Palette.color(0xff1d1a2a));
            canvas.drawRoundRect(card, RADIUS * dp, RADIUS * dp, paint);
        }
        if (queue && Settings.get(Settings.QUEUE_SHIMMER) && !Wave.paused && Wave.animationsEnabled() && shows((View) owner, SpatchWidget.nowTitle)) shimmer(canvas, (View) owner);
    }

    /** Queue rows hold their title in a TextView; a Compose row would simply never match. */
    static boolean shows(View view, String title) {
        if (title == null) return false;
        if (view instanceof android.widget.TextView) return title.contentEquals(((android.widget.TextView) view).getText());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (shows(((ViewGroup) view).getChildAt(i), title)) return true;
        return false;
    }

    private void shimmer(Canvas canvas, View row) {
        float width = card.right - card.left, band = width * .4f, t = android.os.SystemClock.uptimeMillis() % SHIMMER_MILLIS / (float) SHIMMER_MILLIS;
        float x = card.left - band + (width + band) * t;
        int light = 0x30ffffff & Tint.album() | 0x30000000;
        paint.setColor(0xffffffff);
        paint.setShader(new LinearGradient(x, 0, x + band, 0, new int[] {light & 0xffffff, light, light & 0xffffff}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card, RADIUS * dp, RADIUS * dp, paint);
        row.postInvalidateDelayed(16);
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
