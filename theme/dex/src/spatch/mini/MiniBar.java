package spatch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
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
    private static volatile int album;
    private int lastAlbum;
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
        Integer known = IDS.get(name); // run() asks every update: getIdentifier was a measured cost
        if (known != null) return known.intValue();
        int found = getResources().getIdentifier(name, "id", getContext().getPackageName());
        IDS.put(name, Integer.valueOf(found));
        return found;
    }

    /** A tap on the artwork or metadata opens the player; remember where the cover was. */
    /** Held on the cover or title, the mini player likes the song instead of opening. */
    private final Runnable holdLike = new Runnable() {
        public void run() {
            View like = find(MiniBar.this);
            if (like == null) return;
            liked = true;
            like.performClick();
            Haptics.saved(like);
        }

        private View find(View view) {
            if (view.getClass().getName().endsWith("AddToButtonView")) return view;
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View inner = find(group.getChildAt(i));
                    if (inner != null) return inner;
                }
            }
            return null;
        }
    };
    private boolean liked, downOnCover, flinging;
    private static final java.util.HashMap<String, Integer> IDS = new java.util.HashMap<String, Integer>();
    private float pressX, pressY, flingBaseDeg;

    static boolean flingCommits(float dx, float dp, long actions) {
        return Math.abs(dx) >= 28 * dp && (actions & (dx < 0 ? 32L : 16L)) != 0;
    }

    private boolean onCover(float x) {
        if (shapedCover == null) roundCover();
        float boundary;
        if (previous != null && previous.getLeft() > 0) {
            boundary = previous.getLeft() - 4 * getResources().getDisplayMetrics().density;
        } else {
            View carousel = findViewById(id("tracks_carousel_view"));
            if (carousel != null && carousel.getRight() > 0) boundary = carousel.getRight();
            else boundary = getWidth() > 0 ? getWidth() - 144 * getResources().getDisplayMetrics().density : 96 * getResources().getDisplayMetrics().density;
        }
        return x >= -16 * getResources().getDisplayMetrics().density && x <= boundary;
    }

    private void skip(boolean toNext) {
        PlayerSwipe.skip(this, toNext);
        MiniSkip btn = toNext ? next : previous;
        if (btn != null && Wave.animationsEnabled() && Settings.buttons()) {
            float nudge = (toNext ? 3 : -3) * getResources().getDisplayMetrics().density;
            btn.setTranslationX(nudge);
            btn.animate().translationX(0).setDuration(200).start();
        }
    }

    private void flingCover(float dx, float deg) {
        if (shapedCover instanceof MiniArt) {
            ((MiniArt) shapedCover).fling(dx, deg);
        } else if (shapedCover != null) {
            int[] at = new int[2];
            shapedCover.getLocationOnScreen(at);
            float dp = getResources().getDisplayMetrics().density;
            float size = Math.max(shapedCover.getWidth(), 36 * dp);
            android.graphics.drawable.Drawable art = shapedCover instanceof android.widget.ImageView ? ((android.widget.ImageView) shapedCover).getDrawable() : null;
            MiniArt.flingAt(shapedCover, art, at[0] + shapedCover.getWidth() / 2f, at[1] + shapedCover.getHeight() / 2f, size, dx, deg);
        }
    }

    private boolean likeTouch(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            liked = false;
            pressX = e.getX();
            pressY = e.getY();
            roundCover();
            downOnCover = onCover(pressX);
            flinging = false;
            flingBaseDeg = shapedCover != null ? shapedCover.getRotation() : 0;
            if (Settings.get(Settings.MINI_HOLD_LIKE) && previous != null && pressX < previous.getLeft()) postDelayed(holdLike, 450);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 24) removeCallbacks(holdLike);
        } else {
            removeCallbacks(holdLike);
            if (liked && action == MotionEvent.ACTION_UP) {
                e.setAction(MotionEvent.ACTION_CANCEL); // that hold wasn't a tap
                super.dispatchTouchEvent(e);
                return true;
            }
        }
        return false;
    }

    public boolean dispatchTouchEvent(MotionEvent e) {
        if (likeTouch(e)) return true;
        int action = e.getActionMasked();
        float dx = e.getX() - pressX, dy = e.getY() - pressY, dp = getResources().getDisplayMetrics().density;
        if (action == MotionEvent.ACTION_MOVE && !flinging && downOnCover && shapedCover != null
                && Settings.get(Settings.MINI_FLING) && Wave.animationsEnabled() && Math.abs(dx) > 12 * dp && Math.abs(dx) > Math.abs(dy)) {
            flinging = true;
            removeCallbacks(holdLike);
            requestDisallowInterceptTouchEvent(true);
            android.view.ViewParent p = getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(true);
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            if (coverSpring != null) coverSpring.onTouch(shapedCover, cancel);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        if (flinging) {
            if (action == MotionEvent.ACTION_MOVE) {
                shapedCover.setPivotX(shapedCover.getWidth() / 2f);
                shapedCover.setPivotY(shapedCover.getHeight() / 2f);
                shapedCover.setTranslationX(dx);
                shapedCover.setRotation(flingBaseDeg + dx / dp * 2.4f);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                flinging = false;
                float deg = shapedCover.getRotation();
                shapedCover.setTranslationX(0);
                if (action == MotionEvent.ACTION_UP && flingCommits(dx, dp, actions)) {
                    flingCover(dx, deg);
                    skip(dx < 0);
                }
            }
            return true;
        }
        View carousel = findViewById(id("tracks_carousel_view"));
        int right = carousel != null ? carousel.getRight() : 0;
        if (coverSpring != null && (action != MotionEvent.ACTION_DOWN || previous != null && e.getX() < right)) coverSpring.onTouch(shapedCover, e);
        if (action == MotionEvent.ACTION_UP && previous != null && e.getX() < right) {
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
        if (cover == null) cover = findCover(this);
        if (cover == null || cover == shapedCover) return;
        shapedCover = cover;
        coverSpring = new Springy(cover);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Palette.color(0xff1d1a2a));
        cover.setBackground(circle);
        cover.setClipToOutline(true);
        setClipChildren(false);
        setClipToPadding(false);
        if (cover.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup box = (android.view.ViewGroup) cover.getParent();
            box.setClipChildren(false);
            box.setClipToPadding(false);
        }
    }

    private View findCover(View v) {
        if (v == null) return null;
        if (v instanceof MiniArt) return v;
        if (v instanceof android.widget.ImageView && v != previous && v != next) {
            CharSequence desc = v.getContentDescription();
            if (desc != null && desc.toString().toLowerCase(java.util.Locale.ROOT).contains("cover")) return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findCover(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Paced by the display's frame time: the clock at callback time wobbles with whatever else the main thread is doing. */
    private final Choreographer.FrameCallback turn = new Choreographer.FrameCallback() {
        public void doFrame(long frame) {
            spinning = playing && Settings.get(Settings.MINI_SPIN) && Wave.animationsEnabled() && isShown() && shapedCover != null;
            if (!spinning) {
                if (shapedCover != null && !flinging && !Settings.get(Settings.MINI_SPIN)) shapedCover.setRotation(0);
                return;
            }
            if (!flinging) {
                shapedCover.setPivotX(shapedCover.getWidth() / 2f); // Spotify leaves it off-centre, which swings the cover out of the bar
                shapedCover.setPivotY(shapedCover.getHeight() / 2f);
                shapedCover.setRotation((shapedCover.getRotation() + Math.min(100f, (frame - turnedAt) / 1e6f) * SPIN) % 360);
            }
            turnedAt = frame;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private boolean spinning;
    private long turnedAt;
    private static final float SPIN = 360f / 9000; // degrees per millisecond

    public void run() {
        if (album != 0 && album != lastAlbum) {
            lastAlbum = album;
            Tint.follow(lastAlbum);
        }
        if (getBackground() != null) getBackground().invalidateSelf();
        roundCover();
        if (!spinning) {
            spinning = true;
            turnedAt = System.nanoTime();
            Choreographer.getInstance().postFrameCallback(turn);
        }
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
        long reported = s == null ? 0 : s.getActions();
        view.actions = (reported & 48L) != 0 ? reported : 48L;
        view.post(view);
    }

    private static int accentOf(Bitmap art) {
        try {
            Bitmap tiny = Bitmap.createScaledBitmap(art, 16, 16, true);
            int[] pixels = new int[256];
            tiny.getPixels(pixels, 0, 16, 0, 0, 16, 16);
            album = Tint.vivid(pixels);
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
