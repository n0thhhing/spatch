package android.app;
public class Activity extends android.content.ContextWrapper { public static final int RESULT_OK = -1; public android.view.Window getWindow() { return null; } public FragmentManager getFragmentManager() { return null; } public void runOnUiThread(Runnable r) {} public void requestPermissions(String[] permissions, int code) {} }
