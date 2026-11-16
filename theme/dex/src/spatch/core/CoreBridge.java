package spatch;

import com.spotify.esperanto.esperanto.Transport;
import com.spotify.esperanto.esperanto.CoroutineTransport;
import java.util.concurrent.CompletableFuture;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spatch's door into Spotify's core. Every Esperanto client (ClientBase) is built around a transport
 * that carries protobuf bytes to a service by name: "spotify.player.esperanto.proto.ContextPlayer" /
 * "GetState" and so on. ClientBase's constructor hands its transport here; it comes back wrapped, so
 * the first call to each service records which transport serves it. The Rust app then calls any
 * service the same way Spotify's own UI does, with bytes in and bytes out (app/proto has the schemas).
 */
public final class CoreBridge implements Transport {
    private static final ConcurrentHashMap<String, Transport> SERVICES = new ConcurrentHashMap<String, Transport>();
    private static final ConcurrentHashMap<Long, Disposable> STREAMS = new ConcurrentHashMap<Long, Disposable>();
    private static final AtomicLong NEXT = new AtomicLong(1);
    /** The first native transport seen: serves services Spotify hasn't called yet. */
    private static volatile Transport fallback;
    private static volatile CoroutineTransport playlistTransport;
    private final Transport inner;

    public static void coroutineTransport(Object transport) {
        playlistTransport = (CoroutineTransport) transport;
    }

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
        if (service != null && !SERVICES.containsKey(service)) SERVICES.putIfAbsent(service, inner);
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

    /** From Rust, on its own thread (never the main one): one request, its reply. Throws with Spotify's error. */
    public static byte[] call(String service, String method, byte[] request) throws Exception {
        CoroutineTransport transport = playlistTransport;
        if (transport != null && service.equals("spotify.playlist_esperanto.proto.PlaylistDataService")) {
            // Spotify's own client calls the dispatching transport's callSingle with a continuation; do the same,
            // on a thread of our own with a bounded wait, and say exactly what came back if it isn't bytes.
            CompletableFuture<Object> reply = new CompletableFuture<>();
            Thread caller = new Thread(() -> {
                try {
                    Object direct = transport.callSingle(service, method, request, new p.lnm() {
                        public p.o8n getContext() { return p.o6x.a; }
                        public void resumeWith(Object value) { reply.complete(value); }
                    });
                    if (direct != p.d9n.a) reply.complete(direct);
                } catch (Throwable e) {
                    reply.completeExceptionally(e);
                }
            }, "SpatchPlaylist");
            caller.setDaemon(true);
            caller.start();
            String why;
            try {
                Object result = reply.get(10, TimeUnit.SECONDS);
                if (result instanceof byte[]) return (byte[]) result;
                why = result == null ? "null" : result.getClass().getName() + ": " + result;
            } catch (Exception e) {
                why = String.valueOf(e.getCause() != null ? e.getCause() : e);
            }
            android.util.Log.w("SpatchCore", "Playlist callSingle gave no bytes (" + why + "); trying the Rx transport");
            try {
                return (byte[]) transport(service).callSingle(service, method, request).timeout(8, TimeUnit.SECONDS).blockingGet();
            } catch (RuntimeException rx) {
                android.util.Log.w("SpatchCore", "Playlist via Rx failed too: " + rx);
                throw new IllegalStateException("Playlist service: " + why);
            }
        }
        return (byte[]) transport(service).callSingle(service, method, request).timeout(8, TimeUnit.SECONDS).blockingGet();
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

    /** Spotify's native Cosmos router (sp:// and hm:// requests through the core's own connection). */
    private static volatile Object router;

    /** Hook: RemoteNativeRxRouter's constructor. */
    public static void router(Object r) {
        if (router == null && r instanceof com.spotify.cosmos.rxrouter.RxRouter) router = r;
    }

    /** From Rust, off the main thread: one Cosmos request; the body of the first reply. Throws on a non-2xx status. */
    public static byte[] request(String action, String uri, byte[] body) {
        Object r = router;
        if (r == null) throw new IllegalStateException("Spotify's router isn't running yet");
        com.spotify.cosmos.cosmos.Response reply = (com.spotify.cosmos.cosmos.Response)
            ((com.spotify.cosmos.rxrouter.RxRouter) r).resolve(new com.spotify.cosmos.cosmos.Request(action, uri, body)).blockingFirst();
        if (reply.getStatus() / 100 != 2) throw new IllegalStateException(action + " " + uri + " answered " + reply.getStatus());
        byte[] out = reply.getBody();
        return out == null ? new byte[0] : out;
    }

    /** Decodes artwork off the UI thread and scales it to the requested size. */
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

    /** Services seen so far, for the developer log. */
    public static String services() {
        return new java.util.TreeSet<String>(SERVICES.keySet()).toString() + (fallback == null ? " (no native transport yet)" : "") + (router == null ? " (no router yet)" : "");
    }

    static native void nativeInit();

    static native void nativeNext(long token, byte[] value);

    static native void nativeError(long token, String message);
}
