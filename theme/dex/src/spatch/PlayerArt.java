package spatch;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** Player-only circular art. Keeps Spotify's image loading, gestures and accessibility. */
public final class PlayerArt extends ImageView {
    private final Paint ink = new Paint(3);
    private final Path outline = new Path();
    // Shared by every carousel page, so a song change crossfades from the old colours rather than
    // from a fresh page's blank backdrop (which flashed dark).
    private static Drawable lastArt;
    private static int[] ids; // background, controls, title, subtitle view ids, looked up once
    private final Path ring = new Path();
    private float ringRadius, ringCx, ringCy;
    private static Atmosphere atmosphere;
    private long lastSetup;
    private float glowSize;
    private Shader glow;
    private int glowTint;
    private String glowStyle;
    private boolean faded;
    private float turn; // the distance Shrink and Fade show, which trails a fast skip
    private long turnAt, centredAt;
    private static long songAt = -100000;
    private Drawable previousArt;
    private long revealStart;
    private long tapAt = -1000;
    private float tapX, tapY;
    private View background;
    private android.widget.ProgressBar headerProgress; // sticky header's thin line, tinted once
    private final int[] loc = new int[2];
    private final float[] squish = {1, 0}; // press spring: scale, velocity
    private float squishTarget = 1;
    private long squishAt;
    private boolean heroHidden, radialOn;
    private Expansion expansion;
    private static Drawable original; // controls background before any page's backdrop replaced it
    public PlayerArt(Context c, AttributeSet a) { super(c, a); Settings.load(c); }

