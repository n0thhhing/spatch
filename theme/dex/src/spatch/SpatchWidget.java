package spatch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.SizeF;
import android.view.KeyEvent;
import android.widget.RemoteViews;
import java.util.HashMap;
import java.util.Map;

/**
 * Spatch's take on Spotify's resizable Now Playing widget (NpvWidgetProvider, 4x1 and 4x2). Glance's
 * RemoteViews are swapped for Spatch layouts at the hand-off (p.rx5 -> replace) and refreshed from
 * Spotify's own media session (attach, from MediaSessionImplApi23). The launcher animates the scallop
 * and equaliser itself (indeterminate progress drawables), so they move while no app code runs.
 */
public final class SpatchWidget extends MediaController.Callback implements Runnable {
    private static final String PROVIDER = "com.spotify.proactiveplatforms.npvwidget.NpvWidgetProvider";
    private static final String BUTTONS = "com.spotify.mediasession.mediasession.receiver.MediaButtonReceiver";
    private static final int VISIBLE = 0, GONE = 8;
    private static MediaController current;
    /** Widget work (bitmaps, RemoteViews, binder calls) stays off Spotify's main thread. */
    private static Handler worker;
    private static final SpatchWidget PUSH = new SpatchWidget(null);
    private final MediaController controller;

    private SpatchWidget(MediaController controller) {
        this.controller = controller;
    }

    /** Called with every media session Spotify creates; the one that plays or has metadata wins. */
    public static void attach(MediaSession session) {
        Context app = Settings.app;
        if (app == null || session == null || Build.VERSION.SDK_INT < 31) return;
        try {
            MediaController c = new MediaController(app, session.getSessionToken());
            c.registerCallback(new SpatchWidget(c), worker());
            if (current == null) current = c;
        } catch (RuntimeException ignored) {
            // A session we cannot follow just leaves the widget to Spotify.
        }
    }

    private static synchronized Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("spatch-widget");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    public void onMetadataChanged(MediaMetadata metadata) {
        if (metadata != null) current = controller;
        schedule();
    }

