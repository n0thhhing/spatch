package spatch;

import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lists built as RecyclerViews (search results, albums, artist pages) hold one view per row, so each
 * row can wear the same card as Library rows. The card measures itself: headings and shelves are the
 * wrong height and draw nothing, so it can sit behind every child. Pressing a row or grid card tilts
 * it in 3D with a fast diagonal specular glint across its surface.
 */
public final class RowCards extends Drawable implements ViewGroup.OnHierarchyChangeListener, View.OnTouchListener {
    private static final float MIN = 48, MAX = 100, SIDES = 8, ENDS = 2.5f, RADIUS = 16, MAX_TILT = 4.5f; // dp, degrees
    private final float dp;
    private final boolean queue;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF();
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final float[] tiltX = new float[2], tiltY = new float[2];
    private float targetX, targetY, touchX = -1, touchY = -1;
    private boolean pressed;
    private long fillingUntil, glintAt = -GLINT_MILLIS, drawnAt;
    private int filled;
    private static final long FILL_MILLIS = 350, GLINT_MILLIS = 380;
    private static final int RISE_ROWS = 10;
    private static final float RISE = 22; // dp
    private static final long SHIMMER_MILLIS = 2800;

    RowCards(float dp, boolean queue) {
        this.dp = dp;
        this.queue = queue;
    }

    /** A row's height decides whether it gets a card, and it must span most of its list. */
    static boolean fits(float heightDp, float width, float listWidth) {
        return heightDp >= MIN && heightDp <= MAX && width >= listWidth * .9f;
    }

    /** Multi-column album or library grid cards: taller than a row and at most two-thirds of the list wide. */
    static boolean fitsGrid(float heightDp, float widthDp, float width, float listWidth) {
        return heightDp > MAX && heightDp <= 280 && widthDp >= 110 && width <= listWidth * .65f;
    }

    /** Subtle 3D lean {rotateX, rotateY} toward the pressed point on a card. */
    static float[] tiltTarget(float x, float y, float width, float height) {
        if (width <= 0 || height <= 0) return new float[] {0f, 0f};
        float hw = width / 2f, hh = height / 2f;
        return new float[] {Math.max(-1f, Math.min(1f, (y - hh) / hh)) * -MAX_TILT, Math.max(-1f, Math.min(1f, (x - hw) / hw)) * MAX_TILT};
    }

