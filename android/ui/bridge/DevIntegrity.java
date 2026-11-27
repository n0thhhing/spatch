package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.spotify.integrity.integrityimpl.proto.VerifyRequest;
import com.spotify.integrity.integrityimpl.proto.VerifyResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/** Always-on integrity experiment for the pinned Spotify build: it suppresses verification and records what happened. */
public final class DevIntegrity {
    private static final String TAG = "SpatchIntegrity";
    private static final String ROUTE = "pitoken/spotify.pitoken.v1.VerifyService/Verify";
    private static volatile SharedPreferences prefs;
    private static volatile byte[][] digests = new byte[0][];

    private static volatile Context app;
    private static final long SNAPSHOT_MS = 120_000L;

    public static void init(Context context) {
        prefs = context.getSharedPreferences("spatch_ui", Context.MODE_PRIVATE);
        // attachBaseContext runs before the Application exists, so getApplicationContext() can be null here.
        Context resolved = context.getApplicationContext();
        app = resolved != null ? resolved : context;
        startSnapshots();
    }

    /** Writes this app's own logcat to a file a tester can send, so a remote failure is still diagnosable. */
    private static void startSnapshots() {
        final Context c = app;
        if (c == null) return;
        Thread t = new Thread(() -> {
            try { Thread.sleep(20_000L); } catch (InterruptedException stopped) { return; } // let startup settle
            while (true) {
                snapshot(c);
                try { Thread.sleep(SNAPSHOT_MS); } catch (InterruptedException stopped) { return; }
            }
        }, "spatch-integrity-snapshot");
        t.setDaemon(true);
        t.start();
    }

    private static java.io.File logFile() {
        Context c = app;
        if (c == null) return null;
        java.io.File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new java.io.File(dir, "integrity.log");
    }

    /** One line for the diagnostics sheet: the sheet is too small to hold the whole log. */
    public static String summary() {
        java.io.File file = logFile();
        if (file == null) return "Integrity log: not started";
        return "Integrity log " + file.length() + " bytes · Copy diagnostics includes it";
    }

    /**
     * The verdicts so far, for the copy button. Kept in its own append-only file because the
     * snapshot dump rolls over and would lose the startup lines a chat-sized paste needs.
     */
    public static String report() {
        java.io.File file = verdictFile();
        if (file == null) return "\nIntegrity: not started";
        try {
            String all = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            int cap = 1200;
            return all.length() > cap ? all.substring(all.length() - cap) : all;
        } catch (Exception failed) {
            return "\nIntegrity: " + failed;
        }
    }

