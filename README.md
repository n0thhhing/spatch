# Spatch

A Rust command-line patcher that gives one exact build of Spotify for Android (9.1.98.521, version
code 148115007) a Material You redesign. It takes the APKM, applies guarded edits and writes a signed
split-APK set. It never modifies the input.

This is a personal project. It supports only the build pinned in `src/input.rs`: any other input is
refused rather than patched blindly.

## What it changes

- **Themes:** Lavender, Gruvbox, Catppuccin, Rosé Pine, Nord, Tokyo Night, Dracula and Gruvbox
  Material, plus custom fonts.
- **Player:** circular artwork, a wavy or audio-reactive ring, a configurable glow, record-style skip
  animations, wavy progress, shaped controls and an immersive full-screen player with a real spectrum
  of Spotify's own audio.
- **Screens:** a floating navigation bar, sliding filter pills, a redesigned side panel, card-based
  Library and playlist rows, and a local-only profile banner.
- **Content:** hide podcasts, audiobooks, Home sections and side menu rows.

Everything is configured in the app: open the side panel and choose **Spatch**. Each feature has a
main switch and a page of finer options, and settings carry over between updates.

## Requirements

- Rust (2024 edition), Java, and Android build-tools (`aapt2`, `zipalign`, `apksigner`)
- The matching APKM, which is not included here
- Your own signing key

## Usage

```bash
cargo build --release
```

Check an APKM:

```bash
./target/release/spatch inspect <input.apkm>
```

Preview a patch without writing a signed set:

```bash
./target/release/spatch patch <input.apkm> --out <new-dir> --theme gruvbox --dry-run
```

Build a signed set. The password is read by `apksigner` from the environment variable you name, and
`spatch` never reads or prints it:

```bash
./target/release/spatch patch <input.apkm> --out <new-dir> --theme gruvbox --keystore <file> --alias <name> --ks-pass-env SPATCH_KS_PASS
```

Add `--debug` for a Developer options page in Spatch settings: on-device self tests, a frame monitor,
a touch inspector, a crash record and log reading. Other builds contain none of that code.

Install every split together. Update in place: uninstalling loses the app's data, and the signer must
match any Spatch build already on the phone.

```bash
adb install-multiple -r base.apk split_config.*.apk
```

## How it works

| Path | What lives there |
| --- | --- |
| `src/dex.rs` | Guarded smali edits and the helper DEX build. Each edit must match its anchor exactly once. |
| `src/theme.rs` | Theme colour roles. |
| `theme/dex/src/spatch/` | The Java helper classes added to the app, including the settings UI. |
| `theme/dex/stubs/` | Compile-only stubs. `p/` holds Spotify's obfuscated classes; see [its README](theme/dex/stubs/p/README.md). |
| `theme/player/` | Pinned resource overrides. |
| `docs/targets.md` | Which app code owns each screen and why each target was chosen. |

## Checks

```bash
cargo fmt --check
```

```bash
cargo test --release
```

```bash
cargo clippy --release -- -D warnings
```

```bash
python3 tests/player-runtime.py
```

The runtime tests run helper logic on JVM doubles. They do not replace testing on a device.

With a phone connected and Spotify open on Home, this checks every icon name against the APK, then
flips every switch and option on the settings pages and restores each one:

```bash
python3 tests/device-settings.py <base.apk>
```

Add `--static-only` to run just the icon check without a phone.

## License

MIT, as declared in `Cargo.toml`. Spotify is a trademark of Spotify AB, and this project is not
affiliated with it. No Spotify code or assets are stored in this repository.
