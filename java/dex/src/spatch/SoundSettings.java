package spatch;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Equalizer controls, effect panels and headphone profile browsing. */
final class SoundSettings {
    private final SpatchUi ui;
    static final String[] SOUND_GROUPS = {"Graphic equalizer", "Headphone correction", "Loudness & bass", "Space", "Gain & dynamics", "Compatibility", "Find headphones", "Import profile"};
    int panel = -1;
    private int headphoneRequest, headphoneCount = -1;
    private String headphoneQuery = "", headphoneError = "", headphoneLoading = "";
    private boolean headphonesLoading;
    private LinearLayout headphoneResults;
    private TextView equalizerStatus;
    SoundSettings(SpatchUi ui) { this.ui = ui; }

    void refreshStatus() {
        if (equalizerStatus != null && equalizerStatus.isAttachedToWindow()) equalizerStatus.setText(soundStatus());
    }

    private TextView soundNote(LinearLayout box, String message) {
        TextView note = ui.text(message, 13, () -> ui.inkDim, false);
        note.setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(14));
        box.addView(note, ui.fill(-2));
        return note;
    }

    private String soundStatus() {
        if (!ui.pref("equalizer", false)) return "Off · your original sound";
        Audio.Capabilities state = Audio.capabilities();
        if (state.activeSession < 0) return "Ready · start playback on this device";
        String status = state.usingFallbackEqualizer ? "Device EQ active · advanced dynamics unavailable" : "Processing on this device";
        if (!Audio.eqError.isEmpty() && state.eqBandCount == 0) status = "Couldn’t connect to this device’s audio effects";
        if (!state.bassBoostError.isEmpty()) status += "\nBass boost unavailable on this device";
        if (!state.reverbError.isEmpty()) status += "\nReverb unavailable on this device";
        if (!state.virtualizerError.isEmpty()) status += "\nVirtualizer unavailable on this device";
        return status;
    }

    private void openPanel(int next) { panel = next; ui.showPage(); ui.enter(ui.dp(28), 0); }

    private int eqSelectedBand = 5;
    private static final String[] EQ_PRESETS = {"Flat", "Bass boost", "Warm", "Vocals", "Treble boost", "Rock", "Electronic", "Acoustic"};
    private static final float[][] EQ_CURVES = {
        {0,0,0,0,0,0,0,0,0,0}, {6,5,4,2,0,0,0,0,0,0}, {3,3,2,1,0,0,-1,-1,-2,-2},
        {-3,-2,-1,0,2,3,3,2,0,-1}, {0,0,0,0,0,0,2,3,4,5}, {4,3,1,-1,-2,-1,2,3,4,4},
        {5,4,1,0,-2,1,2,3,4,5}, {3,2,1,0,1,2,2,3,2,1}
    };

    private float[] eqGains() {
        float[] gains = new float[Audio.EQ_HZ.length];
        for (int i = 0; i < gains.length; i++) gains[i] = Audio.gain(ui.prefs, i);
        return gains;
    }

    /** The current graphic EQ curve as GraphicEQ text, the same format "Import a profile" reads. */
    private String graphicEqString() {
        StringBuilder data = new StringBuilder("GraphicEQ: ");
        float[] gains = eqGains();
        for (int i = 0; i < gains.length; i++) {
            if (i > 0) data.append("; ");
            data.append(Audio.EQ_HZ[i]).append(' ').append(gains[i]);
        }
        return data.toString();
    }

    private String eqPresetName() {
        float[] gains = eqGains();
        for (int i = 0; i < EQ_CURVES.length; i++) if (java.util.Arrays.equals(gains, EQ_CURVES[i])) return EQ_PRESETS[i];
        try {
            Audio.GraphicEqProfile saved = Audio.GraphicEqProfile.parse(ui.prefs.getString("eq_saved_curve", ""));
            boolean matches = true;
            for (int i = 0; i < gains.length; i++) if (Math.abs(saved.evaluate(Audio.EQ_HZ[i]) - gains[i]) > .001f) matches = false;
            if (matches) return "Saved curve";
        } catch (IllegalArgumentException ignored) {}
        return "Custom";
    }

    private void applyEqCurve(float[] gains) {
        android.content.SharedPreferences.Editor edit = ui.prefs.edit();
        for (int i = 0; i < Audio.EQ_HZ.length; i++) edit.putFloat("eq_band_" + i, Math.max(-12, Math.min(12, gains[i])));
        edit.apply(); Audio.refreshEqualizer();
    }

    private String eqFrequency(int band) {
        int hz = Audio.EQ_HZ[band];
        return hz < 1000 ? hz + " Hz" : (hz / 1000) + " kHz";
    }

    private void eqToolbarButton(TextView button) {
        button.setTextSize(13);
        button.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        GradientDrawable background = ui.round(ui.surfHi, 14);
        button.setBackground(background);
        ui.colored(button, () -> { button.setTextColor(ui.ink); background.setColor(ui.surfHi); });
    }

    private void graphicEqualizer(LinearLayout page) {
        LinearLayout tone = ui.column();
        ui.bgRound(tone, 20, () -> ui.surf);
        LinearLayout.LayoutParams toneParams = ui.fill(-2); toneParams.topMargin = ui.dp(12);
        page.addView(tone, toneParams);
        ui.toggle(tone, "Tone curve", "Bypass this curve to compare your sound", "eq_geq_enabled", true, false);
        LinearLayout toolbar = ui.row();
        toolbar.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(4));
        EqualizerCurve graph = new EqualizerCurve();
        TextView preset = ui.pill(eqPresetName() + "  ⌄", () -> {});
        eqToolbarButton(preset);
        toolbar.addView(preset, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        TextView save = ui.pill("Save", () -> {
            ui.prefs.edit().putString("eq_saved_curve", graphicEqString()).apply();
            Toast.makeText(ui.activity, "Curve saved in Presets", Toast.LENGTH_SHORT).show();
        });
        eqToolbarButton(save);
        LinearLayout.LayoutParams saveParams = ui.lp(ui.dp(68), ui.dp(48)); saveParams.leftMargin = ui.dp(6);
        toolbar.addView(save, saveParams);
        TextView copy = ui.pill("Copy", () -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Graphic EQ curve", graphicEqString()));
                Toast.makeText(ui.activity, "Curve copied, to back up or share", Toast.LENGTH_SHORT).show();
            }
        });
        eqToolbarButton(copy);
        LinearLayout.LayoutParams copyParams = ui.lp(ui.dp(68), ui.dp(48)); copyParams.leftMargin = ui.dp(6);
        toolbar.addView(copy, copyParams);
        TextView reset = ui.pill("Reset", () -> { applyEqCurve(new float[10]); graph.refresh(); });
        eqToolbarButton(reset);
        LinearLayout.LayoutParams resetParams = ui.lp(ui.dp(68), ui.dp(48)); resetParams.leftMargin = ui.dp(6);
        toolbar.addView(reset, resetParams);
        tone.addView(toolbar, ui.fill(-2));
        tone.addView(graph, ui.fill(ui.dp(224)));
        LinearLayout editor = ui.column();
        tone.addView(editor, ui.fill(-2));
        final android.widget.SeekBar[] fine = {null};
        graph.selection = () -> {
            editor.removeAllViews();
            TextView band = ui.text(eqFrequency(eqSelectedBand) + "  ⌄", 15, () -> ui.ink, true);
            band.setPadding(ui.dp(18), 0, ui.dp(18), 0);
            ui.tappable(band, "Choose frequency band", () -> {
                String[] names = new String[10];
                for (int i = 0; i < names.length; i++) names[i] = eqFrequency(i) + " · " + audioNumber(Audio.gain(ui.prefs, i), "dB");
                ui.sheet("Frequency band", "Choose a band, or drag a point on the curve", names, pick -> graph.select(pick));
            });
            editor.addView(band, ui.fill(ui.dp(42)));
            fine[0] = audioSlider(editor, "Fine adjustment", "eq_band_" + eqSelectedBand, -12, 12, 0, .1f, "dB", true,
                () -> { graph.invalidate(); preset.setText(eqPresetName() + "  ⌄"); });
        };
        graph.edit = () -> {
            if (fine[0] != null) fine[0].setProgress(Math.round((Audio.gain(ui.prefs, eqSelectedBand) + 12) * 10));
            preset.setText(eqPresetName() + "  ⌄");
        };
        ui.tappable(preset, "Equalizer presets", () -> {
            List<String> names = new ArrayList<>(java.util.Arrays.asList(EQ_PRESETS));
            Audio.GraphicEqProfile saved = null;
            try { saved = Audio.GraphicEqProfile.parse(ui.prefs.getString("eq_saved_curve", "")); } catch (IllegalArgumentException ignored) {}
            final Audio.GraphicEqProfile curve = saved;
            if (curve != null) names.add("Saved curve");
            ui.sheet("Presets", "A starting point for your sound", names.toArray(new String[0]), pick -> {
                if (pick < EQ_CURVES.length) applyEqCurve(EQ_CURVES[pick]);
                else {
                    float[] gains = new float[10];
                    for (int i = 0; i < gains.length; i++) gains[i] = curve.evaluate(Audio.EQ_HZ[i]);
                    applyEqCurve(gains);
                }
                graph.refresh();
            });
        });
        graph.selection.run();
    }

    private final class EqualizerCurve extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path(), area = new Path();
        private final Typeface labelFace = Typeface.create("sans-serif", Typeface.NORMAL);
        private LinearGradient fillGradient;
        private float gradTop = -1, gradBottom = -1;
        private int gradAccent;
        Runnable selection, edit;
        private float downY;
        private boolean moved;
        EqualizerCurve() { super(ui.activity); setContentDescription("Equalizer curve. Drag a point to adjust a frequency, or use Fine adjustment below."); }
        void select(int band) { eqSelectedBand = Math.max(0, Math.min(9, band)); if (selection != null) selection.run(); invalidate(); }
        void refresh() { if (selection != null) selection.run(); if (edit != null) edit.run(); invalidate(); }
        private float left() { return ui.dp(36); }
        private float right() { return getWidth() - ui.dp(22); }
        private float top() { return ui.dp(20); }
        private float bottom() { return getHeight() - ui.dp(32); }
        private float y(float gain) { return top() + (12 - gain) / 24f * (bottom() - top()); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float width = right() - left();
            if (width <= 0) return;
            paint.setTypeface(labelFace);
            paint.setTextSize(ui.dp(9)); paint.setStrokeWidth(ui.dp(1));
            for (int db = -12; db <= 12; db += 6) {
                paint.setColor(ui.inkDim); paint.setAlpha(db == 0 ? 65 : 24);
                c.drawLine(left(), y(db), right(), y(db), paint);
                paint.setAlpha(180); paint.setTextAlign(Paint.Align.RIGHT);
                c.drawText(db > 0 ? "+" + db : Integer.toString(db), left() - ui.dp(8), y(db) + ui.dp(3), paint);
            }
            float[] gains = eqGains();
            line.rewind();
            for (int i = 0; i < 10; i++) {
                float x = left() + width * i / 9f;
                if (i == 0) line.moveTo(x, y(gains[i])); else line.lineTo(x, y(gains[i]));
                paint.setColor(ui.inkDim); paint.setAlpha(18); c.drawLine(x, top(), x, bottom(), paint);
                paint.setAlpha(220); paint.setTextAlign(Paint.Align.CENTER);
                String hz = Audio.EQ_HZ[i] < 1000 ? Integer.toString(Audio.EQ_HZ[i]) : (Audio.EQ_HZ[i] / 1000) + "k";
                c.drawText(hz, x, bottom() + ui.dp(20), paint);
            }
            area.set(line); area.lineTo(right(), bottom()); area.lineTo(left(), bottom()); area.close();
            float t = top(), b = bottom();
            if (fillGradient == null || gradTop != t || gradBottom != b || gradAccent != ui.accent) {
                gradTop = t; gradBottom = b; gradAccent = ui.accent;
                fillGradient = new LinearGradient(0, t, 0, b, (ui.accent & 0xffffff) | 0x44000000, ui.accent & 0xffffff, Shader.TileMode.CLAMP);
            }
            paint.setShader(fillGradient);
            paint.setStyle(Paint.Style.FILL); paint.setAlpha(255); c.drawPath(area, paint); paint.setShader(null);
            paint.setColor(ui.accent); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ui.dp(2.5f)); paint.setStrokeJoin(Paint.Join.ROUND); c.drawPath(line, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 10; i++) {
                float x = left() + width * i / 9f, yy = y(gains[i]);
                if (i == eqSelectedBand) { paint.setColor((ui.accent & 0xffffff) | 0x33000000); c.drawCircle(x, yy, ui.dp(12), paint); }
                paint.setColor(ui.accent); c.drawCircle(x, yy, ui.dp(i == eqSelectedBand ? 5 : 3.5f), paint);
                paint.setColor(ui.surf); c.drawCircle(x, yy, ui.dp(1.5f), paint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downY = event.getY(); moved = false;
                select(Math.round((event.getX() - left()) / Math.max(1, right() - left()) * 9));
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (Math.abs(event.getY() - downY) > ui.dp(3)) moved = true;
                if (moved) {
                    float gain = Math.max(-12, Math.min(12, 12 - (event.getY() - top()) / Math.max(1, bottom() - top()) * 24));
                    ui.prefs.edit().putFloat("eq_band_" + eqSelectedBand, Math.round(gain * 10) / 10f).apply();
                    Audio.refreshEqualizer();
                    if (edit != null) edit.run();
                    invalidate();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) performClick();
                return true;
            }
            return false;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

    void render(LinearLayout page) {
        if (panel < 0) {
            LinearLayout master = ui.column();
            ui.bgRound(master, 20, () -> ui.surf);
            page.addView(master, ui.fill(-2));
            ui.toggle(master, "Sound processing", "Apply to playback on this device", "equalizer", false, false);
            equalizerStatus = ui.text(soundStatus(), 13, () -> ui.inkDim, false);
            equalizerStatus.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(14));
            master.addView(equalizerStatus, ui.fill(-2));
            graphicEqualizer(page);
            LinearLayout stages = ui.card(page, "Effects & headphones");
            String[] descriptions = {"", ui.prefs.getString("eq_autoeq_name", "Find a correction for your headphones"),
                "Equal loudness and bass character", "Reverb and stereo width", "Headroom, limiter and channel balance", "Device support and processing buffer"};
            for (int i = 1; i < 6; i++) {
                final int panel = i;
                ui.settingsAction(stages, SOUND_GROUPS[i], descriptions[i], () -> openPanel(panel));
            }
            return;
        }
        if (panel == 0) {
            graphicEqualizer(page);
        } else if (panel == 1) {
            LinearLayout correction = ui.card(page, "AutoEq");
            ui.toggle(correction, "Headphone correction", "Compensate for your headphone’s measured frequency response", "eq_autoeq_enabled", false, false);
            ui.settingsAction(correction, "Find your headphones", ui.prefs.getString("eq_autoeq_name", "Search the AutoEq database"), () -> { openPanel(6); loadHeadphones(); });
            ui.settingsAction(correction, "Import a profile", "Paste GraphicEQ data from a custom measurement", () -> openPanel(7));
            audioSlider(correction, "Correction intensity", "eq_autoeq_intensity", 0, 200, 100, 1, "%", false);
            if (!ui.prefs.getString("eq_autoeq_curve", "").isEmpty()) {
                ui.settingsAction(correction, "Copy profile", "Copy this profile’s GraphicEQ data, to back it up or share it", () -> {
                    String name = ui.prefs.getString("eq_autoeq_name", "Custom profile");
                    String curve = ui.prefs.getString("eq_autoeq_curve", "");
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(name, curve));
                        Toast.makeText(ui.activity, "Profile copied", Toast.LENGTH_SHORT).show();
                    }
                });
                ui.settingsAction(correction, "Remove profile", "Keep your graphic EQ and other sound settings", () -> {
                    ui.prefs.edit().remove("eq_autoeq_name").remove("eq_autoeq_curve").putBoolean("eq_autoeq_enabled", false).apply();
                    Audio.refreshEqualizer(); ui.settingsChanged(false);
                });
            }
            soundNote(correction, "AutoEq by Jaakko Pasanen and the measurement contributors. Choose the exact model and listening mode for your headphones.");
        } else if (panel == 2) {
            LinearLayout loudness = ui.card(page, "Equal loudness");
            ui.toggle(loudness, "Equal loudness", "ISO 226 compensation that follows your media volume", "eq_loudness_enabled", false, false);
            audioSlider(loudness, "Volume threshold", "eq_loudness_threshold", 0, 100, 60, 1, "%", false);
            LinearLayout bass = ui.card(page, "Bass boost");
            ui.toggle(bass, "Bass boost", "Add weight to the low end", "eq_bass_boost_enabled", false, false);
            audioSlider(bass, "Strength", "eq_bass_boost_strength", 0, 100, 50, 1, "%", false);
            LinearLayout tuner = ui.card(page, "Bass tuner");
            ui.toggle(tuner, "Bass tuner", "Tune bass response and dynamics", "eq_bass_tuner_enabled", false, false);
            ui.choice(tuner, "Bass character", new String[]{"Natural", "Transient", "Sustain"}, "eq_bass_tuner_type", 0, false);
            audioSlider(tuner, "Cutoff frequency", "eq_bass_tuner_cutoff_hz", 20, 250, 100, 1, "Hz", false);
            audioSlider(tuner, "Post-gain", "eq_bass_tuner_post_gain_db", -15, 15, 0, .5f, "dB", true);
            equalizerStatus = soundNote(tuner, soundStatus());
        } else if (panel == 3) {
            LinearLayout space = ui.card(page, "Reverberation");
            ui.toggle(space, "Reverberation", "Give the music a sense of room and space", "eq_reverb_enabled", false, false);
            audioPick(space, "Room", new String[]{"Off", "Small room", "Medium room", "Large room", "Medium hall", "Large hall", "Plate"}, "eq_reverb_preset", 1);
            LinearLayout stereo = ui.card(page, "Stereo image");
            ui.toggle(stereo, "Virtualizer", "Widen the stereo image on supported audio systems", "eq_virtualizer_enabled", false, false);
            audioSlider(stereo, "Strength", "eq_virtualizer_strength", 0, 100, 50, 1, "%", false);
            equalizerStatus = soundNote(stereo, soundStatus());
            soundNote(stereo, "Effect availability depends on the Android audio system. You can configure these before playback starts.");
        } else if (panel == 4) {
            LinearLayout attenuation = ui.card(page, "Attenuation");
            ui.toggle(attenuation, "Automatic headroom", "Compensate for boosts across your sound settings to avoid clipping", "eq_attenuator_auto", true, false);
            audioSlider(attenuation, "Manual attenuation", "eq_attenuator_db", -30, 0, 0, .5f, "dB", true);
            soundNote(attenuation, "Manual attenuation applies when automatic headroom is off.");
            LinearLayout limiter = ui.card(page, "Limiter");
            ui.toggle(limiter, "Limiter", "Control peaks and smooth changes in loudness", "eq_limiter_enabled", false, false);
            audioSlider(limiter, "Attack", "eq_limiter_attack_ms", .1f, 100, 1, .1f, "ms", true);
            audioSlider(limiter, "Release", "eq_limiter_release_ms", 1, 500, 60, 1, "ms", true);
            audioSlider(limiter, "Ratio", "eq_limiter_ratio", 1, 20, 4, .5f, ":1", true);
            audioSlider(limiter, "Threshold", "eq_limiter_threshold_db", -40, 0, -6, .5f, "dBFS", true);
            audioSlider(limiter, "Post-gain", "eq_limiter_post_gain_db", -15, 15, 0, .5f, "dB", true);
            LinearLayout balance = ui.card(page, "Channel balance");
            ui.toggle(balance, "Channel balance", "Adjust the left and right channels independently", "eq_balance_enabled", false, false);
            audioSlider(balance, "Left channel", "eq_balance_left_db", -30, 0, 0, .5f, "dB", true);
            audioSlider(balance, "Right channel", "eq_balance_right_db", -30, 0, 0, .5f, "dB", true);
        } else if (panel == 5) {
            LinearLayout compatibility = ui.card(page, "Audio compatibility");
            ui.toggle(compatibility, "Legacy compatibility", "Try the output-mix session when Spotify has no active audio session; may affect other audio", "eq_legacy", false, false);
            audioSlider(compatibility, "Preferred buffer", "eq_buffer_ms", 0, 50, 0, 1, "ms", false);
            soundNote(compatibility, "0 ms uses the device default. Larger buffers can improve stability at the cost of latency.");
            Audio.Capabilities support = Audio.capabilities();
            soundNote(compatibility, Audio.eqStatus + "\n" + (support.usingFallbackEqualizer ? "Using the device equalizer fallback. Advanced dynamics need DynamicsProcessing." : "Advanced effects use Android DynamicsProcessing when available.")
                + (Audio.eqError.isEmpty() ? "" : "\n" + Audio.eqError));
        } else if (panel == 6) {
            android.widget.EditText search = ui.createField("Search headphone models", headphoneQuery, false);
            page.addView(search, ui.fill(ui.dp(52)));
            headphoneResults = ui.column();
            page.addView(headphoneResults, ui.fill(-2));
            search.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { headphoneQuery = s.toString(); renderHeadphones(); }
                public void afterTextChanged(android.text.Editable e) {}
            });
            renderHeadphones();
            loadHeadphones();
        } else if (panel == 7) {
            LinearLayout imported = ui.card(page, "Custom headphone profile");
            android.widget.EditText name = ui.createField("Profile name", "", false);
            android.widget.EditText data = ui.createField("GraphicEQ: frequency gain; frequency gain; …", "", true);
            imported.addView(name, ui.fill(-2)); imported.addView(data, ui.fill(ui.dp(180)));
            TextView error = ui.text("", 13, () -> ui.inkDim, false);
            error.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)); imported.addView(error);
            imported.addView(ui.pill("Import profile", () -> {
                try { activateProfile(name.getText().toString().trim().isEmpty() ? "Custom profile" : name.getText().toString().trim(), data.getText().toString()); }
                catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
            }), ui.fill(ui.dp(48)));
        }
    }

    private void audioPick(LinearLayout box, String label, String[] values, String key, int fallback) {
        int selected = Math.max(0, Math.min(values.length - 1, ui.prefs.getInt(key, fallback)));
        ui.settingsAction(box, label, values[selected], () -> ui.sheet(label, "Choose the sound you prefer", values, pick -> {
            ui.prefs.edit().putInt(key, pick).apply(); Audio.refreshEqualizer(); ui.settingsChanged(false);
        }));
    }

    private android.widget.SeekBar audioSlider(LinearLayout box, String label, String key, float low, float high, float fallback, float step, String unit, boolean fractional) {
        return audioSlider(box, label, key, low, high, fallback, step, unit, fractional, null);
    }

    private android.widget.SeekBar audioSlider(LinearLayout box, String label, String key, float low, float high, float fallback, float step, String unit, boolean fractional, Runnable changed) {
        Object saved = ui.prefs.getAll().get(key);
        float raw = saved instanceof Number ? ((Number) saved).floatValue() : fallback;
        float value = Float.isFinite(raw) ? Math.max(low, Math.min(high, raw)) : fallback;
        LinearLayout words = ui.row();
        words.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), 0);
        words.addView(ui.text(label, 14, () -> ui.ink, true), ui.weight());
        TextView amount = ui.text(audioNumber(value, unit), 13, () -> ui.inkDim, false);
        words.addView(amount); box.addView(words, ui.fill(-2));
        android.widget.SeekBar slider = new android.widget.SeekBar(ui.activity);
        slider.setMax(Math.round((high - low) / step));
        slider.setProgress(Math.round((value - low) / step));
        slider.setContentDescription(label);
        if (android.os.Build.VERSION.SDK_INT >= 30) slider.setStateDescription(amount.getText());
        GradientDrawable track = ui.round(ui.surfHi, 8), fill = ui.round(ui.accent, 8), thumb = ui.round(ui.ink, 3);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{track,
            new android.graphics.drawable.ClipDrawable(fill, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress);
        for (int layer = 0; layer < 2; layer++) { layers.setLayerHeight(layer, ui.dp(8)); layers.setLayerGravity(layer, Gravity.CENTER_VERTICAL); }
        thumb.setSize(ui.dp(5), ui.dp(22)); slider.setProgressDrawable(layers); slider.setThumb(thumb); slider.setSplitTrack(false);
        ui.colored(slider, () -> { track.setColor(ui.surfHi); fill.setColor(ui.accent); thumb.setColor(ui.ink); });
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
                float next = low + progress * step;
                amount.setText(audioNumber(next, unit));
                if (android.os.Build.VERSION.SDK_INT >= 30) bar.setStateDescription(amount.getText());
                if (user) {
                    android.content.SharedPreferences.Editor edit = ui.prefs.edit();
                    if (fractional) edit.putFloat(key, next); else edit.putInt(key, Math.round(next));
                    edit.apply(); Audio.refreshEqualizer();
                    if (changed != null) changed.run();
                }
            }
            public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });
        LinearLayout.LayoutParams params = ui.fill(ui.dp(46)); params.setMargins(ui.dp(12), 0, ui.dp(12), 0);
        box.addView(slider, params);
        return slider;
    }

    private static String audioNumber(float value, String unit) {
        String number = Math.abs(value - Math.round(value)) < .001f ? Integer.toString(Math.round(value)) : String.format(java.util.Locale.ROOT, "%.1f", value);
        return (unit.startsWith("dB") && value > 0 ? "+" : "") + number + (unit.equals(":1") || unit.equals("%") ? "" : " ") + unit;
    }

    private void loadHeadphones() {
        if (headphoneCount > 0 || headphonesLoading) return;
        headphonesLoading = true; headphoneError = "";
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            int found = -1;
            String error = "";
            try { found = AutoEqCatalog.loadIndex(ui.activity.getFilesDir()); }
            catch (Exception failed) { error = failed.getMessage() == null ? "Couldn’t load the headphone database" : failed.getMessage(); }
            final int result = found;
            final String failure = error;
            ui.post(() -> { headphonesLoading = false; headphoneCount = result; headphoneError = failure; renderHeadphones(); });
        });
    }

    private void renderHeadphones() {
        LinearLayout rows = headphoneResults;
        if (rows == null || ui.settingsSection != 2 || panel != 6) return;
        rows.removeAllViews();
        if (!headphoneError.isEmpty()) {
            soundNote(rows, headphoneError);
            rows.addView(ui.pill("Retry", () -> { headphoneError = ""; loadHeadphones(); renderHeadphones(); }));
            return;
        }
        if (headphoneCount <= 0) { soundNote(rows, "Loading the AutoEq headphone database…"); return; }
        String query = headphoneQuery.trim();
        if (query.isEmpty()) { soundNote(rows, headphoneCount + " headphone models. Search for your exact model."); return; }
        List<AutoEqCatalog.Headphone> matches = AutoEqCatalog.search(query, 40);
        for (AutoEqCatalog.Headphone headphone : matches) {
            ui.settingsAction(rows, headphone.name, headphoneLoading.equals(headphone.name) ? "Loading correction…" : "Load this AutoEq correction", () -> {
                ui.hideKeyboard();
                headphoneLoading = headphone.name;
                int request = ++headphoneRequest;
                renderHeadphones();
                ui.submit(SpatchUi.PLAYLISTS, () -> {
                    String curve = "", error = "";
                    try { curve = AutoEqCatalog.profile(headphone); }
                    catch (Exception failed) { error = failed.getMessage() == null ? "Couldn’t download the profile" : failed.getMessage(); }
                    final String data = curve, failure = error;
                    ui.post(() -> {
                        if (request != headphoneRequest) return;
                        headphoneLoading = "";
                        try {
                            if (!failure.isEmpty()) throw new IllegalArgumentException(failure);
                            activateProfile(headphone.name, data);
                        } catch (IllegalArgumentException invalid) {
                            Toast.makeText(ui.activity, invalid.getMessage(), Toast.LENGTH_LONG).show(); renderHeadphones();
                        }
                    });
                });
            });
        }
        if (matches.isEmpty()) soundNote(rows, "No matching headphones. Try the brand and model name, or import your own correction.");
    }

    private void activateProfile(String name, String curve) {
        Audio.importGraphicEq(ui.prefs, name, curve);
        ui.prefs.edit().putBoolean("eq_autoeq_enabled", true).putBoolean("equalizer", true).apply();
        Audio.refreshEqualizer(); ui.hideKeyboard(); openPanel(1);
        Toast.makeText(ui.activity, "Loaded “" + name + "”", Toast.LENGTH_SHORT).show();
    }

}
