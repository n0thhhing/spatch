package spatch;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/** Shared cover rendering and player artwork effects. */
final class ArtworkViews {
    /** Width, height and blur radius of each backdrop style: soft and deep are smooth blurs, streaks a stretched sliver. */
    private static final int[][] BLUR_SIZES = {{56, 56, 4}, {40, 40, 6}, {2, 16, 0}};
    /** The same softness for the GPU blur, as Gaussian sigma over the image width (what three box passes add up to). */
    private static final float[] GPU_SIGMA = {.08f, .16f, 0f};
    private static final int[] MOTE_COUNTS = {12, 30, 64};
    private static final int[] MOTE_SHAPES = {Confetti.DOT, Confetti.STAR, Confetti.HEART};
    private final SpatchUi ui;
    ArtworkViews(SpatchUi ui) { this.ui = ui; }

    /** Tiny filtered textures keep backdrop effects off the per-frame allocation path. */
    static void cacheBlurs(String key, Bitmap cover, boolean gpu) {
        for (int i = 0; i < BLUR_SIZES.length; i++) {
            int[] size = BLUR_SIZES[i];
            Bitmap made = size[2] != 0 && gpu ? GpuFx.blurOnce(cover, 128, GPU_SIGMA[i]) : null;
            SpatchUi.IMAGES.put(key + "/blur/" + i, made != null ? made : size[2] == 0 ? Bitmap.createScaledBitmap(cover, size[0], size[1], true) : Blur.soft(cover, size[0], size[1], size[2]));
        }
    }

    final class Art extends View {
        static final int CIRCLE = 0, ROUNDED = 1, WIDE = 2;
        int shapeKind;
        String letter = "";
        /** Liked Songs, which has no cover: a gradient tile with a heart instead. */
        boolean heart;
        private final Path heartPath = new Path();
        private String url = "";
        private final boolean ring;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix crop = new Matrix();
        private final RectF box = new RectF(), ringBox = new RectF();
        private LinearGradient phShader;
        private float phW, phH;
        private int phC1, phC2;
        /** Mini player and hero: a new cover fades in over the old one instead of replacing it. */
        boolean crossfade;
        private boolean waiting;
        private long shownAt;
        private Bitmap shownBitmap, previousBitmap;
        private String shownUrl = "";

