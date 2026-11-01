package android.view;

public final class ViewTreeObserver {
    public interface OnDrawListener { void onDraw(); }
    public void addOnDrawListener(OnDrawListener l) {}
    public void removeOnDrawListener(OnDrawListener l) {}
}
