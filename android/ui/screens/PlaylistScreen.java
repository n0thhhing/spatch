package spatch;

import spatch.ArtworkViews.Art;
import spatch.Controls.DownloadButton;
import spatch.Controls.PlayButton;
import spatch.Dock.DockScroll;
import spatch.MediaEntry.Song;
import spatch.SpatchUi.Icon;
import spatch.SpatchUi.Option;
import spatch.SpatchUi.StickerBackdrop;
import static spatch.PackedStrings.packStrings;
import static spatch.PackedStrings.unpackStrings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Playlist and album pages: track list, header wash, search and sort tools and the cover flight into the page. */
final class PlaylistScreen {
    private final SpatchUi ui;
    PlaylistScreen(SpatchUi ui) { this.ui = ui; }

    boolean playlistLoading, playlistFailed;

    private String playlistError = "";

    /** For an album or artist page: Spotify's own heading, and the releases shelf. */
    String detailTitle = "", detailSubtitle = "", detailImage = "";
    private List<MediaEntry> releases = new ArrayList<>();

    View headCover, flightView;
    private RectF flightFrom;
    private String flightImage = "";
    private boolean flightCircle;
    private TextView nowPlayingChip, songsNoMatch, songCount;
    private String songCountText = "";

    private static final int R_JUMP = 0x7f5a0001;

    void openPlaylist(MediaEntry e) { openPlaylist(e, true); }

    void openPlaylist(MediaEntry e, boolean forward) {
        if (ui.watchingPlaylist) { ui.watchingPlaylist = false; SpatchUi.WATCHES.execute(SpatchUi::nativeStopPlaylist); }
        if (forward && ui.playlist != null && ui.playlist != e) ui.pageStack.push(ui.playlist);
        if (forward) { ui.rememberScroll(); ui.shownKey = null; ui.scrolls.remove("playlist:" + e.uri); } // a fresh visit opens at the top
        ui.playlist = e;
        ui.playlistTracks = new ArrayList<>();
        releases = new ArrayList<>();
        ui.liveUri = "";
        songQuery = "";
        songFields = new String[0];
        allSongs.clear();
        recommendedSongs.clear();
        shownReply = null;
        shownCached = false;
        detailTitle = detailSubtitle = detailImage = "";
        playlistLoading = true;
        playlistFailed = false;
        String[] saved = unpackStrings(SpatchUi.nativeCached((SpatchUi.isDetail(e) ? "page3:" : "playlist4:") + e.uri));
        if (saved != null && saved.length > 0) applyReply(e, saved, true);
        else ui.showPage();
        ui.dockUi.updateDock();
        ui.enter((forward ? 1 : -1) * ui.dp(44), 0);
        loadPlaylist(e, 0);
    }

