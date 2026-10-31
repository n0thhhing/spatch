package spatch;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.*;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import p.w2b1;
import p.z2b1;

/**
 * Spatch fullscreen immersive player overlay.
 * Opens on double-tap of the player artwork; displays artwork, metadata, wavy seekbar,
 * transport controls with Scallop play/pause, volume slider, and bottom spectrum visualizer.
 */
public final class Immersive extends View implements Runnable {
    private static Immersive CURRENT;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private PlayerArt playerArt;
    private final Atmosphere atmosphere = new Atmosphere();
    private final AudioManager audioManager;

    private TextView titleView;
    private TextView subtitleView;
    private TextView posView;
    private TextView durView;
    private SeekBar seekBar;
    private View playPauseBtn;
    private View prevBtn;
    private View nextBtn;
    private View shuffleBtn;
    private View repeatBtn;

    private static final long CLOSE_MS = 280;

    private long openTime;
    private long closeTime;
    private boolean closing;
    Object back; // spatch.Back on API 33+; typed Object so older devices never resolve it
    private final float dp;
    private final Wave wave = new Wave();
    // Layout, computed once per frame in onDraw and read by onTouchEvent.
    private float artX, artY, artSize, artRadius, seekL, seekR, seekY, ctrlY, spacing, volL, volR, volY;
    // Where the artwork flies from/to: the player's own artwork (centre, size, corner radius).
    private float fromCx, fromCy, fromSize, fromRadius;
    private float drawnCx, drawnCy, drawnSize, drawnRadius;
    private float closeCx, closeCy, closeSize, closeRadius;
    private float drawnProgress, closeProgress;
    private final int[] loc = new int[2], ownLoc = new int[2];
    private final float[] squish = {1, 0};
    private float squishTarget = 1;
    private long lastFrame;
    private Drawable lastArt;
    private long popAt = -10000;
    private float touchDownX, touchDownY;
    private boolean isDraggingSeek;
    private boolean isDraggingVol;
    private long lastTapTime;
    private long seekDownTime;
    private final ImmersiveButton[] buttons = new ImmersiveButton[6];
    private int pressedButton = -1, hoveredButton = -1;
    private long controlsAt;
    private boolean seekHovered;
    private float seekThumb, volumeThumb = 4;

    // Spotify's own Encore icons (spoticon glyphs), built once the width is known.
    private Drawable icClose, icShuffle, icPrev, icNext, icRepeat, icVolume, icPlay, icPause;

    private boolean windowActive = true;

    private Shader shade;
    private float shadeH;
    private final Path spectrumPath = new Path();
    private long titleChangedAt = -1000;
    private String shownTitle;
    private boolean shadeFloating;

    public Immersive(Context context, PlayerArt art) {
        super(context, null);
        this.playerArt = art;
        this.openTime = SystemClock.uptimeMillis();
        this.dp = context.getResources().getDisplayMetrics().density;
        wave.setCallback(this); // Wave finds the context (AudioManager) through its callback
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        int[] shapes = {0,4,-3,5,3,6};
        for (int i=0;i<buttons.length;i++) buttons[i]=new ImmersiveButton(context,shapes[i],i==0);
        findViews();
    }

    public static void toggle(PlayerArt art) {
        if (CURRENT != null) {
            CURRENT.dismiss();
        } else {
            open(art);
        }
    }

    public static void open(PlayerArt art) {
        if (CURRENT != null) return;
        View root = art.getRootView();
        if (!(root instanceof ViewGroup)) return;
        Immersive v = new Immersive(art.getContext(), art);
        CURRENT = v;
        ((ViewGroup) root).addView(v, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.bringToFront();
        v.requestFocus();
        v.measureFrom();
        v.playerArt.hideForHero(true);
        if (android.os.Build.VERSION.SDK_INT >= 33) v.back = Back.register(v, v);
        v.postInvalidate();
    }

    public static void close() {
        if (CURRENT != null) CURRENT.dismiss();
    }

    public void dismiss() {
        if (closing) return;
        closeProgress = drawnProgress;
        closeCx = drawnCx; closeCy = drawnCy; closeSize = drawnSize; closeRadius = drawnRadius;
        playerArt.settlePress();
        closing = true;
        AudioSpectrum.drop(AudioSpectrum.BOTTOM);
        if (back != null) { ((Back) back).unregister(); back = null; }
        measureFrom();
        closeTime = SystemClock.uptimeMillis();
        postInvalidate();
        postDelayed(this, Wave.animationsEnabled() ? CLOSE_MS + 32 : 0); // fallback if no final frame is drawn
    }

    /** Before closing (Back): animate out. Once closing: detach. */
    public void run() {
        if (!closing) {
            dismiss();
            return;
        }
        removeCallbacks(this);
        AudioSpectrum.drop(AudioSpectrum.BOTTOM);
        playerArt.hideForHero(false);
        ViewParent p = getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(this);
        }
        if (CURRENT == this) CURRENT = null;
    }

