# Patch targets — Spotify 9.1.98.521 (148115007)

## Player effects (2026-10-28)

### Catchphrase pill (built, signed, device-verified)

An artwork easter egg (`Toast` showing "i mean i guess bro") is
triggered by a double tap on `PlayerArt`. `dispatchTouchEvent` only observes events:
two DOWNs within 300ms and 2× slop of each other, with no move/cancel past the slop
in between, so carousel swipes don't trigger it. It always forwards to `super`,
leaving Spotify's gestures alone. The pill is drawn over the art in `onDraw`: an
inverse-surface pill, a soft-burst sparkle in the darker accent, and bold dark text.
It fades in over 250ms with a spring from 0.9 scale, holds until 2.2s, then fades
out over 250ms. With system animations off, it snaps in and out. Colors use theme
tokens. New compile stub: `android.view.MotionEvent`.

Installed `/tmp/spatch-catchphrase` (gruvbox, device test key) in place on Pixel.
A double tap shows the pill over the art (`/tmp/spatch-cp-3.png`). It is gone after
2.2s, and a single tap doesn't show it (`/tmp/spatch-cp-4.png`). Crash buffer empty;
no verifier/link/class errors.

### Motion polish

Spin/skip follow-up: Scallop integrates contour angle only while its requested
state is 9 lobes (playing), at one turn per 12 seconds. Pause freezes the angle;
resume continues. Frame delta is capped at 80ms to avoid off-screen jumps. Only
outline coordinates rotate, leaving the glyph upright; reduced motion disables it.
TapBounce now includes native buttons wrapped in Transport, scaling the whole view
rather than just its background icon. Rebound coefficient increased to 0.28 for
clearer feedback. Transport no longer scales its own drawing (avoids double scale);
previous/next nudge in their own direction during press and release. Neutral,
interaction-only feedback and mirrored surfaces remain.

Device-verified `/tmp/spatch-spin-skip-bounce`: 8s recording
`/tmp/spatch-spin-skip-verified.mp4` / frame sheet confirms both skip-button presses,
moving playing outline with upright icon, then frozen paused outline. Media session
PAUSED after test; no crash-buffer entries. 22 tests pass, 1 ignored; formatting,
clippy, release and real signed build pass.

Click bounce: transport icons and surfaces compress to 90% while pressed, then
rebound with a damped 500ms spring on release. Scallop's canvas transform also
scales the original play/pause glyph (the owning lbg restores the canvas after
drawing); its 5/9-lobe morph continues independently. Shape switches trigger the
same rebound if a tap is too short to render a pressed frame. No hit-box changes.
System disabled animations suppress compression/rebound and extra frame scheduling.

Extended click bounce to other native player buttons via `TapBounce`: samples
pressed state, scales to 90%, then settles over 500ms. Traverses only the current
player window while PlayerArt is visible; small clickable wrappers animate once,
otherwise native clickable leaf controls animate. Existing touch/click listeners
are never replaced. Custom Transport/Scallop leaf drawables keep their own motion.
The weak state map drops settled entries; disabled/reduced-motion states reset scale.
PlayerArt ticks at 16ms while shown for smooth feedback. Compose virtual controls
are not individually discoverable as native Views; their existing animation remains.

Installed `/tmp/spatch-player-button-bounce-v2`; frame sheet
`/tmp/spatch-share-bounce-verified.png` confirms share/queue shrink and rebound.
Touches were cancelled after held presses to preserve action state. No crashes.
22 tests pass, one ignored; fmt/clippy/release and signed real-input build pass.

Latest device verification: installed `/tmp/spatch-play-pause-morph` in place.
Frame sheet `/tmp/spatch-morph-frames.png` from the 7s recording
`/tmp/spatch-shape-morph-check.mp4` shows both 5→9 and 9→5 intermediate outlines.
`/tmp/spatch-large-feedback.png` shows enlarged neutral previous-button feedback
without clipping. Crash buffer empty; no verifier/link errors. 22 tests pass (one
ignored); fmt/clippy/release and real signed build pass.

Play/pause follow-up: `Scallop.draw` now receives its owning `lbg` Drawable through
the existing classes3 call site. A WeakHashMap keyed by the shared ImageButton
callback joins the otherwise separate play/pause drawables. It blends periodic
5- and 9-lobe contours with 440ms smoothstep and a 3.5% settle, retaining continuity
during rapid toggles. Frame scheduling uses the currently displayed drawable;
weak keys/references do not retain the player. System disabled animations snap.
`Transport` radius grew from 0.47 to 0.62 of icon bounds (32% larger). Clipping is
disabled only inside the player transport subtree so decoration can extend beyond
the icon while preserving original sizes/hit boxes and neutral hover/press policy.

`Transport` now animates surface presence with a 350ms damped spring on entry and
cubic fade on exit. Directional skip icons nudge 3.5% of their width while pressed;
their hit areas stay fixed. Neutral fill and mirrored direction are preserved.
`PlayerArt.setImageDrawable` retains the outgoing drawable for a 420ms cubic
crossfade with a subtle 0.97→1 reveal scale. Bounds are restored after drawing.
`Atmosphere` interpolates sampled colors over 900ms using smoothstep and blends
the small background rasters. All transitions snap to final state when system
animations are disabled. Ambient ring/backdrop motion remains separately enabled.

Built/signed/installed `/tmp/spatch-player-motion` in place on Pixel. Recording
`/tmp/spatch-motion-check.mp4` captures neutral button feedback, changing artwork,
backdrop color transitions, and Spotify's retained carousel swipe transitions.
No crash-buffer entries or recent verifier/link errors. 22 unit tests pass, one
expensive dry-run ignored; signed real-input build, fmt, clippy, release build pass.
Reduced-motion branch is present but the device OS setting was not changed/tested.

- One new pinned resource: `square_cover_art_content.xml`, original SHA-256
  `be430f822bf1505cb7ec73f65dee5f6c4c6cfd82b7a90654357fcbbfaf581d97`.
  `p.x7b1` inflates this for the music artwork. Only its ImageView type becomes
  `spatch.PlayerArt`, retaining Spotify's loader, IDs, gestures, and other children.
- `PlayerArt`: clips artwork into a circle, paints an accent halo and 24-wave ring.
  `Atmosphere`: captures the drawable into a cached 12x12 raster on drawable changes,
  uses bilinear enlargement plus drifting radial color fields behind the player.
  Original drawable bounds are restored after sampling. No audio capture permission.
- `Transport`: state-aware 4/6/3-lobe surfaces for shuffle/repeat/skip. Preserves and
  draws original background icons and forwards drawable state. First device build
  hid these background icons; fixed and verified in the final installed build.
  Previous uses shape ID -3, horizontally reflecting its surface; Next uses +3.
  The original previous/next icons are drawn without reflection.
  User correction: decorative surfaces appear only while enabled and pressed/hovered,
  using neutral white at 25%/14% opacity rather than the orange/theme accent. Idle
  controls keep their original icon with no added shape. Untoggled hover/press
  feedback uses a neutral surface role.
  Buttons are found inside the player transport container by English accessibility
  labels; other locales currently retain their original controls.
- Extended split-reference declarations to keep `square_cover_art_size` (0x7f070755)
  and `square_cover_art_top_margin` (0x7f070756). Placeholder dimensions exist only
  in compilation staging, not the shipped resource table. Both density splits checked.
