package spatch;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;

/** The mini-player's theme surface: Spotify's artwork tint (tro0, gah) and its 8dp radius stop here. */
final class MiniSurface extends GradientDrawable {
    MiniSurface(int color, float radius) {
        setColor(color);
        super.setCornerRadius(radius);
    }

    public void setCornerRadius(float radius) {}

    public void setTint(int color) {}

    public void setTintList(ColorStateList list) {}
}
