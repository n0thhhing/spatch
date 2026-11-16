package spatch;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import dev.makepad.android.MakepadNative;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hosts the Rust app (assets/spatch/libspatch_app.so) over Spotify's window: a full-screen
 * SurfaceView fed to Makepad's Android layer (dev.makepad.android.MakepadNative).
 * Spotify keeps running underneath, so its core (sign-in, playback) is live for CoreBridge.
 */
public final class SpatchApp implements SurfaceHolder.Callback, View.OnTouchListener {
    private static final String ASSET = "spatch/libspatch_app.so";
    private static String loadError;
    private static boolean loaded;
    private static boolean makepadStarted;
    private static SpatchApp current;
    private final FrameLayout host;
    private View spotify;

    private SpatchApp(Activity activity) {
        host = new FrameLayout(activity);
        host.setClickable(true);
        SurfaceView surface = new SurfaceView(activity);
        surface.getHolder().addCallback(this);
        surface.setOnTouchListener(this);
        host.addView(surface, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Host object passed to MakepadNative.activityOnCreate: provides AssetManager, no-ops the rest. */
    public static final class Host {
        private final Activity activity;

        Host(Activity activity) {
            this.activity = activity;
        }

        public AssetManager getAssets() {
            return activity.getAssets();
        }

        public void setFullScreen(boolean fullscreen) {}

        public void switchActivity() {}

        public void showKeyboard(boolean visible) {}

        public void copyToClipboard(String content) {}

        public void requestHttp(long id, long metadataId, String url, String method, String headers, byte[] body) {}

        public void openWebSocket(long id, String url, long callback) {}

        public void sendWebSocketMessage(long id, byte[] message) {}

        public void closeWebSocket(long id) {}

        public String[] getAudioDevices(long flag) {
            return new String[0];
        }

        public void openAllMidiDevices(long delay) {}

        public void prepareVideoPlayback(long id, Object source, int tex, boolean autoplay, boolean loop) {}

        public void beginVideoPlayback(long id) {}

        public void pauseVideoPlayback(long id) {}

        public void resumeVideoPlayback(long id) {}

        public void muteVideoPlayback(long id) {}

        public void unmuteVideoPlayback(long id) {}

        public void cleanupVideoPlaybackResources(long id) {}
    }

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
            loaded = true;
            loadError = null;
        } catch (Throwable e) {
            loadError = String.valueOf(e);
        }
        return loaded;
    }

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

    private static boolean startHandled;

    /** Spotify's main screen resumed: the app comes up over it once per launch, as the UI. */
    public static void resumed(Activity a) {
        if (startHandled || !"com.spotify.music.SpotifyMainActivity".equals(a.getClass().getName())) return;
        startHandled = true;
        try {
            open(a);
        } catch (Throwable t) {
            android.util.Log.e("SpatchApp", "resumed failed", t);
        }
    }

    /** Opens the Rust app over this activity. False (see loadError) when the library can't load. */
    static boolean open(Activity activity) {
        if (current != null) return true;
        try {
            if (!load(activity)) {
                android.util.Log.e("SpatchApp", "load failed: " + loadError);
                return false;
            }
            if (!makepadStarted) {
                makepadStarted = true;
                CoreBridge.nativeInit();
                MakepadNative.activityOnCreate(new Host(activity));
                float density = activity.getResources().getDisplayMetrics().density;
                MakepadNative.onAndroidParams(
                    activity.getCacheDir().getAbsolutePath(),
                    density,
                    false,
                    "",
                    "",
                    ""
                );
                MakepadNative.initChoreographer(60f, Build.VERSION.SDK_INT);
                MakepadNative.activityOnStart();
            }
            MakepadNative.activityOnResume();
            SpatchApp app = new SpatchApp(activity);
            current = app;
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.addView(app.host, new FrameLayout.LayoutParams(-1, -1));
            app.spotify = decor.findViewById(0x01020002); // android.R.id.content
            if (app.spotify != null && app.spotify.getVisibility() == View.VISIBLE) app.spotify.setVisibility(View.INVISIBLE);
            else app.spotify = null;
            return true;
        } catch (Throwable t) {
            android.util.Log.e("SpatchApp", "open failed", t);
            return false;
        }
    }

    /** Called by the Rust app (any thread) to close itself. */
    static void close() {
        final SpatchApp app = current;
        if (app == null) return;
        app.host.post(new Runnable() {
            public void run() {
                if (current != app) return;
                current = null;
                MakepadNative.activityOnPause();
                if (app.spotify != null) app.spotify.setVisibility(View.VISIBLE);
                ViewGroup parent = (ViewGroup) app.host.getParent();
                if (parent != null) parent.removeView(app.host);
            }
        });
    }

    public void surfaceCreated(SurfaceHolder holder) {
        MakepadNative.surfaceOnSurfaceCreated(holder.getSurface());
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        MakepadNative.surfaceOnSurfaceChanged(holder.getSurface(), width, height);
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        MakepadNative.surfaceOnSurfaceDestroyed();
    }

    public boolean onTouch(View v, MotionEvent e) {
        MakepadNative.surfaceOnTouch(e);
        return true;
    }
}
