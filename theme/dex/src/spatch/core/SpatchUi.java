package spatch;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Spatch's UI. Android composites these views and our Canvas drawing on the window's GPU. */
public final class SpatchUi extends FrameLayout {
    private final int surface, container, high, onSurface, onVariant, primary, onPrimary;
    private final int secondaryContainer, onSecondaryContainer, tertiaryContainer, onTertiaryContainer;
    private final boolean dark;
    private static final int HOME = 0, LIBRARY = 1, PLAYER = 2, IMMERSIVE = 3, QUEUE = 4, PLAYLIST = 5;
    private static final int PREVIOUS = 2, NEXT = 3, SEEK = 4, SHUFFLE = 5, REPEAT = 6, PLAY = 7;
    private static final String[] SECTIONS = {"Playlists", "Albums", "Artists", "Podcasts"};
    private static final ExecutorService DATA = Executors.newSingleThreadExecutor();
    private static final ExecutorService LIBRARY_DATA = Executors.newSingleThreadExecutor();
    private static final ExecutorService COMMANDS = Executors.newSingleThreadExecutor();
    private static final ExecutorService ART = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getAllocationByteCount(); }
    };
    private final Set<String> loadingImages = new HashSet<>(), failedImages = new HashSet<>();

    private static native int nativeNavigate(int route, int action, int category);
    private static native boolean nativeConnect();
    private static native String[] nativePlayer(long revision);
    private static native String nativeLibrary();
    private static native String[] nativeEntries(int filter, int limit);
    private static native String nativeLiked();
    private static native String[] nativePlaylist(String uri, int offset);
    private static native boolean nativeCommand(int action, long value, String uri);

    private final Activity activity;
    private final float density;
    private int route, screen = HOME, filter = -1;
    private int homeScroll, collectionRow, collectionOffset;
    private long revision, lastConnect, libraryRetry;
    private boolean polling, fetching, libraryReady, seekPending;
    private String libraryStatus = "Connecting to your library…";
    private String title = "", artist = "", album = "", source = "", image = "", uri = "";
    private String albumArtist = "", release = "", albumPosition = "", rating = "";
    private boolean playing, shuffle;
    private Boolean pendingPlayback;
    private int playbackAttempt;
    private int repeat;
    private long position, duration, stamp;
    private List<Entry> upcoming = new ArrayList<>();
    private final List<Art> visibleArt = new ArrayList<>();
    private LinearLayout content;
    private ScrollView scroll;
    private ListView libraryList, playlistList;
    private int playlistRow, playlistTop;
    private TextView playbackSource, infoTrack, infoArtist, infoAlbum, infoAlbumArtist, infoRelease,
        infoPosition, infoRating, infoSource;
    private LinearLayout infoNext;
    private Entry playlist;
    private List<Entry> playlistTracks = new ArrayList<>();
    private int playlistOffset;
    private boolean playlistLoading, playlistFailed;
    private TextView trackTitle, trackArtist, albumTitle, heroTitle, heroArtist;
    private Art trackArt, heroArt, atmosphere;
    private Glyph playButton, heroPlay, shuffleButton, repeatButton;
    private Wave wave;
    private Visualizer visualizer;

    SpatchUi(Activity activity) {
        super(activity);
        this.activity = activity;
        density = getResources().getDisplayMetrics().density;
        dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
        surface = tone("neutral1", dark ? 900 : 10, dark ? 0xff141218 : 0xfffdf8ff);
        container = tone("neutral1", dark ? 800 : 100, dark ? 0xff211f26 : 0xfff0eaf4);
        high = tone("neutral1", dark ? 700 : 200, dark ? 0xff2b2930 : 0xffeae4ee);
        onSurface = tone("neutral1", dark ? 100 : 900, dark ? 0xffe7e0ec : 0xff1d1b20);
        onVariant = tone("neutral2", dark ? 200 : 700, dark ? 0xffcac4d0 : 0xff49454f);
        primary = tone("accent1", dark ? 200 : 600, dark ? 0xffd0bcff : 0xff6750a4);
        onPrimary = tone("accent1", dark ? 800 : 0, dark ? 0xff381e72 : Color.WHITE);
        secondaryContainer = tone("accent2", dark ? 700 : 100, dark ? 0xff4a4458 : 0xffe8def8);
        onSecondaryContainer = tone("accent2", dark ? 100 : 900, dark ? 0xffe8def8 : 0xff1d192b);
        tertiaryContainer = tone("accent3", dark ? 700 : 100, dark ? 0xff633b48 : 0xffffd8e4);
        onTertiaryContainer = tone("accent3", dark ? 100 : 900, dark ? 0xffffd8e4 : 0xff31111d);
        setBackgroundColor(surface);
        setClickable(true);
        setFocusableInTouchMode(true);
        setOnApplyWindowInsetsListener((v, insets) -> {
            setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                       insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        render();
    }

    private int tone(String palette, int shade, int fallback) {
        if (android.os.Build.VERSION.SDK_INT < 31) return fallback;
        int id = getResources().getIdentifier("system_" + palette + "_" + shade, "color", "android");
        return id == 0 ? fallback : activity.getColor(id);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
        android.view.Window window = activity.getWindow();
        window.setStatusBarColor(surface);
        window.setNavigationBarColor(surface);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        View decor = window.getDecorView();
        int bars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int current = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(dark ? current & ~bars : current | bars);
        requestFocus();
        if (getWindowVisibility() == VISIBLE) {
            removeCallbacks(poll);
            post(poll);
        }
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (poll == null) return;
        removeCallbacks(poll);
        if (visibility == VISIBLE && isAttachedToWindow()) {
            post(poll);
            if (wave != null) wave.invalidate();
        }
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(poll);
        super.onDetachedFromWindow();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
            String[] state = nativePlayer(revision);
            if (state != null) applyPlayer(state);
            long now = SystemClock.uptimeMillis();
            if (!polling && now - lastConnect > 2000) {
                polling = true;
                lastConnect = now;
                DATA.execute(() -> {
                    try { nativeConnect(); }
                    finally { post(() -> polling = false); }
                });
            }
            if (!libraryReady && !fetching && now >= libraryRetry) loadLibrary();
            postDelayed(this, 350);
        }
    };

    private void loadLibrary() {
        fetching = true;
        LIBRARY_DATA.execute(() -> {
            String status = nativeLibrary();
            post(() -> {
                fetching = false;
                libraryReady = status != null && status.isEmpty();
                String previousStatus = libraryStatus;
                if (libraryReady) {
                    libraryStatus = "Nothing saved here yet";
                } else {
                    libraryStatus = "Couldn’t load your library";
                    libraryRetry = SystemClock.uptimeMillis() + 12_000;
                    android.util.Log.w("SpatchUi", "Library request failed: "
                        + (status == null ? "No response" : status));
                }
                if ((screen == HOME || screen == LIBRARY) && !previousStatus.equals(libraryStatus)) redrawPage();
            });
        });
    }

    private void applyPlayer(String[] state) {
        revision = Long.parseLong(state[0]);
        boolean trackChanged = !uri.equals(state[1]);
        uri = state[1]; title = state[2]; artist = state[3]; album = state[4]; image = state[5];
        playing = Boolean.parseBoolean(state[6]);
        if (pendingPlayback != null && (trackChanged || pendingPlayback == playing)) {
            pendingPlayback = null;
        }
        shuffle = Boolean.parseBoolean(state[7]);
        repeat = Integer.parseInt(state[8]);
        position = Long.parseLong(state[9]);
        duration = Long.parseLong(state[10]);
        source = state[11];
        albumArtist = state[12]; release = state[13]; albumPosition = state[14]; rating = state[15];
        stamp = SystemClock.uptimeMillis();
        List<Entry> next = new ArrayList<>();
        for (int i = 16; i + 2 < state.length; i += 3) {
            next.add(new Entry(state[i], state[i + 1], "", state[i + 2], -1));
        }
        boolean queueChanged = !upcoming.equals(next);
        if (queueChanged) upcoming = next;
        if (screen == QUEUE && queueChanged) redrawPage();
        else refreshPlayer();
    }

    private void command(int action, long value, String target) {
        COMMANDS.execute(() -> {
            if (!nativeCommand(action, value, target)) post(() -> {
                if (isAttachedToWindow()) Toast.makeText(activity, "Playback command unavailable", Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    void back() {
        if (screen == HOME) activity.moveTaskToBack(true);
        else navigate(5, 0);
    }

    private void navigate(int action, int category) {
        int previous = screen;
        int next = nativeNavigate(route, action, category);
        if (next == route) return;
        Art original = trackArt;
        int destination = next & 7;
        boolean coverTransition = motionEnabled() && original != null && original.isLaidOut()
            && ((previous <= LIBRARY && destination == PLAYER)
            || (previous == PLAYER && (destination <= LIBRARY || destination == IMMERSIVE))
            || (previous == IMMERSIVE && destination == PLAYER));
        RectF from = coverTransition ? coverBounds(original) : null;
        Bitmap cover = coverTransition ? original.cachedArtwork() : null;
        rememberScroll();
        route = next;
        screen = route & 7;
        int selected = (route >> 7) - 1;
        if (filter != selected) collectionRow = collectionOffset = 0;
        filter = selected;
        render();
        if (coverTransition && trackArt != null) moveArtwork(from, cover, original.shape, trackArt);
        if (coverTransition) {
            content.setAlpha(0f);
            content.animate().alpha(1f).setDuration(220).start();
            if (atmosphere != null) {
                atmosphere.setAlpha(0f);
                atmosphere.animate().alpha(1f).setDuration(220).start();
            }
        }
    }

    private RectF coverBounds(Art view) {
        int[] origin = new int[2], position = new int[2];
        getLocationInWindow(origin);
        view.getLocationInWindow(position);
        float side = view.coverSide();
        float x = position[0] - origin[0] + (view.getWidth() - side) * .5f;
        float y = position[1] - origin[1] + (view.getHeight() - side) * .5f;
        return new RectF(x, y, x + side, y + side);
    }

    private void moveArtwork(RectF from, Bitmap bitmap, int fromShape, Art target) {
        target.setAlpha(0f);
        getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                if (trackArt != target) return true;
                RectF to = coverBounds(target);
                CoverTransition moving = new CoverTransition(from, to, bitmap,
                    fromShape, target.shape);
                getOverlay().add(moving);
                android.animation.ValueAnimator animation = android.animation.ValueAnimator.ofFloat(0f, 1f);
                animation.setDuration(420);
                animation.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f));
                animation.addUpdateListener(step -> {
                    moving.progress = (float) step.getAnimatedValue();
                    moving.invalidateSelf();
                    if (trackArt != target) animation.cancel();
                });
                animation.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        getOverlay().remove(moving);
                        if (bitmap != null) {
                            target.revealedBitmap = target.cachedArtwork();
                            target.revealStart = 0;
                        }
                        target.setAlpha(1f);
                    }
                });
                animation.start();
                return true;
            }
        });
    }

    private final class CoverTransition extends android.graphics.drawable.Drawable {
        private final RectF from, to;
        private final float[] first, last;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path path = new Path();
        private final Matrix matrix = new Matrix();
        private final Bitmap bitmap;
        private final BitmapShader shader;
        float progress;

        CoverTransition(RectF from, RectF to, Bitmap bitmap, int firstShape, int lastShape) {
            this.from = from;
            this.to = to;
            this.bitmap = bitmap;
            first = coverPath(firstShape);
            last = coverPath(lastShape);
            shader = bitmap == null ? null : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            setBounds(0, 0, getWidth(), getHeight());
        }

        @Override public void draw(Canvas canvas) {
            float x = mix(from.left, to.left, progress), y = mix(from.top, to.top, progress);
            float side = mix(from.width(), to.width(), progress);
            outline(path, first, last, progress, x, y, side);
            paint.setColor(secondaryContainer);
            if (shader != null) {
                float scale = Math.max(side / bitmap.getWidth(), side / bitmap.getHeight());
                matrix.setScale(scale, scale);
                matrix.postTranslate(x + (side - bitmap.getWidth() * scale) / 2, y + (side - bitmap.getHeight() * scale) / 2);
                shader.setLocalMatrix(matrix);
                paint.setShader(shader);
            }
            canvas.drawPath(path, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static float[] coverPath(int shape) {
        return shape == Art.ROUND ? CIRCLE_COVER : shape == Art.IMMERSIVE ? COVER : PLAYER_COVER;
    }

    private static float mix(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }

    private static void outline(Path path, float[] first, float[] last, float fraction, float x, float y, float side) {
        path.rewind();
        for (int i = 0; i < first.length; i += 2) {
            float px = x + mix(first[i], last[i], fraction) * side;
            float py = y + mix(first[i + 1], last[i + 1], fraction) * side;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
    }

    private void rememberScroll() {
        if (screen == HOME && scroll != null) homeScroll = scroll.getScrollY();
        if (screen == LIBRARY && libraryList != null) {
            collectionRow = libraryList.getFirstVisiblePosition();
            View first = libraryList.getChildAt(0);
            collectionOffset = first == null ? 0 : first.getTop();
        }
    }

    private void redrawPage() {
        rememberScroll();
        render();
    }

    private void openPlayer() { navigate(2, 0); }
    private void openLibrary(int category) { navigate(1, category); }

    private void render() {
        removeAllViews();
        visibleArt.clear();
        scroll = null;
        libraryList = playlistList = null;
        trackTitle = trackArtist = albumTitle = heroTitle = heroArtist = null;
        trackArt = heroArt = atmosphere = null;
        playbackSource = infoTrack = infoArtist = infoAlbum = null;
        infoAlbumArtist = infoRelease = infoPosition = infoRating = infoSource = null;
        infoNext = null;
        playButton = heroPlay = shuffleButton = repeatButton = null;
        wave = null;
        visualizer = null;
        if (screen == PLAYER || screen == IMMERSIVE) {
            atmosphere = new Art(Art.BACKDROP);
            addView(atmosphere, new FrameLayout.LayoutParams(-1, -1));
        }
        content = column();
        addView(content, new FrameLayout.LayoutParams(-1, -1));
        if (screen == HOME || screen == LIBRARY) buildBrowse();
        else if (screen == QUEUE) buildQueue();
        else if (screen == PLAYLIST) buildPlaylist();
        else buildPlayer();
        refreshPlayer();
        int restore = screen == HOME ? homeScroll : 0;
        ScrollView current = scroll;
        if (current != null && restore != 0) current.post(() -> current.scrollTo(0, restore));
    }

    private void buildBrowse() {
        if (screen == HOME) {
            LinearLayout feed = feed();
            FrameLayout header = new FrameLayout(activity);
            header.setBackground(background(secondaryContainer, 30));
            header.setClipToOutline(true);
            WordmarkDecoration flourish = new WordmarkDecoration();
            header.addView(flourish, new FrameLayout.LayoutParams(-1, dp(88)));
            header.setFocusable(true);
            header.setOnClickListener(v -> {
                header.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                flourish.spin();
            });
            TextView mark = text("Spatch", 38, onSecondaryContainer, true);
            mark.setGravity(Gravity.CENTER_VERTICAL);
            mark.setPadding(dp(20), dp(10), dp(24), dp(10));
            if (android.os.Build.VERSION.SDK_INT >= 28) mark.setAccessibilityHeading(true);
            mark.setMinHeight(dp(88));
            header.addView(mark, new FrameLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams headerParams = match(-2);
            headerParams.bottomMargin = dp(12);
            feed.addView(header, headerParams);
            LinearLayout hero = column();
            hero.setBackground(background(secondaryContainer, 28));
            hero.setClipToOutline(true);
            heroArt = new Art(Art.WIDE);
            hero.addView(heroArt, match(dp(154)));
            LinearLayout heroFooter = row();
            heroFooter.setPadding(dp(16), dp(8), dp(10), dp(8));
            LinearLayout details = column();
            heroTitle = text("", 27, onSecondaryContainer, true);
            heroArtist = text("", 14, onSecondaryContainer, false);
            single(heroTitle); single(heroArtist);
            details.addView(heroTitle);
            details.addView(heroArtist);
            heroFooter.addView(details, weighted());
            heroPlay = glyph("play", "Play", true, this::toggle);
            heroFooter.addView(heroPlay, control(56));
            hero.addView(heroFooter, match(-2));
            makeClickable(hero, "Open player", this::openPlayer);
            springPress(hero);
            feed.addView(hero, match(-2));
            gap(feed, 12);
            LinearLayout shortcuts = row();
            TextView liked = chip("Liked songs", tertiaryContainer, onTertiaryContainer, () -> {
                String likedUri = nativeLiked();
                openPlaylist(new Entry("Liked Songs", "", likedUri == null || likedUri.isEmpty()
                    ? "spotify:collection:tracks" : likedUri, "", 0));
            });
            shortcuts.addView(liked, weighted());
            LinearLayout.LayoutParams second = weighted();
            second.leftMargin = dp(8);
            shortcuts.addView(chip("Your library", secondaryContainer, onSecondaryContainer, () -> openLibrary(-1)), second);
            feed.addView(shortcuts, match(-2));
            boolean any = false;
            for (int category = 0; category < SECTIONS.length; category++) any |= addShelf(feed, category);
            if (!any) libraryMessage(feed);
        } else {
            TextView heading = text("Your library", 36, onSurface, true);
            heading.setPadding(dp(20), dp(20), dp(20), dp(12));
            if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            content.addView(heading, match(-2));
            HorizontalScrollView choices = new HorizontalScrollView(activity);
            choices.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = row();
            for (int i = -1; i < SECTIONS.length; i++) {
                final int selected = i;
                TextView chip = chip(i == -1 ? "All" : SECTIONS[i], i == filter ? secondaryContainer : surface,
                                     i == filter ? onSecondaryContainer : onVariant, () -> openLibrary(selected));
                chip.setSelected(i == filter);
                GradientDrawable filterShape = background(i == filter ? secondaryContainer : surface, 10);
                if (i != filter) filterShape.setStroke(dp(1), onVariant);
                chip.setBackground(filterShape);
                chip.setMinHeight(dp(40));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.rightMargin = dp(6);
                chips.addView(chip, params);
            }
            choices.setPadding(dp(16), dp(8), dp(16), dp(4));
            choices.addView(chips);
            content.addView(choices, match(-2));
            List<Entry> entries = entries(filter, 300);
            if (entries.isEmpty()) libraryMessage(feed());
            else {
                libraryList = entryList(entries);
                libraryList.setSelectionFromTop(collectionRow, collectionOffset);
            }

        }
        // Keep the feed visible around the floating player, with room to reach its last row.
        View browse = content.getChildAt(content.getChildCount() - 1);
        content.removeView(browse);
        ((ViewGroup) browse).setClipToPadding(false);
        browse.setPadding(browse.getPaddingLeft(), browse.getPaddingTop(),
            browse.getPaddingRight(), browse.getPaddingBottom() + dp(134));
        FrameLayout dock = new FrameLayout(activity);
        dock.addView(browse, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(activity);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{surface & 0x00ffffff, (surface & 0x00ffffff) | 0xc0000000, surface}));
        dock.addView(fade, new FrameLayout.LayoutParams(-1, dp(154), Gravity.BOTTOM));
        addMini(dock);

        LinearLayout nav = row();
        nav.setPadding(dp(4), 0, dp(4), 0);
        nav.setBackground(background(high, 28));
        nav.setElevation(dp(4));
        nav.addView(destination("Home", "home", screen == HOME, () -> navigate(0, 0)), weighted());
        nav.addView(destination("Library", "library", screen == LIBRARY, () -> openLibrary(filter)), weighted());
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(dp(224), dp(56),
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        navParams.bottomMargin = dp(8);
        dock.addView(nav, navParams);
        content.addView(dock, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private View destination(String title, String symbol, boolean selected, Runnable action) {
        LinearLayout tab = column();
        tab.setGravity(Gravity.CENTER);
        tab.setMinimumHeight(dp(56));
        tab.setSelected(selected);
        Glyph icon = new Glyph(symbol, false);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (selected) {
            icon.setBackground(background(secondaryContainer, 16));
        }
        icon.ink = selected ? onSecondaryContainer : onVariant;
        tab.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(32)));
        gap(tab, 3);
        TextView label = text(title, 12, selected ? onSurface : onVariant, selected);
        label.setGravity(Gravity.CENTER);
        tab.addView(label, match(-2));
        makeClickable(tab, title, action);
        return tab;
    }

    private List<Entry> entries(int category, int limit) {
        List<Entry> result = new ArrayList<>();
        String[] fields = nativeEntries(category, limit);
        if (fields == null) return result;
        for (int i = 0; i + 4 < fields.length; i += 5) {
            result.add(new Entry(fields[i], fields[i + 1], fields[i + 2], fields[i + 3], Integer.parseInt(fields[i + 4])));
        }
        return result;
    }

    private boolean addShelf(LinearLayout feed, int category) {
        List<Entry> items = entries(category, 12);
        if (items.isEmpty()) return false;
        gap(feed, 18);
        LinearLayout header = row();
        header.addView(text(SECTIONS[category], 22, onSurface, true), weighted());
        header.addView(chip("See all", surface, primary, () -> openLibrary(category)));
        feed.addView(header, match(-2));
        HorizontalScrollView carousel = new HorizontalScrollView(activity);
        carousel.setHorizontalScrollBarEnabled(false);
        LinearLayout tiles = row();
        for (int index = 0; index < items.size(); index++) {
            Entry entry = items.get(index);
            int available = getResources().getConfiguration().screenWidthDp - 32;
            int width = Math.max(100, Math.min(168, (available - 32) / 2));
            LinearLayout tile = column();
            tile.setBackground(background(container, 20));
            tile.setPadding(dp(6), dp(6), dp(6), dp(6));
            Art art = new Art(category == 2 ? Art.ROUND : Art.SQUARE);
            art.bind(entry.image);
            tile.addView(art, match(dp(width - 12)));
            TextView name = text(entry.title, 14, onSurface, true);
            single(name);
            name.setPadding(dp(4), dp(7), dp(4), dp(2));
            tile.addView(name, match(-2));
            if (!entry.subtitle.isEmpty()) {
                TextView subtitle = text(entry.subtitle, 12, onVariant, false);
                single(subtitle);
                subtitle.setPadding(dp(4), 0, dp(4), dp(3));
                tile.addView(subtitle, match(-2));
            }
            if (!entry.uri.isEmpty()) {
                makeClickable(tile, (entry.category == 0 ? "Open " : "Play ") + entry.title, () -> selectEntry(entry));
                springPress(tile);
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(width), -2);
            p.rightMargin = dp(10);
            tiles.addView(tile, p);
        }
        carousel.addView(tiles);
        feed.addView(carousel, match(-2));
        return true;
    }

    private void addMini(FrameLayout dock) {
        LinearLayout mini = row();
        mini.setPadding(dp(8), dp(4), dp(5), dp(4));
        mini.setBackground(background(high, 28));
        mini.setElevation(dp(4));
        trackArt = new Art(Art.ROUND);
        mini.addView(trackArt, new LinearLayout.LayoutParams(dp(42), dp(42)));
        trackArt.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        makeClickable(trackArt, "Open player", this::openPlayer);
        artworkGestures(trackArt, true);
        LinearLayout info = column();
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(9), 0, dp(2), 0);
        trackTitle = text("", 15, onSurface, true);
        trackArtist = text("", 12, onVariant, false);
        single(trackTitle); single(trackArtist);
        info.addView(trackTitle);
        info.addView(trackArtist);
        makeClickable(info, "Open player", this::openPlayer);
        artworkGestures(info, true);
        mini.addView(info, weighted());
        addTransport(mini, glyph("previous", "Previous track", false, () -> command(PREVIOUS, 0, "")), 48, true, false);
        playButton = glyph("play", "Play", true, this::toggle);
        addTransport(mini, playButton, 48, false, false);
        addTransport(mini, glyph("next", "Next track", false, () -> command(NEXT, 0, "")), 48, false, true);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.setMargins(dp(12), 0, dp(12), dp(72));
        dock.addView(mini, params);
    }

    private void buildPlayer() {
        boolean full = screen == IMMERSIVE;
        if (!full) {
            LinearLayout top = row();
            top.setPadding(dp(10), dp(6), dp(10), 0);
            top.addView(glyph("down", "Close player", false, this::back), control(48));
            top.addView(new View(activity), control(48));
            playbackSource = text("", 16, onSurface, true);
            single(playbackSource);
            playbackSource.setGravity(Gravity.CENTER);
            top.addView(playbackSource, weighted());
            top.addView(glyph("immersive", "Open immersive mode", false, () -> navigate(3, 0)), control(48));
            top.addView(glyph("queue", "Up next", false, () -> navigate(4, 0)), control(48));
            content.addView(top, match(-2));
        }

        LinearLayout body = column();
        body.setPadding(dp(16), 0, dp(16), dp(12));
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        if (full) content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        trackArt = new Art(screen == PLAYER ? Art.IMMERSIVE : Art.PLAYER_ART);
        body.addView(trackArt, new LinearLayout.LayoutParams(-1, 0, 1));
        trackArt.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        makeClickable(trackArt, screen == IMMERSIVE ? "Return to player" : "Open immersive mode", () -> navigate(3, 0));
        artworkGestures(trackArt, false);
        gap(body, full ? 4 : 18);
        LinearLayout details = column();
        if (!full) {
            details.setPadding(dp(16), dp(16), dp(16), dp(12));
            details.setBackground(background(container, 30));
        }
        body.addView(details, match(-2));
        trackTitle = text("", full ? 32 : 40, onSurface, true);
        trackTitle.setMaxLines(2);
        trackTitle.setEllipsize(TextUtils.TruncateAt.END);
        trackArtist = text("", 17, onVariant, false);
        albumTitle = text("", 13, onVariant, false);
        for (TextView labelView : new TextView[]{trackTitle, trackArtist, albumTitle}) {
            labelView.setGravity(full ? Gravity.CENTER : Gravity.START);
            details.addView(labelView, match(-2));
        }
        gap(details, full ? 2 : 8);
        wave = new Wave();
        details.addView(wave, match(dp(full ? 54 : 65)));
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        shuffleButton = glyph("shuffle", "Shuffle", false, () -> command(SHUFFLE, shuffle ? 0 : 1, ""));
        repeatButton = glyph("repeat", "Repeat", false, () -> command(REPEAT, (repeat + 1) % 3, ""));
        playButton = glyph("play", "Play", true, this::toggle);
        addTransport(controls, shuffleButton, 48, true, false);
        addTransport(controls, glyph("previous", "Previous track", false, () -> command(PREVIOUS, 0, "")), 50, false, false);
        addTransport(controls, playButton, 74, false, false);
        addTransport(controls, glyph("next", "Next track", false, () -> command(NEXT, 0, "")), 50, false, false);
        addTransport(controls, repeatButton, 48, false, true);
        details.addView(controls, match(-2));
        if (full) {
            addVolume(details);
            visualizer = new Visualizer();
            details.addView(visualizer, match(dp(82)));
        } else {
            addPlayerDetails(body);
        }
    }

    private void addPlayerDetails(LinearLayout player) {
        LinearLayout pages = column();
        ScrollView playerScroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                // The scroll container receives the viewport; its vertical child is unbounded.
                int viewport = getMeasuredHeight();
                if (viewport != player.getLayoutParams().height) {
                    player.getLayoutParams().height = viewport;
                    player.forceLayout();
                    pages.forceLayout();
                    super.onMeasure(widthSpec, heightSpec);
                }
            }
        };
        playerScroll.setVerticalScrollBarEnabled(false);
        playerScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        playerScroll.addView(pages, new ScrollView.LayoutParams(-1, -2));
        content.addView(playerScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        pages.addView(player, match(0));

        LinearLayout section = column();
        section.setPadding(dp(16), dp(10), dp(16), dp(24));
        pages.addView(section, match(-2));
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setBackground(background(container, 28));
        section.addView(card, match(-2));
        TextView heading = text("Track details", 24, onSurface, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        card.addView(heading, match(-2));
        gap(card, 10);
        infoTrack = detailLine(card, "Track");
        infoArtist = detailLine(card, "Artist");
        infoAlbum = detailLine(card, "Album");
        infoAlbumArtist = detailLine(card, "Album artist");
        infoRelease = detailLine(card, "Released");
        infoPosition = detailLine(card, "On album");
        infoRating = detailLine(card, "Rating");
        infoSource = detailLine(card, "Playing from");

        gap(section, 14);
        LinearLayout next = row();
        next.addView(text("Up next", 23, onSurface, true), weighted());
        next.addView(chip("View queue", secondaryContainer, onSecondaryContainer, () -> navigate(4, 0)));
        section.addView(next, match(-2));
        infoNext = column();
        section.addView(infoNext, match(-2));

    }

    private TextView detailLine(LinearLayout card, String label) {
        LinearLayout line = row();
        line.setPadding(0, dp(5), 0, dp(5));
        line.addView(text(label, 14, onVariant, false), new LinearLayout.LayoutParams(dp(104), -2));
        TextView value = text("", 16, onSurface, true);
        line.addView(value, weighted());
        card.addView(line, match(-2));
        return value;
    }

    private void refreshPlayerDetails() {
        if (infoTrack == null) return;
        infoTrack.setText(title.isEmpty() ? "Nothing playing" : duration > 0 ? title + " · " + time(duration) : title);
        infoArtist.setText(artist.isEmpty() ? "Unavailable" : artist);
        infoAlbum.setText(album.isEmpty() ? "Unavailable" : album);
        optionalDetail(infoAlbumArtist, albumArtist);
        optionalDetail(infoRelease, release);
        optionalDetail(infoPosition, albumPosition);
        optionalDetail(infoRating, rating);
        optionalDetail(infoSource, source.equals(album) ? "" : source);
        if (infoNext.getTag() == upcoming) return;
        infoNext.setTag(upcoming);
        infoNext.removeAllViews();
        for (int i = 0; i < Math.min(3, upcoming.size()); i++) {
            EntryRow row = new EntryRow();
            row.bind(upcoming.get(i));
            row.setBackground(background(container, 18));
            infoNext.addView(row, match(-2));
            gap(infoNext, 4);
        }
        if (upcoming.isEmpty()) message(infoNext, "The queue is empty");
    }

    private void optionalDetail(TextView field, String value) {
        field.setText(value);
        ((View) field.getParent()).setVisibility(value.isEmpty() ? GONE : VISIBLE);
    }

    private void addVolume(LinearLayout details) {
        AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        SeekBar slider = new SeekBar(activity);
        slider.setContentDescription("Media volume");
        slider.setProgressTintList(ColorStateList.valueOf(primary));
        slider.setThumbTintList(ColorStateList.valueOf(primary));
        int stream = AudioManager.STREAM_MUSIC;
        int min = android.os.Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(stream) : 0;
        slider.setMax(audio.getStreamMaxVolume(stream) - min);
        slider.setProgress(audio.getStreamVolume(stream) - min);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(stream, min + value, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {
                view.setProgress(audio.getStreamVolume(stream) - min);
            }
        });
        LinearLayout volume = row();
        Glyph icon = new Glyph("volume", false);
        icon.ink = onVariant;
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        volume.addView(icon, control(36));
        volume.addView(slider, new LinearLayout.LayoutParams(0, dp(48), 1));
        details.addView(volume, match(-2));
    }

    private void artworkGestures(View view, boolean mini) {
        GestureDetector gestures = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                return mini && view.performClick();
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                return !mini && view.performClick();
            }
            @Override public boolean onFling(MotionEvent start, MotionEvent end, float vx, float vy) {
                if (start == null) return false;
                float dx = end.getX() - start.getX(), dy = end.getY() - start.getY();
                if (Math.hypot(dx, dy) < dp(36)) return false;
                if (Math.abs(dy) > Math.abs(dx)) {
                    if (mini && dy < 0) openPlayer();
                    else if (!mini && dy > 0) back();
                    else return false;
                } else if (!mini && !uri.isEmpty()) {
                    command(dx < 0 ? NEXT : PREVIOUS, 0, "");
                } else return false;
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                return true;
            }
        });
        view.setOnTouchListener((v, event) -> {
            gestures.onTouchEvent(event);
            return true;
        });
    }

    private void selectEntry(Entry entry) {
        if (entry.category == 0 || entry.uri.startsWith("spotify:playlist:")
                || (entry.uri.startsWith("spotify:user:") && entry.uri.contains(":playlist:"))) {
            openPlaylist(entry);
        } else if (!entry.uri.isEmpty()) {
            command(PLAY, 0, entry.uri);
        }
    }

    private void openPlaylist(Entry entry) {
        playlist = entry;
        playlistTracks = new ArrayList<>();
        playlistOffset = playlistRow = playlistTop = 0;
        playlistFailed = false;
        playlistLoading = true;
        navigate(6, 0);
        loadPlaylist(entry, 0);
    }

    private void loadPlaylist(Entry entry, int offset) {
        loadPlaylist(entry, offset, 0);
    }

    private void loadPlaylist(Entry entry, int offset, int attempt) {
        LIBRARY_DATA.execute(() -> {
            String[] result = nativePlaylist(entry.uri, offset);
            post(() -> {
                if (playlist != entry || screen != PLAYLIST) return;
                if (result != null && result.length == 1 && "loading".equals(result[0]) && attempt < 3) {
                    postDelayed(() -> {
                        if (playlist == entry && screen == PLAYLIST) loadPlaylist(entry, offset, attempt + 1);
                    }, 650);
                    return;
                }
                playlistLoading = false;
                playlistFailed = result == null || result.length < 2 || !result[0].isEmpty();
                if (!playlistFailed) {
                    playlistOffset = Integer.parseInt(result[1]);
                    for (int i = 2; i + 3 < result.length; i += 4) {
                        playlistTracks.add(new Entry(result[i], result[i + 1], result[i + 2], result[i + 3], -1));
                    }
                }
                if (screen == PLAYLIST) render();
            });
        });
    }

    private void buildPlaylist() {
        if (playlist == null) return;
        LinearLayout top = row();
        top.setPadding(dp(10), dp(6), dp(10), 0);
        top.addView(glyph("back", "Back", false, this::back), control(48));
        TextView heading = text(playlist.title, 26, onSurface, true);
        single(heading);
        top.addView(heading, weighted());
        top.addView(chip("Play", primary, onPrimary, () -> command(PLAY, 0, playlist.uri)));
        content.addView(top, match(-2));
        if (playlistTracks.isEmpty()) {
            LinearLayout body = feed();
            message(body, playlistLoading ? "Loading playlist…" : playlistFailed ? "Couldn’t load playlist" : "This playlist is empty");
        } else {
            playlistList = entryList(playlistTracks);
            playlistList.setSelectionFromTop(playlistRow, playlistTop);
        }
        if (!playlistLoading && (playlistFailed || playlistOffset > 0)) {
            content.addView(chip(playlistFailed ? "Retry" : "Load more", secondaryContainer, onSecondaryContainer, () -> {
                if (playlistList != null) {
                    playlistRow = playlistList.getFirstVisiblePosition();
                    View first = playlistList.getChildAt(0);
                    playlistTop = first == null ? 0 : first.getTop();
                }
                playlistLoading = true;
                render();
                loadPlaylist(playlist, playlistOffset);
            }), match(-2));
        }
    }

    private void addTransport(LinearLayout row, Glyph glyph, int size, boolean first, boolean last) {
        glyph.connected = !glyph.primary;
        glyph.first = first;
        glyph.last = last;
        if (glyph.connected) glyph.updateContainer();
        LinearLayout.LayoutParams params = control(size);
        params.setMarginStart(first ? 0 : dp(2));
        row.addView(glyph, params);
    }

    private void buildQueue() {
        LinearLayout header = row();
        header.setPadding(dp(10), dp(8), dp(14), 0);
        header.addView(glyph("back", "Back", false, this::back), control(48));
        header.addView(text("Up next", 30, onSurface, true), weighted());
        content.addView(header, match(-2));
        if (upcoming.isEmpty()) message(feed(), "The queue is empty");
        else entryList(upcoming);
    }

    private ListView entryList(List<Entry> entries) {
        ListView list = new ListView(activity);
        list.setDivider(new android.graphics.drawable.ColorDrawable(surface));
        list.setDividerHeight(dp(3));
        list.setPadding(dp(16), 0, dp(16), 0);
        list.setClipToPadding(false);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Entry getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return !getItem(position).uri.isEmpty(); }
            @Override public View getView(int position, View previous, ViewGroup parent) {
                EntryRow row = previous instanceof EntryRow ? (EntryRow) previous : new EntryRow();
                row.bind(getItem(position));
                row.setBackground(segment(container, position == 0 ? 20 : 5, position == 0 ? 20 : 5,
                    position == getCount() - 1 ? 20 : 5, position == getCount() - 1 ? 20 : 5));
                return row;
            }
        });
        list.setOnItemClickListener((parent, row, index, id) -> {
            Entry entry = entries.get(index);
            if (!entry.uri.isEmpty()) selectEntry(entry);
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return list;
    }

    private final class EntryRow extends LinearLayout {
        private final Art imageView;
        private final TextView name, subtitle;
        EntryRow() {
            super(activity);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(7), dp(12), dp(7));
            imageView = new Art(Art.SQUARE);
            addView(imageView, control(48));
            LinearLayout info = column();
            info.setPadding(dp(12), 0, 0, 0);
            name = text("", 16, onSurface, true);
            subtitle = text("", 13, onVariant, false);
            single(name); single(subtitle);
            info.addView(name); info.addView(subtitle);
            addView(info, weighted());
        }

        void bind(Entry entry) {
            imageView.shape = entry.category == 2 ? Art.ROUND : Art.SQUARE;
            imageView.bind(entry.image);
            imageView.invalidate();
            name.setText(entry.title);
            subtitle.setText(entry.subtitle);
        }
    }

    private void refreshPlayer() {
        refreshPlayerDetails();
        if (visualizer != null) visualizer.invalidate();
        String shown = title.isEmpty() ? "Nothing playing" : title;
        if (playbackSource != null) playbackSource.setText(source.isEmpty() ? album : source);
        if (trackTitle != null) trackTitle.setText(shown);
        if (trackArtist != null) trackArtist.setText(artist);
        if (albumTitle != null) {
            albumTitle.setText(album);
            albumTitle.setVisibility(album.isEmpty() ? GONE : VISIBLE);
        }
        if (heroTitle != null) heroTitle.setText(shown);
        if (heroArtist != null) heroArtist.setText(artist);
        if (trackArt != null) trackArt.bind(image);
        if (heroArt != null) heroArt.bind(image);
        if (atmosphere != null) atmosphere.bind(image);
        refreshPlaybackButtons();
        if (shuffleButton != null) {
            shuffleButton.setSelected(shuffle);
            shuffleButton.updateContainer();
            shuffleButton.setContentDescription(shuffle ? "Shuffle on" : "Shuffle off");
            shuffleButton.invalidate();
        }
        if (repeatButton != null) {
            repeatButton.symbol = repeat == 2 ? "repeat-one" : "repeat";
            repeatButton.setSelected(repeat != 0);
            repeatButton.updateContainer();
            repeatButton.setContentDescription(repeat == 2 ? "Repeat track" : repeat == 1 ? "Repeat queue" : "Repeat off");
            repeatButton.invalidate();
        }
        if (wave != null) wave.invalidate();
    }

    private void refreshPlaybackButtons() {
        boolean shownPlaying = pendingPlayback == null ? playing : pendingPlayback;
        if (playButton != null) playButton.setPlayback(shownPlaying, !uri.isEmpty());
        if (heroPlay != null) heroPlay.setPlayback(shownPlaying, !uri.isEmpty());
    }

    private void toggle() {
        if (uri.isEmpty() || pendingPlayback != null) return;
        boolean requested = !playing;
        int attempt = ++playbackAttempt;
        pendingPlayback = requested;
        refreshPlaybackButtons();
        COMMANDS.execute(() -> {
            boolean accepted = nativeCommand(requested ? 1 : 0, 0, "");
            post(() -> {
                if (accepted) {
                    postDelayed(() -> finishPlaybackAttempt(attempt, false), 2500);
                } else {
                    finishPlaybackAttempt(attempt, true);
                }
            });
        });
    }

    private void finishPlaybackAttempt(int attempt, boolean failed) {
        if (attempt != playbackAttempt || pendingPlayback == null) return;
        pendingPlayback = null;
        refreshPlaybackButtons();
        if (failed && isAttachedToWindow()) {
            Toast.makeText(activity, "Playback command unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private long playhead() {
        long elapsed = playing && stamp > 0 ? Math.max(0, SystemClock.uptimeMillis() - stamp) : 0;
        return Math.max(0, Math.min(duration, position + elapsed));
    }

    private void seek(float fraction) {
        if (duration <= 0 || seekPending) return;
        long target = Math.round(duration * (double) Math.max(0, Math.min(1, fraction)));
        seekPending = true;
        COMMANDS.execute(() -> {
            boolean ok = nativeCommand(SEEK, target, "");
            post(() -> {
                seekPending = false;
                if (!ok && isAttachedToWindow()) Toast.makeText(activity, "Couldn't seek", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private LinearLayout feed() {
        scroll = new ScrollView(activity);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = column();
        page.setPadding(dp(16), dp(8), dp(16), dp(12));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private boolean motionEnabled() {
        return android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    private int dp(float value) { return Math.round(value * density); }
    private LinearLayout.LayoutParams match(int height) { return new LinearLayout.LayoutParams(-1, height); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private LinearLayout.LayoutParams control(int size) { return new LinearLayout.LayoutParams(dp(size), dp(size)); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private void gap(LinearLayout col, int height) { col.addView(new View(activity), match(dp(height))); }
    private void single(TextView v) { v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END); }

    private TextView text(String label, int size, int color, boolean bold) {
        TextView v = new TextView(activity);
        v.setText(label);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setTypeface(Typeface.create(bold && size >= 26 ? "sans-serif" : bold ? "sans-serif-medium" : "sans-serif",
            bold && size >= 26 ? Typeface.BOLD : Typeface.NORMAL));
        return v;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable segment(int color, int topLeft, int topRight, int bottomRight, int bottomLeft) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadii(new float[]{dp(topLeft), dp(topLeft), dp(topRight), dp(topRight),
            dp(bottomRight), dp(bottomRight), dp(bottomLeft), dp(bottomLeft)});
        return shape;
    }

    private void springPress(View view) {
        if (!motionEnabled()) return;
        android.animation.StateListAnimator states = new android.animation.StateListAnimator();
        android.animation.ObjectAnimator down = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            view, android.animation.PropertyValuesHolder.ofFloat("scaleX", .97f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", .97f));
        down.setDuration(105);
        states.addState(new int[]{android.R.attr.state_pressed}, down);
        android.animation.ObjectAnimator up = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            view, android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f));
        up.setDuration(260);
        up.setInterpolator(new android.view.animation.OvershootInterpolator(.85f));
        states.addState(new int[0], up);
        view.setStateListAnimator(states);
    }

    private final class WordmarkDecoration extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path petal = new Path();
        private float angle;
        private android.animation.ValueAnimator turn;

        void spin() {
            if (!motionEnabled()) return;
            if (turn != null) turn.cancel();
            angle %= 360f;
            turn = android.animation.ValueAnimator.ofFloat(angle, angle + 144f);
            turn.setDuration(620);
            turn.setInterpolator(new android.view.animation.OvershootInterpolator(.8f));
            turn.addUpdateListener(frame -> {
                angle = (float) frame.getAnimatedValue();
                invalidate();
            });
            turn.start();
        }

        WordmarkDecoration() {
            super(activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            petal.moveTo(WORDMARK[0], WORDMARK[1]);
            for (int i = 2; i < WORDMARK.length; i += 2) {
                petal.lineTo(WORDMARK[i], WORDMARK[i + 1]);
            }
            petal.close();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            canvas.save();
            canvas.translate(w * (rtl ? .12f : .88f), h * .62f);
            canvas.rotate((rtl ? -15f : 15f) + angle);
            canvas.scale(h * .57f, h * .57f);
            paint.setColor(tertiaryContainer);
            canvas.drawPath(petal, paint);
            canvas.restore();
            paint.setColor(primary);
            canvas.drawCircle(w * (rtl ? .30f : .70f), h * .28f, dp(4), paint);
            canvas.drawCircle(w * (rtl ? .34f : .66f), h * .45f, dp(2), paint);
        }
    }

    private void makeClickable(View v, String label, Runnable action) {
        if (label != null) v.setContentDescription(label);
        v.setFocusable(true);
        v.setOnClickListener(ignored -> action.run());
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff), null, background(Color.WHITE, 18)));
    }

    private TextView chip(String name, int bg, int color, Runnable action) {
        TextView v = text(name, 14, color, true);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(48));
        v.setPadding(dp(14), 0, dp(14), 0);
        v.setBackground(background(bg, 16));
        makeClickable(v, null, action);
        springPress(v);
        return v;
    }

    private void libraryMessage(LinearLayout parent) {
        message(parent, libraryStatus);
        if (!libraryReady && !libraryStatus.startsWith("Connecting")) {
            parent.addView(chip("Retry", secondaryContainer, onSecondaryContainer, () -> {
                if (!fetching) loadLibrary();
            }));
        }
    }

    private void message(LinearLayout parent, String value) {
        TextView v = text(value, 14, onVariant, false);
        v.setPadding(dp(4), dp(16), dp(4), dp(10));
        parent.addView(v, match(-2));
    }

    private Glyph glyph(String symbol, String name, boolean filled, Runnable action) {
        Glyph glyph = new Glyph(symbol, filled);
        makeClickable(glyph, name, action);
        if (filled) glyph.setForeground(null);
        return glyph;
    }

    private static final class Entry {
        final String title, subtitle, uri, image;
        final int category;
        Entry(String title, String subtitle, String uri, String image, int category) {
            this.title = title; this.subtitle = subtitle; this.uri = uri; this.image = image; this.category = category;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Entry)) return false;
            Entry e = (Entry) other;
            return title.equals(e.title) && subtitle.equals(e.subtitle) && uri.equals(e.uri) && image.equals(e.image) && category == e.category;
        }
        @Override public int hashCode() { return java.util.Objects.hash(title, subtitle, uri, image, category); }
    }

    private final class Art extends View {
        static final int SQUARE = 0, ROUND = 1, WIDE = 2, IMMERSIVE = 3, BACKDROP = 4, PLAYER_ART = 5;
        private int shape;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix crop = new Matrix();
        private final Path outline = new Path();
        private final android.graphics.Rect visible = new android.graphics.Rect();
        private String url = "";
        private BitmapShader bitmapShader;
        private LinearGradient wash;
        private float washHeight;
        private Bitmap previousBitmap, revealedBitmap;
        private long revealStart;
        private int previousWidth, previousHeight;

        Art(int shape) {
            super(activity);
            this.shape = shape;
            visibleArt.add(this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void bind(String value) {
            if (url.equals(value)) return;
            url = value;
            previousBitmap = null;
            revealedBitmap = null;
            bitmapShader = null;
            invalidate();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int size = Math.min(width, dp(330));
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(size, heightSpec));
        }

        float coverSide() { return Math.min(Math.min(getWidth(), getHeight()), dp(344)); }

        private int artworkSize() {
            return shape == BACKDROP || getWidth() > dp(200) || getHeight() > dp(200) ? 440 : 180;
        }

        Bitmap cachedArtwork() {
            Bitmap bitmap = IMAGES.get(url + ':' + artworkSize());
            return bitmap != null ? bitmap : IMAGES.get(url + ":180");
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            int size = artworkSize();
            String key = url + ':' + size;
            Bitmap bitmap = IMAGES.get(key);
            if (bitmap == null && !url.isEmpty() && getLocalVisibleRect(visible)) fetchArt(url, size);
            if (shape == BACKDROP) {
                Bitmap blur = IMAGES.get(key + "/blur");
                if (blur != null) {
                    paint.setAlpha(255);
                    setBitmap(blur, w, h);
                    c.drawRect(0, 0, w, h, paint);
                    paint.setShader(null);
                }
                if (wash == null || washHeight != h) {
                    washHeight = h;
                    int tint = (dark ? 0xa0000000 : 0x98000000) | (surface & 0x00ffffff);
                    wash = new LinearGradient(0, 0, 0, h,
                        new int[]{surface, tint, surface}, new float[]{0f, .34f, 1f}, Shader.TileMode.CLAMP);
                }
                paint.setAlpha(255);
                paint.setShader(wash);
                c.drawRect(0, 0, w, h, paint);
                paint.setShader(null);
                return;
            }

            if (bitmap == null && size != 180 && (shape == IMMERSIVE || shape == PLAYER_ART)) {
                bitmap = IMAGES.get(url + ":180");
            }
            int alpha = 255;
            if (bitmap != null && (shape == WIDE || shape == IMMERSIVE || shape == PLAYER_ART) && w > dp(200) && motionEnabled()) {
                long now = SystemClock.uptimeMillis();
                if (revealedBitmap != bitmap) {
                    revealedBitmap = bitmap;
                    revealStart = now;
                }
                float fraction = Math.min(1f, (now - revealStart) / 340f);
                alpha = Math.round(255f * fraction * (2f - fraction));
                if (alpha < 255) postInvalidateOnAnimation();
            }

            int save = c.save();
            if (shape == IMMERSIVE || shape == PLAYER_ART) {
                float side = coverSide();
                c.translate((w - side) * .5f, (h - side) * .5f);
                w = h = side;
                paint.setShader(null);
                float[] points = shape == IMMERSIVE ? COVER : PLAYER_COVER;
                outline(outline, points, points, 0, 0, 0, side);
                if (alpha < 255 || bitmap == null) {
                    paint.setColor(container);
                    c.drawPath(outline, paint);
                }
                if (bitmap != null) {
                    setBitmap(bitmap, w, h);
                    paint.setAlpha(alpha);
                    c.drawPath(outline, paint);
                }
                paint.setShader(null);
            } else {
                if (bitmap == null || alpha < 255) {
                    paint.setColor(container);
                    if (shape != WIDE) {
                        paint.setShader(new LinearGradient(0, 0, w, h, high, container, Shader.TileMode.CLAMP));
                    }
                }
                float r = shape == ROUND ? Math.min(w, h) / 2 : dp(shape == WIDE ? 0 : 18);
                if (bitmap == null || alpha < 255) {
                    c.drawRoundRect(0, 0, w, h, r, r, paint);
                    paint.setShader(null);
                }
                if (bitmap != null) {
                    setBitmap(bitmap, w, h);
                    paint.setAlpha(alpha);
                    c.drawRoundRect(0, 0, w, h, r, r, paint);
                    paint.setShader(null);
                }
                if (bitmap != null && shape == WIDE) {
                    paint.setColor(0x2814131e);
                    c.drawRoundRect(0, 0, w, h, r, r, paint);
                }
            }
            c.restoreToCount(save);
        }

        private void setBitmap(Bitmap bitmap, float w, float h) {
            if (bitmap != previousBitmap || w != previousWidth || h != previousHeight) {
                previousBitmap = bitmap;
                previousWidth = (int) w;
                previousHeight = (int) h;
                float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
                crop.setScale(scale, scale);
                crop.postTranslate((w - bitmap.getWidth() * scale) / 2, (h - bitmap.getHeight() * scale) / 2);
                bitmapShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bitmapShader.setLocalMatrix(crop);
            }
            paint.setShader(bitmapShader);
            if (shape == SQUARE || shape == ROUND) {
                bitmapShader = null;
                previousBitmap = null;
            }
        }
    }

    private void fetchArt(String url, int size) {
        String key = url + ':' + size;
        if (failedImages.contains(key) || !loadingImages.add(key)) return;
        ART.execute(() -> {
            Bitmap bitmap = CoreBridge.artwork(url, size);
            if (bitmap != null) {
                IMAGES.put(key, bitmap);
                if (size == 440) IMAGES.put(key + "/blur", Bitmap.createScaledBitmap(bitmap, 10, 10, true));
            }
            post(() -> {
                loadingImages.remove(key);
                if (bitmap == null) failedImages.add(key);
                for (Art art : visibleArt) if (art.url.equals(url)) art.invalidate();
            });
        });
    }

    private final class Glyph extends View {
        private String symbol;
        private final boolean primary;
        private boolean connected, first, last;
        private Integer ink;

        void updateContainer() {
            if (!connected) return;
            int start = first || isSelected() ? 24 : 8;
            int end = last || isSelected() ? 24 : 8;
            setBackground(segment(isSelected() ? SpatchUi.this.primary : high, start, end, end, start));
        }

        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (!isClickable()) return;
            if (primary) {
                morphTo(isPressed() ? 1f - restingShape : restingShape);
                return;
            }
            if (!motionEnabled()) return;
            float scale = isPressed() ? .92f : 1f;
            animate().scaleX(scale).scaleY(scale).setDuration(isPressed() ? 90 : 260)
                .setInterpolator(isPressed() ? new android.view.animation.DecelerateInterpolator()
                    : new android.view.animation.OvershootInterpolator(.9f)).start();
        }
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        private final android.graphics.Rect visible = new android.graphics.Rect();
        private float shape, restingShape;
        private android.animation.ValueAnimator morph;

        private void morphTo(float target) {
            if (morph != null) morph.cancel();
            if (!motionEnabled() || Math.abs(shape - target) < .001f) {
                shape = target;
                invalidate();
                return;
            }
            morph = android.animation.ValueAnimator.ofFloat(shape, target);
            morph.setDuration(isPressed() ? 125 : 270);
            morph.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f));
            morph.addUpdateListener(animation -> {
                shape = (float) animation.getAnimatedValue();
                invalidate();
            });
            morph.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (morph != null) morph.cancel();
            super.onDetachedFromWindow();
        }

        private long spinStarted, iconChangedAt;
        private String previousSymbol;
        private float stoppedAngle;

        private float angle(long now) {
            return spinStarted == 0 ? stoppedAngle : (stoppedAngle + (now - spinStarted) * .028f) % 360f;
        }

        void setPlayback(boolean playing, boolean enabled) {
            long now = SystemClock.uptimeMillis();
            if (playing && spinStarted == 0) spinStarted = now;
            if (!playing && spinStarted != 0) {
                stoppedAngle = angle(now);
                spinStarted = 0;
            }
            float nextShape = playing ? 1f : 0f;
            if (restingShape != nextShape) {
                restingShape = nextShape;
                morphTo(isPressed() ? 1f - restingShape : restingShape);
            }
            String nextSymbol = playing ? "pause" : "play";
            if (!nextSymbol.equals(symbol)) {
                previousSymbol = symbol;
                iconChangedAt = now;
                symbol = nextSymbol;
            }
            setEnabled(enabled);
            setContentDescription(playing ? "Pause" : "Play");
            invalidate();
        }

        Glyph(String symbol, boolean primary) {
            super(activity);
            this.symbol = symbol;
            this.primary = primary;
        }

        @Override public CharSequence getAccessibilityClassName() { return android.widget.Button.class.getName(); }

        @Override protected void onDraw(Canvas canvas) {
            int save = canvas.save();
            float available = Math.min(getWidth(), getHeight());
            float size = Math.min(dp(primary ? 62 : 23), available - dp(4));
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            if (primary) {
                float r = size * .5f;
                path.rewind();
                for (int i = 0; i < BUTTON_FIVE.length; i += 2) {
                    float x = (BUTTON_FIVE[i] + (BUTTON_NINE[i] - BUTTON_FIVE[i]) * shape) * r;
                    float y = (BUTTON_FIVE[i + 1] + (BUTTON_NINE[i + 1] - BUTTON_FIVE[i + 1]) * shape) * r;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                paint.setColor(isEnabled() ? SpatchUi.this.primary : high);
                paint.setStyle(Paint.Style.FILL);
                int shapeSave = canvas.save();
                canvas.rotate(motionEnabled() ? angle(SystemClock.uptimeMillis()) : 0);
                canvas.drawPath(path, paint);
                canvas.restoreToCount(shapeSave);
                if (spinStarted != 0 && motionEnabled() && getLocalVisibleRect(visible)) postInvalidateOnAnimation();
                size *= .51f;
            }
            float unit = size / 24f;
            canvas.scale(unit, unit);
            canvas.translate(-12, -12);
            int glyphColor = ink != null ? ink : primary || (connected && isSelected()) ? onPrimary : onSurface;
            float blend = primary && previousSymbol != null && motionEnabled()
                ? Math.min(1f, (SystemClock.uptimeMillis() - iconChangedAt) / 180f) : 1f;
            if (blend < 1f) {
                drawGlyph(canvas, previousSymbol, glyphColor, 1f - blend, 1f - .22f * blend);
                postInvalidateOnAnimation();
            }
            drawGlyph(canvas, symbol, glyphColor, blend, .78f + .22f * blend);
            canvas.restoreToCount(save);
            if (connected && isSelected()) {
                paint.setColor(onPrimary);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f + dp(16), dp(2), paint);
            }
        }

        private void drawGlyph(Canvas canvas, String key, int color, float opacity, float scale) {
            float[] points = GLYPHS.get(key);
            if (points == null || opacity <= 0) return;
            int save = canvas.save();
            canvas.scale(scale, scale, 12, 12);
            paint.setColor(color);
            paint.setAlpha(Math.round(255 * opacity));
            paint.setStrokeWidth(2);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            for (int i = 0; i < points.length;) {
                int count = (int) points[i++];
                path.rewind();
                path.moveTo(points[i++], points[i++]);
                for (int j = 1; j < Math.abs(count); j++) path.lineTo(points[i++], points[i++]);
                if (count < 0) path.close();
                paint.setStyle(count < 0 ? Paint.Style.FILL : Paint.Style.STROKE);
                canvas.drawPath(path, paint);
            }
            canvas.restoreToCount(save);
        }
    }

    private final class Visualizer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Visualizer() {
            super(activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            boolean running = playing && motionEnabled() && getWindowVisibility() == VISIBLE;
            float phase = (playhead() % 2400) / 2400f;
            float step = getWidth() / 32f;
            paint.setStrokeWidth(Math.min(dp(4), step * .45f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            float baseline = getHeight() - paint.getStrokeWidth() / 2f;
            for (int i = 0; i < 32; i++) {
                float envelope = 1f - Math.abs(i - 15.5f) / 17f;
                float ripple = .5f + .5f * waveSample(i * .12f - phase);
                float bar = dp(3) + getHeight() * .75f * envelope * ripple;
                float x = (i + .5f) * step;
                paint.setColor(i % 3 == 0 ? primary : onSecondaryContainer);
                paint.setAlpha(playing ? 180 : 100);
                canvas.drawLine(x, baseline - bar, x, baseline, paint);
            }
            // shortcut: follows playback position; upgrade when the core exposes audio samples.
            if (running) postInvalidateDelayed(42);
        }
    }

    private final class Wave extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float preview = -1;
        private long shownElapsed = -1, shownRemaining = -1;
        private String elapsedLabel = "", remainingLabel = "";

        Wave() {
            super(activity);
            setFocusable(true);
            setContentDescription("Playback position");
        }

        @Override protected void onDraw(Canvas canvas) {
            long elapsed = playhead();
            float fraction = preview >= 0 ? preview : duration <= 0 ? 0 : elapsed / (float) duration;
            float x0 = dp(8), x1 = getWidth() - dp(8), y = Math.min(dp(23), getHeight() * .35f);
            float timeBaseline = getHeight() - dp(10);
            float tip = x0 + Math.max(0, x1 - x0) * fraction;
            paint.setColor(high);
            paint.setStrokeWidth(dp(4));
            paint.setStrokeCap(Paint.Cap.ROUND);
            if (tip + dp(9) < x1) canvas.drawLine(tip + dp(9), y, x1, y, paint);
            paint.setColor(primary);
            if (tip + dp(11) < x1) canvas.drawCircle(x1, y, dp(2), paint);
            paint.setColor(primary);
            paint.setStyle(Paint.Style.STROKE);
            path.rewind();
            float phase = playing && motionEnabled() ? (SystemClock.uptimeMillis() % 1600) / 1600f : 0;
            float amplitude = playing ? dp(3) : 0;
            float wavelength = dp(33);
            for (float x = x0; x < tip; x += dp(2)) {
                float dy = amplitude * waveSample((x - x0) / wavelength + phase);
                if (x == x0) path.moveTo(x, y + dy); else path.lineTo(x, y + dy);
            }
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(tip, y + amplitude * waveSample((tip - x0) / wavelength + phase), dp(4), paint);
            paint.setColor(onVariant);
            paint.setTextSize(dp(12));
            paint.setTextAlign(Paint.Align.LEFT);
            long remaining = Math.max(0, duration - elapsed);
            if (shownElapsed != elapsed / 1000) {
                elapsedLabel = time(elapsed);
                shownElapsed = elapsed / 1000;
            }
            if (shownRemaining != remaining / 1000) {
                remainingLabel = "−" + time(remaining);
                shownRemaining = remaining / 1000;
            }
            canvas.drawText(elapsedLabel, x0, timeBaseline, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(remainingLabel, x1, timeBaseline, paint);
            if (playing && isShown()) {
                if (motionEnabled()) postInvalidateOnAnimation();
                else postInvalidateDelayed(500);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (duration <= 0) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // Fall through to place the preview exactly where the user touched.
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    preview = Math.max(0, Math.min(1, (e.getX() - dp(8)) / Math.max(1, getWidth() - dp(16))));
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) { seek(preview); preview = -1; }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    preview = -1;
                    invalidate();
                    return true;
                default: return false;
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo node) {
            super.onInitializeAccessibilityNodeInfo(node);
            node.setClassName(android.widget.SeekBar.class.getName());
            if (duration > 0) {
                node.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                    0, duration / 1000f, playhead() / 1000f));
                node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
                node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (duration > 0) {
                if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && arguments != null) {
                    seek(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) * 1000 / duration);
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    seek((playhead() + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 10000 : -10000)) / (float) duration);
                    return true;
                }
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }

    private static String time(long ms) {
        long seconds = ms / 1000;
        long remainder = seconds % 60;
        return (seconds / 60) + (remainder < 10 ? ":0" : ":") + remainder;
    }

    private static native float[] nativeGeometry(String kind);
    private static final float[] COVER = nativeGeometry("cover");
    private static final float[] CIRCLE_COVER = nativeGeometry("circle-cover");
    private static final float[] PLAYER_COVER = nativeGeometry("player-cover");
    private static final float[] WORDMARK = nativeGeometry("wordmark");
    private static final float[] BUTTON_FIVE = nativeGeometry("button5");
    private static final float[] BUTTON_NINE = nativeGeometry("button9");
    private static final float[] WAVE = nativeGeometry("wave");
    private static final java.util.Map<String, float[]> GLYPHS = new java.util.HashMap<>();
    static {
        for (String key : new String[]{"back", "down", "previous", "next", "play", "pause", "shuffle", "repeat", "repeat-one", "volume", "queue", "immersive", "home", "library"}) {
            GLYPHS.put(key, nativeGeometry(key));
        }
    }
    private static float waveSample(float phase) {
        float position = (phase - (float) Math.floor(phase)) * WAVE.length;
        int a = (int) position % WAVE.length;
        int b = (a + 1) % WAVE.length;
        return WAVE[a] + (WAVE[b] - WAVE[a]) * (position - (int) position);
    }
}
