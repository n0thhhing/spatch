package spatch;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.view.MotionEvent;
import android.view.View;
import android.os.Build;
import android.os.SystemClock;

/**
 * Spatch progress, used as the whole progress drawable (res/drawable/seekbar_progress.xml)
 * of the player seekbar and the mini-player bar. Played part: a sine wave that flows while playback
 * advances and eases flat when paused. Remaining part: a thin flat track (never drawn under the
 * played part). While the seekbar is held the played line is thicker; it keeps waving if playback
 * was running when the press began (Spotify pauses position updates while dragging).
 */
public final class Wave extends Drawable implements Runnable, View.OnTouchListener {
    private int detent = -1, pressLevel;
    private boolean watching;
    private static final int ACCENT = 0xd0bcff; // theme accent token, substituted at patch time
    private static final long ACTIVE_MS = 1200;
    private static final long PERIOD_MS = 1600;
    /** Largest level step still treated as playback (10000 = whole track); bigger jumps are seeks. */
    private static final int MAX_PLAYBACK_STEP = 400;
    private static final int STATE_PRESSED = 0x010100a7; // android.R.attr.state_pressed

    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int level;
    private long lastAdvance;
    private boolean pressed;
    private boolean hovered;
    private float thumbVisible;
    void setThumbVisible(float value) { thumbVisible=value; }
    private boolean playingAtPress;
    private AudioManager audio;
    private long audioCheckedAt;
    private boolean audioActive;
    private float amp; // 0 = flat, 1 = full wave
    private float thick; // 0 = normal stroke, 1 = dragging stroke

    public Wave() {
        played.setStyle(Paint.Style.STROKE);
        played.setStrokeCap(Paint.Cap.ROUND);
        played.setColor(0xff000000 | ACCENT);
        rest.setStyle(Paint.Style.STROKE);
        rest.setStrokeCap(Paint.Cap.ROUND);
        rest.setColor(0x4d000000 | ACCENT);
    }

    /** Ten steps along a seekbar, only while a finger holds it: playback updates stay silent. */
    private void detent(int level) {
        int step = level / 1000;
        if (step == detent) return;
        detent = step;
        View host = host();
        if (host != null && host.isPressed()) Haptics.detent(host);
    }

    private View host() {
        Object cb = getCallback();
        if (cb instanceof Drawable) cb = ((Drawable) cb).getCallback();
        return cb instanceof View ? (View) cb : null;
    }

    /** ACTION_UP, unlike the pressed state, means a seek landed; a cancelled gesture stays silent. */
    @Override
    public boolean onTouch(View v, MotionEvent e) {
        SeekBubble.touch(v, e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP && level != pressLevel) Haptics.seekDone(v);
        return false;
    }

