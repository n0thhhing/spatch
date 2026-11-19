package spatch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;

/** Visual progress easing, independent of the playback position and view hierarchy. */
final class ProgressMotion {
    private final Runnable redraw;
    private final TimeInterpolator interpolator;
    private ValueAnimator animator;
    private float offset;

    ProgressMotion(Runnable redraw, TimeInterpolator interpolator) {
        this.redraw = redraw;
        this.interpolator = interpolator;
    }

    float shown(float playback, boolean motion) {
        return Math.max(0, Math.min(1, playback + (motion ? offset : 0)));
    }

    void move(float from, float playback, boolean animate) {
        cancel();
        offset = from - playback;
        if (!animate || Math.abs(offset) < .0005f) {
            offset = 0;
            redraw.run();
            return;
        }
        ValueAnimator next = ValueAnimator.ofFloat(offset, 0);
        animator = next;
        next.setDuration(280);
        next.setInterpolator(interpolator);
        next.addUpdateListener(a -> { offset = (float) a.getAnimatedValue(); redraw.run(); });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (animator == next) { offset = 0; animator = null; redraw.run(); }
            }
        });
        next.start();
    }

    void cancel() {
        if (animator != null) { animator.cancel(); animator = null; }
        offset = 0;
    }
}
