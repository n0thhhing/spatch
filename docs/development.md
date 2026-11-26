# Working on fresh

This branch replaces Spotify’s screen with shared Android views backed by Rust. `main` contains
the earlier Spotify-UI patch. Start changes from `fresh`; keep the pinned input hashes intact.

## Source map

| Location | Responsibility |
| --- | --- |
| `crates/patcher/src/` | Desktop patcher, guarded edits, DEX compilation, bundles and source staging |
| `crates/core/src/spotify/` | Spotify protobuf requests, decoders and domain operations |
| `crates/core/src/ui/mod.rs` | Playback/library/playlist JNI contracts, revision counters and current account state |
| `crates/core/src/ui/downloads.rs` | Offline JNI endpoints and validated, account-scoped song labels |
| `crates/core/src/ui/account.rs` | Profile and playlist creation/addition endpoints with their preview tests |
| `crates/core/src/bridge.rs` | JNI transport, Rust stream handlers, session generations and UI pushes |
| `crates/core/src/cache.rs` | Bounded account-scoped screen answers and packed string encoding |
| `crates/core/src/eq.rs`, `spectrum.rs`, `autoeq.rs` | DSP math, FFT analysis and headphone catalog parsing |
| `android/ui/views/` | Shared views (`SpatchUi.java`, `Dock.java`, `ArtworkViews.java`, `Controls.java`, `MediaEntry.java`, `ProgressMotion.java`, `Blur.java`, `Confetti.java`, `GpuFx.java`) |
| `android/ui/screens/` | App screens (`HomeScreen.java`, `PlayerScreen.java`, `PlaylistScreen.java`, `LibraryScreen.java`, `SearchScreen.java`, `SettingsScreen.java`, `SoundSettings.java`, `Downloads.java`, `CreationSheet.java`, `AboutScreen.java`) |
| `android/ui/bridge/` | Spotify bridge (`CoreBridge.java`, `Subscriptions.java`, `SpatchApp.java`, `PackedStrings.java`, `PublicTrackMetadata.java`, `WebAuth.java`, `DevIntegrity.java`, `SpotifyWeb.java`) |
| `android/ui/audio/` | Android audio sessions/effects (`Audio.java`, `OfficialPlayback.java`) |
| `android/ui/lyrics/` | Synced lyrics (`Lyrics.java`, `LyricGen.java`) |
| `android/ui/widget/` | Home-screen widgets, persisted last-played state, background resume, exported `spatch.action.*` intents and Quick Settings tile (`SpatchWidget.java`, `SpatchWidgetSmall.java`, `PlaybackTile.java`) |
| `android/widget/res/` | Widget layouts, animators and drawables compiled by `aapt2` |
| `android/stubs/` | Compile-only Spotify/RxJava signatures; never package them |
| `preview/src/spatch/` | Shared Android preview host and offline Spotify boundary adapter |
| `preview/desktop/` | Native Mac host build and rendered UI tests |
| `android/tests/`, `preview/desktop/src/test/` | Java contract/math tests, desktop contract runners (`nightly-inputs.py`, `java-contracts.py`, `integrity-diagnostics.py`) and rendered UI regression checks |
| `tools/` | Nightly build script (`tools/nightly.sh`) and version-porting helpers (`tools/port/`) |

Register production Java helpers in `crates/patcher/src/helpers.rs`. The preview excludes Spotify-only bridges
and compiles the actual UI/audio sources; do not create a second implementation of a screen.
All Java files under `android/ui/` declare `package spatch;` and are grouped into subdirectories by responsibility. Both preview builds
discover the shared sources from `android/ui/`.
Update their exclusions when introducing another Spotify-only helper.

## Lifecycle rules

- Never block the UI thread on Spotify requests. `DATA` handles connection maintenance, `PLAYLISTS`
  handles independent page work, `COMMANDS` orders mutations, and `WATCHES` orders playlist stream
  open/close operations. Submit UI jobs through the lifetime guard so detached account screens
  cannot perform queued edits in a subsequent account.
- Rust creates a stream token; Java uses that same token. Register the disposable slot before
  subscribing because Rx can complete synchronously. Completion, error and cancellation must
  remove ownership on both sides. Invoke Rust handlers outside the handler-map lock.
- Stream callbacks commit under the session gate. For blocking requests, capture `bridge::session()`
  before the request and use `bridge::in_session` for account-state writes. Never make a network
  request from inside that gate or a stream callback.
