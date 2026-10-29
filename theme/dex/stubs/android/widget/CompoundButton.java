package android.widget;
// Compile-time stub only; never packaged (the device provides the real class).
public class CompoundButton extends TextView {
    public interface OnCheckedChangeListener {
        void onCheckedChanged(CompoundButton b, boolean checked);
    }
    private boolean checked;
    public CompoundButton(android.content.Context c) { super(c, null); }
    public void setChecked(boolean c) { checked = c; }
    public boolean isChecked() { return checked; }
    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {}
}
