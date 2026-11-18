# Spotify hooks on fresh

[The edit table](../src/edits.rs) applies these six hooks to Spotify 9.2.0.26. Each edit requires
exactly one anchor match.

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
four Java helpers also live.

- [CoreBridge.java](../java/dex/src/spatch/core/CoreBridge.java) bridges calls and subscriptions between Rust and Spotify's transport.
- [SpatchApp.java](../java/dex/src/spatch/core/SpatchApp.java) loads the Rust library and mounts the UI on the activity.
- [SpatchUi.java](../java/dex/src/spatch/core/SpatchUi.java) hosts Android views and renders the UI.
- [Audio.java](../java/dex/src/spatch/core/Audio.java) captures FFT samples from the active playback session.

For input hashes and version porting, see [Patch targets](targets.md). The earlier redesign lives on
the `main` branch.

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
- The response field numbers match [the minimal schema](../app/proto/com.spotify.searchview.proto.proto):
  hits 1, next page token 4, entity identity 1–3, nested section 14, track duration 18.
  Best-match highlights are a separate oneof and do not replace the track/album/etc. payload.

The temporary `Radiohead` request logger and its proxy hook have been removed. Search results
still use the existing [catalog decoder](../app/src/spotify/catalog.rs) and Search screen; failed
requests remain available in Settings → Developer → Playback diagnostics.

`python3 tests/catalog-request.py` (with `JAVA_HOME` and `ANDROID_HOME` set) runs the production
Java bridge against local fixtures. It checks factory selection, complete metadata, canonical
locale use, Unicode queries, opaque paging tokens and reply serialization. These checks also
run in CI; the fixtures do not establish authentication or live server acceptance.
