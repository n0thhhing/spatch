# Suggestions

Ideas that were not built in the overnight pass of 2026-11-01. Nothing here is a finished feature
being held back: each item either needs a decision, needs something the patcher can't reach yet, or
hasn't been started. Findings from attempts are included so nobody repeats them.

## Tried and not working yet

**Cards on Spotify's settings rows and the queue.** Search, albums and artist pages are `RecyclerView`s, so `RowCards` gives their rows cards. Spotify's settings is one full-screen `ComposeView`, and `kro1.d` doesn't reach it. The first needs the real row function. The queue now joins from its layout manager, not yet seen on a phone.

**Spotify's own Settings screen (2026-11-06).** The main list is one `ComposeView` with no test tags. `ocl.create()`
builds each page (`xr61`) and its rows (`jgb1`, icon spec `xt61` with an Encore icon such as `bkx.c`), but the row
drawing hasn't been found: `ga51.e(hux, ryn1, xt61, Modifier, Composer, I)` takes the icon spec and a Modifier, yet
hooking it changed nothing on the main list or the Playback page. The main-thread sampler doesn't catch rows while
scrolling (lazy rows don't recompose); sample while the page first opens instead.

**Spotify's white pills (Refresh, Log out, round play buttons on Home).** Turning white into the accent
in `Surfaces.bg`, including near-white, changed nothing, so these don't paint through `yw9`. The next
step is finding Encore's contained button.

## Eye candy

- Lyrics: earlier lines fading out. It needs a method call in classes9, which is at its limit.
- Context menus: rows rising in turn, a tinted header from the cover.
- Connect sheet: animated rings around the current device.
- Home shelves: scroll parallax on large covers.
- Artist page: header image zooms slightly on pull, name fades into the top bar.
- Animated wallpaper-style backdrop on Home, off by default.

## Fun interactions

- Immersive: drag up and down for volume.
- Mini player: fling the cover away to skip, with it spinning off the bar.
- Nav bar: long-press a tab for shortcuts, such as Recents or a pinned playlist.
- Search: pull down on the page to focus the search field.
- Library: pinch to switch between list and grid.
- Shake to shuffle, off by default.
- Swipe between tabs from the page content (rows that scroll sideways need an exclusion).
- Hold the play button to set a sleep timer by dragging around it.

## Speed

Spatch is now a small part of main-thread time during skips, around 4% of busy samples. What remains
is Spotify's own Compose and layout work when a song changes (about 6% janky frames at 120 Hz) and
when the player opens (about 4.4%, worst around 48 ms).

- Trim what recomposes on a song change, such as the cards below the player while they're off screen.
- Pre-inflate the player while idle so its first open is cheaper.
