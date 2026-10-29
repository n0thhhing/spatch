package android.graphics;
// Compile-time stub only; never packaged (the device provides the real class).
public class Path {
    public enum Direction {CW,CCW}
    public void addCircle(float x,float y,float r,Direction d){}
    public void reset() { throw new RuntimeException(); }
    public void moveTo(float x, float y) { throw new RuntimeException(); }
    public void lineTo(float x, float y) { throw new RuntimeException(); }
    public void close() { throw new RuntimeException(); }
    public void addRoundRect(RectF rect, float rx, float ry, Direction dir) {}
    public void addRoundRect(float left, float top, float right, float bottom, float rx, float ry, Direction dir) {}
}
