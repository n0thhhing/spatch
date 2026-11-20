package spatch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.RemoteViews;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spatch's own home-screen widget, listed beside Spotify's (which is left alone). It borrows Spotify's widget layout
 * and ids, looked up by name, so the patch adds no resources: cover, title, artist and the previous / play / next
 * buttons. State arrives from the Rust player whenever it changes (SpatchUi.onNativeChange), even with the app closed.
 */
public final class SpatchWidget extends AppWidgetProvider {
    private static final String PREVIOUS = "spatch.widget.PREVIOUS", TOGGLE = "spatch.widget.TOGGLE", NEXT = "spatch.widget.NEXT";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static volatile Context app;
    private static long seen = -1;
    private static String title = "", artist = "", coverUrl = "";
    private static boolean playing;
    private static boolean placed;
    private static long checkedAt;
    private static Bitmap cover;

    /** True once the UI has started, which means the Rust library is loaded; before that no native method may run. */
    private static volatile boolean ready;

    /** Remembers a context to draw with; the receiver calls this, and the UI does too, which also marks the Rust app ready. */
    static void attach(Context context) {
        if (app == null) app = context.getApplicationContext();
    }

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
                if (!url.equals(coverUrl)) { coverUrl = url; cover = fetch(url); }
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
                Bitmap full = BitmapFactory.decodeStream(in);
                return full == null ? null : Bitmap.createScaledBitmap(full, 256, 256, true);
            } finally {
                c.disconnect();
            }
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static int id(Context context, String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    private static PendingIntent broadcast(Context context, String action) {
        Intent intent = new Intent(context, SpatchWidget.class).setAction(action);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static RemoteViews views(Context context) {
        RemoteViews v = new RemoteViews(context.getPackageName(), id(context, "widget", "layout"));
        v.setTextViewText(id(context, "title", "id"), title.isEmpty() ? "Nothing playing" : title);
        v.setTextViewText(id(context, "artist", "id"), artist);
        int art = id(context, "coverart", "id");
        if (cover != null) v.setImageViewBitmap(art, cover);
        // Spotify's layout has a dimmed and a live copy of each button; show the live ones.
        for (String gone : new String[] { "btn_prev_disabled", "btn_play_disabled", "btn_pause_disabled", "btn_next_disabled" })
            v.setViewVisibility(id(context, gone, "id"), View.GONE);
        int prev = id(context, "btn_prev_normal", "id"), next = id(context, "btn_next_normal", "id");
        int play = id(context, "btn_play_normal", "id"), pause = id(context, "btn_pause_normal", "id");
        v.setViewVisibility(prev, View.VISIBLE);
        v.setViewVisibility(next, View.VISIBLE);
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
        if (PREVIOUS.equals(action)) SpatchUi.widgetCommand(2);
        else if (NEXT.equals(action)) SpatchUi.widgetCommand(3);
        else if (TOGGLE.equals(action)) SpatchUi.widgetCommand(playing ? 0 : 1);
        else super.onReceive(context, intent);
    }
}
