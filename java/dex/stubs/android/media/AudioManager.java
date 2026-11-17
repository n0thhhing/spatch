package android.media;
// Compile-time stub only; never packaged (the device provides the real class).
public class AudioManager {
    public static final int STREAM_MUSIC = 3;
    public int getStreamVolume(int streamType) { return 0; }
    public int getStreamMaxVolume(int streamType) { return 0; }
    public void setStreamVolume(int streamType, int index, int flags) {}
    public boolean isMusicActive() { return false; }
}
