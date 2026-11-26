package spatch;

import spatch.ArtworkViews.Art;
import spatch.ArtworkViews.Backdrop;
import spatch.ArtworkViews.Disc;
import spatch.ArtworkViews.Motes;
import spatch.Controls.Bars;
import spatch.Controls.PlayButton;
import spatch.Controls.Wave;
import spatch.MediaEntry.Song;
import static spatch.PackedStrings.unpackStrings;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
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

/**
 * Shared Android screens and interactions, backed by Rust JNI state.
 * The Android app and native Mac preview compile this same view implementation.
 */
public final class SpatchUi extends FrameLayout {
    private static native void nativeAttachUi();
    private static native void nativeRevisions(long[] out);
    private static native boolean nativeConnect();
    private static native byte[] nativePlayer(long revision);
    private static native String nativeLibrary();
    static native boolean nativeRefreshLibrary();
    private static native byte[] nativeEntries(int filter, String query, boolean alphabetical, int limit);
    static native byte[] nativeRecent();
    static native String nativeLiked();
    static native String nativeLikedNote();
    static native byte[] nativePlaylist(String uri, int offset);
    static native byte[] nativePage(String uri);
    private static native byte[] nativePlaylistTargets();
    static native String[] nativeDevices();
    private static native void nativeCacheDir(String dir);
    static native String nativeBuild();
    private static native float nativePlaybackSpeed();
    static native int nativeVolume();
    static native String nativeSetVolume(int level);
    private static native String[] nativeConnection();
    static native byte[] nativeSearch(String query);
    static native byte[] nativeCatalog(byte[] response);
    private static native void nativeDownloadsStart();
    static native byte[] nativeDownloads();
    private static native long nativeDownloadsRevision();
    private static native long nativeDownloadsSet();
    static native String nativeDownload(String uri, int action, byte[] entry);
    static native String[] nativeDownloadTotals();
    static native int nativeShuffleMode(String uri);
    static native String nativeSetShuffleMode(String uri, int mode);
    static native void nativeWatchPlaylist(String uri, String key);
    static native void nativeStopPlaylist();
    static native byte[] nativePlaylistRest(String uri, int offset);
    static native void nativeSongs(byte[] packed);
    static native int[] nativeArrange(String query, int sort);
    private static native boolean nativePlayFrom(String context, String track, int index);
    static native byte[] nativeCached(String key);
    static native void nativeRemember(String key, byte[] packed);
    static native int[] nativePalette2(int seed, boolean hasSeed, int seed2, boolean hasSeed2, boolean dark);
    private static native int[] nativeSeedPair(int[] pixels);
    static native void nativeRing(int perSide, float[] out);
    static native String[] nativeSpotifySettings();
    static native String nativeSetSpotifySetting(String key, int value);
    static native String nativeTransfer(String id);
    static native boolean nativeCommand(int action, long value, String uri);
    static native int nativeSaved(String uri);
    static native boolean nativeSave(String uri, boolean save);
    static native float[] nativeGeometry(String kind);
    private static native String[] nativeProfile();
    static native String[] nativeCreatePlaylist(String name, String description, boolean fromQueue);
    static native String nativeAddToPlaylist(String playlistUri, String[] uris);

    static final int HOME = 0, SEARCH = 1, LIBRARY = 2, SETTINGS = 3, CREATE = 4, PROFILE = 5, STATS = 6;
    static final String[] SECTIONS = {"Playlists", "Albums", "Artists", "Podcasts"};
    /** The Library filter after the four sections: what's downloaded. */
    static final int DOWNLOADED = SECTIONS.length;

