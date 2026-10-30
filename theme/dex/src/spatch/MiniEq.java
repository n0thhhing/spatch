package spatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Tiny equalizer on the mini-player artwork. Bars are the shared capture's real levels; paused, silent,
 * remote or denied capture all rest at the same quiet height. About 30fps, and only while it can move.
 */
public final class MiniEq extends View {
    private static final int BARS = 5;
    private static final long FRAME = 33;
    private static final int[] BAND = {1, 5, 10, 15, 20}; // bass to treble in Spectrum's 24 bands
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] shown = new float[BARS];
    private int color = Palette.color(0xffd0bcff), target = color;
    private boolean playing;
    private long drawn;

    public MiniEq(Context c, AttributeSet a) {
        super(c, a);
        chip.setColor(Palette.color(0xd91d1a2a));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void show(boolean isPlaying, int accent) {
        playing = isPlaying;
        target = accent;
        setVisibility(Settings.get(Settings.MINI_VISUALIZER) ? View.VISIBLE : View.GONE);
        invalidate();
    }

    private boolean live() {
        return playing && isShown() && hasWindowFocus() && Wave.animationsEnabled() && !Settings.reduceMotion();
    }

    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        boolean live = live();
        if (live) {
            AudioSpectrum.request(getContext(), AudioSpectrum.MINI, true);
            Spectrum.step(AudioSpectrum.SHARED.levels, AudioSpectrum.session(), now, true, Settings.vizSmooth());
        } else {
            AudioSpectrum.drop(AudioSpectrum.MINI);
        }
        boolean real = live && AudioSpectrum.SHARED.status == null && Spectrum.seeded;
        float dt = Math.min(1f, (now - drawn) / 120f);
        drawn = now;
        boolean moving = false;
        for (int i = 0; i < BARS; i++) {
            float to = real ? Spectrum.alpha * Spectrum.bands[BAND[i]] : 0;
            shown[i] += (to - shown[i]) * dt;
            moving |= Math.abs(to - shown[i]) > 0.01f;
        }
        if (color != target) {
            color = Pill.blend(color, target, dt);
            moving = true;
        }
        float w = getWidth(), h = getHeight(), radius = h / 3;
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, radius, radius, chip);
        paint.setColor(color);
        float d = getResources().getDisplayMetrics().density, bar = 1.5f * d, gap = (w - 6 * d - BARS * bar) / (BARS - 1);
        float low = 2 * d, max = h - 6 * d;
        for (int i = 0; i < BARS; i++) {
            float x = 3 * d + i * (bar + gap), height = low + (max - low) * Math.min(1, shown[i]);
            rect.set(x, (h - height) / 2, x + bar, (h + height) / 2);
            canvas.drawRoundRect(rect, bar / 2, bar / 2, paint);
        }
        if (live || moving) postInvalidateDelayed(FRAME);
    }

    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != 0) AudioSpectrum.drop(AudioSpectrum.MINI);
        else invalidate();
    }

    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) AudioSpectrum.drop(AudioSpectrum.MINI);
        else invalidate();
    }

    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AudioSpectrum.drop(AudioSpectrum.MINI);
    }

    /** The most colourful artwork pixel, lifted until it reads against the surface; fallback if the art is grey. */
    static int accent(int[] pixels, int surface, int fallback) {
        float best = 0.12f;
        int pick = fallback;
        for (int p : pixels) {
            int r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            float score = (max - min) / 255f * (0.4f + 0.6f * max / 255f);
            if (score > best) {
                best = score;
                pick = 0xff000000 | p;
            }
        }
        for (int i = 0; i < 12 && contrast(pick, surface) < 3; i++) pick = Pill.blend(pick, 0xffffffff, 0.15f);
        return pick;
    }

    private static double lum(int c) {
        return 0.2126 * channel(c >> 16 & 255) + 0.7152 * channel(c >> 8 & 255) + 0.0722 * channel(c & 255);
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    static double contrast(int a, int b) {
        double x = lum(a), y = lum(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }
}
