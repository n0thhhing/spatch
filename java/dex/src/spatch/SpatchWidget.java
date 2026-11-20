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
public final class SpatchWidget extends AppWidgetProvider {
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
    /** Colours for the current cover: background, accent, and what sits on the accent. */
    private static int background = 0xFF15161C, accent = 0xFF9DB7FF, onAccent = 0xFF101218;

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
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, SpatchWidget.class));
            placed = ids.length > 0;
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
            for (int id : ids) manager.updateAppWidget(id, views(context));
        } catch (RuntimeException e) {
            android.util.Log.e("SpatchWidget", "refresh failed", e);
        }
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
        if (art == null) { background = 0xFF15161C; accent = 0xFF9DB7FF; onAccent = 0xFF101218; return; }
        Bitmap tiny = Bitmap.createScaledBitmap(art, 8, 8, true);
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int c = tiny.getPixel(x, y);
            r += Color.red(c); g += Color.green(c); b += Color.blue(c);
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 64), (int) (g / 64), (int) (b / 64), hsv);
        float hue = hsv[0], sat = hsv[1];
        background = Color.HSVToColor(new float[] { hue, Math.min(sat, .55f), .17f });
        accent = Color.HSVToColor(new float[] { hue, Math.max(.3f, Math.min(sat, .75f)), .96f });
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

    /** The cover as a rounded square, or Spatch's own tile of visualizer bars when there is none. */
    private static Bitmap tile(Bitmap art, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float radius = px * .14f;
        RectF all = new RectF(0, 0, px, px);
        if (art != null) {
            canvas.drawRoundRect(all, radius, radius, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            int side = Math.min(art.getWidth(), art.getHeight());
            android.graphics.Rect from = new android.graphics.Rect((art.getWidth() - side) / 2, (art.getHeight() - side) / 2, (art.getWidth() + side) / 2, (art.getHeight() + side) / 2);
            canvas.drawBitmap(art, from, all, paint);
            return out;
        }
        paint.setShader(new LinearGradient(0, 0, px, px, (accent & 0x00FFFFFF) | 0x66000000, (accent & 0x00FFFFFF) | 0x22000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(all, radius, radius, paint);
        paint.setShader(null);
        paint.setColor(accent);
        float[] heights = { .30f, .55f, .42f, .66f, .36f };
        float bar = px * .08f, gap = px * .055f, left = (px - (5 * bar + 4 * gap)) / 2;
        for (int i = 0; i < 5; i++) {
            float x = left + i * (bar + gap), h = px * heights[i];
            canvas.drawRoundRect(new RectF(x, (px - h) / 2, x + bar, (px + h) / 2), bar / 2, bar / 2, paint);
        }
        return out;
    }

    /** kind: 0 previous, 1 next, 2 play, 3 pause. Play and pause sit on an accent disc. */
    private static Bitmap icon(int kind, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean disc = kind >= 2;
        if (disc) {
            paint.setColor(accent);
            canvas.drawCircle(px / 2f, px / 2f, px / 2f, paint);
        }
        paint.setColor(disc ? onAccent : 0xFFFFFFFF);
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(px * .05f);
        float u = px / 100f, c = px / 2f;
        Path p = new Path();
        switch (kind) {
            case 0: // bar, then a triangle pointing left
                canvas.drawRoundRect(new RectF(30 * u, 32 * u, 37 * u, 68 * u), 3 * u, 3 * u, paint);
                p.moveTo(70 * u, 32 * u); p.lineTo(70 * u, 68 * u); p.lineTo(42 * u, c); p.close();
                break;
            case 1:
                canvas.drawRoundRect(new RectF(63 * u, 32 * u, 70 * u, 68 * u), 3 * u, 3 * u, paint);
                p.moveTo(30 * u, 32 * u); p.lineTo(30 * u, 68 * u); p.lineTo(58 * u, c); p.close();
                break;
            case 2:
                p.moveTo(40 * u, 31 * u); p.lineTo(40 * u, 69 * u); p.lineTo(70 * u, c); p.close();
                break;
            default:
                canvas.drawRoundRect(new RectF(33 * u, 31 * u, 44 * u, 69 * u), 3 * u, 3 * u, paint);
                canvas.drawRoundRect(new RectF(56 * u, 31 * u, 67 * u, 69 * u), 3 * u, 3 * u, paint);
        }
        canvas.drawPath(p, paint);
        return out;
    }

    private static RemoteViews views(Context context) {
        RemoteViews v = new RemoteViews(context.getPackageName(), id(context, "spatch_widget", "layout"));
        v.setInt(android.R.id.background, "setBackgroundColor", background);
        int titleView = id(context, "title", "id"), artistView = id(context, "artist", "id");
        v.setTextViewText(titleView, title.isEmpty() ? "Spatch" : title);
        v.setTextColor(titleView, 0xFFFFFFFF);
        v.setTextViewText(artistView, title.isEmpty() ? "Nothing playing" : artist);
        v.setTextColor(artistView, 0xB3FFFFFF);
        int art = id(context, "coverart", "id");
        v.setImageViewBitmap(art, tile(cover, 256));
        int prev = id(context, "btn_prev_normal", "id"), next = id(context, "btn_next_normal", "id");
        int play = id(context, "btn_play_normal", "id"), pause = id(context, "btn_pause_normal", "id");
        v.setImageViewBitmap(prev, icon(0, 112));
        v.setImageViewBitmap(next, icon(1, 112));
        v.setImageViewBitmap(play, icon(2, 132));
        v.setImageViewBitmap(pause, icon(3, 132));
        v.setViewVisibility(play, playing ? View.GONE : View.VISIBLE);
        v.setViewVisibility(pause, playing ? View.VISIBLE : View.GONE);
        v.setOnClickPendingIntent(prev, broadcast(context, PREVIOUS));
        v.setOnClickPendingIntent(next, broadcast(context, NEXT));
        v.setOnClickPendingIntent(play, broadcast(context, TOGGLE));
        v.setOnClickPendingIntent(pause, broadcast(context, TOGGLE));
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open != null) {
            PendingIntent launch = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            v.setOnClickPendingIntent(art, launch);
            v.setOnClickPendingIntent(id(context, "texts", "id"), launch);
        }
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
