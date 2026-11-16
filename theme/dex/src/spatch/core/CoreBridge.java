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

    /** Hook: the account's coroutine transport; nothing needs it now that playlists use the account's Rx transport. */
    public static void coroutineTransport(Object transport) {}

    /** Hook: Spotify's Cosmos router; kept so the hook stays valid, unused by the UI. */
    public static void router(Object router) {}

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
        Transport t = SERVICES.get(service);
        if (t == null) t = SERVICES.get(ACCOUNT_SERVICE);
        if (t == null) t = transport(service);
        return (byte[]) t.callSingle(service, method, request).timeout(8, TimeUnit.SECONDS).blockingGet();
    }

    /** From Rust: replies stream to nativeNext(token, bytes) until cancel(id) or an error (nativeError). */
    public static long subscribe(String service, String method, byte[] request, final long token) {
        final long id = NEXT.getAndIncrement();
        Disposable d = transport(service).callStream(service, method, request).subscribe(new Consumer() {
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

    /** Downloads artwork off the UI thread and scales it to fit `size`; null if it can't be had. */
    static android.graphics.Bitmap artwork(String url, int size) {
        if (!url.startsWith("https://")) return null;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            byte[] data;
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