- Authentication removes the old screen and Back callback, cancels subscriptions and clears
  account state. Private disk answers require a verified username; an editable collaboration’s
  owner is not the current user. The previous unscoped cache format is ignored.
- Keep page and request-generation checks before applying results or starting a watch. Playlist
  loading timeouts must offer a useful retry message. Preserve open forms/sheets when insets change.
- Use keyed view tags for palette callbacks. A normal tag may already store interaction state.
- Keep `shownProgress()` visual only: commands and timers use the real playhead. Ease discontinuous
  positions in the line and mini-player ring together; direct dragging follows the finger and
  both drag/hold seeking flatten the wave. Respect Reduce motion and cancel work on detach.
- Recalculate EQ gains and peak attenuation for the actual hardware band centres, including
  fallback bands. Unsupported effects retry with backoff; failed visualizer captures clear stale
  levels and use the slower retry loop. On pause, ignore stale bands and keep drawing until the
  artwork ring settles; paused frames must not renew audio capture.
- Invalidate the motes on playback pushes and window visibility changes so a paused frame loop can
  restart. Ease their speed to zero, advance sway/shimmer from that same local phase, and keep
  Reduce motion still. Cache the small Soft/Deep/Streaks blur textures when artwork loads rather
  than allocating them during drawing. Filter enlarged textures and dither the backdrop gradients
  to avoid block edges and dark colour bands; all visual preferences use the shared Android UI.

- Individual downloads share the core’s `AddDownload`/`RemoveDownload` URI contract. Keep item
  names/artwork in the account-scoped `download-items` answer, validate the five-field metadata
  before mutation, and commit it under the session gate. Retain uncached offline URIs in the
  Downloaded filter. Cancelling preview jobs must invalidate their tokens before removing state.
- `SpatchWidget` persists the last-played track metadata in `spatch_widget` preferences and its
  cover in `filesDir` (`spatch_widget_cover.png`) so home-screen widgets keep their state when the
  app is closed. Pressing Play or sending a `spatch.action.*` broadcast while closed uses
  `goAsync()` to wake Spotify’s `MediaButtonReceiver` in the background, load `libspatch_app.so`,
  wait for `CoreBridge.hasTransport()` + `SpatchUi.connectNative()`, and resume or seek to the
  saved position without opening `SpotifyMainActivity`.

## Checks

From the repository root:

```sh
cargo test --locked
cargo clippy --all-targets --locked -- -D warnings
cargo test --locked --manifest-path crates/core/Cargo.toml --features preview
cargo clippy --all-targets --locked --manifest-path crates/core/Cargo.toml --features preview -- -D warnings
python3 android/tests/nightly-inputs.py
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/android-sdk python3 android/tests/java-contracts.py
```

On Mac, `preview/mac.sh --check` runs the shared Android UI under Robolectric and writes PNGs
to `preview/desktop/build/reports/preview/`. The suite covers navigation/animation, creation,
EQ editing, playlist picking/filtering, inset preservation and detached-screen job cancellation.
Use `preview/mac.sh` to rebuild/open the native Mac app. Close an existing preview before
replacing its bundle. The Android preview APK is built by `preview/build.sh`.

For patch packaging, build the ARM64 library using [the core prerequisites](../crates/core/README.md#build),
then run the patcher with the pinned APKM and a fresh output directory:

```sh
cargo ndk -t arm64-v8a -P 29 build --release --locked --manifest-path crates/core/Cargo.toml
cargo build --release --locked
./target/release/spatch patch /path/to/pinned.apkm --out /tmp/spatch-check-new --dry-run
```

The dry run verifies exact inputs, smali anchors, Java compilation and allowed helper DEX classes.
It does not establish authenticated Spotify acceptance or acoustic behavior on a phone. See
[the hook map](spotify-map.md) for protocol evidence and [external control intents](intents.md)
for the exported automation receiver.

## Device follow-up

Use an in-place update signed by the existing key. Verify fresh sign-in and switching accounts,
live search/paging, song addition to owned and collaborative playlists, new-playlist seeding,
playlist Back/open races, playback pause/resume and visualizer recovery. Check EQ on the device’s
actual effects backend and verify its reported fallback/availability. Native creation gates remain
Spotify/account dependent. Device storage comes from Android byte counts; raw Spotify download
storage counters are not labeled as bytes until their unit conversion is verified.
