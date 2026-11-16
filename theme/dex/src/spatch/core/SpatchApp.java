package spatch;

import android.app.Activity;
import android.content.Context;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hosts the Rust app (assets/spatch/libspatch_app.so) over Spotify's window: a full-screen
 * SurfaceView whose buffer the Rust side draws into, with touches and frame ticks passed down.
 * Spotify keeps running underneath, so its core (sign-in, playback) is live for CoreBridge.
 * The library ships as an asset and is copied out once: Spotify's manifest and splits stay untouched.
 */
final class SpatchApp implements SurfaceHolder.Callback, Choreographer.FrameCallback, View.OnTouchListener {
    private static final String ASSET = "spatch/libspatch_app.so";
    private static String loadError;
    private static boolean loaded;
    private static SpatchApp current;
    private final Activity activity;
    private final FrameLayout host;
    private boolean ticking;

    private SpatchApp(Activity activity) {
        this.activity = activity;
        host = new FrameLayout(activity);
        host.setClickable(true); // nothing reaches Spotify's views underneath
        SurfaceView surface = new SurfaceView(activity);
        surface.getHolder().addCallback(this);
        surface.setOnTouchListener(this);
        host.addView(surface, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Whether the Rust library is loaded; copies it out of the APK on first use. */
    static synchronized boolean load(Context c) {
        if (loaded) return true;
        try {
            File dir = new File(c.getFilesDir(), "spatch-app");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("can't create " + dir);
            byte[] bytes = read(c.getAssets().open(ASSET));
            // Named by size and checksum so an updated library never loads a stale copy.
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
            System.load(lib.getAbsolutePath());
            loaded = true;
            loadError = null;
        } catch (Throwable e) {
            loadError = String.valueOf(e);
        }
        return loaded;
    }

    /** Why the library didn't load, or null. */
    static String loadError() {
        return loadError;
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        try (InputStream s = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n; (n = s.read(buf)) > 0;) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Opens the Rust app over this activity. False (see loadError) when the library can't load. */
    static boolean open(Activity activity) {
        if (current != null) return true;
        if (!load(activity)) return false;
        SpatchApp app = new SpatchApp(activity);
        current = app;
        ((ViewGroup) activity.getWindow().getDecorView()).addView(app.host, new FrameLayout.LayoutParams(-1, -1));
        nativeOpen(activity.getResources().getDisplayMetrics().density);
        return true;
    }

    /** Called by the Rust app (any thread) to close itself. */
    static void close() {
        final SpatchApp app = current;
        if (app == null) return;
        app.host.post(new Runnable() {
            public void run() {
                if (current != app) return;
                current = null;
                app.ticking = false;
                ViewGroup parent = (ViewGroup) app.host.getParent();
                if (parent != null) parent.removeView(app.host);
                nativeClose();
            }
        });
    }

    public void surfaceCreated(SurfaceHolder holder) {}

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeSurface(holder.getSurface(), width, height);
        if (!ticking) {
            ticking = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        ticking = false;
        nativeSurface(null, 0, 0);
    }

    public void doFrame(long frameTimeNanos) {
        if (!ticking || current != this) return;
        nativeFrame();
        Choreographer.getInstance().postFrameCallback(this);
    }

    public boolean onTouch(View v, MotionEvent e) {
        nativeTouch(e.getActionMasked(), e.getX(), e.getY());
        return true;
    }

    static native void nativeOpen(float density);

    static native void nativeClose();

    static native void nativeSurface(Object surface, int width, int height);

    static native void nativeFrame();

    static native void nativeTouch(int action, float x, float y);
}