    static final ExecutorService DATA = Executors.newSingleThreadExecutor();
    static final ExecutorService COMMANDS = Executors.newSingleThreadExecutor();
    static final ExecutorService PLAYLISTS = Executors.newCachedThreadPool();
    static final ExecutorService WATCHES = Executors.newSingleThreadExecutor();
    static final ExecutorService ART = Executors.newFixedThreadPool(6);
    static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getAllocationByteCount(); }
    };
    static final java.util.Map<String, Integer> SEEDS = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Map<String, Integer> SEEDS2 = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.Map<String, float[]> GLYPHS = new java.util.HashMap<>();
    static final ColorMatrixColorFilter VIVID;
    static {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.5f);
        VIVID = new ColorMatrixColorFilter(m);
    }

    volatile boolean closed;
    private volatile long lifetime;

    /** Queued work from a detached account screen must never run in the next account. */
    void submit(ExecutorService executor, Runnable work) {
        long generation = lifetime;
        executor.execute(() -> {
            if (!closed && generation == lifetime) work.run();
        });
    }

    final CreationSheet creation = new CreationSheet(this);
    final SoundSettings sound = new SoundSettings(this);
    final PlayerScreen playerScreen = new PlayerScreen(this);
    final PlaylistScreen playlistScreen = new PlaylistScreen(this);
    final SettingsScreen settingsScreen = new SettingsScreen(this);
    final SearchScreen searchScreen = new SearchScreen(this);
    final LibraryScreen libraryScreen = new LibraryScreen(this);
    final HomeScreen homeScreen = new HomeScreen(this);
    final Dock dockUi = new Dock(this);
    final Controls controls = new Controls(this);
    final Downloads downloadsScreen = new Downloads(this);
    final ArtworkViews artwork = new ArtworkViews(this);
    final Activity activity;
    private final float density;
    final android.content.SharedPreferences prefs;
    final android.graphics.Rect inset = new android.graphics.Rect();
    private android.graphics.Rect pendingInsets;
    private final Set<String> loading = new HashSet<>();
    final java.util.Map<String, Long> failed = new java.util.HashMap<>();

    // palette, derived from the playing cover
    int bg, surf, surfHi, ink, inkDim, accent, onAccent, accent2;
    String paletteKey = "";
    private android.animation.ValueAnimator paletteAnim;
    private boolean paletteReady;

    // navigation
    int tab = HOME, filter = -1;
    int settingsSection = -1;
    final java.util.ArrayDeque<Integer> tabHistory = new java.util.ArrayDeque<>();
    android.widget.PopupWindow accountMenu;
    private String profileName = "", profileUsername = "", profileImage = "", profileUri = "", profileError = "";
    private boolean profileLoading, profileAnnounce;
    private long profileAt, profileStartedAt;
    private int profileTicket, profileAttempts;
    /** Asks again for the account until it's known (the library, which it's learned from, may not be ready yet). */
    private final Runnable profileRetry = () -> loadProfile(true);
    private final Set<Art> avatars = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    MediaEntry playlist;
    List<MediaEntry> playlistTracks = new ArrayList<>();
    boolean playerOpen;
    /** The link actually used for the open page: Liked Songs' real link is learned from Spotify when it opens. */
    String liveUri = "";
    String playUri() { return liveUri.isEmpty() ? playlist.uri : liveUri; }

    static boolean isDetail(MediaEntry e) { return e.uri.startsWith("spotify:album:") || e.uri.startsWith("spotify:artist:") || e.uri.startsWith("spotify:show:"); }
    int playlistRequest;
    String query = "";

    // player state
    long revision, lastConnect, libraryRetry, libraryLoadedAt, stamp, position, duration;
    private int libraryAttempts;
    boolean polling, fetching, libraryReady, refreshing, playing, shuffle;
    int repeat;
    Boolean pending, saved;
    long pendingUntil;
    String title = "", artist = "", album = "", source = "", image = "", uri = "", rating = "", context = "", quality = "";
    boolean buffering, canNext = true, canPrevious = true;
    private long prevTappedAt;
    String albumUri = "", artistUri = "";
    /** Skip buttons (true for next), dimmed and disabled while Spotify doesn't allow that skip. */
    final java.util.Map<View, Boolean> skipButtons = new java.util.WeakHashMap<>();
    final java.util.Set<PlayButton> playButtons = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    List<MediaEntry> upcoming = new ArrayList<>();
    String libraryStatus = "Connecting to your library…";

    // views that live-update
    FrameLayout pageHost, player;
    LinearLayout dock, nextPill;
    View dockFade, topScrim;
    int scrollTravel;
    ScrollView pageScroll;
    ListView pageList;
    Disc disc;
    Wave wave;
    final ProgressMotion progress = new ProgressMotion(this::invalidateProgress, EMPHASIZED);
    boolean holdingSeek;
    Runnable cancelHeldSeek;
    Backdrop backdrop;
    Motes motes;
    TextView pQuality, pSpeed;
    float speed = 1f;
    /** Last backdrop blur and cover kept across switches so transitions start from the previous track's art. */
    Bitmap lastBlur, lastCover;
    String lastCoverUrl = "", nextKey = "";
    TextView pTitle, pArtist, pSource, pBadge, mTitle, mArtist, heroTitle, heroArtist;
    int originalOrientation = -2;
    /** In landscape the right panel switches between Lyrics (0) and Up next (1) beside the cover. */
    FrameLayout sideHost;
    int sideTab, sideQueueOffset;
    boolean sideTabManual;
    java.util.function.BiConsumer<Integer, Boolean> sideTabSelect;
    volatile String noLyricsUri = "";
    Icon pLike, pShuffle, pRepeat, pSparkle;
    /** Whether the playing playlist is in Smart Shuffle, as Spotify keeps it (read when the context changes). */
    boolean smartShuffle;
    Art mArt, heroArt;
    View heroProgress;
    Runnable sheetClose;

    SpatchUi(Activity activity) {
        super(activity);
        this.activity = activity;
        SpatchWidget.uiStarted(activity);
        originalOrientation = activity.getRequestedOrientation();
        post(playerScreen::applyRotation);
        density = getResources().getDisplayMetrics().density;
        prefs = activity.getSharedPreferences("spatch_ui", Context.MODE_PRIVATE);
        materialIcons = prefs.getBoolean("material_icons", false);
        OfficialPlayback.init(activity);
        if (forgetProfile) {
            forgetProfile = false;
            prefs.edit().remove("profile_name").remove("profile_user").remove("profile_image").remove("profile_uri").apply();
        }
        // Last launch's account shows at once; the real lookup (which needs the library) replaces it.
        profileName = prefs.getString("profile_name", "");
        profileUsername = prefs.getString("profile_user", "");
        profileImage = prefs.getString("profile_image", "");
        profileUri = prefs.getString("profile_uri", "");
        Audio.initialize(activity.getApplicationContext(), prefs);
        java.io.File covers = new java.io.File(activity.getCacheDir(), "covers");
        covers.mkdirs();
        CoreBridge.cacheDir = covers;
        java.io.File savedLyrics = new java.io.File(activity.getFilesDir(), "lyrics");
        savedLyrics.mkdirs();
        CoreBridge.lyricsDir = savedLyrics;
        CoreBridge.prefs = prefs;
        LyricGen.dir = new java.io.File(activity.getFilesDir(), "whisper");
        LyricGen.prefs = prefs;
        ART.execute(CoreBridge::pruneCache);
        nativeCacheDir(new java.io.File(activity.getCacheDir(), "answers").getPath());
        for (String key : new String[]{"back", "down", "previous", "next", "play", "pause", "shuffle", "repeat", "repeat-one", "volume",
                "queue", "home", "library", "settings", "add", "note", "people", "mix", "blend", "prompt", "jam", "heart", "heart-outline", "search", "check", "close", "chevron", "timer", "devices", "album", "artist", "share", "sparkle", "download", "more",
                "badge4", "badge6", "badge8", "button5"}) {
            GLYPHS.put(key, nativeGeometry(key));
        }
        applyPalette(null, null);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        // Android otherwise lays a 12% white "focus highlight" over this focused view, washing out the whole app,
        // and it vanished whenever a search box took focus. The dark palette carries that lift instead.
        if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
        setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Rect next = new android.graphics.Rect();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets sides = insets.getInsets(WindowInsets.Type.systemBars());
                next.set(cutoutSide(insets, true, sides.left, bars.left), bars.top, cutoutSide(insets, false, sides.right, bars.right), bars.bottom);
            } else {
                next.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            if (!next.equals(inset)) {
                pendingInsets = next;
                applyPendingInsets();
            } else pendingInsets = null;
            // The keyboard draws over Spatch rather than resizing it, so results keep clear of it by padding.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                int keyboard = Math.max(0, insets.getInsets(WindowInsets.Type.ime()).bottom - inset.bottom);
                if (keyboard != searchScreen.imeInset) {
                    searchScreen.imeInset = keyboard;
                    if (searchScreen.searchScroll != null) searchScreen.searchScroll.setPadding(0, 0, 0, searchScreen.imeInset);
                }
            }
            return insets;
        });
        Carried was = carried;
        carried = null;
        boolean resumed = was != null && SystemClock.uptimeMillis() - was.at < 5000;
        if (resumed) {
            tab = was.tab;
            settingsSection = was.settingsSection;
            tabHistory.addAll(was.history);
            pageStack.addAll(was.stack);
            scrolls.putAll(was.scrolls);
            playerOpen = was.player; // build() reopens it
        }
        build();
        if (resumed && was.playlist != null) {
            quietEnter = true;
            playlistScreen.openPlaylist(was.playlist, false);
            quietEnter = false;
        }
    }

    /**
     * What the screen showed, handed to the next SpatchUi when Android recreates the activity (rotation, a theme
     * change): without it the new one started on Home with the player closed.
     */
    private static final class Carried {
        final long at = SystemClock.uptimeMillis();
        int tab, settingsSection;
        boolean player;
        MediaEntry playlist;
        final java.util.List<Integer> history = new ArrayList<>();
        final java.util.List<MediaEntry> stack = new ArrayList<>();
        final java.util.Map<String, int[]> scrolls = new java.util.HashMap<>();
    }
    private static Carried carried;
    private boolean quietEnter;

    /** Where each page was scrolled to (first row and its offset, or a scroll offset), keyed by {@link #pageKey}. */
    final java.util.Map<String, int[]> scrolls = new java.util.HashMap<>();
    String shownKey;

    private String pageKey() {
        return playlist != null ? "playlist:" + playlist.uri : tab == SETTINGS ? "settings:" + settingsSection : "tab:" + tab;
    }

    /** Notes how far the page on screen is scrolled, so coming back to it lands in the same place. */
    void rememberScroll() {
        if (shownKey == null) return;
        android.widget.AbsListView list = pageList != null ? pageList : libraryList;
        // A page rebuilt before it was ever laid out (fresh data right after the cached copy) hasn't moved: keep
        // what was saved rather than recording the top.
        if (list != null) { if (list.isLaidOut() && list.getChildCount() > 0) scrolls.put(shownKey, new int[] { list.getFirstVisiblePosition(), list.getChildAt(0).getTop(), 0 }); }
        else if (pageScroll != null && pageScroll.isLaidOut()) scrolls.put(shownKey, new int[] { 0, 0, pageScroll.getScrollY() });
    }

    private void restoreScroll() {
        int[] at = scrolls.get(shownKey);
        if (at == null) return;
        android.widget.AbsListView list = pageList != null ? pageList : libraryList;
        if (list instanceof ListView) ((ListView) list).setSelectionFromTop(at[0], at[1]);
        else if (list != null) list.setSelection(at[0]);
        else keepScroll(pageScroll, at[2]);
    }

    /**
     * A side inset that keeps clear of the camera hole, not of the whole strip Android reserves for it. In landscape
     * the safe area is the full height of the screen, but the hole is a small circle; content already sits dp(18)
     * in from the edge, so the inset only needs to make up the rest of the hole's width (plus a little air).
     */
    private int cutoutSide(WindowInsets insets, boolean left, int bars, int safe) {
        if (safe <= bars) return bars;
        android.view.DisplayCutout cutout = insets.getDisplayCutout();
        int width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        if (cutout == null || width <= 0) return safe;
        int reach = 0;
        for (android.graphics.Rect r : cutout.getBoundingRects()) {
            if (left && r.left < width / 4) reach = Math.max(reach, r.right);
            else if (!left && r.right > width * 3 / 4) reach = Math.max(reach, width - r.left);
        }
        if (reach == 0) return safe; // the cutout isn't on this side after all: keep what Android asked for
        return Math.max(bars, Math.min(safe, reach + dp(6) - dp(18)));
    }

    private void applyPendingInsets() {
        if (pendingInsets == null || sheetClose != null || creation.createOverlay != null || playerOpen
                || playerScreen.queuePanel != null || accountMenu != null || findFocus() instanceof android.widget.EditText) return;
        inset.set(pendingInsets);
        pendingInsets = null;
        build();
    }


    boolean pref(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }

    boolean dark = true;
    static final int[] ACCENTS = {0, 0xff8b6fd6, 0xffd6567a, 0xff3d8fd6, 0xff2fa37a, 0xffd99a2b, 0xffe0663f};

    static final int[][] PALETTES = {null,
        {0x14121c, 0x1d1a2a, 0x2b2640, 0xf3eeff, 0xc6b8ff, 0xbcb3dc, 0xd0bcff},
        {0x282828, 0x3c3836, 0x504945, 0xfbf1c7, 0xd5c4a1, 0xa89984, 0xfe8019},
        {0x1e1e2e, 0x313244, 0x45475a, 0xcdd6f4, 0xf5c2e7, 0xa6adc8, 0xcba6f7},
        {0x24273a, 0x363a4f, 0x494d64, 0xcad3f5, 0xa5adcb, 0x888ea8, 0xc6a0f6},
        {0x303446, 0x414559, 0x51576d, 0xc6d0f5, 0xa5adce, 0x969cb4, 0xca9ee6},
        {0x191724, 0x1f1d2e, 0x26233a, 0xe0def4, 0xebbcba, 0x908caa, 0xc4a7e7},
        {0x2a273f, 0x393552, 0x44415a, 0xe0def4, 0x918dab, 0x918ea3, 0xc4a7e7},
        {0x2e3440, 0x3b4252, 0x434c5e, 0xeceff4, 0x8fbcbb, 0xd8dee9, 0x88c0d0},
        {0x1a1b26, 0x24283b, 0x292e42, 0xc0caf5, 0xbb9af7, 0x9aa5ce, 0x7aa2f7},
        {0x24283b, 0x292e42, 0x3b4261, 0xc0caf5, 0xa9b1d6, 0x898fac, 0x7aa2f7},
        {0x282a36, 0x343746, 0x44475a, 0xf8f8f2, 0xff79c6, 0xadb5cb, 0xbd93f9},
        {0x282828, 0x32302f, 0x45403d, 0xddc7a1, 0xd4be98, 0xa89984, 0xa9b665},
        {0x2d353b, 0x3d484d, 0x475258, 0xd3c6aa, 0x9da9a0, 0x949f97, 0xa7c080},
        {0x1f1f28, 0x2a2a37, 0x363646, 0xdcd7ba, 0xc8c093, 0x898881, 0x7e9cd8},
        {0x002b36, 0x073642, 0x0d4452, 0x9fabab, 0x839496, 0x809196, 0x3794d6},
        {0x282c34, 0x2c313a, 0x3e4451, 0xabb2bf, 0x9da5b4, 0x8f939c, 0x61afef},
        {0x121212, 0x1f1f1f, 0x2a2a2a, 0xffffff, 0xb3b3b3, 0x7d7d7d, 0x1ed760},
        {0x19191b, 0x232326, 0x2e2e31, 0xececee, 0xa9a9ae, 0x818187, 0xececed}};

    /** The chosen fixed palette (null for Dynamic) as bg, surf, surfHi, ink, inkDim, accent, accent2, onAccent. */
    int[] fixedPalette() {
        int pick = prefs.getInt("palette", 0);
        int[] p = pick > 0 && pick < PALETTES.length ? PALETTES[pick] : null;
        if (p == null) return null;
        int a = p[6] | 0xff000000;
        return new int[]{p[0] | 0xff000000, p[1] | 0xff000000, p[2] | 0xff000000, p[3] | 0xff000000, p[5] | 0xff000000, a, p[4] | 0xff000000,
            luminance(a) > .55f ? 0xff101014 : 0xffffffff};
    }

    static float luminance(int c) { return (.2126f * Color.red(c) + .7152f * Color.green(c) + .0722f * Color.blue(c)) / 255f; }

    /** Colours from the cover when there is one, else from the chosen accent; animated smoothly in place. */
    private void applyPalette(Integer seed, Integer seed2) {
        int theme = prefs.getInt("theme", 1);
        dark = theme == 1 || (theme == 0 && (getResources().getConfiguration().uiMode & 0x30) == 0x20);
        int accentChoice = Math.max(0, Math.min(ACCENTS.length - 1, prefs.getInt("accent", 0)));
        boolean artSeed = seed != null;
        if (seed == null && accentChoice > 0) seed = ACCENTS[accentChoice];
        if (!artSeed) seed2 = null;
        int[] fixed = fixedPalette();
        if (fixed != null) dark = true;
        int[] target = fixed != null ? fixed : nativePalette2(seed == null ? 0 : seed, seed != null, seed2 == null ? 0 : seed2, seed2 != null, dark);
        View decor = activity.getWindow().getDecorView();
        int bars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? decor.getSystemUiVisibility() & ~bars : decor.getSystemUiVisibility() | bars);
        if (paletteAnim != null) { paletteAnim.cancel(); paletteAnim = null; }
        if (!paletteReady || !motion() || snapPalette) {
            paletteReady = true;
            snapPalette = false;
            bg = target[0]; surf = target[1]; surfHi = target[2]; ink = target[3];
            inkDim = target[4]; accent = target[5]; accent2 = target[6]; onAccent = target[7];
            applyColors();
            return;
        }
        int[] from = {bg, surf, surfHi, ink, inkDim, accent, accent2, onAccent};
        paletteAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        paletteAnim.setDuration(420);
        paletteAnim.setInterpolator(EMPHASIZED);
        paletteAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            bg = blend(from[0], target[0], f);
            surf = blend(from[1], target[1], f);
            surfHi = blend(from[2], target[2], f);
            ink = blend(from[3], target[3], f);
            inkDim = blend(from[4], target[4], f);
            accent = blend(from[5], target[5], f);
            accent2 = blend(from[6], target[6], f);
            onAccent = blend(from[7], target[7], f);
            applyColors();
        });
        paletteAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { paletteAnim = null; }
        });
        paletteAnim.start();
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t), Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
            Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** The cover's primary and secondary accents: Rust picks from a 12x12 shrink of it. */
    private static int[] seedPairOf(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 12, 12, true);
        int[] pixels = new int[144];
        small.getPixels(pixels, 0, 12, 0, 0, 12, 12);
        if (small != bitmap) small.recycle();
        return nativeSeedPair(pixels);
    }

    /** Re-tints everything once the playing cover's colour is known; true if the palette changed. */
    private TextView toastView;
    private Controls.Spring toastDrop;
    private boolean toastShown;
    private final Runnable toastAway = this::tuckToast;

    void toast(CharSequence text) { toast(text, false); }

    /**
     * A short confirmation as a pill that drops in from the top on a spring (overshooting a little, then settling),
     * holds a moment and tucks away. Frosted over the page when Frosted bars is on, else a translucent pill. A new one
     * while it shows just takes its place. Off screen (a background error) it falls back to the system toast.
     */
    void toast(CharSequence text, boolean longer) {
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE) {
            Toast.makeText(activity, text, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return;
        }
        if (toastView == null || toastView.getParent() != this) {
            TextView pill = text("", 14, () -> ink, true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(18), dp(11), dp(18), dp(11));
            pill.setMaxLines(3);
            if (dockUi.frosted) pill.setBackground(dockUi.new Frost(pill, 22, () -> surfHi));
            else bgRound(pill, 22, () -> (surfHi & 0x00ffffff) | 0xf0000000);
            pill.setElevation(dp(8));
            LayoutParams at = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            at.topMargin = inset.top + dp(10);
            at.leftMargin = at.rightMargin = dp(24);
            addView(pill, at);
            toastView = pill;
            toastDrop = new Controls.Spring(-dp(120), 15f, .52f, pill::setTranslationY);
            toastShown = false;
        }
        TextView pill = toastView;
        pill.bringToFront();
        pill.setText(text);
        pill.announceForAccessibility(text);
        removeCallbacks(toastAway);
        pill.animate().cancel();
        pill.setAlpha(1f);
        if (!motion()) { toastDrop.snap(0f); }
        else {
            if (!toastShown) toastDrop.snap(-(inset.top + dp(120)));
            toastDrop.to(0f);
        }
        toastShown = true;
        postDelayed(toastAway, longer ? 3500 : 2200);
    }

    private void tuckToast() {
        TextView pill = toastView;
        if (pill == null || !toastShown) return;
        toastShown = false;
        toastDrop.cancel();
        float away = -(inset.top + pill.getHeight() + dp(24));
        pill.animate().translationY(away).alpha(0f).setDuration(motion() ? 240 : 0).setInterpolator(EMPHASIZED)
            .withEndAction(() -> toastDrop.snap(away)).start();
    }

    /** The next palette change lands at once instead of crossfading: a reveal from the swatch shows it instead. */
    boolean snapPalette;
    /** Where the last touch went down, in window coordinates: where a reveal starts when no view is at hand. */
    float touchX, touchY;

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            int[] at = new int[2];
            getLocationInWindow(at);
            touchX = at[0] + e.getX();
            touchY = at[1] + e.getY();
        }
        return super.dispatchTouchEvent(e);
    }

    boolean refreshPalette() {
        boolean fromArt = pref("art_colors", true);
        Integer seed = fromArt ? SEEDS.get(image) : null;
        Integer seed2 = fromArt ? SEEDS2.get(image) : null;
        // A new cover's colour is known once its art loads: keep the current colours until then rather than
        // flashing to the default palette and back on every skip. A forced refresh ("?") still applies.
        if (fromArt && seed == null && !image.isEmpty() && !"?".equals(paletteKey) && !artFailed(image + ":440")) return false;
        String key = seed == null ? "" : seed + (seed2 == null ? "" : ":" + seed2);
        if (key.equals(paletteKey)) return false;
        paletteKey = key;
        applyPalette(seed, seed2);
        return true;
    }


    int dp(float v) { return Math.round(v * density); }
    LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    LinearLayout.LayoutParams fill(int h) { return new LinearLayout.LayoutParams(-1, h); }
    LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    View space(int w, int h) { View v = new View(activity); v.setLayoutParams(lp(w, h)); return v; }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    interface ColorOf { int get(); }

    <T extends View> T colored(T v, Runnable paint) { paint.run(); v.setTag(R_COLOR, paint); return v; }

    TextView text(String label, int size, ColorOf color, boolean bold) {
        TextView v = text(label, size, color.get(), bold);
        return colored(v, () -> v.setTextColor(color.get()));
    }

    Icon icon(String symbol, ColorOf color) {
        Icon v = new Icon(symbol, color.get());
        return colored(v, () -> v.color = color.get());
    }




    GradientDrawable bgRound(View v, float radiusDp, ColorOf color) {
        GradientDrawable d = round(color.get(), radiusDp);
        v.setBackground(d);
        colored(v, () -> d.setColor(color.get()));
        return d;
    }

    static void recolorView(View v) {
        if (v.getTag(R_COLOR) instanceof Runnable) ((Runnable) v.getTag(R_COLOR)).run();
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) recolorView(g.getChildAt(i));
        }
    }

    static void invalidateArt(View v) {
        if (v instanceof Art || v instanceof Disc || v instanceof Backdrop) v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateArt(g.getChildAt(i));
        }
    }

    private void applyColors() {
        setBackgroundColor(bg);
        recolorView(this);
    }

    TextView text(String label, int size, int color, boolean bold) {
        TextView v = new TextView(activity);
        v.setText(label);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        Typeface face = null;
        if (pref("expressive_type", true)) {
            // Pixel ships Material 3 Expressive type roles as system families; elsewhere this is the default face.
            face = Typeface.create(bold ? (size >= 22 ? "variable-headline-small-emphasized" : "variable-title-medium-emphasized") : "variable-body-large", Typeface.NORMAL);
            if (face != null && face.equals(Typeface.DEFAULT)) face = null;
        }
        if (face != null) {
            v.setTypeface(face);
            if (bold) v.setFontVariationSettings("'wght' " + (size >= 28 ? 720 : size >= 20 ? 650 : 580) + ", 'ROND' " + (size >= 22 ? 100 : 40));
        } else {
            v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        }
        return v;
    }

    void oneLine(TextView v) { v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END); }


    void tick(View v) {
        if (pref("haptics", true)) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    boolean motion() {
        if (pref("reduce_motion", false)) return false;
        return android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    /** Tap with a springy press; nothing highlights outside the view's own shape. */
    void tappable(View v, String label, Runnable action) {
        tappable(v, label, .96f, action);
    }

    void tappable(View v, String label, float pressScale, Runnable action) {
        if (label != null) v.setContentDescription(label);
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(x -> { tick(v); action.run(); });
        if (motion()) {
            v.setOnTouchListener((view, e) -> {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) view.animate().scaleX(pressScale).scaleY(pressScale).setDuration(90).start();
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                return false;
            });
        }
    }

    static boolean playerCommand(int action, long value, String target) {
        return OfficialPlayback.enabled() ? OfficialPlayback.command(action, value, target) : nativeCommand(action, value, target);
    }

    private static boolean playerPlayFrom(String context, String track, int index) {
        return OfficialPlayback.enabled() ? OfficialPlayback.command(PlayerScreen.PLAY, 0, track) : nativePlayFrom(context, track, index);
    }

    static String playbackFailure() {
        return OfficialPlayback.enabled() ? OfficialPlayback.status() + " · this action may be unavailable" : "That didn’t go through";
    }

    void command(int action, long value, String target) {
        if (action == PlayerScreen.NEXT || action == PlayerScreen.PLAY || action == PlayerScreen.SKIP_TO) prevTappedAt = 0;
        // Every skip, from a button or a swipe anywhere, lands as one firm click.
        if ((action == PlayerScreen.NEXT || action == PlayerScreen.PREVIOUS) && pref("haptics", true)) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        submit(COMMANDS, () -> {
            if (!playerCommand(action, value, target)) post(() -> {
                if (OfficialPlayback.enabled()) { revision = -1; syncNativeState(); }
                if (isAttachedToWindow()) toast(playbackFailure());
            });
        });
    }

    /** Restarts the song when well into it on the first tap, then forces skipping to the previous song on repeat taps or near the start. */
    void previousTrack() {
        if (!canPrevious) {
            setPosition(0);
            command(PlayerScreen.SEEK, 0, "");
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean allowSeek = playhead() > 3000 && now - prevTappedAt > 4000;
        prevTappedAt = now;
        if (allowSeek) {
            setPosition(0);
        }
        command(PlayerScreen.PREVIOUS, allowSeek ? 1 : 0, "");
    }

    static String time(long ms) {
        long s = Math.max(0, ms) / 1000, r = s % 60;
        return (s / 60) + (r < 10 ? ":0" : ":") + r;
    }

    long playhead() {
        long elapsed = playing && stamp > 0 ? Math.max(0, SystemClock.uptimeMillis() - stamp) : 0;
        long at = Math.max(0, position + elapsed);
        return duration > 0 ? Math.min(duration, at) : at;
    }

    private float playbackFraction() {
        return duration <= 0 ? 0 : Math.max(0, Math.min(1, playhead() / (float) duration));
    }

    /** Visual easing never changes the position sent to Spotify or used by the sleep timer. */
    float shownProgress() {
        if (wave != null && wave.preview >= 0) return wave.preview;
        return progress.shown(playbackFraction(), motion());
    }

    void invalidateProgress() {
        if (wave != null) wave.invalidate();
        if (mArt != null) mArt.invalidate();
        if (heroProgress != null) heroProgress.invalidate();
    }

    void animateProgress(float from) {
        progress.move(from, playbackFraction(), motion() && isShown() && (wave == null || wave.preview < 0));
    }

    void setPosition(long target) {
        float from = shownProgress();
        position = Math.max(0, duration > 0 ? Math.min(duration, target) : target);
        stamp = SystemClock.uptimeMillis();
        animateProgress(from);
    }

    void stopHeldSeek() {
        if (cancelHeldSeek != null) { cancelHeldSeek.run(); cancelHeldSeek = null; }
        holdingSeek = false;
        invalidateProgress();
    }

    static String initial(String name) {
        for (int i = 0; i < name.length(); i++) {
            int cp = name.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
        }
        return "";
    }

    static void notifyList(ListView list) {
        android.widget.ListAdapter a = list.getAdapter();
        if (a instanceof android.widget.HeaderViewListAdapter) a = ((android.widget.HeaderViewListAdapter) a).getWrappedAdapter();
        if (a instanceof BaseAdapter) ((BaseAdapter) a).notifyDataSetChanged();
    }


    @Override protected void onAttachedToWindow() {
        closed = false;
        super.onAttachedToWindow();
        requestApplyInsets();
        android.view.Window w = activity.getWindow();
        // The keyboard draws over Spatch instead of squeezing it: resizing the window re-laid out every page
        // around the keyboard and could leave the bottom fade misplaced. The search fields sit at the top.
        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        View decor = w.getDecorView();
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? decor.getSystemUiVisibility() & ~lightBars : decor.getSystemUiVisibility() | lightBars);
        requestFocus();
        activeUi = new java.lang.ref.WeakReference<>(this);
        nativeAttachUi();
        removeCallbacks(syncPush);
        removeCallbacks(poll);
        post(poll);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(syncPush);
        removeCallbacks(poll);
        removeCallbacks(profileRetry);
        if (visibility == VISIBLE && isAttachedToWindow()) {
            activeUi = new java.lang.ref.WeakReference<>(this);
            nativeAttachUi();
            if (!libraryReady) {
                libraryRetry = 0;
                lastConnect = 0;
            }
            if (profileUsername.isEmpty() || (!profileError.isEmpty() && profileAttempts < 12)) loadProfile(true);
            post(poll);
        }
    }

    @Override protected void onDetachedFromWindow() {
        rememberScroll();
        Carried c = new Carried();
        c.tab = tab; c.settingsSection = settingsSection; c.player = playerOpen; c.playlist = playlist;
        c.history.addAll(tabHistory); c.stack.addAll(pageStack); c.scrolls.putAll(scrolls);
        carried = activity.isChangingConfigurations() ? c : null; // only a recreation (rotation, theme) resumes; a real exit starts fresh
        closed = true;
        lifetime++;
        stopHeldSeek();
        progress.cancel();
        if (accountMenu != null) accountMenu.dismiss();
        creation.closeCreateMenu();
        java.lang.ref.WeakReference<SpatchUi> ref = activeUi;
        if (ref != null && ref.get() == this) activeUi = null;
        playlistRequest++;
        searchScreen.searchRequest++;
        profileTicket++;
        profileLoading = false;
        if (watchingPlaylist) { watchingPlaylist = false; WATCHES.execute(SpatchUi::nativeStopPlaylist); }
        removeCallbacks(syncPush);
        removeCallbacks(poll);
        removeCallbacks(profileRetry);
        if (paletteAnim != null) { paletteAnim.cancel(); paletteAnim = null; }
        super.onDetachedFromWindow();
    }

    /** Taps on empty space stop here instead of reaching Spotify's views underneath. */
    @Override public boolean onTouchEvent(MotionEvent e) { return true; }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean wasLandscape;

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        boolean land = w > h && w >= dp(600);
        if (oldw > 0 && land != wasLandscape) {
            wasLandscape = land;
            // Rebuild the underlying page (and player if open) immediately on orientation flip so closing never shifts.
            post(() -> {
                showPage();
                dockUi.updateDock();
                if (playerOpen) {
                    removeView(player);
                    player = null;
                    playerOpen = false;
                    playerScreen.openPlayer(false);
                }
            });
        } else {
            wasLandscape = land;
        }
    }

    Object backCallback() { return (android.window.OnBackInvokedCallback) this::back; }

    void back() {
        // Spatch's back handler outranks the keyboard's, so close the keyboard here first.
        if (hideKeyboard()) return;
        if (creation.createOverlay != null) {
            if (creation.createComposerOpen && creation.createComposerFromMenu) creation.showCreateMenuContent(true);
            else creation.closeCreateMenu();
        }
        else if (accountMenu != null && accountMenu.isShowing()) accountMenu.dismiss();
        else if (sheetClose != null) sheetClose.run();
        else if (playerScreen.lyricsPanel != null) playerScreen.closeLyrics();
        else if (playerScreen.queuePanel != null) playerScreen.closeQueue();
        else if (playerOpen) playerScreen.closePlayer();
        else if (!pageStack.isEmpty()) playlistScreen.openPlaylist(pageStack.pop(), false);
        else if (playlist != null) { playlist = null; showPage(); dockUi.updateDock(); enter(-dp(44), 0); }
        else if (tab == SETTINGS && settingsSection == 2 && sound.panel >= 0) { sound.panel = sound.panel >= 6 ? 1 : -1; showPage(); }
        else if (tab == SETTINGS && settingsSection >= 0) { settingsSection = -1; showPage(); }
        else if (!tabHistory.isEmpty()) go(tabHistory.pop(), false);
        else if (tab != HOME) go(HOME, false);
        else activity.moveTaskToBack(true);
    }

    private static volatile java.lang.ref.WeakReference<SpatchUi> activeUi;
    private final long[] revisions = new long[5];

    /** Called by Rust (from any thread) whenever player, library, playlist, or download state changes. */
    static boolean onNativeChange() {
        SpatchWidget.changed();
        java.lang.ref.WeakReference<SpatchUi> ref = activeUi;
        SpatchUi ui = ref == null ? null : ref.get();
        if (ui == null || !ui.isAttachedToWindow() || ui.getWindowVisibility() != VISIBLE) return false;
        ui.post(ui.syncPush);
        return true;
    }

    /** The player's fields when they changed since `revision`, for the home-screen widget. */
    static String[] widgetState(long revision) { return OfficialPlayback.enabled() ? OfficialPlayback.state(revision) : unpackStrings(nativePlayer(revision)); }

    /** A button on the home-screen widget or external control intent. */
    static void widgetCommand(int action) { widgetCommand(action, 0, ""); }
    static void widgetCommand(int action, long value, String target) { COMMANDS.execute(() -> playerCommand(action, value, target)); }
    static boolean connectNative() { return nativeConnect(); }
    static boolean playFrom(String context, String track) { return playerPlayFrom(context, track, 0); }

    private final Runnable syncPush = this::syncNativeState;
    private boolean libraryRedrawPending;

    void syncNativeState() {
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        nativeRevisions(revisions);
        if (OfficialPlayback.enabled() || revisions[0] != revision) {
            String[] state = widgetState(revision);
            if (state != null) applyPlayer(state);
        }
        long listRevision = revisions[1];
        if (listRevision != playlistRevision) {
            playlistRevision = listRevision;
            MediaEntry open = playlist;
            // The push carries the first page only, so ask again for the whole playlist; it redraws if it changed.
            if (open != null && !isDetail(open) && !playlistScreen.playlistLoading) playlistScreen.loadPlaylist(open, 1);
        }
        long downloadsNow = revisions[2];
        if (downloadsNow != downloadsScreen.downloadRevision) {
            downloadsScreen.downloadRevision = downloadsNow;
            downloadsScreen.pullDownloads();
            long setNow = revisions[3];
            boolean setChanged = setNow != downloadsScreen.downloadSetRevision;
            downloadsScreen.downloadSetRevision = setNow;
            downloadsScreen.onDownloadsChanged(setChanged);
        }
        long libRevision = revisions[4];
        if (libRevision != libraryRevision) {
            libraryRevision = libRevision;
            libraryRedrawPending = true;
        }
        if (libraryRedrawPending && libraryReady && playlist == null && sheetClose == null && !playerOpen
                && tab != SETTINGS && tab != SEARCH && !(findFocus() instanceof android.widget.EditText)) {
            libraryRedrawPending = false;
            showPageKeepingPlace();
        }
    }

    /** Low-frequency connection/retry maintenance; live state updates arrive immediately via onNativeChange(). */
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
            syncNativeState();
            applyPendingInsets();
            sound.refreshStatus();
            long now = SystemClock.uptimeMillis();
            if (!polling && now - lastConnect > 2000) {
                polling = true;
                lastConnect = now;
                submit(DATA, () -> {
                    try { nativeConnect(); } finally { post(() -> polling = false); }
                });
            }
            if (!libraryReady && !fetching && now >= libraryRetry) loadLibrary();
            postDelayed(this, 2000);
        }
    };

    void loadLibrary() {
        fetching = true;
        submit(DATA, () -> {
            String status = nativeLibrary();
            post(() -> {
                fetching = false;
                boolean cached = "cached".equals(status);
                libraryReady = status != null && (status.isEmpty() || cached);
                if (libraryReady) {
                    libraryAttempts = 0;
                    // Last launch's copy shows at once; backdating it makes the next check ask Spotify again.
                    libraryLoadedAt = SystemClock.uptimeMillis() - (cached ? 90_000 : 0);
                    libraryStatus = "Nothing saved here yet";
                    // The username is learned from the library, so ask again now that it's here.
                    if (profileUsername.isEmpty()) loadProfile(true);
                    if (!downloadsScreen.downloadsStarted) {
                        downloadsScreen.downloadsStarted = true;
                        submit(PLAYLISTS, SpatchUi::nativeDownloadsStart);
                    }
                } else {
                    libraryAttempts++;
                    // A slow Spotify core or a flaky network can take a few tries; say so once it's been a while,
                    // instead of repeating the same unqualified error.
                    libraryStatus = libraryAttempts < 4 ? "Couldn’t load your library"
                        : "Still trying to load your library. This is taking longer than usual.";
                    libraryRetry = SystemClock.uptimeMillis() + Math.min(20_000, 4_000L << Math.min(libraryAttempts - 1, 2));
                    android.util.Log.w("SpatchUi", "Library request failed: " + (status == null ? "no response" : status));
                }
                if (playlist == null) showPage();
                if (cached) refreshLibraryIfStale();
            });
        });
    }

    private long libraryRevision, playlistRevision;
    android.widget.AbsListView libraryList;

    /** Redraws the page for new data without losing your place in the Library list or Home's scroll. */
    void showPageKeepingPlace() {
        // Only for Home and Library: by the time a posted redraw runs, a playlist or the player may have opened.
        if (playlist != null || playerOpen || sheetClose != null || tab == SETTINGS || tab == SEARCH) return;
        android.widget.AbsListView list = tab == LIBRARY ? libraryList : null;
        int first = list != null ? list.getFirstVisiblePosition() : 0;
        int top = list != null && list.getChildCount() > 0 ? list.getChildAt(0).getTop() : 0;
        int y = pageScroll != null ? pageScroll.getScrollY() : 0;
        showPage();
        android.widget.AbsListView again = tab == LIBRARY ? libraryList : null;
        if (again instanceof ListView) ((ListView) again).setSelectionFromTop(first, top);
        else if (again != null) again.setSelection(first);
        else keepScroll(pageScroll, y);
        if (again != null) again.setLayoutAnimation(null); // no cascade for a quiet refresh
    }
    boolean watchingPlaylist;

    private void refreshLibraryIfStale() {
        long now = SystemClock.uptimeMillis();
        if (!libraryReady || refreshing || now - libraryLoadedAt < 90_000) return;
        refreshing = true;
        libraryLoadedAt = now;
        submit(DATA, () -> {
            boolean changed = nativeRefreshLibrary();
            post(() -> { refreshing = false; if (changed && playlist == null && sheetClose == null) showPage(); });
        });
    }

    /** state: revision, uri, title, artist, album, image, playing, shuffle, repeat, position, duration, source,
     *  explicit, context, then four strings (title, artist, image, uri) per queued track. */
    private void applyPlayer(String[] s) {
        float from = shownProgress(), previousFraction = playbackFraction();
        revision = Long.parseLong(s[0]);
        boolean changed = !uri.equals(s[1]);
        if (changed) {
            stopHeldSeek();
            if (wave != null) wave.cancelScrub();
        }
        uri = s[1]; title = s[2]; artist = s[3]; album = s[4]; image = s[5];
        if (changed && playerScreen.lyricsPanel != null) { playerScreen.closeLyrics(); post(playerScreen::openLyrics); } // a new song: show its lyrics, not the last one's
        if (changed) sideQueueOffset = 0;
        if (changed && sideHost != null) {
            sideTabManual = false;
            if (uri.isEmpty()) {
                if (sideTab != 1 && sideTabSelect != null) post(() -> { if (sideTabSelect != null) sideTabSelect.accept(1, true); });
            } else if (sideTab == 0) {
                post(() -> playerScreen.loadSidePanel(false, 0));
            } else {
                String trackId = uri.substring(uri.lastIndexOf(':') + 1);
                String trackUri = uri, trackTitle = title, trackArtist = artist;
                submit(ART, () -> {
                    CoreBridge.Lyrics got = null;
                    try { got = CoreBridge.lyrics(trackId, trackTitle, trackArtist, 0); } catch (Throwable ignored) {}
                    boolean has = got != null && got.words.length > 0;
                    if (!has) noLyricsUri = trackUri;
                    post(() -> {
                        if (has && !sideTabManual && sideHost != null && sideTab == 1 && sideTabSelect != null && trackUri.equals(uri)) {
                            sideTabSelect.accept(0, true);
                        }
                    });
                });
            }
        }
        playing = Boolean.parseBoolean(s[6]);
        if (backdrop != null) backdrop.drifting(playing);
        if (pending != null && (changed || (pending == playing && SystemClock.uptimeMillis() >= pendingUntil))) pending = null;
        shuffle = Boolean.parseBoolean(s[7]);
        repeat = Integer.parseInt(s[8]);
        position = Long.parseLong(s[9]);
        long coreDuration = Long.parseLong(s[10]);
        if (coreDuration > 0 || changed) duration = Math.max(0, coreDuration);
        if (duration == 0 && !uri.isEmpty()) for (Song song : playlistScreen.allSongs) {
            if (song.uri.equals(uri) && song.duration > 0) { duration = song.duration; break; }
        }
        source = s[11];
        rating = s[12];
        boolean contextChanged = !context.equals(s[13]);
        if (!context.equals(s[13])) {
            // Learn whether the new context is in Smart Shuffle.
            String next = s[13];
            smartShuffle = false;
            if (PlayerScreen.isPlaylistLink(next)) submit(PLAYLISTS, () -> {
                int m = nativeShuffleMode(next);
                post(() -> { if (next.equals(context)) { smartShuffle = m == 2; refresh(); } });
            });
        }
        context = s[13];
        // Home and Library mark what's playing; redraw them in place when that changes.
        if (contextChanged && playlist == null && sheetClose == null && !playerOpen && tab != SETTINGS && tab != SEARCH && !(findFocus() instanceof android.widget.EditText)) post(this::showPageKeepingPlace);
        quality = s[14];
        speed = OfficialPlayback.enabled() ? 1f : nativePlaybackSpeed();
        boolean wasBuffering = buffering;
        // The core can report buffering while paused and idle; a spinner there would turn forever for nothing, so it shows only while playing or after a play tap.
        buffering = Boolean.parseBoolean(s[15]) && (playing || Boolean.TRUE.equals(pending));
        boolean skipChanged = canNext != Boolean.parseBoolean(s[16]) || canPrevious != Boolean.parseBoolean(s[17]);
        canNext = Boolean.parseBoolean(s[16]);
        canPrevious = Boolean.parseBoolean(s[17]);
        albumUri = s[18];
        artistUri = s[19];
        // New skip buttons set their own initial state (skip()), so only a real change needs the fade.
        if (skipChanged) for (View b : skipButtons.keySet()) {
            boolean allowed = skipButtons.get(b) ? canNext : canPrevious;
            b.setEnabled(allowed);
            b.animate().alpha(allowed ? 1f : .35f).setDuration(200).start();
        }
        if (buffering != wasBuffering) {
            for (PlayButton b : playButtons) b.invalidate();
            if (playlistScreen.listFab != null) playlistScreen.listFab.invalidate();
        }
        stamp = SystemClock.uptimeMillis();
        LyricGen.playback(uri.substring(uri.lastIndexOf(':') + 1), position, playing, duration);
        if (changed || Math.abs(playbackFraction() - previousFraction) >= .0005f) animateProgress(from);
        List<MediaEntry> next = new ArrayList<>();
        for (int i = 20; i + 5 < s.length; i += 6) {
            MediaEntry entry = new MediaEntry(s[i], s[i + 1], s[i + 3], s[i + 2], -1);
            entry.queueKey = s[i + 4]; entry.editable = Boolean.parseBoolean(s[i + 5]);
            next.add(entry);
        }
        boolean queueChanged = !next.equals(upcoming) || changed;
        for (int i = 0; !queueChanged && i < next.size(); i++) queueChanged = !next.get(i).queueKey.equals(upcoming.get(i).queueKey);
        upcoming = next;
        if (playerScreen.queuePanel != null && queueChanged) playerScreen.renderQueue();
        if (queueChanged && sideHost != null && sideTab == 1) post(() -> playerScreen.loadSidePanel(false));
        if (changed) playerScreen.checkSaved(uri);
        refreshPalette();
        refresh();
    }


    /** Rebuilds the whole tree: insets or font/motion settings changed. */
    void build() {
        materialIcons = prefs.getBoolean("material_icons", false);
        if (accountMenu != null) accountMenu.dismiss();
        creation.closeCreateMenu();
        playerScreen.closeQueue();
        removeAllViews();
        sheetClose = null;
        nextKey = "";
        setBackgroundColor(bg);
        pageHost = new FrameLayout(activity) {
            // GPU effects: what the page draws is also kept as a RenderNode, which the dock's frosted pills blur.
            @Override protected void dispatchDraw(Canvas canvas) {
                if (!dockUi.frosted || !canvas.isHardwareAccelerated()) { super.dispatchDraw(canvas); return; }
                if (dockUi.pageNode == null) { dockUi.pageNode = new android.graphics.RenderNode("page"); dockUi.pageNode.setClipToBounds(false); }
                dockUi.pageNode.setPosition(0, 0, getWidth(), getHeight());
                android.graphics.RecordingCanvas into = dockUi.pageNode.beginRecording();
                super.dispatchDraw(into);
                dockUi.pageNode.endRecording();
                canvas.drawRenderNode(dockUi.pageNode);
            }
        };
        // Measured on a Pixel 11 Pro: about 12% more CPU and 2 ms slower frames while scrolling, so it's opt-in.
        dockUi.frosted = GpuFx.blur(prefs) && prefs.getBoolean("frost", false);
        LayoutParams hostAt = new LayoutParams(-1, -1);
        hostAt.leftMargin = sideLeft(); hostAt.rightMargin = inset.right; // landscape: clear of the side system bar, cutout and rail
        pageHost.setClipChildren(false); // page backdrops may reach out under those insets (and the rail)
        setClipChildren(false);
        addView(pageHost, hostAt);
        // Lists dim as they pass under the mini player and bar instead of showing through them.
        View fade = new View(activity);
        dockFade = fade;
        GradientDrawable fadeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bg & 0x00ffffff, (bg & 0x00ffffff) | 0xd8000000, bg, bg});
        fade.setBackground(fadeBg);
        colored(fade, () -> fadeBg.setColors(new int[]{bg & 0x00ffffff, (bg & 0x00ffffff) | 0xd8000000, bg, bg}));
        addView(fade, new LayoutParams(-1, dockRoom() + dp(20), Gravity.BOTTOM));
        if (landscape() || dockUi.frosted) fade.setVisibility(GONE); // no fade when frosted glass blurs the page itself
        // Subtle edge-to-edge top fade so scrolled content softens behind the clock/icons without a hard solid bar.
        View scrim = new View(activity);
        topScrim = scrim;
        GradientDrawable topBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{(bg & 0x00ffffff) | 0x48000000, bg & 0x00ffffff});
        scrim.setBackground(topBg);
        colored(scrim, () -> topBg.setColors(new int[]{(bg & 0x00ffffff) | 0x48000000, bg & 0x00ffffff}));
        addView(scrim, new LayoutParams(-1, inset.top + dp(16), Gravity.TOP));
        dock = column();
        // A swipe nudges the mini player sideways past the dock's padding (and the rail's narrow edge); let it show.
        dock.setClipToPadding(false);
        dock.setClipChildren(false);
        dockUi.dockHidden = false;
        scrollTravel = 0;
        if (landscape()) {
            // Sideways the dock is a rail down the left: tabs at the top, the playing song at the foot, and the
            // content keeps the whole height beside it instead of sliding under a bar.
            dock.setPadding(dp(RAIL_GAP) + inset.left, inset.top + dp(4), 0, dp(8) + inset.bottom);
            addView(dock, new LayoutParams(dp(RAIL_GAP + RAIL) + inset.left, -1, Gravity.START));
        } else {
            dock.setPadding(dp(12) + inset.left, 0, dp(12) + inset.right, dp(10) + inset.bottom);
            addView(dock, new LayoutParams(-1, -2, Gravity.BOTTOM));
        }
        dockUi.buildDock();
        player = null;
        showPage();
        if (playerOpen) { playerOpen = false; playerScreen.openPlayer(false); }
    }

    /** Room a page leaves at its foot for the dock: two rows in portrait; in landscape the dock is a side rail. */
    int dockRoom() { return dp(landscape() ? 16 : 170) + inset.bottom; }

    /** Side rail width and its gap from the screen edge (dp); the gap keeps the rail clear of the camera hole. */
    static final int RAIL = 64, RAIL_GAP = 14;

    /** Where pages start from the left: past the side inset, plus the rail in landscape (less the page's own margin). */
    int sideLeft() { return inset.left + (landscape() ? dp(RAIL_GAP + RAIL - 8) : 0); }

    boolean landscape() {
        return activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    static final android.view.animation.Interpolator EMPHASIZED = new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f);

    /** Marks a page's full-bleed backdrop (see {@link #enter}). */
    static final Object BACKDROP = new Object();

    /** The page just shown slides in from (dx, dy) while it fades up. */
    void enter(float dx, float dy) {
        if (!motion() || quietEnter || pageHost.getChildCount() == 0) return;
        View page = pageHost.getChildAt(0);
        // Fading a page normally draws it through an offscreen layer cut to the page's bounds, which hid a backdrop
        // reaching past them (under the rail and camera strip) until the fade ended; fade each child in place instead.
        page.forceHasOverlappingRendering(false);
        page.setAlpha(0f);
        page.setTranslationX(dx);
        page.setTranslationY(dy);
        page.animate().alpha(1f).translationX(0).translationY(0).setDuration(300).setInterpolator(EMPHASIZED).start();
        // A backdrop that reaches past the page (under the rail and the camera strip) holds still and only fades,
        // so those edges colour in with the page instead of sliding in late behind it.
        if (page instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) page).getChildCount(); i++) {
            View child = ((ViewGroup) page).getChildAt(i);
            if (child.getTag() != BACKDROP) continue;
            if (dx == 0) continue; // vertical entrances leave it be: the backdrop's own scroll parallax owns translationY
            child.setTranslationX(-dx);
            child.animate().translationX(0).setDuration(300).setInterpolator(EMPHASIZED).start();
        }
    }

    /** Closes the on-screen keyboard and drops text focus; true if it was open. */
    boolean hideKeyboard() {
        View focused = findFocus();
        boolean open = android.os.Build.VERSION.SDK_INT >= 30 && getRootWindowInsets() != null && getRootWindowInsets().isVisible(WindowInsets.Type.ime());
        if (!(focused instanceof android.widget.EditText) && !open) return false;
        android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        if (focused != null) focused.clearFocus();
        requestFocus();
        return android.os.Build.VERSION.SDK_INT >= 30 ? open : focused instanceof android.widget.EditText;
    }

    /** An × at the end of a search box, shown once there's text, that empties it. */
    void clearButton(LinearLayout box, android.widget.EditText field) {
        View clear = iconButton(icon("close", () -> inkDim), "Clear search", () -> field.setText(""));
        clear.setVisibility(field.length() > 0 ? VISIBLE : GONE);
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { clear.setVisibility(e.length() > 0 ? VISIBLE : GONE); }
        });
        box.addView(clear, lp(dp(40), dp(40)));
    }

    // Spotify's downloads live in its core (Offline service); Rust follows them and these screens show them.
























    TextView pageHeading(LinearLayout page, String title, boolean edgeToEdge) {
        return pageHeading(page, title, edgeToEdge, null);
    }

    TextView pageHeading(LinearLayout page, String title, boolean edgeToEdge, View leading) {
        LinearLayout head = row();
        head.setPadding(edgeToEdge ? dp(18) : 0, dp(16), edgeToEdge ? dp(18) : 0, dp(12));
        if (leading != null) {
            LinearLayout.LayoutParams gp = lp(dp(34), dp(34));
            gp.rightMargin = dp(10);
            head.addView(leading, gp);
        }
        TextView label = text(title, 34, () -> ink, true);
        oneLine(label);
        head.addView(label, weight());
        head.addView(space(dp(12), 1));
        FrameLayout account = new FrameLayout(activity);
        bgRound(account, 24, () -> surfHi);
        account.addView(profilePhoto(), new LayoutParams(-1, -1));
        tappable(account, "Account menu", () -> accountMenu(account));
        head.addView(account, lp(dp(42), dp(42)));
        page.addView(head, fill(-2));
        loadProfile(false);
        return label;
    }

    private Art profilePhoto() {
        Art photo = artwork.new Art(Art.CIRCLE, false);
        photo.letter = initial(profileName.isEmpty() ? profileUsername.isEmpty() ? "You" : profileUsername : profileName);
        photo.bind(profileImage);
        avatars.add(photo);
        return photo;
    }

    /** A short "updated Xs/Xm/Xh ago" style label from an `uptimeMillis()` timestamp. */
    private static String ago(long at) {
        long secs = (SystemClock.uptimeMillis() - at) / 1000;
        if (secs < 5) return "just now";
        if (secs < 60) return secs + "s ago";
        long mins = secs / 60;
        if (mins < 60) return mins + "m ago";
        return (mins / 60) + "h ago";
    }

    private void loadProfile(boolean force) {
        if (closed) return;
        long now = SystemClock.uptimeMillis();
        // A load that has hung for 20 s no longer blocks a new one (it used to, so Refresh did nothing).
        boolean hung = profileLoading && now - profileStartedAt > 20_000;
        if (profileLoading && !hung) return;
        if (!force && profileAt != 0 && now - profileAt < (profileUsername.isEmpty() ? 5_000 : 60_000)) return;
        removeCallbacks(profileRetry);
        profileLoading = true;
        profileStartedAt = now;
        final int ticket = ++profileTicket;
        submit(PLAYLISTS, () -> {
            String[] result;
            try { result = nativeProfile(); }
            catch (RuntimeException | LinkageError failed) { result = new String[]{"", "", "", "", failed.toString()}; }
            final String[] info = result;
            post(() -> {
                if (closed || ticket != profileTicket) return; // a newer load or detach took over
                profileLoading = false;
                profileAt = SystemClock.uptimeMillis();
                if (info != null && info.length >= 5) {
                    boolean fallback = !info[4].isEmpty();
                    if (!info[1].isEmpty()) {
                        // A failed profile fetch falls back to the username; keep the saved name/photo for that account,
                        // but let a successful fetch update or clear them, and give a newly discovered username fresh retries.
                        boolean same = info[1].equals(profileUsername);
                        if (!same) profileAttempts = 0;
                        String image = fallback && info[2].isEmpty() && same ? profileImage : info[2];
                        String name = fallback && same && !profileName.isEmpty() && info[0].equals(info[1]) ? profileName : info[0];
                        profileName = name; profileUsername = info[1]; profileImage = image; profileUri = info[3];
                        prefs.edit().putString("profile_name", profileName).putString("profile_user", profileUsername)
                            .putString("profile_image", profileImage).putString("profile_uri", profileUri).apply();
                    }
                    // A failed lookup never blanks the account saved from last time.
                    profileError = !fallback && info[1].isEmpty() ? "Profile information is not available yet" : info[4];
                    if (!profileError.isEmpty() && profileUsername.isEmpty()) profileName = "";
                    for (Art photo : avatars) {
                        photo.letter = initial(profileName.isEmpty() ? profileUsername.isEmpty() ? "You" : profileUsername : profileName);
                        photo.bind(profileImage);
                        photo.invalidate();
                    }
                } else profileError = "Profile information is not available yet";
                if (!profileError.isEmpty() && (profileUsername.isEmpty() || profileAttempts < 12)) {
                    // Keep trying with a growing pause: 2, 4, 8, 16, then every 30 seconds (twelve times if the
                    // saved account is already showing).
                    profileAttempts++;
                    if (isAttachedToWindow() && getWindowVisibility() == VISIBLE) {
                        postDelayed(profileRetry, Math.min(30_000, 2_000L << Math.min(profileAttempts - 1, 4)));
                    }
                } else if (profileError.isEmpty()) profileAttempts = 0;
                if (profileAnnounce) {
                    profileAnnounce = false;
                    String msg = profileUsername.isEmpty()
                        ? "Couldn’t read your account yet. Spatch will keep trying."
                        : profileError.isEmpty() ? "Signed in as @" + profileUsername : "Couldn’t refresh profile: " + profileError;
                    toast(msg);
                }
                if (tab == PROFILE && playlist == null) showPage();
            });
        });
    }

    private void accountMenu(View anchor) {
        hideKeyboard();
        if (accountMenu != null) accountMenu.dismiss();
        LinearLayout menu = column();
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout identity = row();
        identity.setPadding(dp(10), dp(10), dp(10), dp(14));
        identity.addView(profilePhoto(), lp(dp(42), dp(42)));
        LinearLayout words = column();
        words.setPadding(dp(12), 0, 0, 0);
        TextView accountName = text(profileName.isEmpty() ? "Your account" : profileName, 16, () -> ink, true);
        TextView accountHandle = text(profileUsername.isEmpty() ? "Spotify profile" : "@" + profileUsername, 12, () -> inkDim, false);
        oneLine(accountName);
        oneLine(accountHandle);
        words.addView(accountName);
        words.addView(accountHandle);
        identity.addView(words, weight());
        menu.addView(identity, fill(-2));
        android.widget.PopupWindow popup = new android.widget.PopupWindow(menu, dp(278), -2, true);
        accountMenu = popup;
        popup.setBackgroundDrawable(round(surf, 24));
        popup.setElevation(dp(14));
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> { if (accountMenu == popup) accountMenu = null; });
        menuAction(menu, "Profile", "artist", () -> { popup.dismiss(); goFromLink(PROFILE); });
        menuAction(menu, "Queue", "queue", () -> { popup.dismiss(); playerScreen.openQueue(); });
        menuAction(menu, "Downloads", "download", () -> { popup.dismiss(); filter = DOWNLOADED; if (tab == LIBRARY && playlist == null) { showPage(); dockUi.updateDock(); } else goFromLink(LIBRARY); });
        menuAction(menu, "Settings", "settings", () -> { popup.dismiss(); openSettings(true); });
        popup.showAsDropDown(anchor, 0, dp(8), Gravity.END);
        popIn(menu);
    }

    /** The menu grows out of its corner. */
    void popIn(LinearLayout menu) {
        if (motion()) {
            menu.setAlpha(0f);
            menu.setTranslationY(-dp(8));
            menu.setScaleX(.92f);
            menu.setScaleY(.92f);
            menu.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    menu.getViewTreeObserver().removeOnPreDrawListener(this);
                    menu.setPivotX(menu.getWidth());
                    menu.setPivotY(0);
                    menu.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                        .setDuration(220).setInterpolator(EMPHASIZED).start();
                    return true;
                }
            });
        }
    }


    void menuAction(LinearLayout menu, String title, String symbol, Runnable action) {
        LinearLayout item = row();
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        item.addView(icon(symbol, () -> inkDim), lp(dp(22), dp(22)));
        TextView label = text(title, 15, () -> ink, true);
        label.setPadding(dp(14), 0, 0, 0);
        item.addView(label, weight());
        tappable(item, title, action);
        menu.addView(item, fill(dp(48)));
    }

    void openSettings(boolean fromLink) {
        settingsSection = -1;
        sound.panel = -1;
        if (tab == SETTINGS && playlist == null) showPage();
        else if (fromLink) goFromLink(SETTINGS);
        else { tabHistory.clear(); go(SETTINGS, false); }
    }

    private String tabLabel(int t) {
        switch (t) {
            case HOME: return "Home";
            case SEARCH: return "Search";
            case LIBRARY: return "Library";
            case SETTINGS: return "Settings";
            case PROFILE: return "Profile";
            case STATS: return "Your library";
            default: return "Back";
        }
    }

    View backHeader(String label, boolean horizontalPad) {
        LinearLayout row = row();
        row.setPadding(horizontalPad ? dp(16) : dp(2), dp(6), dp(12), dp(2));
        row.addView(icon("back", () -> accent), lp(dp(20), dp(20)));
        TextView text = text(label, 15, () -> accent, true);
        text.setPadding(dp(6), 0, 0, 0);
        row.addView(text);
        tappable(row, "Back to " + label, this::back);
        return row;
    }

    /**
     * Spotify's own screens do the sign-in. Starting its login activity from here with hand-built extras hung after the
     * credentials were entered, while Spotify's own Add account (account switching) works, so this reveals Spotify's
     * UI instead; its "Back to Spatch" pill returns here.
     */
    private void openLogin() {
        toast("Open your profile in Spotify and choose Add account", true);
        openNativeSpotify();
    }

    void openNativeSpotify() {
        String error = CoreBridge.openNativeSpotify(activity);
        if (error != null && !error.isEmpty()) toast(error, true);
    }

    /** What Spatch knows about the connection to Spotify, with a button to copy it for whoever is helping. */
    private void connectionCard(LinearLayout page) {
        LinearLayout box = settingsScreen.card(page, "Connection");
        String[] c;
        try { c = nativeConnection(); } catch (RuntimeException | LinkageError failed) { c = null; }
        String library = c != null && c.length > 0 ? c[0] : "unknown";
        String username = !profileUsername.isEmpty() ? "@" + profileUsername : c != null && c.length > 1 && !c[1].isEmpty() ? "@" + c[1] : "not found yet";
        String lookup = c != null && c.length > 2 ? c[2] : "";
        StringBuilder report = new StringBuilder("Spatch build ").append(nativeBuild())
            .append("\nLibrary: ").append(library)
            .append("\nUsername: ").append(username)
            .append("\nProfile: ").append(profileError.isEmpty() ? (profileLoading ? "loading" : "ok") : profileError);
        if (!lookup.isEmpty()) report.append("\nLast lookup: ").append(lookup);
        TextView facts = text(report.toString(), 13, () -> inkDim, false);
        facts.setPadding(dp(18), dp(14), dp(18), dp(6));
        box.addView(facts, fill(-2));
        final String copy = report.toString();
        settingsScreen.settingsAction(box, "Copy details", "For troubleshooting: paste it to whoever is helping you", () -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spatch connection details", copy));
            toast("Details copied");
        });
    }

    private View profilePage() {
        LinearLayout page = column();
        page.addView(backHeader(tabHistory.isEmpty() ? "Back" : tabLabel(tabHistory.peek()), false), lp(-2, dp(36)));
        pageHeading(page, "Your profile", false);
        LinearLayout identity = settingsScreen.card(page, "Spotify account");
        LinearLayout photo = row();
        photo.setPadding(dp(18), dp(16), dp(18), dp(16));
        photo.addView(profilePhoto(), lp(dp(88), dp(88)));
        LinearLayout words = column();
        words.setPadding(dp(18), 0, 0, 0);
        words.addView(text(profileName.isEmpty() ? "Your profile" : profileName, 24, () -> ink, true));
        TextView profileUsernameLine = text(profileUsername.isEmpty() ? (profileLoading ? "Connecting to your Spotify account…" : "Couldn’t read your username yet. Still trying.") : "@" + profileUsername, 13, () -> inkDim, false);
        if (!profileUsername.isEmpty()) oneLine(profileUsernameLine);
        words.addView(profileUsernameLine);
        if (profileUsername.isEmpty() && !profileError.isEmpty() && !profileLoading) words.addView(text(profileError, 12, () -> inkDim, false));
        photo.addView(words, weight());
        identity.addView(photo, fill(-2));
        if (!profileUri.isEmpty()) settingsScreen.settingsAction(identity, "Copy profile link", "Share your Spotify profile", () -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            String link = profileUri.startsWith("spotify:user:") ? "https://open.spotify.com/user/" + android.net.Uri.encode(profileUri.substring(13)) : profileUri;
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotify profile", link));
            toast("Profile link copied");
        });
        String refreshSubtitle = profileUsername.isEmpty() ? "Spatch is still working out who you are. Tap to check now."
            : profileLoading ? "Checking your Spotify account…"
            : profileAt == 0 ? "Reload your name and photo from Spotify"
            : "Reload your name and photo from Spotify · Updated " + ago(profileAt);
        settingsScreen.settingsAction(identity, "Refresh profile", refreshSubtitle, () -> {
            profileAnnounce = true;
            toast(profileLoading ? "Still checking your Spotify account…" : "Checking your Spotify account…");
            loadProfile(true);
        });
        settingsScreen.settingsAction(identity, "Sign in or switch account", "Opens Spotify’s own screens to sign in or add an account. If you’re already signed in, you don’t need this.", this::openLogin);
        settingsScreen.settingsAction(identity, "Sign in here (test)", "Spotify’s own sign-in screen, opened the way Spotify opens it", () -> {
            String error = CoreBridge.openSpotifyLogin(activity);
            if (error != null && !error.isEmpty()) toast(error, true);
        });
        settingsScreen.settingsAction(identity, "Open native Spotify", "Temporarily reveal Spotify’s own interface to sign in or manage account", this::openNativeSpotify);
        connectionCard(page);
        LinearLayout music = settingsScreen.card(page, "Your music");
        settingsScreen.settingsAction(music, "Your playlists", "Open your library", () -> { filter = 0; goFromLink(LIBRARY); });
        settingsScreen.settingsAction(music, "Create a playlist", "Start a new collection", () -> { creation.pendingPlaylistSong = null; creation.openCreatePlaylist(false); });
        settingsScreen.settingsAction(music, "Your library, at a glance", "What's saved, and what you've played lately", () -> goFromLink(STATS));
        settingsScreen.settingsAction(music, "Settings", "Personalise Spatch", () -> openSettings(true));
        return scrolling(page);
    }

    /** A simple recap built only from what Spatch already has cached: counts by kind, and the recent shelf. */
    private View statsPage() {
        LinearLayout page = column();
        page.addView(backHeader(tabHistory.isEmpty() ? "Back" : tabLabel(tabHistory.peek()), false), lp(-2, dp(36)));
        pageHeading(page, "Your library", false);
        int[] counts = new int[4]; // playlists, albums, artists, podcasts, by chip index
        for (MediaEntry e : entries(-1, "", false, 4000)) if (e.category >= 0 && e.category < counts.length) counts[e.category]++;
        LinearLayout glance = settingsScreen.card(page, "Saved");
        if (motion()) {
            android.view.animation.AnimationSet rise = new android.view.animation.AnimationSet(true);
            rise.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
            rise.addAnimation(new android.view.animation.TranslateAnimation(0, 0, dp(18), 0));
            rise.setDuration(320);
            rise.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
            glance.setLayoutAnimation(new android.view.animation.LayoutAnimationController(rise, .07f));
        }
        String[] kindNames = {"Playlists", "Albums", "Artists", "Podcasts"};
        for (int i = 0; i < counts.length; i++) {
            LinearLayout row = row();
            row.setPadding(dp(18), dp(10), dp(18), dp(10));
            row.addView(text(kindNames[i], 15, () -> ink, false), weight());
            row.addView(text(String.valueOf(counts[i]), 15, () -> inkDim, true));
            glance.addView(row, fill(-2));
        }
        int total = counts[0] + counts[1] + counts[2] + counts[3];
        TextView totalNote = text(total == 1 ? "1 item saved" : total + " items saved, across everything Spatch can see in your library", 12, () -> inkDim, false);
        totalNote.setPadding(dp(18), dp(4), dp(18), dp(12));
        glance.addView(totalNote);
        homeScreen.shelf(page, "Recently played", homeScreen.recent, null);
        return scrolling(page);
    }

    android.widget.EditText createField(String hint, String value, boolean multiline) {
        android.widget.EditText field = new android.widget.EditText(activity);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(16);
        field.setPadding(dp(18), dp(16), dp(18), dp(16));
        field.setSingleLine(!multiline);
        if (multiline) { field.setMinLines(3); field.setGravity(Gravity.TOP); }
        bgRound(field, 20, () -> surf);
        colored(field, () -> { field.setTextColor(ink); field.setHintTextColor(inkDim); });
        field.setContentDescription(hint);
        return field;
    }

    private View createPage() { return homeScreen.homePage(); }












    void goFromLink(int next) {
        if (next != tab && (tabHistory.isEmpty() || tabHistory.peek() != tab)) tabHistory.push(tab);
        go(next, true);
    }

    void go(int next) {
        tabHistory.clear();
        go(next, false);
    }

    void go(int next, boolean pushForward) {
        creation.closeCreateMenu();
        hideKeyboard();
        if (next == tab && playlist == null) return;
        boolean forward = pushForward || next > tab;
        playlist = null;
        pageStack.clear();
        tab = next;
        dockUi.setDockHidden(false);
        showPage();
        dockUi.updateDock();
        enter((forward ? 1 : -1) * dp(36), 0);
    }








    void showPage() {
        scrollTravel = 0;
        dockUi.setDockHidden(false);
        if (playlist == null && watchingPlaylist) { watchingPlaylist = false; WATCHES.execute(SpatchUi::nativeStopPlaylist); }
        if (playlistScreen.flightView != null) { removeView(playlistScreen.flightView); playlistScreen.flightView = null; }
        rememberScroll();
        pageHost.removeAllViews();
        pageScroll = null;
        pageList = null;
        libraryList = null;
        heroArt = null; heroProgress = null; heroTitle = heroArtist = null;
        if (topScrim != null) topScrim.setVisibility(playlist == null ? VISIBLE : GONE);
        View page = playlist != null ? playlistScreen.playlistPage() : tab == HOME ? homeScreen.homePage() : tab == SEARCH ? searchScreen.searchPage() : tab == LIBRARY ? libraryScreen.libraryPage() : tab == CREATE ? createPage() : tab == PROFILE ? profilePage() : tab == STATS ? statsPage() : settingsScreen.settingsPage();
        pageHost.addView(page, new LayoutParams(-1, -1));
        shownKey = pageKey();
        restoreScroll();
        if (tab != SETTINGS && playlist == null) refreshLibraryIfStale();
        if ((tab == HOME || tab == STATS) && playlist == null) homeScreen.loadRecentIfStale();
        refresh();
    }

    ScrollView scrolling(LinearLayout content) {
        ScrollView s = new ScrollView(activity);
        s.setVerticalScrollBarEnabled(false);
        s.setClipToPadding(false);
        s.setPadding(0, inset.top, 0, 0);
        content.setPadding(dp(18), dp(10), dp(18), dockRoom());
        content.setClipToPadding(false); // edge-to-edge shelves draw into the side padding
        s.addView(content, new ScrollView.LayoutParams(-1, -2));
        s.setOnScrollChangeListener((view, x, y, oldX, oldY) -> dockUi.scrollDock(y - oldY, y <= 0));
        pageScroll = s;
        return s;
    }




    TextView pill(String label, Runnable action) {
        TextView v = text(label, 14, onAccent, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable d = round(accent, 22);
        v.setBackground(d);
        colored(v, () -> { v.setTextColor(onAccent); d.setColor(accent); });
        tappable(v, label, action);
        LinearLayout.LayoutParams p = lp(-2, -2);
        p.topMargin = dp(12);
        v.setLayoutParams(p);
        return v;
    }

    static List<MediaEntry> entryList(String[] f) {
        List<MediaEntry> out = new ArrayList<>();
        if (f == null) return out;
        for (int i = 0; i + 4 < f.length; i += 5) out.add(new MediaEntry(f[i], f[i + 1], f[i + 2], f[i + 3], PlaylistScreen.parseInt(f[i + 4])));
        return out;
    }

    /** Library items for a chip (-1 for all), searched and sorted by Rust. */
    List<MediaEntry> entries(int category, String search, boolean alphabetical, int limit) {
        if (category == DOWNLOADED) {
            List<MediaEntry> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (MediaEntry e : entryList(unpackStrings(nativeEntries(-1, search, alphabetical, 400)))) {
                if (downloadsScreen.downloadState(e.uri) != 0 && out.size() < limit && seen.add(e.uri)) out.add(e);
            }
            String[] words = search.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
            for (MediaEntry e : downloadsScreen.downloadDetails.values()) {
                if (out.size() >= limit || !seen.add(e.uri)) continue;
                String text = (e.title + " " + e.subtitle).toLowerCase(java.util.Locale.ROOT);
                boolean matches = true;
                for (String word : words) if (!text.contains(word)) matches = false;
                if (matches) out.add(e);
            }
            if (alphabetical) out.sort((a, b) -> liked(a.uri) != liked(b.uri) ? liked(a.uri) ? -1 : 1 : a.title.compareToIgnoreCase(b.title));
            return out;
        }
        return entryList(unpackStrings(nativeEntries(category, search, alphabetical, limit)));
    }

    List<MediaEntry> entries(int category, int limit) { return entries(category, "", false, limit); }










    /** A grid cell: a square cover (round for artists) with the name and kind under it. */
    final class Tile extends LinearLayout {
        final Art art = artwork.new Art(Art.ROUNDED, false);
        final TextView name = text("", 15, ink, true), sub = text("", 13, inkDim, false);
        private final FrameLayout chipHolder = new FrameLayout(activity);
        Tile() {
            super(activity);
            setOrientation(VERTICAL);
            FrameLayout square = new FrameLayout(activity) {
                @Override protected void onMeasure(int w, int h) { super.onMeasure(w, w); }
            };
            square.addView(art, new LayoutParams(-1, -1));
            LayoutParams hp = new LayoutParams(dp(26), dp(26), Gravity.BOTTOM | Gravity.START);
            hp.setMargins(dp(8), 0, 0, dp(8));
            square.addView(chipHolder, hp);
            addView(square, new LinearLayout.LayoutParams(-1, -2));
            oneLine(name); oneLine(sub);
            name.setPadding(dp(2), dp(8), dp(2), 0);
            sub.setPadding(dp(2), dp(2), dp(2), 0);
            addView(name);
            addView(sub);
            colored(this, () -> { name.setTextColor(ink); sub.setTextColor(inkDim); });
        }
        @Override public void setPressed(boolean pressed) {
            boolean was = isPressed();
            super.setPressed(pressed);
            if (was == pressed || !motion()) return;
            animate().scaleX(pressed ? .955f : 1f).scaleY(pressed ? .955f : 1f).setDuration(pressed ? 90 : 280)
                .setInterpolator(pressed ? new android.view.animation.DecelerateInterpolator() : new android.view.animation.OvershootInterpolator(2f)).start();
        }
        void bind(MediaEntry e) {
            art.shapeKind = e.category == 2 ? Art.CIRCLE : Art.ROUNDED;
            art.letter = initial(e.title);
            art.heart = liked(e.uri) && e.image.isEmpty();
            art.bind(e.image);
            name.setText(e.title);
            name.setTextColor(playingFrom(e.uri) ? accent : ink);
            sub.setText(e.subtitle);
            chipHolder.removeAllViews();
            int ds = downloadsScreen.downloadState(e.uri);
            if (ds != 0) chipHolder.addView(downloadsScreen.downloadChip(ds), new LayoutParams(-1, -1));
        }
    }

    ListView entryList(List<MediaEntry> items, View header) {
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        if (header != null) list.addHeaderView(header, null, false);
        if (motion()) {
            // Rows cascade in when a page opens: each rises and fades in a beat after the one above.
            android.view.animation.AnimationSet rise = new android.view.animation.AnimationSet(true);
            rise.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
            rise.addAnimation(new android.view.animation.TranslateAnimation(0, 0, dp(18), 0));
            rise.setDuration(320);
            rise.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
            android.view.animation.LayoutAnimationController cascade = new android.view.animation.LayoutAnimationController(rise, .07f);
            list.setLayoutAnimation(cascade);
        }
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public MediaEntry getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View old, ViewGroup parent) {
                Row row = old instanceof Row ? (Row) old : new Row();
                // On an album every song shares the cover, so rows show their track number instead, as Spotify does.
                row.number = playlist != null && playlist.uri.startsWith("spotify:album:") && items == playlistTracks ? i + 1 : 0;
                row.bind(getItem(i));
                return row;
            }
        });
        list.setOnItemClickListener((p, v, i, id) -> {
            Object item = p.getItemAtPosition(i);
            if (item instanceof MediaEntry && !((MediaEntry) item).uri.isEmpty()) open((MediaEntry) item);
        });
        list.setOnItemLongClickListener((p, v, i, id) -> {
            Object item = p.getItemAtPosition(i);
            if (!(item instanceof MediaEntry) || ((MediaEntry) item).uri.isEmpty()) return false;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            actions((MediaEntry) item);
            return true;
        });
        return list;
    }

    final Set<Row> attachedRows = new HashSet<>();

    final class Row extends LinearLayout {
        private String boundUri = "";
        private MediaEntry boundEntry;
        final Art art = artwork.new Art(Art.ROUNDED, false);
        final TextView name = text("", 16, ink, true), sub = text("", 13, inkDim, false), length = text("", 13, inkDim, false);
        private final Bars bars = controls.new Bars();
        private final FrameLayout lead = new FrameLayout(activity);
        private final View artScrim = new View(activity);
        private final GradientDrawable artScrimBg = round(0x88000000, 14);
        private final GradientDrawable pressedBg = round(surfHi, 16), badgeBg = round(inkDim, 3);
        private final TextView explicitBadge = text("E", 10, bg, true);
        private final Icon dlBadge = new Icon("download", inkDim);
        private final Icon moreIcon = new Icon("more", inkDim);
        private final FrameLayout moreBtn = new FrameLayout(activity);
        private int dlState;
        private boolean current;
        int number;
        private final TextView trackNumber = text("", 16, inkDim, true);
        Row() {
            super(activity);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(4), dp(7), dp(2), dp(7));
            lead.addView(art, new FrameLayout.LayoutParams(-1, -1));
            artScrim.setBackground(artScrimBg);
            artScrim.setVisibility(GONE);
            lead.addView(artScrim, new FrameLayout.LayoutParams(-1, -1));
            trackNumber.setGravity(Gravity.CENTER);
            trackNumber.setVisibility(GONE);
            lead.addView(trackNumber, new FrameLayout.LayoutParams(-1, -1));
            lead.addView(bars, new FrameLayout.LayoutParams(dp(18), dp(16), Gravity.CENTER));
            addView(lead, lp(dp(52), dp(52)));
            LinearLayout words = column();
            words.setPadding(dp(14), 0, dp(8), 0);
            oneLine(name); oneLine(sub);
            words.addView(name);
            // Explicit songs get Spotify's small E before the artist.
            LinearLayout byline = row();
            explicitBadge.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ep = lp(dp(15), dp(15));
            ep.rightMargin = dp(6);
            LinearLayout.LayoutParams dp2 = lp(dp(14), dp(14));
            dp2.rightMargin = dp(5);
            dlBadge.setVisibility(GONE);
            byline.addView(dlBadge, dp2);
            byline.addView(explicitBadge, ep);
            byline.addView(sub, weight());
            words.addView(byline);
            addView(words, weight());
            length.setPadding(0, 0, dp(4), 0);
            addView(length, lp(-2, -2));
            moreBtn.addView(moreIcon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            GradientDrawable moreBg = round(0, 18);
            moreBtn.setBackground(moreBg);
            tappable(moreBtn, "More options", .88f, () -> {
                animateMore(moreIcon, motion());
                if (motion()) {
                    moreBg.setColor((accent & 0x00ffffff) | 0x30000000);
                    android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(0x30, 0);
                    fade.setDuration(320);
                    fade.addUpdateListener(a -> moreBg.setColor((accent & 0x00ffffff) | (((int) a.getAnimatedValue()) << 24)));
                    fade.start();
                }
                if (boundEntry != null) actions(boundEntry);
            });
            moreBtn.setFocusable(false);
            moreBtn.setFocusableInTouchMode(false);
            moreBtn.setAlpha(.72f);
            moreBtn.setVisibility(GONE);
            addView(moreBtn, lp(dp(34), dp(40)));
            pressedBg.setAlpha(0);
            setBackground(pressedBg);
            colored(this, this::tint);
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attachedRows.add(this); updateDownloadBadge(); }
        @Override protected void onDetachedFromWindow() { attachedRows.remove(this); super.onDetachedFromWindow(); }
        void updateDownloadBadge() {
            dlState = downloadsScreen.downloadState(boundUri);
            dlBadge.setVisibility(dlState != 0 ? VISIBLE : GONE);
            tint();
        }
        private void tint() {
            name.setTextColor(current ? accent : ink);
            sub.setTextColor(inkDim);
            trackNumber.setTextColor(current ? accent : inkDim);
            length.setTextColor(inkDim);
            pressedBg.setColor(surfHi);
            badgeBg.setColor(inkDim);
            dlBadge.color = dlState == Downloads.DL_DONE ? accent : inkDim;
            dlBadge.invalidate();
            moreIcon.color = inkDim;
            moreIcon.invalidate();
            explicitBadge.setTextColor(bg);
            explicitBadge.setBackground(badgeBg);
        }
        /** A press sinks the row a little and fades a soft highlight in under it. */
        @Override public void setPressed(boolean pressed) {
            boolean was = isPressed();
            super.setPressed(pressed);
            if (was == pressed || !motion()) return;
            animate().scaleX(pressed ? .975f : 1f).scaleY(pressed ? .975f : 1f).setDuration(pressed ? 90 : 260)
                .setInterpolator(pressed ? new android.view.animation.DecelerateInterpolator() : new android.view.animation.OvershootInterpolator(2f)).start();
            android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(pressedBg.getAlpha(), pressed ? 255 : 0);
            fade.setDuration(pressed ? 90 : 320);
            fade.addUpdateListener(a -> pressedBg.setAlpha((int) a.getAnimatedValue()));
            fade.start();
        }
        void bind(MediaEntry e) {
            boundEntry = e;
            boolean numbered = number > 0;
            int leadW = dp(numbered ? 36 : 52);
            if (lead.getLayoutParams().width != leadW) { lead.getLayoutParams().width = leadW; lead.requestLayout(); }
            art.setVisibility(numbered ? GONE : VISIBLE);
            trackNumber.setText(String.valueOf(number));
            art.shapeKind = e.category == 2 ? Art.CIRCLE : Art.ROUNDED;
            artScrimBg.setCornerRadius(dp(e.category == 2 ? 26 : 14));
            art.letter = initial(e.title);
            art.heart = liked(e.uri) && e.image.isEmpty();
            art.bind(e.image);
            name.setText(e.title);
            sub.setText(e.subtitle);
            long ms = e instanceof Song ? ((Song) e).duration : 0;
            length.setText(ms > 0 ? time(ms) : "");
            length.setVisibility(ms > 0 ? VISIBLE : GONE);
            boolean hasMenu = e instanceof Song || SearchScreen.isTrack(e.uri);
            moreBtn.setVisibility(hasMenu ? VISIBLE : GONE);
            explicitBadge.setVisibility(e instanceof Song && ((Song) e).explicit ? VISIBLE : GONE);
            boundUri = e.uri;
            updateDownloadBadge();
            current = playlist != null ? !uri.isEmpty() && e.uri.equals(uri) : playingFrom(e.uri);
            tint();
            artScrim.setVisibility(!numbered && current ? VISIBLE : GONE);
            trackNumber.setVisibility(numbered && !current ? VISIBLE : GONE);
            bars.setVisibility(current ? VISIBLE : GONE);
        }
    }

    boolean playingFrom(String item) {
        if (context.isEmpty() || item.isEmpty()) return false;
        return item.equals(context) || (liked(item) && liked(context));
    }

    static boolean liked(String u) {
        return u.equals("spotify:collection") || u.endsWith(":collection") || u.endsWith(":collection:tracks");
    }


    void open(MediaEntry e) {
        hideKeyboard(); // a search result shouldn't open under the keyboard
        // A song on a playlist or album page plays that list from the song, as Spotify does.
        if (playlist != null && (e.uri.startsWith("spotify:track:") || e.uri.startsWith("spotify:episode:") || e.uri.startsWith("spotify:preview:"))) {
            // The song that's already playing opens the player rather than starting over.
            if (e.uri.equals(uri) && playlistScreen.listIsPlaying()) { playerScreen.openPlayer(true); return; }
            int index = e instanceof Song ? ((Song) e).index : Math.max(0, playlistTracks.indexOf(e));
            String context = playUri();
            submit(COMMANDS, () -> {
                if (!playerPlayFrom(context, e.uri, index)) post(() -> toast(playbackFailure()));
            });
            return;
        }
        if (isDetail(e)) { playlistScreen.openPlaylist(e); return; }
        boolean isList = e.category == 0 || e.uri.startsWith("spotify:playlist:") || (e.uri.startsWith("spotify:user:") && e.uri.contains(":playlist:")) || liked(e.uri);
        if (isList) playlistScreen.openPlaylist(e);
        else command(PlayerScreen.PLAY, 0, e.uri);
    }

    /** View tag key for the now-playing chip's position (any id unique to this file). */
    static final int R_COLOR = 0x7f5a0002;
    final java.util.ArrayDeque<MediaEntry> pageStack = new java.util.ArrayDeque<>();





















    void currentSongActions() {
        if (!uri.isEmpty()) searchScreen.searchActions(new MediaEntry(title, artist, uri, image, -1));
    }

    void addToPlaylist(MediaEntry song) {
        List<MediaEntry> targets = new ArrayList<>();
        List<Option> options = new ArrayList<>();
        options.add(new Option("New playlist", "Start a collection", "", "add", false));
        for (MediaEntry candidate : entryList(unpackStrings(nativePlaylistTargets()))) {
            if (!(candidate.uri.startsWith("spotify:playlist:") || candidate.uri.contains(":playlist:") || candidate.uri.startsWith("spotify:preview:"))) continue;
            targets.add(candidate);
            options.add(new Option(candidate.title, candidate.subtitle, candidate.image, "playlist", false));
        }
        sheet("Add to playlist", song.title, options.toArray(new Option[0]), choice -> {
            if (choice == 0) { creation.pendingPlaylistSong = song; creation.createFromQueue = false; creation.openCreatePlaylist(false); return; }
            appendSong(targets.get(choice - 1), song);
        }, true);
    }

    private void appendSong(MediaEntry target, MediaEntry song) {
        submit(COMMANDS, () -> {
            String error;
            try { error = nativeAddToPlaylist(target.uri, new String[]{song.uri}); }
            catch (RuntimeException | LinkageError failed) { error = failed.toString(); }
            final String failure = error == null ? "Couldn’t add this song" : error;
            post(() -> {
                toast(failure.isEmpty() ? "Added to “" + target.title + "”" : failure, true);
                if (failure.isEmpty() && playlist != null && playlist.uri.equals(target.uri)) playlistScreen.openPlaylist(target);
            });
        });
    }

    static void animateMore(View icon, boolean motion) {
        if (!motion || icon == null) return;
        icon.animate().cancel();
        icon.setRotation(0f);
        icon.animate().rotation(90f).scaleX(1.18f).scaleY(1.18f).setDuration(240)
            .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f))
            .withEndAction(() -> icon.animate().rotation(0f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(260)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start())
            .start();
    }

    void actions(MediaEntry e) {
        if (e instanceof Song) { playlistScreen.songActions((Song) e); return; }
        if (SearchScreen.isTrack(e.uri)) { searchScreen.searchActions(e); return; }
        boolean song = playlist != null;
        Option[] options = song
            ? new Option[]{new Option("Play", "", "", "play", false), new Option("Add to queue", "", "", "queue", false)}
            : new Option[]{new Option("Play", "", "", "play", false), new Option("Shuffle play", "", "", "shuffle", false), new Option("Open", "", "", "chevron", false)};
        sheet(e.title, e.subtitle.isEmpty() ? "Choose what to do" : e.subtitle, options, choice -> {
            if (song) {
                if (choice == 0) command(PlayerScreen.PLAY, 0, e.uri);
                else { command(PlayerScreen.QUEUE_ADD, 0, e.uri); toast("Added to queue"); }
            } else if (choice == 2) open(e);
            else { command(PlayerScreen.SHUFFLE, choice == 1 ? 1 : 0, ""); command(PlayerScreen.PLAY, 0, e.uri); }
        });
    }













    /** Puts a rebuilt page back at `y` after layout but before its first frame, so it never shows at the top. */
    static void keepScroll(ScrollView s, int y) {
        if (s == null || y <= 0) return;
        s.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                s.getViewTreeObserver().removeOnPreDrawListener(this);
                s.scrollTo(0, y);
                return true;
            }
        });
    }














    interface Choice { void picked(int index); }

    /** A sheet choice: a label, an optional second line, a cover (image url) or icon, and whether it's the current one. */
    static final class Option {
        final String label, detail, image, glyph;
        final boolean selected;
        Option(String label, String detail, String image, String glyph, boolean selected) {
            this.label = label; this.detail = detail; this.image = image; this.glyph = glyph; this.selected = selected;
        }
    }

    /** A view shown under the next sheet's note, then forgotten (the devices sheet's volume bar). */
    View sheetExtra;


    /** Circular live countdown ring with remaining time shown inside the active sleep timer sheet. */
    final class SleepRingView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF arc = new android.graphics.RectF();
        final Runnable tick = new Runnable() {
            @Override public void run() {
                invalidate();
                if (isAttachedToWindow() && sleepAt > SystemClock.uptimeMillis()) postDelayed(this, 1000);
            }
        };

        SleepRingView() {
            super(activity);
            colored(this, this::invalidate);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            removeCallbacks(tick);
            if (sleepAt > SystemClock.uptimeMillis()) postDelayed(tick, 1000);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), landscape() ? dp(116) : dp(148));
        }

        @Override protected void onDraw(Canvas c) {
            long left = Math.max(0L, sleepAt - SystemClock.uptimeMillis());
            float frac = sleepTotalMs > 0 ? Math.max(0f, Math.min(1f, left / (float) sleepTotalMs)) : 0f;
            boolean compact = landscape();
            float cx = getWidth() / 2f, cy = getHeight() / 2f - dp(4), r = dp(compact ? 44 : 56);
            arc.set(cx - r, cy - r, cx + r, cy + r);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeWidth(dp(compact ? 11 : 13));
            paint.setColor((accent2 & 0x00ffffff) | 0x22000000);
            c.drawCircle(cx, cy, r, paint);
            paint.setStrokeWidth(dp(compact ? 6 : 7));
            paint.setColor(surfHi);
            c.drawCircle(cx, cy, r, paint);
            if (frac > 0.002f) {
                float sweep = 360f * frac;
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(accent);
                c.drawArc(arc, -90f, sweep, false, paint);
                double rad = Math.toRadians(-90f + sweep);
                float dotX = (float) (cx + r * Math.cos(rad)), dotY = (float) (cy + r * Math.sin(rad));
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(dotX, dotY, dp(5), paint);
                paint.setColor(onAccent);
                c.drawCircle(dotX, dotY, dp(2), paint);
            }
            long secs = (left + 999L) / 1000L;
            String clock = secs >= 3600
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
                : String.format(java.util.Locale.US, "%02d:%02d", secs / 60, secs % 60);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setFontFeatureSettings("tnum");
            paint.setTextSize(dp(compact ? 19 : 22));
            paint.setColor(ink);
            c.drawText(clock, cx, cy + dp(5), paint);
            paint.setFontFeatureSettings(null);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(dp(11));
            paint.setColor(inkDim);
            c.drawText("remaining", cx, cy + dp(compact ? 19 : 21), paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    void sheet(String heading, String note, String[] options, Choice choice) {
        Option[] plain = new Option[options.length];
        for (int i = 0; i < options.length; i++) plain[i] = new Option(options[i], "", "", "", false);
        sheet(heading, note, plain, choice);
    }

    void sheet(String heading, String note, Option[] options, Choice choice) {
        sheet(heading, note, options, choice, false);
    }

    void sheet(String heading, String note, Option[] options, Choice choice, boolean filterable) {
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(0x77000000);
        LinearLayout card = column();
        card.setPadding(dp(20), dp(12), dp(20), dp(20) + inset.bottom);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surf);
        shape.setCornerRadii(new float[]{dp(32), dp(32), dp(32), dp(32), 0, 0, 0, 0});
        card.setBackground(shape);
        colored(card, () -> shape.setColor(surf));
        card.setClickable(true);
        View handle = new View(activity);
        bgRound(handle, 3, () -> inkDim);
        handle.setAlpha(.5f);
        LinearLayout.LayoutParams hp = lp(dp(36), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(14);
        card.addView(handle, hp);
        card.addView(text(heading, 22, () -> ink, true));
        TextView n = text(note, 14, () -> inkDim, false);
        n.setPadding(0, dp(4), 0, dp(14));
        card.addView(n);
        if (sheetExtra != null) {
            card.addView(sheetExtra, fill(-2));
            sheetExtra = null;
        }
        // A long list scrolls inside the sheet instead of running off the screen.
        int most = Math.round(getHeight() * .62f);
        ScrollView choices = new ScrollView(activity) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, most > 0 ? MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST) : h);
            }
        };
        choices.setVerticalScrollBarEnabled(false);
        LinearLayout list = column();
        choices.addView(list);
        if (filterable) {
            android.widget.EditText filter = new android.widget.EditText(activity);
            filter.setSingleLine(true);
            filter.setTextColor(ink);
            filter.setHintTextColor(inkDim);
            filter.setHint("Search playlists");
            filter.setContentDescription("Search playlists");
            filter.setPadding(dp(12), dp(8), dp(12), dp(8));
            card.addView(filter, fill(dp(48)));
            filter.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
                public void onTextChanged(CharSequence t, int a, int b, int c) {
                    String q = t.toString().trim().toLowerCase(java.util.Locale.ROOT);
                    for (int i = 0; i < list.getChildCount(); i++) {
                        Option option = options[i];
                        boolean show = i == 0 || (option.label + " " + option.detail).toLowerCase(java.util.Locale.ROOT).contains(q);
                        list.getChildAt(i).setVisibility(show ? VISIBLE : GONE);
                    }
                }
                public void afterTextChanged(android.text.Editable e) {}
            });
        }
        Runnable[] close = new Runnable[1];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            Option o = options[i];
            LinearLayout option = row();
            option.setPadding(dp(o.image.isEmpty() ? 18 : 8), dp(8), dp(14), dp(8));
            GradientDrawable optBg = round(o.selected ? blend(surfHi, accent, .22f) : surfHi, 16);
            option.setBackground(optBg);
            if (!o.image.isEmpty()) {
                Art art = artwork.new Art(Art.ROUNDED, false);
                art.letter = initial(o.label);
                art.bind(o.image);
                LinearLayout.LayoutParams ap = lp(dp(42), dp(42));
                ap.rightMargin = dp(12);
                option.addView(art, ap);
            } else if (!o.glyph.isEmpty()) {
                LinearLayout.LayoutParams ip = lp(dp(24), dp(24));
                ip.rightMargin = dp(14);
                option.addView(icon(o.glyph, () -> o.selected ? accent : inkDim), ip);
            }
            LinearLayout words = column();
            TextView label = text(o.label, 16, () -> o.selected ? accent : ink, true);
            oneLine(label);
            words.addView(label);
            if (!o.detail.isEmpty()) {
                TextView detail = text(o.detail, 13, () -> inkDim, false);
                oneLine(detail);
                detail.setPadding(0, dp(2), 0, 0);
                words.addView(detail);
            }
            option.addView(words, weight());
            if (o.selected) option.addView(icon("check", () -> accent), lp(dp(22), dp(22)));
            colored(option, () -> optBg.setColor(o.selected ? blend(surfHi, accent, .22f) : surfHi));
            tappable(option, o.label, () -> { close[0].run(); choice.picked(index); });
            LinearLayout.LayoutParams p = fill(o.detail.isEmpty() && o.image.isEmpty() ? dp(54) : -2);
            if (!o.detail.isEmpty() || !o.image.isEmpty()) option.setMinimumHeight(dp(58));
            p.bottomMargin = dp(5);
            list.addView(option, p);
        }
        card.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        int sheetWidth = landscape() ? Math.min(dp(520), Math.max(dp(320), getWidth() - dp(48))) : -1;
        scrim.addView(card, new LayoutParams(sheetWidth, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        addView(scrim, new LayoutParams(-1, -1));
        close[0] = () -> {
            sheetClose = null;
            if (scrim.getParent() == null) return;
            if (!motion()) { removeView(scrim); return; }
            scrim.animate().alpha(0f).setDuration(160).start();
            card.animate().translationY(card.getHeight()).setDuration(220).withEndAction(() -> removeView(scrim)).start();
        };
        scrim.setOnClickListener(v -> close[0].run());
        sheetClose = close[0];
        if (motion()) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(180).start();
            card.setTranslationY(dp(400));
            card.animate().translationY(0).setDuration(340).setInterpolator(EMPHASIZED).start();
        }
    }
















    View iconButton(Icon icon, String label, Runnable action) {
        return iconButton(icon, label, .96f, action);
    }

    View iconButton(Icon icon, String label, float pressScale, Runnable action) {
        FrameLayout box = new FrameLayout(activity);
        box.addView(icon, new LayoutParams(dp(24), dp(24), Gravity.CENTER));
        tappable(box, label, pressScale, action);
        return box;
    }























    // Sleep timer: pauses playback later. Kept on the main looper, not in a view, so it survives page changes.
    static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    static long sleepAt, sleepTotalMs;
    private static volatile long accountGeneration;
    static final Runnable SLEEP = () -> {
        sleepAt = 0;
        sleepTotalMs = 0;
        long generation = accountGeneration;
        COMMANDS.execute(() -> { if (generation == accountGeneration) playerCommand(PlayerScreen.PAUSE, 0, ""); });
    };

    /** Set when Spotify's login opens: the next screen drops the saved profile so another account never shows it. */
    private static volatile boolean forgetProfile;

    static void resetAccount() {
        forgetProfile = true;
        accountGeneration++;
        sleepAt = 0;
        sleepTotalMs = 0;
        MAIN.removeCallbacks(SLEEP);
    }




















    void refresh() {
        for (Bars bars : controls.attachedBars) bars.invalidate();
        if (playlistScreen.listFab != null && playlistScreen.listFab.isAttachedToWindow()) playlistScreen.listFab.set(playlistScreen.listIsPlaying() && (pending == null ? playing : pending));
        String shown = title.isEmpty() ? "Nothing playing" : title;
        boolean on = pending == null ? playing : pending;
        if (mTitle != null) mTitle.setText(shown);
        if (mArtist != null) mArtist.setText(artist);
        if (mArt != null) { mArt.bind(image); mArt.invalidate(); }
        for (PlayButton b : playButtons) if (b != dockUi.pPlay) b.set(on);
        if (heroTitle != null) heroTitle.setText(shown);
        if (heroArtist != null) heroArtist.setText(artist);
        if (heroArt != null) heroArt.bind(image);
        if (pTitle != null && !shown.contentEquals(pTitle.getText())) {
            if (pTitle.getText().length() > 0 && motion()) {
                pTitle.setAlpha(0f);
                pTitle.setTranslationY(dp(12));
                pTitle.animate().alpha(1f).translationY(0).setDuration(340).start();
            }
            pTitle.setText(shown);
        }
        if (pArtist != null) {
            if (pArtist.getText().length() > 0 && !artist.contentEquals(pArtist.getText()) && motion()) {
                pArtist.setAlpha(0f);
                pArtist.setTranslationY(dp(8));
                pArtist.animate().alpha(1f).translationY(0).setStartDelay(60).setDuration(360).start(); // a beat behind the title
            }
            pArtist.setText(artist);
        }
        if (pSource != null) pSource.setText(source.isEmpty() ? album : source);
        if (pBadge != null) pBadge.setVisibility(rating.isEmpty() ? GONE : VISIBLE);
        if (pSpeed != null) {
            // Spoken word only: demo songs stand in for episodes in the preview app.
            boolean spoken = uri.startsWith("spotify:episode:") || uri.startsWith("spotify:preview:");
            pSpeed.setVisibility(spoken ? VISIBLE : GONE);
            pSpeed.setText(PlayerScreen.speedLabel(speed));
        }
        if (pQuality != null) {
            String label = quality.toUpperCase(java.util.Locale.ROOT);
            if (!(label.isEmpty() ? " " : label).contentEquals(pQuality.getText())) {
                pQuality.setText(label.isEmpty() ? " " : label);
                pQuality.setVisibility(label.isEmpty() ? INVISIBLE : VISIBLE);
                if (!label.isEmpty() && motion()) { pQuality.setAlpha(0f); pQuality.animate().alpha(1f).setDuration(260).start(); }
            }
        }
        if (dockUi.pPlay != null) dockUi.pPlay.set(on);
        if (pLike != null) {
            View button = (View) pLike.getParent();
            recolorView(button);
            button.setSelected(Boolean.TRUE.equals(saved));
            if (android.os.Build.VERSION.SDK_INT >= 30) button.setStateDescription(saved == null ? "Checking Liked Songs" : Boolean.TRUE.equals(saved) ? "In Liked Songs" : "Not in Liked Songs");
            pLike.setAlpha(saved == null ? .4f : 1f);
        }
        if (pShuffle != null) ((Runnable) pShuffle.getTag(R_COLOR)).run();
        if (pSparkle != null) ((Runnable) pSparkle.getTag(R_COLOR)).run();
        if (pRepeat != null) ((Runnable) pRepeat.getTag(R_COLOR)).run();
        if (disc != null) { disc.url = image; disc.invalidate(); }
        if (backdrop != null) backdrop.invalidate();
        if (motes != null) motes.invalidate();
        if (wave != null) wave.invalidate();
        if (nextPill != null) fillNext();
        if (pageList != null && playlist != null) notifyList(pageList);
    }

    private void fillNext() {
        if (upcoming.isEmpty()) {
            nextPill.setVisibility(GONE);
            if (!nextKey.isEmpty()) { nextPill.removeAllViews(); nextKey = ""; }
            return;
        }
        MediaEntry n = upcoming.get(0);
        String key = n.uri + '\n' + n.title + '\n' + n.subtitle + '\n' + n.image;
        nextPill.setVisibility(VISIBLE);
        if (key.equals(nextKey)) return;
        nextKey = key;
        nextPill.removeAllViews();
        Art a = artwork.new Art(Art.ROUNDED, false);
        a.letter = initial(n.title);
        a.bind(n.image);
        nextPill.addView(a, lp(dp(34), dp(34)));
        TextView label = text("Next", 13, () -> inkDim, true);
        label.setPadding(dp(10), 0, dp(8), 0);
        nextPill.addView(label);
        TextView name = text(n.title + (n.subtitle.isEmpty() ? "" : " · " + n.subtitle), 13, () -> ink, false);
        oneLine(name);
        nextPill.addView(name, weight());
    }


    final class Icon extends View {
        String symbol;
        int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Icon(String symbol, int color) { super(activity); this.symbol = symbol; this.color = color; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) { drawGlyph(c, paint, path, symbol, color, Math.min(getWidth(), getHeight())); }
    }

    /** Draws an icon (Material Symbols Rounded when enabled, else 24-unit grid from Rust) into a `size` square at the canvas origin. */
    static volatile boolean materialIcons;
    private static final android.graphics.CornerPathEffect GLYPH_CORNER = new android.graphics.CornerPathEffect(1.4f);

    static void drawGlyph(Canvas canvas, Paint paint, Path path, String key, int color, float size) {
        if (materialIcons && MaterialIcons.draw(canvas, paint, key, color, size)) return;
        float[] points = GLYPHS.get(key);
        if (points == null) return;
        canvas.save();
        canvas.scale(size / 24f, size / 24f);
        paint.setColor(color);
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
            paint.setPathEffect(count < 0 ? GLYPH_CORNER : null);
            canvas.drawPath(path, paint);
        }
        paint.setPathEffect(null);
        canvas.restore();
    }

    /** Places three curated low-contrast geometric shapes framing playlist/album header washes with gentle rotation. */
    final class StickerBackdrop extends View {
        private static final int COUNT = 3;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float[][] shapePts = new float[COUNT][];
        private final float[] xFrac = new float[COUNT], yFrac = new float[COUNT], sizeDp = new float[COUNT], rot = new float[COUNT];
        private final float[] spinDir = {1f, -1f, 2f};
        private final boolean[] useAccent2 = {true, false, false};
        private float spinPhase, spinBoost;
        private android.animation.ValueAnimator spinAnim, boostAnim;

        StickerBackdrop(String seedKey) {
            super(activity);
            setWillNotDraw(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            String[] pool = {"badge4", "badge6", "badge8", "button5"};
            float[] baseX = {.87f, .11f, .84f}, baseY = {.20f, .48f, .68f}, baseSize = {52f, 46f, 36f};
            int h = 0x811c9dc5;
            for (int i = 0; i < seedKey.length(); i++) { h ^= seedKey.charAt(i); h *= 0x01000193; }
            int startIdx = Math.abs(h >>> 16) % pool.length;
            for (int i = 0; i < COUNT; i++) {
                h = h * 1664525 + 1013904223;
                float u = ((h >>> 8) & 0xFFFF) / 65535f;
                h = h * 1664525 + 1013904223;
                float v = ((h >>> 8) & 0xFFFF) / 65535f;
                h = h * 1664525 + 1013904223;
                float s = ((h >>> 8) & 0xFFFF) / 65535f;
                h = h * 1664525 + 1013904223;
                float a = ((h >>> 8) & 0xFFFF) / 65535f;
                xFrac[i] = baseX[i] + (u - .5f) * .04f;
                yFrac[i] = baseY[i] + (v - .5f) * .04f;
                sizeDp[i] = baseSize[i] + s * 10f;
                rot[i] = (a - .5f) * 70f;
                shapePts[i] = GLYPHS.get(pool[(startIdx + i) % pool.length]);
            }
            paint.setStyle(Paint.Style.FILL);
            colored(this, this::invalidate);
        }

        private void syncSpin() {
            // Off by default: turning without end keeps an idle playlist page redrawing about 60 times a second.
            if (isAttachedToWindow() && getWindowVisibility() == VISIBLE && motion() && pref("sticker_spin", false)) {
                if (spinAnim == null) {
                    spinAnim = android.animation.ValueAnimator.ofFloat(0f, 360f);
                    spinAnim.setDuration(22_000L);
                    spinAnim.setInterpolator(new android.view.animation.LinearInterpolator());
                    spinAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    spinAnim.setRepeatMode(android.animation.ValueAnimator.RESTART);
                    spinAnim.addUpdateListener(anim -> {
                        spinPhase = (float) anim.getAnimatedValue();
                        invalidate();
                    });
                    spinAnim.start();
                }
            } else if (spinAnim != null) {
                spinAnim.cancel();
                spinAnim = null;
            }
        }

        void boostSpin() {
            if (!motion()) return;
            if (boostAnim != null) boostAnim.cancel();
            boostAnim = android.animation.ValueAnimator.ofFloat(spinBoost, spinBoost + 180f);
            boostAnim.setDuration(650L);
            boostAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f));
            boostAnim.addUpdateListener(anim -> {
                spinBoost = (float) anim.getAnimatedValue();
                invalidate();
            });
            boostAnim.start();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            syncSpin();
        }

        @Override protected void onDetachedFromWindow() {
            if (spinAnim != null) {
                spinAnim.cancel();
                spinAnim = null;
            }
            if (boostAnim != null) {
                boostAnim.cancel();
                boostAnim = null;
            }
            super.onDetachedFromWindow();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            syncSpin();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int alpha = dark ? 20 : 16;
            for (int i = 0; i < COUNT; i++) {
                float[] pts = shapePts[i];
                if (pts == null || pts.length < 6) continue;
                float r = Math.min(dp(sizeDp[i]), h * .28f);
                float cx = w * xFrac[i], cy = Math.min(h * .85f - r, h * yFrac[i]);
                path.rewind();
                for (int j = 0; j < pts.length; j += 2) {
                    float px = pts[j] * r, py = pts[j + 1] * r;
                    if (j == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                path.close();
                paint.setColor(useAccent2[i] ? accent2 : accent);
                paint.setAlpha(alpha);
                c.save();
                c.translate(cx, cy);
                c.rotate(rot[i] + (spinPhase + spinBoost) * spinDir[i]);
                c.drawPath(path, paint);
                c.restore();
            }
        }
    }


    /** An empty state: `symbol` on a lobed tonal badge (`shape` is a badge geometry) that springs in, over a title and a hint. */
    View emptyState(String symbol, String shape, String title, String hint) {
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(28), dp(24), dp(20));
        float[] outline = nativeGeometry(shape);
        View badge = new View(activity) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path lobes = new Path(), glyph = new Path();
            @Override protected void onDraw(Canvas c) {
                float r = Math.min(getWidth(), getHeight()) / 2f;
                lobes.rewind();
                for (int i = 0; i + 1 < outline.length; i += 2) {
                    if (i == 0) lobes.moveTo(getWidth() / 2f + outline[i] * r, getHeight() / 2f + outline[i + 1] * r);
                    else lobes.lineTo(getWidth() / 2f + outline[i] * r, getHeight() / 2f + outline[i + 1] * r);
                }
                lobes.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(blend(surfHi, accent, .22f));
                c.drawPath(lobes, paint);
                c.save();
                c.translate(getWidth() / 2f - r * .45f, getHeight() / 2f - r * .45f);
                drawGlyph(c, paint, glyph, symbol, accent, r * .9f);
                c.restore();
            }
        };
        badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(badge, lp(dp(80), dp(80)));
        if (motion()) {
            badge.setScaleX(.4f); badge.setScaleY(.4f); badge.setRotation(-60f);
            badge.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(520).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
        }
        TextView head = text(title, 17, () -> ink, true);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, dp(14), 0, dp(4));
        box.addView(head, fill(-2));
        if (hint != null) {
            TextView sub = text(hint, 13, () -> inkDim, false);
            sub.setGravity(Gravity.CENTER);
            box.addView(sub, fill(-2));
        }
        return box;
    }






    /** A cached cover at `size`, or null while it downloads (views are invalidated when it arrives). */
    boolean artFailed(String key) {
        return SystemClock.uptimeMillis() < failed.getOrDefault(key, 0L);
    }

    Bitmap art(String url, int size) {
        String key = url + ':' + size;
        Bitmap b = IMAGES.get(key);
        if (b == null && !url.isEmpty() && SystemClock.uptimeMillis() >= failed.getOrDefault(key, 0L) && loading.add(key)) {
            ART.execute(() -> {
                // Small tiles ask Spotify's CDN for its 300 px (album) or 160 px (artist) copy instead of the 640 px one.
                String small = size <= 180 ? smaller(url) : null;
                Bitmap got = small == null ? null : CoreBridge.artwork(small, size);
                if (got == null) got = CoreBridge.artwork(url, size);
                if (got != null) {
                    IMAGES.put(key, got);
                    if (size == 440) ArtworkViews.cacheBlurs(key, got, GpuFx.blur(prefs));
                    if (!SEEDS.containsKey(url)) {
                        int[] pair = seedPairOf(got);
                        if (pair != null && pair.length >= 2) {
                            if (pair[0] != 0) SEEDS.put(url, pair[0]);
                            if (pair[1] != 0) SEEDS2.put(url, pair[1]);
                        }
                    }
                }
                final Bitmap result = got;
                post(() -> {
                    loading.remove(key);
                    if (result == null) {
                        if (failed.size() >= 400) failed.clear();
                        failed.put(key, SystemClock.uptimeMillis() + 30_000);
                        postDelayed(() -> {
                            Long retry = failed.get(key);
                            if (!closed && retry != null && SystemClock.uptimeMillis() >= retry) {
                                failed.remove(key);
                                invalidateArt(SpatchUi.this);
                            }
                        }, 30_000);
                    } else failed.remove(key);
                    if (url.equals(image)) refreshPalette();
                    if (playlist != null && (url.equals(playlist.image) || url.equals(playlistScreen.detailImage))) applyColors();
                    invalidateArt(SpatchUi.this);
                });
            });
        }
        return b;
    }

    private static String smaller(String url) {
        String[][] swaps = {{"ab67616d0000b273", "ab67616d00001e02"}, {"ab6761610000e5eb", "ab6761610000f178"}};
        for (String[] s : swaps) if (url.contains("/image/" + s[0])) return url.replace(s[0], s[1]);
        return null;
    }


}
