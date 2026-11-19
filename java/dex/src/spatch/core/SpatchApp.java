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
    private static final String RETURN_TAG = "spatch-return";
    private static final String MAIN_ACTIVITY = "com.spotify.music.SpotifyMainActivity";
    private static boolean loaded;
    private static volatile boolean nativeFeatureMode;
    private static volatile String mainActivityClass;
    private static String loadError;
    private static final java.util.WeakHashMap<Application, Boolean> WATCHED_APPS = new java.util.WeakHashMap<Application, Boolean>();
    private static final java.util.WeakHashMap<Activity, Object> BACK_CALLBACKS = new java.util.WeakHashMap<Activity, Object>();
    private static final java.util.WeakHashMap<Activity, Boolean> BACK_REGISTERED = new java.util.WeakHashMap<Activity, Boolean>();

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
        if (activity != null && !isAuthActivity(activity)) {
            mainActivityClass = activity.getClass().getName();
        }
        Application app = activity == null ? null : activity.getApplication();
        if (app != null) {
            boolean register;
            synchronized (WATCHED_APPS) {
                register = !Boolean.TRUE.equals(WATCHED_APPS.get(app));
                if (register) WATCHED_APPS.put(app, Boolean.TRUE);
            }
            if (register) {
                app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(Activity resumed) { onAnyActivityResumed(resumed); }
                    @Override public void onActivityCreated(Activity a, Bundle state) {}
                    @Override public void onActivityStarted(Activity a) {}
                    @Override public void onActivityPaused(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
                    @Override public void onActivityDestroyed(Activity a) {
                        synchronized (BACK_CALLBACKS) {
                            BACK_CALLBACKS.remove(a);
                            BACK_REGISTERED.remove(a);
                        }
                    }
                });
            }
        }
        if (activity != null) mount(activity);
    }

    /**
     * Only mount Spatch's overlay on the main host activity (SpotifyMainActivity).
     * Auth, consent, onboarding, permission, and other secondary Spotify activities stay native and uncovered.
     */
    static boolean isMainHostActivity(Activity activity) {
        if (activity == null || isAuthActivity(activity)) return false;
        String name = activity.getClass().getName();
        String host = mainActivityClass;
        return MAIN_ACTIVITY.equals(name) || (host != null && host.equals(name));
    }

    private static void onAnyActivityResumed(Activity resumed) {
        if (resumed == null || resumed.isFinishing() || resumed.isDestroyed()) return;
        if (isAuthActivity(resumed)) return;
        if (nativeFeatureMode) {
            applyNativeMode(resumed);
            return;
        }
        if (isMainHostActivity(resumed)) {
            mount(resumed);
        }
    }

    static boolean isNativeFeatureMode() {
        return nativeFeatureMode;
    }

    /**
     * Activities that handle Spotify sign-in, sign-up, OAuth, account switching, or verification.
     * Spatch must never cover these or hide their content, otherwise fresh installs cannot log in.
     */
    static boolean isAuthClassName(String name) {
        if (name == null || name.isEmpty()) return false;
        if ("com.spotify.music.SpotifyMainActivity".equals(name)) return false;
        if (name.startsWith("com.spotify.login.")
            || name.startsWith("com.spotify.appauthorization.")
            || name.startsWith("com.spotify.accountswitching.")
            || name.startsWith("com.spotify.ageverification.")
            || name.startsWith("com.spotify.genalpha")
            || name.startsWith("com.spotify.mediasession.mediasession.login.")
            || name.startsWith("com.google.android.gms.auth.")
            || name.startsWith("com.facebook.")
            || name.startsWith("androidx.browser.customtabs.")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        String simple = (dot >= 0 ? name.substring(dot + 1) : name).toLowerCase(java.util.Locale.ROOT);
        return simple.contains("login")
            || simple.contains("signin")
            || simple.contains("signup")
            || simple.contains("auth")
            || simple.contains("accountpicker")
            || simple.contains("accountswitch")
            || simple.contains("credential");
    }

    static boolean isAuthActivity(Activity activity) {
        return activity != null && isAuthClassName(activity.getClass().getName());
    }

    /** Temporarily hides Spatch's overlay so a launched native Spotify flow is visible and usable. */
    static void enterNativeFeature(final Activity activity) {
        nativeFeatureMode = true;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        runOnMain(activity, new Runnable() {
            @Override public void run() {
                applyNativeMode(activity);
            }
        });
    }

    /** Restores Spatch's overlay and back handling, closing secondary native activities if needed. */
    static void exitNativeFeature(final Activity activity) {
        nativeFeatureMode = false;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        runOnMain(activity, new Runnable() {
            @Override public void run() {
                try {
                    ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                    removeReturnControl(decor);
                    if (decor.findViewWithTag(ROOT_TAG) == null && !activity.isTaskRoot()) {
                        activity.finish();
                        return;
                    }
                } catch (Throwable t) {
                    android.util.Log.e("SpatchApp", "exitNativeFeature failed", t);
                }
                mount(activity);
            }
        });
    }

    private static void runOnMain(Activity activity, Runnable action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
        } else {
            activity.runOnUiThread(action);
        }
    }

    private static void setBackRegistered(Activity activity, boolean want) {
        if (Build.VERSION.SDK_INT < 33) return;
        synchronized (BACK_CALLBACKS) {
            Object callback = BACK_CALLBACKS.get(activity);
            if (callback == null) return;
            boolean registered = Boolean.TRUE.equals(BACK_REGISTERED.get(activity));
            if (want && !registered) {
                activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, (android.window.OnBackInvokedCallback) callback);
                BACK_REGISTERED.put(activity, Boolean.TRUE);
            } else if (!want && registered) {
                activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) callback);
                BACK_REGISTERED.put(activity, Boolean.FALSE);
            }
        }
    }

    private static void removeReturnControl(ViewGroup decor) {
        View button = decor.findViewWithTag(RETURN_TAG);
        if (button != null) decor.removeView(button);
    }

    private static void applyNativeMode(final Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            activity.setVisible(true);
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            setBackRegistered(activity, false);
            View root = decor.findViewWithTag(ROOT_TAG);
            if (root != null) {
                root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                root.setVisibility(View.GONE);
            }
            View spotify = decor.findViewById(android.R.id.content);
            if (spotify != null) {
                spotify.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                spotify.setVisibility(View.VISIBLE);
            }
            View existing = decor.findViewWithTag(RETURN_TAG);
            if (existing != null) {
                existing.bringToFront();
                return;
            }
            float density = activity.getResources().getDisplayMetrics().density;
            int padH = Math.round(14f * density);
            int padV = Math.round(8f * density);
            final int margin = Math.round(12f * density);
            final int baseTop = Math.round(8f * density);
            android.widget.TextView back = new android.widget.TextView(activity);
            back.setTag(RETURN_TAG);
            back.setText("Back to Spatch");
            back.setContentDescription("Back to Spatch");
            back.setTextColor(android.graphics.Color.WHITE);
            back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
            back.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            back.setGravity(android.view.Gravity.CENTER);
            back.setPadding(padH, padV, padH, padV);
            back.setMinHeight(Math.round(48f * density));
            back.setClickable(true);
            back.setFocusable(true);
            back.setElevation(8f * density);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xe6111715);
            bg.setCornerRadius(999f * density);
            bg.setStroke(Math.max(1, Math.round(density)), 0x55ffffff);
            back.setBackground(bg);
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    exitNativeFeature(activity);
                }
            });
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.END);
            int statusBar = 0;
            int resId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) statusBar = activity.getResources().getDimensionPixelSize(resId);
            lp.topMargin = statusBar + baseTop;
            lp.rightMargin = margin;
            back.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                    int top = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.displayCutout()).top
                        : insets.getSystemWindowInsetTop();
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
                    if (params != null && params.topMargin != top + baseTop) {
                        params.topMargin = top + baseTop;
                        v.setLayoutParams(params);
                    }
                    return insets;
                }
            });
            decor.addView(back, lp);
            back.bringToFront();
        } catch (Throwable t) {
            android.util.Log.e("SpatchApp", "applyNativeMode failed", t);
        }
    }

    private static void mount(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (isAuthActivity(activity)) return;
        if (nativeFeatureMode) {
            applyNativeMode(activity);
            return;
        }
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            removeReturnControl(decor);
            View spotify = decor.findViewById(android.R.id.content);
            if (spotify != null) {
                spotify.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                spotify.setVisibility(View.INVISIBLE);
            }
            View existing = decor.findViewWithTag(ROOT_TAG);
            if (existing != null) {
                existing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                existing.setVisibility(View.VISIBLE);
                setBackRegistered(activity, true);
                return;
            }
            View root;
            if (load(activity)) {
                SpatchUi ui = new SpatchUi(activity);
                if (Build.VERSION.SDK_INT >= 33) {
                    synchronized (BACK_CALLBACKS) {
                        BACK_CALLBACKS.put(activity, ui.backCallback());
                    }
                    setBackRegistered(activity, true);
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