- Six helper classes total; compile-only Android stubs are never packaged.
- Built/signed/installed `/tmp/spatch-player-effects-final`, gruvbox, same device key.
  Pixel: circle/ring/halo, artwork-derived backdrop, shaped controls visible; original
  icons restored. Pause/play and track change checked. Crash buffer empty, no recent
  verifier/link/fatal errors. Screenshot `/tmp/spatch-effects-final.png`.
- 22 unit tests pass, 1 expensive dry run ignored; real signed build passed pipeline
  guards. Formatting, clippy and release build pass.
- Limits: ring/backdrop motion is ambient, not audio-reactive or pause-synchronized.
  System disabled animations disable motion in code; the OS toggle wasn't changed
  during testing. No GPU/frame-time profiling, TalkBack, rotation or video-player test.

## Rounded Compose artwork (2026-10-28)

`p.kvx.a(long, ikb0, kat)` in the pinned `classes.dex` implements the current Encore
artwork shape (`bv61`). `eqo1` constructs it as the default artwork shape; callers
also include `fob1`, `md60`, and `r0a1`. It converts the longest side to dp and chooses
4dp at sizes >=33dp, otherwise 2dp. Two unique constant anchors now select 16dp and
8dp respectively. The threshold, outline calculation, density conversion and artist
circle branches remain intact. No method or field references are added.

This differs from the older `n37` / `yzb.c` path previously tested without
visible changes. Original APKM and DEX hashes remain the existing compatibility guard.
The new unit test rejects missing, duplicate, and already-patched radius anchors.

Runtime-tested on Pixel 11 Pro: Library covers now have visibly rounded corners;
artist circles remain circles. Playlist header and track-row artwork also show the
larger corners, and tapping a Library playlist opens normally. In-place install
succeeded with the existing certificate, preserving login. Crash buffer empty;
recent logs have no VerifyError/NoSuchMethodError/NoSuchFieldError/FATAL EXCEPTION.
Output: `/tmp/spatch-rounded-art` (gruvbox). 21 unit tests pass, one expensive dry-run
test skipped; the full signed real-input build passed all pipeline guards instead.
Formatting, clippy and release build pass.

Status labels: **inspected** (static evidence), **built** (in a spatch output), **signed** (apksigner-verified), **runtime-tested** (seen on a device). Nothing here is runtime-tested yet: no device was attached.

## Package facts (inspected)

- `aapt2 dump badging base.apk`: `com.spotify.music`, versionCode 148115007, versionName 9.1.98.521, targetSdk 37, compileSdk 37.
- Base manifest: `requiredSplitTypes="base__abi,base__density"`. Install set = base + `config.arm64_v8a` + one of `config.xxhdpi` / `config.xxxhdpi` (+ `config.en`). The two density splits are alternatives.
- Original signer: `CN=Spotify, OU=Android, O=Spotify, L=Stockholm`, cert SHA-256 `6505b181…8ffd`; v2+v3 and Google source stamp. Re-signed output cannot update a stock install.

## Why entry injection instead of a full apktool rebuild

- `apktool d` on base alone warns `Unresolved resource reference` for IDs that only the density splits define (e.g. `0x7f0707c4 dimen/track_info_subtitle_text_size`, 16sp in `config.xxhdpi`). apktool writes those references as `@null` (288 occurrences in decoded layouts).
- A rebuilt base compiles (after removing two `recreateOnConfigChanges` values aapt2 36 rejects), and `aapt2 dump xmltree` of rebuilt vs original layouts is identical **except** those `@null` references — confirmed on `track_info_nowplaying_view.xml`. Shipping a rebuilt `resources.arsc`/layouts would silently drop them.
- Therefore spatch compiles the patched XML with apktool in staging, checks every `@0x7f…` reference exists in the original table and that none is `@null`, and injects only those compiled entries into the original base. `resources.arsc`, `AndroidManifest.xml` and all 15 DEX files stay byte-identical (confirmed by inventory/CRC diff: only the 4 targets plus new signature files differ).

## Now Playing ownership (inspected via JADX, not runtime-confirmed)

| Resource | Owner | Evidence |
| --- | --- | --- |
| `layout/activity_now_playing` | `com.spotify.nowplaying.musicinstallation.NowPlayingActivity` | `setContentView(... R.layout.activity_now_playing)`; hosts mode fragments |
| `layout/revised_template_page_layout` (+ `include` `revised_template_overlay`) | `p.jcs` (`n9c0`, `p3p0`) | `inflate(R.layout.revised_template_page_layout)`; `jcs` constructed in 5 branches of `p.dap` (the shared full-player template) |
| controls in `playback_controls_container` | built in code by `p.jcs` via `vsh.Q(...)` | no layout file; play/pause, prev/next, seekbar are code/Encore components |
| `layout/track_info_nowplaying_view` | `p.u1o` | `inflate(R.layout.track_info_nowplaying_view)` |
| `drawable/nowplaying_overlay_gradient` | player overlays only (revised template, endless feed, ads, video-first, tablet) and `OverlayHidingFrameLayout` | grep of decoded res + JADX |

Constraints honoured: `player_overlay_footer` stays a direct child (`p.zxd` measures its `getBottom()`), both barriers keep their ids (`p.lzf0`).

## Patched entries (built, signed)

| Entry | Original SHA-256 | Change |
| --- | --- | --- |
| `res/layout/revised_template_page_layout.xml` | `c616ffbf…9361` | player surface `#14121C` instead of `?pasteColorBackground` |
| `res/layout/revised_template_overlay.xml` | `973d7edf…35f9` | footer becomes a floating rounded card (36dp, existing opaque `av_rounded_background` tinted `#EB1F1B2E`, 12dp side inset); transport row in a pill tray (same drawable, `#2B2640`, 12dp padding). An earlier build used the 30%-alpha `seek_timestamp_background_large`, which made the tint render faintly (found on device 2026-10-28) |
| `res/layout/track_info_nowplaying_view.xml` | `9bf266fa…614e` | title 24sp `sans-serif-black`, subtitle 16sp lavender `#C6B8FF` (explicit sizes replace split-only dimens) |
| `res/drawable/nowplaying_overlay_gradient.xml` | `441662e8…3d55` | clear-top → deep violet scrim |

All colours/sizes are **proposed** Android values on Spatch's radius scale (8/12/17/23/30) and its lavender/dark direction, not extracted tokens. No third-party code, fonts or assets are used.

## Player controls trace (inspected via JADX + apktool smali, not runtime-confirmed)

### Play/pause

- `p.jcs` builds the transport row with `vsh.Q(playback_controls_container, prev?, wd51.a, rem-view, wd51.b, next?)`; the centre view comes from `rem` (built at `jcs` constructor line 153 from `cot0`).
- `rem` (mode 2) yields view holder **`p.lot0`** (`kot0` case 1, `classes9.dex`). Its root is a `FrameLayout` with id `nowplaying_elements_playpause_button` containing a `LottieAnimationView`, an `AppCompatImageButton` and a `ComposeView`.
- Variant chosen in `jcs`: `this.e1.d ? fot0 : eot0`. `eot0` (default) uses the `AppCompatImageButton`. `fot0` (feature flag) renders a Compose button (`e1p1.e` → `f1p1.j`), which these patches would not reach.
- Default image: `p.lbg` (`classes3.dex`), a `Drawable` wrapping the Encore icon drawable `p.w2b1` (`z2b1.PLAY` / `z2b1.PAUSE`, 64dp) at scale 0.45. Fill colour is `encore_button_white` (0x7f0606aa, CSL: white / 70% pressed / 50% disabled); the icon is `encore_button_black` (0x7f0606a0).
- **Shape owner:** `lbg.draw()` → `canvas.drawCircle(0,0,r,this.d)` (`smali_classes3/p/lbg.smali` ~line 351; a second stroke circle runs only when strokeWidth > 0, which is 0 here).
- `lot0` keeps **two separate `lbg` instances**: `a` (play icon, shown while paused; `new-instance` at `lot0.smali` ~116) and `b` (pause icon, shown while playing; ~208). `lot0.b()` swaps them on state. So a 5-bump paused shape and a 9-scallop playing shape need no new state handling.
- The other `lbg` user is `p.o2a` (`classes3.dex`, scale 0.6). It must keep drawing a circle.

