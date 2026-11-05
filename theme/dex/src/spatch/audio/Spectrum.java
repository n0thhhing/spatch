package spatch;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * Shared spectrum model and renderers. The model turns AudioSpectrum's raw bands into display
 * levels: eased, with a per-band plus shared auto-gain. Its gain state is seeded from the first valid
 * frame (not from zero, which read as every bar at full height until it drifted: the startup dome)
 * and survives reopening the player while the same audio session lives.
 */
final class Spectrum {
    static final int BANDS = 24;
    static final float CEILING = 1.12f;
    /** Below this mean level a frame is silence: nothing is learned from it. */
    static final float VALID = 0.12f;
    /** Half-width of a seeded per-band range. */
    private static final float SEED = 0.06f;
    private static final float KNEE = 1 - (float) Math.exp(-1.8f); // level 1 at the tracked peak

    static final float[] shown = new float[BANDS]; // raw levels eased per frame
    static final float[] bands = new float[BANDS]; // final display levels, 0..1.12
    private static final float[] lo = new float[BANDS], hi = new float[BANDS];
    private static float gainLo, gainHi = 1;
    static float alpha;
    private static long frame;
    private static int session;
    static boolean seeded;
    static long kickAt;
    static float kickEnergy;
    private static float bassSmooth;
    private static long bassAt;
    private static boolean bassReady;

    private Spectrum() {}

    /** Typical band level relative to the frame mean, averaged from settled music on the device. */
    private static final float[] PRIOR = {
        0f, .02f, .01f, .01f, .03f, .04f, 0f, -.04f, -.03f, -.04f, -.02f, -.03f,
        -.06f, -.05f, .08f, .07f, .04f, .04f, .03f, -.01f, -.03f, -.05f, -.06f, -.14f,
    };

    static float prior(int band) {
        return PRIOR[band];
    }

    static void reset() {
        seeded = false;
        java.util.Arrays.fill(shown, 0);
        java.util.Arrays.fill(bands, 0);
        alpha = 0.8f;
        bassReady = false; kickAt = 0; kickEnergy = 0;
    }

    /** Advances the model one frame. Call once per drawn frame, from one visible renderer per frame. */
    static void step(float[] raw, int audioSession, long now, boolean motion, boolean smooth) {
        if (now >= frame && now - frame < 8) return; // renderers within one frame share one step
        if (audioSession != session) {
            session = audioSession;
            reset();
        }
        float dt = Math.min(64, Math.max(1, now - frame));
        frame = now;
        float rise = motion ? 1 - (float) Math.exp(-dt / (smooth ? 70f : 15f)) : 1;
        float fall = motion ? 1 - (float) Math.exp(-dt / (smooth ? 260f : 60f)) : 1;
        float rawMean = 0;
        for (float v : raw) rawMean += v;
        rawMean /= BANDS;
        if (!seeded && rawMean >= VALID) {
            for (int i = 0; i < BANDS; i++) {
                float centre = rawMean + prior(i);
                lo[i] = centre - SEED;
                hi[i] = centre + SEED;
                shown[i] = raw[i];
                bands[i] = Math.min(CEILING, raw[i] * 1.2f);
            }
            gainLo = rawMean - 0.15f;
            gainHi = rawMean + 0.12f;
            seeded = true;
            alpha = 1f;
        }
        float min = Float.MAX_VALUE, max = 0, sum = 0;
        for (int i = 0; i < BANDS; i++) {
            shown[i] += (raw[i] - shown[i]) * (raw[i] > shown[i] ? rise : fall);
            min = Math.min(min, shown[i]);
            max = Math.max(max, shown[i]);
            sum += shown[i];
        }
        float mean = sum / BANDS;
        boolean learn = seeded && mean >= VALID; // silence never moves the ranges
        if (learn) {
            float k = motion ? 1 - (float) Math.exp(-dt / 1500f) : 1; // shared gain drifts over ~1.5 s
            gainLo += (min - gainLo) * k;
            gainHi += (max - gainHi) * k;
        }
        float range = Math.max(0.12f, gainHi - gainLo);
        // Each band learns its own range (~2.5 s), so music's bass tilt does not always make a dome;
        // blended with the shared gain so quiet treble can't twitch.
        float kBand = motion ? 1 - (float) Math.exp(-dt / 2500f) : 1;
        for (int i = 0; i < BANDS; i++) {
            float v = shown[i];
            if (learn) {
                lo[i] += (v - lo[i]) * (v < lo[i] ? 0.5f : kBand); // new low: follow fast
                hi[i] += (v - hi[i]) * (v > hi[i] ? 0.5f : kBand); // new peak: follow fast
            }
            if (!seeded) {
                bands[i] = 0;
                continue;
            }
            float own = (v - lo[i]) / Math.max(0.15f, hi[i] - lo[i]);
            float x = Math.max(0, 0.65f * own + 0.35f * (v - gainLo) / range);
            // Soft knee instead of a hard clip: peaks above the tracked range stay rounded, not flat.
            bands[i] = Math.min(CEILING, (1 - (float) Math.exp(-1.8f * x)) / KNEE);
        }
        float present = clamp01(mean * 4); // silence or pause: settle and fade out
        alpha = motion ? alpha + (present - alpha) * (1 - (float) Math.exp(-dt / 200f)) : present;
        // The presence fade must not manufacture a kick when playback starts.
        trackBass((bands[0]+bands[1])/(2*CEILING), now, motion && learn && alpha > .05f);
    }

