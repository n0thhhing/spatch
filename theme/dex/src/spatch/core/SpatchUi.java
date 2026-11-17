package spatch;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
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
 * Spatch's interface: a Swiftify-style player (round artwork inside a ring of bars, everything tinted by
 * the cover) with Home, Library and Settings behind a bottom bar. Plain Android views on the window's GPU;
 * the data comes from Rust (app/src/ui.rs).
 */
public final class SpatchUi extends FrameLayout {
    // ---- Rust (app/src/ui.rs, shapes.rs) ----
    private static native boolean nativeConnect();
    private static native String[] nativePlayer(long revision);
    private static native String nativeLibrary();
    private static native boolean nativeRefreshLibrary();
    private static native String[] nativeEntries(int filter, String query, boolean alphabetical, int limit);
    private static native String[] nativeRecent();
    private static native String nativeLiked();
    private static native String nativeLikedNote();
    private static native String[] nativePlaylist(String uri, int offset);
    private static native String[] nativePage(String uri);
    private static native String[] nativeDevices();
    private static native void nativeCacheDir(String dir);
    private static native long nativeLibraryRevision();
    private static native void nativeWatchPlaylist(String uri, String key);
    private static native void nativeStopPlaylist();
    private static native long nativePlaylistRevision();
    private static native String[] nativePlaylistRest(String uri, int offset);
    private static native int[] nativeArrange(String[] fields, String query, int sort);
    private static native boolean nativePlayFrom(String context, String track, int index);
    private static native String[] nativeCached(String key);
    private static native void nativeRemember(String key, String[] fields);
    private static native int[] nativePalette(int seed, boolean hasSeed, boolean dark);
    private static native int nativeSeed(int[] pixels);
    private static native void nativeRing(int perSide, float[] out);
    private static native String[] nativeSpotifySettings();
    private static native String nativeSetSpotifySetting(String key, int value);
    private static native String nativeTransfer(String id);
    private static native boolean nativeCommand(int action, long value, String uri);
    private static native int nativeSaved(String uri);
    private static native boolean nativeSave(String uri, boolean save);
    private static native float[] nativeGeometry(String kind);

    private static final int PAUSE = 0, RESUME = 1, PREVIOUS = 2, NEXT = 3, SEEK = 4, SHUFFLE = 5, REPEAT = 6, PLAY = 7, SKIP_TO = 8, QUEUE_ADD = 9;
    private static final int HOME = 0, LIBRARY = 1, SETTINGS = 2;
    private static final String[] SECTIONS = {"Playlists", "Albums", "Artists", "Podcasts"};

