package android.view;

public final class ViewTreeObserver {
    public interface OnDrawListener { void onDraw(); }
    public interface OnGlobalLayoutListener { void onGlobalLayout(); }
    public void addOnGlobalLayoutListener(OnGlobalLayoutListener l) {}
    public void addOnDrawListener(OnDrawListener l) {}
    public void removeOnDrawListener(OnDrawListener l) {}
}
