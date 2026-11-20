package spatch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import android.view.View;
import android.widget.RemoteViews;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spatch's own home-screen widget, listed beside Spotify's (which is left alone). Its layout and description are
 * added to the app by the patch (java/widget); the view ids are Spotify's own names. Everything that makes it
 * Spatch is drawn here: colours taken from the playing cover, a rounded cover, and Spatch's own play, pause and
 * skip icons. State arrives from the Rust player whenever it changes (SpatchUi.onNativeChange), even with the
 * app in the background.
 */
public class SpatchWidget extends AppWidgetProvider {
    private static final String PREVIOUS = "spatch.widget.PREVIOUS", TOGGLE = "spatch.widget.TOGGLE", NEXT = "spatch.widget.NEXT";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final int PAUSE_ACTION = 0, RESUME_ACTION = 1, PREVIOUS_ACTION = 2, NEXT_ACTION = 3;
    private static volatile Context app;
    /** True once the UI has started, which means the Rust library is loaded; before that no native method may run. */
    private static volatile boolean ready;
    private static long seen = -1, checkedAt;
    private static boolean playing, placed;
    private static String title = "", artist = "", coverUrl = "";
    private static Bitmap cover;
    /** What one slide of the widget shows. Two slides alternate, so a change cross-fades from one to the other. */
    private static final class Snap {
        String title = "", artist = "";
        Bitmap banner, tile, backdrop;
    }
    private static Snap cur, prev;
    private static String curKey = "\0";
    private static int shown;
    /** The play button's shape, 0 (five lobes, paused) to 1 (nine lobes, playing), and whether it has been drawn yet. */
    private static float shape;
    private static boolean shapeKnown;
    /** Colours for the current cover: background, accent, and what sits on the accent. */
    private static int background = 0xFF15161C, accent = 0xFF9DB7FF, accent2 = 0xFFC9D6FF, onAccent = 0xFF101218;

