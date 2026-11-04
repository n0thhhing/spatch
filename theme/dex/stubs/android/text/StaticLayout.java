package android.text;
// Compile-time stub only; Android supplies the real class.
public class StaticLayout extends Layout {
    public void draw(android.graphics.Canvas canvas) {}
    public static class Builder {
        public static Builder obtain(CharSequence text,int start,int end,TextPaint paint,int width) { return new Builder(); }
        public Builder setAlignment(Layout.Alignment align) { return this; }
        public Builder setIncludePad(boolean pad) { return this; }
        public Builder setMaxLines(int lines) { return this; }
        public Builder setEllipsize(TextUtils.TruncateAt where) { return this; }
        public StaticLayout build() { return new StaticLayout(); }
    }
}
