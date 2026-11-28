package spatch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spatch's home-screen widgets (4x2 and 4x1), listed beside Spotify's own, which are left alone. They are the old
 * Spatch's widget design: a blurred-cover backdrop, the cover filling the height, the title with an equaliser
 * that bounces while playing, and the scallop play button that morphs between five and eight bumps and keeps
 * turning. The layouts, animations and drawables are added to the app by the patch (java/widget/res); the
 * launcher runs those animations itself, so they move while no Spatch code runs. What is drawn here: colours from
 * the playing cover, the blurred backdrop, and the player's own glyphs. A song change writes the new song into the
 * hidden one of two slots and flips to it (the ViewFlippers slide the old one out). State arrives from the Rust
 * player whenever it changes (SpatchUi.onNativeChange), even with the app in the background.
 */
public class SpatchWidget extends AppWidgetProvider {
    private static final String PREVIOUS = "spatch.widget.PREVIOUS", TOGGLE = "spatch.widget.TOGGLE", NEXT = "spatch.widget.NEXT";
    private static final String ACT_PLAY = "spatch.action.PLAY", ACT_PAUSE = "spatch.action.PAUSE", ACT_TOGGLE = "spatch.action.TOGGLE";
    private static final String ACT_NEXT = "spatch.action.NEXT", ACT_PREVIOUS = "spatch.action.PREVIOUS", ACT_SEEK = "spatch.action.SEEK";
    private static final String ACT_PLAY_URI = "spatch.action.PLAY_URI", ACT_SHUFFLE = "spatch.action.SHUFFLE", ACT_REPEAT = "spatch.action.REPEAT", ACT_QUEUE = "spatch.action.QUEUE";
    private static final String PREFS = "spatch_widget", COVER_FILE = "spatch_widget_cover.png";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService WAKE = Executors.newSingleThreadExecutor();
    private static final int PAUSE_ACTION = 0, RESUME_ACTION = 1, PREVIOUS_ACTION = 2, NEXT_ACTION = 3, SEEK_ACTION = 4, SHUFFLE_ACTION = 5, REPEAT_ACTION = 6, PLAY_ACTION = 7, QUEUE_ACTION = 9;
    private static final long FLIP_MS = 420; // the slide animations (320 ms) plus margin
    private static volatile Context app;
    /** True once the UI has started, which means the Rust library is loaded; before that no native method may run. */
    private static volatile boolean ready;
    private static boolean persistedLoaded;
    private static long seen = -1, checkedAt, flipAt = -FLIP_MS, positionMs;
    private static boolean playing, placed;
    private static String title = "", artist = "", coverUrl = "", uri = "", trackContext = "", lastPushedKey = "";
    private static Bitmap cover, squareCover, backdrop, cachedPlaceholder;
    private static int placeholderAccent, placeholderAccent2;
    /** Two song slots for the flip: the shown one and the one that slid out. */
    private static int shown;
    private static final String[] slotKey = new String[2], slotTitle = new String[2], slotArtist = new String[2];
    private static final Bitmap[] slotArt = new Bitmap[2];
    /** Colours for the current cover: background, accent, and what sits on the accent. */
    private static int background = 0xFF1D1A2A, accent = 0xFFD0BCFF, accent2 = 0xFFE8DEFF, onAccent = 0xFF14121C;
    /** Whether a skip is possible right now (from the player state); dims the button rather than disabling it. */
    private static boolean canNext = true, canPrevious = true;

    /** Remembers a context to draw with; the receiver calls this. */
    static void attach(Context context) {
        if (app == null) app = context.getApplicationContext();
        OfficialPlayback.init(context);
    }

    /** The UI calls this once it is up, which also means the Rust app is loaded. */
    static void uiStarted(Context context) {
        attach(context);
        ready = true;
    }

    /** A player change from Rust, on any thread. Cheap when no widget is on a home screen. */
    static void changed() {
        Context context = app;
        if (context == null) return;
        WORK.execute(() -> refresh(context, false));
    }

