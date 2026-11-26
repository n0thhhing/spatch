package spatch;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;

/** Custom controls: play button, spring, switch, volume bar, equalizer bars, loader, wave and download button. */
final class Controls {
    private final SpatchUi ui;
    Controls(SpatchUi ui) { this.ui = ui; }

    interface UriOf { String get(); }

    /** The page's download button: a ring with an arrow, a filling ring while it downloads, a check once it has. */
    final class DownloadButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path glyph = new Path();
        private final RectF oval = new RectF();
        private final UriOf target;
        private final String name;
        private int descState = -1;

        DownloadButton(UriOf target, String name) {
            super(ui.activity);
            this.target = target;
            this.name = name;
            ui.tappable(this, "Download", () -> ui.downloadsScreen.downloadTapped(target.get(), name));
        }

        @Override protected void onDraw(Canvas c) {
            int state = ui.downloadsScreen.downloadState(target.get());
            int[] d = ui.downloadsScreen.downloads.get(target.get());
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, size = Math.min(w, h);
            boolean busy = state == Downloads.DL_BUSY || state == Downloads.DL_REQUESTED || state == Downloads.DL_WAITING;
            int descKind = state == Downloads.DL_DONE ? 2 : busy ? 1 : 0;
            if (descKind != descState) {
                descState = descKind;
                setContentDescription(descKind == 2 ? "Downloaded. Tap to remove" : descKind == 1 ? "Downloading. Tap to cancel" : "Download");
            }
            paint.setStyle(Paint.Style.FILL);
            if (state == Downloads.DL_DONE) {
                paint.setColor(ui.accent);
                c.drawCircle(cx, cy, size * .3f, paint);
                c.save();
                c.translate(cx - size * .15f, cy - size * .15f);
                SpatchUi.drawGlyph(c, paint, glyph, "check", ui.onAccent, size * .3f);
                c.restore();
            } else if (busy) {
                float r = size * .3f;
                oval.set(cx - r, cy - r, cx + r, cy + r);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2.5f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor((ui.ink & 0x00ffffff) | 0x33000000);
                c.drawOval(oval, paint);
                paint.setColor(ui.accent);
                int percent = d == null ? 0 : d[1];
                boolean known = state == Downloads.DL_BUSY && percent > 0;
                float start = known ? -90 : (SystemClock.uptimeMillis() % 1100) / 1100f * 360f, sweep = known ? 360f * percent / 100f : 95f;
                c.drawArc(oval, start, sweep, false, paint);
                paint.setStyle(Paint.Style.FILL);
                float half = size * .075f;
                c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, ui.dp(1.5f), ui.dp(1.5f), paint);
                if (!known && ui.motion() && isShown() && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
            } else {
                c.save();
                c.translate(cx - size * .21f, cy - size * .21f);
                SpatchUi.drawGlyph(c, paint, glyph, "download", state == Downloads.DL_ERROR ? ui.accent2 : ui.ink, size * .42f);
                c.restore();
            }
        }
    }

    final class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        boolean on;
        private float t;
        Switch(boolean on) { super(ui.activity); this.on = on; t = on ? 1 : 0; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        private android.animation.ValueAnimator anim;
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
        }
        void set(boolean v) {
            if (v == on) return;
            on = v;
            if (anim != null) anim.cancel(); // a quick double toggle must not leave two animators fighting over t
            if (!ui.motion()) { t = v ? 1 : 0; invalidate(); return; }
            android.animation.ValueAnimator a = anim = android.animation.ValueAnimator.ofFloat(t, v ? 1 : 0);
            a.setDuration(260);
            a.setInterpolator(new android.view.animation.OvershootInterpolator(1.3f));
            a.addUpdateListener(f -> { t = (float) f.getAnimatedValue(); invalidate(); });
            a.start();
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2, k = Math.max(0, Math.min(1, t));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(SpatchUi.blend(ui.surfHi, ui.accent, k));
            c.drawRoundRect(0, 0, w, h, r, r, paint);
            paint.setColor(SpatchUi.blend(ui.inkDim, ui.onAccent, k));
            float cx = r + (w - 2 * r) * t;
            c.drawCircle(cx, r, ui.dp(8) + ui.dp(4) * t, paint);
            // Material 3's check inside the thumb, fading in as it slides on.
            if (k > .05f) {
                float size = ui.dp(12);
                c.save();
                c.translate(cx - size / 2, r - size / 2);
                SpatchUi.drawGlyph(c, paint, check, "check", (ui.accent & 0x00ffffff) | (Math.round(255 * k) << 24), size);
                c.restore();
            }
        }
    }

    /** The volume of the device that's playing: a track you drag, with a speaker and the percentage. */
    final class VolumeBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path glyph = new Path();
        private float value;
        private boolean dragging;
        private long sentAt;
        private boolean failed;

        VolumeBar(int level) {
            super(ui.activity);
            value = Math.max(0, Math.min(1, level / 65535f));
            setContentDescription("Volume");
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), ui.dp(52)); }

        private float left() { return ui.dp(40); }
        private float right() { return getWidth() - ui.dp(52); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), cy = getHeight() / 2f, x0 = left(), x1 = right(), x = x0 + (x1 - x0) * value;
            c.save();
            c.translate(ui.dp(4), cy - ui.dp(11));
            SpatchUi.drawGlyph(c, paint, glyph, "volume", ui.ink, ui.dp(22));
            c.restore();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((ui.ink & 0x00ffffff) | 0x30000000);
            c.drawRoundRect(x0, cy - ui.dp(3), x1, cy + ui.dp(3), ui.dp(3), ui.dp(3), paint);
            paint.setColor(ui.accent);
            c.drawRoundRect(x0, cy - ui.dp(3), Math.max(x0 + ui.dp(3), x), cy + ui.dp(3), ui.dp(3), ui.dp(3), paint);
            c.drawCircle(x, cy, dragging ? ui.dp(13) : ui.dp(10), paint);
            paint.setColor(ui.inkDim);
            paint.setTextSize(ui.dp(13));
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Math.round(value * 100) + "%", w - ui.dp(2), cy + ui.dp(5), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) { dragging = true; getParent().requestDisallowInterceptTouchEvent(true); }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                value = Math.max(0, Math.min(1, (e.getX() - left()) / Math.max(1, right() - left())));
                long now = SystemClock.uptimeMillis();
                if (action == MotionEvent.ACTION_UP || now - sentAt > 120) { sentAt = now; send(); }
                if (action == MotionEvent.ACTION_UP) dragging = false;
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) { dragging = false; invalidate(); return true; }
            return false;
        }

        /** Sent in order, a little apart while dragging; the last position always goes. */
        private void send() {
            final int level = Math.round(value * 65535);
            ui.submit(SpatchUi.COMMANDS, () -> {
                String error = SpatchUi.nativeSetVolume(level);
                if (error != null && !error.isEmpty() && !failed) {
                    failed = true;
                    post(() -> ui.toast("Couldn’t change the volume: " + error));
                }
            });
        }
    }

    /** A loading indicator: a tonal shape that turns and morphs between four, six and eight lobes, over `label`. */
    View loader(String label) {
        LinearLayout box = ui.column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, ui.dp(28), 0, ui.dp(20));
        box.setContentDescription(label);
        Blob blob = new Blob();
        blob.spinning = true;
        box.addView(blob, ui.lp(ui.dp(44), ui.dp(44)));
        TextView words = ui.text(label + "…", 14, () -> ui.inkDim, false);
        words.setGravity(Gravity.CENTER);
        words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        words.setPadding(0, ui.dp(12), 0, 0);
        box.addView(words, ui.lp(-2, -2));
        return box;
    }

    /**
     * The Material 3 Expressive lobed loader: turns and morphs smoothly through four, six and eight lobes.
     */
    final class Blob extends View {
        boolean spinning;
        float progress;
        boolean accentFill;
        private final float[][] shapes = {SpatchUi.nativeGeometry("badge4"), SpatchUi.nativeGeometry("badge6"), SpatchUi.nativeGeometry("badge8")};
        private final long born = SystemClock.uptimeMillis();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Blob() { super(ui.activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.44f;
            if (r <= 0) return;
            float t = spinning && ui.motion() ? (SystemClock.uptimeMillis() - born) / 680f : progress * 2f;
            int k = ((int) t) % 3;
            if (k < 0) k += 3;
            float f = t - (int) t;
            f = f * f * (3 - 2 * f);
            float[] a = shapes[k], b = shapes[(k + 1) % 3];
            double turn = Math.toRadians(spinning ? t * 72f : progress * 220f);
            float cos = (float) Math.cos(turn), sin = (float) Math.sin(turn);
            path.rewind();
            for (int i = 0; i + 1 < a.length && i + 1 < b.length; i += 2) {
                float px = a[i] + (b[i] - a[i]) * f, py = a[i + 1] + (b[i + 1] - a[i + 1]) * f;
                float x = px * cos - py * sin, y = px * sin + py * cos;
                if (i == 0) path.moveTo(x * r, y * r); else path.lineTo(x * r, y * r);
            }
            path.close();
            c.save();
            c.translate(w / 2f, h / 2f);
            paint.setColor(accentFill ? ui.accent : SpatchUi.blend(ui.surfHi, ui.accent, .5f));
            c.drawPath(path, paint);
            c.restore();
            if (spinning && ui.motion() && isShown() && getWindowVisibility() == VISIBLE && !ui.playerOpen) postInvalidateOnAnimation();
        }
    }

    /**
     * Pull to refresh around a scrolling view: a contained tonal circle holding the morphing Expressive loader.
     */
    final class PullRefresh extends android.widget.FrameLayout {
        private final java.util.function.Supplier<View> scrolling;
        private final Runnable refresh;
        private final java.util.function.BooleanSupplier busy;
        private final android.widget.FrameLayout badge = new android.widget.FrameLayout(ui.activity);
        private final Blob blob = new Blob();
        private final int size = ui.dp(44), hideY = -ui.dp(56), slop = android.view.ViewConfiguration.get(ui.activity).getScaledTouchSlop();
        private float downY;
        private boolean pulling, refreshing;

        PullRefresh(java.util.function.Supplier<View> scrolling, Runnable refresh, java.util.function.BooleanSupplier busy) {
            super(ui.activity);
            this.scrolling = scrolling;
            this.refresh = refresh;
            this.busy = busy;
            blob.accentFill = true;
            android.graphics.drawable.GradientDrawable badgeBg = ui.round(ui.surfHi, 22);
            badge.setBackground(badgeBg);
            ui.colored(badge, () -> {
                badgeBg.setColor(ui.surfHi);
                badgeBg.setStroke(Math.max(1, ui.dp(1f)), (ui.ink & 0x00ffffff) | 0x1a000000);
                blob.invalidate();
            });
            badge.setElevation(ui.dp(6));
            badge.addView(blob, new android.widget.FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER));
            badge.setAlpha(0f);
            badge.setTranslationY(hideY);
            setClipChildren(true);
            addView(badge, new android.widget.FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }

        // The page swaps its content in and out of this view; the loader stays, above whatever is shown.
        @Override public void removeAllViews() {
            super.removeAllViews();
            addView(badge, new android.widget.FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }

        @Override public void onViewAdded(View child) {
            super.onViewAdded(child);
            if (child != badge && badge.getParent() == this) badge.bringToFront();
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            View target = scrolling.get();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downY = e.getY(); pulling = false; }
            else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !pulling && !refreshing
                && e.getY() - downY > slop && (target == null || !target.canScrollVertically(-1))) pulling = true;
            return pulling;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!pulling) return super.onTouchEvent(e);
            float pull = Math.min(ui.dp(140), Math.max(0f, e.getY() - downY - slop) * .5f);
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                float p = Math.min(1f, pull / ui.dp(90));
                blob.progress = p;
                blob.invalidate();
                float s = 0.65f + 0.35f * p;
                badge.setScaleX(s);
                badge.setScaleY(s);
                badge.setTranslationY(hideY + (ui.dp(14) - hideY) * p + Math.max(0f, pull - ui.dp(90)) * 0.25f);
                badge.setAlpha(Math.min(1f, pull / ui.dp(32)));
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pulling = false;
                if (pull > ui.dp(68) && action == MotionEvent.ACTION_UP) start();
                else retract();
            }
            return true;
        }

        private void start() {
            refreshing = true;
            ui.tick(this);
            blob.spinning = true;
            badge.animate().cancel();
            badge.animate().translationY(ui.dp(14)).scaleX(1f).scaleY(1f).alpha(1f).setDuration(240)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
            blob.invalidate();
            refresh.run();
            postDelayed(new Runnable() {
                @Override public void run() {
                    if (busy.getAsBoolean() && isAttachedToWindow()) { postDelayed(this, 120); return; }
                    refreshing = false;
                    badge.animate().scaleX(0.5f).scaleY(0.5f).alpha(0f).setDuration(200).withEndAction(() -> {
                        blob.spinning = false;
                        blob.progress = 0f;
                        badge.setScaleX(1f);
                        badge.setScaleY(1f);
                        badge.setTranslationY(hideY);
                    }).start();
                }
            }, 300);
        }

        private void retract() {
            badge.animate().cancel();
            badge.animate().translationY(hideY).scaleX(0.65f).scaleY(0.65f).alpha(0f).setDuration(200)
                .setInterpolator(SpatchUi.EMPHASIZED).withEndAction(() -> { blob.progress = 0f; }).start();
        }
    }

    /** The scallop play button: five lobes at rest, nine while playing, turning slowly. */
    final class PlayButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path scallop = new Path(), glyph = new Path();
        private final float[] five = SpatchUi.nativeGeometry("button5"), nine = SpatchUi.nativeGeometry("button9");
        private float shape, angle, pop = 1f;
        private long spunAt;
        private boolean on, started;
        private android.animation.ValueAnimator popping;
        /** The five-to-nine morph rides a loose spring, so the scallops overshoot and wobble into place. */
        private final Spring morph = new Spring(0f, 14f, .35f, v -> { shape = v; invalidate(); });
        /** Held down it squishes; let go and it springs back past full size before settling. */
        private final Spring press = new Spring(1f, 22f, .28f, v -> { setScaleX(v); setScaleY(v); });

        PlayButton() {
            super(ui.activity);
            ui.playButtons.add(this);
            ui.tappable(this, "Play or pause", 1f, ui.playerScreen::togglePlay);
            setOnTouchListener((view, e) -> {
                int a = e.getActionMasked();
                if (!ui.motion()) return false;
                if (a == MotionEvent.ACTION_DOWN) press.to(.88f);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) press.to(1f);
                return false;
            });
        }

        @Override protected void onDetachedFromWindow() {
            morph.cancel();
            press.snap(1f);
            if (popping != null) popping.cancel();
            super.onDetachedFromWindow();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE && (on || ui.buffering) && ui.motion()) invalidate();
        }

        @Override public boolean performClick() {
            Confetti.clicked(this, ui.prefs, ui.accent, ui.accent2, on, ui.motion());
            return super.performClick();
        }

        void set(boolean playingNow) {
            if (started && playingNow == on) return;
            started = true;
            on = playingNow;
            if (!ui.motion() || !isAttachedToWindow()) { morph.snap(on ? 1 : 0); return; }
            morph.to(on ? 1 : 0);
            // The new glyph pops in from small with a strong overshoot.
            if (popping != null) popping.cancel();
            popping = android.animation.ValueAnimator.ofFloat(.55f, 1f);
            popping.setDuration(480);
            popping.setInterpolator(new android.view.animation.OvershootInterpolator(2.6f));
            popping.addUpdateListener(a -> { pop = (float) a.getAnimatedValue(); invalidate(); });
            popping.start();
        }

        @Override protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f, outline = r * .95f; // room for the morph's overshoot
            c.save();
            c.translate(getWidth() / 2f, getHeight() / 2f);
            scallop.rewind();
            for (int i = 0; i < five.length; i += 2) {
                float x = (five[i] + (nine[i] - five[i]) * shape) * outline, y = (five[i + 1] + (nine[i + 1] - five[i + 1]) * shape) * outline;
                if (i == 0) scallop.moveTo(x, y); else scallop.lineTo(x, y);
            }
            scallop.close();
            // Buffering: the scallop spins faster and a bright arc chases round inside it until the audio arrives.
            // Turns by time, not by frame, so a 120 Hz screen doesn't spin it twice as fast.
            long now = SystemClock.uptimeMillis();
            float dt = spunAt == 0 ? 0f : Math.min(50, now - spunAt) / 1000f;
            spunAt = now;
            if ((on || ui.buffering) && ui.motion()) { angle = (angle + dt * (ui.buffering ? 130f : 21f)) % 360f; c.rotate(angle); }
            else spunAt = 0;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ui.accent);
            c.drawPath(scallop, paint);
            if (ui.buffering) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(r * .09f);
                paint.setColor((ui.onAccent & 0x00ffffff) | 0x99000000);
                float ring = r * .72f;
                c.drawArc(-ring, -ring, ring, ring, angle * 2.5f, 100, false, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            c.restore();
            c.save();
            c.scale(pop, pop, getWidth() / 2f, getHeight() / 2f);
            c.translate(getWidth() / 2f - r * .5f, getHeight() / 2f - r * .5f);
            SpatchUi.drawGlyph(c, paint, glyph, on ? "pause" : "play", ui.onAccent, r);
            c.restore();
            if ((on || ui.buffering) && ui.motion() && isShown() && getWindowVisibility() == VISIBLE && (this == ui.dockUi.pPlay || !ui.playerOpen)) postInvalidateOnAnimation();
        }
    }

    /**
     * A damped spring toward a target, stepped every frame by the real time elapsed (so it moves the same at 60 or
     * 120 Hz); `omega` is its stiffness in radians a second, `zeta` its damping, below 1 for an overshoot.
     */
    static final class Spring implements android.view.Choreographer.FrameCallback {
        private float omega, zeta;
        private final java.util.function.Consumer<Float> step;
        private float value, velocity, target;
        private long last;
        private boolean running;

        Spring(float start, float omega, float zeta, java.util.function.Consumer<Float> step) {
            this.value = this.target = start; this.omega = omega; this.zeta = zeta; this.step = step;
        }

        void to(float next) {
            target = next;
            if (running) return;
            running = true;
            last = 0;
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }

        /** Changes its stiffness and damping, for a spring whose feel depends on which way it's going. */
        void tune(float omega, float zeta) { this.omega = omega; this.zeta = zeta; }

        void snap(float next) {
            cancel();
            value = target = next;
            velocity = 0;
            step.accept(value);
        }

        void cancel() {
            if (running) android.view.Choreographer.getInstance().removeFrameCallback(this);
            running = false;
        }

        float advance(float seconds) {
            for (int i = 0; i < 4; i++) { // small sub-steps keep a stiff spring stable
                float h = seconds / 4, pull = -omega * omega * (value - target) - 2 * zeta * omega * velocity;
                velocity += pull * h;
                value += velocity * h;
            }
            if (Math.abs(value - target) < .001f && Math.abs(velocity) < .01f) { value = target; velocity = 0; running = false; }
            return value;
        }

        @Override public void doFrame(long nanos) {
            float dt = last == 0 ? 1 / 60f : Math.min(.05f, (nanos - last) / 1e9f);
            last = nanos;
            step.accept(advance(dt));
            if (running) android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** Three bouncing bars: this one is playing. */
    final Set<Bars> attachedBars = new HashSet<>();

    final class Bars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bars() { super(ui.activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attachedBars.add(this); invalidate(); }
        @Override protected void onDetachedFromWindow() { attachedBars.remove(this); super.onDetachedFromWindow(); }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            boolean run = ui.playing && ui.motion();
            float t = run ? SystemClock.uptimeMillis() / 1000f : 0, h = getHeight(), bw = getWidth() / 5f;
            paint.setColor(ui.accent);
            for (int i = 0; i < 3; i++) {
                float k = run ? .35f + .65f * Math.abs((float) Math.sin(t * (3.1f + i * 1.7f) + i * 1.3f)) : .3f + .2f * i;
                c.drawRoundRect(bw * i * 2, h * (1 - k), bw * i * 2 + bw, h, bw / 2, bw / 2, paint);
            }
            if (run && isShown() && getWindowVisibility() == VISIBLE && !ui.playerOpen) postInvalidateOnAnimation();
        }
    }

    /** Progress eases across position changes and flattens while paused or seeking. */
    final class Wave extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF bubble = new RectF();
        float preview = -1, amp = -1, grow = 0;
        private int lastTenth = -1;
        private String scrubUri = "";
        Wave() { super(ui.activity); setContentDescription("Playback position"); }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) invalidate();
        }

        void cancelScrub() {
            preview = -1;
            lastTenth = -1;
            scrubUri = "";
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }

        @Override protected void onDraw(Canvas c) {
            long el = ui.playhead();
            float f = ui.shownProgress();
            float x0 = ui.dp(6), x1 = getWidth() - ui.dp(6), y = ui.dp(26), tip = x0 + (x1 - x0) * f;
            // Scrubbing thickens the line; it eases back when you let go.
            float wantGrow = preview >= 0 ? 1f : 0f;
            grow = !ui.motion() ? wantGrow : grow + (wantGrow - grow) * .25f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(4 + 3 * grow));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor((ui.ink & 0x00ffffff) | 0x2a000000);
            if (tip + ui.dp(8) < x1) c.drawLine(tip + ui.dp(8), y, x1, y, paint);
            float target = ui.playing && preview < 0 && !ui.holdingSeek ? ui.dp(3.2f) : 0;
            amp = amp < 0 || !ui.motion() ? target : amp + (target - amp) * .12f;
            paint.setColor(ui.accent);
            path.rewind();
            float phase = (SystemClock.uptimeMillis() % 1600) / 1600f;
            for (float x = x0; x < tip; x += ui.dp(2)) {
                float dy = amp * (float) Math.sin(((x - x0) / ui.dp(34) + phase) * 6.283f);
                if (x == x0) path.moveTo(x, y + dy); else path.lineTo(x, y + dy);
            }
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(tip, y + amp * (float) Math.sin(((tip - x0) / ui.dp(34) + phase) * 6.283f), ui.dp(preview >= 0 ? 9 : 6), paint);
            long shownTime = preview >= 0 ? Math.round(preview * (double) ui.duration) : el;
            if (preview >= 0) {
                String seekLabel = SpatchUi.time(shownTime);
                paint.setTextSize(ui.dp(10));
                paint.setFakeBoldText(true);
                float bw = paint.measureText(seekLabel) + ui.dp(12), bh = ui.dp(18);
                float bx = Math.max(x0, Math.min(x1 - bw, tip - bw / 2f)), by = ui.dp(2);
                bubble.set(bx, by, bx + bw, by + bh);
                paint.setColor(ui.accent);
                c.drawRoundRect(bubble, ui.dp(9), ui.dp(9), paint);
                paint.setColor(ui.onAccent);
                paint.setTextAlign(Paint.Align.CENTER);
                c.drawText(seekLabel, bubble.centerX(), bubble.centerY() - (paint.descent() + paint.ascent()) / 2f, paint);
                paint.setFakeBoldText(false);
            }
            paint.setTextSize(ui.dp(12));
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(preview >= 0 ? ui.accent : ui.inkDim);
            paint.setFakeBoldText(preview >= 0);
            c.drawText(SpatchUi.time(shownTime), x0, ui.dp(55), paint);
            paint.setFakeBoldText(false);
            paint.setColor(ui.inkDim);
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(ui.duration > 0 ? "−" + SpatchUi.time(Math.max(0, ui.duration - shownTime)) : "—", x1, ui.dp(55), paint);
            boolean settling = Math.abs(target - amp) > .05f || Math.abs(wantGrow - grow) > .01f;
            if ((ui.playing || settling) && isShown() && getWindowVisibility() == VISIBLE && isAttachedToWindow()) { if (ui.motion()) postInvalidateOnAnimation(); else postInvalidateDelayed(500); }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                float from = ui.shownProgress();
                cancelScrub();
                ui.animateProgress(from);
                return true;
            }
            if (ui.duration <= 0) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scrubUri = ui.uri;
                    ui.stopHeldSeek();
                    ui.progress.cancel();
                    getParent().requestDisallowInterceptTouchEvent(true);
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (e.getActionMasked() != MotionEvent.ACTION_DOWN && (preview < 0 || !ui.uri.equals(scrubUri))) {
                        if (preview >= 0 && !ui.uri.equals(scrubUri)) { cancelScrub(); ui.invalidateProgress(); }
                        return false;
                    }
                    preview = Math.max(0, Math.min(1, (e.getX() - ui.dp(6)) / Math.max(1, getWidth() - ui.dp(12))));
                    // A light tick each time the drag crosses a tenth of the song.
                    int tenth = (int) (preview * 10);
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) lastTenth = tenth;
                    else if (tenth != lastTenth) {
                        lastTenth = tenth;
                        // Android 14's slider tick where there is one: lighter, and made for exactly this.
                        if (ui.pref("haptics", true)) performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 34 ? android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        long target = Math.round(ui.duration * (double) preview);
                        ui.command(PlayerScreen.SEEK, target, "");
                        ui.setPosition(target);
                        cancelScrub();
                    }
                    ui.invalidateProgress();
                    return true;
                default: return false;
            }
        }
    }
}
