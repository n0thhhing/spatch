package spatch;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** Samples native pressed state without replacing any touch/click listeners. Player-scoped. */
public final class TapBounce {
    private TapBounce(){}
    public static void tick(View view){
        if(!view.isShown())return;
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            // Some player actions attach their click listener to a small wrapper,
            // rather than its icon. Animate that target once, not both parent and child.
            if(group.isClickable() && group.getWidth()>0 &&
               group.getWidth()<=group.getRootView().getWidth()/3 &&
               group.getHeight()<=group.getRootView().getHeight()/6){animate(group);return;}
            for(int i=0;i<group.getChildCount();i++)tick(group.getChildAt(i));
            return;
        }
        animate(view);
    }
    /**
     * Transport bounce: only shuffle and
     * repeat, on click, scale up to 1.15x over 110 ms (out-cubic) and settle to 1 over 400 ms on a
     * slightly overshooting curve. No shrink while pressed; every other player button stays at 1x.
     */
    private static void animate(View view){
        if(!view.isClickable())return;
        Drawable bg=view.getBackground();
        if(isScallop(bg))return;
        boolean bounces=bg instanceof Transport && ((Transport)bg).bounces() && Settings.buttonBounce();
        if(!bounces || !Settings.buttons() || !view.isEnabled()){
            if(view.getScaleX()!=1f){view.setScaleX(1);view.setScaleY(1);}
            return;
        }
        long t=SystemClock.uptimeMillis()-((Transport)bg).releasedAt();
        float scale=t<110?1+.15f*Transport.outCubic(t/110f)
            :t<510?1.15f-.15f*Transport.bezier(.38f,1.21f,.22f,1f,(t-110)/400f):1f;
        view.setScaleX(scale);view.setScaleY(scale);
    }
    private static boolean isScallop(Drawable drawable){
        return drawable!=null && drawable.getClass().getName().equals("p.lbg");
    }
}
