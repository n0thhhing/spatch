# Suggestions

Ideas that were not built in the overnight pass of 2026-11-01. Nothing here is a finished feature
being held back: each item either needs a decision, needs something the patcher can't reach yet, or
hasn't been started. Findings from attempts are included so nobody repeats them.

## Tried and not working yet

**Cards on search results, album tracks and Spotify's settings rows.** Library and playlist rows have
cards through `r0a1.d` and `eeb1`. `kro1.d`, Encore's shared list row, was hooked with a guard against
double cards. It had no visible effect on these three screens, so they use another row and the hook
was removed. Spotify's settings is one full-screen `ComposeView`. `"track_row"` leads to `r030` and
`g5d1.e`, which is the next place to look. The sampler in Developer options shows which `p.*`
functions run during a scroll, which is the best way to find the real row.

**Spotify's white pills (Refresh, Log out, round play buttons on Home).** Turning white into the accent
in `Surfaces.bg`, including near-white, changed nothing, so these don't paint through `yw9`. The next
step is finding Encore's contained button.

**Liked Songs' blue header.** `b521` holds `0xff4100f5` and `0xff509bf5`, but that may be the Liked
Songs icon rather than the header. It needs a test on its own.

## Eye candy

- Lyrics: glow on the current line, gentle scale on the active line, album-coloured background.
- Queue: rows rise in, and a playing shimmer on the current row.
- Context menus: rows rising in turn, a tinted header from the cover.
- Connect sheet: animated rings around the current device.
- Home shelves: scroll parallax on large covers.
- Artist page: header image zooms slightly on pull, name fades into the top bar.
- Now Playing title: colour taken from the cover, per setting.
- Seek bar styles beyond the wave: beads, pulse, a line that follows the spectrum.
- Theme picker: a circular reveal from the tapped swatch.
- Animated wallpaper-style backdrop on Home, off by default.

## Fun interactions

- Immersive: swipe the artwork sideways to skip, drag up and down for volume.
- Mini player: fling the cover away to skip, with it spinning off the bar.
- Nav bar: long-press a tab for shortcuts, such as Recents or a pinned playlist.
- Search: pull down on the page to focus the search field.
- Library: pinch to switch between list and grid.
- Shake to shuffle, off by default.
- Swipe between tabs from the page content (rows that scroll sideways need an exclusion).
- Hold the play button to set a sleep timer by dragging around it.

## Settings

- Export and import all Spatch settings as text.
- Named presets of your own, beyond Calm and Everything.
- Strength sliders for tilt, ripple size and hide distance instead of fixed values.
- A live preview of the player inside Spatch settings.

## Speed

Spatch is now a small part of main-thread time during skips, around 4% of busy samples. What remains
is Spotify's own Compose and layout work when a song changes (about 6% janky frames at 120 Hz) and
when the player opens (about 4.4%, worst around 48 ms).

- Trim what recomposes on a song change, such as the cards below the player while they're off screen.
- Pre-inflate the player while idle so its first open is cheaper.
