package android.widget;
// Compile-time stub only; never packaged (the device provides the real class).
public class LinearLayout extends android.view.ViewGroup {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public static class LayoutParams extends android.view.ViewGroup.LayoutParams {
        public float weight;
        public int gravity = -1;
        public int leftMargin, topMargin, rightMargin, bottomMargin;
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(int w, int h, float weight) { super(w, h); this.weight = weight; }
        public void setMargins(int l, int t, int r, int b) { leftMargin = l; topMargin = t; rightMargin = r; bottomMargin = b; }
    }

    public LinearLayout(android.content.Context c) { super(c, null); }
    public void setOrientation(int o) {}
    public void setGravity(int g) {}
}