    /** A rising kick, not sustained loudness; one detector shared by every visible renderer. */
    static void trackBass(float level, long now, boolean active) {
        long elapsed = now-bassAt;
        if (!active || !bassReady || elapsed > 500 || elapsed < 0) {
            bassSmooth = level; bassReady = active; kickAt = 0; kickEnergy = 0; bassAt = now;
            return;
        }
        float jump = level-bassSmooth;
        bassSmooth += jump*Math.min(1, elapsed/140f);
        bassAt = now;
        if (jump > .22f && now-kickAt > 240) { kickAt = now; kickEnergy = Math.min(1, jump/.5f); }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Level for a position 0..1 along the row: mirrored puts bass at the centre, treble at the sides. */
    static float at(float[] levels, float pos, boolean mirrored) {
        float t = mirrored ? Math.abs(pos * 2 - 1) : pos; // 0 = bass, 1 = treble
        // Slight asymmetric harmonic shift near the center so the two middle bars don't mirror into identical twins.
        float shift = mirrored ? (pos < 0.5f ? -0.4f : 0.4f) * (1f - t) : 0;
        float f = Math.max(0, Math.min(levels.length - 1, t * (levels.length - 1) + shift));
        int b0 = (int) f, b1 = Math.min(levels.length - 1, b0 + 1);
        float base = levels[b0] + (levels[b1] - levels[b0]) * (f - b0);
        if (mirrored && t < 0.15f) {
            float variance = pos < 0.5f ? 0.94f : 1.06f;
            return Math.min(CEILING, base * variance);
        }
        return base;
    }

    /** Height factor for a position: flat, or easing off slightly at the sides. */
    static float taper(float pos, boolean subtle) {
        return subtle ? 0.8f + 0.2f * (float) Math.sin(Math.PI * pos) : 1f;
    }

    /**
     * Draws `levels` in the selected shape between `left` and `right`, on a floor at `base`, at most
     * `maxH` tall. `floating` rests quiet bars as dots above the floor; otherwise they run off it.
     * The caller sets paint alpha; the shader fade is built here.
     */
    static void draw(Canvas c, Paint paint, Path path, float[] levels, String shape, float left, float right,
                     float base, float maxH, boolean mirrored, boolean thin, boolean floating, boolean taper,
                     float dp) {
        if ("scope".equals(shape)) {
            boolean motion = Wave.animationsEnabled();
            AudioScope.draw(c, paint, path, left, base-maxH, right, base, motion ? AudioScope.energy(levels) : 0,
                motion ? (android.os.SystemClock.uptimeMillis()%120000)/800f : 0, true, dp);
            return;
        }
        int bars = thin ? 48 : 24;
        float step = (right - left) / bars, barW = step * 0.62f;
        boolean centre = "centre".equals(shape), contour = "contour".equals(shape), dots = "dots".equals(shape);
        float mid = base - maxH / 2;
        path.reset();
        if (contour) {
            int points = 48;
            float stepX = (right - left) / (points - 1), px = left, py = base;
            path.moveTo(left, base + 1);
            for (int i = 0; i < points; i++) {
                float pos = i / (points - 1f);
                float x = left + i * stepX, y = base - maxH * at(levels, pos, mirrored) * taper(pos, taper);
                if (i == 0) path.lineTo(x, y);
                else path.quadTo(px, py, (px + x) / 2, (py + y) / 2);
                px = x;
                py = y;
            }
            path.lineTo(right, py);
            path.lineTo(right, base + 1);
            path.close();
        } else {
            for (int i = 0; i < bars; i++) {
                float pos = (i + 0.5f) / bars;
                float h = maxH * at(levels, pos, mirrored) * taper(pos, taper);
                float x = left + i * step + (step - barW) / 2, r = barW / 2;
                float minH = Math.max(dp * 2.5f, barW);
                if (centre) {
                    float half = Math.max(dp * 1.5f, Math.max(r, h / 2));
                    path.addRoundRect(x, mid - half, x + barW, mid + half, r, r, Path.Direction.CW);
                } else if (dots) {
                    float pitch = barW * 1.5f;
                    int count = Math.max(1, Math.round(Math.max(dp * 2f, h) / pitch));
                    float floor = floating ? base - r : base - r - dp;
                    for (int d = 0; d < count; d++) path.addCircle(x + r, floor - d * pitch, r, Path.Direction.CW);
                } else {
                    float barH = Math.max(minH, h);
                    path.addRoundRect(x, base - barH, x + barW, floating ? base : base + barW, r, r, Path.Direction.CW);
                }
            }
        }
        float top = centre ? mid - maxH / 2 : base - maxH;
        float bottom = centre ? mid + maxH / 2 : base;
        int accent = Palette.color(0xffd0bcff) & 0x00ffffff;
        if (barGradient == null || top != gradTop || bottom != gradBottom || accent != gradColor || centre != gradCentre || floating != gradFloating) {
            gradTop = top; gradBottom = bottom; gradColor = accent; gradCentre = centre; gradFloating = floating;
            barGradient = new LinearGradient(0, top, 0, bottom,
                centre ? new int[] {0x66000000 | accent, 0xe6000000 | accent, 0x66000000 | accent}
                       : new int[] {0xe6000000 | accent, (floating ? 0x4d000000 : 0x1a000000) | accent},
                null, Shader.TileMode.CLAMP);
        }
        paint.setShader(barGradient);
        c.drawPath(path, paint);
        paint.setShader(null);
    }

    private static LinearGradient barGradient;
    private static float gradTop, gradBottom;
    private static int gradColor;
    private static boolean gradCentre, gradFloating;

    private static final int RAYS = 72;
    private static final float[] boundary = new float[4], outerX = new float[RAYS], outerY = new float[RAYS], innerX = new float[RAYS], innerY = new float[RAYS];
    private static android.graphics.RadialGradient radial;
    private static float radialX, radialY, radialRadius;
    private static int radialColor;

    /** Bars, dots and a closed corona follow the actual contour's normal, including rounded corners. */
    static void drawArtwork(Canvas c, Paint paint, Path path, float[] levels, String style, String shape, float corner,
                            float cx, float cy, float halfSize, float gap, float depth, float dp) {
        boolean ribbon = "ribbon".equals(style), dots = "dots".equals(style), fine = "fine".equals(style);
        int count = fine ? 120 : RAYS;
        float stroke = fine ? 1.25f*dp : Math.max(2*dp, halfSize*.012f);
        if (fine) { gap = 12*dp; depth = Math.min(9*dp, halfSize*.05f); }
        path.reset();
        for (int i = 0; i < count; i++) {
            float pos = i/(float)count;
            ArtContour.point(shape, 2*Math.PI*pos-Math.PI/2, corner, boundary);
            float energy = at(levels, pos, true);
            if (fine) energy = .08f+.92f*energy*energy;
            float length = depth*energy;
            float x = cx+halfSize*boundary[0]+gap*boundary[2], y = cy+halfSize*boundary[1]+gap*boundary[3];
            float endX = x+(length+(ribbon ? 2*dp : 0))*boundary[2], endY = y+(length+(ribbon ? 2*dp : 0))*boundary[3];
            if (ribbon) {
                innerX[i] = x; innerY[i] = y; outerX[i] = endX; outerY[i] = endY;
            } else if (dots) path.addCircle(endX, endY, stroke, Path.Direction.CW);
            else { path.moveTo(x, y); path.lineTo(endX, endY); }
        }
        if (ribbon) { loop(path, outerX, outerY, false); loop(path, innerX, innerY, true); }
        int color = Tint.color(Settings.PLAYER_COLOR), alpha = paint.getAlpha();
        float radius = halfSize*("circle".equals(shape) || "scallop".equals(shape) ? 1 : 1.42f)+gap+depth*CEILING+2*dp;
        if (radial == null || radialX != cx || radialY != cy || radialRadius != radius || radialColor != color) {
            radial = new android.graphics.RadialGradient(cx, cy, radius, new int[] {color, color & 0xffffff}, new float[] {.7f, 1}, Shader.TileMode.CLAMP);
            radialX = cx; radialY = cy; radialRadius = radius; radialColor = color;
        }
        paint.setColor(color); paint.setAlpha(alpha); paint.setShader(radial);
        paint.setStyle(ribbon || dots ? Paint.Style.FILL : Paint.Style.STROKE);
        paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(path, paint);
        paint.setShader(null); paint.setStyle(Paint.Style.FILL);
    }

    /** Opposite winding leaves the artwork and its gap clear inside the ribbon. */
    private static void loop(Path path, float[] x, float[] y, boolean reverse) {
        int last = reverse ? 0 : RAYS-1, first = reverse ? RAYS-1 : 0;
        path.moveTo((x[last]+x[first])/2, (y[last]+y[first])/2);
        for (int i = 0; i < RAYS; i++) {
            int a = reverse ? RAYS-1-i : i, b = reverse ? (a+RAYS-1)%RAYS : (a+1)%RAYS;
            path.quadTo(x[a], y[a], (x[a]+x[b])/2, (y[a]+y[b])/2);
        }
        path.close();
    }
}
