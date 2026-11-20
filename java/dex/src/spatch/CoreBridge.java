package spatch;

import com.spotify.esperanto.esperanto.Transport;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Spatch's door into Spotify's core. Every Esperanto client is built around a transport that carries
 * protobuf bytes to a service by name ("spotify.player.esperanto.proto.ContextPlayer" / "GetState").
 * ClientBase's constructor hands its transport to {@link #wrap}, so the first call to each service
 * records which transport serves it; Rust then calls any service the way Spotify's own screens do.
 */
public final class CoreBridge implements Transport {
    private static final ConcurrentHashMap<String, Transport> SERVICES = new ConcurrentHashMap<String, Transport>();
    private static final Subscriptions STREAMS = new Subscriptions();
    /** The first native transport seen: serves services Spotify hasn't called yet. */
    private static volatile Transport fallback;
    /** A service every signed-in session uses, so its transport is known to reach the account. */
    private static final String ACCOUNT_SERVICE = "spotify.your_library_esperanto.proto.YourLibraryService";
    private final Transport inner;

    private CoreBridge(Transport inner) {
        this.inner = inner;
    }

    /** Hook: ClientBase's constructor passes its transport through here. */
    public static Transport wrap(Object transport) {
        if (!(transport instanceof Transport) || transport instanceof CoreBridge) return (Transport) transport;
        Transport t = (Transport) transport;
        String name = t.getClass().getName();
        if (name.endsWith(".NativeTransport") || (fallback == null && name.endsWith(".SchedulingTransport"))) fallback = t;
        return new CoreBridge(t);
    }

    private void seen(String service) {
        if (service != null) SERVICES.putIfAbsent(service, inner);
    }

    public Single callSingle(String service, String method, byte[] request) {
        seen(service);
        return inner.callSingle(service, method, request);
    }

    public Observable callStream(String service, String method, byte[] request) {
        seen(service);
        return inner.callStream(service, method, request);
    }

    public byte[] callSync(String service, String method, byte[] request) {
        seen(service);
        return inner.callSync(service, method, request);
    }

    private static Transport transport(String service) {
        Transport t = SERVICES.get(service);
        if (t == null) t = fallback;
        if (t == null) throw new IllegalStateException("Spotify's core isn't running yet");
        return t;
    }

    /**
     * From Rust, on its own thread (never the main one): one request, its reply. Services Spotify hasn't
     * used yet go over the account's transport (the library's) rather than whichever native one came first.
     */
    public static byte[] call(String service, String method, byte[] request) {
        return callTimed(service, method, request, 8000);
    }

    public static byte[] callTimed(String service, String method, byte[] request, long timeoutMs) {
        return (byte[]) pick(service).callSingle(service, method, request)
            .timeout(Math.max(1, Math.min(8000, timeoutMs)), TimeUnit.MILLISECONDS).blockingGet();
    }

    /** The transport Spotify used for this service, else the account's (the library's), else the first one seen. */
    private static Transport pick(String service) {
        Transport t = SERVICES.get(service);
        if (t == null) t = SERVICES.get(ACCOUNT_SERVICE);
        return t != null ? t : transport(service);
    }

    /** Rust owns the token; completion, error and cancellation release both sides. */
    public static synchronized long subscribe(String service, String method, byte[] request, final long token) {
        Subscriptions.Slot slot = STREAMS.open(token);
        try {
            Disposable d = pick(service).callStream(service, method, request).subscribe(new Consumer() {
                public void accept(Object value) {
                    nativeNext(token, (byte[]) value);
                }
            }, new Consumer() {
                public void accept(Object error) {
                    STREAMS.cancel(token);
                    nativeError(token, String.valueOf(error));
                }
            }, () -> {
                STREAMS.cancel(token);
                nativeError(token, "Stream completed");
            });
            slot.set(d);
            return token;
        } catch (RuntimeException | Error failed) {
            STREAMS.cancel(token);
            throw failed;
        }
    }

