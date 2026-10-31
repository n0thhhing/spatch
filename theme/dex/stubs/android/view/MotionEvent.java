package android.view;
// Compile-time stub only; never packaged (the device provides the real class).
public final class MotionEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_HOVER_ENTER=9, ACTION_HOVER_MOVE=7, ACTION_HOVER_EXIT=10;
    public int getActionMasked() { throw new RuntimeException(); }
    public float getX() { throw new RuntimeException(); }
    public float getY() { throw new RuntimeException(); }
    public long getEventTime() { throw new RuntimeException(); }
    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y, int metaState) { throw new RuntimeException(); }
    public static MotionEvent obtain(MotionEvent e) { throw new RuntimeException(); }
    public void setAction(int action) { throw new RuntimeException(); }
    public void offsetLocation(float x, float y) { throw new RuntimeException(); }
    public void recycle() { throw new RuntimeException(); }
}
