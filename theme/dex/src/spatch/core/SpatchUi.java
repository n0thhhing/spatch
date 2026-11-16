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
    private int surface, container, high, onSurface, onVariant, primary, onPrimary;
    private int secondaryContainer, onSecondaryContainer, tertiaryContainer, onTertiaryContainer;
    private boolean dark;
    private static final int HOME = 0, LIBRARY = 1, PLAYER = 2, IMMERSIVE = 3, QUEUE = 4, PLAYLIST = 5, SETTINGS = 6;
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

    SpatchUi(Activity activity) {
        super(activity);
        this.activity = activity;
        density = getResources().getDisplayMetrics().density;
        prefs = activity.getSharedPreferences("spatch_ui", Context.MODE_PRIVATE);
        applyPalette();
        setClickable(true);
        setFocusableInTouchMode(true);
        setOnApplyWindowInsetsListener((v, insets) -> {
            setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                       insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        render();
    }

    private final android.content.SharedPreferences prefs;

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
        String want = artworkSeedWanted();
        if (want != null) appliedSeed = want;
        if (!appliedSeed.isEmpty()) seedPalette(Integer.parseInt(appliedSeed));
        setBackgroundColor(surface);
    }

    private static final java.util.Map<String, Integer> SEEDS = new java.util.concurrent.ConcurrentHashMap<>();
    private String appliedSeed = "";

    /** "" for wallpaper colours, the seed for artwork colours, or null to keep what's shown until the art loads. */
    private String artworkSeedWanted() {
        if (!pref("art_colors", true) || (screen != PLAYER && screen != IMMERSIVE && screen != QUEUE)) return "";
        Integer seed = SEEDS.get(image);
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
        window.setStatusBarColor(surface);
        window.setNavigationBarColor(surface);
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
        return shape == Art.ROUND || shape == Art.DISC ? CIRCLE_COVER : shape == Art.IMMERSIVE ? COVER : PLAYER_COVER;
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
        if (immersiveDrift != null) immersiveDrift.cancel();
        immersiveDrift = null;
        immersiveChrome = null;
        applyPalette();
        paintSystemBars();
        if (screen == IMMERSIVE && pref("keep_on", true)) activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        addView(content, new FrameLayout.LayoutParams(-1, -1));
        if (screen == HOME || screen == LIBRARY) buildBrowse();
        else if (screen == QUEUE) buildQueue();
        else if (screen == PLAYLIST) buildPlaylist();
        else if (screen == SETTINGS) buildSettings();
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
            feed.addView(likedCard(), match(-2));
            boolean any = false;
            for (int category = 0; category < SECTIONS.length; category++) any |= addShelf(feed, category);
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
        Glyph settings = glyph("settings", "Settings", false, () -> navigate(7, 0));
        settings.setBackground(background(high, 24));
        heading.addView(settings, control(48));
        bar.addView(heading, match(dp(60)));
        gap(bar, 8);
        LinearLayout group = row();
        group.addView(segment("Home", "home", screen == HOME, true, () -> navigate(0, 0)), new LinearLayout.LayoutParams(0, dp(48), 1));
        View space = new View(activity);
        group.addView(space, new LinearLayout.LayoutParams(dp(4), 1));
        group.addView(segment("Library", "library", screen == LIBRARY, false, () -> openLibrary(filter)), new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(group, match(-2));
        content.addView(bar, match(-2));
    }

    private View segment(String title, String symbol, boolean selected, boolean first, Runnable action) {
        LinearLayout button = row();
        button.setGravity(Gravity.CENTER);
        button.setSelected(selected);
        int outer = 24, inner = selected ? 24 : 8;
        button.setBackground(segment(selected ? primary : high,
            first ? outer : inner, first ? inner : outer, first ? inner : outer, first ? outer : inner));
        Glyph icon = new Glyph(symbol, false);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.ink = selected ? onPrimary : onVariant;
        button.addView(icon, control(28));
        TextView label = text(title, 15, selected ? onPrimary : onSurface, true);
        label.setPadding(dp(6), 0, 0, 0);
        button.addView(label);
        makeClickable(button, title, selected ? () -> {
            if (scroll != null) scroll.smoothScrollTo(0, 0);
            if (libraryList != null) libraryList.smoothScrollToPosition(0);
        } : action);
        springPress(button);
        return button;
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
        trackArt = new Art(pref("round_mini", true) ? Art.ROUND : Art.SQUARE);
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
        if (pref("details", true)) {
            addPlayerDetails(body);
        } else {
            content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    private View immersiveChrome;
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
        top.addView(glyph("queue", "Up next", false, () -> navigate(4, 0)), control(48));
        content.addView(top, match(-2));

        FrameLayout stage = new FrameLayout(activity);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        if (pref("glow", true)) stage.addView(new Glow(), new FrameLayout.LayoutParams(-1, -1));
        trackArt = new Art(pref("spin_art", true) ? Art.DISC : Art.PLAYER_ART);
        trackArt.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        makeClickable(trackArt, "Return to player", () -> navigate(3, 0));
        trackArt.setForeground(null);
        artworkGestures(trackArt, false);
        FrameLayout.LayoutParams artParams = new FrameLayout.LayoutParams(-1, -1);
        artParams.setMargins(dp(36), dp(28), dp(36), dp(28));
        stage.addView(trackArt, artParams);
        content.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout chrome = column();
        chrome.setPadding(dp(20), 0, dp(20), dp(16));
        immersiveChrome = chrome;
        trackTitle = text("", 30, onSurface, true);
        trackTitle.setGravity(Gravity.CENTER);
        trackTitle.setMaxLines(2);
        trackTitle.setEllipsize(TextUtils.TruncateAt.END);
        chrome.addView(trackTitle, match(-2));
        trackArtist = text("", 17, onVariant, false);
        trackArtist.setGravity(Gravity.CENTER);
        trackArtist.setPadding(0, dp(4), 0, 0);
        single(trackArtist);
        chrome.addView(trackArtist, match(-2));
        gap(chrome, 10);
        wave = new Wave();
        chrome.addView(wave, match(dp(54)));
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        shuffleButton = glyph("shuffle", "Shuffle", false, () -> command(SHUFFLE, shuffle ? 0 : 1, ""));
        repeatButton = glyph("repeat", "Repeat", false, () -> command(REPEAT, (repeat + 1) % 3, ""));
        playButton = glyph("play", "Play", true, this::toggle);
        Glyph previous = glyph("previous", "Previous track", false, () -> command(PREVIOUS, 0, ""));
        Glyph next = glyph("next", "Next track", false, () -> command(NEXT, 0, ""));
        for (Glyph g : new Glyph[]{shuffleButton, previous, playButton, next, repeatButton}) g.glass = true;
        addTransport(controls, shuffleButton, 48, true, false);
        addTransport(controls, previous, 56, false, false);
        addTransport(controls, playButton, 84, false, false);
        addTransport(controls, next, 56, false, false);
        addTransport(controls, repeatButton, 48, false, true);
        chrome.addView(controls, match(-2));
        gap(chrome, 6);
        addVolume(chrome);
        content.addView(chrome, match(-2));
        if (motionEnabled()) {
            chrome.setTranslationY(dp(28));
            chrome.setAlpha(0f);
            chrome.animate().translationY(0).alpha(1f).setStartDelay(90).setDuration(420)
                .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
        }
    }

    private void toggleChrome() {
        View chrome = immersiveChrome;
        if (chrome == null) return;
        boolean hide = chrome.getAlpha() > .5f;
        chrome.animate().alpha(hide ? 0f : 1f).translationY(hide ? dp(24) : 0).setStartDelay(0).setDuration(320)
            .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
        chrome.setVisibility(VISIBLE);
        ViewGroup group = (ViewGroup) chrome;
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setEnabled(!hide);
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

    /** A halo behind the artwork that breathes slowly while playing. */
    private final class Glow extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private RadialGradient gradient;
        private int gradientColor;
        private float gradientRadius;

        Glow() {
            super(activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float side = Math.min(Math.min(w - dp(72), h - dp(56)), dp(344));
            float radius = side * .5f * 1.42f;
            if (gradient == null || gradientColor != primary || gradientRadius != radius) {
                gradientColor = primary;
                gradientRadius = radius;
                gradient = new RadialGradient(0, 0, radius,
                    new int[]{(primary & 0x00ffffff) | 0x70000000, (primary & 0x00ffffff) | 0x26000000, primary & 0x00ffffff},
                    new float[]{.5f, .75f, 1f}, Shader.TileMode.CLAMP);
            }
            boolean running = playing && motionEnabled() && getWindowVisibility() == VISIBLE;
            float breath = running ? (float) Math.sin(SystemClock.uptimeMillis() / 4200f * 6.283f) : 0f;
            float scale = 1f + .06f * breath;
            c.save();
            c.translate(w / 2f, h / 2f);
            c.scale(scale, scale);
            paint.setShader(gradient);
            c.drawCircle(0, 0, radius, paint);
            c.restore();
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
                } else if (!mini && !uri.isEmpty()) {
                    command(dx < 0 ? NEXT : PREVIOUS, 0, "");
                } else return false;
                if (pref("haptics", true)) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
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

    private void buildSettings() {
        LinearLayout top = row();
        top.setPadding(dp(10), dp(6), dp(10), dp(4));
        top.addView(glyph("back", "Back", false, this::back), control(48));
        TextView heading = text("Settings", 30, onSurface, true);
        heading.setPadding(dp(6), 0, 0, 0);
        top.addView(heading, weighted());
        content.addView(top, match(-2));
        LinearLayout page = feed();

        LinearLayout look = settingsGroup(page, "Look");
        LinearLayout themes = row();
        themes.setPadding(dp(16), dp(4), dp(16), dp(14));
        String[] names = {"System", "Dark", "Light"};
        int theme = prefs.getInt("theme", 0);
        for (int i = 0; i < names.length; i++) {
            final int choice = i;
            boolean on = i == theme;
            int outer = 22, inner = on ? 22 : 6;
            TextView option = text(names[i], 14, on ? onPrimary : onSurface, true);
            option.setGravity(Gravity.CENTER);
            option.setBackground(segment(on ? primary : high, i == 0 ? outer : inner, i == 2 ? outer : inner,
                i == 2 ? outer : inner, i == 0 ? outer : inner));
            makeClickable(option, names[i] + " theme", () -> {
                prefs.edit().putInt("theme", choice).apply();
                applyPalette();
                paintSystemBars();
                render();
            });
            springPress(option);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (i > 0) p.leftMargin = dp(3);
            themes.addView(option, p);
        }
        look.addView(themes, match(-2));
        toggle(look, "Artwork colours", "Tint the player with the song’s cover", "art_colors", true);
        toggle(look, "Round artwork in the mini player", "Off shows a rounded square", "round_mini", true);

        LinearLayout motion = settingsGroup(page, "Eye candy");
        toggle(motion, "Floating motes", "Soft particles drift behind the immersive player", "motes", true);
        toggle(motion, "Spinning artwork", "Immersive artwork turns slowly while music plays", "spin_art", true);
        toggle(motion, "Breathing glow", "A halo around the artwork pulses with the track", "glow", true);
        toggle(motion, "Wavy progress", "The progress line ripples while playing", "wavy", true);

        LinearLayout playback = settingsGroup(page, "Player");
        toggle(playback, "Keep screen on", "While the immersive player is open", "keep_on", true);
        toggle(playback, "Track details", "Show the details card under the player", "details", true);
        toggle(playback, "Haptics", "Little taps when you skip or change shapes", "haptics", true);

        LinearLayout about = settingsGroup(page, "About");
        TextView info = text("Spatch draws its own interface on top of Spotify’s core. Playback, your library and "
            + "your account stay Spotify’s; Spatch never asks for a separate sign-in.", 14, onVariant, false);
        info.setLineSpacing(0, 1.2f);
        info.setPadding(dp(16), dp(2), dp(16), dp(16));
        about.addView(info, match(-2));
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
            if (pref("haptics", true)) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        });
        line.setForeground(new RippleDrawable(ColorStateList.valueOf((onSurface & 0x00ffffff) | 0x1f000000), null, null));
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
            canvas.save();
            canvas.translate(w * .55f, h * .58f);
            canvas.rotate(15f + angle);
            canvas.scale(h * .3f, h * .3f);
            paint.setColor(tertiaryContainer);
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
        static final int SQUARE = 0, ROUND = 1, WIDE = 2, IMMERSIVE = 3, BACKDROP = 4, PLAYER_ART = 5, DISC = 6;
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

            if (bitmap == null && size != 180 && (shape == IMMERSIVE || shape == PLAYER_ART || shape == DISC)) {
                bitmap = IMAGES.get(url + ":180");
            }
            int alpha = 255;
            if (bitmap != null && (shape == WIDE || shape == IMMERSIVE || shape == PLAYER_ART || shape == DISC) && w > dp(200) && motionEnabled()) {
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
            if (shape == IMMERSIVE || shape == PLAYER_ART || shape == DISC) {
                float side = coverSide();
                c.translate((w - side) * .5f, (h - side) * .5f);
                w = h = side;
                paint.setShader(null);
                float[] points = coverPath(shape);
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
                if (shape == DISC) {
                    // A pressed-vinyl rim and spindle so the turning reads as a record, not a wobbling image.
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1.5f));
                    paint.setColor(0x33ffffff);
                    c.drawCircle(side / 2, side / 2, side / 2 - dp(1), paint);
                    paint.setColor(0x1effffff);
                    c.drawCircle(side / 2, side / 2, side * .36f, paint);
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
                if (!SEEDS.containsKey(url)) SEEDS.put(url, seedOf(bitmap));
            }
            post(() -> {
                loadingImages.remove(key);
                if (bitmap == null) failedImages.add(key);
                for (Art art : visibleArt) if (art.url.equals(url)) art.invalidate();
                if (url.equals(image)) updateMotion();
            });
        });
    }

    private final class Glyph extends View {
        private String symbol;
        private final boolean primary;
        private boolean connected, first, last, glass;
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
            float amplitude = playing && pref("wavy", true) ? dp(3) : 0;
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
        for (String key : new String[]{"back", "down", "previous", "next", "play", "pause", "shuffle", "repeat", "repeat-one", "volume", "queue", "immersive", "home", "library", "settings", "heart", "close", "sparkle", "chevron"}) {
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