    /** Leading edge of the diagonal specular sheen as it sweeps from left to right over t in 0..1. */
    static float glintX(float left, float right, float band, float t) {
        t = Math.max(0f, Math.min(1f, t));
        float ease = 1f - (1f - t) * (1f - t);
        return left - band + (right - left + band * 1.6f) * ease;
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
                if (Settings.get(Settings.LIST_CARDS) || Settings.get(Settings.CARD_GLINT)) scan(decor);
                Fonts.cover(decor);
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
        if (view.getClass().getName().endsWith("RecyclerView") && !JOINED.containsKey(view) && !tiles(view)) {
            list(group, false);
        }
        for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i));
    }

    /** Search's browse page is a grid of category tiles, already cards: a card behind a row of them shows between. */
    private static boolean tiles(View list) {
        if (list.getId() == View.NO_ID) return false;
        try {
            return "browse_recycler_view".equals(list.getResources().getResourceEntryName(list.getId()));
        } catch (RuntimeException unnamed) {
            return false;
        }
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
        RowCards rowCard = new RowCards(dp, queue);
        child.setOnTouchListener(rowCard);
        Drawable was = child.getBackground();
        if (was == null) child.setBackground(rowCard);
        else if (!(was instanceof android.graphics.drawable.LayerDrawable)) { // rows keep their press ripple
            child.setBackground(null); // releases it, so the layer can take it without a second owner
            child.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[] {rowCard, was}));
        }
    }

    public void onChildViewRemoved(View parent, View child) {}

    public boolean onTouch(View v, MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            touchX = e.getX();
            touchY = e.getY();
            if (action == MotionEvent.ACTION_DOWN) setPressedState(true, v.getWidth(), v.getHeight());
            else if (pressed) aimTilt(v.getWidth(), v.getHeight());
            v.invalidate();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            setPressedState(false, v.getWidth(), v.getHeight());
            v.invalidate();
        }
        return false;
    }

    public boolean isStateful() {
        return true;
    }

    protected boolean onStateChange(int[] state) {
        boolean down = false;
        if (state != null) for (int s : state) if (s == 0x010100a7) down = true;
        if (down == pressed) return false;
        Rect b = getBounds();
        if (down && touchX < 0) {
            touchX = b.width() * 0.72f;
            touchY = b.height() * 0.28f;
        }
        setPressedState(down, b.width(), b.height());
        return true;
    }

    private void setPressedState(boolean down, float w, float h) {
        if (down && !pressed && Settings.get(Settings.CARD_GLINT) && Wave.animationsEnabled()) {
            glintAt = android.os.SystemClock.uptimeMillis();
        }
        pressed = down;
        aimTilt(w, h);
    }

    private void aimTilt(float w, float h) {
        if (pressed && Settings.get(Settings.CARD_GLINT) && Wave.animationsEnabled()) {
            float[] t = tiltTarget(touchX, touchY, w, h);
            targetX = t[0];
            targetY = t[1];
        } else {
            targetX = targetY = 0;
        }
    }

    public void draw(Canvas canvas) {
        Rect b = getBounds();
        Object owner = getCallback();
        while (owner instanceof Drawable) owner = ((Drawable) owner).getCallback();
        View parent = owner instanceof View && ((View) owner).getParent() instanceof View ? (View) ((View) owner).getParent() : null;
        if (parent == null) return;
        boolean row = fits(b.height() / dp, b.width(), parent.getWidth());
        boolean grid = !row && fitsGrid(b.height() / dp, b.width() / dp, b.width(), parent.getWidth());
        if (!row && !grid) return;
        float padX = (row ? SIDES : ENDS) * dp, padY = ENDS * dp;
        card.set(b.left + padX, b.top + padY, b.right - padX, b.bottom - padY);
        long now = android.os.SystemClock.uptimeMillis();
        boolean active = false;
        if (Settings.get(Settings.CARD_GLINT) && Wave.animationsEnabled()) {
            long dt = drawnAt == 0 ? 16 : now - drawnAt;
            PlayerArt.spring(tiltX, targetX, dt);
            PlayerArt.spring(tiltY, targetY, dt);
            if (Math.abs(tiltX[0] - targetX) > 0.02f || Math.abs(tiltY[0] - targetY) > 0.02f || Math.abs(tiltX[1]) > 0.02f || Math.abs(tiltY[1]) > 0.02f) active = true;
            else {
                tiltX[0] = targetX;
                tiltY[0] = targetY;
                tiltX[1] = tiltY[1] = 0;
            }
        } else {
            tiltX[0] = tiltY[0] = tiltX[1] = tiltY[1] = targetX = targetY = 0;
        }
        drawnAt = now;
        int save = canvas.save();
        if (tiltX[0] != 0 || tiltY[0] != 0) {
            float cx = (card.left + card.right) / 2f, cy = (card.top + card.bottom) / 2f;
            camera.save();
            camera.rotateX(tiltX[0]);
            camera.rotateY(tiltY[0]);
            camera.getMatrix(matrix);
            camera.restore();
            matrix.preTranslate(-cx, -cy);
            matrix.postTranslate(cx, cy);
            canvas.concat(matrix);
        }
        if ((row || grid) && Settings.get(Settings.LIST_CARDS)) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Palette.color(0xff2b2640));
            float r = (grid ? 18 : RADIUS) * dp;
            canvas.drawRoundRect(card, r, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f * dp);
            paint.setColor(Palette.color(0x18ffffff));
            canvas.drawRoundRect(card, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        if (Settings.get(Settings.CARD_GLINT) && Wave.animationsEnabled() && now - glintAt >= 0 && now - glintAt < GLINT_MILLIS) {
            glint(canvas, (now - glintAt) / (float) GLINT_MILLIS);
            active = true;
        }
        if (row && !Wave.paused && Wave.animationsEnabled() && shows((View) owner, SpatchWidget.nowTitle)) {
            if (queue && Settings.get(Settings.QUEUE_SHIMMER)) shimmer(canvas, (View) owner);
            if (Settings.get(Settings.ROW_EQUALIZER)) drawMiniEq(canvas, (View) owner);
        }
        canvas.restoreToCount(save);
        if (active && owner instanceof View) ((View) owner).postInvalidateDelayed(16);
    }

    private void drawMiniEq(Canvas canvas, View row) {
        float h = 14 * dp, barW = 2.2f * dp, gap = 2f * dp;
        float right = card.right - 14 * dp, cy = (card.top + card.bottom) / 2f, bottom = cy + h / 2f;
        long now = android.os.SystemClock.uptimeMillis();
        int accent = Tint.album();
        if (accent == 0 || (accent & 0xffffff) == 0) accent = Palette.color(0xffd0bcff);
        paint.setColor(accent);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        int[] bands = {1, 5, 10, 16};
        for (int i = 0; i < 4; i++) {
            float energy = Spectrum.alpha * (Spectrum.bands != null && Spectrum.bands.length > bands[i] ? Spectrum.bands[bands[i]] : 0f);
            float level = Math.max(0.2f, Math.min(1f, energy + 0.16f * (float) Math.sin(now * 0.009 + i * 1.3)));
            float barH = Math.max(2.5f * dp, h * level);
            float x = right - (4 - i) * (barW + gap);
            canvas.drawRoundRect(x, bottom - barH, x + barW, bottom, barW / 2f, barW / 2f, paint);
        }
        row.postInvalidateDelayed(33);
    }

    private void glint(Canvas canvas, float t) {
        float width = card.right - card.left, height = card.bottom - card.top, band = Math.max(height, width * 0.32f);
        float x = glintX(card.left, card.right, band, t);
        int peak = (Math.round(0x38 * (1f - t * 0.6f)) << 24) | 0xffffff;
        paint.setColor(0xffffffff);
        paint.setShader(new LinearGradient(x, card.top, x + band * 0.7f, card.bottom, new int[] {0x00ffffff, peak, 0x00ffffff}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card, RADIUS * dp, RADIUS * dp, paint);
        paint.setShader(null);
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