    public boolean dispatchTouchEvent(MotionEvent e) {
        float slop = Math.max(36, getWidth() * 0.08f);
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && Settings.artPress()) squishTarget = PRESSED;
        else if (action == MotionEvent.ACTION_UP) {
            if (Settings.artPress()) release(squish);
            squishTarget = 1;
        } else if (action == MotionEvent.ACTION_CANCEL) { squishTarget = 1; tapAt = -1000; }
        if (action == MotionEvent.ACTION_DOWN) {
            long t = SystemClock.uptimeMillis();
            if (Settings.immersive() && t - tapAt > 40 && t - tapAt < 500 && Math.abs(e.getX() - tapX) < slop * 4 && Math.abs(e.getY() - tapY) < slop * 4) {
                tapAt = -1000;
                if (Settings.artPress()) release(squish);
                squishTarget = 1; // the overlay may consume the second tap's UP
                Immersive.toggle(this);
            } else {
                tapAt = t;
            }
            tapX = e.getX(); tapY = e.getY();
        }
        postInvalidate();
        super.dispatchTouchEvent(e);
        return true; // keep receiving UP/CANCEL even when ImageView has no click listener
    }

    public void setImageDrawable(Drawable art) {
        Drawable old=getDrawable();
        super.setImageDrawable(art);
        if(old!=null && art!=null && old!=art && Wave.animationsEnabled()) {
            previousArt=old;
            revealStart=SystemClock.uptimeMillis();
        } else previousArt=null;
    }

    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        if (Wave.animationsEnabled()) spring(squish, squishTarget, now - squishAt);
        else { squish[0] = 1; squish[1] = 0; }
        squishAt = now;
        int scaled = canvas.save();
        canvas.scale(squish[0], squish[0], getWidth()/2f, getHeight()/2f);
        String skip = Settings.choice(Settings.ART_SKIP);
        boolean skipMotion = !"slide".equals(skip) && Wave.animationsEnabled();
        float offset = skipMotion ? pageOffset() : 0;
        if (Math.abs(offset) > 0.25f) centredAt = 0;
        else if (centredAt == 0) centredAt = now;
        // The title changes before the page moves: only a page that came in after it arrives, or the old art snaps away.
        turn = skipMotion ? Math.max(settle(turn, Math.abs(offset), now - turnAt), centredAt > songAt ? arriving(now - songAt) : 0) : 0;
        turnAt = now;
        turnPage(canvas, skip, offset);
        if (!heroHidden) {
            if (Settings.roundArt()) drawRound(canvas); else super.onDraw(canvas);
        }
        canvas.restoreToCount(scaled);
        // The carousel keeps three pages alive; only the centred one does per-frame work. The others
        // redraw slowly, just enough to notice when they become the centred page.
        boolean current=isCurrentPage();
        if(!current) {
            // Spin and fade follow the slide, so a page crossing the screen draws every frame.
            if(Wave.animationsEnabled() && isShown()) postInvalidateDelayed(skipMotion && Math.abs(offset) < 1.1f ? 16 : 250);
            return;
        }
        float[] from=MiniBar.claimExpansion();
        if(from!=null && Settings.miniFlight()) expansion=Expansion.start(this,from);
        if(ids==null) ids=new int[]{id("overlay_controls_layout"),id("playback_controls_container"),id("track_info_view_title"),id("track_info_view_subtitle"),id("nowplaying_elements_playpause_button")};
        if(background==null || now-lastSetup>750) {
            background=getRootView().findViewById(ids[0]);
        }
        // The artwork carousel keeps the previous and next tracks' pages alive off-screen; only the
        // page centred on screen may set the blurred backdrop, or a neighbour's art wins.
        Drawable art=getDrawable();
        Tint.follow(art);
        if(background!=null && !Settings.backdrop()) {
            if(background.getBackground() instanceof Atmosphere) background.setBackground(original);
        } else if(background!=null && art!=null) {
            if(art!=lastArt) {
                lastArt=art;
                if(atmosphere==null) atmosphere=new Atmosphere();
                atmosphere.capture(art);
            }
            if(background.getBackground()!=atmosphere) {
                if(!(background.getBackground() instanceof Atmosphere)) original=background.getBackground();
                background.setBackground(atmosphere);
            }
        }
        if(now-lastSetup>750) {
            lastSetup=now;
            View root=getRootView();
            View controls=root.findViewById(ids[1]);
            if(controls instanceof ViewGroup) decorate((ViewGroup)controls);
            ClickHaptic.attach(controls);
            TextSlide.attach(root.findViewById(ids[2]));
            TextSlide.attach(root.findViewById(ids[3]));
            if(headerProgress==null && root instanceof ViewGroup) {
                // Collapsed-player header (built in code by p.s22, no id): its plain ProgressBar is
                // Spotify white; give it the theme accent. Exact class, so SeekBars are left alone.
                headerProgress=findProgress((ViewGroup)root);
                if(headerProgress!=null) {
                    headerProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffd0bcff));
                    headerProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x4dd0bcff));
                }
            }
        }
        TapBounce.tick(getRootView());
        if(Wave.animationsEnabled() && isShown()) postInvalidateDelayed(16);
    }

    /** How far this carousel page sits from the screen's centre, in screen widths; signed. */
    private float pageOffset() {
        getLocationOnScreen(loc);
        return (loc[0] + getWidth() / 2f - getRootView().getWidth() / 2f) / getRootView().getWidth();
    }

    /** Skip animation: the page rolls like a record as it crosses, or shrinks or fades on the way. */
    private void turnPage(Canvas canvas, String skip, float offset) {
        boolean fade = "fade".equals(skip) && Wave.animationsEnabled();
        if (fade || faded) {
            setAlpha(fade ? 1 - Math.min(1, turn * 1.4f) : 1f);
            faded = fade;
        }
        if ("slide".equals(skip) || fade) return;
        if ("spin".equals(skip)) {
            canvas.rotate(offset * 200f, getWidth() / 2f, getHeight() / 2f);
            return;
        }
        float scale = 1 - 0.35f * Math.min(1, turn);
        canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
    }

    /** A skip lands too fast to see a size or opacity change, so arriving is held to a readable pace; a finger drag is slower and is followed exactly. */
    static float settle(float shown, float distance, long elapsed) {
        return Math.max(distance, shown - Math.min(elapsed, 32) / ARRIVE_MILLIS);
    }

    /** Spotify can jump the carousel to a new song without sliding it, so the song itself starts the arrival. */
    static void songChanged() {
        songAt = SystemClock.uptimeMillis();
    }

    static float arriving(long sinceSong) {
        return Math.max(0, 1 - sinceSong / ARRIVE_MILLIS);
    }

    /** Circular art with a soft glow and a slowly turning scalloped ring. */
    private void drawRound(Canvas canvas) {
        float cx = getWidth()/2f, cy = getHeight()/2f;
        float radius = Math.min(cx,cy)*0.88f;
        drawGlow(canvas);
        outline.reset();
        outline.addCircle(cx,cy,radius,Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(outline);
        float reveal=Wave.animationsEnabled()?Math.min(1f,(SystemClock.uptimeMillis()-revealStart)/420f):1f;
        reveal=1-(1-reveal)*(1-reveal)*(1-reveal);
        if(previousArt!=null && reveal<1) {
            drawArtwork(canvas, previousArt, 0, 0, getWidth(), getHeight());
            int layer=canvas.saveLayerAlpha(0,0,getWidth(),getHeight(),(int)(255*reveal));
            canvas.scale(.97f+.03f*reveal,.97f+.03f*reveal,cx,cy);
            super.onDraw(canvas);
            canvas.restoreToCount(layer);
        } else {
            previousArt=null;
            super.onDraw(canvas);
        }
        canvas.restoreToCount(save);
        drawRing(canvas);
        drawRadial(canvas, cx, cy, radius);
    }

    /** Real audio energy around the circular art, from the shared capture. Centred page only. */
    private void drawRadial(Canvas canvas, float cx, float cy, float radius) {
        String style = Settings.choice(Settings.ART_SPECTRUM);
        boolean bars = !"off".equals(style), reactive = "audio".equals(Settings.choice(Settings.ART_OUTLINE));
        if (!bars && !reactive || heroHidden || !Wave.animationsEnabled() || !isCurrentPage() || !hasWindowFocus()) {
            // Neighbour carousel pages redraw too: only the page that asked may release the capture.
            if (radialOn) AudioSpectrum.drop(AudioSpectrum.RADIAL);
            radialOn = false;
            return;
        }
        radialOn = true;
        AudioSpectrum.request(getContext(), AudioSpectrum.RADIAL, playing());
        Spectrum.step(AudioSpectrum.SHARED.levels, AudioSpectrum.session(), SystemClock.uptimeMillis(), Wave.animationsEnabled(), Settings.vizSmooth());
        if (!bars || Spectrum.alpha < 0.01f) return;
        float dp = getResources().getDisplayMetrics().density;
        // Fits inside the view's 12% margin, past the outline; nothing is clipped or covers controls.
        float gap = "none".equals(Settings.choice(Settings.ART_OUTLINE)) ? radius * 0.03f : radius * 0.07f;
        ink.setShader(null);
        ink.setColor(0xffd0bcff);
        ink.setAlpha(Math.round(255 * Spectrum.alpha));
        if ("fine".equals(style)) {
            ink.setAlpha(Math.round(190 * Spectrum.alpha));
            Spectrum.drawFine(canvas, ink, outline, Spectrum.bands, cx, cy, radius, 0, dp);
        } else {
            Spectrum.drawRadial(canvas, ink, outline, Spectrum.bands, "dots".equals(style), cx, cy, radius, gap, radius * 0.05f, 0, dp);
        }
    }

    private boolean playing() {
        View button=ids==null?null:getRootView().findViewById(ids[4]);
        CharSequence d=button==null?null:button.getContentDescription();
        return d!=null && d.toString().toLowerCase(java.util.Locale.ROOT).contains("pause");
    }

    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != 0) AudioSpectrum.drop(AudioSpectrum.RADIAL);
    }

    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) AudioSpectrum.drop(AudioSpectrum.RADIAL);
    }

    protected void onDetachedFromWindow() {
        if(expansion!=null) expansion.finish();
        expansion=null;
        AudioSpectrum.drop(AudioSpectrum.RADIAL);
        super.onDetachedFromWindow();
    }

    private void drawGlow(Canvas canvas) {
        if ("off".equals(Settings.choice(Settings.ART_GLOW))) return;
        float cx = getWidth()/2f, cy = getHeight()/2f, edge = Math.min(cx,cy);
        float radius = edge*0.88f;
        String style = Settings.choice(Settings.GLOW_STYLE);
        int tint = Tint.color(Settings.GLOW_COLOR) & 0xffffff;
        boolean motion = Wave.animationsEnabled();
        long now = SystemClock.uptimeMillis();
        float strength = "soft".equals(Settings.choice(Settings.ART_GLOW)) ? 0.5f : 1f;
        if ("fade".equals(style) && motion) strength *= 0.55f + 0.45f * (float) Math.sin(now / 700.0);
        if(glow==null || glowSize!=radius || glowTint!=tint || !style.equals(glowStyle)) {
            glowSize=radius; glowTint=tint; glowStyle=style;
            int on = 0x55000000 | tint, off = tint;
            if ("sweep".equals(style)) {
                int second = 0x77000000 | Pill.blend(tint, 0xffffff, 0.45f) & 0xffffff;
                glow=new ComposeShader(new RadialGradient(cx,cy,edge,new int[]{0,0xff000000,0},new float[]{0,.86f,1},Shader.TileMode.CLAMP),
                    new SweepGradient(cx,cy,new int[]{on,off,second,off,on},null),PorterDuff.Mode.SRC_IN);
            } else if ("wide".equals(style)) {
                glow=new RadialGradient(cx,cy,edge,new int[]{off,on,0x33000000|tint,off},new float[]{0,.8f,.92f,1},Shader.TileMode.CLAMP);
            } else {
                glow=new RadialGradient(cx,cy,edge,new int[]{off,on,off},new float[]{0,.86f,1},Shader.TileMode.CLAMP);
            }
        }
        ink.setAlpha(Math.round(255 * strength));
        ink.setStyle(Paint.Style.FILL); ink.setShader(glow);
        int save = canvas.save();
        if ("sweep".equals(style) && motion) canvas.rotate((now % 6000) * 0.06f, cx, cy);
        canvas.drawCircle(cx, cy, edge, ink);
        canvas.restoreToCount(save);
        ink.setShader(null);
    }

    private void drawRing(Canvas canvas) {
        float cx = getWidth()/2f, cy = getHeight()/2f;
        float radius = Math.min(cx,cy)*0.88f;
        String style = Settings.choice(Settings.ART_OUTLINE);
        if ("none".equals(style)) return;
        long now = SystemClock.uptimeMillis();
        String speed = Settings.choice(Settings.RING_SPEED);
        long period = "slow".equals(speed) ? 36000 : "fast".equals(speed) ? 7000 : 18000;
        float phase = Wave.animationsEnabled() && !"still".equals(speed) ? (now%period)/(float)period*6.283185f : 0;
        String width = Settings.choice(Settings.RING_WIDTH);
        ink.setShader(null);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(Math.max(2, radius*.012f) * ("thin".equals(width) ? .6f : "bold".equals(width) ? BOLD : 1f));
        ink.setColor(Tint.color(Settings.RING_COLOR));
        if ("audio".equals(style)) {
            traceAudioRing(cx, cy, radius);
            ringRadius = 0; // the resting shape is rebuilt when the style changes back
            int save=canvas.save();
            canvas.rotate((float)Math.toDegrees(phase/24),cx,cy);
            canvas.drawPath(ring,ink);
            canvas.restoreToCount(save);
            return;
        }
        if(ringRadius!=radius || ringCx!=cx || ringCy!=cy) {
            // Built once per size; the motion is a rotation, since sin(24a - phase) is the same curve
            // turned by phase/24.
            ringRadius=radius; ringCx=cx; ringCy=cy;
            ring.reset();
            for(int i=0;i<=360;i++) {
                double angle=i*Math.PI/180;
                float r=radius+radius*.045f+(float)Math.sin(angle*24)*radius*.012f;
                float x=cx+(float)Math.cos(angle)*r, y=cy+(float)Math.sin(angle)*r;
                if(i==0) ring.moveTo(x,y); else ring.lineTo(x,y);
            }
            ring.close();
        }
        if ("simple".equals(style)) {
            canvas.drawCircle(cx,cy,radius*1.045f,ink);
            return;
        }
        int save=canvas.save();
        canvas.rotate((float)Math.toDegrees(phase/24),cx,cy);
        canvas.drawPath(ring,ink);
        canvas.restoreToCount(save);
    }

    /** The band under a ring angle, bass at 0 degrees and treble at 180, mirrored down the other side. */
    static float audioLevel(int degrees, float[] bands) {
        float at=(degrees<=180?degrees:360-degrees)/180f*(bands.length-1);
        int low=Math.min(bands.length-2,(int)at);
        return bands[low]+(bands[low+1]-bands[low])*(at-low);
    }

    /** The wavy ring with each angle pushed out by its band: bass at the top, mirrored like the bars. */
    private void traceAudioRing(float cx, float cy, float radius) {
        ring.reset();
        for(int i=0;i<=360;i++) {
            double angle=i*Math.PI/180;
            float r=radius*1.045f+(float)Math.sin(angle*24)*radius*.012f+audioLevel(i,Spectrum.bands)*Spectrum.alpha*radius*AUDIO_REACH;
            float x=cx+(float)Math.sin(angle)*r, y=cy-(float)Math.cos(angle)*r;
            if(i==0) ring.moveTo(x,y); else ring.lineTo(x,y);
        }
        ring.close();
    }

    private int id(String name) {
        return getContext().getResources().getIdentifier(name,"id",getContext().getPackageName());
    }

    void drawHeroDecoration(Canvas canvas, float cx, float cy, float size, float progress, boolean ring) {
        if (!Settings.roundArt()) return;
        float t = 1 - Math.max(0, Math.min(1, progress));
        int alpha = Math.round(255 * t * t * (3 - 2 * t));
        if (alpha == 0) return;
        float scale = size / (Math.min(getWidth(), getHeight()) * 0.88f);
        int save = canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(scale, scale, 0, 0);
        canvas.translate(-getWidth()/2f, -getHeight()/2f);
        int layer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), alpha);
        if (ring) drawRing(canvas); else drawGlow(canvas);
        canvas.restoreToCount(layer);
        canvas.restoreToCount(save);
    }

    void hideForHero(boolean hide) {
        heroHidden = hide;
        invalidate();
    }

    void settlePress() { squish[0] = 1; squish[1] = 0; squishTarget = 1; }
    float artScale() { return squish[0]; }

    /** Keep the native ImageView crop at the player end of the flight. */
    void drawHeroImage(Canvas canvas, float cx, float cy, float size, float progress) {
        float fraction = Settings.roundArt() ? 0.88f + 0.12f * Math.max(0, Math.min(1, progress)) : 1;
        float scale = size / (Math.min(getWidth(), getHeight()) * fraction);
        int save = canvas.save();
        try {
            canvas.translate(cx, cy);
            canvas.scale(scale, scale, 0, 0);
            canvas.translate(-getWidth() / 2f, -getHeight() / 2f);
            super.onDraw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    static final float PRESSED = 0.94f, ARRIVE_MILLIS = 420f;
    /** Ring push at level 1, in radii, and the Bold stroke: both must fit the view's margin at the loudest level. */
    static final float AUDIO_REACH = 0.055f, BOLD = 1.8f;

    static void release(float[] state) {
        // A quick DOWN/UP can land between frames; ensure it still starts a visible rebound.
        if (Wave.animationsEnabled()) state[0] = Math.min(state[0], PRESSED);
    }

    /** Draw shared artwork without changing its bounds or invalidating ImageView's cached geometry. */
    static void drawArtwork(Canvas canvas, Drawable art, float x, float y, float width, float height) {
        Rect bounds = art.getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        int save = canvas.save();
        try {
            canvas.translate(x, y);
            canvas.scale(width / bounds.width(), height / bounds.height(), 0, 0);
            canvas.translate(-bounds.left, -bounds.top);
            art.draw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * Advances a bouncy spring (about 2.6 Hz, damping ratio 0.3: a squish that springs back past
     * rest, a press "bounce") by dtMs toward target; s = {value, velocity}.
     */
    static void spring(float[] s, float target, long dtMs) {
        final float w = 16.3f, z = 0.3f;
        for (long t = Math.min(dtMs, 64); t > 0; t -= 4) {
            float dt = Math.min(t, 4) / 1000f;
            s[1] += (-w * w * (s[0] - target) - 2 * z * w * s[1]) * dt;
            s[0] += s[1] * dt;
        }
        if (Math.abs(s[0] - target) < 0.0005f && Math.abs(s[1]) < 0.005f) { s[0] = target; s[1] = 0; }
    }

    static PlayerArt currentPage(View root) {
        if (root instanceof PlayerArt && ((PlayerArt) root).isCurrentPage()) return (PlayerArt) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                PlayerArt art = currentPage(group.getChildAt(i));
                if (art != null) return art;
            }
        }
        return null;
    }

    private static android.widget.ProgressBar findProgress(ViewGroup group) {
        for(int i=0;i<group.getChildCount();i++) {
            View v=group.getChildAt(i);
            if(v.getClass()==android.widget.ProgressBar.class) return (android.widget.ProgressBar)v;
            if(v instanceof ViewGroup) {
                android.widget.ProgressBar found=findProgress((ViewGroup)v);
                if(found!=null) return found;
            }
        }
        return null;
    }

    boolean isCurrentPage() {
        if(getWidth()<=0 || !isShown()) return false;
        getLocationOnScreen(loc);
        float centre=loc[0]+getWidth()/2f;
        return Math.abs(centre-getRootView().getWidth()/2f) < getWidth()/4f;
    }

    private static void decorate(ViewGroup group) {
        // Decorative feedback extends beyond icon bounds; keep hit boxes and icon sizes intact.
        group.setClipChildren(false);
        group.setClipToPadding(false);
        for(int i=0;i<group.getChildCount();i++) {
            View v=group.getChildAt(i);
            if(v instanceof ViewGroup) decorate((ViewGroup)v);
            else if(v instanceof ImageView) {
                CharSequence description=v.getContentDescription();
                if(description==null) continue;
                String s=description.toString().toLowerCase(java.util.Locale.ROOT);
                // English descriptions; "Choose a Listening Mode" is the shuffle button in some contexts.
                int kind=s.contains("shuffle")||s.contains("listening mode")?4:s.contains("repeat")?6:s.equals("next")?3:s.equals("previous")?-3:0;
                if(kind!=0 && !(v.getBackground() instanceof Transport)) v.setBackground(new Transport(v,kind,v.getBackground()));
            }
        }
    }
}
