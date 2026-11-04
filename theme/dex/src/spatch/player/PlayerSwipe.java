package spatch;

import android.media.session.MediaController;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/** Decide the axis before the outer player can turn a sideways swipe into a dismiss. */
public final class PlayerSwipe {
    private static float x, y, width, dp;
    private static long down;
    private static boolean candidate, horizontal, forwarding;
    private static final int[] at = new int[2];

    static boolean committed(float distance, long elapsed, float width, float dp) {
        distance=Math.abs(distance);
        return distance>=Math.max(32*dp,width*.12f)
            || distance>=18*dp && distance*1000/Math.max(1,elapsed)>=220*dp;
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
            candidate=y>=at[1]-48*dp && y<=at[1]+art.getHeight()+48*dp;
            if (candidate && parent!=null) parent.requestDisallowInterceptTouchEvent(true);
            return false;
        }
        if (!candidate) return false;
        float dx=event.getRawX()-x,dy=event.getRawY()-y;
        if (action==MotionEvent.ACTION_MOVE && !horizontal) {
            PlayerArt art=PlayerArt.currentPage(host);
            if (art!=null && art.scratching()) {candidate=false;return false;}
            if (Math.abs(dy)>8*dp && Math.abs(dy)>Math.abs(dx)*1.3f) {
                candidate=false;
                if (parent!=null) parent.requestDisallowInterceptTouchEvent(false);
                return false;
            }
            if (Math.abs(dx)>=8*dp && Math.abs(dx)>=Math.abs(dy)*.7f) {
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
                skip(host, dx < 0);
            }
        }
        return handled;
    }

    public static void skip(View host, boolean next) {
        if (host != null) Haptics.playback(host);
        View root = host == null ? null : host.getRootView();
        View button = findButton(root, next);
        if (button == null && host != null && host != root) button = findButton(host, next);
        if (button != null && !(button instanceof MiniSkip) && button.isEnabled()) {
            ClickHaptic.forward(button);
            return;
        }
        MediaController c = SpatchWidget.controller();
        if (c != null) {
            if (next) c.getTransportControls().skipToNext();
            else c.getTransportControls().skipToPrevious();
        }
    }

    private static View findButton(View root, boolean next) {
        if (root == null) return null;
        View controls = root.findViewById(R_id_playback_controls_container);
        if (controls != null) {
            View idBtn = controls.findViewById(next ? R_id_next_button : R_id_previous_button);
            if (idBtn != null) return idBtn;
            View scanned = scan(controls, next, 0);
            if (scanned != null) return scanned;
        }
        View fallbackId = root.findViewById(next ? R_id_next_button : R_id_previous_button);
        if (fallbackId != null) return fallbackId;
        return scan(root, next, 0);
    }

    private static View scan(View v, boolean next, int depth) {
        if (v == null || depth > 8) return null;
        CharSequence desc = v.getContentDescription();
        if (desc != null) {
            String s = desc.toString().toLowerCase(java.util.Locale.ROOT);
            if (next && (s.equals("next") || s.contains("next track") || s.contains("skip forward"))) return v;
            if (!next && (s.contains("previous") || s.contains("skip back") || s.contains("skip backward"))) return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = scan(g.getChildAt(i), next, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }
}
