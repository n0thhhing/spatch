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

/** Mounts the Spatch UI over Spotify while its core runs underneath. */
public final class SpatchApp {
    private static final String ASSET = "spatch/libspatch_app.so";
    private static String loadError;
    private static boolean loaded;
    private static final String ROOT = "spatch-root";
    private static boolean watching;

    /** Whether the Rust library is loaded; copies it out of the APK on first use. */
    static synchronized boolean load(Context c) {
        if (loaded) return true;
        try {
            File dir = new File(c.getFilesDir(), "spatch-app");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("can't create " + dir);
            byte[] bytes = read(c.getAssets().open(ASSET));
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bytes, 0, bytes.length);
            File lib = new File(dir, "libspatch_app-" + bytes.length + "-" + Long.toHexString(crc.getValue()) + ".so");
            if (!lib.isFile()) {
                File tmp = new File(dir, lib.getName() + ".part");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(bytes);
                }
                if (!tmp.renameTo(lib)) throw new java.io.IOException("can't save " + lib);
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) if (!f.equals(lib)) f.delete();
            }
            if (lib.canWrite() && !lib.setReadOnly()) throw new java.io.IOException("can't make " + lib + " read-only");
            System.load(lib.getAbsolutePath());
            CoreBridge.nativeInit();
            loaded = true;
            loadError = null;
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

    /** Called from the launcher activity before Spotify resumes its presentation layer. */
    public static void resumed(Activity activity) {
        if (!watching) {
            watching = true;
            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity resumed) { mount(resumed); }
                @Override public void onActivityCreated(Activity activity, Bundle state) {}
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        }
        mount(activity);
    }

    private static void mount(Activity activity) {
        if (activity.isFinishing()) return;
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View nativeContent = decor.findViewById(android.R.id.content);
            if (nativeContent != null) {
                nativeContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                nativeContent.setVisibility(View.INVISIBLE);
            }
            if (decor.findViewWithTag(ROOT) != null) return;

            activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            View root;
            if (load(activity)) {
                SpatchUi ui = new SpatchUi(activity);
                if (Build.VERSION.SDK_INT >= 33) Back33.register(activity, ui);
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
            root.setTag(ROOT);
            decor.addView(root, new FrameLayout.LayoutParams(-1, -1));
        } catch (Throwable t) {
            android.util.Log.e("SpatchApp", "mount failed", t);
        }
    }

    private static final class Back33 {
        private static void register(Activity activity, SpatchUi ui) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, ui::back);
        }
    }
}
