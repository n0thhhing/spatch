package android.text;

public abstract class Layout {
    public enum Alignment { ALIGN_CENTER }
    public int getEllipsisCount(int line) { return 0; }
    public int getHeight() { return 0; }
}
