package android.view;

public final class PixelCopy {
    public static final int SUCCESS = 0;
    public interface OnPixelCopyFinishedListener { void onPixelCopyFinished(int result); }
    public static void request(Window source, android.graphics.Bitmap dest, OnPixelCopyFinishedListener listener, android.os.Handler handler) {}
}
