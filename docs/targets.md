# Patch targets and version porting

Spatch supports the exact ARM64 Spotify APKM for `com.spotify.music` version **9.2.0.2053**
(`148387875`). [crates/patcher/src/input.rs](../crates/patcher/src/input.rs) pins the APKM and every split by SHA-256.
`spatch inspect <input.apkm>` checks the archive and prints the supported hashes.

Spatch reassembles `classes.dex`, `classes2.dex`, `classes3.dex`, `classes9.dex` and `classes11.dex`, and adds `classes16.dex`.
Spotify's resources and other DEX entries stay byte-identical; the manifest and resources table gain Spatch's two home-screen widgets (4×2 and 4×1, the old Spatch widget design: layouts, animations and drawables in `android/widget/res`, compiled by aapt2 and listed by `crates/patcher/src/resources.rs`; the layouts reuse the ids of Spotify's own widget views, read from the app being patched).
See the [hook map](spotify-map.md) for the classes and calls involved.

## Porting a new Spotify build

1. Run `python3 tools/port/port.py <old.apkm> <new.apkm> --work <work-dir>` to decode both APKMs
   and generate proposed class, member and resource mappings.
2. Review the [manual alias table](../tools/port/aliases.tsv) and compare classes with
   `python3 tools/port/diff-target.py <alias-or-smali-path> --work <work-dir>`.
3. Update the pinned input hashes in [crates/patcher/src/input.rs](../crates/patcher/src/input.rs), the DEX hashes and relocation
   paths in [crates/patcher/src/dex.rs](../crates/patcher/src/dex.rs), and any changed paths or anchors in [crates/patcher/src/edits.rs](../crates/patcher/src/edits.rs).
4. Run the [DEX dry run](../README.md#usage) and test a signed build on a device. The patcher checks
   that every anchor matches once and that rebuilt DEX files stay within the reference limits.
