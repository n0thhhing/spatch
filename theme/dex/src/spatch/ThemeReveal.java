package spatch;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * A chosen theme's colours growing out of its swatch. Spotify only reads the palette at startup, so
 * this shows the theme rather than applying it, and shrinks back if the restart is put off.
 */
final class ThemeReveal extends Drawable implements ValueAnimator.AnimatorUpdateListener {
    private static final long MILLIS = 480;
    private final View host;
    private final float x, y, reach, dp;
    private final int surface, accent, text;
    private final String name;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t;
    private boolean closing;

    private ThemeReveal(View host, float x, float y, int theme, String name) {
        this.host = host;
        this.x = x;
        this.y = y;
        this.name = name;
        reach = reach(host.getWidth(), host.getHeight(), x, y);
        dp = host.getResources().getDisplayMetrics().density;
        surface = Palette.tone(theme, 0);
        text = Palette.tone(theme, 3);
        accent = Palette.tone(theme, 6);
    }

    /** The circle's size when it has just covered the host: the distance to the farthest corner. */
    static float reach(float w, float h, float x, float y) {
        return (float) Math.hypot(Math.max(x, w - x), Math.max(y, h - y));
    }

    /** Starts from the middle of the swatch; null when motion is off. */
    static ThemeReveal open(View host, View swatch, int theme, String name) {
        if (!Wave.animationsEnabled() || host.getWidth() == 0) return null;
        int[] from = new int[2], to = new int[2];
        host.getLocationOnScreen(to);
        swatch.getLocationOnScreen(from);
        ThemeReveal reveal = new ThemeReveal(host, from[0] - to[0] + swatch.getWidth() / 2f, from[1] - to[1] + swatch.getHeight() / 2f, theme, name);
        reveal.setBounds(0, 0, host.getWidth(), host.getHeight());
        host.getOverlay().add(reveal);
        reveal.run();
        return reveal;
    }

    void close() {
        closing = true;
        run();
    }

    private void run() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(MILLIS);
        animator.addUpdateListener(this);
        animator.start();
    }

    public void onAnimationUpdate(ValueAnimator a) {
        float value = ((Float) a.getAnimatedValue()).floatValue();
        t = closing ? 1 - value : value;
        if (closing && value >= 1) host.getOverlay().remove(this);
        else invalidateSelf();
    }

    public void draw(Canvas c) {
        float eased = t * t * (3 - 2 * t), radius = reach * eased;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surface);
        c.drawCircle(x, y, radius, paint);
        // The rim fades as the circle leaves the screen, so no line is left behind.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * dp);
        paint.setColor(accent);
        paint.setAlpha(Math.round(255 * (1 - eased)));
        c.drawCircle(x, y, radius, paint);
        int fade = Math.round(255 * Math.max(0, t * 2 - 1));
        if (fade == 0) return;
        float cx = getBounds().width() / 2f, cy = getBounds().height() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent);
        paint.setAlpha(fade);
        c.drawCircle(cx, cy - 72 * dp, 28 * dp, paint);
        paint.setColor(text);
        paint.setAlpha(fade);
        paint.setTextSize(30 * dp);
        paint.setTypeface(Fonts.settings(700));
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(name, cx, cy - 12 * dp, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    public void setAlpha(int alpha) {}
    public void setColorFilter(ColorFilter filter) {}
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
