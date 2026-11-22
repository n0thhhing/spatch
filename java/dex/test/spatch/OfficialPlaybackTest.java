package spatch;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
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
}
