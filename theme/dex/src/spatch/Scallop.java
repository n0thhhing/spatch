package spatch;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import android.view.View;

/**
 * Expressive play/pause background: a circle whose edge is modulated into rounded bumps
 * (5 while paused, 9 while playing). Called from the patched p.lbg.draw() in place of drawCircle.
 */
public final class Scallop implements Runnable {
    private static final int ACCENT = 0xd0bcff; // theme accent token, substituted at patch time
    private static final int STEPS = 180;

    private static final WeakHashMap<Object,Scallop> STATES=new WeakHashMap<Object,Scallop>();
    private WeakReference<Drawable> owner;
    private float mix, from, target;
    private long changedAt;
    private boolean wasPressed;
    private long bounceAt;
    private long lastFrame;
    private double spin;
    private Scallop(float initial) {mix=from=target=initial;}
    public void run(){Drawable d=owner==null?null:owner.get();if(d!=null)d.invalidateSelf();}

    /** Canvas is already translated to the centre; r is the circle radius lbg would have drawn. */
    public static void draw(Canvas canvas, float r, Paint source, int bumps, Drawable drawable) {
        // Play and pause are separate lbg instances but share the same ImageButton callback.
        // Key by that callback so switching drawable retains the current outline, including
        // rapid toggles. Weak keys and a weak drawable reference avoid retaining the player.
        Object key=drawable.getCallback();
        if(key==null)key=drawable;
        float desired=bumps==9?1:0;
        Scallop state=STATES.get(key);
        if(state==null){state=new Scallop(desired);STATES.put(key,state);}
        state.owner=new WeakReference<Drawable>(drawable);
        long now=SystemClock.uptimeMillis();
        // Integrate only playing time: pause freezes the current angle and resume
        // continues it. Rotate the contour coordinates, never the play/pause glyph.
        if(state.lastFrame!=0 && desired==1 && state.target==1 && Wave.animationsEnabled())
            state.spin=(state.spin+Math.min(80,now-state.lastFrame)*Math.PI*2/12000)% (Math.PI*2);
        state.lastFrame=now;
        boolean pressed=key instanceof View && ((View)key).isPressed();
        if((state.wasPressed && !pressed) || state.target!=desired)state.bounceAt=now;
        state.wasPressed=pressed;
        float bt=Math.min(1f,(now-state.bounceAt)/500f);
        float bounce=pressed?.90f:bt<1?1+.14f*(float)(Math.exp(-6*bt)*Math.sin(3*Math.PI*bt))-.10f*(float)Math.exp(-15*bt):1;
        if(!Wave.animationsEnabled())bounce=1;
        // lbg restores its canvas at the end of draw(), so this scales the outline AND
        // the original play/pause glyph drawn immediately after this helper returns.
        canvas.scale(bounce,bounce,0,0);
        if(state.target!=desired){state.from=state.mix;state.target=desired;state.changedAt=now;}
        float progress=Math.min(1f,(now-state.changedAt)/440f);
        if(!Wave.animationsEnabled())progress=1;
        float eased=progress*progress*(3-2*progress);
        state.mix=state.from+(state.target-state.from)*eased;
        r*=1-.035f*(float)Math.sin(progress*Math.PI);
        float depth = r * 0.075f;
        float base = r - depth;
        Path path = new Path();
        for (int i = 0; i <= STEPS; i++) {
            double t = 2 * Math.PI * i / STEPS;
            // Blend two periodic contours, rather than a fractional bump count with a seam.
            float wave=(1-state.mix)*(float)Math.cos(5*t)+state.mix*(float)Math.cos(9*t);
            float rad = base + depth * wave;
            // sin/-cos puts the first bump at 12 o'clock.
            float x = rad * (float) Math.sin(t+state.spin);
            float y = -rad * (float) Math.cos(t+state.spin);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        Paint paint = new Paint(source);
        // Keep lbg's state alpha (pressed 70%, disabled 50%) and swap only the colour.
        paint.setColor((source.getAlpha() << 24) | ACCENT);
        canvas.drawPath(path, paint);
        if((desired==1 || progress<1 || bt<1) && Wave.animationsEnabled() && drawable.isVisible())drawable.scheduleSelf(state,now+16);
    }
}
