# Spatch UI

Spatch runs over Spotify's existing core inside the patched Android package. Rust decodes playback and library data, dispatches commands, owns the current navigation route, and generates the paths for the artwork, wave, and transport controls. Android's GPU-backed Canvas draws these paths. Android views provide text, accessibility, scrolling, and touch input.

The launcher mounts Spatch and hides Spotify's activity content. Spatch handles navigation, including Back. This build expects an existing Spotify session. It has no Spatch sign-in screen.

## Build

Install the Rust Android target, cargo-ndk, protoc, Android SDK platform 35 or later, and the Android build tools. From the repository root:

```sh
cargo test --locked --manifest-path app/Cargo.toml
cargo ndk -t arm64-v8a -P 29 build --release --locked --manifest-path app/Cargo.toml
cargo build --release --locked
```

Set `ANDROID_NDK_HOME` to the installed NDK if cargo-ndk cannot locate it. The patcher reads `app/target/aarch64-linux-android/release/libspatch_app.so` or the file specified by `SPATCH_APP_SO`. It adds the library and the helper DEX to the base APK.

Home, Search, Library, Settings and + live in the bottom navigator. Tap a destination or slide across the bar and release to switch; a single indicator follows the pointer and settles into the selected destination. Your Spotify avatar at the top right opens a menu with Profile, Settings, Queue and Downloads. The + button opens a menu above the navigator with Playlist, Collaborative playlist, Mixed playlist, Blend, Prompted Playlist and Jam. Playlist opens the blank/current-queue form. Other options open Spotify’s own authenticated creation screens with a Back to Spatch control; Collaborative and Mixed use Spotify’s native creator menu so its account eligibility is preserved. Long-press a song to add it to an existing or new playlist.

Profile has one entry in the account menu; Settings groups appearance, playback, sound, navigation, storage and developer diagnostics. New installations start in dark mode, with System and Light still available. Navigation dragging and hiding the navigator/mini player while scrolling are configurable. Motion, haptics, player visuals and the legacy Spatch play/pause confetti can be tuned or disabled; Reduce motion suppresses the animations and confetti.

Equalizer & sound opens directly to a draggable 10-band curve with 0.1 dB fine adjustment, eight presets, a saved custom curve, reset and curve bypass. It also includes searchable [AutoEq headphone corrections](https://github.com/jaakkopasanen/AutoEq), custom GraphicEQ text import, correction intensity, ISO 226 equal loudness, bass boost, natural/transient/sustain bass tuning, reverb, virtualizer, automatic/manual attenuation, limiter controls, independent left/right attenuation, preferred processing buffer and legacy output-mix compatibility. These controls cover the processing stages documented in [Wavelet’s feature guide](https://pittvandewitt.github.io/Wavelet/Features/); they use Android effects, not Wavelet’s proprietary implementation. AutoEq listings and the chosen profile download from the AutoEq project on GitHub; imported profiles are validated before replacing the saved curve.

Advanced EQ uses Android DynamicsProcessing with 64 bands for headphone corrections, falling back to 32/10 bands or the device equalizer when needed. Advanced dynamics and channel balance need DynamicsProcessing; bass boost, virtualizer and insert reverb depend on the device’s effects. Settings reports unavailable effects instead of treating a successful toggle as proof of processing. Legacy mode may affect other apps’ audio. Spotify’s own session hooks provide detection without Wavelet’s cross-app DUMP/notification access setup. Acoustic results and manufacturer support still need testing on a physical phone.

**Follow the music** makes the immersive glow, shockwaves and motes react to Spotify’s playback session; Android lists that capture as microphone access. Live Spotify search, profile and playlist mutation use the existing authenticated core session.

## Preview without Spotify

Run `app/preview/build.sh` to build `dist/spatch-preview.apk`. Install it on an ARM64 Android 10+ phone or emulator with `adb install -r dist/spatch-preview.apk` and open **Spatch Preview**. It uses the same Android views, Rust navigation, and renderer as the patched UI. Sample playback, seeking, and the library work offline.

### Native Mac window

Run `app/preview/mac.sh`, or double-click `app/preview/Spatch Preview.command` in Finder.
It builds `dist/Spatch Preview.app` and opens an interactive desktop window through
[Robolectric's native desktop host](https://robolectric.org/simulator/). You can open that `.app`
directly afterward; rerun the command to rebuild it after source changes.
No browser, Android emulator, virtual device or connected phone is needed.

The host compiles **the actual `SpatchUi.java` and `Audio.java`**, and loads a Mac build of the
same Rust library with its existing `preview` feature. There is no second screen implementation:
changes to the Android UI, icons, palette and Rust preview behavior appear on the next launch.
The small preview activity and offline artwork adapter are shared with the preview APK too.

You need Rust, `protoc`, and JDK 21 (`brew install protobuf openjdk@21`). The Gradle wrapper
downloads its pinned build tools and Robolectric dependencies on first launch. `JAVA_HOME` can
select a different compatible JDK. Closing the preview window quits the desktop host.

Playback, seeking, library browsing, search and queue controls use sample data. Profile and playlist creation/addition use the same JNI contracts with an in-memory preview library. This previews
the shared UI; live Spotify catalog requests, real audio processing and Android system integration
still need the signed Android app. Robolectric's rendering can differ from a physical device.

Run `app/preview/mac.sh --check` for the desktop smoke test. It navigates the shared screen and
writes Settings, creation-menu and equalizer screenshots to `app/preview/desktop/build/reports/preview/`. It also checks band editing/save/reset, menu visibility, indicator transitions and native-flow accessibility/Back restoration.
