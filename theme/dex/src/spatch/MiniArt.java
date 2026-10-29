package spatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Mini-player cover (layout/now_playing_bar cover_image): a new track's artwork pops in from 70%
 * with a small overshoot instead of swapping in place.
 */
public final class MiniArt extends ImageView {
    private static final long POP_MS = 560;
    private long popAt = -POP_MS;

    public MiniArt(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setImageDrawable(Drawable art) {
        Drawable old = getDrawable();
        super.setImageDrawable(art);
        long now = SystemClock.uptimeMillis();
        // Placeholder-then-image loads arrive in quick succession; pop once per track change.
        if (old != null && art != null && old != art && now - popAt > POP_MS && Wave.animationsEnabled()) {
            popAt = now;
            invalidate();
        }
    }

    protected void onDraw(Canvas canvas) {
        long t = SystemClock.uptimeMillis() - popAt;
        if (t >= POP_MS) {
            super.onDraw(canvas);
            return;
        }
        float s = 0.7f + 0.3f * outBack(t / (float) POP_MS, 2.4f);
        int save = canvas.save();
        canvas.scale(s, s, getWidth() / 2f, getHeight() / 2f);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
        postInvalidateDelayed(16);
    }

    /** Ease out with overshoot s past the end before settling. */
    static float outBack(float t, float s) {
        t = Math.max(0f, Math.min(1f, t)) - 1;
        return 1 + (s + 1) * t * t * t + s * t * t;
    }
}
