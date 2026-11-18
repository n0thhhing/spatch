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

Home and Library switch from the button group at the top, beside Settings. Tap the mini player to open the player, swipe it sideways to skip, or long-press a library item for Play, Shuffle play and Open. In the player, double-tap the artwork or use the corner button for immersive mode; pull the artwork down to close. In immersive mode a tap on the disc hides the controls.

Settings covers theme, accent colour, artwork colours, the player artwork shape, motion effects, keep-screen-on, the details card and haptics. **Follow the music** makes the immersive glow, shockwaves and motes react to Spotify's own playback session; Android lists that capture as microphone access.

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

Playback, seeking, library browsing, local search and queue controls use sample data. This previews
the shared UI; live Spotify catalog requests, real audio processing and Android system integration
still need the signed Android app. Robolectric's rendering can differ from a physical device.

Run `app/preview/mac.sh --check` for the desktop smoke test. It navigates the shared screen and
writes a screenshot to `app/preview/desktop/build/reports/preview/settings.png`.
