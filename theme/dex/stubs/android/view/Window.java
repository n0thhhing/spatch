package android.view;
// Compile-time stub only; never packaged (the device provides the real class).
public abstract class Window {
    public void setBackgroundDrawable(android.graphics.drawable.Drawable background) {}
    public interface Callback { boolean dispatchTouchEvent(MotionEvent event); }
    public final Callback getCallback() { return null; }
    public void setCallback(Callback callback) {}
    public android.view.View getDecorView() { return null; }
}
