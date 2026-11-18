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
import android.graphics.RadialGradient;
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
    private int surface, container, high, onSurface, onVariant, primary, onPrimary;
    private int secondaryContainer, onSecondaryContainer, tertiaryContainer, onTertiaryContainer;
    private boolean dark;
    private static final int HOME = 0, LIBRARY = 1, PLAYER = 2, IMMERSIVE = 3, QUEUE = 4, PLAYLIST = 5, SETTINGS = 6, EQUALIZER = 7;
    private static final int PREVIOUS = 2, NEXT = 3, SEEK = 4, SHUFFLE = 5, REPEAT = 6, PLAY = 7, SKIP_TO = 8, QUEUE_ADD = 9;
    private static final int QUEUE_REMOVE = 10, QUEUE_MOVE_UP = 11, QUEUE_MOVE_DOWN = 12;
    private static final String[] SECTIONS = {"Playlists", "Albums", "Artists", "Podcasts"};
    private static final ExecutorService DATA = Executors.newSingleThreadExecutor();
    private static final ExecutorService LIBRARY_DATA = Executors.newSingleThreadExecutor();
    /** Playlists get their own threads: a stuck reply can't hold up the library or the next try. */
    private static final ExecutorService PLAYLIST_DATA = Executors.newCachedThreadPool();
    private static final ExecutorService COMMANDS = Executors.newSingleThreadExecutor();
    private static final ExecutorService SEARCH_DATA = Executors.newCachedThreadPool();
    private static final ExecutorService ART = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getAllocationByteCount(); }
    };
    private final Set<String> loadingImages = new HashSet<>(), failedImages = new HashSet<>();

    private static native int nativeNavigate(int route, int action, int category);
    private static native boolean nativeConnect();
    private static native String[] nativePlayer(long revision);
    private static native String nativeLibrary();
    private static native boolean nativeRefreshLibrary();
    private static native String[] nativeEntries(int filter, int limit);
    private static native String nativeLiked();
    private static native String[] nativePlaylist(String uri, int offset);
    private static native String[] nativeSearch(byte[] response);
    private static native boolean nativeCommand(int action, long value, String uri);
    private static native int nativeSaved(String uri);
    private static native boolean nativeSave(String uri, boolean save);

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
    private ListView queueList;
    private TextView equalizerStatus;
    private List<Entry> upcoming = new ArrayList<>();
    private final List<Art> visibleArt = new ArrayList<>();
    private LinearLayout content;
    private ScrollView scroll;
    private ListView libraryList, playlistList;
    private android.widget.GridView libraryGrid;
    private TextView searchEmpty, searchMore;
    private boolean catalogSearch = true;
    private int searchRequest;
    private String searchPage = "", searchError = "";
    private List<Entry> catalogResults = new ArrayList<>();
    private int playlistRow, playlistTop;
    private TextView playbackSource, infoTrack, infoArtist, infoAlbum, infoAlbumArtist, infoRelease,
        infoPosition, infoRating, infoSource;
    private LinearLayout infoNext;
    private Entry playlist;
    private List<Entry> playlistTracks = new ArrayList<>();
    private int playlistOffset;
    private boolean playlistLoading, playlistFailed;
    private TextView trackTitle, trackArtist, albumTitle, heroTitle, heroArtist, heroBadge, explicitBadge;
    private Art trackArt, heroArt, atmosphere;
    private Glyph playButton, heroPlay, shuffleButton, repeatButton;
    private Wave wave;

    SpatchUi(Activity activity) {
        super(activity);
        this.activity = activity;
        density = getResources().getDisplayMetrics().density;
        prefs = activity.getSharedPreferences("spatch_ui", Context.MODE_PRIVATE);
        Audio.initialize(activity.getApplicationContext(), prefs);
        applyPalette();
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        setForeground(null);
        // Content sits inside the system bars; the backdrop and motes draw under them, edge to edge.
        setOnApplyWindowInsetsListener((v, insets) -> {
            inset.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                      insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            if (content != null) content.setPadding(inset.left, inset.top, inset.right, inset.bottom);
            return insets;
        });
        render();
    }

    private final android.content.SharedPreferences prefs;
    private final android.graphics.Rect inset = new android.graphics.Rect();

    private boolean pref(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }

    /** Theme: 0 follows the system, 1 dark, 2 light. Colours come from the wallpaper (Material You). */
    private void applyPalette() {
        int theme = prefs.getInt("theme", 0);
        dark = theme == 0 ? (getResources().getConfiguration().uiMode & 0x30) == 0x20 : theme == 1;
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
        int accent = prefs.getInt("accent", 0);
        if (accent != 0) seedPalette(accent);
        String want = artworkSeedWanted();
        if (want != null) appliedSeed = want;
        if (!appliedSeed.isEmpty()) seedPalette(Integer.parseInt(appliedSeed));
        setBackgroundColor(surface);
    }

    private static final java.util.Map<String, Integer> SEEDS = new java.util.concurrent.ConcurrentHashMap<>();
    private String appliedSeed = "";

    /** "" for wallpaper colours, the seed for artwork colours, or null to keep what's shown until the art loads. */
    private String artworkSeedWanted() {
        if (!pref("art_colors", true)) return "";
        String cover = screen == PLAYLIST && playlist != null ? playlist.image
            : screen == PLAYER || screen == IMMERSIVE || screen == QUEUE ? image : null;
        if (cover == null) return "";
        if (cover.isEmpty()) return "";
        Integer seed = SEEDS.get(cover);
        return seed == null ? null : String.valueOf(seed);
    }

    /** Material-style tonal roles from one artwork colour: hue kept, chroma tamed, tones fixed per role. */
    private void seedPalette(int seed) {
        float[] hsv = new float[3];
        Color.colorToHSV(seed, hsv);
        float h = hsv[0], c = Math.min(hsv[1], .85f);
        float vivid = Math.max(.28f, Math.min(.72f, c));
        float soft = Math.min(c, .38f), faint = Math.min(c, .22f);
        float h3 = (h + 48f) % 360f;
        if (dark) {
            primary = hsl(h, vivid, .80f); onPrimary = hsl(h, .5f * Math.min(1, c * 3), .17f);
            secondaryContainer = hsl(h, soft, .29f); onSecondaryContainer = hsl(h, soft, .91f);
            tertiaryContainer = hsl(h3, soft, .29f); onTertiaryContainer = hsl(h3, soft, .91f);
            surface = hsl(h, faint, .075f); container = hsl(h, faint, .125f); high = hsl(h, faint, .19f);
            onSurface = hsl(h, faint * .6f, .93f); onVariant = hsl(h, faint, .77f);
        } else {
            primary = hsl(h, vivid, .38f); onPrimary = Color.WHITE;
            secondaryContainer = hsl(h, Math.max(soft, .3f) * Math.min(1, c * 3), .87f); onSecondaryContainer = hsl(h, soft, .13f);
            tertiaryContainer = hsl(h3, Math.max(soft, .3f) * Math.min(1, c * 3), .87f); onTertiaryContainer = hsl(h3, soft, .13f);
            surface = hsl(h, faint, .975f); container = hsl(h, faint, .935f); high = hsl(h, faint, .895f);
            onSurface = hsl(h, faint * .6f, .1f); onVariant = hsl(h, faint, .33f);
        }
    }

    private static int hsl(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = l - c / 2;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; } else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; } else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return Color.rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }

    /** The most colourful of a few averaged regions, so a small accent beats a large grey field only when it's vivid. */
    static int seedOf(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 6, 6, true);
        float[] hsv = new float[3];
        int best = small.getPixel(3, 3);
        float score = -1;
        for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
            int p = small.getPixel(x, y);
            Color.colorToHSV(p, hsv);
            float v = hsv[1] * (1f - Math.abs(hsv[2] - .6f));
            if (v > score) { score = v; best = p; }
        }
        if (small != bitmap) small.recycle();
        return best;
    }

    private void paintSystemBars() {
        android.view.Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        View decor = window.getDecorView();
        int bars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        int current = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(dark ? current & ~bars : current | bars);
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
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        paintSystemBars();
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
            if (equalizerStatus != null) equalizerStatus.setText(Audio.eqStatus);
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
                    libraryLoadedAt = SystemClock.uptimeMillis();
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

    private String contextUri = "";

    /** Whether playback comes from this library item; Liked Songs has several URI spellings. */
    private boolean playingFrom(String item) {
        if (contextUri.isEmpty() || item.isEmpty()) return false;
        return item.equals(contextUri) || (liked(item) && liked(contextUri));
    }

    private static boolean liked(String uri) {
        return uri.equals("spotify:collection") || uri.endsWith(":collection") || uri.endsWith(":collection:tracks");
    }
    private long libraryLoadedAt;
    private boolean refreshing;

    /** Back on Home or Library after a while: fetch the library again quietly and redraw only if it changed. */
    private void refreshLibraryIfStale() {
        long now = SystemClock.uptimeMillis();
        if (!libraryReady || refreshing || now - libraryLoadedAt < 90_000) return;
        refreshing = true;
        libraryLoadedAt = now;
        LIBRARY_DATA.execute(() -> {
            boolean changed = nativeRefreshLibrary();
            post(() -> {
                refreshing = false;
                if (changed && (screen == HOME || screen == LIBRARY) && sheetClose == null) redrawPage();
            });
        });
    }

    private void applyPlayer(String[] state) {
        revision = Long.parseLong(state[0]);
        boolean trackChanged = !uri.equals(state[1]);
        boolean queueHeaderChanged = trackChanged || !title.equals(state[2]) || !artist.equals(state[3])
            || !image.equals(state[5]) || !source.equals(state[11]);
        if (trackChanged) checkSaved(state[1]);
        uri = state[1]; title = state[2]; artist = state[3]; album = state[4]; image = state[5];
        playing = Boolean.parseBoolean(state[6]);
        if (pendingPlayback != null && (trackChanged || pendingPlayback == playing)) {
            pendingPlayback = null;
        }
        shuffle = Boolean.parseBoolean(state[7]);
        repeat = Integer.parseInt(state[8]);
        position = Long.parseLong(state[9]);
        long coreDuration = Long.parseLong(state[10]);
        if (coreDuration > 0 || trackChanged) duration = Math.max(0, coreDuration);
        if (duration == 0 && !uri.isEmpty()) {
            for (Entry track : playlistTracks) {
                if (track.uri.equals(uri) && track.durationMs > 0) {
                    duration = track.durationMs;
                    break;
                }
            }
        }
        source = state[11];
        albumArtist = state[12]; release = state[13]; albumPosition = state[14]; rating = state[15];
        stamp = SystemClock.uptimeMillis();
        List<Entry> next = new ArrayList<>();
        String context = state[16];
        boolean contextChanged = !context.equals(contextUri);
        contextUri = context;
        if (contextChanged && (screen == HOME || screen == LIBRARY) && sheetClose == null && revision > 1) post(this::redrawPage);
        for (int i = 17; i + 5 < state.length; i += 6) {
            next.add(new Entry(state[i].isEmpty() ? "Unknown track" : state[i], state[i + 1],
                state[i + 3], state[i + 2], -1, state[i + 4], Boolean.parseBoolean(state[i + 5])));
        }
        boolean queueChanged = !upcoming.equals(next);
        if (queueChanged) upcoming = next;
        if (screen == QUEUE && (queueChanged || queueHeaderChanged)) {
            int first = queueList == null ? 0 : queueList.getFirstVisiblePosition();
            View row = queueList == null ? null : queueList.getChildAt(0);
            int offset = row == null ? 0 : row.getTop() - queueList.getPaddingTop();
            redrawPage();
            if (queueList != null) queueList.setSelectionFromTop(Math.min(first, queueList.getCount() - 1), offset);
        }
        else refreshPlayer();
    }

    private void command(int action, long value, String target) {
        if (pref("haptics", true) && (action == PREVIOUS || action == NEXT)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        COMMANDS.execute(() -> {
            boolean success = nativeCommand(action, value, target);
            if (!success || action == QUEUE_ADD) post(() -> {
                if (!isAttachedToWindow()) return;
                String message = success ? "Added to queue"
                    : action >= QUEUE_ADD ? "Couldn’t update the queue" : "Playback command unavailable";
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** Taps on empty space stop here so they never reach Spotify's views below, but nothing lights up. */
    @Override public boolean onTouchEvent(MotionEvent event) { return true; }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** For OnBackInvokedDispatcher: on Android 14+ the page follows the back gesture before it leaves. */
    Object backCallback() {
        if (android.os.Build.VERSION.SDK_INT >= 34) return Back34.make(this);
        return (android.window.OnBackInvokedCallback) this::back;
    }

    private static final class Back34 {
        static Object make(SpatchUi ui) {
            return new android.window.OnBackAnimationCallback() {
                @Override public void onBackProgressed(android.window.BackEvent event) {
                    ui.peek(event.getProgress(), event.getSwipeEdge() == android.window.BackEvent.EDGE_LEFT);
                }
                @Override public void onBackCancelled() { ui.peekCancelled(); }
                @Override public void onBackInvoked() { ui.back(); }
            };
        }
    }

    /** Predictive back: the page shrinks toward the far edge as the gesture grows (not on Home, which leaves the app). */
    void peek(float progress, boolean fromLeft) {
        if (screen == HOME || content == null || sheetClose != null || !motionEnabled()) return;
        float eased = 1f - (1f - progress) * (1f - progress);
        content.setPivotX(fromLeft ? content.getWidth() : 0);
        content.setPivotY(content.getHeight() / 2f);
        content.setScaleX(1f - .1f * eased);
        content.setScaleY(1f - .1f * eased);
        content.setTranslationX((fromLeft ? 1 : -1) * dp(8) * eased);
        content.setAlpha(1f - .25f * eased);
    }

    void peekCancelled() {
        if (content == null) return;
        content.animate().scaleX(1f).scaleY(1f).translationX(0).alpha(1f).setDuration(220).start();
    }

    void back() {
        if (sheetClose != null) {
            sheetClose.run();
            return;
        }
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
        float fromAngle = coverTransition ? original.outlineAngle() : 0;
        // The immersive disc turns as a whole view; the morph starts from that turn instead of snapping upright.
        float fromTurn = coverTransition ? original.getRotation() % 360f : 0;
        rememberScroll();
        route = next;
        screen = route & 7;
        int selected = (route >> 7) - 1;
        if (filter != selected) collectionRow = collectionOffset = 0;
        filter = selected;
        render();
        if (coverTransition && trackArt != null) moveArtwork(from, cover, original.shape, trackArt, fromAngle, fromTurn);
        if (!coverTransition && motionEnabled() && content != null) {
            boolean sideways = previous <= LIBRARY && destination <= LIBRARY && previous != destination;
            View moving = sideways ? content.getChildAt(content.getChildCount() - 1) : content;
            moving.setAlpha(0f);
            if (sideways) moving.setTranslationX((destination > previous ? 1 : -1) * dp(36));
            else moving.setTranslationY(dp(destination == SETTINGS || destination == PLAYLIST || destination == QUEUE ? 18 : -10));
            moving.animate().alpha(1f).translationX(0).translationY(0).setDuration(280)
                .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
        }
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
        if (view.getRotation() != 0 && view.getParent() instanceof View) {
            // A turning disc reports its rotated corner; measure from its parent and unrotated layout instead.
            ((View) view.getParent()).getLocationInWindow(position);
            position[0] += view.getLeft();
            position[1] += view.getTop();
        } else {
            view.getLocationInWindow(position);
        }
        float side = view.coverSide();
        float x = position[0] - origin[0] + (view.getWidth() - side) * .5f;
        float y = position[1] - origin[1] + (view.getHeight() - side) * .5f;
        return new RectF(x, y, x + side, y + side);
    }

    private void moveArtwork(RectF from, Bitmap bitmap, int fromShape, Art target, float fromAngle, float fromTurn) {
        target.setAlpha(0f);
        getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                if (trackArt != target) return true;
                RectF to = coverBounds(target);
                CoverTransition moving = new CoverTransition(from, to, bitmap,
                    fromShape, target.shape);
                moving.fromAngle = fromAngle;
                moving.fromTurn = fromTurn > 180 ? fromTurn - 360 : fromTurn;
                getOverlay().add(moving);
                android.animation.ValueAnimator animation = android.animation.ValueAnimator.ofFloat(0f, 1f);
                // Long enough to read as one shape becoming the other, eased like Material's emphasized motion.
                animation.setDuration(shapeChanges(fromShape, target.shape) ? 580 : 420);
                animation.setInterpolator(new android.view.animation.PathInterpolator(.3f, 0f, 0f, 1f));
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
        private final Matrix spin = new Matrix();
        float progress, fromAngle, fromTurn;

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
            if (fromAngle != 0) {
                // The player's scallop is mid-turn: unwind it while it morphs so nothing jumps.
                spin.setRotate(fromAngle * (1 - progress), x + side / 2, y + side / 2);
                path.transform(spin);
            }
            int turned = -1;
            if (fromTurn != 0) {
                turned = canvas.save();
                canvas.rotate(fromTurn * (1 - progress), x + side / 2, y + side / 2);
            }
            paint.setColor(secondaryContainer);
            if (shader != null) {
                float scale = Math.max(side / bitmap.getWidth(), side / bitmap.getHeight());
                matrix.setScale(scale, scale);
                matrix.postTranslate(x + (side - bitmap.getWidth() * scale) / 2, y + (side - bitmap.getHeight() * scale) / 2);
                shader.setLocalMatrix(matrix);
                paint.setShader(shader);
            }
            canvas.drawPath(path, paint);
            if (turned >= 0) canvas.restoreToCount(turned);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static boolean shapeChanges(int a, int b) { return coverPath(a) != coverPath(b); }

    private static float[] coverPath(int shape) {
        return shape == Art.ROUND || shape == Art.DISC || shape == Art.PLAYER_ROUND ? CIRCLE_COVER : shape == Art.IMMERSIVE ? COVER : PLAYER_COVER;
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
        if (screen == LIBRARY && libraryGrid != null) { collectionRow = libraryGrid.getFirstVisiblePosition(); collectionOffset = 0; }
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
        searchRequest++;
        sheetClose = null;
        hideKeyboard();
        requestFocus();
        if (immersiveDrift != null) immersiveDrift.cancel();
        immersiveDrift = null;
        immersiveChrome = null;
        immersiveTop = null;
        applyPalette();
        paintSystemBars();
        if (screen == IMMERSIVE && pref("keep_on", true)) activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        removeAllViews();
        visibleArt.clear();
        scroll = null;
        libraryList = playlistList = queueList = null;
        equalizerStatus = null;
        libraryGrid = null;
        searchEmpty = searchMore = null;
        miniShape = null;
        miniColor = 0;
        trackTitle = trackArtist = albumTitle = heroTitle = heroArtist = heroBadge = explicitBadge = null;
        trackArt = heroArt = atmosphere = null;
        playbackSource = infoTrack = infoArtist = infoAlbum = null;
        infoAlbumArtist = infoRelease = infoPosition = infoRating = infoSource = null;
        infoNext = null;
        playButton = heroPlay = shuffleButton = repeatButton = like = null;
        wave = null;
        if (screen == PLAYER || screen == IMMERSIVE) {
            atmosphere = new Art(Art.BACKDROP);
            addView(atmosphere, new FrameLayout.LayoutParams(-1, -1));
            if (screen == IMMERSIVE && motionEnabled()) {
                // Ken Burns drift: a property animation, so the blurred layer is reused and only moved.
                View drifting = atmosphere;
                immersiveDrift = android.animation.ObjectAnimator.ofPropertyValuesHolder(drifting,
                    android.animation.PropertyValuesHolder.ofFloat("scaleX", 1.12f, 1.32f),
                    android.animation.PropertyValuesHolder.ofFloat("scaleY", 1.12f, 1.32f),
                    android.animation.PropertyValuesHolder.ofFloat("translationX", -dp(18), dp(22)),
                    android.animation.PropertyValuesHolder.ofFloat("rotation", -3f, 3f));
                immersiveDrift.setDuration(16000);
                immersiveDrift.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                immersiveDrift.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                immersiveDrift.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                drifting.setScaleX(1.12f);
                drifting.setScaleY(1.12f);
            }
        }
        content = column();
        content.setPadding(inset.left, inset.top, inset.right, inset.bottom);
        addView(content, new FrameLayout.LayoutParams(-1, -1));
        if (screen == HOME || screen == LIBRARY) {
            buildBrowse();
            refreshLibraryIfStale();
        }
        else if (screen == QUEUE) buildQueue();
        else if (screen == PLAYLIST) buildPlaylist();
        else if (screen == SETTINGS) buildSettings();
        else if (screen == EQUALIZER) buildEqualizer();
        else buildPlayer();
        refreshPlayer();
        int restore = screen == HOME ? homeScroll : 0;
        ScrollView current = scroll;
        if (current != null && restore != 0) current.post(() -> current.scrollTo(0, restore));
    }

    private void buildBrowse() {
        addTopBar();
        if (screen == HOME) {
            LinearLayout feed = feed();
            FrameLayout hero = new FrameLayout(activity);
            hero.setBackground(background(secondaryContainer, 28));
            hero.setClipToOutline(true);
            heroArt = new Art(Art.WIDE);
            hero.addView(heroArt, new FrameLayout.LayoutParams(-1, dp(212)));
            View scrim = new View(activity);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                secondaryContainer & 0x00ffffff, (secondaryContainer & 0x00ffffff) | 0xb0000000, secondaryContainer}));
            hero.addView(scrim, new FrameLayout.LayoutParams(-1, dp(132), Gravity.BOTTOM));
            LinearLayout badge = row();
            badge.setPadding(dp(10), dp(5), dp(12), dp(5));
            badge.setBackground(background((surface & 0x00ffffff) | 0xb8000000, 14));
            badge.addView(new Bars(primary), new LinearLayout.LayoutParams(dp(12), dp(11)));
            TextView now = text("NOW PLAYING", 11, onSurface, true);
            heroBadge = now;
            now.setLetterSpacing(.1f);
            now.setPadding(dp(7), 0, 0, 0);
            badge.addView(now);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2);
            badgeParams.setMargins(dp(12), dp(12), 0, 0);
            hero.addView(badge, badgeParams);
            LinearLayout heroFooter = row();
            heroFooter.setPadding(dp(16), dp(8), dp(10), dp(10));
            LinearLayout details = column();
            heroTitle = text("", 27, onSecondaryContainer, true);
            heroArtist = text("", 14, onSecondaryContainer, false);
            single(heroTitle); single(heroArtist);
            details.addView(heroTitle);
            details.addView(heroArtist);
            heroFooter.addView(details, weighted());
            heroPlay = glyph("play", "Play", true, this::toggle);
            heroFooter.addView(heroPlay, control(60));
            hero.addView(heroFooter, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            makeClickable(hero, "Open player", this::openPlayer);
            springPress(hero);
            if (motionEnabled() && scroll != null) {
                // Parallax: the cover drifts slower than the page, inside the card's clip.
                Art drifting = heroArt;
                scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> drifting.setTranslationY(Math.min(y, dp(220)) * .35f));
            }
            feed.addView(hero, match(-2));
            gap(feed, 12);
            feed.addView(likedCard(), match(-2));
            if (pref("home_quick", true)) addQuickGrid(feed);
            boolean any = false;
            for (int category = 0; category < SECTIONS.length; category++) {
                if (pref("home_shelf_" + category, true)) any |= addShelf(feed, category);
                else any |= !entries(category, 1).isEmpty();
            }
            if (!any) libraryMessage(feed);
        } else {
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
            choices.setClipToPadding(false);
            boolean grid = pref("library_grid", false);
            Glyph layout = glyph(grid ? "queue" : "grid", grid ? "Show as list" : "Show as grid", false, () -> {
                prefs.edit().putBoolean("library_grid", !pref("library_grid", false)).apply();
                collectionRow = collectionOffset = 0;
                render();
            });
            layout.setBackground(background(high, 20));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.rightMargin = dp(6);
            chips.addView(layout, 0, lp);
            boolean alpha = pref("library_az", false);
            TextView sort = chip(alpha ? "A–Z" : "Recent", high, onSurface, () -> {
                prefs.edit().putBoolean("library_az", !pref("library_az", false)).apply();
                collectionRow = collectionOffset = 0;
                render();
            });
            sort.setContentDescription(alpha ? "Sorted A to Z, tap for recent" : "Sorted by recent, tap for A to Z");
            sort.setBackground(background(high, 20));
            sort.setMinHeight(dp(40));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(40));
            sp.rightMargin = dp(6);
            chips.addView(sort, 1, sp);
            choices.addView(chips);
            content.addView(choices, match(-2));
            List<Entry> entries = entries(filter, 300);
            if (pref("library_az", false)) {
                // Pinned items (Liked Songs first) stay on top; the rest sort by name.
                List<Entry> pinned = new ArrayList<>();
                for (Entry e : entries) if (e.uri.contains("collection")) pinned.add(e);
                entries.removeAll(pinned);
                java.text.Collator order = java.text.Collator.getInstance();
                order.setStrength(java.text.Collator.PRIMARY);
                entries.sort((a, b) -> order.compare(a.title, b.title));
                entries.addAll(0, pinned);
            }
            boolean online = catalogSearch && !libraryQuery.trim().isEmpty();
            List<Entry> shown = online ? new ArrayList<>(catalogResults) : matching(entries, libraryQuery);
            choices.setVisibility(online ? GONE : VISIBLE);
            LinearLayout scope = row();
            scope.setPadding(dp(16), dp(4), dp(16), 0);
            for (int i = 0; i < 2; i++) {
                final boolean spotify = i == 0;
                TextView option = chip(spotify ? "Spotify" : "Your library",
                    catalogSearch == spotify ? secondaryContainer : surface,
                    catalogSearch == spotify ? onSecondaryContainer : onVariant, () -> {
                        catalogSearch = spotify;
                        catalogResults.clear();
                        collectionRow = collectionOffset = 0;
                        render();
                    });
                option.setSelected(catalogSearch == spotify);
                scope.addView(option, new LinearLayout.LayoutParams(0, dp(44), 1));
            }
            content.addView(scope, match(-2));
            content.addView(searchField(entries, shown, choices), match(-2));
            searchEmpty = text(online ? "Searching Spotify…" : "No matches in your library", 16, onVariant, false);
            searchEmpty.setPadding(dp(20), dp(20), dp(20), dp(12));
            searchEmpty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            searchEmpty.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
            content.addView(searchEmpty, match(-2));
            searchMore = chip("Load more results", secondaryContainer, onSecondaryContainer,
                () -> searchCatalog(shown, libraryQuery.trim(), searchPage, searchRequest));
            searchMore.setVisibility(GONE);
            content.addView(searchMore, match(-2));
            if (pref("library_grid", false)) libraryGrid = entryGrid(shown);
            else {
                libraryList = entryList(shown);
                libraryList.setSelectionFromTop(collectionRow, collectionOffset);
            }
            if (online) scheduleSearch(shown);

        }
        // Keep the feed visible around the floating player, with room to reach its last row.
        View browse = content.getChildAt(content.getChildCount() - 1);
        content.removeView(browse);
        ((ViewGroup) browse).setClipToPadding(false);
        browse.setPadding(browse.getPaddingLeft(), browse.getPaddingTop(),
            browse.getPaddingRight(), browse.getPaddingBottom() + dp(96));
        FrameLayout dock = new FrameLayout(activity);
        dock.addView(browse, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(activity);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{surface & 0x00ffffff, (surface & 0x00ffffff) | 0xc0000000, surface}));
        dock.addView(fade, new FrameLayout.LayoutParams(-1, dp(104), Gravity.BOTTOM));
        addMini(dock);
        content.addView(dock, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    /** Wordmark, settings, and a connected Home/Library button group: navigation lives up top. */
    private void addTopBar() {
        LinearLayout bar = column();
        bar.setPadding(dp(16), dp(6), dp(12), dp(10));
        LinearLayout heading = row();
        if (screen == LIBRARY) {
            heading.addView(glyph("back", "Back to Home", false, this::back), control(44));
            heading.addView(new View(activity), new LinearLayout.LayoutParams(dp(4), 1));
        }
        TextView mark = text(screen == HOME ? "Spatch" : "Library", 34, onSurface, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) mark.setAccessibilityHeading(true);
        heading.addView(mark);
        WordmarkDecoration flourish = new WordmarkDecoration();
        heading.addView(flourish, control(44));
        mark.setOnClickListener(v -> {
            if (pref("haptics", true)) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            flourish.spin();
        });
        heading.addView(new View(activity), weighted());
        if (screen == HOME) {
            // Library lives one tap away in the header; no tab bar top or bottom.
            Glyph library = glyph("library", "Your library", false, () -> openLibrary(filter));
            library.setBackground(background(high, 24));
            heading.addView(library, control(48));
            heading.addView(new View(activity), new LinearLayout.LayoutParams(dp(8), 1));
        }
        Glyph settings = glyph("settings", "Settings", false, () -> navigate(7, 0));
        settings.setBackground(background(high, 24));
        heading.addView(settings, control(48));
        bar.addView(heading, match(dp(60)));
        if (screen == HOME) {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            String hello = hour < 5 ? "Up late? Here’s something soft" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
            TextView greeting = text(hello, 15, onVariant, false);
            greeting.setPadding(0, 0, 0, dp(2));
            bar.addView(greeting, match(-2));
        } else if (libraryReady) {
            // What's in the library, at a glance.
            StringBuilder summary = new StringBuilder();
            String[] nouns = {"playlist", "album", "artist", "podcast"};
            for (int i = 0; i < nouns.length; i++) {
                int count = entries(i, 1000).size();
                if (count == 0) continue;
                if (summary.length() > 0) summary.append(" · ");
                summary.append(count).append(' ').append(nouns[i]).append(count == 1 ? "" : "s");
            }
            if (summary.length() > 0) {
                TextView line = text(summary.toString(), 15, onVariant, false);
                line.setPadding(0, 0, 0, dp(2));
                single(line);
                bar.addView(line, match(-2));
            }
        }
        content.addView(bar, match(-2));
    }

    /** Six recent library items as compact tiles, two per row: the fastest way back into something. */
    private void addQuickGrid(LinearLayout feed) {
        // What you opened or played here most recently first, then Spotify's own library order.
        List<Entry> items = history();
        for (Entry e : entries(-1, 12)) {
            boolean seen = false;
            for (Entry h : items) seen |= h.uri.equals(e.uri);
            if (!seen) items.add(e);
        }
        items.removeIf(e -> e.uri.isEmpty() || e.uri.contains("collection"));
        if (items.size() < 2) return;
        gap(feed, 18);
        feed.addView(text("Jump back in", 22, onSurface, true), match(-2));
        gap(feed, 10);
        for (int i = 0; i + 1 < Math.min(items.size(), 6); i += 2) {
            LinearLayout line = row();
            for (int j = 0; j < 2; j++) {
                Entry entry = items.get(i + j);
                LinearLayout tile = row();
                tile.setBackground(background(container, 16));
                tile.setPadding(dp(6), dp(6), dp(10), dp(6));
                Art art = new Art(entry.category == 2 ? Art.ROUND : Art.SQUARE);
                art.small = true;
                art.letter = initial(entry.title);
                art.bind(entry.image);
                tile.addView(art, control(44));
                boolean playingFrom = playingFrom(entry.uri);
                TextView name = text(entry.title, 14, playingFrom ? primary : onSurface, true);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setPadding(dp(10), 0, 0, 0);
                tile.addView(name, weighted());
                if (playingFrom) tile.addView(new Bars(primary), new LinearLayout.LayoutParams(dp(14), dp(13)));
                makeClickable(tile, (entry.category == 0 ? "Open " : "Play ") + entry.title, () -> selectEntry(entry));
                springPress(tile);
                longPress(tile, entry);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1);
                if (j == 1) p.leftMargin = dp(8);
                line.addView(tile, p);
            }
            LinearLayout.LayoutParams lp = match(-2);
            lp.bottomMargin = dp(8);
            feed.addView(line, lp);
        }
    }

    private View likedCard() {
        LinearLayout card = row();
        card.setPadding(dp(14), dp(10), dp(16), dp(10));
        card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{tertiaryContainer, secondaryContainer}));
        ((GradientDrawable) card.getBackground()).setCornerRadius(dp(24));
        Glyph heart = new Glyph("heart", false);
        heart.ink = onTertiaryContainer;
        heart.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        heart.setBackground(background((onTertiaryContainer & 0x00ffffff) | 0x22000000, 20));
        card.addView(heart, control(44));
        LinearLayout words = column();
        words.setPadding(dp(14), 0, 0, 0);
        words.addView(text("Liked Songs", 17, onTertiaryContainer, true));
        TextView hint = text("Everything you’ve hearted", 13, onTertiaryContainer, false);
        hint.setAlpha(.8f);
        words.addView(hint);
        card.addView(words, weighted());
        Glyph go = new Glyph("chevron", false);
        go.ink = onTertiaryContainer;
        go.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        go.setAlpha(.6f);
        card.addView(go, control(28));
        makeClickable(card, "Open Liked Songs", () -> {
            String likedUri = nativeLiked();
            openPlaylist(new Entry("Liked Songs", "", likedUri == null || likedUri.isEmpty()
                ? "spotify:collection:tracks" : likedUri, "", 0));
        });
        springPress(card);
        return card;
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
            art.letter = initial(entry.title);
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
                longPress(tile, entry);
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
        miniShape = background(miniTint(), 28);
        mini.setBackground(miniShape);
        mini.setElevation(dp(4));
        trackArt = new Art(pref("round_mini", true) ? Art.ROUND : Art.SQUARE);
        trackArt.ring = true;
        mini.addView(trackArt, new LinearLayout.LayoutParams(dp(46), dp(46)));
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
        Glyph previous = glyph("previous", "Previous track", false, () -> command(PREVIOUS, 0, ""));
        Glyph next = glyph("next", "Next track", false, () -> command(NEXT, 0, ""));
        previous.glass = next.glass = true;
        addTransport(mini, previous, 48, true, false);
        playButton = glyph("play", "Play", true, this::toggle);
        addTransport(mini, playButton, 48, false, false);
        addTransport(mini, next, 48, false, true);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.setMargins(dp(12), 0, dp(12), dp(12));
        dock.addView(mini, params);
    }

    private void buildPlayer() {
        boolean full = screen == IMMERSIVE;
        if (full) {
            buildImmersive();
            return;
        }
        if (!full) {
            LinearLayout top = row();
            top.setPadding(dp(10), dp(6), dp(10), 0);
            top.addView(glyph("down", "Close player", false, this::back), control(48));
            top.addView(timerButton(), control(48));
            LinearLayout from = column();
            TextView caption = text("PLAYING FROM", 11, onVariant, true);
            caption.setLetterSpacing(.12f);
            caption.setGravity(Gravity.CENTER);
            from.addView(caption, match(-2));
            playbackSource = text("", 15, onSurface, true);
            single(playbackSource);
            playbackSource.setGravity(Gravity.CENTER);
            from.addView(playbackSource, match(-2));
            top.addView(from, weighted());
            top.addView(glyph("immersive", "Open immersive mode", false, () -> navigate(3, 0)), control(48));
            TextView eq = chip("EQ", high, primary, () -> navigate(8, 0));
            eq.setContentDescription("Equalizer");
            eq.setPadding(0, 0, 0, 0);
            top.addView(eq, control(48));
            top.addView(glyph("queue", "Queue", false, () -> navigate(4, 0)), control(48));
            content.addView(top, match(-2));
        }

        LinearLayout body = column();
        body.setPadding(dp(16), 0, dp(16), dp(12));
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        if (full) content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        trackArt = new Art(new int[]{Art.IMMERSIVE, Art.PLAYER_ROUND, Art.PLAYER_ART}[Math.max(0, Math.min(2, prefs.getInt("art_shape", 0)))]);
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
        marquee(trackTitle);
        trackArtist = text("", 17, onVariant, false);
        albumTitle = text("", 13, onVariant, false);
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.TOP);
        titleRow.addView(trackTitle, weighted());
        trackTitle.setOnLongClickListener(v -> shareSheet());
        titleRow.addView(likeButton(), control(48));
        details.addView(titleRow, match(-2));
        details.addView(artistLine(false), match(-2));
        details.addView(albumTitle, match(-2));
        gap(details, full ? 2 : 8);
        wave = new Wave();
        details.addView(wave, match(dp(full ? 54 : 65)));
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        shuffleButton = glyph("shuffle", "Shuffle", false, () -> command(SHUFFLE, shuffle ? 0 : 1, ""));
        repeatButton = glyph("repeat", "Repeat", false, () -> command(REPEAT, (repeat + 1) % 3, ""));
        playButton = glyph("play", "Play", true, this::toggle);
        addTransport(controls, shuffleButton, 48, true, false);
        addTransport(controls, seeker(false), 50, false, false);
        addTransport(controls, playButton, 74, false, false);
        addTransport(controls, seeker(true), 50, false, false);
        addTransport(controls, repeatButton, 48, false, true);
        details.addView(controls, match(-2));
        if (pref("details", true)) {
            addPlayerDetails(body);
        } else {
            content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    private View immersiveChrome;
    private View immersiveTop;
    private GradientDrawable miniShape;
    private int miniColor;

    /** The mini player's fill: the container colour, washed with the cover's hue when artwork colours are on. */
    private int miniTint() {
        Integer seed = pref("art_colors", true) ? SEEDS.get(image) : null;
        if (seed == null) return high;
        float[] hsv = new float[3];
        Color.colorToHSV(seed, hsv);
        return hsl(hsv[0], Math.min(hsv[1], .32f), dark ? .21f : .87f);
    }

    private void refreshMini() {
        if (miniShape == null) return;
        int target = miniTint();
        if (target == miniColor) return;
        int from = miniColor == 0 ? target : miniColor;
        miniColor = target;
        if (!motionEnabled() || from == target) { miniShape.setColor(target); return; }
        android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofArgb(from, target);
        fade.setDuration(600);
        GradientDrawable shape = miniShape;
        fade.addUpdateListener(f -> shape.setColor((int) f.getAnimatedValue()));
        fade.start();
    }
    private android.animation.ObjectAnimator immersiveDrift;

    /** Immersive: the artwork floats as a slowly turning disc over its own blurred light. */
    private void buildImmersive() {
        if (pref("motes", true)) addView(new Motes(), 1, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = row();
        top.setPadding(dp(10), dp(6), dp(10), 0);
        top.addView(glyph("down", "Close immersive mode", false, this::back), control(48));
        LinearLayout from = column();
        from.setGravity(Gravity.CENTER);
        TextView caption = text("PLAYING FROM", 11, onVariant, true);
        caption.setLetterSpacing(.12f);
        caption.setGravity(Gravity.CENTER);
        from.addView(caption, match(-2));
        playbackSource = text("", 15, onSurface, true);
        single(playbackSource);
        playbackSource.setGravity(Gravity.CENTER);
        from.addView(playbackSource, match(-2));
        top.addView(from, weighted());
        top.addView(likeButton(), control(48));
        top.addView(timerButton(), control(48));
        top.addView(glyph("queue", "Queue", false, () -> navigate(4, 0)), control(48));
        content.addView(top, match(-2));
        immersiveTop = top;

        FrameLayout stage = new FrameLayout(activity);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        if (pref("glow", true)) addView(new Glow(stage), 1, new FrameLayout.LayoutParams(-1, -1));
        trackArt = new Art(pref("spin_art", true) ? Art.DISC : Art.PLAYER_ART);
        trackArt.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        makeClickable(trackArt, "Return to player", () -> navigate(3, 0));
        trackArt.setForeground(null);
        artworkGestures(trackArt, false);
        FrameLayout.LayoutParams artParams = new FrameLayout.LayoutParams(-1, -1);
        artParams.setMargins(dp(36), dp(28), dp(36), dp(28));
        stage.addView(trackArt, artParams);
        if (trackArt.shape == Art.DISC) stage.addView(new Sheen(), new FrameLayout.LayoutParams(artParams));
        content.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout chrome = column();
        chrome.setPadding(dp(20), 0, dp(20), dp(16));
        immersiveChrome = chrome;
        trackTitle = text("", 30, onSurface, true);
        trackTitle.setGravity(Gravity.CENTER);
        trackTitle.setOnLongClickListener(v -> shareSheet());
        marquee(trackTitle);
        chrome.addView(trackTitle, match(-2));
        trackArtist = text("", 17, onVariant, false);
        LinearLayout byline = artistLine(true);
        byline.setPadding(0, dp(4), 0, 0);
        chrome.addView(byline, match(-2));
        gap(chrome, 10);
        wave = new Wave();
        chrome.addView(wave, match(dp(54)));
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        shuffleButton = glyph("shuffle", "Shuffle", false, () -> command(SHUFFLE, shuffle ? 0 : 1, ""));
        repeatButton = glyph("repeat", "Repeat", false, () -> command(REPEAT, (repeat + 1) % 3, ""));
        playButton = glyph("play", "Play", true, this::toggle);
        Glyph previous = seeker(false);
        Glyph next = seeker(true);
        for (Glyph g : new Glyph[]{shuffleButton, previous, playButton, next, repeatButton}) g.glass = true;
        addTransport(controls, shuffleButton, 48, true, false);
        addTransport(controls, previous, 56, false, false);
        addTransport(controls, playButton, 84, false, false);
        addTransport(controls, next, 56, false, false);
        addTransport(controls, repeatButton, 48, false, true);
        chrome.addView(controls, match(-2));
        gap(chrome, 6);
        addVolume(chrome);
        if (!upcoming.isEmpty()) {
            Entry coming = upcoming.get(0);
            LinearLayout pill = row();
            pill.setPadding(dp(6), dp(6), dp(14), dp(6));
            pill.setBackground(background((onSurface & 0x00ffffff) | 0x14000000, 22));
            Art art = new Art(Art.SQUARE);
            art.small = true;
            art.letter = initial(coming.title);
            art.bind(coming.image);
            pill.addView(art, control(32));
            TextView label = text("Next  ", 13, onVariant, true);
            label.setPadding(dp(10), 0, 0, 0);
            pill.addView(label);
            TextView name = text(coming.title + (coming.subtitle.isEmpty() ? "" : " · " + coming.subtitle), 13, onSurface, false);
            single(name);
            pill.addView(name, weighted());
            makeClickable(pill, "Up next: " + coming.title, () -> navigate(4, 0));
            springPress(pill);
            LinearLayout.LayoutParams pp = match(-2);
            pp.topMargin = dp(10);
            chrome.addView(pill, pp);
        }
        content.addView(chrome, match(-2));
        if (motionEnabled()) {
            chrome.setTranslationY(dp(28));
            chrome.setAlpha(0f);
            chrome.animate().translationY(0).alpha(1f).setStartDelay(90).setDuration(420)
                .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
        }
    }

    /** Skip on tap; hold to jump 10 seconds at a time, repeating until released. */
    private Glyph seeker(boolean forward) {
        Glyph g = glyph(forward ? "next" : "previous", forward ? "Next track, hold to fast forward" : "Previous track, hold to rewind",
            false, () -> command(forward ? NEXT : PREVIOUS, 0, ""));
        boolean[] held = new boolean[1];
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (!held[0] || duration <= 0 || !g.isAttachedToWindow()) return;
            long target = Math.max(0, Math.min(duration - 1500, playhead() + (forward ? 10_000 : -10_000)));
            // Show the jump at once; Spotify's next state confirms it.
            position = target;
            stamp = SystemClock.uptimeMillis();
            if (wave != null) wave.invalidate();
            seek(target / (float) duration);
            if (pref("haptics", true)) g.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            g.postDelayed(step[0], 480);
        };
        g.setOnLongClickListener(v -> {
            held[0] = true;
            step[0].run();
            return true;
        });
        g.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) held[0] = false;
            return false;
        });
        return g;
    }

    /** Titles stay on one line; a long one scrolls slowly instead of wrapping. */
    private void marquee(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        v.setMarqueeRepeatLimit(-1);
        v.setHorizontalFadingEdgeEnabled(true);
        v.setFadingEdgeLength(dp(24));
        v.setSelected(true);
    }

    /** The artist with the explicit badge in front of it, centred on the text so it is never clipped. */
    private LinearLayout artistLine(boolean centred) {
        LinearLayout line = row();
        line.setGravity((centred ? Gravity.CENTER_HORIZONTAL : Gravity.START) | Gravity.CENTER_VERTICAL);
        explicitBadge = text("E", 11, surface, true);
        explicitBadge.setGravity(Gravity.CENTER);
        explicitBadge.setIncludeFontPadding(false);
        explicitBadge.setBackground(background(onVariant, 4));
        explicitBadge.setContentDescription("Explicit");
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(17), dp(17));
        ep.rightMargin = dp(7);
        line.addView(explicitBadge, ep);
        single(trackArtist);
        line.addView(trackArtist, centred ? new LinearLayout.LayoutParams(-2, -2) : weighted());
        return line;
    }

    private void toggleChrome() {
        View chrome = immersiveChrome;
        if (chrome == null) return;
        boolean hide = chrome.getAlpha() > .5f;
        chrome.animate().alpha(hide ? 0f : 1f).translationY(hide ? dp(24) : 0).setStartDelay(0).setDuration(320)
            .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
        chrome.setVisibility(VISIBLE);
        if (trackArt != null && trackArt.getParent() instanceof View) {
            View stage = (View) trackArt.getParent();
            float grow = hide ? 1.14f : 1f;
            stage.animate().scaleX(grow).scaleY(grow).translationY(hide ? chrome.getHeight() / 2f : 0)
                .setDuration(420).setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
        }
        ViewGroup group = (ViewGroup) chrome;
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setEnabled(!hide);
        // Zen: the top bar leaves too; a tap anywhere on the disc brings everything back.
        if (immersiveTop != null) {
            immersiveTop.animate().alpha(hide ? 0f : 1f).translationY(hide ? -dp(16) : 0).setDuration(320).start();
            ViewGroup bar = (ViewGroup) immersiveTop;
            for (int i = 0; i < bar.getChildCount(); i++) bar.getChildAt(i).setEnabled(!hide);
        }
    }

    /** Soft particles that rise through the backdrop while music plays. Drawn in one pass, no allocations. */
    private final class Motes extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] x = new float[34], y = new float[34], size = new float[34], speed = new float[34], phase = new float[34];
        private long last;

        Motes() {
            super(activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < x.length; i++) {
                x[i] = r.nextFloat(); y[i] = r.nextFloat();
                size[i] = 1.2f + r.nextFloat() * r.nextFloat() * 4.5f;
                speed[i] = .012f + r.nextFloat() * .03f;
                phase[i] = r.nextFloat() * 6.283f;
            }
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            boolean running = playing && motionEnabled() && getWindowVisibility() == VISIBLE;
            float dt = last == 0 || !running ? 0 : Math.min(.05f, (now - last) / 1000f);
            if (running && listening()) dt *= 1f + 3.5f * Audio.level + 4f * Audio.kick;
            last = now;
            float w = getWidth(), h = getHeight(), t = now / 1000f;
            for (int i = 0; i < x.length; i++) {
                y[i] -= speed[i] * dt;
                if (y[i] < -.03f) { y[i] = 1.03f; x[i] = (x[i] + .618f) % 1f; }
                float sway = (float) Math.sin(t * .6f + phase[i]) * dp(10);
                float twinkle = .45f + .55f * (float) Math.sin(t * 1.3f + phase[i] * 3f) * .5f + .275f;
                float fadeEdge = Math.min(1f, Math.min(y[i], 1f - y[i]) * 6f);
                int ink = i % 3 == 0 ? onTertiaryContainer : i % 3 == 1 ? primary : onSurface;
                paint.setColor(ink);
                paint.setAlpha(Math.max(0, Math.round(120 * twinkle * Math.max(0f, fadeEdge))));
                c.drawCircle(x[i] * w + sway, y[i] * h, dp(size[i]), paint);
            }
            if (running) postInvalidateOnAnimation();
        }
    }

    /** Whether visuals follow Spotify's audio: the setting is on and the permission granted. */
    private boolean listening() {
        return pref("audio", false) && Audio.permitted(activity);
    }

    /** A halo behind the artwork that breathes slowly while playing. */
    private final class Glow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private RadialGradient gradient;
        private int gradientColor;
        private float gradientRadius;
        private final View stage;
        private final int[] at = new int[2], origin = new int[2];
        private android.graphics.SweepGradient sweep;
        private int sweepColor;
        private float sweepX, sweepY;

        /** Full screen behind the content, centred on the disc's stage, so only the screen edges bound the halo. */
        Glow(View stage) {
            super(activity);
            this.stage = stage;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            if (trackArt == null || stage.getWidth() == 0) return;
            stage.getLocationInWindow(at);
            getLocationInWindow(origin);
            float sx = stage.getScaleX();
            float cx = at[0] - origin[0] + stage.getWidth() * sx / 2f, cy = at[1] - origin[1] + stage.getHeight() * stage.getScaleY() / 2f;
            float side = trackArt.coverSide() * sx;
            float w = cx * 2, h = cy * 2;
            float radius = side * .5f * 1.42f;
            if (gradient == null || gradientColor != primary || gradientRadius != radius) {
                gradientColor = primary;
                gradientRadius = radius;
                gradient = new RadialGradient(0, 0, radius,
                    new int[]{(primary & 0x00ffffff) | 0x70000000, (primary & 0x00ffffff) | 0x26000000, primary & 0x00ffffff},
                    new float[]{.5f, .75f, 1f}, Shader.TileMode.CLAMP);
            }
            boolean running = playing && motionEnabled() && getWindowVisibility() == VISIBLE;
            boolean audio = running && listening();
            long now = SystemClock.uptimeMillis();
            float breath = running ? (float) Math.sin(now / 4200f * 6.283f) : 0f;
            float scale = 1f + .06f * breath + (audio ? .14f * Audio.bass + .1f * Audio.kick : 0f);
            c.save();
            c.translate(w / 2f, h / 2f);
            c.scale(scale, scale);
            paint.setShader(gradient);
            c.drawCircle(0, 0, radius, paint);
            c.restore();
            paint.setShader(null);
            // Bass shockwave: a ring leaves the disc's edge on each kick and fades as it grows.
            long age = now - Audio.kickAt;
            if (audio && Audio.kickAt != 0 && age < 900) {
                float t = age / 900f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3) * (1 - t) + dp(.5f));
                paint.setColor(primary);
                paint.setAlpha(Math.round(170 * (1 - t) * (1 - t)));
                c.drawCircle(w / 2f, h / 2f, side / 2f * (1.02f + .5f * (1 - (1 - t) * (1 - t))), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if (audio) {
                // Bars radiating from a solid ring around the art, coloured in a sweep from the accent round to
                // the tertiary hue; bands mirror left and right so the shape stays balanced.
                int n = Audio.BANDS.length, bars = 72;
                float ring = side / 2f + dp(5);
                if (sweep == null || sweepColor != primary || sweepX != cx || sweepY != cy) {
                    sweepColor = primary;
                    sweepX = cx;
                    sweepY = cy;
                    int warm = tertiaryContainer == 0 ? primary : blend(primary, onTertiaryContainer, .55f);
                    sweep = new android.graphics.SweepGradient(cx, cy, new int[]{primary, warm, primary, warm, primary}, null);
                    Matrix turn = new Matrix();
                    turn.setRotate(-90, cx, cy);
                    sweep.setLocalMatrix(turn);
                }
                paint.setShader(sweep);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(5));
                paint.setAlpha(255);
                c.drawCircle(cx, cy, ring, paint);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(Math.min(dp(7), (float) (Math.PI * 2 * ring / bars) * .55f));
                float start = ring + dp(10);
                for (int k = 0; k < bars; k++) {
                    int half = bars / 2, b = k < half ? k : bars - 1 - k;
                    float v = Audio.BANDS[Math.min(n - 1, b * n / half)];
                    double a = -Math.PI / 2 + (k + .5) / bars * Math.PI * 2;
                    float len = dp(3) + dp(40) * v;
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    c.drawLine(cx + cos * start, cy + sin * start, cx + cos * (start + len), cy + sin * (start + len), paint);
                }
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                Audio.want();
            }
            if (running) postInvalidateOnAnimation();
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
            Entry queued = upcoming.get(i);
            row.bind(queued);
            row.setBackground(background(container, 18));
            final int index = i;
            if (!queued.uri.isEmpty()) makeClickable(row, "Play " + queued.title, () -> command(SKIP_TO, index, queued.queueKey));
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
        VolumeSlider slider = new VolumeSlider(audio);
        LinearLayout.LayoutParams p = match(dp(36));
        p.setMargins(0, dp(6), 0, 0);
        LinearLayout line = row();
        line.addView(slider, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView eq = chip("EQ", high, primary, () -> navigate(8, 0));
        eq.setContentDescription("Equalizer");
        eq.setPadding(0, 0, 0, 0);
        line.addView(eq, control(48));
        p.height = -2;
        details.addView(line, p);
    }

    /** Expressive slider: a thick pill track that fills with the accent, the speaker riding inside it. */
    private final class VolumeSlider extends View {
        private final AudioManager audio;
        private final int stream = AudioManager.STREAM_MUSIC, min, max;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Glyph icon = new Glyph("volume", false);
        private float shown = -1;
        private boolean dragging;

        VolumeSlider(AudioManager audio) {
            super(activity);
            this.audio = audio;
            min = android.os.Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(stream) : 0;
            max = audio.getStreamMaxVolume(stream);
            setFocusable(true);
            setContentDescription("Media volume");
        }

        private float level() { return max <= min ? 0 : (audio.getStreamVolume(stream) - min) / (float) (max - min); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            float target = level();
            if (shown < 0 || !motionEnabled()) shown = target;
            shown += (target - shown) * .35f;
            if (Math.abs(target - shown) > .002f) postInvalidateOnAnimation(); else shown = target;
            // A quiet line: speaker, thin track, small thumb that grows while dragged.
            float side = dp(20), left = h, right = w - dp(8), mid = h / 2f;
            c.save();
            c.translate((h - side) / 2f, (h - side) / 2f);
            c.scale(side / 24f, side / 24f);
            icon.drawGlyph(c, "volume", onVariant, 1f, 1f);
            c.restore();
            float x = left + (right - left) * shown;
            paint.setColor((onSurface & 0x00ffffff) | 0x26000000);
            c.drawRoundRect(left, mid - dp(2), right, mid + dp(2), dp(2), dp(2), paint);
            paint.setColor(primary);
            c.drawRoundRect(left, mid - dp(2), Math.max(left + dp(4), x), mid + dp(2), dp(2), dp(2), paint);
            c.drawCircle(x, mid, dp(dragging ? 9 : 6), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    dragging = e.getActionMasked() != MotionEvent.ACTION_UP;
                    float h = getHeight();
                    float f = Math.max(0f, Math.min(1f, (e.getX() - h) / Math.max(1f, getWidth() - dp(8) - h)));
                    int value = min + Math.round(f * (max - min));
                    if (value != audio.getStreamVolume(stream)) {
                        audio.setStreamVolume(stream, value, 0);
                        if (pref("haptics", true)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                    return true;
                default:
                    return e.getActionMasked() == MotionEvent.ACTION_CANCEL;
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo node) {
            super.onInitializeAccessibilityNodeInfo(node);
            node.setClassName(android.widget.SeekBar.class.getName());
            node.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                min, max, audio.getStreamVolume(stream)));
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                audio.adjustStreamVolume(stream, action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                invalidate();
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }

    private void artworkGestures(View view, boolean mini) {
        GestureDetector gestures = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) {
                return mini && view.performClick();
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (mini || screen != IMMERSIVE) return false;
                toggleChrome();
                return true;
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
                } else if (!uri.isEmpty()) {
                    command(dx < 0 ? NEXT : PREVIOUS, 0, "");
                } else return false;
                if (pref("haptics", true)) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                return true;
            }
        });
        float[] start = new float[2];
        boolean[] dragging = new boolean[1];
        view.setOnTouchListener((v, event) -> {
            if (!mini && content != null && motionEnabled()) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        start[0] = event.getRawX(); start[1] = event.getRawY();
                        dragging[0] = false;
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - start[0], dy = event.getRawY() - start[1];
                        // A downward pull at the top of the page is ours before the details scroller can claim it.
                        if (!dragging[0] && dy > 0 && dy > Math.abs(dx) && !scrolledDown(v)) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (!dragging[0] && dy > dp(16) && dy > Math.abs(dx) * 1.4f && !scrolledDown(v)) {
                            dragging[0] = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (dragging[0]) {
                            // The page follows the finger with resistance, shrinking a little, like a card being put away.
                            float pull = Math.max(0, dy);
                            float eased = pull / (1f + pull / dp(420));
                            content.setTranslationY(eased);
                            float k = Math.min(1f, eased / dp(300));
                            content.setScaleX(1f - .06f * k);
                            content.setScaleY(1f - .06f * k);
                            if (atmosphere != null) atmosphere.setAlpha(1f - .5f * k);
                            return true;
                        }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging[0]) {
                            dragging[0] = false;
                            float dy = event.getRawY() - start[1];
                            if (event.getActionMasked() == MotionEvent.ACTION_UP && dy > dp(140)) {
                                if (pref("haptics", true)) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                                back();
                            } else {
                                content.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(320)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(.8f)).start();
                                if (atmosphere != null) atmosphere.animate().alpha(1f).setDuration(320).start();
                            }
                            return true;
                        }
                        break;
                }
            }
            gestures.onTouchEvent(event);
            return true;
        });
    }

    private static boolean scrolledDown(View v) {
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            if (p instanceof ScrollView && ((ScrollView) p).getScrollY() > 0) return true;
        }
        return false;
    }

    private void selectEntry(Entry entry) {
        if (screen != PLAYLIST) remember(entry);
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

    private int playlistRequest;

    private void loadPlaylist(Entry entry, int offset, int attempt) {
        int request = ++playlistRequest;
        // Never wait forever: if Spotify hasn't answered in 15 s, offer Retry instead of "Loading…".
        postDelayed(() -> {
            if (request != playlistRequest || playlist != entry || !playlistLoading) return;
            android.util.Log.w("SpatchUi", "Playlist request timed out: " + entry.uri);
            playlistRequest++;
            playlistLoading = false;
            playlistFailed = true;
            if (screen == PLAYLIST) render();
        }, 20_000);
        PLAYLIST_DATA.execute(() -> {
            String[] result = nativePlaylist(entry.uri, offset);
            post(() -> {
                if (request != playlistRequest || playlist != entry || screen != PLAYLIST) return;
                if (result != null && result.length == 1 && "loading".equals(result[0]) && attempt < 10) {
                    postDelayed(() -> {
                        if (playlist == entry && screen == PLAYLIST) loadPlaylist(entry, offset, attempt + 1);
                    }, 700);
                    return;
                }
                if (result == null || result.length < 2 || !result[0].isEmpty()) {
                    android.util.Log.w("SpatchUi", "Playlist request failed: " + (result == null || result.length == 0 ? "no reply" : result[0]));
                }
                playlistLoading = false;
                playlistFailed = result == null || result.length < 2 || !result[0].isEmpty();
                if (!playlistFailed) {
                    playlistOffset = Integer.parseInt(result[1]);
                    for (int i = 2; i + 4 < result.length; i += 5) {
                        long length = Long.parseLong(result[i + 4]);
                        playlistTracks.add(new Entry(result[i], result[i + 1], result[i + 2],
                            result[i + 3], -1, "", false, length));
                        if (duration == 0 && !uri.isEmpty() && uri.equals(result[i + 2])) duration = length;
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
        TextView compact = text(playlist.title, 20, onSurface, true);
        single(compact);
        compact.setPadding(dp(6), 0, dp(8), 0);
        compact.setAlpha(0f);
        top.addView(compact, weighted());
        content.addView(top, match(-2));
        View header = playlistHeader();
        if (playlistTracks.isEmpty()) {
            LinearLayout body = feed();
            body.addView(header, match(-2));
            message(body, playlistLoading ? "Loading playlist…" : playlistFailed ? "Couldn’t load playlist" : "This playlist is empty");
        } else {
            playlistList = entryList(playlistTracks, header);
            playlistList.setSelectionFromTop(playlistRow, playlistTop);
            // The name moves into the top bar once the big header has scrolled away.
            playlistList.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(android.widget.AbsListView view, int state) {}
                @Override public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
                    float shown = first > 0 ? 1f : Math.max(0f, Math.min(1f, -header.getTop() / (float) Math.max(1, header.getHeight() - dp(60))));
                    shown = Math.max(0f, (shown - .55f) / .45f);
                    if (Math.abs(compact.getAlpha() - shown) > .01f) {
                        compact.setAlpha(shown);
                        compact.setTranslationY(dp(8) * (1 - shown));
                    }
                }
            });
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

    private void buildEqualizer() {
        LinearLayout header = row();
        header.setPadding(dp(10), dp(8), dp(14), 0);
        header.addView(glyph("back", "Back", false, this::back), control(48));
        header.addView(text("Equalizer", 30, onSurface, true), weighted());
        content.addView(header, match(-2));
        LinearLayout page = feed();

        LinearLayout controls = settingsGroup(page, "Playback");
        toggle(controls, "Enable equalizer", "Automatically lowers the input level when bands are boosted", "equalizer", false);
        equalizerStatus = text(Audio.eqStatus, 13, onVariant, false);
        equalizerStatus.setPadding(dp(16), 0, dp(16), dp(12));
        controls.addView(equalizerStatus, match(-2));

        String[] presets = {"Flat", "Bass boost", "Vocals", "Treble boost", "Loudness"};
        int[][] curves = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {6, 6, 4, 2, 0, 0, 0, 0, 0, 0},
            {-4, -3, -2, 0, 2, 4, 4, 2, 0, -1},
            {0, 0, 0, 0, 0, 0, 2, 4, 5, 5},
            {5, 4, 2, 0, -1, -1, 0, 2, 4, 5}
        };
        android.widget.SeekBar[] sliders = new android.widget.SeekBar[Audio.EQ_HZ.length];
        controls.addView(chip("Presets", secondaryContainer, onSecondaryContainer, () -> {
            int selected = -1;
            int[] gains = new int[sliders.length];
            for (int i = 0; i < gains.length; i++) gains[i] = Audio.gain(prefs, i);
            for (int i = 0; i < curves.length; i++) if (java.util.Arrays.equals(gains, curves[i])) selected = i;
            sheet("Presets", "Choose a starting curve", presets, selected, choice -> {
                android.content.SharedPreferences.Editor editor = prefs.edit();
                for (int i = 0; i < sliders.length; i++) editor.putInt("eq_band_" + i, curves[choice][i]);
                editor.apply();
                for (int i = 0; i < sliders.length; i++) sliders[i].setProgress(curves[choice][i] + Audio.EQ_RANGE);
                Audio.refreshEqualizer();
            });
        }), match(-2));

        LinearLayout bands = settingsGroup(page, "Frequency bands");
        for (int i = 0; i < sliders.length; i++) {
            final int band = i;
            int hz = Audio.EQ_HZ[i];
            String frequency = hz < 1000 ? hz + " Hz" : (hz / 1000) + " kHz";
            LinearLayout line = row();
            line.setPadding(dp(16), 0, dp(16), 0);
            line.addView(text(frequency, 14, onSurface, true), new LinearLayout.LayoutParams(dp(62), -2));
            TextView level = text(db(Audio.gain(prefs, i)), 13, onVariant, false);
            level.setGravity(Gravity.END);
            android.widget.SeekBar slider = new android.widget.SeekBar(activity);
            sliders[i] = slider;
            slider.setMax(Audio.EQ_RANGE * 2);
            slider.setProgress(Audio.gain(prefs, i) + Audio.EQ_RANGE);
            slider.setContentDescription(frequency);
            if (android.os.Build.VERSION.SDK_INT >= 30) slider.setStateDescription(level.getText());
            slider.setProgressTintList(ColorStateList.valueOf(primary));
            slider.setThumbTintList(ColorStateList.valueOf(primary));
            slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
                    int gain = progress - Audio.EQ_RANGE;
                    level.setText(db(gain));
                    if (android.os.Build.VERSION.SDK_INT >= 30) bar.setStateDescription(level.getText());
                    if (user) {
                        prefs.edit().putInt("eq_band_" + band, gain).apply();
                        Audio.refreshEqualizer();
                    }
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
            });
            line.addView(slider, new LinearLayout.LayoutParams(0, dp(48), 1));
            line.addView(level, new LinearLayout.LayoutParams(dp(52), -2));
            bands.addView(line, match(-2));
        }
    }

    private static String db(int gain) { return (gain > 0 ? "+" : "") + gain + " dB"; }

    private void buildSettings() {
        LinearLayout top = row();
        top.setPadding(dp(10), dp(6), dp(10), dp(4));
        top.addView(glyph("back", "Back", false, this::back), control(48));
        TextView heading = text("Settings", 30, onSurface, true);
        heading.setPadding(dp(6), 0, 0, 0);
        top.addView(heading, weighted());
        content.addView(top, match(-2));
        LinearLayout page = feed();

        FrameLayout hero = new FrameLayout(activity);
        GradientDrawable heroShape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{primary, tertiaryContainer});
        heroShape.setCornerRadius(dp(32));
        hero.setBackground(heroShape);
        hero.setClipToOutline(true);
        WordmarkDecoration flower = new WordmarkDecoration();
        flower.petalColor = (onPrimary & 0x00ffffff) | 0x30000000;
        FrameLayout.LayoutParams flowerParams = new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.END | Gravity.CENTER_VERTICAL);
        flowerParams.rightMargin = -dp(28);
        hero.addView(flower, flowerParams);
        LinearLayout words = column();
        words.setPadding(dp(20), dp(20), dp(110), dp(20));
        words.addView(text("Make it yours", 26, onPrimary, true));
        TextView sub = text("Colours, motion and how the player feels", 14, onPrimary, false);
        sub.setAlpha(.85f);
        sub.setPadding(0, dp(4), 0, 0);
        words.addView(sub);
        hero.addView(words, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        hero.setOnClickListener(v -> flower.spin());
        page.addView(hero, match(dp(124)));
        LinearLayout look = settingsGroup(page, "Look");
        look.addView(choiceRow(new String[]{"System", "Dark", "Light"}, "theme", " theme"), match(-2));
        LinearLayout swatches = row();
        swatches.setPadding(dp(12), 0, dp(12), dp(10));
        int[] seeds = {0, 0xff8b6fd6, 0xffd6567a, 0xff3d8fd6, 0xff2fa37a, 0xffd99a2b, 0xffe0663f, 0xff7a8a99};
        String[] seedNames = {"Wallpaper", "Lavender", "Rose", "Sky", "Mint", "Amber", "Coral", "Slate"};
        int chosen = prefs.getInt("accent", 0);
        for (int i = 0; i < seeds.length; i++) {
            final int seed = seeds[i];
            Swatch swatch = new Swatch(seed == 0 ? tone("accent1", 500, 0xff6750a4) : seed, seed == chosen, seed == 0);
            makeClickable(swatch, seedNames[i] + " colours", () -> {
                prefs.edit().putInt("accent", seed).apply();
                if (pref("haptics", true)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                restyleFrom(swatch);
            });
            swatch.setForeground(null);
            springPress(swatch);
            swatches.addView(swatch, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        look.addView(swatches, match(-2));
        TextView shapeLabel = text("Player artwork", 16, onSurface, true);
        shapeLabel.setPadding(dp(16), dp(6), dp(16), dp(8));
        look.addView(shapeLabel, match(-2));
        look.addView(choiceRow(new String[]{"Scallop", "Circle", "Rounded"}, "art_shape", " artwork"), match(-2));
        toggle(look, "Artwork colours", "Tint the player with the song’s cover", "art_colors", true);
        toggle(look, "Round artwork in the mini player", "Off shows a rounded square", "round_mini", true);
        if (typeRole(true, 30) != null) {
            toggle(look, "Expressive type", "Google Sans Flex in Material 3 Expressive weights; off uses Roboto", "expressive_type", true);
        }

        LinearLayout motion = settingsGroup(page, "Eye candy");
        toggle(motion, "Floating motes", "Soft particles drift behind the immersive player", "motes", true);
        toggle(motion, "Spinning artwork", "Immersive artwork turns slowly while music plays", "spin_art", true);
        toggle(motion, "Breathing glow", "A halo around the artwork pulses with the track", "glow", true);
        toggle(motion, "Wavy progress", "The progress line ripples while playing", "wavy", true);
        toggle(motion, "Follow the music", "Glow, shockwaves and motes move with the beat. Reads only Spotify’s own playback; "
            + "Android lists this as microphone access", "audio", false);

        LinearLayout home = settingsGroup(page, "Home");
        toggle(home, "Jump back in", "Six quick tiles under Liked Songs", "home_quick", true);
        for (int i = 0; i < SECTIONS.length; i++) {
            toggle(home, SECTIONS[i], "Show the " + SECTIONS[i].toLowerCase(java.util.Locale.ROOT) + " shelf", "home_shelf_" + i, true);
        }

        LinearLayout playback = settingsGroup(page, "Player");
        playback.addView(chip("Equalizer", secondaryContainer, onSecondaryContainer, () -> navigate(8, 0)), match(-2));
        toggle(playback, "Keep screen on", "While the immersive player is open", "keep_on", true);
        toggle(playback, "Track details", "Show the details card under the player", "details", true);
        toggle(playback, "Haptics", "Little taps when you skip or change shapes", "haptics", true);

        LinearLayout developer = settingsGroup(page, "Developer");
        developer.addView(chip("Playback diagnostics", high, onSurface, () -> {
            String report = "Spatch · Android " + android.os.Build.VERSION.SDK_INT
                + "\nPlayback stream revision " + revision
                + "\nPlaying " + playing + " · position " + position + " ms · duration " + duration + " ms"
                + "\nLibrary loaded " + libraryReady
                + "\nUpcoming tracks " + upcoming.size()
                + "\nEqualizer " + Audio.eqStatus
                + "\nSearch " + (searchError.isEmpty() ? "No error" : searchError)
                + "\nServices " + CoreBridge.diagnostics();
            sheet("Playback diagnostics", report, new String[]{"Copy diagnostics", "Close"}, -1, choice -> {
                if (choice != 0) return;
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spatch diagnostics", report));
                    Toast.makeText(activity, "Diagnostics copied", Toast.LENGTH_SHORT).show();
                }
            });
        }), match(-2));
        developer.addView(chip("Refresh library", high, onSurface, () -> {
            LIBRARY_DATA.execute(() -> {
                nativeRefreshLibrary();
                post(() -> Toast.makeText(activity, "Library refresh finished", Toast.LENGTH_SHORT).show());
            });
        }), match(-2));

        LinearLayout about = settingsGroup(page, "About");
        TextView info = text("Spatch draws its own interface on top of Spotify’s core. Playback, your library and "
            + "your account stay Spotify’s; Spatch never asks for a separate sign-in.", 14, onVariant, false);
        info.setLineSpacing(0, 1.2f);
        info.setPadding(dp(16), dp(2), dp(16), dp(12));
        about.addView(info, match(-2));
        TextView reset = chip("Reset Spatch settings", high, onSurface, () ->
            sheet("Reset settings?", "Theme, colours, motion and player choices go back to their defaults.",
                new String[]{"Reset", "Keep my settings"}, -1, choice -> {
                    if (choice != 0) return;
                    prefs.edit().clear().apply();
                    Audio.refreshEqualizer();
                    render();
                    Toast.makeText(activity, "Settings reset", Toast.LENGTH_SHORT).show();
                }));
        LinearLayout.LayoutParams rp = match(-2);
        rp.setMargins(dp(16), 0, dp(16), dp(16));
        about.addView(reset, rp);
    }

    /**
     * Rebuilds Settings in its new look, revealed in a growing circle from the tapped control: the old
     * screen is kept as a picture on top with a hole that widens until it is gone.
     */
    private void restyleFrom(View origin) {
        int y = scroll == null ? 0 : scroll.getScrollY();
        Bitmap before = null;
        int[] at = new int[2], here = new int[2];
        origin.getLocationInWindow(at);
        getLocationInWindow(here);
        float cx = at[0] - here[0] + origin.getWidth() / 2f, cy = at[1] - here[1] + origin.getHeight() / 2f;
        if (motionEnabled() && getWidth() > 0 && getHeight() > 0) {
            try {
                before = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                draw(new Canvas(before));
            } catch (OutOfMemoryError e) {
                before = null;
            }
        }
        render();
        ScrollView again = scroll;
        if (again != null) again.post(() -> again.scrollTo(0, y));
        if (before == null) return;
        Bitmap picture = before;
        float reach = (float) Math.hypot(Math.max(cx, getWidth() - cx), Math.max(cy, getHeight() - cy));
        Veil veil = new Veil(picture, cx, cy);
        veil.setBounds(0, 0, getWidth(), getHeight());
        getOverlay().add(veil);
        android.animation.ValueAnimator grow = android.animation.ValueAnimator.ofFloat(0f, reach);
        grow.setDuration(520);
        grow.setInterpolator(new android.view.animation.PathInterpolator(.4f, 0f, .2f, 1f));
        grow.addUpdateListener(f -> {
            veil.radius = (float) f.getAnimatedValue();
            veil.invalidateSelf();
        });
        grow.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                getOverlay().remove(veil);
                picture.recycle();
            }
        });
        grow.start();
    }

    /** The old screen as a picture with a circular hole: as the hole grows, the new look shows through. */
    private static final class Veil extends android.graphics.drawable.Drawable {
        private final Bitmap picture;
        private final float cx, cy;
        private final Path hole = new Path();
        float radius;

        Veil(Bitmap picture, float cx, float cy) { this.picture = picture; this.cx = cx; this.cy = cy; }

        @Override public void draw(Canvas c) {
            hole.rewind();
            hole.addCircle(cx, cy, radius, Path.Direction.CW);
            c.save();
            c.clipOutPath(hole);
            c.drawBitmap(picture, 0, 0, null);
            c.restore();
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** A connected button group storing its index under `key`; picking one rebuilds the page in place. */
    private LinearLayout choiceRow(String[] names, String key, String spoken) {
        LinearLayout options = row();
        options.setPadding(dp(16), dp(4), dp(16), dp(14));
        int current = prefs.getInt(key, 0), last = names.length - 1;
        for (int i = 0; i < names.length; i++) {
            final int choice = i;
            boolean on = i == current;
            int outer = 22, inner = on ? 22 : 6;
            TextView option = text(names[i], 14, on ? onPrimary : onSurface, true);
            option.setGravity(Gravity.CENTER);
            option.setBackground(segment(on ? primary : high, i == 0 ? outer : inner, i == last ? outer : inner,
                i == last ? outer : inner, i == 0 ? outer : inner));
            makeClickable(option, names[i] + spoken, () -> {
                prefs.edit().putInt(key, choice).apply();
                if (pref("haptics", true)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                restyleFrom(option);
            });
            springPress(option);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (i > 0) p.leftMargin = dp(3);
            options.addView(option, p);
        }
        return options;
    }

    private LinearLayout settingsGroup(LinearLayout page, String title) {
        TextView label = text(title, 14, primary, true);
        label.setPadding(dp(16), dp(18), dp(16), dp(8));
        if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        page.addView(label, match(-2));
        LinearLayout card = column();
        card.setBackground(background(container, 28));
        card.setPadding(0, dp(8), 0, dp(4));
        page.addView(card, match(-2));
        return card;
    }

    private void toggle(LinearLayout card, String title, String detail, String key, boolean fallback) {
        LinearLayout line = row();
        line.setPadding(dp(16), dp(10), dp(16), dp(10));
        line.setMinimumHeight(dp(64));
        LinearLayout words = column();
        words.addView(text(title, 16, onSurface, true));
        TextView hint = text(detail, 13, onVariant, false);
        hint.setPadding(0, dp(3), 0, 0);
        words.addView(hint);
        line.addView(words, weighted());
        Switch toggle = new Switch(pref(key, fallback));
        line.addView(toggle, new LinearLayout.LayoutParams(dp(52), dp(32)));
        line.setContentDescription(title);
        line.setFocusable(true);
        line.setOnClickListener(v -> {
            toggle.set(!toggle.on);
            prefs.edit().putBoolean(key, toggle.on).apply();
            if (key.equals("equalizer")) Audio.refreshEqualizer();
            if (key.equals("audio") && toggle.on && !Audio.permitted(activity)) {
                activity.requestPermissions(new String[]{Audio.PERMISSION}, 7341);
            }
            if (pref("haptics", true)) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        line.setForeground(new RippleDrawable(ColorStateList.valueOf((onSurface & 0x00ffffff) | 0x1f000000), null, background(Color.WHITE, 20)));
        line.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo node) {
                super.onInitializeAccessibilityNodeInfo(host, node);
                node.setClassName(android.widget.Switch.class.getName());
                node.setCheckable(true);
                node.setChecked(toggle.on);
            }
        });
        card.addView(line, match(-2));
    }

    /** A colour choice: a filled circle, a ring when chosen, and a split dot for the wallpaper option. */
    private final class Swatch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private final boolean chosen, wallpaper;
        Swatch(int color, boolean chosen, boolean wallpaper) {
            super(activity);
            this.color = color; this.chosen = chosen; this.wallpaper = wallpaper;
        }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(4);
            if (chosen) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(onSurface);
                c.drawCircle(cx, cy, r + dp(2), paint);
                paint.setStyle(Paint.Style.FILL);
                r -= dp(3);
            }
            paint.setColor(color);
            c.drawCircle(cx, cy, r, paint);
            if (wallpaper) {
                paint.setColor(tone("accent3", 300, 0xffefb8c8));
                c.drawArc(cx - r, cy - r, cx + r, cy + r, -45, 180, true, paint);
            }
        }
    }

    /** Material 3 switch: the thumb grows and slides when it turns on. */
    private final class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean on;
        private float t;

        Switch(boolean on) {
            super(activity);
            this.on = on;
            t = on ? 1f : 0f;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(boolean value) {
            on = value;
            if (!motionEnabled()) { t = on ? 1f : 0f; invalidate(); return; }
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(t, on ? 1f : 0f);
            a.setDuration(280);
            a.setInterpolator(new android.view.animation.OvershootInterpolator(1.4f));
            a.addUpdateListener(f -> { t = (float) f.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            float k = Math.max(0f, Math.min(1f, t));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(high, primary, k));
            c.drawRoundRect(0, 0, w, h, r, r, paint);
            if (k < 1f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(blend(onVariant, primary, k));
                c.drawRoundRect(dp(1), dp(1), w - dp(1), h - dp(1), r - dp(1), r - dp(1), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            float thumb = dp(8) + dp(4) * t;
            float x = r + (w - 2 * r) * t;
            paint.setColor(blend(onVariant, onPrimary, k));
            c.drawCircle(x, r, thumb, paint);
        }
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, aa = a >>> 24;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255, ba = b >>> 24;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
            | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    private View nowPlayingCard() {
        LinearLayout box = column();
        box.setPadding(0, dp(6), 0, dp(6));
        TextView label = text("Now playing", 14, primary, true);
        label.setPadding(dp(4), 0, 0, dp(8));
        box.addView(label, match(-2));
        LinearLayout card = row();
        card.setPadding(dp(10), dp(10), dp(16), dp(10));
        card.setBackground(background(secondaryContainer, 24));
        Art art = new Art(Art.SQUARE);
        art.small = true;
        art.bind(image);
        card.addView(art, control(56));
        LinearLayout words = column();
        words.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(title.isEmpty() ? "Nothing playing" : title, 17, onSecondaryContainer, true);
        TextView by = text(artist, 13, onSecondaryContainer, false);
        by.setAlpha(.8f);
        single(name); single(by);
        words.addView(name); words.addView(by);
        card.addView(words, weighted());
        card.addView(new Bars(onSecondaryContainer), new LinearLayout.LayoutParams(dp(22), dp(20)));
        makeClickable(card, "Back to the player", this::back);
        box.addView(card, match(-2));
        TextView next = text(source.isEmpty() ? "Next up" : "Next from " + source, 14, primary, true);
        next.setPadding(dp(4), dp(18), 0, dp(8));
        single(next);
        box.addView(next, match(-2));
        return box;
    }

    // Liked Songs state of the current track: null until Spotify answers.
    private Boolean saved;
    private String savedUri = "";
    private Glyph like;
    private boolean saving;

    private void checkSaved(String track) {
        savedUri = track;
        saved = null;
        refreshLike();
        if (!track.startsWith("spotify:track:") && !track.startsWith("spotify:episode:") && !track.startsWith("spotify:preview:")) return;
        DATA.execute(() -> {
            int answer = nativeSaved(track);
            post(() -> {
                if (!track.equals(savedUri)) return;
                saved = answer < 0 ? null : answer == 1;
                refreshLike();
            });
        });
    }

    private Glyph likeButton() {
        like = glyph("heart-outline", "Save to Liked Songs", false, this::toggleSaved);
        refreshLike();
        return like;
    }

    private void refreshLike() {
        if (like == null) return;
        boolean on = Boolean.TRUE.equals(saved);
        like.symbol = on ? "heart" : "heart-outline";
        like.ink = on ? primary : null;
        like.setAlpha(saved == null ? .45f : 1f);
        like.setContentDescription(on ? "Remove from Liked Songs" : "Save to Liked Songs");
        like.invalidate();
    }

    private void toggleSaved() {
        if (saved == null || saving || uri.isEmpty()) return;
        boolean want = !saved;
        String track = uri;
        saving = true;
        saved = want;
        refreshLike();
        if (pref("haptics", true)) performHapticFeedback(want ? android.view.HapticFeedbackConstants.CONFIRM : android.view.HapticFeedbackConstants.CLOCK_TICK);
        if (want && like != null && motionEnabled()) burst(like);
        COMMANDS.execute(() -> {
            boolean ok = nativeSave(track, want);
            post(() -> {
                saving = false;
                if (!track.equals(savedUri)) return;
                if (!ok) {
                    saved = !want;
                    refreshLike();
                    if (isAttachedToWindow()) Toast.makeText(activity, "Couldn’t update Liked Songs", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** A ring of small hearts and sparks flies out of the button, and the heart itself pops. */
    private void burst(View from) {
        int[] origin = new int[2], at = new int[2];
        getLocationInWindow(origin);
        from.getLocationInWindow(at);
        float cx = at[0] - origin[0] + from.getWidth() / 2f, cy = at[1] - origin[1] + from.getHeight() / 2f;
        Burst spray = new Burst(cx, cy);
        spray.setBounds(0, 0, getWidth(), getHeight());
        getOverlay().add(spray);
        android.animation.ValueAnimator run = android.animation.ValueAnimator.ofFloat(0f, 1f);
        run.setDuration(620);
        run.addUpdateListener(f -> {
            spray.t = (float) f.getAnimatedValue();
            spray.invalidateSelf();
        });
        run.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { getOverlay().remove(spray); }
        });
        run.start();
        from.setScaleX(.6f);
        from.setScaleY(.6f);
        from.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
    }

    private final class Burst extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float[] heart = GLYPHS.get("heart");
        private final float cx, cy;
        float t;

        Burst(float cx, float cy) { this.cx = cx; this.cy = cy; }

        @Override public void draw(Canvas c) {
            float ease = 1f - (1f - t) * (1f - t) * (1f - t);
            for (int i = 0; i < 10; i++) {
                double a = i / 10.0 * Math.PI * 2 + .3;
                float d = dp(14) + dp(46) * ease * (i % 2 == 0 ? 1f : .7f);
                float x = cx + (float) Math.cos(a) * d, y = cy + (float) Math.sin(a) * d;
                paint.setColor(i % 3 == 0 ? tertiaryContainer : primary);
                paint.setAlpha(Math.round(255 * (1 - t)));
                if (i % 2 == 0) {
                    float size = dp(9) * (1 - t * .5f);
                    c.save();
                    c.translate(x - size / 2, y - size / 2);
                    c.scale(size / 24f, size / 24f);
                    path.rewind();
                    path.moveTo(heart[1], heart[2]);
                    for (int k = 3; k + 1 < heart.length; k += 2) path.lineTo(heart[k], heart[k + 1]);
                    path.close();
                    c.drawPath(path, paint);
                    c.restore();
                } else c.drawCircle(x, y, dp(2.5f) * (1 - t), paint);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // Sleep timer: lives on the main looper, not a view, so it survives page changes.
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static long sleepAt;
    private static int sleepChoice;
    private static final Runnable SLEEP = () -> {
        sleepAt = 0;
        sleepChoice = 0;
        COMMANDS.execute(() -> nativeCommand(0, 0, ""));
    };

    private Glyph timerButton() {
        Glyph timer = glyph("timer", "Sleep timer", false, this::openSleepSheet);
        if (sleepAt > SystemClock.uptimeMillis()) {
            timer.ink = primary;
            timer.setContentDescription("Sleep timer on");
        }
        return timer;
    }

    private void openSleepSheet() {
        String[] names = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "End of track"};
        long left = sleepAt - SystemClock.uptimeMillis();
        String note = left > 0 ? "Pausing in " + Math.max(1, (left + 59_999) / 60_000) + " min" : "Pause playback later";
        sheet("Sleep timer", note, names, sleepChoice, choice -> {
            MAIN.removeCallbacks(SLEEP);
            sleepChoice = choice;
            long delay = choice == 0 ? 0 : choice == 5 ? Math.max(1000, duration - playhead() - 600)
                : new long[]{0, 15, 30, 45, 60}[choice] * 60_000L;
            sleepAt = choice == 0 ? 0 : SystemClock.uptimeMillis() + delay;
            if (choice != 0) MAIN.postDelayed(SLEEP, delay);
            Toast.makeText(activity, choice == 0 ? "Sleep timer off" : "Pausing " + (choice == 5 ? "after this track"
                : "in " + names[choice]), Toast.LENGTH_SHORT).show();
            redrawPage();
        });
    }

    interface Choice { void picked(int index); }

    /** open.spotify.com link for the playing track or episode, or "" for anything else. */
    private String songLink() {
        for (String kind : new String[]{"track", "episode"}) {
            String prefix = "spotify:" + kind + ":";
            if (uri.startsWith(prefix) && uri.length() > prefix.length()) return "https://open.spotify.com/" + kind + "/" + uri.substring(prefix.length());
        }
        return "";
    }

    /** Long press on the title: hand the song's link to the share sheet, or copy it. */
    private boolean shareSheet() {
        String link = songLink();
        if (link.isEmpty()) return false;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        sheet(title, artist, new String[]{"Share song link", "Copy song link"}, -1, choice -> {
            if (choice == 0) {
                android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(android.content.Intent.EXTRA_TEXT, link);
                activity.startActivity(android.content.Intent.createChooser(send, title));
            } else {
                android.content.ClipboardManager clip = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText(title, link));
                if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(activity, "Link copied", Toast.LENGTH_SHORT).show();
            }
        });
        return true;
    }

    private void longPress(View view, Entry entry) {
        view.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            entryActions(entry);
            return true;
        });
    }

    /** Long press: play it, shuffle it, or open it (playlists only), from a sheet. */
    private void entryActions(Entry entry) {
        if (screen == PLAYLIST || entry.uri.startsWith("spotify:track:") || entry.uri.startsWith("spotify:episode:")) {
            // A song in a playlist or search: play it now or line it up.
            sheet(entry.title, entry.subtitle.isEmpty() ? "Song" : entry.subtitle, new String[]{"Play", "Add to queue"}, -1, choice -> {
                if (choice == 0) { selectEntry(entry); return; }
                command(QUEUE_ADD, 0, entry.uri);
            });
            return;
        }
        boolean openable = entry.category == 0 || entry.uri.startsWith("spotify:playlist:")
            || (entry.uri.startsWith("spotify:user:") && entry.uri.contains(":playlist:"));
        String[] options = openable ? new String[]{"Play", "Shuffle play", "Open"} : new String[]{"Play", "Shuffle play"};
        sheet(entry.title, entry.subtitle.isEmpty() ? "Choose what to do" : entry.subtitle, options, -1, choice -> {
            remember(entry);
            if (choice == 2) { openPlaylist(entry); return; }
            command(SHUFFLE, choice == 1 ? 1 : 0, "");
            command(PLAY, 0, entry.uri);
        });
    }

    /** A bottom sheet that rises over a dimmed page: one tap picks, a tap outside dismisses. */
    private void sheet(String heading, String note, String[] options, int selected, Choice choice) {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(0x66000000);
        LinearLayout card = column();
        card.setPadding(dp(20), dp(10), dp(20), dp(20) + inset.bottom);
        card.setBackground(segment(container, 32, 32, 0, 0));
        card.setClickable(true);
        View handle = new View(activity);
        handle.setBackground(background(onVariant, 3));
        handle.setAlpha(.5f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(14);
        card.addView(handle, hp);
        card.addView(text(heading, 24, onSurface, true), match(-2));
        TextView sub = text(note, 14, onVariant, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        card.addView(sub, match(-2));
        Runnable[] close = new Runnable[1];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            boolean on = i == selected;
            LinearLayout option = row();
            option.setPadding(dp(16), 0, dp(16), 0);
            option.setBackground(background(on ? secondaryContainer : high, on ? 24 : 12));
            option.addView(text(options[i], 16, on ? onSecondaryContainer : onSurface, on), weighted());
            if (on) {
                Glyph tick = new Glyph("check", false);
                tick.ink = onSecondaryContainer;
                tick.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                option.addView(tick, control(24));
            }
            makeClickable(option, options[i], () -> { close[0].run(); choice.picked(index); });
            springPress(option);
            LinearLayout.LayoutParams p = match(dp(54));
            p.bottomMargin = dp(4);
            card.addView(option, p);
        }
        scrim.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        close[0] = () -> {
            sheetClose = null;
            if (scrim.getParent() == null) return;
            if (!motionEnabled()) { removeView(scrim); return; }
            scrim.animate().alpha(0f).setDuration(180).start();
            card.animate().translationY(card.getHeight()).setDuration(220)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> removeView(scrim)).start();
        };
        scrim.setOnClickListener(v -> close[0].run());
        sheetClose = close[0];
        if (motionEnabled()) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(200).start();
            card.setTranslationY(dp(420));
            card.animate().translationY(0).setDuration(380)
                .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
        }
    }

    private Runnable sheetClose;

    private final class Shimmer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int index;
        Shimmer(int index) {
            super(activity);
            this.index = index;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float t = motionEnabled() ? (SystemClock.uptimeMillis() % 1400) / 1400f : .5f;
            float x = -w * .5f + (w * 2f) * t - index * dp(18);
            paint.setShader(new LinearGradient(x - w * .4f, 0, x + w * .4f, 0,
                new int[]{container, high, container}, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, w, h, dp(18), dp(18), paint);
            paint.setShader(null);
            paint.setColor((onSurface & 0x00ffffff) | 0x10000000);
            c.drawRoundRect(dp(10), dp(8), dp(58), h - dp(8), dp(12), dp(12), paint);
            c.drawRoundRect(dp(72), h / 2 - dp(12), w * (.62f - .06f * (index % 3)), h / 2 - dp(2), dp(5), dp(5), paint);
            c.drawRoundRect(dp(72), h / 2 + dp(4), w * .38f, h / 2 + dp(12), dp(4), dp(4), paint);
            if (motionEnabled() && isShown()) postInvalidateOnAnimation();
        }
    }

    /** Three bouncing bars: the universal "this one is playing" mark. Still when paused. */
    private final Set<Bars> attachedBars = new HashSet<>();

    private final class Bars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bars(int ink) {
            super(activity);
            paint.setColor(ink);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attachedBars.add(this);
            invalidate();
        }
        @Override protected void onDetachedFromWindow() {
            attachedBars.remove(this);
            super.onDetachedFromWindow();
        }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            boolean running = playing && motionEnabled() && isShown() && getWindowVisibility() == VISIBLE;
            float t = running ? SystemClock.uptimeMillis() / 1000f : 0f;
            float w = getWidth(), h = getHeight(), bar = w / 5f;
            for (int i = 0; i < 3; i++) {
                float k = running ? .35f + .65f * Math.abs((float) Math.sin(t * (3.1f + i * 1.7f) + i * 1.3f)) : .3f + .2f * i % 3;
                float x = bar * (i * 2);
                c.drawRoundRect(x, h * (1 - k), x + bar, h, bar / 2, bar / 2, paint);
            }
            if (running) postInvalidateOnAnimation();
        }
    }

    private String libraryQuery = "";

    /** Recently opened library items, kept on the phone in their own file so a settings reset leaves them. */
    private android.content.SharedPreferences recents() {
        return activity.getSharedPreferences("spatch_history", Context.MODE_PRIVATE);
    }

    private void remember(Entry e) {
        if (e.uri.isEmpty() || e.uri.contains("collection") || e.category < 0) return;
        try {
            org.json.JSONArray old = new org.json.JSONArray(recents().getString("items", "[]"));
            org.json.JSONArray next = new org.json.JSONArray();
            next.put(new org.json.JSONArray().put(e.title).put(e.subtitle).put(e.uri).put(e.image).put(e.category));
            for (int i = 0; i < old.length() && next.length() < 12; i++) {
                org.json.JSONArray item = old.getJSONArray(i);
                if (!item.getString(2).equals(e.uri)) next.put(item);
            }
            recents().edit().putString("items", next.toString()).apply();
        } catch (org.json.JSONException ignored) {
            recents().edit().remove("items").apply(); // unreadable history: start over rather than fail every time
        }
    }

    private List<Entry> history() {
        List<Entry> out = new ArrayList<>();
        try {
            org.json.JSONArray items = new org.json.JSONArray(recents().getString("items", "[]"));
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONArray it = items.getJSONArray(i);
                out.add(new Entry(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getInt(4)));
            }
        } catch (org.json.JSONException ignored) {
            out.clear();
        }
        return out;
    }

    private static final class LibrarySearch {
        static String normalize(String text) {
            return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        }

        static boolean matches(String title, String subtitle, String[] words) {
            String searchable = normalize(title + " " + subtitle);
            for (String word : words) {
                if (!searchable.contains(word)) return false;
            }
            return true;
        }
    }

    private static List<Entry> matching(List<Entry> all, String query) {
        List<Entry> out = new ArrayList<>();
        String normalized = LibrarySearch.normalize(query);
        String[] words = normalized.isEmpty() ? new String[0] : normalized.split(" +");
        for (Entry entry : all) {
            if (words.length == 0 || LibrarySearch.matches(entry.title, entry.subtitle, words)) out.add(entry);
        }
        return out;
    }

    /** Filters the library list in place as you type; the query survives page changes. */
    private View searchField(List<Entry> all, List<Entry> shown, View libraryFilters) {
        LinearLayout box = row();
        box.setPadding(dp(14), 0, dp(4), 0);
        box.setBackground(background(high, 28));
        Glyph icon = new Glyph("search", false);
        icon.ink = onVariant;
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(icon, control(24));
        android.widget.EditText field = new android.widget.EditText(activity);
        field.setBackground(null);
        field.setHint(catalogSearch ? "Search Spotify" : "Search titles or artists");
        field.setContentDescription("Search your library");
        field.setHintTextColor(onVariant);
        field.setTextColor(onSurface);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(dp(10), 0, dp(4), 0);
        field.setText(libraryQuery);
        field.setSelection(field.length());
        box.addView(field, new LinearLayout.LayoutParams(0, dp(52), 1));
        Glyph clear = glyph("close", "Clear search", false, () -> field.setText(""));
        clear.setVisibility(libraryQuery.isEmpty() ? GONE : VISIBLE);
        box.addView(clear, control(44));
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable text) {
                libraryQuery = text.toString();
                clear.setVisibility(libraryQuery.isEmpty() ? GONE : VISIBLE);
                searchRequest++;
                catalogResults.clear();
                shown.clear();
                boolean online = catalogSearch && !libraryQuery.trim().isEmpty();
                libraryFilters.setVisibility(online ? GONE : VISIBLE);
                if (!online) shown.addAll(matching(all, libraryQuery));
                if (searchEmpty != null) {
                    searchEmpty.setOnClickListener(null);
                    searchEmpty.setText(online ? "Searching Spotify…" : "No matches in your library");
                    searchEmpty.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
                }
                if (searchMore != null) searchMore.setVisibility(GONE);
                if (online) scheduleSearch(shown);
                collectionRow = collectionOffset = 0;
                if (libraryList != null) {
                    ((BaseAdapter) libraryList.getAdapter()).notifyDataSetChanged();
                    libraryList.setSelection(0);
                }
                if (libraryGrid != null) {
                    ((BaseAdapter) libraryGrid.getAdapter()).notifyDataSetChanged();
                    libraryGrid.setSelection(0);
                }
            }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    && action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    && (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER)) return false;
            hideKeyboard();
            SpatchUi.this.requestFocus();
            return true;
        });
        FrameLayout wrap = new FrameLayout(activity);
        wrap.setPadding(dp(16), dp(4), dp(16), 0);
        wrap.addView(box, new FrameLayout.LayoutParams(-1, dp(52)));
        return wrap;
    }

    private void scheduleSearch(List<Entry> shown) {
        int request = ++searchRequest;
        String query = libraryQuery.trim();
        postDelayed(() -> {
            if (request == searchRequest && screen == LIBRARY && catalogSearch) {
                searchCatalog(shown, query, "", request);
            }
        }, 350);
    }

    private void searchCatalog(List<Entry> shown, String query, String token, int request) {
        if (request != searchRequest || query.isEmpty()) return;
        searchMore.setVisibility(GONE);
        if (token.isEmpty()) {
            searchEmpty.setText("Searching Spotify…");
            searchEmpty.setVisibility(VISIBLE);
        }
        SEARCH_DATA.execute(() -> {
            String[] result = null;
            String error = "";
            try {
                result = nativeSearch(CoreBridge.search(query, token));
                if (result == null || result.length == 0 || (result.length - 1) % 6 != 0) {
                    throw new IllegalStateException("Search returned an invalid response");
                }
            } catch (Exception failed) {
                error = failed.toString();
                android.util.Log.w("SpatchUi", "Catalog search failed", failed);
            }
            String[] response = result;
            String failure = error;
            post(() -> {
                if (request != searchRequest || screen != LIBRARY || !catalogSearch) return;
                searchError = failure;
                if (!failure.isEmpty()) {
                    searchEmpty.setText("Couldn’t search Spotify. Tap to retry.");
                    searchEmpty.setVisibility(VISIBLE);
                    searchEmpty.setOnClickListener(v -> searchCatalog(shown, query, token, request));
                    return;
                }
                searchEmpty.setOnClickListener(null);
                if (token.isEmpty()) shown.clear();
                Set<String> seen = new HashSet<>();
                for (Entry entry : shown) seen.add(entry.uri);
                for (int i = 1; i + 5 < response.length; i += 6) {
                    if (seen.add(response[i + 2])) {
                        shown.add(new Entry(response[i], response[i + 1], response[i + 2], response[i + 3],
                            Integer.parseInt(response[i + 4]), "", false, Long.parseLong(response[i + 5])));
                    }
                }
                catalogResults = new ArrayList<>(shown);
                searchPage = response[0];
                searchMore.setVisibility(!searchPage.isEmpty() && !searchPage.equals(token) ? VISIBLE : GONE);
                searchEmpty.setText("No results on Spotify");
                searchEmpty.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
                if (libraryList != null) ((BaseAdapter) libraryList.getAdapter()).notifyDataSetChanged();
                if (libraryGrid != null) ((BaseAdapter) libraryGrid.getAdapter()).notifyDataSetChanged();
            });
        });
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager keys =
            (android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keys != null) keys.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    /** Cover, name and the two ways to start it: in order or shuffled. */
    private View playlistHeader() {
        LinearLayout header = column();
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(0, dp(4), 0, dp(14));
        boolean liked = playlist.image.isEmpty();
        if (liked) {
            Glyph heart = new Glyph("heart", false);
            heart.ink = onTertiaryContainer;
            heart.iconSize = 76;
            heart.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{tertiaryContainer, secondaryContainer});
            g.setCornerRadius(dp(32));
            heart.setBackground(g);
            header.addView(heart, control(176));
        } else {
            Art art = new Art(Art.SQUARE);
            art.bind(playlist.image);
            header.addView(art, control(196));
        }
        gap(header, 14);
        TextView name = text(playlist.title, 28, onSurface, true);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(name, match(-2));
        String count = playlistTracks.isEmpty() ? (playlist.subtitle.isEmpty() ? "Playlist" : playlist.subtitle)
            : playlistTracks.size() + (playlistOffset > 0 ? "+ songs" : playlistTracks.size() == 1 ? " song" : " songs");
        TextView meta = text(count, 14, onVariant, false);
        meta.setGravity(Gravity.CENTER);
        meta.setPadding(0, dp(4), 0, dp(14));
        header.addView(meta, match(-2));
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER);
        TextView shuffleAll = chip("Shuffle", secondaryContainer, onSecondaryContainer, () -> {
            command(SHUFFLE, 1, "");
            command(PLAY, 0, playlist.uri);
        });
        shuffleAll.setBackground(segment(secondaryContainer, 24, 8, 8, 24));
        actions.addView(shuffleAll, new LinearLayout.LayoutParams(dp(128), dp(52)));
        View space = new View(activity);
        actions.addView(space, new LinearLayout.LayoutParams(dp(4), 1));
        TextView play = chip("Play", primary, onPrimary, () -> {
            if (shuffle) command(SHUFFLE, 0, "");
            command(PLAY, 0, playlist.uri);
        });
        play.setBackground(segment(primary, 8, 24, 24, 8));
        actions.addView(play, new LinearLayout.LayoutParams(dp(128), dp(52)));
        header.addView(actions, match(-2));
        return header;
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
        header.addView(text("Queue", 30, onSurface, true), weighted());
        content.addView(header, match(-2));
        View now = nowPlayingCard();
        if (upcoming.isEmpty()) {
            LinearLayout page = feed();
            page.addView(now, match(-2));
            message(page, "Nothing queued after this");
        } else queueList = entryList(upcoming, now);
    }

    private void queueActions(List<Entry> entries, int index) {
        Entry entry = entries.get(index);
        if (!entry.editable) return;
        List<String> options = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        if (index > 0 && entries.get(index - 1).editable) {
            options.add("Move up");
            actions.add(QUEUE_MOVE_UP);
        }
        if (index + 1 < entries.size() && entries.get(index + 1).editable) {
            options.add("Move down");
            actions.add(QUEUE_MOVE_DOWN);
        }
        options.add("Remove from queue");
        actions.add(QUEUE_REMOVE);
        sheet(entry.title, entry.subtitle, options.toArray(new String[0]), -1,
            choice -> command(actions.get(choice), index, entry.queueKey));
    }

    private ListView entryList(List<Entry> entries) { return entryList(entries, null); }

    private ListView entryList(List<Entry> entries, View header) {
        ListView list = new ListView(activity);
        if (header != null) list.addHeaderView(header, null, false);
        list.setDivider(new android.graphics.drawable.ColorDrawable(surface));
        list.setDividerHeight(dp(3));
        list.setPadding(dp(16), 0, dp(16), 0);
        list.setClipToPadding(false);
        if (screen != QUEUE && motionEnabled()) {
            // Rows arrive in a quick cascade when a page opens.
            android.view.animation.AnimationSet arrive = new android.view.animation.AnimationSet(true);
            arrive.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
            arrive.addAnimation(new android.view.animation.TranslateAnimation(0, 0, dp(18), 0));
            arrive.setDuration(260);
            arrive.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f));
            android.view.animation.LayoutAnimationController cascade = new android.view.animation.LayoutAnimationController(arrive, .06f);
            list.setLayoutAnimation(cascade);
        }
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Entry getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return !getItem(position).uri.isEmpty(); }
            @Override public View getView(int position, View previous, ViewGroup parent) {
                EntryRow row = previous instanceof EntryRow ? (EntryRow) previous : new EntryRow();
                Entry entry = getItem(position);
                row.bind(entry);
                row.queueMenu.setVisibility(entry.editable ? VISIBLE : GONE);
                row.queueMenu.setOnClickListener(entry.editable ? v -> queueActions(entries, position) : null);
                row.queueMenu.setContentDescription("Queue options for " + entry.title);
                row.setBackground(segment(container, position == 0 ? 20 : 5, position == 0 ? 20 : 5,
                    position == getCount() - 1 ? 20 : 5, position == getCount() - 1 ? 20 : 5));
                return row;
            }
        });
        list.setOnItemLongClickListener((parent, row, index, id) -> {
            Object item = parent.getItemAtPosition(index);
            if (!(item instanceof Entry) || ((Entry) item).uri.isEmpty()) return false;
            if (screen == QUEUE) {
                if (!((Entry) item).editable) return false;
                queueActions(entries, index - list.getHeaderViewsCount());
                return true;
            }
            row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            entryActions((Entry) item);
            return true;
        });
        list.setOnItemClickListener((parent, row, index, id) -> {
            Object item = parent.getItemAtPosition(index);
            if (!(item instanceof Entry) || ((Entry) item).uri.isEmpty()) return;
            Entry entry = (Entry) item;
            if (screen == QUEUE) command(SKIP_TO, index - list.getHeaderViewsCount(), entry.queueKey);
            else selectEntry(entry);
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return list;
    }

    /** Two columns of covers with names under them; tap and long press work as in the list. */
    private android.widget.GridView entryGrid(List<Entry> entries) {
        android.widget.GridView grid = new android.widget.GridView(activity);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setPadding(dp(16), dp(6), dp(16), 0);
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return entries.size(); }
            @Override public Entry getItem(int position) { return entries.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View previous, ViewGroup parent) {
                GridTile tile = previous instanceof GridTile ? (GridTile) previous : new GridTile();
                tile.bind(getItem(position));
                return tile;
            }
        });
        grid.setOnItemClickListener((parent, view, index, id) -> {
            Entry entry = entries.get(index);
            if (!entry.uri.isEmpty()) selectEntry(entry);
        });
        grid.setOnItemLongClickListener((parent, view, index, id) -> {
            Entry entry = entries.get(index);
            if (entry.uri.isEmpty()) return false;
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            entryActions(entry);
            return true;
        });
        grid.setSelection(collectionRow);
        content.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        return grid;
    }

    private final class GridTile extends LinearLayout {
        private final Art art = new Art(Art.SQUARE);
        private final TextView name, subtitle;
        GridTile() {
            super(activity);
            setOrientation(VERTICAL);
            setPadding(dp(6), dp(6), dp(6), dp(8));
            setBackground(background(container, 22));
            addView(art, new LinearLayout.LayoutParams(-1, -2));
            name = text("", 15, onSurface, true);
            subtitle = text("", 12, onVariant, false);
            single(name); single(subtitle);
            name.setPadding(dp(4), dp(8), dp(4), dp(1));
            subtitle.setPadding(dp(4), 0, dp(4), 0);
            addView(name, match(-2));
            addView(subtitle, match(-2));
        }
        void bind(Entry entry) {
            art.shape = entry.category == 2 ? Art.ROUND : Art.SQUARE;
            art.letter = initial(entry.title);
            art.bind(entry.image);
            art.invalidate();
            name.setText(entry.title);
            subtitle.setText(entry.subtitle);
        }
    }

    private final class EntryRow extends LinearLayout {
        private final Art imageView;
        private final TextView name, subtitle, queueMenu, length;
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
            length = text("", 13, onVariant, false);
            length.setPadding(dp(8), 0, dp(4), 0);
            length.setVisibility(GONE);
            addView(length, new LinearLayout.LayoutParams(-2, -2));
            queueMenu = text("⋮", 24, primary, true);
            queueMenu.setGravity(Gravity.CENTER);
            queueMenu.setFocusable(false);
            queueMenu.setVisibility(GONE);
            addView(queueMenu, control(48));
            bars = new Bars(primary);
            addView(bars, new LinearLayout.LayoutParams(dp(18), dp(16)));
        }

        private final Bars bars;
        private Entry entry;

        /** The track that's playing now reads in the accent colour with bouncing bars. */
        void bind(Entry entry) {
            this.entry = entry;
            imageView.shape = entry.category == 2 ? Art.ROUND : Art.SQUARE;
            imageView.letter = initial(entry.title);
            imageView.bind(entry.image);
            imageView.invalidate();
            name.setText(entry.title);
            subtitle.setText(entry.editable
                ? (entry.subtitle.isEmpty() ? "In queue" : entry.subtitle + " · In queue") : entry.subtitle);
            length.setVisibility(entry.durationMs > 0 ? VISIBLE : GONE);
            if (entry.durationMs > 0) length.setText(time(entry.durationMs));
            refreshPlayback();
        }

        void refreshPlayback() {
            if (entry == null) return;
            boolean current = screen == PLAYLIST ? !uri.isEmpty() && entry.uri.equals(uri)
                : screen == LIBRARY && playingFrom(entry.uri);
            name.setTextColor(current ? primary : onSurface);
            bars.setVisibility(current ? VISIBLE : GONE);
        }
    }

    private void refreshPlayer() {
        refreshPlayerDetails();
        String shown = title.isEmpty() ? "Nothing playing" : title;
        if (playbackSource != null) playbackSource.setText(source.isEmpty() ? album : source);
        if (trackTitle != null && trackTitle.getText().length() > 0 && !shown.contentEquals(trackTitle.getText())
                && motionEnabled() && screen != HOME && screen != LIBRARY) {
            for (View v : new View[]{trackTitle, trackArtist}) {
                v.setAlpha(0f);
                v.setTranslationY(dp(14));
                v.animate().alpha(1f).translationY(0).setDuration(360)
                    .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
            }
        }
        if (trackTitle != null) trackTitle.setText(shown);
        if (trackArtist != null) trackArtist.setText(artist);
        if (albumTitle != null) {
            albumTitle.setText(album);
            albumTitle.setVisibility(album.isEmpty() ? GONE : VISIBLE);
        }
        if (heroTitle != null) heroTitle.setText(shown);
        if (heroArtist != null) heroArtist.setText(artist);
        if (explicitBadge != null) explicitBadge.setVisibility(rating.isEmpty() ? GONE : VISIBLE);
        if (heroBadge != null) heroBadge.setText(uri.isEmpty() ? "READY" : playing ? "NOW PLAYING" : "PAUSED");
        if (trackArt != null) trackArt.bind(image);
        if (heroArt != null) heroArt.bind(image);
        if (atmosphere != null) atmosphere.bind(image);
        refreshPlaybackButtons();
        refreshMini();
        for (ListView list : new ListView[]{playlistList, libraryList}) {
            if (list == null) continue;
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (child instanceof EntryRow) ((EntryRow) child).refreshPlayback();
            }
        }
        for (Bars bars : attachedBars) bars.invalidate();
        updateMotion();
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

    /** Drift and spin only while music plays, so a paused screen stops drawing. */
    private void updateMotion() {
        boolean run = playing && motionEnabled() && getWindowVisibility() == VISIBLE;
        if (immersiveDrift != null) {
            if (run && !immersiveDrift.isStarted()) immersiveDrift.start();
            else if (run && immersiveDrift.isPaused()) immersiveDrift.resume();
            else if (!run && immersiveDrift.isRunning()) immersiveDrift.pause();
        }
        if (trackArt != null && trackArt.shape == Art.DISC) trackArt.spin(run);
        String wanted = artworkSeedWanted();
        if (wanted != null && !wanted.equals(appliedSeed)) {
            rememberScroll();
            render();
            if (motionEnabled() && content != null) {
                content.setAlpha(.55f);
                content.animate().alpha(1f).setDuration(260).start();
            }
        }
    }

    private void refreshPlaybackButtons() {
        boolean shownPlaying = pendingPlayback == null ? playing : pendingPlayback;
        if (playButton != null) playButton.setPlayback(shownPlaying, !uri.isEmpty());
        if (heroPlay != null) heroPlay.setPlayback(shownPlaying, !uri.isEmpty());
    }

    private void toggle() {
        if (uri.isEmpty() || pendingPlayback != null) return;
        if (pref("haptics", true)) performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
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
        long at = Math.max(0, position + elapsed);
        return duration > 0 ? Math.min(duration, at) : at;
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
        Typeface expressive = pref("expressive_type", true) ? typeRole(bold, size) : null;
        v.setTypeface(expressive != null ? expressive
            : Typeface.create(bold && size >= 26 ? "sans-serif" : bold ? "sans-serif-medium" : "sans-serif",
                bold && size >= 26 ? Typeface.BOLD : Typeface.NORMAL));
        if (expressive != null && bold) {
            // Google Sans Flex is variable: heavier, rounder headings are the Expressive look.
            int weight = size >= 30 ? 720 : size >= 22 ? 650 : size >= 15 ? 580 : 540;
            v.setFontVariationSettings("'wght' " + weight + ", 'ROND' " + (size >= 22 ? 100 : 50));
        }
        return v;
    }

    private static final java.util.Map<String, Typeface> FACES = new java.util.HashMap<>();

    /**
     * Pixel ships Material 3 Expressive type roles as named system families (Google Sans Flex). Elsewhere the
     * name resolves to the default face, so null keeps the plain fallback.
     */
    private static Typeface typeRole(boolean bold, int size) {
        String role = bold
            ? size >= 30 ? "variable-display-small-emphasized" : size >= 22 ? "variable-headline-small-emphasized"
                : size >= 15 ? "variable-title-medium-emphasized" : "variable-label-large-emphasized"
            : size >= 16 ? "variable-body-large" : size >= 13 ? "variable-body-medium" : "variable-body-small";
        if (!FACES.containsKey(role)) {
            Typeface face = Typeface.create(role, Typeface.NORMAL);
            FACES.put(role, face == null || face.equals(Typeface.DEFAULT) ? null : face);
        }
        return FACES.get(role);
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
        int petalColor;
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
            canvas.save();
            canvas.translate(w * .55f, h * .58f);
            canvas.rotate(15f + angle);
            canvas.scale(h * .3f, h * .3f);
            paint.setColor(petalColor != 0 ? petalColor : tertiaryContainer);
            canvas.drawPath(petal, paint);
            canvas.restore();
            paint.setColor(primary);
            canvas.drawCircle(w * .22f, h * .22f, dp(3), paint);
            canvas.drawCircle(w * .12f, h * .42f, dp(1.6f), paint);
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
        makeClickable(v, null, () -> {
            if (pref("haptics", true)) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            action.run();
        });
        springPress(v);
        return v;
    }

    private void libraryMessage(LinearLayout parent) {
        if (!libraryReady && libraryStatus.startsWith("Connecting")) {
            // Placeholder rows that shimmer while Spotify's library service warms up.
            gap(parent, 12);
            for (int i = 0; i < 6; i++) {
                LinearLayout.LayoutParams p = match(dp(64));
                p.bottomMargin = dp(6);
                parent.addView(new Shimmer(i), p);
            }
            return;
        }
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
        final String title, subtitle, uri, image, queueKey;
        final int category;
        final boolean editable;
        final long durationMs;
        Entry(String title, String subtitle, String uri, String image, int category) {
            this(title, subtitle, uri, image, category, "", false);
        }
        Entry(String title, String subtitle, String uri, String image, int category, String queueKey, boolean editable) {
            this(title, subtitle, uri, image, category, queueKey, editable, 0);
        }
        Entry(String title, String subtitle, String uri, String image, int category, String queueKey, boolean editable, long durationMs) {
            this.durationMs = durationMs;
            this.title = title; this.subtitle = subtitle; this.uri = uri; this.image = image; this.category = category;
            this.queueKey = queueKey; this.editable = editable;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Entry)) return false;
            Entry e = (Entry) other;
            return title.equals(e.title) && subtitle.equals(e.subtitle) && uri.equals(e.uri) && image.equals(e.image) && category == e.category
                && queueKey.equals(e.queueKey) && editable == e.editable && durationMs == e.durationMs;
        }
        @Override public int hashCode() { return java.util.Objects.hash(title, subtitle, uri, image, category, queueKey, editable, durationMs); }
    }

    private final class Art extends View {
        static final int SQUARE = 0, ROUND = 1, WIDE = 2, IMMERSIVE = 3, BACKDROP = 4, PLAYER_ART = 5, DISC = 6, PLAYER_ROUND = 7;
        private android.animation.ValueAnimator spin;
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
        /** Mini player: a progress ring hugs the artwork. */
        boolean ring, small;
        /** Drawn when an item has no artwork. */
        String letter = "";

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

        private final Matrix turn = new Matrix();
        private long contourStarted;
        private float contourAngle;

        /** The outline's current turn, so a transition can start where the eye sees it. */
        float outlineAngle() {
            return shape == IMMERSIVE && motionEnabled() && pref("spin_art", true) ? contourAngle(SystemClock.uptimeMillis()) : 0;
        }

        private float contourAngle(long now) {
            return contourStarted == 0 ? contourAngle : (contourAngle + (now - contourStarted) * .006f) % 360f;
        }

        /** The player shapes: one square cover, centred and cut to an outline path. */
        boolean centered() { return shape == IMMERSIVE || shape == PLAYER_ART || shape == DISC || shape == PLAYER_ROUND; }

        void spin(boolean run) {
            if (run) {
                if (spin == null) {
                    float start = getRotation() % 360f;
                    spin = android.animation.ValueAnimator.ofFloat(start, start + 360f);
                    spin.setDuration(24000);
                    spin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    spin.setInterpolator(new android.view.animation.LinearInterpolator());
                    spin.addUpdateListener(f -> setRotation((float) f.getAnimatedValue()));
                    spin.start();
                } else if (spin.isPaused()) spin.resume();
            } else if (spin != null && spin.isRunning()) {
                spin.pause();
            }
        }

        @Override protected void onDetachedFromWindow() {
            if (spin != null) spin.cancel();
            super.onDetachedFromWindow();
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
            // Fetch on first draw even off screen: scrolling replays the recorded drawing without calling onDraw again.
            if (bitmap == null && !url.isEmpty()) fetchArt(url, size);
            if (shape == BACKDROP) {
                Bitmap blur = IMAGES.get(key + "/blur");
                if (blur != null) {
                    paint.setAlpha(255);
                    setBitmap(blur, w, h);
                    // A saturation lift keeps the blurred cover's colour instead of a muddy average.
                    paint.setColorFilter(VIVID);
                    c.drawRect(0, 0, w, h, paint);
                    paint.setColorFilter(null);
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

            if (bitmap == null && size != 180 && centered()) {
                bitmap = IMAGES.get(url + ":180");
            }
            int alpha = 255;
            if (bitmap != null && (shape == WIDE || centered()) && w > dp(200) && motionEnabled()) {
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
            if (centered()) {
                float side = coverSide();
                c.translate((w - side) * .5f, (h - side) * .5f);
                w = h = side;
                paint.setShader(null);
                float[] points = coverPath(shape);
                outline(outline, points, points, 0, 0, 0, side);
                if (shape == IMMERSIVE && motionEnabled() && pref("spin_art", true)) {
                    // The scallop's outline turns slowly while playing; the picture inside stays upright.
                    long now = SystemClock.uptimeMillis();
                    if (playing && contourStarted == 0) contourStarted = now;
                    if (!playing && contourStarted != 0) { contourAngle = contourAngle(now); contourStarted = 0; }
                    turn.setRotate(contourAngle(now), side / 2f, side / 2f);
                    outline.transform(turn);
                    if (contourStarted != 0 && getLocalVisibleRect(visible)) postInvalidateOnAnimation();
                }
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
                if (shape == DISC) {
                    // A pressed-vinyl rim and spindle so the turning reads as a record, not a wobbling image.
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1.5f));
                    paint.setColor(0x33ffffff);
                    c.drawCircle(side / 2, side / 2, side / 2 - dp(1), paint);
                    paint.setColor(0x1effffff);
                    c.drawCircle(side / 2, side / 2, side * .36f, paint);
                    // Fine grooves between the label and the rim.
                    paint.setStrokeWidth(dp(.6f));
                    paint.setColor(0x10ffffff);
                    for (float g = .39f; g < .49f; g += .018f) c.drawCircle(side / 2, side / 2, side * g, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(surface);
                    paint.setAlpha(230);
                    c.drawCircle(side / 2, side / 2, side * .055f, paint);
                    paint.setColor(0x40ffffff);
                    c.drawCircle(side / 2, side / 2, side * .018f, paint);
                }
            } else {
                if (bitmap == null || alpha < 255) {
                    paint.setColor(container);
                    if (shape != WIDE) {
                        paint.setShader(new LinearGradient(0, 0, w, h, high, container, Shader.TileMode.CLAMP));
                    }
                }
                if (ring) {
                    float inset = dp(4);
                    c.translate(inset, inset);
                    w -= 2 * inset;
                    h -= 2 * inset;
                }
                float r = shape == ROUND ? Math.min(w, h) / 2 : dp(shape == WIDE ? 0 : ring || small ? 11 : 18);
                if (bitmap == null || alpha < 255) {
                    c.drawRoundRect(0, 0, w, h, r, r, paint);
                    paint.setShader(null);
                }
                if (bitmap == null && !letter.isEmpty() && (url.isEmpty() || failedImages.contains(url + ':' + size))) {
                    // No artwork: the name's first letter, so a blank square doesn't read as still loading.
                    paint.setColor(onVariant);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(Math.min(w, h) * .42f);
                    Typeface face = pref("expressive_type", true) ? typeRole(true, 30) : null;
                    if (face != null) paint.setTypeface(face);
                    paint.setFakeBoldText(true);
                    c.drawText(letter, w / 2, h / 2 - (paint.descent() + paint.ascent()) / 2, paint);
                    paint.setFakeBoldText(false);
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
            if (ring) drawRing(c);
        }

        private final RectF ringBounds = new RectF();

        private void drawRing(Canvas c) {
            float stroke = dp(2.5f), inset = stroke / 2f;
            ringBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float fraction = duration <= 0 ? 0 : Math.min(1f, playhead() / (float) duration);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor((onSurface & 0x00ffffff) | 0x26000000);
            float corner = shape == ROUND ? ringBounds.width() / 2 : dp(13);
            c.drawRoundRect(ringBounds, corner, corner, paint);
            paint.setColor(primary);
            c.drawArc(ringBounds, -90, 360 * fraction, false, paint);
            paint.setStyle(Paint.Style.FILL);
            // A second's worth of arc is under a pixel: once a second is plenty.
            if (playing && isShown()) postInvalidateDelayed(1000);
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

    private static final android.graphics.ColorMatrixColorFilter VIVID;
    static {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setSaturation(1.45f);
        VIVID = new android.graphics.ColorMatrixColorFilter(m);
    }

    /** Light falling on the disc from the upper left; it stays put while the record turns under it. */
    private final class Sheen extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float shaderSide;
        Sheen() {
            super(activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas c) {
            float side = Math.min(Math.min(getWidth(), getHeight()), dp(344));
            if (side <= 0) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (shaderSide != side) {
                shaderSide = side;
                android.graphics.SweepGradient sweep = new android.graphics.SweepGradient(cx, cy,
                    new int[]{0x00ffffff, 0x2effffff, 0x00ffffff, 0x00ffffff, 0x1affffff, 0x00ffffff},
                    new float[]{0f, .09f, .2f, .5f, .6f, .7f});
                Matrix turn = new Matrix();
                turn.setRotate(195, cx, cy);
                sweep.setLocalMatrix(turn);
                paint.setShader(sweep);
            }
            c.drawCircle(cx, cy, side / 2f - dp(1), paint);
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
                if (!SEEDS.containsKey(url)) SEEDS.put(url, seedOf(bitmap));
            }
            post(() -> {
                loadingImages.remove(key);
                if (bitmap == null) failedImages.add(key);
                for (Art art : visibleArt) if (art.url.equals(url)) art.invalidate();
                if (url.equals(image)) refreshMini();
                if (url.equals(image) || (screen == PLAYLIST && playlist != null && url.equals(playlist.image))) updateMotion();
            });
        });
    }

    private final class Glyph extends View {
        private String symbol;
        private final boolean primary;
        private boolean connected, first, last, glass;
        private int iconSize = 23;
        private Integer ink;

        void updateContainer() {
            if (!connected) return;
            int start = first || isSelected() ? 24 : 8;
            int end = last || isSelected() ? 24 : 8;
            int idle = glass ? (onSurface & 0x00ffffff) | 0x1c000000 : high;
            setBackground(segment(isSelected() ? SpatchUi.this.primary : idle, start, end, end, start));
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
            float size = Math.min(dp(primary ? 62 : iconSize), available - dp(4));
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

    private final class Wave extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float preview = -1;
        private long shownElapsed = -1, shownRemaining = -1;
        private String elapsedLabel = "", remainingLabel = "";
        private float amp = -1, wavePhase;

        Wave() {
            super(activity);
            Typeface face = pref("expressive_type", true) ? typeRole(false, 12) : null;
            if (face != null) paint.setTypeface(face);
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
            long now = SystemClock.uptimeMillis();
            // The phase keeps flowing while the wave flattens, so pausing settles instead of snapping.
            if (playing || amp > .05f) wavePhase = (now % 1600) / 1600f;
            float target = playing && pref("wavy", true) ? dp(3) : 0;
            amp = amp < 0 || !motionEnabled() ? target : amp + (target - amp) * .12f;
            if (Math.abs(target - amp) < .05f) amp = target;
            float phase = motionEnabled() ? wavePhase : 0;
            float amplitude = amp;
            float wavelength = dp(33);
            for (float x = x0; x < tip; x += dp(2)) {
                float dy = amplitude * waveSample((x - x0) / wavelength + phase);
                if (x == x0) path.moveTo(x, y + dy); else path.lineTo(x, y + dy);
            }
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(tip, y + amplitude * waveSample((tip - x0) / wavelength + phase), dp(preview >= 0 ? 9 : 4), paint);
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
            if (preview >= 0) {
                // While scrubbing, the left label reads the target time, large and in the accent colour.
                paint.setColor(primary);
                paint.setTextSize(dp(15));
                paint.setFakeBoldText(true);
                canvas.drawText(time(Math.round(preview * (double) duration)), x0, timeBaseline + dp(2), paint);
                paint.setFakeBoldText(false);
                paint.setTextSize(dp(12));
                paint.setColor(onVariant);
            } else canvas.drawText(elapsedLabel, x0, timeBaseline, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(duration > 0 ? remainingLabel : "—", x1, timeBaseline, paint);
            boolean settling = Math.abs((playing && pref("wavy", true) ? dp(3) : 0) - amp) > 0;
            if ((playing || settling) && isShown()) {
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

    private static String initial(String name) {
        for (int i = 0; i < name.length(); i++) {
            int cp = name.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
        }
        return "";
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
        for (String key : new String[]{"back", "down", "previous", "next", "play", "pause", "shuffle", "repeat", "repeat-one", "volume", "queue", "immersive", "home", "library", "settings", "heart", "close", "sparkle", "chevron", "timer", "search", "check", "heart-outline", "grid"}) {
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
