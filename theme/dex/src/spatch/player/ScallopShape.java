package spatch;

import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import p.S_ComposeOutline;
import p.S_ComposeShape;
import p.S_ComposeMutableState;
import p.S_ComposeLayoutDirection;
import p.S_ComposeAndroidPath;
import p.S_ComposeGenericOutline;
import p.S_ComposeDensity;
import p.S_ComposePathFactory;
import p.S_ComposeSnapshot;

/**
 * Compose Shape with the player's play-button contour (first bump at 12 o'clock; same curve as Scallop),
 * for Compose play buttons that would otherwise be circles. live() is called while f1p1.m composes, with
 * the state Spotify is showing: five bumps paused, nine and turning while playing. A frame tick only
 * continues while something composes it, so a button that scrolled away or went to the background stops.
 */
public final class ScallopShape implements S_ComposeShape {
    private static final int STEPS = 180;
    static final int PLAYING = 1; // f1p1.m's first state, the pause glyph
    private static final float TURN_MILLIS = 12000f, MORPH_MILLIS = 440f;
    private static final long FRAME = 16, STALE = 250;
    private static final S_ComposeMutableState TICK = S_ComposeSnapshot.q(Long.valueOf(0L));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable FRAMES = new Runnable() {
        public void run() {
            long now = SystemClock.uptimeMillis();
            ticking = now - composedAt < STALE;
            if (!ticking) return;
            TICK.setValue(Long.valueOf(now));
            MAIN.postDelayed(this, FRAME);
        }
    };
    private static float shown = -1, from, target;
    private static long changedAt, composedAt, lastFrame;
    private static double spin;
    private static boolean ticking;

    private final float mix;
    private final double angle;

    private ScallopShape(float mix, double angle) {
        this.mix = mix;
        this.angle = angle;
    }

    public static S_ComposeShape live(int state) {
        TICK.getValue(); // subscribes this composition to the tick
        long now = SystemClock.uptimeMillis();
        float wanted = state == PLAYING ? 1 : 0;
        if (shown < 0) shown = from = target = wanted;
        if (wanted != target) {
            from = shown;
            target = wanted;
            changedAt = now;
        }
        boolean motion = Settings.playlistMotion();
        float progress = motion && Settings.playMorph() ? Math.min(1f, (now - changedAt) / MORPH_MILLIS) : 1;
        shown = morph(from, target, progress);
        if (motion && wanted == 1 && lastFrame != 0 && Settings.playSpin()) spin = (spin + Math.min(80, now - lastFrame) * Math.PI * 2 / TURN_MILLIS) % (Math.PI * 2);
        lastFrame = now;
        composedAt = now;
        if (motion && (wanted == 1 && Settings.playSpin() || progress < 1) && !ticking) {
            ticking = true;
            MAIN.postDelayed(FRAMES, FRAME);
        }
        return new ScallopShape(shown, spin);
    }

    static float morph(float from, float to, float progress) {
        return from + (to - from) * progress * progress * (3 - 2 * progress);
    }

    public S_ComposeOutline a(long size, S_ComposeLayoutDirection direction, S_ComposeDensity density) {
        float w = Float.intBitsToFloat((int) (size >> 32)), h = Float.intBitsToFloat((int) size);
        S_ComposeAndroidPath outline = S_ComposePathFactory.a();
        trace(outline.a, w, h, mix, angle);
        return new S_ComposeGenericOutline(outline);
    }

    static void trace(Path path, float w, float h) {
        trace(path, w, h, 0, 0);
    }

    private static void trace(Path path, float w, float h, float mix, double angle) {
        float r = Math.min(w, h) / 2f, depth = r * 0.075f, base = r - depth, cx = w / 2f, cy = h / 2f;
        if (!Settings.shapes()) {
            path.addCircle(cx, cy, r, Path.Direction.CW);
            return;
        }
        for (int i = 0; i <= STEPS; i++) {
            double t = 2 * Math.PI * i / STEPS;
            float rad = base + depth * ((1 - mix) * (float) Math.cos(5 * t) + mix * (float) Math.cos(9 * t));
            float x = cx + rad * (float) Math.sin(t + angle), y = cy - rad * (float) Math.cos(t + angle);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
    }
}
