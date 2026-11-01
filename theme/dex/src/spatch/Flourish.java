package spatch;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import p.hqr0;
import p.rhm0;
import p.z341;
import p.zpo;

/**
 * Touch candy for Your Library and playlists, whose Compose rows Spatch can't reach individually: the
 * window's touch stream is watched, never consumed, to draw a glow under a dragging finger and a ring
 * that charges up to a long press. Rows also rise in, in turn, when a page opens.
 */
final class Flourish extends Drawable implements InvocationHandler, Runnable {
    static final int NONE = 0, LIBRARY = 1, PLAYLIST = 2;
    private static final int LIBRARY_TAB = 2;
    private static final long HOLD_START = 130, HOLD_END = 400, TOUCH_WINDOW = 900, OPEN_WINDOW = 1500; // ms
    private static final float GLOW = 120, RING = 34, RING_WIDTH = 4, FOLLOW = 0.3f; // dp
    private static final float CASCADE_MILLIS = 340, CASCADE_STAGGER = 45, CASCADE_RISE = 26; // ms, dp
    private static final int CASCADE_ROWS = 10, CASCADE_MASK = 0xfffeb; // alpha and translationY only
    private static final long FRAME = 16;

    private static final hqr0 TICK = zpo.q(Long.valueOf(0L));
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable FRAMES = new Runnable() {
        public void run() {
            long now = SystemClock.uptimeMillis();
            TICK.setValue(Long.valueOf(now)); // also after the end, so the rows drop their layers
            if (now - cascadeAt <= CASCADE_MILLIS + CASCADE_STAGGER * CASCADE_ROWS) MAIN.postDelayed(this, FRAME);
        }
    };
    private static int page;
    private static boolean armed;
    private static long cascadeAt = -100000, tickSeen, tapAt = -100000;
    private static int order;
    private static Flourish current;

    private final View root;
    private final Window.Callback next;
    private final float dp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Shader glow;
    private final int slop;
    private float x, y, downX, downY, glowAlpha;
    private long downAt = -100000;
    private boolean down, dragging, popped, running;

    private Flourish(View root, Window.Callback next) {
        this.root = root;
        this.next = next;
        dp = root.getResources().getDisplayMetrics().density;
        slop = android.view.ViewConfiguration.get(root.getContext()).getScaledTouchSlop();
        int accent = Palette.color(0xffd0bcff) & 0xffffff;
        glow = new RadialGradient(0, 0, GLOW * dp, new int[] {0x40000000 | accent, 0x18000000 | accent, accent}, new float[] {0, .5f, 1}, Shader.TileMode.CLAMP);
    }

    /** Joins the window once; called by a view living in it. */
    static void attach(View anchor) {
        Activity activity = activity(anchor.getContext());
        if (activity == null) return;
        Window window = activity.getWindow();
        Window.Callback was = window.getCallback();
        if (was == null || Proxy.isProxyClass(was.getClass())) return;
        View root = anchor.getRootView();
        Flourish flourish = new Flourish(root, was);
        flourish.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(flourish);
        window.setCallback((Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(), new Class<?>[] {Window.Callback.class}, flourish));
        current = flourish;
    }

    static boolean attached() {
        return current != null;
    }

    static int page() {
        return page;
    }

