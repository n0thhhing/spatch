package spatch;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.os.SystemClock;

/** Decorative state-aware shape behind Spotify's unchanged transport icon and hit target. */
public final class Transport extends Drawable implements Runnable {
    private final View owner;
    private final int bumps;
    private final Drawable original;
    private final Paint paint=new Paint(3);
    private final Path path=new Path();
    private float presence, from, target;
    private long transition;
    private boolean wasPressed;
    private long bounceAt;
    public Transport(View v,int n,Drawable d){owner=v;bumps=n;original=d;}
    /**
     * Previous/next, matching Swiftify's TransportButton (measured from its source, not copied):
     * while held, a light rounded arrow pointing the skip way fades in over 140 ms and grows from
     * 0.35x with a springy ease; on release the icon and arrow nudge ~4dp that way (90 ms out-cubic)
     * and return over 350 ms with a slight overshoot. The button itself never scales.
     */
    boolean isSkip(){return Math.abs(bumps)==3;}
    /** Shuffle (4) and repeat (6) are the only buttons that bounce (see TapBounce). */
    boolean bounces(){return Math.abs(bumps)==4||Math.abs(bumps)==6;}
    /** Last release (click) time, seen via drawable state even for taps shorter than a frame. */
    long releasedAt(){return bounceAt;}
    private boolean showing;
    private long showChangedAt, nudgeAt;
    private float opFrom, scFrom, opacity, shapeScale=.35f;
    private void drawSkip(Canvas c, Rect b, float cx, float cy) {
        long now=SystemClock.uptimeMillis();
        boolean motion=Settings.buttons();
        boolean pressed=owner.isEnabled() && owner.isPressed();
        if(wasPressed && !pressed)nudgeAt=now;
        wasPressed=pressed;
        int dir=bumps<0?-1:1;
        long sinceNudge=now-nudgeAt;
        boolean nudging=motion && nudgeAt!=0 && sinceNudge<440;
        boolean show=pressed||nudging;
        if(show!=showing){showing=show;showChangedAt=now;opFrom=opacity;scFrom=shapeScale;}
        float to=show?1f:0f, scTo=show?1f:.35f;
        if(motion){
            float to1=Math.min(1f,(now-showChangedAt)/140f), ts=Math.min(1f,(now-showChangedAt)/350f);
            opacity=opFrom+(to-opFrom)*outCubic(to1);
            shapeScale=scFrom+(scTo-scFrom)*bezier(.38f,1.21f,.22f,1f,ts);
        }else{opacity=to;shapeScale=scTo;}
        float unit=b.width()/12f; // ~4dp on the 48dp button
        float dx=0;
        if(nudging){
            dx=sinceNudge<90?dir*unit*outCubic(sinceNudge/90f)
                :dir*unit*(1-bezier(.42f,1.67f,.21f,.90f,(sinceNudge-90)/350f));
        }
        // Spotify sets these icons as the ImageButton's image, not its background, so move the whole
        // view: icon and indicator then travel together.
        if(owner.getTranslationX()!=dx)owner.setTranslationX(dx);
        int save=c.save();
        if(opacity>.001f){
            float radius=(Math.min(b.width(),b.height())/2f+unit)*.9f*shapeScale;
            path.reset();
            for(int i=0;i<=120;i++){
                double a=i*Math.PI/60;
                float r=radius*(.90f+.10f*(float)Math.cos(3*a)); // soft convex rounded triangle
                float x=cx+dir*(float)Math.cos(a)*r,y=cy+(float)Math.sin(a)*r;
                if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
            }
            path.close();
            paint.setColor(0xffffffff);
            paint.setAlpha((int)((pressed?44:36)*opacity));
            c.drawPath(path,paint);
        }
        if(original!=null){original.setBounds(b.left,b.top,b.right,b.bottom);original.draw(c);}
        c.restoreToCount(save);
        boolean animating=motion && (now-showChangedAt<350 || nudging);
        if(animating && isVisible()) scheduleSelf(this,now+16);
    }
    static float outCubic(float t){t=Math.max(0f,Math.min(1f,t));float u=1-t;return 1-u*u*u;}
    /** CSS-style cubic-bezier(x1,y1,x2,y2) evaluated at progress x (0..1); y may overshoot 1. */
    static float bezier(float x1,float y1,float x2,float y2,float x){
        x=Math.max(0f,Math.min(1f,x));
        float lo=0f,hi=1f,t=x;
        for(int i=0;i<20;i++){
            t=(lo+hi)/2f;
            float bx=3*(1-t)*(1-t)*t*x1+3*(1-t)*t*t*x2+t*t*t;
            if(bx<x)lo=t;else hi=t;
        }
        return 3*(1-t)*(1-t)*t*y1+3*(1-t)*t*t*y2+t*t*t;
    }
    public void draw(Canvas c) {
        Rect b=getBounds();
        float cx=(b.left+b.right)/2f,cy=(b.top+b.bottom)/2f;
        if(isSkip()){drawSkip(c,b,cx,cy);return;}
        float radius=Math.min(b.width(),b.height())*.62f;
        // Swiftify's decorative surfaces are interaction feedback, not permanent accent fills.
        boolean interacting=owner.isEnabled() && (owner.isPressed()||owner.isHovered());
        long now=SystemClock.uptimeMillis();
        boolean pressed=owner.isEnabled() && owner.isPressed();
        if(wasPressed && !pressed)bounceAt=now;
        wasPressed=pressed;
        float bt=Math.min(1f,(now-bounceAt)/500f);
        float bounce=pressed?.90f:bt<1?1+.14f*(float)(Math.exp(-6*bt)*Math.sin(3*Math.PI*bt))-.10f*(float)Math.exp(-15*bt):1;
        if(!Settings.buttons())bounce=1;
        // TapBounce now scales the entire native button, including src icons.
        float desired=interacting?1f:0f;
        if(desired!=target){from=presence;target=desired;transition=now;}
        float t=Math.min(1f,(now-transition)/350f);
        if(!Settings.buttons()) t=1f;
        float ease=target>from?(float)(1-Math.exp(-7*t)*Math.cos(10*t)):1-(1-t)*(1-t)*(1-t);
        presence=t>=1?target:from+(target-from)*ease;
        radius*=.35f+.65f*Math.max(0,presence);
        paint.setColor(owner.isPressed()?0x40ffffff:0x24ffffff);
        paint.setAlpha((int)((owner.isPressed()?64:36)*Math.max(0,Math.min(1,presence))));
        float strength=owner.isPressed()?.20f:.12f;
        path.reset();
        for(int i=0;i<=120;i++) {
            double a=i*Math.PI/60;
            float r=radius*(1-strength+strength*(float)Math.cos(a*bumps));
            // Negative shape IDs mirror only the decorative surface, never the stock icon.
            float x=cx+(bumps<0?-1:1)*(float)Math.cos(a)*r,y=cy+(float)Math.sin(a)*r;
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        path.close();
        if(presence>.001f) c.drawPath(path,paint);
        // Spotify supplies these icons as the background, not ImageView.src.
        if(original!=null) {
            original.setBounds(b.left,b.top,b.right,b.bottom);
            int save=c.save();
            if(Math.abs(bumps)==3 && Settings.buttons()) {
                float nudge=pressed?.035f:bt<1?.065f*(float)(Math.exp(-5*bt)*Math.sin(Math.PI*bt)):0;
                c.translate((bumps<0?-1:1)*b.width()*nudge,0);
            }
            original.draw(c);
            c.restoreToCount(save);
        }
        if((t<1 || bt<1) && Settings.buttons() && isVisible()) scheduleSelf(this,now+16);
    }
    public void run(){invalidateSelf();}
    protected boolean onStateChange(int[] states){
        // Release is often seen here first (state change), before draw(); start the skip nudge here too.
        if(wasPressed && !owner.isPressed()){bounceAt=SystemClock.uptimeMillis();nudgeAt=bounceAt;}
        wasPressed=owner.isPressed();
        if(original!=null)original.setState(states);
        invalidateSelf();return true;
    }
    public boolean isStateful(){return true;}
    public void setAlpha(int a){paint.setAlpha(a);}
    public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