### Seekbar

- `p.ews` (`classes9.dex`) implements `TrackSeekbarNowPlaying`. It inflates `layout/track_seekbar` (or `video_first_track_seekbar`) and holds a `CancellableSeekBar` styled `NowPlayingSeekBar` (`progressDrawable=@drawable/seekbar_progress`, `thumb=@drawable/seekbar_thumb_default`).
- On each state update `ews.g()` calls `setThumb(getDrawable(seekbar_thumb_default))` and `getProgressDrawable().setColorFilter(getColor(default_seekbar_color), SRC_IN)`. `default_seekbar_color` (0x7f06066c, `#ffffff`) is an arsc value, not a file; the constant is loaded at `ews.smali` ~line 737. Themed thumbs/colours for special tracks come from `p.vjv`.
- A wavy "playing" track needs code (a custom progress drawable driven by play state). The thumb and track geometry can be changed by entry injection: `seekbar_thumb_default.xml` and `seekbar_progress.xml` (`seekbar_progress` is reached only through the `NowPlayingSeekBar` style).

### Hard constraint

`classes9.dex` has `method_ids_size = 65536`, the DEX maximum (`dexdump -f`). Patches in `classes9` (`lot0`, `ews`) must add **no new method references**; field refs (59,763 used) and constant swaps are fine. `classes3.dex` has 64,976 method refs, so there is room for one static call. New helper code belongs in a new `classes16.dex`.

### Controls patch (built and signed, not runtime-tested)

