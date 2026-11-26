# Spotify hooks

[The edit table](../crates/patcher/src/edits.rs) applies guarded hooks to Spotify 9.2.0.2053. Each edit requires
exactly one anchor match; the diagnostic controls described below default off.

| Spotify entry point | Spatch call | Purpose |
| --- | --- | --- |
| `RetrofitMaker` constructor | `CoreBridge.webgate` | Makes Spotify's authenticated catalog search factory available |
| `ClientBase` transport assignment | `CoreBridge.wrap` | Makes Spotify's Esperanto transport available to the Rust app |
| `SpotifyMainActivity.onResume` | `SpatchApp.resumed` | Mounts Spatch and hides the original activity content |
| `AudioEffectsListener` session creation | `Audio.created` | Tracks the playback audio session |
| `AudioEffectsListener.onPaused` | `Audio.paused` | Updates capture state on playback pause and resume |
| `AudioEffectsListener` session destruction | `Audio.destroyed` | Releases capture when the session ends |

`RetrofitMaker` and `ClientBase` move out of `classes.dex` and `AudioEffectsListener` moves out of `classes2.dex` to
make room for their new calls. They keep their original class names in `classes16.dex`, where the
[Java helpers](../crates/patcher/src/helpers.rs) also live.

- [CoreBridge.java](../android/ui/bridge/CoreBridge.java) bridges calls and subscriptions between Rust and Spotify's transport.
- [Subscriptions.java](../android/ui/bridge/Subscriptions.java) owns Rx disposables, including synchronous termination and cancellation races.
- [SpatchApp.java](../android/ui/bridge/SpatchApp.java) loads the Rust library and mounts the UI on the activity.
- [SpatchUi.java](../android/ui/views/SpatchUi.java) hosts Android views and renders the UI.
- [Audio.java](../android/ui/audio/Audio.java) captures FFT samples and manages the active session’s audio effects.

For input hashes and version porting, see [Patch targets](targets.md). The earlier redesign lives on
the `legacy` branch.

## Public song details fallback

Playlist membership comes from the account's existing core/playlist service. When its song-detail
services leave rows blank, [PublicTrackMetadata.java](../android/ui/bridge/PublicTrackMetadata.java)
uses Spotify's public embed session to fill names, artists, covers, albums and durations.
`GET https://open.spotify.com/embed/api/token` must explicitly return `isAnonymous=true`; an account
token is never accepted by this fallback. Its short-lived token is kept in memory and sent only to
`https://api-partner.spotify.com/pathfinder/v1/query`, with redirects disabled.

The request matches the Web Player's public `lookupEntities` track decorator: up to 100 validated
`spotify:track:` links per request, using persisted query
`f952da037440f694cc6925b9e3f649d39077a744c4db7dfba01cb883723f4f77`. Responses are matched to the
requested links; successful public details are cached, while transport, HTTP and schema failures
trigger a backoff. This replaces neither playlist authorization nor playback authorization. Local
files and catalog entries absent from the public response can still lack details.

The previous per-song Web API fallback returned 404 and could trigger rate limiting; it remains
removed. The public route is an internal Spotify contract and may need updating if its token
endpoint, persisted query or response schema changes.

## Catalog search request contract

The pinned APK's `p.rd51.a(Map, Map)` returns an RxJava `Single<MainViewResponse>` from
`GET searchview/v3/search`, with `Accept: application/protobuf`. `RetrofitMaker` creates it with
the feature name `android-search-onlineproto`. Its first map is query parameters; its second is
headers. This uses Spotify's authenticated transport without copying account tokens into Spatch.

The normal app factory is constructed by `p.t4c0.B`, with assertion singleton `p.o610.t`. A second
factory in `p.vhg0` uses a different native-playback HTTP client and assertion `p.yz10.Z`. The
constructor hook accepts only the former, rather than letting the last constructor call overwrite
the search connection. Both factories and the marker classes are specific to this pinned APK.

Spotify's request builder (`p.wem.c`, also `p.efb1`) and header builder (`p.lc20.apply`, also
`p.ffe1.apply`) use `p.di4.K()` to select **one complete metadata format**:

| Format | Query parameters | Search headers |
| --- | --- | --- |
| Query metadata | `request_id`, `timestamp` | None |
| Header metadata | `_t` (cached timestamp) | Both `x-search-request-id` and `x-search-timestamp` |

The previous bridge mixed query metadata with only `x-search-request-id`; requests on the phone
returned HTTP 400 with an empty error body. The bridge now uses the complete query format, also
used by `p.g931.v` and `p.g1i.n`. Whether this resolves the server rejection still requires a
signed-in device test; a local build cannot establish server acceptance.

Other details checked against the original APK:

- `p.t5h1.a` generates UUID request IDs; `p.h94.b` supplies epoch milliseconds.
- `query_complete` is an enum string: `submit` for a completed query, `qac` for typed suggestions
  (`p.pob.f`). It is not a boolean.
- `p.upn1.m` calls Spotify's locale normalizer (`p.w45.a`), which handles supported languages,
  aliases and country codes. The bridge calls this same helper.
- `p.mo21` defines the singular entity names: `track`, `album`, `artist`, `playlist`, `show`,
  `audio_episode`. The bridge requests these supported result types with a native page limit of 20.
- `on_demand_sets_enabled` is conditional on account configuration; the bridge does not force it.
- Query text and opaque page tokens pass unencoded to Retrofit's query map, which performs encoding.
- The response field numbers match [the minimal schema](../crates/core/proto/com.spotify.searchview.proto.proto):
  hits 1, next page token 4, entity identity 1–3, nested section 14, track duration 18.
  Best-match highlights are a separate oneof and do not replace the track/album/etc. payload.

