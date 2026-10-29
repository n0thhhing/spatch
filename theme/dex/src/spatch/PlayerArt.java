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
    private Drawable lastArt;
    private Atmosphere atmosphere;
    private long lastSetup;
    private float glowSize;
    private Shader glow;
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
    private boolean heroHidden;
    private static Drawable original; // controls background before any page's backdrop replaced it
    public PlayerArt(Context c, AttributeSet a) { super(c, a); Settings.load(c); }

    public boolean dispatchTouchEvent(MotionEvent e) {
        float slop = Math.max(36, getWidth() * 0.08f);
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) squishTarget = PRESSED;
        else if (action == MotionEvent.ACTION_UP) {
            release(squish);
            squishTarget = 1;
        } else if (action == MotionEvent.ACTION_CANCEL) { squishTarget = 1; tapAt = -1000; }
        if (action == MotionEvent.ACTION_DOWN) {
            long t = SystemClock.uptimeMillis();
            if (Settings.immersive() && t - tapAt > 40 && t - tapAt < 500 && Math.abs(e.getX() - tapX) < slop * 4 && Math.abs(e.getY() - tapY) < slop * 4) {
                tapAt = -1000;
                release(squish);
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
        if (!heroHidden) {
            if (Settings.roundArt()) drawRound(canvas); else super.onDraw(canvas);
        }
        canvas.restoreToCount(scaled);
        if(background==null || now-lastSetup>750) {
            View root=getRootView();
            int backgroundId=getContext().getResources().getIdentifier("overlay_controls_layout","id",getContext().getPackageName());
            background=root.findViewById(backgroundId);
        }
        // The artwork carousel keeps the previous and next tracks' pages alive off-screen; only the
        // page centred on screen may set the blurred backdrop, or a neighbour's art wins.
        Drawable art=getDrawable();
        if(background!=null && !Settings.backdrop()) {
            if(background.getBackground() instanceof Atmosphere) background.setBackground(original);
        } else if(background!=null && art!=null && isCurrentPage()) {
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
            int controlsId=getContext().getResources().getIdentifier("playback_controls_container","id",getContext().getPackageName());
            View controls=root.findViewById(controlsId);
            if(controls instanceof ViewGroup) decorate((ViewGroup)controls);
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
    }

    private void drawGlow(Canvas canvas) {
        float cx = getWidth()/2f, cy = getHeight()/2f;
        float radius = Math.min(cx,cy)*0.88f;
        if(glow==null || glowSize!=radius) {
            glowSize=radius;
            glow=new RadialGradient(cx,cy,Math.min(cx,cy),new int[]{0x00d0bcff,0x55d0bcff,0x00d0bcff},new float[]{0,.86f,1},Shader.TileMode.CLAMP);
        }
        ink.setStyle(Paint.Style.FILL); ink.setShader(glow);
        canvas.drawRect(0,0,getWidth(),getHeight(),ink);
        ink.setShader(null);
    }

    private void drawRing(Canvas canvas) {
        float cx = getWidth()/2f, cy = getHeight()/2f;
        float radius = Math.min(cx,cy)*0.88f;
        long now = SystemClock.uptimeMillis();
        float phase = Wave.animationsEnabled() ? (now%18000)/18000f*6.283185f : 0;
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(Math.max(2, radius*.012f));
        ink.setColor(0xffd0bcff);
        outline.reset();
        for(int i=0;i<=360;i++) {
            double angle=i*Math.PI/180;
            float r=radius+radius*.045f+(float)Math.sin(angle*24-phase)*radius*.012f;
            float x=cx+(float)Math.cos(angle)*r, y=cy+(float)Math.sin(angle)*r;
            if(i==0) outline.moveTo(x,y); else outline.lineTo(x,y);
        }
        outline.close();
        canvas.drawPath(outline,ink);
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

    static final float PRESSED = 0.94f;

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
