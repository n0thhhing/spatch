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
    private boolean seeking;
    private static int accent() { return 0xd0bcff; } // theme accent token, resolved each call: the theme can change live
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
    static volatile boolean paused; // the session says at once; position updates and audio both trail a pause
    private float amp; // 0 = flat, 1 = full wave
    private float thick; // 0 = normal stroke, 1 = dragging stroke
    private float liquid; // independent of the optional thick-track setting
    private float phase; // how far the crests have travelled, in pixels
    private long phaseAt;

    public Wave() {
        played.setStyle(Paint.Style.STROKE);
        played.setStrokeCap(Paint.Cap.ROUND);
        played.setColor(0xff000000 | accent());
        rest.setStyle(Paint.Style.STROKE);
        rest.setStrokeCap(Paint.Cap.ROUND);
        rest.setColor(0x4d000000 | accent());
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
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) { seeking = true; pressLevel = level; }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (seeking && action == MotionEvent.ACTION_UP && level != pressLevel) {
                Haptics.seekDone(v);
                float width = Math.max(0, v.getWidth()-v.getPaddingLeft()-v.getPaddingRight());
                SeekSplash.at(v, v.getPaddingLeft()+width*level/10000f, v.getHeight()/2f);
            }
            seeking = false;
        }
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
                seeking = true; // the first DOWN can precede installing our touch listener
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
        boolean playing = !paused && (pressed ? playingAtPress || musicActive(now)
            : now - lastAdvance < ACTIVE_MS || musicActive(now));
        amp = motion && Settings.wave() ? approach(amp, playing ? 1f : 0f) : 0f;
        thick = !Settings.waveThick() ? 0f : motion ? approach(thick, pressed || hovered ? 1f : 0f) : (pressed ? 1f : 0f);

        liquid = motion && Settings.get(Settings.LIQUID_SCRUBBER)
            ? approach(liquid, pressed ? 1f : 0f) : 0f;

        // Proportions of the drawable height so the seekbar and the mini-player share one drawable.
        float stroke = h / 3f + (h * 0.6f - h / 3f) * thick;
        float restStroke = h / 5f;
        String style = Settings.choice(Settings.SEEK_STYLE);
        boolean scope = "scope".equals(style);
        boolean real = ("wave".equals(style) || scope) && listening(now, motion);
        String reacts = Settings.choice(Settings.WAVE_REACTS), follows = Settings.choice(Settings.WAVE_FOLLOWS), strength = Settings.choice(Settings.WAVE_STRENGTH);
        boolean height = real && ("height".equals(reacts) || "all".equals(reacts));
        float beat = real ? beat(Spectrum.bands, Spectrum.alpha) : 0f;
        if (real && ("thickness".equals(reacts) || "all".equals(reacts))) stroke = thickness(stroke, h, beat, strength);
        float a = "wave".equals(style) ? Math.max(0f, (h - stroke) / 2f) * amp : 0f;
        float wl = h * 2.5f;
        String speed = Settings.choice(Settings.WAVE_SPEED);
        long period = "slow".equals(speed) ? PERIOD_MS * 2 : "fast".equals(speed) ? PERIOD_MS / 2 : PERIOD_MS;
        // Crests travel at the chosen speed; with Speed following the music, a beat sends them along faster.
        float rush = real && ("speed".equals(reacts) || "all".equals(reacts)) ? 1f + 2f * push(strength) * beat : 1f;
        phase = (phase + Math.min(100, Math.max(0, now - phaseAt)) / (float) period * wl * rush) % wl;
        phaseAt = now;
        float cy = (b.top + b.bottom) / 2f;
        float x0 = b.left + stroke / 2f;
        float x1 = b.right - restStroke / 2f;
        float px = b.left + (b.right - b.left) * (level / 10000f);

        // Remaining track: from just after the played line to the end, never underneath it.
        float thumbGap = Math.max(thumbVisible, pressed ? thick : 0f) * h * .5f; // Spotify's own line thumb shows while dragging
        float restStart = Math.max(px + Math.max(stroke,thumbGap), x0);
        if (restStart < x1) {
            rest.setStrokeWidth(restStroke);
            canvas.drawLine(restStart, cy, x1, cy, rest);
        }
        float end = Math.min(px-thumbGap, x1);
        int opacity = played.getAlpha();
        played.setColor(Tint.color(Settings.PLAYER_COLOR));
        played.setAlpha(opacity);
        if (end > x0 && scope) {
            float energy = real ? AudioScope.energy(Spectrum.bands)*Spectrum.alpha*amp : 0;
            AudioScope.draw(canvas, played, path, x0, b.top, end, b.bottom, energy, (now%120000)/800f, false, h/12f);
        } else if (end > x0 && "beads".equals(style)) {
            // Beads swell in a slow wave that runs back along the played part.
            float gap = stroke * 2.4f;
            for (float x = x0; x <= end; x += gap) {
                played.setStrokeWidth(stroke * (1 + 0.45f * amp * (float) Math.sin((x - b.left + phase) / wl * Math.PI)));
                canvas.drawPoint(x, cy, played);
            }
        } else if (end > x0 && "pulse".equals(style)) {
            float step = h / 4f;
            for (float x = x0; x < end; x += step) {
                played.setStrokeWidth(stroke * (0.8f + 0.5f * amp * (float) Math.max(0, Math.sin((x - b.left + phase) / wl * Math.PI))));
                canvas.drawLine(x, cy, Math.min(end, x + step), cy, played);
            }
        } else if (end > x0) {
            path.reset();
            for (float x = x0; ; x = Math.min(x + 2f, end)) {
                // + phase: crests travel left, back along the played part (not toward the playhead).
                float y = cy + a * (height ? swell(Spectrum.bands, (x - x0) / (x1 - x0), Spectrum.alpha, follows, strength) : 1f) * (1f - thick * (1f - taper(end - x, wl))) * (float) Math.sin((x - b.left + phase) / wl * 2.0 * Math.PI);
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
        if (end > x0 && liquid > 0f) {
            // A single connected meniscus, contained in the drawable. Keep Spotify's
            // own thumb clear instead of painting another knob over it.
            float radius = Math.min(h * .46f, stroke * .5f + h * .14f * liquid);
            float tip = Math.min(end, b.right) - radius;
            float start = Math.max(x0, tip - h * 2f);
            float neck = Math.min(stroke * .5f, radius);
            if (tip > start) {
                path.reset();
                path.moveTo(start, cy - neck);
                path.quadTo(tip - radius, cy - neck, tip - radius, cy - radius);
                path.quadTo(tip + radius, cy - radius, tip + radius, cy);
                path.quadTo(tip + radius, cy + radius, tip - radius, cy + radius);
                path.quadTo(tip - radius, cy + neck, start, cy + neck);
                path.close();
                played.setStyle(Paint.Style.FILL);
                canvas.drawPath(path, played);
                played.setStyle(Paint.Style.STROKE);
            }
        }
        boolean settling = (amp > 0f && amp < 1f) || (thick > 0f && thick < 1f)
            || (liquid > 0f && liquid < 1f);
        if (amp > 0f || settling) {
            if (onScreen(this)) scheduleSelf(this, now + 16);
        }
    }

    /** Flat at the thumb, full half a wavelength back: dragging brings the wave onto the centre line there. */
    static float taper(float left, float wl) {
        float t = Math.max(0f, Math.min(1f, left / (wl / 2f)));
        return t * t * (3 - 2 * t);
    }

    /** Whether real audio is reaching the wave; without it the wave keeps its plain shape. */
    private boolean listening(long now, boolean motion) {
        View host = host();
        if (host == null || amp == 0f || !motion || !Settings.get(Settings.WAVE_AUDIO)) return false;
        if (host.getContext().checkSelfPermission(AudioSpectrum.PERMISSION) != 0) {
            if (Settings.firstTime("wave_audio_asked")) AudioSpectrum.requestPermission(host.getContext());
            return false;
        }
        AudioSpectrum.lease(host.getContext(), AudioSpectrum.WAVE);
        Spectrum.step(AudioSpectrum.SHARED.levels, AudioSpectrum.session(), now, true, Settings.vizSmooth());
        return AudioSpectrum.SHARED.status == null && Spectrum.seeded;
    }

    /** Wave height at a point: mostly the beat, so the whole wave moves even when only the bass end has played. Never above the plain wave, which fills the bounds. */
    static float swell(float[] bands, float along, float alpha) {
        return swell(bands, along, alpha, "mix", "normal");
    }

    /** follows: mix (beat and melody), beat (the whole wave together) or melody (each point its own band); strength widens or narrows the swing. */
    static float swell(float[] bands, float along, float alpha, String follows, String strength) {
        float melody = Spectrum.at(bands, Math.max(0f, Math.min(1f, along)), false);
        float mix = "beat".equals(follows) ? (bands[0] + bands[1]) / 2f : "melody".equals(follows) ? melody : .65f * (bands[0] + bands[1]) / 2f + .35f * melody;
        float level = Math.max(0f, Math.min(1f, alpha * mix / Spectrum.CEILING));
        if ("subtle".equals(strength)) return .55f + .45f * level;
        if ("strong".equals(strength)) return .02f + .98f * level * level;
        return .12f + .88f * level;
    }

    /** The beat's strength, 0 to 1: the two lowest bands. */
    static float beat(float[] bands, float alpha) {
        return Math.max(0f, Math.min(1f, alpha * (bands[0] + bands[1]) / (2f * Spectrum.CEILING)));
    }

    /** Use the available stroke space rather than hitting a ceiling halfway through a loud beat. */
    static float thickness(float stroke, float height, float beat, String strength) {
        return stroke + (height * .8f - stroke) * push(strength) * beat;
    }

    /** How hard Thickness and Speed follow a beat. */
    static float push(String strength) {
        return "subtle".equals(strength) ? .25f : "strong".equals(strength) ? .9f : .5f;
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
