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

On Android, tap the mini-player artwork or track information to open the player. Double-tap the artwork to enter immersive mode. Back returns to the standard player.
