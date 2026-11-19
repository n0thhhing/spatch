# Working on fresh

This branch replaces Spotify’s screen with shared Android views backed by Rust. `main` contains
the earlier Spotify-UI patch. Start changes from `fresh`; keep the pinned input hashes intact.

## Source map

| Location | Responsibility |
| --- | --- |
| `src/` | Desktop patcher, guarded edits, DEX compilation, bundles and source staging |
| `app/src/spotify/` | Spotify protobuf requests, decoders and domain operations |
| `app/src/ui/mod.rs` | Playback/library/playlist JNI contracts, revision counters and current account state |
| `app/src/ui/account.rs` | Profile and playlist creation/addition endpoints with their preview tests |
| `app/src/bridge.rs` | JNI transport, Rust stream handlers, session generations and UI pushes |
| `app/src/cache.rs` | Bounded account-scoped screen answers and packed string encoding |
| `app/src/eq.rs`, `spectrum.rs`, `autoeq.rs` | DSP math, FFT analysis and headphone catalog parsing |
| `java/dex/src/spatch/SpatchUi.java` | Screen composition, navigation, playback/library state and JNI boundary |
| `ArtworkViews.java` in the same directory | Cover tiles, artwork transitions, backdrop, motes and the audio ring |
| `SoundSettings.java` in the same directory | Equalizer editor, effects and headphone profile browsing |
| `CreationSheet.java` in the same directory | Create menu, playlist draft, validation and submission |
| `SpatchApp.java` in the same directory | Async native loading, overlay/auth lifecycle and native return control |
| `MediaEntry.java`, `ProgressMotion.java` in the same directory | Media row/song models and playback progress animation |
| `PackedStrings.java` in the same directory | Shared UTF-8 JNI payload encoding/decoding |
| `CoreBridge.java`, `Subscriptions.java` | Spotify transports, stream disposal, catalog calls and artwork IO |
| `Audio.java` in the same directory | Android audio sessions/effects, capture and retry scheduling |
| `java/dex/stubs/` | Compile-only Spotify/RxJava signatures; never package them |
| `app/preview/src/spatch/` | Shared Android preview host and offline Spotify boundary adapter |
| `app/preview/desktop/` | Native Mac host build and rendered UI tests |
| `java/dex/test/`, `app/preview/desktop/src/test/` | Java contract/math and rendered UI regression checks |
| `tests/` | Desktop contract runners and nightly input selection checks |
| `manager/`, `source/cloudflare/` | Android installer and optional private source service |

Register production Java helpers in `src/helpers.rs`. The preview excludes Spotify-only bridges
and compiles the actual UI/audio sources; do not create a second implementation of a screen.
Java files live directly under their declared `spatch` package directory. Both preview builds
discover the shared sources from the same directory.
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

## Checks

From the repository root:

```sh
cargo test --locked
cargo clippy --all-targets --locked -- -D warnings
cargo test --locked --manifest-path app/Cargo.toml
cargo clippy --all-targets --locked --manifest-path app/Cargo.toml -- -D warnings
cargo test --locked --manifest-path app/Cargo.toml --features preview
cargo clippy --all-targets --locked --manifest-path app/Cargo.toml --features preview -- -D warnings
python3 tests/nightly-inputs.py
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/android-sdk python3 tests/java-contracts.py
node --test source/cloudflare/worker.test.mjs
```

On Mac, `app/preview/mac.sh --check` runs the shared Android UI under Robolectric and writes PNGs
to `app/preview/desktop/build/reports/preview/`. The suite covers navigation/animation, creation,
EQ editing, playlist picking/filtering, inset preservation and detached-screen job cancellation.
Use `app/preview/mac.sh` to rebuild/open the native Mac app. Close an existing preview before
replacing its bundle. The Android preview APK is built by `app/preview/build.sh`.

For patch packaging, build the ARM64 library using [the app prerequisites](../app/README.md#build),
then run the patcher with the pinned APKM and a fresh output directory:

```sh
cargo ndk -t arm64-v8a -P 29 build --release --locked --manifest-path app/Cargo.toml
cargo build --release --locked
./target/release/spatch patch /path/to/pinned.apkm --out /tmp/spatch-check-new --dry-run
```

The dry run verifies exact inputs, smali anchors, Java compilation and allowed helper DEX classes.
It does not establish authenticated Spotify acceptance or acoustic behavior on a phone. See
[the hook map](spotify-map.md) for protocol evidence and [Manager checks](../manager/README.md#checking)
for signed bundle round trips.

## Device follow-up

Use an in-place update signed by the existing key. Verify fresh sign-in and switching accounts,
live search/paging, song addition to owned and collaborative playlists, new-playlist seeding,
playlist Back/open races, playback pause/resume and visualizer recovery. Check EQ on the device’s
actual effects backend and verify its reported fallback/availability. Native creation gates remain
Spotify/account dependent. Device storage comes from Android byte counts; raw Spotify download
storage counters are not labeled as bytes until their unit conversion is verified.
