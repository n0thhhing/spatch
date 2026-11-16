package spatch;

import android.content.Context;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import java.util.LinkedHashSet;

/**
 * Loudness of Spotify's own playback session, for the immersive player's glow, disc and motes.
 * Spotify's AudioEffectsListener reports its sessions here; a Visualizer on that session gives an
 * FFT (no microphone, no output-mix capture). Capture runs on its own thread only while a screen
 * asks for it, and everything reads zero when it can't run, so visuals fall back to their idle motion.
 */
public final class Audio {
    static final String PERMISSION = "android.permission.RECORD_AUDIO";
    private static final LinkedHashSet<Integer> SESSIONS = new LinkedHashSet<Integer>();
    private static volatile int session;
    /** The application, set by the UI; capture checks the permission against it. */
    static volatile Context context;

    /** Hooks: AudioEffectsListener's session opened, closed, paused or resumed. */
    public static synchronized void created(int id) { if (id > 0) { SESSIONS.add(id); session = id; } }
    public static synchronized void destroyed(int id) {
        SESSIONS.remove(id);
        if (session == id) { session = 0; for (int left : SESSIONS) session = left; }
    }
    public static synchronized void paused(boolean paused, int id) { if (!paused && SESSIONS.contains(id)) session = id; }

    /** 0..1, eased: low end, whole spectrum, and the strength of the last kick (decays to 0). */
    static volatile float bass, level, kick;
    /** When the last kick landed (uptime ms), 0 if never. */
    static volatile long kickAt;

    private static android.os.Handler worker;
    private static volatile long wantedUntil;
    private static Visualizer visualizer;
    private static int capturing;
    private static byte[] fft;
    private static long sampled, retryAt;
    private static float average;

    static boolean permitted(Context context) {
        return context.checkSelfPermission(PERMISSION) == 0;
    }

    /** A visible screen wants levels for the next second; call it from a frame callback. */
    static void want() {
        long now = SystemClock.uptimeMillis();
        boolean idle = wantedUntil < now;
        wantedUntil = now + 1000;
        if (idle) worker().post(TICK);
    }

    private static android.os.Handler worker() {
        if (worker == null) {
            android.os.HandlerThread thread = new android.os.HandlerThread("SpatchAudio");
            thread.start();
            worker = new android.os.Handler(thread.getLooper());
        }
        return worker;
    }

    private static final Runnable TICK = new Runnable() {
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (now > wantedUntil || !sample(now)) {
                release();
                bass = level = kick = 0;
                return;
            }
            worker.postDelayed(this, 16);
        }
    };

    /** One capture; false stops the loop (nothing playing, no permission, or the session refused). */
    private static boolean sample(long now) {
        int id = session;
        if (id <= 0 || context == null || !permitted(context)) return false;
        if (capturing != id) { release(); capturing = id; retryAt = 0; }
        if (visualizer == null && now < retryAt) return true;
        try {
            if (visualizer == null) {
                visualizer = new Visualizer(id);
                int[] range = Visualizer.getCaptureSizeRange();
                int size = Math.max(range[0], Math.min(1024, range[1]));
                if (visualizer.setCaptureSize(size) != 0 || visualizer.setEnabled(true) != 0) throw new IllegalStateException();
                fft = new byte[size];
            }
            if (visualizer.getFft(fft) != 0) throw new IllegalStateException();
            float dt = sampled == 0 ? 16 : Math.min(100, now - sampled);
            sampled = now;
            int rate = visualizer.getSamplingRate() / 1000;
            float low = band(fft, rate, 40, 160), all = band(fft, rate, 40, 12000);
            bass += (low - bass) * (1 - (float) Math.exp(-dt / (low > bass ? 18f : 120f)));
            level += (all - level) * (1 - (float) Math.exp(-dt / (all > level ? 30f : 200f)));
            average += (low - average) * (1 - (float) Math.exp(-dt / 900f));
            // A kick: the low end jumps well above its recent average, at most ~4 per second.
            if (low > .18f && low > average * 1.32f && now - kickAt > 240) {
                kickAt = now;
                kick = Math.min(1f, (low - average) * 3f + .35f);
            }
            kick *= (float) Math.exp(-dt / 260f);
            return true;
        } catch (RuntimeException e) {
            release();
            retryAt = now + 2000;
            return true;
        }
    }

    /** Band energy between two frequencies (Hz) as a 0..1 display level (dB, silence at 0). */
    static float band(byte[] fft, int rateHz, float fromHz, float toHz) {
        if (rateHz <= 0 || fft.length < 4) return 0;
        int n = fft.length, max = n / 2 - 1;
        int start = Math.max(1, Math.round(fromHz * n / rateHz)), end = Math.min(max, Math.max(start, Math.round(toHz * n / rateHz)));
        double power = 0;
        for (int k = start; k <= end; k++) {
            double re = fft[k * 2], im = fft[k * 2 + 1];
            power += (re * re + im * im) / (128.0 * 128.0);
        }
        power /= (end - start + 1);
        return power <= 0 ? 0 : Math.max(0, Math.min(1, (float) ((10 * Math.log10(power) + 48) / 44)));
    }

    private static void release() {
        if (visualizer != null) {
            try { visualizer.release(); } catch (RuntimeException ignored) { /* the audio server already dropped it */ }
        }
        visualizer = null;
        sampled = 0;
    }
}
