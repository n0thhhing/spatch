package spatch;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import p.S_SaveButtonState;
import p.S_SaveButtonStage;

/**
 * A completed save: AddToButtonView.g reports each real state it is given. Only a tap followed by
 * not-added to added celebrates, so restored state, unsave, failure and recycled rows stay quiet.
 * A few dots leave the button inside its own bounds, and clean themselves up.
 */
public final class SaveBurst extends Drawable implements ValueAnimator.AnimatorUpdateListener {
    private static final int DOTS = 8;
    private final View host;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t;

    private SaveBurst(View host) {
        this.host = host;
        paint.setColor(Palette.color(0xffd0bcff));
    }

    static boolean celebrates(boolean tapped, S_SaveButtonState before, S_SaveButtonState now) {
        return tapped && before != null && now != null && before.a == S_SaveButtonStage.a && now.a != S_SaveButtonStage.a;
    }

    public static void changed(View button, boolean tapped, S_SaveButtonState before, S_SaveButtonState now) {
        if (!celebrates(tapped, before, now)) return;
        Haptics.saved(button);
        if (!Wave.animationsEnabled() || !button.isAttachedToWindow()) return;
        SaveBurst burst = new SaveBurst(button);
        burst.setBounds(0, 0, button.getWidth(), button.getHeight());
        button.getOverlay().add(burst);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(360);
        a.addUpdateListener(burst);
        a.start();
    }

    public void onAnimationUpdate(ValueAnimator a) {
        t = ((Float) a.getAnimatedValue()).floatValue();
        if (t >= 1) host.getOverlay().remove(this);
        else invalidateSelf();
    }

    public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY(), reach = Math.min(b.width(), b.height()) * 0.5f;
        float r = reach * 0.08f * (1 - t), out = reach * (0.5f + 0.42f * (1 - (1 - t) * (1 - t)));
        paint.setAlpha(Math.round(255 * (1 - t)));
        for (int i = 0; i < DOTS; i++) {
            double a = 2 * Math.PI * i / DOTS;
            c.drawCircle(cx + (float) Math.cos(a) * out, cy + (float) Math.sin(a) * out, r, paint);
        }
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
