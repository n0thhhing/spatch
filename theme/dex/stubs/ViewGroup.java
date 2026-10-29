package android.view;
// Compile-time stub only; never packaged (the device provides the real class).
public class ViewGroup extends View implements ViewParent {
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public LayoutParams(int w, int h) {}
    }
    public ViewGroup(android.content.Context c, android.util.AttributeSet a) { super(c, a); }
    public int getChildCount() { return 0; }
    public View getChildAt(int i) { return null; }
    public void setClipChildren(boolean c) {}
    public void setClipToPadding(boolean c) {}
    public void addView(View v) {}
    public void addView(View v, LayoutParams p) {}
    public void removeView(View v) {}
}