1. New `smali_classes16/spatch/Scallop.smali`: static `draw(Canvas, float r, Paint, int bumps)` that fills a scalloped path (radius modulated by `cos(bumps·θ)`) with lavender RGB and the paint's alpha, so pressed/disabled states still dim.
2. `lbg`: add field `spatchBumps:I`; in `draw()`, if `spatchBumps > 0` call `Scallop.draw`, else keep `drawCircle`. `o2a` stays a circle.
3. `lot0`: after each `lbg` constructor, `iput` 5 (play icon / paused) or 9 (pause icon / playing). Adds field refs only.
4. `ews`: change `const v1, 0x7f06066c` to `0x7f0608ef` (`m3_ref_palette_primary80`, #D0BCFF), which tints the progress lavender. Inject the new `seekbar_thumb_default.xml` (pill thumb) and `seekbar_progress.xml` (4dp rounded track).
5. Pipeline: `apktool d` (full smali decode of base took ~11 s), patch with guarded exact-match edits, `apktool b`, inject only `classes3.dex`, `classes9.dex` and the new `classes16.dex`. Guards: rebuilt `classes9` method ids ≤ 65536, and every other DEX unchanged.

Implemented in `src/dex.rs` (smali edits with exactly-one-anchor guards, pinned SHA-256 of the original `classes3`/`classes9`, `dexdump -f` reference-limit check) and `theme/dex/src/spatch/Scallop.java` (compiled with `javac --release 11` against compile-only stubs, then `d8 --min-api 24`; the helper DEX must contain exactly `spatch.Scallop`). `theme/player/res/drawable/seekbar_{thumb_default,progress}.xml` are injected like the layouts.

Output evidence (`dexdump`): `classes3` has 64,977 method refs (+1, the `Scallop.draw` call) and 45,494 field refs (+1). `classes9` has **65,536 method refs (unchanged)** and 59,764 field refs (+1). `classes16.dex` has 13 method refs. The patched instructions are present: `lbg` has `iget spatchBumps` / `invoke-static Scallop.draw`; `lot0` has `iput spatchBumps` twice; `ews` has `const v1, 0x7f0608ef`.

Known side effects:
- The original Google source stamp (`stamp-cert-sha256`) is gone. It cannot be valid after re-signing.
- `assets/dexopt/baseline.prof` still describes the original `classes3`/`classes9`, so ART will probably ignore the profile for those files. That costs startup speed, not behaviour.

Not covered by these patches: the Compose `fot0` variant and the wavy animated seekbar.

## Mini-player (built, signed, runtime-tested on Pixel 11 Pro)

- Owner: `p.m1p0` inflates `layout/now_playing_bar` (a MotionLayout). Geometry comes from `xml/now_playing_bar_scene` (`default_size` / `large_size` ConstraintSets), which overrides layout-file constraints. `TrackInfoView` inflates `now_playing_bar_track_info_view`.
- Play/pause: `m1p0` builds `rem` → `p.lot0` in compact mode (`got0`), which showed bare 24dp `w2b1` icons (fields `c`/`d`). Two smali edits in `lot0.b()` read the scalloped `lbg` fields `b`/`a` instead (existing field refs, no new method refs; `classes9` stays at 65,536). Compact mode is also used by `p.spd0` (live-room bar), `p.wlj1` (video-first player), `p.yvb1` and `p.rk9` (Compose hosts of the same component).
- Injected: `layout/now_playing_bar.xml` (cover `image_bg` 8dp outline + `clipToOutline`, progress tint `#D0BCFF` / track `#40D0BCFF`), `xml/now_playing_bar_scene.xml` (play/pause 48dp, progress 3dp inset 16dp, 3dp off the bottom), `layout/now_playing_bar_track_info_view.xml` (`sans-serif-black` title, `#C6B8FF` subtitle). The original layout has two deliberate `@null` ViewStub backgrounds, so the reference guard now compares `@null` counts with the original compiled entry.
- Bar shape: the 8dp outer corners came from `setCornerRadius` on the bar's own background after inflation (logged at runtime: 73.5 px, then 21 px), not from a Compose host; an earlier note here said otherwise. `spatch.MiniSurface` ignores that call and artwork tint (`p.tro0`, `Z.getBackground().setTint`). Root margins add 8dp to the host's 8dp so the pill lines up with the nav bar.
- Row: save, Previous, play/pause, Next on an even 48dp pitch, end-anchored (device bounds 481, 607, 733, 859 px, each 126 wide). `progress_bar` is a 48dp ring around Play (`spatch.MiniRing`, set by `MiniBar`; replaces `seekbar_progress` here, so the wave setting no longer affects the mini-player). The scene sets the hidden Connect stub to alpha 0 because ConstraintSet resets it.

## Bottom navigation (built, signed, runtime-tested on Pixel 11 Pro)

- `layout/vertical_main` (phone portrait `MainLayout`) hosts `navigation_bar`, a `MainLayoutContentContainerView` (Compose host). Its constructor reads styleable `p.vmz0.c` = `contentBackground`, `contentCornerRadius`, `contentMarginBottom`, `contentMarginTop` and draws the background and corners itself. Runtime overrides exist only for `bannerContainer` (`p.c0g0`) and the now-playing container background (`p.a6p0` via `p.izf0`).
- Injected `res/layout/vertical_main.xml`: `navigation_bar` gets `contentBackground #F21D1A2A`, `contentCornerRadius 28dp`, 12dp side and 6dp bottom margins (a floating rounded panel). The bottom gradients go from black to violet (`#E614121C`, `#FF14121C`).
- Tabs: the four tab items are Compose (`uiautomator`: id-less `View`/`Button` with "Home, Tab 1 of 4" descriptions, built from `p.psn0` with the label in `p.tb3`). Selected-state colours and icons come from Encore Compose theme code, not resources, so the selected tab keeps Spotify's filled-icon/white-label treatment. A selected pill needs a Compose code patch (done later: `spatch.Pill`).
- Not patched: `horizontal_main` / `large_main` (landscape, tablets).

## Home shelf cards (built, signed, runtime-tested on Pixel 11 Pro)

- `uiautomator` on Home: `recycler_view` → `heading_container` + `carousel` RecyclerViews of `card_root` (`image`, `pre_title`, `title`, `subtitle`). The match is `layout/image_link_card_layout` (inflated by `p.a2s`). The shortcut grid, filter chips and section headings are Compose.
- `ArtworkView` computes its own corner radius (`max(4dp, 2.5% of size)`), and its constructor calls `t0u.v(this, 0f)` → `setClipToOutline(false)`, so the art cannot be clipped directly. Instead the card root clips: `android:background="@drawable/artist_pick_comment_bg"` (existing 20dp, **opaque** fill) + `backgroundTint #1D1A2A` + `clipToOutline`. Text is inset 12dp; the title is `sans-serif-black` `#F3EEFF` and the subtitle `#BCB3DC`.
- Lesson (device-verified): `backgroundTint` uses SRC_IN, so it keeps the borrowed drawable's alpha. The first build used a drawable with a translucent theme fill, and the panel rendered at ~7% (pixel sample 19,19,20 vs page 18,18,18). Only opaque-filled drawables are used now; the Now Playing card/tray were moved to `av_rounded_background` for the same reason.
- The same Encore card appears in other shelves ("New releases for you" confirmed). `recents_card_layout` (Recents shelf) and the Compose shortcut grid/headings are not patched.

## Search, Library and the Compose palette (built, signed, runtime-tested on Pixel 11 Pro)

- Ownership (`uiautomator`): Search is one full-page `ComposeView`; Library is Compose (69 id-less `View`s, `TextView`/`Button` without ids). Neither has a layout or drawable that controls its structure, so resource injection cannot change their layout or shapes.
- Lever used: the Encore Compose palette `p.ggx` (`classes.dex`, `<clinit>` builds `lex` sets: base, bright accent, negative, warning, positive, announcement…). Each colour is loaded once by `const-wide` and reused through registers. Seven guarded constant swaps (no new references; `classes.dex` stays at 65,535 method refs):
  - `#121212`→`#14121C` (base background), `#1F1F1F`→`#1D1A2A`, `#2A2A2A`→`#2B2640`, `#B3B3B3`→`#BCB3DC` (subdued text)
  - `#1ED760`→`#D0BCFF`, `#3BE477`→`#E0D2FF`, `#1ABC54`→`#B7A3EE` (bright accent). The same register feeds the **positive** set, so Compose success/"added" green also becomes lavender.
- Device results: Library background samples `(20,18,28)` = `#14121C`; pins, now-playing title and secondary text are lavender. On Home, the selected "All" chip and the now-playing indicator sample `(208,188,255)` = `#D0BCFF`.
- Not reached: the Home and Search page backgrounds stay `#121212`. Tried and reverted: (1) `launcher_screen` window background (kept, it changes the splash/window surface, but these pages paint over it); (2) `p.jex` grayscale `#121212` → no visible change, so the edit was removed. The remaining source is most likely the View theme attribute `?backgroundBase` → a `resources.arsc` colour value, which this pipeline does not modify. The Search field, category tiles (server colours), Library rows/chips layout and shapes are unchanged. View-based greens (e.g. the "saved" check in player and mini-player) come from arsc colours and stay green.
- New mechanism: `SPLIT_REFS` declares density-split-only resources (here `drawable/ic_spotify_icon_rgb_green` `0x7f080a71`, the splash logo) with their exact IDs in the staging resource table, after checking they are absent from base and identical in both density splits. Compiled XML can then keep those references; the reference guard accepts them.

## Theme colour patch in resources.arsc (built, signed, runtime-tested on Pixel 11 Pro)

- Why: the Home/Search page background comes from the View theme. `?backgroundBase` → `@color/dark_base_background_base` → `@color/gray_7` (`0x7f060741`, `#121212`), an arsc value that no file entry can change.
- `src/arsc.rs`: in-place swaps of literal colour `Res_value`s (8 bytes: size=8, res0=0, dataType 0x1c ARGB8 or 0x1d RGB8, data). Same size in and out, so no offsets or chunks move. Pinned occurrence counts per colour, from the original table: `#121212`×12, `#1F1F1F`×6, `#2A2A2A`×6, `#B3B3B3`×3, `#1ED760`×28, `#1DB954`×1, `#3BE477`×4. Any mismatch (other build, already patched) fails the build. The targets mirror the Compose palette (`p.ggx`) swaps.
- Packaging: `rewrite_apk` now keeps each replaced entry's original compression method, so `resources.arsc` stays **stored** (required for mapping); zipalign then aligns it. The output check requires the arsc hash to differ from the original (`cc2b8bbe…4eb4`).
- Verified: output arsc stored, same size (3,325,464 bytes), 165 bytes differ; `aapt2 dump resources` shows `color/gray_7 () #ff14121c`.
- Device (`out13`, installed in place): Home, Search and Library backgrounds all sample `(20,18,28)` = `#14121C`. The Home shortcut tiles and voice bar pick up the violet elevated colours. No crash entries for Spotify; 0 verify/link/`Resources$NotFoundException` matches.
- The animated "saved" check was Lottie JSON, now recoloured (see "Lottie animation colours").
- Not changed: colours in the density/language split tables; light-theme colours (`light_*`).

## Playlist/album pages (runtime-tested on Pixel 11 Pro; palette level only)

- Ownership: `layout/playlist_root` (`list_entity_root` → `container`) hosts `layout/playlist_header_layout`, a CoordinatorLayout whose whole content (header art, title, actions, chips, track rows) is one `ComposeView` (`uiautomator`: 74 id-less `View`s). No layout or drawable entry controls the structure.
- The pages already pick up the Compose palette (`p.ggx`) and the arsc colours: violet surface, lavender play button, artist names and chips.
- Shape lever tried and reverted: `p.yzb.c` = `lev0(0.025f, 4dp)`, the Compose corner size used by the `n37` rounded artwork shape (artists use the `l37` circle). It was changed to 6%/6dp in `classes12.dex` and installed (`out14`). Header-art and Library-thumbnail corner pixels were **identical** to before, so those surfaces use another shape. The edit and the `classes12` target were removed (`out15` has the original `classes12.dex`).
- Not changed: header layout, track-row layout, artwork corners and the green "saved" check. Any of these needs a Compose code patch at the specific composables.

## Compose corner sizes (built, signed, runtime-tested)

- `p.c3v` (`classes.dex`) is Compose `CornerSize(dp)`. Its constructor now doubles corners of 12dp or less (4→8, 6→12, 8→16, 12→24; larger radii, pills and NaN unchanged). Device: Search category tiles changed (187/900 corner pixels, visibly rounder).
- Tried and reverted (no pixel change on device): `p.yzb.c` artwork corner token (`classes12`), and `ArtworkView.getContentRadius()` constants (4dp/2.5% → 8dp/6%). Library thumbnails, playlist header art and Home card art use another rounding path, not yet found (measured Library thumbnail corner ≈ 8 px ≈ 3dp).

## Patch-time themes (`--theme`)

- `src/theme.rs`: 7 palettes — `lavender` (default, the original look), `gruvbox`, `catppuccin-mocha`, `rose-pine`, `nord`, `tokyo-night`, `dracula`. Each has 9 roles: surface, elevated, highlight, text, secondary text, subdued text, accent, accent highlight (accent mixed 25% to white), accent pressed (15% to black).
- The lavender values act as role tokens. They are substituted in one pass (no chaining) into the `theme/` XML sources, the `Scallop.java` accent, the smali edit replacements (Compose palette `p.ggx`) and the `resources.arsc` swap targets. Alpha bytes are kept.
- The `ews` seekbar constant now points to `dark_base_essential_brightaccent` (`0x7f0605f6`, `#1ED760`), which the arsc swap maps to each theme's accent.
- Guards: secondary/subdued text ≥ 4.5:1 contrast checked when choosing values (Dracula's subdued text was lightened from 3.0:1); tests check that the usage text lists every theme and that no theme colour equals an arsc search colour.
- Device (`--theme gruvbox`, installed in place): surfaces `#282828`, orange scalloped play/pause, orange seekbar, warm grey cards/tray, cream titles, orange chips and links; no crash entries; 0 verify/link/resource errors. The install manifest records `"theme"`.

## Wavy progress (built, signed, runtime-tested)

- `theme/dex/src/spatch/Wave.java`: a custom `Drawable` in the helper DEX (`classes16.dex`, now `Scallop` + `Wave`; the helper check requires exactly these classes). It draws a stroked sine wave in the theme accent; stroke, amplitude and wavelength are proportions of the drawable height. It is "active" while `onLevelChange` keeps firing (playback position advancing) and eases flat about 1.2 s after it stops. It redraws every 16 ms only while it has amplitude, and stays flat when system animations are off (`ValueAnimator.areAnimatorsEnabled()`, API 26+).
- `res/drawable/seekbar_progress.xml`: the progress layer is `<clip><drawable class="spatch.Wave"/></clip>` (custom drawable inflation, API 24+ = minSdk). The background/secondary layers are 3dp centred rounded tracks. `res/layout/track_seekbar.xml` (new target, `p.ews`) sets the SeekBar to 12dp; `now_playing_bar.xml` uses `seekbar_progress` as the mini-player's `progressDrawable` (8dp tall in the scene).
- Compile-only stubs added: `Rect`, `ColorFilter`, `PixelFormat`, `Drawable`, `SystemClock`, `Build`, `ValueAnimator`, plus more `Paint`/`Path` members. Constants inlined from stubs match the platform (`ANTI_ALIAS_FLAG`=1, `TRANSLUCENT`=-3); `Build.VERSION.SDK_INT` is deliberately non-constant.
- Device (`--theme gruvbox`): while `PLAYING`, the seekbar line has a 21 px vertical spread and the wave phase shifts between frames 300 ms apart (flowing). While `PAUSED` (sampled 3 s after pausing), the spread is 0 (flat). The mini-player shows the same wave while playing. Crash buffer: no Spotify entries; 0 verify/link/inflate errors.
- Limits: "playing" is inferred from position updates, so dragging the seekbar while paused briefly waves. On the mini-player the wave's crest touches the bottom of the cover art.

## Selected-tab pill (built, signed, runtime-tested)

- Owner: `p.psn0.a()` (`classes2.dex`) draws a bottom-navigation tab icon with `n191.r(modifier, 24f)` (Compose `Modifier.size`); `psn0.g` is the selected flag (it also picks the filled icon and label colour).
- `classes2.dex` had 65,535 method refs, so the edit adds exactly one: that call becomes `spatch.Pill.a(modifier, this.g)` (`iget-boolean p3, p0, psn0.g`). The caller's modifier register `p4` is not overwritten (Compose keeps it for recomposition), and `.locals` is unchanged (raising it would push `p4` past the 4-bit invoke limit). Output `classes2` has exactly 65,536 refs.
- `theme/dex/src/spatch/Pill.java` (helper DEX): every icon gets `padding(0, 3dp)` and `padding(20dp, 4dp)` so selected and unselected tabs keep the same height (labels aligned). Only the selected one gets `background(accent @ 25%, 50%-rounded shape)` between them, a 64×32dp pill. Calls resolved by tracing: `mtg.n` = background (wraps `BackgroundElement(color, null, 1f, shape)`), `cxn1.D` = padding (`PaddingElement(h, v, h, v)`), `xp31.a` = 50% rounded shape, `n191.r` = size. Compose colour = ARGB << 32. Compile-only stubs for these obfuscated classes are in `theme/dex/stubs/p/`.
- Incident (fixed): the first device build shipped the `psn0` call without `Pill` in the helper DEX (a script edit to the helper list silently did not match) → `NoClassDefFoundError: spatch.Pill` crash on Home. Fixed, and a new test (`every_helper_class_called_by_an_edit_is_built`) fails the build if any edit calls a helper class that is not compiled.
- Device (`--theme gruvbox`): the pill shows behind the selected tab and moves with selection on Home/Search/Library (pill-fill pixel sample `(108,73,46)` on the selected tab vs `(59,55,53)` panel elsewhere). Labels aligned; the mini-player sits above the slightly taller nav without overlap. Crash buffer clean after the fix; 0 verify/link/class errors.

## Lottie animation colours (built, signed, runtime-tested)

- The green "saved" check (and other green animations) come from Lottie JSON in `res/raw/`, with colours as `[r,g,b,a]` 0–1 floats. 21 base animations contain Spotify green `#1ED760` and/or `#1DB954`: `save_now_*`, `heart_*`, `play_indicator_*`, `download_status_*` (incl. `downloading_infinite`, which has only `#1DB954`), `bell_notification_*`, `auto_invite_*` and `stopwatch_*`.
- `src/lottie.rs`: reads each pinned original entry from base, rewrites every array whose RGB is within 0.004 of either green to the theme accent (alpha kept), and replaces the entry with its original compression. Each pinned file must contain at least one match, or the build fails.
- Verified: no `res/raw/*.json` in the output contains either green. Device (gruvbox): the "saved" check in the mini-player and the full player is orange; no crash entries; 0 verify/class/Lottie errors.
- Not changed: green animations inside the density/language splits (none checked).

## Concurrent work (recorded 2026-10-28)

- Between ~15:40 and 17:30 a parallel change added `Immersive.java` (double-tap-artwork fullscreen player), `PlayerArt.java` (circular player art + per-frame decoration of the player), `Atmosphere.java`, `Transport.java` (press highlight behind transport buttons), `TapBounce.java` (scale bounce on pressed player buttons), changes to `Scallop.java`, `src/dex.rs` (helpers, stubs, a `kvx` edit) and many stubs, and installed builds signed with the same device test key. The user chose to have me take over; I continued from those files rather than reverting them.

## Fixes after user review (built, signed, runtime-tested)

- **Wave** (`Wave.java`, `seekbar_progress.xml` now a single `spatch.Wave` layer): the remaining track is drawn only after the played part (no straight line under the squiggle). Dragging (SeekBar `state_pressed`) makes the played line flat and thicker. Only small forward level steps (≤400/10000) count as playback, so seeking no longer triggers a wave. Eases are critically damped (no overshoot).
  Device: playing shows the wave with no line underneath; mid-drag (3 s swipe) shows a flat band 17 px thick (vs ~11 px); the drag seeked to 1:29.
- **Previous/next** (`Transport.java`, `TapBounce.java`): no scale bounce. While pressed the icon and its circular highlight move sideways together in the skip direction; on release they swing back with a damped sideways bounce, then the highlight fades. Shuffle/repeat are unchanged. Device: screen recording at 30 fps confirms a sideways shift of icon + circle and no scaling.
- **Immersive player** (`Immersive.java`):
  - Icons are Spotify's Encore icons (`p.w2b1`, which renders Spotify's `spoticon` font) instead of hand-drawn paths: chevron-down (close), shuffle, skip back, play/pause, skip forward, repeat, volume. Obfuscated `p.z2b1` constants come from the enum's name strings (e.g. PLAY=`L4`, PAUSE=`E4`, SKIP_FORWARD=`h6`, SKIP_BACK=`e6`, SHUFFLE=`a6`, REPEAT=`Q5`, VOLUME=`y7`, CHEVRON_DOWN=`J1`).
  - Removed controls that were drawn but did nothing: the "fullscreen" square, four top-right circles and the bottom-right pill.
  - Seeking was broken (`SeekBar.setProgress` is programmatic, so Spotify ignored it). It now replays the touch on Spotify's real SeekBar (`MotionEvent` down/move/up/cancel). Device: tap at 80% → position 95,880 ms of a 2:00 track; Next tap changed the track.
  - Still decorative: the bottom spectrum visualiser is simulated from time and play state, not from audio.

## Press animations (built, signed, runtime-tested)

Provenance: the early timings were measured from a user-supplied screen recording and a local design-reference checkout kept out of this repo (no licence, so values only; no code copied).
- **Previous/next** (`Transport.drawSkip`): the indicator is a light, soft rounded triangle pointing the skip way (the immersive view uses a dark one). It fades in over 140 ms (out-cubic) and grows from 0.35× on `cubic-bezier(.38,1.21,.22,1)` (350 ms), at about the button size + 4dp each side. On release the icon and indicator nudge 4dp (button width / 12) toward the skip direction over 90 ms (out-cubic), then return over 350 ms on `cubic-bezier(.42,1.67,.21,.90)`. The button never scales. The release is caught in `onStateChange` (it usually arrives before `draw`). Device: frames show the triangle growing in and Next nudging right, then settling.
- **Shuffle/repeat** (`TapBounce`): only these bounce, scaling up to 1.15× over 110 ms (out-cubic), then back to 1 over 400 ms on `cubic-bezier(.38,1.21,.22,1)`, timed from `Transport.releasedAt()` so sub-frame taps count. Every other player button, including the seekbar, now stays at 1× (the old shrink-to-0.9 bounce is gone). `PlayerArt` also treats "Choose a Listening Mode" as the shuffle button. **Not device-verified:** in the current context (a recommendations station) shuffle and repeat are disabled.
- **Seekbar hold** (`Wave`): Spotify stops position updates while the bar is touched (pressed state only starts once you drag), so playback is also read from `AudioManager.isMusicActive()` (polled at most every 500 ms). Device: holding while playing keeps the wave (29 px spread); holding while paused is flat (0). No scaling of the seekbar.

## Skip icon nudge + blurred backdrop fixes (built, signed, runtime-tested)

- **Skip icon didn't move with its indicator.** Spotify sets the previous/next icon as the `ImageButton`'s image (`setImageDrawable`), not its background, so translating the `Transport` canvas moved only the indicator. The nudge now sets the button view's `translationX`. Device (60 fps recording): the Previous icon centre goes 338.5 → 328.5 px (≈4dp left), overshoots to 339.5 and settles at 338.5.
- **Blurred backdrop used the wrong track's artwork.** The artwork carousel keeps the previous/next tracks' pages alive off-screen, and every page's `PlayerArt` set the backdrop (`overlay_controls_layout`) from its own art, so a neighbour's art could win. Only the page centred on screen (`getLocationOnScreen`, within a quarter of its width of the screen centre) may set it now. It is re-applied every frame against a cached view, so the blur follows a skip immediately. Device: on red artwork the backdrop tint is (+18, −3, −16) vs artwork tint (+91, −31, −60), and it changes with each track. **User-verified correct after next and previous.**

## Other screens (pending)








Menus, sheets, dialogs and settings are not inspected.

## Immersive takeover correction (2026-10-28; built/signed, device check pending)

`Immersive.onDraw` must snapshot the four coordinates of `Drawable.getBounds()`;
the returned Rect belongs to the drawable and is mutated by `setBounds`. Retaining
that Rect then restoring it did not restore the original image bounds. The overlay
also follows `PlayerArt.currentPage(root)` when its original carousel page leaves
centre. Reduced motion uses 250ms state refresh with no spring/pop animation,
rather than stopping playback updates. Immersive now honours `Settings.backdrop`
and forwards its seek gesture state to `Wave`. Behavior regression checks are in
`tests/player-runtime.py`. Output: `dist/immersive-finished`. Installation and visual
checks remain pending because ADB listener creation is denied in the build environment.

### Artwork tap and immersive-return correction

`PlayerArt` now accepts touch sequences, invalidates on touch, gives sub-frame taps
a visible release compression and releases the source press before opening immersive.
`PlayerArt.drawArtwork` maps the artwork's existing bounds through the canvas,
without resizing the shared Drawable. It is used by `Immersive`, `Atmosphere.capture`
and `PlayerArt` crossfades to avoid leaving the normal ImageView with displaced or
clipped art. Regression checks: `python3 tests/player-runtime.py` (nine checks).
Latest signed set: `dist/artwork-bounce-return-fix`. Android runtime check pending.

### Smooth return handoff

The flight now renders through `PlayerArt.drawHeroImage` (native ImageView crop),
interpolating the circular crop fraction from 1 to .88 instead of shrinking the
full bitmap into the normal circular clip. The source artwork alone stays hidden
during the flight. Close snapshots the displayed geometry and crop/fade progress,
lands on the native player geometry and posts removal after drawing that endpoint.
Regression groups cover exact landing transforms and Back during opening. Signed
output target: `dist/immersive-smooth-return`; device visual verification pending.

## Real immersive visualizer and action feedback

- Original manifest already declares RECORD_AUDIO and MODIFY_AUDIO_SETTINGS; unchanged.
- New guarded targets: classes2 `com.spotify.playbacknative.AudioEffectsListener`
  `onAudioTrackCreated(AudioTrackAdapter)`, `onAudioTrackDestroyed(AudioTrackAdapter)`
  and `onPaused(boolean,int)`. The first two expose `getAudioSessionId`; onPaused
  feeds the same ID to the original equalizer/session logic. Confirmed in smali.
- The full listener class is assembled separately and merged with the helpers into
  classes16 to avoid classes2's exhausted method table. Both original and helper
  DEX definitions and reference counts are checked before publication.
- `AudioSpectrum` polls 1024-point native FFT at most ~30Hz from that session only,
  maps signed real/imaginary components into 42 log-frequency bands, and releases
  on inactivity. No microphone AudioRecord, output-mix capture or synthetic fallback.
  Reference: https://developer.android.com/reference/android/media/audiofx/Visualizer
- `ImmersiveButton` mirrors actual View/ancestor enabled state, selected state and
  actions with Transport/Scallop feedback, hover handling and cancelled-tap guards.
- Shared `PlayerArt.drawHeroDecoration` fades actual glow/ring paths with matching
  phase and final geometry. `tests/player-runtime.py` checks intermediate/final alpha.
- Runtime audio/rendering validation remains pending; latest target is
  `dist/immersive-real-audio`.


## Slider thumbs, selectable themes and spectrum shaping

Latest signed output: `dist/immersive-polish-final`.

- Volume uses a 4dp by 20dp line thumb, narrowing to 2dp during dragging.
  Progress uses a 4dp by 18dp line that fades in on hover/drag and fades out at rest.
- Customize includes all seven palettes. Selection persists; force-stop and reopen
  Spotify to apply it. Resource-loader switching requires Android 11 or newer.
  Packaged overlays include matching native layouts and animated icons.
- Spectrum integrates signed FFT power over 24 weighted logarithmic frequency bands,
  avoiding repeated low-frequency bins. It displays 48 mirrored bars with bass at
  the center, narrower spacing and theme accent. Pink-noise balance, tones and silence
  are covered by behavioral checks. Music can naturally remain bass-heavy; no fake
  signal is substituted when capture is unavailable.
- Palette loads inside the existing final attachBaseContext method after SplitCompat.
  The original p.y2p1 utility moves intact into the helper DEX to avoid the full
  classes4 method table. Original application manifest remains byte-identical.

Validation from the completed build: 27 Rust tests including real-input dry-run;
18 JVM behavior groups; format/clippy/release checks; signed/align-verified APKs;
SDK resource parser accepts all seven overlays (37 entries each), with matching
thumb and animation colors. JVM behavior checks and base signature were rerun after
reconnection and pass. Live phone rendering remains unverified: ADB was previously
blocked in the build environment.

Install from the project directory:
`adb -s 65280DLKX00265 install-multiple -r --no-incremental dist/immersive-polish-final/base.apk dist/immersive-polish-final/split_config.arm64_v8a.apk dist/immersive-polish-final/split_config.en.apk dist/immersive-polish-final/split_config.xxxhdpi.apk`


## Theme selection and restart fix

Output: `dist/theme-restart-fix` (gruvbox default, existing signing key).
Fixed unresolved `@THEME@` in generated Palette source: the previous Gruvbox build
incorrectly treated Lavender as its default and could skip its resource overlay.
Regression checks now require the generated default to equal the CLI theme and
reject the unresolved token. Theme selection uses synchronous preference commit;
a failed save is reported rather than claiming success. Pending changes expose
Restart now and Later, with 48dp minimum targets. Later preserves the selection;
Restart now launches Android's launcher restart task and exits the old process so
startup reloads native/Compose colors. No manifest or permissions changes.
Checks: 26 Rust tests pass (one real-input dry-run ignored in this run), 18 JVM
behavior groups pass including pending/active restart visibility and Later saving;
format/clippy/release pass. Signed pipeline completes and verifies all five APKs.
Theme application and automatic relaunch still require live phone verification.

## Player personalization and navigation revision (R1-R8, 2026-10-30, built, signed, device-tested)

**Spectrum.** `Spectrum.java` owns one model for every renderer (bands 0..1.12, `step` once per frame); `AudioSpectrum.SHARED` owns the one `Visualizer`, with consumer bits `BOTTOM` and `RADIAL`. Startup dome cause: ranges started at zero and took seconds to learn. They are now seeded from the first frame with mean >= `VALID` (0.12) using `PRIOR`, a per-band table measured from settled music on the device. A new audio session resets; silence never seeds or moves ranges.

**Choices.** `Settings.CHOICE_KEYS` hold stable strings; an unknown stored value falls back to the first (today's look). `viz_shape`, `viz_taper`, `art_outline`, `art_glow`, `art_spectrum`. In immersive the art is a rounded square, so the radial follows that contour (exponent 8) rather than drawing a circle over it.

**Content (`Content.java`).** Presentation only, nothing deleted.
- Home: `p.xmc0` constructor (`classes2`, relocated to the helper DEX) receives `q6k1` pairs of model and `spotify:section:<id>|<slug>`. The section URI is identity and the title is a label. Models: `cu70`, `oz71`, `bfi0` filter item by item; others (`udi0`, `dp01`, `zw80`) drop only if every content URI is blocked. The `casita` HomeStructure and `dgm0` paths never fired on the test account. `spatch.HomeReview` steps through these sections on Home: position = index in `Content.onHome` + `r411.e()` (getItemCount) - section count; `LinearLayoutManager.w1` is scrollToPositionWithOffset (phone verification pending).
- Home chips: `p.sdy` (`classes7`) maps feeds via `aj00.a`; live ids `client-native:default|music|podcasts|audiobooks`. Fetched once at startup, so a toggle offers a restart, which also returns to All.
- Search: `p.s8e` constructor (`classes5`), `s6e.a` page URIs `spotify:page:0JQ5DArNBzkmxXHCqFLx2J` (Podcasts), `spotify:page:0JQ5DAqbMKFETqK4t8f1n3` (Audiobooks).
- Library: items `zzo1.D` (`classes12`) by `dsm1.e` (`ndy.b` SHOW, `Y` NEW_EPISODES, `a` BOOK, `g` AUTHOR); chips `oxo1.q` (`classes4`), classes `e5c0`/`u4c0` (podcasts, downloaded) and `l4c0`/`r4c0` (books, downloaded).
- Messages: drawer block is `p.upf` in `Settings.rows`.

**Bottom navigation.** Tab: `k1u.c` (`classes12`) wraps `k1u.d`, which calls `psn0.a` (icon) and `psn0.b` (label). `psn0` is relocated to the helper DEX because `classes2` has no method refs left.
- Floating, icon-only bar (look and timing from the PixelMusic screenshots and values only; that repo is proprietary, no code copied): `vertical_main.xml` gives `navigation_bar` 16dp side and 10dp bottom margins, radius 32dp. `k1u.c` always takes its compact branch (the `screenHeightDp < 520` test is removed), which drops the label; the tab's description stays for TalkBack.
- `Pill`: a `hqr0` snapshot state per tab position (`zpo.q` is `mutableStateOf`), driven by a 320 ms overshooting `ValueAnimator`, so only that tab recomposes and only while it moves. Reduced motion sets it directly. Indicator grows from the centre: outer plus inner horizontal padding is always 20dp. The icon scales to 1.1 through `z341.q` (`graphicsLayer`, mask `1048572` keeps every parameter but scale at its default).
- Press: the Encore default indication `kch(slo1.l, usr.f)` contains `if41`, a 0.9 scale of the whole tab. `k1u.c` now passes `usr.b` (`tsr`, a dim overlay inside the tab) by setting `amo1.c`'s mask `0x7b8` to `0x6b8`.

**Settings page.** `SpatchSettings` plus `M3Switch`, `SettingsHero`, `ThemeSwatch`. Colours go through `Palette.color`/`Palette.tone`, icons through `encore_icon_<name>_24` by name. `SettingsHero` stops its frame loop once scrolled away. Section list key moved to `home_seen2` to drop the entries collected before podcast feeds were excluded (`Content.showsOnly`); rows are one per title, so a repeated title hides all its ids.


## Compose owners found by runtime trace (2026-10-30, device-confirmed)

A temporary stack log in `p.yw9` (background), `p.dto1.e` (text) and `p.eqo1.f` (image) mapped these; the trace is not shipped.

- Search: field `p.bqn1.e`; category tile `p.o6e` (colour field `c`, tilt 25 degrees, built from `hcx`/`gcx`); page title `p.rro1.g`.
- Home: shortcut `p.ry71.a`/`p.qy71`; chips `p.epr0.b`; shelf heading `p.dfm` ("NavigableHeadingTitle") with `p.hon1.g` Show all; skeleton `p.cp01`.
- Library: list rows `p.r0a1.j/r` with `p.vrh1.f` subtitle; grid `p.kdy.a/b`; sort row `p.ik41`; surface `p.i2d1.c`.
- Below player: lyrics `p.r030.f`/`p.yo10.i`; cards `p.c1p1.r`, `p.rro1.e`, `p.kyo1.e`; SongDNA `p.c6a1`.
- Save: `AddToButtonView.a(kk1)`, tap flag `e`, `lk1.a` = not added. Spotify's own Lottie is the plus-to-check.

Mini-player: `spatch.MiniBar` subclasses MotionLayout in `now_playing_bar.xml` (classes9 stays full); `cover_image` is replaced at runtime, so `MiniArt` no longer renders there. MotionLayout's onAttachedToWindow, onMeasure, onLayout, dispatchDraw and touch methods are final. `activity_main` children need ids because ConstraintSet clones them.

## Nav bar drag (runtime-tested on Pixel 11 Pro)

`spatch.NavIndicator` sits above `navigation_bar` with the same bounds, so its local coordinates match. It forwards touches to the bar until a sideways move passes the touch slop, cancels the forwarded press, then follows the finger. Release sends a synthetic DOWN and UP to the Compose bar at the target tab's centre, so Spotify's own tab handling runs. If selection doesn't land (Create opens a sheet) the pill returns after 700ms. TalkBack does not go through this path.

Create tab opening: `p.jub.V0` (classes5) draws a close disc and rotates, shrinks and tints the icon. Edits keep the `+` at rest and turn the disc into a tonal pill (`Pill.closePill`, `closeColor`, `closeDiscTop`), neutralise scale and tint, and pass the angle to `Pill.a`, which rotates just the icon (`fwq.t`); the outer rotation pivoted on Pill's asymmetric padding. `closeRest` clears the angle when `jub` stops drawing. Assumes Create is the last tab. Landscape rail offset is not device-tested.

Tab press feedback: `k1u.c` (classes12) gets a null indication, so Compose draws no press overlay. `NavIndicator` draws a tonal pill on the held tab and brightens the selected pill. The landscape rail has none.

## Typography (traced; phone verification pending)

- `res/font`: `spoticon` (0x7f090001, never replaced), `spotify_mix` (0x7f090002, variable, API 29+), mono (0x7f090003), then `spotify_mix_ui_*` and script fallbacks up to 0x7f09001b.
- View: `EncoreTextView`, `EncoreButton`, `StateListAnimatorButton`, `SuppressLayoutTextView` and `p.vgx` go through `p.p930.a/b` to `p.n74.K`. AppCompat and direct users (`p.d96`, `p.nsg`, `p.tf10`, `p.mix`, `p.wqa1`, VideoSurfaceView) go through `p.jw21.b/c` (ResourcesCompat).
- Compose: `p.sqt.c` to `p.kix.a` (static cache by resource) to `n74.K`; `p.tzb.a/b` make `p.rv21` resource fonts loaded by `p.o641` through `p.io71.u/v` to `jw21`; `p.cxo1.t` to `p.b0h1.a` applies variation axes; `p.h4b1.a` is the 'wdth' 150 display face.
- Edits: Fonts.resource at `jw21.c` and `n74.K` and `h4b1.a`; `tzb.a` picks a role by weight; `b0h1.a` skips Spatch faces so Spotify's width axis cannot widen monospace. `jw21`, `tzb`, `my50` relocated to the helper DEX (classes.dex and classes2 are full).
- Unsupported by design: Android text using `sans-serif*` families from Material styles, `spoticon`, Rive text, WebViews, notifications and the home-screen widget (RemoteViews cannot take a custom Typeface), and `ac01`, the audiobook reader's own font menu.

## Home filter row (traced; phone verification pending)

`p.my50` inflates `layout/funkis_filter_chips_header`: profile image plus ComposeView `filter_chips_container` running `p.kpo1.e` (test tag HomeFilterRow, LazyRow) with `p.go60.a/b/c` chips. Model `nn60(list of in60, mn60)`; `ln60.a/b` are the selected parent and child. Chip taps become `dn60/en60/fn60/gn60`, and `qe50` maps every one to `ey50(id)` on `my50.a`. `spatch.FilterRow` is added above the ComposeView from `my50.b`, which goes INVISIBLE only after a successful bind. `oj20.k` drops a parent tapped while its child is selected, so the reducer falls back to the first chip (All); FilterRow sends the child's id instead, which keeps the parent selected. Not reproduced: promotional `in60.j` highlight, the Wrapped chip's Rive treatment and tooltip anchoring.

## Side drawer (traced; phone verification pending)

`p.hf81` creates ComposeView `gf81`; `p.uf81` lays out rows through element factories. Link rows render in `p.x571` (group 0x1c2f65b9, `sqo1.m` list item); background comes from `ag81` with `sidedrawer_background`. Rows gain `Surfaces.drawerRow`; `Settings.attach` paints the drawer view with the theme surface.

## Seek haptics

`Wave` is the player seekbar's progress drawable. Detents come from `onLevelChange` while pressed. `Wave` installs itself as the host's touch listener at first press (nothing else sets one; the listener returns false) and confirms on ACTION_UP with a changed level.

## Side menu rows (traced; phone verification pending)

`iiq` builds the provider list in this order: `yh81`, `upf` Messages, `vse1` Listening stats or Your Sound Capsule, `wwm1` Your plan or Your Premium, `ft01(0)` Recents, `ae2` conditional custom row (not identified), `ft01(1)` Settings and privacy, `ptd1` Taste Profile, `nbd0(1)` Your Updates, `ft01(2)` Company Information (KR), `f7`, `nbd0(0)` DM/Following/Group chat list. Types and Spatch's mapping are in `Settings.hidden`.


## Overnight redesign (2026-10-31)

- **Playlist play button:** `p.f1p1.j` (classes4) owns the click and state; `Flourish.pressed` wraps the click for the burst, so playback that starts any other way never bursts. `p.f1p1.m` draws it. `ScallopShape.live(state)` takes Spotify's own state (1 = pause glyph shown) and is read during composition; a tick continues only while something composes it, so it stops offscreen and in the background.
- **Profile (Compose, not `y5x0`/`jis`):** `p.r7j` -> `p.bn31.i` -> `p.bn31.h`. `h` paints `l6x0.k` plus a vertical gradient; `j` paints the top bar. `l6x0.m` is false on a `PREVIEW_CURRENT_USER` profile, so own-profile detection uses the `e6x0` Edit action in `l6x0.f`. `ProfileBanner` extends `tu61` (ShaderBrush).
- **Held rows:** `tbx.e` in `p.ggx`'s base set was opaque black; `Surfaces.pressed` replaces that slot.
- **Back:** `Back` unregisters when it fires, so `SpatchSettings.close` registers again after popping a sub-page.
- **Gruvbox Material Dark:** palette from github.com/sainnhe/gruvbox-material (MIT, Copyright (c) 2020 sainnhe), dark medium background. Internal name `gruvbox-material-dark`; `theme_assets::PATHS` is sized by `THEMES.len()`, so a missing overlay path is a compile error.

- **Filter chips:** `FilterRow.resize` changes chip width with the pill; removing a chip at once clipped the pill at the shrunken row's edge.
- **Your Library rows:** `p.r0a1.d`. Selected toggle chips: `p.n0k0.c`.
- **Playlist track rows:** `p.eeb1` through `p.kro1.d` (a list-item variant that does not pass through `sqo1.m`). Edit-playlist rows are `p.kt31`, also through `kro1.d`, and are left alone.
