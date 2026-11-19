package spatch;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Focused backend tests for Audio DSP math, GraphicEQ parsing/interpolation,
 * ISO-226 equal-loudness contours, and SharedPreferences schema handling.
 *
 *   JDK=/opt/homebrew/Cellar/openjdk@21/21.0.13.0.3/libexec/openjdk.jdk/Contents/Home
 *   JAR=/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar
 *   "$JDK/bin/javac" --release 11 -cp "$JAR" -d /tmp/at java/dex/src/spatch/core/Audio.java java/dex/test/spatch/core/AudioTest.java
 *   "$JDK/bin/java" -cp "/tmp/at:$JAR" spatch.AudioTest
 */
public final class AudioTest {
    public static void main(String[] args) {
        testGraphicEqParseAndEvaluate();
        Audio.GraphicEqProfile tiny = Audio.GraphicEqProfile.parse("0.01 1; 20 0");
        check(Audio.GraphicEqProfile.parse(tiny.canonical).freqs[0] == .01f, "canonical profile preserves small frequencies");
        testGraphicEqValidationRejectsBadInputs();
        testImportAndLoadGraphicEq();
        testIso226EqualLoudness();
        testTenBandGainAtAndClamping();
        testLogSpacedFrequencies();
        testReadFloatPref();
        testInitialCapabilities();
        System.out.println("AudioTest: all checks passed");
    }

    private static void testGraphicEqParseAndEvaluate() {
        String raw = "GraphicEQ: 1000 0.0; 20 -6.0; 20000 6.0; 100 3.0\n";
        Audio.GraphicEqProfile p = Audio.GraphicEqProfile.parse(raw);
        check(p.freqs.length == 4, "sorted into 4 points");
        checkApprox(p.freqs[0], 20f, 1e-4f, "min freq 20");
        checkApprox(p.freqs[3], 20000f, 1e-4f, "max freq 20000");

        // Clamping outside range
        checkApprox(p.evaluate(10f), -6.0f, 1e-4f, "clamp below min");
        checkApprox(p.evaluate(25000f), 6.0f, 1e-4f, "clamp above max");

        // Exact points
        checkApprox(p.evaluate(100f), 3.0f, 1e-4f, "exact 100 Hz");
        checkApprox(p.evaluate(1000f), 0.0f, 1e-4f, "exact 1000 Hz");

        // Geometric midpoint between 20 and 100 is sqrt(2000) ~= 44.72136 Hz -> midpoint gain -1.5 dB
        float midHz = (float) Math.sqrt(20.0 * 100.0);
        checkApprox(p.evaluate(midHz), -1.5f, 1e-3f, "log-freq midpoint");

        // Intensity scaling
        checkApprox(p.evaluate(100f, 0f), 0.0f, 1e-4f, "0% intensity");
        checkApprox(p.evaluate(100f, 50f), 1.5f, 1e-4f, "50% intensity");
        checkApprox(p.evaluate(100f, 150f), 4.5f, 1e-4f, "150% intensity");
        checkApprox(p.evaluate(100f, 500f), 6.0f, 1e-4f, "intensity clamped to 200%");

        // Duplicate frequencies keep last value
        Audio.GraphicEqProfile dup = Audio.GraphicEqProfile.parse("20 -2.0; 20 -4.0; 200 2.0");
        check(dup.freqs.length == 2, "duplicate frequency collapsed");
        checkApprox(dup.evaluate(20f), -4.0f, 1e-4f, "duplicate keeps latest gain");
    }

    private static void testGraphicEqValidationRejectsBadInputs() {
        expectIllegal(null, "null");
        expectIllegal("", "empty");
        expectIllegal("GraphicEQ:   ", "header only");
        expectIllegal("GraphicEQ: 100 2.0", "single point");
        expectIllegal("GraphicEQ: 0 1.0; 100 2.0", "zero frequency");
        expectIllegal("GraphicEQ: -50 1.0; 100 2.0", "negative frequency");
        expectIllegal("GraphicEQ: 100 NaN; 200 1.0", "NaN gain");
        expectIllegal("GraphicEQ: 100 50.0; 200 1.0", "gain out of range");
        expectIllegal("GraphicEQ: 100 abc; 200 1.0", "non-numeric");
        expectIllegal("GraphicEQ: 100 1.0; 100 2.0", "only one distinct frequency");
    }

    private static void testImportAndLoadGraphicEq() {
        MemoryPrefs prefs = new MemoryPrefs();
        check(Audio.loadGraphicEq(prefs) == null, "empty prefs yields null profile");

        Audio.GraphicEqProfile imported = Audio.importGraphicEq(prefs, "  HD 650  ", "20 2.5; 1000 -1.5; 10000 3.0");
        check(imported != null, "imported profile non-null");
        check("HD 650".equals(prefs.getString("eq_autoeq_name", "")), "trimmed name stored");
        check(prefs.getString("eq_autoeq_curve", "").startsWith("GraphicEQ: "), "canonical curve stored");

        Audio.GraphicEqProfile loaded = Audio.loadGraphicEq(prefs);
        check(loaded != null && loaded.freqs.length == 3, "reloaded profile matches");
        checkApprox(Audio.evaluateGraphicEq(loaded, 1000f, 100f), -1.5f, 1e-4f, "helper evaluates loaded profile");
        checkApprox(Audio.evaluateGraphicEq(null, 1000f, 100f), 0f, 1e-4f, "null profile evaluates to 0");
    }

