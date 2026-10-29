package spatch;

import android.graphics.Path;
import p.b971;
import p.bv61;
import p.ikb0;
import p.iw4;
import p.jsq0;
import p.kat;
import p.lw4;

/**
 * Compose Shape with the player's play-button contour (five shallow bumps, first at 12 o'clock;
 * same curve as Scallop when paused), for Compose play buttons that would otherwise be circles.
 */
public final class ScallopShape implements bv61 {
    public static final ScallopShape PLAY = new ScallopShape();
    private static final int STEPS = 180;

    private ScallopShape() {}

    public b971 a(long size, ikb0 direction, kat density) {
        float w = Float.intBitsToFloat((int) (size >> 32)), h = Float.intBitsToFloat((int) size);
        iw4 outline = lw4.a();
        trace(outline.a, w, h);
        return new jsq0(outline);
    }

    static void trace(Path path, float w, float h) {
        float r = Math.min(w, h) / 2f, depth = r * 0.075f, base = r - depth, cx = w / 2f, cy = h / 2f;
        if (!Settings.shapes()) {
            path.addCircle(cx, cy, r, Path.Direction.CW);
            return;
        }
        for (int i = 0; i <= STEPS; i++) {
            double t = 2 * Math.PI * i / STEPS;
            float rad = base + depth * (float) Math.cos(5 * t);
            float x = cx + rad * (float) Math.sin(t), y = cy - rad * (float) Math.cos(t);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
    }
}
