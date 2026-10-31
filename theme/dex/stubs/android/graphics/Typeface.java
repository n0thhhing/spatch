package android.graphics;
// Compile-time stub only; never packaged (the device provides the real class).
public class Typeface {
    public static final Typeface DEFAULT = null;
    public static final Typeface DEFAULT_BOLD = null;
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static Typeface create(Typeface family, int style) { return null; }
    public static Typeface create(Typeface family, int weight, boolean italic) { return null; }
    public final boolean isBold() { return false; }
    public static final class CustomFallbackBuilder {
        public CustomFallbackBuilder(android.graphics.fonts.FontFamily f) {}
        public CustomFallbackBuilder setStyle(android.graphics.fonts.FontStyle s) { return this; }
        public CustomFallbackBuilder setSystemFallback(String name) { return this; }
        public Typeface build() { return null; }
    }
}