    /** Remembers a context to draw with; the receiver calls this. */
    static void attach(Context context) {
        if (app == null) app = context.getApplicationContext();
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
            long now = android.os.SystemClock.uptimeMillis();
            if (!force && !placed && now - checkedAt < 10000) return;
            checkedAt = now;
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] big = manager.getAppWidgetIds(new ComponentName(context, SpatchWidget.class));
            int[] small = manager.getAppWidgetIds(new ComponentName(context, SpatchWidgetSmall.class));
            placed = big.length + small.length > 0;
            if (!placed) return;
            String[] s = ready ? SpatchUi.widgetState(force ? -1 : seen) : null;
            if (s != null) {
                seen = Long.parseLong(s[0]);
                String url = s[5];
                if (!url.equals(coverUrl)) { coverUrl = url; cover = fetch(url); colors(cover); }
                title = s[2]; artist = s[3]; playing = Boolean.parseBoolean(s[6]);
            } else if (!force) {
                return;
            }
            String key = coverUrl + '\n' + title + '\n' + artist;
            if (cur == null || !key.equals(curKey)) {
                curKey = key;
                prev = cur == null ? snap() : cur;
                cur = snap();
                shown ^= 1;
            }
            float from = shapeKnown ? shape : (playing ? 1 : 0), to = playing ? 1 : 0;
            shape = from;
            for (int id : big) manager.updateAppWidget(id, views(context, false));
            for (int id : small) manager.updateAppWidget(id, views(context, true));
            if (shapeKnown && from != to) morph(context, manager, big, small, from, to);
            shape = to;
            shapeKnown = true;
        } catch (RuntimeException e) {
            android.util.Log.e("SpatchWidget", "refresh failed", e);
        }
    }

    /** The play button turning between its paused and playing shapes, as frames pushed to the home screen. */
    private static void morph(Context context, AppWidgetManager manager, int[] big, int[] small, float from, float to) {
        android.view.animation.PathInterpolator ease = new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f);
        for (int i = 1; i <= 8; i++) {
            shape = from + (to - from) * ease.getInterpolation(i / 8f);
            if (big.length > 0) manager.partiallyUpdateAppWidget(big, button(context, false));
            if (small.length > 0) manager.partiallyUpdateAppWidget(small, button(context, true));
            try { Thread.sleep(36); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static RemoteViews button(Context context, boolean small) {
        RemoteViews v = new RemoteViews(context.getPackageName(), id(context, small ? "spatch_widget_small" : "spatch_widget", "layout"));
        v.setImageViewBitmap(id(context, "btn_play_normal", "id"), playButton(small ? 138 : 162));
        return v;
    }

    /** The drawn bitmaps for what is playing now, in the current colours. */
    private static Snap snap() {
        Snap n = new Snap();
        n.title = title.isEmpty() ? "Spatch" : title;
        n.artist = title.isEmpty() ? "Nothing playing" : artist;
        n.banner = banner(cover);
        n.tile = tile(cover, 168);
        n.backdrop = backdrop(cover);
        return n;
    }

    private static Bitmap fetch(String url) {
        if (!url.startsWith("https://")) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** A deep background and a bright accent in the cover's hue, so the widget follows what is playing. */
    private static void colors(Bitmap art) {
        if (art == null) { background = 0xFF15161C; accent = 0xFF9DB7FF; accent2 = 0xFFC9D6FF; onAccent = 0xFF101218; return; }
        Bitmap tiny = Bitmap.createScaledBitmap(art, 8, 8, true);
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int c = tiny.getPixel(x, y);
            r += Color.red(c); g += Color.green(c); b += Color.blue(c);
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 64), (int) (g / 64), (int) (b / 64), hsv);
        float hue = hsv[0], sat = hsv[1];
        background = Color.HSVToColor(new float[] { hue, Math.min(sat, .6f), .2f });
        accent = Color.HSVToColor(new float[] { hue, Math.max(.3f, Math.min(sat, .75f)), .96f });
        accent2 = Color.HSVToColor(new float[] { (hue + 340) % 360, Math.max(.25f, Math.min(sat, .6f)), 1f });
        onAccent = Color.HSVToColor(new float[] { hue, .5f, .12f });
    }

    private static int id(Context context, String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    private static PendingIntent broadcast(Context context, String action) {
        Intent intent = new Intent(context, SpatchWidget.class).setAction(action);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ---- drawing ----

    /** The cover as a softly rounded square, or an accent-washed tile with a music note when there is none. */
    private static Bitmap tile(Bitmap art, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF all = new RectF(0, 0, px, px);
        float radius = px * .22f;
        canvas.drawRoundRect(all, radius, radius, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        if (art != null) {
            int side = Math.min(art.getWidth(), art.getHeight());
            android.graphics.Rect from = new android.graphics.Rect((art.getWidth() - side) / 2, (art.getHeight() - side) / 2, (art.getWidth() + side) / 2, (art.getHeight() + side) / 2);
            canvas.drawBitmap(art, from, all, paint);
            return out;
        }
        paint.setShader(new LinearGradient(0, 0, px, px, (accent2 & 0x00FFFFFF) | 0x66000000, (accent & 0x00FFFFFF) | 0x33000000, Shader.TileMode.CLAMP));
        canvas.drawRect(all, paint);
        paint.setShader(null);
        paint.setXfermode(null);
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
     * The 4x2 banner, like the one on Home: the cover fills the card and is dimmed evenly with the deep colour so the
     * text on it stays readable. With no cover, a soft accent wash and a music note.
     */
    private static Bitmap banner(Bitmap art) {
        int w = 560, h = 280;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawColor(background);
        int rgb = background & 0x00FFFFFF;
        if (art != null) {
            int side = Math.min(art.getWidth(), art.getHeight());
            // The cover, cropped to the card's shape, from its upper part (faces and titles usually sit there).
            int cropH = side / 2, top = Math.max(0, (art.getHeight() - cropH) / 3);
            canvas.drawBitmap(art, new android.graphics.Rect(0, top, art.getWidth(), Math.min(art.getHeight(), top + art.getWidth() / 2)), new RectF(0, 0, w, h), paint);
        } else {
            paint.setShader(new LinearGradient(0, 0, w, h, (accent2 & 0x00FFFFFF) | 0x59000000, (accent & 0x00FFFFFF) | 0x14000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setColor((accent & 0x00FFFFFF) | 0xCC000000);
            float u = h / 100f, x = w * .5f;
            canvas.drawOval(new RectF(x, 50 * u, x + 22 * u, 66 * u), paint);
            canvas.drawRect(x + 17 * u, 20 * u, x + 22 * u, 58 * u, paint);
            Path flag = new Path();
            flag.moveTo(x + 17 * u, 20 * u); flag.lineTo(x + 42 * u, 28 * u); flag.lineTo(x + 42 * u, 38 * u); flag.lineTo(x + 17 * u, 32 * u); flag.close();
            canvas.drawPath(flag, paint);
        }
        // An even dim over the whole cover (no fade), enough for white text to read on any art.
        paint.setShader(null);
        paint.setColor(rgb | (art != null ? 0x73000000 : 0));
        canvas.drawRect(0, 0, w, h, paint);
        return out;
    }

    /**
     * The widget's backdrop, in Spatch's player style: the cover blown up and blurred, then washed with the deep
     * colour, solid behind the text on the left and letting the cover's colour through on the right.
     */
    private static Bitmap backdrop(Bitmap art) {
        int w = 120, h = 48;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawColor(background);
        if (art != null) {
            // Down to a few pixels and back up: a cheap blur.
            Bitmap tiny = Bitmap.createScaledBitmap(art, 10, 10, true);
            canvas.drawBitmap(tiny, null, new RectF(0, -(w - h) / 2f, w, h + (w - h) / 2f), paint);
        }
        int top = (background & 0x00FFFFFF) | (art != null ? 0x80000000 : 0xE6000000), low = (background & 0x00FFFFFF) | (art != null ? 0xB8000000 : 0xFF000000);
        paint.setShader(new LinearGradient(0, 0, 0, h, top, low, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        return out;
    }

    // The player's own shapes (app/src/shapes.rs): glyphs on a 24-unit grid, filled polygons or round-capped strokes.
    private static final float[][] G_PLAY = { { 7, 4, 20, 12, 7, 20 } };
    private static final float[][] G_PAUSE = { { 6, 5, 10, 5, 10, 19, 6, 19 }, { 14, 5, 18, 5, 18, 19, 14, 19 } };
    private static final float[][] G_PREVIOUS = { { 19, 4, 7, 12, 19, 20 } };
    private static final float[][] G_NEXT = { { 5, 4, 17, 12, 5, 20 } };

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
        if (kind == 0) glyph(new Canvas(out), G_PREVIOUS, new float[] { 4, 5, 4, 19 }, 0xFFFFFFFF, at, at, box);
        else glyph(new Canvas(out), G_NEXT, new float[] { 20, 5, 20, 19 }, 0xFFFFFFFF, at, at, box);
        return out;
    }

    /**
     * The player's scallop play button: a 120-point polygon whose radius is 0.91 + 0.055 cos(lobes * angle), five
     * lobes at rest and nine while playing (`shape` blends the two), with the glyph at half its size.
     */
    private static Bitmap playButton(int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = px / 2f;
        Path scallop = new Path();
        for (int i = 0; i < 120; i++) {
            double a = i / 120.0 * 2 * Math.PI;
            double five = .91 + .055 * Math.cos(a * 5), nine = .91 + .055 * Math.cos(a * 9);
            float rad = (float) (five + (nine - five) * shape) * r;
            float x = r + (float) Math.cos(a) * rad, y = r + (float) Math.sin(a) * rad;
            if (i == 0) scallop.moveTo(x, y); else scallop.lineTo(x, y);
        }
        scallop.close();
        paint.setColor(accent);
        canvas.drawPath(scallop, paint);
        glyph(canvas, playing ? G_PAUSE : G_PLAY, null, onAccent, r - r * .5f, r - r * .5f, r);
        return out;
    }

    /** The ids of one slide's views: backdrop, cover, label, title, artist (the framework's own ids are borrowed for the second). */
    private static int[] slide(Context context, int k) {
        return k == 0
            ? new int[] { android.R.id.icon, id(context, "coverart", "id"), android.R.id.text1, id(context, "title", "id"), id(context, "artist", "id") }
            : new int[] { android.R.id.icon2, android.R.id.icon1, android.R.id.text2, android.R.id.title, android.R.id.summary };
    }

    private static void fill(RemoteViews v, int[] ids, Snap s, boolean small) {
        v.setImageViewBitmap(ids[1], small ? s.tile : s.banner);
        if (small) v.setImageViewBitmap(ids[0], s.backdrop);
        else {
            v.setTextViewText(ids[2], title.isEmpty() ? "SPATCH" : playing ? "NOW PLAYING" : "PAUSED");
            v.setTextColor(ids[2], accent);
        }
        v.setTextViewText(ids[3], s.title);
        v.setTextColor(ids[3], 0xFFFFFFFF);
        v.setTextViewText(ids[4], s.artist);
        v.setTextColor(ids[4], 0xCCFFFFFF);
    }

    private static RemoteViews views(Context context, boolean small) {
        RemoteViews v = new RemoteViews(context.getPackageName(), id(context, small ? "spatch_widget_small" : "spatch_widget", "layout"));
        fill(v, slide(context, shown), cur, small);
        fill(v, slide(context, shown ^ 1), prev == null ? cur : prev, small);
        v.setDisplayedChild(android.R.id.widget_frame, shown);
        int prevButton = id(context, "btn_prev_normal", "id"), next = id(context, "btn_next_normal", "id"), play = id(context, "btn_play_normal", "id");
        v.setImageViewBitmap(prevButton, skip(0, small ? 108 : 108));
        v.setImageViewBitmap(next, skip(1, small ? 108 : 108));
        v.setImageViewBitmap(play, playButton(small ? 138 : 162));
        v.setOnClickPendingIntent(prevButton, broadcast(context, PREVIOUS));
        v.setOnClickPendingIntent(next, broadcast(context, NEXT));
        v.setOnClickPendingIntent(play, broadcast(context, TOGGLE));
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) v.setOnClickPendingIntent(android.R.id.widget_frame, PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        attach(context);
        WORK.execute(() -> refresh(context.getApplicationContext(), true));
    }

    @Override public void onReceive(Context context, Intent intent) {
        attach(context);
        String action = intent.getAction();
        if (!ready && action != null && action.startsWith("spatch.widget.")) return; // nothing is running to take the tap
        if (PREVIOUS.equals(action)) SpatchUi.widgetCommand(PREVIOUS_ACTION);
        else if (NEXT.equals(action)) SpatchUi.widgetCommand(NEXT_ACTION);
        else if (TOGGLE.equals(action)) SpatchUi.widgetCommand(playing ? PAUSE_ACTION : RESUME_ACTION);
        else super.onReceive(context, intent);
    }
}