        Art(int shape, boolean ring) { super(ui.activity); this.shapeKind = shape; this.ring = ring; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        void bind(String u) {
            if (crossfade && !u.isEmpty() && shownBitmap == null && ui.lastCover != null) { shownBitmap = ui.lastCover; shownUrl = ui.lastCoverUrl; }
            if (url.equals(u)) return;
            url = u;
            waiting = false;
            if (!crossfade || u.isEmpty()) { shownAt = 0; shownBitmap = previousBitmap = null; shownUrl = ""; }
            invalidate();
        }

        private Bitmap shaderBmpA, shaderBmpB;
        private BitmapShader shaderA, shaderB;

        private BitmapShader shaderFor(Bitmap b) {
            if (b == shaderBmpA) return shaderA;
            if (b == shaderBmpB) return shaderB;
            BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaderBmpB = shaderBmpA; shaderB = shaderA;
            shaderBmpA = b; shaderA = s;
            return s;
        }

        private void drawCover(Canvas c, Bitmap b, int alpha, float radius) {
            BitmapShader shader = shaderFor(b);
            float k = Math.max(box.width() / b.getWidth(), box.height() / b.getHeight());
            crop.setScale(k, k);
            crop.postTranslate(box.left + (box.width() - b.getWidth() * k) / 2, box.top + (box.height() - b.getHeight() * k) / 2);
            shader.setLocalMatrix(crop);
            paint.setShader(shader);
            paint.setAlpha(alpha);
            c.drawRoundRect(box, radius, radius, paint);
            paint.setAlpha(255);
            paint.setShader(null);
        }

        private LinearGradient shimmerShader;
        private float shimmerW, shimmerH;
        private int shimmerInk;
        private final Matrix shimmerMatrix = new Matrix();

        private LinearGradient placeholderShader(float w, float h, int c1, int c2) {
            if (phShader == null || phW != w || phH != h || phC1 != c1 || phC2 != c2) {
                phW = w; phH = h; phC1 = c1; phC2 = c2;
                phShader = new LinearGradient(0, 0, w, h, c1, c2, Shader.TileMode.CLAMP);
            }
            return phShader;
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w == 0) return;
            float pad = ring ? ui.dp(4) : 0;
            box.set(pad, pad, w - pad, h - pad);
            float r = shapeKind == CIRCLE ? Math.min(box.width(), box.height()) / 2 : shapeKind == WIDE ? 0 : ui.dp(16);
            long now = SystemClock.uptimeMillis();
            Bitmap b = ui.art(url, w > ui.dp(200) || h > ui.dp(190) ? 440 : 180);
            if (b == null) b = ui.art(url, 180);
            if (b != null && !url.isEmpty() && url.equals(ui.image) && (ui.lastCover == null || !url.equals(ui.lastCoverUrl) || b.getWidth() >= ui.lastCover.getWidth())) {
                ui.lastCover = b;
                ui.lastCoverUrl = url;
            }
            if (crossfade) {
                if (b != null && b != shownBitmap) {
                    boolean hadShown = shownBitmap != null;
                    if (!url.equals(shownUrl)) {
                        previousBitmap = ui.motion() ? shownBitmap : null;
                        shownBitmap = b;
                        shownUrl = url;
                        shownAt = previousBitmap != null ? now : 0;
                    } else {
                        shownBitmap = b;
                    }
                    if (hadShown) waiting = false;
                } else if (b == null && !url.isEmpty() && shownBitmap != null) {
                    b = shownBitmap;
                }
            }
            if (previousBitmap != null && shownAt != 0 && now - shownAt < 420) {
                drawCover(c, previousBitmap, 255, r);
            } else previousBitmap = null;
            if (b == null || (previousBitmap == null && shownAt != 0 && now - shownAt < 320)) {
                paint.setShader(placeholderShader(w, h, ui.surfHi, ui.surf));
                c.drawRoundRect(box, r, r, paint);
                paint.setShader(null);
            }
            if (b == null && heart) {
                paint.setShader(placeholderShader(w, h, SpatchUi.blend(ui.surfHi, ui.accent2, .55f), SpatchUi.blend(ui.surfHi, ui.accent, .45f)));
                c.drawRoundRect(box, r, r, paint);
                paint.setShader(null);
                float size = Math.min(w, h) * .42f;
                c.save();
                c.translate(w / 2 - size / 2, h / 2 - size / 2);
                SpatchUi.drawGlyph(c, paint, heartPath, "heart", ui.ink, size);
                c.restore();
            } else if (b == null) {
                waiting = true;
                // Still loading: a soft band of light sweeps across the placeholder until the cover arrives.
                if (!url.isEmpty() && !ui.artFailed(url + ":180") && ui.motion() && isShown() && getWindowVisibility() == VISIBLE && !ui.playerOpen) {
                    float t = (SystemClock.uptimeMillis() % 1400) / 1400f, x = -w + t * w * 3;
                    if (shimmerShader == null || shimmerW != w || shimmerH != h || shimmerInk != ui.ink) {
                        shimmerW = w;
                        shimmerH = h;
                        shimmerInk = ui.ink;
                        int glow = (ui.ink & 0x00ffffff) | 0x14000000, clear = ui.ink & 0x00ffffff;
                        shimmerShader = new LinearGradient(0, 0, w, h, new int[]{clear, glow, clear}, null, Shader.TileMode.CLAMP);
                    }
                    shimmerMatrix.setTranslate(x, 0);
                    shimmerShader.setLocalMatrix(shimmerMatrix);
                    paint.setShader(shimmerShader);
                    c.drawRoundRect(box, r, r, paint);
                    paint.setShader(null);
                    postInvalidateOnAnimation();
                }
                if (!letter.isEmpty() && (url.isEmpty() || ui.artFailed(url + ":180"))) {
                    paint.setColor(ui.inkDim);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(Math.min(w, h) * .4f);
                    paint.setFakeBoldText(true);
                    c.drawText(letter, w / 2, h / 2 - (paint.descent() + paint.ascent()) / 2, paint);
                    paint.setFakeBoldText(false);
                }
            } else {
                if (waiting && ui.motion()) { waiting = false; shownAt = now; }
                long span = previousBitmap != null ? 420 : 320;
                float f = shownAt == 0 ? 1f : Math.min(1f, (now - shownAt) / (float) span);
                drawCover(c, b, Math.round(255 * f * (2f - f)), r);
                if (f < 1f && isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation(); else if (f >= 1f) { shownAt = 0; previousBitmap = null; }
            }
            if (ring) {
                float f = ui.shownProgress();
                ringBox.set(ui.dp(1.5f), ui.dp(1.5f), w - ui.dp(1.5f), h - ui.dp(1.5f));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2.5f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor((ui.ink & 0x00ffffff) | 0x30000000);
                c.drawOval(ringBox, paint);
                paint.setColor(ui.accent);
                c.drawArc(ringBox, -90, 360 * f, false, paint);
                paint.setStyle(Paint.Style.FILL);
                if (ui.playing && isShown() && getWindowVisibility() == VISIBLE && !ui.playerOpen) postInvalidateDelayed(1000);
            }
        }
    }

