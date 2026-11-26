package spatch;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
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
    private boolean eqFadersMode;

    private boolean easeGains(float[] want, float[] shown, boolean drawnOnce) {
        boolean settling = false;
        for (int i = 0; i < 10; i++) {
            float d = want[i] - shown[i];
            shown[i] = !ui.motion() || !drawnOnce || Math.abs(d) < .05f ? want[i] : shown[i] + d * .2f;
            settling |= shown[i] != want[i];
        }
        return settling;
    }

    private void drawDbPill(Canvas c, Paint paint, RectF box, Typeface face, float gain, float cx, float by, float left, float right) {
        float g = Math.round(gain * 10) / 10f;
        String badge = (Math.abs(g) < 0.05f ? "0" : (g > 0 ? "+" : "") + (Math.abs(g - Math.round(g)) < 0.05f ? Integer.toString(Math.round(g)) : String.format(java.util.Locale.ROOT, "%.1f", g))) + " dB";
        paint.setTypeface(face);
        paint.setTextSize(ui.dp(10));
        float bw = paint.measureText(badge) + ui.dp(14), bh = ui.dp(18);
        float bx = Math.max(left, Math.min(right - bw, cx - bw / 2f));
        box.set(bx, by, bx + bw, by + bh);
        paint.setColor(ui.accent);
        paint.setAlpha(255);
        c.drawRoundRect(box, ui.dp(9), ui.dp(9), paint);
        paint.setColor(ui.onAccent);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(badge, box.centerX(), box.centerY() - (paint.descent() + paint.ascent()) / 2f, paint);
    }
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
        button.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        ui.oneLine(button);
        GradientDrawable background = ui.round(ui.surfHi, 14);
        button.setBackground(background);
        ui.colored(button, () -> { button.setTextColor(ui.ink); background.setColor(ui.surfHi); });
    }

    private void graphicEqualizer(LinearLayout page) {
        LinearLayout tone = ui.column();
        ui.bgRound(tone, 20, () -> ui.surf);
        LinearLayout.LayoutParams toneParams = ui.fill(-2); toneParams.topMargin = ui.dp(12);
        page.addView(tone, toneParams);
        ui.settingsScreen.toggle(tone, "Tone curve", "Bypass this curve to compare your sound", "eq_geq_enabled", true, false);
        LinearLayout toolbar = ui.row();
        toolbar.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(4));
        EqualizerCurve graph = new EqualizerCurve();
        EqualizerFaders faders = new EqualizerFaders();
        graph.setVisibility(eqFadersMode ? View.GONE : View.VISIBLE);
        faders.setVisibility(eqFadersMode ? View.VISIBLE : View.GONE);
        TextView preset = ui.pill(eqPresetName() + "  ⌄", () -> {});
        eqToolbarButton(preset);
        toolbar.addView(preset, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
        TextView[] viewToggle = new TextView[1];
        viewToggle[0] = ui.pill(eqFadersMode ? "Curve" : "Faders", () -> {
            eqFadersMode = !eqFadersMode;
            viewToggle[0].setText(eqFadersMode ? "Curve" : "Faders");
            graph.setVisibility(eqFadersMode ? View.GONE : View.VISIBLE);
            faders.setVisibility(eqFadersMode ? View.VISIBLE : View.GONE);
            if (eqFadersMode) faders.invalidate(); else graph.invalidate();
        });
        eqToolbarButton(viewToggle[0]);
        LinearLayout.LayoutParams toggleParams = ui.lp(ui.dp(60), ui.dp(48)); toggleParams.leftMargin = ui.dp(6);
        toolbar.addView(viewToggle[0], toggleParams);
        TextView save = ui.pill("Save", () -> {
            ui.prefs.edit().putString("eq_saved_curve", graphicEqString()).apply();
            ui.toast("Curve saved in Presets");
        });
        eqToolbarButton(save);
        LinearLayout.LayoutParams saveParams = ui.lp(ui.dp(54), ui.dp(48)); saveParams.leftMargin = ui.dp(6);
        toolbar.addView(save, saveParams);
        TextView copy = ui.pill("Copy", () -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Graphic EQ curve", graphicEqString()));
                ui.toast("Curve copied, to back up or share");
            }
        });
        eqToolbarButton(copy);
        LinearLayout.LayoutParams copyParams = ui.lp(ui.dp(54), ui.dp(48)); copyParams.leftMargin = ui.dp(6);
        toolbar.addView(copy, copyParams);
        TextView reset = ui.pill("Reset", () -> { applyEqCurve(new float[10]); graph.refresh(); });
        eqToolbarButton(reset);
        LinearLayout.LayoutParams resetParams = ui.lp(ui.dp(54), ui.dp(48)); resetParams.leftMargin = ui.dp(6);
        toolbar.addView(reset, resetParams);
        tone.addView(toolbar, ui.fill(-2));
        FrameLayout eqSlot = new FrameLayout(ui.activity);
        eqSlot.addView(graph, new FrameLayout.LayoutParams(-1, -1));
        eqSlot.addView(faders, new FrameLayout.LayoutParams(-1, -1));
        tone.addView(eqSlot, ui.fill(ui.dp(224)));
        LinearLayout editor = ui.column();
        tone.addView(editor, ui.fill(-2));
        final android.widget.SeekBar[] fine = {null};
        graph.selection = faders.selection = () -> {
            editor.removeAllViews();
            TextView band = ui.text(eqFrequency(eqSelectedBand) + "  ⌄", 15, () -> ui.ink, true);
            band.setPadding(ui.dp(18), 0, ui.dp(18), 0);
            ui.tappable(band, "Choose frequency band", () -> {
                String[] names = new String[10];
                for (int i = 0; i < names.length; i++) names[i] = eqFrequency(i) + " · " + audioNumber(Audio.gain(ui.prefs, i), "dB");
                ui.sheet("Frequency band", "Choose a band, or drag on the EQ", names, pick -> { graph.select(pick); faders.invalidate(); });
            });
            editor.addView(band, ui.fill(ui.dp(42)));
            fine[0] = audioSlider(editor, "Fine adjustment", "eq_band_" + eqSelectedBand, -12, 12, 0, .1f, "dB", true,
                () -> { graph.invalidate(); faders.invalidate(); preset.setText(eqPresetName() + "  ⌄"); });
        };
        graph.edit = faders.edit = () -> {
            if (fine[0] != null) fine[0].setProgress(Math.round((Audio.gain(ui.prefs, eqSelectedBand) + 12) * 10));
            preset.setText(eqPresetName() + "  ⌄");
            faders.invalidate();
            graph.invalidate();
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
        private final RectF pill = new RectF();
        private final Typeface labelFace = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface badgeFace = Typeface.create("sans-serif", Typeface.BOLD);
        private LinearGradient fillGradient;
        private float gradTop = -1, gradBottom = -1;
        private int gradAccent;
        Runnable selection, edit;
        private float downY, downGain;
        private boolean moved, drawnOnce;
        private final float[] shown = new float[10];
        private final float[] spectrum = new float[Audio.BANDS.length];
        private final float[] specBins = new float[16];
        private Shader spectrumShader;
        private final Path spectrumPath = new Path(), correction = new Path(), correctionArea = new Path();
        private Audio.GraphicEqProfile correctionOf;
        private float correctionWidth;
        private long lastFrame;
        /** Read-only, for the AutoEq page: the grid, live spectrum and headphone correction, without your own bands. */
        private final boolean correctionOnly;
        EqualizerCurve() { this(false); }
        EqualizerCurve(boolean correctionOnly) {
            super(ui.activity);
            this.correctionOnly = correctionOnly;
            setContentDescription(correctionOnly ? "Headphone correction curve" : "Equalizer curve. Drag a point to adjust a frequency, or use Fine adjustment below.");
        }
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
            boolean moving = drawSpectrum(c, width);
            moving |= drawCorrection(c, width);
            if (correctionOnly) {
                paint.setTypeface(labelFace);
                paint.setTextSize(ui.dp(9));
                for (int i = 0; i < 10; i++) {
                    paint.setColor(ui.inkDim);
                    paint.setAlpha(220);
                    paint.setTextAlign(Paint.Align.CENTER);
                    String hz = Audio.EQ_HZ[i] < 1000 ? Integer.toString(Audio.EQ_HZ[i]) : (Audio.EQ_HZ[i] / 1000) + "k";
                    c.drawText(hz, left() + width * i / 9f, bottom() + ui.dp(20), paint);
                }
                if (moving && isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
                return;
            }
            // The drawn gains glide to the stored ones, so a preset change sweeps instead of jumping.
            float[] gains = shown;
            boolean settling = easeGains(eqGains(), gains, drawnOnce);
            drawnOnce = true;
            for (int i = 0; i < 10; i++) {
                float x = left() + width * i / 9f;
                paint.setColor(i == eqSelectedBand ? ui.accent : ui.inkDim);
                paint.setAlpha(i == eqSelectedBand ? 70 : 18);
                paint.setStrokeWidth(ui.dp(i == eqSelectedBand ? 1.5f : 1));
                c.drawLine(x, top(), x, bottom(), paint);
                paint.setColor(ui.inkDim);
                paint.setAlpha(i == eqSelectedBand ? 255 : 220); paint.setTextAlign(Paint.Align.CENTER);
                String hz = Audio.EQ_HZ[i] < 1000 ? Integer.toString(Audio.EQ_HZ[i]) : (Audio.EQ_HZ[i] / 1000) + "k";
                c.drawText(hz, x, bottom() + ui.dp(20), paint);
            }
            // Sum neighbouring bands with Gaussian bells so the curve reads like a continuous frequency response.
            line.rewind();
            final int steps = 90;
            for (int s = 0; s <= steps; s++) {
                float u = 9f * s / steps;
                float resp = 0;
                for (int i = 0; i < 10; i++) {
                    float d = (u - i) / 0.58f;
                    resp += gains[i] * (float) Math.exp(-0.5 * d * d);
                }
                resp = Math.max(-14f, Math.min(14f, resp));
                float x = left() + width * s / steps, yy = y(resp);
                if (s == 0) line.moveTo(x, yy); else line.lineTo(x, yy);
            }
            area.set(line); area.lineTo(right(), bottom()); area.lineTo(left(), bottom()); area.close();
            float t = top(), b = bottom();
            if (fillGradient == null || gradTop != t || gradBottom != b || gradAccent != ui.accent) {
                gradTop = t; gradBottom = b; gradAccent = ui.accent;
                fillGradient = new LinearGradient(0, t, 0, b, (ui.accent & 0xffffff) | 0x44000000, ui.accent & 0xffffff, Shader.TileMode.CLAMP);
            }
            paint.setShader(fillGradient);
            paint.setStyle(Paint.Style.FILL); paint.setAlpha(255); c.drawPath(area, paint); paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor((ui.accent & 0xffffff) | 0x30000000); paint.setStrokeWidth(ui.dp(9)); c.drawPath(line, paint); // soft glow under the line
            paint.setColor(ui.accent); paint.setStrokeWidth(ui.dp(2.5f)); c.drawPath(line, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 10; i++) {
                float x = left() + width * i / 9f, yy = y(gains[i]);
                if (i == eqSelectedBand) { paint.setColor((ui.accent & 0xffffff) | 0x33000000); c.drawCircle(x, yy, ui.dp(12), paint); }
                paint.setColor(ui.accent); c.drawCircle(x, yy, ui.dp(i == eqSelectedBand ? 5 : 3.5f), paint);
                paint.setColor(ui.surf); c.drawCircle(x, yy, ui.dp(1.5f), paint);
            }
            // Floating dB pill near the selected handle, kept strictly within view bounds.
            float selX = left() + width * eqSelectedBand / 9f, selY = y(gains[eqSelectedBand]);
            float by = selY - ui.dp(28) >= ui.dp(4) ? selY - ui.dp(28) : selY + ui.dp(10);
            drawDbPill(c, paint, pill, badgeFace, gains[eqSelectedBand], selX, by, left(), right());
            if ((settling || moving) && isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
        }

        /**
         * A faint live spectrum of what's playing behind the curve, on the curve's own frequency axis, so you can see
         * which bands a change will touch. It runs only while music plays; paused, it sinks away and stops.
         */
        private boolean drawSpectrum(Canvas c, float width) {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? .016f : Math.min(.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean live = ui.playing && ui.motion() && ui.pref("audio", true) && Audio.ready(ui.activity);
            if (live) Audio.want();
            float peak = 0f, h = bottom() - top();
            for (int i = 0; i < spectrum.length; i++) {
                float want = live ? Math.min(1f, Audio.BANDS[i]) : 0f;
                spectrum[i] += (want - spectrum[i]) * (1f - (float) Math.exp(-dt / (want > spectrum[i] ? .04f : .18f)));
                peak = Math.max(peak, spectrum[i]);
            }
            if (peak < .004f) { lastFrame = 0; return false; }
            if (GpuFx.shaders(ui.prefs) && c.isHardwareAccelerated()) {
                if (spectrumShader == null) {
                    String src = GpuFx.source("spectrum");
                    if (src != null) spectrumShader = new android.graphics.RuntimeShader(src);
                }
                if (spectrumShader != null) {
                    java.util.Arrays.fill(specBins, 0f);
                    for (int i = 0; i < spectrum.length; i++) {
                        int bin = Math.max(0, Math.min(15, Math.round(BAND_AT[i] / 9f * 15f)));
                        specBins[bin] = Math.max(specBins[bin], spectrum[i] * .8f);
                    }
                    android.graphics.RuntimeShader rs = (android.graphics.RuntimeShader) spectrumShader;
                    rs.setFloatUniform("rect", left(), top(), width, h);
                    rs.setFloatUniform("params", 16f, .15f, .28f, ui.dp(3.5f));
                    rs.setFloatUniform("v0", specBins[0], specBins[1], specBins[2], specBins[3]);
                    rs.setFloatUniform("v1", specBins[4], specBins[5], specBins[6], specBins[7]);
                    rs.setFloatUniform("v2", specBins[8], specBins[9], specBins[10], specBins[11]);
                    rs.setFloatUniform("v3", specBins[12], specBins[13], specBins[14], specBins[15]);
                    rs.setColorUniform("tint", SpatchUi.blend(ui.ink, ui.accent, .45f));
                    paint.setStyle(Paint.Style.FILL);
                    paint.setShader(rs);
                    c.drawRect(left(), top(), right(), bottom(), paint);
                    paint.setShader(null);
                    return true;
                }
            }
            spectrumPath.rewind();
            spectrumPath.moveTo(left(), bottom());
            for (int i = 0; i < spectrum.length; i++) spectrumPath.lineTo(left() + width * BAND_AT[i] / 9f, bottom() - spectrum[i] * h * .8f);
            spectrumPath.lineTo(left() + width * BAND_AT[spectrum.length - 1] / 9f, bottom());
            spectrumPath.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((ui.ink & 0x00ffffff) | 0x1c000000);
            c.drawPath(spectrumPath, paint);
            return true;
        }

        /**
         * The headphone correction, as its own line in the second accent. Just after a profile is applied it draws
         * itself from left to right, fills in under it, and a small confirmation pops in above.
         */
        private boolean drawCorrection(Canvas c, float width) {
            Audio.GraphicEqProfile profile = ui.pref("eq_autoeq_enabled", false) ? Audio.loadGraphicEq(ui.prefs) : null;
            if (profile == null || profile.freqs.length < 2) return false;
            if (profile != correctionOf || correctionWidth != width) {
                correctionOf = profile;
                correctionWidth = width;
                correction.rewind();
                boolean started = false;
                for (int i = 0; i < profile.freqs.length; i++) {
                    // Only what the axis shows (31 Hz to 16 kHz): points beyond it would pile up on its ends.
                    if (profile.freqs[i] < Audio.EQ_HZ[0] || profile.freqs[i] > Audio.EQ_HZ[Audio.EQ_HZ.length - 1]) continue;
                    float x = left() + width * axisAt(profile.freqs[i]) / 9f, yy = y(Math.max(-12f, Math.min(12f, profile.gains[i])));
                    if (!started) { correction.moveTo(x, yy); started = true; } else correction.lineTo(x, yy);
                }
                if (!started) return false;
                correctionArea.set(correction);
                correctionArea.lineTo(left() + width, y(0));
                correctionArea.lineTo(left(), y(0));
                correctionArea.close();
            }
            long since = revealAt == 0 ? Long.MAX_VALUE / 2 : SystemClock.uptimeMillis() - revealAt;
            float drawn = !ui.motion() ? 1f : Math.min(1f, since / 900f);
            drawn = 1f - (1f - drawn) * (1f - drawn);
            float filled = !ui.motion() ? 1f : Math.max(0f, Math.min(1f, (since - 450) / 500f));
            c.save();
            c.clipRect(left() - ui.dp(4), 0, left() + width * drawn + ui.dp(4), getHeight());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((ui.accent2 & 0x00ffffff) | Math.round(0x26 * filled) << 24);
            c.drawPath(correctionArea, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(ui.dp(1.8f));
            paint.setColor((ui.accent2 & 0x00ffffff) | 0xcc000000);
            c.drawPath(correction, paint);
            c.restore();
            paint.setStyle(Paint.Style.FILL);
            boolean popping = since < 2800;
            if (popping && ui.motion()) {
                float in = Math.max(0f, Math.min(1f, (since - 800) / 320f));
                float out = Math.max(0f, Math.min(1f, (since - 2400) / 400f));
                float scale = in < 1f ? POP.getInterpolation(in) : 1f;
                if (in > 0f) {
                    String text = revealName;
                    paint.setTypeface(badgeFace);
                    paint.setTextSize(ui.dp(12));
                    float tw = Math.min(paint.measureText(text), right() - left() - ui.dp(24)), cx = (left() + right()) / 2f, cy = top() + ui.dp(2);
                    c.save();
                    c.scale(scale, scale, cx, cy);
                    pill.set(cx - tw / 2f - ui.dp(12), cy - ui.dp(13), cx + tw / 2f + ui.dp(12), cy + ui.dp(13));
                    int alpha = Math.round(255 * (1f - out));
                    paint.setColor((ui.accent2 & 0x00ffffff) | alpha << 24);
                    c.drawRoundRect(pill, ui.dp(13), ui.dp(13), paint);
                    paint.setColor((ui.onAccent & 0x00ffffff) | alpha << 24);
                    paint.setTextAlign(Paint.Align.CENTER);
                    c.drawText(text, 0, text.length(), cx, cy + ui.dp(4), paint);
                    c.restore();
                }
            }
            return ui.motion() && (drawn < 1f || filled < 1f || popping);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (correctionOnly) return false; // nothing to drag on the AutoEq page
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downY = event.getY(); moved = false;
                select(Math.round((event.getX() - left()) / Math.max(1, right() - left()) * 9));
                downGain = Audio.gain(ui.prefs, eqSelectedBand);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (Math.abs(event.getY() - downY) > ui.dp(3)) moved = true;
                if (moved) {
                    float gain = Math.max(-12, Math.min(12, downGain - (event.getY() - downY) / Math.max(1, bottom() - top()) * 24f));
                    ui.prefs.edit().putFloat("eq_band_" + eqSelectedBand, Math.round(gain * 10) / 10f).apply();
                    shown[eqSelectedBand] = Math.round(gain * 10) / 10f; // the finger's point follows at once
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

    /** 10 vertical center-zero band faders with relative delta drag. */
    private final class EqualizerFaders extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Typeface labelFace = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface badgeFace = Typeface.create("sans-serif", Typeface.BOLD);
        private final float[] shown = new float[10];
        Runnable selection, edit;
        private float downY, downGain;
        private boolean moved, drawnOnce;

        EqualizerFaders() {
            super(ui.activity);
            setContentDescription("Equalizer faders. Drag up or down on any band to adjust its gain.");
        }

        private float left() { return ui.dp(18); }
        private float right() { return getWidth() - ui.dp(18); }
        private float top() { return ui.dp(28); }
        private float bottom() { return getHeight() - ui.dp(28); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = right() - left(), t = top(), b = bottom();
            if (w <= 0 || b <= t) return;
            float[] gains = shown;
            boolean settling = easeGains(eqGains(), gains, drawnOnce);
            drawnOnce = true;
            float colW = w / 10f, midY = (t + b) / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1));
            paint.setColor(ui.inkDim);
            paint.setAlpha(48);
            c.drawLine(left(), midY, right(), midY, paint);
            paint.setStyle(Paint.Style.FILL);
            float trackHalf = ui.dp(2.5f);
            for (int i = 0; i < 10; i++) {
                float cx = left() + colW * (i + .5f);
                boolean sel = i == eqSelectedBand;
                paint.setColor(sel ? ui.accent : ui.inkDim);
                paint.setAlpha(sel ? 52 : 36);
                box.set(cx - trackHalf, t, cx + trackHalf, b);
                c.drawRoundRect(box, trackHalf, trackHalf, paint);

                float valY = t + (12f - gains[i]) / 24f * (b - t);
                paint.setColor(ui.accent);
                paint.setAlpha(sel ? 255 : 190);
                box.set(cx - trackHalf, Math.min(midY, valY), cx + trackHalf, Math.max(midY, valY));
                c.drawRoundRect(box, trackHalf, trackHalf, paint);

                box.set(cx - ui.dp(11), valY - ui.dp(4.5f), cx + ui.dp(11), valY + ui.dp(4.5f));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(sel ? ui.ink : ui.surfHi);
                paint.setAlpha(255);
                c.drawRoundRect(box, ui.dp(4.5f), ui.dp(4.5f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(1.5f));
                paint.setColor(ui.accent);
                c.drawRoundRect(box, ui.dp(4.5f), ui.dp(4.5f), paint);
                paint.setStyle(Paint.Style.FILL);

                paint.setTypeface(labelFace);
                paint.setTextSize(ui.dp(9));
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(sel ? ui.accent : ui.inkDim);
                paint.setAlpha(sel ? 255 : 210);
                String hz = Audio.EQ_HZ[i] < 1000 ? Integer.toString(Audio.EQ_HZ[i]) : (Audio.EQ_HZ[i] / 1000) + "k";
                c.drawText(hz, cx, b + ui.dp(18), paint);
            }
            float selX = left() + colW * (eqSelectedBand + .5f);
            drawDbPill(c, paint, box, badgeFace, gains[eqSelectedBand], selX, ui.dp(4), left(), right());
            if (settling) postInvalidateOnAnimation();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            float w = right() - left(), travel = Math.max(1f, bottom() - top());
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downY = event.getY();
                moved = false;
                if (w > 0) eqSelectedBand = Math.max(0, Math.min(9, (int) ((event.getX() - left()) / (w / 10f))));
                downGain = Audio.gain(ui.prefs, eqSelectedBand);
                if (selection != null) selection.run();
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (Math.abs(event.getY() - downY) > ui.dp(3)) moved = true;
                if (moved) {
                    float gain = Math.max(-12f, Math.min(12f, downGain - (event.getY() - downY) / travel * 24f));
                    float rounded = Math.round(gain * 10) / 10f;
                    ui.prefs.edit().putFloat("eq_band_" + eqSelectedBand, rounded).apply();
                    shown[eqSelectedBand] = rounded;
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
            ui.settingsScreen.toggle(master, "Sound processing", "Apply to playback on this device", "equalizer", false, false);
            equalizerStatus = ui.text(soundStatus(), 13, () -> ui.inkDim, false);
            equalizerStatus.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(14));
            master.addView(equalizerStatus, ui.fill(-2));
            graphicEqualizer(page);
            LinearLayout stages = ui.settingsScreen.card(page, "Effects & headphones");
            String[] descriptions = {"", ui.prefs.getString("eq_autoeq_name", "Find a correction for your headphones"),
                "Equal loudness and bass character", "Reverb and stereo width", "Headroom, limiter and channel balance", "Device support and processing buffer"};
            for (int i = 1; i < 6; i++) {
                final int panel = i;
                ui.settingsScreen.settingsAction(stages, SOUND_GROUPS[i], descriptions[i], () -> openPanel(panel));
            }
            return;
        }
        if (panel == 0) {
            graphicEqualizer(page);
        } else if (panel == 1) {
            LinearLayout correction = ui.settingsScreen.card(page, "AutoEq");
            if (!ui.prefs.getString("eq_autoeq_curve", "").isEmpty()) {
                // The loaded correction, drawn in from the left just after it's applied, with the confirmation above.
                LinearLayout.LayoutParams gp = ui.fill(ui.dp(190));
                gp.setMargins(ui.dp(8), ui.dp(4), ui.dp(8), 0);
                correction.addView(new EqualizerCurve(true), gp);
            }
            ui.settingsScreen.toggle(correction, "Headphone correction", "Compensate for your headphone’s measured frequency response", "eq_autoeq_enabled", false, false);
            ui.settingsScreen.settingsAction(correction, "Find your headphones", ui.prefs.getString("eq_autoeq_name", "Search the AutoEq database"), () -> { openPanel(6); loadHeadphones(); });
            ui.settingsScreen.settingsAction(correction, "Import a profile", "Paste GraphicEQ data from a custom measurement", () -> openPanel(7));
            audioSlider(correction, "Correction intensity", "eq_autoeq_intensity", 0, 200, 100, 1, "%", false);
            if (!ui.prefs.getString("eq_autoeq_curve", "").isEmpty()) {
                ui.settingsScreen.settingsAction(correction, "Copy profile", "Copy this profile’s GraphicEQ data, to back it up or share it", () -> {
                    String name = ui.prefs.getString("eq_autoeq_name", "Custom profile");
                    String curve = ui.prefs.getString("eq_autoeq_curve", "");
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(name, curve));
                        ui.toast("Profile copied");
                    }
                });
                ui.settingsScreen.settingsAction(correction, "Remove profile", "Keep your graphic EQ and other sound settings", () -> {
                    ui.prefs.edit().remove("eq_autoeq_name").remove("eq_autoeq_curve").putBoolean("eq_autoeq_enabled", false).apply();
                    Audio.refreshEqualizer(); ui.settingsScreen.settingsChanged(false);
                });
            }
            soundNote(correction, "AutoEq by Jaakko Pasanen and the measurement contributors. Choose the exact model and listening mode for your headphones.");
        } else if (panel == 2) {
            LinearLayout loudness = ui.settingsScreen.card(page, "Equal loudness");
            ui.settingsScreen.toggle(loudness, "Equal loudness", "ISO 226 compensation that follows your media volume", "eq_loudness_enabled", false, false);
            audioSlider(loudness, "Volume threshold", "eq_loudness_threshold", 0, 100, 60, 1, "%", false);
            LinearLayout bass = ui.settingsScreen.card(page, "Bass boost");
            ui.settingsScreen.toggle(bass, "Bass boost", "Add weight to the low end", "eq_bass_boost_enabled", false, false);
            audioSlider(bass, "Strength", "eq_bass_boost_strength", 0, 100, 50, 1, "%", false);
            LinearLayout tuner = ui.settingsScreen.card(page, "Bass tuner");
            ui.settingsScreen.toggle(tuner, "Bass tuner", "Tune bass response and dynamics", "eq_bass_tuner_enabled", false, false);
            ui.settingsScreen.choice(tuner, "Bass character", new String[]{"Natural", "Transient", "Sustain"}, "eq_bass_tuner_type", 0, false);
            audioSlider(tuner, "Cutoff frequency", "eq_bass_tuner_cutoff_hz", 20, 250, 100, 1, "Hz", false);
            audioSlider(tuner, "Post-gain", "eq_bass_tuner_post_gain_db", -15, 15, 0, .5f, "dB", true);
            equalizerStatus = soundNote(tuner, soundStatus());
        } else if (panel == 3) {
            LinearLayout space = ui.settingsScreen.card(page, "Reverberation");
            ui.settingsScreen.toggle(space, "Reverberation", "Give the music a sense of room and space", "eq_reverb_enabled", false, false);
            audioPick(space, "Room", new String[]{"Off", "Small room", "Medium room", "Large room", "Medium hall", "Large hall", "Plate"}, "eq_reverb_preset", 1);
            LinearLayout stereo = ui.settingsScreen.card(page, "Stereo image");
            ui.settingsScreen.toggle(stereo, "Virtualizer", "Widen the stereo image on supported audio systems", "eq_virtualizer_enabled", false, false);
            audioSlider(stereo, "Strength", "eq_virtualizer_strength", 0, 100, 50, 1, "%", false);
            equalizerStatus = soundNote(stereo, soundStatus());
            soundNote(stereo, "Effect availability depends on the Android audio system. You can configure these before playback starts.");
        } else if (panel == 4) {
            LinearLayout attenuation = ui.settingsScreen.card(page, "Attenuation");
            ui.settingsScreen.toggle(attenuation, "Automatic headroom", "Compensate for boosts across your sound settings to avoid clipping", "eq_attenuator_auto", true, false);
            audioSlider(attenuation, "Manual attenuation", "eq_attenuator_db", -30, 0, 0, .5f, "dB", true);
            soundNote(attenuation, "Manual attenuation applies when automatic headroom is off.");
            LinearLayout limiter = ui.settingsScreen.card(page, "Limiter");
            ui.settingsScreen.toggle(limiter, "Limiter", "Control peaks and smooth changes in loudness", "eq_limiter_enabled", false, false);
            audioSlider(limiter, "Attack", "eq_limiter_attack_ms", .1f, 100, 1, .1f, "ms", true);
            audioSlider(limiter, "Release", "eq_limiter_release_ms", 1, 500, 60, 1, "ms", true);
            audioSlider(limiter, "Ratio", "eq_limiter_ratio", 1, 20, 4, .5f, ":1", true);
            audioSlider(limiter, "Threshold", "eq_limiter_threshold_db", -40, 0, -6, .5f, "dBFS", true);
            audioSlider(limiter, "Post-gain", "eq_limiter_post_gain_db", -15, 15, 0, .5f, "dB", true);
            LinearLayout balance = ui.settingsScreen.card(page, "Channel balance");
            ui.settingsScreen.toggle(balance, "Channel balance", "Adjust the left and right channels independently", "eq_balance_enabled", false, false);
            audioSlider(balance, "Left channel", "eq_balance_left_db", -30, 0, 0, .5f, "dB", true);
            audioSlider(balance, "Right channel", "eq_balance_right_db", -30, 0, 0, .5f, "dB", true);
        } else if (panel == 5) {
            LinearLayout compatibility = ui.settingsScreen.card(page, "Audio compatibility");
            ui.settingsScreen.toggle(compatibility, "Legacy compatibility", "Try the output-mix session when Spotify has no active audio session; may affect other audio", "eq_legacy", false, false);
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
            LinearLayout imported = ui.settingsScreen.card(page, "Custom headphone profile");
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
        ui.settingsScreen.settingsAction(box, label, values[selected], () -> ui.sheet(label, "Choose the sound you prefer", values, pick -> {
            ui.prefs.edit().putInt(key, pick).apply(); Audio.refreshEqualizer(); ui.settingsScreen.settingsChanged(false);
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
            ui.settingsScreen.settingsAction(rows, headphone.name, headphoneLoading.equals(headphone.name) ? "Loading correction…" : "Load this AutoEq correction", () -> {
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
                            ui.toast(invalid.getMessage(), true); renderHeadphones();
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
        Audio.refreshEqualizer(); ui.hideKeyboard();
        // The curve panel draws the new correction in from the left and pops the confirmation in itself.
        revealAt = SystemClock.uptimeMillis();
        revealName = "✓  " + name;
        openPanel(1);
        if (!ui.motion()) ui.toast("Loaded “" + name + "”");
    }

    /** When a headphone profile was last applied (0 for never), and its name: the curve's draw-in and confirmation. */
    private long revealAt;
    private String revealName = "";
    private static final android.view.animation.Interpolator POP = new android.view.animation.OvershootInterpolator(2.2f);

    /** Each spectrum band's place on the curve's axis (0 at the first EQ band, 9 at the last); log-spaced like the bands. */
    private static final float[] BAND_AT = new float[Audio.BANDS.length];
    static {
        for (int i = 0; i < BAND_AT.length; i++) BAND_AT[i] = axisAt(40f * (float) Math.pow(16000f / 40f, i / (float) (BAND_AT.length - 1)));
    }

    /** Where a frequency sits on the curve's axis: between the two EQ bands around it, by log frequency. */
    static float axisAt(float hz) {
        int[] at = Audio.EQ_HZ;
        if (hz <= at[0]) return 0f;
        for (int i = 0; i + 1 < at.length; i++) {
            if (hz <= at[i + 1]) return i + (float) (Math.log(hz / at[i]) / Math.log(at[i + 1] / (double) at[i]));
        }
        return at.length - 1;
    }

}
