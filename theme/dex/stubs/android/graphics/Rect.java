package android.graphics;
// Compile-time stub only; never packaged (the device provides the real class).
public final class Rect {
    public int width(){return right-left;}
    public float exactCenterX(){return (left+right)*.5f;}
    public float exactCenterY(){return (top+bottom)*.5f;}
    public int left, top, right, bottom;
    public int height() { throw new RuntimeException(); }
}
