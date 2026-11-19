# Spatch

Spatch runs a Rust-driven Android UI over Spotify's existing playback, cache and Connect services.
This branch supports Spotify **9.2.0.26** (version code **148373686**) and supports Spotify’s native sign-in flow on fresh installs. APKM and split hashes are pinned in [src/input.rs](src/input.rs).

See [the app README](app/README.md) for UI features and the standalone preview, and
[Spatch Manager](manager/README.md) for applying and signing bundles on Android.
[Development notes](docs/development.md) map the sources, lifecycle rules and validation commands.
The earlier redesign lives on the `main` branch.

## Requirements

- Rust with 2024 edition support, a JDK, apktool, Android SDK platform 35 or later, and Android SDK build tools
- The matching Spotify APKM
- The [ARM64 Rust library build](app/README.md#build) for signed patches and bundles
- Your signing key for signed patches

## Usage

```bash
cargo build --release
```

Check an APKM:

```bash
./target/release/spatch inspect <input.apkm>
```

Verify the APKM and rebuild the DEX with the JDK, apktool, Android platform JAR, `d8` and
`dexdump`:

```bash
./target/release/spatch patch <input.apkm> --out <new-dir> --dry-run
```

Build a signed set. The password is read by `apksigner` from the environment variable you name, and
`spatch` never reads or prints it:

```bash
./target/release/spatch patch <input.apkm> --out <new-dir> --keystore <file> --alias <name> --ks-pass-env SPATCH_KS_PASS
```

Write a patch bundle for [Spatch Manager](manager/README.md). Bundle creation also uses `aapt2`:

```bash
./target/release/spatch bundle <input.apkm> --out <new-file.zip>
```

Prepare a [private source](docs/private-source.md) for invited phones. This command only needs
`aapt2` from the Android SDK:

```bash
./target/release/spatch source <input.apkm> --out <dir>
```

Install every split together. Update in place: uninstalling loses the app's data, and the signer must
match any Spatch build already on the phone.

```bash
adb install-multiple -r base.apk split_config.*.apk
```

## How it works

The patcher checks the input hashes, applies six guarded smali edits, then builds the Java helper
DEX. Signed patches and Manager bundles also include the ARM64 Rust library. See the
[hook map](docs/spotify-map.md) and [porting notes](docs/targets.md).

| Path | Role |
| --- | --- |
| `src/input.rs` | Supported input and split hashes |
| `src/edits.rs` | Smali edits and their required anchors |
| `src/dex.rs` | DEX reassembly, relocation and helper compilation |
| `src/bundle.rs` | Manager bundle creation and clone metadata |
| `java/dex/stubs/` | Compile-only Spotify Transport and RxJava stubs |
| `src/source.rs` | APKM and index staging for private sources |

## Checks

```bash
cargo test --locked
cargo clippy --all-targets --locked -- -D warnings
cargo test --manifest-path app/Cargo.toml --locked
cargo test --manifest-path app/Cargo.toml --features preview --locked
python3 tests/nightly-inputs.py
JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/android-sdk python3 tests/java-contracts.py
app/preview/mac.sh --check
```

Run the real-APKM smoke test with the pinned archive and Android tools installed:

```bash
SPATCH_APKM=/path/to/spotify.apkm cargo test --locked dry_run_on_supplied_apkm -- --ignored
```

For a local nightly bundle without publishing, use the [ARM64 build prerequisites](app/README.md#build).
The script builds the library from the selected Git commit. Set `SPATCH_APP_SO` to use a prebuilt library.

```bash
SPATCH_APKM=/path/to/spotify.apkm NIGHTLY_BUILD=normal NIGHTLY_OUT=/absolute/output/dir scripts/nightly.sh
```

See [Manager checks](manager/README.md#checking) for comparing a Manager-applied bundle with the
desktop patcher output.

## License

[MIT](LICENSE). Spatch is unofficial and unaffiliated with Spotify AB.
