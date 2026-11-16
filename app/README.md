# Spatch app

Spatch's own Spotify UI, in Rust and Slint, running inside the patched Spotify app on top of Spotify's
own core. The core keeps doing sign-in, playback, decryption, caching and Connect; this crate only
calls it the way Spotify's own screens do.

## How it reaches the core

Spotify's screens talk to the core through **Esperanto**: RPC services called by name with protobuf
bytes. The layer is unobfuscated:

- `com.spotify.esperanto.esperanto.ClientBase` holds a `Transport` and calls
  `callSingle / callStream / callSync(service, method, byte[])`.
- `Transport` implementations: `esperantoimpl.NativeTransport` (into `liborbit-jni-spotify.so`),
  `SchedulingTransport` (wraps one with an Rx scheduler), `esperantocosmos.CosmosTransport`.
- RxJava 3 (`io.reactivex.rxjava3.*`) keeps its names, so replies can be subscribed to directly.

One patch (`src/edits.rs`, ClientBase's constructor) passes every client's transport through
`spatch.CoreBridge.wrap`. The wrapper records which transport serves each service the first time
Spotify calls it, then forwards everything unchanged. Because every name involved is readable, this hook
needs no alias and should survive Spotify updates.

```
Rust (app/)  ── JNI ──>  spatch.CoreBridge.call / subscribe   (theme/dex/src/spatch/core)
                              │ the transport Spotify uses for that service
                              v
                         Esperanto transport ──> Spotify's core (liborbit)
```

`spatch.SpatchApp` hosts the UI: a full-screen `SurfaceView` over Spotify's activity, with Spotify's
own content view set invisible underneath so it stops drawing. Rust hands the surface's `ANativeWindow`
to Slint's Skia renderer (`i-slint-renderer-skia`, OpenGL ES through EGL, the renderer Slint's own Android
backend uses; its public custom-platform GPU API is off on Android), wrapped in a small window adapter
(`window.rs`). Frames are only asked for when something is due. `nativeFrame` returns 0 (next vsync,
while animating), a delay (the clock's next second while playing) or -1 (idle until a touch or a core
reply wakes it). The surface asks for 120 Hz only while animating, so the LTPO panel can drop its
refresh rate when nothing moves. The library ships as `assets/spatch/libspatch_app.so` and is
copied to app storage and loaded with `System.load`, so Spotify's manifest and splits are untouched.
Open it from **Spatch settings → Developer options → Open Spatch app (preview)** (debug builds).

## Services

`proto/rpc.tsv` lists every Esperanto RPC Spotify's Java code makes (313 RPCs in 64 services): the
service, method, `single`/`stream`, request and response. Responses come from the decoder each call site
uses. Where a shared decoder couldn't be pinned, the response is the closest match by package, and
`(by name)` marks requests inferred from the response's name. Verify those live before relying on them.

The ones a client needs:

| Area | Service | Methods |
| --- | --- | --- |
| Player | `spotify.player.esperanto.proto.ContextPlayer` | `GetState` (stream), `Play`, `Pause`, `Resume`, `SkipNext`, `SkipPrev`, `SeekTo`, `SetShufflingContext`, `SetOptions`, `AddToQueue`, `GetQueue` (stream), `SetQueue`, `SetSleepTimer` |
| Library | `spotify.your_library_esperanto.proto.YourLibraryService` | `All`, `Contains`, … |
| Saved items | `spotify.collection_esperanto.proto.CollectionService` | 18 methods |
| Playlists | `spotify.playlist_esperanto.proto.PlaylistService`, `PlaylistDataService` | `Modify`, `GetMembers`, … |
| Metadata | `spotify.metadata_esperanto.proto.ClassicMetadataService` | `GetEntity`, `MultigetEntity` |
| Account | `spotify.product_state.esperanto.proto.ProductState` | `SubValues` (stream) |
| Connect | `spotify.connect.esperanto.proto.ConnectService`, `ConnectVolumeService` | `State` (stream), `Transfer`, … |
| Offline | `spotify.offline_esperanto.proto.Offline`, `Download` | 24 + 12 methods |

**Cosmos.** A second alias-free hook captures `com.spotify.cosmos.servicebasedrouter.RemoteNativeRxRouter`
(the native Cosmos router). `core::request(action, uri, body)` sends a `com.spotify.cosmos.cosmos.Request`
through it. That covers `sp://` local endpoints and `hm://` backend requests over the core's own
authenticated connection. Untested: try it first for search and home before hooking HTTP.

**Not Esperanto: search and home.** These go through Spotify's authenticated HTTP client: Retrofit
interfaces such as `searchview/v3/search` against `spclient.wg.spotify.com`, over an obfuscated OkHttp.
That needs its own hook (Spotify's Retrofit or OkHttp client, alias-mapped) before those screens can be
built. `spotify.search_esperanto.proto.OfflineSearchService/Query` covers offline content only.

Images need no hook: track metadata carries `spotify:image:<id>`, which the public CDN serves at
`https://i.scdn.co/image/<id>` (`player::image_url`).

## Protos

`proto/*.proto` are rebuilt from Spotify's generated protobuf-lite classes, not guessed. Each message
passes its exact schema (the "message info" string and an object array) to `newMessageInfo`, and R8
keeps both, plus the `*_FIELD_NUMBER` constants that give the field names.

```bash
apktool d -s base.apk -o /tmp/dec                 # Spotify's base.apk from the APKM
scripts/port/protos.py /tmp/dec /tmp/protos com/ spotify/   # all 4,905 messages (551 packages); protoc accepts them
scripts/port/rpc.py /tmp/dec > app/proto/rpc.tsv
```

Enums are emitted as `int32`, which has the same wire format. `app/proto` holds only what the app
compiles and its imports. Copy more packages from the full set as screens need them, and add them to
`build.rs`.

## UI

The screens are in `ui/*.slint`, compiled by `build.rs`. They follow Material 3 Expressive, the look of
Pixel's own apps:
- `theme.slint`: Material colour roles, made in `colors.rs` from the running Spatch theme's nine colours
  (Java passes them, with the system bar heights, at open).
- `icons.slint`: Material Symbols generated from Spatch's bundled `spatch_ms_*` drawables.
- `components.slint`: icon buttons, list items, filter chips, the navigation bar, the wavy progress bar
  and the play button that morphs from circle to rounded square.

`cargo run --example preview -- <dir>` renders both screens on the Mac as PPM files, a quick layout check
without a phone.

## Building

```bash
rustup target add aarch64-linux-android && cargo install cargo-ndk
brew install protobuf                               # build.rs runs protoc
cd app && cargo test                                # player decoding, on the Mac
ANDROID_NDK_HOME=/opt/homebrew/share/android-ndk cargo ndk -t arm64-v8a -P 26 build --release
```

The patcher picks up `app/target/aarch64-linux-android/release/libspatch_app.so`, or `$SPATCH_APP_SO`,
when it exists; without it the patch still builds and the preview row reports the library as missing.
The nightly workflow builds the library whenever `app/` exists.

## Status

- Built and unit-tested on the Mac; the arm64 library links only system libraries.
- **Not yet run on a phone.** Unknowns to check first:
  - whether `GetState` streams through the wrapped transport;
  - whether bare command requests (no logging params) are accepted;
  - text rendering: Slint 1.18 finds fonts through fontique, whose Android backend reads
    `/system/etc/fonts.xml`, so system fonts should load, but this hasn't been seen on a screen yet;
  - the EGL surface on the SurfaceView (Skia via OpenGL ES) and the on-demand frame pacing.