    private void findViews() {
        View root = playerArt.getRootView();
        if (root == null) return;
        Context c = getContext();
        String pkg = c.getPackageName();
        int tId = c.getResources().getIdentifier("track_info_view_title", "id", pkg);
        int sId = c.getResources().getIdentifier("track_info_view_subtitle", "id", pkg);
        int pId = c.getResources().getIdentifier("position_text", "id", pkg);
        int dId = c.getResources().getIdentifier("duration_text", "id", pkg);
        int sbId = c.getResources().getIdentifier("seekbar", "id", pkg);
        int ppId = c.getResources().getIdentifier("nowplaying_elements_playpause_button", "id", pkg);
        int ctlId = c.getResources().getIdentifier("playback_controls_container", "id", pkg);

        View v = root.findViewById(tId);
        if (v instanceof TextView) titleView = (TextView) v;
        v = root.findViewById(sId);
        if (v instanceof TextView) subtitleView = (TextView) v;
        v = root.findViewById(pId);
        if (v instanceof TextView) posView = (TextView) v;
        v = root.findViewById(dId);
        if (v instanceof TextView) durView = (TextView) v;
        v = root.findViewById(sbId);
        if (v instanceof SeekBar) seekBar = (SeekBar) v;
        playPauseBtn = root.findViewById(ppId);

        prevBtn = nextBtn = shuffleBtn = repeatBtn = null;
        scanControls(root.findViewById(ctlId));
        buttons[1].source=shuffleBtn;buttons[2].source=prevBtn;buttons[3].source=playPauseBtn;
        buttons[4].source=nextBtn;buttons[5].source=repeatBtn;
    }

