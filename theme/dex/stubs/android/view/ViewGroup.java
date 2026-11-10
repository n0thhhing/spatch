package android.view;
// Compile-time stub only; never packaged (the device provides the real class).
public class ViewGroup extends View implements ViewParent {
    public interface OnHierarchyChangeListener { void onChildViewAdded(View parent, View child); void onChildViewRemoved(View parent, View child); }
    public void setOnHierarchyChangeListener(OnHierarchyChangeListener l) {}
    public void requestDisallowInterceptTouchEvent(boolean disallow) {}
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public int height;
        public LayoutParams(int w, int h) {}
    }
    public ViewGroup(android.content.Context c, android.util.AttributeSet a) { super(c, a); }
    public int getChildCount() { return 0; }
    protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) { return false; }
    public View getChildAt(int i) { return null; }
    public void removeViewAt(int i) {}
    public void setClipChildren(boolean c) {}
    public boolean getClipChildren() { return true; }
    public void setClipToPadding(boolean c) {}
    public void addView(View v) {}
    public void removeAllViews() {}
    public void addView(View v, int i, LayoutParams p) {}
    public void addView(View v, LayoutParams p) {}
    public void removeView(View v) {}
}
