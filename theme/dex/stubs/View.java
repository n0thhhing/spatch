package android.view;
// Compile-time stub only; never packaged (the device provides the real class).
public class View implements android.graphics.drawable.Drawable.Callback {
    public static final int VISIBLE = 0, INVISIBLE = 4;
    public interface OnClickListener { void onClick(View v); }
    public void setOnClickListener(OnClickListener l) {}
    public android.window.OnBackInvokedDispatcher findOnBackInvokedDispatcher() { return null; }
    public void setPadding(int l, int t, int r, int b) {}
    public void setLayoutParams(ViewGroup.LayoutParams p) {}
    public void setMinimumHeight(int h) {}
    public View(android.content.Context c) {}
    public View(android.content.Context c, android.util.AttributeSet a) {}
    protected void onDraw(android.graphics.Canvas c) {}
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public int getPaddingLeft() { return 0; }
    public int getPaddingRight() { return 0; }
    public android.content.Context getContext() { return null; }
    public View getRootView() { return null; }
    public View findViewById(int id) { return null; }
    public void setBackground(android.graphics.drawable.Drawable d) {}
    public void setBackgroundColor(int color) {}
    public void setBackgroundResource(int resid) {}
    public android.graphics.drawable.Drawable getBackground() { return null; }
    public CharSequence getContentDescription() { return null; }
    public void setContentDescription(CharSequence c) {}
    private int visibility;
    public void setVisibility(int value){visibility=value;}
    public int getVisibility(){return visibility;}
    public void setSelected(boolean selected) {}
    public boolean isSelected() { return false; }
    public boolean isActivated() { return false; }
    public boolean isPressed() { return false; }
    public boolean isHovered() { return false; }
    public void setEnabled(boolean enabled) {}
    public void setPressed(boolean pressed) {}
    public void setHovered(boolean hovered) {}
    public boolean isEnabled() { return true; }
    public boolean isShown() { return true; }
    public boolean isClickable() { return false; }
    public void setClickable(boolean c) {}
    public void setScaleX(float s) {}
    public float getScaleX() { return 1f; }
    public void setTranslationX(float x) {}
    public void getLocationOnScreen(int[] out) {}
    public float getTranslationX() { return 0f; }
    public void setScaleY(float s) {}
    public void postInvalidateDelayed(long delay) {}
    public void postInvalidate() {}
    public boolean onHoverEvent(MotionEvent e) { return false; }
    public boolean onTouchEvent(MotionEvent e) { return false; }
    public boolean dispatchTouchEvent(MotionEvent e) { return false; }
    public void invalidate() {}
    public ViewParent parent;
    public ViewParent getParent() { return parent; }
    public boolean performClick() { return false; }
    public void bringToFront() {}
    public boolean dispatchKeyEvent(KeyEvent e) { return false; }
    public boolean postDelayed(Runnable r, long delay) { return false; }
    public boolean removeCallbacks(Runnable r) { return false; }
    public void setFocusable(boolean f) {}
    public void setFocusableInTouchMode(boolean f) {}
    public boolean requestFocus() { return false; }
    protected void onWindowVisibilityChanged(int visibility) {}
    public void onWindowFocusChanged(boolean focus) {}
    protected void onDetachedFromWindow() {}
    public boolean post(Runnable r) { return false; }
}
