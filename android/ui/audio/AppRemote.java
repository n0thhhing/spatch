package spatch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java Spotify Android App Remote IPC client (WAMP v2 JSON over Messenger + ServiceConnection).
 *
 * When a Spotify Developer Client ID is configured and authorized on-device for com.spotify.music,
 * Spatch can control playback, shuffle, repeat, queueing, seeking, in-context track selection, and
 * cover metadata directly in-process without switching apps.
 */
final class AppRemote {
    static final String BIND_ACTION = "com.spotify.mobile.appprotocol.action.BIND_PROTOCOL_SERVICE";
    static final String LEGACY_ACTION = "com.spotify.mobile.appprotocol.action.START_APP_PROTOCOL_SERVICE";
    static final String DEFAULT_REDIRECT = "spatch://callback";

    private static final String PREF_CLIENT_ID = "official_client_id";
    private static final String PREF_REDIRECT_URI = "official_redirect_uri";

    static final int MSG_HANDSHAKE = 1;
    static final int MSG_MESSAGE = 2;
    static final String KEY_BODY = "MESSAGE_BODY";

    static final int WAMP_HELLO = 1;
    static final int WAMP_WELCOME = 2;
    static final int WAMP_ABORT = 3;
    static final int WAMP_GOODBYE = 6;
    static final int WAMP_ERROR = 8;
    static final int WAMP_SUBSCRIBE = 32;
    static final int WAMP_SUBSCRIBED = 33;
    static final int WAMP_UNSUBSCRIBE = 34;
    static final int WAMP_EVENT = 36;
    static final int WAMP_CALL = 48;
    static final int WAMP_RESULT = 50;

    private static final String TOPIC_PLAYER_STATE = "com.spotify.player_state";
    private static final String TOPIC_CURRENT_CONTEXT = "com.spotify.current_context";

    private static final Object LOCK = new Object();
    private static final AtomicLong NEXT_REQ_ID = new AtomicLong(1);
    private static final AtomicLong REVISION = new AtomicLong(1);
    private static final Map<Long, String> PENDING_SUBS = new ConcurrentHashMap<>();
    private static final Map<Long, String> SUBS = new ConcurrentHashMap<>();
    private static final Map<Long, CompletableFuture<Boolean>> PENDING_CALLS = new ConcurrentHashMap<>();
    private static final Map<Long, CompletableFuture<Boolean>> CONNECT_WAITERS = new ConcurrentHashMap<>();

    private static volatile SharedPreferences prefs;
    private static volatile Context appContext;
    private static volatile Messenger outgoing;
    private static volatile Messenger incoming;
    private static volatile ServiceConnection connection;
    private static volatile boolean bound;
    private static volatile boolean connecting;
    private static volatile boolean welcomed;
    private static volatile boolean pendingInteractive;
    private static volatile long pendingStateCallId;
    private static volatile String lastError = "";

    private static volatile String uri = "";
    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile String album = "";
    private static volatile String image = "";
    private static volatile String albumUri = "";
    private static volatile String artistUri = "";
    private static volatile String contextUri = "";
    private static volatile String contextTitle = "";
    private static volatile boolean playing;
    private static volatile boolean buffering;
    private static volatile boolean shuffle;
    private static volatile int repeat;
    private static volatile long duration;
    private static volatile long position;
    private static volatile long anchorMs;
    private static volatile float playbackSpeed = 1f;
    private static volatile boolean canSkipNext = true;
    private static volatile boolean canSkipPrev = true;
    private static volatile boolean canToggleShuffle = true;
    private static volatile boolean canRepeatTrack = true;
    private static volatile boolean canRepeatContext = true;
    private static volatile boolean canSeek = true;

    private AppRemote() {}

    static void setPrefs(SharedPreferences sharedPrefs) {
        if (sharedPrefs != null) prefs = sharedPrefs;
    }

