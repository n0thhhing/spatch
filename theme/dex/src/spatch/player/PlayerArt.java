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
    boolean preview; // settings sample: draws with the live settings, never touches the player or audio
    private final Paint ink = new Paint(3);
    private final Path outline = new Path();
    // Shared by every carousel page, so a song change crossfades from the old colours rather than
    // from a fresh page's blank backdrop (which flashed dark).
    private static Drawable lastArt;
    private static int[] ids; // background, controls, title, subtitle view ids, looked up once
    /** Spotify can wrap the button's drawable and give the id to a parent, so follow both a few steps up. */
    static boolean isPlayButton(Object v){
        if(ids==null) return false;
        for(int i=0;i<4 && v instanceof android.graphics.drawable.Drawable;i++) v=((android.graphics.drawable.Drawable)v).getCallback();
        for(int i=0;i<3 && v instanceof View;i++){ if(((View)v).getId()==ids[4]) return true; v=((View)v).getParent(); }
        return false;
    }
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
    static long songAt = -100000;
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
    private int titleColour, artistColour;
    private static final float TITLE_ALBUM=.7f;
    private float beatLevel;
    private final ArtMotion motion = new ArtMotion(this);
    private final Camera flipCamera = new Camera();
    private final Matrix flipMatrix = new Matrix();
    private Expansion expansion;
    private static Drawable original; // controls background before any page's backdrop replaced it
    private static boolean cardsHidden;
    private static Drawable pageOriginal; // the Now Playing page's own background, while the backdrop covers it
    public PlayerArt(Context c, AttributeSet a) { super(c, a); Settings.load(c); }

    static PlayerArt preview(Context c, Drawable art) {
        PlayerArt sample = new PlayerArt(c, null);
        sample.preview = true;
        sample.setImageDrawable(art);
        return sample;
    }

    public boolean dispatchTouchEvent(MotionEvent e) {
        if (preview) return false;
        float slop = Math.max(36, getWidth() * 0.08f);
        motion.touch(e);
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
        resnap(this, now);
        if (Wave.animationsEnabled()) spring(squish, squishTarget, now - squishAt);
        else { squish[0] = 1; squish[1] = 0; }
        squishAt = now;
        int scaled = canvas.save();
        float size = squish[0] * (1 + (Settings.get(Settings.BEAT_BOUNCE) && Wave.animationsEnabled() ? BEAT * Math.min(1, beatLevel) : 0));
        canvas.scale(size, size, getWidth()/2f, getHeight()/2f);
        String skip = Settings.choice(Settings.ART_SKIP);
        boolean skipMotion = !"slide".equals(skip) && Wave.animationsEnabled();
        float offset = skipMotion ? pageOffset() : 0;
        if (Math.abs(offset) > 0.25f) centredAt = 0;
        else if (centredAt == 0) centredAt = now;
        // The title changes before the page moves: only a page that came in after it arrives, or the old art snaps away.
        turn = skipMotion ? Math.max(settle(turn, Math.abs(offset), now - turnAt), centredAt > songAt ? arriving(now - songAt) : 0) : 0;
        turnAt = now;
        // An ad brings its own artwork and frame: reshaping it as well made the two fight over the shape.
        boolean ad = SpatchWidget.adPlaying;
        if (!ad) turnPage(canvas, skip, offset);
        if (Wave.animationsEnabled() && !ad) motion.apply(canvas, now);
        if (!heroHidden) {
            if (Settings.roundArt() && !ad) drawRound(canvas); else super.onDraw(canvas);
        }
        canvas.restoreToCount(scaled);
        // The carousel keeps three pages alive; only the centred one does per-frame work. The others
        // redraw slowly, just enough to notice when they become the centred page.
        boolean current=isCurrentPage();
        if(!current) {
            // Spin and fade follow the slide, so a page crossing the screen draws every frame.
            if(Wave.animationsEnabled() && isShown()) {
                if(preview || skipMotion && Math.abs(offset) < 1.1f) postInvalidateOnAnimation();
                else postInvalidateDelayed(250);
            }
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
        if (!preview) {PlayerLyrics.bind(getRootView());Immersive.restore(this);}
        updateAudio();
        Tint.follow(art);
        colourTitle();
        // With Artwork colours the backdrop sits behind the whole page, so Spotify's scroll cards ride on it too;
        // otherwise it fills the player and fades into the page's theme colour.
        View page=background!=null && background.getParent() instanceof View ? (View)((View)background.getParent()).getParent() : null;
        if(page!=null && !preview && (playerPage==null || playerPage.get()!=page)) playerPage=new java.lang.ref.WeakReference<View>(page);
        boolean whole=page!=null && Settings.get(Settings.ART_THEME);
        if(background!=null && !Settings.backdrop()) {
            if(background.getBackground() instanceof Atmosphere || whole && background.getBackground()==null) background.setBackground(original);
            if(page!=null && page.getBackground() instanceof Atmosphere) page.setBackground(pageOriginal);
        } else if(background!=null && art!=null) {
            if(art!=lastArt) {
                lastArt=art;
                if(atmosphere==null) atmosphere=new Atmosphere();
                atmosphere.capture(art);
            }
            atmosphere.fadeBottom=!whole;
            View host=whole?page:background;
            if(!whole && page!=null && page.getBackground() instanceof Atmosphere) page.setBackground(pageOriginal);
            if(whole && background.getBackground()!=null) {
                if(!(background.getBackground() instanceof Atmosphere)) original=background.getBackground();
                background.setBackground(null);
            }
            if(host.getBackground()!=atmosphere) {
                if(host==page){ if(!(page.getBackground() instanceof Atmosphere)) pageOriginal=page.getBackground(); }
                else if(!(background.getBackground() instanceof Atmosphere) && background.getBackground()!=null) original=background.getBackground();
                host.setBackground(atmosphere);
            }
            atmosphere.follow(this,host,Math.min(getWidth(),getHeight())*(Settings.roundArt() && !ad ? .44f : .5f)*size);
        }
        if(now-lastSetup>750) {
            lastSetup=now;
            View root=getRootView();
            unclip(this, root.getWidth());
            View controls=root.findViewById(ids[1]);
            if(controls instanceof ViewGroup) decorate((ViewGroup)controls);
            ClickHaptic.attach(controls);
            TextSlide.attach(root.findViewById(ids[2]));
            TextSlide.attach(root.findViewById(ids[3]));
            if(headerProgress==null && root instanceof ViewGroup) {
                // Collapsed-player header (built in code by p.S_CollapsedPlayerHeader, no id): its plain ProgressBar is
                // Spotify white; give it the theme accent. Exact class, so SeekBars are left alone.
                headerProgress=findProgress((ViewGroup)root);
            }
            if(headerProgress!=null) { // retinted each pass: with Artwork colours it follows the song
                headerProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Tint.player(0xffd0bcff)));
                headerProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Tint.player(0x4dd0bcff)));
            }
        }
        TapBounce.tick(getRootView());
        if(Wave.animationsEnabled() && isShown()) postInvalidateOnAnimation();
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
        if ("flip".equals(skip)) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            flipCamera.save();
            flipCamera.rotateY(offset * 90f);
            flipCamera.getMatrix(flipMatrix);
            flipCamera.restore();
            flipMatrix.preTranslate(-cx, -cy);
            flipMatrix.postTranslate(cx, cy);
            canvas.concat(flipMatrix);
            return;
        }
        float scale = 1 - 0.35f * Math.min(1, turn);
        canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
    }

    private static long snapCheckedAt, offSlotSince;

    /**
     * Free accounts: when Spotify takes an ad page back out of the player's carousel, the remaining pages
     * can be left part-way between slots, half a cover on screen. A carousel at rest with a page off its
     * slot for a moment is asked to settle on its current position; a drag or fling is left alone.
     */
    static void resnap(View art, long now) {
        if (now - snapCheckedAt < 300) return;
        snapCheckedAt = now;
        View page = art;
        android.view.ViewParent up = art.getParent();
        while (up instanceof View && !(up instanceof com.spotify.legacyglue.carousel.CarouselView)) {
            page = (View) up;
            up = up.getParent();
        }
        if (!(up instanceof com.spotify.legacyglue.carousel.CarouselView)) return;
        com.spotify.legacyglue.carousel.CarouselView carousel = (com.spotify.legacyglue.carousel.CarouselView) up;
        int width = carousel.getWidth();
        if (width == 0 || page.getWidth() != width || carousel.getScrollState() != 0 || Math.floorMod(page.getLeft(), width) == 0) {
            offSlotSince = 0;
            return;
        }
        if (offSlotSince == 0) {
            offSlotSince = now;
        } else if (now - offSlotSince >= 300) {
            offSlotSince = 0;
            carousel.setPosition(carousel.getCurrentPosition());
        }
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
        trace(outline, Settings.choice(Settings.ART_SHAPE), cx, cy, radius);
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
        motion.sheen(canvas, radius);
        canvas.restoreToCount(save);
        drawRing(canvas);
        drawRadial(canvas, cx, cy, radius);
        if (motion.scratching) motion.readout(canvas, getResources().getDisplayMetrics().density);
    }

    /** The artwork's outline, every shape reaching about the same size. */
    static void trace(Path path, String shape, float cx, float cy, float radius) {
        ArtContour.trace(path, shape, cx, cy, radius);
    }

    /** One capture lease for all artwork audio, even when the circular mask is off. */
    private long shownKick;
    private void updateAudio() {
        boolean wanted = Settings.roundArt() && (!"off".equals(Settings.choice(Settings.ART_SPECTRUM)) || "audio".equals(Settings.choice(Settings.ART_OUTLINE))
            || Settings.get(Settings.BEAT_BOUNCE)) || Settings.backdrop() && Settings.get(Settings.BASS_SHOCKWAVE);
        if (!wanted || preview || heroHidden || !Wave.animationsEnabled() || !isCurrentPage() || !hasWindowFocus()) {
            if (radialOn) AudioSpectrum.drop(AudioSpectrum.RADIAL);
            radialOn = false;
            beatLevel = 0;
            return;
        }
        radialOn = true;
        AudioSpectrum.request(getContext(), AudioSpectrum.RADIAL, playing());
        Spectrum.step(AudioSpectrum.SHARED.levels, AudioSpectrum.session(), SystemClock.uptimeMillis(), true, Settings.vizSmooth());
        if (shownKick != Spectrum.kickAt) {
            shownKick = Spectrum.kickAt;
            if (shownKick > 0 && SystemClock.uptimeMillis()-shownKick < 120 && background != null && Settings.get(Settings.BASS_SHOCKWAVE)) background.invalidate();
        }
        beatLevel = Settings.get(Settings.BEAT_BOUNCE) ? Spectrum.alpha*(Spectrum.bands[0]+Spectrum.bands[1])/2 : 0;
    }

    private void drawRadial(Canvas canvas, float cx, float cy, float radius) {
        String style = Settings.choice(Settings.ART_SPECTRUM);
        if ("off".equals(style) || heroHidden || !Wave.animationsEnabled() || !isCurrentPage() || !hasWindowFocus() || Spectrum.alpha < .01f) return;
        float dp = getResources().getDisplayMetrics().density;
        float gap = "none".equals(Settings.choice(Settings.ART_OUTLINE)) ? radius*.03f : radius*.07f;
        ink.setAlpha(Math.round(("fine".equals(style) ? 190 : 255)*Spectrum.alpha));
        Spectrum.drawArtwork(canvas, ink, outline, Spectrum.bands, style, Settings.choice(Settings.ART_SHAPE), .26f,
            cx, cy, radius, gap, radius*.05f, dp);
    }

    private boolean playing() {
        View button=ids==null?null:getRootView().findViewById(ids[4]);
        CharSequence d=button==null?null:button.getContentDescription();
        return d!=null && d.toString().toLowerCase(java.util.Locale.ROOT).contains("pause");
    }

    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != 0) AudioSpectrum.drop(AudioSpectrum.RADIAL);
        showing(visibility == 0);
    }

    // Every carousel page reports in, and neighbours come and go with each song.
    private static final java.util.WeakHashMap<PlayerArt,Boolean> VISIBLE=new java.util.WeakHashMap<PlayerArt,Boolean>();
    private static long shownAt; private static boolean shownLast;
    private static java.lang.ref.WeakReference<View> playerPage;
    private void showing(boolean on){ if(on && !preview) VISIBLE.put(this,Boolean.TRUE); else VISIBLE.remove(this); shownAt=0; }

    /**
     * Now Playing is expanded and its artwork is on screen. The player shares the main window, so window
     * visibility alone stays on after it collapses; checked at most every 100ms as Compose asks often.
     */
    static boolean shown(){
        long now=SystemClock.uptimeMillis();
        if(now-shownAt<100) return shownLast;
        shownAt=now;
        boolean any=false;
        int[] at=new int[2];
        try {
            View page=playerPage==null?null:playerPage.get();
            boolean live=false;
            for(PlayerArt p:VISIBLE.keySet()) if(p!=null && p.isShown()){ live=true; break; }
            // The whole Now Playing page (it stays put while its cards scroll): expanded when it sits near the top.
            if(live && page!=null && page.isShown() && page.getHeight()>0){
                page.getLocationOnScreen(at);
                any=at[1] < page.getResources().getDisplayMetrics().heightPixels*0.4f;
            }
        } catch(RuntimeException e) { any=false; } // a page added mid-walk: ask again next time
        shownLast=any;
        return any;
    }

    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) AudioSpectrum.drop(AudioSpectrum.RADIAL);
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if(getWindowVisibility()==0) showing(true);
    }

    protected void onDetachedFromWindow() {
        if(expansion!=null) expansion.finish();
        expansion=null;
        AudioSpectrum.drop(AudioSpectrum.RADIAL);
        showing(false);
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
        if (motion && beatLevel > 0.01f) strength *= (1f + 0.35f * beatLevel);
        if(glow==null || glowSize!=radius || glowTint!=tint || !style.equals(glowStyle)) {
            glowSize=radius; glowTint=tint; glowStyle=style;
            int on = 0x55000000 | tint, off = tint;
            if ("sweep".equals(style)) {
                int second = 0x77000000 | Pill.blend(tint, 0xffffff, 0.45f) & 0xffffff;
                glow=new ComposeShader(new RadialGradient(cx,cy,edge,new int[]{0,0xff000000,0},new float[]{0,.86f,1},Shader.TileMode.CLAMP),
                    new SweepGradient(cx,cy,new int[]{on,off,second,off,on},null),PorterDuff.Mode.SRC_IN);
            } else if ("wide".equals(style)) {
                glow=new RadialGradient(cx,cy,edge,new int[]{off,on,0x33000000|tint,off},new float[]{0,.8f,.92f,1},Shader.TileMode.CLAMP);
            } else if ("aura".equals(style)) {
                glow=new RadialGradient(cx,cy,edge,new int[]{off,0x66000000|tint,0x35000000|tint,0x10000000|tint,off},new float[]{0,.72f,.85f,.94f,1},Shader.TileMode.CLAMP);
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
        if (!"circle".equals(Settings.choice(Settings.ART_SHAPE))) {
            // Other shapes wear a plain line around their own outline.
            ink.setShader(null);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(Math.max(2, radius*.012f));
            ink.setColor(Tint.color(Settings.RING_COLOR));
            int shaped=canvas.save();
            canvas.scale(1.05f,1.05f,cx,cy);
            canvas.drawPath(outline,ink);
            canvas.restoreToCount(shaped);
            return;
        }
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

    private static final java.util.HashMap<String,Integer> IDS=new java.util.HashMap<String,Integer>();

    /** Resource ids by name, looked up once: getIdentifier is a slow string search. */
    private int id(String name) {
        Integer known=IDS.get(name);
        if(known==null){ known=getContext().getResources().getIdentifier(name,"id",getContext().getPackageName()); IDS.put(name,known); }
        return known;
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

    /** Mostly the cover's colour, kept part theme text so a dark cover stays readable; restored when off. Artwork colours tint the artist line too. */
    private static long colouredAt;

    private void colourTitle() {
        if(preview) return;
        // Runs from onDraw: a few times a second normally, ~30fps while a song's colours fade in.
        long now=SystemClock.uptimeMillis();
        if(now-colouredAt < (Tint.fading() ? 33 : 400) && now>=colouredAt) return;
        colouredAt=now;
        View found=getRootView().findViewById(ids[2]);
        if(found instanceof android.widget.TextView) {
            android.widget.TextView title=(android.widget.TextView)found;
            if(titleColour==0) titleColour=title.getCurrentTextColor();
            int base=Settings.get(Settings.ART_THEME)?Tint.player(0xfff3eeff):titleColour;
            int wanted=Settings.get(Settings.TITLE_ALBUM)?Pill.blend(base,Tint.album(),TITLE_ALBUM):base;
            if(wanted!=title.getCurrentTextColor()) title.setTextColor(wanted);
        }
        found=getRootView().findViewById(ids[3]);
        if(found instanceof android.widget.TextView) {
            android.widget.TextView artist=(android.widget.TextView)found;
            if(artistColour==0) artistColour=artist.getCurrentTextColor();
            int wanted=Settings.get(Settings.ART_THEME)?Tint.player(0xffbcb3dc):artistColour;
            if(wanted!=artist.getCurrentTextColor()) artist.setTextColor(wanted);
        }
        // The footer card and the controls tray are tinted in the layout; Tint.player gives those same colours when off.
        surface(getRootView().findViewById(id("player_overlay_footer")), Tint.player(0xeb1d1a2a));
        surface(getRootView().findViewById(ids[1]), Tint.player(0xff2b2640));
        View play=getRootView().findViewById(ids[4]);
        if(play!=null && Settings.get(Settings.ART_THEME) && Tint.fading()) play.invalidate(); // its scallop takes the new colour as it fades
        // Spotify's like check and device label pick their accent by state, so they're recoloured as drawn rather than set.
        int from=Palette.color(0xffd0bcff), to=Tint.player(0xffd0bcff);
        accentRemap(getRootView().findViewById(id("feedback_buttons_container")), from, to);
        accentRemap(getRootView().findViewById(id("connect_entry_point_container")), from, to);
        // Below the player: the page and Spotify's scroll cards (Up Next, lyrics, artist...) on the cover's ground and card colours.
        View cards=getRootView().findViewById(id("widgets_container"));
        // Just the player: Spotify's cards under it are hidden, and only shown again if this hid them.
        if(cards!=null){
            boolean hide=Settings.get(Settings.PLAYER_ONLY);
            if(hide && cards.getVisibility()!=8){ cards.setVisibility(8); cardsHidden=true; }
            else if(!hide && cardsHidden){ cards.setVisibility(0); cardsHidden=false; }
        }
        if(cards!=null){
            View page=cards.getParent() instanceof View ? (View)((View)cards.getParent()).getParent() : null;
            if(!(page!=null && page.getBackground() instanceof Atmosphere)) retint(page, Tint.player(0xff14121c));
            if(cards instanceof ViewGroup){ ViewGroup g=(ViewGroup)cards; int card=Tint.player(0xff2b2640);
                for(int i=0;i<g.getChildCount();i++){ View c=g.getChildAt(i); retint(cardOf(c,2), card); panels(c, card, 4); } }
        }
        View sticky=getRootView().findViewById(id("revised_template_sticky_header"));
        retint(cardOf(sticky,1), Tint.player(0xff1d1a2a));
        accentRemap(sticky, from, to); // its like check
        if(sticky!=null && Settings.get(Settings.ART_THEME) && Tint.fading()) redraw(sticky, 3); // its play button picks the new colour as it fades
    }

    private static final java.util.WeakHashMap<View,Boolean> retinted=new java.util.WeakHashMap<View,Boolean>();

    /** Solid backgrounds only, and undone when Artwork colours goes off, so Spotify's own colours come back. */
    private static void retint(View v, int colour) {
        if(v==null) return;
        if(!Settings.get(Settings.ART_THEME)){ if(retinted.remove(v)!=null) v.setBackgroundTintList(null); return; }
        surface(v, colour);
        retinted.put(v, Boolean.TRUE);
    }

    private static void redraw(View v, int depth) {
        v.invalidate();
        if(depth>0 && v instanceof ViewGroup){ ViewGroup g=(ViewGroup)v; for(int i=0;i<g.getChildCount();i++) redraw(g.getChildAt(i), depth-1); }
    }

    /** Grey or theme-surface panels inside a card (About the artist's lower half and kin); images and colourful fills are left. */
    private static void panels(View v, int card, int depth) {
        if(v==null) return;
        Drawable bg=v.getBackground();
        if(bg instanceof android.graphics.drawable.ColorDrawable && (retinted.containsKey(v) || panel(((android.graphics.drawable.ColorDrawable)bg).getColor()))) retint(v, card);
        if(depth>0 && v instanceof ViewGroup){ ViewGroup g=(ViewGroup)v; for(int i=0;i<g.getChildCount();i++) panels(g.getChildAt(i), card, depth-1); }
    }

    static boolean panel(int argb) {
        if((argb>>>24)<0x80) return false;
        int role=Palette.role(argb);
        if(role==1 || role==2) return true;
        int max=Math.max(argb>>16&255,Math.max(argb>>8&255,argb&255));
        return Surfaces.chroma(argb)<20 && max>0x18 && max<0x70;
    }

    /** The card's own view: the child itself or a wrapper's first child with a plain colour or shape background. */
    private static View cardOf(View v, int depth) {
        if(v==null) return null;
        Drawable bg=v.getBackground();
        if(bg instanceof android.graphics.drawable.ColorDrawable || bg instanceof android.graphics.drawable.GradientDrawable) return v;
        if(depth>0 && v instanceof ViewGroup && ((ViewGroup)v).getChildCount()>0) return cardOf(((ViewGroup)v).getChildAt(0), depth-1);
        return null;
    }

    private static final java.util.WeakHashMap<View,Integer> remapped=new java.util.WeakHashMap<View,Integer>();

    private static void accentRemap(View v, int from, int to) {
        if(v==null) return;
        Integer had=remapped.get(v);
        float[] m=Settings.get(Settings.ART_THEME) ? remapMatrix(from,to) : null;
        if(m==null){ if(had!=null){ v.setLayerType(View.LAYER_TYPE_NONE,null); remapped.remove(v);} return; }
        if(had!=null && had==to) return;
        Paint layer=new Paint(0);
        layer.setColorFilter(new android.graphics.ColorMatrixColorFilter(m));
        v.setLayerType(View.LAYER_TYPE_HARDWARE,layer);
        remapped.put(v,to);
    }

    /** A linear RGB map sending from to to while every grey (white, black, Spotify's greys) stays as it is; null when from is itself grey. */
    static float[] remapMatrix(int from, int to) {
        float[] a={(from>>16&255)/255f,(from>>8&255)/255f,(from&255)/255f}, b={(to>>16&255)/255f,(to>>8&255)/255f,(to&255)/255f};
        float[] p={a[2]-a[1],a[0]-a[2],a[1]-a[0]}; // (1,1,1) x a: off the grey axis and off a, kept fixed
        float[][] src={{1,a[0],p[0]},{1,a[1],p[1]},{1,a[2],p[2]}}, dst={{1,b[0],p[0]},{1,b[1],p[1]},{1,b[2],p[2]}};
        float det=src[0][0]*(src[1][1]*src[2][2]-src[1][2]*src[2][1])-src[0][1]*(src[1][0]*src[2][2]-src[1][2]*src[2][0])+src[0][2]*(src[1][0]*src[2][1]-src[1][1]*src[2][0]);
        if(Math.abs(det)<1e-4f) return null;
        float[][] inv=new float[3][3];
        for(int r=0;r<3;r++)for(int c=0;c<3;c++){
            int r1=(c+1)%3,r2=(c+2)%3,c1=(r+1)%3,c2=(r+2)%3;
            inv[r][c]=(src[r1][c1]*src[r2][c2]-src[r1][c2]*src[r2][c1])/det;
        }
        float[] m=new float[20];
        for(int r=0;r<3;r++)for(int c=0;c<3;c++){float sum=0;for(int k=0;k<3;k++)sum+=dst[r][k]*inv[k][c];m[r*5+c]=sum;}
        m[18]=1;
        return m;
    }

    private static void surface(View v, int colour) {
        if(v==null) return;
        android.content.res.ColorStateList now=v.getBackgroundTintList();
        if(now==null || now.getDefaultColor()!=colour) v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colour));
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

    static final float PRESSED = 0.94f, ARRIVE_MILLIS = 420f, BEAT = 0.02f;
    /** Ring push at level 1, in radii, and the Bold stroke: both must fit the view's margin at the loudest level. */
    static final float AUDIO_REACH = 0.035f, BOLD = 1.8f;

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

    boolean scratching() {return motion.scratching;}

    boolean isCurrentPage() {
        if(preview || getWidth()<=0 || !isShown()) return false;
        getLocationOnScreen(loc);
        float centre=loc[0]+getWidth()/2f;
        return Math.abs(centre-getRootView().getWidth()/2f) < getWidth()/4f;
    }

    /** The beat bounce and tilt carry the ring past the view's edge into the screen margin, so nothing up to the full-width page may clip it. */
    static void unclip(View art, int screenWidth) {
        for(android.view.ViewParent p=art.getParent(); p instanceof ViewGroup; p=p.getParent()) {
            ViewGroup group=(ViewGroup)p;
            group.setClipChildren(false);
            group.setClipToPadding(false);
            if(group.getWidth()>=screenWidth) return;
        }
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
                if(kind!=0 && !(v.getBackground() instanceof Transport)) {
                    v.setBackground(new Transport(v,kind,v.getBackground()));
                    if(kind==3||kind==-3) v.setOnTouchListener(new HoldSeek(v,kind==3));
                }
            }
        }
    }
}
