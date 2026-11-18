package spatch;

import com.spotify.esperanto.esperanto.Transport;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spatch's door into Spotify's core. Every Esperanto client is built around a transport that carries
 * protobuf bytes to a service by name ("spotify.player.esperanto.proto.ContextPlayer" / "GetState").
 * ClientBase's constructor hands its transport to {@link #wrap}, so the first call to each service
 * records which transport serves it; Rust then calls any service the way Spotify's own screens do.
 */
public final class CoreBridge implements Transport {
    private static final ConcurrentHashMap<String, Transport> SERVICES = new ConcurrentHashMap<String, Transport>();
    private static final ConcurrentHashMap<Long, Disposable> STREAMS = new ConcurrentHashMap<Long, Disposable>();
    private static final AtomicLong NEXT = new AtomicLong(1);
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
        return (byte[]) pick(service).callSingle(service, method, request).timeout(8, TimeUnit.SECONDS).blockingGet();
    }

    /** The transport Spotify used for this service, else the account's (the library's), else the first one seen. */
    private static Transport pick(String service) {
        Transport t = SERVICES.get(service);
        if (t == null) t = SERVICES.get(ACCOUNT_SERVICE);
        return t != null ? t : transport(service);
    }

    /** From Rust: replies stream to nativeNext(token, bytes) until cancel(id) or an error (nativeError). */
    public static long subscribe(String service, String method, byte[] request, final long token) {
        final long id = NEXT.getAndIncrement();
        Disposable d = pick(service).callStream(service, method, request).subscribe(new Consumer() {
            public void accept(Object value) {
                nativeNext(token, (byte[]) value);
            }
        }, new Consumer() {
            public void accept(Object error) {
                STREAMS.remove(id);
                nativeError(token, String.valueOf(error));
            }
        });
        STREAMS.put(id, d);
        return id;
    }

    public static void cancel(long id) {
        Disposable d = STREAMS.remove(id);
        if (d != null) d.dispose();
    }

    private static volatile Object webgate;

    /** Hook: the authenticated Retrofit factory used by Spotify's online screens. */
    public static void webgate(Object maker) { webgate = maker; }

    public static Object searchService(Class<?> api, Object service) {
        if (!api.getName().equals("p.rd51")) return service;
        return java.lang.reflect.Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
            if (args != null && args.length >= 2 && args[0] instanceof java.util.Map) {
                java.util.Map params = (java.util.Map) args[0];
                if ("Radiohead".equals(params.get("query")))
                    android.util.Log.i("SpatchSearch", "Native params " + params + " header names " + ((java.util.Map) args[1]).keySet());
            }
            try { return method.invoke(service, args); }
            catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
        });
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
        params.put("limit", "30");
        params.put("entity_types", "track,album,artist,playlist,show,audio_episode");
        params.put("query_complete", "submit");
        params.put("show_type", "podcast");
        params.put("on_demand_sets_enabled", "true");
        params.put("locale", java.util.Locale.getDefault().toString());
        params.put("request_id", requestId);
        params.put("page_token", pageToken);
        params.put("timestamp", Long.toString(System.currentTimeMillis()));
        java.util.Map<String, String> headers = java.util.Collections.singletonMap("x-search-request-id", requestId);
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

    /** Where downloaded covers are kept between launches (set by the UI). */
    static volatile java.io.File cacheDir;

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
        java.io.File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (java.io.File f : files) total += f.length();
        if (total < 80L * 1024 * 1024) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (java.io.File f : files) {
            if (total < 60L * 1024 * 1024) break;
            total -= f.length();
            f.delete();
        }
    }

    /** Covers from the disk cache or the network, scaled to fit `size`; null if they can't be had. */
    static android.graphics.Bitmap artwork(String url, int size) {
        if (!url.startsWith("https://")) return null;
        try {
            java.io.File file = cacheFile(url);
            byte[] data = null;
            if (file != null && file.isFile()) {
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
                    java.io.File part = new java.io.File(file.getPath() + ".part");
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                        out.write(data);
                    }
                    if (!part.renameTo(file)) part.delete();
                }
            }
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
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
