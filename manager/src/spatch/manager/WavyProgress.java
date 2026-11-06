package spatch.manager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.ProgressBar;

/**
 * Material 3 Expressive's wavy progress: the done part is a travelling wave in the primary colour,
 * the rest a flat track with a stop dot. Indeterminate, a wavy segment sweeps along. It keeps the
 * ProgressBar API, so callers set max, progress and indeterminate as before. Without motion the wave
 * stands still.
 */
final class WavyProgress extends ProgressBar {
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private float shown, phase, sweep;
    private long last;

    WavyProgress(Context context, int primary, int trackColor) {
        super(context, null, android.R.attr.progressBarStyleHorizontal);
        density = context.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[] {wave, track}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(4 * density);
        }
        wave.setColor(primary);
        track.setColor(trackColor);
        setMinimumHeight(Math.round(14 * density));
    }

    @Override
    protected synchronized void onMeasure(int w, int h) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), w), Math.round(14 * density));
    }

    @Override
    protected synchronized void onDraw(Canvas c) {
        boolean motion = Look.motion(getContext());
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        if (motion) phase += dt * 6f;
        float w = getWidth(), mid = getHeight() / 2f, inset = 4 * density, gap = 6 * density;
        float amp = 2.5f * density, length = 22 * density;
        float from, to;
        if (isIndeterminate()) {
            sweep = (sweep + dt * (motion ? 0.55f : 0f)) % 1.4f;
            from = Math.max(0, sweep - 0.4f);
            to = Math.min(1, sweep);
        } else {
            float target = getMax() == 0 ? 0 : (float) getProgress() / getMax();
            shown += (target - shown) * (motion ? 1 - (float) Math.exp(-dt * 8) : 1); // eases to the new value
            from = 0;
            to = shown;
        }
        float a = inset + (w - 2 * inset) * from, b = inset + (w - 2 * inset) * to;
        // track: before and after the wave, with a gap on each side, and a stop dot at the end
        if (a - gap > inset) c.drawLine(inset, mid, a - gap, mid, track);
        if (b + gap < w - inset) c.drawLine(b + gap, mid, w - inset, mid, track);
        c.drawPoint(w - inset, mid, wave);
        if (b > a) {
            path.reset();
            for (float x = a; x <= b; x += density) {
                float y = mid + amp * (float) Math.sin((x / length) * Math.PI * 2 - phase);
                if (x == a) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            c.drawPath(path, wave);
        }
        if (isShown() && (motion || Math.abs(shown - (getMax() == 0 ? 0 : (float) getProgress() / getMax())) > 0.002f)) postInvalidateOnAnimation();
        else last = 0;
    }
}