    private static void snapshot(Context c) {
        java.io.File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir(); // external storage can be unmounted
        java.io.File out = new java.io.File(dir, "integrity.log");
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-v", "time", "--pid=" + android.os.Process.myPid()).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] chunk = new byte[8192];
                for (int n; (n = in.read(chunk)) > 0; ) buffer.write(chunk, 0, n);
            }
            // Only replace the file once the dump has content, so a failed read keeps the last good log.
            if (buffer.size() > 0) {
                try (java.io.FileOutputStream file = new java.io.FileOutputStream(out, false)) {
                    buffer.writeTo(file);
                }
            }
        } catch (Exception failed) {
            Log.w(TAG, "snapshot failed: " + failed);
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static java.io.File verdictFile() {
        Context c = app;
        if (c == null) return null;
        java.io.File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return new java.io.File(dir, "integrity-verdicts.txt");
    }

    private static void verdict(String line) {
        java.io.File file = verdictFile();
        if (file == null) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
            out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // The log still has it; the copy button is the convenience, not the record.
        }
    }

    private static boolean enabled(String key) {
        SharedPreferences p = prefs;
        return p == null || p.getBoolean(key, true);
    }

    /** Always on: this build is the experiment, so every hook reports. */
    private static boolean observing() {
        return true;
    }

    public static void pluginConstructed() {
        if (observing()) Log.i(TAG, "plugin_constructed");
        verdict("plugin_constructed");
    }

    public static void pluginStarted() {
        if (observing()) Log.i(TAG, "plugin_started");
        verdict("plugin_started");
    }

    public static void validation(boolean value) {
        if (observing()) Log.i(TAG, "validation_enabled=" + value);
    }

    public static void employeeValidation(boolean value) {
        if (observing()) Log.i(TAG, "employee_validation_enabled=" + value);
    }

    /** Logs the day guard; the patch also always falls through so a fresh verify runs on every start. */
    public static void recheck(int savedDay, int deviceDay) {
        Log.i(TAG, "recheck_forced saved_day=" + savedDay + " device_day=" + deviceDay);
        verdict("recheck_forced saved_day=" + savedDay + " device_day=" + deviceDay);
    }

    public static void scheduled(int savedDay) {
        if (observing()) Log.i(TAG, "saved_attempt_day=" + savedDay + " device_day="
            + java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR));
    }

    public static void tokenStarted() {
        if (observing()) Log.i(TAG, "token_acquisition_entered");
    }

    public static void tokenOutcome(Object value) {
        if (observing()) Log.i(TAG, "token_result_class=" + (value == null ? "null" : value.getClass().getName()));
    }

    /** Preserve the request and continuation unchanged unless an experiment is selected. */
    public static Object verify(p.dmt0 service, VerifyRequest request, p.osm continuation) throws ReflectiveOperationException {
        // A null prefs must not escape as an NPE: the caller would record a permanent error for this request.
        SharedPreferences p = prefs;
        // 1 by default now: a blank local reply alone left playback broken, so forward the verify with an
        // empty token instead and let Spotify see a call. 0 restores the blank reply.
        int variant = p == null ? 1 : p.getInt("integrity_variant", 1);
        boolean skip = enabled("integrity_skip");
        boolean empty = skip && variant == 1;
        int originalSize = request.getSerializedSize();
        Log.i(TAG, "verify_reached skip=" + skip + " variant=" + variant + " bytes=" + originalSize);
        verdict("verify_reached variant=" + variant + " bytes=" + originalSize);
        if (skip && !empty) {
            // A local fake answer, so Spotify never sees this client's failing verdict.
            Log.i(TAG, ROUTE + " mode=skip request_bytes=" + originalSize + " forwarded=false");
            verdict("mode=skip forwarded=false");
            return VerifyResponse.n();
        }
        if (empty) {
            // The generated builder's names are pinned to 9.2.0.2053; copy to preserve the original and unknown fields.
            Object builder = request.getClass().getMethod("toBuilder").invoke(request);
            builder.getClass().getMethod("r", String.class).invoke(builder, "");
            request = (VerifyRequest) builder.getClass().getMethod("build").invoke(builder);
        }
        Log.i(TAG, ROUTE + " mode=" + (empty ? "empty-token" : "forward") + " request_bytes=" + originalSize
            + " sent_bytes=" + request.getSerializedSize() + " forwarded=true");
        verdict("mode=" + (empty ? "empty-token" : "forward") + " forwarded=true");
        return service.a(request, continuation);
    }

    /** Runs after the verifier has consumed the response, including after a coroutine resumes. */
    public static void accepted() {
        if (observing()) Log.i(TAG, ROUTE + " caller_result=accepted");
        verdict("caller_result=accepted");
    }

    /** Do not log exception messages: they may contain tokens or request contents. */
    public static void failure(Throwable error) {
        verdict("caller_error=" + error.getClass().getSimpleName());
        if (observing()) Log.i(TAG, ROUTE + " caller_error=" + error.getClass().getName());
    }

    public static void signature(String value) {
        digests = patterns(value);
        if (enabled("integrity_trace")) Log.i(TAG, "self_signature_sha1=" + value + " callers=" + callers());
    }

    static byte[][] patterns(String value) {
        ArrayList<byte[]> out = new ArrayList<>();
        if (value == null) return new byte[0][];
        for (String digest : value.split(":")) {
            if (!digest.matches("[0-9a-fA-F]{40}")) continue;
            out.add(digest.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
            out.add(digest.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
            byte[] raw = new byte[20];
            for (int i = 0; i < raw.length; i++) raw[i] = (byte) Integer.parseInt(digest.substring(i * 2, i * 2 + 2), 16);
            out.add(raw);
        }
        return out.toArray(new byte[0][]);
    }

    static boolean contains(byte[] body, int length, byte[][] patterns) {
        if (body == null || patterns == null || length < 0 || length > body.length) return false;
        for (byte[] pattern : patterns) {
            if (pattern == null || pattern.length == 0) continue;
            for (int i = 0; i <= length - pattern.length; i++) {
                int j = 0;
                while (j < pattern.length && body[i + j] == pattern[j]) j++;
                if (j == pattern.length) return true;
            }
        }
        return false;
    }

    // shortcut: scans raw/hex digests at observed senders; extend when another encoding or sender is found.
    public static void body(byte[] bytes, int length) {
        if (!enabled("integrity_trace")) return;
        byte[][] known = digests;
        Log.i(TAG, "http-byte-body bytes=" + length + " digest_match="
            + (known.length == 0 ? "unknown-no-read" : contains(bytes, length, known)) + " callers=" + callers());
    }

    /** Serialized event handed to Spotify's publisher; this does not establish server delivery. */
    public static void event(String name, byte[] bytes) {
        outbound("event/" + name, bytes);
    }

    static void outbound(String route, byte[] bytes) {
        if (!enabled("integrity_trace")) return;
        byte[][] known = digests;
        Log.i(TAG, route + " bytes=" + (bytes == null ? 0 : bytes.length) + " digest_match="
            + (known.length == 0 ? "unknown-no-read" : contains(bytes, bytes == null ? 0 : bytes.length, known)));
    }

    private static String callers() {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if (frame.getClassName().equals(DevIntegrity.class.getName())) continue;
            if (out.length() > 0) out.append(" <- ");
            out.append(frame.getClassName()).append('.').append(frame.getMethodName());
            if (out.length() > 700) break;
        }
        return out.toString();
    }
}
