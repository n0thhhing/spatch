package spatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

/**
 * Mini-player cover (layout/now_playing_bar cover_image): a new track's artwork pops in from 70%
 * with a small overshoot instead of swapping in place, and a sideways fling spins the old disc off
 * the bar while the incoming disc drops in with an elastic overshoot.
 */
public final class MiniArt extends ImageView {
    static final long POP_MS = 560, FLING_MS = 360;
    static int live;
    private long popAt = -POP_MS, dropAt = -POP_MS;

    public MiniArt(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setImageDrawable(Drawable art) {
        if (Settings.get(Settings.MINI_ALBUM)) Tint.follow(art);
        Drawable old = getDrawable();
        super.setImageDrawable(art);
        long now = SystemClock.uptimeMillis();
        if (art != null && old != art && now - dropAt < POP_MS && Wave.animationsEnabled()) {
            dropAt = popAt = now;
            invalidate();
        } else if (old != null && art != null && old != art && now - popAt > POP_MS && Wave.animationsEnabled() && Settings.miniPop()) {
            // Placeholder-then-image loads arrive in quick succession; pop once per track change.
            popAt = now;
            invalidate();
        }
    }

    void fling(float dx, float deg) {
        int[] at = new int[2];
        getLocationOnScreen(at);
        float size = Math.max(getWidth(), 36 * getResources().getDisplayMetrics().density);
        flingAt(this, getDrawable(), at[0] + getWidth() / 2f, at[1] + getHeight() / 2f, size, dx, deg);
        dropAt = popAt = SystemClock.uptimeMillis();
        invalidate();
    }

    static void flingAt(View host, Drawable art, float x, float y, float size, float dx, float deg) {
        if (!Settings.get(Settings.MINI_FLING) || !Wave.animationsEnabled() || !host.isShown() || live >= 2) return;
        View root = host.getRootView();
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        Fling f = new Fling(root, art, x - origin[0], y - origin[1], size, dx, deg);
        f.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(f);
        live++;
        f.run();
    }

    protected void onDraw(Canvas canvas) {
        long t = SystemClock.uptimeMillis() - popAt;
        if (t >= POP_MS) {
            super.onDraw(canvas);
            drawVinyl(canvas);
            return;
        }
        float[] step = dropStep(t / (float) POP_MS, getHeight(), popAt == dropAt);
        int save = canvas.save();
        canvas.translate(0, step[1]);
        canvas.scale(step[0], step[0], getWidth() / 2f, getHeight() / 2f);
        super.onDraw(canvas);
        drawVinyl(canvas);
        canvas.restoreToCount(save);
        postInvalidateDelayed(16);
    }

    private void drawVinyl(Canvas canvas) {
        if (!Settings.get(Settings.VINYL_EFFECT) || (!Settings.get(Settings.MINI_SPIN) && !"circle".equals(Settings.choice(Settings.ART_SHAPE)))) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f, radius = Math.min(cx, cy) * 0.94f;
        if (radius <= 0) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        for (float r = radius * 0.52f; r < radius * 0.95f; r += radius * 0.14f) {
            p.setColor(0x18ffffff);
            canvas.drawCircle(cx, cy, r, p);
        }
        p.setStyle(Paint.Style.FILL);
        long now = SystemClock.uptimeMillis();
        float rot = ((now % 10000) * 0.036f) % 360f;
        p.setShader(new android.graphics.SweepGradient(cx, cy,
            new int[] {0x00ffffff, 0x18ffffff, 0x00ffffff, 0x00ffffff, 0x18ffffff, 0x00ffffff},
            new float[] {0f, 0.12f, 0.25f, 0.5f, 0.62f, 0.75f}));
        int s = canvas.save();
        canvas.rotate(rot, cx, cy);
        canvas.drawCircle(cx, cy, radius, p);
        canvas.restoreToCount(s);
    }

    /** {scale, dy} for the incoming cover; a fling-skip drops in with a stronger elastic overshoot. */
    static float[] dropStep(float t, float size, boolean drop) {
        float over = outBack(t, drop ? 2.8f : 2.4f);
        return new float[] {drop ? 0.52f + 0.48f * over : 0.7f + 0.3f * over, drop ? (1f - over) * -size * 0.42f : 0f};
    }

    /** {cx, cy, deg, scale, alpha} as the flung miniature disc spins off the bar with momentum. */
    static float[] flingStep(float x, float y, float dx, float deg, float size, float t) {
        t = Math.max(0f, Math.min(1f, t));
        float dir = dx < 0 ? -1f : 1f, ease = 1f - (1f - t) * (1f - t);
        float travel = Math.max(size * 2.2f, Math.abs(dx) * 1.6f) * ease;
        return new float[] {x + dx + dir * travel, y + size * 0.28f * t * t, deg + dir * 480f * t, 1f - 0.2f * t, 1f - t * t};
    }

    /** Ease out with overshoot s past the end before settling. */
    static float outBack(float t, float s) {
        t = Math.max(0f, Math.min(1f, t)) - 1;
        return 1 + (s + 1) * t * t * t + s * t * t;
    }

    static final class Fling extends Drawable implements Runnable {
        private final View root;
        private final Drawable art;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final float x, y, size, dx, deg;
        private final long started = SystemClock.uptimeMillis();
        private boolean finished;

        Fling(View root, Drawable art, float x, float y, float size, float dx, float deg) {
            this.root = root;
            this.art = art;
            this.x = x;
            this.y = y;
            this.size = size;
            this.dx = dx;
            this.deg = deg;
        }

        public void run() {
            if (finished) return;
            if (SystemClock.uptimeMillis() - started >= FLING_MS || !Wave.animationsEnabled() || !Settings.get(Settings.MINI_FLING) || !root.isShown()) {
                finish();
                return;
            }
            invalidateSelf();
            root.postDelayed(this, 16);
        }

        void finish() {
            if (finished) return;
            finished = true;
            root.removeCallbacks(this);
            root.getOverlay().remove(this);
            live--;
        }

        public void draw(Canvas c) {
            float t = Math.max(0f, Math.min(1f, (SystemClock.uptimeMillis() - started) / (float) FLING_MS));
            float[] s = flingStep(x, y, dx, deg, size, t);
            int alpha = Math.round(255 * s[4]);
            if (alpha <= 0) return;
            float r = size / 2f;
            int save = c.save();
            c.translate(s[0], s[1]);
            c.rotate(s[2], 0, 0);
            c.scale(s[3], s[3], 0, 0);
            paint.setColor(Palette.color(0xff1d1a2a));
            paint.setAlpha(alpha);
            c.drawCircle(0, 0, r, paint);
            if (art != null) {
                clip.reset();
                clip.addCircle(0, 0, r, Path.Direction.CW);
                c.clipPath(clip);
                int layer = alpha < 255 ? c.saveLayerAlpha(-r, -r, r, r, alpha) : c.save();
                PlayerArt.drawArtwork(c, art, -r, -r, size, size);
                c.restoreToCount(layer);
            }
            c.restoreToCount(save);
        }

        public void setAlpha(int alpha) {}
        public void setColorFilter(ColorFilter filter) {}
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
