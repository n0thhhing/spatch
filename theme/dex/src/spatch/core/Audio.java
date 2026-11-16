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
    /** 48 log-spaced bands from 40 Hz to 16 kHz, 0..1; read by the spectrum ring. */
    static final float[] BANDS = new float[48];

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

    /** What the capture is doing, for Settings: it says why the bars aren't following the music. */
    static volatile String status = "Not started";
    private static boolean looping;

    private static void say(String text) {
        if (!text.equals(status)) {
            status = text;
            android.util.Log.i("SpatchAudio", text);
        }
    }

    /** A visible screen wants levels for the next second; call it from a frame callback. */
    static void want() {
        wantedUntil = SystemClock.uptimeMillis() + 1000;
        synchronized (Audio.class) {
            if (looping) return;
            looping = true;
        }
        worker().post(TICK);
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
            if (now > wantedUntil) {
                // Nobody has asked for a second: stop capturing until they do.
                release();
                bass = level = kick = 0;
                java.util.Arrays.fill(BANDS, 0);
                say("Idle");
                synchronized (Audio.class) { looping = false; }
                return;
            }
            // While nothing can be captured yet (no session, no permission) keep looking, slowly, rather than giving up.
            boolean ok = sample(now);
            if (!ok) {
                bass = level = kick = 0;
                java.util.Arrays.fill(BANDS, 0);
            }
            worker.postDelayed(this, ok ? 16 : 400);
        }
    };

    /** One capture; false stops the loop (nothing playing, no permission, or the session refused). */
    private static boolean sample(long now) {
        int id = session;
        if (context == null || !permitted(context)) { say("Needs the microphone permission"); return false; }
        if (id <= 0) { say("Waiting for Spotify to start playing"); return false; }
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
            say("Listening to Spotify (audio session " + id + ")");
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
            analyse(rate, dt / 1000f, now);
            return true;
        } catch (RuntimeException e) {
            say("Couldn’t read the audio: " + e);
            release();
            retryAt = now + 2000;
            return true;
        }
    }

    // The analysis is Swiftify's (spotify-core/src/spectrum.rs), on Android's FFT: band *amplitudes*, linear, not dB,
    // divided by one slow shared reference so balance between bands survives and a hit still lands tall, then a soft
    // shoulder. It states plainly that scaling dB flattens dynamics into a wall of equal bars.
    private static final float MIN_REFERENCE = .05f, INITIAL_REFERENCE = .25f, KNEE = 1.8f;
    private static float reference = INITIAL_REFERENCE;
    private static final float[] AMPLITUDE = new float[48];
    private static long loggedAt;

    private static void analyse(int rate, float seconds, long now) {
        float strongest = 0;
        for (int i = 0; i < BANDS.length; i++) {
            float from = 40f * (float) Math.pow(400, i / (double) BANDS.length), to = 40f * (float) Math.pow(400, (i + 1) / (double) BANDS.length);
            AMPLITUDE[i] = bandAmplitude(fft, rate, from, to);
            strongest = Math.max(strongest, AMPLITUDE[i]);
        }
        if (strongest > .005f) {
            float target = Math.max(strongest, MIN_REFERENCE);
            float tc = target > reference ? .35f : 3f;
            reference += (target - reference) * (1 - (float) Math.exp(-seconds / tc));
        }
        float attack = 1 - (float) Math.exp(-seconds / .008f), release = 1 - (float) Math.exp(-seconds / .065f);
        float tallest = 0;
        for (int i = 0; i < BANDS.length; i++) {
            float relative = Math.max(0f, AMPLITUDE[i] - .0001f) / reference;
            float target = 1f - (float) Math.exp(-KNEE * Math.pow(relative, .85));
            BANDS[i] += (target - BANDS[i]) * (target > BANDS[i] ? attack : release);
            if (BANDS[i] < .0001f) BANDS[i] = 0;
            tallest = Math.max(tallest, BANDS[i]);
        }
        if (now - loggedAt > 3000) {
            loggedAt = now;
            android.util.Log.i("SpatchAudio", String.format(java.util.Locale.ROOT, "strongest band %.3f, reference %.3f, tallest bar %.2f", strongest, reference, tallest));
        }
    }

    /** Amplitude of the bins between two frequencies: root of their summed power, 0..~1. */
    private static float bandAmplitude(byte[] fft, int rateHz, float fromHz, float toHz) {
        if (rateHz <= 0 || fft.length < 4) return 0f;
        int n = fft.length, max = n / 2 - 1;
        int start = Math.max(1, Math.min(max, Math.round(fromHz * n / rateHz))), end = Math.min(max, Math.max(start, Math.round(toHz * n / rateHz)));
        double power = 0;
        for (int k = start; k <= end; k++) {
            double re = fft[k * 2], im = fft[k * 2 + 1];
            power += (re * re + im * im) / (128.0 * 128.0);
        }
        return (float) Math.sqrt(power);
    }

    /** Band energy between two frequencies (Hz) in dB (about 0 for full scale, -90 for silence). */
    static float bandDb(byte[] fft, int rateHz, float fromHz, float toHz) {
        if (rateHz <= 0 || fft.length < 4) return -90f;
        int n = fft.length, max = n / 2 - 1;
        int start = Math.max(1, Math.round(fromHz * n / rateHz)), end = Math.min(max, Math.max(start, Math.round(toHz * n / rateHz)));
        double power = 0;
        for (int k = start; k <= end; k++) {
            double re = fft[k * 2], im = fft[k * 2 + 1];
            power += (re * re + im * im) / (128.0 * 128.0);
        }
        power /= (end - start + 1);
        return power <= 1e-9 ? -90f : (float) Math.max(-90.0, 10 * Math.log10(power));
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
        reference = INITIAL_REFERENCE;
    }
}