    void loadPlaylist(MediaEntry e, int attempt) {
        int request = ++ui.playlistRequest;
        // Never wait forever: after 20 s the page offers Retry.
        ui.postDelayed(() -> {
            if (request != ui.playlistRequest || ui.playlist != e || !playlistLoading) return;
            ui.playlistRequest++;
            playlistLoading = false;
            playlistFailed = true;
            playlistError = "Spotify didn’t answer in time";
            ui.showPage();
        }, 20_000);
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            String key = (SpatchUi.isDetail(e) ? "page3:" : "playlist4:") + e.uri;
            // What this page showed last time appears at once; Spotify's answer replaces it if it differs.
            if (attempt == 0 && !shownCached) {
                String[] saved = unpackStrings(SpatchUi.nativeCached(key));
                if (saved != null) ui.post(() -> { if (request == ui.playlistRequest && ui.playlist == e && playlistLoading) applyReply(e, saved, true); });
            }
            // Liked Songs' link includes your username, which Spotify has to be asked for first.
            String target = SpatchUi.liked(e.uri) ? SpatchUi.nativeLiked() : e.uri;
            if (target == null || target.isEmpty()) target = e.uri;
            final String resolved = target;
            byte[] firstBytes = SpatchUi.isDetail(e) ? SpatchUi.nativePage(e.uri) : SpatchUi.nativePlaylist(resolved, 0);
            String[] first = unpackStrings(firstBytes);
            boolean ok = first != null && first.length >= (SpatchUi.isDetail(e) ? 6 : 2) && first[0].isEmpty();
            int more = ok && !SpatchUi.isDetail(e) ? parseInt(first[1]) : 0;
            String[] full;
            if (more > 0) {
                // Long playlist: show the first page now if nothing is on screen, then fetch the rest and show it all.
                ui.post(() -> { if (request == ui.playlistRequest && ui.playlist == e && shownReply == null) applyReply(e, first, false); });
                String[] rest = unpackStrings(SpatchUi.nativePlaylistRest(resolved, more));
                if (rest == null) rest = new String[0];
                String[] all = java.util.Arrays.copyOf(first, first.length + rest.length);
                System.arraycopy(rest, 0, all, first.length, rest.length);
                all[1] = "0";
                full = all;
            } else full = first;
            final String[] r = full;
            ui.post(() -> {
                if (request != ui.playlistRequest || ui.playlist != e || !ui.isAttachedToWindow()) return;
                ui.liveUri = resolved;
                if (ok) {
                    SpatchUi.nativeRemember(key, more > 0 ? packStrings(r) : firstBytes);
                    if (!SpatchUi.isDetail(e) && !ui.watchingPlaylist) {
                        ui.watchingPlaylist = true;
                        SpatchUi.WATCHES.execute(() -> SpatchUi.nativeWatchPlaylist(resolved, "push:" + e.uri));
                    }
                }
                if (r != null && r.length == 1 && "loading".equals(r[0]) && attempt < 10) {
                    ui.postDelayed(() -> { if (request == ui.playlistRequest && ui.playlist == e) loadPlaylist(e, attempt + 1); }, 700);
                    return;
                }
                if (ok && java.util.Arrays.equals(r, shownReply)) return;
                if (!ok && shownCached) { android.util.Log.w("SpatchUi", "Kept the saved copy: " + (r == null || r.length == 0 ? "no reply" : r[0])); return; }
                applyReply(e, r != null && r.length == 1 && "loading".equals(r[0])
                    ? new String[]{"Spotify is still loading this playlist. Try again in a moment."} : r, false);
            });
        });
    }

    /** Rust's playlist fields: SONG_FIELDS strings per song. */
    private static final int SONG_FIELDS = Song.FIELDS;
    /** Every song of the open playlist, in its order; playlistTracks is what the search and sort leave of it. */
    private String[] songFields = new String[0];
    final List<Song> allSongs = new ArrayList<>();
    private final List<Song> recommendedSongs = new ArrayList<>();
    private String songQuery = "";

    /** The reply this page is showing, and whether it came from the saved copy. */
    private String[] shownReply;
    private boolean shownCached;

    private void applyReply(MediaEntry e, String[] r, boolean cached) {
        // Replacing what's on screen (saved copy -> Spotify's answer, or a push) keeps the scroll position.
        boolean replacing = shownReply != null;
        ListView list = ui.pageList;
        int first = replacing && list != null ? list.getFirstVisiblePosition() : 0;
        int top = replacing && list != null && list.getChildCount() > 0 ? list.getChildAt(0).getTop() : 0;
        // A newer copy of the playlist that's already on screen updates the list where it stands: rebuilding the page
        // flashed it and moved the list every time Spotify pushed the playlist, which it does whenever a song starts.
        boolean inPlace = replacing && !SpatchUi.isDetail(e) && !playlistLoading && !playlistFailed && list != null && songCount != null && list.isAttachedToWindow();
        shownReply = r;
        shownCached = cached;
        if (inPlace) {
            ui.playlistTracks.clear();
        } else {
            ui.playlistTracks = new ArrayList<>();
            releases = new ArrayList<>();
        }
        if (SpatchUi.isDetail(e)) applyDetail(r);
        else applyPlaylist(r, inPlace);
        ListView again = ui.pageList;
        if (replacing && again != null && first > 0 && again != list) again.setSelectionFromTop(Math.min(first, Math.max(0, again.getCount() - 1)), top);
    }

    private void applyPlaylist(String[] r) { applyPlaylist(r, false); }

    /** inPlace: the page already lists this playlist, so only its songs and count change. */
    private void applyPlaylist(String[] r, boolean inPlace) {
        playlistLoading = false;
        playlistFailed = r == null || r.length < 2 || !r[0].isEmpty();
        playlistError = r == null || r.length == 0 ? "No reply from Spotify" : r[0];
        if (playlistFailed) android.util.Log.w("SpatchUi", "Playlist request failed: " + playlistError);
        else {
            allSongs.clear();
            recommendedSongs.clear();
            int count = (r.length - 2) / SONG_FIELDS;
            List<String> kept = new ArrayList<>(count * SONG_FIELDS);
            for (int i = 0; i < count; i++) {
                int at = 2 + i * SONG_FIELDS;
                boolean rec = !r[at + 10].isEmpty();
                if (rec) {
                    recommendedSongs.add(new Song(r, at, recommendedSongs.size()));
                } else {
                    allSongs.add(new Song(r, at, allSongs.size()));
                    for (int k = 0; k < SONG_FIELDS; k++) kept.add(r[at + k]);
                }
            }
            if (ui.duration == 0 && !ui.uri.isEmpty()) {
                float from = ui.shownProgress();
                for (Song song : allSongs) {
                    if (song.uri.equals(ui.uri) && song.duration > 0) { ui.duration = song.duration; break; }
                }
                if (ui.duration > 0) ui.animateProgress(from);
            }
            String[] next = kept.toArray(new String[0]);
            // Only the recommended songs changed: the list on screen is already right, so don't repack or rebind it.
            boolean unchanged = inPlace && ui.pageList != null && songCount != null && java.util.Arrays.equals(next, songFields);
            songFields = next;
            if (!unchanged) SpatchUi.nativeSongs(packStrings(songFields));
            arrangeSongs();
            if (unchanged) return;
            if (inPlace && ui.pageList != null && songCount != null) {
                SpatchUi.notifyList(ui.pageList);
                int total = allSongs.size();
                String time = totalTime();
                songCountText = total + (total == 1 ? " song" : " songs") + (time.isEmpty() ? "" : " • " + time);
                String q = songQuery.trim();
                songCount.setText(q.isEmpty() ? songCountText : ui.playlistTracks.size() + " of " + allSongs.size() + " songs");
                if (songsNoMatch != null) songsNoMatch.setVisibility(ui.playlistTracks.isEmpty() && !q.isEmpty() ? View.VISIBLE : View.GONE);
                return;
            }
        }
        ui.showPage();
    }

    /** playlistTracks becomes the songs the search and sort leave, in that order (Rust: playlist::arrange). */
    private void arrangeSongs() {
        int[] order = SpatchUi.nativeArrange(songQuery, ui.prefs.getInt("song_sort", 0));
        ui.playlistTracks.clear();
        if (order != null) for (int i : order) if (i < allSongs.size()) ui.playlistTracks.add(allSongs.get(i));
    }

    static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static final String[] SONG_SORTS = {"Playlist order", "Title", "Artist", "Album", "Recently added"};

    /** "2 h 31 min" or "48 min" for every song's length together. */
    private String totalTime() {
        long ms = 0;
        for (Song song : allSongs) ms += song.duration;
        long minutes = Math.round(ms / 60000.0);
        if (minutes == 0) return "";
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    /** Reads nativePage's reply into the page state and redraws. */
    private void applyDetail(String[] r) {
        playlistLoading = false;
        playlistFailed = r == null || r.length < 6 || !r[0].isEmpty();
        playlistError = r == null || r.length == 0 ? "No reply from Spotify" : r[0];
        if (playlistFailed) android.util.Log.w("SpatchUi", "Page request failed: " + playlistError);
        else {
            detailTitle = r[1]; detailSubtitle = r[2]; detailImage = r[3];
            int tracks = Math.max(0, parseInt(r[4])), shelf = Math.max(0, parseInt(r[5]));
            int at = 6;
            for (int i = 0; i < tracks && at + SONG_FIELDS <= r.length; i++, at += SONG_FIELDS) ui.playlistTracks.add(new Song(r, at, i));
            for (int i = 0; i < shelf && at + 3 < r.length; i++, at += 4) releases.add(new MediaEntry(r[at], r[at + 1], r[at + 2], r[at + 3], 1));
        }
        ui.showPage();
    }

    private void openRecommended() {
        if (recommendedSongs.isEmpty()) return;
        Option[] options = new Option[recommendedSongs.size()];
        for (int i = 0; i < options.length; i++) {
            Song s = recommendedSongs.get(i);
            options[i] = new Option(s.title, s.subtitle, s.image, "play", false);
        }
        ui.sheet("Similar songs", "Recommended based on this playlist", options, i -> ui.command(PlayerScreen.PLAY, 0, recommendedSongs.get(i).uri));
    }

    /** Long-press on a playlist song: play from it, queue it, go to its album or artist, like or unlike it. */
    void songActions(Song song) {
        List<Option> names = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        names.add(new Option("Play from here", "", "", "play", false)); runs.add(() -> ui.open(song));
        names.add(new Option("Play next", "", "", "next", false)); runs.add(() -> { ui.command(PlayerScreen.QUEUE_NEXT, 0, song.uri); Toast.makeText(ui.activity, "Playing next", Toast.LENGTH_SHORT).show(); });
        names.add(new Option("Add to queue", "", "", "queue", false)); runs.add(() -> { ui.command(PlayerScreen.QUEUE_ADD, 0, song.uri); Toast.makeText(ui.activity, "Added to queue", Toast.LENGTH_SHORT).show(); });
        names.add(new Option("Add to playlist", "", "", "add", false)); runs.add(() -> ui.addToPlaylist(song));
        names.add(ui.downloadsScreen.songDownloadOption(song)); runs.add(() -> ui.downloadsScreen.downloadTapped(song));
        if (!song.albumUri.isEmpty()) { names.add(new Option("Go to album", song.album, "", "album", false)); runs.add(() -> openPlaylist(new MediaEntry(song.album, song.subtitle, song.albumUri, song.image, -1))); }
        if (!song.artistUri.isEmpty()) { names.add(new Option("Go to artist", song.subtitle, "", "artist", false)); runs.add(() -> openPlaylist(new MediaEntry(song.subtitle, "Artist", song.artistUri, "", -1))); }
        names.add(new Option(song.liked ? "Remove from Liked Songs" : "Add to Liked Songs", "", "", song.liked ? "heart" : "heart-outline", false));
        runs.add(() -> ui.playerScreen.setLikedSong(song, !song.liked));
        String note = song.album.isEmpty() ? song.subtitle : song.subtitle + " • " + song.album;
        ui.sheet(song.title, note, names.toArray(new Option[0]), choice -> runs.get(choice).run());
    }

    private void playlistActions(String title, String link) {
        if (ui.playlist == null) return;
        List<Option> names = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        boolean playingThis = listIsPlaying() && ui.playing;
        names.add(new Option(playingThis ? "Pause" : "Play", "", "", playingThis ? "pause" : "play", false));
        runs.add(() -> { if (listIsPlaying()) ui.playerScreen.togglePlay(); else ui.command(PlayerScreen.PLAY, listShuffle > 0 ? 1 : 2, ui.playUri()); });
        if (!OfficialPlayback.enabled()) {
            names.add(new Option("Shuffle play", "", "", "shuffle", false));
            runs.add(() -> ui.command(PlayerScreen.PLAY, 1, ui.playUri()));
        }
        if (!ui.playlist.uri.startsWith("spotify:artist:") && ui.downloadsScreen.downloadButton != null) {
            names.add(new Option("Download", "", "", "download", false));
            runs.add(() -> ui.downloadsScreen.downloadButton.performClick());
        }
        if (!link.isEmpty()) {
            names.add(new Option("Share link", "", "", "share", false));
            runs.add(() -> shareLinkSheet(title, link));
        }
        names.add(new Option("Refresh", "", "", "repeat", false));
        runs.add(() -> { playlistLoading = true; playlistFailed = false; ui.showPage(); loadPlaylist(ui.playlist, 0); });
        ui.sheet(title, ui.playlist.subtitle, names.toArray(new Option[0]), choice -> runs.get(choice).run());
    }

    void captureFlight(View artView, String img, boolean circle) {
        if (!ui.motion() || artView == null || artView.getWidth() <= 0 || artView.getHeight() <= 0 || img.isEmpty()) {
            flightFrom = null;
            return;
        }
        int[] rootAt = new int[2], viewAt = new int[2];
        ui.getLocationInWindow(rootAt);
        artView.getLocationInWindow(viewAt);
        float l = viewAt[0] - rootAt[0], t = viewAt[1] - rootAt[1];
        flightFrom = new RectF(l, t, l + artView.getWidth(), t + artView.getHeight());
        flightImage = img;
        flightCircle = circle;
    }

    private void launchHeroFlight(final View cover, final RectF from, final String img, final boolean circ) {
        final android.view.ViewTreeObserver vto = cover.getViewTreeObserver();
        vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.ViewTreeObserver curVto = cover.getViewTreeObserver();
                if (curVto.isAlive()) curVto.removeOnPreDrawListener(this);
                float tx = 0, ty = 0;
                for (View cur = cover; cur != null && cur != ui; cur = cur.getParent() instanceof View ? (View) cur.getParent() : null) {
                    tx += cur.getLeft();
                    ty += cur.getTop();
                }
                float tw = cover.getWidth(), th = cover.getHeight();
                if (tw <= 0 || th <= 0 || !cover.isAttachedToWindow()) return true;
                cover.setAlpha(0f);
                final Art flyer = ui.artwork.new Art(circ ? Art.CIRCLE : Art.ROUNDED, false);
                flyer.bind(img);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) Math.ceil(tw), (int) Math.ceil(th));
                flyer.setPivotX(0f);
                flyer.setPivotY(0f);
                flyer.setX(from.left);
                flyer.setY(from.top);
                flyer.setScaleX(from.width() / tw);
                flyer.setScaleY(from.height() / th);
                ui.addView(flyer, lp);
                flightView = flyer;
                final Runnable finish = () -> {
                    cover.setAlpha(1f);
                    if (flightView == flyer) flightView = null;
                    if (flyer.getParent() != null) ui.removeView(flyer);
                };
                cover.postDelayed(finish, 420);
                flyer.animate().x(tx).y(ty).scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(SpatchUi.EMPHASIZED).withEndAction(finish).start();
                return true;
            }
        });
    }

    private void wirePlaylistTools(ListView list, android.widget.EditText songSearch, TextView sortPill) {
        if (songSearch != null) {
            songSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    songQuery = e.toString();
                    arrangeSongs();
                    SpatchUi.notifyList(list);
                    if (songCount != null) {
                        String q = songQuery.trim();
                        songCount.setText(q.isEmpty() ? songCountText : ui.playlistTracks.size() + " of " + allSongs.size() + " songs");
                    }
                    if (songsNoMatch != null) {
                        songsNoMatch.setText("No songs match “" + songQuery.trim() + "”");
                        songsNoMatch.setVisibility(ui.playlistTracks.isEmpty() && !songQuery.trim().isEmpty() ? View.VISIBLE : View.GONE);
                    }
                }
            });
        }
        if (nowPlayingChip != null) {
            TextView chip = nowPlayingChip;
            int at = (Integer) chip.getTag(R_JUMP);
            ui.tappable(chip, "Jump to the song that's playing", () -> list.smoothScrollToPositionFromTop(at + list.getHeaderViewsCount(), ui.dp(120), 520));
        }
        if (sortPill != null) {
            TextView pillView = sortPill;
            ui.tappable(sortPill, "Sort songs", () -> ui.sheet("Sort songs", "Only changes how Spatch shows this playlist", SONG_SORTS, choice -> {
                ui.prefs.edit().putInt("song_sort", choice).apply();
                pillView.setText(SONG_SORTS[choice]);
                if (pillView.getTag(SpatchUi.R_COLOR) instanceof Runnable) ((Runnable) pillView.getTag(SpatchUi.R_COLOR)).run();
                arrangeSongs();
                SpatchUi.notifyList(list);
            }));
        }
    }

    View playlistPage() {
        FrameLayout page = new FrameLayout(ui.activity);
        boolean detail = SpatchUi.isDetail(ui.playlist);
        boolean land = ui.landscape() && !(ui.playlistTracks.isEmpty() && releases.isEmpty());
        String headImage = detail && !detailImage.isEmpty() ? detailImage : ui.playlist.image;
        String headTitle = detail && !detailTitle.isEmpty() ? detailTitle : ui.playlist.title;
        LinearLayout rightPane = land ? ui.column() : null;

        // Atmospheric gradient wash behind the header, tinted from the page cover once known (else the active accent).
        // GPU mode paints the wash as a still colour field from four tints of the cover's colours; otherwise a gradient.
        boolean gpuWash = GpuFx.shaders(ui.prefs);
        GradientDrawable washBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ui.surfHi, ui.surf, ui.bg});
        washBg.setDither(true);
        final int[] washColors = new int[5];
        final int washSeed = ui.playlist.uri.hashCode();
        View wash = !gpuWash ? new View(ui.activity) : new View(ui.activity) {
            private Shader field;
            private final Paint fieldPaint = new Paint();
            @Override protected void onDraw(Canvas c) {
                if (!c.isHardwareAccelerated()) { washBg.setBounds(0, 0, getWidth(), getHeight()); washBg.draw(c); return; } // a bitmap snapshot can't run shaders
                if (washColors[4] == 0) return;
                if (field == null) field = new android.graphics.RuntimeShader(GpuFx.source("field"));
                android.graphics.RuntimeShader rs = (android.graphics.RuntimeShader) field;
                rs.setFloatUniform("size", getWidth(), getHeight());
                rs.setFloatUniform("time", (washSeed & 0xffff) / 655.35f); // each playlist gets its own still arrangement
                rs.setFloatUniform("shade", 2f);
                for (int i = 0; i < 4; i++) rs.setColorUniform("c" + i, washColors[i]);
                rs.setColorUniform("base", washColors[4]);
                fieldPaint.setShader(rs);
                c.drawRect(0, 0, getWidth(), getHeight(), fieldPaint);
            }
        };
        if (!gpuWash) wash.setBackground(washBg);
        final Integer[] lastSeed = {null};
        final boolean[] lastDark = {ui.dark};
        final int[] headPal = {0, 0};
        ui.colored(wash, () -> {
            Integer seed = headImage.isEmpty() ? null : SpatchUi.SEEDS.get(headImage);
            Integer seed2 = headImage.isEmpty() ? null : SpatchUi.SEEDS2.get(headImage);
            int top, mid;
            if (seed != null) {
                if (!seed.equals(lastSeed[0]) || lastDark[0] != ui.dark || headPal[0] == 0) {
                    int[] p = SpatchUi.nativePalette2(seed, true, seed2 == null ? 0 : seed2, seed2 != null, ui.dark);
                    headPal[0] = p[5];
                    headPal[1] = p[6];
                    lastSeed[0] = seed;
                    lastDark[0] = ui.dark;
                }
                top = SpatchUi.blend(ui.surfHi, headPal[0], ui.dark ? .38f : .26f);
                mid = SpatchUi.blend(ui.surf, headPal[1], ui.dark ? .20f : .14f);
            } else {
                top = SpatchUi.blend(ui.surfHi, ui.accent2, ui.dark ? .34f : .22f);
                mid = SpatchUi.blend(ui.surf, ui.accent, ui.dark ? .18f : .12f);
            }
            washBg.setColors(new int[]{top, mid, ui.bg});
            int c0 = headPal[0] != 0 ? headPal[0] : ui.accent2, c1 = headPal[1] != 0 ? headPal[1] : ui.accent;
            washColors[0] = top;
            washColors[1] = SpatchUi.blend(ui.surfHi, c1, ui.dark ? .34f : .22f);
            washColors[2] = mid;
            washColors[3] = SpatchUi.blend(ui.surf, c0, ui.dark ? .20f : .12f);
            washColors[4] = ui.bg;
            wash.invalidate();
        });
        // The wash runs under the side insets too (past the page's edges), so no plain strip shows beside it in landscape.
        page.setClipChildren(false);
        FrameLayout.LayoutParams washAt = new FrameLayout.LayoutParams(-1, ui.dp(370) + ui.inset.top, Gravity.TOP);
        washAt.setMargins(-ui.sideLeft(), 0, -ui.inset.right, 0);
        wash.setTag(SpatchUi.BACKDROP);
        page.addView(wash, washAt);
        StickerBackdrop stickers = ui.new StickerBackdrop(ui.playlist.uri + ":" + headTitle);
        stickers.setTag(SpatchUi.BACKDROP);
        page.addView(stickers, new FrameLayout.LayoutParams(washAt));

        LinearLayout header = ui.column();
        header.setPadding(ui.dp(2), ui.inset.top + ui.dp(56), ui.dp(2), ui.dp(14));

        // Centered Material 3 Expressive hero cover over a dual-colour ambient cover glow.
        FrameLayout glowBox = new FrameLayout(ui.activity);
        View heroGlow = new View(ui.activity) {
            private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            private RadialGradient ga, gb;
            private int ca, cb, gw, gh;
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                int c1 = headPal[0] != 0 ? headPal[0] : ui.accent;
                int c2 = headPal[1] != 0 ? headPal[1] : ui.accent2;
                if (ga == null || ca != c1 || cb != c2 || gw != w || gh != h) {
                    ca = c1; cb = c2; gw = w; gh = h;
                    float cx = w / 2f, cy = h / 2f, ox = ui.dp(14), oy = ui.dp(9);
                    float r = Math.max(1f, Math.min(w, h) * .5f - Math.max(ox, oy) - ui.dp(2));
                    float[] stops = new float[]{0f, .45f, .78f, 1f};
                    int a1 = (c1 & 0x00ffffff) | ((ui.dark ? 78 : 56) << 24), a1m = (c1 & 0x00ffffff) | ((ui.dark ? 28 : 18) << 24);
                    int a2 = (c2 & 0x00ffffff) | ((ui.dark ? 62 : 44) << 24), a2m = (c2 & 0x00ffffff) | ((ui.dark ? 22 : 14) << 24);
                    ga = new RadialGradient(cx - ox, cy + oy, r, new int[]{a1, a1m, c1 & 0x00ffffff, 0}, stops, Shader.TileMode.CLAMP);
                    gb = new RadialGradient(cx + ox, cy - oy, r, new int[]{a2, a2m, c2 & 0x00ffffff, 0}, stops, Shader.TileMode.CLAMP);
                }
                glowPaint.setShader(ga);
                c.drawRect(0, 0, w, h, glowPaint);
                glowPaint.setShader(gb);
                c.drawRect(0, 0, w, h, glowPaint);
                glowPaint.setShader(null);
            }
        };
        heroGlow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ui.colored(heroGlow, heroGlow::invalidate);
        glowBox.addView(heroGlow, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout coverWrap = new FrameLayout(ui.activity);
        boolean artistPage = ui.playlist.uri.startsWith("spotify:artist:");
        GradientDrawable coverShape = ui.round(ui.surfHi, artistPage ? 104 : 26);
        coverWrap.setBackground(coverShape);
        ui.colored(coverWrap, () -> coverShape.setColor(ui.surfHi));
        coverWrap.setClipToOutline(true);
        coverWrap.setElevation(ui.dp(8));
        if (headImage.isEmpty() && !detail && SpatchUi.liked(ui.playlist.uri)) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{SpatchUi.blend(ui.surfHi, ui.accent2, .5f), SpatchUi.blend(ui.surfHi, ui.accent, .4f)});
            g.setCornerRadius(ui.dp(26));
            coverWrap.setBackground(g);
            ui.colored(coverWrap, () -> g.setColors(new int[]{SpatchUi.blend(ui.surfHi, ui.accent2, .5f), SpatchUi.blend(ui.surfHi, ui.accent, .4f)}));
            coverWrap.addView(ui.icon("heart", () -> ui.ink), new FrameLayout.LayoutParams(ui.dp(76), ui.dp(76), Gravity.CENTER));
        } else {
            Art art = ui.artwork.new Art(Art.WIDE, false);
            art.letter = SpatchUi.initial(headTitle);
            art.bind(headImage);
            coverWrap.addView(art, new FrameLayout.LayoutParams(-1, -1));
        }
        int covDp = land ? 172 : 208; // sideways the header column has the height to spare for a larger cover
        glowBox.addView(coverWrap, new FrameLayout.LayoutParams(ui.dp(covDp), ui.dp(covDp), Gravity.CENTER));
        coverWrap.setClickable(true);
        coverWrap.setOnClickListener(v -> {
            ui.tick(coverWrap);
            stickers.boostSpin();
            if (ui.motion()) {
                coverWrap.animate().cancel();
                coverWrap.setPivotX(coverWrap.getWidth() / 2f); // bounce from the middle (the scroll shrink moves the pivot to the top)
                coverWrap.setPivotY(coverWrap.getHeight() / 2f);
                coverWrap.setScaleX(.94f);
                coverWrap.setScaleY(.94f);
                coverWrap.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
            }
        });
        LinearLayout.LayoutParams covP = ui.lp(ui.dp(land ? 212 : 260), ui.dp(land ? 208 : 256));
        covP.gravity = Gravity.CENTER_HORIZONTAL;
        covP.topMargin = -ui.dp(land ? 18 : 24);
        covP.bottomMargin = -ui.dp(land ? 16 : 24);
        header.addView(glowBox, covP);
        headCover = coverWrap;

        // Left-aligned Spotify-style editorial title and stats block.
        TextView name = ui.text(headTitle, land ? 22 : 26, () -> ui.ink, true);
        name.setPadding(0, ui.dp(land ? 10 : 18), 0, ui.dp(4));
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(name, ui.fill(-2));
        int total = detail ? ui.playlistTracks.size() : allSongs.size();
        String time = detail ? "" : totalTime();
        String songs = total + (total == 1 ? " song" : " songs") + (time.isEmpty() ? "" : " • " + time);
        TextView count = ui.text(playlistLoading ? "Loading…" : playlistFailed ? "Couldn’t load" : detail && !detailSubtitle.isEmpty() ? detailSubtitle + " • " + songs : songs, 14, () -> ui.inkDim, false);
        header.addView(count, ui.fill(-2));
        songCount = count;
        songCountText = count.getText().toString();
        if (playlistFailed && !playlistError.isEmpty()) {
            // Spotify's own reason, so a failure says what happened.
            String lookup = SpatchUi.liked(ui.playlist.uri) ? SpatchUi.nativeLikedNote() : "";
            TextView why = ui.text(playlistError + "\n" + ui.playUri() + (lookup.isEmpty() ? "" : "\n" + lookup), 12, () -> ui.inkDim, false);
            why.setPadding(0, ui.dp(6), 0, ui.dp(4));
            header.addView(why, ui.fill(-2));
        }

        // Spotify-like action row: icon-only shuffle mode, share, Similar on the left; big scalloped play button on the right.
        LinearLayout actions = ui.row();
        // Landscape gives the header a narrower column, so the buttons tighten up to keep the play button whole.
        int actionSize = ui.dp(land ? 42 : 46), actionGap = ui.dp(land ? 8 : 10);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        String listUri = ui.playUri();
        boolean smartAllowed = !detail && !SpatchUi.liked(ui.playlist.uri);

        FrameLayout shuffleBox = new FrameLayout(ui.activity);
        GradientDrawable shufRing = ui.round(ui.surfHi, 23);
        shuffleBox.setBackground(shufRing);
        Icon modeIcon = ui.new Icon("shuffle", ui.inkDim);
        Icon modeSparkle = ui.new Icon("sparkle", ui.accent);
        shuffleBox.addView(modeIcon, new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ui.dp(11), ui.dp(11), Gravity.TOP | Gravity.END);
        sp.setMargins(0, ui.dp(8), ui.dp(7), 0);
        shuffleBox.addView(modeSparkle, sp);
        Runnable paintMode = () -> {
            boolean on = listShuffle > 0;
            shufRing.setColor(on ? SpatchUi.blend(ui.surfHi, ui.accent, .24f) : ui.surfHi);
            shufRing.setStroke(Math.max(1, ui.dp(.8f)), on ? (ui.accent & 0x00ffffff) | 0x48000000 : (ui.ink & 0x00ffffff) | 0x14000000);
            modeIcon.color = on ? ui.accent : ui.inkDim;
            modeIcon.invalidate();
            modeSparkle.color = ui.accent;
            modeSparkle.setVisibility(listShuffle == 2 ? View.VISIBLE : View.GONE);
            modeSparkle.invalidate();
        };
        ui.colored(shuffleBox, paintMode);
        ui.tappable(shuffleBox, "Shuffle: off, on or Smart Shuffle", () -> {
            if (OfficialPlayback.enabled()) {
                Toast.makeText(ui.activity, "Change shuffle in official Spotify", Toast.LENGTH_SHORT).show();
                return;
            }
            // Off → Shuffle → Smart Shuffle (playlists only) → Off, stored with Spotify for this list.
            int next = listShuffle <= 0 ? 1 : listShuffle == 1 && smartAllowed ? 2 : 0;
            int before = listShuffle;
            listShuffle = next;
            paintMode.run();
            if (listIsPlaying()) ui.command(PlayerScreen.SHUFFLE, next > 0 ? 1 : 0, "");
            ui.submit(SpatchUi.PLAYLISTS, () -> {
                String error = SpatchUi.nativeSetShuffleMode(listUri, next);
                if (error != null && !error.isEmpty()) ui.post(() -> {
                    listShuffle = before;
                    paintMode.run();
                    Toast.makeText(ui.activity, "Couldn’t change shuffle: " + error, Toast.LENGTH_SHORT).show();
                });
            });
        });
        actions.addView(shuffleBox, ui.lp(actionSize, actionSize));

        listShuffle = OfficialPlayback.enabled() ? 0 : -1;
        if (OfficialPlayback.enabled()) shuffleBox.setVisibility(View.GONE);
        paintMode.run();
        if (!OfficialPlayback.enabled()) ui.submit(SpatchUi.PLAYLISTS, () -> {
            int known = SpatchUi.nativeShuffleMode(listUri);
            ui.post(() -> { if (ui.playlist != null && listUri.equals(ui.playUri()) && known >= 0) { listShuffle = known; paintMode.run(); } });
        });

        String link = shareLink(ui.playlist.uri);
        if (!link.isEmpty()) {
            actions.addView(ui.space(actionGap, 1));
            View shareBtn = ui.iconButton(ui.icon("share", () -> ui.ink), "Share", () -> shareLinkSheet(headTitle, link));
            GradientDrawable shareBg = ui.round(ui.surfHi, 23);
            shareBtn.setBackground(shareBg);
            ui.colored(shareBtn, () -> {
                shareBg.setColor(ui.surfHi);
                shareBg.setStroke(Math.max(1, ui.dp(.8f)), (ui.ink & 0x00ffffff) | 0x14000000);
            });
            actions.addView(shareBtn, ui.lp(actionSize, actionSize));
        }
        if (!ui.playlist.uri.startsWith("spotify:artist:")) {
            actions.addView(ui.space(actionGap, 1));
            DownloadButton dlBtn = ui.controls.new DownloadButton(ui::playUri, headTitle);
            GradientDrawable dlBg = ui.round(ui.surfHi, 23);
            dlBtn.setBackground(dlBg);
            ui.colored(dlBtn, () -> {
                dlBg.setColor(ui.surfHi);
                dlBg.setStroke(Math.max(1, ui.dp(.8f)), (ui.ink & 0x00ffffff) | 0x14000000);
            });
            actions.addView(dlBtn, ui.lp(actionSize, actionSize));
            ui.downloadsScreen.downloadButton = dlBtn;
        }
        if (!recommendedSongs.isEmpty()) {
            actions.addView(ui.space(actionGap, 1));
            View simBtn = ui.iconButton(ui.icon("sparkle", () -> ui.accent), "Similar songs", this::openRecommended);
            GradientDrawable simBg = ui.round(ui.surfHi, 23);
            simBtn.setBackground(simBg);
            ui.colored(simBtn, () -> {
                simBg.setColor(ui.surfHi);
                simBg.setStroke(Math.max(1, ui.dp(.8f)), (ui.ink & 0x00ffffff) | 0x14000000);
            });
            actions.addView(simBtn, ui.lp(actionSize, actionSize));
        }
        PlayButton fab = ui.controls.new PlayButton();
        ui.playButtons.remove(fab);
        fab.set(listIsPlaying() && ui.playing);
        ui.tappable(fab, "Play", () -> {
            if (listIsPlaying()) { ui.playerScreen.togglePlay(); return; }
            ui.command(PlayerScreen.PLAY, listShuffle > 0 ? 1 : 2, ui.playUri());
            highlightWave(); // the button's own confetti burst goes first; then this runs down the songs
        });
        listFab = fab;
        // Portrait pushes the play button to the far end; sideways it follows the other buttons, so the row reads as one.
        actions.addView(land ? ui.space(actionGap, 1) : ui.space(0, 1), land ? ui.lp(actionGap, 1) : ui.weight());
        // In portrait, a placeholder keeps room for the floating pinned PlayButton; in landscape it lives right in actions.
        View fabSlot = land ? null : ui.space(ui.dp(62), ui.dp(62));
        if (land) actions.addView(fab, ui.lp(ui.dp(58), ui.dp(58)));
        else actions.addView(fabSlot);
        LinearLayout.LayoutParams ap = ui.fill(-2);
        ap.topMargin = ui.dp(12);
        header.addView(actions, ap);

        if (playlistFailed) header.addView(ui.pill("Retry", () -> { playlistLoading = true; playlistFailed = false; ui.showPage(); loadPlaylist(ui.playlist, 0); }));
        LinearLayout listTop = land ? rightPane : header;
        android.widget.EditText songSearch = null;
        TextView sortPill = null;
        if (!detail && allSongs.size() > 1) {
            // Search and sort inside the playlist, done in Rust over the songs already here.
            LinearLayout tools = ui.row();
            LinearLayout search = ui.row();
            search.setPadding(ui.dp(14), 0, ui.dp(6), 0);
            GradientDrawable searchBg = ui.round(ui.surfHi, 23);
            search.setBackground(searchBg);
            ui.colored(search, () -> {
                searchBg.setColor(ui.surfHi);
                searchBg.setStroke(Math.max(1, ui.dp(.8f)), (ui.ink & 0x00ffffff) | 0x12000000);
            });
            search.addView(ui.icon("search", () -> ui.inkDim), ui.lp(ui.dp(19), ui.dp(19)));
            songSearch = new android.widget.EditText(ui.activity);
            songSearch.setBackground(null);
            songSearch.setHint("Find in playlist");
            android.widget.EditText find = songSearch;
            ui.colored(find, () -> { find.setHintTextColor(ui.inkDim); find.setTextColor(ui.ink); });
            songSearch.setTextSize(15);
            songSearch.setSingleLine(true);
            songSearch.setPadding(ui.dp(10), 0, ui.dp(4), 0);
            songSearch.setText(songQuery);
            search.addView(songSearch, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
            ui.clearButton(search, songSearch);
            tools.addView(search, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
            tools.addView(ui.space(ui.dp(8), 1));
            int sortChoice = Math.max(0, Math.min(SONG_SORTS.length - 1, ui.prefs.getInt("song_sort", 0)));
            sortPill = ui.text(SONG_SORTS[sortChoice], 14, sortChoice > 0 ? ui.accent : ui.ink, true);
            sortPill.setGravity(Gravity.CENTER);
            sortPill.setPadding(ui.dp(15), 0, ui.dp(15), 0);
            sortPill.setSingleLine(true);
            GradientDrawable sortBg = ui.round(ui.surfHi, 23);
            sortPill.setBackground(sortBg);
            TextView pill = sortPill;
            ui.colored(pill, () -> {
                boolean active = ui.prefs.getInt("song_sort", 0) > 0;
                pill.setTextColor(active ? ui.accent : ui.ink);
                sortBg.setColor(active ? SpatchUi.blend(ui.surfHi, ui.accent, .20f) : ui.surfHi);
                sortBg.setStroke(Math.max(1, ui.dp(.8f)), active ? (ui.accent & 0x00ffffff) | 0x40000000 : (ui.ink & 0x00ffffff) | 0x12000000);
            });
            tools.addView(sortPill, ui.lp(-2, ui.dp(44)));
            LinearLayout.LayoutParams tp = ui.fill(-2);
            if (!land) tp.topMargin = ui.dp(14);
            listTop.addView(tools, tp);
            songsNoMatch = ui.text("", 15, () -> ui.inkDim, false);
            songsNoMatch.setGravity(Gravity.CENTER);
            songsNoMatch.setPadding(0, ui.dp(24), 0, 0);
            songsNoMatch.setVisibility(View.GONE);
            listTop.addView(songsNoMatch, ui.fill(-2));
            // The song that's playing is in this list: a chip that glides the list down to it.
            int playingAt = -1;
            for (int i = 0; i < ui.playlistTracks.size(); i++) {
                MediaEntry t = ui.playlistTracks.get(i);
                if (!ui.title.isEmpty() && t.title.equals(ui.title) && t.subtitle.equals(ui.artist)) { playingAt = i; break; }
            }
            if (playingAt > 3) {
                nowPlayingChip = ui.text("Now playing • " + ui.title, 13, () -> ui.onAccent, true);
                nowPlayingChip.setSingleLine(true);
                nowPlayingChip.setEllipsize(TextUtils.TruncateAt.END);
                nowPlayingChip.setGravity(Gravity.CENTER);
                nowPlayingChip.setPadding(ui.dp(16), 0, ui.dp(16), 0);
                ui.bgRound(nowPlayingChip, 18, () -> ui.accent);
                nowPlayingChip.setTag(R_JUMP, playingAt);
                LinearLayout.LayoutParams cp = ui.lp(-2, ui.dp(34));
                cp.topMargin = ui.dp(10);
                listTop.addView(nowPlayingChip, cp);
            } else nowPlayingChip = null;
        }

        // Collapsing floating top bar: tonal back circle + page title that fades in as the hero scrolls off + tonal more circle.
        LinearLayout topBar = ui.row();
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(ui.dp(14), ui.inset.top + ui.dp(6), ui.dp(14), ui.dp(8));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(ui.bg);
        topBar.setBackground(barBg);
        barBg.setAlpha(0);
        ui.colored(topBar, () -> barBg.setColor(ui.bg));
        View barFade = new View(ui.activity);
        GradientDrawable barFadeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ui.bg, ui.bg & 0x00ffffff});
        barFade.setBackground(barFadeBg);
        ui.colored(barFade, () -> barFadeBg.setColors(new int[]{ui.bg, ui.bg & 0x00ffffff}));
        barFade.setAlpha(0f);
        FrameLayout backBox = new FrameLayout(ui.activity);
        GradientDrawable backShape = ui.round(ui.surf, 21);
        backBox.setBackground(backShape);
        ui.colored(backBox, () -> {
            backShape.setColor((ui.surf & 0x00ffffff) | 0xc8000000);
            backShape.setStroke(Math.max(1, ui.dp(.8f)), (ui.ink & 0x00ffffff) | 0x16000000);
        });
        backBox.addView(ui.icon("back", () -> ui.ink), new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        ui.tappable(backBox, "Back", ui::back);
        if (!land) topBar.addView(backBox, ui.lp(ui.dp(42), ui.dp(42)));
        TextView collapsed = ui.text(headTitle, 18, () -> ui.ink, true);
        ui.oneLine(collapsed);
        collapsed.setPadding(ui.dp(12), 0, ui.dp(74), 0);
        collapsed.setAlpha(0f);
        topBar.addView(collapsed, ui.weight());
        FrameLayout moreBox = new FrameLayout(ui.activity);
        GradientDrawable moreShape = ui.round(ui.surf, 21);
        moreBox.setBackground(moreShape);
        ui.colored(moreBox, () -> {
            moreShape.setColor((ui.surf & 0x00ffffff) | 0xc8000000);
            moreShape.setStroke(Math.max(1, ui.dp(.8f)), (ui.ink & 0x00ffffff) | 0x16000000);
        });
        View moreGlyph = ui.icon("more", () -> ui.ink);
        moreBox.addView(moreGlyph, new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
        ui.tappable(moreBox, "More options", () -> {
            SpatchUi.animateMore(moreGlyph, ui.motion());
            playlistActions(headTitle, link);
        });
        if (!land) topBar.addView(moreBox, ui.lp(ui.dp(42), ui.dp(42)));

        final float pinY = ui.inset.top + ui.dp(25);
        Runnable syncFab = () -> {
            if (fabSlot == null || fabSlot.getHeight() == 0) return;
            boolean headerVisible = header.getParent() != null;
            float scrollY = header.getParent() instanceof ScrollView ? ((ScrollView) header.getParent()).getScrollY() : 0f;
            float naturalY = headerVisible ? header.getTop() + actions.getTop() + fabSlot.getTop() - scrollY : -ui.dp(200);
            fab.setTranslationY(Math.max(pinY, naturalY));
            if (fab.getVisibility() != View.VISIBLE) fab.setVisibility(View.VISIBLE);
        };
        if (!land) {
            fab.setVisibility(View.INVISIBLE);
            header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> syncFab.run());
        }

        if (ui.playlistTracks.isEmpty() && releases.isEmpty()) {
            ScrollView s = new ScrollView(ui.activity);
            s.setVerticalScrollBarEnabled(false);
            header.setPadding(ui.dp(20), ui.inset.top + ui.dp(56), ui.dp(20), ui.dockRoom());
            s.addView(header);
            s.setOnScrollChangeListener((v, sx, sy, osx, osy) -> { syncFab.run(); ui.dockUi.scrollDock(sy - osy, sy <= 0); });
            page.addView(s, new FrameLayout.LayoutParams(-1, -1));
        } else if (land) {
            LinearLayout split = ui.row();
            ScrollView leftScroll = new ScrollView(ui.activity);
            leftScroll.setVerticalScrollBarEnabled(false);
            leftScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            header.setPadding(ui.dp(18), ui.inset.top + ui.dp(16), ui.dp(12), ui.dockRoom()); // the page host already keeps clear of the side insets
            leftScroll.addView(header, new ScrollView.LayoutParams(-1, -2));
            split.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, 1f));
            ListView list = ui.entryList(ui.playlistTracks, null);
            list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            wirePlaylistTools(list, songSearch, sortPill);
            if (!releases.isEmpty()) {
                LinearLayout footer = ui.column();
                ui.homeScreen.shelf(footer, "Releases", releases, null);
                list.addFooterView(footer, null, false);
            }
            list.setPadding(0, ui.dp(8), 0, ui.dockRoom());
            list.setClipToPadding(false);
            list.setVerticalFadingEdgeEnabled(true); // rows fade out under the search bar instead of being sliced off
            list.setFadingEdgeLength(ui.dp(28));
            list.setOnScrollListener(ui.dockUi.new DockScroll());
            rightPane.setPadding(ui.dp(10), ui.inset.top + ui.dp(12), ui.dp(18), 0);
            rightPane.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            split.addView(rightPane, new LinearLayout.LayoutParams(0, -1, 1.25f));
            page.addView(split, new FrameLayout.LayoutParams(-1, -1));
            ui.pageList = list;
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ui.dp(42), ui.dp(42), Gravity.TOP | Gravity.START);
            bp.setMargins(ui.dp(14), ui.inset.top + ui.dp(6), 0, 0);
            page.addView(backBox, bp);
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(ui.dp(42), ui.dp(42), Gravity.TOP | Gravity.END);
            mp.setMargins(0, ui.inset.top + ui.dp(6), ui.dp(14), 0);
            page.addView(moreBox, mp);
        } else {
            ListView list = ui.entryList(ui.playlistTracks, header);
            list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            wirePlaylistTools(list, songSearch, sortPill);
            if (!releases.isEmpty()) {
                LinearLayout footer = ui.column();
                ui.homeScreen.shelf(footer, "Releases", releases, null);
                list.addFooterView(footer, null, false);
            }
            list.setPadding(ui.dp(18), 0, ui.dp(18), ui.dockRoom());
            // The cover shrinks up and fades, the atmospheric wash recedes, and the floating bar brings in the title.
            View coverView = headCover;
            DockScroll dockScroll = ui.dockUi.new DockScroll();
            list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(android.widget.AbsListView v, int state) {}
                @Override public void onScroll(android.widget.AbsListView v, int first, int visible, int totalItems) {
                    dockScroll.onScroll(v, first, visible, totalItems);
                    float scrolled = first == 0 && v.getChildCount() > 0 ? Math.max(0, -v.getChildAt(0).getTop()) : ui.dp(400);
                    float f = Math.max(0f, Math.min(1f, scrolled / ui.dp(240)));
                    if (coverView != null && ui.motion()) {
                        // Shrinks toward its top middle; both axes are set, since an explicit pivot no longer centres the other one.
                        coverView.setPivotX(coverView.getWidth() / 2f);
                        coverView.setPivotY(0);
                        coverView.setScaleX(1f - .3f * f);
                        coverView.setScaleY(1f - .3f * f);
                        coverView.setAlpha(1f - .9f * f);
                    }
                    glowBox.setAlpha(1f - .9f * f);
                    wash.setTranslationY(-scrolled * .35f);
                    wash.setAlpha(1f - .9f * f);
                    stickers.setTranslationY(-scrolled * .22f);
                    stickers.setAlpha(1f - f);
                    float barT = Math.max(0f, Math.min(1f, (scrolled - ui.dp(190)) / ui.dp(60)));
                    barBg.setAlpha(Math.round(255 * barT));
                    barFade.setAlpha(barT);
                    collapsed.setAlpha(barT);
                    float moreAlpha = 1f - barT;
                    moreBox.setAlpha(moreAlpha);
                    moreBox.setScaleX(1f - 0.18f * barT);
                    moreBox.setScaleY(1f - 0.18f * barT);
                    moreBox.setClickable(moreAlpha > 0.5f);
                    moreBox.setEnabled(moreAlpha > 0.5f);
                    syncFab.run();
                }
            });
            list.setClipToPadding(false);
            page.addView(list, new FrameLayout.LayoutParams(-1, -1));
            ui.pageList = list;
        }
        if (!land) {
            page.addView(topBar, new FrameLayout.LayoutParams(-1, ui.dp(56) + ui.inset.top, Gravity.TOP));
            FrameLayout.LayoutParams bf = new FrameLayout.LayoutParams(-1, ui.dp(16), Gravity.TOP);
            bf.topMargin = ui.dp(56) + ui.inset.top;
            page.addView(barFade, bf);
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(ui.dp(62), ui.dp(62), Gravity.TOP | Gravity.END);
            fp.rightMargin = ui.dp(20);
            page.addView(fab, fp);
        }
        if (flightFrom != null && ui.motion()) {
            RectF from = flightFrom; String img = flightImage; boolean circ = flightCircle;
            flightFrom = null;
            launchHeroFlight(coverWrap, from, img, circ);
        }
        return page;
    }

    /** Pushes the current state into whichever views exist. */
    PlayButton listFab;

    /**
     * Playing a whole list sends a wave of highlight down its songs: each row on screen swells with a soft accent tint
     * and fades, a beat after the one above. Drawn on each row's overlay, so nothing is laid out.
     */
    private void highlightWave() {
        android.widget.ListView list = ui.pageList;
        if (list == null || !ui.motion()) return;
        int headers = list.getHeaderViewsCount(), first = list.getFirstVisiblePosition(), step = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            if (first + i < headers) continue;
            View row = list.getChildAt(i);
            GradientDrawable glow = ui.round((ui.accent & 0x00ffffff) | 0x38000000, 16);
            glow.setBounds(ui.dp(-6), ui.dp(2), row.getWidth() + ui.dp(6), row.getHeight() - ui.dp(2));
            glow.setAlpha(0);
            row.getOverlay().add(glow);
            android.animation.ValueAnimator swell = android.animation.ValueAnimator.ofFloat(0f, 1f);
            swell.setStartDelay(260L + step++ * 45L);
            swell.setDuration(560);
            swell.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                glow.setAlpha(Math.round(255 * (t < .3f ? t / .3f : 1f - (t - .3f) / .7f))); // quick to light, slow to fade
            });
            swell.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { row.getOverlay().remove(glow); }
            });
            swell.start();
        }
    }
    /** The open list's shuffle mode as Spotify keeps it: -1 unknown, 0 off, 1 shuffle, 2 Smart Shuffle. */
    private int listShuffle = -1;

    /** The open.spotify.com link for a playlist, album, artist, show, song or episode; "" for others. */
    private static String shareLink(String u) {
        for (String kind : new String[]{"playlist", "album", "artist", "show", "track", "episode"}) {
            String prefix = "spotify:" + kind + ":";
            if (u.startsWith(prefix)) return "https://open.spotify.com/" + kind + "/" + u.substring(prefix.length());
            int at = u.indexOf(":" + kind + ":");
            if (u.startsWith("spotify:user:") && at > 0 && kind.equals("playlist")) return "https://open.spotify.com/playlist/" + u.substring(at + kind.length() + 2);
        }
        return "";
    }

    private void shareLinkSheet(String name, String link) {
        ui.sheet(name, link, new Option[]{new Option("Share link", "", "", "share", false), new Option("Copy link", "", "", "check", false)}, c -> {
            if (c == 0) {
                android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(android.content.Intent.EXTRA_TEXT, link);
                ui.activity.startActivity(android.content.Intent.createChooser(send, name));
            } else {
                android.content.ClipboardManager clip = (android.content.ClipboardManager) ui.activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText(name, link));
                Toast.makeText(ui.activity, "Link copied", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Whether the open playlist or page is what's playing. */
    boolean listIsPlaying() {
        if (ui.playlist == null || ui.context.isEmpty()) return false;
        String list = ui.playUri();
        return ui.context.equals(list) || ui.context.equals(ui.playlist.uri) || (SpatchUi.liked(ui.context) && SpatchUi.liked(ui.playlist.uri));
    }
}
