package spatch;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Mini-player to full player: Now Playing is its own Activity, so the real artwork flies from the mini
 * cover's rectangle in that window's overlay. An overlay takes no touches; the player below is the only
 * interactive one, and the end, a cancel or detach always gives the art back.
 */
final class Expansion extends Drawable implements ValueAnimator.AnimatorUpdateListener {
    private static final long MILLIS = 300;
    private final PlayerArt art;
    private final View root;
    private final float fromX, fromY, fromSize, toX, toY, toSize, corner;
    private final Path clip = new Path();
    private ValueAnimator animator;
    private float t;

    private Expansion(PlayerArt art, View root, float[] from, float[] to, float corner) {
        this.art = art;
        this.root = root;
        fromX = from[0];
        fromY = from[1];
        fromSize = from[2];
        toX = to[0];
        toY = to[1];
        toSize = to[2];
        this.corner = corner;
    }

    static Expansion start(PlayerArt art, float[] from) {
        View root = art.getRootView();
        int[] at = new int[2], base = new int[2];
        art.getLocationOnScreen(at);
        root.getLocationOnScreen(base);
        float size = Math.min(art.getWidth(), art.getHeight());
        float[] to = {at[0] - base[0] + art.getWidth() / 2f, at[1] - base[1] + art.getHeight() / 2f, size};
        float[] start = {from[0] - base[0], from[1] - base[1], from[2]};
        Expansion e = new Expansion(art, root, start, to, 12 * root.getResources().getDisplayMetrics().density);
        e.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(e);
        art.hideForHero(true);
        e.animator = ValueAnimator.ofFloat(0f, 1f);
        e.animator.setDuration(MILLIS);
        e.animator.setInterpolator(new DecelerateInterpolator(2f));
        e.animator.addUpdateListener(e);
        e.animator.start();
        return e;
    }

    public void onAnimationUpdate(ValueAnimator a) {
        t = ((Float) a.getAnimatedValue()).floatValue();
        if (t >= 1) finish();
        else invalidateSelf();
    }

    void finish() {
        if (animator != null) animator.removeAllUpdateListeners();
        animator = null;
        root.getOverlay().remove(this);
        art.hideForHero(false);
    }

    public void draw(Canvas c) {
        float x = fromX + (toX - fromX) * t, y = fromY + (toY - fromY) * t, size = fromSize + (toSize - fromSize) * t;
        float round = Settings.roundArt() ? size / 2 : corner;
        float r = corner + (round - corner) * t;
        clip.reset();
        clip.addRoundRect(x - size / 2, y - size / 2, x + size / 2, y + size / 2, r, r, Path.Direction.CW);
        int save = c.save();
        c.clipPath(clip);
        art.drawHeroImage(c, x, y, size, t);
        c.restoreToCount(save);
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