The temporary `Radiohead` request logger and its proxy hook have been removed. Search results
still use the existing [catalog decoder](../crates/core/src/spotify/catalog.rs) and Search screen; failed
requests remain available in Settings → Developer → Playback diagnostics.

`python3 android/tests/java-contracts.py` (with `JAVA_HOME` and `ANDROID_HOME` set) runs the production
Java bridge against local fixtures. It checks factory selection, complete metadata, canonical
locale use, Unicode queries, opaque paging tokens, reply serialization, native routes and subscription cancellation races. These checks also
run in CI; the fixtures do not establish authentication or live server acceptance.

## Profile and playlist mutations

The pinned APK supplies these Esperanto contracts; Spatch calls them through the existing transport on worker threads:

- Profile: `spotify.profile_esperanto.proto.v1.ProfileService.SubscribeToProfiles`, `GetProfilesRequest.usernames`. Confirmed in `p.be.i` / `p.v5n`; the response is a list of IdentityV3 profiles with a status code (`p.fjb`). The largest available image becomes the account avatar. Username discovery reuses the player/library/owned-playlist path.
- Creation: `RootlistModificationService.Modify` with `operation="create"`, `playlist=true`, `before="start"`, name and optional initial URIs. Confirmed in `p.jn7`, `p.zn0` and `p.o130`. A successful response must contain the new playlist URI.
- Description: `PlaylistService.Modify` with `operation="set"` and `attributes.description`, confirmed in `p.xyc0` / `p.oux0`. If this follow-up fails, creation still returns its URI with a warning to prevent accidental duplicate playlists.
- Addition: `PlaylistService.Modify` with `operation="add"` and ordered `uris`; no before/after anchor appends to the end (`p.xyc0`, lines 305–383 in the decoded pinned APK).

Protocol tests cover fields, status failures, blank/oversized names and invalid item types. The native preview retains created playlists and appended songs in memory through the same JNI APIs. Authenticated device acceptance still needs a phone test.

The helper DEX validator also recognizes D8’s synthetic `-IA` constructor-access marker, only when its owner is a compiled helper and its class is flagged synthetic. Unknown classes remain rejected.

## Native creation routes

The shared + menu opens the existing Spatch form for a normal playlist. `CoreBridge.openCreation` routes the other options through Spotify’s signed-in native UI:

| Choice | Pinned 9.2.0.26 route | Evidence |
| --- | --- | --- |
| Blend | `spotify:blend:invitation` | `p.f7b1` URI enum, `p.qv3` invitation-page registration |
| Jam | `spotify:jam` | `p.f7b1`, `p.h1a0` and `p.ru9` router |
| Prompted Playlist | `spotify:create-prompted-playlist` | `p.f7b1`, feature-gated handler `p.hgu0` |
| Collaborative / Mixed | `spotify:create-menu` | `p.nik1.S4`, `p.znn` registration, `p.n3l0` menu types |

Collaborative and Mixed have no separately verified standalone URI: they open the native menu, where the user chooses the account-eligible action. Their internal creator uses `create_playlist_page_parameters` (`p.spn`), and Mixed is gated by `mixed_playlist_create_menu_item_enabled` (`p.ew4`). Spatch does not override those gates.

`SpatchApp` temporarily reveals native content and hides its own root during these flows, unregistering its overlay Back callback. An inset-aware Back to Spatch button restores the overlay; native secondary activities are finished on return. The host tests exercise visibility, accessibility flags, callback registration and cleanup. Routes are statically confirmed against the pinned APK; account acceptance and actual device navigation remain unverified.

## Account lifecycle and private answers

An owned library playlist’s `creator_uri` identifies the account only when `owned_by_self` is true.
Editable collaborations (`can_add`) do not identify the current user. A bounded owned-playlist
lookup remains a fallback (six seconds overall, at most 1.5 seconds per candidate).

The library request/stream caches answers only under a verified account namespace. Unverified
answers are held in memory; the old unscoped on-disk format is never read. Authentication activities
reset core subscriptions, library/player/queue/download state and username resolution. Cached
transport routes are discarded. A session generation and short commit lock reject late replies;
the lock is never held across a network request. Rx stream tokens are assigned by Rust and used
unchanged in Java. Cancellation, completion and errors release both callback and disposable state.

`YourLibraryPlaylistExtraInfo.can_add` or `owned_by_self` controls the add-to-playlist picker.
Album, artist and show pages carry the same twelve song fields as playlists, including length,
explicit status and available album/artist links. Catalog durations survive grouping and paging.

Storage `GetStats` field numbers are known, but the counters’ unit conversion is not established
from the pinned client. Device space displayed in Settings comes from `StatFs` in bytes; raw core
counters are not presented as byte totals.

## Individual offline items

The pinned APK’s `p.vpp0.a` and `p.oqp0.a` call `Offline.AddDownload` with
`com.spotify.offline_esperanto.proto.EsOffline$DownloadRequest`; `p.byp0.a/c` pass the item URI
unchanged with this phone’s DeviceKey for add/remove. Spatch uses that existing request (device
field 1, URI field 2) for individual song/episode actions as well as album/playlist downloads.
No new transport, authentication flow or automatic playlist is introduced. Core progress drives
status; responses still surface Spotify’s error codes. Static request parity and the shared preview
are tested; acceptance of individual music-track URIs requires a signed-in phone test.

## Integrity diagnostics

The diagnostics were removed and live in the tag `integrity-diagnostics-2026-11-26`.

An [official-app playback backend](official-playback-fallback.md) is implemented as an opt-in
fallback, pending device validation. Standalone playback without that dependency remains the
investigation goal.