    private static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("dispatchTouchEvent".equals(method.getName())) {
            try {
                touch((MotionEvent) args[0]);
            } catch (RuntimeException decoration) {
                // candy must never cost the app a touch
            }
        }
        try {
            return method.invoke(next, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The tab bar's selection tells which page is open; it comes before any of its rows. */
    static void tab(int tab) {
        page = tab == LIBRARY_TAB ? LIBRARY : NONE;
        armed = page == LIBRARY;
    }

    /** Rows of both kinds can compose on one page, so only a tap may mean a page opened: a replay hides every row. */
    private static void saw(int kind) {
        if (page == kind) return;
        page = kind;
        armed = SystemClock.uptimeMillis() - tapAt < OPEN_WINDOW;
    }

    /** Where a row rises from at this time since the page opened: {alpha, rise as 0..1}. */
    static float[] rise(float elapsed, int row) {
        float t = Math.max(0, Math.min(1, (elapsed - row * CASCADE_STAGGER) / CASCADE_MILLIS));
        float eased = 1 - (1 - t) * (1 - t) * (1 - t);
        return new float[] {eased, 1 - eased};
    }

    /** Called while a row composes; a frame tick recomposes the rows only while they are rising. */
    private static rhm0 cascade(rhm0 row, boolean enabled) {
        long tick = ((Long) TICK.getValue()).longValue();
        if (!enabled || !Wave.animationsEnabled()) return row;
        long now = SystemClock.uptimeMillis();
        if (armed) {
            armed = false;
            cascadeAt = now;
            tickSeen = tick;
            order = 0;
            MAIN.postDelayed(FRAMES, FRAME);
        }
        float elapsed = now - cascadeAt;
        if (elapsed > CASCADE_MILLIS + CASCADE_STAGGER * CASCADE_ROWS) return row;
        if (tick != tickSeen) {
            tickSeen = tick;
            order = 0;
        }
        float[] state = rise(elapsed, Math.min(order++, CASCADE_ROWS));
        float rise = state[1] * CASCADE_RISE * (current == null ? 3 : current.dp);
        return z341.q(row, 1f, 1f, state[0], 0f, rise, 0f, 0f, 0L, null, false, 0L, 0L, 0, null, CASCADE_MASK);
    }

    static rhm0 libraryRow(rhm0 row) {
        if (NavIndicator.tab() == LIBRARY_TAB) saw(LIBRARY);
        return cascade(row, Settings.get(Settings.LIBRARY_CASCADE));
    }

    static rhm0 trackRow(rhm0 row) {
        saw(PLAYLIST);
        return cascade(row, Settings.get(Settings.PLAYLIST_CASCADE));
    }

    /** The last press, for Compose buttons that report a change but not where they are. */
    static void played(boolean starting) {
        final Flourish f = current;
        if (f == null || SystemClock.uptimeMillis() - f.downAt > TOUCH_WINDOW || Confetti.answered(f.downAt)) return;
        Confetti.wanted(f.root, f.downX, f.downY, starting, Settings.BURST_LISTS);
    }

    private static boolean holdOn() {
        return page == LIBRARY ? Settings.get(Settings.LIBRARY_HOLD) : page == PLAYLIST && Settings.get(Settings.PLAYLIST_HOLD);
    }

    private static boolean glowOn() {
        return page == LIBRARY ? Settings.get(Settings.LIBRARY_GLOW) : page == PLAYLIST && Settings.get(Settings.PLAYLIST_GLOW);
    }

    private void touch(MotionEvent e) {
        int action = e.getActionMasked();
        x = e.getX();
        y = e.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            setBounds(0, 0, root.getWidth(), root.getHeight()); // unset when it joined, before layout
            down = true;
            dragging = popped = false;
            downX = x;
            downY = y;
            downAt = SystemClock.uptimeMillis();
            start();
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) dragging = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            down = false;
            if (action == MotionEvent.ACTION_UP && !dragging) tapAt = SystemClock.uptimeMillis();
        }
    }

    private void start() {
        if (running || !Wave.animationsEnabled() || !holdOn() && !glowOn()) return;
        running = true;
        root.postDelayed(this, FRAME);
    }

    /** Frames run only around a touch; the overlay is idle otherwise. */
    public void run() {
        glowAlpha += ((down && dragging && glowOn() ? 1 : 0) - glowAlpha) * FOLLOW;
        if (glowAlpha < 0.01f) glowAlpha = 0;
        float held = held();
        if (held >= 1 && !popped) {
            popped = true;
            Confetti.at(root, downX, downY);
        }
        invalidateSelf();
        running = down || glowAlpha > 0;
        if (running) root.postDelayed(this, FRAME);
    }

    /** 0 until a still finger has rested a moment, then 1 at the moment long press fires. */
    private float held() {
        if (!down || dragging || !holdOn()) return 0;
        return holdProgress(SystemClock.uptimeMillis() - downAt);
    }

    static float holdProgress(long pressed) {
        return Math.max(0, Math.min(1, (pressed - HOLD_START) / (float) (HOLD_END - HOLD_START)));
    }

    public void draw(Canvas c) {
        if (glowAlpha > 0) {
            int save = c.save();
            c.translate(x, y);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(glow);
            paint.setAlpha(Math.round(255 * glowAlpha));
            c.drawCircle(0, 0, GLOW * dp, paint);
            paint.setShader(null);
            c.restoreToCount(save);
        }
        float held = held();
        if (held > 0 && !popped) {
            float r = RING * dp * (1.25f - 0.25f * held);
            arc.set(downX - r, downY - r, downX + r, downY + r);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(RING_WIDTH * dp);
            paint.setColor(Palette.color(0x33d0bcff));
            c.drawArc(arc, 0, 360, false, paint);
            paint.setColor(Palette.color(0xffd0bcff));
            c.drawArc(arc, -90, 360 * held, false, paint);
        }
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
