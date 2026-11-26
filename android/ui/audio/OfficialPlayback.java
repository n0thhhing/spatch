package spatch;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import java.util.Arrays;
import java.util.List;

/** Optional transport/state adapter. The official app owns audio, ads and account restrictions. */
public final class OfficialPlayback extends NotificationListenerService {
    static final String PACKAGE = "com.spotify.music";
    private static volatile Context app;
    private static volatile MediaController controller;
    private static OfficialPlayback listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long revision = System.currentTimeMillis();
    private static String[] previous;

    static void init(Context context) { app = context.getApplicationContext(); }
    static boolean enabled() {
        Context c = app;
        return c != null && c.getSharedPreferences("spatch_ui", MODE_PRIVATE).getBoolean("official_playback", false);
    }
    static boolean installed() {
        Context c = app;
        if (c == null || PACKAGE.equals(c.getPackageName())) return false;
        try { c.getPackageManager().getPackageInfo(PACKAGE, 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }
    static boolean allowed() {
        Context c = app;
        if (c == null) return false;
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationListenerAccessGranted(new ComponentName(c, OfficialPlayback.class));
    }
    static String status() {
        if (!installed()) return "Install official Spotify alongside the clone";
        if (!allowed()) return "Allow notification access for the Spotify playback fallback";
        if (controller == null) return "Open official Spotify and start playback to connect";
        return "Connected to official Spotify · normal ads and account restrictions apply";
    }
    static void configure(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(context, OfficialPlayback.class).flattenToString());
        try { context.startActivity(intent); }
        catch (android.content.ActivityNotFoundException e) {
            context.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }
    static void setEnabled(boolean value) {
        // Do not leave the old backend playing after switching away from it.
        MediaController old = controller;
        if (!value && old != null) try { old.getTransportControls().pause(); }
        catch (RuntimeException ignored) { /* A destroyed session is already disconnected. */ }
        app.getSharedPreferences("spatch_ui", MODE_PRIVATE).edit().putBoolean("official_playback", value).apply();
        MAIN.post(() -> {
            bind(null);
            if (listener != null) listener.watch();
            else if (value && allowed()) requestRebind(new ComponentName(app, OfficialPlayback.class));
            SpatchUi.onNativeChange();
        });
    }

    private static final MediaController.Callback CALLBACK = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { changed(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { changed(); }
        @Override public void onSessionDestroyed() {
            bind(null);
            if (listener != null) listener.discover();
            changed();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = ignored -> discover();
    private boolean watching;

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        init(this);
        SpatchWidget.attach(this);
        listener = this;
        watch();
    }
    @Override public void onListenerDisconnected() {
        stop();
        super.onListenerDisconnected();
    }
    @Override public void onDestroy() {
        stop();
        super.onDestroy();
    }
    private void stop() {
        if (watching) {
            getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessions);
            watching = false;
        }
        if (listener == this) { listener = null; bind(null); changed(); }
    }
    private void watch() {
        MediaSessionManager manager = getSystemService(MediaSessionManager.class);
        if (watching && !enabled()) { manager.removeOnActiveSessionsChangedListener(sessions); watching = false; }
        if (!enabled()) { bind(null); return; }
        try {
            if (!watching) {
                manager.addOnActiveSessionsChangedListener(sessions, new ComponentName(this, OfficialPlayback.class), MAIN);
                watching = true;
            }
            discover();
        } catch (SecurityException e) { bind(null); changed(); }
    }
    private void discover() {
        if (!enabled()) { bind(null); return; }
        try {
            List<MediaController> active = getSystemService(MediaSessionManager.class)
                .getActiveSessions(new ComponentName(this, OfficialPlayback.class));
            MediaController selected = null;
            for (MediaController candidate : active) if (PACKAGE.equals(candidate.getPackageName())) { selected = candidate; break; }
            bind(selected);
        } catch (SecurityException e) { bind(null); }
        changed();
    }
    private static void bind(MediaController next) {
        MediaController old = controller;
        if (old == null && next == null || old != null && next != null && old.getSessionToken().equals(next.getSessionToken())) return;
        if (old != null) old.unregisterCallback(CALLBACK);
        controller = next;
        if (next != null) next.registerCallback(CALLBACK, MAIN);
    }
    private static void changed() { if (enabled()) SpatchUi.onNativeChange(); }

    /** Matches nativePlayer's 20-field contract; a disconnected backend clears stale native state. */
    static synchronized String[] state(long seen) {
        MediaController c = allowed() ? controller : null;
        String[] fields = fields(c == null ? null : c.getMetadata(), c == null ? null : c.getPlaybackState(), SystemClock.elapsedRealtime());
        if (!Arrays.equals(fields, previous)) { previous = fields.clone(); revision++; }
        if (seen == revision) return null;
        fields[0] = Long.toString(revision);
        return fields;
    }
    static String[] fields(MediaMetadata metadata, PlaybackState state, long now) {
        String[] f = new String[20];
        Arrays.fill(f, "");
        f[0] = "0";
        if (metadata != null) {
            f[1] = text(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (!f[1].startsWith("spotify:")) f[1] = text(metadata, MediaMetadata.METADATA_KEY_MEDIA_URI);
            f[2] = text(metadata, MediaMetadata.METADATA_KEY_TITLE);
            f[3] = text(metadata, MediaMetadata.METADATA_KEY_ARTIST);
            f[4] = text(metadata, MediaMetadata.METADATA_KEY_ALBUM);
            f[5] = text(metadata, MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (f[5].isEmpty()) f[5] = text(metadata, MediaMetadata.METADATA_KEY_ART_URI);
            if (f[5].isEmpty()) f[5] = text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        }
        int s = state == null ? PlaybackState.STATE_NONE : state.getState();
        // Ads may have no Spotify URI but still need working pause/resume controls.
        if (f[1].isEmpty() && (metadata != null || state != null)) f[1] = "official:session";
        f[6] = Boolean.toString(s == PlaybackState.STATE_PLAYING);
        f[7] = "false"; f[8] = "0";
        long duration = metadata == null ? 0 : Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        long position = state == null ? 0 : Math.max(0, state.getPosition());
        if (state != null && s == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0)
            position += (long) (Math.max(0, now - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        f[9] = Long.toString(Math.max(0, duration > 0 ? Math.min(duration, position) : position));
        f[10] = Long.toString(duration);
        f[11] = "Official Spotify";
        f[15] = Boolean.toString(s == PlaybackState.STATE_BUFFERING || s == PlaybackState.STATE_CONNECTING);
        long actions = state == null ? 0 : state.getActions();
        f[16] = Boolean.toString((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
        f[17] = Boolean.toString((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        return f;
    }
    private static String text(MediaMetadata m, String key) {
        String value = m.getString(key);
        return value == null ? "" : value;
    }
    static boolean supports(int action, long value, long actions) {
        long required;
        switch (action) {
            case 0: required = PlaybackState.ACTION_PAUSE; break;
            case 1: required = PlaybackState.ACTION_PLAY; break;
            case 2: required = value != 0 ? PlaybackState.ACTION_SEEK_TO : PlaybackState.ACTION_SKIP_TO_PREVIOUS; break;
            case 3: required = PlaybackState.ACTION_SKIP_TO_NEXT; break;
            case 4: if (value < 0) return false; required = PlaybackState.ACTION_SEEK_TO; break;
            default: return false;
        }
        return (actions & required) != 0 || (action == 0 || action == 1) && (actions & PlaybackState.ACTION_PLAY_PAUSE) != 0;
    }
    static boolean command(int action, long value, String uri) {
        if (!enabled() || !installed() || !allowed()) return false;
        MediaController c = controller;
        try {
            if (action == 7) return play(uri);
            PlaybackState state = c == null ? null : c.getPlaybackState();
            if (state == null) {
                if (action >= 1 && action <= 3) return sendMediaButton(action);
                return false;
            }
            if (!supports(action, value, state.getActions())) return false;
            MediaController.TransportControls transport = c.getTransportControls();
            switch (action) {
                case 0: transport.pause(); break;
                case 1: transport.play(); break;
                case 2: if (value != 0) transport.seekTo(0); else transport.skipToPrevious(); break;
                case 3: transport.skipToNext(); break;
                case 4: transport.seekTo(value); break;
                default: return false;
            }
            return true; // Android accepted dispatch; the callback, not this return value, confirms playback.
        } catch (RuntimeException e) { return false; }
    }
    static boolean validUri(String uri) {
        return uri != null && uri.matches("spotify:(track|album|playlist|artist|episode|show):[A-Za-z0-9]+")
            || "spotify:collection:tracks".equals(uri) || "spotify:collection".equals(uri)
            || uri != null && uri.matches("spotify:user:[^:/?#]+:(collection(:tracks)?|playlist:[A-Za-z0-9]+)");
    }
    static boolean play(String uri) {
        if (!validUri(uri)) return false;
        if (uri.startsWith("spotify:user:")) {
            int playlist = uri.indexOf(":playlist:", 13);
            uri = playlist >= 0 ? "spotify:playlist:" + uri.substring(playlist + 10) : "spotify:collection:tracks";
        } else if (uri.equals("spotify:collection")) uri = "spotify:collection:tracks";
        // Official Spotify's MediaSession leaves onPlayFromUri unimplemented and rejects onPlayFromMediaId
        // for un-allowlisted packages. A visible, package-scoped ACTION_VIEW handoff is the only working route.
        try {
            app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (android.content.ActivityNotFoundException e) { return false; }
    }
    static boolean sendMediaButton(int action) {
        Context c = app;
        return c != null && sendMediaButton(c, PACKAGE, action);
    }
    static boolean sendMediaButton(Context context, String pkg, int action) {
        int keyCode;
        switch (action) {
            case 0: keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case 1: keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case 2: keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case 3: keyCode = android.view.KeyEvent.KEYCODE_MEDIA_NEXT; break;
            default: return false;
        }
        try {
            ComponentName receiver = new ComponentName(pkg, "com.spotify.mediasession.mediasession.receiver.MediaButtonReceiver");
            for (int keyAction : new int[] { android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP }) {
                Intent media = new Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(receiver)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(keyAction, keyCode));
                context.sendBroadcast(media);
            }
            return true;
        } catch (RuntimeException e) { return false; }
    }
}
