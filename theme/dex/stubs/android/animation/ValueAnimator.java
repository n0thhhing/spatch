package android.animation;
// Compile-time stub only; never packaged (the device provides the real class).
public class ValueAnimator { public void removeAllUpdateListeners() {}
    public interface AnimatorUpdateListener { void onAnimationUpdate(ValueAnimator animation); }
    public static boolean areAnimatorsEnabled() { throw new RuntimeException(); }
    public static ValueAnimator ofFloat(float... values) { throw new RuntimeException(); }
    public ValueAnimator setDuration(long duration) { throw new RuntimeException(); }
    public void addUpdateListener(AnimatorUpdateListener listener) { throw new RuntimeException(); }
    public void setInterpolator(TimeInterpolator value) { throw new RuntimeException(); }
    public void start() { throw new RuntimeException(); }
    public boolean isRunning() { throw new RuntimeException(); }
    public void cancel() { throw new RuntimeException(); }
    public Object getAnimatedValue() { throw new RuntimeException(); }
}
