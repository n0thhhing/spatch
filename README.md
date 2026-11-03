# Spatch

A Rust command-line patcher that gives one exact build of Spotify for Android (9.1.98.521, version
code 148115007) a Material You redesign. It takes the APKM, applies guarded edits and writes a signed
split-APK set. It never modifies the input.

**Website:** [n0thhhing.github.io/spatch](https://n0thhhing.github.io/spatch/), with features, themes, an install guide and screenshots.

[Spatch Manager](manager/README.md) does the same on the phone: it applies a published patch bundle
to your own APKM and signs it with a key that never leaves the phone.

This is a personal project. It supports only the build pinned in `src/input.rs`: any other input is
refused rather than patched blindly.

## What it changes

- **Themes:** Lavender, Gruvbox, Catppuccin, Rosé Pine, Nord, Tokyo Night, Dracula and Gruvbox
  Material, plus custom fonts.
- **Player:** circular artwork, a wavy or audio-reactive ring, a configurable glow, record-style skip
  animations, wavy progress, shaped controls and an immersive full-screen player with a real spectrum
  of Spotify's own audio. Artwork comes in several shapes with drifting dust or stars behind it, leans
  under your finger, pulses with the bass, and can be held and spun like a record to seek.
- **Controls:** hold Next or Previous to run through a song, a time bubble while seeking, seek bar
  styles, a player colour that can follow the album, and swipe to skip in the immersive player.
- **Screens:** a floating navigation bar that can hide while you scroll, sliding filter pills, a
  redesigned side panel, cards on Library, playlist, search and album rows, softened page headers, and
  a local-only profile banner.
- **Feel:** bouncy press on tiles, tap ripples, a spinning mini player cover, an album-tinted mini
  bar, and bursts on the play button.
- **Content:** hide podcasts, audiobooks, Home sections and side menu rows.

Everything is configured in the app: open the side panel and choose **Spatch**. Each feature has a
main switch and a page of finer options, and settings carry over between updates. Search finds any
setting by name, Calm and Everything turn the playful extras off or on together, and your own named
presets save toggles, choices and strengths. Sliders adjust artwork tilt, ripple size and the scroll
distance before the bars hide. Copy and Paste move those settings between installs.

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

Write a patch bundle for Spatch Manager instead (no signing; see [the manager](manager/README.md)):

```bash
./target/release/spatch bundle <input.apkm> --out <new-file.zip>
```

Prepare a private source that invited phones download the APKM from (see
[docs/private-source.md](docs/private-source.md)):

```bash
./target/release/spatch source <input.apkm> --out <dir>
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
| `src/edits.rs` | Guarded smali edits. Each must match its anchor exactly once. |
| `src/helpers.rs` | The helper Java and stub file lists. |
| `src/dex.rs` | The helper DEX build and reassembly. |
| `src/bundle.rs` | Patch bundles for Spatch Manager. |
| `src/source.rs` | Files for a private source: the checked APKM and its `index.json`. |
| `source/cloudflare/` | A free private source: a Cloudflare Worker in front of a private GitHub release. |
| `manager/` | Spatch Manager, the Android app that patches, signs and installs on the phone. |
| `src/theme.rs` | Theme colour roles. |
| `theme/dex/src/spatch/` | The Java helper classes added to the app, by area: `player`, `mini`, `lists`, `audio`, `settings`, `look` (themes, fonts), `motion` and `dev`. All are one Java package. |
| `theme/dex/stubs/` | Compile-only stubs. `p/` holds Spotify's obfuscated classes; see [its README](theme/dex/stubs/p/README.md). |
| `theme/player/` | Spatch's own resources, and diffs (`*.diff`) applied to the decoded originals. The diffs hold only Spatch's added lines. |
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

## License and disclaimer

MIT; see [LICENSE](LICENSE). Bundled fonts keep their own licences in `theme/fonts/licenses/`. The icons in
Spatch settings are [Material Symbols](https://github.com/google/material-design-icons) (Google, Apache 2.0).

Spatch is unofficial and not affiliated with or endorsed by Spotify AB; Spotify is its trademark. No
Spotify code or assets are stored in this repository, and no Spotify APKs are distributed here: you
supply your own APKM. The patch bundles on the releases page are binary diffs against that APKM;
because Spatch rebuilds Spotify's DEX files, those diffs include parts of Spotify's code in
re-encoded form. Spatch changes only how the app looks and moves. Modified clients are against
Spotify's terms of use, so use it at your own risk.