    private static void refresh(Context context, boolean force) {
        try {
            // Rust reports every player change; ask the system for widgets at most every 10 s while none is placed.
            long now = SystemClock.uptimeMillis();
            if (!force && !placed && now - checkedAt < 10000) return;
            checkedAt = now;
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] big = manager.getAppWidgetIds(new ComponentName(context, SpatchWidget.class));
            int[] small = manager.getAppWidgetIds(new ComponentName(context, SpatchWidgetSmall.class));
            placed = big.length + small.length > 0;
            if (!placed) return;
            loadPersisted(context);
            String[] s = ready || OfficialPlayback.enabled() ? SpatchUi.widgetState(force ? -1 : seen) : null;
            if (s != null) {
                seen = Long.parseLong(s[0]);
                if (!s[2].isEmpty()) {
                    String url = s[5];
                    Bitmap freshCover = null;
                    if (!url.equals(coverUrl) || cover == null) {
                        coverUrl = url;
                        freshCover = fetch(url);
                        if (freshCover != null) {
                            cover = freshCover;
                            colors(cover);
                            squareCover = square(cover);
                            backdrop = backdrop(squareCover);
                            cover = squareCover;
                        } else if (url.isEmpty()) {
                            cover = null; squareCover = null; backdrop = null;
                            colors(null);
                        }
                    }
                    title = s[2]; artist = s[3]; uri = s[1]; trackContext = s[13];
                    positionMs = parsePos(s[9]);
                    playing = Boolean.parseBoolean(s[6]);
                    canNext = Boolean.parseBoolean(s[16]); canPrevious = Boolean.parseBoolean(s[17]);
                    savePersisted(context, freshCover);
                } else {
                    playing = false;
                }
            } else if (!force) {
                return;
            }
            String pushedKey = title + '\n' + artist + '\n' + coverUrl + '\n' + playing + '\n' + canNext + '\n' + canPrevious;
            if (!force && pushedKey.equals(lastPushedKey)) return;
            lastPushedKey = pushedKey;
            // A flip's slide-out must not be overwritten mid-animation: let it finish first.
            long wait = FLIP_MS - (SystemClock.uptimeMillis() - flipAt);
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            // Keyed by title only: a cover or artist arriving a moment later updates the shown slot instead of flipping again.
            boolean flip = false;
            if (!title.equals(slotKey[shown])) {
                if (slotKey[shown] != null) { shown = 1 - shown; flipAt = SystemClock.uptimeMillis(); flip = true; }
                slotKey[shown] = title;
            }
            slotTitle[shown] = title.isEmpty() ? "Spatch" : title;
            slotArtist[shown] = title.isEmpty() ? "Nothing playing" : artist;
            slotArt[shown] = squareCover != null ? squareCover : placeholder();
            // One RemoteViews per layout, pushed to every instance of it at once instead of redrawn per widget id.
            push(context, manager, big, "spatch_widget", flip, 14, 16, 152);
            push(context, manager, small, "spatch_widget_small", flip, 8, 12, 128);
        } catch (RuntimeException e) {
            android.util.Log.e("SpatchWidget", "refresh failed", e);
        }
    }

    /**
     * The cover is always a square: its side follows the widget's actual size (the launcher reports it per widget), leaving
     * room for the controls. One RemoteViews still goes to every widget of the same layout and size.
     */
    private static void push(Context context, AppWidgetManager manager, int[] ids, String layout, boolean flip, int pad, int gap, int controls) {
        // Key: negative = the tall layout at that cover side; positive or 0 = the normal layout.
        java.util.Map<Integer, java.util.List<Integer>> bySize = new java.util.TreeMap<>();
        for (int id : ids) bySize.computeIfAbsent(artSize(context, manager, id, pad, gap, controls, layout.equals("spatch_widget")), k -> new java.util.ArrayList<>()).add(id);
        for (java.util.Map.Entry<Integer, java.util.List<Integer>> group : bySize.entrySet()) {
            int[] members = new int[group.getValue().size()];
            for (int i = 0; i < members.length; i++) members[i] = group.getValue().get(i);
            int key = group.getKey();
            manager.updateAppWidget(members, views(context, key < 0 ? "spatch_widget_tall" : layout, flip, Math.abs(key)));
        }
    }

    /** The cover's side in dp for one widget (negative for the tall layout), or 0 when the launcher hasn't said (the layout's own sizes then apply). */
    private static int artSize(Context context, AppWidgetManager manager, int id, int pad, int gap, int controls, boolean mayBeTall) {
        if (Build.VERSION.SDK_INT < 31) return 0;
        android.os.Bundle o = manager.getAppWidgetOptions(id);
        boolean landscape = context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int h = o.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        int w = o.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        if (h <= 0) return 0;
        if (mayBeTall && w > 0 && h >= w * .8f) { // tall: the cover on top, room kept for the title and controls below
            return -Math.max(56, Math.min(Math.min(w - 32, h - 32 - 14 - 56 - 60 - 12), 220));
        }
        int side = h - 2 * pad - 8; // the reported height includes the launcher's own inset
        if (w > 0) side = Math.min(side, w - 2 * pad - gap - controls);
        return Math.max(48, Math.min(side, 160));
    }

    private static Bitmap fetch(String url) {
        return CoreBridge.artwork(url, 256);
    }

    /** Deep background and vibrant primary + contrasting secondary accents derived from the cover. */
    private static void colors(Bitmap art) {
        if (art == null) { background = 0xFF1D1A2A; accent = 0xFFD0BCFF; accent2 = 0xFFE8DEFF; onAccent = 0xFF14121C; recenter(); return; }
        Bitmap tiny = Bitmap.createScaledBitmap(art, 12, 12, true);
        int[] pixels = new int[144];
        tiny.getPixels(pixels, 0, 12, 0, 0, 12, 12);
        tiny.recycle();
        float[] hsv = new float[3];
        long r = 0, g = 0, b = 0;
        float bestScore = -1, hue = 0, sat = 0;
        for (int c : pixels) {
            r += Color.red(c); g += Color.green(c); b += Color.blue(c);
            Color.colorToHSV(c, hsv);
            float sc = hsv[1] * (1f - Math.abs(hsv[2] - .65f));
            if (sc > bestScore) { bestScore = sc; hue = hsv[0]; sat = hsv[1]; }
        }
        if (bestScore < .08f) {
            Color.RGBToHSV((int) (r / pixels.length), (int) (g / pixels.length), (int) (b / pixels.length), hsv);
            hue = hsv[0]; sat = hsv[1];
        }
        float secScore = -1, secHue = (hue + 340) % 360, secSat = 0;
        for (int c : pixels) {
            Color.colorToHSV(c, hsv);
            float d = Math.abs(hsv[0] - hue);
            if (Math.min(d, 360f - d) >= 35f && hsv[1] >= .25f && hsv[2] >= .20f) {
                float sc = hsv[1] * (1f - Math.abs(hsv[2] - .65f));
                if (sc > secScore) { secScore = sc; secHue = hsv[0]; secSat = hsv[1]; }
            }
        }
        background = Color.HSVToColor(new float[] { hue, Math.min(sat, .6f), .2f });
        accent = Color.HSVToColor(new float[] { hue, Math.max(.3f, Math.min(sat, .75f)), .96f });
        accent2 = secScore > 0
            ? Color.HSVToColor(new float[] { secHue, Math.max(.3f, Math.min(secSat, .7f)), 1f })
            : Color.HSVToColor(new float[] { (hue + 340) % 360, Math.max(.25f, Math.min(sat, .6f)), 1f });
        onAccent = Color.HSVToColor(new float[] { hue, .5f, .12f });
        recenter();
    }

    /** Redraws the play/pause glyph for the current onAccent; colors() calls this whenever the cover (and so the
     * colour) changes, so views() can just reuse the cached pair instead of drawing it on every refresh. */
    private static void recenter() {
        centerPlay = center(false, 96);
        centerPause = center(true, 96);
    }

    /** getIdentifier walks the resource table by name; views() asks ~30 times per refresh, and ids never change at run time. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> IDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static int id(Context context, String name, String type) {
        return IDS.computeIfAbsent(type + "/" + name, k -> context.getResources().getIdentifier(name, type, context.getPackageName()));
    }

    private static PendingIntent broadcast(Context context, String action) {
        Intent intent = new Intent(context, SpatchWidget.class).setAction(action);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ---- drawing ----

    /** The cover at the size the widget needs (two covers travel per update, so they stay small). */
    private static Bitmap square(Bitmap art) {
        return Math.max(art.getWidth(), art.getHeight()) > 256 ? Bitmap.createScaledBitmap(art, 256, 256, true) : art;
    }

    /** With no cover: a tinted tile with a music note, cached per accent pair. */
    private static Bitmap placeholder() {
        if (cachedPlaceholder != null && placeholderAccent == accent && placeholderAccent2 == accent2) return cachedPlaceholder;
        int px = 160;
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, px, px, (accent2 & 0x00FFFFFF) | 0x66000000, (accent & 0x00FFFFFF) | 0x33000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, px, px, paint);
        paint.setShader(null);
        paint.setColor(accent);
        float u = px / 100f;
        canvas.drawOval(new RectF(30 * u, 58 * u, 52 * u, 74 * u), paint);
        canvas.drawRect(47 * u, 28 * u, 52 * u, 66 * u, paint);
        Path flag = new Path();
        flag.moveTo(47 * u, 28 * u); flag.lineTo(72 * u, 36 * u); flag.lineTo(72 * u, 46 * u); flag.lineTo(47 * u, 40 * u); flag.close();
        canvas.drawPath(flag, paint);
        placeholderAccent = accent;
        placeholderAccent2 = accent2;
        return cachedPlaceholder = out;
    }


    /**
     * The player's backdrop idea at widget scale: the cover shrunk to a few pixels and smoothed back up (a cheap,
     * strong blur), under a scrim in the cover's deep colour so the text stays readable.
     */
    private static Bitmap backdrop(Bitmap art) {
        // A real blur on a small square (the view fills it without stretching), under a directional scrim so title and controls stay crisp.
        Bitmap out = Blur.soft(art, 96, 96, 7);
        Paint p = new Paint();
        int left = (background & 0x00FFFFFF) | 0x80000000;
        int right = (background & 0x00FFFFFF) | 0xCC000000;
        p.setShader(new LinearGradient(0, 0, out.getWidth(), 0, left, right, Shader.TileMode.CLAMP));
        new Canvas(out).drawRect(0, 0, out.getWidth(), out.getHeight(), p);
        return out;
    }

    // The player's own shapes (app/src/shapes.rs): glyphs on a 24-unit grid, filled polygons or round-capped strokes.
    private static final float[][] G_PLAY = { { 7, 4, 20, 12, 7, 20 } };
    private static final float[][] G_PAUSE = { { 6, 5, 10, 5, 10, 19, 6, 19 }, { 14, 5, 18, 5, 18, 19, 14, 19 } };
    private static final float[][] G_PREVIOUS = { { 19, 4, 7, 12, 19, 20 } };
    private static final float[][] G_NEXT = { { 5, 4, 17, 12, 5, 20 } };

    /** The skip glyphs never change (fixed colour and size): built once. Declared after G_PREVIOUS/G_NEXT, which
     * skip() needs; static fields initialise in textual order. */
    private static final Bitmap SKIP_PREV = skip(0, 108), SKIP_NEXT = skip(1, 108);
    /** The play/pause glyph for the current accent colour: rebuilt only when the colour changes, in colors(). Never
     * null, so a views() call before the first cover arrives still has something to draw. Also declared here, after
     * G_PLAY/G_PAUSE, for the same reason. */
    private static Bitmap centerPlay = center(false, 96), centerPause = center(true, 96);

    /** Draws a glyph from the 24-unit grid into the square of side `box` at (x, y). */
    private static void glyph(Canvas canvas, float[][] fills, float[] line, int color, float x, float y, float box) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(box / 24f, box / 24f);
        Path path = new Path();
        for (float[] f : fills) {
            path.rewind();
            path.moveTo(f[0], f[1]);
            for (int i = 2; i < f.length; i += 2) path.lineTo(f[i], f[i + 1]);
            path.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
        }
        if (line != null) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(line[0], line[1], line[2], line[3], paint);
        }
        canvas.restore();
    }

    /** Previous (kind 0) or next (kind 1): the player's plain glyph. */
    private static Bitmap skip(int kind, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        float box = px * .62f, at = (px - box) / 2;
        if (kind == 0) glyph(new Canvas(out), G_PREVIOUS, new float[] { 4, 5, 4, 19 }, 0xFFF3EEFF, at, at, box);
        else glyph(new Canvas(out), G_NEXT, new float[] { 20, 5, 20, 19 }, 0xFFF3EEFF, at, at, box);
        return out;
    }

    /** The play or pause glyph that sits on the scallop. */
    private static Bitmap center(boolean pause, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        glyph(new Canvas(out), pause ? G_PAUSE : G_PLAY, null, onAccent, 0, 0, px);
        return out;
    }

    /** For the Quick Settings tile: the same play/pause glyph the widget draws. */
    static Bitmap tileIcon(boolean pause, int px) { return center(pause, px); }
    static boolean playing() { return playing; }
    static String title() { return title; }

    private static RemoteViews views(Context context, String layout, boolean flip, int artSide) {
        RemoteViews v = new RemoteViews(context.getPackageName(), id(context, layout, "layout"));
        int root = android.R.id.background;
        boolean modern = Build.VERSION.SDK_INT >= 31;
        if (modern) v.setColorStateList(root, "setBackgroundTintList", ColorStateList.valueOf(background));
        if (modern && artSide > 0) {
            for (String name : new String[] { "artwork", "thumbnail" }) {
                int artId = id(context, name, "id");
                v.setViewLayoutWidth(artId, artSide, android.util.TypedValue.COMPLEX_UNIT_DIP);
                v.setViewLayoutHeight(artId, artSide, android.util.TypedValue.COMPLEX_UNIT_DIP);
                v.setViewOutlinePreferredRadius(artId, 16f, android.util.TypedValue.COMPLEX_UNIT_DIP);
            }
        }
        String[][] slots = { { "title", "subtitle", "artwork" }, { "title2", "subtitle2", "thumbnail" } };
        for (int k = 0; k < 2; k++) {
            int i = flip ? k : shown;
            v.setTextViewText(id(context, slots[k][0], "id"), slotTitle[i] == null ? "" : slotTitle[i]);
            v.setTextColor(id(context, slots[k][0], "id"), 0xFFF3EEFF);
            v.setTextViewText(id(context, slots[k][1], "id"), slotArtist[i] == null ? "" : slotArtist[i]);
            v.setTextColor(id(context, slots[k][1], "id"), accent2);
            if (slotArt[i] != null) v.setImageViewBitmap(id(context, slots[k][2], "id"), slotArt[i]);
        }
        // A ViewFlipper restarts its animation on every setDisplayedChild, so it is sent only on the flip itself.
        if (flip) {
            v.setDisplayedChild(id(context, "text_container", "id"), shown);
            v.setDisplayedChild(id(context, "metadata", "id"), shown);
        }
        if (backdrop != null) { v.setViewVisibility(id(context, "frame", "id"), View.VISIBLE); v.setImageViewBitmap(id(context, "frame", "id"), backdrop); }
        else v.setViewVisibility(id(context, "frame", "id"), View.GONE);
        // The launcher starts whichever animated scallop is shown: on play, five bumps morph to eight and keep turning;
        // on pause, eight settle back to five. The equaliser bounces while playing.
        v.setViewVisibility(id(context, "spinner", "id"), playing ? View.VISIBLE : View.GONE);
        v.setViewVisibility(id(context, "image", "id"), playing ? View.GONE : View.VISIBLE);
        v.setViewVisibility(id(context, "header", "id"), playing ? View.VISIBLE : View.GONE);
        if (modern) {
            ColorStateList tint = ColorStateList.valueOf(accent);
            for (String name : new String[] { "spinner", "image", "text1", "text2", "icon_view", "image_view" }) v.setColorStateList(id(context, name, "id"), "setIndeterminateTintList", tint);
        }
        int icon = id(context, "icon", "id");
        v.setImageViewBitmap(icon, playing ? centerPause : centerPlay);
        v.setContentDescription(id(context, "play_button", "id"), playing ? "Pause" : "Play");
        int prevId = id(context, "previous_button", "id"), nextId = id(context, "next_button", "id");
        v.setImageViewBitmap(prevId, SKIP_PREV);
        v.setImageViewBitmap(nextId, SKIP_NEXT);
        v.setInt(prevId, "setImageAlpha", canPrevious ? 255 : 110);
        v.setInt(nextId, "setImageAlpha", canNext ? 255 : 110);
        v.setBoolean(prevId, "setEnabled", canPrevious);
        v.setBoolean(nextId, "setEnabled", canNext);
        v.setOnClickPendingIntent(id(context, "play_button", "id"), broadcast(context, TOGGLE));
        v.setOnClickPendingIntent(prevId, broadcast(context, PREVIOUS));
        v.setOnClickPendingIntent(nextId, broadcast(context, NEXT));
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) v.setOnClickPendingIntent(root, PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        attach(context);
        WORK.execute(() -> refresh(context.getApplicationContext(), true));
    }

    @Override public void onReceive(Context context, Intent intent) {
        attach(context);
        int cmd = -1;
        long val = 0;
        String target = "", ctxUri = "";
        long seekAfter = -1;
        try {
            String action = intent.getAction();
            if (PREVIOUS.equals(action) || ACT_PREVIOUS.equals(action)) cmd = PREVIOUS_ACTION;
            else if (NEXT.equals(action) || ACT_NEXT.equals(action)) cmd = NEXT_ACTION;
            else if (TOGGLE.equals(action) || ACT_TOGGLE.equals(action)) cmd = playing ? PAUSE_ACTION : RESUME_ACTION;
            else if (ACT_PLAY.equals(action)) cmd = RESUME_ACTION;
            else if (ACT_PAUSE.equals(action)) cmd = PAUSE_ACTION;
            else if (ACT_SEEK.equals(action)) {
                val = longExtra(intent, "position_ms", longExtra(intent, "position", -1L));
                if (val >= 0) cmd = SEEK_ACTION;
            } else if (ACT_PLAY_URI.equals(action)) {
                target = strExtra(intent, "uri");
                ctxUri = strExtra(intent, "context");
                if (!target.isEmpty() || !ctxUri.isEmpty()) {
                    cmd = PLAY_ACTION;
                    if (intent.hasExtra("shuffle")) val = intent.getBooleanExtra("shuffle", false) ? 1 : 2;
                    seekAfter = longExtra(intent, "position_ms", -1L);
                }
            } else if (ACT_SHUFFLE.equals(action)) {
                cmd = SHUFFLE_ACTION;
                boolean en = intent.hasExtra("enabled") ? intent.getBooleanExtra("enabled", true)
                    : intent.hasExtra("shuffle") ? intent.getBooleanExtra("shuffle", true)
                    : intent.getIntExtra("mode", 1) != 0;
                val = en ? 1 : 0;
            } else if (ACT_REPEAT.equals(action)) {
                int mode = intent.getIntExtra("mode", 0);
                if (mode >= 0 && mode <= 2) { cmd = REPEAT_ACTION; val = mode; }
            } else if ("spatch.action.STREAM_TEST".equals(action)) {
                target = strExtra(intent, "uri");
                if (!target.isEmpty()) cmd = 92;
            } else if ("spatch.action.DECRYPT_TEST".equals(action)) {
                target = strExtra(intent, "uri");
                if (!target.isEmpty()) cmd = 91;
            } else if ("spatch.action.AUDIO_TEST".equals(action)) {
                target = strExtra(intent, "uri");
                if (!target.isEmpty()) cmd = 90; // a one-off code, only this receiver uses it
            } else if (ACT_QUEUE.equals(action)) {
                target = strExtra(intent, "uri");
                if (!target.isEmpty()) cmd = QUEUE_ACTION;
            }
        } catch (RuntimeException e) {
            Log.w("SpatchWidget", "Bad widget intent extras", e);
            return;
        }
        if (cmd < 0) {
            super.onReceive(context, intent);
            return;
        }
        final int fCmd = cmd;
        final long fVal = val, fSeek = seekAfter;
        final String fTarget = target, fCtx = ctxUri;
        if (ready || OfficialPlayback.enabled()) {
            if (fCmd == PLAY_ACTION && !fCtx.isEmpty() && !fTarget.isEmpty()) {
                WAKE.execute(() -> {
                    SpatchUi.playFrom(fCtx, fTarget);
                    if (fSeek >= 0) {
                        try { Thread.sleep(250); } catch (InterruptedException ignored) { return; }
                        SpatchUi.playerCommand(SEEK_ACTION, fSeek, "");
                    }
                });
            } else if (fCmd == PLAY_ACTION && fSeek >= 0) {
                WAKE.execute(() -> {
                    SpatchUi.playerCommand(PLAY_ACTION, fVal, fTarget.isEmpty() ? fCtx : fTarget);
                    try { Thread.sleep(250); } catch (InterruptedException ignored) { return; }
                    SpatchUi.playerCommand(SEEK_ACTION, fSeek, "");
                });
            } else if (fCmd == 90) {
                SpatchUi.audioTest(fTarget);
            } else if (fCmd == 91) {
                SpatchUi.decryptTest(fTarget);
            } else if (fCmd == 92) {
                SpatchUi.streamTest(fTarget);
            } else {
                SpatchUi.widgetCommand(fCmd, fVal, fTarget.isEmpty() ? fCtx : fTarget);
            }
            return;
        }
        final PendingResult pending = goAsync();
        final Context appCtx = context.getApplicationContext();
        WAKE.execute(() -> {
            try {
                wakeAndCommand(appCtx, fCmd, fVal, fTarget, fCtx, fSeek);
            } finally {
                if (pending != null) pending.finish();
            }
        });
    }

    private static String strExtra(Intent intent, String key) {
        String v = intent.getStringExtra(key);
        return v == null ? "" : v.trim();
    }

    private static long longExtra(Intent intent, String key, long fallback) {
        if (!intent.hasExtra(key)) return fallback;
        Object raw = intent.getExtras() == null ? null : intent.getExtras().get(key);
        if (raw instanceof Number) return ((Number) raw).longValue();
        if (raw instanceof String) try { return Long.parseLong((String) raw); } catch (NumberFormatException ignored) {}
        return fallback;
    }

    private static synchronized void loadPersisted(Context context) {
        if (persistedLoaded) return;
        persistedLoaded = true;
        android.content.SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!title.isEmpty()) return;
        title = p.getString("title", "");
        artist = p.getString("artist", "");
        coverUrl = p.getString("cover_url", "");
        uri = p.getString("uri", "");
        trackContext = p.getString("context", "");
        positionMs = Math.max(0L, p.getLong("position", 0L));
        canNext = p.getBoolean("can_next", true);
        canPrevious = p.getBoolean("can_previous", true);
        playing = false;
        java.io.File file = new java.io.File(context.getFilesDir(), COVER_FILE);
        if (file.isFile()) {
            Bitmap disk = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (disk != null) {
                colors(disk);
                squareCover = square(disk);
                backdrop = backdrop(squareCover);
                cover = squareCover;
            }
        }
    }

    private static void savePersisted(Context context, Bitmap newCover) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", title)
            .putString("artist", artist)
            .putString("cover_url", coverUrl)
            .putString("uri", uri)
            .putString("context", trackContext)
            .putLong("position", positionMs)
            .putBoolean("can_next", canNext)
            .putBoolean("can_previous", canPrevious)
            .apply();
        if (newCover != null) {
            java.io.File file = new java.io.File(context.getFilesDir(), COVER_FILE);
            java.io.File part = new java.io.File(context.getFilesDir(), COVER_FILE + ".part");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                square(newCover).compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (java.io.IOException ignored) {
                part.delete();
                return;
            }
            if (!part.renameTo(file)) part.delete();
        }
    }

    private static long parsePos(String raw) {
        try { return Math.max(0L, Long.parseLong(raw)); }
        catch (NumberFormatException e) { return 0L; }
    }

    private static boolean loadCore(Context context) {
        try {
            java.lang.reflect.Method m = Class.forName("spatch.SpatchApp").getDeclaredMethod("load", Context.class);
            m.setAccessible(true);
            return Boolean.TRUE.equals(m.invoke(null, context));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void wakeAndCommand(Context context, int cmd, long val, String target, String ctxUri, long seekAfter) {
        loadPersisted(context);
        // Ask Spotify's own MediaButtonReceiver to spin up playback in the background without opening an activity.
        if (cmd <= NEXT_ACTION) OfficialPlayback.sendMediaButton(context, context.getPackageName(), cmd);
        else OfficialPlayback.sendMediaButton(context, context.getPackageName(), RESUME_ACTION);
        if (loadCore(context)) ready = true;
        if (!ready) return;
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (CoreBridge.hasTransport() && SpatchUi.connectNative()) break;
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (!CoreBridge.hasTransport()) return;
        String[] s = SpatchUi.widgetState(-1);
        boolean isPlaying = s != null && Boolean.parseBoolean(s[6]);
        if (cmd == RESUME_ACTION && !isPlaying) {
            SpatchUi.playerCommand(RESUME_ACTION, 0, "");
            try { Thread.sleep(350); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            s = SpatchUi.widgetState(-1);
            isPlaying = s != null && Boolean.parseBoolean(s[6]);
            if (!isPlaying && !uri.isEmpty()) {
                boolean started = !trackContext.isEmpty() && trackContext.startsWith("spotify:")
                    ? SpatchUi.playFrom(trackContext, uri)
                    : SpatchUi.playerCommand(PLAY_ACTION, 0, uri);
                if (started && positionMs > 0) {
                    try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    SpatchUi.playerCommand(SEEK_ACTION, positionMs, "");
                }
            }
        } else if (cmd == PLAY_ACTION) {
            boolean started = !ctxUri.isEmpty() && !target.isEmpty()
                ? SpatchUi.playFrom(ctxUri, target)
                : SpatchUi.playerCommand(PLAY_ACTION, val, target.isEmpty() ? ctxUri : target);
            if (started && seekAfter >= 0) {
                try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                SpatchUi.playerCommand(SEEK_ACTION, seekAfter, "");
            }
        } else if ((cmd == PREVIOUS_ACTION || cmd == NEXT_ACTION) && !isPlaying) {
            SpatchUi.playerCommand(cmd, 0, "");
        } else if (cmd != RESUME_ACTION && cmd != PREVIOUS_ACTION && cmd != NEXT_ACTION) {
            SpatchUi.playerCommand(cmd, val, target.isEmpty() ? ctxUri : target);
        }
        refresh(context, true);
    }
}