    @Override
    protected boolean onLevelChange(int newLevel) {
        detent(newLevel);
        int step = newLevel - level;
        level = newLevel;
        if (!pressed && step > 0 && step <= MAX_PLAYBACK_STEP) {
            lastAdvance = SystemClock.uptimeMillis();
        }
        invalidateSelf();
        return true;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean now = false, hover = false;
        for (int s : state) { now |= s == STATE_PRESSED; hover |= s == 0x01010367; }
        boolean hoverChanged = hover != hovered; hovered=hover;
        if (now == pressed && !hoverChanged) {
            return false;
        }
        long t = SystemClock.uptimeMillis();
        if (now != pressed) {
            if (now) {
                playingAtPress = t - lastAdvance < ACTIVE_MS;
                pressLevel = level;
                View host = host();
                if (host != null && !watching) {
                    watching = true;
                    host.setOnTouchListener(this);
                }
            }
            else if (playingAtPress) lastAdvance = t; // bridge until playback position updates resume
        }
        pressed = now;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float h = b.height();
        if (h <= 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean motion = animationsEnabled();
        // Spotify stops position updates while the bar is touched, so also ask whether audio is playing.
        boolean playing = pressed ? playingAtPress || musicActive(now)
            : now - lastAdvance < ACTIVE_MS || musicActive(now);
        amp = motion && Settings.wave() ? approach(amp, playing ? 1f : 0f) : 0f;
        thick = !Settings.waveThick() ? 0f : motion ? approach(thick, pressed || hovered ? 1f : 0f) : (pressed ? 1f : 0f);

        // Proportions of the drawable height so the seekbar and the mini-player share one drawable.
        float stroke = h / 3f + (h * 0.6f - h / 3f) * thick;
        float restStroke = h / 5f;
        float a = Math.max(0f, (h - stroke) / 2f) * amp;
        float wl = h * 2.5f;
        String speed = Settings.choice(Settings.WAVE_SPEED);
        long period = "slow".equals(speed) ? PERIOD_MS * 2 : "fast".equals(speed) ? PERIOD_MS / 2 : PERIOD_MS;
        float phase = (now % period) / (float) period * wl;
        float cy = (b.top + b.bottom) / 2f;
        float x0 = b.left + stroke / 2f;
        float x1 = b.right - restStroke / 2f;
        float px = b.left + (b.right - b.left) * (level / 10000f);

        // Remaining track: from just after the played line to the end, never underneath it.
        float thumbGap = thumbVisible * h * .5f;
        float restStart = Math.max(px + Math.max(stroke,thumbGap), x0);
        if (restStart < x1) {
            rest.setStrokeWidth(restStroke);
            canvas.drawLine(restStart, cy, x1, cy, rest);
        }
        float end = Math.min(px-thumbGap, x1);
        if (end > x0) {
            path.reset();
            for (float x = x0; ; x = Math.min(x + 2f, end)) {
                // + phase: crests travel left, back along the played part (not toward the playhead).
                float y = cy + a * (float) Math.sin((x - b.left + phase) / wl * 2.0 * Math.PI);
                if (x == x0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
                if (x >= end) {
                    break;
                }
            }
            played.setStrokeWidth(stroke);
            canvas.drawPath(path, played);
        }
        boolean settling = (amp > 0f && amp < 1f) || (thick > 0f && thick < 1f);
        if (amp > 0f || settling) {
            if (onScreen(this)) scheduleSelf(this, now + 16);
        }
    }

    /** Local playback state, polled at most twice a second (binder call); false if unavailable. */
    private boolean musicActive(long now) {
        if (now - audioCheckedAt < 500) {
            return audioActive;
        }
        audioCheckedAt = now;
        if (audio == null) {
            Object cb = getCallback();
            while (cb instanceof Drawable) {
                cb = ((Drawable) cb).getCallback(); // seekbar_progress is a LayerDrawable around us
            }
            if (cb instanceof View) {
                audio = (AudioManager) ((View) cb).getContext().getSystemService("audio");
                Settings.load(((View) cb).getContext());
            }
        }
        audioActive = audio != null && audio.isMusicActive();
        return audioActive;
    }

    /** Critically damped step toward a target (no overshoot, so no "spring"). */
    private static float approach(float v, float target) {
        v += (target - v) * 0.2f;
        return Math.abs(target - v) < 0.01f ? target : v;
    }

    @Override
    public void run() {
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        played.setAlpha(alpha);
        rest.setAlpha(alpha * 0x4d / 255);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        played.setColorFilter(filter);
        rest.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /**
     * False when the drawable's window is covered by another activity (e.g. Now Playing over the
     * mini-player): its continuous animation would otherwise redraw the hidden screen every frame.
     * The next progress update or focus change redraws it and restarts the animation.
     */
    static boolean onScreen(Drawable d) {
        Object cb = d.getCallback();
        while (cb instanceof Drawable) {
            cb = ((Drawable) cb).getCallback();
        }
        return !(cb instanceof View) || ((View) cb).hasWindowFocus();
    }

    /** Reduced motion: no wave or animated thickness when the system animator scale is 0 (API 26+). */
    public static boolean animationsEnabled() {
        return (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) && !Settings.reduceMotion();
    }
}
