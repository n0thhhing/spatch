package android.view;
// Compile-time stub only; never packaged (the device provides the real class).
public final class Choreographer {
    public interface FrameCallback { void doFrame(long frameTimeNanos); }
    public static Choreographer getInstance() { throw new RuntimeException(); }
    public void postFrameCallback(FrameCallback callback) {}
    public void removeFrameCallback(FrameCallback callback) {}
}
