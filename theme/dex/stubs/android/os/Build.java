package android.os;
// Compile-time stub only; never packaged (the device provides the real class).
public class Build {
    public static final String MODEL = null;
    public static class VERSION {
        // Deliberately not a compile-time constant, so javac reads the device value.
        public static int SDK_INT;
    }
}