    private static void testIso226EqualLoudness() {
        // Above or at threshold -> 0 dB everywhere
        checkApprox(Audio.iso226Gain(40f, 0.6f, 0.6f), 0f, 1e-4f, "at threshold = 0 dB");
        checkApprox(Audio.iso226Gain(40f, 0.9f, 0.6f), 0f, 1e-4f, "above threshold = 0 dB");

        // Normalized at 1 kHz -> 0 dB even at low volume
        checkApprox(Audio.iso226Gain(1000f, 0.2f, 0.6f), 0f, 1e-3f, "1 kHz normalized to 0 dB");

        // Below threshold -> bass and high treble boosted relative to 1 kHz
        float bassLowVol = Audio.iso226Gain(40f, 0.2f, 0.6f);
        float bassVeryLowVol = Audio.iso226Gain(40f, 0.05f, 0.6f);
        float trebleLowVol = Audio.iso226Gain(12500f, 0.2f, 0.6f);
        check(bassLowVol > 3.0f, "40 Hz boosted at low volume (got " + bassLowVol + ")");
        check(bassVeryLowVol > bassLowVol, "lower volume produces stronger bass compensation");
        check(trebleLowVol > 0.5f, "12.5 kHz boosted at low volume (got " + trebleLowVol + ")");
    }

    private static void testTenBandGainAtAndClamping() {
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.edit().putInt("eq_band_0", 25).putInt("eq_band_5", -20).apply();
        check(Audio.gain(prefs, 0) == Audio.EQ_RANGE, "gain clamped to +EQ_RANGE");
        check(Audio.gain(prefs, 5) == -Audio.EQ_RANGE, "gain clamped to -EQ_RANGE");
        prefs.edit().putFloat("eq_band_2", 2.3f).putFloat("eq_band_3", Float.NaN).apply();
        checkApprox(Audio.gain(prefs, 2), 2.3f, .001f, "fractional gain preserved alongside old integer preferences");
        check(Audio.gain(prefs, 3) == 0, "invalid stored gain is neutral");

        float[] gains = {6, 0, 0, 0, 0, 0, 0, 0, 0, -6};
        checkApprox(Audio.gainAt(20f, gains), 6f, 1e-4f, "clamp below 31 Hz");
        checkApprox(Audio.gainAt(20000f, gains), -6f, 1e-4f, "clamp above 16 kHz");
        float mid31_62 = (float) Math.sqrt(31.0 * 62.0);
        checkApprox(Audio.gainAt(mid31_62, gains), 3f, 1e-3f, "10-band log midpoint");
    }

    private static void testLogSpacedFrequencies() {
        float[] hz = Audio.logSpacedFrequencies(64, 20f, 20000f);
        check(hz.length == 64, "64 bands generated");
        checkApprox(hz[0], 20f, 1e-3f, "starts at 20 Hz");
        checkApprox(hz[63], 20000f, 1e-1f, "ends at 20000 Hz");
        for (int i = 1; i < hz.length; i++) {
            check(hz[i] > hz[i - 1], "strictly increasing at " + i);
        }
    }

    private static void testReadFloatPref() {
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.edit().putFloat("f", -3.5f).putInt("i", -7).apply();
        checkApprox(Audio.readFloatPref(prefs, "f", 0f), -3.5f, 1e-4f, "reads float pref");
        checkApprox(Audio.readFloatPref(prefs, "i", 0f), -7f, 1e-4f, "falls back to int pref");
        checkApprox(Audio.readFloatPref(prefs, "missing", 1.25f), 1.25f, 1e-4f, "returns default");
    }

    private static void testInitialCapabilities() {
        Audio.Capabilities caps = Audio.capabilities();
        check(caps != null, "capabilities never null");
        check(!caps.geqActive && !caps.bassTunerActive && !caps.limiterActive, "idle capabilities inactive");
    }

    private static void expectIllegal(String raw, String label) {
        try {
            Audio.GraphicEqProfile.parse(raw);
            throw new AssertionError("Expected IllegalArgumentException for " + label);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Check failed: " + msg);
    }

    private static void checkApprox(float actual, float expected, float eps, String msg) {
        if (Math.abs(actual - expected) > eps) {
            throw new AssertionError(msg + ": expected " + expected + " +/- " + eps + " but got " + actual);
        }
    }

    private static final class MemoryPrefs implements SharedPreferences {
        private final Map<String, Object> data = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(data); }
        @Override public String getString(String k, String d) { Object v = data.get(k); return v instanceof String ? (String) v : d; }
        @Override @SuppressWarnings("unchecked") public Set<String> getStringSet(String k, Set<String> d) { Object v = data.get(k); return v instanceof Set ? (Set<String>) v : d; }
        @Override public int getInt(String k, int d) { Object v = data.get(k); if (v == null) return d; if (v instanceof Integer) return (Integer) v; throw new ClassCastException(); }
        @Override public long getLong(String k, long d) { Object v = data.get(k); return v instanceof Long ? (Long) v : d; }
        @Override public float getFloat(String k, float d) { Object v = data.get(k); if (v == null) return d; if (v instanceof Float) return (Float) v; throw new ClassCastException(); }
        @Override public boolean getBoolean(String k, boolean d) { Object v = data.get(k); return v instanceof Boolean ? (Boolean) v : d; }
        @Override public boolean contains(String k) { return data.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>();
                private final Set<String> removals = new HashSet<>();
                private boolean clear;

                @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
                @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
                @Override public Editor remove(String k) { removals.add(k); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() {
                    if (clear) data.clear();
                    for (String r : removals) data.remove(r);
                    data.putAll(pending);
                }
            };
        }
    }
}
