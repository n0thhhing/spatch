# Official Spotify playback fallback

Status: implemented as an opt-in fallback and validated on-device with Spotify 9.2.0.2053. Standalone playback remains the preferred outcome;
see [the investigation task](../tasks/standalone-free-playback.md) (local task file).

Keep Spatch's interface and library browsing in the clone, and use the installed, Spotify-signed
`com.spotify.music` app for audio playback. That app performs its own authentication and
verification. This does not transfer its integrity verification to the clone.

Spatch's player and home-screen widgets mirror the official app's media session. The official app
owns audio and system media controls. A media session alone does not authorize the clone to
receive Spotify audio; a second proxy session is not created.

## Seamless playback modes

Spatch supports two complementary ways to drive the installed official `com.spotify.music` app while keeping Spatch as the active interface:

1. **Spotify App Remote IPC (`spatch.AppRemote`, optional Client ID)**:
   - Bind directly to `com.spotify.music`'s protocol service (`BIND_PROTOCOL_SERVICE` / `START_APP_PROTOCOL_SERVICE`) using a pure-Java WAMP v2 over `Messenger` client.
   - Configure an optional **App Remote Client ID**, **Redirect URI** (default `spatch://callback`), and copy Spatch's **package name & signing SHA-1** from **Settings → Playback → Playback backend**, then tap **Connect App Remote**.
   - Once authorized by official Spotify, Spatch plays tracks/playlists/albums in context (`skip_to_index` / `play_spotify_uri`), queues tracks, seeks, toggles shuffle and repeat, and receives live track/context state without leaving Spatch.

2. **MediaSession + Auto-return fallback (`spatch.OfficialPlayback`, zero-setup)**:
   - Mirrors playback state, cover artwork (`spotify:image:` CDN URLs and in-memory `MediaMetadata` bitmaps + palette colors), and supported `MediaController` transport controls via Android notification-listener access.
   - When playing a selection through the package-scoped `ACTION_VIEW` handoff and **Auto-return after handoff** is enabled (default on), Spatch listens for the official session to enter `PLAYING` / `BUFFERING` or update metadata and brings itself back to the front (`FLAG_ACTIVITY_REORDER_TO_FRONT`) after ~260 ms.

Both modes preserve official Spotify's own authentication, free-account restrictions, and ads.

## Setup

In the clone, open **Settings → Playback → Playback backend → Official Spotify fallback**.
Keep official Spotify installed and signed into the same account for consistent library access. Grant **Spotify playback fallback** notification
access in Android's settings when prompted, then return to Spatch. If no session is available,
use **Open official Spotify** on the same settings page and start playback once. For full in-app control without `ACTION_VIEW` switching, add a Spotify Developer Client ID in the **App Remote Client ID** row and tap **Connect App Remote**.

If Android says **Controlled by Restricted Setting**, tap the greyed-out **Allow notification
access** row once to trigger Android's *"App was denied access"* dialog (newer Android builds hide
the menu until that denial occurs), then open Spotify Two's **App info → ⋮ → Allow restricted
settings**, and return to the notification-access page to enable the listener. The system requires
the user's approval; the app does not grant itself access.

Notification-listener access is Android's authorization for discovering media sessions. The
adapter does not inspect or log notification contents, and selects only `com.spotify.music`.
The setting defaults off; choosing **Built-in player** restores the original route. Switching
backends pauses the old backend.

## Integration

- Discover the official app's media session through Android notification-listener access,
  explicitly granted by the user. Restrict discovery to `com.spotify.music`.
- Route pause/resume, previous/next and seek when advertised through its `MediaController`, and subscribe to metadata,
  playback state and session changes to keep Spatch's player synchronized.
- Open track/context selection through an explicit, package-scoped `ACTION_VIEW` Spotify deep link
  to `com.spotify.music`. Official Spotify 9.2.0.2053 advertises `ACTION_PLAY_FROM_URI` on its
  media session but silently drops `playFromUri` calls from third-party callers, so the adapter
  uses a visible handoff rather than silently doing nothing. Selecting a song within a list sends
  that song's URI; preserving the list context and offset is not supported by this adapter.
- Handle missing app, missing permission, expired session and disconnected playback visibly.
  Clear disconnected playback state and use the official session as the authoritative player.
- Retain normal free-account restrictions and ads. Preserve both apps' accounts and data.

## Observed device validation (Pixel, Android 17, Spotify 9.2.0.2053)

Validated with `com.spotify.music.two` (clone) controlling genuine `com.spotify.music` (both
9.2.0.2053, free account, media muted at volume 0):

- **Listener connection & metadata mirroring**: after explicit user approval of Restricted Settings
  and Notification Access, `spatch.OfficialPlayback` binds to `com.spotify.music` only and mirrors
  track title, artist, track URI (highlighting the active row in playlists), playback state and
  advancing position in both the miniplayer and full player.
- **Transport controls**: resume, pause and skip-next work through `MediaController` and advance
  actual song position (verified `Verbatim` advancing `15.8 s → 20.6 s`, and `Story: No Title`
  advancing `1.5 s → 24.4 s`). On this free account's current session, official Spotify did not
  advertise `ACTION_SEEK_TO` or `ACTION_SKIP_TO_PREVIOUS` (`actions=404131`/`404132`), and the
  adapter respects those advertised capability bits instead of sending unsupported commands.
- **Track selection**: official Spotify's `MediaSession` leaves `onPlayFromUri` unimplemented for
  general callers and restricts `onPlayFromMediaId` to allowlisted partner packages (`Callback
  handler for the package ... does not support an expected action`), silently dropping both from
  `com.spotify.music.two`. Because official Spotify blocks background track selection for
  third-party apps, `OfficialPlayback.play` uses an explicit package-scoped `ACTION_VIEW` handoff,
  which visibly opens `com.spotify.music`, starts the selected song with advancing position, and
  mirrors back into Spatch on return.
- **Reconnect, cold restart & opt-out**: listener binding and metadata mirroring survived an
  in-place APK update / cold process restart with zero crashes. Switching **Playback backend** back
  to **Built-in player** cleanly restored built-in playback settings, and re-enabling **Official
  Spotify fallback** reconnected immediately.
- **Known limits**: album art in the mirrored player is not yet rendered when official Spotify
  supplies artwork outside Spatch's HTTP loader; queue edits, shuffle/repeat overrides, playback
  speed, clone downloads/audio effects and Connect device transfers are not routed to the official
  backend. Use official Spotify for those controls; unsupported transport commands fail instead of
  being sent to the clone's inactive player.

`OfficialPlaybackTest` checks the 20-field player contract, monotonic position extrapolation,
duration bounds, paused/disconnected/ad state, URI validation, package-scoped `ACTION_VIEW`
handoff, action restrictions and simulated listener filtering/reconnect/callback cleanup. The
patcher's manifest test checks service placement, binding permission, package visibility and
duplicate rejection.

This fallback requires the official app to remain installed. Do not silently switch to it or
describe it as standalone playback.