    public static void cancel(long id) {
        STREAMS.cancel(id);
    }

    static synchronized void resetSession() {
        STREAMS.clear();
        SERVICES.clear();
        try { nativeResetSession(); } catch (UnsatisfiedLinkError ignored) { /* before first mount */ }
    }

    static native void nativeResetSession();

    private static volatile Object webgate;

    /** Hook: t4c0.B's app factory, excluding vhg0's separate native-playback factory. */
    public static void webgate(Object maker, Object assertion) {
        if (assertion != null && assertion.getClass().getName().equals("p.o610")) webgate = maker;
    }

    /** Uses the exact catalog interface shipped in the pinned Spotify 9.2.0.26 APK. */
    public static byte[] search(String query, String pageToken) throws Exception {
        Object maker = webgate;
        if (maker == null) throw new IllegalStateException("Spotify’s search connection is starting");
        Class<?> api = Class.forName("p.rd51", true, maker.getClass().getClassLoader());
        Object service = maker.getClass().getMethod("createWebgateService", Class.class, String.class)
            .invoke(maker, api, "android-search-onlineproto");
        String requestId = java.util.UUID.randomUUID().toString();
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("query", query);
        params.put("limit", "20");
        params.put("entity_types", "track,album,artist,playlist,show,audio_episode");
        params.put("query_complete", "submit");
        params.put("show_type", "podcast");
        Class<?> locale = Class.forName("p.upn1", true, maker.getClass().getClassLoader());
        params.put("locale", (String) locale.getMethod("m").invoke(null));
        // Match Spotify's query-metadata mode; header mode needs BOTH x-search-* headers plus _t.
        params.put("request_id", requestId);
        params.put("page_token", pageToken);
        params.put("timestamp", Long.toString(System.currentTimeMillis()));
        java.util.Map<String, String> headers = java.util.Collections.emptyMap();
        try {
            Single call = (Single) api.getMethod("a", java.util.Map.class, java.util.Map.class).invoke(service, params, headers);
            Object response = call.timeout(12, TimeUnit.SECONDS).blockingGet();
            return (byte[]) response.getClass().getMethod("toByteArray").invoke(response);
        } catch (RuntimeException failed) {
            try {
                Class<?> httpError = Class.forName("retrofit2.HttpException", true, maker.getClass().getClassLoader());
                if (httpError.isInstance(failed)) {
                    Object response = httpError.getField("c").get(failed);
                    Object body = response == null ? null : response.getClass().getField("c").get(response);
                    if (body != null) {
                        Class<?> bodyType = Class.forName("p.uz21", true, maker.getClass().getClassLoader());
                        String detail = (String) bodyType.getMethod("f").invoke(body);
                        throw new IllegalStateException("Search " + failed.getMessage() + ": "
                            + detail.substring(0, Math.min(512, detail.length())), failed);
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Preserve the original network error if its body cannot be read.
            }
            throw failed;
        } catch (java.lang.reflect.InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failed;
        }
    }

    static String diagnostics() {
        return "Core transport " + (fallback == null ? "waiting" : "connected")
            + " · search connection " + (webgate == null ? "waiting" : "connected")
            + " · services " + SERVICES.size();
    }

    /**
     * Exact verified Spotify 9.2.0.26 creation routes:
     * - blend -> spotify:blend:invitation (p/f7b1.smali:12802, p/qv3.smali:1041)
     * - jam -> spotify:jam (p/f7b1.smali:26434, p/h1a0.smali:15)
     * - prompted -> spotify:create-prompted-playlist (p/f7b1.smali:40965, p/hgu0.smali:30)
     * - collaborative / mixed -> spotify:create-menu (p/nik1.smali:7466, p/znn.smali:39; no standalone deep link)
     */
    static String creationRoute(String kind) {
        if (kind == null) return null;
        switch (kind.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "blend": return "spotify:blend:invitation";
            case "jam": return "spotify:jam";
            case "prompted": return "spotify:create-prompted-playlist";
            case "collaborative":
            case "mixed": return "spotify:create-menu";
            default: return null;
        }
    }

    /**
     * Launches a verified native Spotify creation flow inside the signed-in Spotify app and enters
     * temporary native-feature mode so it is visible. Returns "" when launched, else an actionable error.
     */
    public static String openCreation(android.app.Activity activity, String kind) {
        String route = creationRoute(kind);
        if (route == null) {
            return "Unsupported creation flow: " + (kind == null || kind.trim().isEmpty() ? "unknown" : kind);
        }
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return "Spotify screen isn’t ready yet";
        }
        android.content.Intent intent = new android.content.Intent(
            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(route));
        intent.setPackage(activity.getPackageName());
        android.content.pm.PackageManager pm = activity.getPackageManager();
        if (pm == null || pm.resolveActivity(intent, 0) == null) {
            return "This creation option isn’t available in this Spotify build.";
        }
        SpatchApp.enterNativeFeature(activity);
        try {
            activity.startActivity(intent);
            return "";
        } catch (Throwable t) {
            SpatchApp.exitNativeFeature(activity);
            String msg = t.getMessage();
            return "Couldn’t open Spotify creation flow: "
                + (msg != null && !msg.isEmpty() ? msg : t.getClass().getSimpleName());
        }
    }

    static final String MAIN_ACTIVITY = "com.spotify.music.SpotifyMainActivity";
    static final String LOGIN_ACTIVITY = "com.spotify.login.loginflowimpl.LoginActivity";
    static final String DELEGATING_LOGIN_ACTIVITY = "com.spotify.login.loginflowimpl.DelegatingLoginActivity";
    static final int LOGIN_TASK_FLAGS =
        android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;

    /**
     * Builds Spotify 9.2.0.26's exact LoginActivity Intent (matching p.iwe0.b / p.vxf0.a):
     * - explicit LoginActivity class
     * - "intent" extra targeting SpotifyMainActivity ("spotify:home") so login returns to the app
     * - "previously_logged_in" = false, "is_web_login_flow_supported" = false, "extra_start_on_login" = false
     * - FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK (268468224) so singleTask LoginActivity
     *   starts fresh instead of hitting onNewIntent(data==null) -> finish()
     */
    static android.content.Intent loginIntent(String packageName) {
        android.content.Intent home = new android.content.Intent(
            android.content.Intent.ACTION_VIEW, android.net.Uri.parse("spotify:home"));
        home.setClassName(packageName, MAIN_ACTIVITY);
        android.content.Intent login = new android.content.Intent();
        login.setClassName(packageName, LOGIN_ACTIVITY);
        login.putExtra("intent", home);
        login.putExtra("previously_logged_in", false);
        // Spotify's own sign-in after a logout (p.a031) asks for the in-app form, not the browser flow; the browser
        // flow has to redirect back into this task, which is where Spatch's sign-in used to hang.
        login.putExtra("is_web_login_flow_supported", false);
        login.putExtra("extra_start_on_login", false);
        login.setFlags(LOGIN_TASK_FLAGS);
        return login;
    }

    /**
     * Opens Spotify's native login flow using Spotify 9.2.0.26's verified iwe0/vxf0 intent contract.
     * Falls back to DelegatingLoginActivity, then to revealing Spotify's native UI if needed.
     */
    public static String openLogin(android.app.Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return "Spotify screen isn’t ready yet";
        }
        String pkg = activity.getPackageName();
        android.content.pm.PackageManager pm = activity.getPackageManager();
        android.content.Intent login = loginIntent(pkg);
        if (pm != null && pm.resolveActivity(login, 0) != null) {
            try {
                activity.startActivity(login);
                activity.overridePendingTransition(0, 0);
                return "";
            } catch (Throwable t) {
                android.util.Log.w("SpatchCore", "Direct LoginActivity launch failed, trying delegate", t);
            }
        }
        android.content.Intent delegate = new android.content.Intent();
        delegate.setClassName(pkg, DELEGATING_LOGIN_ACTIVITY);
        delegate.setFlags(LOGIN_TASK_FLAGS);
        if (pm != null && pm.resolveActivity(delegate, 0) != null) {
            try {
                activity.startActivity(delegate);
                activity.overridePendingTransition(0, 0);
                return "";
            } catch (Throwable t) {
                android.util.Log.w("SpatchCore", "DelegatingLoginActivity launch failed, falling back to native UI", t);
            }
        }
        SpatchApp.enterNativeFeature(activity);
        return "";
    }

