package android.content;
// Compile-time stub only; never packaged (the device provides the real class).
public interface SharedPreferences {
    interface Editor {
        Editor putString(String k,String v);
        Editor putBoolean(String k, boolean v);
        void apply();
        boolean commit();
    }
    String getString(String k,String fallback);
    boolean getBoolean(String k, boolean d);
    Editor edit();
}
