package spatch;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;

/** The mini-player's theme surface: Spotify's artwork tint (tro0, gah) and its 8dp radius stop here. */
final class MiniSurface extends GradientDrawable implements Runnable {
    private static final float ALBUM = 0.22f;
    private final int surface;
    private int shown;
    private final float radius;

    MiniSurface(int color, float radius) {
        this.surface = shown = color;
        this.radius = radius;
        int glass = (0xeb << 24) | (color & 0x00ffffff);
        setColor(glass);
        super.setCornerRadius(radius);
        setStroke((int) Math.max(1, radius * 0.04f), 0x2affffff | (Tint.album() & 0x18ffffff));
    }

    /** The album colour fades on its own, so this redraws only while it changes. */
    @Override
    public void draw(android.graphics.Canvas canvas) {
        int wanted = Settings.get(Settings.MINI_ALBUM) ? Pill.blend(surface, Tint.album(), ALBUM) : surface;
        if (wanted != shown) {
            shown = wanted;
            int glass = (0xeb << 24) | (wanted & 0x00ffffff);
            setColor(glass);
            int rimColor = 0x2affffff | (Tint.album() & 0x18ffffff);
            setStroke((int) Math.max(1, radius * 0.04f), rimColor);
        }
        super.draw(canvas);
        if (Settings.get(Settings.MINI_ALBUM) && Tint.fading() && isVisible()) scheduleSelf(this, android.os.SystemClock.uptimeMillis() + 16);
    }

    public void run() { invalidateSelf(); }

    public void setCornerRadius(float radius) {}

    public void setTint(int color) {}

    public void setTintList(ColorStateList list) {}
}