    static SharedPreferences prefs() {
        SharedPreferences p = prefs;
        if (p != null) return p;
        Context c = appContext;
        if (c != null) {
            p = c.getSharedPreferences("spatch_ui", Context.MODE_PRIVATE);
            prefs = p;
        }
        return p;
    }

    static String clientId() {
        SharedPreferences p = prefs();
        return p == null ? "" : p.getString(PREF_CLIENT_ID, "").trim();
    }

    static void setClientId(String value) {
        SharedPreferences p = prefs();
        if (p == null) return;
        String clean = value == null ? "" : value.trim();
        p.edit().putString(PREF_CLIENT_ID, clean).apply();
        if (clean.isEmpty()) disconnect();
    }

    static String redirectUri() {
        SharedPreferences p = prefs();
        String val = p == null ? "" : p.getString(PREF_REDIRECT_URI, "").trim();
        return val.isEmpty() ? DEFAULT_REDIRECT : val;
    }

    static void setRedirectUri(String value) {
        SharedPreferences p = prefs();
        if (p == null) return;
        String clean = value == null ? "" : value.trim();
        p.edit().putString(PREF_REDIRECT_URI, clean.isEmpty() ? DEFAULT_REDIRECT : clean).apply();
    }

    static boolean configured() {
        return !clientId().isEmpty();
    }

    static boolean connected() {
        return welcomed;
    }

    static boolean isConnecting() {
        return connecting;
    }

    static String lastError() {
        return lastError;
    }

    static synchronized void init(Context ctx) {
        if (ctx != null) appContext = ctx.getApplicationContext();
        if (OfficialPlayback.enabled() && configured() && !connected() && !connecting && appContext != null) {
            connect(appContext, false);
        }
    }

