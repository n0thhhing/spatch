package spatch;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Mounts the Spatch UI over Spotify's screen while Spotify's core keeps running underneath. */
public final class SpatchApp {
    private static final String LIBRARY_ASSET = "spatch/libspatch_app.so";
    private static final String ROOT_TAG = "spatch-root";
    private static boolean loaded, watching;
    private static String loadError;

    /** Copies the Rust library out of the APK on first use and loads it; true once it is loaded. */
    private static synchronized boolean load(Context context) {
        if (loaded) return true;
        try {
            File dir = new File(context.getFilesDir(), "spatch-app");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("can't create " + dir);
            byte[] bytes = read(context.getAssets().open(LIBRARY_ASSET));
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bytes, 0, bytes.length);
            File lib = new File(dir, "libspatch_app-" + bytes.length + "-" + Long.toHexString(crc.getValue()) + ".so");
            if (!lib.isFile()) {
                File part = new File(dir, lib.getName() + ".part");
                try (OutputStream out = new FileOutputStream(part)) {
                    out.write(bytes);
                }
                if (!part.renameTo(lib)) throw new java.io.IOException("can't save " + lib);
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) if (!f.equals(lib)) f.delete();
            }
            // Android 14 refuses to load a writable library.
            if (lib.canWrite() && !lib.setReadOnly()) throw new java.io.IOException("can't make " + lib + " read-only");
            System.load(lib.getAbsolutePath());
            CoreBridge.nativeInit();
            loaded = true;
        } catch (Throwable e) {
            loadError = String.valueOf(e);
        }
        return loaded;
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        try (InputStream s = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n; (n = s.read(buf)) > 0;) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Hook: the launcher activity's onResume, before Spotify resumes its own screen. */
    public static void resumed(Activity activity) {
        if (!watching) {
            watching = true;
            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity resumed) { mount(resumed); }
                @Override public void onActivityCreated(Activity a, Bundle state) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
                @Override public void onActivityDestroyed(Activity a) {}
            });
        }
        mount(activity);
    }

    private static void mount(Activity activity) {
        if (activity.isFinishing()) return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View spotify = decor.findViewById(android.R.id.content);
            if (spotify != null) {
                spotify.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                spotify.setVisibility(View.INVISIBLE);
            }
            if (decor.findViewWithTag(ROOT_TAG) != null) return;
            View root;
            if (load(activity)) {
                SpatchUi ui = new SpatchUi(activity);
                if (Build.VERSION.SDK_INT >= 33) {
                    activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, (android.window.OnBackInvokedCallback) ui.backCallback());
                }
                root = ui;
            } else {
                android.util.Log.e("SpatchApp", "load failed: " + loadError);
                android.widget.TextView error = new android.widget.TextView(activity);
                error.setGravity(android.view.Gravity.CENTER);
                error.setTextColor(android.graphics.Color.WHITE);
                error.setBackgroundColor(0xff111715);
                error.setText("Spatch couldn’t start");
                root = error;
            }
            root.setTag(ROOT_TAG);
            decor.addView(root, new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable t) {
            android.util.Log.e("SpatchApp", "mount failed", t);
        }
    }
}
