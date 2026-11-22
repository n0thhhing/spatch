package spatch;

import spatch.MediaEntry.Song;
import spatch.ArtworkViews.Art;
import spatch.ArtworkViews.Backdrop;
import spatch.ArtworkViews.Disc;
import spatch.ArtworkViews.Motes;
import static spatch.PackedStrings.packStrings;
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
import android.graphics.RectF;
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

/**
 * Shared Android screens and interactions, backed by Rust JNI state.
 * The Android app and native Mac preview compile this same view implementation.
 */
public final class SpatchUi extends FrameLayout {
    // ---- Rust (app/src/ui/mod.rs, shapes.rs) ----
    private static native void nativeAttachUi();
    private static native void nativeRevisions(long[] out);
    private static native boolean nativeConnect();
    private static native byte[] nativePlayer(long revision);
    private static native String nativeLibrary();
    private static native boolean nativeRefreshLibrary();
    private static native byte[] nativeEntries(int filter, String query, boolean alphabetical, int limit);
    private static native byte[] nativeRecent();
    private static native String nativeLiked();
    private static native String nativeLikedNote();
    private static native byte[] nativePlaylist(String uri, int offset);
    private static native byte[] nativePage(String uri);
    private static native byte[] nativePlaylistTargets();
    private static native String[] nativeDevices();
    private static native void nativeCacheDir(String dir);
    private static native long nativeLibraryRevision();
    private static native String nativeBuild();
    private static native float nativePlaybackSpeed();
    private static native int nativeVolume();
    private static native String nativeSetVolume(int level);
    private static native String[] nativeConnection();
    private static native byte[] nativeSearch(String query);
    private static native byte[] nativeCatalog(byte[] response);
    private static native void nativeDownloadsStart();
    private static native byte[] nativeDownloads();
    private static native long nativeDownloadsRevision();
    private static native long nativeDownloadsSet();
    private static native String nativeDownload(String uri, int action, byte[] entry);
    private static native String[] nativeDownloadTotals();
    private static native int nativeShuffleMode(String uri);
    private static native String nativeSetShuffleMode(String uri, int mode);
    private static native void nativeWatchPlaylist(String uri, String key);
    private static native void nativeStopPlaylist();
    private static native long nativePlaylistRevision();
    private static native byte[] nativePlaylistRest(String uri, int offset);
    private static native void nativeSongs(byte[] packed);
    private static native int[] nativeArrange(String query, int sort);
    private static native boolean nativePlayFrom(String context, String track, int index);
    private static native byte[] nativeCached(String key);
    private static native void nativeRemember(String key, byte[] packed);
    private static native int[] nativePalette(int seed, boolean hasSeed, boolean dark);
    private static native int nativeSeed(int[] pixels);
    static native void nativeRing(int perSide, float[] out);
    private static native String[] nativeSpotifySettings();
    private static native String nativeSetSpotifySetting(String key, int value);
    private static native String nativeTransfer(String id);
    private static native boolean nativeCommand(int action, long value, String uri);
    private static native int nativeSaved(String uri);
    private static native boolean nativeSave(String uri, boolean save);
    private static native float[] nativeGeometry(String kind);
    private static native String[] nativeProfile();
    static native String[] nativeCreatePlaylist(String name, String description, boolean fromQueue);
    static native String nativeAddToPlaylist(String playlistUri, String[] uris);

    private static final int PAUSE = 0, RESUME = 1, PREVIOUS = 2, NEXT = 3, SEEK = 4, SHUFFLE = 5, REPEAT = 6, PLAY = 7, SKIP_TO = 8, QUEUE_ADD = 9, QUEUE_NEXT = 13, SPEED = 14;
    private static final int HOME = 0, SEARCH = 1, LIBRARY = 2, SETTINGS = 3, CREATE = 4, PROFILE = 5, STATS = 6;
    private static final int[] NAV_TABS = {HOME, SEARCH, LIBRARY, CREATE};
    private static final String[] SETTINGS_GROUPS = {"Appearance", "Playback", "Equalizer & sound", "Navigation & gestures", "Downloads & storage", "Developer", "About"};
    private static final String[] SECTIONS = {"Playlists", "Albums", "Artists", "Podcasts"};
    /** The Library filter after the four sections: what's downloaded. */
    private static final int DOWNLOADED = SECTIONS.length;

