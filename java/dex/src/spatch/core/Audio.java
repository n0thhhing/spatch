package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
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
    private static final Map<Integer, AudioEffect> effects = new HashMap<>();
    private static SharedPreferences eqPrefs;
    static volatile String eqStatus = "";
    static volatile String eqError = "";

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
        Iterator<Map.Entry<Integer, AudioEffect>> entries = effects.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, AudioEffect> entry = entries.next();
            if (!on || !sessions.contains(entry.getKey())) {
                releaseEffect(entry.getValue());
                entries.remove();
            }
        }
        if (!on || sessions.isEmpty()) {
            eqStatus = on ? "Start playback on this device to apply the equalizer" : "Equalizer off";
            return;
        }
        int[] gains = new int[EQ_HZ.length];
        int boost = 0;
        for (int band = 0; band < gains.length; band++) {
            gains[band] = gain(prefs, band);
            boost = Math.max(boost, gains[band]);
        }
        int activeBands = 0;
        for (int id : sessions) {
            AudioEffect effect = effects.get(id);
            try {
                if (!(effect instanceof Equalizer)) {
                    try {
                        if (effect == null) {
                            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                                true, EQ_HZ.length, false, 0, false, 0, false).build();
                            effect = new DynamicsProcessing(0, id, config);
                        }
                        if (!effect.hasControl()) throw new IllegalStateException("DynamicsProcessing is controlled by another equalizer");
                        DynamicsProcessing dynamics = (DynamicsProcessing) effect;
                        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, EQ_HZ.length);
                        for (int band = 0; band < gains.length; band++) {
                            eq.setBand(band, new DynamicsProcessing.EqBand(true, EQ_HZ[band], gains[band]));
                        }
                        dynamics.setInputGainAllChannelsTo(-boost);
                        dynamics.setPreEqAllChannelsTo(eq);
                    } catch (RuntimeException unavailable) {
                        eqError = unavailable.toString();
                        releaseEffect(effect);
                        effect = new Equalizer(0, id);
                    }
                }
                if (!effect.hasControl()) throw new IllegalStateException("The device equalizer is controlled by another app");
                if (effect instanceof Equalizer) {
                    Equalizer equalizer = (Equalizer) effect;
                    short[] range = equalizer.getBandLevelRange();
                    short count = equalizer.getNumberOfBands();
                    if (id == active) activeBands = count;
                    if (count <= 0) throw new IllegalStateException("The device equalizer exposes no bands");
                    for (short band = 0; band < count; band++) {
                        float hz = equalizer.getCenterFreq(band) / 1000f;
                        int level = Math.round((gainAt(hz, gains) - boost) * 100);
                        equalizer.setBandLevel(band, (short) Math.max(range[0], Math.min(range[1], level)));
                    }
                }
                if (effect.setEnabled(true) != AudioEffect.SUCCESS) throw new IllegalStateException("The audio effect could not be enabled");
                effects.put(id, effect);
            } catch (RuntimeException unavailable) {
                eqError = unavailable.toString();
                android.util.Log.w("SpatchAudio", "Equalizer session " + id + ": " + eqError);
                effects.remove(id);
                releaseEffect(effect);
            }
        }
        AudioEffect current = effects.get(active);
        eqStatus = current instanceof Equalizer ? "Active · " + activeBands + " device bands"
            : current != null ? "Active · 10 bands" : "Waiting for audio effects · retrying";
        if (effects.size() < sessions.size()) worker().postDelayed(Audio.EQUALIZE, 2000);
    };

    /** Interpolate the saved curve on a device's own band centres. */
    static float gainAt(float hz, int[] gains) {
        if (hz <= EQ_HZ[0]) return gains[0];
        for (int band = 1; band < EQ_HZ.length; band++) {
            if (hz <= EQ_HZ[band]) {
                double fraction = Math.log((double) hz / EQ_HZ[band - 1]) / Math.log((double) EQ_HZ[band] / EQ_HZ[band - 1]);
                return (float) (gains[band - 1] + fraction * (gains[band] - gains[band - 1]));
            }
        }
        return gains[gains.length - 1];
    }

    private static void releaseEffect(AudioEffect effect) {
        if (effect != null) {
            try { effect.release(); } catch (RuntimeException ignored) { /* the audio server already dropped it */ }
        }
    }

    /** 0..1, eased: low end, whole spectrum, and the strength of the last kick (decays to 0). */
    static volatile float bass, level, kick;
    /** 48 log-spaced bands from 40 Hz to 16 kHz, 0..1; read by the spectrum ring. */
    static final float[] BANDS = new float[48];

    private static android.os.Handler worker;
    private static volatile long wantedUntil;
    private static Visualizer visualizer;
    private static int capturing;
    private static byte[] fft;
    private static long sampled, retryAt;

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
            nativeAnalyse(fft, visualizer.getSamplingRate() / 1000, dt, now, OUT);
            System.arraycopy(OUT, 0, BANDS, 0, BANDS.length);
            bass = OUT[48]; level = OUT[49]; kick = OUT[50];
            if (now - loggedAt > 3000) {
                loggedAt = now;
                android.util.Log.i("SpatchAudio", String.format(java.util.Locale.ROOT, "strongest band %.3f, reference %.3f", OUT[51], OUT[52]));
            }
            return true;
        } catch (RuntimeException e) {
            say("Couldn’t read the audio: " + e);
            release();
            retryAt = now + 2000;
            return true;
        }
    }

    // The analysis is in Rust (app/src/spectrum.rs): 48 bands, then bass, level, kick, strongest band and reference.
    private static native void nativeAnalyse(byte[] fft, int rateHz, float dtMs, long nowMs, float[] out);
    private static native void nativeReset();
    private static final float[] OUT = new float[53];
    private static long loggedAt;

    private static void release() {
        if (visualizer != null) {
            try { visualizer.release(); } catch (RuntimeException ignored) { /* the audio server already dropped it */ }
        }
        visualizer = null;
        sampled = 0;
        try { nativeReset(); } catch (UnsatisfiedLinkError ignored) { /* the library isn't loaded yet */ }
    }
}