    private void scanControls(View v) {
        if (v == null) return;
        CharSequence desc=v.getContentDescription();
        if (desc != null) {
            String s=desc.toString().toLowerCase(java.util.Locale.ROOT);
            if (s.contains("previous")) prevBtn=v;
            else if (s.equals("next") || s.contains("next track")) nextBtn=v;
            else if (s.contains("shuffle") || s.contains("listening mode")) shuffleBtn=v;
            else if (s.contains("repeat")) repeatBtn=v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++) scanControls(group.getChildAt(i));
        }
    }

    private boolean isPlaying() {
        if (playPauseBtn != null) {
            CharSequence cd = playPauseBtn.getContentDescription();
            if (cd != null) {
                String s = cd.toString().toLowerCase(java.util.Locale.ROOT);
                if (s.contains("pause")) return true;
                if (s.contains("play")) return false;
            }
        }
        return false;
    }

    /** Records where the player's artwork is on screen, as the start/end of the hero flight. */
    private void measureFrom() {
        refreshArt();
        if (playerArt.getWidth() <= 0 || !playerArt.isShown()) {
            fromSize = 0; // not on screen: fall back to a scale-in at the immersive spot
            return;
        }
        playerArt.getLocationOnScreen(loc);
        getLocationOnScreen(ownLoc);
        float pw = playerArt.getWidth(), ph = playerArt.getHeight();
        boolean round = Settings.roundArt();
        fromSize = Math.min(pw, ph) * (round ? 0.88f : 1f) * playerArt.artScale();
        fromRadius = round ? fromSize / 2f : 0f;
        fromCx = loc[0] - ownLoc[0] + pw / 2f;
        fromCy = loc[1] - ownLoc[1] + ph / 2f;
    }

    // Carousel views are reused for neighbouring tracks; keep following the centred page.
    private void refreshArt() {
        if (playerArt.isCurrentPage()) return;
        PlayerArt current = PlayerArt.currentPage(getRootView());
        if (current != null && current != playerArt) {
            playerArt.hideForHero(false);
            playerArt = current;
            playerArt.hideForHero(true);
            findViews();
        }
    }

    /** Portrait layout: artwork, title/artist, seek row (art + 80dp wide), transport, volume. */
    private void layout(float w, float h) {
        artSize = Math.min(w * 0.74f, h * 0.36f);
        artX = (w - artSize) / 2f;
        artY = h * 0.125f;
        artRadius = Math.max(18 * dp, artSize * 0.055f);
        seekY = artY + artSize + h * 0.052f + h * 0.038f + h * 0.055f;
        float row = Math.min(w - 32 * dp, artSize + 80 * dp);
        float rowL = (w - row) / 2f;
        seekL = rowL + 50 * dp; // 42dp time label + 8dp gap
        seekR = rowL + row - 56 * dp; // 48dp time label + 8dp gap
        ctrlY = seekY + h * 0.075f;
        spacing = w * 0.165f;
        volY = ctrlY + h * 0.075f;
        volL = w * 0.22f;
        volR = w * 0.78f;
    }

    /** Hero progress: 0 = at the player's artwork, 1 = in place (springs slightly past it when opening). */
    private float progress(long now, boolean motion) {
        if (closing) {
            return motion ? 1 - Transport.outCubic((now - closeTime) / (float) CLOSE_MS) : 0;
        }
        if (!motion) return 1;
        float t = (now - openTime) / 1000f;
        if (t > 1.5f) return 1;
        // underdamped step response, 1.5 Hz, damping ratio 0.6 (about 9% overshoot)
        final double wn = 9.42, z = 0.6, wd = wn * Math.sqrt(1 - z * z);
        return (float) (1 - Math.exp(-z * wn * t) * (Math.cos(wd * t) + z * wn / wd * Math.sin(wd * t)));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        long now = SystemClock.uptimeMillis();
        boolean motion = Wave.animationsEnabled();
        layout(w, h);
        if (now-controlsAt>750) {findViews();controlsAt=now;}
        float p = progress(now, motion && Settings.immersiveFlight());
        float visualProgress = closing ? closeProgress * p : clamp01(p);
        int bgAlpha = Math.round(visualProgress * 255);
        int uiAlpha = Math.round(clamp01((visualProgress - 0.5f) / 0.5f) * 255);

        refreshArt();
        Drawable art = playerArt.getDrawable();
        if (art != lastArt) {
            if (art != null) atmosphere.capture(art); // follow track changes, not just the opening track
            if (lastArt != null && art != null && motion) popAt = now;
            Tint.follow(art);
            lastArt = art;
        }

        // 1. Backdrop (fades with the flight)
        int layer = bgAlpha < 255 ? canvas.saveLayerAlpha(0, 0, w, h, bgAlpha) : canvas.save();
        if (art != null && Settings.backdrop()) {
            atmosphere.setBounds(0, 0, (int) w, (int) h);
            atmosphere.draw(canvas);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Settings.backdrop() ? 0x8c14121c : 0xff14121c); // keep an opaque surface when the backdrop is off
        canvas.drawRect(0, 0, w, h, paint);
        // Calmer ground for the controls and spectrum: the surface deepens over the lower 45%.
        if (shade == null || shadeH != h) {
            shadeH = h;
            shade = new LinearGradient(0, h * 0.55f, 0, h, new int[] {0x0014121c, 0xb314121c}, null, Shader.TileMode.CLAMP);
        }
        paint.setShader(shade);
        canvas.drawRect(0, h * 0.55f, w, h, paint);
        paint.setShader(null);
        canvas.restoreToCount(layer);

        // 2. Artwork: flies between the player and its spot; squishes on press; pops on a new song.
        if (motion && Settings.artPress()) PlayerArt.spring(squish, squishTarget, now - lastFrame);
        else { squish[0] = 1; squish[1] = 0; }
        lastFrame = now;
        String change = Settings.choice(Settings.IMM_CHANGE);
        boolean changing = motion && now - popAt < 640;
        float pop = changing && "pop".equals(change) ? 0.9f + 0.1f * outBack((now - popAt) / 640f, 2f) : 1f;
        float toCx = artX + artSize / 2f, toCy = artY + artSize / 2f;
        float sCx = fromSize > 0 ? fromCx : toCx, sCy = fromSize > 0 ? fromCy : toCy;
        float sSize = fromSize > 0 ? fromSize : artSize * 0.85f;
        float sRadius = fromSize > 0 ? fromRadius : artRadius;
        float cxArt = sCx + (toCx - sCx) * p, cyArt = sCy + (toCy - sCy) * p;
        float size = (sSize + (artSize - sSize) * p) * squish[0] * pop;
        float radius = Math.max(0, sRadius + (artRadius - sRadius) * clamp01(p)) * squish[0] * pop;
        if (closing && closeSize > 0) {
            float t = 1 - p;
            cxArt = closeCx + (sCx - closeCx) * t;
            cyArt = closeCy + (sCy - closeCy) * t;
            size = closeSize + (sSize - closeSize) * t;
            radius = closeRadius + (sRadius - closeRadius) * t;
        }
        drawnProgress = visualProgress;
        drawnCx = cxArt; drawnCy = cyArt; drawnSize = size; drawnRadius = radius;
        float left = cxArt - size / 2f, top = cyArt - size / 2f;

        int turned = canvas.save();
        if (changing && "spin".equals(change)) canvas.rotate(360f * Transport.outCubic((now - popAt) / 640f), cxArt, cyArt);
        int artLayer = changing && "fade".equals(change) ? canvas.saveLayerAlpha(0, 0, w, h, Math.round(255 * clamp01((now - popAt) / 420f))) : canvas.save();
        playerArt.drawHeroDecoration(canvas, cxArt, cyArt, size, visualProgress, false);
        // Soft depth instead of a hard border: an accent glow, then a dark drop shadow under the art.
        String glowChoice = Settings.choice(Settings.ART_GLOW);
        if (!"off".equals(glowChoice)) {
            String glowStyle = Settings.choice(Settings.GLOW_STYLE);
            float strength = "soft".equals(glowChoice) ? 0.22f : 0.45f;
            if ("fade".equals(glowStyle) && motion) strength *= 0.55f + 0.45f * (float) Math.sin(now / 700.0);
            paint.setColor(0xff14121c);
            paint.setShadowLayer(("wide".equals(glowStyle) ? 60 : 36) * dp, 0, 0, (Math.round(uiAlpha * strength) << 24) | Tint.color(Settings.GLOW_COLOR) & 0xffffff);
            canvas.drawRoundRect(left, top, left + size, top + size, radius, radius, paint);
            paint.setShadowLayer(20 * dp, 0, 10 * dp, Math.round(bgAlpha * 0.55f) << 24);
            canvas.drawRoundRect(left, top, left + size, top + size, radius, radius, paint);
            paint.clearShadowLayer();
        }
        int save = canvas.save();
        path.reset();
        path.addRoundRect(left, top, left + size, top + size, radius, radius, Path.Direction.CW);
        canvas.clipPath(path);
        if (art != null) {
            playerArt.drawHeroImage(canvas, cxArt, cyArt, size, visualProgress);
        } else {
            paint.setColor(0xff2b2640);
            canvas.drawRect(left, top, left + size, top + size, paint);
        }
        canvas.restoreToCount(save);

        playerArt.drawHeroDecoration(canvas, cxArt, cyArt, size, visualProgress, true);
        canvas.restoreToCount(artLayer);
        canvas.restoreToCount(turned);
        drawRadial(canvas, cxArt, cyArt, size, Math.round(uiAlpha * clamp01(visualProgress)), now);

        // 3. Everything else fades in once the artwork is most of the way there.
        if (uiAlpha > 0) {
            int ui = uiAlpha < 255 ? canvas.saveLayerAlpha(0, 0, w, h, uiAlpha) : canvas.save();
            drawControls(canvas, w, h, now);
            canvas.restoreToCount(ui);
        }

        if (closing && now - closeTime >= CLOSE_MS) post(this); // draw the landing before revealing the source
        else if (isShown() || closing) {
            if (motion) postInvalidateOnAnimation(); // next display frame (120Hz-safe), not a fixed 16ms timer
            else postInvalidateDelayed(250);
        }
    }

    /** Easing with overshoot s past 1 before settling (new artwork pops in with s = 2). */
    private static float outBack(float t, float s) {
        t = clamp01(t) - 1;
        return 1 + (s + 1) * t * t * t + s * t * t;
    }

    private void drawControls(Canvas canvas, float w, float h, long now) {
        ensureIcons(w);
        // Top bar: close
        float topY = h * 0.065f;
        float closeX = w * 0.08f, closeR = w * 0.045f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x44f3eeff);
        canvas.drawCircle(closeX, topY, closeR, paint);
        buttons[0].draw(canvas,icClose,null,closeX,topY,w*.06f,false);

        // Metadata, ellipsized to the seek row's width
        float titleY = artY + artSize + h * 0.052f;
        float maxText = Math.min(w - 32 * dp, artSize + 80 * dp);
        Typeface face = titleView != null ? titleView.getTypeface() : null;
        paint.setTypeface(face != null ? Typeface.create(face, Typeface.BOLD) : Typeface.DEFAULT_BOLD); // Spotify's title face
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0xfff3eeff);
        paint.setTextSize(w * 0.058f);
        String title = titleView != null && titleView.getText() != null ? titleView.getText().toString() : "Spotify";
        if (!title.equals(shownTitle)) {
            if (shownTitle != null && Wave.animationsEnabled() && Settings.immersiveTitles()) titleChangedAt = now;
            shownTitle = title;
        }
        // A new song's title and artist ease in: fade up while sliding 6dp into place.
        float ease = Transport.outCubic((now - titleChangedAt - 120) / 260f); // after the track-change hitch
        float slide = (1 - ease) * 6 * dp;
        paint.setAlpha(Math.round(255 * ease));
        canvas.drawText(fit(title, maxText), w / 2f + slide, titleY, paint);
        Typeface subFace = subtitleView != null ? subtitleView.getTypeface() : null;
        paint.setTypeface(subFace != null ? subFace : Typeface.DEFAULT); // Spotify's own subtitle face, regular weight
        paint.setColor(0xffbcb3dc);
        paint.setTextSize(w * 0.038f);
        String sub = subtitleView != null && subtitleView.getText() != null ? subtitleView.getText().toString() : "";
        paint.setAlpha(Math.round(255 * ease));
        canvas.drawText(fit(sub, maxText), w / 2f + slide, titleY + h * 0.038f, paint);
        paint.setAlpha(255);

        // Seek row: times either side of the same Wave drawable the player seekbar uses.
        float prog = 0f;
        if (seekBar != null && seekBar.getMax() > 0) prog = clamp01((float) seekBar.getProgress() / seekBar.getMax());
        paint.setTextSize(13 * dp);
        paint.setColor(0xccf3eeff);
        paint.setTextAlign(Paint.Align.RIGHT);
        String posText = posView != null && posView.getText() != null ? posView.getText().toString() : "0:00";
        canvas.drawText(posText, seekL - 8 * dp, seekY + 4.5f * dp, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        String durText = durView != null && durView.getText() != null ? durView.getText().toString() : "0:00";
        canvas.drawText(durText, seekR + 8 * dp, seekY + 4.5f * dp, paint);
        float thumbTarget = isDraggingSeek || seekHovered ? 1 : 0;
        seekThumb = Wave.animationsEnabled() ? seekThumb + (thumbTarget-seekThumb)*.25f : thumbTarget;
        wave.setThumbVisible(seekThumb);
        wave.setLevel(Math.round(prog * 10000));
        wave.setBounds(Math.round(seekL), Math.round(seekY - 6 * dp), Math.round(seekR), Math.round(seekY + 6 * dp));
        wave.draw(canvas);
        float thumbWidth = 4 * dp * seekThumb;
        float thumbX = seekL + (seekR-seekL)*prog;
        if (seekThumb > .01f) {
            paint.setStyle(Paint.Style.FILL);paint.setColor(Palette.color(0xffd0bcff));
            paint.setAlpha(Math.round(255*seekThumb));
            canvas.drawRoundRect(thumbX-thumbWidth/2,seekY-9*dp,thumbX+thumbWidth/2,seekY+9*dp,thumbWidth/2,thumbWidth/2,paint);
            paint.setAlpha(255);
        }

        // Transport: shuffle, previous, Scallop play/pause, next, repeat
        boolean playing = isPlaying();
        float cx = w / 2f;
        buttons[1].draw(canvas,icShuffle,null,cx-spacing*2,ctrlY,w*.06f,playing);
        buttons[2].draw(canvas,icPrev,null,cx-spacing,ctrlY,w*.075f,playing);
        buttons[3].draw(canvas,icPlay,icPause,cx,ctrlY,w*.082f,playing);
        buttons[4].draw(canvas,icNext,null,cx+spacing,ctrlY,w*.075f,playing);
        buttons[5].draw(canvas,icRepeat,null,cx+spacing*2,ctrlY,w*.06f,playing);

        // Volume
        drawIcon(canvas, icVolume, volL - w * 0.06f, volY, w * 0.05f, 255);
        float volProg = 0.5f;
        if (audioManager != null) {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max > 0) volProg = (float) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max;
        }
        float t = 2 * dp;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33f3eeff);
        float currentVolX = volL + (volR - volL) * volProg;
        float targetWidth = isDraggingVol ? 2 : 4;
        volumeThumb = Wave.animationsEnabled() ? volumeThumb + (targetWidth-volumeThumb)*.25f : targetWidth;
        float half = volumeThumb * dp / 2, gap = half + 4*dp;
        if (currentVolX+gap<volR) canvas.drawRoundRect(currentVolX+gap,volY-t,volR,volY+t,t,t,paint);
        paint.setColor(Palette.color(0xffd0bcff));
        if (currentVolX-gap>volL) canvas.drawRoundRect(volL,volY-t,currentVolX-gap,volY+t,t,t,paint);
        canvas.drawRoundRect(currentVolX-half,volY-10*dp,currentVolX+half,volY+10*dp,half,half,paint);

        drawSpectrum(canvas, w, h, playing, now);
    }

    /** Cuts text to fit maxWidth with the current paint, ending in an ellipsis. */
    private String fit(String text, float maxWidth) {
        int n = paint.breakText(text, true, maxWidth, null);
        return n >= text.length() ? text : text.substring(0, Math.max(0, n - 1)) + "\u2026";
    }

    private void ensureIcons(float w) {
        if (icPrev != null) return;
        Context c = getContext();
        icClose = encore(c, z2b1.J1, w * 0.06f, 0xfff3eeff);
        icShuffle = encore(c, z2b1.a6, w * 0.06f, 0xddf3eeff);
        icPrev = encore(c, z2b1.e6, w * 0.075f, 0xfff3eeff);
        icNext = encore(c, z2b1.h6, w * 0.075f, 0xfff3eeff);
        icRepeat = encore(c, z2b1.Q5, w * 0.06f, 0xddf3eeff);
        icVolume = encore(c, z2b1.y7, w * 0.05f, 0xccf3eeff);
        icPlay = encore(c, z2b1.L4, w * 0.07f, 0xff14121c);
        icPause = encore(c, z2b1.E4, w * 0.07f, 0xff14121c);
    }

    private static Drawable encore(Context c, z2b1 icon, float sizePx, int color) {
        w2b1 d = new w2b1(c, icon, sizePx);
        d.b(color);
        return d;
    }

    private static void drawIcon(Canvas canvas, Drawable d, float cx, float cy, float size, int alpha) {
        int half = Math.round(size / 2f), x = Math.round(cx), y = Math.round(cy);
        d.setBounds(x - half, y - half, x + half, y + half);
        d.setAlpha(alpha);
        d.draw(canvas);
    }

    /** Artwork spectrum on immersive's rounded-square art, from the same capture as the bottom bars. */
    private void drawRadial(Canvas canvas, float cx, float cy, float size, int alpha, long now) {
        String style = Settings.choice(Settings.ART_SPECTRUM);
        if ("off".equals(style) || !Wave.animationsEnabled() || closing) {
            AudioSpectrum.drop(AudioSpectrum.RADIAL);
            return;
        }
        AudioSpectrum.request(getContext(), AudioSpectrum.RADIAL, isPlaying() && windowActive);
        Spectrum.step(AudioSpectrum.SHARED.levels, AudioSpectrum.session(), now, true, Settings.vizSmooth());
        if (Spectrum.alpha < 0.01f || alpha <= 0) return;
        paint.setColor(0xffd0bcff);
        paint.setAlpha(Math.round(alpha * Spectrum.alpha));
        if ("fine".equals(style)) {
            paint.setAlpha(Math.round(alpha * 0.75f * Spectrum.alpha));
            Spectrum.drawFine(canvas, paint, spectrumPath, Spectrum.bands, cx, cy, size / 2, 8f, dp);
        } else {
            Spectrum.drawRadial(canvas, paint, spectrumPath, Spectrum.bands, "dots".equals(style), cx, cy, size / 2, 8 * dp,
                Math.min(16 * dp, size * 0.05f), 8f, dp);
        }
        paint.setAlpha(255);
    }

    /** Bottom spectrum in the selected shape; gain state and rendering live in Spectrum. */
    private void drawSpectrum(Canvas canvas, float w, float h, boolean playing, long now) {
        if (!Settings.visualizer()) {
            AudioSpectrum.drop(AudioSpectrum.BOTTOM);
            return;
        }
        AudioSpectrum.request(getContext(), AudioSpectrum.BOTTOM, playing && windowActive && !closing);
        if (AudioSpectrum.SHARED.status != null) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(13 * dp);
            paint.setColor(0xffbcb3dc);
            canvas.drawText(AudioSpectrum.SHARED.status, w / 2, h - 64 * dp, paint);
            return;
        }
        Spectrum.step(AudioSpectrum.SHARED.levels, AudioSpectrum.session(), now, Wave.animationsEnabled(), Settings.vizSmooth());
        // Optional side margin; "Floating bars" also lifts them off the bottom edge (clearing the gesture bar).
        boolean floating = Settings.vizFloating();
        float margin = Settings.vizSideMargin() ? 16 * dp : 0, base = floating ? h - 32 * dp : h;
        float maxH = Math.max(0, Math.min(150 * dp, base - volY - 40 * dp));
        if (Spectrum.alpha < 0.01f || maxH <= 0) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffd0bcff);
        paint.setAlpha(Math.round(255 * Spectrum.alpha));
        Spectrum.draw(canvas, paint, spectrumPath, Spectrum.bands, Settings.choice(Settings.VIZ_SHAPE), margin, w - margin,
            base, maxH, Settings.vizMirrored(), Settings.vizThin(), floating, "subtle".equals(Settings.choice(Settings.VIZ_TAPER)), dp);
        paint.setAlpha(255);
    }

    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowActive = visibility == 0;
        if (!windowActive) AudioSpectrum.drop(AudioSpectrum.BOTTOM);
    }

    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        windowActive = focus;
        if (!focus) AudioSpectrum.drop(AudioSpectrum.BOTTOM);
    }

    protected void onDetachedFromWindow() {
        AudioSpectrum.drop(AudioSpectrum.BOTTOM);
        removeCallbacks(this);
        playerArt.hideForHero(false);
        if (back != null) { ((Back)back).unregister(); back = null; }
        if (CURRENT == this) CURRENT = null;
        super.onDetachedFromWindow();
    }

    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        float w = getWidth(), h = getHeight();
        layout(w,h);
        if (closing) return true;
        boolean onArt = x >= artX && x <= artX + artSize && y >= artY && y <= artY + artSize;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = x; touchDownY = y;
                pressedButton=buttonAt(x,y);
                if (pressedButton>=0) {
                    if (!buttons[pressedButton].enabled()) pressedButton=-1;
                    updateButtonFeedback(false);
                    return true;
                }
                isDraggingSeek = false;
                isDraggingVol = false;
                if (seekBar != null && seekBar.isEnabled() && Math.abs(y - seekY) < 24 * dp && x >= seekL - 8 * dp && x <= seekR + 8 * dp) {
                    isDraggingSeek = true;
                    handleSeek(MotionEvent.ACTION_DOWN, x);
                    return true;
                }
                if (Math.abs(y - volY) < 20 * dp && x >= volL - 8 * dp && x <= volR + 8 * dp) {
                    isDraggingVol = true;
                    handleVol(x);
                    return true;
                }
                if (onArt) squishTarget = PlayerArt.PRESSED;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pressedButton>=0 && buttonAt(x,y)!=pressedButton) {pressedButton=-1;updateButtonFeedback(false);}
                if (isDraggingSeek) handleSeek(MotionEvent.ACTION_MOVE, x);
                else if (isDraggingVol) handleVol(x);
                else if (!onArt) squishTarget = 1;
                return true;

            case MotionEvent.ACTION_CANCEL:
                pressedButton=-1;updateButtonFeedback(false);
                if (isDraggingSeek) handleSeek(MotionEvent.ACTION_CANCEL, x);
                isDraggingSeek = false;
                isDraggingVol = false;
                squishTarget = 1;
                return true;

            case MotionEvent.ACTION_UP:
                int clicked=pressedButton;
                boolean click=clicked>=0 && clicked==buttonAt(x,y) && buttons[clicked].enabled();
                updateButtonFeedback(click);
                pressedButton=-1;
                updateButtonFeedback(false);
                if (click) {
                    if (clicked==0) dismiss(); else { Haptics.playback(this); buttons[clicked].click(); }
                    return true;
                }
                if (squishTarget == PlayerArt.PRESSED) PlayerArt.release(squish);
                squishTarget = 1;
                if (isDraggingSeek) {
                    handleSeek(MotionEvent.ACTION_UP, x);
                    isDraggingSeek = false;
                    return true;
                }
                if (isDraggingVol) {
                    handleVol(x);
                    isDraggingVol = false;
                    return true;
                }

                // Dragged down to close
                if (y - touchDownY > 180 && Math.abs(x - touchDownX) < 160) {
                    dismiss();
                    return true;
                }

                if (Settings.visualizer() && y > h - h * 0.22f && getContext().checkSelfPermission(AudioSpectrum.PERMISSION) != 0) {
                    AudioSpectrum.requestPermission(getContext());
                    return true;
                }

                // Double tap on artwork to close
                if (onArt) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastTapTime < 500) {
                        dismiss();
                        lastTapTime = 0;
                        return true;
                    }
                    lastTapTime = now;
                }
                return true;
        }
        return true;
    }

    private int buttonAt(float x, float y) {
        float w=getWidth(),h=getHeight();
        if (Math.hypot(x-w*.08f,y-h*.065f)<Math.max(24*dp,w*.06f)) return 0;
        float r=Math.min(spacing*.48f,Math.max(24*dp,w*.08f));
        for (int i=1;i<6;i++) if (Math.hypot(x-(w/2+(i-3)*spacing),y-ctrlY)<r) return i;
        return -1;
    }

    private void updateButtonFeedback(boolean clicked) {
        for (int i=0;i<buttons.length;i++) buttons[i].interaction(i==pressedButton,i==hoveredButton,clicked && i==pressedButton);
        invalidate();
    }

    public boolean onHoverEvent(MotionEvent event) {
        layout(getWidth(),getHeight());
        seekHovered=event.getActionMasked()!=MotionEvent.ACTION_HOVER_EXIT && seekBar!=null && seekBar.isEnabled()
            && Math.abs(event.getY()-seekY)<24*dp && event.getX()>=seekL && event.getX()<=seekR;
        wave.setState(isDraggingSeek ? new int[]{0x010100a7} : seekHovered ? new int[]{0x01010367} : new int[0]);
        hoveredButton=event.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT ? -1 : buttonAt(event.getX(),event.getY());
        updateButtonFeedback(false);
        return true;
    }

    /**
     * Seeks through Spotify's own seekbar: setProgress() is a programmatic update Spotify ignores
     * (fromUser=false), so the touch is replayed on the real SeekBar, which then seeks as if dragged.
     */
    private void handleSeek(int action, float touchX) {
        wave.setState(action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
            ? new int[0] : new int[]{0x010100a7}); // mirror the real seekbar pressed state
        float frac = clamp01((touchX - seekL) / (seekR - seekL));
        if (seekBar != null && seekBar.isEnabled() && seekBar.getWidth() > 0) {
            float inner = seekBar.getWidth() - seekBar.getPaddingLeft() - seekBar.getPaddingRight();
            long t = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) seekDownTime = t;
            MotionEvent ev = MotionEvent.obtain(seekDownTime, t, action,
                seekBar.getPaddingLeft() + frac * inner, seekBar.getHeight() / 2f, 0);
            seekBar.dispatchTouchEvent(ev);
            ev.recycle();
        }
        invalidate();
    }

    private void handleVol(float touchX) {
        float frac = clamp01((touchX - volL) / (volR - volL));
        if (audioManager != null) {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(frac * max), 0);
        }
        invalidate();
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            dismiss();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
