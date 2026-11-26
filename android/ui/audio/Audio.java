package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private static final int HI_RES_BANDS = 64;
    private static final int MID_RES_BANDS = 32;
    private static final float HI_RES_MIN_HZ = 20f;
    private static final float HI_RES_MAX_HZ = 20000f;

    public static final int BASS_TUNER_NATURAL = 0;
    public static final int BASS_TUNER_TRANSIENT = 1;
    public static final int BASS_TUNER_SUSTAIN = 2;

    private static final Map<Integer, SessionChain> effects = new HashMap<>();
    private static SharedPreferences eqPrefs;
    private static final VolumeWatcher volumeWatcher = new VolumeWatcher();
    static volatile String eqStatus = "";
    static volatile String eqError = "";
    private static volatile Capabilities lastCapabilities = new Capabilities();

    static synchronized void initialize(Context app, SharedPreferences prefs) {
        context = app;
        eqPrefs = prefs;
        directOn = prefs.getBoolean("audio_direct", false);
        try { nativeSetDirect(directOn); } catch (UnsatisfiedLinkError ignored) {}
        refreshEqualizer();
    }

    private static volatile int equalizerRetryMs = 2000;

    static synchronized void refreshEqualizer() {
        equalizerRetryMs = 2000;
        if (eqPrefs == null) return;
        Handler handler = worker();
        handler.removeCallbacks(EQUALIZE);
        handler.post(EQUALIZE);
    }

    static float gain(SharedPreferences prefs, int band) {
        float value = readFloatPref(prefs, "eq_band_" + band, 0);
        return Float.isFinite(value) ? Math.max(-EQ_RANGE, Math.min(EQ_RANGE, value)) : 0;
    }

    /** Public capability and active-effect snapshot for Settings and Diagnostics. */
    public static Capabilities capabilities() {
        return lastCapabilities;
    }

    /** Parse, validate, and persist a GraphicEQ profile into SharedPreferences. */
    public static GraphicEqProfile importGraphicEq(SharedPreferences prefs, String name, String raw) {
        GraphicEqProfile profile = GraphicEqProfile.parse(raw);
        if (prefs != null) {
            prefs.edit()
                .putString("eq_autoeq_name", name == null ? "" : name.trim())
                .putString("eq_autoeq_curve", profile.canonical)
                .apply();
            refreshEqualizer();
        }
        return profile;
    }

    private static volatile String cachedGraphicEqRaw;
    private static volatile GraphicEqProfile cachedGraphicEqProfile;

    /** Load the currently saved GraphicEQ profile, or null if absent/invalid. */
    public static GraphicEqProfile loadGraphicEq(SharedPreferences prefs) {
        if (prefs == null) return null;
        String raw = prefs.getString("eq_autoeq_curve", "");
        if (raw == null || raw.trim().isEmpty()) return null;
        if (raw.equals(cachedGraphicEqRaw)) return cachedGraphicEqProfile;
        try {
            GraphicEqProfile parsed = GraphicEqProfile.parse(raw);
            cachedGraphicEqRaw = raw;
            cachedGraphicEqProfile = parsed;
            return parsed;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Evaluate a validated GraphicEQ profile at {@code hz} scaled by {@code intensityPercent}. */
    public static float evaluateGraphicEq(GraphicEqProfile profile, float hz, float intensityPercent) {
        return profile == null ? 0f : profile.evaluate(hz, intensityPercent);
    }

    /** Pure ISO-226:2003 equal-loudness compensation (dB) normalized at 1 kHz. */
    public static float iso226Gain(float hz, float volumeFraction, float thresholdFraction) {
        return Iso226.compensationDb(hz, volumeFraction, thresholdFraction);
    }

    private static final Runnable EQUALIZE = () -> {
        LinkedHashSet<Integer> sessions;
        SharedPreferences prefs;
        Context app;
        int active;
        synchronized (Audio.class) {
            sessions = new LinkedHashSet<>(SESSIONS);
            prefs = eqPrefs;
            app = context;
            active = session;
        }
        if (prefs == null) return;

        DspSettings cfg = DspSettings.read(prefs, app);
        volumeWatcher.update(app, cfg.masterEnabled && cfg.loudnessEnabled);

        boolean usingLegacy = cfg.masterEnabled && cfg.legacyMode && sessions.isEmpty();
        LinkedHashSet<Integer> targetSessions = new LinkedHashSet<>();
        if (cfg.masterEnabled) {
            if (!sessions.isEmpty()) targetSessions.addAll(sessions);
            else if (usingLegacy) {
                targetSessions.add(0);
                active = 0;
            }
        }

        Iterator<Map.Entry<Integer, SessionChain>> entries = effects.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, SessionChain> entry = entries.next();
            if (!cfg.masterEnabled || !targetSessions.contains(entry.getKey())) {
                entry.getValue().releaseAll();
                entries.remove();
            }
        }

        if (!cfg.masterEnabled || targetSessions.isEmpty()) {
            eqStatus = cfg.masterEnabled
                ? "Start playback on this device to apply the equalizer"
                : "Equalizer off";
            lastCapabilities = Capabilities.idle();
            return;
        }

        float[] eqBandHz = buildBandFrequencies(cfg.wantsHiResEq());
        float[] eqBandGains = new float[eqBandHz.length];
        float peakEqBoostDb = evaluateBands(
            eqBandHz,
            cfg.geqEnabled, cfg.geqGains,
            cfg.autoEqEnabled ? cfg.autoEqProfile : null, cfg.autoEqIntensity,
            cfg.loudnessEnabled, cfg.volumeFraction, cfg.loudnessThreshold / 100f,
            eqBandGains
        );
        float peakBassBoostDb = cfg.bassTunerEnabled ? Math.max(0f, cfg.bassTunerPostGainDb) : 0f;
        float peakLimiterPostDb = cfg.limiterEnabled ? Math.max(0f, cfg.limiterPostGainDb) : 0f;
        float autoAttenDb = -Math.max(0f, peakEqBoostDb + peakBassBoostDb + peakLimiterPostDb);
        float baseInputGainDb = cfg.attenuatorAuto ? autoAttenDb : cfg.attenuatorManualDb;

        eqError = "";
        for (int id : targetSessions) {
            SessionChain chain = effects.get(id);
            if (chain == null) chain = new SessionChain(id);
            try {
                applyMainEffect(chain, id, cfg, eqBandHz, eqBandGains, baseInputGainDb, peakEqBoostDb);
                applyAuxEffects(chain, id, cfg, usingLegacy);
                effects.put(id, chain);
            } catch (RuntimeException unavailable) {
                eqError = unavailable.toString();
                android.util.Log.w("SpatchAudio", "Equalizer session " + id + ": " + eqError);
                effects.remove(id);
                chain.releaseAll();
            }
        }

        SessionChain current = effects.get(active);
        if (current == null && !effects.isEmpty()) current = effects.values().iterator().next();
        if (current == null) {
            eqStatus = "Waiting for audio effects · retrying";
        } else if (current.main instanceof Equalizer) {
            eqStatus = "Active · " + current.eqBandCount + " device bands (fallback)";
        } else {
            eqStatus = formatActiveStatus(current, cfg);
        }
        lastCapabilities = Capabilities.from(current, active);
        if (effects.size() < targetSessions.size()) {
            worker().postDelayed(Audio.EQUALIZE, equalizerRetryMs);
            equalizerRetryMs = Math.min(30_000, equalizerRetryMs * 2);
        } else equalizerRetryMs = 2000;
    };

    private static float[] buildBandFrequencies(boolean hiRes) {
        if (!hiRes) {
            float[] hz = new float[EQ_HZ.length];
            for (int i = 0; i < EQ_HZ.length; i++) hz[i] = EQ_HZ[i];
            return hz;
        }
        return logSpacedFrequencies(HI_RES_BANDS, HI_RES_MIN_HZ, HI_RES_MAX_HZ);
    }

    static float[] logSpacedFrequencies(int count, float minHz, float maxHz) {
        float[] out = new float[count];
        if (count <= 1) {
            if (count == 1) out[0] = minHz;
            return out;
        }
        double logMin = Math.log(minHz);
        double logMax = Math.log(maxHz);
        for (int i = 0; i < count; i++) {
            double t = (double) i / (count - 1);
            out[i] = (float) Math.exp(logMin + t * (logMax - logMin));
        }
        return out;
    }

    static float evaluateBands(
        float[] eqBandHz,
        boolean geqEnabled, float[] geqGains,
        GraphicEqProfile autoEqProfile, float autoEqIntensity,
        boolean loudnessEnabled, float volumeFraction, float thresholdFraction,
        float[] outGains
    ) {
        float peak = 0f;
        for (int i = 0; i < eqBandHz.length; i++) {
            float hz = eqBandHz[i];
            float total = 0f;
            if (geqEnabled && geqGains != null) total += gainAt(hz, geqGains);
            if (autoEqProfile != null) total += autoEqProfile.evaluate(hz, autoEqIntensity);
            if (loudnessEnabled) total += Iso226.compensationDb(hz, volumeFraction, thresholdFraction);
            outGains[i] = total;
            if (total > peak) peak = total;
        }
        return peak;
    }

    private static float evaluateCombinedEqGain(float hz, DspSettings cfg) {
        float total = 0f;
        if (cfg.geqEnabled) total += gainAt(hz, cfg.geqGains);
        if (cfg.autoEqEnabled && cfg.autoEqProfile != null) {
            total += cfg.autoEqProfile.evaluate(hz, cfg.autoEqIntensity);
        }
        if (cfg.loudnessEnabled) {
            total += Iso226.compensationDb(hz, cfg.volumeFraction, cfg.loudnessThreshold / 100f);
        }
        return total;
    }

    private static void applyMainEffect(
        SessionChain chain, int sessionId, DspSettings cfg,
        float[] eqHz, float[] eqGains, float baseInputGainDb, float peakEqBoostDb
    ) {
        boolean needsDp = !(chain.main instanceof Equalizer);
        if (needsDp) {
            try {
                boolean wantMbc = cfg.bassTunerEnabled;
                boolean wantLimiter = cfg.limiterEnabled;
                boolean wantPreEq = cfg.geqEnabled || (cfg.autoEqEnabled && cfg.autoEqProfile != null) || cfg.loudnessEnabled;
                int desiredBands = wantPreEq ? eqHz.length : EQ_HZ.length;
                float frameMs = cfg.bufferMs;

                if (chain.main == null || chain.needsRebuild(wantPreEq, desiredBands, wantMbc, wantLimiter, frameMs)) {
                    chain.releaseMain();
                    DynamicsProcessing created = createDynamicsProcessing(
                        sessionId, wantPreEq, desiredBands, wantMbc, wantLimiter, frameMs
                    );
                    chain.main = created;
                    chain.dpPreEqInUse = wantPreEq;
                    chain.dpDesiredBands = desiredBands;
                    chain.dpPreEqBands = created.getConfig().getPreEqBandCount();
                    chain.dpMbcInUse = wantMbc;
                    chain.dpLimiterInUse = wantLimiter;
                    chain.dpFrameMs = frameMs;
                    chain.eqBandCount = chain.dpPreEqBands;
                }

                if (!chain.main.hasControl()) {
                    throw new IllegalStateException("DynamicsProcessing is controlled by another equalizer");
                }
                DynamicsProcessing dp = (DynamicsProcessing) chain.main;
                int bandCount = chain.dpPreEqBands;

                float[] actualHz = bandCount == eqHz.length ? eqHz : bandCount == EQ_HZ.length
                    ? buildBandFrequencies(false) : logSpacedFrequencies(bandCount, HI_RES_MIN_HZ, HI_RES_MAX_HZ);
                float[] actualGains = bandCount == eqHz.length ? eqGains : new float[bandCount];
                float actualPeak = bandCount == eqHz.length ? peakEqBoostDb : evaluateBands(
                    actualHz, cfg.geqEnabled, cfg.geqGains, cfg.autoEqEnabled ? cfg.autoEqProfile : null,
                    cfg.autoEqIntensity, cfg.loudnessEnabled, cfg.volumeFraction,
                    cfg.loudnessThreshold / 100f, actualGains);
                chain.inputGainDb = cfg.attenuatorAuto
                    ? baseInputGainDb + peakEqBoostDb - actualPeak : baseInputGainDb;
                dp.setInputGainbyChannel(0, chain.inputGainDb + (cfg.balanceEnabled ? cfg.balanceLeftDb : 0f));
                dp.setInputGainbyChannel(1, chain.inputGainDb + (cfg.balanceEnabled ? cfg.balanceRightDb : 0f));
                DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, wantPreEq, bandCount);
                for (int i = 0; i < bandCount; i++) {
                    eq.setBand(i, new DynamicsProcessing.EqBand(true, actualHz[i], actualGains[i]));
                }
                dp.setPreEqAllChannelsTo(eq);

                if (wantMbc) {
                    dp.setMbcAllChannelsTo(buildBassTunerMbc(cfg));
                }
                if (wantLimiter) {
                    dp.setLimiterAllChannelsTo(buildLimiter(cfg));
                }

                if (dp.setEnabled(true) != AudioEffect.SUCCESS) {
                    throw new IllegalStateException("The audio effect could not be enabled");
                }
                chain.bassTunerActive = wantMbc;
                chain.limiterActive = wantLimiter;
                chain.channelBalanceActive = cfg.balanceEnabled;
                return;
            } catch (RuntimeException unavailable) {
                eqError = unavailable.toString();
                chain.releaseMain();
                chain.bassTunerActive = false;
                chain.limiterActive = false;
                chain.channelBalanceActive = false;
                chain.main = new Equalizer(0, sessionId);
            }
        }

        Equalizer equalizer = (Equalizer) chain.main;
        if (!equalizer.hasControl()) {
            throw new IllegalStateException("The device equalizer is controlled by another app");
        }
        short[] range = equalizer.getBandLevelRange();
        short count = equalizer.getNumberOfBands();
        if (count <= 0) throw new IllegalStateException("The device equalizer exposes no bands");
        chain.eqBandCount = count;
        float[] deviceGains = new float[count];
        float devicePeak = 0;
        for (short band = 0; band < count; band++) {
            deviceGains[band] = evaluateCombinedEqGain(equalizer.getCenterFreq(band) / 1000f, cfg);
            devicePeak = Math.max(devicePeak, deviceGains[band]);
        }
        float fallbackAtten = cfg.attenuatorAuto ? -devicePeak : cfg.attenuatorManualDb;
        chain.inputGainDb = fallbackAtten;
        for (short band = 0; band < count; band++) {
            float targetDb = deviceGains[band] + fallbackAtten;
            int levelMb = Math.round(targetDb * 100f);
            equalizer.setBandLevel(band, (short) Math.max(range[0], Math.min(range[1], levelMb)));
        }
        if (equalizer.setEnabled(true) != AudioEffect.SUCCESS) {
            throw new IllegalStateException("The audio effect could not be enabled");
        }
        chain.bassTunerActive = false;
        chain.limiterActive = false;
        chain.channelBalanceActive = false;
    }

    private static DynamicsProcessing createDynamicsProcessing(
        int sessionId, boolean preEqInUse, int desiredBands, boolean mbcInUse, boolean limiterInUse, float frameMs
    ) {
        int[] candidates = desiredBands > MID_RES_BANDS
            ? new int[]{desiredBands, MID_RES_BANDS, EQ_HZ.length}
            : (desiredBands > EQ_HZ.length ? new int[]{desiredBands, EQ_HZ.length} : new int[]{EQ_HZ.length});
        RuntimeException lastError = null;
        for (int bandCount : candidates) {
            try {
                DynamicsProcessing.Config.Builder builder = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    preEqInUse, bandCount,
                    mbcInUse, mbcInUse ? 2 : 0,
                    false, 0,
                    limiterInUse
                );
                if (frameMs > 0f) builder.setPreferredFrameDuration(frameMs);
                return new DynamicsProcessing(0, sessionId, builder.build());
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("DynamicsProcessing unavailable");
    }

    static DynamicsProcessing.Mbc buildBassTunerMbc(DspSettings cfg) {
        DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, true, 2);
        float cutoff = cfg.bassTunerCutoffHz;
        float postGain = cfg.bassTunerPostGainDb;
        float attackMs;
        float releaseMs;
        float ratio;
        float thresholdDb;
        float kneeDb;
        if (cfg.bassTunerType == BASS_TUNER_TRANSIENT) {
            attackMs = 3.0f;
            releaseMs = 60.0f;
            ratio = 2.5f;
            thresholdDb = -24.0f;
            kneeDb = 6.0f;
        } else if (cfg.bassTunerType == BASS_TUNER_SUSTAIN) {
            attackMs = 35.0f;
            releaseMs = 220.0f;
            ratio = 2.5f;
            thresholdDb = -28.0f;
            kneeDb = 6.0f;
        } else {
            attackMs = 10.0f;
            releaseMs = 100.0f;
            ratio = 1.0f;
            thresholdDb = 0.0f;
            kneeDb = 0.0f;
        }
        mbc.setBand(0, new DynamicsProcessing.MbcBand(
            true, cutoff, attackMs, releaseMs, ratio, thresholdDb, kneeDb,
            -90.0f, 1.0f, 0.0f, postGain
        ));
        mbc.setBand(1, new DynamicsProcessing.MbcBand(
            true, 24000.0f, 1.0f, 100.0f, 1.0f, 0.0f, 0.0f,
            -90.0f, 1.0f, 0.0f, 0.0f
        ));
        return mbc;
    }

    private static DynamicsProcessing.Limiter buildLimiter(DspSettings cfg) {
        return new DynamicsProcessing.Limiter(
            true, true, 0,
            cfg.limiterAttackMs,
            cfg.limiterReleaseMs,
            cfg.limiterRatio,
            cfg.limiterThresholdDb,
            cfg.limiterPostGainDb
        );
    }

    private static void applyAuxEffects(SessionChain chain, int sessionId, DspSettings cfg, boolean usingLegacy) {
        // Bass Boost
        if (cfg.bassBoostEnabled) {
            try {
                if (chain.bassBoost == null) chain.bassBoost = new BassBoost(0, sessionId);
                if (!chain.bassBoost.hasControl()) throw new IllegalStateException("BassBoost controlled by another app");
                if (chain.bassBoost.getStrengthSupported()) {
                    chain.bassBoost.setStrength((short) Math.round(cfg.bassBoostStrength * 10f));
                } else if (cfg.bassBoostStrength > 0) {
                    throw new UnsupportedOperationException("BassBoost strength unsupported on this device");
                }
                if (chain.bassBoost.setEnabled(true) != AudioEffect.SUCCESS) {
                    throw new IllegalStateException("BassBoost could not be enabled");
                }
                chain.bassBoostActive = true;
                chain.bassBoostError = "";
            } catch (RuntimeException e) {
                chain.bassBoostActive = false;
                chain.bassBoostError = e.toString();
                releaseEffect(chain.bassBoost);
                chain.bassBoost = null;
            }
        } else {
            chain.bassBoostActive = false;
            chain.bassBoostError = "";
            releaseEffect(chain.bassBoost);
            chain.bassBoost = null;
        }

        // Preset Reverberation (Wavelet: unavailable when legacy mode is active)
        if (cfg.reverbEnabled && !usingLegacy) {
            try {
                if (chain.reverb == null) chain.reverb = new PresetReverb(0, sessionId);
                if (!AudioEffect.EFFECT_INSERT.equals(chain.reverb.getDescriptor().connectMode))
                    throw new UnsupportedOperationException("This device only provides auxiliary reverberation");
                if (!chain.reverb.hasControl()) throw new IllegalStateException("PresetReverb controlled by another app");
                chain.reverb.setPreset((short) cfg.reverbPreset);
                if (chain.reverb.setEnabled(true) != AudioEffect.SUCCESS) {
                    throw new IllegalStateException("PresetReverb could not be enabled");
                }
                chain.reverbActive = true;
                chain.reverbError = "";
            } catch (RuntimeException e) {
                chain.reverbActive = false;
                chain.reverbError = e.toString();
                releaseEffect(chain.reverb);
                chain.reverb = null;
            }
        } else {
            chain.reverbActive = false;
            chain.reverbError = (cfg.reverbEnabled && usingLegacy) ? "Unavailable in legacy mode" : "";
            releaseEffect(chain.reverb);
            chain.reverb = null;
        }

        // Virtualizer
        if (cfg.virtualizerEnabled) {
            try {
                if (chain.virtualizer == null) chain.virtualizer = new Virtualizer(0, sessionId);
                if (!chain.virtualizer.hasControl()) throw new IllegalStateException("Virtualizer controlled by another app");
                if (chain.virtualizer.getStrengthSupported()) {
                    chain.virtualizer.setStrength((short) Math.round(cfg.virtualizerStrength * 10f));
                } else if (cfg.virtualizerStrength > 0) {
                    throw new UnsupportedOperationException("Virtualizer strength unsupported on this device");
                }
                if (chain.virtualizer.setEnabled(true) != AudioEffect.SUCCESS) {
                    throw new IllegalStateException("Virtualizer could not be enabled");
                }
                chain.virtualizerActive = true;
                chain.virtualizerError = "";
            } catch (RuntimeException e) {
                chain.virtualizerActive = false;
                chain.virtualizerError = e.toString();
                releaseEffect(chain.virtualizer);
                chain.virtualizer = null;
            }
        } else {
            chain.virtualizerActive = false;
            chain.virtualizerError = "";
            releaseEffect(chain.virtualizer);
            chain.virtualizer = null;
        }
    }

    private static String formatActiveStatus(SessionChain chain, DspSettings cfg) {
        StringBuilder sb = new StringBuilder("Active · ");
        sb.append(chain.dpPreEqBands).append(" bands");
        if (cfg.autoEqEnabled && cfg.autoEqProfile != null) sb.append(" · AutoEQ");
        if (cfg.loudnessEnabled) sb.append(" · Loudness");
        if (chain.bassTunerActive) sb.append(" · BassTuner");
        if (chain.limiterActive) sb.append(" · Limiter");
        if (chain.bassBoostActive) sb.append(" · BassBoost");
        if (chain.reverbActive) sb.append(" · Reverb");
        if (chain.virtualizerActive) sb.append(" · Virtualizer");
        return sb.toString();
    }

    /** Interpolate the saved curve on a device's own band centres. */
    static float gainAt(float hz, float[] gains) {
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

    static float readFloatPref(SharedPreferences prefs, String key, float def) {
        try {
            return prefs.getFloat(key, def);
        } catch (ClassCastException e) {
            try {
                return prefs.getInt(key, Math.round(def));
            } catch (ClassCastException ignored) {
                return def;
            }
        }
    }

    static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Validated AutoEQ / EqualizerAPO GraphicEQ curve. */
    public static final class GraphicEqProfile {
        public final float[] freqs;
        public final float[] gains;
        public final String canonical;

        private GraphicEqProfile(float[] freqs, float[] gains, String canonical) {
            this.freqs = freqs;
            this.gains = gains;
            this.canonical = canonical;
        }

        public static GraphicEqProfile parse(String raw) {
            if (raw == null) throw new IllegalArgumentException("GraphicEQ text is null");
            return parseJava(raw);
        }

        static GraphicEqProfile parseJava(String raw) {
            if (raw == null) throw new IllegalArgumentException("GraphicEQ text is null");
            if (raw.length() > 256 * 1024) throw new IllegalArgumentException("GraphicEQ profile is too large");
            String body = raw.trim();
            if (body.isEmpty()) throw new IllegalArgumentException("GraphicEQ text is empty");
            if (body.regionMatches(true, 0, "GraphicEQ:", 0, "GraphicEQ:".length())) {
                body = body.substring("GraphicEQ:".length()).trim();
            }
            String[] entries = body.split("[;\\r\\n]+");
            List<float[]> points = new ArrayList<>();
            for (String entry : entries) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("[\\s,]+");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Invalid GraphicEQ point: " + trimmed);
                }
                float hz;
                float db;
                try {
                    hz = Float.parseFloat(parts[0]);
                    db = Float.parseFloat(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Non-numeric GraphicEQ point: " + trimmed, e);
                }
                if (!Float.isFinite(hz) || hz <= 0f || hz > 48000f) {
                    throw new IllegalArgumentException("Invalid frequency in GraphicEQ: " + hz);
                }
                if (!Float.isFinite(db) || db < -36f || db > 36f) {
                    throw new IllegalArgumentException("Invalid gain in GraphicEQ: " + db);
                }
                points.add(new float[]{hz, db});
            }
            if (points.size() < 2) {
                throw new IllegalArgumentException("GraphicEQ requires at least 2 points");
            }
            Collections.sort(points, new Comparator<float[]>() {
                @Override public int compare(float[] a, float[] b) {
                    return Float.compare(a[0], b[0]);
                }
            });
            List<float[]> deduped = new ArrayList<>(points.size());
            for (float[] pt : points) {
                if (!deduped.isEmpty() && Math.abs(deduped.get(deduped.size() - 1)[0] - pt[0]) < 1e-4f) {
                    deduped.get(deduped.size() - 1)[1] = pt[1];
                } else {
                    deduped.add(pt);
                }
            }
            if (deduped.size() < 2) {
                throw new IllegalArgumentException("GraphicEQ requires at least 2 distinct frequencies");
            }
            float[] freqs = new float[deduped.size()];
            float[] gains = new float[deduped.size()];
            StringBuilder canonical = new StringBuilder("GraphicEQ: ");
            for (int i = 0; i < deduped.size(); i++) {
                freqs[i] = deduped.get(i)[0];
                gains[i] = deduped.get(i)[1];
                if (i > 0) canonical.append("; ");
                canonical.append(Float.toString(freqs[i])).append(' ').append(Float.toString(gains[i]));
            }
            return new GraphicEqProfile(freqs, gains, canonical.toString());
        }

        public float evaluate(float hz) {
            if (hz <= freqs[0]) return gains[0];
            for (int i = 1; i < freqs.length; i++) {
                if (hz <= freqs[i]) {
                    double t = Math.log(hz / freqs[i - 1]) / Math.log(freqs[i] / freqs[i - 1]);
                    return (float) (gains[i - 1] + t * (gains[i] - gains[i - 1]));
                }
            }
            return gains[gains.length - 1];
        }

        public float evaluate(float hz, float intensityPercent) {
            float scale = clamp(intensityPercent, 0f, 200f) / 100f;
            return evaluate(hz) * scale;
        }
    }

    /** ISO 226:2003 equal-loudness-level contour compensation relative to 1 kHz. */
    private static final class Iso226 {
        private static final float[] FREQS = {
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
            200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
            2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f
        };
        private static final float[] ALPHA = {
            0.532f, 0.506f, 0.480f, 0.455f, 0.432f, 0.409f, 0.387f, 0.367f, 0.349f, 0.330f,
            0.315f, 0.301f, 0.288f, 0.276f, 0.267f, 0.259f, 0.253f, 0.250f, 0.246f, 0.244f,
            0.243f, 0.243f, 0.243f, 0.242f, 0.242f, 0.245f, 0.254f, 0.271f, 0.301f
        };
        private static final float[] LU = {
            -31.6f, -27.2f, -23.0f, -19.1f, -15.9f, -13.0f, -10.3f, -8.1f, -6.2f, -4.5f,
            -3.1f, -2.0f, -1.1f, -0.4f, 0.0f, 0.3f, 0.5f, 0.0f, -2.7f, -4.1f,
            -1.0f, 1.7f, 2.5f, 1.2f, -2.1f, -7.1f, -11.2f, -10.7f, -3.1f
        };
        private static final float[] TF = {
            78.5f, 68.7f, 59.5f, 51.1f, 44.0f, 37.5f, 31.5f, 26.5f, 22.1f, 17.9f,
            14.4f, 11.4f, 8.6f, 6.2f, 4.4f, 3.0f, 2.2f, 2.4f, 3.5f, 1.7f,
            -1.3f, -4.2f, -6.0f, -5.4f, -1.5f, 6.0f, 12.6f, 13.9f, 12.3f
        };
        private static final int INDEX_1KHZ = 17;
        private static final double REF_PHONS = 80.0;
        private static final double MIN_PHONS = 20.0;

        static float compensationDb(float hz, float volumeFraction, float thresholdFraction) {
            float thresh = clamp(thresholdFraction, 0f, 1f);
            float vol = clamp(volumeFraction, 0f, 1f);
            if (thresh <= 0.001f || vol >= thresh) return 0f;
            float ratio = vol / thresh;
            double currentPhons = MIN_PHONS + (REF_PHONS - MIN_PHONS) * ratio;
            double ref1k = splAtIndex(INDEX_1KHZ, REF_PHONS);
            double cur1k = splAtIndex(INDEX_1KHZ, currentPhons);

            if (hz <= FREQS[0]) {
                return clamp((float) ((splAtIndex(0, currentPhons) - cur1k) - (splAtIndex(0, REF_PHONS) - ref1k)), -12f, 18f);
            }
            int last = FREQS.length - 1;
            if (hz >= FREQS[last]) {
                return clamp((float) ((splAtIndex(last, currentPhons) - cur1k) - (splAtIndex(last, REF_PHONS) - ref1k)), -12f, 18f);
            }
            for (int i = 1; i < FREQS.length; i++) {
                if (hz <= FREQS[i]) {
                    double t = Math.log(hz / FREQS[i - 1]) / Math.log(FREQS[i] / FREQS[i - 1]);
                    double g0 = (splAtIndex(i - 1, currentPhons) - cur1k) - (splAtIndex(i - 1, REF_PHONS) - ref1k);
                    double g1 = (splAtIndex(i, currentPhons) - cur1k) - (splAtIndex(i, REF_PHONS) - ref1k);
                    return clamp((float) (g0 + t * (g1 - g0)), -12f, 18f);
                }
            }
            return 0f;
        }

        private static double splAtIndex(int idx, double phons) {
            double alpha = ALPHA[idx];
            double lu = LU[idx];
            double tf = TF[idx];
            double af = 4.47e-3 * (Math.pow(10.0, 0.025 * phons) - 1.15)
                + Math.pow(0.4 * Math.pow(10.0, (tf + lu) / 10.0 - 9.0), alpha);
            return (10.0 / alpha) * Math.log10(af) - lu + 94.0;
        }
    }

    private static final class DspSettings {
        final boolean masterEnabled;
        final boolean geqEnabled;
        final float[] geqGains = new float[EQ_HZ.length];
        final boolean autoEqEnabled;
        final GraphicEqProfile autoEqProfile;
        final float autoEqIntensity;
        final boolean loudnessEnabled;
        final int loudnessThreshold;
        final float volumeFraction;
        final boolean bassBoostEnabled;
        final int bassBoostStrength;
        final boolean reverbEnabled;
        final int reverbPreset;
        final boolean virtualizerEnabled;
        final int virtualizerStrength;
        final boolean bassTunerEnabled;
        final int bassTunerType;
        final float bassTunerCutoffHz;
        final float bassTunerPostGainDb;
        final boolean attenuatorAuto;
        final float attenuatorManualDb;
        final boolean limiterEnabled;
        final float limiterAttackMs;
        final float limiterReleaseMs;
        final float limiterRatio;
        final float limiterThresholdDb;
        final float limiterPostGainDb;
        final boolean balanceEnabled;
        final float balanceLeftDb;
        final float balanceRightDb;
        final int bufferMs;
        final boolean legacyMode;

        private DspSettings(SharedPreferences prefs, Context app) {
            this.masterEnabled = prefs.getBoolean("equalizer", false);
            this.geqEnabled = prefs.getBoolean("eq_geq_enabled", true);
            for (int i = 0; i < EQ_HZ.length; i++) this.geqGains[i] = gain(prefs, i);
            this.autoEqEnabled = prefs.getBoolean("eq_autoeq_enabled", false);
            this.autoEqProfile = loadGraphicEq(prefs);
            this.autoEqIntensity = clampInt(prefs.getInt("eq_autoeq_intensity", 100), 0, 200);
            this.loudnessEnabled = prefs.getBoolean("eq_loudness_enabled", false);
            this.loudnessThreshold = clampInt(prefs.getInt("eq_loudness_threshold", 60), 0, 100);
            this.volumeFraction = readMediaVolumeFraction(app, this.loudnessThreshold / 100f);
            this.bassBoostEnabled = prefs.getBoolean("eq_bass_boost_enabled", false);
            this.bassBoostStrength = clampInt(prefs.getInt("eq_bass_boost_strength", 50), 0, 100);
            this.reverbEnabled = prefs.getBoolean("eq_reverb_enabled", false);
            this.reverbPreset = clampInt(prefs.getInt("eq_reverb_preset", 1), 0, 6);
            this.virtualizerEnabled = prefs.getBoolean("eq_virtualizer_enabled", false);
            this.virtualizerStrength = clampInt(prefs.getInt("eq_virtualizer_strength", 50), 0, 100);
            this.bassTunerEnabled = prefs.getBoolean("eq_bass_tuner_enabled", false);
            this.bassTunerType = clampInt(prefs.getInt("eq_bass_tuner_type", BASS_TUNER_NATURAL), 0, 2);
            this.bassTunerCutoffHz = clampInt(prefs.getInt("eq_bass_tuner_cutoff_hz", 100), 20, 250);
            this.bassTunerPostGainDb = clamp(readFloatPref(prefs, "eq_bass_tuner_post_gain_db", 0f), -15f, 15f);
            this.attenuatorAuto = prefs.getBoolean("eq_attenuator_auto", true);
            this.attenuatorManualDb = clamp(readFloatPref(prefs, "eq_attenuator_db", 0f), -30f, 0f);
            this.limiterEnabled = prefs.getBoolean("eq_limiter_enabled", false);
            this.limiterAttackMs = clamp(readFloatPref(prefs, "eq_limiter_attack_ms", 1f), 0.1f, 100f);
            this.limiterReleaseMs = clamp(readFloatPref(prefs, "eq_limiter_release_ms", 60f), 1f, 500f);
            this.limiterRatio = clamp(readFloatPref(prefs, "eq_limiter_ratio", 4f), 1f, 20f);
            this.limiterThresholdDb = clamp(readFloatPref(prefs, "eq_limiter_threshold_db", -6f), -40f, 0f);
            this.limiterPostGainDb = clamp(readFloatPref(prefs, "eq_limiter_post_gain_db", 0f), -15f, 15f);
            this.balanceEnabled = prefs.getBoolean("eq_balance_enabled", false);
            this.balanceLeftDb = clamp(readFloatPref(prefs, "eq_balance_left_db", 0f), -30f, 0f);
            this.balanceRightDb = clamp(readFloatPref(prefs, "eq_balance_right_db", 0f), -30f, 0f);
            this.bufferMs = clampInt(prefs.getInt("eq_buffer_ms", 0), 0, 50);
            this.legacyMode = prefs.getBoolean("eq_legacy", false);
        }

        static DspSettings read(SharedPreferences prefs, Context app) {
            return new DspSettings(prefs, app);
        }

        boolean wantsHiResEq() {
            return (autoEqEnabled && autoEqProfile != null) || loudnessEnabled;
        }

        private static float readMediaVolumeFraction(Context app, float thresholdFraction) {
            if (app == null) return thresholdFraction;
            try {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return thresholdFraction;
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int min = Build.VERSION.SDK_INT >= 28 ? am.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0;
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (max <= min) return 1f;
                return clamp((float) (cur - min) / (float) (max - min), 0f, 1f);
            } catch (RuntimeException e) {
                return thresholdFraction;
            }
        }
    }

    private static final class SessionChain {
        final int sessionId;
        AudioEffect main;
        boolean dpPreEqInUse;
        int dpDesiredBands;
        int dpPreEqBands;
        boolean dpMbcInUse;
        boolean dpLimiterInUse;
        float dpFrameMs, inputGainDb;
        int eqBandCount;
        boolean bassTunerActive;
        boolean limiterActive;
        boolean channelBalanceActive;
        BassBoost bassBoost;
        boolean bassBoostActive;
        String bassBoostError = "";
        PresetReverb reverb;
        boolean reverbActive;
        String reverbError = "";
        @SuppressWarnings("deprecation")
        Virtualizer virtualizer;
        boolean virtualizerActive;
        String virtualizerError = "";

        SessionChain(int sessionId) {
            this.sessionId = sessionId;
        }

        boolean needsRebuild(boolean preEq, int bands, boolean mbc, boolean limiter, float frameMs) {
            if (!(main instanceof DynamicsProcessing)) return true;
            return dpPreEqInUse != preEq
                || dpDesiredBands != bands
                || dpMbcInUse != mbc
                || dpLimiterInUse != limiter
                || Math.abs(dpFrameMs - frameMs) > 0.01f;
        }

        void releaseMain() {
            releaseEffect(main);
            main = null;
        }

        void releaseAll() {
            releaseMain();
            releaseEffect(bassBoost);
            bassBoost = null;
            releaseEffect(reverb);
            reverb = null;
            releaseEffect(virtualizer);
            virtualizer = null;
            bassTunerActive = false;
            limiterActive = false;
            channelBalanceActive = false;
            bassBoostActive = false;
            reverbActive = false;
            virtualizerActive = false;
        }
    }

    /** Immutable snapshot of active session DSP state. */
    public static final class Capabilities {
        public final int activeSession;
        public final boolean usingFallbackEqualizer;
        public final int eqBandCount;
        public final String bassBoostError;
        public final String reverbError;
        public final String virtualizerError;

        private Capabilities() {
            this(-1, false, 0, "", "", "");
        }

        private Capabilities(
            int activeSession, boolean fallbackEq, int eqBandCount,
            String bassBoostError, String reverbError, String virtualizerError
        ) {
            this.activeSession = activeSession;
            this.usingFallbackEqualizer = fallbackEq;
            this.eqBandCount = eqBandCount;
            this.bassBoostError = bassBoostError;
            this.reverbError = reverbError;
            this.virtualizerError = virtualizerError;
        }

        static Capabilities idle() {
            return new Capabilities();
        }

        static Capabilities from(SessionChain chain, int activeSession) {
            if (chain == null) return idle();
            return new Capabilities(
                activeSession,
                chain.main instanceof Equalizer,
                chain.eqBandCount,
                chain.bassBoostError,
                chain.reverbError,
                chain.virtualizerError
            );
        }
    }

    private static final class VolumeWatcher {
        private boolean registered;
        private Context registeredContext;
        private ContentObserver observer;
        private float lastVolume = -1;

        void update(Context app, boolean want) {
            if (app == null) return;
            if (want && !registered) {
                try {
                    if (observer == null) {
                        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                            @Override public void onChange(boolean selfChange) {
                                float volume = DspSettings.readMediaVolumeFraction(app, 1f);
                                if (volume != lastVolume) {
                                    lastVolume = volume;
                                    refreshEqualizer();
                                }
                            }
                        };
                    }
                    app.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, observer);
                    lastVolume = DspSettings.readMediaVolumeFraction(app, 1f);
                    registered = true;
                    registeredContext = app;
                } catch (RuntimeException ignored) { /* preview/test context */ }
            } else if (!want && registered) {
                try {
                    if (registeredContext != null && observer != null) {
                        registeredContext.getContentResolver().unregisterContentObserver(observer);
                    }
                } catch (RuntimeException ignored) {}
                registered = false;
                registeredContext = null;
            }
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

    /** On when the bars read Spotify's audio directly (the native Oboe tap) instead of an Android Visualizer, which needs the microphone permission. */
    static volatile boolean directOn;

    static void setDirect(boolean on) {
        directOn = on;
        try { nativeSetDirect(on); } catch (UnsatisfiedLinkError ignored) {}
        refreshEqualizer();
    }

    static boolean ready(Context context) {
        return directOn || permitted(context);
    }

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

    /** One capture; false clears stale levels and schedules a slower retry. */
    private static boolean sample(long now) {
        if (directOn) return sampleDirect(now);
        int id = session;
        if (context == null || !permitted(context)) { say("Needs the microphone permission"); return false; }
        if (id <= 0) { say("Waiting for Spotify to start playing"); return false; }
        if (capturing != id) { release(); capturing = id; retryAt = 0; }
        if (visualizer == null && now < retryAt) return false;
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
            return false;
        }
    }

    /** One analysis from the native Oboe tap; false (bars decay) when no audio has arrived lately. */
    private static boolean sampleDirect(long now) {
        float dt = sampled == 0 ? 16 : Math.min(100, now - sampled);
        try {
            if (nativeSampleDirect(dt, now, OUT)) {
                sampled = now;
                say("Reading Spotify's audio directly (Oboe)");
                System.arraycopy(OUT, 0, BANDS, 0, BANDS.length);
                bass = OUT[48]; level = OUT[49]; kick = OUT[50];
                return true;
            }
        } catch (UnsatisfiedLinkError ignored) {
            // Preview or standalone build without libspatch_app.
        }
        say("Waiting for Spotify's audio");
        return false;
    }

    // The analysis and EQ curve solver are in Rust (app/src/spectrum.rs, eq.rs).
    private static native void nativeSetDirect(boolean on);
    private static native boolean nativeSampleDirect(float dtMs, long nowMs, float[] out);
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
