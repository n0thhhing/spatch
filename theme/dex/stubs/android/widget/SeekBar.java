package android.widget;
// Compile-time stub only; never packaged (the device provides the real class).
public class SeekBar extends android.view.View {
    public SeekBar(android.content.Context c, android.util.AttributeSet a) { super(c, a); }
    public int getProgress() { return 0; }
    public int getMax() { return 0; }
    public void setProgress(int p) {}
    public void setMax(int max) {}
    public void setProgressTintList(android.content.res.ColorStateList tint) {}
    public void setThumbTintList(android.content.res.ColorStateList tint) {}
    public interface OnSeekBarChangeListener {
        void onProgressChanged(SeekBar bar, int progress, boolean fromUser);
        void onStartTrackingTouch(SeekBar bar);
        void onStopTrackingTouch(SeekBar bar);
    }
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {}
}