    /** The cover, blurred and drifting, under a dark wash. */
    final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Matrix m = new Matrix();
        private android.animation.ObjectAnimator drift;
        private Bitmap shown = ui.lastBlur, previous;
        private long fadeAt;
        private LinearGradient scrim;
        private float scrimH;
        private int scrimBg;
        /** GPU mode: the blurred cover is drawn through a grain shader, so its long dark fades don't band. */
        private final boolean gpu;
        private Shader grain;
        /**
         * The optional "Flowing" style: a colour field from the cover's four quarters, drifting by time played. It is
         * drawn into the cached layer about 30 times a second; the frames between reuse the texture.
         */
        private final boolean flow;
        private android.animation.ValueAnimator clock;
        private Shader field;
        private Bitmap colorsOf;
        private int[] colorsFrom, colorsTo;
        private final int[] colorsNow = new int[4];
        private long colorAt, phaseAt, drawnAt;
        private float phase;

        Backdrop() {
            super(ui.activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            int style = ui.prefs.getInt("backdrop_blur", 0);
            gpu = GpuFx.shaders(ui.prefs) && style != 2; // the streaks style is a sharp sliver, nothing to band
            flow = GpuFx.shaders(ui.prefs) && style == 3;
            // GPU mode draws through a grain shader; a hardware layer keeps that result, so the drift only moves a texture.
            if (gpu) setLayerType(LAYER_TYPE_HARDWARE, null);
            if (flow) {
                if (ui.motion()) {
                    clock = android.animation.ValueAnimator.ofFloat(0f, 1f);
                    clock.setDuration(1000);
                    clock.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    clock.addUpdateListener(a -> { if (SystemClock.uptimeMillis() - drawnAt >= 33) invalidate(); });
                    clock.start();
                    if (!ui.playing) clock.pause();
                }
            } else if (ui.motion()) {
                setScaleX(1.25f);
                setScaleY(1.25f);
                drift = android.animation.ObjectAnimator.ofPropertyValuesHolder(this,
                    android.animation.PropertyValuesHolder.ofFloat("translationX", -ui.dp(20), ui.dp(24)),
                    android.animation.PropertyValuesHolder.ofFloat("rotation", -3f, 3f));
                drift.setDuration(16000);
                drift.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                drift.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                drift.start();
                if (!ui.playing) drift.pause();
            }
        }

        /** The drift is mood lighting for music that is playing; frozen while paused so an idle player stops redrawing. */
        void drifting(boolean on) {
            if (clock != null) {
                if (on && clock.isPaused()) clock.resume();
                else if (!on && clock.isRunning()) clock.pause();
            }
            if (drift == null) return;
            if (on && drift.isPaused()) drift.resume();
            else if (!on && drift.isRunning()) drift.pause();
        }

        private Bitmap washBmpA, washBmpB;
        private BitmapShader washShaderA, washShaderB;

        private BitmapShader washShaderFor(Bitmap blur) {
            if (blur == washBmpA) return washShaderA;
            if (blur == washBmpB) return washShaderB;
            BitmapShader s = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            // Inside the grain shader the paint's filter flag doesn't reach it, and nearest sampling shows the small blur as tiles.
            if (android.os.Build.VERSION.SDK_INT >= 33) s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            washBmpB = washBmpA; washShaderB = washShaderA;
            washBmpA = blur; washShaderA = s;
            return s;
        }

        private void wash(Canvas c, Bitmap blur, float w, float h, int alpha) {
            Shader s = washShaderFor(blur);
            if (blur.getWidth() < 8) m.setScale(w / blur.getWidth(), h / blur.getHeight()); // the streaks sliver is meant to stretch
            else {
                // Filled without stretching (a square cover stretched to a tall screen smears into bars), centred.
                float k = Math.max(w / blur.getWidth(), h / blur.getHeight());
                m.setScale(k, k);
                m.postTranslate((w - blur.getWidth() * k) / 2, (h - blur.getHeight() * k) / 2);
            }
            s.setLocalMatrix(m);
            if (gpu && c.isHardwareAccelerated()) { // a bitmap snapshot can't run shaders
                if (grain == null) grain = new android.graphics.RuntimeShader(GpuFx.source("grain"));
                ((android.graphics.RuntimeShader) grain).setInputShader("content", s);
                s = grain;
            }
            paint.setShader(s);
            paint.setAlpha(alpha);
            c.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
            paint.setShader(null);
        }

        @Override protected void onDetachedFromWindow() { if (drift != null) drift.cancel(); if (clock != null) clock.cancel(); super.onDetachedFromWindow(); }

        private void drawField(Canvas c, float w, float h) {
            long now = SystemClock.uptimeMillis();
            drawnAt = now;
            Bitmap cover = ui.art(ui.image, 440);
            if (cover != null && cover != colorsOf) {
                colorsOf = cover;
                int[] next = GpuFx.quarters(cover);
                colorsFrom = colorsTo == null ? next : colorsNow.clone(); // a new cover's colours fade in over the old
                colorsTo = next;
                colorAt = colorsFrom != next && ui.motion() ? now : 0;
            }
            if (colorsTo == null) { c.drawColor(ui.bg); return; }
            float f = colorAt == 0 ? 1f : Math.min(1f, (now - colorAt) / 700f);
            for (int i = 0; i < 4; i++) colorsNow[i] = SpatchUi.blend(colorsFrom[i], colorsTo[i], f);
            if (f < 1f && isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
            // It moves by time played, so it holds still while paused and doesn't jump when music resumes.
            boolean moving = clock != null && clock.isRunning();
            if (moving && phaseAt != 0) phase += Math.min(100, now - phaseAt) / 1000f;
            phaseAt = moving ? now : 0;
            if (field == null) field = new android.graphics.RuntimeShader(GpuFx.source("field"));
            android.graphics.RuntimeShader rs = (android.graphics.RuntimeShader) field;
            rs.setFloatUniform("size", w, h);
            rs.setFloatUniform("time", phase);
            rs.setFloatUniform("shade", 1f);
            for (int i = 0; i < 4; i++) rs.setColorUniform("c" + i, colorsNow[i]);
            rs.setColorUniform("base", ui.bg);
            paint.setShader(rs);
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            drifting(visibility == VISIBLE && ui.playing);
            if (visibility == VISIBLE) invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (!ui.pref("backdrop", true)) { c.drawColor(ui.bg); return; }
            if (flow && c.isHardwareAccelerated()) { drawField(c, w, h); return; } // a bitmap snapshot can't run shaders
            ui.art(ui.image, 440);
            int chosen = ui.prefs.getInt("backdrop_blur", 0);
            int style = chosen == 3 ? 0 : Math.max(0, Math.min(BLUR_SIZES.length - 1, chosen)); // Flowing falls back to Soft
            Bitmap blur = SpatchUi.IMAGES.get(ui.image + ":440/blur/" + style);
            c.drawColor(ui.bg);
            long now = SystemClock.uptimeMillis();
            // A new cover's blur fades in over the old one.
            if (blur != null && blur != shown) {
                previous = ui.motion() ? shown : null;
                shown = blur;
                fadeAt = now;
            }
            ui.lastBlur = shown;
            float f = previous == null ? 1f : Math.min(1f, (now - fadeAt) / 700f);
            if (f >= 1f) previous = null;
            paint.setColorFilter(SpatchUi.VIVID);
            if (previous != null) wash(c, previous, w, h, 255);
            if (shown != null) wash(c, shown, w, h, Math.round(255 * f));
            paint.setColorFilter(null);
            if (f < 1f && isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
            if (scrim == null || scrimH != h || scrimBg != ui.bg) {
                scrimH = h; scrimBg = ui.bg;
                scrim = new LinearGradient(0, 0, 0, h, new int[]{(ui.bg & 0x00ffffff) | 0x66000000, (ui.bg & 0x00ffffff) | 0x99000000, ui.bg}, new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP);
            }
            paint.setShader(scrim);
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }

    /** Particles coast and dim on pause; playback and visibility changes restart their frames. */
    final class Motes extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] x = new float[64], y = new float[64], sz = new float[64], sp = new float[64], ph = new float[64];
        private long last;
        private float speed, phase;
        private int amountIdx = 1, shapeIdx;
        private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, k) -> {
            if ("motes_amount".equals(k)) amountIdx = Math.max(0, Math.min(MOTE_COUNTS.length - 1, p.getInt("motes_amount", 1)));
            else if ("motes_shape".equals(k)) shapeIdx = Math.max(0, Math.min(3, p.getInt("motes_shape", 0)));
        };

        Motes() {
            super(ui.activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < x.length; i++) { x[i] = r.nextFloat(); y[i] = r.nextFloat(); sz[i] = 1.2f + r.nextFloat() * r.nextFloat() * 4.2f; sp[i] = .012f + r.nextFloat() * .03f; ph[i] = r.nextFloat() * 6.283f; }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            amountIdx = Math.max(0, Math.min(MOTE_COUNTS.length - 1, ui.prefs.getInt("motes_amount", 1)));
            shapeIdx = Math.max(0, Math.min(3, ui.prefs.getInt("motes_shape", 0)));
            ui.prefs.registerOnSharedPreferenceChangeListener(prefListener);
            last = 0;
            invalidate();
        }
        @Override protected void onDetachedFromWindow() {
            ui.prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            super.onDetachedFromWindow();
        }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) { last = 0; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            boolean animate = ui.motion(), run = ui.playing && animate;
            float dt = last == 0 ? 0 : Math.min(.05f, (now - last) / 1000f);
            last = now;
            float target = run ? 1f : 0f;
            speed = !animate || Math.abs(target - speed) < .001f ? target
                : speed + (target - speed) * (1f - (float) Math.exp(-dt / .18f));
            float travel = dt * speed;
            phase += travel;
            if (run && ui.pref("audio", true) && Audio.level > 0) travel *= 1f + 3f * Audio.level + 3f * Audio.kick;
            float w = getWidth(), h = getHeight();
            int count = MOTE_COUNTS[amountIdx];
            int shape = shapeIdx;
            for (int i = 0; i < count; i++) {
                y[i] -= sp[i] * travel;
                if (y[i] < -.03f) { y[i] = 1.03f; x[i] = (x[i] + .618f) % 1f; }
                float edge = Math.min(1f, Math.min(y[i], 1f - y[i]) * 6f);
                paint.setColor(i % 2 == 0 ? ui.accent : ui.ink);
                paint.setAlpha(Math.max(0, Math.round(110 * (.55f + .45f * speed)
                    * (.5f + .5f * (float) Math.sin(phase * 1.3f + ph[i] * 3f)) * Math.max(0f, edge))));
                float px = x[i] * w + (float) Math.sin(phase * .6f + ph[i]) * ui.dp(10), py = y[i] * h;
                int kind = MOTE_SHAPES[shape == 3 ? i % MOTE_SHAPES.length : shape];
                if (kind == Confetti.DOT) c.drawCircle(px, py, ui.dp(sz[i]), paint);
                else {
                    c.save();
                    c.translate(px, py);
                    float size = ui.dp(Math.max(2.5f, sz[i] * 1.3f));
                    c.scale(size, size);
                    c.rotate((phase * 12 + ph[i] * 30) % 360);
                    c.drawPath(Confetti.shape(kind), paint);
                    c.restore();
                }
            }
            if ((run || speed > 0) && isShown() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(16);
        }
    }