    /** Reveals Spotify's underlying native screen with a "Back to Spatch" return pill. */
    public static String openNativeSpotify(android.app.Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return "Spotify screen isn’t ready yet";
        }
        SpatchApp.enterNativeFeature(activity);
        return "";
    }

    /** Where downloaded covers are kept between launches (set by the UI). */
    static volatile java.io.File cacheDir;
    private static final java.util.concurrent.atomic.AtomicInteger COVER_WRITES = new java.util.concurrent.atomic.AtomicInteger();

    private static java.io.File cacheFile(String url) throws java.security.NoSuchAlgorithmException {
        java.io.File dir = cacheDir;
        if (dir == null) return null;
        byte[] hash = java.security.MessageDigest.getInstance("SHA-1").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 10; i++) name.append(String.format("%02x", hash[i]));
        return new java.io.File(dir, name.toString());
    }

    /** Keeps the cover cache under about 60 MB, dropping the least recently written files first. */
    static void pruneCache() {
        java.io.File dir = cacheDir;
        java.io.File[] files = dir == null ? null : dir.listFiles(f -> f.isFile() && !f.getName().endsWith(".part"));
        if (files == null) return;
        long total = 0;
        for (java.io.File f : files) total += f.length();
        if (total < 80L * 1024 * 1024) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (java.io.File f : files) {
            if (total < 60L * 1024 * 1024) break;
            long length = f.length();
            if (f.delete()) total -= length;
        }
    }

    /** Covers from the disk cache or the network, scaled to fit `size`; null if they can't be had. */
    static android.graphics.Bitmap artwork(String url, int size) {
        if (!url.startsWith("https://")) return null;
        try {
            java.io.File file = cacheFile(url);
            byte[] data = null;
            if (file != null && file.isFile() && file.length() > 0 && file.length() <= 8 * 1024 * 1024) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    data = new byte[(int) file.length()];
                    int got = 0;
                    for (int n; got < data.length && (n = in.read(data, got, data.length - got)) > 0;) got += n;
                    if (got != data.length) data = null;
                }
            }
            if (data == null) {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(false);
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[1 << 14];
                    for (int n; (n = in.read(buf)) > 0;) {
                        if (out.size() + n > 8 * 1024 * 1024) return null;
                        out.write(buf, 0, n);
                    }
                    data = out.toByteArray();
                } finally {
                    c.disconnect();
                }
                if (file != null) {
                    java.io.File part = java.io.File.createTempFile(file.getName(), ".part", file.getParentFile());
                    try {
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) { out.write(data); }
                        part.renameTo(file);
                    } finally {
                        part.delete();
                    }
                    if ((COVER_WRITES.incrementAndGet() & 31) == 0) pruneCache();
                }
            }
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                if (file != null) file.delete();
                return null;
            }
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / opts.inSampleSize >= size * 2) opts.inSampleSize *= 2;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (b == null) return null;
            if (b.getWidth() > size || b.getHeight() > size) {
                float k = Math.min(size / (float) b.getWidth(), size / (float) b.getHeight());
                b = android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
            }
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    static native void nativeInit();

    static native void nativeNext(long token, byte[] value);

    static native void nativeError(long token, String message);
}