    public void onPlaybackStateChanged(PlaybackState state) {
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) current = controller;
        schedule();
    }

    /** A skip or play/pause fires several callbacks in a burst; rebuild once, after it settles. */
    private static void schedule() {
        Handler h = worker();
        h.removeCallbacks(PUSH);
        h.postDelayed(PUSH, 100);
    }

    public void run() {
        push();
    }

    /** Glance hand-off: Spatch's views for the Now Playing widget once a session is known. */
    public static RemoteViews replace(int widgetId, RemoteViews glance) {
        Context app = Settings.app;
        if (app == null || current == null || Build.VERSION.SDK_INT < 31) return glance;
        try {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(app).getAppWidgetInfo(widgetId);
            if (info == null || !PROVIDER.equals(info.provider.getClassName())) return glance;
            RemoteViews views = build(app);
            return views != null ? views : glance;
        } catch (RuntimeException e) {
            return glance;
        }
    }

    private static void push() {
        Context app = Settings.app;
        if (app == null) return;
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(app);
            int[] ids = manager.getAppWidgetIds(new ComponentName(app, PROVIDER));
            RemoteViews views = ids == null || ids.length == 0 ? null : build(app);
            if (views != null) manager.updateAppWidget(ids, views);
        } catch (RuntimeException ignored) {
            // The launcher may be gone or restarting; the next change retries.
        }
    }

    private static RemoteViews build(Context app) {
        MediaController c = current;
        MediaMetadata m = c == null ? null : c.getMetadata();
        if (m == null) return null;
        PlaybackState s = c.getPlaybackState();
        int state = s == null ? 0 : s.getState();
        boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != null && Math.max(art.getWidth(), art.getHeight()) > 320) {
            art = Bitmap.createScaledBitmap(art, 320, 320, true); // keep the RemoteViews bitmap small
        }
        Bitmap backdrop = art == null ? null : backdrop(art);
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        Map<SizeF, RemoteViews> sizes = new HashMap<SizeF, RemoteViews>();
        sizes.put(new SizeF(220, 50), views(app, "spatch_widget", title, artist, art, backdrop, playing));
        sizes.put(new SizeF(220, 110), views(app, "spatch_widget_large", title, artist, art, backdrop, playing));
        return new RemoteViews(sizes);
    }

    /**
     * The player's backdrop idea at widget scale: the cover shrunk to a few pixels and smoothed back
     * up (a cheap, strong blur), under a theme-surface scrim so text stays readable.
     */
    private static Bitmap backdrop(Bitmap art) {
        Bitmap tiny = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(art, 48, 48, true), 10, 10, true);
        Bitmap soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(tiny, 40, 40, true), 200, 100, true);
        Bitmap out = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(soft, 0, 0, null);
        canvas.drawColor(0xb814121c);
        return out;
    }

    private static RemoteViews views(Context app, String layout, String title, String artist, Bitmap art, Bitmap backdrop, boolean playing) {
        Resources r = app.getResources();
        String pkg = app.getPackageName();
        RemoteViews v = new RemoteViews(pkg, r.getIdentifier(layout, "layout", pkg));
        int root = 0x01020000; // android.R.id.background
        v.setColorStateList(root, "setBackgroundTintList", ColorStateList.valueOf(0xff1d1a2a));
        v.setTextViewText(id(r, pkg, "title"), title == null ? "" : title);
        v.setTextColor(id(r, pkg, "title"), 0xfff3eeff);
        v.setTextViewText(id(r, pkg, "subtitle"), artist == null ? "" : artist);
        v.setTextColor(id(r, pkg, "subtitle"), 0xffbcb3dc);
        if (art != null) v.setImageViewBitmap(id(r, pkg, "artwork"), art);
        if (backdrop != null) v.setImageViewBitmap(id(r, pkg, "frame"), backdrop);
        ColorStateList accent = ColorStateList.valueOf(0xffd0bcff);
        // The launcher starts whichever animated scallop is shown: on play, 5 bumps morph to 8 and keep
        // turning; on pause, 8 settle back to 5. The equaliser bounces while playing.
        v.setViewVisibility(id(r, pkg, "spinner"), playing ? VISIBLE : GONE);
        v.setViewVisibility(id(r, pkg, "image"), playing ? GONE : VISIBLE);
        v.setViewVisibility(id(r, pkg, "header"), playing ? VISIBLE : GONE);
        v.setColorStateList(id(r, pkg, "spinner"), "setIndeterminateTintList", accent);
        for (String bar : new String[] {"text1", "text2", "icon_view", "image_view"}) { // equaliser bars
            v.setColorStateList(id(r, pkg, bar), "setIndeterminateTintList", accent);
        }
        v.setColorStateList(id(r, pkg, "image"), "setIndeterminateTintList", accent);
        v.setImageViewResource(id(r, pkg, "icon"), r.getIdentifier(playing ? "encore_icon_pause_24" : "encore_icon_play_24", "drawable", pkg));
        v.setInt(id(r, pkg, "icon"), "setColorFilter", 0xff14121c);
        v.setContentDescription(id(r, pkg, "play_button"), playing ? "Pause" : "Play");
        v.setInt(id(r, pkg, "previous_button"), "setColorFilter", 0xfff3eeff);
        v.setInt(id(r, pkg, "next_button"), "setColorFilter", 0xfff3eeff);
        v.setOnClickPendingIntent(id(r, pkg, "play_button"), key(app, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        v.setOnClickPendingIntent(id(r, pkg, "previous_button"), key(app, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        v.setOnClickPendingIntent(id(r, pkg, "next_button"), key(app, KeyEvent.KEYCODE_MEDIA_NEXT));
        Intent open = app.getPackageManager().getLaunchIntentForPackage(pkg);
        if (open != null) {
            v.setOnClickPendingIntent(root, PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return v;
    }

    private static int id(Resources r, String pkg, String name) {
        return r.getIdentifier(name, "id", pkg);
    }

    /** A media-button broadcast to Spotify's own receiver, as headsets and lock screens use. */
    private static PendingIntent key(Context app, int code) {
        Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(new ComponentName(app, BUTTONS))
            .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, code));
        return PendingIntent.getBroadcast(app, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
