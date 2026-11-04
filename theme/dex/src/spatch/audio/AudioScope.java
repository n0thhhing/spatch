package spatch;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** An FFT-driven Lissajous decoration, not a capture of stereo phase. No per-frame allocations. */
final class AudioScope {
    static float energy(float[] bands) {
        float sum = 0;
        for (float value : bands) sum += value;
        return Math.max(0, Math.min(1, sum / (bands.length * Spectrum.CEILING)));
    }

    static void trace(Path path, float left, float top, float right, float bottom, float energy, float phase, boolean loop) {
        path.reset();
        for (int i = 0; i <= 96; i++) {
            float t = i/96f;
            double a = t*Math.PI*2;
            float x, y;
            if (loop) {
                x = .5f + .45f*energy*(float)Math.sin(a+phase*.3f);
                y = .5f + .45f*energy*((1-energy)*(float)Math.sin(2*a+phase)+energy*(float)Math.sin(3*a-phase*.5f));
            } else {
                float taper = i == 0 || i == 96 ? 0 : (float)Math.sin(Math.PI*t);
                x = Math.max(0, Math.min(1, t+.06f*energy*taper*(float)Math.sin(2*a+phase)));
                y = .5f + .45f*energy*taper*((1-energy)*(float)Math.sin(3*a+phase)+energy*(float)Math.sin(5*a-phase));
            }
            // A silent scope rests as a line instead of a dot.
            if (loop && energy == 0) x = t;
            x = left + (right-left)*x; y = top + (bottom-top)*y;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        if (loop && energy > 0) path.close();
    }

    static void draw(Canvas c, Paint paint, Path path, float left, float top, float right, float bottom, float energy, float phase, boolean loop, float dp) {
        if (right <= left || bottom <= top) return;
        float glow = Math.min(5*dp, Math.min(right-left, bottom-top)*.5f), pad = glow/2;
        trace(path, left+pad, top+pad, right-pad, bottom-pad, energy, phase, loop);
        int alpha = paint.getAlpha(), color = paint.getColor();
        Paint.Style style = paint.getStyle();
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(glow);
        paint.setAlpha(Math.round(alpha*.25f));
        c.drawPath(path, paint);
        paint.setStrokeWidth(Math.min(1.5f*dp, glow*.4f));
        paint.setColor(Pill.blend(color, 0xffffffff, .35f));
        paint.setAlpha(Math.round(alpha*.95f));
        c.drawPath(path, paint);
        paint.setColor(color); paint.setAlpha(alpha); paint.setStyle(style);
    }
}
