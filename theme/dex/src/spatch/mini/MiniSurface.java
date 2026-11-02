package spatch;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;

/** The mini-player's theme surface: Spotify's artwork tint (tro0, gah) and its 8dp radius stop here. */
final class MiniSurface extends GradientDrawable {
    private static final float ALBUM = 0.2f;
    private final int surface;
    private int shown;

    MiniSurface(int color, float radius) {
        surface = shown = color;
        setColor(color);
        super.setCornerRadius(radius);
    }

    /** The album colour fades on its own, so this redraws only while it changes. */
    @Override
    public void draw(android.graphics.Canvas canvas) {
        int wanted = Settings.get(Settings.MINI_ALBUM) ? Pill.blend(surface, Tint.album(), ALBUM) : surface;
        if (wanted != shown) {
            shown = wanted;
            setColor(wanted);
        }
        super.draw(canvas);
    }

    public void setCornerRadius(float radius) {}

    public void setTint(int color) {}

    public void setTintList(ColorStateList list) {}
}
