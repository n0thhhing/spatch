package android.view;
// Compile-time stub only; never packaged (the device provides the real class).
public class View implements android.graphics.drawable.Drawable.Callback {
    public static final int VISIBLE = 0, INVISIBLE = 4;
    public void setAlpha(float a) {}
    public android.content.res.Resources getResources() { return null; }
    public ViewPropertyAnimator animate() { return new ViewPropertyAnimator(); }
    public interface OnClickListener { void onClick(View v); }
    public void setOnClickListener(OnClickListener l) {}
    public android.window.OnBackInvokedDispatcher findOnBackInvokedDispatcher() { return null; }
    public void setPadding(int l, int t, int r, int b) {}
    private Object tag; public void setTag(Object t) { tag = t; } public Object getTag() { return tag; }
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
    public boolean isHovered() { return false; }
    public void setEnabled(boolean enabled) {}
    public void setPressed(boolean pressed) {}
    public void setHovered(boolean hovered) {}
    public boolean isEnabled() { return true; }
    public boolean isShown() { return true; }
    public static class AccessibilityDelegate { public void sendAccessibilityEvent(View host, int type) {} }
    public AccessibilityDelegate getAccessibilityDelegate() { return null; }
    public void setAccessibilityDelegate(AccessibilityDelegate d) {}
    public android.view.ViewOverlay getOverlay() { return new android.view.ViewOverlay(); }
    public boolean isPressed() { return false; }
    public int getId() { return 0; }
    protected void onFinishInflate() {}
    public static final int LAYOUT_DIRECTION_RTL = 1, IMPORTANT_FOR_ACCESSIBILITY_NO = 2, IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4;
    public boolean performHapticFeedback(int c) { return true; }
    public boolean isAttachedToWindow() { return true; }
    public int getLayoutDirection() { return 0; }
    public void setImportantForAccessibility(int m) {}
    public int getLeft() { return 0; } public int getRight() { return 0; } public int getTop() { return 0; } public int getBottom() { return 0; }
    public boolean isClickable() { return false; }
    public void setClickable(boolean c) {}
    public void setScaleX(float s) {}
    public float getScaleX() { return 1f; }
    public void setTranslationX(float x) {}
    public static final int GONE = 8;
    public void setTranslationY(float y) {}
    public void setRotation(float r) {}
    public ViewGroup.LayoutParams getLayoutParams() { return null; }
    public interface OnScrollChangeListener { void onScrollChange(View v, int x, int y, int oldX, int oldY); }
    public void setOnScrollChangeListener(OnScrollChangeListener l) {}
    public void getLocationOnScreen(int[] out) {}
    public float getTranslationX() { return 0f; }
    public void setScaleY(float s) {}
    public void postInvalidateDelayed(long delay) {}
    public void postInvalidate() {}
    public boolean hasWindowFocus() { return true; }
    public void postInvalidateOnAnimation() {}
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