    /** The art in a circle, turning slowly, inside a sweep-coloured ring with bars radiating from it. */
    final class Disc extends View {
        String url = "";
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        /** GPU mode: the bars and their glow in one shader pass; the 36 levels ride in as nine float4 uniforms. */
        private Shader ringShader;
        private final Path clip = new Path();
        private final Matrix m = new Matrix();
        private final Path contour = new Path();
        private float angle;
        private SweepGradient sweep;
        private int sweepA, sweepB;
        private float sweepCx, sweepCy;
        private RadialGradient glowA, glowB;
        private int glowAccent, glowAccent2;
        private float glowHalf, glowCx, glowCy;
        private Bitmap shown = ui.lastCover, previous;
        private String shownUrl = ui.lastCoverUrl;
        private long fadeAt, lastFrame;
        private float bassBreath;
        private final float[] level = new float[36], ringTargets = new float[36];

        Disc() { super(ui.activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        private void cover(Canvas c, Bitmap b, float cx, float cy, float radius, int alpha) {
            float k = Math.max(radius * 2 / b.getWidth(), radius * 2 / b.getHeight());
            m.setScale(k, k);
            m.postTranslate(cx - b.getWidth() * k / 2, cy - b.getHeight() * k / 2);
            m.postRotate(angle, cx, cy);
            paint.setAlpha(alpha);
            c.drawBitmap(b, m, paint);
            paint.setAlpha(255);
        }

        /** Two soft offset pools of the palette's colours behind the cover; both fade out smoothly before the view's edge. */
        private void glow(Canvas c, float cx, float cy, float half) {
            if (glowA == null || glowAccent != ui.accent || glowAccent2 != ui.accent2 || glowHalf != half || glowCx != cx || glowCy != cy) {
                glowAccent = ui.accent; glowAccent2 = ui.accent2; glowHalf = half; glowCx = cx; glowCy = cy;
                float offX = half * .10f, offY = half * .06f;
                float r = Math.max(1f, half - (float) Math.hypot(offX, offY));
                float[] stops = new float[]{0f, .32f, .58f, .80f, 1f};
                glowA = new RadialGradient(cx - offX, cy + offY, r, new int[]{withAlpha(ui.accent, 68), withAlpha(ui.accent, 40), withAlpha(ui.accent, 16), withAlpha(ui.accent, 4), 0}, stops, Shader.TileMode.CLAMP);
                glowB = new RadialGradient(cx + offX, cy - offY, r, new int[]{withAlpha(ui.accent2, 52), withAlpha(ui.accent2, 28), withAlpha(ui.accent2, 11), withAlpha(ui.accent2, 3), 0}, stops, Shader.TileMode.CLAMP);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(glowA);
            c.drawCircle(cx, cy, half, paint);
            paint.setShader(glowB);
            c.drawCircle(cx, cy, half, paint);
            paint.setShader(null);
        }

        private int withAlpha(int colour, int alpha) { return (colour & 0xffffff) | alpha << 24; }

        private final String[] LEVEL_NAMES = {"l0", "l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8"};

        private void ringBars(Canvas c, float cx, float cy, float start, float bar, float stroke) {
            if (ringShader == null) ringShader = new android.graphics.RuntimeShader(GpuFx.source("ring"));
            android.graphics.RuntimeShader rs = (android.graphics.RuntimeShader) ringShader;
            for (int v = 0; v < 9; v++) rs.setFloatUniform(LEVEL_NAMES[v], level[v * 4], level[v * 4 + 1], level[v * 4 + 2], level[v * 4 + 3]);
            float glow = ui.dp(5);
            rs.setFloatUniform("center", cx, cy);
            rs.setFloatUniform("start", start);
            rs.setFloatUniform("minLen", ui.dp(3));
            rs.setFloatUniform("span", bar - ui.dp(6));
            rs.setFloatUniform("stroke", stroke);
            rs.setFloatUniform("glow", glow);
            rs.setColorUniform("a", ui.accent);
            rs.setColorUniform("b", ui.accent2);
            Paint.Style was = paint.getStyle();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(rs);
            float outer = start + bar + glow * 4;
            c.drawRect(cx - outer, cy - outer, cx + outer, cy + outer, paint);
            paint.setStyle(was);
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) { lastFrame = 0; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            float s = Math.min(w, h);
            int bars = 72;
            float ringStroke = Math.max(ui.dp(2f), Math.min(ui.dp(3.5f), s * 0.014f));
            float bar = Math.min(ui.dp(30), Math.max(ui.dp(12), s * 0.12f));
            float stroke = Math.min(ui.dp(6), (float) (Math.PI * 2 * Math.max(ui.dp(28), s / 2f - bar - ui.dp(6)) / bars) * .52f);
            float ring = Math.max(ui.dp(28), s / 2f - bar - ui.dp(4) - stroke * .5f);
            float artR = Math.max(ui.dp(24), ring - ringStroke * .5f - ui.dp(3));
            boolean animate = ui.motion();
            boolean run = ui.playing && animate;
            boolean pulse = animate && ui.pref("cover_pulse", true);
            boolean audio = ui.pref("audio", true) && Audio.ready(ui.activity);
            boolean live = run && audio && Audio.BANDS[0] + Audio.BANDS[8] > 0;
            float targetBass = live && pulse ? (level[0] + level[1] + level[2] + level[3]) * .25f : 0f;
            if (!pulse) bassBreath = 0f;
            else {
                bassBreath += (targetBass - bassBreath) * .18f;
                if (!live && bassBreath < .002f) bassBreath = 0f;
            }
            float pulseR = Math.min(ring - ringStroke * .5f - ui.dp(1.5f), artR * (1f + bassBreath * .045f));
            long now = SystemClock.uptimeMillis();
            if (url.isEmpty()) { shown = previous = null; shownUrl = ""; }
            Bitmap b = ui.art(url, 440);
            if (b == null) b = ui.art(url, 180);
            if (b != null && !url.isEmpty() && url.equals(ui.image) && (ui.lastCover == null || !url.equals(ui.lastCoverUrl) || b.getWidth() >= ui.lastCover.getWidth())) {
                ui.lastCover = b;
                ui.lastCoverUrl = url;
            }
            // A new cover fades in over the old one instead of cutting.
            if (b != null && b != shown) {
                if (!url.equals(shownUrl)) {
                    previous = ui.motion() ? shown : null;
                    shown = b;
                    shownUrl = url;
                    fadeAt = previous != null ? now : 0;
                } else {
                    shown = b;
                }
            }
            float fade = previous == null ? 1f : Math.min(1f, (now - fadeAt) / 450f);
            if (fade >= 1f) previous = null;
            if (ui.pref("cover_glow", true)) glow(c, cx, cy, s / 2f);
            c.save();
            clip.rewind();
            clip.addCircle(cx, cy, pulseR, Path.Direction.CW);
            c.clipPath(clip);
            float dt = lastFrame == 0 ? .016f : Math.min(.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            if (shown != null) {
                if (run && ui.pref("spin", true)) angle = (angle + 7.2f * dt) % 360f;
                paint.setShader(null);
                if (previous != null) cover(c, previous, cx, cy, pulseR, 255);
                cover(c, shown, cx, cy, pulseR, Math.round(255 * fade));
            } else {
                paint.setColor(ui.surfHi);
                c.drawCircle(cx, cy, pulseR, paint);
            }
            c.restore();
            if (fade < 1f && isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
            if (sweep == null || sweepA != ui.accent || sweepB != ui.accent2 || sweepCx != cx || sweepCy != cy) {
                sweepA = ui.accent;
                sweepB = ui.accent2;
                sweepCx = cx;
                sweepCy = cy;
                sweep = new SweepGradient(cx, cy, new int[]{ui.accent, ui.accent2, ui.accent, ui.accent2, ui.accent}, null);
            }
            // Visualizer style: 0 bars, 1 ring only, 2 off, 3 smooth radial contour.
            int style = ui.prefs.getInt("viz", 0);
            paint.setShader(sweep);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ringStroke);
            if (style != 2) c.drawCircle(cx, cy, ring, paint);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(stroke);
            float t = now / 1000f;
            int half = bars / 2;
            // The bands spread over each side's bars, bass-weighted (Rust: spectrum::bass_expanded).
            if (live) SpatchUi.nativeRing(half, ringTargets);
            boolean settling = bassBreath > 0f;
            for (int i = 0; i < half; i++) {
                float v;
                if (live) {
                    v = ringTargets[i];
                } else if (run) {
                    // No audio to read: a pulse that still reads as music.
                    float swell = Math.max(0f, (float) Math.sin(t * 4.4f)) * (1f - i / (float) half) * .3f;
                    v = .25f + .18f * (float) Math.sin(t * 2.6f + i * .5f) + .12f * (float) Math.sin(t * 1.5f - i * .27f) + swell;
                } else v = 0f;
                // Ring response: base .02 + 1.12 x level^1.1, rising in 16 ms and falling in 45 ms.
                float shown = Math.max(0f, Math.min(1f, .02f + 1.12f * (float) Math.pow(Math.max(0f, v), 1.1)));
                float tau = !run ? .16f : shown > level[i] ? .016f : .045f;
                if (!animate || Math.abs(shown - level[i]) < .001f) level[i] = shown;
                else {
                    level[i] += (shown - level[i]) * (1f - (float) Math.exp(-dt / tau));
                    settling = true;
                }
            }
            if (style == 0 && GpuFx.shaders(ui.prefs) && c.isHardwareAccelerated()) {
                ringBars(c, cx, cy, ring + ui.dp(10), bar, stroke);
            } else if (style == 0) {
                for (int k = 0; k < bars; k++) {
                    int bi = k < half ? k : bars - 1 - k;
                    double a = -Math.PI / 2 + (k + .5) / bars * Math.PI * 2;
                    float len = ui.dp(3) + (bar - ui.dp(6)) * Math.min(1f, level[bi]), start = ring + ui.dp(10);
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    c.drawLine(cx + cos * start, cy + sin * start, cx + cos * (start + len), cy + sin * (start + len), paint);
                }
            } else if (style == 3) {
                float baseR = ring + ui.dp(8), span = (bar - ui.dp(6)) * .92f;
                int lastIdx = bars - 1;
                double aLast = -Math.PI / 2 + (lastIdx + .5) / bars * Math.PI * 2;
                float rLast = baseR + span * Math.min(1f, level[lastIdx < half ? lastIdx : bars - 1 - lastIdx]);
                float xPrev = cx + (float) Math.cos(aLast) * rLast, yPrev = cy + (float) Math.sin(aLast) * rLast;
                double a0 = -Math.PI / 2 + .5 / bars * Math.PI * 2;
                float r0 = baseR + span * Math.min(1f, level[0]);
                float xc = cx + (float) Math.cos(a0) * r0, yc = cy + (float) Math.sin(a0) * r0;
                contour.rewind();
                contour.moveTo((xPrev + xc) * .5f, (yPrev + yc) * .5f);
                for (int k = 0; k < bars; k++) {
                    int nextK = (k + 1) % bars;
                    int biNext = nextK < half ? nextK : bars - 1 - nextK;
                    double aNext = -Math.PI / 2 + (nextK + .5) / bars * Math.PI * 2;
                    float rNext = baseR + span * Math.min(1f, level[biNext]);
                    float xn = cx + (float) Math.cos(aNext) * rNext, yn = cy + (float) Math.sin(aNext) * rNext;
                    contour.quadTo(xc, yc, (xc + xn) * .5f, (yc + yn) * .5f);
                    xc = xn;
                    yc = yn;
                }
                contour.close();
                c.save();
                clip.rewind();
                clip.addCircle(cx, cy, ring, Path.Direction.CW);
                if (android.os.Build.VERSION.SDK_INT >= 26) c.clipOutPath(clip);
                paint.setStyle(Paint.Style.FILL);
                paint.setAlpha(38);
                c.drawPath(contour, paint);
                c.restore();
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setAlpha(72);
                paint.setStrokeWidth(ui.dp(6));
                c.drawPath(contour, paint);
                paint.setAlpha(255);
                paint.setStrokeWidth(ui.dp(2.5f));
                c.drawPath(contour, paint);
            }
            paint.setAlpha(255);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            boolean reactiveStyle = style == 0 || style == 3;
            if (run && audio && reactiveStyle) Audio.want();
            if ((run || (settling && reactiveStyle)) && isShown() && getWindowVisibility() == VISIBLE) {
                if (reactiveStyle) postInvalidateOnAnimation();
                else postInvalidateDelayed(16);
            }
        }
    }

}
