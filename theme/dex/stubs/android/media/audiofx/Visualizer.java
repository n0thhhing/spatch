package android.media.audiofx;
// Compile-only Android API; never packaged.
public class Visualizer {
    public Visualizer(int session) {}
    public static int[] getCaptureSizeRange() { return new int[]{128,1024}; }
    public int setCaptureSize(int size) { return 0; }
    public int setEnabled(boolean enabled) { return 0; }
    public int getSamplingRate() { return 48000000; }
    public int getFft(byte[] fft) { return 0; }
    public void release() {}
}
