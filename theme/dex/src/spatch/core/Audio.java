package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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
    public static synchronized void created(int id) {
        if (id > 0) {
            SESSIONS.add(id);
            session = id;
            refreshEqualizer();
        }
    }
    public static synchronized void destroyed(int id) {
        SESSIONS.remove(id);
        if (session == id) { session = 0; for (int left : SESSIONS) session = left; }
        refreshEqualizer();
    }
    public static synchronized void paused(boolean paused, int id) {
        if (!paused && SESSIONS.contains(id)) {
            session = id;
            refreshEqualizer();
        }
    }

    static final int[] EQ_HZ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final int EQ_RANGE = 12;
    private static final Map<Integer, DynamicsProcessing> effects = new HashMap<>();
    private static SharedPreferences eqPrefs;
    static volatile String eqStatus = "";

    static synchronized void initialize(Context app, SharedPreferences prefs) {
        context = app;
        eqPrefs = prefs;
        refreshEqualizer();
    }

    static synchronized void refreshEqualizer() {
        if (eqPrefs == null) return;
        android.os.Handler handler = worker();
        handler.removeCallbacks(EQUALIZE);
        handler.post(EQUALIZE);
    }

    static int gain(SharedPreferences prefs, int band) {
        return Math.max(-EQ_RANGE, Math.min(EQ_RANGE, prefs.getInt("eq_band_" + band, 0)));
    }

    private static final Runnable EQUALIZE = () -> {
        LinkedHashSet<Integer> sessions;
        SharedPreferences prefs;
        int active;
        synchronized (Audio.class) {
            sessions = new LinkedHashSet<>(SESSIONS);
            prefs = eqPrefs;
            active = session;
        }
        boolean on = prefs.getBoolean("equalizer", false);
        Iterator<Map.Entry<Integer, DynamicsProcessing>> entries = effects.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, DynamicsProcessing> entry = entries.next();
            if (!on || !sessions.contains(entry.getKey())) {
                releaseEffect(entry.getValue());
                entries.remove();
            }
        }
        if (!on || sessions.isEmpty()) {
            eqStatus = on ? "Start playback on this device to apply the equalizer" : "Equalizer off";
            return;
        }
        int boost = 0;
        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, EQ_HZ.length);
        for (int band = 0; band < EQ_HZ.length; band++) {
            int gain = gain(prefs, band);
            boost = Math.max(boost, gain);
            eq.setBand(band, new DynamicsProcessing.EqBand(true, EQ_HZ[band], gain));
        }
        for (int id : sessions) {
            DynamicsProcessing effect = effects.get(id);
            try {
                if (effect == null) {
                    DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        true, EQ_HZ.length, false, 0, false, 0, false).build();
                    effect = new DynamicsProcessing(0, id, config);
                    effects.put(id, effect);
                }
                effect.setInputGainAllChannelsTo(-boost);
                effect.setPreEqAllChannelsTo(eq);
                if (effect.setEnabled(true) != 0) throw new IllegalStateException("Equalizer unavailable");
            } catch (RuntimeException unavailable) {
                releaseEffect(effects.remove(id));
            }
        }
        eqStatus = effects.containsKey(active) ? "Active on this device" : "Equalizer unavailable for this playback session";
    };

    private static void releaseEffect(DynamicsProcessing effect) {
        if (effect != null) {
            try { effect.release(); } catch (RuntimeException ignored) { /* the audio server already dropped it */ }
        }
    }

    /** 0..1, eased: low end, whole spectrum, and the strength of the last kick (decays to 0). */
    static volatile float bass, level, kick;
    /** When the last kick landed (uptime ms), 0 if never. */
    static volatile long kickAt;
    /** 24 log-spaced bands from 50 Hz to 16 kHz, 0..1, eased; read by the spectrum ring. */
    static final float[] BANDS = new float[24];

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

    private static synchronized android.os.Handler worker() {
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
                java.util.Arrays.fill(BANDS, 0);
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
            for (int i = 0; i < BANDS.length; i++) {
                float from = 50f * (float) Math.pow(320, i / (float) BANDS.length), to = 50f * (float) Math.pow(320, (i + 1) / (float) BANDS.length);
                float target = band(fft, rate, from, to);
                BANDS[i] += (target - BANDS[i]) * (1 - (float) Math.exp(-dt / (target > BANDS[i] ? 22f : 140f)));
            }
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
