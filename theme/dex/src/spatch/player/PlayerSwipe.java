package spatch;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/** Decide the axis before the outer player can turn a sideways swipe into a dismiss. */
public final class PlayerSwipe {
    private static float x, y, width, dp;
    private static long down;
    private static boolean candidate, horizontal, forwarding;
    private static final int[] at = new int[2];

    static boolean committed(float distance, long elapsed, float width, float dp) {
        distance=Math.abs(distance);
        return distance>=Math.max(36*dp,width*.12f)
            || distance>=20*dp && distance*1000/Math.max(1,elapsed)>=250*dp;
    }

    public static boolean touch(View host, MotionEvent event) {
        if (forwarding) return false;
        int action=event.getActionMasked();
        ViewParent parent=host.getParent();
        if (action==MotionEvent.ACTION_DOWN) {
            candidate=horizontal=false;
            if (!Settings.get(Settings.IMM_SWIPE) || SpatchWidget.adPlaying) return false;
            PlayerArt art=PlayerArt.currentPage(host);
            if (art==null || !art.isShown()) return false;
            dp=host.getResources().getDisplayMetrics().density;
            art.getLocationOnScreen(at);
            x=event.getRawX();y=event.getRawY();width=art.getWidth();down=event.getEventTime();
            candidate=y>=at[1]-24*dp && y<=at[1]+art.getHeight()+32*dp;
            if (candidate && parent!=null) parent.requestDisallowInterceptTouchEvent(true);
            return false;
        }
        if (!candidate) return false;
        float dx=event.getRawX()-x,dy=event.getRawY()-y;
        if (action==MotionEvent.ACTION_MOVE && !horizontal) {
            PlayerArt art=PlayerArt.currentPage(host);
            if (art!=null && art.scratching()) {candidate=false;return false;}
            if (Math.abs(dy)>8*dp && Math.abs(dy)>Math.abs(dx)*1.4f) {
                candidate=false;
                if (parent!=null) parent.requestDisallowInterceptTouchEvent(false);
                return false;
            }
            if (Math.abs(dx)>=8*dp && Math.abs(dx)>=Math.abs(dy)*.75f) {
                horizontal=true;
                MotionEvent cancel=MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                forwarding=true;
                try {host.dispatchTouchEvent(cancel);} finally {forwarding=false;cancel.recycle();}
            }
        }
        boolean handled=horizontal;
        if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
            candidate=horizontal=false;
            if (parent!=null) parent.requestDisallowInterceptTouchEvent(false);
            if (handled && action==MotionEvent.ACTION_UP && committed(dx,event.getEventTime()-down,width,dp)) {
                View controls=host.getRootView().findViewById(R_id_playback_controls_container);
                View button=controls==null ? null : controls.findViewById(dx<0 ? R_id_next_button : R_id_previous_button);
                if (button!=null && button.isEnabled()) {Haptics.playback(host);ClickHaptic.forward(button);}
            }
        }
        return handled;
    }
}
