# Contributing to Spatch

## Code & Comment Style

- **Comments explain why, not what.** Write full-sentence comments only where the reason, invariant, or
  edge-case protection isn't obvious from the code itself.
- **No section-header or banner comments.** Never add `// ---- Tabs ----` or `// ===== section =====`
  banners; organize files by grouping related functions and types together.
- **Keep changes minimal and local.** Prefer standard library or platform APIs over hand-rolled helpers,
  and keep functions small and single-purpose.

## Performance & UI Rules (Android)

- **LTPO 120 Hz discipline:** Keep reactive audio-visualizer and scroll animations at native 120 Hz;
  pace passive ambient motion (background motes, non-reactive artwork spin) to ~60 Hz.
- **Never redraw when hidden or occluded:** Guard `postInvalidateOnAnimation()` / `postInvalidateDelayed()`
  with visibility checks (`isShown() && getWindowVisibility() == VISIBLE`) and `!playerOpen` when behind
  the opaque player sheet.
- **Zero allocations in `onDraw()`:** Reuse `Paint`, `Path`, `RectF`, `Matrix`, and `Shader` instances;
  never allocate gradients or read `SharedPreferences` inside `onDraw()`.
- **Prefer GPU-backed properties:** Animate `translationX`/`translationY`, `scaleX`/`scaleY`, `alpha`, and
  `RuntimeShader` uniforms rather than triggering layout passes or software bitmaps.

## Pre-Push Checks

Run these before pushing changes:

- **Mac preview & Java UI:** `app/preview/mac.sh --check`
- **Java bridge contracts:** `python3 tests/java-contracts.py` (with `JAVA_HOME` and `ANDROID_HOME` set)
- **Rust core (`app/`):**
  - `cargo test --locked`
  - `cargo test --features preview --locked`
  - `cargo clippy --all-targets --locked -- -D warnings`
  - `cargo clippy --all-targets --features preview --locked -- -D warnings`
  - `cargo ndk -t arm64-v8a -P 29 build --release --locked` (with `ANDROID_NDK_ROOT` set)
- **Patcher (repo root):**
  - `cargo build --release --locked`
  - `cargo clippy --all-targets --locked -- -D warnings`
  - `cargo run --release --locked -- patch --dry-run` (against the pinned Spotify APKM)
