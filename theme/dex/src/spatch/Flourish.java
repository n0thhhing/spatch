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
import kotlin.jvm.functions.Function0;
import p.hqr0;
import p.rhm0;
import p.z341;
import p.zpo;

/**
 * Touch candy for Your Library and playlists, whose Compose rows Spatch can't reach individually: the
 * window's touch stream is watched, never consumed, to draw a glow under a dragging finger and a ring
 * that charges up to a long press. Rows also rise in, in turn, when a page opens.
 */
public final class Flourish extends Drawable implements InvocationHandler, Runnable, android.view.ViewTreeObserver.OnGlobalLayoutListener {
    static final int NONE = 0, LIBRARY = 1, PLAYLIST = 2;
    private static final int LIBRARY_TAB = 2;
    private static final long HOLD_START = 130, HOLD_END = 400, OPEN_WINDOW = 1500, RIPPLE_MILLIS = 340;
    private static final float BAR_PULL = 40; // dp // ms
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
    private long downAt = -100000, rippleAt = -100000;
    private float rippleX, rippleY, lastY, pulled;
    private View nav, pill, mini;
    private boolean barsHidden, scanning;
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
        root.getViewTreeObserver().addOnGlobalLayoutListener(flourish);
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
        if ("dispatchKeyEvent".equals(method.getName()) && nav != null) bars(-1); // Back can leave the page they hid on
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
        float alpha = 1, rise = 0;
        if (enabled && Wave.animationsEnabled()) {
            long now = SystemClock.uptimeMillis();
            if (armed) {
                armed = false;
                cascadeAt = now;
                tickSeen = tick;
                order = 0;
                MAIN.postDelayed(FRAMES, FRAME);
            }
            float elapsed = now - cascadeAt;
            if (elapsed <= CASCADE_MILLIS + CASCADE_STAGGER * CASCADE_ROWS) {
                if (tick != tickSeen) {
                    tickSeen = tick;
                    order = 0;
                }
                float[] state = rise(elapsed, Math.min(order++, CASCADE_ROWS));
                alpha = state[0];
                rise = state[1] * CASCADE_RISE * (current == null ? 3 : current.dp);
            }
        }
        // Always the layer, even at rest: on the phone, rows that lost it lost their card too (cause unknown).
        return z341.q(row, 1f, 1f, alpha, 0f, rise, 0f, 0f, 0L, null, false, 0L, 0L, 0, null, CASCADE_MASK);
    }

    static rhm0 libraryRow(rhm0 row) {
        if (NavIndicator.tab() == LIBRARY_TAB) saw(LIBRARY);
        return cascade(row, Settings.get(Settings.LIBRARY_CASCADE));
    }

    static rhm0 trackRow(rhm0 row) {
        saw(PLAYLIST);
        return cascade(row, Settings.get(Settings.PLAYLIST_CASCADE));
    }

    /** A Compose play button's own click (f1p1.j), showing state: only a press of it bursts, at the last touch. */
    public static Function0 pressed(final Function0 click, final int state) {
        return new Function0() {
            public Object invoke() {
                Flourish f = current;
                if (f != null) Confetti.wanted(f.root, f.downX, f.downY, state != ScallopShape.PLAYING, Settings.BURST_LISTS);
                return click.invoke();
            }
        };
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
            lastY = y;
            bars(0);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (Settings.get(Settings.HIDE_BARS)) {
                pulled += y - lastY;
                if (Math.abs(x - downX) < Math.abs(y - downY)) {
                    if (pulled < -BAR_PULL * dp) bars(1);
                    else if (pulled > BAR_PULL * dp / 3) bars(-1);
                }
                lastY = y;
            }
            if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) dragging = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            down = false;
            pulled = 0;
            if (action == MotionEvent.ACTION_UP && !dragging) {
                bars(-1); // a tap may open anything: nothing should stay hidden under it
                tapAt = SystemClock.uptimeMillis();
                if (Settings.get(Settings.TAP_RIPPLE) && Wave.animationsEnabled()) {
                    rippleAt = tapAt;
                    rippleX = x;
                    rippleY = y;
                    start();
                }
            }
        }
    }

    /** Slides the bottom bars: 1 away, -1 back, 0 only finds them for this touch. */
    private void bars(int way) {
        if (way == 0) {
            nav = root.findViewById(root.getResources().getIdentifier("navigation_bar", "id", root.getContext().getPackageName()));
            pill = root.findViewById(root.getResources().getIdentifier("indicator", "id", root.getContext().getPackageName()));
            mini = MiniBar.current();
            return;
        }
        boolean hide = way > 0;
        if (hide == barsHidden || hide && (nav == null || !nav.isShown())) return;
        barsHidden = hide;
        pulled = 0;
        for (View bar : new View[] {nav, pill, mini}) {
            if (bar == null) continue;
            int[] at = new int[2];
            bar.getLocationOnScreen(at);
            float by = hide ? root.getHeight() - at[1] + 16 * dp : 0;
            if (Wave.animationsEnabled()) bar.animate().translationY(by).setDuration(hide ? 240 : 320).start();
            else bar.setTranslationY(by);
        }
    }

    private final Runnable scan = new Runnable() {
        public void run() {
            scanning = false;
            if (Settings.get(Settings.LIST_CARDS)) RowCards.scan(root);
        }
    };

    /** Lists appear whenever a page or result arrives, so layouts ask for a look, at most every half second. */
    public void onGlobalLayout() {
        if (scanning) return;
        scanning = true;
        MAIN.postDelayed(scan, 500);
    }

    private void start() {
        if (running || !Wave.animationsEnabled() || !holdOn() && !glowOn() && !rippling()) return;
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
        running = down || glowAlpha > 0 || rippling();
        if (running) root.postDelayed(this, FRAME);
    }

    private boolean rippling() {
        return SystemClock.uptimeMillis() - rippleAt < RIPPLE_MILLIS;
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
        float ripple = (SystemClock.uptimeMillis() - rippleAt) / (float) RIPPLE_MILLIS;
        if (ripple < 1) {
            float eased = 1 - (1 - ripple) * (1 - ripple);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((3 - 2 * ripple) * dp);
            paint.setColor(Tint.color(Settings.RIPPLE_COLOR));
            paint.setAlpha(Math.round(120 * (1 - ripple)));
            c.drawCircle(rippleX, rippleY, (10 + 44 * eased) * dp, paint);
        }
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
