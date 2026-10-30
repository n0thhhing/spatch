package spatch;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** The mini-player's progress: a ring around Play, filled clockwise from the top by ProgressBar's level. */
final class MiniRing extends Drawable {
    private static final float STROKE_DP = 2.5f, INSET_DP = 3;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int color;
    private final float dp;

    MiniRing(int color, float dp) {
        this.color = color;
        this.dp = dp;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(STROKE_DP * dp);
    }

    public void draw(Canvas c) {
        Rect b = getBounds();
        float inset = INSET_DP * dp;
        oval.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        paint.setColor(color & 0x4dffffff);
        c.drawArc(oval, 0, 360, false, paint);
        paint.setColor(color);
        c.drawArc(oval, -90, getLevel() * 0.036f, false, paint);
    }

    protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
