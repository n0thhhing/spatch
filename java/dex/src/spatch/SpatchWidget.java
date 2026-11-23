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
import android.view.View;
import android.widget.RemoteViews;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final int PAUSE_ACTION = 0, RESUME_ACTION = 1, PREVIOUS_ACTION = 2, NEXT_ACTION = 3;
    private static final long FLIP_MS = 420; // the slide animations (320 ms) plus margin
    private static volatile Context app;
    /** True once the UI has started, which means the Rust library is loaded; before that no native method may run. */
    private static volatile boolean ready;
    private static long seen = -1, checkedAt, flipAt = -FLIP_MS;
    private static boolean playing, placed;
    private static String title = "", artist = "", coverUrl = "", lastPushedKey = "";
    private static Bitmap cover, backdrop;
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
            String[] s = ready || OfficialPlayback.enabled() ? SpatchUi.widgetState(force ? -1 : seen) : null;
            if (s != null) {
                seen = Long.parseLong(s[0]);
                String url = s[5];
                if (!url.equals(coverUrl)) {
                    coverUrl = url;
                    cover = fetch(url);
                    colors(cover);
                    backdrop = cover == null ? null : backdrop(cover);
                }
                title = s[2]; artist = s[3]; playing = Boolean.parseBoolean(s[6]);
                canNext = Boolean.parseBoolean(s[16]); canPrevious = Boolean.parseBoolean(s[17]);
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
            slotArt[shown] = cover == null ? placeholder() : square(cover);
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
        if (!url.startsWith("https://")) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n, total = 0;
                while ((n = in.read(chunk)) != -1 && (total += n) <= 4 * 1024 * 1024) buf.write(chunk, 0, n);
                byte[] data = buf.toByteArray();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                int maxSide = Math.max(opts.outWidth, opts.outHeight), sample = 1;
                while (maxSide / (sample * 2) >= 256) sample *= 2;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            } finally {
                c.disconnect();
            }
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** A deep background and a bright accent in the cover's hue, so the widget follows what is playing. */
    private static void colors(Bitmap art) {
        if (art == null) { background = 0xFF1D1A2A; accent = 0xFFD0BCFF; accent2 = 0xFFE8DEFF; onAccent = 0xFF14121C; recenter(); return; }
        Bitmap tiny = Bitmap.createScaledBitmap(art, 8, 8, true);
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int c = tiny.getPixel(x, y);
            r += Color.red(c); g += Color.green(c); b += Color.blue(c);
        }
        tiny.recycle();
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 64), (int) (g / 64), (int) (b / 64), hsv);
        float hue = hsv[0], sat = hsv[1];
        background = Color.HSVToColor(new float[] { hue, Math.min(sat, .6f), .2f });
        accent = Color.HSVToColor(new float[] { hue, Math.max(.3f, Math.min(sat, .75f)), .96f });
        accent2 = Color.HSVToColor(new float[] { (hue + 340) % 360, Math.max(.25f, Math.min(sat, .6f)), 1f });
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

    /** With no cover: a tinted tile with a music note. */
    private static Bitmap placeholder() {
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
        return out;
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
                v.setViewLayoutWidth(id(context, name, "id"), artSide, android.util.TypedValue.COMPLEX_UNIT_DIP);
                v.setViewLayoutHeight(id(context, name, "id"), artSide, android.util.TypedValue.COMPLEX_UNIT_DIP);
            }
        }
        String[][] slots = { { "title", "subtitle", "artwork" }, { "title2", "subtitle2", "thumbnail" } };
        for (int k = 0; k < 2; k++) {
            if (!flip && k != shown) continue; // the hidden slot isn't on screen: no point resending its text and cover
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
        String action = intent.getAction();
        if (!ready && !OfficialPlayback.enabled() && action != null && action.startsWith("spatch.widget.")) return;
        if (PREVIOUS.equals(action)) SpatchUi.widgetCommand(PREVIOUS_ACTION);
        else if (NEXT.equals(action)) SpatchUi.widgetCommand(NEXT_ACTION);
        else if (TOGGLE.equals(action)) SpatchUi.widgetCommand(playing ? PAUSE_ACTION : RESUME_ACTION);
        else super.onReceive(context, intent);
    }
}