    static boolean connect(Context ctx, boolean interactive) {
        Context context = ctx != null ? ctx.getApplicationContext() : appContext;
        if (context == null || !configured() || !OfficialPlayback.installed(context)) return false;
        appContext = context;
        synchronized (LOCK) {
            if (welcomed) return true;
            if (connecting && !interactive) return true;
            if (interactive && connection != null) {
                unbindLocked();
            }
            pendingInteractive = interactive;
            connecting = true;
            lastError = "";
            if (incoming == null) {
                incoming = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
                    handleServiceMessage(msg);
                    return true;
                }));
            }
            final ServiceConnection conn = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    if (service == null) {
                        failConnect("Empty binder");
                        return;
                    }
                    outgoing = new Messenger(service);
                    Message hs = Message.obtain(null, MSG_HANDSHAKE);
                    hs.replyTo = incoming;
                    try {
                        outgoing.send(hs);
                    } catch (RemoteException e) {
                        failConnect(e.getMessage() == null ? "Handshake failed" : e.getMessage());
                    }
                }

                @Override public void onServiceDisconnected(ComponentName name) {
                    handleDisconnect("Service disconnected");
                }
            };
            connection = conn;
            boolean ok = false;
            try {
                Intent intent = new Intent(BIND_ACTION).setPackage(OfficialPlayback.PACKAGE);
                ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE);
            } catch (Throwable ignored) {}
            if (!ok) {
                try {
                    Intent legacy = new Intent(LEGACY_ACTION).setPackage(OfficialPlayback.PACKAGE);
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(legacy);
                    else context.startService(legacy);
                    ok = context.bindService(legacy, conn, Context.BIND_AUTO_CREATE);
                } catch (Throwable t) {
                    lastError = t.getMessage() == null ? "Bind failed" : t.getMessage();
                    ok = false;
                }
            }
            bound = ok;
            if (!ok) {
                connection = null;
                connecting = false;
                if (lastError.isEmpty()) lastError = "Spotify App Remote service unavailable";
                notifyUi();
            }
            return ok;
        }
    }

    static boolean connectBlocking(Context ctx, boolean interactive, long timeoutMs) {
        if (connected()) return true;
        long key = NEXT_REQ_ID.getAndIncrement();
        CompletableFuture<Boolean> waiter = new CompletableFuture<>();
        CONNECT_WAITERS.put(key, waiter);
        if (!connect(ctx, interactive)) {
            CONNECT_WAITERS.remove(key);
            return false;
        }
        try {
            return waiter.get(Math.max(200L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            CONNECT_WAITERS.remove(key);
            return connected();
        }
    }

    static synchronized void disconnect() {
        synchronized (LOCK) {
            unbindLocked();
        }
        lastError = "";
        notifyUi();
    }

    private static void unbindLocked() {
        ServiceConnection old = connection;
        connection = null;
        if (old != null && appContext != null && bound) {
            try { appContext.unbindService(old); } catch (Throwable ignored) {}
        }
        bound = false;
        connecting = false;
        welcomed = false;
        outgoing = null;
        PENDING_SUBS.clear();
        SUBS.clear();
        failPendingCalls();
        resolveConnectWaiters(false);
    }

    private static void handleDisconnect(String reason) {
        synchronized (LOCK) {
            bound = false;
            connecting = false;
            welcomed = false;
            outgoing = null;
            connection = null;
            if (reason != null && !reason.isEmpty()) lastError = reason;
            PENDING_SUBS.clear();
            SUBS.clear();
            failPendingCalls();
            resolveConnectWaiters(false);
        }
        bumpRevision();
    }

    private static void failConnect(String error) {
        handleDisconnect(error);
    }

    private static void handleServiceMessage(Message msg) {
        if (msg == null) return;
        if (msg.what == MSG_HANDSHAKE) {
            if (msg.replyTo != null) outgoing = msg.replyTo;
            sendWamp(buildHello(clientId(), redirectUri(), pendingInteractive));
            return;
        }
        if (msg.what != MSG_MESSAGE) return;
        Bundle data = msg.getData();
        if (data == null) return;
        byte[] body = data.getByteArray(KEY_BODY);
        if (body == null || body.length == 0) return;
        try {
            JSONArray frame = new JSONArray(new String(body, StandardCharsets.UTF_8));
            onWampMessage(frame);
        } catch (JSONException e) {
            lastError = "Malformed protocol message";
        }
    }

    static JSONArray buildHello(String id, String redirect, boolean showAuthView) {
        JSONObject roles = new JSONObject();
        JSONObject info = new JSONObject();
        JSONObject extras = new JSONObject();
        try {
            roles.put("caller", new JSONObject());
            roles.put("subscriber", new JSONObject());
            info.put("protocol_version", 1);
            info.put("id", id == null ? "" : id);
            info.put("name", "Spatch");
            info.put("model", Build.MODEL == null ? "Android" : Build.MODEL);
            info.put("category", "app");
            info.put("version", "0.8.0");
            info.put("image_type", "jpeg");
            info.put("default_image_width", 440);
            info.put("default_image_height", 440);
            info.put("default_thumbnail_image_width", 180);
            info.put("default_thumbnail_image_height", 180);
            extras.put("redirect_uri", (redirect == null || redirect.trim().isEmpty()) ? DEFAULT_REDIRECT : redirect.trim());
            extras.put("show_auth_view", String.valueOf(showAuthView));
            extras.put("scopes", "app-remote-control");
            JSONObject details = new JSONObject();
            details.put("roles", roles);
            details.put("info", info);
            details.put("authmethods", new JSONArray().put("appid"));
            details.put("authid", id == null ? "" : id);
            details.put("extras", extras);
            return new JSONArray().put(WAMP_HELLO).put("spotify").put(details);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static void onWampMessage(JSONArray frame) {
        if (frame == null || frame.length() == 0) return;
        int code = frame.optInt(0, -1);
        switch (code) {
            case WAMP_WELCOME:
                welcomed = true;
                connecting = false;
                pendingInteractive = false;
                lastError = "";
                resolveConnectWaiters(true);
                subscribe(TOPIC_PLAYER_STATE);
                subscribe(TOPIC_CURRENT_CONTEXT);
                pendingStateCallId = sendCall("com.spotify.get_player_state", null);
                bumpRevision();
                break;
            case WAMP_SUBSCRIBED: {
                long reqId = frame.optLong(1);
                long subId = frame.optLong(2);
                String topic = PENDING_SUBS.remove(reqId);
                if (topic != null) SUBS.put(subId, topic);
                break;
            }
            case WAMP_EVENT: {
                long subId = frame.optLong(1);
                String topic = SUBS.get(subId);
                JSONObject payload = extractPayload(frame, 4, 5);
                if (payload != null) {
                    if (TOPIC_PLAYER_STATE.equals(topic)) parsePlayerState(payload);
                    else if (TOPIC_CURRENT_CONTEXT.equals(topic)) parseContext(payload);
                    bumpRevision();
                }
                break;
            }
            case WAMP_RESULT: {
                long reqId = frame.optLong(1);
                JSONObject payload = extractPayload(frame, 3, 4);
                if (reqId == pendingStateCallId && payload != null) {
                    parsePlayerState(payload);
                    bumpRevision();
                }
                CompletableFuture<Boolean> f = PENDING_CALLS.remove(reqId);
                if (f != null) f.complete(true);
                break;
            }
            case WAMP_ERROR: {
                long reqId = frame.optLong(2);
                String errUri = frame.optString(4, "");
                lastError = formatError(errUri);
                PENDING_SUBS.remove(reqId);
                CompletableFuture<Boolean> f = PENDING_CALLS.remove(reqId);
                if (f != null) f.complete(false);
                bumpRevision();
                break;
            }
            case WAMP_ABORT:
            case WAMP_GOODBYE: {
                String reason = frame.optString(2, code == WAMP_ABORT ? "Connection aborted" : "Session ended");
                handleDisconnect(formatError(reason));
                break;
            }
            default:
                break;
        }
    }

    private static JSONObject extractPayload(JSONArray frame, int argsIndex, int kwargsIndex) {
        JSONObject kw = frame.optJSONObject(kwargsIndex);
        if (kw != null) return kw;
        JSONArray args = frame.optJSONArray(argsIndex);
        if (args != null && args.length() > 0) {
            JSONObject first = args.optJSONObject(0);
            if (first != null) return first;
        }
        return frame.optJSONObject(argsIndex);
    }

    static boolean parsePlayerState(JSONObject obj) {
        if (obj == null) return false;
        JSONObject track = obj.optJSONObject("track");
        if (track != null) {
            uri = track.optString("uri", "");
            title = track.optString("name", "");
            album = "";
            albumUri = "";
            JSONObject alb = track.optJSONObject("album");
            if (alb != null) {
                album = alb.optString("name", "");
                albumUri = alb.optString("uri", "");
            }
            artist = readArtists(track);
            artistUri = readArtistUri(track);
            String imageId = track.optString("image_id", "");
            image = OfficialPlayback.normalizeImageUri(imageId);
            duration = Math.max(0L, track.optLong("duration_ms", 0L));
        }
        boolean paused = obj.optBoolean("is_paused", false);
        double spd = obj.optDouble("playback_speed", paused ? 0.0 : 1.0);
        playing = !paused && spd > 0.0;
        playbackSpeed = (float) Math.max(0.0, spd);
        buffering = false;
        position = Math.max(0L, obj.optLong("playback_position", 0L));
        anchorMs = SystemClock.uptimeMillis();

        JSONObject opts = obj.optJSONObject("playback_options");
        if (opts != null) {
            shuffle = opts.optBoolean("shuffle", false);
            repeat = remoteToSpatchRepeat(opts.optInt("repeat", 0));
        }

        JSONObject res = obj.optJSONObject("playback_restrictions");
        if (res != null) {
            canSkipNext = res.optBoolean("can_skip_next", true);
            canSkipPrev = res.optBoolean("can_skip_prev", true);
            canRepeatTrack = res.optBoolean("can_repeat_track", true);
            canRepeatContext = res.optBoolean("can_repeat_context", true);
            canToggleShuffle = res.optBoolean("can_toggle_shuffle", true);
            canSeek = res.optBoolean("can_seek", true);
        }
        return true;
    }

    static void parseContext(JSONObject obj) {
        if (obj == null) return;
        String nextUri = obj.optString("uri", "");
        String nextTitle = obj.optString("title", "");
        if (!nextUri.isEmpty()) contextUri = nextUri;
        if (!nextTitle.isEmpty()) contextTitle = nextTitle;
    }

    private static String readArtists(JSONObject track) {
        JSONArray list = track.optJSONArray("artists");
        if (list != null && list.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.length(); i++) {
                JSONObject a = list.optJSONObject(i);
                String n = a == null ? "" : a.optString("name", "").trim();
                if (!n.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(n);
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        JSONObject single = track.optJSONObject("artist");
        return single == null ? "" : single.optString("name", "");
    }

    private static String readArtistUri(JSONObject track) {
        JSONObject single = track.optJSONObject("artist");
        String singleUri = single == null ? "" : single.optString("uri", "").trim();
        if (!singleUri.isEmpty()) return singleUri;
        JSONArray list = track.optJSONArray("artists");
        if (list != null && list.length() > 0) {
            JSONObject first = list.optJSONObject(0);
            if (first != null) return first.optString("uri", "").trim();
        }
        return "";
    }

    static String[] fields(long knownRevision, long nowMs) {
        long rev = REVISION.get();
        if (knownRevision == rev) return null;
        return new String[]{
            Long.toString(rev),
            uri,
            title,
            artist,
            album,
            image,
            Boolean.toString(playing),
            Boolean.toString(shuffle),
            Integer.toString(repeat),
            Long.toString(livePosition(nowMs)),
            Long.toString(duration),
            contextTitle,
            "",
            contextUri,
            "Official Spotify · App Remote",
            Boolean.toString(buffering),
            Boolean.toString(canSkipNext),
            Boolean.toString(canSkipPrev),
            albumUri,
            artistUri
        };
    }

    private static long livePosition(long nowMs) {
        if (!playing || anchorMs <= 0L) return position;
        long advanced = Math.round((nowMs - anchorMs) * (double) Math.max(0.5f, playbackSpeed));
        long est = position + Math.max(0L, advanced);
        return duration > 0L ? Math.min(duration, est) : est;
    }

    static boolean supports(int action) {
        if (!connected()) return false;
        switch (action) {
            case PlayerScreen.PAUSE:
            case PlayerScreen.RESUME:
            case PlayerScreen.PLAY:
            case PlayerScreen.SKIP_TO:
            case PlayerScreen.QUEUE_ADD:
                return true;
            case PlayerScreen.NEXT:
                return canSkipNext;
            case PlayerScreen.PREVIOUS:
                return canSkipPrev;
            case PlayerScreen.SEEK:
                return canSeek;
            case PlayerScreen.SHUFFLE:
                return canToggleShuffle;
            case PlayerScreen.REPEAT:
                return canRepeatContext || canRepeatTrack;
            default:
                return false;
        }
    }

    static boolean supportsShuffle() {
        return connected() && canToggleShuffle;
    }

    static boolean command(int action, long value, String target) {
        if (!connected() && (!configured() || !connectBlocking(appContext, false, 3500))) return false;
        switch (action) {
            case PlayerScreen.PAUSE:
                return callBlocking("com.spotify.set_playback_speed", jsonObj("playback_speed", 0));
            case PlayerScreen.RESUME:
                return callBlocking("com.spotify.set_playback_speed", jsonObj("playback_speed", 1));
            case PlayerScreen.NEXT:
                return callBlocking("com.spotify.skip_next", null);
            case PlayerScreen.PREVIOUS:
                return callBlocking("com.spotify.skip_previous", null);
            case PlayerScreen.SEEK:
                return callBlocking("com.spotify.set_playback_position", jsonObj("position_ms", Math.max(0L, value)));
            case PlayerScreen.SHUFFLE:
                return callBlocking("com.spotify.set_shuffle", jsonObj("shuffle", value != 0));
            case PlayerScreen.REPEAT:
                return callBlocking("com.spotify.set_repeat", jsonObj("repeat", spatchToRemoteRepeat((int) value)));
            case PlayerScreen.QUEUE_ADD: {
                String canonical = OfficialPlayback.canonicalUri(target);
                return !canonical.isEmpty() && callBlocking("com.spotify.queue_spotify_uri", jsonObj("uri", canonical));
            }
            case PlayerScreen.PLAY: {
                String canonical = OfficialPlayback.canonicalUri(target);
                if (canonical.isEmpty()) return callBlocking("com.spotify.set_playback_speed", jsonObj("playback_speed", 1));
                if (value > 0 && canToggleShuffle) callBlocking("com.spotify.set_shuffle", jsonObj("shuffle", true));
                return callBlocking("com.spotify.play_spotify_uri", jsonObj("uri", canonical));
            }
            case PlayerScreen.SKIP_TO: {
                String canonical = OfficialPlayback.canonicalUri(target);
                if (!canonical.isEmpty()) return playFrom("", canonical, (int) value);
                if (!contextUri.isEmpty()) return playFrom(contextUri, "", (int) value);
                return false;
            }
            default:
                return false;
        }
    }

    static boolean playFrom(String context, String track, int index) {
        if (!connected() && (!configured() || !connectBlocking(appContext, false, 3500))) return false;
        String ctxUri = OfficialPlayback.canonicalUri(context);
        String trackUri = OfficialPlayback.canonicalUri(track);
        if (!ctxUri.isEmpty() && index >= 0) {
            JSONObject kwargs = new JSONObject();
            try {
                kwargs.put("uri", ctxUri);
                kwargs.put("index", index);
            } catch (JSONException ignored) {}
            if (callBlocking("com.spotify.skip_to_index", kwargs)) return true;
        }
        if (!trackUri.isEmpty()) return callBlocking("com.spotify.play_spotify_uri", jsonObj("uri", trackUri));
        if (!ctxUri.isEmpty()) return callBlocking("com.spotify.play_spotify_uri", jsonObj("uri", ctxUri));
        return false;
    }

    private static void subscribe(String topic) {
        long reqId = NEXT_REQ_ID.getAndIncrement();
        PENDING_SUBS.put(reqId, topic);
        sendWamp(new JSONArray().put(WAMP_SUBSCRIBE).put(reqId).put(new JSONObject()).put(topic));
    }

    private static long sendCall(String proc, JSONObject kwargs) {
        long reqId = NEXT_REQ_ID.getAndIncrement();
        JSONArray frame = new JSONArray().put(WAMP_CALL).put(reqId).put(new JSONObject()).put(proc);
        if (kwargs != null) frame.put(new JSONArray()).put(kwargs);
        sendWamp(frame);
        return reqId;
    }

    private static boolean callBlocking(String proc, JSONObject kwargs) {
        if (!connected()) return false;
        long reqId = NEXT_REQ_ID.getAndIncrement();
        CompletableFuture<Boolean> waiter = new CompletableFuture<>();
        PENDING_CALLS.put(reqId, waiter);
        JSONArray frame = new JSONArray().put(WAMP_CALL).put(reqId).put(new JSONObject()).put(proc);
        if (kwargs != null) frame.put(new JSONArray()).put(kwargs);
        if (!sendWamp(frame)) {
            PENDING_CALLS.remove(reqId);
            return false;
        }
        try {
            return waiter.get(3000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            PENDING_CALLS.remove(reqId);
            return false;
        }
    }

    private static boolean sendWamp(JSONArray frame) {
        Messenger target = outgoing;
        if (target == null || frame == null) return false;
        Message msg = Message.obtain(null, MSG_MESSAGE);
        Bundle b = new Bundle();
        b.putByteArray(KEY_BODY, frame.toString().getBytes(StandardCharsets.UTF_8));
        msg.setData(b);
        try {
            target.send(msg);
            return true;
        } catch (RemoteException e) {
            handleDisconnect("Remote connection closed");
            return false;
        }
    }

    private static JSONObject jsonObj(String key, Object value) {
        JSONObject obj = new JSONObject();
        try { obj.put(key, value); } catch (JSONException ignored) {}
        return obj;
    }

    private static int remoteToSpatchRepeat(int remote) {
        if (remote == 1) return 2;
        if (remote == 2) return 1;
        return 0;
    }

    private static int spatchToRemoteRepeat(int spatchRepeat) {
        if (spatchRepeat == 1) return 2;
        if (spatchRepeat == 2) return 1;
        return 0;
    }

    private static void failPendingCalls() {
        for (CompletableFuture<Boolean> f : PENDING_CALLS.values()) f.complete(false);
        PENDING_CALLS.clear();
    }

    private static void resolveConnectWaiters(boolean ok) {
        for (CompletableFuture<Boolean> f : CONNECT_WAITERS.values()) f.complete(ok);
        CONNECT_WAITERS.clear();
    }

    private static String formatError(String raw) {
        if (raw == null || raw.isEmpty()) return "Unknown App Remote error";
        if (raw.contains("not_logged_in")) return "Sign in to official Spotify first";
        if (raw.contains("authentication_failed") || raw.contains("user_not_authorized")) {
            return "Authorization denied or Client ID/SHA-1 mismatch";
        }
        if (raw.contains("offline")) return "Official Spotify is offline";
        if (raw.contains("explicit_content")) return "Explicit content restricted";
        return raw;
    }

    static String statusSummary() {
        if (connected()) return "App Remote connected (seamless control)";
        if (!configured()) return "Optional · add a Spotify Developer Client ID for seamless control";
        if (connecting) return "Connecting to official Spotify App Remote…";
        if (!lastError.isEmpty()) return "App Remote: " + lastError;
        return "App Remote ready · tap Connect App Remote to authorize";
    }

    @SuppressWarnings("deprecation")
    static String signingSha1(Context ctx) {
        if (ctx == null) return "";
        try {
            PackageManager pm = ctx.getPackageManager();
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo si = info == null ? null : info.signingInfo;
                if (si != null) sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
            } else {
                PackageInfo info = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
                if (info != null) sigs = info.signatures;
            }
            if (sigs == null || sigs.length == 0) return "";
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(sigs[0].toByteArray());
            StringBuilder hex = new StringBuilder(digest.length * 3);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) hex.append(':');
                hex.append(String.format(Locale.ROOT, "%02X", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static String packageAndSha1(Context ctx) {
        if (ctx == null) return "";
        String pkg = ctx.getPackageName();
        String sha = signingSha1(ctx);
        return sha.isEmpty() ? pkg : pkg + "\n" + sha;
    }

    private static void bumpRevision() {
        REVISION.incrementAndGet();
        notifyUi();
    }

    private static void notifyUi() {
        try {
            SpatchUi.onNativeChange();
        } catch (Throwable ignored) {}
    }

    static void resetForTest() {
        synchronized (LOCK) {
            unbindLocked();
        }
        prefs = null;
        appContext = null;
        lastError = "";
        pendingStateCallId = 0;
        pendingInteractive = false;
        uri = title = artist = album = image = albumUri = artistUri = contextUri = contextTitle = "";
        playing = buffering = shuffle = false;
        repeat = 0;
        duration = position = anchorMs = 0L;
        playbackSpeed = 1f;
        canSkipNext = canSkipPrev = canToggleShuffle = canRepeatTrack = canRepeatContext = canSeek = true;
        PENDING_SUBS.clear();
        SUBS.clear();
        PENDING_CALLS.clear();
        CONNECT_WAITERS.clear();
        NEXT_REQ_ID.set(1);
        REVISION.set(1);
    }
}
