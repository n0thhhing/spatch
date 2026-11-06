package android.view;

public final class ViewTreeObserver {
    public interface OnDrawListener { void onDraw(); }
    public interface OnGlobalLayoutListener { void onGlobalLayout(); }
    public interface OnScrollChangedListener { void onScrollChanged(); }
    public void addOnScrollChangedListener(OnScrollChangedListener l) {}
    public void addOnGlobalLayoutListener(OnGlobalLayoutListener l) {}
    public void addOnDrawListener(OnDrawListener l) {}
    public void removeOnDrawListener(OnDrawListener l) {}
}
