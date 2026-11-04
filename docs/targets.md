# Patch Targets & Reverse Engineering Notes — Spotify for Android

This document records the reverse-engineering analysis, component ownership, DEX/resource constraints, and patch targets for modifying Spotify for Android using [Spatch](../README.md).

---

## 1. Target Versions & Package Inventory

Spatch targets exact builds of Spotify for Android. Because Spotify aggressively obfuscates its code with R8/ProGuard on every release, patch targets are pinned with SHA-256 hashes and strict structural preconditions.

| Parameter | Primary Supported Build | Baseline / Porting Origin |
| --- | --- | --- |
| **Version Name** | `9.2.0.26` | `9.1.98.521` |
| **Version Code** | `148373686` | `148115007` |
| **Package** | `com.spotify.music` | `com.spotify.music` |
| **Target SDK / Compile SDK** | 37 / 37 | 37 / 37 |
| **Required Splits** | `base__abi,base__density` | `base__abi,base__density` |
| **APKM SHA-256** | `186e251a3d4998b0a8182916c55be2ade2d7b9444efc6e8e8cc79bde08ed1ee2` | N/A |
| **base.apk SHA-256** | `cc165669d31851b1193cae7d54c9261579c431b432080970f460704d06e4612f` | `be430f822bf1505cb7ec73f65dee5f6c4c6cfd82b7a90654357fcbbfaf581d97` |
| **split_config.arm64_v8a.apk** | `d9360d4b9a29a46c752b61b8c6409c67c4aa78ca07ca9af1f768af950b0155bd` | `935c10fa...` |
| **split_config.en.apk** | `a6e91962543f23478c59d70a19bd00637b9ab03d4a321480cea50c90b1d281ff` | `c0c13f93...` |
| **split_config.xxhdpi.apk** | `371b587b87fde2cb5078d78fb3cd13a239a059149a22918372034e499944b800` | `2ea79493...` |
| **Original Signer** | `CN=Spotify, OU=Android, O=Spotify, L=Stockholm` (cert `6505b181…8ffd`) | Same |

> [!NOTE]
> Re-signed APK outputs cannot update a stock Spotify installation because Android's package manager enforces matching signing certificates. All splits (`base` + ABI split + density split + language split) must be installed together via `adb install-multiple -r` or [Spatch Manager](../manager/README.md).

---

## 2. Core Reverse Engineering Architecture

