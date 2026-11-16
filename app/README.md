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

On an Apple silicon Mac, run `app/preview/mac.sh` to open Spatch Preview in its own Android emulator. The script shows the SDK install command if the emulator or its system image is missing.
