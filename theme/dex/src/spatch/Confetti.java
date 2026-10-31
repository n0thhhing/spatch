package spatch;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.Random;

/**
 * Shapes thrown from a play button. Drawn in the window's overlay so they can fly past the button and
 * the view holding it; each burst removes itself, so nothing keeps running afterwards.
 */
final class Confetti extends Drawable implements ValueAnimator.AnimatorUpdateListener {
    private static final long MILLIS = 780;
    private static final int MAX_LIVE = 4, BASE_COUNT = 12;
    private static final float REACH = 44, SPREAD = 52, SIZE = 5, SIZE_SPREAD = 6, FALL = 58; // dp
    private static final int COOKIE = 0, SUN = 1, CLOVER = 2, HEART = 3, STAR = 4, DOT = 5;
    private static final Path[] SHAPES = new Path[6];
    private static int live;

    private final View root;
    private final float x, y, dp;
    private final float[] angle, reach, size, spin;
    private final int[] shape, color;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t;

    private Confetti(View root, float x, float y) {
        this.root = root;
        this.x = x;
        this.y = y;
        dp = root.getResources().getDisplayMetrics().density;
        int count = count(Settings.choice(Settings.BURST_AMOUNT));
        float scale = scale(Settings.choice(Settings.BURST_SIZE));
        Random random = new Random();
        angle = new float[count];
        reach = new float[count];
        size = new float[count];
        spin = new float[count];
        shape = new int[count];
        color = new int[count];
        int[] tones = tones(random);
        int[] shapes = shapes(Settings.choice(Settings.BURST_SHAPE));
        for (int i = 0; i < count; i++) {
            angle[i] = (float) (2 * Math.PI * (i + random.nextFloat() * 0.7f) / count);
            reach[i] = (REACH + SPREAD * random.nextFloat()) * dp;
            size[i] = (SIZE + SIZE_SPREAD * random.nextFloat()) * dp * scale;
            spin[i] = (random.nextBoolean() ? 1 : -1) * (140 + 300 * random.nextFloat());
            shape[i] = shapes[random.nextInt(shapes.length)];
            color[i] = tones[random.nextInt(tones.length)];
        }
    }

    static int count(String amount) {
        return "few".equals(amount) ? BASE_COUNT / 2 : "lots".equals(amount) ? BASE_COUNT * 2 : BASE_COUNT;
    }

    static float scale(String size) {
        return "small".equals(size) ? 0.7f : "big".equals(size) ? 1.5f : 1f;
    }

    static int[] shapes(String name) {
        if ("cookies".equals(name)) return new int[] {COOKIE, SUN, CLOVER};
        if ("hearts".equals(name)) return new int[] {HEART};
        if ("stars".equals(name)) return new int[] {STAR};
        if ("dots".equals(name)) return new int[] {DOT};
        return new int[] {COOKIE, SUN, CLOVER, HEART, STAR, DOT};
    }

    private static int[] tones(Random random) {
        String tones = Settings.choice(Settings.BURST_TONES);
        int base = Tint.color(Settings.BURST_COLOR);
        if ("solid".equals(tones)) return new int[] {base};
        if ("rainbow".equals(tones)) return new int[] {0xfff2a1b8, 0xffffc777, 0xff8ee6c4, 0xff8fc7ff, 0xffcba6f7};
        return new int[] {base, Pill.blend(base, 0xffffffff, 0.45f), Palette.color(0xffb7a3ee)};
    }

    /** Out fast then easing off, with gravity pulling it down: {x, y} for a unit direction. */
    static float[] place(float t, float dx, float dy, float reach, float fall) {
        float travel = 1 - (1 - t) * (1 - t) * (1 - t);
        return new float[] {dx * reach * travel, dy * reach * travel + fall * t * t};
    }

    /** Pops in, shrinks slowly, and gone by the end. */
    static float pieceScale(float t) {
        return t < 0.15f ? t / 0.15f : 1 - 0.5f * (t - 0.15f) / 0.85f;
    }

    static float pieceAlpha(float t) {
        return t < 0.6f ? 1 : Math.max(0, 1 - (t - 0.6f) / 0.4f);
    }

    /** A button's click; wasPlaying is what it showed before this click. */
    static void clicked(View button, boolean wasPlaying, int where) {
        int[] at = new int[2];
        button.getLocationOnScreen(at);
        wanted(button, at[0] + button.getWidth() / 2f, at[1] + button.getHeight() / 2f, !wasPlaying, where);
    }

    /** Where a Compose button or the immersive player reports only a point. */
    static void wanted(View host, float x, float y, boolean starting, int where) {
        if (Settings.get(Settings.BURST) && Settings.get(where) && (starting || !"play".equals(Settings.choice(Settings.BURST_WHEN)))) {
            at(host, x, y);
        }
    }

    /** Always bursts, for the settings preview. */
    static void at(View host, float x, float y) {
        if (!Wave.animationsEnabled() || live >= MAX_LIVE) return;
        final View root = host.getRootView();
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        Confetti burst = new Confetti(root, x - origin[0], y - origin[1]);
        burst.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(burst);
        live++;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(MILLIS);
        animator.addUpdateListener(burst);
        animator.start();
    }

    public void onAnimationUpdate(ValueAnimator a) {
        t = ((Float) a.getAnimatedValue()).floatValue();
        if (t >= 1) {
            root.getOverlay().remove(this);
            live--;
        } else {
            invalidateSelf();
        }
    }

    public void draw(Canvas c) {
        float grow = pieceScale(t), fall = FALL * dp;
        paint.setAlpha(255);
        for (int i = 0; i < angle.length; i++) {
            float[] p = place(t, (float) Math.cos(angle[i]), (float) Math.sin(angle[i]), reach[i], fall);
            float s = size[i] * grow;
            int save = c.save();
            c.translate(x + p[0], y + p[1]);
            c.rotate(spin[i] * t, 0, 0);
            c.scale(s, s, 0, 0);
            paint.setColor(color[i]);
            paint.setAlpha(Math.round(255 * pieceAlpha(t)));
            c.drawPath(shape(shape[i]), paint);
            c.restoreToCount(save);
        }
    }

    private static Path shape(int kind) {
        if (SHAPES[kind] == null) SHAPES[kind] = trace(kind);
        return SHAPES[kind];
    }

    /** Unit-sized outlines from polar curves, so a piece stays crisp at any size. */
    private static Path trace(int kind) {
        Path path = new Path();
        int steps = 72;
        for (int i = 0; i <= steps; i++) {
            double a = 2 * Math.PI * i / steps;
            double r = kind == COOKIE ? 1 + 0.12 * Math.cos(7 * a)
                : kind == SUN ? 1 + 0.22 * Math.signum(Math.cos(8 * a)) * Math.abs(Math.cos(8 * a))
                : kind == CLOVER ? 0.72 + 0.34 * Math.abs(Math.cos(2 * a))
                : kind == STAR ? 0.62 + 0.42 * Math.pow(Math.abs(Math.cos(2.5 * a)), 3)
                : 0.8;
            float px = (float) (r * Math.sin(a)), py = (float) (-r * Math.cos(a));
            if (kind == HEART) { // the classic parametric heart, scaled to about one unit
                double s = Math.sin(a);
                px = (float) (16 * s * s * s / 16);
                py = (float) (-(13 * Math.cos(a) - 5 * Math.cos(2 * a) - 2 * Math.cos(3 * a) - Math.cos(4 * a)) / 16);
            }
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        path.close();
        return path;
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
