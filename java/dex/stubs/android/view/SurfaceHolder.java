package android.view;
// Compile-time stub only; never packaged (the device provides the real class).
public interface SurfaceHolder {
    interface Callback {
        void surfaceCreated(SurfaceHolder holder);
        void surfaceChanged(SurfaceHolder holder, int format, int width, int height);
        void surfaceDestroyed(SurfaceHolder holder);
    }
    void addCallback(Callback callback);
    Surface getSurface();
}
