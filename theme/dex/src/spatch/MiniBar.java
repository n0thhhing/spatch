package spatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;
import androidx.constraintlayout.motion.widget.MotionLayout;
import java.lang.ref.WeakReference;

/**
 * The mini-player root (layout/now_playing_bar). m1p0 and tro0 tint its background from artwork;
 * MiniSurface ignores that, so the theme surface stays. classes9 is full, so this is a view subclass
 * rather than DEX edits. The session worker calls update(); bindings run on the main thread.
 */
public final class MiniBar extends MotionLayout implements Runnable {
    private static WeakReference<MiniBar> bar;
    private static Bitmap accentSource;
    private static int accent;
    private static final long EXPAND_WINDOW = 1800; // the player can take a while to open on a track with lyrics
    private static float[] expandFrom;
    private static long expandAt;
    private MiniSkip previous, next;
    private MiniEq eq;
    private View shapedCover;
    private Springy coverSpring;
    private volatile boolean playing;
    private volatile long actions = 48;

    public MiniBar(Context c, AttributeSet a) {
        super(c, a);
    }

    protected void onFinishInflate() {
        super.onFinishInflate();
        setBackground(new MiniSurface(Palette.color(0xff2b2640), 28 * getResources().getDisplayMetrics().density));
        for (int i = 0; i < getChildCount(); i++) adopt(getChildAt(i));
        bar = new WeakReference<MiniBar>(this);
        SpatchWidget.refreshMini();
    }

    public void onViewAdded(View child) {
        super.onViewAdded(child);
        adopt(child);
    }

    private void adopt(View child) {
        if (child instanceof MiniSkip) {
            if ("next".equals(child.getTag())) next = (MiniSkip) child;
            else previous = (MiniSkip) child;
        } else if (child instanceof MiniEq) {
            eq = (MiniEq) child;
        } else if (child.getId() == id("progress_bar")) {
            ((ProgressBar) child).setProgressDrawable(new MiniRing(Palette.color(0xffd0bcff), getResources().getDisplayMetrics().density));
        } else if (child.getId() == id("connect_destination_button")) {
            // Device picking stays in the full player: no pixels, touch or focus here.
            child.setAlpha(0f);
            child.setEnabled(false);
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    private int id(String name) {
        return getResources().getIdentifier(name, "id", getContext().getPackageName());
    }

    /** A tap on the artwork or metadata opens the player; remember where the cover was. */
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (coverSpring != null && (e.getActionMasked() != MotionEvent.ACTION_DOWN || previous != null && e.getX() < findViewById(id("tracks_carousel_view")).getRight())) coverSpring.onTouch(shapedCover, e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP && previous != null && e.getX() < findViewById(id("tracks_carousel_view")).getRight()) {
            View cover = findViewById(id("cover_image"));
            if (cover != null && cover.getWidth() > 0) {
                int[] at = new int[2];
                cover.getLocationOnScreen(at);
                expandFrom = new float[] {at[0] + cover.getWidth() / 2f, at[1] + cover.getHeight() / 2f, cover.getWidth()};
                expandAt = SystemClock.uptimeMillis();
            }
        }
        return super.dispatchTouchEvent(e);
    }

    /** One flight per tap, and only if the player really opened right after it. */
    static float[] claimExpansion() {
        float[] from = expandFrom;
        expandFrom = null;
        return from != null && SystemClock.uptimeMillis() - expandAt < EXPAND_WINDOW && Wave.animationsEnabled() ? from : null;
    }

    /** m1p0 swaps the cover view after inflation; a circle sits concentric in the pill's end. */
    private void roundCover() {
        View cover = findViewById(id("cover_image"));
        if (cover == null || cover == shapedCover) return;
        shapedCover = cover;
        coverSpring = new Springy(cover);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Palette.color(0xff1d1a2a));
        cover.setBackground(circle);
        cover.setClipToOutline(true);
    }

    public void run() {
        roundCover();
        if (previous != null) previous.allow(actions);
        if (next != null) next.allow(actions);
        ClickHaptic.attach(findViewById(id("play_pause_button")));
        if (eq != null) eq.show(playing, accent != 0 ? accent : Palette.color(0xffd0bcff));
    }

    /** Session worker: one accent per artwork bitmap, derived off the draw path. */
    static void update(MediaMetadata m, PlaybackState s, Bitmap art) {
        MiniBar view = bar == null ? null : bar.get();
        if (view == null) return;
        if (art != accentSource) {
            accentSource = art;
            accent = art == null ? 0 : accentOf(art);
        }
        int state = s == null ? 0 : s.getState();
        view.playing = state == PlaybackState.STATE_PLAYING;
        view.actions = s == null ? 0 : s.getActions();
        view.post(view);
    }

    private static int accentOf(Bitmap art) {
        try {
            Bitmap tiny = Bitmap.createScaledBitmap(art, 16, 16, true);
            int[] pixels = new int[256];
            tiny.getPixels(pixels, 0, 16, 0, 0, 16, 16);
            return MiniEq.accent(pixels, Palette.color(0xff2b2640), Palette.color(0xffd0bcff));
        } catch (RuntimeException e) {
            return 0; // a recycled cover: the theme accent stands in
        }
    }

    static MiniBar current() {
        return bar == null ? null : bar.get();
    }

    static void settingsChanged() {
        MiniBar view = bar == null ? null : bar.get();
        if (view != null) view.run();
    }
}