Spotify's Android client is a hybrid application comprising:
1. **Classical Android Views** (`ViewGroup`, `MotionLayout`, `RecyclerView`, `CoordinatorLayout`).
2. **Jetpack Compose & Encore** (Spotify's proprietary design system, utilizing Compose runtime, custom `Modifier` chains, slots, and design tokens).
3. **Native C++ Audio Core (`liborbit-jni`)** driving playback through `AudioTrackAdapter`.
4. **Lottie Vector Animations** for reactive UI icons.

Modifying this binary without source code requires navigating three critical constraints:

### 2.1 Split APK Reference Preservation (The `@null` Trap)
- **Problem:** Running a conventional `apktool d base.apk` and `apktool b` breaks resource references. Spotify places specific density resources (such as `0x7f0707c4 dimen/track_info_subtitle_text_size` at 16sp) exclusively in the density split APK (`config.xxhdpi` / `config.xxxhdpi`). When `base.apk` is disassembled in isolation, `apktool` cannot resolve these IDs and emits `@null` for all 288 split-only references in decoded layouts (e.g. `track_info_nowplaying_view.xml`).
- **Solution:** Spatch does **not** perform a full APK round-trip. Instead, it disassembles the base layout in a staging environment, verifies that split references remain intact using pinned split reference declarations (`SPLIT_REFS`), compiles only the target modified XML entries against the build's resource table, and surgically injects the compiled binary XML into the original, byte-identical `base.apk`.

### 2.2 The 64K Multi-DEX Method Reference Limit & Companion DEX (`classes16.dex`)
- **Problem:** Dalvik/ART enforces a hard limit of 65,536 method and field references per DEX file. Several of Spotify's DEX files are completely saturated:
  - `classes9.dex` (which holds player controls and seekbar logic) has **exactly 65,536 method references** (`dexdump -f`). Adding even one method reference causes build/verifier failure.
  - `classes2.dex`, `classes.dex`, `classes4.dex`, and `classes7.dex` are also at or within 1–2 references of the limit.
- **Solution:**
  1. **Strict Reference Discipline:** Edits in `classes9.dex` (e.g. `S_PlayPauseIcon`, `S_SeekbarTint`) use only **field references** and **constant swaps**, never adding new method references.
  2. **Companion DEX (`classes16.dex`):** Spatch introduces a new DEX slot (`classes16.dex`, the next available slot after Spotify's 15 DEX files). Helper logic (`spatch.*` package: `PlayerArt`, `Atmosphere`, `Wave`, `Transport`, `Pill`, `MiniBar`, `Scallop`, `AudioSpectrum`, etc.) compiles into `classes16.dex`.
  3. **Class Relocation:** When an existing Spotify class requires edits that exceed its host DEX's capacity, the entire class is extracted from its original DEX and relocated into `classes16.dex`. Dalvik/ART resolves classes across the multi-DEX class loader without requiring identical DEX index placement. 13 classes are relocated:
     - `S_MixFontLoaderD`, `S_MixFontLoaderG`, `S_HomeFilterRow`
     - `S_PageBackground`, `S_ComposeShapes`
     - `S_EncorePressable`, `S_EncoreTint`
     - `com.spotify.playbacknative.AudioEffectsListener`
     - `S_GlanceRemoteViews`, `S_MediaSessionSite`
     - `S_HomeFeedModel`, `S_NavBarTabState`, `S_ToggleChip`

### 2.3 Semantic Alias System (`S_*`) & Porting Architecture
To prevent reverse engineering notes and patch code from degrading into unmaintainable obfuscated strings (`p.lot0`, `p.ews`, `p.kvx`), Spatch defines a stable semantic alias model:
- Obfuscated classes are aliased as `S_<Alias>` (e.g., `S_PlayPauseIcon`, `S_SeekbarTint`, `S_NavBarTab`).
- Resource references are aliased as `R_<type>_<name>` (e.g., `R_id_play_pause_button`, `R_font_spotify_mix`).
- [aliases.tsv](file:///Users/levi.napz/Projects/spatch/scripts/port/aliases.tsv) maintains the cross-version mapping between `9.1.98.521` and `9.2.0.26`.
- [`src/names.rs`](file:///Users/levi.napz/Projects/spatch/src/names.rs) rewrites all smali templates, stubs, and hooks prior to compilation and patching.

---

## 3. Patched Entries & DEX Targets

### 3.1 Binary XML Injections (20 Entries in `TARGETS`)
Every injected entry replaces a compiled XML file in `base.apk`. Line changes are stored as compact RCS diffs against the decoded original:

| Target Entry | Role & Ownership | Original SHA-256 (`9.2.0.26`) |
| --- | --- | --- |
| `res/drawable/floating_layout_bg.xml` | Mini-player outer surface outline | `f90e14c0385085195ea639bdb7f157f7c5c5b387b9ba1d886a8d29432712ce82` |
| `res/drawable/play_button_header_style_background.xml` | Album/show header play button background (`spatch.ScallopFill`) | `c2205ec1f2a06c3bb3f32e4274a6bd472b5ae3bc1275710bb19bd2cd1bd4d9b6` |
| `res/layout/square_cover_art_content.xml` | Player artwork container (`p.x7b1`/`p.y7b1` inflates); replaced with `spatch.PlayerArt` | `458b7efeee492a6da6b24950b7468a81abddbcd87472e4f618c3f30cb027f9be` |
| `res/layout/revised_template_page_layout.xml` | Full player page surface (`#14121C` / theme surface) | `e117f445f1c51e87dbaa3a8ce16ac25da49097d8f07ca7a00960c6209b74fa8a` |
| `res/layout/revised_template_overlay.xml` | Floating player footer & transport pill tray | `ef8a5a337e413850b71b4b0cc40588ef562b222158d5ffbe2b397dfaaae38277` |
| `res/layout/track_info_nowplaying_view.xml` | Now Playing title & artist typography (replaces split-only dimens) | `810522ba98b10a4bdda974b6596dd144cc4d9438463b2da4102d75fe7dd9fbde` |
| `res/drawable/nowplaying_overlay_gradient.xml` | Player background scrim (deep violet/theme gradient) | `441662e890cab6c5ea08b3b302b04a405614d67b262a3418250907721b843d55` |
| `res/drawable/seekbar_thumb_default.xml` | Player seekbar pill thumb | `77d89f8b63fed7bacef8bc2d709a854b2d185dab7f82f394b603dda66104fd75` |
| `res/drawable/seekbar_progress.xml` | Player progress drawable (`spatch.Wave` custom drawable clip) | `5b47d070e7982c168d18f157bf5d8621aa99d473a0309917a73929cede19cff9` |
| `res/layout/now_playing_bar.xml` | Mini-player MotionLayout (subclassed by `spatch.MiniBar`) | `30fe15ae6d232df25871d8118eb3ee05bd6e3b27a0faa168700dab6c40068237` |
| `res/xml/now_playing_bar_scene.xml` | Mini-player ConstraintSets (progress geometry, 48dp pitch) | `16bd1cb3f454f5104fdf3c3f811f2272733e774a0a0eb5e693f405073d80603c` |
| `res/layout/now_playing_bar_track_info_view.xml` | Mini-player track typography and subdued subtitle | `3005e9d451493def337cac2861bca9023d1c03d02693d44a305d90bb496d934a` |
| `res/drawable/library_compose_onboarding_card_default_background.xml` | Library add-row icon background (prevents bleed on held row) | `435338e090e72b314587cc1ae7ad718ea09d445ee6adec8bfa4e3659af0f042f` |
| `res/drawable/library_onboarding_card_circular_background.xml` | Library circular onboarding background | `0abe5c33408e437abe9d5f36c0808e069038efe408b150555173ec0258d9ee1c` |
| `res/anim/fade_in_hard.xml` | Page transition animation enhancement | `ff21800118ac677bf8ad53c60bb34a3786baadd6fdfdbb7ae2ac38b371ec966b` |
| `res/layout/vertical_main.xml` | Main phone frame (`navigation_bar` floating margins & corner radius) | `e905808d28a5081548a5417aaa73522273422970d2f782bbf7262b68819c2523` |
| `res/layout/image_link_card_layout.xml` | Home shelf card root (`artist_pick_comment_bg` card clip & tint) | `a2f0e0915852d33ebc5c22a5d66249f558e1585b0439f67a3adec0c0788628cb` |
| `res/layout/track_seekbar.xml` | Player seekbar container height allocation (12dp for wave) | `302c0d8dae5138258dc6e15002134135d8897e63655d0ed44753d6115e1233b6` |
| `res/drawable/launcher_screen.xml` | App window background behind transparent Compose screens | `80beb693d332799a6250beeda1401020a1af164bc1e36a17472547e43cfe8370` |
| `res/xml/dynamic_npv_widget_info.xml` | Widget configuration and previewLayout redirect | `775f05bb9b5d2b1bf386a3d906152a4e21a6ea62a637cfd36a9b40097f48b11b` |

### 3.2 DEX Targets (11 Reassembled DEX Files)
Only target DEX files are disassembled to smali, edited with exact anchor guards, and reassembled:

| DEX File | Primary Contents / Hooked Subsystems | Pinned SHA-256 (`9.2.0.26`) |
| --- | --- | --- |
| `classes.dex` | Compose runtime, `S_ComposeCornerSize` (`q3v`), `S_ArtworkShape` (`zvx`), `S_EncoreColorTokens` (`vgx`) | `0520d0e2b66ba60593b815e71902247327da8c5a6392334219cd77d1d438ea21` |
| `classes2.dex` | `S_NavBarTabState` (`otn0`), Home model pairs (`rnc0`), Flow collectors | `6f75b6523f9c11d4de3cefa3209d9d408c61e07caaa12d42155b3c9141b0b532` |
| `classes3.dex` | `S_PlayButtonShape` (`dcg` / `lbg`), static invoke redirect to `Scallop.draw()` | `28b77b1cc585e4d4853a662a48144e4941e7532265f21d8b2dc598325c290da3` |
| `classes4.dex` | `S_MixPlayButton` (`g3p1`), `S_SearchBar` (`ryn1`), Compose background wrappers | `6c97eeef14cdcb27979633bd4d1e929156b0426e0e631a15eaf9fb67ecc32e52` |
| `classes5.dex` | `S_CreateTab` (`fvb`), `S_SearchTiles` (`m9e`) | `d7fa0ae3238dd5a5d4c3755f7b63116c610c8e84be973871a8ef61cee05e95b5` |
| `classes7.dex` | `S_SaveButtonState` (`pk1`), `S_HomeChipFeed` (`dqu`), `S_LyricLineStyle` (`olf0`) | `f81bc0fa078bc2134b63e9043969060f42eb3044d29bc5d1d2d9c6f051d4f8b8` |
| `classes8.dex` | `S_PlaylistTrackRow` (`vfb1`), `S_PressOverlay` (`itr`), `S_YourPlanRowProvider` (`lym1`) | `7956386cce678c2d4c0b907ef04eb9ed6aa564edefd4054067f91b2e128c8d5a` |
| `classes9.dex` | `S_PlayPauseIcon` (`mpt0`), `S_SeekbarTint` (`qws`), `S_LyricLine` (`wlf0`), `MainLayout` spacer | `3cf0dd51390e5cfa4adc4389a90e921921656eef9178ad434d60239ff6b7cb90` |
| `classes10.dex` | `S_PlaylistHeader` (`w060`), Home list item layouts | `0b48ba31f2c4905e0541172be072874873babc6b7322fc14238a3a9ceaa577a1` |
| `classes11.dex` | `S_Drawer` (`mg81`), `S_DrawerLinkRows` (`c771`), `S_OwnProfileTopBar` (`nb51`) | `f994a60a684dd0e02567afa439a28e2717edaa372d749511c369e0431d3dac22` |
| `classes12.dex` | `S_NavBarTab` (`mp10`), `S_LibraryRow` (`sgc1`), Library chips (`z5c0`, `g5c0`) | `b51ab44ec17867799300aa46a0a46fe3eba6169ba7382a996bbb928d034ac526` |

---

## 4. Subsystem Reverse Engineering Details

### 4.1 Now Playing / Full Player Subsystem

#### View Hierarchy & Ownership
- **Host Activity:** `com.spotify.nowplaying.musicinstallation.NowPlayingActivity` sets content view to `res/layout/activity_now_playing.xml`, which hosts mode fragments.
- **Player Template:** `res/layout/revised_template_page_layout.xml` is inflated by `p.jcs` (`n9c0`/`p3p0` in `9.1.98.521`; ported in `9.2.0.26`). `jcs` is constructed in five branches of `p.dap` (the shared full-player template).
- **Overlay & Controls:** Included layout `revised_template_overlay.xml` holds `player_overlay_footer`. `p.zxd` measures its `getBottom()`, and `p.lzf0` references barrier constraints.
- **Track Info:** `p.u1o` inflates `layout/track_info_nowplaying_view.xml`.

#### Play/Pause Controls & Morphing (`Scallop`)
- **Control Construction:** `p.jcs` builds the transport row via `vsh.Q(playback_controls_container, ...)`. The centre view holder is `S_PlayPauseIcon` (`lot0` in 9.1, `mpt0` in 9.2, `classes9.dex`).
- **Compose vs View Flag:** Variant switch in `jcs` chooses `this.e1.d ? fot0 : eot0`. `eot0` (default) uses `AppCompatImageButton`; `fot0` renders Compose (`e1p1.e`).
- **Drawable Lifecycle:** `S_PlayPauseIcon` instantiates two separate instances of `S_PlayButtonShape` (`lbg` in 9.1, `dcg` in 9.2, `classes3.dex`):
  - Field `a`: Play icon (`z2b1.PLAY`), shown while paused.
  - Field `b`: Pause icon (`z2b1.PAUSE`), shown while playing.
  - State method `.b()` toggles visibility between them.
- **Shape Hook:** In `lbg`/`dcg.draw()`, Spotify originally executed `canvas.drawCircle(0, 0, r, this.d)`. Spatch injects field `spatchBumps:I` into `lbg`/`dcg`:
  - `lot0`/`mpt0` sets `iput 5` on field `a` (play/paused) and `iput 9` on field `b` (pause/playing).
  - `lbg`/`dcg.draw()` checks `spatchBumps > 0`: if true, it redirects via static invoke to `spatch.Scallop.draw(Canvas, float, Paint, int, Drawable)`.
  - A `WeakHashMap` keyed by the shared `ImageButton` joins the two drawables, interpolating periodic 5- and 9-lobe contours over a 440ms smoothstep curve with a 3.5% settle.

#### Transport Buttons & Gestures (`Transport`, `TapBounce`)
- **Press Surfaces:** `spatch.Transport` wraps shuffle, repeat, previous, and next. Idle controls retain their original vector icon. Press/hover displays a 4/6/3-lobe neutral white surface (25% pressed, 14% hovered) with a 350ms damped spring.
- **Directional Skip Nudge:** Previous/Next buttons (`ImageButton`) do not scale on press. Instead, `Transport` shifts the view's actual `translationX` by ±4dp over 90ms (out-cubic) and returns over 350ms on `cubic-bezier(.42, 1.67, .21, .90)`.
- **Shuffle/Repeat Scale Bounce:** Managed by `spatch.TapBounce`. Scales up to 1.15× over 110ms and recovers over 400ms.

#### Seekbar, Waveform & Detents (`Wave`, `SeekSplash`)
- **Inflator:** `S_SeekbarTint` (`ews` in 9.1, `qws` in 9.2, `classes9.dex`) inflates `layout/track_seekbar.xml`. It controls a `CancellableSeekBar` styled with `NowPlayingSeekBar`.
- **Custom Progress Drawable:** `seekbar_progress.xml` progress layer is replaced with custom drawable `<clip><drawable class="spatch.Wave"/></clip>`.
- **Playback Detection:** `Wave.java` checks forward progress delta (steps ≤ 400/10000). While dragging or paused, the line remains flat. Because Spotify halts position updates while touching the seekbar, playback status is supplemented by polling `AudioManager.isMusicActive()` (throttled to 500ms).
- **Haptics:** Seekbar touch listener intercepts `ACTION_DOWN`/`ACTION_MOVE`/`ACTION_UP`, providing tactile ticks on progress detents.
- **Seek Splash:** On seek release or vinyl scratch commit, `SeekSplash.java` emits droplet physics across the canvas.

#### Artwork, Halo & Vinyl Record (`PlayerArt`, `ArtContour`)
- **Layout Substitution:** `res/layout/square_cover_art_content.xml` substitutes `ImageView` with `spatch.PlayerArt`.
- **Visuals:** Clips cover art to circle/squircle, applies an accent halo, paints an ambient 24-wave ring, renders contour ribbon outlines (`ArtContour`), and tilts in 3D under touch.
- **Vinyl Scratch / Spin:** Holding the cover allows angular dragging like a vinyl record; releasing it snaps or resumes playback position.
- **Double-Tap Easter Egg:** `PlayerArt.dispatchTouchEvent` detects two `ACTION_DOWN` events within 300ms and 2× touch slop without intervening moves. It draws an inverse-surface pill over the art ("catchphrase pill"), fading in over 250ms, holding for 2.2s, and fading out.

#### Ambient Backdrop (`Atmosphere`)
- **Color Sampling:** Captures artwork into a downsampled 12×12 raster on drawable change. It applies bilinear enlargement and smoothstep color interpolation over 900ms.
- **Multi-Page Carousel Guard:** The artwork carousel keeps previous/next pages alive off-screen. `Atmosphere` samples only the page centered on screen (`getLocationOnScreen` within screen width / 4) to prevent off-screen track colors from bleeding into the backdrop.
- **Bass Shockwaves:** Detects low-frequency transient kicks in audio FFT and propagates fading radial shockwaves outward from behind the album art.

#### Inline Lyrics Preview Slot (`PlayerLyrics`, `S_LyricLine`)
- **Slot Collapsing:** `PlayerLyrics.java` locates `R_id_lyrics_element` in the full player view hierarchy. When the user selects "Hide player lyric preview", it sets the slot's layout height to 0, sets `clipChildren=true`, and marks `setImportantForAccessibility(NO_HIDE_DESCENDANTS)`. Spotify continues updating its internal lyric state cleanly.
- **Text Glow & Focus:** Smali edits in `S_LyricLine` (`wlf0`) scale inactive lines down to 0.83; edits in `S_LyricLineStyle` (`olf0`) clear mask bit `0x2000` to inject `Tint.lyricGlow` shadow into the active lyric line.

#### Immersive Full-Screen Player (`Immersive`)
- **Activation:** Triggered by double-tapping the player artwork.
- **Handoff Animation:** `PlayerArt.drawHeroImage` snapshots displayed coordinates, flying the circular art into full-screen bounds without mutating the shared `Drawable` bounds.
- **Control Delegation:** Renders Spotify's official Encore font icons (`p.w2b1` spoticon glyphs: `PLAY`, `PAUSE`, `SKIP_FORWARD`, `SKIP_BACK`, `SHUFFLE`, `REPEAT`, `VOLUME`, `CHEVRON_DOWN`).
- **Touch Replay:** Seeking in immersive replays synthesized `MotionEvent` sequences onto Spotify's hidden `SeekBar` instance to ensure Spotify's playback controller updates.

---

### 4.2 Mini-Player Subsystem

- **Host & Layout:** `res/layout/now_playing_bar.xml` (MotionLayout) is inflated by `p.m1p0` (9.1) / `p.u1p0` (9.2). Geometry is driven by `res/xml/now_playing_bar_scene.xml`.
- **Subclassing:** In `now_playing_bar.xml`, the root is changed to `spatch.MiniBar` (inheriting from `MotionLayout`). Because `MotionLayout.onMeasure` and `onLayout` are final, custom child manipulation occurs via layout hooks.
- **Play/Pause Icon:** `S_PlayPauseIcon.b()` is edited to swap the compact mode icons (`got0` / `hpt0`) to read from scalloped `lbg`/`dcg` fields `b`/`a`.
- **Corner Radius Override Interception:** Spotify's `setCornerRadius` dynamically forces the bar's corners to 73.5px and then 21px at runtime. `spatch.MiniSurface` intercepts and neutralizes this call, locking the pill to match the floating navigation bar.
- **Progress Ring:** `spatch.MiniRing` wraps the play/pause button in a 48dp circular progress ring, unlinking it from full-player seekbar styling.
- **Fling to Skip:** `MiniArt.Fling` listens to lateral touch flings across the mini-player cover art, spinning the art off-axis and dispatching a skip command.

---

### 4.3 Navigation & Floating Bar Subsystem

- **Container:** Phone portrait layout `res/layout/vertical_main.xml` hosts `navigation_bar`, a `MainLayoutContentContainerView`. Its constructor reads styleable `vmz0.c` (`contentBackground`, `contentCornerRadius`, `contentMarginBottom`, `contentMarginTop`).
- **Floating Pill Insets:** `vertical_main.xml` injects 16dp horizontal margins, 10dp bottom margin, and a 32dp corner radius.
- **Tab Layout & Label Elimination:** `NavBarTab.c` (`k1u.c` in 9.1, `mp10.c` in 9.2, `classes12.dex`) builds tab items. Spatch eliminates the `screenHeightDp < 520` condition, forcing the compact branch to drop tab labels while retaining accessibility descriptions for TalkBack.
- **Selected Tab Pill (`spatch.Pill`):**
  - Uses Compose snapshot state (`zpo.q` / `v8r.q`, `mutableStateOf`) driven by a 320ms overshooting `ValueAnimator`.
  - Injects a 64×32dp pill background (`mtg.n` / `dug.n`, wrapping `BackgroundElement`) with 25% accent fill.
  - Scales the selected icon to 1.1× via `z341.q` / `i541.q` (`graphicsLayer`).
- **Touch Forwarding & Drag (`spatch.NavIndicator`):**
  - Overlays `navigation_bar` with identical bounds.
  - Forwards touches to Compose until a lateral swipe exceeds touch slop, then tracks the finger. Releasing sends synthetic `ACTION_DOWN` and `ACTION_UP` events to the destination tab's center coordinate.
- **Create Tab Rotation:** `CreateTab.V0` (`jub.V0` in 9.1, `fvb.V0` in 9.2, `classes5.dex`) draws the `+` Create disc. Spatch replaces the disc with a tonal pill, neutralizes background tint scaling, and rotates only the inner vector icon.
- **Bottom Inset Nullification (`MainLayout`):**
  - Spotify's `MainLayout` uses `S_InsetSpacer` (`uyf0` / `vzf0`) to pad the bottom of scrollable pages so content clears the navigation bar.
  - While Spatch slides the bars away during scroll, a smali edit forces `getMeasuredHeight()` of the spacer to return `0`. This allows Library and playlist rows to scroll cleanly behind the floating bar rather than terminating in an empty grey strip.

---

### 4.4 Home, Shelves & Filter Chips Subsystem

- **Shelf Cards:** `p.a2s` inflates `res/layout/image_link_card_layout.xml`.
  - `ArtworkView` calculates its own corner radius (`max(4dp, 2.5% of size)`) and calls `setClipToOutline(false)`, preventing direct child clipping.
  - Spatch enforces clipping at the card root: `android:background="@drawable/artist_pick_comment_bg"` (20dp opaque fill) + `backgroundTint="#1D1A2A"` + `clipToOutline`. Opaque drawables are strictly required because `backgroundTint` uses `SRC_IN` blending and inherits transparent drawable alpha.
  - Press interactions trigger `spatch.CardTilt`, tilting the card in 3D perspective with a diagonal sheen.
- **Filter Header (`spatch.FilterRow`):**
  - `p.my50` (9.1) / `p.az50` (9.2) inflates `layout/funkis_filter_chips_header`, hosting Compose `LazyRow` filter chips.
  - The model `nn60` / `zn60` holds chip selections. Under Spotify's default reducer (`oj20.k`), tapping a parent chip while a child chip is selected resets to "All".
  - `FilterRow.bind` intercepts chip taps, dispatching the child ID to preserve parent selection context.
- **Content Redaction (`Content.java`):**
  - Intercepts `S_HomeFeedModel` constructor (`xmc0` in 9.1, `rnc0` in 9.2, relocated to `classes16.dex`).
  - Filters out podcast and audiobook sections (`spotify:section:*`, `spotify:page:0JQ5DArNBzkmxXHCqFLx2J`, `spotify:page:0JQ5DAqbMKFETqK4t8f1n3`).
  - Hides side drawer rows by provider type in `Settings.hidden` (`upf` Messages, `nbd0` Updates, `ft01(2)` Company info).

---

### 4.5 Search, Library, Side Drawer & Profile

- **Search Screen:** A full-page ComposeView. Category tiles (`o6e` in 9.1, `j7e` in 9.2) are tilted at 25° with dynamic colors.
- **Your Library:**
  - Row rendering: `S_LibraryRow` (`r0a1.d` in 9.1, `sgc1.d` in 9.2).
  - Selected filter toggle chips: `S_ToggleChip` (`n0k0.c` in 9.1, `i1k0.c` in 9.2).
  - Artwork corner radius: `S_ArtworkShape` (`kvx` in 9.1, `zvx` in 9.2) chooses corner radii based on dp dimensions. Pinned constant anchors are edited from 4dp/2dp to 16dp (for covers ≥33dp) and 8dp (for thumbnails).
- **Side Drawer:**
  - Root: `S_Drawer` (`hf81` in 9.1, `mg81` in 9.2) creates `ComposeView` `gf81`.
  - Link rows: `S_DrawerLinkRows` (`x571` in 9.1, `c771` in 9.2) injects `Surfaces.drawerRow` and `Surfaces.drawerItem` for tonal rounded surfaces.
- **Queue Bottom Sheet:** `com.spotify.nowplayingqueue.bottomsheet.view.layoutmanager.StickyHeaderLinearLayoutManager` reports its RecyclerView on layout. Spatch intercepts `t0()` to call `spatch.RowCards.queue()` for rounded card surfaces.
- **Profile Header:** `S_OwnProfileTopBar` (`bn31.h` in 9.1, `nb51.h` in 9.2) renders profile banners. Spatch detects user-owned profiles via `ProfileEditAction` (`e6x0` / `k7x0`) and applies `spatch.ProfileBanner` (subclassing `ShaderBrush` `tu61` / `zv61`).

---

### 4.6 Encore Design System, Theme Engine & ARSC Patching

#### Compose Palette Swaps (`S_EncoreColorTokens`)
Encore Compose colors are initialized in `<clinit>` of `S_EncoreColorTokens` (`ggx` in 9.1, `vgx` in 9.2, `classes.dex`). Seven `const-wide` values are replaced with theme tokens:
- `#121212` → `#14121C` (base background)
- `#1F1F1F` → `#1D1A2A` (elevated base)
- `#2A2A2A` → `#2B2640` (elevated highlight)
- `#B3B3B3` → `#BCB3DC` (subdued text)
- `#1ED760` → `#D0BCFF` (bright accent / positive green)
- `#3BE477` → `#E0D2FF` (accent highlight)
- `#1ABC54` → `#B7A3EE` (accent dark)

#### Binary `resources.arsc` Literal In-Place Patching
- **Mechanism:** View theme backgrounds (`?backgroundBase` → `@color/dark_base_background_base` → `@color/gray_7` `0x7f060741`) live in `resources.arsc`. Because changing chunk sizes or string pools risks alignment failure, Spatch performs in-place replacements of 8-byte `Res_value` structs (`size=8, res0=0, dataType, data`).
- **Occurrences:** Literal values are swapped across data types `0x1c` (ARGB8) and `0x1d` (RGB8) with pinned occurrence counts:
  - `#121212` × 12
  - `#1F1F1F` × 6
  - `#2A2A2A` × 6
  - `#B3B3B3` × 3
  - `#1ED760` × 28
  - `#1DB954` × 1
  - `#3BE477` × 4
- **Packaging:** `resources.arsc` must remain **stored** (uncompressed) and 4-byte aligned for `mmap` performance on Android.

#### Compose Corner Radii (`S_ComposeCornerSize`)
`S_ComposeCornerSize` (`c3v` in 9.1, `q3v` in 9.2, `classes.dex`) is Compose `CornerSize(dp)`. The constructor is edited to double all corner radii ≤ 12dp (4→8, 6→12, 8→16, 12→24), giving search tiles, dialogs, and chips smooth Material 3 curvatures.

#### Lottie JSON Colors
Vector animations in `res/raw/*.json` (e.g. `save_now_*`, `heart_*`, `play_indicator_*`, `download_status_*`) define colors as `[r, g, b, a]` floating point arrays. `src/lottie.rs` scans these JSON files and rewrites all RGB floats within 0.004 of Spotify green to the active theme accent.

---

### 4.7 Audio Capture & Real-Time Spectrum Engine

```
[Spotify C++ Audio Core]
       │
       ▼
liborbit-jni.so
       │
       ▼
p.AudioTrackAdapter  ──►  getAudioSessionId()
       │
       ▼
classes2 / classes16: com.spotify.playbacknative.AudioEffectsListener
       │
       ▼
spatch.AudioSpectrum.SHARED
       │
       ├──► android.media.audiofx.Visualizer(audioSessionId)
       │    (1024-point native FFT @ ~30Hz)
       │
       ├──► 24/42 Logarithmic Frequency Bands (PRIOR seeded)
       │
       ├──► Radial Contour Visualizer (PlayerArt)
       ├──► Full Spectrum Visualizer (Immersive)
       └──► Bass Shockwave Detector (Atmosphere)
```

- **Audio Session Hook:** Spotify's native engine (`classes2.dex`) defines `com.spotify.playbacknative.AudioEffectsListener`:
  - `onAudioTrackCreated(AudioTrackAdapter)`
  - `onAudioTrackDestroyed(AudioTrackAdapter)`
  - `onPaused(boolean, int)`
  Spatch relocates `AudioEffectsListener` into `classes16.dex`, extracting `AudioTrackAdapter.getAudioSessionId()`.
- **Zero Microphone Permissions:** Spatch requires no `RECORD_AUDIO` mic permissions or output-mix capture. It binds `android.media.audiofx.Visualizer` directly to Spotify's private audio session ID.
- **FFT Processing (`AudioSpectrum.java`, `Spectrum.java`):**
  - Polls 1024-point FFT at ~30Hz.
  - Maps real and imaginary components into 24 or 42 logarithmic frequency bands.
  - Avoids zero-start dome artifacts by seeding band ranges using `PRIOR` (a calibration table measured from settled device audio) once mean power exceeds 0.12. Silence never seeds or shifts calibration ranges.
  - Releases the visualizer immediately on pause or playback destruction.

---

### 4.8 Typography & Font Subsystem

- **Font Table:** Spotify includes:
  - `0x7f090001` `spoticon` (Encore vector icon font — **never replaced**).
  - `0x7f090002` `spotify_mix` (Variable font face).
  - `0x7f090003` `spotify_mix_ui_*` variants.
- **View Hierarchy Font Hooks:**
  - `EncoreTextView` and `EncoreButton` resolve fonts via `p.p930` → `p.n74.K` (`S_MixFontLoaderE`).
  - Direct AppCompat users call `p.jw21` (`S_MixFontLoaderD`, `ResourcesCompat`).
  - Spatch redirects font ID lookups to `spatch.Fonts.resource(id)`, injecting custom typefaces.
- **Compose Font Pipeline:**
  - Compose font resolution passes through `S_MixFontLoaderG` (`tzb`), `S_MixFontLoaderA` (`b0h1`), and `S_MixFontLoaderC` (`h4b1`).
  - Spatch hooks `Fonts.variable(weight)` into `tzb`, while `b0h1.a` skips width-axis compression for custom fonts.

---

## 5. Reverse Engineering & Porting Toolchain

Spatch maintains automated inspection scripts in [`scripts/port/`](file:///Users/levi.napz/Projects/spatch/scripts/port/):

### 5.1 Porting Workflow (`port.py`)
To port Spatch to a future Spotify release:
```bash
python3 scripts/port/port.py <old.apkm> <new.apkm> --work <work-dir>
```
1. Disassembles both APKMs with `apktool`.
2. Extracts and indexes class features: framework superclasses, interfaces, string constants, line count, and method/field count signatures (`hashlib.sha1`).
3. Computes Jaccard similarity across normalized instruction sequences (blanking out obfuscated `Lp/*;` names and mapping resource IDs).
4. Generates proposed mappings in `classes.tsv` and `resources.tsv`.

### 5.2 Stub & Member Verification (`check-members.py`)
Verifies that all stub fields, methods, and constructors match the decompiled Spotify build:
```bash
python3 scripts/port/check-members.py <decoded-dir> 9.2.0.26
```
Flags any method or field that moved, was renamed by R8, or changed parameter signatures.

### 5.3 Inheritance Checking (`hierarchy.py`)
Validates that stub `extends` and `implements` declarations reflect real class hierarchies:
```bash
python3 scripts/port/hierarchy.py <decoded-dir> 9.2.0.26
```

### 5.4 Semantic Smali Diffs (`diff-target.py`)
Generates unified diffs of target classes with obfuscated identifiers translated to `S_*` and `R_*`:
```bash
python3 scripts/port/diff-target.py S_PlayPauseIcon
```

---

## 6. Verification & Test Evidence

All reverse-engineered targets must pass four verification levels:

1. **Static Analysis & Guard Integrity:**
   - Every smali edit anchor in [`src/edits.rs`](file:///Users/levi.napz/Projects/spatch/src/edits.rs) must match **exactly once**. Duplicate or missing anchors abort the build.
   - Reassembled DEX reference sizes must not exceed 65,536 (`dexdump -f`).
2. **Unit & Pipeline Regression (`cargo test`):**
   - 51 automated Rust tests cover ARSC swapping, diff application, ZIP extraction boundaries, and alias resolution.
3. **JVM Runtime Behavior Checks (`python3 tests/player-runtime.py`):**
   - 44 test groups execute helper logic on JVM doubles (verifying gesture spring mathematics, FFT logarithmic binning, preset persistence, and accessibility flags).
4. **On-Device Instrumentation (`tests/device-settings.py`):**
   - Executed on a physical Google Pixel running Spotify with Spatch:
   - Validates UIAutomator nodes, logs crash buffers (`VerifyError`, `NoSuchMethodError`, `NoSuchFieldError`, `FATAL EXCEPTION`), verifies frame timings at 120Hz, and samples rendered screen pixels to confirm theme color application.
