package spatch;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * XML-inflated background (res/drawable/play_button_header_style_background.xml) filling the
 * player's scallop contour; honours the tint list Encore's PlayButtonView sets, including pressed.
 */
public final class ScallopFill extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private ColorStateList tint;

    public ScallopFill() {
        paint.setColor(0xffd0bcff); // theme accent token until a tint arrives
    }

    @Override
    public void setTintList(ColorStateList list) {
        tint = list;
        onStateChange(getState());
    }

    @Override
    public boolean isStateful() {
        return tint != null && tint.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (tint == null) return false;
        int color = tint.getColorForState(state, tint.getDefaultColor());
        if (color == paint.getColor()) return false;
        paint.setColor(color);
        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        path.reset();
        ScallopShape.trace(path, b.width(), b.height());
        path.offset(b.left, b.top);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