    private static final ExecutorService DATA = Executors.newSingleThreadExecutor();
    static final ExecutorService COMMANDS = Executors.newSingleThreadExecutor();
    static final ExecutorService PLAYLISTS = Executors.newCachedThreadPool();
    private static final ExecutorService WATCHES = Executors.newSingleThreadExecutor();
    private static final ExecutorService ART = Executors.newFixedThreadPool(6);
    static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getAllocationByteCount(); }
    };
    private static final java.util.Map<String, Integer> SEEDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, float[]> GLYPHS = new java.util.HashMap<>();
    static final ColorMatrixColorFilter VIVID;
    static {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.5f);
        VIVID = new ColorMatrixColorFilter(m);
    }

    private volatile boolean closed;
    private volatile long lifetime;

    /** Queued work from a detached account screen must never run in the next account. */
    void submit(ExecutorService executor, Runnable work) {
        long generation = lifetime;
        executor.execute(() -> {
            if (!closed && generation == lifetime) work.run();
        });
    }

    private final CreationSheet creation = new CreationSheet(this);
    private final SoundSettings sound = new SoundSettings(this);
    final ArtworkViews artwork = new ArtworkViews(this);
    final Activity activity;
    private final float density;
    final android.content.SharedPreferences prefs;
    final android.graphics.Rect inset = new android.graphics.Rect();
    private android.graphics.Rect pendingInsets;
    private final Set<String> loading = new HashSet<>();
    private final java.util.Map<String, Long> failed = new java.util.HashMap<>();

    // palette, derived from the playing cover
    int bg, surf, surfHi, ink, inkDim, accent, onAccent, accent2;
    private String paletteKey = "";
    private android.animation.ValueAnimator paletteAnim;
    private boolean paletteReady;

    // navigation
    private int tab = HOME, filter = -1;
    int settingsSection = -1;
    private int navHover = -1;
    private final java.util.ArrayDeque<Integer> tabHistory = new java.util.ArrayDeque<>();
    android.widget.PopupWindow accountMenu;
    private String profileName = "", profileUsername = "", profileImage = "", profileUri = "", profileError = "";
    private boolean profileLoading, profileAnnounce;
    private long profileAt, profileStartedAt;
    private int profileTicket, profileAttempts;
    /** Asks again for the account until it's known (the library, which it's learned from, may not be ready yet). */
    private final Runnable profileRetry = () -> loadProfile(true);
    private final Set<Art> avatars = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private MediaEntry playlist;
    private List<MediaEntry> playlistTracks = new ArrayList<>();
    private boolean playlistLoading, playlistFailed, playerOpen;
    private String playlistError = "";
    /** The link actually used for the open page: Liked Songs' real link is learned from Spotify when it opens. */
    private String liveUri = "";
    private String playUri() { return liveUri.isEmpty() ? playlist.uri : liveUri; }
    /** For an album or artist page: Spotify's own heading, and the releases shelf. */
    private String detailTitle = "", detailSubtitle = "", detailImage = "";
    private List<MediaEntry> releases = new ArrayList<>();

    private static boolean isDetail(MediaEntry e) { return e.uri.startsWith("spotify:album:") || e.uri.startsWith("spotify:artist:") || e.uri.startsWith("spotify:show:"); }
    private int playlistRequest;
    private String query = "";

    // player state
    private long revision, lastConnect, libraryRetry, libraryLoadedAt, stamp, position, duration;
    private int libraryAttempts;
    boolean polling, fetching, libraryReady, refreshing, playing, shuffle;
    private int repeat;
    private Boolean pending, saved;
    private String savedUri = "";
    private int savedRequest;
    String title = "", artist = "", album = "", source = "", image = "", uri = "", rating = "", context = "", quality = "";
    private boolean buffering, canNext = true, canPrevious = true;
    private long prevTappedAt;
    private String albumUri = "", artistUri = "";
    /** Skip buttons (true for next), dimmed and disabled while Spotify doesn't allow that skip. */
    private final java.util.Map<View, Boolean> skipButtons = new java.util.WeakHashMap<>();
    private final java.util.Set<PlayButton> playButtons = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    List<MediaEntry> upcoming = new ArrayList<>();
    private String libraryStatus = "Connecting to your library…";

    // views that live-update
    private FrameLayout pageHost, player;
    private LinearLayout dock, nextPill;
    Navigator dockBar;
    private View dockFade;
    private boolean dockHidden, navDragging;
    private int scrollTravel;
    private ScrollView pageScroll;
    private ListView pageList;
    private Disc disc;
    private Wave wave;
    private final ProgressMotion progress = new ProgressMotion(this::invalidateProgress, EMPHASIZED);
    private boolean holdingSeek;
    private Runnable cancelHeldSeek;
    private Backdrop backdrop;
    private Motes motes;
    private TextView pQuality, pSpeed;
    private float speed = 1f;
    /** Last backdrop blur and cover kept across switches so transitions start from the previous track's art. */
    Bitmap lastBlur, lastCover;
    String lastCoverUrl = "", nextKey = "";
    private TextView pTitle, pArtist, pSource, pBadge, mTitle, mArtist, heroTitle, heroArtist;
    private Icon pLike, pShuffle, pRepeat, pSparkle;
    /** Whether the playing playlist is in Smart Shuffle, as Spotify keeps it (read when the context changes). */
    private boolean smartShuffle;
    private PlayButton pPlay, mPlay;
    private Art mArt, heroArt;
    private Runnable sheetClose;

    SpatchUi(Activity activity) {
        super(activity);
        this.activity = activity;
        SpatchWidget.uiStarted(activity);
        density = getResources().getDisplayMetrics().density;
        prefs = activity.getSharedPreferences("spatch_ui", Context.MODE_PRIVATE);
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
        ART.execute(CoreBridge::pruneCache);
        nativeCacheDir(new java.io.File(activity.getCacheDir(), "answers").getPath());
        for (String key : new String[]{"back", "down", "previous", "next", "play", "pause", "shuffle", "repeat", "repeat-one", "volume",
                "queue", "home", "library", "settings", "add", "note", "people", "mix", "blend", "prompt", "jam", "heart", "heart-outline", "search", "check", "close", "chevron", "timer", "devices", "album", "artist", "share", "sparkle", "download"}) {
            GLYPHS.put(key, nativeGeometry(key));
        }
        applyPalette(null);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        // Android otherwise lays a 12% white "focus highlight" over this focused view, washing out the whole app,
        // and it vanished whenever a search box took focus. The dark palette carries that lift instead.
        if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
        setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Rect next = new android.graphics.Rect();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                next.set(bars.left, bars.top, bars.right, bars.bottom);
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
                if (keyboard != imeInset) {
                    imeInset = keyboard;
                    if (searchScroll != null) searchScroll.setPadding(0, 0, 0, imeInset);
                }
            }
            return insets;
        });
        build();
    }

    private void applyPendingInsets() {
        if (pendingInsets == null || sheetClose != null || creation.createOverlay != null || playerOpen
                || queuePanel != null || accountMenu != null || findFocus() instanceof android.widget.EditText) return;
        inset.set(pendingInsets);
        pendingInsets = null;
        build();
    }

    // ================= palette =================

    boolean pref(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }

    private boolean dark = true;
    private static final int[] ACCENTS = {0, 0xff8b6fd6, 0xffd6567a, 0xff3d8fd6, 0xff2fa37a, 0xffd99a2b, 0xffe0663f};
    private static final String[] ACCENT_NAMES = {"Default", "Lavender", "Rose", "Sky", "Mint", "Amber", "Coral"};

    /** Colours from the cover when there is one, else from the chosen accent; animated smoothly in place. */
    private void applyPalette(Integer seed) {
        int theme = prefs.getInt("theme", 1);
        dark = theme == 1 || (theme == 0 && (getResources().getConfiguration().uiMode & 0x30) == 0x20);
        int accentChoice = Math.max(0, Math.min(ACCENTS.length - 1, prefs.getInt("accent", 0)));
        if (seed == null && accentChoice > 0) seed = ACCENTS[accentChoice];
        int[] target = nativePalette(seed == null ? 0 : seed, seed != null, dark);
        View decor = activity.getWindow().getDecorView();
        int bars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? decor.getSystemUiVisibility() & ~bars : decor.getSystemUiVisibility() | bars);
        if (paletteAnim != null) { paletteAnim.cancel(); paletteAnim = null; }
        if (!paletteReady || !motion()) {
            paletteReady = true;
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

    /** The cover's seed colour: Rust picks the most colourful of a 6x6 shrink of it. */
    private static int seedOf(Bitmap bitmap) {
        Bitmap small = Bitmap.createScaledBitmap(bitmap, 6, 6, true);
        int[] pixels = new int[36];
        small.getPixels(pixels, 0, 6, 0, 0, 6, 6);
        if (small != bitmap) small.recycle();
        return nativeSeed(pixels);
    }

    /** Re-tints everything once the playing cover's colour is known; true if the palette changed. */
    private boolean refreshPalette() {
        boolean fromArt = pref("art_colors", true);
        Integer seed = fromArt ? SEEDS.get(image) : null;
        // A new cover's colour is known once its art loads: keep the current colours until then rather than
        // flashing to the default palette and back on every skip. A forced refresh ("?") still applies.
        if (fromArt && seed == null && !image.isEmpty() && !"?".equals(paletteKey) && !artFailed(image + ":440")) return false;
        String key = seed == null ? "" : String.valueOf(seed);
        if (key.equals(paletteKey)) return false;
        paletteKey = key;
        applyPalette(seed);
        return true;
    }

    // ================= small helpers =================

    int dp(float v) { return Math.round(v * density); }
    LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    LinearLayout.LayoutParams fill(int h) { return new LinearLayout.LayoutParams(-1, h); }
    LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private View space(int w, int h) { View v = new View(activity); v.setLayoutParams(lp(w, h)); return v; }

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

    private static void recolorView(View v) {
        if (v.getTag(R_COLOR) instanceof Runnable) ((Runnable) v.getTag(R_COLOR)).run();
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) recolorView(g.getChildAt(i));
        }
    }

    private static void invalidateArt(View v) {
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

    private void marquee(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        v.setMarqueeRepeatLimit(-1);
        v.setHorizontalFadingEdgeEnabled(true);
        v.setFadingEdgeLength(dp(24));
        v.setSelected(true);
    }

    private void tick(View v) {
        if (pref("haptics", true)) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    boolean motion() {
        if (pref("reduce_motion", false)) return false;
        return android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    /** Tap with a springy press; nothing highlights outside the view's own shape. */
    void tappable(View v, String label, Runnable action) {
        if (label != null) v.setContentDescription(label);
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(x -> { tick(v); action.run(); });
        if (motion()) {
            v.setOnTouchListener((view, e) -> {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) view.animate().scaleX(.96f).scaleY(.96f).setDuration(90).start();
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                return false;
            });
        }
    }

    private static boolean playerCommand(int action, long value, String target) {
        return OfficialPlayback.enabled() ? OfficialPlayback.command(action, value, target) : nativeCommand(action, value, target);
    }

    private static boolean playerPlayFrom(String context, String track, int index) {
        return OfficialPlayback.enabled() ? OfficialPlayback.command(PLAY, 0, track) : nativePlayFrom(context, track, index);
    }

    private static String playbackFailure() {
        return OfficialPlayback.enabled() ? OfficialPlayback.status() + " · this action may be unavailable" : "That didn’t go through";
    }

    private void command(int action, long value, String target) {
        if (action == NEXT || action == PLAY || action == SKIP_TO) prevTappedAt = 0;
        submit(COMMANDS, () -> {
            if (!playerCommand(action, value, target)) post(() -> {
                if (OfficialPlayback.enabled()) { revision = -1; syncNativeState(); }
                if (isAttachedToWindow()) Toast.makeText(activity, playbackFailure(), Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** Restarts the song when well into it on the first tap, then forces skipping to the previous song on repeat taps or near the start. */
    private void previousTrack() {
        long now = SystemClock.uptimeMillis();
        boolean allowSeek = playhead() > 3000 && now - prevTappedAt > 4000;
        prevTappedAt = now;
        if (allowSeek) {
            setPosition(0);
        }
        command(PREVIOUS, allowSeek ? 1 : 0, "");
    }

    private static String time(long ms) {
        long s = Math.max(0, ms) / 1000, r = s % 60;
        return (s / 60) + (r < 10 ? ":0" : ":") + r;
    }

    private long playhead() {
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

    private void invalidateProgress() {
        if (wave != null) wave.invalidate();
        if (mArt != null) mArt.invalidate();
    }

    private void animateProgress(float from) {
        progress.move(from, playbackFraction(), motion() && isShown() && (wave == null || wave.preview < 0));
    }

    private void setPosition(long target) {
        float from = shownProgress();
        position = Math.max(0, duration > 0 ? Math.min(duration, target) : target);
        stamp = SystemClock.uptimeMillis();
        animateProgress(from);
    }

    private void stopHeldSeek() {
        if (cancelHeldSeek != null) { cancelHeldSeek.run(); cancelHeldSeek = null; }
        holdingSeek = false;
        invalidateProgress();
    }

    private static String initial(String name) {
        for (int i = 0; i < name.length(); i++) {
            int cp = name.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
        }
        return "";
    }

    private static void notifyList(ListView list) {
        android.widget.ListAdapter a = list.getAdapter();
        if (a instanceof android.widget.HeaderViewListAdapter) a = ((android.widget.HeaderViewListAdapter) a).getWrappedAdapter();
        if (a instanceof BaseAdapter) ((BaseAdapter) a).notifyDataSetChanged();
    }

    // ================= window plumbing =================

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
        closed = true;
        lifetime++;
        stopHeldSeek();
        progress.cancel();
        if (accountMenu != null) accountMenu.dismiss();
        creation.closeCreateMenu();
        java.lang.ref.WeakReference<SpatchUi> ref = activeUi;
        if (ref != null && ref.get() == this) activeUi = null;
        playlistRequest++;
        searchRequest++;
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
        else if (queuePanel != null) closeQueue();
        else if (playerOpen) closePlayer();
        else if (!pageStack.isEmpty()) openPlaylist(pageStack.pop(), false);
        else if (playlist != null) { playlist = null; showPage(); updateDock(); enter(-dp(44), 0); }
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

    /** A button on the home-screen widget: pause, resume, previous or next. */
    static void widgetCommand(int action) { COMMANDS.execute(() -> playerCommand(action, 0, "")); }

    private final Runnable syncPush = this::syncNativeState;
    private boolean libraryRedrawPending;

    private void syncNativeState() {
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
            if (open != null && !isDetail(open) && !playlistLoading) loadPlaylist(open, 1);
        }
        long downloadsNow = revisions[2];
        if (downloadsNow != downloadRevision) {
            downloadRevision = downloadsNow;
            pullDownloads();
            long setNow = revisions[3];
            boolean setChanged = setNow != downloadSetRevision;
            downloadSetRevision = setNow;
            onDownloadsChanged(setChanged);
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

    private void loadLibrary() {
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
                    if (!downloadsStarted) {
                        downloadsStarted = true;
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
    private android.widget.AbsListView libraryList;

    /** Redraws the page for new data without losing your place in the Library list or Home's scroll. */
    private void showPageKeepingPlace() {
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
    private boolean watchingPlaylist;

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
        playing = Boolean.parseBoolean(s[6]);
        if (pending != null && (changed || pending == playing)) pending = null;
        shuffle = Boolean.parseBoolean(s[7]);
        repeat = Integer.parseInt(s[8]);
        position = Long.parseLong(s[9]);
        long coreDuration = Long.parseLong(s[10]);
        if (coreDuration > 0 || changed) duration = Math.max(0, coreDuration);
        if (duration == 0 && !uri.isEmpty()) for (Song song : allSongs) {
            if (song.uri.equals(uri) && song.duration > 0) { duration = song.duration; break; }
        }
        source = s[11];
        rating = s[12];
        boolean contextChanged = !context.equals(s[13]);
        if (!context.equals(s[13])) {
            // Learn whether the new context is in Smart Shuffle.
            String next = s[13];
            smartShuffle = false;
            if (isPlaylistLink(next)) submit(PLAYLISTS, () -> {
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
        buffering = Boolean.parseBoolean(s[15]);
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
            if (listFab != null) listFab.invalidate();
        }
        stamp = SystemClock.uptimeMillis();
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
        if (queuePanel != null && queueChanged) renderQueue();
        if (changed) checkSaved(uri);
        refreshPalette();
        refresh();
    }

    // ================= building =================

    /** Rebuilds the whole tree: insets or font/motion settings changed. */
    private void build() {
        if (accountMenu != null) accountMenu.dismiss();
        creation.closeCreateMenu();
        closeQueue();
        removeAllViews();
        sheetClose = null;
        nextKey = "";
        setBackgroundColor(bg);
        pageHost = new FrameLayout(activity);
        addView(pageHost, new LayoutParams(-1, -1));
        // Lists dim as they pass under the mini player and bar instead of showing through them.
        View fade = new View(activity);
        dockFade = fade;
        GradientDrawable fadeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bg & 0x00ffffff, (bg & 0x00ffffff) | 0xd8000000, bg, bg});
        fade.setBackground(fadeBg);
        colored(fade, () -> fadeBg.setColors(new int[]{bg & 0x00ffffff, (bg & 0x00ffffff) | 0xd8000000, bg, bg}));
        addView(fade, new LayoutParams(-1, dp(190) + inset.bottom, Gravity.BOTTOM));
        // And under the status bar, so scrolled content doesn't collide with the clock and icons: solid behind
        // the bar itself, then a short fade below it.
        View topSolid = new View(activity);
        colored(topSolid, () -> topSolid.setBackgroundColor(bg));
        addView(topSolid, new LayoutParams(-1, inset.top, Gravity.TOP));
        View topFade = new View(activity);
        GradientDrawable topBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bg, bg & 0x00ffffff});
        topFade.setBackground(topBg);
        colored(topFade, () -> topBg.setColors(new int[]{bg, bg & 0x00ffffff}));
        LayoutParams tf = new LayoutParams(-1, dp(22), Gravity.TOP);
        tf.topMargin = inset.top;
        addView(topFade, tf);
        dock = column();
        dockHidden = false;
        scrollTravel = 0;
        dock.setPadding(dp(12), 0, dp(12), dp(10) + inset.bottom);
        addView(dock, new LayoutParams(-1, -2, Gravity.BOTTOM));
        buildDock();
        player = null;
        showPage();
        if (playerOpen) { playerOpen = false; openPlayer(false); }
    }

    static final android.view.animation.Interpolator EMPHASIZED = new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f);

    /** The page just shown slides in from (dx, dy) while it fades up. */
    void enter(float dx, float dy) {
        if (!motion() || pageHost.getChildCount() == 0) return;
        View page = pageHost.getChildAt(0);
        page.setAlpha(0f);
        page.setTranslationX(dx);
        page.setTranslationY(dy);
        page.animate().alpha(1f).translationX(0).translationY(0).setDuration(300).setInterpolator(EMPHASIZED).start();
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
    private void clearButton(LinearLayout box, android.widget.EditText field) {
        View clear = iconButton(icon("close", () -> inkDim), "Clear search", () -> field.setText(""));
        clear.setVisibility(field.length() > 0 ? VISIBLE : GONE);
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { clear.setVisibility(e.length() > 0 ? VISIBLE : GONE); }
        });
        box.addView(clear, lp(dp(40), dp(40)));
    }

    // ================= downloads =================
    // Spotify's downloads live in its core (Offline service); Rust follows them and these screens show them.

    private static final int DL_ERROR = 1, DL_REQUESTED = 2, DL_DONE = 3, DL_BUSY = 4, DL_WAITING = 5;
    /** Downloaded or on its way, by uri: {state, percent 0 to 100}. */
    private final java.util.Map<String, int[]> downloads = new java.util.HashMap<>();
    private final java.util.Map<String, MediaEntry> downloadDetails = new java.util.LinkedHashMap<>();
    private long downloadRevision, downloadSetRevision;
    private boolean downloadsStarted;
    private View downloadButton;
    private LinearLayout downloadsList, downloadsProgress;
    private TextView downloadsSummary, downloadsStorage, downloadsProgressText;
    private Meter downloadsMeter;
    private View removeAllDownloads;
    private final java.util.Map<String, Object[]> downloadRows = new java.util.HashMap<>();
    private long storageFree, storageTotal;

    private int downloadState(String uri) {
        int[] d = uri == null ? null : downloads.get(uri);
        return d == null ? 0 : d[0];
    }

    private void pullDownloads() {
        String[] f = unpackStrings(nativeDownloads());
        downloads.clear();
        if (f != null) for (int i = 0; i + 2 < f.length; i += 3) downloads.put(f[i], new int[]{parseInt(f[i + 1]), parseInt(f[i + 2])});
        downloadDetails.clear();
        for (MediaEntry e : entryList(unpackStrings(nativeCached("download-items")))) if (downloads.containsKey(e.uri)) downloadDetails.put(e.uri, e);
        for (String item : downloads.keySet()) if (isTrack(item) && !downloadDetails.containsKey(item)) {
            downloadDetails.put(item, new MediaEntry(kindName(item), "", item, "", -1));
        }
    }

    /** Something changed in the core's downloads: progress only, or a download added, removed or finished. */
    private void onDownloadsChanged(boolean setChanged) {
        if (setChanged) for (Row row : attachedRows) row.updateDownloadBadge();
        if (downloadButton != null && downloadButton.isAttachedToWindow()) downloadButton.invalidate();
        if (downloadsList != null && downloadsList.isAttachedToWindow()) {
            if (setChanged) { renderDownloads(); loadStorage(); } else updateDownloadProgress();
        }
        if (setChanged && tab != SETTINGS && tab != SEARCH && playlist == null && sheetClose == null && !playerOpen
                && !(findFocus() instanceof android.widget.EditText)) showPageKeepingPlace();
    }

    private interface UriOf { String get(); }

    /** The page's download button: a ring with an arrow, a filling ring while it downloads, a check once it has. */
    private final class DownloadButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path glyph = new Path();
        private final RectF oval = new RectF();
        private final UriOf target;
        private final String name;

        DownloadButton(UriOf target, String name) {
            super(activity);
            this.target = target;
            this.name = name;
            tappable(this, "Download", () -> downloadTapped(target.get(), name));
        }

        @Override protected void onDraw(Canvas c) {
            int state = downloadState(target.get());
            int[] d = downloads.get(target.get());
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, size = Math.min(w, h);
            boolean busy = state == DL_BUSY || state == DL_REQUESTED || state == DL_WAITING;
            setContentDescription(state == DL_DONE ? "Downloaded. Tap to remove" : busy ? "Downloading. Tap to cancel" : "Download");
            paint.setStyle(Paint.Style.FILL);
            if (state == DL_DONE) {
                paint.setColor(accent);
                c.drawCircle(cx, cy, size * .3f, paint);
                c.save();
                c.translate(cx - size * .15f, cy - size * .15f);
                drawGlyph(c, paint, glyph, "check", onAccent, size * .3f);
                c.restore();
            } else if (busy) {
                float r = size * .3f;
                oval.set(cx - r, cy - r, cx + r, cy + r);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor((ink & 0x00ffffff) | 0x33000000);
                c.drawOval(oval, paint);
                paint.setColor(accent);
                int percent = d == null ? 0 : d[1];
                boolean known = state == DL_BUSY && percent > 0;
                float start = known ? -90 : (SystemClock.uptimeMillis() % 1100) / 1100f * 360f, sweep = known ? 360f * percent / 100f : 95f;
                c.drawArc(oval, start, sweep, false, paint);
                paint.setStyle(Paint.Style.FILL);
                float half = size * .075f;
                c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, dp(1.5f), dp(1.5f), paint);
                if (!known && motion() && isShown()) postInvalidateOnAnimation();
            } else {
                c.save();
                c.translate(cx - size * .21f, cy - size * .21f);
                drawGlyph(c, paint, glyph, "download", state == DL_ERROR ? accent2 : ink, size * .42f);
                c.restore();
            }
        }
    }

    /** A thin progress bar. */
    private final class Meter extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value;
        Meter() { super(activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(float v) {
            float next = Math.max(0f, Math.min(1f, v));
            if (next == value) return;
            value = next;
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            paint.setColor((ink & 0x00ffffff) | 0x24000000);
            c.drawRoundRect(0, 0, w, h, r, r, paint);
            paint.setColor(accent);
            c.drawRoundRect(0, 0, Math.max(h, w * value), h, r, r, paint);
        }
    }

    /** The small round badge on a cover that's downloaded (accent) or downloading (dim). */
    private View downloadChip(int state) {
        FrameLayout chip = new FrameLayout(activity);
        boolean done = state == DL_DONE;
        bgRound(chip, 13, () -> done ? accent : (bg & 0x00ffffff) | 0xd0000000);
        chip.addView(icon("download", () -> done ? onAccent : ink), new LayoutParams(dp(14), dp(14), Gravity.CENTER));
        return chip;
    }

    private String downloadLabel(String uri) {
        int state = downloadState(uri);
        if (state == DL_DONE) return "Remove download";
        if (state == DL_BUSY || state == DL_REQUESTED || state == DL_WAITING) return "Cancel download";
        return state == DL_ERROR ? "Retry download" : uri.startsWith("spotify:episode:") ? "Download episode" : "Download song";
    }

    private Option songDownloadOption(MediaEntry song) {
        int[] state = downloads.get(song.uri);
        String note = state == null ? "Listen offline on this device" : downloadStatus(state);
        return new Option(downloadLabel(song.uri), note, "", "download", state != null && state[0] == DL_DONE);
    }

    private void downloadTapped(String uri, String name) {
        downloadTapped(new MediaEntry(name, "", uri, "", -1));
    }

    private void downloadTapped(MediaEntry entry) {
        String uri = entry.uri, name = entry.title.isEmpty() ? kindName(uri) : entry.title;
        int state = downloadState(uri);
        if (state == 0 || state == DL_ERROR) {
            submit(PLAYLISTS, () -> {
                String error = nativeDownload(uri, 0, packStrings(new String[]{name, entry.subtitle, uri, entry.image, Integer.toString(entry.category)}));
                post(() -> {
                    if (closed) return;
                    if (error != null && error.isEmpty()) { pullDownloads(); onDownloadsChanged(true); }
                    Toast.makeText(activity, error != null && error.isEmpty() ? "Downloading “" + name + "”" : error == null ? "No reply from Spotify" : error,
                        error != null && error.isEmpty() ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                });
            });
            return;
        }
        confirmRemoveDownload(uri, name, state == DL_DONE);
    }

    private void confirmRemoveDownload(String uri, String name, boolean done) {
        sheet(name, done ? "Removes this download from this phone. It stays in your library." : "Stops the download and removes what’s been saved so far.",
            new Option[]{new Option(done ? "Remove download" : "Cancel download", "", "", "close", false)}, c -> submit(PLAYLISTS, () -> {
                String error = nativeDownload(uri, 1, null);
                post(() -> Toast.makeText(activity, error == null || error.isEmpty() ? (done ? "Removed download" : "Download cancelled") : error, Toast.LENGTH_LONG).show());
            }));
    }

    private static String bytes(long b) {
        if (b >= 1_000_000_000L) return String.format(java.util.Locale.ROOT, "%.1f GB", b / 1e9);
        if (b >= 1_000_000L) return Math.round(b / 1e6) + " MB";
        return Math.max(0, Math.round(b / 1e3)) + " KB";
    }

    private static String timeLeft(long seconds) {
        if (seconds <= 0) return "";
        return seconds < 60 ? "under a minute left" : ((seconds + 59) / 60) + " min left";
    }

    private static String kindName(String uri) {
        return uri.startsWith("spotify:track:") ? "Song" : uri.startsWith("spotify:episode:") ? "Episode" : uri.contains(":album:") ? "Album" : uri.contains(":show:") ? "Podcast" : "Playlist";
    }

    private void downloadsCard(LinearLayout box) {
        downloadsSummary = text("", 16, () -> ink, true);
        downloadsSummary.setPadding(dp(18), dp(14), dp(18), dp(2));
        box.addView(downloadsSummary);
        downloadsStorage = text("", 13, () -> inkDim, false);
        downloadsStorage.setPadding(dp(18), 0, dp(18), dp(8));
        box.addView(downloadsStorage);

        downloadsProgress = column();
        downloadsProgress.setPadding(dp(18), dp(4), dp(18), dp(12));
        downloadsProgressText = text("", 13, () -> ink, false);
        downloadsProgress.addView(downloadsProgressText);
        downloadsMeter = new Meter();
        LinearLayout.LayoutParams mp = fill(dp(4));
        mp.topMargin = dp(8);
        downloadsProgress.addView(downloadsMeter, mp);
        downloadsProgress.setVisibility(GONE);
        box.addView(downloadsProgress, fill(-2));

        downloadsList = column();
        box.addView(downloadsList, fill(-2));

        ensureSpotifySettings();
        if (spotifySettings != null) {
            int quality = spotifyValue("download_quality");
            segmented(box, "Download quality", new String[]{"Low", "Normal", "High", "Very high", "Lossless"}, quality >= 1 && quality <= 5 ? quality - 1 : -1, i -> setSpotify("download_quality", i + 1));
        }

        if (spotifySettings != null) offlineModeRow(box);

        TextView clear = pill("Remove all downloads", () -> sheet("Remove all downloads?", "Every downloaded song, episode, playlist and album is removed from this phone. Your library stays as it is.",
            new Option[]{new Option("Remove all downloads", "", "", "close", false)}, c -> submit(PLAYLISTS, () -> {
                String error = nativeDownload("", 2, null);
                post(() -> Toast.makeText(activity, error == null || error.isEmpty() ? "Removed all downloads" : error, Toast.LENGTH_LONG).show());
            })));
        clear.setTextColor(ink);
        GradientDrawable clearBg = round(surfHi, 22);
        clear.setBackground(clearBg);
        colored(clear, () -> { clear.setTextColor(ink); clearBg.setColor(surfHi); });
        LinearLayout.LayoutParams cp = fill(dp(46));
        cp.setMargins(dp(18), dp(6), dp(18), dp(14));
        box.addView(clear, cp);
        removeAllDownloads = clear;

        renderDownloads();
        loadStorage();
    }

    /** Android's filesystem API guarantees bytes for this app's storage volume. */
    private void loadStorage() {
        submit(PLAYLISTS, () -> {
            try {
                android.os.StatFs fs = new android.os.StatFs(activity.getFilesDir().getPath());
                long free = fs.getAvailableBytes(), total = fs.getTotalBytes();
                post(() -> {
                    storageFree = free;
                    storageTotal = total;
                    updateDownloadSummary();
                });
            } catch (RuntimeException unavailable) {
                android.util.Log.w("SpatchUi", "Storage statistics unavailable", unavailable);
            }
        });
    }

    private void updateDownloadSummary() {
        if (downloadsSummary == null) return;
        int n = downloads.size();
        downloadsSummary.setText(n == 0 ? "Nothing downloaded" : n + (n == 1 ? " download" : " downloads"));
        downloadsStorage.setText(storageTotal > 0 ? bytes(storageFree) + " free of " + bytes(storageTotal) + " on this phone" : "");
        downloadsStorage.setVisibility(storageTotal > 0 ? VISIBLE : GONE);
    }

    private String downloadStatus(int[] d) {
        switch (d[0]) {
            case DL_DONE: return "Downloaded";
            case DL_BUSY: return d[1] > 0 ? "Downloading • " + d[1] + "%" : "Downloading…";
            case DL_ERROR: return "Couldn’t download • tap to retry";
            default: return "Waiting to download";
        }
    }

    /** Rebuilds the list of downloads in Settings. */
    private void renderDownloads() {
        LinearLayout list = downloadsList;
        if (list == null) return;
        list.removeAllViews();
        downloadRows.clear();
        java.util.Map<String, MediaEntry> known = new java.util.HashMap<>();
        known.putAll(downloadDetails);
        for (MediaEntry e : entries(-1, "", false, 400)) known.put(e.uri, e);
        List<String> order = new ArrayList<>(downloads.keySet());
        java.util.Collections.sort(order, (a, b) -> {
            int sa = downloads.get(a)[0] == DL_DONE ? 1 : 0, sb = downloads.get(b)[0] == DL_DONE ? 1 : 0;
            if (sa != sb) return sa - sb; // what's still downloading first
            MediaEntry ea = known.get(a), eb = known.get(b);
            return (ea == null ? kindName(a) : ea.title).compareToIgnoreCase(eb == null ? kindName(b) : eb.title);
        });
        if (order.isEmpty()) {
            TextView none = text("Hold a song or episode and choose Download, or use the download button on a playlist, album or podcast.", 13, () -> inkDim, false);
            none.setPadding(dp(18), dp(4), dp(18), dp(12));
            list.addView(none, fill(-2));
        }
        for (String uri : order) {
            MediaEntry known1 = known.get(uri);
            String name = known1 != null ? known1.title : kindName(uri);
            MediaEntry entry = known1 != null ? known1 : new MediaEntry(name, "", uri, "", isTrack(uri) ? -1 : uri.contains(":album:") ? 1 : uri.contains(":show:") ? 3 : 0);
            int[] d = downloads.get(uri);
            LinearLayout line = row();
            line.setPadding(dp(14), dp(8), dp(6), dp(8));
            Art art = artwork.new Art(Art.ROUNDED, false);
            art.letter = initial(name);
            art.heart = liked(uri) && entry.image.isEmpty();
            art.bind(entry.image);
            line.addView(art, lp(dp(46), dp(46)));
            LinearLayout words = column();
            words.setPadding(dp(12), 0, dp(6), 0);
            TextView title = text(name, 15, () -> ink, true);
            oneLine(title);
            words.addView(title);
            TextView status = text(downloadStatus(d), 13, () -> d[0] == DL_ERROR ? accent2 : inkDim, false);
            oneLine(status);
            words.addView(status);
            Meter meter = new Meter();
            meter.set(d[1] / 100f);
            meter.setVisibility(d[0] == DL_BUSY ? VISIBLE : GONE);
            LinearLayout.LayoutParams mp = fill(dp(3));
            mp.topMargin = dp(6);
            words.addView(meter, mp);
            line.addView(words, weight());
            line.addView(iconButton(icon("close", () -> inkDim), "Remove download", () -> confirmRemoveDownload(uri, name, d[0] == DL_DONE)), lp(dp(40), dp(40)));
            line.setClickable(true);
            line.setOnClickListener(v -> {
                if (d[0] == DL_ERROR) submit(PLAYLISTS, () -> nativeDownload(uri, 0, null));
                else open(entry);
            });
            list.addView(line, fill(-2));
            downloadRows.put(uri, new Object[]{status, meter});
        }
        if (removeAllDownloads != null) removeAllDownloads.setVisibility(order.isEmpty() ? GONE : VISIBLE);
        updateDownloadSummary();
        updateDownloadProgress();
    }

    /** Refreshes the progress lines in place while downloads move, without rebuilding the list. */
    private void updateDownloadProgress() {
        if (downloadsProgress == null) return;
        String[] t = nativeDownloadTotals();
        boolean busy = t != null && t.length >= 9 && "true".equals(t[4]);
        downloadsProgress.setVisibility(busy ? VISIBLE : GONE);
        if (busy) {
            String left = timeLeft(parseInt(t[6]));
            downloadsProgressText.setText("Downloading • " + t[0] + " of " + t[1] + " songs" + (left.isEmpty() ? "" : " • " + left));
            downloadsMeter.set(parseInt(t[5]) / 100f);
        }
        for (java.util.Map.Entry<String, Object[]> row : downloadRows.entrySet()) {
            int[] d = downloads.get(row.getKey());
            if (d == null) continue;
            ((TextView) row.getValue()[0]).setText(downloadStatus(d));
            ((Meter) row.getValue()[1]).set(d[1] / 100f);
        }
    }

    // ================= account and creation =================

    private TextView pageHeading(LinearLayout page, String title, boolean edgeToEdge) {
        LinearLayout head = row();
        head.setPadding(edgeToEdge ? dp(18) : 0, dp(16), edgeToEdge ? dp(18) : 0, dp(12));
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
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
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
        menuAction(menu, "Queue", "queue", () -> { popup.dismiss(); openQueue(); });
        menuAction(menu, "Downloads", "download", () -> { popup.dismiss(); filter = DOWNLOADED; if (tab == LIBRARY && playlist == null) { showPage(); updateDock(); } else goFromLink(LIBRARY); });
        menuAction(menu, "Settings", "settings", () -> { popup.dismiss(); openSettings(true); });
        popup.showAsDropDown(anchor, 0, dp(8), Gravity.END);
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

    private void menuAction(LinearLayout menu, String title, String symbol, Runnable action) {
        LinearLayout item = row();
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        item.addView(icon(symbol, () -> inkDim), lp(dp(22), dp(22)));
        TextView label = text(title, 15, () -> ink, true);
        label.setPadding(dp(14), 0, 0, 0);
        item.addView(label, weight());
        tappable(item, title, action);
        menu.addView(item, fill(dp(48)));
    }

    private void openSettings(boolean fromLink) {
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

    private View backHeader(String label, boolean horizontalPad) {
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
        Toast.makeText(activity, "Open your profile in Spotify and choose Add account", Toast.LENGTH_LONG).show();
        openNativeSpotify();
    }

    private void openNativeSpotify() {
        String error = CoreBridge.openNativeSpotify(activity);
        if (error != null && !error.isEmpty()) Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
    }

    /** What Spatch knows about the connection to Spotify, with a button to copy it for whoever is helping. */
    private void connectionCard(LinearLayout page) {
        LinearLayout box = card(page, "Connection");
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
        settingsAction(box, "Copy details", "For troubleshooting: paste it to whoever is helping you", () -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spatch connection details", copy));
            Toast.makeText(activity, "Details copied", Toast.LENGTH_SHORT).show();
        });
    }

    private View profilePage() {
        LinearLayout page = column();
        page.addView(backHeader(tabHistory.isEmpty() ? "Back" : tabLabel(tabHistory.peek()), false), lp(-2, dp(36)));
        pageHeading(page, "Your profile", false);
        LinearLayout identity = card(page, "Spotify account");
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
        if (!profileUri.isEmpty()) settingsAction(identity, "Copy profile link", "Share your Spotify profile", () -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            String link = profileUri.startsWith("spotify:user:") ? "https://open.spotify.com/user/" + android.net.Uri.encode(profileUri.substring(13)) : profileUri;
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotify profile", link));
            Toast.makeText(activity, "Profile link copied", Toast.LENGTH_SHORT).show();
        });
        String refreshSubtitle = profileUsername.isEmpty() ? "Spatch is still working out who you are. Tap to check now."
            : profileLoading ? "Checking your Spotify account…"
            : profileAt == 0 ? "Reload your name and photo from Spotify"
            : "Reload your name and photo from Spotify · Updated " + ago(profileAt);
        settingsAction(identity, "Refresh profile", refreshSubtitle, () -> {
            profileAnnounce = true;
            Toast.makeText(activity, profileLoading ? "Still checking your Spotify account…" : "Checking your Spotify account…", Toast.LENGTH_SHORT).show();
            loadProfile(true);
        });
        settingsAction(identity, "Sign in or switch account", "Opens Spotify’s own screens to sign in or add an account. If you’re already signed in, you don’t need this.", this::openLogin);
        settingsAction(identity, "Sign in here (test)", "Spotify’s own sign-in screen, opened the way Spotify opens it", () -> {
            String error = CoreBridge.openSpotifyLogin(activity);
            if (error != null && !error.isEmpty()) Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
        });
        settingsAction(identity, "Open native Spotify", "Temporarily reveal Spotify’s own interface to sign in or manage account", this::openNativeSpotify);
        connectionCard(page);
        LinearLayout music = card(page, "Your music");
        settingsAction(music, "Your playlists", "Open your library", () -> { filter = 0; goFromLink(LIBRARY); });
        settingsAction(music, "Create a playlist", "Start a new collection", () -> { creation.pendingPlaylistSong = null; creation.openCreatePlaylist(false); });
        settingsAction(music, "Your library, at a glance", "What's saved, and what you've played lately", () -> goFromLink(STATS));
        settingsAction(music, "Settings", "Personalise Spatch", () -> openSettings(true));
        return scrolling(page);
    }

    /** A simple recap built only from what Spatch already has cached: counts by kind, and the recent shelf. */
    private View statsPage() {
        LinearLayout page = column();
        page.addView(backHeader(tabHistory.isEmpty() ? "Back" : tabLabel(tabHistory.peek()), false), lp(-2, dp(36)));
        pageHeading(page, "Your library", false);
        int[] counts = new int[4]; // playlists, albums, artists, podcasts, by chip index
        for (MediaEntry e : entries(-1, "", false, 4000)) if (e.category >= 0 && e.category < counts.length) counts[e.category]++;
        LinearLayout glance = card(page, "Saved");
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
        shelf(page, "Recently played", recent, null);
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

    private View createPage() { return homePage(); }

    // ================= search =================

    private static final int HIT_FIELDS = 7;
    private String searchQuery = "";
    private String[] searchHits = new String[0];
    private boolean searchBusy, catalogSearch = true;
    private String catalogPage = "", catalogError = "";
    private int searchRequest, imeInset;
    private ScrollView searchScroll;
    private LinearLayout searchResults;
    private android.widget.EditText searchField;

    private final Runnable searchRun = () -> runSearch("", searchRequest);

    private void scheduleSearch() {
        removeCallbacks(searchRun);
        searchRequest++;
        searchHits = new String[0];
        catalogPage = catalogError = "";
        searchBusy = !searchQuery.trim().isEmpty();
        renderSearch();
        if (searchBusy) postDelayed(searchRun, 300);
    }

    private void runSearch(String token, int id) {
        final String q = searchQuery.trim();
        final boolean online = catalogSearch;
        searchBusy = true;
        renderSearch();
        submit(PLAYLISTS, () -> {
            String[] found = null;
            String next = "", error = "";
            try {
                if (online) {
                    String[] response = unpackStrings(nativeCatalog(CoreBridge.search(q, token)));
                    if (response == null || response.length == 0 || (response.length - 1) % 6 != 0)
                        throw new IllegalStateException("Invalid search response");
                    next = response[0];
                    List<String> hits = new ArrayList<>();
                    for (int i = 1; i + 5 < response.length; i += 6) {
                        int category = parseInt(response[i + 4]);
                        String section = category == 0 ? "Playlists" : category == 1 ? "Albums" : category == 2 ? "Artists"
                            : category == 3 ? "Podcasts" : response[i + 2].startsWith("spotify:episode:") ? "Podcast episodes" : "Songs";
                        java.util.Collections.addAll(hits, section, response[i], response[i + 1], response[i + 2],
                            response[i + 3], response[i + 4], response[i + 5]);
                    }
                    found = hits.toArray(new String[0]);
                } else {
                    String[] offline = unpackStrings(nativeSearch(q));
                    List<String> hits = new ArrayList<>();
                    if (offline != null) for (int i = 0; i + 6 <= offline.length; i += 6) {
                        for (int j = 0; j < 6; j++) hits.add(offline[i + j]);
                        hits.add("0");
                    }
                    found = hits.toArray(new String[0]);
                }
            } catch (Exception failed) {
                error = failed.toString();
                android.util.Log.w("SpatchUi", "Search failed", failed);
            }
            String[] result = found;
            String page = next, failure = error;
            post(() -> {
                if (id != searchRequest) return;
                searchBusy = false;
                catalogError = failure;
                if (failure.isEmpty()) {
                    List<String> merged = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    if (!token.isEmpty()) {
                        java.util.Collections.addAll(merged, searchHits);
                        for (int i = 0; i + HIT_FIELDS <= searchHits.length; i += HIT_FIELDS) seen.add(searchHits[i + 3]);
                    }
                    if (result != null) for (int i = 0; i + HIT_FIELDS <= result.length; i += HIT_FIELDS) {
                        if (seen.add(result[i + 3])) for (int j = 0; j < HIT_FIELDS; j++) merged.add(result[i + j]);
                    }
                    searchHits = merged.toArray(new String[0]);
                    catalogPage = page.equals(token) ? "" : page;
                }
                renderSearch();
            });
        });
    }

    private List<String> recentSearches() {
        List<String> out = new ArrayList<>();
        for (String q : prefs.getString("recent_searches", "").split("\n")) if (!q.trim().isEmpty()) out.add(q.trim());
        return out;
    }

    private void rememberSearch(String q) {
        q = q.trim();
        if (q.isEmpty()) return;
        List<String> all = recentSearches();
        all.remove(q);
        all.add(0, q);
        while (all.size() > 8) all.remove(all.size() - 1);
        prefs.edit().putString("recent_searches", android.text.TextUtils.join("\n", all)).apply();
    }

    private View searchPage() {
        LinearLayout page = column();
        page.setPadding(0, inset.top, 0, 0);
        pageHeading(page, "Search", true);

        segmented(page, "Search in", new String[]{"Spotify", "On this device"}, catalogSearch ? 0 : 1, selected -> {
            catalogSearch = selected == 0;
            scheduleSearch();
            showPage();
        });

        LinearLayout box = row();
        box.setPadding(dp(16), 0, dp(8), 0);
        bgRound(box, 26, () -> surf);
        box.addView(icon("search", () -> inkDim), lp(dp(22), dp(22)));
        android.widget.EditText field = new android.widget.EditText(activity);
        field.setBackground(null);
        field.setHint("Songs, albums, playlists, podcasts");
        field.setHintTextColor(inkDim);
        field.setTextColor(ink);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.setPadding(dp(12), 0, dp(4), 0);
        field.setText(searchQuery);
        box.addView(field, new LinearLayout.LayoutParams(0, dp(50), 1));
        clearButton(box, field);
        LinearLayout.LayoutParams bp = fill(dp(50));
        bp.setMargins(dp(18), 0, dp(18), dp(8));
        page.addView(box, bp);
        searchField = field;

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(true);
        scroll.setPadding(0, 0, 0, imeInset);
        LinearLayout results = column();
        results.setPadding(dp(18), dp(4), dp(18), dp(170) + inset.bottom);
        scroll.addView(results, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> scrollDock(y - oldY, y <= 0));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        searchScroll = scroll;
        searchResults = results;

        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                searchQuery = e.toString();
                scheduleSearch();
            }
        });
        field.setOnEditorActionListener((v, action, event) -> { hideKeyboard(); return true; });
        renderSearch();
        return page;
    }

    /** Draws the results (or, with nothing typed, the recent searches and a note on what's searched). */
    private void renderSearch() {
        LinearLayout box = searchResults;
        if (box == null || !box.isAttachedToWindow() && box.getParent() == null) return;
        box.removeAllViews();
        String q = searchQuery.trim();
        if (q.isEmpty()) {
            List<String> recents = recentSearches();
            if (!recents.isEmpty()) {
                LinearLayout head = row();
                TextView title = text("Recent searches", 15, () -> ink, true);
                head.addView(title, weight());
                TextView clear = pill("Clear", () -> { prefs.edit().remove("recent_searches").apply(); renderSearch(); });
                clear.setTextSize(12);
                clear.setPadding(dp(14), 0, dp(14), 0);
                head.addView(clear, lp(-2, dp(32)));
                head.setPadding(0, dp(6), 0, dp(6));
                box.addView(head, fill(-2));
                for (String recent : recents) {
                    LinearLayout line = row();
                    line.setPadding(dp(4), dp(4), dp(4), dp(4));
                    line.addView(icon("timer", () -> inkDim), lp(dp(20), dp(20)));
                    TextView label = text(recent, 16, () -> ink, false);
                    label.setPadding(dp(14), dp(10), dp(8), dp(10));
                    oneLine(label);
                    line.addView(label, weight());
                    line.setOnClickListener(v -> { searchField.setText(recent); searchField.setSelection(recent.length()); hideKeyboard(); });
                    line.setClickable(true);
                    box.addView(line, fill(-2));
                }
            }
            TextView note = text("Search Spotify’s catalog, or switch to On this device for your library and cached music.", 13, () -> inkDim, false);
            note.setPadding(dp(2), dp(18), dp(2), 0);
            box.addView(note, fill(-2));
            return;
        }
        if (!catalogError.isEmpty()) {
            TextView retry = pill("Couldn’t search Spotify · Retry", () -> runSearch(catalogPage, searchRequest));
            box.addView(retry, fill(dp(48)));
            return;
        }
        if (searchHits.length < HIT_FIELDS) {
            if (searchBusy) {
                box.addView(text("Searching…", 15, () -> inkDim, false), fill(-2));
                return;
            }
            TextView none = text("Nothing found for “" + q + "”", 16, () -> ink, true);
            none.setPadding(dp(2), dp(14), dp(2), dp(4));
            box.addView(none, fill(-2));
            TextView why = text(catalogSearch ? "Try another title or artist." : "Searches your library and Spotify’s cached music on this device.", 13, () -> inkDim, false);
            why.setPadding(dp(2), 0, dp(2), 0);
            box.addView(why, fill(-2));
            return;
        }
        String section = "";
        for (int i = 0; i + HIT_FIELDS <= searchHits.length; i += HIT_FIELDS) {
            if (!searchHits[i].equals(section)) {
                section = searchHits[i];
                TextView header = text(section, 13, () -> accent, true);
                header.setLetterSpacing(.04f);
                header.setPadding(dp(4), dp(i == 0 ? 6 : 18), 0, dp(4));
                box.addView(header, fill(-2));
            }
            MediaEntry e = new MediaEntry(searchHits[i + 1], searchHits[i + 2], searchHits[i + 3], searchHits[i + 4], parseInt(searchHits[i + 5]));
            Row row = new Row();
            row.bind(e);
            if (isTrack(e.uri)) {
                int ms = parseInt(searchHits[i + 6]);
                row.length.setText(ms > 0 ? time(ms) : "");
                row.length.setVisibility(ms > 0 ? VISIBLE : GONE);
            }
            row.setClickable(true);
            row.setOnClickListener(v -> { tick(v); rememberSearch(searchQuery); open(e); });
            row.setOnLongClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); searchActions(e); return true; });
            box.addView(row, fill(-2));
        }
        if (!catalogPage.isEmpty()) box.addView(pill(searchBusy ? "Loading…" : "Load more results", () -> {
            if (!searchBusy) runSearch(catalogPage, searchRequest);
        }), fill(dp(48)));
    }

    /** Long-press on a result: songs and episodes can play or queue; lists, albums and artists get the library menu. */
    private static boolean isTrack(String uri) {
        return uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:") || uri.startsWith("spotify:preview:");
    }

    private void searchActions(MediaEntry e) {
        if (!e.uri.startsWith("spotify:track:") && !e.uri.startsWith("spotify:episode:") && !e.uri.startsWith("spotify:preview:")) { actions(e); return; }
        sheet(e.title, e.subtitle, new Option[]{new Option("Play", "", "", "play", false), new Option("Play next", "", "", "next", false), new Option("Add to queue", "", "", "queue", false), new Option("Add to playlist", "", "", "add", false), songDownloadOption(e)}, c -> {
            if (c == 0) command(PLAY, 0, e.uri);
            else if (c == 1) { command(QUEUE_NEXT, 0, e.uri); Toast.makeText(activity, "Playing next", Toast.LENGTH_SHORT).show(); }
            else if (c == 3) addToPlaylist(e);
            else if (c == 4) downloadTapped(e);
            else { command(QUEUE_ADD, 0, e.uri); Toast.makeText(activity, "Added to queue", Toast.LENGTH_SHORT).show(); }
        });
    }

    private void goFromLink(int next) {
        if (next != tab && (tabHistory.isEmpty() || tabHistory.peek() != tab)) tabHistory.push(tab);
        go(next, true);
    }

    private void go(int next) {
        tabHistory.clear();
        go(next, false);
    }

    private void go(int next, boolean pushForward) {
        creation.closeCreateMenu();
        hideKeyboard();
        if (next == tab && playlist == null) return;
        boolean forward = pushForward || next > tab;
        playlist = null;
        pageStack.clear();
        tab = next;
        setDockHidden(false);
        showPage();
        updateDock();
        enter((forward ? 1 : -1) * dp(36), 0);
    }

    private void buildDock() {
        dock.removeAllViews();
        LinearLayout mini = row();
        mini.setPadding(dp(8), dp(6), dp(8), dp(6));
        // A hairline edge instead of an elevation shadow, which drew unevenly under the rounded, tinted pills.
        bgRound(mini, 30, () -> blend(surfHi, accent, .12f)).setStroke(Math.max(1, dp(.8f)), (ink & 0x00ffffff) | 0x16000000);
        mArt = artwork.new Art(Art.CIRCLE, true);
        mArt.crossfade = true;
        mini.addView(mArt, lp(dp(46), dp(46)));
        LinearLayout words = column();
        words.setPadding(dp(11), 0, dp(6), 0);
        mTitle = text("", 15, () -> ink, true);
        mArtist = text("", 12, () -> inkDim, false);
        oneLine(mTitle); oneLine(mArtist);
        words.addView(mTitle);
        words.addView(mArtist);
        mini.addView(words, weight());
        mini.addView(skip(iconButton(icon("previous", () -> ink), "Previous", this::previousTrack), false), lp(dp(44), dp(44)));
        mPlay = new PlayButton();
        mini.addView(mPlay, lp(dp(50), dp(50)));
        mini.addView(skip(iconButton(icon("next", () -> ink), "Next", () -> command(NEXT, 0, "")), true), lp(dp(44), dp(44)));
        tappable(mini, "Open player", () -> openPlayer(true));
        miniGestures(mini);
        mini.setOnLongClickListener(v -> { currentSongActions(); return true; });
        LinearLayout.LayoutParams mp = fill(-2);
        mp.bottomMargin = dp(8);
        dock.addView(mini, mp);

        dockBar = new Navigator();
        dockBar.setGravity(Gravity.CENTER);
        bgRound(dockBar, 28, () -> surf).setStroke(Math.max(1, dp(.8f)), (ink & 0x00ffffff) | 0x16000000);
        dockBar.setPadding(dp(6), dp(5), dp(6), dp(5));
        dock.addView(dockBar, fill(dp(56)));
        updateDock();
        refresh();
    }

    private void navigate(int destination) {
        if (destination == CREATE) {
            if (creation.createOverlay == null) creation.openCreateMenu(); else creation.closeCreateMenu();
        } else if (destination == SETTINGS) openSettings(false);
        else go(destination);
    }

    void updateDock() {
        if (dockBar == null) return;
        String[] names = {"Home", "Search", "Library", "Create"}, glyphs = {"home", "search", "library", "add"};
        if (dockBar.getChildCount() == 0) {
            for (int i = 0; i < names.length; i++) {
                final int index = NAV_TABS[i];
                LinearLayout item = row();
                item.setGravity(Gravity.CENTER);
                item.addView(icon(glyphs[i], () -> ink), lp(dp(25), dp(25)));
                tappable(item, names[i], () -> navigate(index));
                dockBar.addView(item, new LinearLayout.LayoutParams(0, dp(46), 1));
            }
        }
        for (int i = 0; i < NAV_TABS.length; i++) if (NAV_TABS[i] == CREATE) {
            Icon add = (Icon) ((ViewGroup) dockBar.getChildAt(i)).getChildAt(0);
            float targetRotation = creation.createOverlay == null ? 0f : 45f;
            if (!Float.valueOf(targetRotation).equals(add.getTag())) {
                add.setTag(targetRotation);
                add.animate().cancel();
                if (motion()) add.animate().rotation(targetRotation).setDuration(260).setInterpolator(EMPHASIZED).start();
                else add.setRotation(targetRotation);
            }
            dockBar.getChildAt(i).setContentDescription(creation.createOverlay == null ? "Create" : "Close create menu");
        }
        recolorView(dockBar);
        dockBar.setDestination(creation.createOverlay != null ? CREATE : playlist == null ? tab : -1);
    }

    private final class DockScroll implements android.widget.AbsListView.OnScrollListener {
        private int lastFirst = -1, lastTop;
        public void onScrollStateChanged(android.widget.AbsListView view, int state) {}
        public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
            if (view.getChildCount() == 0) return;
            int top = view.getChildAt(0).getTop();
            int delta = lastFirst < 0 ? 0 : first == lastFirst ? lastTop - top : first > lastFirst ? dp(48) : -dp(48);
            lastFirst = first; lastTop = top;
            scrollDock(delta, first == 0 && top >= view.getPaddingTop());
        }
    }

    final class Navigator extends LinearLayout {
        private float startX, startY, indicatorX = -1, indicatorTarget = -1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.animation.ValueAnimator slide;
        private boolean draggingSlide;
        private long lastTickMs;
        private int destination = HOME;
        Navigator() { super(activity); setOrientation(HORIZONTAL); }

        void setDestination(int target) {
            destination = target;
            if (navDragging || getWidth() == 0) return;
            float x = -1;
            for (int i = 0; i < getChildCount(); i++) if (NAV_TABS[i] == target) {
                View child = getChildAt(i); x = (child.getLeft() + child.getRight()) / 2f;
            }
            settleIndicator(x);
        }
        private void settleIndicator(float x) {
            if (x == indicatorTarget && !draggingSlide) return;
            indicatorTarget = x;
            draggingSlide = false;
            if (slide != null) slide.cancel();
            if (!motion() || indicatorX < 0 || x < 0) { indicatorX = x; invalidate(); return; }
            slide = android.animation.ValueAnimator.ofFloat(indicatorX, x);
            slide.setDuration(240);
            slide.setInterpolator(EMPHASIZED);
            slide.addUpdateListener(a -> { indicatorX = (float) a.getAnimatedValue(); invalidate(); });
            slide.start();
        }
        private void chaseIndicator(float x) {
            indicatorTarget = x;
            if (!motion() || indicatorX < 0 || x < 0) { indicatorX = x; invalidate(); return; }
            if (slide != null && slide.isRunning() && draggingSlide) return;
            if (slide != null) slide.cancel();
            draggingSlide = true;
            lastTickMs = -1;
            slide = android.animation.ValueAnimator.ofFloat(0f, 1f);
            slide.setDuration(1000);
            slide.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            slide.setInterpolator(new android.view.animation.LinearInterpolator());
            slide.addUpdateListener(a -> {
                long now = a.getCurrentPlayTime();
                if (lastTickMs < 0) {
                    lastTickMs = now;
                    if (now == 0) return;
                }
                long rawDt = now >= lastTickMs ? now - lastTickMs : (1000 - lastTickMs) + now;
                lastTickMs = now;
                if (rawDt == 0) return;
                long dt = Math.min(50, rawDt);
                float alpha = 1f - (float) Math.exp(-dt * 0.032f);
                float next = indicatorX + (indicatorTarget - indicatorX) * alpha;
                if (Math.abs(indicatorTarget - next) < 0.5f) {
                    indicatorX = indicatorTarget;
                    invalidate();
                    if (!navDragging) { draggingSlide = false; a.cancel(); }
                } else {
                    indicatorX = next;
                    invalidate();
                }
            });
            slide.start();
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            setDestination(destination);
        }
        private float indicatorHalf() {
            return Math.min(dp(34), (getWidth() - getPaddingLeft() - getPaddingRight()) / (2f * Math.max(1, getChildCount())) - dp(3));
        }
        @Override protected void dispatchDraw(Canvas canvas) {
            if (indicatorX >= 0) {
                paint.setColor(blend(surf, accent, navDragging ? .38f : .25f));
                float half = indicatorHalf();
                canvas.drawRoundRect(indicatorX - half, dp(8), indicatorX + half, getHeight() - dp(8), dp(22), dp(22), paint);
            }
            super.dispatchDraw(canvas);
        }
        @Override protected void onDetachedFromWindow() { if (slide != null) slide.cancel(); draggingSlide = false; super.onDetachedFromWindow(); }
        private void follow(MotionEvent event) {
            float edge = Math.max(dp(24), getPaddingLeft() + indicatorHalf() + dp(3));
            chaseIndicator(Math.max(edge, Math.min(getWidth() - edge, event.getX())));
            int target = targetAt(event);
            if (target >= 0 && target != navHover) {
                navHover = target;
                if (pref("haptics", true)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (!pref("nav_drag", true)) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                startX = event.getX(); startY = event.getY(); navDragging = false;
            } else if (action == MotionEvent.ACTION_MOVE
                && Math.abs(event.getX() - startX) > dp(6)
                && Math.abs(event.getX() - startX) >= Math.abs(event.getY() - startY)) {
                navDragging = true;
                follow(event);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                navDragging = false;
            }
            return navDragging;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && pref("nav_drag", true)) {
                startX = event.getX(); startY = event.getY(); navDragging = false;
                return true;
            }
            if (!navDragging) {
                if (pref("nav_drag", true) && action == MotionEvent.ACTION_MOVE
                    && Math.abs(event.getX() - startX) > dp(6)
                    && Math.abs(event.getX() - startX) >= Math.abs(event.getY() - startY)) {
                    navDragging = true;
                    follow(event);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP) {
                    int target = targetAt(event);
                    if (target >= 0) navigate(target);
                    return true;
                }
                return super.onTouchEvent(event);
            }
            if (action == MotionEvent.ACTION_MOVE) follow(event);
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                int target = targetAt(event);
                navHover = -1; navDragging = false;
                if (action == MotionEvent.ACTION_UP && target >= 0) navigate(target);
                updateDock();
            }
            return true;
        }
        private int targetAt(MotionEvent event) {
            int count = getChildCount();
            if (count == 0) return -1;
            float slopY = dp(16);
            if (event.getY() < -slopY || event.getY() > getHeight() + slopY) return -1;
            if (event.getX() < 0 || event.getX() > getWidth()) return -1;
            float clampedX = Math.max(getChildAt(0).getLeft(), Math.min(getChildAt(count - 1).getRight(), event.getX()));
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (clampedX >= child.getLeft() && clampedX <= child.getRight()) return NAV_TABS[i];
            }
            return -1;
        }
    }

    void setDockHidden(boolean hidden) {
        if (dock == null) return;
        hidden = hidden && pref("auto_hide_dock", false) && !playerOpen;
        if (dockHidden == hidden) return;
        dockHidden = hidden;
        float target = hidden ? dock.getHeight() + dp(24) : 0;
        dock.animate().cancel();
        dock.animate().translationY(target).alpha(hidden ? 0 : 1).setDuration(motion() ? 240 : 0).setInterpolator(EMPHASIZED).start();
        if (dockFade != null) dockFade.animate().alpha(hidden ? 0 : 1).setDuration(motion() ? 240 : 0).start();
    }

    private void scrollDock(int delta, boolean atTop) {
        if (!pref("auto_hide_dock", false) || playerOpen || sheetClose != null || creation.createOverlay != null || queuePanel != null) return;
        if (atTop) { scrollTravel = 0; setDockHidden(false); return; }
        if (Integer.signum(delta) != Integer.signum(scrollTravel)) scrollTravel = 0;
        scrollTravel += delta;
        if (scrollTravel > dp(32)) setDockHidden(true);
        else if (scrollTravel < -dp(18)) setDockHidden(false);
    }

    void showPage() {
        scrollTravel = 0;
        setDockHidden(false);
        if (playlist == null && watchingPlaylist) { watchingPlaylist = false; WATCHES.execute(SpatchUi::nativeStopPlaylist); }
        pageHost.removeAllViews();
        pageScroll = null;
        pageList = null;
        libraryList = null;
        heroArt = null; heroTitle = heroArtist = null;
        View page = playlist != null ? playlistPage() : tab == HOME ? homePage() : tab == SEARCH ? searchPage() : tab == LIBRARY ? libraryPage() : tab == CREATE ? createPage() : tab == PROFILE ? profilePage() : tab == STATS ? statsPage() : settingsPage();
        pageHost.addView(page, new LayoutParams(-1, -1));
        if (tab != SETTINGS && playlist == null) refreshLibraryIfStale();
        if ((tab == HOME || tab == STATS) && playlist == null) loadRecentIfStale();
        refresh();
    }

    private ScrollView scrolling(LinearLayout content) {
        ScrollView s = new ScrollView(activity);
        s.setVerticalScrollBarEnabled(false);
        s.setClipToPadding(false);
        s.setPadding(0, inset.top, 0, 0);
        content.setPadding(dp(18), dp(10), dp(18), dp(170) + inset.bottom);
        content.setClipToPadding(false); // edge-to-edge shelves draw into the side padding
        s.addView(content, new ScrollView.LayoutParams(-1, -2));
        s.setOnScrollChangeListener((view, x, y, oldX, oldY) -> scrollDock(y - oldY, y <= 0));
        pageScroll = s;
        return s;
    }

    // ---------- Home ----------

    private View homePage() {
        LinearLayout page = column();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        TextView h = pageHeading(page, hour < 5 ? "Up late?" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening", false);
        // Expressive type: the first time Home appears the greeting swells from light to bold (variable fonts only).
        if (!greeted && motion() && pref("expressive_type", true) && h.getFontVariationSettings() != null) {
            greeted = true;
            android.animation.ValueAnimator swell = android.animation.ValueAnimator.ofInt(250, 720);
            swell.setDuration(900);
            swell.setStartDelay(120);
            swell.setInterpolator(EMPHASIZED);
            swell.addUpdateListener(a -> h.setFontVariationSettings("'wght' " + a.getAnimatedValue() + ", 'ROND' 100"));
            h.setFontVariationSettings("'wght' 250, 'ROND' 100");
            swell.start();
        }
        TextView sub = text("Here’s your music", 15, () -> inkDim, false);
        sub.setPadding(0, 0, 0, dp(16));
        page.addView(sub);

        FrameLayout hero = new FrameLayout(activity);
        bgRound(hero, 30, () -> surf);
        hero.setClipToOutline(true);
        heroArt = artwork.new Art(Art.WIDE, false);
        heroArt.crossfade = true;
        hero.addView(heroArt, new LayoutParams(-1, dp(200)));
        View scrim = new View(activity);
        GradientDrawable scrimBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{surf & 0x00ffffff, (surf & 0x00ffffff) | 0xc8000000, surf});
        scrim.setBackground(scrimBg);
        colored(scrim, () -> scrimBg.setColors(new int[]{surf & 0x00ffffff, (surf & 0x00ffffff) | 0xc8000000, surf}));
        hero.addView(scrim, new LayoutParams(-1, dp(130), Gravity.BOTTOM));
        LinearLayout words = column();
        words.setPadding(dp(18), 0, dp(84), dp(16));
        heroTitle = text("", 26, () -> ink, true);
        heroArtist = text("", 14, () -> inkDim, false);
        oneLine(heroTitle); oneLine(heroArtist);
        words.addView(heroTitle);
        words.addView(heroArtist);
        hero.addView(words, new LayoutParams(-1, -2, Gravity.BOTTOM));
        // Play or pause right from the banner.
        PlayButton heroPlay = new PlayButton();
        heroPlay.set(pending != null ? pending : playing);
        LayoutParams hp = new LayoutParams(dp(58), dp(58), Gravity.BOTTOM | Gravity.END);
        hp.setMargins(0, 0, dp(16), dp(14));
        hero.addView(heroPlay, hp);
        tappable(hero, "Open player", () -> openPlayer(true));
        page.addView(hero, fill(dp(200)));

        LinearLayout liked = row();
        liked.setPadding(dp(14), dp(12), dp(16), dp(12));
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{blend(surfHi, accent2, .28f), blend(surfHi, accent, .22f)});
        lg.setCornerRadius(dp(26));
        liked.setBackground(lg);
        colored(liked, () -> lg.setColors(new int[]{blend(surfHi, accent2, .28f), blend(surfHi, accent, .22f)}));
        FrameLayout badge = new FrameLayout(activity);
        bgRound(badge, 18, () -> (ink & 0x00ffffff) | 0x26000000);
        badge.addView(icon("heart", () -> ink), new LayoutParams(dp(22), dp(22), Gravity.CENTER));
        liked.addView(badge, lp(dp(44), dp(44)));
        LinearLayout lw = column();
        lw.setPadding(dp(14), 0, 0, 0);
        lw.addView(text("Liked Songs", 17, () -> ink, true));
        lw.addView(text("Everything you’ve hearted", 13, () -> inkDim, false));
        liked.addView(lw, weight());
        tappable(liked, "Open Liked Songs", () -> openPlaylist(new MediaEntry("Liked Songs", "", "spotify:collection:tracks", "", 0)));
        LinearLayout.LayoutParams likedParams = fill(-2);
        likedParams.topMargin = dp(12);
        page.addView(liked, likedParams);

        boolean any = false, hidden = false;
        if (!pref("home_recent", true)) hidden = true;
        else if (!recent.isEmpty()) any |= shelf(page, "Recently played", recent, null);
        for (int c = 0; c < SECTIONS.length; c++) {
            final int category = c;
            if (!pref("home_shelf_" + c, true)) { hidden = true; continue; }
            any |= shelf(page, SECTIONS[c], entries(c, 14), () -> { filter = category; goFromLink(LIBRARY); });
        }
        if (!any && (!hidden || !libraryReady)) {
            TextView note = text(libraryStatus, 14, () -> inkDim, false);
            note.setPadding(dp(4), dp(24), 0, 0);
            page.addView(note);
            libraryRecoveryActions(page, false);
        }
        return scrolling(page);
    }

    private void libraryRecoveryActions(LinearLayout page, boolean edgeToEdge) {
        // A pending/failed library request does not establish that the account is signed out.
        if (libraryReady || fetching || libraryStatus.startsWith("Connecting")) return;
        LinearLayout actions = row();
        if (edgeToEdge) actions.setPadding(dp(22), 0, dp(22), 0);
        actions.addView(pill("Retry", () -> {
            if (fetching) return;
            libraryStatus = "Connecting to your library…";
            loadLibrary();
            showPageKeepingPlace();
        }));
        actions.addView(space(dp(10), 1));
        actions.addView(pill("Open Spotify", this::openNativeSpotify));
        page.addView(actions, lp(-2, -2));
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

    private static List<MediaEntry> entryList(String[] f) {
        List<MediaEntry> out = new ArrayList<>();
        if (f == null) return out;
        for (int i = 0; i + 4 < f.length; i += 5) out.add(new MediaEntry(f[i], f[i + 1], f[i + 2], f[i + 3], parseInt(f[i + 4])));
        return out;
    }

    /** Library items for a chip (-1 for all), searched and sorted by Rust. */
    private List<MediaEntry> entries(int category, String search, boolean alphabetical, int limit) {
        if (category == DOWNLOADED) {
            List<MediaEntry> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (MediaEntry e : entryList(unpackStrings(nativeEntries(-1, search, alphabetical, 400)))) {
                if (downloadState(e.uri) != 0 && out.size() < limit && seen.add(e.uri)) out.add(e);
            }
            String[] words = search.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
            for (MediaEntry e : downloadDetails.values()) {
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

    private List<MediaEntry> entries(int category, int limit) { return entries(category, "", false, limit); }

    // ---- Spotify's own recently played, loaded in the background ----
    private List<MediaEntry> recent = new ArrayList<>();
    private long recentAt;
    private boolean loadingRecent;

    private void loadRecentIfStale() {
        long now = SystemClock.uptimeMillis();
        if (!libraryReady || loadingRecent || (recentAt != 0 && now - recentAt < 60_000)) return;
        loadingRecent = true;
        boolean first = recentAt == 0 && recent.isEmpty();
        submit(PLAYLISTS, () -> {
            if (first) {
                String[] saved = unpackStrings(nativeCached("recent"));
                if (saved != null) post(() -> {
                    if (!recent.isEmpty()) return;
                    recent = entryList(saved);
                    if ((tab == HOME || tab == STATS) && playlist == null && sheetClose == null) showPage();
                });
            }
            byte[] freshBytes = nativeRecent();
            if (freshBytes != null && freshBytes.length > 0) nativeRemember("recent", freshBytes);
            List<MediaEntry> got = entryList(unpackStrings(freshBytes));
            post(() -> {
                loadingRecent = false;
                recentAt = SystemClock.uptimeMillis();
                if (got.isEmpty() || got.equals(recent)) return;
                recent = got;
                if ((tab == HOME || tab == STATS) && playlist == null && sheetClose == null) showPage();
            });
        });
    }

    private boolean shelf(LinearLayout page, String heading, List<MediaEntry> items, Runnable seeAll) {
        if (items.isEmpty()) return false;
        LinearLayout head = row();
        head.setPadding(0, dp(24), 0, dp(10));
        head.addView(text(heading, 22, () -> ink, true), weight());
        if (seeAll != null) {
            TextView all = text("See all", 14, () -> accent, true);
            tappable(all, "See all " + heading, seeAll);
            head.addView(all);
        }
        page.addView(head, fill(-2));
        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout tiles = row();
        int width = Math.max(120, Math.min(160, (getResources().getConfiguration().screenWidthDp - 50) / 2));
        for (MediaEntry e : items) {
            LinearLayout tile = column();
            Art art = artwork.new Art(e.category == 2 ? Art.CIRCLE : Art.ROUNDED, false);
            art.letter = initial(e.title);
            art.heart = liked(e.uri) && e.image.isEmpty();
            art.bind(e.image);
            FrameLayout cover = new FrameLayout(activity);
            cover.addView(art, new LayoutParams(-1, -1));
            // What's playing gets the little moving bars in a chip on its cover.
            if (playingFrom(e.uri)) {
                FrameLayout chip = new FrameLayout(activity);
                bgRound(chip, 12, () -> (bg & 0x00ffffff) | 0xcc000000);
                chip.addView(new Bars(), new LayoutParams(dp(16), dp(14), Gravity.CENTER));
                LayoutParams cp = new LayoutParams(dp(34), dp(26), Gravity.BOTTOM | Gravity.END);
                cp.setMargins(0, 0, dp(e.category == 2 ? 18 : 8), dp(e.category == 2 ? 18 : 8));
                cover.addView(chip, cp);
            }
            int shelfDownload = downloadState(e.uri);
            if (shelfDownload != 0) {
                LayoutParams hp = new LayoutParams(dp(26), dp(26), Gravity.BOTTOM | Gravity.START);
                hp.setMargins(dp(8), 0, 0, dp(8));
                cover.addView(downloadChip(shelfDownload), hp);
            }
            tile.addView(cover, lp(dp(width), dp(width)));
            TextView name = text(e.title, 14, () -> playingFrom(e.uri) ? accent : ink, true);
            oneLine(name);
            name.setPadding(dp(2), dp(8), dp(2), 0);
            tile.addView(name);
            if (!e.subtitle.isEmpty()) {
                TextView sub = text(e.subtitle, 12, () -> inkDim, false);
                oneLine(sub);
                sub.setPadding(dp(2), 0, dp(2), 0);
                tile.addView(sub);
            }
            if (!e.uri.isEmpty()) tappable(tile, e.title, () -> open(e));
            LinearLayout.LayoutParams p = lp(dp(width), -2);
            p.rightMargin = dp(14);
            tiles.addView(tile, p);
        }
        scroller.addView(tiles);
        // Shelves run edge to edge, so cards scroll off the screen rather than being cut at the page margin.
        scroller.setClipToPadding(false);
        scroller.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams sp = fill(-2);
        sp.leftMargin = -dp(18);
        sp.rightMargin = -dp(18);
        page.addView(scroller, sp);
        return true;
    }

    // ---------- Library ----------

    private View libraryPage() {
        LinearLayout page = column();
        page.setPadding(0, inset.top, 0, 0);
        if (!tabHistory.isEmpty() && tabHistory.peek() == PROFILE) {
            page.addView(backHeader("Profile", true), lp(-2, dp(36)));
        }
        pageHeading(page, "Library", true);

        LinearLayout search = row();
        search.setPadding(dp(16), 0, dp(8), 0);
        bgRound(search, 26, () -> surf);
        search.addView(icon("search", () -> inkDim), lp(dp(22), dp(22)));
        android.widget.EditText field = new android.widget.EditText(activity);
        field.setBackground(null);
        field.setHint("Search your library");
        colored(field, () -> { field.setHintTextColor(inkDim); field.setTextColor(ink); });
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(12), 0, dp(4), 0);
        field.setText(query);
        search.addView(field, new LinearLayout.LayoutParams(0, dp(50), 1));
        clearButton(search, field);
        LinearLayout.LayoutParams sp = fill(dp(50));
        sp.setMargins(dp(18), 0, dp(18), dp(10));
        page.addView(search, sp);

        HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setPadding(dp(18), 0, dp(10), dp(6));
        LinearLayout chips = row();
        boolean az = pref("library_az", false);
        TextView sort = text(az ? "A–Z" : "Recent", 14, ink, true);
        sort.setGravity(Gravity.CENTER);
        sort.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable sortBg = round(surfHi, 19);
        sort.setBackground(sortBg);
        colored(sort, () -> { sort.setTextColor(ink); sortBg.setColor(surfHi); });
        tappable(sort, az ? "Sorted A to Z, tap for recent" : "Sorted by recent, tap for A to Z", () -> { prefs.edit().putBoolean("library_az", !az).apply(); showPage(); });
        chips.addView(sort, lp(-2, dp(38)));
        chips.addView(space(dp(8), 1));
        boolean grid = pref("library_grid", false);
        TextView layout = text(grid ? "Grid" : "List", 14, ink, true);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable layoutBg = round(surfHi, 19);
        layout.setBackground(layoutBg);
        colored(layout, () -> { layout.setTextColor(ink); layoutBg.setColor(surfHi); });
        tappable(layout, grid ? "Shown as a grid, tap for a list" : "Shown as a list, tap for a grid", () -> { prefs.edit().putBoolean("library_grid", !grid).apply(); showPage(); });
        chips.addView(layout, lp(-2, dp(38)));
        chips.addView(space(dp(8), 1));
        TextView selectedChip = null;
        for (int i = -1; i <= SECTIONS.length; i++) {
            final int index = i;
            boolean on = i == filter;
            TextView chip = text(i == -1 ? "All" : i == DOWNLOADED ? "Downloaded" : SECTIONS[i], 14, on ? onAccent : ink, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            GradientDrawable chipBg = round(on ? accent : surf, 19);
            chip.setBackground(chipBg);
            colored(chip, () -> { chip.setTextColor(on ? onAccent : ink); chipBg.setColor(on ? accent : surf); });
            tappable(chip, chip.getText().toString(), () -> { filter = index; showPage(); });
            chips.addView(chip, lp(-2, dp(38)));
            chips.addView(space(dp(8), 1));
            if (on) selectedChip = chip;
        }
        chipScroll.addView(chips);
        // The page is rebuilt on each pick, so bring the chosen chip back into view instead of leaving the row at its start.
        if (selectedChip != null && filter >= 0) {
            final View picked = selectedChip;
            chipScroll.post(() -> chipScroll.scrollTo(Math.max(0, picked.getLeft() - dp(40)), 0));
        }
        page.addView(chipScroll, fill(-2));

        List<MediaEntry> shown = entries(filter, query, az, 400);
        if (shown.isEmpty() && query.isEmpty()) {
            TextView note = text(filter == DOWNLOADED && libraryReady ? "Nothing downloaded yet. Open a playlist, album or podcast and tap its download button." : libraryStatus, 14, () -> inkDim, false);
            note.setPadding(dp(22), dp(20), dp(22), 0);
            page.addView(note);
            libraryRecoveryActions(page, true);
        } else {
            TextView noMatch = text("", 15, () -> inkDim, false);
            noMatch.setPadding(dp(22), dp(18), dp(22), 0);
            noMatch.setVisibility(GONE);
            page.addView(noMatch);
            android.widget.AbsListView list = grid ? entryGrid(shown) : entryList(shown, null);
            list.setOnScrollListener(new DockScroll());
            list.setPadding(dp(grid ? 14 : 18), dp(4), dp(grid ? 14 : 18), dp(170) + inset.bottom);
            list.setClipToPadding(false);
            page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            if (list instanceof ListView) pageList = (ListView) list;
            libraryList = list;
            field.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    query = e.toString();
                    shown.clear();
                    shown.addAll(entries(filter, query, az, 400));
                    ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
                    noMatch.setText("Nothing in your library matches “" + query.trim() + "”");
                    noMatch.setVisibility(shown.isEmpty() && !query.trim().isEmpty() ? VISIBLE : GONE);
                }
            });
        }
        return page;
    }

    /** The library as big covers, two to a row. */
    private android.widget.GridView entryGrid(List<MediaEntry> items) {
        android.widget.GridView grid = new android.widget.GridView(activity);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(16));
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setOverScrollMode(OVER_SCROLL_NEVER);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public MediaEntry getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View old, ViewGroup parent) {
                Tile tile = old instanceof Tile ? (Tile) old : new Tile();
                tile.bind(getItem(i));
                return tile;
            }
        });
        grid.setOnItemClickListener((p, v, i, id) -> open(items.get(i)));
        grid.setOnItemLongClickListener((p, v, i, id) -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); actions(items.get(i)); return true; });
        if (motion()) {
            android.view.animation.AnimationSet rise = new android.view.animation.AnimationSet(true);
            rise.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
            rise.addAnimation(new android.view.animation.ScaleAnimation(.92f, 1f, .92f, 1f, android.view.animation.Animation.RELATIVE_TO_SELF, .5f, android.view.animation.Animation.RELATIVE_TO_SELF, .5f));
            rise.setDuration(300);
            rise.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
            grid.setLayoutAnimation(new android.view.animation.GridLayoutAnimationController(rise, .12f, .1f));
        }
        return grid;
    }

    /** A grid cell: a square cover (round for artists) with the name and kind under it. */
    private final class Tile extends LinearLayout {
        private final Art art = artwork.new Art(Art.ROUNDED, false);
        private final TextView name = text("", 15, ink, true), sub = text("", 13, inkDim, false);
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
            int ds = downloadState(e.uri);
            if (ds != 0) chipHolder.addView(downloadChip(ds), new LayoutParams(-1, -1));
        }
    }

    private ListView entryList(List<MediaEntry> items, View header) {
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

    private final Set<Row> attachedRows = new HashSet<>();

    private final class Row extends LinearLayout {
        private String boundUri = "";
        private final Art art = artwork.new Art(Art.ROUNDED, false);
        private final TextView name = text("", 16, ink, true), sub = text("", 13, inkDim, false), length = text("", 13, inkDim, false);
        private final Bars bars = new Bars();
        private final GradientDrawable pressedBg = round(surfHi, 16), badgeBg = round(inkDim, 3);
        private final TextView explicitBadge = text("E", 10, bg, true);
        private final Icon dlBadge = new Icon("download", inkDim);
        private int dlState;
        private boolean current;
        int number;
        private final TextView trackNumber = text("", 16, inkDim, true);
        Row() {
            super(activity);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(4), dp(7), dp(4), dp(7));
            addView(art, lp(dp(52), dp(52)));
            trackNumber.setGravity(Gravity.CENTER);
            trackNumber.setVisibility(GONE);
            addView(trackNumber, lp(dp(36), dp(52)));
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
            length.setPadding(0, 0, dp(10), 0);
            addView(length, lp(-2, -2));
            addView(bars, lp(dp(18), dp(16)));
            pressedBg.setAlpha(0);
            setBackground(pressedBg);
            colored(this, this::tint);
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attachedRows.add(this); updateDownloadBadge(); }
        @Override protected void onDetachedFromWindow() { attachedRows.remove(this); super.onDetachedFromWindow(); }
        void updateDownloadBadge() {
            dlState = downloadState(boundUri);
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
            dlBadge.color = dlState == DL_DONE ? accent : inkDim;
            dlBadge.invalidate();
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
            art.setVisibility(number > 0 ? GONE : VISIBLE);
            trackNumber.setVisibility(number > 0 ? VISIBLE : GONE);
            trackNumber.setText(String.valueOf(number));
            art.shapeKind = e.category == 2 ? Art.CIRCLE : Art.ROUNDED;
            art.letter = initial(e.title);
            art.heart = liked(e.uri) && e.image.isEmpty();
            art.bind(e.image);
            name.setText(e.title);
            sub.setText(e.subtitle);
            long ms = e instanceof Song ? ((Song) e).duration : 0;
            length.setText(ms > 0 ? time(ms) : "");
            length.setVisibility(ms > 0 ? VISIBLE : GONE);
            explicitBadge.setVisibility(e instanceof Song && ((Song) e).explicit ? VISIBLE : GONE);
            boundUri = e.uri;
            updateDownloadBadge();
            current = playlist != null ? !uri.isEmpty() && e.uri.equals(uri) : playingFrom(e.uri);
            tint();
            bars.setVisibility(current ? VISIBLE : GONE);
        }
    }

    private boolean playingFrom(String item) {
        if (context.isEmpty() || item.isEmpty()) return false;
        return item.equals(context) || (liked(item) && liked(context));
    }

    private static boolean liked(String u) {
        return u.equals("spotify:collection") || u.endsWith(":collection") || u.endsWith(":collection:tracks");
    }

    // ---------- Playlist ----------

    private void open(MediaEntry e) {
        hideKeyboard(); // a search result shouldn't open under the keyboard
        // A song on a playlist or album page plays that list from the song, as Spotify does.
        if (playlist != null && (e.uri.startsWith("spotify:track:") || e.uri.startsWith("spotify:episode:") || e.uri.startsWith("spotify:preview:"))) {
            // The song that's already playing opens the player rather than starting over.
            if (e.uri.equals(uri) && listIsPlaying()) { openPlayer(true); return; }
            int index = e instanceof Song ? ((Song) e).index : Math.max(0, playlistTracks.indexOf(e));
            String context = playUri();
            submit(COMMANDS, () -> {
                if (!playerPlayFrom(context, e.uri, index)) post(() -> Toast.makeText(activity, playbackFailure(), Toast.LENGTH_SHORT).show());
            });
            return;
        }
        if (isDetail(e)) { openPlaylist(e); return; }
        boolean isList = e.category == 0 || e.uri.startsWith("spotify:playlist:") || (e.uri.startsWith("spotify:user:") && e.uri.contains(":playlist:")) || liked(e.uri);
        if (isList) openPlaylist(e);
        else command(PLAY, 0, e.uri);
    }

    /** Pages opened from another page (an album from a playlist...), so back retraces them. */
    private static boolean greeted;
    private View headCover;
    private TextView nowPlayingChip, songsNoMatch, songCount;
    private String songCountText = "";
    /** View tag key for the now-playing chip's position (any id unique to this file). */
    private static final int R_COLOR = 0x7f5a0002;
    private static final int R_JUMP = 0x7f5a0001;
    private final java.util.ArrayDeque<MediaEntry> pageStack = new java.util.ArrayDeque<>();

    void openPlaylist(MediaEntry e) { openPlaylist(e, true); }

    void openPlaylist(MediaEntry e, boolean forward) {
        if (watchingPlaylist) { watchingPlaylist = false; WATCHES.execute(SpatchUi::nativeStopPlaylist); }
        if (forward && playlist != null && playlist != e) pageStack.push(playlist);
        playlist = e;
        playlistTracks = new ArrayList<>();
        releases = new ArrayList<>();
        liveUri = "";
        songQuery = "";
        songFields = new String[0];
        allSongs.clear();
        recommendedSongs.clear();
        shownReply = null;
        shownCached = false;
        detailTitle = detailSubtitle = detailImage = "";
        playlistLoading = true;
        playlistFailed = false;
        showPage();
        updateDock();
        enter((forward ? 1 : -1) * dp(44), 0);
        loadPlaylist(e, 0);
    }

    private void loadPlaylist(MediaEntry e, int attempt) {
        int request = ++playlistRequest;
        // Never wait forever: after 20 s the page offers Retry.
        postDelayed(() -> {
            if (request != playlistRequest || playlist != e || !playlistLoading) return;
            playlistRequest++;
            playlistLoading = false;
            playlistFailed = true;
            playlistError = "Spotify didn’t answer in time";
            showPage();
        }, 20_000);
        submit(PLAYLISTS, () -> {
            String key = (isDetail(e) ? "page3:" : "playlist4:") + e.uri;
            // What this page showed last time appears at once; Spotify's answer replaces it if it differs.
            if (attempt == 0) {
                String[] saved = unpackStrings(nativeCached(key));
                if (saved != null) post(() -> { if (request == playlistRequest && playlist == e && playlistLoading) applyReply(e, saved, true); });
            }
            // Liked Songs' link includes your username, which Spotify has to be asked for first.
            String target = liked(e.uri) ? nativeLiked() : e.uri;
            if (target == null || target.isEmpty()) target = e.uri;
            final String resolved = target;
            byte[] firstBytes = isDetail(e) ? nativePage(e.uri) : nativePlaylist(resolved, 0);
            String[] first = unpackStrings(firstBytes);
            boolean ok = first != null && first.length >= (isDetail(e) ? 6 : 2) && first[0].isEmpty();
            int more = ok && !isDetail(e) ? parseInt(first[1]) : 0;
            String[] full;
            if (more > 0) {
                // Long playlist: show the first page now if nothing is on screen, then fetch the rest and show it all.
                post(() -> { if (request == playlistRequest && playlist == e && shownReply == null) applyReply(e, first, false); });
                String[] rest = unpackStrings(nativePlaylistRest(resolved, more));
                if (rest == null) rest = new String[0];
                String[] all = java.util.Arrays.copyOf(first, first.length + rest.length);
                System.arraycopy(rest, 0, all, first.length, rest.length);
                all[1] = "0";
                full = all;
            } else full = first;
            final String[] r = full;
            post(() -> {
                if (request != playlistRequest || playlist != e || !isAttachedToWindow()) return;
                liveUri = resolved;
                if (ok) {
                    nativeRemember(key, more > 0 ? packStrings(r) : firstBytes);
                    if (!isDetail(e) && !watchingPlaylist) {
                        watchingPlaylist = true;
                        WATCHES.execute(() -> nativeWatchPlaylist(resolved, "push:" + e.uri));
                    }
                }
                if (r != null && r.length == 1 && "loading".equals(r[0]) && attempt < 10) {
                    postDelayed(() -> { if (request == playlistRequest && playlist == e) loadPlaylist(e, attempt + 1); }, 700);
                    return;
                }
                if (ok && java.util.Arrays.equals(r, shownReply)) return;
                if (!ok && shownCached) { android.util.Log.w("SpatchUi", "Kept the saved copy: " + (r == null || r.length == 0 ? "no reply" : r[0])); return; }
                applyReply(e, r != null && r.length == 1 && "loading".equals(r[0])
                    ? new String[]{"Spotify is still loading this playlist. Try again in a moment."} : r, false);
            });
        });
    }

    /** Rust's playlist fields: SONG_FIELDS strings per song. */
    private static final int SONG_FIELDS = Song.FIELDS;
    /** Every song of the open playlist, in its order; playlistTracks is what the search and sort leave of it. */
    private String[] songFields = new String[0];
    private final List<Song> allSongs = new ArrayList<>();
    private final List<Song> recommendedSongs = new ArrayList<>();
    private String songQuery = "";

    /** The reply this page is showing, and whether it came from the saved copy. */
    private String[] shownReply;
    private boolean shownCached;

    private void applyReply(MediaEntry e, String[] r, boolean cached) {
        // Replacing what's on screen (saved copy -> Spotify's answer, or a push) keeps the scroll position.
        boolean replacing = shownReply != null;
        ListView list = pageList;
        int first = replacing && list != null ? list.getFirstVisiblePosition() : 0;
        int top = replacing && list != null && list.getChildCount() > 0 ? list.getChildAt(0).getTop() : 0;
        // A newer copy of the playlist that's already on screen updates the list where it stands: rebuilding the page
        // flashed it and moved the list every time Spotify pushed the playlist, which it does whenever a song starts.
        boolean inPlace = replacing && !isDetail(e) && !playlistLoading && !playlistFailed && list != null && songCount != null && list.isAttachedToWindow();
        shownReply = r;
        shownCached = cached;
        if (inPlace) {
            playlistTracks.clear();
        } else {
            playlistTracks = new ArrayList<>();
            releases = new ArrayList<>();
        }
        if (isDetail(e)) applyDetail(r);
        else applyPlaylist(r, inPlace);
        ListView again = pageList;
        if (replacing && again != null && first > 0 && again != list) again.setSelectionFromTop(Math.min(first, Math.max(0, again.getCount() - 1)), top);
    }

    private void applyPlaylist(String[] r) { applyPlaylist(r, false); }

    /** inPlace: the page already lists this playlist, so only its songs and count change. */
    private void applyPlaylist(String[] r, boolean inPlace) {
        playlistLoading = false;
        playlistFailed = r == null || r.length < 2 || !r[0].isEmpty();
        playlistError = r == null || r.length == 0 ? "No reply from Spotify" : r[0];
        if (playlistFailed) android.util.Log.w("SpatchUi", "Playlist request failed: " + playlistError);
        else {
            allSongs.clear();
            recommendedSongs.clear();
            int count = (r.length - 2) / SONG_FIELDS;
            List<String> kept = new ArrayList<>(count * SONG_FIELDS);
            for (int i = 0; i < count; i++) {
                int at = 2 + i * SONG_FIELDS;
                boolean rec = !r[at + 10].isEmpty();
                if (rec) {
                    recommendedSongs.add(new Song(r, at, recommendedSongs.size()));
                } else {
                    allSongs.add(new Song(r, at, allSongs.size()));
                    for (int k = 0; k < SONG_FIELDS; k++) kept.add(r[at + k]);
                }
            }
            if (duration == 0 && !uri.isEmpty()) {
                float from = shownProgress();
                for (Song song : allSongs) {
                    if (song.uri.equals(uri) && song.duration > 0) { duration = song.duration; break; }
                }
                if (duration > 0) animateProgress(from);
            }
            String[] next = kept.toArray(new String[0]);
            // Only the recommended songs changed: the list on screen is already right, so don't repack or rebind it.
            boolean unchanged = inPlace && pageList != null && songCount != null && java.util.Arrays.equals(next, songFields);
            songFields = next;
            if (!unchanged) nativeSongs(packStrings(songFields));
            arrangeSongs();
            if (unchanged) return;
            if (inPlace && pageList != null && songCount != null) {
                notifyList(pageList);
                int total = allSongs.size();
                String time = totalTime();
                songCountText = total + (total == 1 ? " song" : " songs") + (time.isEmpty() ? "" : " • " + time);
                String q = songQuery.trim();
                songCount.setText(q.isEmpty() ? songCountText : playlistTracks.size() + " of " + allSongs.size() + " songs");
                if (songsNoMatch != null) songsNoMatch.setVisibility(playlistTracks.isEmpty() && !q.isEmpty() ? VISIBLE : GONE);
                return;
            }
        }
        showPage();
    }

    /** playlistTracks becomes the songs the search and sort leave, in that order (Rust: playlist::arrange). */
    private void arrangeSongs() {
        int[] order = nativeArrange(songQuery, prefs.getInt("song_sort", 0));
        playlistTracks.clear();
        if (order != null) for (int i : order) if (i < allSongs.size()) playlistTracks.add(allSongs.get(i));
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static final String[] SONG_SORTS = {"Playlist order", "Title", "Artist", "Album", "Recently added"};

    /** "2 h 31 min" or "48 min" for every song's length together. */
    private String totalTime() {
        long ms = 0;
        for (Song song : allSongs) ms += song.duration;
        long minutes = Math.round(ms / 60000.0);
        if (minutes == 0) return "";
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    /** Reads nativePage's reply into the page state and redraws. */
    private void applyDetail(String[] r) {
        playlistLoading = false;
        playlistFailed = r == null || r.length < 6 || !r[0].isEmpty();
        playlistError = r == null || r.length == 0 ? "No reply from Spotify" : r[0];
        if (playlistFailed) android.util.Log.w("SpatchUi", "Page request failed: " + playlistError);
        else {
            detailTitle = r[1]; detailSubtitle = r[2]; detailImage = r[3];
            int tracks = Math.max(0, parseInt(r[4])), shelf = Math.max(0, parseInt(r[5]));
            int at = 6;
            for (int i = 0; i < tracks && at + SONG_FIELDS <= r.length; i++, at += SONG_FIELDS) playlistTracks.add(new Song(r, at, i));
            for (int i = 0; i < shelf && at + 3 < r.length; i++, at += 4) releases.add(new MediaEntry(r[at], r[at + 1], r[at + 2], r[at + 3], 1));
        }
        showPage();
    }

    private void openRecommended() {
        if (recommendedSongs.isEmpty()) return;
        Option[] options = new Option[recommendedSongs.size()];
        for (int i = 0; i < options.length; i++) {
            Song s = recommendedSongs.get(i);
            options[i] = new Option(s.title, s.subtitle, s.image, "play", false);
        }
        sheet("Similar songs", "Recommended based on this playlist", options, i -> command(PLAY, 0, recommendedSongs.get(i).uri));
    }

    /** The mini player follows a sideways drag; a flick left or right skips, a flick up opens the player. */
    private void miniGestures(View mini) {
        final float[] down = new float[2];
        final boolean[] flung = new boolean[1];
        android.view.GestureDetector detector = new android.view.GestureDetector(activity, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (dy < -dp(28) && Math.abs(dy) > Math.abs(dx)) { flung[0] = true; openPlayer(true); }
                else if (Math.abs(dx) > dp(48) && Math.abs(dx) > Math.abs(dy)) {
                    flung[0] = true;
                    if (dx < 0) command(NEXT, 0, ""); else previousTrack();
                    tick(mini);
                }
                return flung[0];
            }
        });
        mini.setOnTouchListener((v, e) -> {
            detector.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY(); flung[0] = false;
                    if (motion()) v.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0];
                    if (motion() && Math.abs(dx) > Math.abs(e.getRawY() - down[1])) v.setTranslationX(dx * .35f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (motion()) v.animate().translationX(0).scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                    else v.setTranslationX(0);
                    if (flung[0]) { v.setPressed(false); return true; } // a flick isn't also a tap
                    break;
            }
            return false;
        });
    }

    /** Long-press on a playlist song: play from it, queue it, go to its album or artist, like or unlike it. */
    private void songActions(Song song) {
        List<Option> names = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        names.add(new Option("Play from here", "", "", "play", false)); runs.add(() -> open(song));
        names.add(new Option("Play next", "", "", "next", false)); runs.add(() -> { command(QUEUE_NEXT, 0, song.uri); Toast.makeText(activity, "Playing next", Toast.LENGTH_SHORT).show(); });
        names.add(new Option("Add to queue", "", "", "queue", false)); runs.add(() -> { command(QUEUE_ADD, 0, song.uri); Toast.makeText(activity, "Added to queue", Toast.LENGTH_SHORT).show(); });
        names.add(new Option("Add to playlist", "", "", "add", false)); runs.add(() -> addToPlaylist(song));
        names.add(songDownloadOption(song)); runs.add(() -> downloadTapped(song));
        if (!song.albumUri.isEmpty()) { names.add(new Option("Go to album", song.album, "", "album", false)); runs.add(() -> openPlaylist(new MediaEntry(song.album, song.subtitle, song.albumUri, song.image, -1))); }
        if (!song.artistUri.isEmpty()) { names.add(new Option("Go to artist", song.subtitle, "", "artist", false)); runs.add(() -> openPlaylist(new MediaEntry(song.subtitle, "Artist", song.artistUri, "", -1))); }
        names.add(new Option(song.liked ? "Remove from Liked Songs" : "Add to Liked Songs", "", "", song.liked ? "heart" : "heart-outline", false));
        runs.add(() -> setLikedSong(song, !song.liked));
        String note = song.album.isEmpty() ? song.subtitle : song.subtitle + " • " + song.album;
        sheet(song.title, note, names.toArray(new Option[0]), choice -> runs.get(choice).run());
    }

    private View playlistPage() {
        FrameLayout page = new FrameLayout(activity);
        boolean detail = isDetail(playlist);
        String headImage = detail && !detailImage.isEmpty() ? detailImage : playlist.image;
        String headTitle = detail && !detailTitle.isEmpty() ? detailTitle : playlist.title;

        // Atmospheric gradient wash behind the header, tinted from the page cover once known (else the active accent).
        View wash = new View(activity);
        GradientDrawable washBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{surfHi, surf, bg});
        wash.setBackground(washBg);
        final Integer[] lastSeed = {null};
        final boolean[] lastDark = {dark};
        final int[] headPal = {0, 0};
        colored(wash, () -> {
            Integer seed = headImage.isEmpty() ? null : SEEDS.get(headImage);
            int top, mid;
            if (seed != null) {
                if (!seed.equals(lastSeed[0]) || lastDark[0] != dark || headPal[0] == 0) {
                    int[] p = nativePalette(seed, true, dark);
                    headPal[0] = p[5];
                    headPal[1] = p[6];
                    lastSeed[0] = seed;
                    lastDark[0] = dark;
                }
                top = blend(surfHi, headPal[0], dark ? .38f : .26f);
                mid = blend(surf, headPal[1], dark ? .20f : .14f);
            } else {
                top = blend(surfHi, accent2, dark ? .34f : .22f);
                mid = blend(surf, accent, dark ? .18f : .12f);
            }
            washBg.setColors(new int[]{top, mid, bg});
        });
        page.addView(wash, new LayoutParams(-1, dp(370) + inset.top, Gravity.TOP));

        LinearLayout header = column();
        header.setPadding(dp(2), inset.top + dp(56), dp(2), dp(14));

        // Centered Material 3 Expressive hero cover with a soft shadow.
        FrameLayout coverWrap = new FrameLayout(activity);
        boolean artistPage = playlist.uri.startsWith("spotify:artist:");
        GradientDrawable coverShape = round(surfHi, artistPage ? 104 : 26);
        coverWrap.setBackground(coverShape);
        colored(coverWrap, () -> coverShape.setColor(surfHi));
        coverWrap.setClipToOutline(true);
        coverWrap.setElevation(dp(8));
        if (headImage.isEmpty() && !detail && liked(playlist.uri)) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{blend(surfHi, accent2, .5f), blend(surfHi, accent, .4f)});
            g.setCornerRadius(dp(26));
            coverWrap.setBackground(g);
            colored(coverWrap, () -> g.setColors(new int[]{blend(surfHi, accent2, .5f), blend(surfHi, accent, .4f)}));
            coverWrap.addView(icon("heart", () -> ink), new LayoutParams(dp(76), dp(76), Gravity.CENTER));
        } else {
            Art art = artwork.new Art(Art.WIDE, false);
            art.letter = initial(headTitle);
            art.bind(headImage);
            coverWrap.addView(art, new LayoutParams(-1, -1));
        }
        LinearLayout.LayoutParams covP = lp(dp(208), dp(208));
        covP.gravity = Gravity.CENTER_HORIZONTAL;
        header.addView(coverWrap, covP);
        headCover = coverWrap;

        // Left-aligned Spotify-style editorial title and stats block.
        TextView name = text(headTitle, 26, () -> ink, true);
        name.setPadding(0, dp(18), 0, dp(4));
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(name, fill(-2));
        int total = detail ? playlistTracks.size() : allSongs.size();
        String time = detail ? "" : totalTime();
        String songs = total + (total == 1 ? " song" : " songs") + (time.isEmpty() ? "" : " • " + time);
        TextView count = text(playlistLoading ? "Loading…" : playlistFailed ? "Couldn’t load" : detail && !detailSubtitle.isEmpty() ? detailSubtitle + " • " + songs : songs, 14, () -> inkDim, false);
        header.addView(count, fill(-2));
        songCount = count;
        songCountText = count.getText().toString();
        if (playlistFailed && !playlistError.isEmpty()) {
            // Spotify's own reason, so a failure says what happened.
            String lookup = liked(playlist.uri) ? nativeLikedNote() : "";
            TextView why = text(playlistError + "\n" + playUri() + (lookup.isEmpty() ? "" : "\n" + lookup), 12, () -> inkDim, false);
            why.setPadding(0, dp(6), 0, dp(4));
            header.addView(why, fill(-2));
        }

        // Spotify-like action row: icon-only shuffle mode, share, Similar on the left; big scalloped play button on the right.
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        String listUri = playUri();
        boolean smartAllowed = !detail && !liked(playlist.uri);

        FrameLayout shuffleBox = new FrameLayout(activity);
        GradientDrawable shufRing = round(surfHi, 23);
        shuffleBox.setBackground(shufRing);
        Icon modeIcon = new Icon("shuffle", inkDim);
        Icon modeSparkle = new Icon("sparkle", accent);
        shuffleBox.addView(modeIcon, new LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LayoutParams sp = new LayoutParams(dp(11), dp(11), Gravity.TOP | Gravity.END);
        sp.setMargins(0, dp(8), dp(7), 0);
        shuffleBox.addView(modeSparkle, sp);
        Runnable paintMode = () -> {
            boolean on = listShuffle > 0;
            shufRing.setColor(on ? blend(surfHi, accent, .24f) : surfHi);
            shufRing.setStroke(Math.max(1, dp(.8f)), on ? (accent & 0x00ffffff) | 0x48000000 : (ink & 0x00ffffff) | 0x14000000);
            modeIcon.color = on ? accent : inkDim;
            modeIcon.invalidate();
            modeSparkle.color = accent;
            modeSparkle.setVisibility(listShuffle == 2 ? VISIBLE : GONE);
            modeSparkle.invalidate();
        };
        colored(shuffleBox, paintMode);
        tappable(shuffleBox, "Shuffle: off, on or Smart Shuffle", () -> {
            if (OfficialPlayback.enabled()) {
                Toast.makeText(activity, "Change shuffle in official Spotify", Toast.LENGTH_SHORT).show();
                return;
            }
            // Off → Shuffle → Smart Shuffle (playlists only) → Off, stored with Spotify for this list.
            int next = listShuffle <= 0 ? 1 : listShuffle == 1 && smartAllowed ? 2 : 0;
            int before = listShuffle;
            listShuffle = next;
            paintMode.run();
            if (listIsPlaying()) command(SHUFFLE, next > 0 ? 1 : 0, "");
            submit(PLAYLISTS, () -> {
                String error = nativeSetShuffleMode(listUri, next);
                if (error != null && !error.isEmpty()) post(() -> {
                    listShuffle = before;
                    paintMode.run();
                    Toast.makeText(activity, "Couldn’t change shuffle: " + error, Toast.LENGTH_SHORT).show();
                });
            });
        });
        actions.addView(shuffleBox, lp(dp(46), dp(46)));

        listShuffle = OfficialPlayback.enabled() ? 0 : -1;
        if (OfficialPlayback.enabled()) shuffleBox.setVisibility(GONE);
        paintMode.run();
        if (!OfficialPlayback.enabled()) submit(PLAYLISTS, () -> {
            int known = nativeShuffleMode(listUri);
            post(() -> { if (playlist != null && listUri.equals(playUri()) && known >= 0) { listShuffle = known; paintMode.run(); } });
        });

        String link = shareLink(playlist.uri);
        if (!link.isEmpty()) {
            actions.addView(space(dp(10), 1));
            View shareBtn = iconButton(icon("share", () -> ink), "Share", () -> shareLinkSheet(headTitle, link));
            GradientDrawable shareBg = round(surfHi, 23);
            shareBtn.setBackground(shareBg);
            colored(shareBtn, () -> {
                shareBg.setColor(surfHi);
                shareBg.setStroke(Math.max(1, dp(.8f)), (ink & 0x00ffffff) | 0x14000000);
            });
            actions.addView(shareBtn, lp(dp(46), dp(46)));
        }
        if (!playlist.uri.startsWith("spotify:artist:")) {
            actions.addView(space(dp(10), 1));
            DownloadButton dlBtn = new DownloadButton(this::playUri, headTitle);
            GradientDrawable dlBg = round(surfHi, 23);
            dlBtn.setBackground(dlBg);
            colored(dlBtn, () -> {
                dlBg.setColor(surfHi);
                dlBg.setStroke(Math.max(1, dp(.8f)), (ink & 0x00ffffff) | 0x14000000);
            });
            actions.addView(dlBtn, lp(dp(46), dp(46)));
            downloadButton = dlBtn;
        }
        if (!recommendedSongs.isEmpty()) {
            actions.addView(space(dp(10), 1));
            View simBtn = iconButton(icon("sparkle", () -> accent), "Similar songs", this::openRecommended);
            GradientDrawable simBg = round(surfHi, 23);
            simBtn.setBackground(simBg);
            colored(simBtn, () -> {
                simBg.setColor(surfHi);
                simBg.setStroke(Math.max(1, dp(.8f)), (ink & 0x00ffffff) | 0x14000000);
            });
            actions.addView(simBtn, lp(dp(46), dp(46)));
        }
        actions.addView(space(0, 1), weight());
        // Placeholder inside the header row; the real PlayButton floats in `page` so it sticks at the top on scroll.
        View fabSlot = space(dp(62), dp(62));
        actions.addView(fabSlot);
        LinearLayout.LayoutParams ap = fill(-2);
        ap.topMargin = dp(12);
        header.addView(actions, ap);

        if (playlistFailed) header.addView(pill("Retry", () -> { playlistLoading = true; playlistFailed = false; showPage(); loadPlaylist(playlist, 0); }));
        android.widget.EditText songSearch = null;
        TextView sortPill = null;
        if (!detail && allSongs.size() > 1) {
            // Search and sort inside the playlist, done in Rust over the songs already here.
            LinearLayout tools = row();
            LinearLayout search = row();
            search.setPadding(dp(14), 0, dp(6), 0);
            GradientDrawable searchBg = round(surfHi, 23);
            search.setBackground(searchBg);
            colored(search, () -> {
                searchBg.setColor(surfHi);
                searchBg.setStroke(Math.max(1, dp(.8f)), (ink & 0x00ffffff) | 0x12000000);
            });
            search.addView(icon("search", () -> inkDim), lp(dp(19), dp(19)));
            songSearch = new android.widget.EditText(activity);
            songSearch.setBackground(null);
            songSearch.setHint("Find in playlist");
            android.widget.EditText find = songSearch;
            colored(find, () -> { find.setHintTextColor(inkDim); find.setTextColor(ink); });
            songSearch.setTextSize(15);
            songSearch.setSingleLine(true);
            songSearch.setPadding(dp(10), 0, dp(4), 0);
            songSearch.setText(songQuery);
            search.addView(songSearch, new LinearLayout.LayoutParams(0, dp(44), 1));
            clearButton(search, songSearch);
            tools.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1));
            tools.addView(space(dp(8), 1));
            int sortChoice = Math.max(0, Math.min(SONG_SORTS.length - 1, prefs.getInt("song_sort", 0)));
            sortPill = text(SONG_SORTS[sortChoice], 14, sortChoice > 0 ? accent : ink, true);
            sortPill.setGravity(Gravity.CENTER);
            sortPill.setPadding(dp(15), 0, dp(15), 0);
            sortPill.setSingleLine(true);
            GradientDrawable sortBg = round(surfHi, 23);
            sortPill.setBackground(sortBg);
            TextView pill = sortPill;
            colored(pill, () -> {
                boolean active = prefs.getInt("song_sort", 0) > 0;
                pill.setTextColor(active ? accent : ink);
                sortBg.setColor(active ? blend(surfHi, accent, .20f) : surfHi);
                sortBg.setStroke(Math.max(1, dp(.8f)), active ? (accent & 0x00ffffff) | 0x40000000 : (ink & 0x00ffffff) | 0x12000000);
            });
            tools.addView(sortPill, lp(-2, dp(44)));
            LinearLayout.LayoutParams tp = fill(-2);
            tp.topMargin = dp(14);
            header.addView(tools, tp);
            songsNoMatch = text("", 15, () -> inkDim, false);
            songsNoMatch.setGravity(Gravity.CENTER);
            songsNoMatch.setPadding(0, dp(24), 0, 0);
            songsNoMatch.setVisibility(GONE);
            header.addView(songsNoMatch, fill(-2));
            // The song that's playing is in this list: a chip that glides the list down to it.
            int playingAt = -1;
            for (int i = 0; i < playlistTracks.size(); i++) {
                MediaEntry t = playlistTracks.get(i);
                if (!title.isEmpty() && t.title.equals(title) && t.subtitle.equals(artist)) { playingAt = i; break; }
            }
            if (playingAt > 3) {
                nowPlayingChip = text("Now playing • " + title, 13, () -> onAccent, true);
                nowPlayingChip.setSingleLine(true);
                nowPlayingChip.setEllipsize(TextUtils.TruncateAt.END);
                nowPlayingChip.setGravity(Gravity.CENTER);
                nowPlayingChip.setPadding(dp(16), 0, dp(16), 0);
                bgRound(nowPlayingChip, 18, () -> accent);
                nowPlayingChip.setTag(R_JUMP, playingAt);
                LinearLayout.LayoutParams cp = lp(-2, dp(34));
                cp.topMargin = dp(10);
                header.addView(nowPlayingChip, cp);
            } else nowPlayingChip = null;
        }

        // Collapsing floating top bar: tonal back circle + page title that fades in as the hero scrolls off.
        LinearLayout topBar = row();
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(14), inset.top + dp(6), dp(18), dp(8));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(bg);
        topBar.setBackground(barBg);
        barBg.setAlpha(0);
        colored(topBar, () -> barBg.setColor(bg));
        View barFade = new View(activity);
        GradientDrawable barFadeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bg, bg & 0x00ffffff});
        barFade.setBackground(barFadeBg);
        colored(barFade, () -> barFadeBg.setColors(new int[]{bg, bg & 0x00ffffff}));
        barFade.setAlpha(0f);
        FrameLayout backBox = new FrameLayout(activity);
        GradientDrawable backShape = round(surf, 21);
        backBox.setBackground(backShape);
        colored(backBox, () -> {
            backShape.setColor((surf & 0x00ffffff) | 0xc8000000);
            backShape.setStroke(Math.max(1, dp(.8f)), (ink & 0x00ffffff) | 0x16000000);
        });
        backBox.addView(icon("back", () -> ink), new LayoutParams(dp(22), dp(22), Gravity.CENTER));
        tappable(backBox, "Back", this::back);
        topBar.addView(backBox, lp(dp(42), dp(42)));
        TextView collapsed = text(headTitle, 18, () -> ink, true);
        oneLine(collapsed);
        collapsed.setPadding(dp(12), 0, dp(74), 0);
        collapsed.setAlpha(0f);
        topBar.addView(collapsed, weight());

        // Floating scalloped PlayButton that tracks the header's action row down to the top bar, then stays pinned.
        PlayButton fab = new PlayButton();
        playButtons.remove(fab);
        fab.set(listIsPlaying() && playing);
        tappable(fab, "Play", () -> { if (listIsPlaying()) togglePlay(); else command(PLAY, listShuffle > 0 ? 1 : 2, playUri()); });
        listFab = fab;
        fab.setVisibility(INVISIBLE);
        final float pinY = inset.top + dp(25);
        Runnable syncFab = () -> {
            if (fabSlot.getHeight() == 0) return;
            boolean headerVisible = header.getParent() != null;
            float scrollY = header.getParent() instanceof ScrollView ? ((ScrollView) header.getParent()).getScrollY() : 0f;
            float naturalY = headerVisible ? header.getTop() + actions.getTop() + fabSlot.getTop() - scrollY : -dp(200);
            fab.setTranslationY(Math.max(pinY, naturalY));
            if (fab.getVisibility() != VISIBLE) fab.setVisibility(VISIBLE);
        };
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> syncFab.run());

        if (playlistTracks.isEmpty() && releases.isEmpty()) {
            ScrollView s = new ScrollView(activity);
            s.setVerticalScrollBarEnabled(false);
            header.setPadding(dp(20), inset.top + dp(56), dp(20), dp(170) + inset.bottom);
            s.addView(header);
            s.setOnScrollChangeListener((v, sx, sy, osx, osy) -> { syncFab.run(); scrollDock(sy - osy, sy <= 0); });
            page.addView(s, new LayoutParams(-1, -1));
        } else {
            ListView list = entryList(playlistTracks, header);
            list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (songSearch != null) {
                songSearch.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
                    @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
                    @Override public void afterTextChanged(android.text.Editable e) {
                        songQuery = e.toString();
                        arrangeSongs();
                        notifyList(list);
                        if (songCount != null) {
                            String q = songQuery.trim();
                            songCount.setText(q.isEmpty() ? songCountText : playlistTracks.size() + " of " + allSongs.size() + " songs");
                        }
                        if (songsNoMatch != null) {
                            songsNoMatch.setText("No songs match “" + songQuery.trim() + "”");
                            songsNoMatch.setVisibility(playlistTracks.isEmpty() && !songQuery.trim().isEmpty() ? VISIBLE : GONE);
                        }
                    }
                });
            }
            if (nowPlayingChip != null) {
                TextView chip = nowPlayingChip;
                int at = (Integer) chip.getTag(R_JUMP);
                tappable(chip, "Jump to the song that's playing", () -> list.smoothScrollToPositionFromTop(at + list.getHeaderViewsCount(), dp(120), 520));
            }
            if (sortPill != null) {
                TextView pillView = sortPill;
                tappable(sortPill, "Sort songs", () -> sheet("Sort songs", "Only changes how Spatch shows this playlist", SONG_SORTS, choice -> {
                    prefs.edit().putInt("song_sort", choice).apply();
                    pillView.setText(SONG_SORTS[choice]);
                    if (pillView.getTag(R_COLOR) instanceof Runnable) ((Runnable) pillView.getTag(R_COLOR)).run();
                    arrangeSongs();
                    notifyList(list);
                }));
            }
            if (!releases.isEmpty()) {
                LinearLayout footer = column();
                shelf(footer, "Releases", releases, null);
                list.addFooterView(footer, null, false);
            }
            list.setPadding(dp(18), 0, dp(18), dp(170) + inset.bottom);
            // The cover shrinks up and fades, the atmospheric wash recedes, and the floating bar brings in the title.
            View coverView = headCover;
            if (coverView != null) coverView.setPivotY(0);
            DockScroll dockScroll = new DockScroll();
            list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(android.widget.AbsListView v, int state) {}
                @Override public void onScroll(android.widget.AbsListView v, int first, int visible, int totalItems) {
                    dockScroll.onScroll(v, first, visible, totalItems);
                    float scrolled = first == 0 && v.getChildCount() > 0 ? Math.max(0, -v.getChildAt(0).getTop()) : dp(400);
                    float f = Math.max(0f, Math.min(1f, scrolled / dp(240)));
                    if (coverView != null && motion()) {
                        coverView.setScaleX(1f - .3f * f);
                        coverView.setScaleY(1f - .3f * f);
                        coverView.setAlpha(1f - .9f * f);
                    }
                    wash.setTranslationY(-scrolled * .35f);
                    wash.setAlpha(1f - .9f * f);
                    float barT = Math.max(0f, Math.min(1f, (scrolled - dp(190)) / dp(60)));
                    barBg.setAlpha(Math.round(255 * barT));
                    barFade.setAlpha(barT);
                    collapsed.setAlpha(barT);
                    syncFab.run();
                }
            });
            list.setClipToPadding(false);
            page.addView(list, new LayoutParams(-1, -1));
            pageList = list;
        }
        page.addView(topBar, new LayoutParams(-1, dp(56) + inset.top, Gravity.TOP));
        LayoutParams bf = new LayoutParams(-1, dp(16), Gravity.TOP);
        bf.topMargin = dp(56) + inset.top;
        page.addView(barFade, bf);
        LayoutParams fp = new LayoutParams(dp(62), dp(62), Gravity.TOP | Gravity.END);
        fp.rightMargin = dp(20);
        page.addView(fab, fp);
        return page;
    }

    private void currentSongActions() {
        if (!uri.isEmpty()) searchActions(new MediaEntry(title, artist, uri, image, -1));
    }

    private void addToPlaylist(MediaEntry song) {
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
                Toast.makeText(activity, failure.isEmpty() ? "Added to “" + target.title + "”" : failure, Toast.LENGTH_LONG).show();
                if (failure.isEmpty() && playlist != null && playlist.uri.equals(target.uri)) openPlaylist(target);
            });
        });
    }

    private void actions(MediaEntry e) {
        if (e instanceof Song) { songActions((Song) e); return; }
        if (isTrack(e.uri)) { searchActions(e); return; }
        boolean song = playlist != null;
        Option[] options = song
            ? new Option[]{new Option("Play", "", "", "play", false), new Option("Add to queue", "", "", "queue", false)}
            : new Option[]{new Option("Play", "", "", "play", false), new Option("Shuffle play", "", "", "shuffle", false), new Option("Open", "", "", "chevron", false)};
        sheet(e.title, e.subtitle.isEmpty() ? "Choose what to do" : e.subtitle, options, choice -> {
            if (song) {
                if (choice == 0) command(PLAY, 0, e.uri);
                else { command(QUEUE_ADD, 0, e.uri); Toast.makeText(activity, "Added to queue", Toast.LENGTH_SHORT).show(); }
            } else if (choice == 2) open(e);
            else { command(SHUFFLE, choice == 1 ? 1 : 0, ""); command(PLAY, 0, e.uri); }
        });
    }

    // ---------- Settings ----------

    private View settingsHub() {
        LinearLayout page = column();
        pageHeading(page, "Settings", false);
        LinearLayout categories = card(page, "Make it yours");
        String[] summaries = {"Colour, artwork, motion and celebrations", "Quality, crossfade and listening controls",
            "Headphone correction, tone and dynamics", "Navigation, scroll behaviour and gestures",
            "Downloads, artwork cache and storage", "Diagnostics and connection details", "Version and app information"};
        for (int i = 0; i < SETTINGS_GROUPS.length; i++) {
            final int section = i;
            settingsAction(categories, SETTINGS_GROUPS[i], summaries[i], () -> { settingsSection = section; sound.panel = -1; showPage(); enter(dp(28), 0); });
        }
        return scrolling(page);
    }

    private View settingsPage() {
        if (settingsSection < 0 || settingsSection >= SETTINGS_GROUPS.length) return settingsHub();
        LinearLayout page = column();
        LinearLayout up = row();
        up.setPadding(dp(2), dp(6), 0, 0);
        up.addView(icon("back", () -> accent), lp(dp(20), dp(20)));
        TextView parent = text(settingsSection == 2 && sound.panel >= 6 ? "Headphone correction" : settingsSection == 2 && sound.panel >= 0 ? "Equalizer & sound" : "Settings", 14, () -> accent, true);
        parent.setPadding(dp(8), 0, 0, 0);
        up.addView(parent);
        tappable(up, "Back to settings", () -> {
            if (settingsSection == 2 && sound.panel >= 0) sound.panel = sound.panel >= 6 ? 1 : -1; else settingsSection = -1;
            showPage();
        });
        page.addView(up, fill(dp(34)));
        pageHeading(page, settingsSection == 2 && sound.panel >= 0 ? SoundSettings.SOUND_GROUPS[sound.panel] : SETTINGS_GROUPS[settingsSection], false).setTextSize(28);
        switch (settingsSection) {
        case 0: {
            LinearLayout look = card(page, "Look");
            choice(look, "Theme", new String[]{"System", "Dark", "Light"}, "theme", 1, true);
            toggle(look, "Artwork colours", "Tint the whole app with the song’s cover", "art_colors", true, true);
            accentRow(look);
            toggle(look, "Expressive type", "Google Sans Flex on Pixel; off uses Roboto", "expressive_type", true, true);

            LinearLayout player = card(page, "Player visuals");
            choice(player, "Visualizer", new String[]{"Ring and bars", "Ring only", "Off"}, "viz", 0, false);
            toggle(player, "Follow the music", "The bars react to Spotify’s playback; if it can’t be read they pulse on their own. Android lists this as microphone access", "audio", true, false);
            TextView audioStatus = text("Status: " + Audio.status, 12, () -> inkDim, false);
            audioStatus.setPadding(dp(18), 0, dp(18), dp(10));
            player.addView(audioStatus);
            toggle(player, "Spinning artwork", "The cover turns slowly while music plays", "spin", true, false);
            toggle(player, "Blurred backdrop", "The cover, blurred and drifting, behind the player", "backdrop", true, false);
            if (pref("backdrop", true)) choice(player, "Backdrop blur", new String[]{"Soft", "Deep", "Streaks"}, "backdrop_blur", 0, false);
            toggle(player, "Floating motes", "Particles coast to a quiet stop on pause and drift again on play", "motes", true, false);
            if (pref("motes", true)) {
                choice(player, "Mote amount", new String[]{"Few", "Normal", "Many"}, "motes_amount", 1, false);
                choice(player, "Mote shapes", new String[]{"Dots", "Stars", "Hearts", "Mix"}, "motes_shape", 0, false);
            }


            LinearLayout celebrations = card(page, "Motion & celebrations");
            toggle(celebrations, "Reduce motion", "A calmer app, without animated transitions or celebrations", "reduce_motion", false, true);
            toggle(celebrations, "Playback confetti", "A burst of shapes when you tap play or pause", "confetti", true, false);
            choice(celebrations, "Celebrate", new String[]{"Play & pause", "Play only"}, "confetti_when", 0, false);
            choice(celebrations, "Amount", new String[]{"Subtle", "Normal", "Festive"}, "confetti_amount", 1, false);
            choice(celebrations, "Shapes", new String[]{"Mix", "Cookies", "Hearts", "Stars", "Dots"}, "confetti_shape", 0, false);
            choice(celebrations, "Size", new String[]{"Small", "Normal", "Large"}, "confetti_size", 1, false);
            choice(celebrations, "Colours", new String[]{"Artwork", "Rainbow", "Solid"}, "confetti_tones", 0, false);

            LinearLayout home = card(page, "Home");
            toggle(home, "Recently played", "Spotify’s own listening history", "home_recent", true, false);
            for (int i = 0; i < SECTIONS.length; i++) toggle(home, SECTIONS[i], "Show the " + SECTIONS[i].toLowerCase(java.util.Locale.ROOT) + " shelf", "home_shelf_" + i, true, false);

            LinearLayout library = card(page, "Library");
            toggle(library, "Sort A to Z", "Alphabetical, with Liked Songs first; off shows Spotify’s recent order", "library_az", false, false);
            toggle(library, "Show as a grid", "Big covers two to a row instead of a list", "library_grid", false, false);
            break;
        }
        case 1: {
            if (!OfficialPlayback.enabled()) {
                LinearLayout spotify = card(page, "Spotify");
                spotifyCard(spotify);
            }

            LinearLayout backend = card(page, "Playback backend");
            settingsAction(backend, "Playback backend", OfficialPlayback.enabled() ? "Official Spotify fallback" : "Built-in player", () -> {
                sheet("Playback backend", "Official Spotify plays audio while Spatch shows your library and controls. Keep it installed and signed in; normal ads and free-account restrictions apply. Some selections may open Spotify.",
                    new String[]{"Built-in player", "Official Spotify fallback"}, selected -> {
                        boolean official = selected == 1;
                        if (official && !OfficialPlayback.installed()) {
                            Toast.makeText(activity, OfficialPlayback.status(), Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (official) COMMANDS.execute(() -> nativeCommand(PAUSE, 0, ""));
                        OfficialPlayback.setEnabled(official);
                        revision = -1;
                        syncNativeState();
                        settingsChanged(false);
                        if (official && !OfficialPlayback.allowed()) OfficialPlayback.configure(activity);
                    });
            });
            if (OfficialPlayback.enabled()) {
                settingsAction(backend, "Notification access", OfficialPlayback.status(), () -> OfficialPlayback.configure(activity));
                settingsAction(backend, "Open official Spotify", "Sign in or start a session, then return to Spatch", () -> {
                    android.content.Intent launch = activity.getPackageManager().getLaunchIntentForPackage(OfficialPlayback.PACKAGE);
                    if (launch != null) activity.startActivity(launch);
                    else Toast.makeText(activity, OfficialPlayback.status(), Toast.LENGTH_LONG).show();
                });
            }

            LinearLayout playback = card(page, "Player");
            toggle(playback, "Keep screen on", "While the player is open", "keep_on", true, false);
            break;
        }
        case 2: {
            if (OfficialPlayback.enabled()) {
                LinearLayout note = card(page, "Official Spotify audio");
                settingsAction(note, "Audio settings", "Manage sound in official Spotify while using the fallback", () -> {
                    android.content.Intent launch = activity.getPackageManager().getLaunchIntentForPackage(OfficialPlayback.PACKAGE);
                    if (launch != null) activity.startActivity(launch);
                });
            } else sound.render(page);
            break;
        }
        case 3: {
            LinearLayout navigation = card(page, "Navigation");
            toggle(navigation, "Slide to switch", "Press and drag across the navigation bar, then release on a destination", "nav_drag", true, false);
            toggle(navigation, "Hide controls while scrolling", "Hide the navigation bar and mini player on the way down; show them when you scroll up", "auto_hide_dock", false, false);

            LinearLayout gestures = card(page, "Gestures and controls");
            toggle(gestures, "Double-tap to play or pause", "On the artwork in the player", "double_tap", true, false);
            toggle(gestures, "Swipe artwork to skip", "Flick the artwork left for next, right for previous", "swipe_skip", true, false);
            choice(gestures, "Hold-to-seek step", new String[]{"5 s", "10 s", "30 s"}, "seek_step", 1, false);
            toggle(gestures, "Haptics", "Little taps on buttons", "haptics", true, false);
            break;
        }
        case 4: {
            LinearLayout downloadsBox = card(page, "Downloads");
            downloadsCard(downloadsBox);

            LinearLayout storage = card(page, "Storage");
            cacheRow(storage);
            break;
        }
        case 5: {
            LinearLayout developer = card(page, "Developer");
            toggle(developer, "Skip integrity attestation", "Experiment only. Restart Spotify after changing; verification may run only once a day.",
                "integrity_skip", false, false);
            choice(developer, "Integrity experiment", new String[]{"Skip call", "Empty token"}, "integrity_variant", 0, false);
            toggle(developer, "Trace signature reads", "Log certificate digests and matching request bodies. Restart to trace startup reads.",
                "integrity_trace", false, false);
            settingsAction(developer, "Playback diagnostics", "Connection, playback and audio effects", () -> {
                String report = "Build " + nativeBuild() + "\nAndroid " + android.os.Build.VERSION.SDK_INT
                    + "\nPlaying " + playing + " · buffering " + buffering
                    + "\nPosition " + position + " ms · duration " + duration + " ms"
                    + "\nLibrary loaded " + libraryReady + " · upcoming " + upcoming.size()
                    + "\nEqualizer " + Audio.eqStatus + "\nEQ detail " + Audio.eqError + "\nVisualizer " + Audio.status
                    + "\nSearch " + (catalogError.isEmpty() ? "No error" : catalogError)
                    + "\n" + CoreBridge.diagnostics();
                sheet("Diagnostics", report, new String[]{"Copy diagnostics", "Close"}, choice -> {
                    if (choice == 0) {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spatch diagnostics", report));
                    }
                });
            });
            settingsAction(developer, "Refresh library", "Reload your saved music from Spotify", () -> {
                submit(DATA, () -> {
                    nativeRefreshLibrary();
                    post(() -> Toast.makeText(activity, "Library refresh finished", Toast.LENGTH_SHORT).show());
                });
            });
            break;
        }
        case 6: {
            AboutScreen.render(this, page, nativeBuild());
            break;
        }
        }
        return scrolling(page);
    }

    // ---- Spotify's own settings (Settings service in its core), read and written live ----
    private java.util.Map<String, Integer> spotifySettings;
    private String spotifyError = "";
    private boolean spotifyLoading;

    private int spotifyValue(String key) { Integer v = spotifySettings == null ? null : spotifySettings.get(key); return v == null ? 0 : v; }

    /** Loads Spotify's own settings once; every card that shows one calls this and is redrawn when they arrive. */
    private void ensureSpotifySettings() {
        if (spotifySettings != null || spotifyLoading || !spotifyError.isEmpty()) return;
        spotifyLoading = true;
        submit(PLAYLISTS, () -> {
            String[] f = nativeSpotifySettings();
            post(() -> {
                spotifyLoading = false;
                if (f == null || f.length < 2 || f[0].equals("!")) spotifyError = f == null || f.length < 2 ? "no reply" : f[1];
                else {
                    java.util.Map<String, Integer> read = new java.util.HashMap<>();
                    for (int i = 0; i + 1 < f.length; i += 2) read.put(f[i], Integer.parseInt(f[i + 1]));
                    spotifySettings = read;
                }
                if (tab == SETTINGS && playlist == null) settingsChanged(false);
            });
        });
    }

    private void spotifyCard(LinearLayout box) {
        if (spotifySettings == null) {
            TextView note = text(spotifyError.isEmpty() ? "Loading Spotify’s settings…" : "Couldn’t read Spotify’s settings: " + spotifyError, 14, () -> inkDim, false);
            note.setPadding(dp(18), dp(12), dp(18), dp(12));
            box.addView(note);
            if (!spotifyError.isEmpty()) {
                TextView retry = pill("Retry", () -> { spotifyError = ""; settingsChanged(false); });
                retry.setPadding(dp(18), dp(10), dp(18), dp(10));
                LinearLayout.LayoutParams rp = lp(-2, -2);
                rp.setMargins(dp(18), 0, 0, dp(12));
                box.addView(retry, rp);
            } else ensureSpotifySettings();
            return;
        }
        // Spotify's scale: 1 Low, 2 Normal, 3 High, 4 Very high, 5 Lossless (Premium where offered).
        String[] qualities = {"Low", "Normal", "High", "Very high", "Lossless"};
        int streaming = spotifyValue("stream_quality");
        segmented(box, "Streaming quality", qualities, streaming >= 1 && streaming <= 5 ? streaming - 1 : -1, i -> setSpotify("stream_quality", i + 1));
        spotifySwitch(box, "Allow quality downgrade", "Lower the quality when your connection is slow", "allow_audio_quality_downgrade");
        spotifySwitch(box, "Download over cellular", "Let downloads use mobile data", "download_over_3g");
        spotifySwitch(box, "Normalise volume", "Play songs at the same level", "normalize");
        spotifySwitch(box, "Gapless playback", "No pause between songs", "gapless");
        spotifySwitch(box, "Automix", "Blend songs together in playlists", "automix");
        int fade = spotifyValue("crossfade") == 0 ? 0 : spotifyValue("crossfade_time_seconds");
        int[] seconds = {0, 3, 6, 12};
        // Spotify's crossfade is any number of seconds; light the nearest of the offered lengths.
        int selected = 0;
        for (int i = 0; i < seconds.length; i++) if (Math.abs(seconds[i] - fade) < Math.abs(seconds[selected] - fade)) selected = i;
        if (fade > 0 && selected == 0) selected = 1;
        segmented(box, "Crossfade", new String[]{"Off", "3 s", "6 s", "12 s"}, selected, i -> {
            if (seconds[i] == 0) setSpotify("crossfade", 0, false);
            else { setSpotify("crossfade_time_seconds", seconds[i], false); setSpotify("crossfade", 1, false); }
            settingsChanged(false);
        });
        spotifySwitch(box, "Play explicit content", "Off hides songs marked explicit", "play_explicit_content");
        spotifySwitch(box, "Private session", "Listen without it showing in your activity", "private_session");
        spotifySwitch(box, "Show unavailable songs", "Keep songs that can’t be played in lists", "show_unavailable_tracks");
        TextView note = text("These are Spotify’s own settings: changing them here changes them in Spotify too.", 12, () -> inkDim, false);
        note.setPadding(dp(18), dp(4), dp(18), dp(10));
        box.addView(note);
    }

    /** Spotify's offline mode: it stops using the internet and plays only downloads, so turning it on asks first. */
    private void offlineModeRow(LinearLayout box) {
        boolean on = spotifyValue("offline_mode") != 0;
        LinearLayout line = row();
        line.setPadding(dp(18), dp(12), dp(16), dp(12));
        LinearLayout words = column();
        words.addView(text("Offline mode", 16, () -> ink, true));
        TextView d = text("Play only what’s downloaded, without using the internet", 13, () -> inkDim, false);
        d.setPadding(0, dp(2), dp(8), 0);
        words.addView(d);
        line.addView(words, weight());
        Switch sw = new Switch(on);
        line.addView(sw, lp(dp(52), dp(32)));
        if (android.os.Build.VERSION.SDK_INT >= 30) line.setStateDescription(on ? "On" : "Off");
        tappable(line, "Offline mode", () -> {
            if (on) { setSpotify("offline_mode", 0); return; }
            sheet("Turn on offline mode?", "Spotify stops using the internet and plays only what you’ve downloaded. Browsing and search won’t load anything new until you turn it off here.",
                new Option[]{new Option("Turn on offline mode", "", "", "download", false)}, c -> setSpotify("offline_mode", 1));
        });
        box.addView(line, fill(-2));
    }

    private void spotifySwitch(LinearLayout box, String name, String detail, String key) {
        LinearLayout line = row();
        line.setPadding(dp(18), dp(12), dp(16), dp(12));
        LinearLayout words = column();
        words.addView(text(name, 16, () -> ink, true));
        TextView d = text(detail, 13, () -> inkDim, false);
        d.setPadding(0, dp(2), dp(8), 0);
        words.addView(d);
        line.addView(words, weight());
        Switch sw = new Switch(spotifyValue(key) != 0);
        line.addView(sw, lp(dp(52), dp(32)));
        if (android.os.Build.VERSION.SDK_INT >= 30) line.setStateDescription(sw.on ? "On" : "Off");
        tappable(line, name, () -> { sw.set(!sw.on); setSpotify(key, sw.on ? 1 : 0, false); });
        box.addView(line, fill(-2));
    }

    /** Shows the new value at once, asks Spotify to change it, and puts the old one back if it refuses. */
    private void setSpotify(String key, int value) { setSpotify(key, value, true); }

    private void setSpotify(String key, int value, boolean redraw) {
        if (spotifySettings == null) return;
        int before = spotifyValue(key);
        spotifySettings.put(key, value);
        if (redraw) settingsChanged(false);
        submit(PLAYLISTS, () -> {
            String error = nativeSetSpotifySetting(key, value);
            if (error != null && !error.isEmpty()) post(() -> {
                spotifySettings.put(key, before);
                Toast.makeText(activity, "Spotify didn’t change that: " + error, Toast.LENGTH_LONG).show();
                if (tab == SETTINGS && playlist == null) settingsChanged(false);
            });
        });
    }

    /** Redraws Settings in place after a change, keeping the scroll position. */
    void settingsChanged(boolean palette) {
        int y = pageScroll != null ? pageScroll.getScrollY() : 0;
        showPage();
        keepScroll(pageScroll, y);
        if (palette) { paletteKey = "?"; refreshPalette(); }
    }

    /** Puts a rebuilt page back at `y` after layout but before its first frame, so it never shows at the top. */
    private static void keepScroll(ScrollView s, int y) {
        if (s == null || y <= 0) return;
        s.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                s.getViewTreeObserver().removeOnPreDrawListener(this);
                s.scrollTo(0, y);
                return true;
            }
        });
    }

    /** A title over a connected button group; the pick is stored as an index under `key`. */
    void choice(LinearLayout box, String name, String[] names, String key, int fallback, boolean palette) {
        segmented(box, name, names, prefs.getInt(key, fallback), pick -> {
            prefs.edit().putInt(key, pick).apply();
            if (key.startsWith("eq_")) Audio.refreshEqualizer();
            settingsChanged(palette);
        });
    }

    interface Pick { void picked(int index); }

    /** A title over a connected button group with `selected` highlighted (-1 for none). */
    private void segmented(LinearLayout box, String name, String[] names, int selected, Pick onPick) {
        TextView label = text(name, 16, () -> ink, true);
        label.setPadding(dp(18), dp(12), dp(18), dp(8));
        box.addView(label);
        LinearLayout group = row();
        group.setPadding(dp(14), 0, dp(14), dp(12));
        int current = selected, last = names.length - 1;
        for (int i = 0; i < names.length; i++) {
            final int pick = i;
            boolean on = i == current;
            TextView option = text(names[i], names.length > 4 ? 12 : 13, on ? onAccent : ink, true);
            option.setGravity(Gravity.CENTER);
            option.setSingleLine(true);
            float outer = 20, inner = on ? 20 : 6;
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(on ? accent : surfHi);
            shape.setCornerRadii(new float[]{dp(i == 0 ? outer : inner), dp(i == 0 ? outer : inner), dp(i == last ? outer : inner), dp(i == last ? outer : inner),
                dp(i == last ? outer : inner), dp(i == last ? outer : inner), dp(i == 0 ? outer : inner), dp(i == 0 ? outer : inner)});
            option.setBackground(shape);
            colored(option, () -> { option.setTextColor(on ? onAccent : ink); shape.setColor(on ? accent : surfHi); });
            tappable(option, name + ": " + names[i], () -> onPick.picked(pick));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
            if (i > 0) p.leftMargin = dp(3);
            group.addView(option, p);
        }
        box.addView(group, fill(-2));
    }

    /** Accent swatches: used when the cover can’t colour the app (or Artwork colours is off). */
    private void accentRow(LinearLayout box) {
        TextView label = text("Accent colour", 16, () -> ink, true);
        label.setPadding(dp(18), dp(10), dp(18), dp(2));
        box.addView(label);
        TextView hint = text("Used when Artwork colours is off or the cover has none", 13, () -> inkDim, false);
        hint.setPadding(dp(18), 0, dp(18), dp(8));
        box.addView(hint);
        LinearLayout swatches = row();
        swatches.setPadding(dp(12), 0, dp(12), dp(12));
        int chosen = prefs.getInt("accent", 0);
        for (int i = 0; i < ACCENTS.length; i++) {
            final int pick = i;
            View dot = new View(activity) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c) {
                    int colour = pick == 0 ? blend(surfHi, accent, .6f) : ACCENTS[pick];
                    float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(5);
                    if (pick == chosen) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(dp(2.5f));
                        paint.setColor(ink);
                        c.drawCircle(cx, cy, r + dp(2), paint);
                        r -= dp(2);
                    }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(colour);
                    c.drawCircle(cx, cy, r, paint);
                }
            };
            tappable(dot, ACCENT_NAMES[i] + " accent", () -> { prefs.edit().putInt("accent", pick).apply(); settingsChanged(true); });
            swatches.addView(dot, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        box.addView(swatches, fill(-2));
    }

    /** Shows how much artwork is stored on the phone and clears it when tapped. */
    private void cacheRow(LinearLayout box) {
        LinearLayout line = row();
        line.setPadding(dp(18), dp(12), dp(16), dp(12));
        LinearLayout words = column();
        words.addView(text("Artwork cache", 16, () -> ink, true));
        TextView size = text("Counting…", 13, () -> inkDim, false);
        size.setPadding(0, dp(2), 0, 0);
        words.addView(size);
        line.addView(words, weight());
        TextView clear = text("Clear", 14, onAccent, true);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable clearBg = round(accent, 20);
        clear.setBackground(clearBg);
        colored(clear, () -> { clear.setTextColor(onAccent); clearBg.setColor(accent); });
        line.addView(clear, lp(-2, dp(40)));
        box.addView(line, fill(-2));
        submit(PLAYLISTS, () -> {
            long bytes = 0;
            java.io.File[] files = CoreBridge.cacheDir == null ? null : CoreBridge.cacheDir.listFiles();
            if (files != null) for (java.io.File f : files) bytes += f.length();
            final String text = bytes < 1024 * 1024 ? (bytes / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
            post(() -> size.setText(text + " of saved covers; they download again when needed"));
        });
        tappable(clear, "Clear the artwork cache", () -> submit(PLAYLISTS, () -> {
            java.io.File[] files = CoreBridge.cacheDir == null ? null : CoreBridge.cacheDir.listFiles();
            if (files != null) for (java.io.File f : files) f.delete();
            post(() -> {
                IMAGES.evictAll();
                failed.clear();
                size.setText("0 KB of saved covers; they download again when needed");
                Toast.makeText(activity, "Artwork cache cleared", Toast.LENGTH_SHORT).show();
                invalidateArt(SpatchUi.this);
            });
        }));
    }

    LinearLayout card(LinearLayout page, String title) {
        TextView label = text(title, 14, () -> accent, true);
        label.setPadding(dp(6), dp(18), 0, dp(8));
        page.addView(label);
        LinearLayout box = column();
        bgRound(box, 26, () -> surf);
        box.setPadding(0, dp(6), 0, dp(6));
        page.addView(box, fill(-2));
        return box;
    }

    void settingsAction(LinearLayout box, String title, String detail, Runnable action) {
        LinearLayout line = row();
        line.setPadding(dp(18), dp(12), dp(16), dp(12));
        LinearLayout words = column();
        words.addView(text(title, 16, () -> ink, true));
        TextView hint = text(detail, 13, () -> inkDim, false);
        hint.setPadding(0, dp(2), dp(8), 0);
        words.addView(hint);
        line.addView(words, weight());
        line.addView(icon("chevron", () -> inkDim), lp(dp(24), dp(24)));
        tappable(line, title, action);
        box.addView(line, fill(-2));
    }

    private static String db(int gain) { return (gain > 0 ? "+" : "") + gain + " dB"; }

    void toggle(LinearLayout box, String name, String detail, String key, boolean fallback, boolean rebuild) {
        LinearLayout line = row();
        line.setPadding(dp(18), dp(12), dp(16), dp(12));
        LinearLayout words = column();
        words.addView(text(name, 16, () -> ink, true));
        TextView d = text(detail, 13, () -> inkDim, false);
        d.setPadding(0, dp(2), dp(8), 0);
        words.addView(d);
        line.addView(words, weight());
        Switch sw = new Switch(pref(key, fallback));
        line.addView(sw, lp(dp(52), dp(32)));
        if (android.os.Build.VERSION.SDK_INT >= 30) line.setStateDescription(sw.on ? "On" : "Off");
        tappable(line, name, () -> {
            sw.set(!sw.on);
            prefs.edit().putBoolean(key, sw.on).apply();
            if (android.os.Build.VERSION.SDK_INT >= 30) line.setStateDescription(sw.on ? "On" : "Off");
            if (key.equals("equalizer") || key.startsWith("eq_")) Audio.refreshEqualizer();
            if (key.equals("auto_hide_dock") && !sw.on) setDockHidden(false);
            if (key.equals("audio") && sw.on && !Audio.permitted(activity)) activity.requestPermissions(new String[]{Audio.PERMISSION}, 7341);
            if (key.equals("art_colors")) { paletteKey = "?"; refreshPalette(); }
            else if (key.equals("motes") || key.equals("backdrop")) settingsChanged(false);
            else if (rebuild) { int y = pageScroll != null ? pageScroll.getScrollY() : 0; build(); keepScroll(pageScroll, y); }
        });
        box.addView(line, fill(-2));
    }

    private final class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        boolean on;
        private float t;
        Switch(boolean on) { super(activity); this.on = on; t = on ? 1 : 0; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        private android.animation.ValueAnimator anim;
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
        }
        void set(boolean v) {
            if (v == on) return;
            on = v;
            if (anim != null) anim.cancel(); // a quick double toggle must not leave two animators fighting over t
            if (!motion()) { t = v ? 1 : 0; invalidate(); return; }
            android.animation.ValueAnimator a = anim = android.animation.ValueAnimator.ofFloat(t, v ? 1 : 0);
            a.setDuration(260);
            a.setInterpolator(new android.view.animation.OvershootInterpolator(1.3f));
            a.addUpdateListener(f -> { t = (float) f.getAnimatedValue(); invalidate(); });
            a.start();
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2, k = Math.max(0, Math.min(1, t));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(surfHi, accent, k));
            c.drawRoundRect(0, 0, w, h, r, r, paint);
            paint.setColor(blend(inkDim, onAccent, k));
            float cx = r + (w - 2 * r) * t;
            c.drawCircle(cx, r, dp(8) + dp(4) * t, paint);
            // Material 3's check inside the thumb, fading in as it slides on.
            if (k > .05f) {
                float size = dp(12);
                c.save();
                c.translate(cx - size / 2, r - size / 2);
                drawGlyph(c, paint, check, "check", (accent & 0x00ffffff) | (Math.round(255 * k) << 24), size);
                c.restore();
            }
        }
    }

    // ================= sheets =================

    interface Choice { void picked(int index); }

    /** A sheet choice: a label, an optional second line, a cover (image url) or icon, and whether it's the current one. */
    private static final class Option {
        final String label, detail, image, glyph;
        final boolean selected;
        Option(String label, String detail, String image, String glyph, boolean selected) {
            this.label = label; this.detail = detail; this.image = image; this.glyph = glyph; this.selected = selected;
        }
    }

    /** A view shown under the next sheet's note, then forgotten (the devices sheet's volume bar). */
    private View sheetExtra;

    /** The volume of the device that's playing: a track you drag, with a speaker and the percentage. */
    private final class VolumeBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path glyph = new Path();
        private float value;
        private boolean dragging;
        private long sentAt;
        private boolean failed;

        VolumeBar(int level) {
            super(activity);
            value = Math.max(0, Math.min(1, level / 65535f));
            setContentDescription("Volume");
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), dp(52)); }

        private float left() { return dp(40); }
        private float right() { return getWidth() - dp(52); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), cy = getHeight() / 2f, x0 = left(), x1 = right(), x = x0 + (x1 - x0) * value;
            c.save();
            c.translate(dp(4), cy - dp(11));
            drawGlyph(c, paint, glyph, "volume", ink, dp(22));
            c.restore();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((ink & 0x00ffffff) | 0x30000000);
            c.drawRoundRect(x0, cy - dp(3), x1, cy + dp(3), dp(3), dp(3), paint);
            paint.setColor(accent);
            c.drawRoundRect(x0, cy - dp(3), Math.max(x0 + dp(3), x), cy + dp(3), dp(3), dp(3), paint);
            c.drawCircle(x, cy, dragging ? dp(13) : dp(10), paint);
            paint.setColor(inkDim);
            paint.setTextSize(dp(13));
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Math.round(value * 100) + "%", w - dp(2), cy + dp(5), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) { dragging = true; getParent().requestDisallowInterceptTouchEvent(true); }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                value = Math.max(0, Math.min(1, (e.getX() - left()) / Math.max(1, right() - left())));
                long now = SystemClock.uptimeMillis();
                if (action == MotionEvent.ACTION_UP || now - sentAt > 120) { sentAt = now; send(); }
                if (action == MotionEvent.ACTION_UP) dragging = false;
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) { dragging = false; invalidate(); return true; }
            return false;
        }

        /** Sent in order, a little apart while dragging; the last position always goes. */
        private void send() {
            final int level = Math.round(value * 65535);
            submit(COMMANDS, () -> {
                String error = nativeSetVolume(level);
                if (error != null && !error.isEmpty() && !failed) {
                    failed = true;
                    post(() -> Toast.makeText(activity, "Couldn’t change the volume: " + error, Toast.LENGTH_SHORT).show());
                }
            });
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
        scrim.addView(card, new LayoutParams(-1, -2, Gravity.BOTTOM));
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

    // ================= player =================

    private void openPlayer(boolean animate) {
        if (playerOpen) return;
        playerOpen = true;
        buildPlayer();
        addView(player, new LayoutParams(-1, -1));
        if (pref("keep_on", true)) activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refresh();
        if (animate && motion()) {
            player.setTranslationY(getHeight() > 0 ? getHeight() : dp(800));
            player.animate().translationY(0).setDuration(440).setInterpolator(EMPHASIZED).start();
            // Backdrop first, then each row of the player rises into place a beat after the one above it.
            backdrop.setAlpha(0f);
            backdrop.animate().alpha(1f).setStartDelay(120).setDuration(500).start();
            ViewGroup col = (ViewGroup) player.getChildAt(player.getChildCount() - 1);
            for (int i = 0; i < col.getChildCount(); i++) {
                View row = col.getChildAt(i);
                row.setAlpha(0f);
                row.setTranslationY(dp(28));
                row.animate().alpha(1f).translationY(0).setStartDelay(140 + i * 55L).setDuration(420).setInterpolator(EMPHASIZED).start();
            }
        }
    }

    private void closePlayer() {
        if (!playerOpen) return;
        stopHeldSeek();
        if (wave != null && wave.preview >= 0) {
            float from = shownProgress();
            wave.cancelScrub();
            animateProgress(from);
        }
        playerOpen = false;
        setDockHidden(false);
        activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View p = player;
        if (motion()) p.animate().translationY(getHeight()).setDuration(300).setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f)).withEndAction(() -> removeView(p)).start();
        else removeView(p);
        player = null;
        disc = null; wave = null; backdrop = null; motes = null; nextPill = null;
        pTitle = pArtist = pSource = pBadge = pQuality = pSpeed = null;
        pLike = pShuffle = pRepeat = pSparkle = null;
        pPlay = null;
        nextKey = "";
    }

    private void buildPlayer() {
        player = new FrameLayout(activity);
        player.setClickable(true);
        backdrop = artwork.new Backdrop();
        player.addView(backdrop, new LayoutParams(-1, -1));
        motes = pref("motes", true) ? artwork.new Motes() : null;
        if (motes != null) player.addView(motes, new LayoutParams(-1, -1));
        LinearLayout col = column();
        col.setPadding(dp(18) + inset.left, dp(6) + inset.top, dp(18) + inset.right, dp(14) + inset.bottom);
        player.addView(col, new LayoutParams(-1, -1));

        LinearLayout top = row();
        top.addView(iconButton(icon("down", () -> ink), "Close player", this::back), lp(dp(48), dp(48)));
        LinearLayout from = column();
        TextView cap = text("PLAYING FROM", 11, () -> inkDim, true);
        cap.setLetterSpacing(.12f);
        cap.setGravity(Gravity.CENTER);
        from.addView(cap, fill(-2));
        pSource = text("", 15, () -> ink, true);
        pSource.setGravity(Gravity.CENTER);
        oneLine(pSource);
        from.addView(pSource, fill(-2));
        top.addView(from, weight());
        // The timer icon carries a small badge with the minutes left while a sleep timer runs.
        FrameLayout timerBox = new FrameLayout(activity);
        timerBox.setClipChildren(false);
        timerBox.addView(iconButton(icon("timer", () -> sleepAt > SystemClock.uptimeMillis() ? accent : ink), "Sleep timer", this::openSleepTimer), new LayoutParams(-1, -1));
        TextView left = text("", 10, () -> onAccent, true);
        left.setGravity(Gravity.CENTER);
        left.setPadding(dp(4), 0, dp(4), 0);
        bgRound(left, 8, () -> accent);
        timerBox.addView(left, new LayoutParams(-2, dp(16), Gravity.BOTTOM | Gravity.END));
        sleepBadge = new Runnable() {
            @Override public void run() {
                left.removeCallbacks(this);
                long ms = sleepAt - SystemClock.uptimeMillis();
                left.setVisibility(ms > 0 ? VISIBLE : GONE);
                recolorView(timerBox);
                if (ms <= 0) return;
                left.setText(Math.max(1, (ms + 59_999) / 60_000) + "m");
                left.postDelayed(this, 15_000);
            }
        };
        sleepBadge.run();
        // Playback speed, for podcasts: a small chip that opens the choices.
        pSpeed = text("1×", 13, () -> ink, true);
        pSpeed.setGravity(Gravity.CENTER);
        pSpeed.setPadding(dp(12), 0, dp(12), 0);
        bgRound(pSpeed, 16, () -> surfHi);
        tappable(pSpeed, "Playback speed", this::openSpeed);
        pSpeed.setVisibility(GONE);
        LinearLayout.LayoutParams speedParams = lp(-2, dp(32));
        speedParams.rightMargin = dp(6);
        top.addView(pSpeed, speedParams);
        top.addView(timerBox, lp(dp(48), dp(48)));
        top.addView(iconButton(icon("devices", () -> ink), "Play on another device", this::openDevices), lp(dp(48), dp(48)));
        top.addView(iconButton(icon("queue", () -> ink), "Up next", this::openQueue), lp(dp(48), dp(48)));
        col.addView(top, fill(-2));

        disc = artwork.new Disc();
        col.addView(disc, new LinearLayout.LayoutParams(-1, 0, 1));
        gestures(disc);

        LinearLayout titleRow = row();
        LinearLayout words = column();
        pTitle = text("", 28, () -> ink, true);
        marquee(pTitle);
        pTitle.setOnLongClickListener(v -> share());
        // The title opens the song's album, the artist line its artist, as in Spotify's player.
        pTitle.setOnClickListener(v -> openFromPlayer(albumUri, album, artist));
        words.addView(pTitle, fill(-2));
        LinearLayout byline = row();
        pBadge = text("E", 11, bg, true);
        pBadge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = round(inkDim, 4);
        pBadge.setBackground(badgeBg);
        colored(pBadge, () -> { pBadge.setTextColor(bg); badgeBg.setColor(inkDim); });
        LinearLayout.LayoutParams bp = lp(dp(17), dp(17));
        bp.rightMargin = dp(7);
        byline.addView(pBadge, bp);
        pArtist = text("", 17, () -> inkDim, false);
        pArtist.setOnClickListener(v -> openFromPlayer(artistUri, artist, "Artist"));
        oneLine(pArtist);
        byline.addView(pArtist, weight());
        LinearLayout.LayoutParams byp = fill(-2);
        byp.topMargin = dp(4);
        words.addView(byline, byp);
        // What Spotify says is playing. The line always keeps its space, so the layout never shifts when it arrives.
        pQuality = text("", 12, () -> accent, true);
        pQuality.setLetterSpacing(.04f);
        pQuality.setSingleLine(true);
        pQuality.setText(" ");
        pQuality.setVisibility(INVISIBLE);
        LinearLayout.LayoutParams qp = lp(-2, -2);
        qp.topMargin = dp(6);
        words.addView(pQuality, qp);
        titleRow.addView(words, weight());
        pLike = new Icon("heart-outline", ink);
        colored(pLike, () -> {
            boolean isLiked = Boolean.TRUE.equals(saved);
            pLike.symbol = isLiked ? "heart" : "heart-outline";
            pLike.color = isLiked ? accent : ink;
            pLike.invalidate();
        });
        titleRow.addView(iconButton(pLike, "Save to Liked Songs", this::toggleSaved), lp(dp(52), dp(52)));
        col.addView(titleRow, fill(-2));

        wave = new Wave();
        LinearLayout.LayoutParams wp = fill(dp(58));
        wp.topMargin = dp(6);
        col.addView(wave, wp);
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        pShuffle = new Icon("shuffle", inkDim);
        colored(pShuffle, () -> { pShuffle.color = shuffle ? accent : inkDim; pShuffle.invalidate(); });
        pRepeat = new Icon("repeat", inkDim);
        colored(pRepeat, () -> { pRepeat.symbol = repeat == 2 ? "repeat-one" : "repeat"; pRepeat.color = repeat != 0 ? accent : inkDim; pRepeat.invalidate(); });
        // Smart Shuffle shows as a small sparkle on the shuffle icon.
        FrameLayout shuffleBox = new FrameLayout(activity);
        shuffleBox.addView(iconButton(pShuffle, "Shuffle: off, on or Smart Shuffle", this::toggleShuffle), new LayoutParams(-1, -1));
        pSparkle = new Icon("sparkle", accent);
        colored(pSparkle, () -> { pSparkle.color = accent; pSparkle.setVisibility(shuffle && smartShuffle ? VISIBLE : GONE); pSparkle.invalidate(); });
        LayoutParams sp = new LayoutParams(dp(11), dp(11), Gravity.TOP | Gravity.END);
        sp.setMargins(0, dp(11), dp(9), 0);
        shuffleBox.addView(pSparkle, sp);
        controls.addView(shuffleBox, lp(dp(52), dp(52)));
        controls.addView(skip(holdToSeek(iconButton(icon("previous", () -> ink), "Previous, hold to rewind", this::previousTrack), false), false), lp(dp(60), dp(60)));
        pPlay = new PlayButton();
        controls.addView(pPlay, lp(dp(86), dp(86)));
        controls.addView(skip(holdToSeek(iconButton(icon("next", () -> ink), "Next, hold to fast forward", () -> command(NEXT, 0, "")), true), true), lp(dp(60), dp(60)));
        controls.addView(iconButton(pRepeat, "Repeat", this::cycleRepeat), lp(dp(52), dp(52)));
        col.addView(controls, fill(-2));

        nextPill = row();
        nextPill.setPadding(dp(6), dp(6), dp(16), dp(6));
        bgRound(nextPill, 24, () -> (ink & 0x00ffffff) | 0x1a000000);
        LinearLayout.LayoutParams np = fill(-2);
        np.topMargin = dp(12);
        col.addView(nextPill, np);
        tappable(nextPill, "Up next", this::openQueue);
    }

    /** Tap skips; holding the button jumps ten seconds at a time until it is released. */
    private View skip(View button, boolean next) {
        // Previous also restarts the song, which Spotify allows even when skipping back isn't, so only Next dims.
        if (!next) return button;
        skipButtons.put(button, true);
        boolean allowed = next ? canNext : canPrevious;
        button.setEnabled(allowed);
        button.setAlpha(allowed ? 1f : .35f);
        return button;
    }

    private View holdToSeek(View button, boolean forward) {
        boolean[] held = new boolean[1];
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (!held[0] || duration <= 0 || !button.isAttachedToWindow()) return;
            long jump = new long[]{5_000, 10_000, 30_000}[Math.max(0, Math.min(2, prefs.getInt("seek_step", 1)))];
            long target = Math.max(0, Math.min(duration - 1500, playhead() + (forward ? jump : -jump)));
            setPosition(target);
            command(SEEK, target, "");
            tick(button);
            button.postDelayed(step[0], 480);
        };
        button.setOnLongClickListener(v -> {
            stopHeldSeek();
            held[0] = true;
            holdingSeek = true;
            cancelHeldSeek = () -> { held[0] = false; button.removeCallbacks(step[0]); };
            step[0].run();
            invalidateProgress();
            return true;
        });
        // iconButton installs its own touch listener for the press animation; wrap it so releasing still ends the hold.
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) v.animate().scaleX(.96f).scaleY(.96f).setDuration(90).start();
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    if (held[0]) stopHeldSeek();
                    v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                }
                return false;
            }
        });
        return button;
    }

    View iconButton(Icon icon, String label, Runnable action) {
        FrameLayout box = new FrameLayout(activity);
        box.addView(icon, new LayoutParams(dp(24), dp(24), Gravity.CENTER));
        tappable(box, label, action);
        return box;
    }

    /** On the disc: pull down to close, flick sideways to skip, double tap to play or pause. */
    private void gestures(View v) {
        float[] start = new float[2];
        boolean[] drag = new boolean[1], menuOpened = new boolean[1];
        v.setContentDescription("Artwork, hold for song options");
        v.setOnLongClickListener(view -> {
            if (uri.isEmpty()) return false;
            menuOpened[0] = true;
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            currentSongActions();
            return true;
        });
        android.view.GestureDetector taps = new android.view.GestureDetector(activity, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) { if (!drag[0]) v.performLongClick(); }
            @Override public boolean onDoubleTap(MotionEvent e) { if (pref("double_tap", true)) togglePlay(); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || !pref("swipe_skip", true) || Math.abs(b.getX() - a.getX()) < dp(60) || Math.abs(b.getX() - a.getX()) < Math.abs(b.getY() - a.getY())) return false;
                if (b.getX() < a.getX()) command(NEXT, 0, ""); else previousTrack();
                tick(v);
                return true;
            }
        });
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) menuOpened[0] = false;
            if (menuOpened[0]) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) taps.onTouchEvent(e);
                return true;
            }
            if (player != null && motion()) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: start[0] = e.getRawX(); start[1] = e.getRawY(); drag[0] = false; break;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = e.getRawY() - start[1], dx = e.getRawX() - start[0];
                        if (!drag[0] && dy > dp(16) && dy > Math.abs(dx) * 1.4f) {
                            drag[0] = true;
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                            MotionEvent cancel = MotionEvent.obtain(e);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            taps.onTouchEvent(cancel);
                            cancel.recycle();
                        }
                        if (drag[0]) { player.setTranslationY(Math.max(0, dy) * .8f); return true; }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (drag[0]) {
                            drag[0] = false;
                            if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getRawY() - start[1] > dp(140)) back();
                            else player.animate().translationY(0).setDuration(300).setInterpolator(new android.view.animation.OvershootInterpolator(.8f)).start();
                            return true;
                        }
                        break;
                }
            }
            taps.onTouchEvent(e);
            return true;
        });
    }

    private void togglePlay() {
        if (uri.isEmpty() || pending != null) return;
        boolean want = !playing;
        pending = want;
        refresh();
        submit(COMMANDS, () -> {
            boolean ok = playerCommand(want ? RESUME : PAUSE, 0, "");
            post(() -> {
                if (!ok) { pending = null; refresh(); }
                else postDelayed(() -> { if (pending != null && pending == want) { pending = null; refresh(); } }, 2500);
            });
        });
    }

    private static boolean isPlaylistLink(String u) {
        return u.startsWith("spotify:playlist:") || (u.startsWith("spotify:user:") && u.contains(":playlist:"));
    }

    /** Off → Shuffle → Smart Shuffle (playlists only) → Off, as Spotify's own shuffle button cycles. */
    private void toggleShuffle() {
        boolean before = shuffle, beforeSmart = smartShuffle;
        int mode = !shuffle ? 1 : !smartShuffle && isPlaylistLink(context) ? 2 : 0;
        boolean want = mode > 0;
        shuffle = want;
        smartShuffle = mode == 2;
        refresh();
        String list = context;
        submit(COMMANDS, () -> {
            boolean ok = playerCommand(SHUFFLE, want ? 1 : 0, "");
            if (ok && isPlaylistLink(list)) {
                String error = nativeSetShuffleMode(list, mode);
                if (error != null && !error.isEmpty()) android.util.Log.w("SpatchUi", "Shuffle mode: " + error);
            }
            if (!ok) post(() -> {
                if (shuffle == want) { shuffle = before; smartShuffle = beforeSmart; refresh(); }
                if (isAttachedToWindow()) Toast.makeText(activity, playbackFailure(), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void cycleRepeat() {
        int before = repeat, want = (before + 1) % 3;
        repeat = want;
        refresh();
        submit(COMMANDS, () -> {
            if (!playerCommand(REPEAT, want, "")) post(() -> {
                if (repeat == want) { repeat = before; refresh(); }
                if (isAttachedToWindow()) Toast.makeText(activity, playbackFailure(), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private FrameLayout queuePanel;
    private ScrollView queueScroll;

    private void closeQueue() {
        if (queuePanel != null) removeView(queuePanel);
        queuePanel = null;
        queueScroll = null;
    }

    private void openQueue() {
        hideKeyboard();
        if (queuePanel == null) {
            queuePanel = new FrameLayout(activity);
            queuePanel.setClickable(true);
            addView(queuePanel, new LayoutParams(-1, -1));
        }
        renderQueue();
    }

    private void renderQueue() {
        int offset = queueScroll == null ? 0 : queueScroll.getScrollY();
        queuePanel.removeAllViews();
        queuePanel.setBackgroundColor(bg);
        LinearLayout body = column();
        body.setPadding(dp(18), inset.top, dp(18), inset.bottom);
        LinearLayout header = row();
        header.addView(iconButton(icon("back", () -> ink), "Close queue", this::closeQueue), lp(dp(48), dp(48)));
        header.addView(text("Queue", 30, () -> ink, true), weight());
        body.addView(header, fill(dp(64)));
        queueScroll = new ScrollView(activity);
        LinearLayout tracks = column();
        tracks.setPadding(0, dp(12), 0, dp(24));
        tracks.addView(text("Now playing", 14, () -> accent, true), fill(-2));
        tracks.addView(text(title.isEmpty() ? "Nothing playing" : title, 22, () -> ink, true), fill(-2));
        tracks.addView(text(artist, 14, () -> inkDim, false), fill(-2));
        tracks.addView(text(source.isEmpty() ? "Up next" : "Next from " + source, 16, () -> accent, true), fill(dp(48)));
        for (int i = 0; i < upcoming.size(); i++) {
            final int index = i;
            MediaEntry entry = upcoming.get(i);
            LinearLayout line = row();
            Row song = new Row();
            song.bind(entry);
            song.setOnClickListener(v -> command(SKIP_TO, index, entry.queueKey));
            line.addView(song, weight());
            TextView options = pill("⋮", () -> queueActions(index, entry));
            options.setContentDescription("Queue options for " + entry.title);
            line.addView(options, lp(dp(48), dp(48)));
            song.setOnLongClickListener(v -> { queueActions(index, entry); return true; });
            tracks.addView(line, fill(-2));
        }
        if (upcoming.isEmpty()) tracks.addView(text("Nothing queued after this", 16, () -> inkDim, false), fill(-2));
        queueScroll.addView(tracks, new ScrollView.LayoutParams(-1, -2));
        body.addView(queueScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        queuePanel.addView(body, new LayoutParams(-1, -1));
        keepScroll(queueScroll, offset);
    }

    private void queueActions(int index, MediaEntry entry) {
        List<String> options = new ArrayList<>();
        List<Integer> commands = new ArrayList<>();
        if (entry.editable && index > 0 && upcoming.get(index - 1).editable) { options.add("Move up"); commands.add(11); }
        if (entry.editable && index + 1 < upcoming.size() && upcoming.get(index + 1).editable) { options.add("Move down"); commands.add(12); }
        options.add("Add to playlist"); commands.add(-1);
        options.add(downloadLabel(entry.uri)); commands.add(-2);
        if (entry.editable) { options.add("Remove from queue"); commands.add(10); }
        sheet(entry.title, entry.subtitle, options.toArray(new String[0]), choice -> { if (commands.get(choice) == -1) addToPlaylist(entry); else if (commands.get(choice) == -2) downloadTapped(entry); else command(commands.get(choice), index, entry.queueKey); });
    }


    // Sleep timer: pauses playback later. Kept on the main looper, not in a view, so it survives page changes.
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static long sleepAt;
    private static volatile long accountGeneration;
    private static final Runnable SLEEP = () -> {
        sleepAt = 0;
        long generation = accountGeneration;
        COMMANDS.execute(() -> { if (generation == accountGeneration) playerCommand(PAUSE, 0, ""); });
    };

    /** Set when Spotify's login opens: the next screen drops the saved profile so another account never shows it. */
    private static volatile boolean forgetProfile;

    static void resetAccount() {
        forgetProfile = true;
        accountGeneration++;
        sleepAt = 0;
        MAIN.removeCallbacks(SLEEP);
    }

    private static String speedLabel(float v) {
        return (Math.abs(v - Math.round(v)) < .01f ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%s", Float.toString(Math.round(v * 100) / 100f))) + "×";
    }

    private void openSpeed() {
        float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f};
        Option[] options = new Option[speeds.length];
        for (int i = 0; i < speeds.length; i++) options[i] = new Option(speedLabel(speeds[i]) + (speeds[i] == 1f ? "  (normal)" : ""), "", "", "play", Math.abs(speed - speeds[i]) < .01f);
        sheet("Playback speed", "For podcasts and audiobooks", options, c -> {
            speed = speeds[c];
            if (pSpeed != null) pSpeed.setText(speedLabel(speed));
            command(SPEED, Math.round(speeds[c] * 100), "");
        });
    }

    private int sleepChoice;
    private Runnable sleepBadge;

    private void openSleepTimer() {
        String[] names = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "End of this track"};
        long left = sleepAt - SystemClock.uptimeMillis();
        String note = left > 0 ? "Pausing in " + Math.max(1, (left + 59_999) / 60_000) + " min" : "Pause playback later";
        int current = left > 0 ? sleepChoice : 0;
        Option[] options = new Option[names.length];
        for (int i = 0; i < names.length; i++) options[i] = new Option(names[i], "", "", i == 0 ? "close" : "timer", i == current);
        sheet("Sleep timer", note, options, choice -> {
            sleepChoice = choice;
            MAIN.removeCallbacks(SLEEP);
            long delay = choice == 0 ? 0 : choice == 5 ? Math.max(1000, duration - playhead() - 500) : new long[]{0, 15, 30, 45, 60}[choice] * 60_000L;
            sleepAt = choice == 0 ? 0 : SystemClock.uptimeMillis() + delay;
            if (choice != 0) MAIN.postDelayed(SLEEP, delay);
            if (sleepBadge != null) sleepBadge.run();
            Toast.makeText(activity, choice == 0 ? "Sleep timer off" : "Pausing " + (choice == 5 ? "after this track" : "in " + names[choice]), Toast.LENGTH_SHORT).show();
        });
    }

    /** Lists the devices Spotify can play on and moves playback to the one you pick. */
    private void openDevices() {
        if (OfficialPlayback.enabled()) {
            Toast.makeText(activity, "Choose playback devices in official Spotify", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Looking for devices…", Toast.LENGTH_SHORT).show();
        submit(PLAYLISTS, () -> {
            String[] f = nativeDevices();
            final int level = nativeVolume();
            post(() -> {
                if (f == null || f.length < 5) { Toast.makeText(activity, "No devices found", Toast.LENGTH_SHORT).show(); return; }
                if (level >= 0) sheetExtra = new VolumeBar(level);
                int count = f.length / 5;
                Option[] devices = new Option[count];
                for (int i = 0; i < count; i++) {
                    boolean active = Boolean.parseBoolean(f[i * 5 + 3]), here = Boolean.parseBoolean(f[i * 5 + 4]);
                    String kind = f[i * 5 + 1];
                    String detail = (here ? "This phone" : kind) + (active ? " • Playing" : "");
                    devices[i] = new Option(f[i * 5], detail, "", here ? "devices" : "volume", active);
                }
                sheet("Play on", "Pick where your music should play", devices, i -> {
                    String id = f[i * 5 + 2];
                    submit(PLAYLISTS, () -> {
                        String error = nativeTransfer(id);
                        post(() -> Toast.makeText(activity, error == null || error.isEmpty() ? "Playing on " + f[i * 5] : "Couldn’t switch: " + error, Toast.LENGTH_LONG).show());
                    });
                });
            });
        });
    }

    private boolean share() {
        for (String kind : new String[]{"track", "episode"}) {
            String prefix = "spotify:" + kind + ":";
            if (!uri.startsWith(prefix)) continue;
            String link = "https://open.spotify.com/" + kind + "/" + uri.substring(prefix.length());
            sheet(title, artist, new String[]{"Share song link", "Copy song link"}, c -> {
                if (c == 0) {
                    android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(android.content.Intent.EXTRA_TEXT, link);
                    activity.startActivity(android.content.Intent.createChooser(send, title));
                } else {
                    android.content.ClipboardManager clip = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText(title, link));
                }
            });
            return true;
        }
        return false;
    }

    // ---------- like ----------

    private void checkSaved(String track) {
        int request = ++savedRequest;
        savedUri = track;
        saved = null;
        if (!track.startsWith("spotify:track:") && !track.startsWith("spotify:episode:") && !track.startsWith("spotify:preview:")) return;
        submit(DATA, () -> {
            int a = nativeSaved(track);
            post(() -> { if (request == savedRequest && track.equals(savedUri)) { saved = a < 0 ? null : a == 1; refresh(); } });
        });
    }

    /** A like's little burst: a ring pops outward and six dots fly off it, fading, over the heart. */
    private void burst(View host) {
        final float[] t = {0f};
        final int ringColour = accent, dotColour = accent2;
        android.graphics.drawable.Drawable pop = new android.graphics.drawable.Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                float f = t[0], cx = host.getWidth() / 2f, cy = host.getHeight() / 2f, max = Math.min(cx, cy);
                int alpha = Math.round(255 * (1f - f));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f) * (1f - f) + 1);
                paint.setColor(ringColour);
                paint.setAlpha(alpha);
                c.drawCircle(cx, cy, max * (.35f + .6f * f), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(dotColour);
                paint.setAlpha(alpha);
                for (int i = 0; i < 6; i++) {
                    double a = Math.PI * 2 * i / 6 - Math.PI / 2;
                    float d = max * (.45f + .5f * f);
                    c.drawCircle(cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, dp(2.6f) * (1f - f * .6f), paint);
                }
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
        pop.setBounds(0, 0, host.getWidth(), host.getHeight());
        host.getOverlay().add(pop);
        android.animation.ValueAnimator run = android.animation.ValueAnimator.ofFloat(0f, 1f);
        run.setDuration(520);
        run.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        run.addUpdateListener(a -> { t[0] = (float) a.getAnimatedValue(); pop.invalidateSelf(); host.invalidate(); });
        run.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { host.getOverlay().remove(pop); }
        });
        run.start();
    }

    private void openFromPlayer(String link, String name, String detail) {
        if (link.isEmpty()) return;
        tick(player);
        closePlayer();
        openPlaylist(new MediaEntry(name, detail, link, link.startsWith("spotify:album:") ? image : "", -1));
    }

    private void toggleSaved() {
        if (!uri.isEmpty()) setLikedSong(new MediaEntry(title, artist, uri, image, -1), !Boolean.TRUE.equals(saved));
    }

    private void setLikedSong(MediaEntry song, boolean want) {
        String track = song.uri;
        boolean current = track.equals(uri);
        Boolean before = current ? saved : song instanceof Song ? ((Song) song).liked : null;
        int request = current ? ++savedRequest : -1;
        if (current) { savedUri = track; saved = want; }
        if (song instanceof Song) ((Song) song).liked = want;
        refresh();
        if (want && current && motion() && pLike != null) {
            pLike.setScaleX(.5f);
            pLike.setScaleY(.5f);
            pLike.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(3.2f)).start();
            if (pLike.getParent() instanceof View) burst((View) pLike.getParent());
        }
        submit(COMMANDS, () -> {
            boolean ok = nativeSave(track, want);
            post(() -> {
                if (closed) return;
                if (!ok) {
                    if (request == savedRequest && track.equals(uri)) saved = before;
                    if (song instanceof Song && ((Song) song).liked == want) ((Song) song).liked = Boolean.TRUE.equals(before);
                }
                refresh();
                Toast.makeText(activity, !ok ? "Couldn’t update Liked Songs" : want ? "Added to Liked Songs" : "Removed from Liked Songs", Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ---------- refresh ----------

    /** Pushes the current state into whichever views exist. */
    private PlayButton listFab;
    /** The open list's shuffle mode as Spotify keeps it: -1 unknown, 0 off, 1 shuffle, 2 Smart Shuffle. */
    private int listShuffle = -1;

    /** The open.spotify.com link for a playlist, album, artist, show, song or episode; "" for others. */
    private static String shareLink(String u) {
        for (String kind : new String[]{"playlist", "album", "artist", "show", "track", "episode"}) {
            String prefix = "spotify:" + kind + ":";
            if (u.startsWith(prefix)) return "https://open.spotify.com/" + kind + "/" + u.substring(prefix.length());
            int at = u.indexOf(":" + kind + ":");
            if (u.startsWith("spotify:user:") && at > 0 && kind.equals("playlist")) return "https://open.spotify.com/playlist/" + u.substring(at + kind.length() + 2);
        }
        return "";
    }

    private void shareLinkSheet(String name, String link) {
        sheet(name, link, new Option[]{new Option("Share link", "", "", "share", false), new Option("Copy link", "", "", "check", false)}, c -> {
            if (c == 0) {
                android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(android.content.Intent.EXTRA_TEXT, link);
                activity.startActivity(android.content.Intent.createChooser(send, name));
            } else {
                android.content.ClipboardManager clip = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText(name, link));
                Toast.makeText(activity, "Link copied", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Whether the open playlist or page is what's playing. */
    private boolean listIsPlaying() {
        if (playlist == null || context.isEmpty()) return false;
        String list = playUri();
        return context.equals(list) || context.equals(playlist.uri) || (liked(context) && liked(playlist.uri));
    }

    private void refresh() {
        for (Bars bars : attachedBars) bars.invalidate();
        if (listFab != null && listFab.isAttachedToWindow()) listFab.set(listIsPlaying() && (pending == null ? playing : pending));
        String shown = title.isEmpty() ? "Nothing playing" : title;
        boolean on = pending == null ? playing : pending;
        if (mTitle != null) mTitle.setText(shown);
        if (mArtist != null) mArtist.setText(artist);
        if (mArt != null) { mArt.bind(image); mArt.invalidate(); }
        for (PlayButton b : playButtons) if (b != pPlay) b.set(on);
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
        if (pArtist != null) pArtist.setText(artist);
        if (pSource != null) pSource.setText(source.isEmpty() ? album : source);
        if (pBadge != null) pBadge.setVisibility(rating.isEmpty() ? GONE : VISIBLE);
        if (pSpeed != null) {
            // Spoken word only: demo songs stand in for episodes in the preview app.
            boolean spoken = uri.startsWith("spotify:episode:") || uri.startsWith("spotify:preview:");
            pSpeed.setVisibility(spoken ? VISIBLE : GONE);
            pSpeed.setText(speedLabel(speed));
        }
        if (pQuality != null) {
            String label = quality.toUpperCase(java.util.Locale.ROOT);
            if (!(label.isEmpty() ? " " : label).contentEquals(pQuality.getText())) {
                pQuality.setText(label.isEmpty() ? " " : label);
                pQuality.setVisibility(label.isEmpty() ? INVISIBLE : VISIBLE);
                if (!label.isEmpty() && motion()) { pQuality.setAlpha(0f); pQuality.animate().alpha(1f).setDuration(260).start(); }
            }
        }
        if (pPlay != null) pPlay.set(on);
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

    // ================= custom views =================

    final class Icon extends View {
        String symbol;
        int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Icon(String symbol, int color) { super(activity); this.symbol = symbol; this.color = color; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) { drawGlyph(c, paint, path, symbol, color, Math.min(getWidth(), getHeight())); }
    }

    /** Draws an icon (24-unit grid from Rust) into a `size` square at the canvas origin. */
    static void drawGlyph(Canvas canvas, Paint paint, Path path, String key, int color, float size) {
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
            canvas.drawPath(path, paint);
        }
        canvas.restore();
    }

    /** The scallop play button: five lobes at rest, nine while playing, turning slowly. */
    private final class PlayButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path scallop = new Path(), glyph = new Path();
        private final float[] five = nativeGeometry("button5"), nine = nativeGeometry("button9");
        private float shape, angle;
        private boolean on, started;
        private android.animation.ValueAnimator morph;

        PlayButton() { super(activity); playButtons.add(this); tappable(this, "Play or pause", SpatchUi.this::togglePlay); }

        @Override public boolean performClick() {
            Confetti.clicked(this, prefs, accent, accent2, on, motion());
            return super.performClick();
        }

        void set(boolean playingNow) {
            if (started && playingNow == on) return;
            started = true;
            on = playingNow;
            if (morph != null) morph.cancel();
            if (!motion()) { shape = on ? 1 : 0; invalidate(); return; }
            morph = android.animation.ValueAnimator.ofFloat(shape, on ? 1 : 0);
            morph.setDuration(300);
            morph.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f));
            morph.addUpdateListener(a -> { shape = (float) a.getAnimatedValue(); invalidate(); });
            morph.start();
        }

        @Override protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            c.save();
            c.translate(getWidth() / 2f, getHeight() / 2f);
            scallop.rewind();
            for (int i = 0; i < five.length; i += 2) {
                float x = (five[i] + (nine[i] - five[i]) * shape) * r, y = (five[i + 1] + (nine[i + 1] - five[i + 1]) * shape) * r;
                if (i == 0) scallop.moveTo(x, y); else scallop.lineTo(x, y);
            }
            scallop.close();
            // Buffering: the scallop spins faster and a bright arc chases round inside it until the audio arrives.
            if ((on || buffering) && motion()) { angle = (angle + (buffering ? 2.2f : .35f)) % 360f; c.rotate(angle); }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            c.drawPath(scallop, paint);
            if (buffering) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(r * .09f);
                paint.setColor((onAccent & 0x00ffffff) | 0x99000000);
                float ring = r * .72f;
                c.drawArc(-ring, -ring, ring, ring, angle * 2.5f, 100, false, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            c.restore();
            c.save();
            c.translate(getWidth() / 2f - r * .5f, getHeight() / 2f - r * .5f);
            drawGlyph(c, paint, glyph, on ? "pause" : "play", onAccent, r);
            c.restore();
            if ((on || buffering) && motion() && isShown()) postInvalidateOnAnimation();
        }
    }

    /** Three bouncing bars: this one is playing. */
    private final Set<Bars> attachedBars = new HashSet<>();

    private final class Bars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bars() { super(activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attachedBars.add(this); invalidate(); }
        @Override protected void onDetachedFromWindow() { attachedBars.remove(this); super.onDetachedFromWindow(); }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            boolean run = playing && motion();
            float t = run ? SystemClock.uptimeMillis() / 1000f : 0, h = getHeight(), bw = getWidth() / 5f;
            paint.setColor(accent);
            for (int i = 0; i < 3; i++) {
                float k = run ? .35f + .65f * Math.abs((float) Math.sin(t * (3.1f + i * 1.7f) + i * 1.3f)) : .3f + .2f * i;
                c.drawRoundRect(bw * i * 2, h * (1 - k), bw * i * 2 + bw, h, bw / 2, bw / 2, paint);
            }
            if (run && isShown()) postInvalidateOnAnimation();
        }
    }

    // ---------- artwork ----------

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
                    if (size == 440) ArtworkViews.cacheBlurs(key, got);
                    if (!SEEDS.containsKey(url)) SEEDS.put(url, seedOf(got));
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
                    if (playlist != null && (url.equals(playlist.image) || url.equals(detailImage))) applyColors();
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

    /** Progress eases across position changes and flattens while paused or seeking. */
    private final class Wave extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float preview = -1, amp = -1, grow = 0;
        private int lastTenth = -1;
        Wave() { super(activity); setContentDescription("Playback position"); }

        private void cancelScrub() {
            preview = -1;
            lastTenth = -1;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }

        @Override protected void onDraw(Canvas c) {
            long el = playhead();
            float f = shownProgress();
            float x0 = dp(6), x1 = getWidth() - dp(6), y = dp(20), tip = x0 + (x1 - x0) * f;
            // Scrubbing thickens the line; it eases back when you let go.
            float wantGrow = preview >= 0 ? 1f : 0f;
            grow = !motion() ? wantGrow : grow + (wantGrow - grow) * .25f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4 + 3 * grow));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor((ink & 0x00ffffff) | 0x2a000000);
            if (tip + dp(8) < x1) c.drawLine(tip + dp(8), y, x1, y, paint);
            float target = playing && preview < 0 && !holdingSeek ? dp(3.2f) : 0;
            amp = amp < 0 || !motion() ? target : amp + (target - amp) * .12f;
            paint.setColor(accent);
            path.rewind();
            float phase = (SystemClock.uptimeMillis() % 1600) / 1600f;
            for (float x = x0; x < tip; x += dp(2)) {
                float dy = amp * (float) Math.sin(((x - x0) / dp(34) + phase) * 6.283f);
                if (x == x0) path.moveTo(x, y + dy); else path.lineTo(x, y + dy);
            }
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(tip, y + amp * (float) Math.sin(((tip - x0) / dp(34) + phase) * 6.283f), dp(preview >= 0 ? 9 : 6), paint);
            paint.setTextSize(dp(12));
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(preview >= 0 ? accent : inkDim);
            paint.setFakeBoldText(preview >= 0);
            long shownTime = preview >= 0 ? Math.round(preview * (double) duration) : el;
            c.drawText(time(shownTime), x0, dp(50), paint);
            paint.setFakeBoldText(false);
            paint.setColor(inkDim);
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(duration > 0 ? "−" + time(Math.max(0, duration - shownTime)) : "—", x1, dp(50), paint);
            boolean settling = Math.abs(target - amp) > .05f || Math.abs(wantGrow - grow) > .01f;
            if ((playing || settling) && isShown()) { if (motion()) postInvalidateOnAnimation(); else postInvalidateDelayed(500); }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                float from = shownProgress();
                cancelScrub();
                animateProgress(from);
                return true;
            }
            if (duration <= 0) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopHeldSeek();
                    progress.cancel();
                    getParent().requestDisallowInterceptTouchEvent(true);
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (e.getActionMasked() != MotionEvent.ACTION_DOWN && preview < 0) return false;
                    preview = Math.max(0, Math.min(1, (e.getX() - dp(6)) / Math.max(1, getWidth() - dp(12))));
                    // A light tick each time the drag crosses a tenth of the song.
                    int tenth = (int) (preview * 10);
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) lastTenth = tenth;
                    else if (tenth != lastTenth) {
                        lastTenth = tenth;
                        if (pref("haptics", true)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        long target = Math.round(duration * (double) preview);
                        command(SEEK, target, "");
                        setPosition(target);
                        cancelScrub();
                    }
                    invalidateProgress();
                    return true;
                default: return false;
            }
        }
    }

}