    private static final ExecutorService DATA = Executors.newSingleThreadExecutor();
    private static final ExecutorService COMMANDS = Executors.newSingleThreadExecutor();
    private static final ExecutorService PLAYLISTS = Executors.newCachedThreadPool();
    private static final ExecutorService ART = Executors.newFixedThreadPool(6);
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getAllocationByteCount(); }
    };
    private static final java.util.Map<String, Integer> SEEDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, float[]> GLYPHS = new java.util.HashMap<>();
    private static final ColorMatrixColorFilter VIVID;
    static {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.5f);
        VIVID = new ColorMatrixColorFilter(m);
    }

    private final Activity activity;
    private final float density;
    private final android.content.SharedPreferences prefs;
    private final android.graphics.Rect inset = new android.graphics.Rect();
    private final Set<String> loading = new HashSet<>(), failed = new HashSet<>();

    // palette, derived from the playing cover
    private int bg, surf, surfHi, ink, inkDim, accent, onAccent, accent2;
    private String paletteKey = "";
    private android.animation.ValueAnimator paletteAnim;
    private boolean paletteReady;

    // navigation
    private int tab = HOME, filter = -1;
    private Entry playlist;
    private List<Entry> playlistTracks = new ArrayList<>();
    private boolean playlistLoading, playlistFailed, playerOpen;
    private String playlistError = "";
    /** The link actually used for the open page: Liked Songs' real link is learned from Spotify when it opens. */
    private String liveUri = "";
    private String playUri() { return liveUri.isEmpty() ? playlist.uri : liveUri; }
    /** For an album or artist page: Spotify's own heading, and the releases shelf. */
    private String detailTitle = "", detailSubtitle = "", detailImage = "";
    private List<Entry> releases = new ArrayList<>();

    private static boolean isDetail(Entry e) { return e.uri.startsWith("spotify:album:") || e.uri.startsWith("spotify:artist:") || e.uri.startsWith("spotify:show:"); }
    private int playlistRequest;
    private String query = "";

    // player state
    private long revision, lastConnect, libraryRetry, libraryLoadedAt, stamp, position, duration;
    private boolean polling, fetching, libraryReady, refreshing, playing, shuffle;
    private int repeat;
    private Boolean pending, saved;
    private String savedUri = "";
    private String title = "", artist = "", album = "", source = "", image = "", uri = "", rating = "", context = "", quality = "";
    private boolean buffering;
    private final java.util.Set<PlayButton> playButtons = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private List<Entry> upcoming = new ArrayList<>();
    private String libraryStatus = "Connecting to your library…";

    // views that live-update
    private FrameLayout pageHost, player;
    private LinearLayout dock, dockBar, nextPill;
    private ScrollView pageScroll;
    private ListView pageList;
    private Disc disc;
    private Wave wave;
    private Backdrop backdrop;
    private TextView pQuality;
    /** Last backdrop blur and cover kept across switches so transitions start from the previous track's art. */
    private Bitmap lastBlur, lastCover;
    private String lastCoverUrl = "", nextKey = "";
    private TextView pTitle, pArtist, pSource, pBadge, mTitle, mArtist, heroTitle, heroArtist;
    private Icon pLike, pShuffle, pRepeat;
    private PlayButton pPlay, mPlay;
    private Art mArt, heroArt;
    private Runnable sheetClose;

    SpatchUi(Activity activity) {
        super(activity);
        this.activity = activity;
        density = getResources().getDisplayMetrics().density;
        prefs = activity.getSharedPreferences("spatch_ui", Context.MODE_PRIVATE);
        Audio.context = activity.getApplicationContext();
        java.io.File covers = new java.io.File(activity.getCacheDir(), "covers");
        covers.mkdirs();
        CoreBridge.cacheDir = covers;
        ART.execute(CoreBridge::pruneCache);
        nativeCacheDir(new java.io.File(activity.getCacheDir(), "answers").getPath());
        for (String key : new String[]{"back", "down", "previous", "next", "play", "pause", "shuffle", "repeat", "repeat-one", "volume",
                "queue", "home", "library", "settings", "heart", "heart-outline", "search", "check", "close", "chevron", "timer", "devices"}) {
            GLYPHS.put(key, nativeGeometry(key));
        }
        applyPalette(null);
        setFocusableInTouchMode(true);
        // Android otherwise lays a 12% white "focus highlight" over this focused view, washing out the whole app,
        // and it vanished whenever a search box took focus. The dark palette carries that lift instead.
        if (android.os.Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
        setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Rect next = new android.graphics.Rect();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                next.set(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                next.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            if (!next.equals(inset)) {
                inset.set(next);
                build();
            }
            return insets;
        });
        build();
    }

    // ================= palette =================

    private boolean pref(String key, boolean fallback) { return prefs.getBoolean(key, fallback); }

    private boolean dark = true;
    private static final int[] ACCENTS = {0, 0xff8b6fd6, 0xffd6567a, 0xff3d8fd6, 0xff2fa37a, 0xffd99a2b, 0xffe0663f};
    private static final String[] ACCENT_NAMES = {"Default", "Lavender", "Rose", "Sky", "Mint", "Amber", "Coral"};

    /** Colours from the cover when there is one, else from the chosen accent; animated smoothly in place. */
    private void applyPalette(Integer seed) {
        int theme = prefs.getInt("theme", 0);
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

    private static int blend(int a, int b, float t) {
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
        if (fromArt && seed == null && !image.isEmpty() && !"?".equals(paletteKey) && !failed.contains(image + ":440")) return false;
        String key = seed == null ? "" : String.valueOf(seed);
        if (key.equals(paletteKey)) return false;
        paletteKey = key;
        applyPalette(seed);
        return true;
    }

    // ================= small helpers =================

    private int dp(float v) { return Math.round(v * density); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams fill(int h) { return new LinearLayout.LayoutParams(-1, h); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private View space(int w, int h) { View v = new View(activity); v.setLayoutParams(lp(w, h)); return v; }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    interface ColorOf { int get(); }

    private <T extends View> T colored(T v, Runnable paint) { paint.run(); v.setTag(paint); return v; }

    private TextView text(String label, int size, ColorOf color, boolean bold) {
        TextView v = text(label, size, color.get(), bold);
        return colored(v, () -> v.setTextColor(color.get()));
    }

    private Icon icon(String symbol, ColorOf color) {
        Icon v = new Icon(symbol, color.get());
        return colored(v, () -> v.color = color.get());
    }

    private GradientDrawable bgRound(View v, float radiusDp, ColorOf color) {
        GradientDrawable d = round(color.get(), radiusDp);
        v.setBackground(d);
        colored(v, () -> d.setColor(color.get()));
        return d;
    }

    private static void recolorView(View v) {
        if (v.getTag() instanceof Runnable) ((Runnable) v.getTag()).run();
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

    private TextView text(String label, int size, int color, boolean bold) {
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

    private void oneLine(TextView v) { v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END); }

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

    private boolean motion() {
        if (pref("reduce_motion", false)) return false;
        return android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    /** Tap with a springy press; nothing highlights outside the view's own shape. */
    private void tappable(View v, String label, Runnable action) {
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

    private void command(int action, long value, String target) {
        COMMANDS.execute(() -> {
            if (!nativeCommand(action, value, target)) post(() -> {
                if (isAttachedToWindow()) Toast.makeText(activity, "That didn’t go through", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private static String time(long ms) {
        long s = Math.max(0, ms) / 1000, r = s % 60;
        return (s / 60) + (r < 10 ? ":0" : ":") + r;
    }

    private long playhead() {
        long elapsed = playing && stamp > 0 ? Math.max(0, SystemClock.uptimeMillis() - stamp) : 0;
        return Math.max(0, Math.min(duration, position + elapsed));
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
        removeCallbacks(poll);
        post(poll);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(poll);
        if (visibility == VISIBLE && isAttachedToWindow()) post(poll);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(poll);
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
        if (sheetClose != null) sheetClose.run();
        else if (playerOpen) closePlayer();
        else if (!pageStack.isEmpty()) openPlaylist(pageStack.pop(), false);
        else if (playlist != null) { playlist = null; showPage(); updateDock(); enter(-dp(44), 0); }
        else if (tab != HOME) go(HOME);
        else activity.moveTaskToBack(true);
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
                    try { nativeConnect(); } finally { post(() -> polling = false); }
                });
            }
            if (!libraryReady && !fetching && now >= libraryRetry) loadLibrary();
            // The library changed (Spotify pushed it to the core, which streamed it to Rust): redraw.
            long listRevision = nativePlaylistRevision();
            if (listRevision != playlistRevision) {
                playlistRevision = listRevision;
                Entry open = playlist;
                // The push carries the first page only, so ask again for the whole playlist; it redraws if it changed.
                if (open != null && !isDetail(open) && !playlistLoading) loadPlaylist(open, 1);
            }
            long libRevision = nativeLibraryRevision();
            if (libRevision != libraryRevision) {
                libraryRevision = libRevision;
                if (libraryReady && playlist == null && sheetClose == null && tab != SETTINGS) showPage();
            }
            postDelayed(this, 350);
        }
    };

    private void loadLibrary() {
        fetching = true;
        DATA.execute(() -> {
            String status = nativeLibrary();
            post(() -> {
                fetching = false;
                boolean cached = "cached".equals(status);
                libraryReady = status != null && (status.isEmpty() || cached);
                if (libraryReady) {
                    // Last launch's copy shows at once; backdating it makes the next check ask Spotify again.
                    libraryLoadedAt = SystemClock.uptimeMillis() - (cached ? 90_000 : 0);
                    libraryStatus = "Nothing saved here yet";
                } else {
                    libraryStatus = "Couldn’t load your library";
                    libraryRetry = SystemClock.uptimeMillis() + 12_000;
                    android.util.Log.w("SpatchUi", "Library request failed: " + (status == null ? "no response" : status));
                }
                if (playlist == null) showPage();
                if (cached) refreshLibraryIfStale();
            });
        });
    }

    private long libraryRevision, playlistRevision;
    private boolean watchingPlaylist;

    private void refreshLibraryIfStale() {
        long now = SystemClock.uptimeMillis();
        if (!libraryReady || refreshing || now - libraryLoadedAt < 90_000) return;
        refreshing = true;
        libraryLoadedAt = now;
        DATA.execute(() -> {
            boolean changed = nativeRefreshLibrary();
            post(() -> { refreshing = false; if (changed && playlist == null && sheetClose == null) showPage(); });
        });
    }

    /** state: revision, uri, title, artist, album, image, playing, shuffle, repeat, position, duration, source,
     *  explicit, context, then four strings (title, artist, image, uri) per queued track. */
    private void applyPlayer(String[] s) {
        revision = Long.parseLong(s[0]);
        boolean changed = !uri.equals(s[1]);
        uri = s[1]; title = s[2]; artist = s[3]; album = s[4]; image = s[5];
        playing = Boolean.parseBoolean(s[6]);
        if (pending != null && (changed || pending == playing)) pending = null;
        shuffle = Boolean.parseBoolean(s[7]);
        repeat = Integer.parseInt(s[8]);
        position = Long.parseLong(s[9]);
        duration = Long.parseLong(s[10]);
        source = s[11];
        rating = s[12];
        context = s[13];
        quality = s[14];
        boolean wasBuffering = buffering;
        buffering = Boolean.parseBoolean(s[15]);
        if (buffering != wasBuffering) for (PlayButton b : playButtons) b.invalidate();
        stamp = SystemClock.uptimeMillis();
        List<Entry> next = new ArrayList<>();
        for (int i = 16; i + 3 < s.length; i += 4) next.add(new Entry(s[i], s[i + 1], s[i + 3], s[i + 2], -1));
        upcoming = next;
        if (changed) checkSaved(uri);
        refreshPalette();
        refresh();
    }

    // ================= building =================

    /** Rebuilds the whole tree: insets or font/motion settings changed. */
    private void build() {
        removeAllViews();
        sheetClose = null;
        nextKey = "";
        setBackgroundColor(bg);
        pageHost = new FrameLayout(activity);
        addView(pageHost, new LayoutParams(-1, -1));
        // Lists dim as they pass under the mini player and bar instead of showing through them.
        View fade = new View(activity);
        GradientDrawable fadeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{bg & 0x00ffffff, (bg & 0x00ffffff) | 0xd8000000, bg});
        fade.setBackground(fadeBg);
        colored(fade, () -> fadeBg.setColors(new int[]{bg & 0x00ffffff, (bg & 0x00ffffff) | 0xd8000000, bg}));
        addView(fade, new LayoutParams(-1, dp(190) + inset.bottom, Gravity.BOTTOM));
        dock = column();
        dock.setPadding(dp(12), 0, dp(12), dp(10) + inset.bottom);
        addView(dock, new LayoutParams(-1, -2, Gravity.BOTTOM));
        buildDock();
        player = null;
        showPage();
        if (playerOpen) { playerOpen = false; openPlayer(false); }
    }

    private static final android.view.animation.Interpolator EMPHASIZED = new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f);

    /** The page just shown slides in from (dx, dy) while it fades up. */
    private void enter(float dx, float dy) {
        if (!motion() || pageHost.getChildCount() == 0) return;
        View page = pageHost.getChildAt(0);
        page.setAlpha(0f);
        page.setTranslationX(dx);
        page.setTranslationY(dy);
        page.animate().alpha(1f).translationX(0).translationY(0).setDuration(300).setInterpolator(EMPHASIZED).start();
    }

    /** Closes the on-screen keyboard and drops text focus; true if it was open. */
    private boolean hideKeyboard() {
        View focused = findFocus();
        boolean open = android.os.Build.VERSION.SDK_INT >= 30 && getRootWindowInsets() != null && getRootWindowInsets().isVisible(WindowInsets.Type.ime());
        if (!(focused instanceof android.widget.EditText) && !open) return false;
        android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        if (focused != null) focused.clearFocus();
        requestFocus();
        return android.os.Build.VERSION.SDK_INT >= 30 ? open : focused instanceof android.widget.EditText;
    }

    private void go(int next) {
        hideKeyboard();
        if (next == tab && playlist == null) return;
        boolean forward = next > tab;
        playlist = null;
        pageStack.clear();
        tab = next;
        showPage();
        updateDock();
        enter((forward ? 1 : -1) * dp(36), 0);
    }

    private void buildDock() {
        dock.removeAllViews();
        LinearLayout mini = row();
        mini.setPadding(dp(8), dp(6), dp(8), dp(6));
        bgRound(mini, 30, () -> blend(surfHi, accent, .12f));
        mini.setElevation(dp(3));
        mArt = new Art(Art.CIRCLE, true);
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
        mini.addView(iconButton(icon("previous", () -> ink), "Previous", () -> command(PREVIOUS, 0, "")), lp(dp(44), dp(44)));
        mPlay = new PlayButton();
        mini.addView(mPlay, lp(dp(50), dp(50)));
        mini.addView(iconButton(icon("next", () -> ink), "Next", () -> command(NEXT, 0, "")), lp(dp(44), dp(44)));
        tappable(mini, "Open player", () -> openPlayer(true));
        LinearLayout.LayoutParams mp = fill(-2);
        mp.bottomMargin = dp(8);
        dock.addView(mini, mp);

        dockBar = row();
        dockBar.setGravity(Gravity.CENTER);
        bgRound(dockBar, 28, () -> surf);
        dockBar.setElevation(dp(3));
        dockBar.setPadding(dp(6), dp(5), dp(6), dp(5));
        dock.addView(dockBar, fill(dp(56)));
        updateDock();
        refresh();
    }

    private void updateDock() {
        if (dockBar == null) return;
        dockBar.removeAllViews();
        String[] names = {"Home", "Library", "Settings"}, glyphs = {"home", "library", "settings"};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            boolean on = tab == i && playlist == null;
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER);
            bgRound(item, 22, () -> on ? accent : Color.TRANSPARENT);
            item.addView(icon(glyphs[i], () -> on ? onAccent : inkDim), lp(dp(24), dp(24)));
            if (on) {
                TextView label = text(names[i], 14, () -> onAccent, true);
                label.setPadding(dp(7), 0, dp(2), 0);
                item.addView(label);
            }
            tappable(item, names[i], () -> go(index));
            dockBar.addView(item, new LinearLayout.LayoutParams(0, dp(46), on ? 1.6f : 1f));
        }
    }

    private void showPage() {
        if (playlist == null && watchingPlaylist) { watchingPlaylist = false; PLAYLISTS.execute(SpatchUi::nativeStopPlaylist); }
        pageHost.removeAllViews();
        pageScroll = null;
        pageList = null;
        heroArt = null; heroTitle = heroArtist = null;
        View page = playlist != null ? playlistPage() : tab == HOME ? homePage() : tab == LIBRARY ? libraryPage() : settingsPage();
        pageHost.addView(page, new LayoutParams(-1, -1));
        if (tab != SETTINGS && playlist == null) refreshLibraryIfStale();
        if (tab == HOME && playlist == null) loadRecentIfStale();
        refresh();
    }

    private ScrollView scrolling(LinearLayout content) {
        ScrollView s = new ScrollView(activity);
        s.setVerticalScrollBarEnabled(false);
        s.setClipToPadding(false);
        s.setPadding(0, inset.top, 0, 0);
        content.setPadding(dp(18), dp(10), dp(18), dp(170) + inset.bottom);
        s.addView(content, new ScrollView.LayoutParams(-1, -2));
        pageScroll = s;
        return s;
    }

    // ---------- Home ----------

    private View homePage() {
        LinearLayout page = column();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        TextView h = text(hour < 5 ? "Up late?" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening", 34, () -> ink, true);
        h.setPadding(0, dp(8), 0, dp(2));
        page.addView(h);
        TextView sub = text("Here’s your music", 15, () -> inkDim, false);
        sub.setPadding(0, 0, 0, dp(16));
        page.addView(sub);

        FrameLayout hero = new FrameLayout(activity);
        bgRound(hero, 30, () -> surf);
        hero.setClipToOutline(true);
        heroArt = new Art(Art.WIDE, false);
        heroArt.crossfade = true;
        hero.addView(heroArt, new LayoutParams(-1, dp(200)));
        View scrim = new View(activity);
        GradientDrawable scrimBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{surf & 0x00ffffff, (surf & 0x00ffffff) | 0xc8000000, surf});
        scrim.setBackground(scrimBg);
        colored(scrim, () -> scrimBg.setColors(new int[]{surf & 0x00ffffff, (surf & 0x00ffffff) | 0xc8000000, surf}));
        hero.addView(scrim, new LayoutParams(-1, dp(130), Gravity.BOTTOM));
        LinearLayout words = column();
        words.setPadding(dp(18), 0, dp(18), dp(16));
        heroTitle = text("", 26, () -> ink, true);
        heroArtist = text("", 14, () -> inkDim, false);
        oneLine(heroTitle); oneLine(heroArtist);
        words.addView(heroTitle);
        words.addView(heroArtist);
        hero.addView(words, new LayoutParams(-1, -2, Gravity.BOTTOM));
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
        tappable(liked, "Open Liked Songs", () -> openPlaylist(new Entry("Liked Songs", "", "spotify:collection:tracks", "", 0)));
        LinearLayout.LayoutParams likedParams = fill(-2);
        likedParams.topMargin = dp(12);
        page.addView(liked, likedParams);

        boolean any = false, hidden = false;
        if (!pref("home_recent", true)) hidden = true;
        else if (!recent.isEmpty()) any |= shelf(page, "Recently played", recent, null);
        for (int c = 0; c < SECTIONS.length; c++) {
            final int category = c;
            if (!pref("home_shelf_" + c, true)) { hidden = true; continue; }
            any |= shelf(page, SECTIONS[c], entries(c, 14), () -> { filter = category; go(LIBRARY); });
        }
        if (!any && !hidden) {
            TextView note = text(libraryStatus, 14, () -> inkDim, false);
            note.setPadding(dp(4), dp(24), 0, 0);
            page.addView(note);
            if (!libraryReady && !libraryStatus.startsWith("Connecting")) page.addView(pill("Retry", () -> { if (!fetching) loadLibrary(); }));
        }
        return scrolling(page);
    }

    private TextView pill(String label, Runnable action) {
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

    private static List<Entry> entryList(String[] f) {
        List<Entry> out = new ArrayList<>();
        if (f == null) return out;
        for (int i = 0; i + 4 < f.length; i += 5) out.add(new Entry(f[i], f[i + 1], f[i + 2], f[i + 3], Integer.parseInt(f[i + 4])));
        return out;
    }

    /** Library items for a chip (-1 for all), searched and sorted by Rust. */
    private List<Entry> entries(int category, String search, boolean alphabetical, int limit) {
        return entryList(nativeEntries(category, search, alphabetical, limit));
    }

    private List<Entry> entries(int category, int limit) { return entries(category, "", false, limit); }

    // ---- Spotify's own recently played, loaded in the background ----
    private List<Entry> recent = new ArrayList<>();
    private long recentAt;
    private boolean loadingRecent;

    private void loadRecentIfStale() {
        long now = SystemClock.uptimeMillis();
        if (!libraryReady || loadingRecent || (recentAt != 0 && now - recentAt < 60_000)) return;
        loadingRecent = true;
        boolean first = recentAt == 0 && recent.isEmpty();
        PLAYLISTS.execute(() -> {
            if (first) {
                String[] saved = nativeCached("recent");
                if (saved != null) post(() -> {
                    if (!recent.isEmpty()) return;
                    recent = entryList(saved);
                    if (tab == HOME && playlist == null && sheetClose == null) showPage();
                });
            }
            String[] fresh = nativeRecent();
            if (fresh != null && fresh.length > 0) nativeRemember("recent", fresh);
            List<Entry> got = entryList(fresh);
            post(() -> {
                loadingRecent = false;
                recentAt = SystemClock.uptimeMillis();
                if (got.isEmpty() || got.equals(recent)) return;
                recent = got;
                if (tab == HOME && playlist == null && sheetClose == null) showPage();
            });
        });
    }

    private boolean shelf(LinearLayout page, String heading, List<Entry> items, Runnable seeAll) {
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
        for (Entry e : items) {
            LinearLayout tile = column();
            Art art = new Art(e.category == 2 ? Art.CIRCLE : Art.ROUNDED, false);
            art.letter = initial(e.title);
            art.bind(e.image);
            tile.addView(art, lp(dp(width), dp(width)));
            TextView name = text(e.title, 14, () -> ink, true);
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
        page.addView(scroller, fill(-2));
        return true;
    }

    // ---------- Library ----------

    private View libraryPage() {
        LinearLayout page = column();
        page.setPadding(0, inset.top, 0, 0);
        TextView h = text("Library", 34, () -> ink, true);
        h.setPadding(dp(18), dp(18), dp(18), dp(10));
        page.addView(h);

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
        for (int i = -1; i < SECTIONS.length; i++) {
            final int index = i;
            boolean on = i == filter;
            TextView chip = text(i == -1 ? "All" : SECTIONS[i], 14, on ? onAccent : ink, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            GradientDrawable chipBg = round(on ? accent : surf, 19);
            chip.setBackground(chipBg);
            colored(chip, () -> { chip.setTextColor(on ? onAccent : ink); chipBg.setColor(on ? accent : surf); });
            tappable(chip, chip.getText().toString(), () -> { filter = index; showPage(); });
            chips.addView(chip, lp(-2, dp(38)));
            chips.addView(space(dp(8), 1));
        }
        chipScroll.addView(chips);
        page.addView(chipScroll, fill(-2));

        List<Entry> shown = entries(filter, query, az, 400);
        if (shown.isEmpty() && query.isEmpty()) {
            TextView note = text(libraryStatus, 14, () -> inkDim, false);
            note.setPadding(dp(22), dp(20), dp(22), 0);
            page.addView(note);
        } else {
            ListView list = entryList(shown, null);
            list.setPadding(dp(18), dp(4), dp(18), dp(170) + inset.bottom);
            list.setClipToPadding(false);
            page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            pageList = list;
            field.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    query = e.toString();
                    shown.clear();
                    shown.addAll(entries(filter, query, az, 400));
                    notifyList(list);
                }
            });
        }
        return page;
    }

    private ListView entryList(List<Entry> items, View header) {
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        if (header != null) list.addHeaderView(header, null, false);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Entry getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View old, ViewGroup parent) {
                Row row = old instanceof Row ? (Row) old : new Row();
                row.bind(getItem(i));
                return row;
            }
        });
        list.setOnItemClickListener((p, v, i, id) -> {
            Object item = p.getItemAtPosition(i);
            if (item instanceof Entry && !((Entry) item).uri.isEmpty()) open((Entry) item);
        });
        list.setOnItemLongClickListener((p, v, i, id) -> {
            Object item = p.getItemAtPosition(i);
            if (!(item instanceof Entry) || ((Entry) item).uri.isEmpty()) return false;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            actions((Entry) item);
            return true;
        });
        return list;
    }

    private final class Row extends LinearLayout {
        private final Art art = new Art(Art.ROUNDED, false);
        private final TextView name = text("", 16, ink, true), sub = text("", 13, inkDim, false);
        private final Bars bars = new Bars();
        private boolean current;
        Row() {
            super(activity);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(4), dp(7), dp(4), dp(7));
            addView(art, lp(dp(52), dp(52)));
            LinearLayout words = column();
            words.setPadding(dp(14), 0, dp(8), 0);
            oneLine(name); oneLine(sub);
            words.addView(name);
            words.addView(sub);
            addView(words, weight());
            addView(bars, lp(dp(18), dp(16)));
            colored(this, this::tint);
        }
        private void tint() {
            name.setTextColor(current ? accent : ink);
            sub.setTextColor(inkDim);
        }
        void bind(Entry e) {
            art.shapeKind = e.category == 2 ? Art.CIRCLE : Art.ROUNDED;
            art.letter = initial(e.title);
            art.bind(e.image);
            name.setText(e.title);
            sub.setText(e.subtitle);
            current = playlist != null ? !title.isEmpty() && e.title.equals(title) && e.subtitle.equals(artist) : playingFrom(e.uri);
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

    private void open(Entry e) {
        // A song on a playlist or album page plays that list from the song, as Spotify does.
        if (playlist != null && (e.uri.startsWith("spotify:track:") || e.uri.startsWith("spotify:episode:") || e.uri.startsWith("spotify:preview:"))) {
            int index = e instanceof Song ? ((Song) e).index : Math.max(0, playlistTracks.indexOf(e));
            String context = playUri();
            COMMANDS.execute(() -> {
                if (!nativePlayFrom(context, e.uri, index)) post(() -> Toast.makeText(activity, "That didn’t go through", Toast.LENGTH_SHORT).show());
            });
            return;
        }
        if (isDetail(e)) { openPlaylist(e); return; }
        boolean isList = e.category == 0 || e.uri.startsWith("spotify:playlist:") || (e.uri.startsWith("spotify:user:") && e.uri.contains(":playlist:")) || liked(e.uri);
        if (isList) openPlaylist(e);
        else command(PLAY, 0, e.uri);
    }

    /** Pages opened from another page (an album from a playlist...), so back retraces them. */
    private final java.util.ArrayDeque<Entry> pageStack = new java.util.ArrayDeque<>();

    private void openPlaylist(Entry e) { openPlaylist(e, true); }

    private void openPlaylist(Entry e, boolean forward) {
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

    private void loadPlaylist(Entry e, int attempt) {
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
        PLAYLISTS.execute(() -> {
            String key = (isDetail(e) ? "page:" : "playlist3:") + e.uri;
            // What this page showed last time appears at once; Spotify's answer replaces it if it differs.
            if (attempt == 0) {
                String[] saved = nativeCached(key);
                if (saved != null) post(() -> { if (request == playlistRequest && playlist == e && playlistLoading) applyReply(e, saved, true); });
            }
            // Liked Songs' link includes your username, which Spotify has to be asked for first.
            String target = liked(e.uri) ? nativeLiked() : e.uri;
            if (target == null || target.isEmpty()) target = e.uri;
            final String resolved = target;
            String[] first = isDetail(e) ? nativePage(e.uri) : nativePlaylist(resolved, 0);
            boolean ok = first != null && first.length >= (isDetail(e) ? 6 : 2) && first[0].isEmpty();
            int more = ok && !isDetail(e) ? parseInt(first[1]) : 0;
            if (more > 0) {
                // Long playlist: show the first page now if nothing is on screen, then fetch the rest and show it all.
                post(() -> { if (request == playlistRequest && playlist == e && shownReply == null) applyReply(e, first, false); });
                String[] rest = nativePlaylistRest(resolved, more);
                String[] all = java.util.Arrays.copyOf(first, first.length + rest.length);
                System.arraycopy(rest, 0, all, first.length, rest.length);
                all[1] = "0";
                lastFull = all;
            } else lastFull = first;
            String[] r = lastFull;
            if (ok) nativeRemember(key, r);
            // From here on Spotify pushes this playlist whenever it changes; the poll reloads it.
            if (ok && !isDetail(e) && playlist == e) { nativeWatchPlaylist(resolved, "push:" + e.uri); watchingPlaylist = true; }
            post(() -> {
                if (playlist == e) liveUri = resolved;
                if (request != playlistRequest || playlist != e) return;
                if (r != null && r.length == 1 && "loading".equals(r[0]) && attempt < 10) {
                    postDelayed(() -> { if (playlist == e) loadPlaylist(e, attempt + 1); }, 700);
                    return;
                }
                if (ok && java.util.Arrays.equals(r, shownReply)) return;
                if (!ok && shownCached) { android.util.Log.w("SpatchUi", "Kept the saved copy: " + (r == null || r.length == 0 ? "no reply" : r[0])); return; }
                applyReply(e, r, false);
            });
        });
    }

    /** Rust's playlist fields: SONG_FIELDS strings per song. */
    private static final int SONG_FIELDS = 11;
    /** Every song of the open playlist, in its order; playlistTracks is what the search and sort leave of it. */
    private String[] songFields = new String[0];
    private final List<Song> allSongs = new ArrayList<>();
    private final List<Song> recommendedSongs = new ArrayList<>();
    private String songQuery = "";

    /** The reply this page is showing, and whether it came from the saved copy. */
    private String[] shownReply;
    private volatile String[] lastFull;
    private boolean shownCached;

    private void applyReply(Entry e, String[] r, boolean cached) {
        // Replacing what's on screen (saved copy -> Spotify's answer, or a push) keeps the scroll position.
        boolean replacing = shownReply != null;
        ListView list = pageList;
        int first = replacing && list != null ? list.getFirstVisiblePosition() : 0;
        int top = replacing && list != null && list.getChildCount() > 0 ? list.getChildAt(0).getTop() : 0;
        shownReply = r;
        shownCached = cached;
        playlistTracks = new ArrayList<>();
        releases = new ArrayList<>();
        if (isDetail(e)) applyDetail(r);
        else applyPlaylist(r);
        ListView again = pageList;
        if (replacing && again != null && first > 0) again.setSelectionFromTop(Math.min(first, Math.max(0, again.getCount() - 1)), top);
    }

    private void applyPlaylist(String[] r) {
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
            songFields = kept.toArray(new String[0]);
            arrangeSongs();
        }
        showPage();
    }

    /** playlistTracks becomes the songs the search and sort leave, in that order (Rust: playlist::arrange). */
    private void arrangeSongs() {
        int[] order = nativeArrange(songFields, songQuery, prefs.getInt("song_sort", 0));
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
            int tracks = Integer.parseInt(r[4]), shelf = Integer.parseInt(r[5]);
            int at = 6;
            for (int i = 0; i < tracks && at + 3 < r.length; i++, at += 4) playlistTracks.add(new Entry(r[at], r[at + 1], r[at + 2], r[at + 3], -1));
            for (int i = 0; i < shelf && at + 3 < r.length; i++, at += 4) releases.add(new Entry(r[at], r[at + 1], r[at + 2], r[at + 3], 1));
        }
        showPage();
    }

    private void openRecommended() {
        if (recommendedSongs.isEmpty()) return;
        String[] names = new String[recommendedSongs.size()];
        for (int i = 0; i < names.length; i++) {
            Song s = recommendedSongs.get(i);
            names[i] = s.title + (s.subtitle.isEmpty() ? "" : " · " + s.subtitle);
        }
        sheet("Similar songs", "Recommended based on this playlist", names, i -> command(PLAY, 0, recommendedSongs.get(i).uri));
    }

    /** Long-press on a playlist song: play from it, queue it, go to its album or artist, like or unlike it. */
    private void songActions(Song song) {
        List<String> names = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        names.add("Play from here"); runs.add(() -> open(song));
        names.add("Add to queue"); runs.add(() -> { command(QUEUE_ADD, 0, song.uri); Toast.makeText(activity, "Added to queue", Toast.LENGTH_SHORT).show(); });
        if (!song.albumUri.isEmpty()) { names.add("Go to album"); runs.add(() -> openPlaylist(new Entry(song.album, song.subtitle, song.albumUri, song.image, -1))); }
        if (!song.artistUri.isEmpty()) { names.add("Go to artist"); runs.add(() -> openPlaylist(new Entry(song.subtitle, "Artist", song.artistUri, "", -1))); }
        names.add(song.liked ? "Remove from Liked Songs" : "Add to Liked Songs");
        runs.add(() -> {
            boolean want = !song.liked;
            song.liked = want;
            DATA.execute(() -> {
                boolean done = nativeSave(song.uri, want);
                post(() -> {
                    if (!done) song.liked = !want;
                    Toast.makeText(activity, !done ? "That didn’t go through" : want ? "Added to Liked Songs" : "Removed from Liked Songs", Toast.LENGTH_SHORT).show();
                });
            });
        });
        String note = song.album.isEmpty() ? song.subtitle : song.subtitle + " • " + song.album;
        sheet(song.title, note, names.toArray(new String[0]), choice -> runs.get(choice).run());
    }

    private View playlistPage() {
        LinearLayout page = column();
        page.setPadding(0, inset.top, 0, 0);
        FrameLayout backBox = new FrameLayout(activity);
        backBox.addView(icon("back", () -> ink), new LayoutParams(dp(24), dp(24), Gravity.CENTER));
        tappable(backBox, "Back", this::back);
        LinearLayout.LayoutParams bp = lp(dp(48), dp(48));
        bp.setMargins(dp(8), dp(8), 0, 0);
        page.addView(backBox, bp);

        LinearLayout header = column();
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(dp(18), dp(4), dp(18), dp(16));
        boolean detail = isDetail(playlist);
        String headImage = detail && !detailImage.isEmpty() ? detailImage : playlist.image;
        String headTitle = detail && !detailTitle.isEmpty() ? detailTitle : playlist.title;
        if (headImage.isEmpty() && !detail) {
            FrameLayout cover = new FrameLayout(activity);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{blend(surfHi, accent2, .5f), blend(surfHi, accent, .4f)});
            g.setCornerRadius(dp(30));
            cover.setBackground(g);
            colored(cover, () -> g.setColors(new int[]{blend(surfHi, accent2, .5f), blend(surfHi, accent, .4f)}));
            cover.addView(icon("heart", () -> ink), new LayoutParams(dp(70), dp(70), Gravity.CENTER));
            header.addView(cover, lp(dp(180), dp(180)));
        } else {
            Art art = new Art(playlist.uri.startsWith("spotify:artist:") ? Art.CIRCLE : Art.ROUNDED, false);
            art.letter = initial(headTitle);
            art.bind(headImage);
            header.addView(art, lp(dp(200), dp(200)));
        }
        TextView name = text(headTitle, 28, () -> ink, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(14), 0, dp(4));
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(name, fill(-2));
        int total = detail ? playlistTracks.size() : allSongs.size();
        String time = detail ? "" : totalTime();
        String songs = total + (total == 1 ? " song" : " songs") + (time.isEmpty() ? "" : " • " + time);
        TextView count = text(playlistLoading ? "Loading…" : playlistFailed ? "Couldn’t load" : detail && !detailSubtitle.isEmpty() ? detailSubtitle + " • " + songs : songs, 14, () -> inkDim, false);
        count.setGravity(Gravity.CENTER);
        header.addView(count, fill(-2));
        if (playlistFailed && !playlistError.isEmpty()) {
            // Spotify's own reason, so a failure says what happened.
            String lookup = liked(playlist.uri) ? nativeLikedNote() : "";
            TextView why = text(playlistError + "\n" + playUri() + (lookup.isEmpty() ? "" : "\n" + lookup), 12, () -> inkDim, false);
            why.setGravity(Gravity.CENTER);
            why.setPadding(dp(8), dp(6), dp(8), dp(4));
            header.addView(why, fill(-2));
        }
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER);
        TextView shuf = pill("Shuffle", () -> command(PLAY, 1, playUri()));
        GradientDrawable shufBg = round(surfHi, 22);
        shuf.setBackground(shufBg);
        colored(shuf, () -> { shuf.setTextColor(ink); shufBg.setColor(surfHi); });
        TextView play = pill("Play", () -> command(PLAY, 2, playUri()));
        // Buttons share the row's width, so two or three always fit the screen.
        actions.addView(shuf, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(space(dp(8), 1));
        actions.addView(play, new LinearLayout.LayoutParams(0, dp(50), 1));
        if (!recommendedSongs.isEmpty()) {
            TextView sim = pill("Similar", this::openRecommended);
            GradientDrawable simBg = round(surfHi, 22);
            sim.setBackground(simBg);
            colored(sim, () -> { sim.setTextColor(ink); simBg.setColor(surfHi); });
            actions.addView(space(dp(8), 1));
            actions.addView(sim, new LinearLayout.LayoutParams(0, dp(50), 1));
        }
        LinearLayout.LayoutParams ap = fill(-2);
        ap.topMargin = dp(4);
        header.addView(actions, ap);
        if (playlistFailed) header.addView(pill("Retry", () -> { playlistLoading = true; playlistFailed = false; showPage(); loadPlaylist(playlist, 0); }));
        android.widget.EditText songSearch = null;
        TextView sortPill = null;
        if (!detail && allSongs.size() > 1) {
            // Search and sort inside the playlist, done in Rust over the songs already here.
            LinearLayout tools = row();
            LinearLayout search = row();
            search.setPadding(dp(14), 0, dp(6), 0);
            bgRound(search, 22, () -> surf);
            search.addView(icon("search", () -> inkDim), lp(dp(20), dp(20)));
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
            tools.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1));
            tools.addView(space(dp(8), 1));
            sortPill = text(SONG_SORTS[Math.max(0, Math.min(SONG_SORTS.length - 1, prefs.getInt("song_sort", 0)))], 14, ink, true);
            sortPill.setGravity(Gravity.CENTER);
            sortPill.setPadding(dp(14), 0, dp(14), 0);
            sortPill.setSingleLine(true);
            GradientDrawable sortBg = round(surfHi, 22);
            sortPill.setBackground(sortBg);
            TextView pill = sortPill;
            colored(pill, () -> { pill.setTextColor(ink); sortBg.setColor(surfHi); });
            tools.addView(sortPill, lp(-2, dp(44)));
            LinearLayout.LayoutParams tp = fill(-2);
            tp.topMargin = dp(14);
            header.addView(tools, tp);
        }

        if (playlistTracks.isEmpty() && releases.isEmpty()) {
            ScrollView s = new ScrollView(activity);
            s.addView(header);
            page.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
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
                    }
                });
            }
            if (sortPill != null) {
                TextView pillView = sortPill;
                tappable(sortPill, "Sort songs", () -> sheet("Sort songs", "Only changes how Spatch shows this playlist", SONG_SORTS, choice -> {
                    prefs.edit().putInt("song_sort", choice).apply();
                    pillView.setText(SONG_SORTS[choice]);
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
            list.setClipToPadding(false);
            page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            pageList = list;
        }
        return page;
    }

    private void actions(Entry e) {
        if (e instanceof Song) { songActions((Song) e); return; }
        boolean song = playlist != null;
        String[] options = song ? new String[]{"Play", "Add to queue"} : new String[]{"Play", "Shuffle play", "Open"};
        sheet(e.title, e.subtitle.isEmpty() ? "Choose what to do" : e.subtitle, options, choice -> {
            if (song) {
                if (choice == 0) command(PLAY, 0, e.uri);
                else { command(QUEUE_ADD, 0, e.uri); Toast.makeText(activity, "Added to queue", Toast.LENGTH_SHORT).show(); }
            } else if (choice == 2) open(e);
            else { command(SHUFFLE, choice == 1 ? 1 : 0, ""); command(PLAY, 0, e.uri); }
        });
    }

    // ---------- Settings ----------

    private View settingsPage() {
        LinearLayout page = column();
        TextView h = text("Settings", 34, () -> ink, true);
        h.setPadding(0, dp(18), 0, dp(14));
        page.addView(h);

        LinearLayout look = card(page, "Look");
        choice(look, "Theme", new String[]{"System", "Dark", "Light"}, "theme", 0, true);
        toggle(look, "Artwork colours", "Tint the whole app with the song’s cover", "art_colors", true, true);
        accentRow(look);
        toggle(look, "Expressive type", "Google Sans Flex on Pixel; off uses Roboto", "expressive_type", true, true);

        LinearLayout spotify = card(page, "Spotify");
        spotifyCard(spotify);

        LinearLayout player = card(page, "Player");
        choice(player, "Visualizer", new String[]{"Ring and bars", "Ring only", "Off"}, "viz", 0, false);
        toggle(player, "Follow the music", "The bars react to Spotify’s playback; if it can’t be read they pulse on their own. Android lists this as microphone access", "audio", true, false);
        TextView audioStatus = text("Status: " + Audio.status, 12, () -> inkDim, false);
        audioStatus.setPadding(dp(18), 0, dp(18), dp(10));
        player.addView(audioStatus);
        toggle(player, "Spinning artwork", "The cover turns slowly while music plays", "spin", true, false);
        toggle(player, "Blurred backdrop", "The cover, blurred and drifting, behind the player", "backdrop", true, false);
        toggle(player, "Floating motes", "Soft particles drift behind the player", "motes", true, false);
        toggle(player, "Keep screen on", "While the player is open", "keep_on", true, false);

        LinearLayout gestures = card(page, "Gestures and controls");
        toggle(gestures, "Double-tap to play or pause", "On the artwork in the player", "double_tap", true, false);
        toggle(gestures, "Swipe artwork to skip", "Flick the artwork left for next, right for previous", "swipe_skip", true, false);
        choice(gestures, "Hold-to-seek step", new String[]{"5 s", "10 s", "30 s"}, "seek_step", 1, false);
        toggle(gestures, "Haptics", "Little taps on buttons", "haptics", true, false);
        toggle(gestures, "Reduce motion", "Skips fades, slides and the spinning; for a calmer, lighter app", "reduce_motion", false, true);

        LinearLayout home = card(page, "Home");
        toggle(home, "Recently played", "Spotify’s own listening history", "home_recent", true, false);
        for (int i = 0; i < SECTIONS.length; i++) toggle(home, SECTIONS[i], "Show the " + SECTIONS[i].toLowerCase(java.util.Locale.ROOT) + " shelf", "home_shelf_" + i, true, false);

        LinearLayout library = card(page, "Library");
        toggle(library, "Sort A to Z", "Alphabetical, with Liked Songs first; off shows Spotify’s recent order", "library_az", false, false);

        LinearLayout storage = card(page, "Storage");
        cacheRow(storage);

        TextView note = text("Spatch draws its own interface over Spotify’s core. Playback, your library and your account stay Spotify’s.", 13, () -> inkDim, false);
        note.setPadding(dp(4), dp(22), dp(4), 0);
        page.addView(note);
        return scrolling(page);
    }

    // ---- Spotify's own settings (Settings service in its core), read and written live ----
    private java.util.Map<String, Integer> spotifySettings;
    private String spotifyError = "";
    private boolean spotifyLoading;

    private int spotifyValue(String key) { Integer v = spotifySettings == null ? null : spotifySettings.get(key); return v == null ? 0 : v; }

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
            } else if (!spotifyLoading) {
                spotifyLoading = true;
                PLAYLISTS.execute(() -> {
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
        int selected = -1;
        for (int i = 0; i < seconds.length; i++) if (seconds[i] == fade) selected = i;
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
        PLAYLISTS.execute(() -> {
            String error = nativeSetSpotifySetting(key, value);
            if (error != null && !error.isEmpty()) post(() -> {
                spotifySettings.put(key, before);
                Toast.makeText(activity, "Spotify didn’t change that: " + error, Toast.LENGTH_LONG).show();
                if (tab == SETTINGS && playlist == null) settingsChanged(false);
            });
        });
    }

    /** Redraws Settings in place after a change, keeping the scroll position. */
    private void settingsChanged(boolean palette) {
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
    private void choice(LinearLayout box, String name, String[] names, String key, int fallback, boolean palette) {
        segmented(box, name, names, prefs.getInt(key, fallback), pick -> { prefs.edit().putInt(key, pick).apply(); settingsChanged(palette); });
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
        PLAYLISTS.execute(() -> {
            long bytes = 0;
            java.io.File[] files = CoreBridge.cacheDir == null ? null : CoreBridge.cacheDir.listFiles();
            if (files != null) for (java.io.File f : files) bytes += f.length();
            final String text = bytes < 1024 * 1024 ? (bytes / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
            post(() -> size.setText(text + " of saved covers; they download again when needed"));
        });
        tappable(clear, "Clear the artwork cache", () -> PLAYLISTS.execute(() -> {
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

    private LinearLayout card(LinearLayout page, String title) {
        TextView label = text(title, 14, () -> accent, true);
        label.setPadding(dp(6), dp(18), 0, dp(8));
        page.addView(label);
        LinearLayout box = column();
        bgRound(box, 26, () -> surf);
        box.setPadding(0, dp(6), 0, dp(6));
        page.addView(box, fill(-2));
        return box;
    }

    private void toggle(LinearLayout box, String name, String detail, String key, boolean fallback, boolean rebuild) {
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
        tappable(line, name, () -> {
            sw.set(!sw.on);
            prefs.edit().putBoolean(key, sw.on).apply();
            if (key.equals("audio") && sw.on && !Audio.permitted(activity)) activity.requestPermissions(new String[]{Audio.PERMISSION}, 7341);
            if (key.equals("art_colors")) { paletteKey = "?"; refreshPalette(); }
            else if (rebuild) { int y = pageScroll != null ? pageScroll.getScrollY() : 0; build(); keepScroll(pageScroll, y); }
        });
        box.addView(line, fill(-2));
    }

    private final class Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean on;
        private float t;
        Switch(boolean on) { super(activity); this.on = on; t = on ? 1 : 0; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(boolean v) {
            on = v;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(t, v ? 1 : 0);
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
            c.drawCircle(r + (w - 2 * r) * t, r, dp(8) + dp(4) * t, paint);
        }
    }

    // ================= sheets =================

    interface Choice { void picked(int index); }

    private void sheet(String heading, String note, String[] options, Choice choice) {
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
        ScrollView choices = new ScrollView(activity);
        LinearLayout list = column();
        choices.addView(list);
        Runnable[] close = new Runnable[1];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView option = text(options[i], 16, ink, true);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(dp(18), 0, dp(18), 0);
            GradientDrawable optBg = round(surfHi, 16);
            option.setBackground(optBg);
            colored(option, () -> { option.setTextColor(ink); optBg.setColor(surfHi); });
            oneLine(option);
            tappable(option, options[i], () -> { close[0].run(); choice.picked(index); });
            LinearLayout.LayoutParams p = fill(dp(54));
            p.bottomMargin = dp(5);
            list.addView(option, p);
        }
        card.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        scrim.addView(card, new LayoutParams(-1, -2, Gravity.BOTTOM));
        addView(scrim, new LayoutParams(-1, -1));
        close[0] = () -> {
            sheetClose = null;
            if (scrim.getParent() == null) return;
            scrim.animate().alpha(0f).setDuration(160).start();
            card.animate().translationY(card.getHeight()).setDuration(220).withEndAction(() -> removeView(scrim)).start();
        };
        scrim.setOnClickListener(v -> close[0].run());
        sheetClose = close[0];
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        card.setTranslationY(dp(400));
        card.animate().translationY(0).setDuration(340).setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
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
        playerOpen = false;
        activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View p = player;
        if (motion()) p.animate().translationY(getHeight()).setDuration(300).setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f)).withEndAction(() -> removeView(p)).start();
        else removeView(p);
        player = null;
        disc = null; wave = null; backdrop = null; nextPill = null;
        pTitle = pArtist = pSource = pBadge = pQuality = null;
        pLike = pShuffle = pRepeat = null;
        pPlay = null;
        nextKey = "";
    }

    private void buildPlayer() {
        player = new FrameLayout(activity);
        player.setClickable(true);
        backdrop = new Backdrop();
        player.addView(backdrop, new LayoutParams(-1, -1));
        if (pref("motes", true)) player.addView(new Motes(), new LayoutParams(-1, -1));
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
        top.addView(iconButton(icon("timer", () -> sleepAt > SystemClock.uptimeMillis() ? accent : ink), "Sleep timer", this::openSleepTimer), lp(dp(48), dp(48)));
        top.addView(iconButton(icon("devices", () -> ink), "Play on another device", this::openDevices), lp(dp(48), dp(48)));
        top.addView(iconButton(icon("queue", () -> ink), "Up next", this::openQueue), lp(dp(48), dp(48)));
        col.addView(top, fill(-2));

        disc = new Disc();
        col.addView(disc, new LinearLayout.LayoutParams(-1, 0, 1));
        gestures(disc);

        LinearLayout titleRow = row();
        LinearLayout words = column();
        pTitle = text("", 28, () -> ink, true);
        marquee(pTitle);
        pTitle.setOnLongClickListener(v -> share());
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
        controls.addView(iconButton(pShuffle, "Shuffle", this::toggleShuffle), lp(dp(52), dp(52)));
        controls.addView(holdToSeek(iconButton(icon("previous", () -> ink), "Previous, hold to rewind", () -> command(PREVIOUS, 0, "")), false), lp(dp(60), dp(60)));
        pPlay = new PlayButton();
        controls.addView(pPlay, lp(dp(86), dp(86)));
        controls.addView(holdToSeek(iconButton(icon("next", () -> ink), "Next, hold to fast forward", () -> command(NEXT, 0, "")), true), lp(dp(60), dp(60)));
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
    private View holdToSeek(View button, boolean forward) {
        boolean[] held = new boolean[1];
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (!held[0] || duration <= 0 || !button.isAttachedToWindow()) return;
            long jump = new long[]{5_000, 10_000, 30_000}[Math.max(0, Math.min(2, prefs.getInt("seek_step", 1)))];
            long target = Math.max(0, Math.min(duration - 1500, playhead() + (forward ? jump : -jump)));
            position = target;
            stamp = SystemClock.uptimeMillis();
            if (wave != null) wave.invalidate();
            command(SEEK, target, "");
            tick(button);
            button.postDelayed(step[0], 480);
        };
        button.setOnLongClickListener(v -> { held[0] = true; step[0].run(); return true; });
        // iconButton installs its own touch listener for the press animation; wrap it so releasing still ends the hold.
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) v.animate().scaleX(.96f).scaleY(.96f).setDuration(90).start();
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    held[0] = false;
                    v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                }
                return false;
            }
        });
        return button;
    }

    private View iconButton(Icon icon, String label, Runnable action) {
        FrameLayout box = new FrameLayout(activity);
        box.addView(icon, new LayoutParams(dp(24), dp(24), Gravity.CENTER));
        tappable(box, label, action);
        return box;
    }

    /** On the disc: pull down to close, flick sideways to skip, double tap to play or pause. */
    private void gestures(View v) {
        float[] start = new float[2];
        boolean[] drag = new boolean[1];
        android.view.GestureDetector taps = new android.view.GestureDetector(activity, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (pref("double_tap", true)) togglePlay(); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || !pref("swipe_skip", true) || Math.abs(b.getX() - a.getX()) < dp(60) || Math.abs(b.getX() - a.getX()) < Math.abs(b.getY() - a.getY())) return false;
                command(b.getX() < a.getX() ? NEXT : PREVIOUS, 0, "");
                tick(v);
                return true;
            }
        });
        v.setOnTouchListener((view, e) -> {
            if (player != null && motion()) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: start[0] = e.getRawX(); start[1] = e.getRawY(); drag[0] = false; break;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = e.getRawY() - start[1], dx = e.getRawX() - start[0];
                        if (!drag[0] && dy > dp(16) && dy > Math.abs(dx) * 1.4f) { drag[0] = true; view.getParent().requestDisallowInterceptTouchEvent(true); }
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
        COMMANDS.execute(() -> {
            boolean ok = nativeCommand(want ? RESUME : PAUSE, 0, "");
            post(() -> {
                if (!ok) { pending = null; refresh(); }
                else postDelayed(() -> { if (pending != null && pending == want) { pending = null; refresh(); } }, 2500);
            });
        });
    }

    private void toggleShuffle() {
        boolean before = shuffle, want = !before;
        shuffle = want;
        refresh();
        COMMANDS.execute(() -> {
            if (!nativeCommand(SHUFFLE, want ? 1 : 0, "")) post(() -> {
                if (shuffle == want) { shuffle = before; refresh(); }
                if (isAttachedToWindow()) Toast.makeText(activity, "That didn’t go through", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void cycleRepeat() {
        int before = repeat, want = (before + 1) % 3;
        repeat = want;
        refresh();
        COMMANDS.execute(() -> {
            if (!nativeCommand(REPEAT, want, "")) post(() -> {
                if (repeat == want) { repeat = before; refresh(); }
                if (isAttachedToWindow()) Toast.makeText(activity, "That didn’t go through", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void openQueue() {
        if (upcoming.isEmpty()) { Toast.makeText(activity, "The queue is empty", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[upcoming.size()];
        for (int i = 0; i < names.length; i++) names[i] = upcoming.get(i).title + (upcoming.get(i).subtitle.isEmpty() ? "" : " · " + upcoming.get(i).subtitle);
        sheet("Up next", source.isEmpty() ? "Coming up" : "From " + source, names, i -> command(SKIP_TO, i, upcoming.get(i).uri));
    }

    // Sleep timer: pauses playback later. Kept on the main looper, not in a view, so it survives page changes.
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static long sleepAt;
    private static final Runnable SLEEP = () -> {
        sleepAt = 0;
        COMMANDS.execute(() -> nativeCommand(PAUSE, 0, ""));
    };

    private void openSleepTimer() {
        String[] names = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "End of this track"};
        long left = sleepAt - SystemClock.uptimeMillis();
        String note = left > 0 ? "Pausing in " + Math.max(1, (left + 59_999) / 60_000) + " min" : "Pause playback later";
        sheet("Sleep timer", note, names, choice -> {
            MAIN.removeCallbacks(SLEEP);
            long delay = choice == 0 ? 0 : choice == 5 ? Math.max(1000, duration - playhead() - 500) : new long[]{0, 15, 30, 45, 60}[choice] * 60_000L;
            sleepAt = choice == 0 ? 0 : SystemClock.uptimeMillis() + delay;
            if (choice != 0) MAIN.postDelayed(SLEEP, delay);
            Toast.makeText(activity, choice == 0 ? "Sleep timer off" : "Pausing " + (choice == 5 ? "after this track" : "in " + names[choice]), Toast.LENGTH_SHORT).show();
        });
    }

    /** Lists the devices Spotify can play on and moves playback to the one you pick. */
    private void openDevices() {
        Toast.makeText(activity, "Looking for devices…", Toast.LENGTH_SHORT).show();
        PLAYLISTS.execute(() -> {
            String[] f = nativeDevices();
            post(() -> {
                if (f == null || f.length < 5) { Toast.makeText(activity, "No devices found", Toast.LENGTH_SHORT).show(); return; }
                int count = f.length / 5;
                String[] names = new String[count];
                for (int i = 0; i < count; i++) {
                    boolean active = Boolean.parseBoolean(f[i * 5 + 3]), here = Boolean.parseBoolean(f[i * 5 + 4]);
                    names[i] = f[i * 5] + (here ? " (this phone)" : "") + (active ? "  ·  playing" : "");
                }
                sheet("Play on", "Pick where your music should play", names, i -> {
                    String id = f[i * 5 + 2];
                    PLAYLISTS.execute(() -> {
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
        savedUri = track;
        saved = null;
        if (!track.startsWith("spotify:track:") && !track.startsWith("spotify:episode:") && !track.startsWith("spotify:preview:")) return;
        DATA.execute(() -> {
            int a = nativeSaved(track);
            post(() -> { if (track.equals(savedUri)) { saved = a < 0 ? null : a == 1; refresh(); } });
        });
    }

    private void toggleSaved() {
        if (uri.isEmpty()) return;
        Boolean before = saved;
        boolean want = !Boolean.TRUE.equals(before);
        String track = uri;
        saved = want;
        refresh();
        if (want && motion() && pLike != null) {
            pLike.setScaleX(.5f);
            pLike.setScaleY(.5f);
            pLike.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(3.2f)).start();
        }
        COMMANDS.execute(() -> {
            boolean ok = nativeSave(track, want);
            post(() -> {
                if (!ok && (track.equals(savedUri) || track.equals(uri))) { saved = before; refresh(); Toast.makeText(activity, "Couldn’t update Liked Songs", Toast.LENGTH_SHORT).show(); }
            });
        });
    }

    // ---------- refresh ----------

    /** Pushes the current state into whichever views exist. */
    private void refresh() {
        String shown = title.isEmpty() ? "Nothing playing" : title;
        boolean on = pending == null ? playing : pending;
        if (mTitle != null) mTitle.setText(shown);
        if (mArtist != null) mArtist.setText(artist);
        if (mArt != null) { mArt.bind(image); mArt.invalidate(); }
        if (mPlay != null) mPlay.set(on);
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
        if (pQuality != null) {
            String label = quality.toUpperCase(java.util.Locale.ROOT);
            if (!(label.isEmpty() ? " " : label).contentEquals(pQuality.getText())) {
                pQuality.setText(label.isEmpty() ? " " : label);
                pQuality.setVisibility(label.isEmpty() ? INVISIBLE : VISIBLE);
                if (!label.isEmpty() && motion()) { pQuality.setAlpha(0f); pQuality.animate().alpha(1f).setDuration(260).start(); }
            }
        }
        if (pPlay != null) pPlay.set(on);
        if (pLike != null) { ((Runnable) pLike.getTag()).run(); pLike.setAlpha(saved == null ? .4f : 1f); }
        if (pShuffle != null) ((Runnable) pShuffle.getTag()).run();
        if (pRepeat != null) ((Runnable) pRepeat.getTag()).run();
        if (disc != null) { disc.url = image; disc.invalidate(); }
        if (backdrop != null) backdrop.invalidate();
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
        Entry n = upcoming.get(0);
        String key = n.uri + '\n' + n.title + '\n' + n.subtitle + '\n' + n.image;
        nextPill.setVisibility(VISIBLE);
        if (key.equals(nextKey)) return;
        nextKey = key;
        nextPill.removeAllViews();
        Art a = new Art(Art.ROUNDED, false);
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

    private final class Icon extends View {
        String symbol;
        int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Icon(String symbol, int color) { super(activity); this.symbol = symbol; this.color = color; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) { drawGlyph(c, paint, path, symbol, color, Math.min(getWidth(), getHeight())); }
    }

    /** Draws an icon (24-unit grid from Rust) into a `size` square at the canvas origin. */
    private static void drawGlyph(Canvas canvas, Paint paint, Path path, String key, int color, float size) {
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
    private final class Bars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bars() { super(activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
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
    private Bitmap art(String url, int size) {
        String key = url + ':' + size;
        Bitmap b = IMAGES.get(key);
        if (b == null && !url.isEmpty() && !failed.contains(key) && loading.add(key)) {
            ART.execute(() -> {
                // Small tiles ask Spotify's CDN for its 300 px (album) or 160 px (artist) copy instead of the 640 px one.
                String small = size <= 180 ? smaller(url) : null;
                Bitmap got = small == null ? null : CoreBridge.artwork(small, size);
                if (got == null) got = CoreBridge.artwork(url, size);
                if (got != null) {
                    IMAGES.put(key, got);
                    if (size == 440) IMAGES.put(key + "/blur", Bitmap.createScaledBitmap(got, 10, 10, true));
                    if (!SEEDS.containsKey(url)) SEEDS.put(url, seedOf(got));
                }
                final Bitmap result = got;
                post(() -> {
                    loading.remove(key);
                    if (result == null) failed.add(key);
                    if (url.equals(image)) refreshPalette();
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

    private final class Art extends View {
        static final int CIRCLE = 0, ROUNDED = 1, WIDE = 2;
        int shapeKind;
        String letter = "";
        private String url = "";
        private final boolean ring;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix crop = new Matrix();
        private final RectF box = new RectF();
        /** Mini player and hero: a new cover fades in over the old one instead of replacing it. */
        boolean crossfade;
        private boolean waiting;
        private long shownAt;
        private Bitmap shownBitmap, previousBitmap;
        private String shownUrl = "";

        Art(int shape, boolean ring) { super(activity); this.shapeKind = shape; this.ring = ring; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        void bind(String u) {
            if (crossfade && !u.isEmpty() && shownBitmap == null && lastCover != null) { shownBitmap = lastCover; shownUrl = lastCoverUrl; }
            if (url.equals(u)) return;
            url = u;
            waiting = false;
            if (!crossfade || u.isEmpty()) { shownAt = 0; shownBitmap = previousBitmap = null; shownUrl = ""; }
            invalidate();
        }

        private void drawCover(Canvas c, Bitmap b, int alpha, float radius) {
            BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float k = Math.max(box.width() / b.getWidth(), box.height() / b.getHeight());
            crop.setScale(k, k);
            crop.postTranslate(box.left + (box.width() - b.getWidth() * k) / 2, box.top + (box.height() - b.getHeight() * k) / 2);
            shader.setLocalMatrix(crop);
            paint.setShader(shader);
            paint.setAlpha(alpha);
            c.drawRoundRect(box, radius, radius, paint);
            paint.setAlpha(255);
            paint.setShader(null);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w == 0) return;
            float pad = ring ? dp(4) : 0;
            box.set(pad, pad, w - pad, h - pad);
            float r = shapeKind == CIRCLE ? Math.min(box.width(), box.height()) / 2 : shapeKind == WIDE ? 0 : dp(16);
            long now = SystemClock.uptimeMillis();
            Bitmap b = art(url, w > dp(200) || h > dp(190) ? 440 : 180);
            if (b == null) b = art(url, 180);
            if (b != null && !url.isEmpty() && url.equals(image) && (lastCover == null || !url.equals(lastCoverUrl) || b.getWidth() >= lastCover.getWidth())) {
                lastCover = b;
                lastCoverUrl = url;
            }
            if (crossfade) {
                if (b != null && b != shownBitmap) {
                    boolean hadShown = shownBitmap != null;
                    if (!url.equals(shownUrl)) {
                        previousBitmap = motion() ? shownBitmap : null;
                        shownBitmap = b;
                        shownUrl = url;
                        shownAt = previousBitmap != null ? now : 0;
                    } else {
                        shownBitmap = b;
                    }
                    if (hadShown) waiting = false;
                } else if (b == null && !url.isEmpty() && shownBitmap != null) {
                    b = shownBitmap;
                }
            }
            if (previousBitmap != null && shownAt != 0 && now - shownAt < 420) {
                drawCover(c, previousBitmap, 255, r);
            } else previousBitmap = null;
            if (b == null || (previousBitmap == null && shownAt != 0 && now - shownAt < 320)) {
                paint.setShader(new LinearGradient(0, 0, w, h, surfHi, surf, Shader.TileMode.CLAMP));
                c.drawRoundRect(box, r, r, paint);
                paint.setShader(null);
            }
            if (b == null) {
                waiting = true;
                if (!letter.isEmpty() && (url.isEmpty() || failed.contains(url + ":180"))) {
                    paint.setColor(inkDim);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTextSize(Math.min(w, h) * .4f);
                    paint.setFakeBoldText(true);
                    c.drawText(letter, w / 2, h / 2 - (paint.descent() + paint.ascent()) / 2, paint);
                    paint.setFakeBoldText(false);
                }
            } else {
                if (waiting && motion()) { waiting = false; shownAt = now; }
                long span = previousBitmap != null ? 420 : 320;
                float f = shownAt == 0 ? 1f : Math.min(1f, (now - shownAt) / (float) span);
                drawCover(c, b, Math.round(255 * f * (2f - f)), r);
                if (f < 1f) postInvalidateOnAnimation(); else { shownAt = 0; previousBitmap = null; }
            }
            if (ring) {
                float f = duration <= 0 ? 0 : Math.min(1f, playhead() / (float) duration);
                RectF rr = new RectF(dp(1.5f), dp(1.5f), w - dp(1.5f), h - dp(1.5f));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor((ink & 0x00ffffff) | 0x30000000);
                c.drawOval(rr, paint);
                paint.setColor(accent);
                c.drawArc(rr, -90, 360 * f, false, paint);
                paint.setStyle(Paint.Style.FILL);
                if (playing && isShown()) postInvalidateDelayed(1000);
            }
        }
    }

    /** The cover, blurred and drifting, under a dark wash. */
    private final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private android.animation.ObjectAnimator drift;
        private Bitmap shown = lastBlur, previous;
        private long fadeAt;

        Backdrop() {
            super(activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (motion()) {
                setScaleX(1.25f);
                setScaleY(1.25f);
                drift = android.animation.ObjectAnimator.ofPropertyValuesHolder(this,
                    android.animation.PropertyValuesHolder.ofFloat("translationX", -dp(20), dp(24)),
                    android.animation.PropertyValuesHolder.ofFloat("rotation", -3f, 3f));
                drift.setDuration(16000);
                drift.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                drift.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                drift.start();
            }
        }

        private void wash(Canvas c, Bitmap blur, float w, float h, int alpha) {
            BitmapShader s = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            m.setScale(w / blur.getWidth(), h / blur.getHeight());
            s.setLocalMatrix(m);
            paint.setShader(s);
            paint.setAlpha(alpha);
            c.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
            paint.setShader(null);
        }

        @Override protected void onDetachedFromWindow() { if (drift != null) drift.cancel(); super.onDetachedFromWindow(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (!pref("backdrop", true)) { c.drawColor(bg); return; }
            art(image, 440);
            Bitmap blur = IMAGES.get(image + ":440/blur");
            c.drawColor(bg);
            long now = SystemClock.uptimeMillis();
            // A new cover's blur fades in over the old one.
            if (blur != null && blur != shown) {
                previous = motion() ? shown : null;
                shown = blur;
                fadeAt = now;
            }
            lastBlur = shown;
            float f = previous == null ? 1f : Math.min(1f, (now - fadeAt) / 700f);
            if (f >= 1f) previous = null;
            paint.setColorFilter(VIVID);
            if (previous != null) wash(c, previous, w, h, 255);
            if (shown != null) wash(c, shown, w, h, Math.round(255 * f));
            paint.setColorFilter(null);
            if (f < 1f) postInvalidateOnAnimation();
            paint.setShader(new LinearGradient(0, 0, 0, h, new int[]{(bg & 0x00ffffff) | 0x66000000, (bg & 0x00ffffff) | 0x99000000, bg}, new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
    }

    /** Soft particles rising through the player while music plays. */
    private final class Motes extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] x = new float[30], y = new float[30], sz = new float[30], sp = new float[30], ph = new float[30];
        private long last;
        Motes() {
            super(activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < x.length; i++) { x[i] = r.nextFloat(); y[i] = r.nextFloat(); sz[i] = 1.2f + r.nextFloat() * r.nextFloat() * 4.2f; sp[i] = .012f + r.nextFloat() * .03f; ph[i] = r.nextFloat() * 6.283f; }
        }
        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            boolean run = playing && motion();
            float dt = last == 0 || !run ? 0 : Math.min(.05f, (now - last) / 1000f);
            last = now;
            if (run && Audio.level > 0) dt *= 1f + 3f * Audio.level + 3f * Audio.kick;
            float w = getWidth(), h = getHeight(), t = now / 1000f;
            for (int i = 0; i < x.length; i++) {
                y[i] -= sp[i] * dt;
                if (y[i] < -.03f) { y[i] = 1.03f; x[i] = (x[i] + .618f) % 1f; }
                float edge = Math.min(1f, Math.min(y[i], 1f - y[i]) * 6f);
                paint.setColor(i % 2 == 0 ? accent : ink);
                paint.setAlpha(Math.max(0, Math.round(110 * (.5f + .5f * (float) Math.sin(t * 1.3f + ph[i] * 3f)) * Math.max(0f, edge))));
                c.drawCircle(x[i] * w + (float) Math.sin(t * .6f + ph[i]) * dp(10), y[i] * h, dp(sz[i]), paint);
            }
            if (run && isShown()) postInvalidateOnAnimation();
        }
    }

    /** The art in a circle, turning slowly, inside a sweep-coloured ring with bars radiating from it. */
    private final class Disc extends View {
        String url = "";
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final Matrix m = new Matrix();
        private float angle;
        private SweepGradient sweep;
        private int sweepA, sweepB;
        private Bitmap shown = lastCover, previous;
        private String shownUrl = lastCoverUrl;
        private long fadeAt, lastFrame;
        private final float[] level = new float[36], ringTargets = new float[36];

        Disc() { super(activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        private void cover(Canvas c, Bitmap b, float cx, float cy, float radius, int alpha) {
            float k = Math.max(radius * 2 / b.getWidth(), radius * 2 / b.getHeight());
            m.setScale(k, k);
            m.postTranslate(cx - b.getWidth() * k / 2, cy - b.getHeight() * k / 2);
            m.postRotate(angle, cx, cy);
            paint.setAlpha(alpha);
            c.drawBitmap(b, m, paint);
            paint.setAlpha(255);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            float s = Math.min(w, h), bar = dp(34);
            float ring = Math.max(dp(60), s / 2f - bar - dp(8)), artR = ring - dp(7);
            boolean run = playing && motion();
            boolean audio = pref("audio", true) && Audio.permitted(activity);
            long now = SystemClock.uptimeMillis();
            if (url.isEmpty()) { shown = previous = null; shownUrl = ""; }
            Bitmap b = art(url, 440);
            if (b == null) b = art(url, 180);
            if (b != null && !url.isEmpty() && url.equals(image) && (lastCover == null || !url.equals(lastCoverUrl) || b.getWidth() >= lastCover.getWidth())) {
                lastCover = b;
                lastCoverUrl = url;
            }
            // A new cover fades in over the old one instead of cutting.
            if (b != null && b != shown) {
                if (!url.equals(shownUrl)) {
                    previous = motion() ? shown : null;
                    shown = b;
                    shownUrl = url;
                    fadeAt = previous != null ? now : 0;
                } else {
                    shown = b;
                }
            }
            float fade = previous == null ? 1f : Math.min(1f, (now - fadeAt) / 450f);
            if (fade >= 1f) previous = null;
            c.save();
            clip.rewind();
            clip.addCircle(cx, cy, artR, Path.Direction.CW);
            c.clipPath(clip);
            if (shown != null) {
                if (run && pref("spin", true)) angle = (angle + .12f) % 360f;
                paint.setShader(null);
                if (previous != null) cover(c, previous, cx, cy, artR, 255);
                cover(c, shown, cx, cy, artR, Math.round(255 * fade));
            } else {
                paint.setColor(surfHi);
                c.drawCircle(cx, cy, artR, paint);
            }
            c.restore();
            if (fade < 1f) postInvalidateOnAnimation();
            if (sweep == null || sweepA != accent || sweepB != accent2) {
                sweepA = accent;
                sweepB = accent2;
                sweep = new SweepGradient(cx, cy, new int[]{accent, accent2, accent, accent2, accent}, null);
            }
            // Visualizer style: 0 ring and bars, 1 ring only, 2 neither.
            int style = prefs.getInt("viz", 0);
            paint.setShader(sweep);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5));
            if (style != 2) c.drawCircle(cx, cy, ring, paint);
            int bars = 72;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.min(dp(7), (float) (Math.PI * 2 * ring / bars) * .55f));
            float t = now / 1000f;
            boolean live = audio && Audio.BANDS[0] + Audio.BANDS[8] > 0;
            int half = bars / 2;
            float dt = lastFrame == 0 ? .016f : Math.min(.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            // The bands spread over each side's bars, bass-weighted (Rust: spectrum::bass_expanded).
            if (live) nativeRing(half, ringTargets);
            for (int i = 0; i < half; i++) {
                float v;
                if (live) {
                    v = ringTargets[i];
                } else if (run) {
                    // No audio to read: a pulse that still reads as music.
                    float swell = Math.max(0f, (float) Math.sin(t * 4.4f)) * (1f - i / (float) half) * .3f;
                    v = .25f + .18f * (float) Math.sin(t * 2.6f + i * .5f) + .12f * (float) Math.sin(t * 1.5f - i * .27f) + swell;
                } else v = 0f;
                // Swiftify's ring style: base .02 + 1.12 x level^1.1, rising in 16 ms and falling in 45 ms.
                float shown = Math.max(0f, Math.min(1f, .02f + 1.12f * (float) Math.pow(Math.max(0f, v), 1.1)));
                float tau = shown > level[i] ? .016f : .045f;
                level[i] += (shown - level[i]) * (1f - (float) Math.exp(-dt / tau));
            }
            for (int k = 0; k < bars && style == 0; k++) {
                int bi = k < half ? k : bars - 1 - k;
                double a = -Math.PI / 2 + (k + .5) / bars * Math.PI * 2;
                float len = dp(3) + (bar - dp(6)) * Math.min(1f, level[bi]), start = ring + dp(10);
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                c.drawLine(cx + cos * start, cy + sin * start, cx + cos * (start + len), cy + sin * (start + len), paint);
            }
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            if (audio && style == 0) Audio.want();
            if (run && isShown()) postInvalidateOnAnimation();
        }
    }

    /** Progress as a wavy line that flattens when paused; drag to seek. */
    private final class Wave extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float preview = -1, amp = -1;
        Wave() { super(activity); setContentDescription("Playback position"); }

        @Override protected void onDraw(Canvas c) {
            long el = playhead();
            float f = preview >= 0 ? preview : duration <= 0 ? 0 : el / (float) duration;
            float x0 = dp(6), x1 = getWidth() - dp(6), y = dp(20), tip = x0 + (x1 - x0) * f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor((ink & 0x00ffffff) | 0x2a000000);
            if (tip + dp(8) < x1) c.drawLine(tip + dp(8), y, x1, y, paint);
            float target = playing ? dp(3.2f) : 0;
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
            c.drawText(time(preview >= 0 ? Math.round(preview * (double) duration) : el), x0, dp(50), paint);
            paint.setFakeBoldText(false);
            paint.setColor(inkDim);
            paint.setTextAlign(Paint.Align.RIGHT);
            c.drawText("−" + time(Math.max(0, duration - el)), x1, dp(50), paint);
            if ((playing || Math.abs(target - amp) > .05f) && isShown()) { if (motion()) postInvalidateOnAnimation(); else postInvalidateDelayed(500); }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (duration <= 0) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: getParent().requestDisallowInterceptTouchEvent(true);
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    preview = Math.max(0, Math.min(1, (e.getX() - dp(6)) / Math.max(1, getWidth() - dp(12))));
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        long target = Math.round(duration * (double) preview);
                        command(SEEK, target, "");
                        position = target;
                        stamp = SystemClock.uptimeMillis();
                        preview = -1;
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL: preview = -1; invalidate(); return true;
                default: return false;
            }
        }
    }

    // ================= data =================

    private static class Entry {
        final String title, subtitle, uri, image;
        final int category;
        Entry(String title, String subtitle, String uri, String image, int category) { this.title = title; this.subtitle = subtitle; this.uri = uri; this.image = image; this.category = category; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Entry)) return false;
            Entry e = (Entry) o;
            return title.equals(e.title) && subtitle.equals(e.subtitle) && uri.equals(e.uri) && image.equals(e.image) && category == e.category;
        }
        @Override public int hashCode() { return java.util.Objects.hash(title, subtitle, uri, image, category); }
    }

    /** A playlist song: an Entry plus what Rust's playlist fields carry, and its place in the playlist. */
    private static final class Song extends Entry {
        final String album, albumUri, artistUri;
        final long duration;
        final int index;
        final boolean recommended;
        boolean liked;
        Song(String[] f, int at, int index) {
            super(f[at], f[at + 1], f[at + 2], f[at + 3], -1);
            album = f[at + 4]; albumUri = f[at + 5]; artistUri = f[at + 6];
            long ms = 0;
            try { ms = Long.parseLong(f[at + 7]); } catch (NumberFormatException ignored) { /* no length */ }
            duration = ms;
            liked = !f[at + 9].isEmpty();
            recommended = !f[at + 10].isEmpty();
            this.index = index;
        }
    }
}
