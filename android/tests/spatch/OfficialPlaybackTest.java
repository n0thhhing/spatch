package spatch;

import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class OfficialPlaybackTest {
    @After public void tearDown() {
        AppRemote.resetForTest();
        SpatchUi.IMAGES.evictAll();
        SpatchUi.SEEDS.clear();
        SpatchUi.SEEDS2.clear();
    }
    @Test public void stateUsesOfficialMetadataAndMonotonicPlaybackClock() {
        MediaMetadata metadata = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "spotify:track:abc")
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 10000).build();
        PlaybackState playing = new PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 2000, 1.5f, 1000)
            .setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT).build();
        String[] state = OfficialPlayback.fields(metadata, playing, 3000);
        assertEquals(20, state.length);
        assertEquals("spotify:track:abc", state[1]);
        assertEquals("Song", state[2]);
        assertEquals("Artist", state[3]);
        assertEquals("true", state[6]);
        assertEquals("5000", state[9]);
        assertEquals("true", state[16]);
        assertEquals("false", state[17]);
        assertEquals("10000", OfficialPlayback.fields(metadata, playing, 99999)[9]);
        assertEquals("2000", OfficialPlayback.fields(metadata, playing, 500)[9]);
        PlaybackState paused = new PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 2000, 0, 1000).build();
        assertEquals("2000", OfficialPlayback.fields(metadata, paused, 99999)[9]);
    }
    @Test public void missingSessionClearsStateAndAdActionsAreRespected() {
        String[] state = OfficialPlayback.fields(null, null, 9000);
        assertEquals("", state[1]);
        assertEquals("", state[2]);
        assertEquals("false", state[6]);
        assertEquals("0", state[9]);
        assertEquals("0", state[10]);
        MediaMetadata adMetadata = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Advertisement").build();
        assertEquals("official:session", OfficialPlayback.fields(adMetadata, null, 9000)[1]);
        long ad = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE;
        assertTrue(OfficialPlayback.supports(0, 0, ad));
        assertTrue(OfficialPlayback.supports(1, 0, ad));
        assertFalse(OfficialPlayback.supports(3, 0, ad));
        assertFalse(OfficialPlayback.supports(4, 1000, ad));
        assertFalse(OfficialPlayback.supports(5, 1, ad));
        assertFalse(OfficialPlayback.supports(4, -1, PlaybackState.ACTION_SEEK_TO));
        assertTrue(OfficialPlayback.supports(2, 1, PlaybackState.ACTION_SEEK_TO));
        assertFalse(OfficialPlayback.supports(2, 0, PlaybackState.ACTION_SEEK_TO));
    }
    @Test public void fallbackRequiresOptInAndOnlyAcceptsSpotifyContentUris() {
        OfficialPlayback.init(RuntimeEnvironment.getApplication());
        assertFalse(OfficialPlayback.enabled());
        assertTrue(OfficialPlayback.validUri("spotify:track:abc123"));
        assertTrue(OfficialPlayback.validUri("spotify:playlist:abc123"));
        assertTrue(OfficialPlayback.validUri("spotify:collection:tracks"));
        assertFalse(OfficialPlayback.validUri("https://example.com"));
        assertFalse(OfficialPlayback.validUri("spotify:track:abc?redirect=evil"));
        assertFalse(OfficialPlayback.validUri(null));
        assertTrue(OfficialPlayback.validUri("spotify:user:someone:playlist:abc123"));
        assertFalse(OfficialPlayback.command(1, 0, ""));
    }
    @Test public void playUsesPackageScopedViewIntent() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        OfficialPlayback.init(app);
        assertTrue(OfficialPlayback.play("spotify:track:abc123"));
        android.content.Intent launched = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(android.content.Intent.ACTION_VIEW, launched.getAction());
        assertEquals(OfficialPlayback.PACKAGE, launched.getPackage());
        assertEquals("spotify:track:abc123", launched.getDataString());
        assertTrue(OfficialPlayback.play("spotify:user:someone:playlist:xyz789"));
        assertEquals("spotify:playlist:xyz789", Shadows.shadowOf(app).getNextStartedActivity().getDataString());
        assertFalse(OfficialPlayback.play("https://example.com"));
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }
    @Test public void listenerFiltersSessionsReconnectsAndDetachesCallbacks() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        OfficialPlayback.init(app);
        app.getSharedPreferences("spatch_ui", 0).edit().putBoolean("official_playback", true).commit();
        Shadows.shadowOf(app.getSystemService(android.app.NotificationManager.class)).setNotificationListenerAccessGranted(
            new android.content.ComponentName(app, OfficialPlayback.class), true);
        MediaSession foreign = new MediaSession(app, "foreign");
        MediaSession official = new MediaSession(app, "official");
        MediaController controller = official.getController();
        Shadows.shadowOf(controller).setPackageName(OfficialPlayback.PACKAGE);
        Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "spotify:track:abc")
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Official song").build());
        org.robolectric.shadows.ShadowMediaSessionManager manager = Shadows.shadowOf(app.getSystemService(MediaSessionManager.class));
        manager.addController(foreign.getController());
        manager.addController(controller);
        OfficialPlayback service = Robolectric.buildService(OfficialPlayback.class).create().get();
        service.onListenerConnected();
        assertEquals("Official song", OfficialPlayback.state(-1)[2]);
        assertEquals(1, Shadows.shadowOf(controller).getCallbacks().size());
        manager.clearControllers();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("", OfficialPlayback.state(-1)[2]);
        assertEquals(0, Shadows.shadowOf(controller).getCallbacks().size());
        manager.addController(controller);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        String[] reconnected = OfficialPlayback.state(-1);
        assertEquals("Official song", reconnected[2]);
        assertNull(OfficialPlayback.state(Long.parseLong(reconnected[0])));
        service.onListenerDisconnected();
        assertEquals(0, Shadows.shadowOf(controller).getCallbacks().size());
        assertEquals("", OfficialPlayback.state(-1)[2]);
        service.onDestroy();
        official.release(); foreign.release();
    }
    @Test public void metadataArtNormalizesSpotifyImageUrisAndCachesBitmap() {
        assertEquals("https://i.scdn.co/image/ab67616d0000b2731234",
            OfficialPlayback.normalizeImageUri("spotify:image:ab67616d0000b2731234"));
        assertEquals("https://i.scdn.co/image/abc",
            OfficialPlayback.normalizeImageUri("  https://i.scdn.co/image/abc "));
        assertEquals("spotify:playlist:xyz789",
            OfficialPlayback.canonicalUri("https://open.spotify.com/playlist/xyz789?si=123"));

        MediaMetadata withUri = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "spotify:track:uri1")
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "spotify:image:ab67616d0000b2739999")
            .build();
        assertEquals("https://i.scdn.co/image/ab67616d0000b2739999",
            OfficialPlayback.fields(withUri, null, 0)[5]);

        Bitmap bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        MediaMetadata withBitmap = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "spotify:track:bmp1")
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
            .build();
        String[] fields = OfficialPlayback.fields(withBitmap, null, 0);
        assertTrue(fields[5].startsWith("official:art:"));
        assertSame(bmp, SpatchUi.IMAGES.get(fields[5] + ":440"));
        assertSame(bmp, SpatchUi.IMAGES.get(fields[5] + ":180"));
    }
    @Test public void appRemoteBuildsHelloParsesStateAndHandlesWampLifecycle() throws Exception {
        AppRemote.resetForTest();
        JSONArray hello = AppRemote.buildHello("client123", "spatch://cb", true);
        assertEquals(AppRemote.WAMP_HELLO, hello.getInt(0));
        assertEquals("spotify", hello.getString(1));
        JSONObject details = hello.getJSONObject(2);
        assertEquals("client123", details.getString("authid"));
        assertEquals("client123", details.getJSONObject("info").getString("id"));
        assertEquals("spatch://cb", details.getJSONObject("extras").getString("redirect_uri"));
        assertEquals("true", details.getJSONObject("extras").getString("show_auth_view"));

        // WELCOME connects and establishes session state.
        AppRemote.onWampMessage(new JSONArray().put(AppRemote.WAMP_WELCOME).put(42L).put(new JSONObject()));
        assertTrue(AppRemote.connected());

        // Map synthetic subscription id 77 -> com.spotify.player_state, then deliver EVENT.
        // onWampMessage mapped subscription 1 -> com.spotify.player_state on WELCOME; simulate SUBSCRIBED for req 1.
        AppRemote.onWampMessage(new JSONArray().put(AppRemote.WAMP_SUBSCRIBED).put(1L).put(77L));
        AppRemote.onWampMessage(new JSONArray().put(AppRemote.WAMP_SUBSCRIBED).put(2L).put(78L));

        JSONObject contextKw = new JSONObject()
            .put("uri", "spotify:playlist:pl1")
            .put("title", "Focus Mix");
        AppRemote.onWampMessage(new JSONArray().put(AppRemote.WAMP_EVENT).put(78L).put(1L).put(new JSONObject()).put(new JSONArray()).put(contextKw));

        JSONObject stateKw = new JSONObject()
            .put("is_paused", false)
            .put("playback_speed", 1.0)
            .put("playback_position", 12000L)
            .put("track", new JSONObject()
                .put("uri", "spotify:track:t1")
                .put("name", "Night Drive")
                .put("image_id", "spotify:image:img42")
                .put("duration_ms", 180000L)
                .put("album", new JSONObject().put("name", "Neon").put("uri", "spotify:album:al1"))
                .put("artist", new JSONObject().put("name", "A1").put("uri", "spotify:artist:ar1"))
                .put("artists", new JSONArray()
                    .put(new JSONObject().put("name", "A1"))
                    .put(new JSONObject().put("name", "A2"))))
            .put("playback_options", new JSONObject().put("shuffle", true).put("repeat", 2))
            .put("playback_restrictions", new JSONObject()
                .put("can_skip_next", true)
                .put("can_skip_prev", false)
                .put("can_toggle_shuffle", true)
                .put("can_seek", true));

        AppRemote.onWampMessage(new JSONArray().put(AppRemote.WAMP_EVENT).put(77L).put(2L).put(new JSONObject()).put(new JSONArray()).put(stateKw));
        String[] fields = AppRemote.fields(-1L, android.os.SystemClock.uptimeMillis());
        assertNotNull(fields);
        assertEquals(20, fields.length);
        assertEquals("spotify:track:t1", fields[1]);
        assertEquals("Night Drive", fields[2]);
        assertEquals("A1, A2", fields[3]);
        assertEquals("Neon", fields[4]);
        assertEquals("https://i.scdn.co/image/img42", fields[5]);
        assertEquals("true", fields[6]);
        assertEquals("true", fields[7]);
        assertEquals("1", fields[8]);
        assertEquals("180000", fields[10]);
        assertEquals("Focus Mix", fields[11]);
        assertEquals("spotify:playlist:pl1", fields[13]);
        assertEquals("true", fields[16]);
        assertEquals("false", fields[17]);
        assertEquals("spotify:album:al1", fields[18]);
        assertEquals("spotify:artist:ar1", fields[19]);
        assertTrue(AppRemote.supportsShuffle());
        assertFalse(AppRemote.supports(PlayerScreen.PREVIOUS));
        assertTrue(AppRemote.supports(PlayerScreen.NEXT));

        // ABORT tears down connected state.
        AppRemote.onWampMessage(new JSONArray().put(AppRemote.WAMP_ABORT).put(new JSONObject()).put("wamp.error.not_logged_in"));
        assertFalse(AppRemote.connected());
        assertFalse(AppRemote.supportsShuffle());
    }
}
