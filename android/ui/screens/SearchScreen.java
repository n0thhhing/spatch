package spatch;

import spatch.SettingsScreen.Pick;
import spatch.SpatchUi.Option;
import spatch.SpatchUi.Row;
import static spatch.PackedStrings.unpackStrings;

import spatch.ArtworkViews.Art;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Search page: query debounce, recent searches and result rendering. */
final class SearchScreen {
    private final SpatchUi ui;
    SearchScreen(SpatchUi ui) { this.ui = ui; }

    private static final int HIT_FIELDS = 7;
    private String searchQuery = "";
    private String[] searchHits = new String[0];
    private boolean searchBusy, catalogSearch = true;
    String catalogPage = "", catalogError = "";
    int searchRequest, imeInset;
    ScrollView searchScroll;
    private LinearLayout searchResults;
    private android.widget.EditText searchField;

    private final Runnable searchRun = () -> runSearch("", searchRequest);

    private void scheduleSearch() {
        ui.removeCallbacks(searchRun);
        searchRequest++;
        // The old results stay until the new ones arrive (then they glide into place), so typing never blanks the list.
        if (searchQuery.trim().isEmpty()) searchHits = new String[0];
        catalogPage = catalogError = "";
        searchBusy = !searchQuery.trim().isEmpty();
        renderSearch();
        if (searchBusy) ui.postDelayed(searchRun, 300);
    }

    private void runSearch(String token, int id) {
        final String q = searchQuery.trim();
        final boolean online = catalogSearch;
        searchBusy = true;
        renderSearch();
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            String[] found = null;
            String next = "", error = "";
            try {
                if (online) {
                    String[] response = unpackStrings(SpatchUi.nativeCatalog(CoreBridge.search(q, token)));
                    if (response == null || response.length == 0 || (response.length - 1) % 6 != 0)
                        throw new IllegalStateException("Invalid search response");
                    next = response[0];
                    List<String> hits = new ArrayList<>();
                    for (int i = 1; i + 5 < response.length; i += 6) {
                        int category = PlaylistScreen.parseInt(response[i + 4]);
                        String section = category == 0 ? "Playlists" : category == 1 ? "Albums" : category == 2 ? "Artists"
                            : category == 3 ? "Podcasts" : response[i + 2].startsWith("spotify:episode:") ? "Podcast episodes" : "Songs";
                        java.util.Collections.addAll(hits, section, response[i], response[i + 1], response[i + 2],
                            response[i + 3], response[i + 4], response[i + 5]);
                    }
                    found = hits.toArray(new String[0]);
                } else {
                    String[] offline = unpackStrings(SpatchUi.nativeSearch(q));
                    List<String> hits = new ArrayList<>();
                    if (offline != null) for (int i = 0; i + 6 <= offline.length; i += 6) {
                        for (int j = 0; j < 6; j++) hits.add(offline[i + j]);
                        hits.add("0");
                    }
                    found = hits.toArray(new String[0]);
                }
            } catch (Exception failed) {
                error = failed.toString();
                android.util.Log.w("SpatchUi", "Search failed", failed);
            }
            String[] result = found;
            String page = next, failure = error;
            ui.post(() -> {
                if (id != searchRequest) return;
                searchBusy = false;
                catalogError = failure;
                if (failure.isEmpty()) {
                    List<String> merged = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    if (!token.isEmpty()) {
                        java.util.Collections.addAll(merged, searchHits);
                        for (int i = 0; i + HIT_FIELDS <= searchHits.length; i += HIT_FIELDS) seen.add(searchHits[i + 3]);
                    }
                    if (result != null) for (int i = 0; i + HIT_FIELDS <= result.length; i += HIT_FIELDS) {
                        if (seen.add(result[i + 3])) for (int j = 0; j < HIT_FIELDS; j++) merged.add(result[i + j]);
                    }
                    searchHits = merged.toArray(new String[0]);
                    catalogPage = page.equals(token) ? "" : page;
                }
                renderSearch();
            });
        });
    }

    private List<String> recentSearches() {
        List<String> out = new ArrayList<>();
        for (String q : ui.prefs.getString("recent_searches", "").split("\n")) if (!q.trim().isEmpty()) out.add(q.trim());
        return out;
    }

    private void rememberSearch(String q) {
        q = q.trim();
        if (q.isEmpty()) return;
        List<String> all = recentSearches();
        all.remove(q);
        all.add(0, q);
        while (all.size() > 8) all.remove(all.size() - 1);
        ui.prefs.edit().putString("recent_searches", android.text.TextUtils.join("\n", all)).apply();
    }

    View searchPage() {
        LinearLayout page = ui.column();
        page.setPadding(0, ui.inset.top, 0, 0);
        ui.pageHeading(page, "Search", true);

        Pick searchIn = selected -> {
            catalogSearch = selected == 0;
            scheduleSearch();
            ui.showPage();
        };

        LinearLayout box = ui.row();
        box.setPadding(ui.dp(16), 0, ui.dp(8), 0);
        ui.bgRound(box, 26, () -> ui.surf);
        box.addView(ui.icon("search", () -> ui.inkDim), ui.lp(ui.dp(22), ui.dp(22)));
        android.widget.EditText field = new android.widget.EditText(ui.activity);
        field.setBackground(null);
        field.setHint("Songs, albums, playlists, podcasts");
        field.setHintTextColor(ui.inkDim);
        field.setTextColor(ui.ink);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.setPadding(ui.dp(12), 0, ui.dp(4), 0);
        field.setText(searchQuery);
        box.addView(field, new LinearLayout.LayoutParams(0, ui.dp(50), 1));
        ui.clearButton(box, field);
        searchField = field;

        ScrollView scroll = new ScrollView(ui.activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(true);
        scroll.setPadding(0, 0, 0, imeInset);
        LinearLayout results = ui.column();
        results.setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dockRoom());
        scroll.addView(results, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> ui.dockUi.scrollDock(y - oldY, y <= 0));
        if (ui.landscape()) {
            LinearLayout split = ui.row();
            LinearLayout left = ui.column();
            left.setGravity(Gravity.TOP);
            left.setPadding(0, 0, 0, ui.dockRoom());
            LinearLayout.LayoutParams bp = ui.fill(ui.dp(50));
            bp.setMargins(ui.dp(18), ui.dp(4), ui.dp(10), ui.dp(10));
            left.addView(box, bp);
            ui.settingsScreen.segmented(left, "Search in", new String[]{"Spotify", "On this device"}, catalogSearch ? 0 : 1, searchIn);
            split.addView(left, new LinearLayout.LayoutParams(0, -1, .85f));
            results.setPadding(ui.dp(10), ui.dp(4), ui.dp(18), ui.dockRoom());
            split.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1.25f));
            page.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            ui.settingsScreen.segmented(page, "Search in", new String[]{"Spotify", "On this device"}, catalogSearch ? 0 : 1, searchIn);
            LinearLayout.LayoutParams bp = ui.fill(ui.dp(50));
            bp.setMargins(ui.dp(18), 0, ui.dp(18), ui.dp(8));
            page.addView(box, bp);
            page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        searchScroll = scroll;
        searchResults = results;

        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                searchQuery = e.toString();
                scheduleSearch();
            }
        });
        field.setOnEditorActionListener((v, action, event) -> { ui.hideKeyboard(); return true; });
        renderSearch();
        return page;
    }

    /** Draws the results (or, with nothing typed, the recent searches and a note on what's searched). */
    private void renderSearch() {
        LinearLayout box = searchResults;
        if (box == null || !box.isAttachedToWindow() && box.getParent() == null) return;
        java.util.Map<Object, Integer> was = new java.util.HashMap<>();
        if (box.isLaidOut()) for (int i = 0; i < box.getChildCount(); i++) {
            View child = box.getChildAt(i);
            if (child.getTag() != null) was.put(child.getTag(), child.getTop());
        }
        box.removeAllViews();
        glide(box, was);
        String q = searchQuery.trim();
        if (q.isEmpty()) {
            List<String> recents = recentSearches();
            if (!recents.isEmpty()) {
                LinearLayout head = ui.row();
                TextView title = ui.text("Recent searches", 15, () -> ui.ink, true);
                head.addView(title, ui.weight());
                TextView clear = ui.pill("Clear", () -> { ui.prefs.edit().remove("recent_searches").apply(); renderSearch(); });
                clear.setTextSize(12);
                clear.setPadding(ui.dp(14), 0, ui.dp(14), 0);
                head.addView(clear, ui.lp(-2, ui.dp(32)));
                head.setPadding(0, ui.dp(6), 0, ui.dp(6));
                box.addView(head, ui.fill(-2));
                for (String recent : recents) {
                    LinearLayout line = ui.row();
                    line.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
                    line.addView(ui.icon("timer", () -> ui.inkDim), ui.lp(ui.dp(20), ui.dp(20)));
                    TextView label = ui.text(recent, 16, () -> ui.ink, false);
                    label.setPadding(ui.dp(14), ui.dp(10), ui.dp(8), ui.dp(10));
                    ui.oneLine(label);
                    line.addView(label, ui.weight());
                    ui.tappable(line, recent, .98f, () -> { searchField.setText(recent); searchField.setSelection(recent.length()); ui.hideKeyboard(); });
                    box.addView(line, ui.fill(-2));
                }
            }
            box.addView(moodBoard(), ui.fill(-2));
            TextView note = ui.text("Search Spotify’s catalog, or switch to On this device for your library and cached music.", 13, () -> ui.inkDim, false);
            note.setPadding(ui.dp(2), ui.dp(18), ui.dp(2), 0);
            box.addView(note, ui.fill(-2));
            return;
        }
        if (!catalogError.isEmpty()) {
            TextView retry = ui.pill("Couldn’t search Spotify · Retry", () -> runSearch(catalogPage, searchRequest));
            box.addView(retry, ui.fill(ui.dp(48)));
            return;
        }
        if (searchHits.length < HIT_FIELDS) {
            if (searchBusy) {
                box.addView(ui.controls.loader("Searching"), ui.fill(-2));
                return;
            }
            box.addView(ui.emptyState("search", "badge8", "Nothing found for “" + q + "”",
                catalogSearch ? "Try another title or artist." : "Searches your library and Spotify’s cached music on this device."), ui.fill(-2));
            return;
        }
        String section = "";
        MediaEntry best = new MediaEntry(searchHits[1], searchHits[2], searchHits[3], searchHits[4], PlaylistScreen.parseInt(searchHits[5]));
        box.addView(topResult(best, searchHits[0], PlaylistScreen.parseInt(searchHits[6])), ui.fill(-2));
        for (int i = HIT_FIELDS; i + HIT_FIELDS <= searchHits.length; i += HIT_FIELDS) {
            if (!searchHits[i].equals(section)) {
                section = searchHits[i];
                TextView header = ui.text(section, 13, () -> ui.accent, true);
                header.setLetterSpacing(.04f);
                header.setPadding(ui.dp(4), ui.dp(18), 0, ui.dp(4));
                header.setTag("section:" + section);
                box.addView(header, ui.fill(-2));
            }
            MediaEntry e = new MediaEntry(searchHits[i + 1], searchHits[i + 2], searchHits[i + 3], searchHits[i + 4], PlaylistScreen.parseInt(searchHits[i + 5]));
            Row row = ui.new Row();
            row.setTag("hit:" + e.uri);
            row.bind(e);
            if (isTrack(e.uri)) {
                int ms = PlaylistScreen.parseInt(searchHits[i + 6]);
                row.length.setText(ms > 0 ? SpatchUi.time(ms) : "");
                row.length.setVisibility(ms > 0 ? View.VISIBLE : View.GONE);
            }
            row.setContentDescription(e.title);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                ui.tick(v);
                rememberSearch(searchQuery);
                if (!isTrack(e.uri)) ui.playlistScreen.captureFlight(row.art, e.image, e.category == 2);
                ui.open(e);
            });
            row.setOnLongClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); searchActions(e); return true; });
            box.addView(row, ui.fill(-2));
        }
        if (!catalogPage.isEmpty()) box.addView(ui.pill(searchBusy ? "Loading…" : "Load more results", () -> {
            if (!searchBusy) runSearch(catalogPage, searchRequest);
        }), ui.fill(ui.dp(48)));
    }

    /**
     * Results glide instead of jumping: once the new children are laid out (before they draw), each one that was there
     * before slides from its old place to its new one, and each new one fades in a beat after the one above.
     */
    private void glide(LinearLayout box, java.util.Map<Object, Integer> was) {
        if (!ui.motion()) return;
        box.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                box.getViewTreeObserver().removeOnPreDrawListener(this);
                int fresh = 0;
                for (int i = 0; i < box.getChildCount(); i++) {
                    View child = box.getChildAt(i);
                    Integer top = child.getTag() == null ? null : was.get(child.getTag());
                    child.animate().cancel();
                    if (top != null) {
                        child.setTranslationY(top - child.getTop());
                        child.animate().translationY(0f).setDuration(260).setInterpolator(SpatchUi.EMPHASIZED).start();
                    } else if (fresh < 10) {
                        child.setAlpha(0f);
                        child.setTranslationY(ui.dp(8));
                        child.animate().alpha(1f).translationY(0f).setStartDelay(fresh++ * 30L).setDuration(240).setInterpolator(SpatchUi.EMPHASIZED).start();
                    }
                }
                return true;
            }
        });
    }

    private String topUri = "";

    /**
     * The best match as a bigger card over its own blurred cover (blurred on the GPU), with the cover itself and its
     * name in front. When a new best match arrives the backdrop settles in from a soft zoom.
     */
    private View topResult(MediaEntry e, String kind, int ms) {
        FrameLayout card = new FrameLayout(ui.activity);
        card.setTag("top");
        ui.bgRound(card, 24, () -> ui.surfHi);
        card.setClipToOutline(true);
        Art backdrop = ui.artwork.new Art(Art.WIDE, false);
        backdrop.bind(e.image);
        if (GpuFx.blur(ui.prefs)) backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(ui.dp(26), ui.dp(26), android.graphics.Shader.TileMode.CLAMP));
        card.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(ui.activity);
        dim.setBackgroundColor((ui.bg & 0x00ffffff) | 0x8c000000);
        card.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout front = ui.row();
        front.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        Art cover = ui.artwork.new Art(e.category == 2 ? Art.CIRCLE : Art.ROUNDED, false);
        cover.letter = SpatchUi.initial(e.title);
        cover.bind(e.image);
        cover.setElevation(ui.dp(6));
        front.addView(cover, ui.lp(ui.dp(104), ui.dp(104)));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(16), 0, 0, 0);
        TextView label = ui.text("Top result · " + (kind.endsWith("s") ? kind.substring(0, kind.length() - 1) : kind), 12, () -> ui.accent, true);
        TextView name = ui.text(e.title, 24, () -> ui.ink, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView sub = ui.text(e.subtitle, 14, () -> ui.inkDim, false);
        ui.oneLine(sub);
        words.addView(label);
        words.addView(name);
        words.addView(sub);
        front.addView(words, ui.weight());
        if (isTrack(e.uri) && ms > 0) front.addView(ui.text(SpatchUi.time(ms), 13, () -> ui.inkDim, false));
        card.addView(front, new FrameLayout.LayoutParams(-1, -2));
        ui.tappable(card, e.title, .98f, () -> {
            rememberSearch(searchQuery);
            if (!isTrack(e.uri)) ui.playlistScreen.captureFlight(cover, e.image, e.category == 2);
            ui.open(e);
        });
        card.setOnLongClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); searchActions(e); return true; });
        if (!e.uri.equals(topUri) && ui.motion()) {
            backdrop.setScaleX(1.18f);
            backdrop.setScaleY(1.18f);
            backdrop.setAlpha(0f);
            backdrop.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(700).setInterpolator(SpatchUi.EMPHASIZED).start();
        }
        topUri = e.uri;
        ViewGroup.MarginLayoutParams spacing = new ViewGroup.MarginLayoutParams(-1, -2);
        spacing.setMargins(0, ui.dp(6), 0, ui.dp(4));
        card.setLayoutParams(spacing);
        return card;
    }

    private static final String[] MOODS = {"Chill", "Pop", "Focus", "Hip-hop", "Workout", "Indie", "Sleep", "Rock", "Party", "Lo-fi", "Mood booster", "Jazz"};
    private static final int[] MOOD_HUES = {200, 330, 160, 30, 0, 270, 230, 15, 300, 180, 50, 25};

    /**
     * With nothing typed: genre and mood shortcuts (each runs that search) in a loose two-column collage, tilted a
     * little like cards pinned to a board, drifting very slightly for the first few seconds before coming to rest.
     */
    private View moodBoard() {
        LinearLayout board = ui.row();
        board.setGravity(Gravity.TOP);
        board.setPadding(0, ui.dp(14), 0, ui.dp(4));
        board.setClipChildren(false);
        board.setClipToPadding(false);
        board.setTag("moods");
        LinearLayout[] columns = {ui.column(), ui.column()};
        for (LinearLayout column : columns) { column.setClipChildren(false); board.addView(column, ui.weight()); }
        java.util.Random seeded = new java.util.Random(7);
        for (int i = 0; i < MOODS.length; i++) {
            String mood = MOODS[i];
            int hue = MOOD_HUES[i];
            TextView tile = ui.text(mood, 17, () -> ui.ink, true);
            tile.setGravity(Gravity.BOTTOM | Gravity.START);
            tile.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            android.graphics.drawable.GradientDrawable bg = ui.bgRound(tile, 20, () -> SpatchUi.blend(ui.surfHi, android.graphics.Color.HSVToColor(new float[]{hue, .55f, ui.dark ? .55f : .85f}), .6f));
            tile.setElevation(ui.dp(2));
            float tilt = (seeded.nextFloat() - .5f) * 5f;
            tile.setRotation(tilt);
            LinearLayout.LayoutParams at = ui.fill(ui.dp(i % 3 == 0 ? 104 : i % 3 == 1 ? 76 : 90));
            at.setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
            ui.tappable(tile, "Search " + mood, .96f, () -> { searchField.setText(mood); searchField.setSelection(mood.length()); ui.hideKeyboard(); });
            columns[i % 2].addView(tile, at);
            if (ui.motion()) {
                // A slight drift, a few seconds long, then still: ambient motion shouldn't keep the screen redrawing.
                android.animation.ObjectAnimator drift = android.animation.ObjectAnimator.ofPropertyValuesHolder(tile,
                    android.animation.PropertyValuesHolder.ofFloat("translationY", 0f, ui.dp(seeded.nextBoolean() ? 3 : -3), 0f),
                    android.animation.PropertyValuesHolder.ofFloat("rotation", tilt, tilt + (seeded.nextFloat() - .5f) * 1.6f, tilt));
                drift.setDuration(3600 + seeded.nextInt(1600));
                drift.setRepeatCount(2);
                drift.setStartDelay(seeded.nextInt(600));
                drift.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                drift.start();
                tile.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) {}
                    @Override public void onViewDetachedFromWindow(View v) { drift.cancel(); }
                });
            }
        }
        return board;
    }

    /** Long-press on a result: songs and episodes can play or queue; lists, albums and artists get the library menu. */
    static boolean isTrack(String uri) {
        return uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:") || uri.startsWith("spotify:preview:");
    }

    void searchActions(MediaEntry e) {
        if (!e.uri.startsWith("spotify:track:") && !e.uri.startsWith("spotify:episode:") && !e.uri.startsWith("spotify:preview:")) { ui.actions(e); return; }
        ui.sheet(e.title, e.subtitle, new Option[]{new Option("Play", "", "", "play", false), new Option("Play next", "", "", "next", false), new Option("Add to queue", "", "", "queue", false), new Option("Add to playlist", "", "", "add", false), ui.downloadsScreen.songDownloadOption(e)}, c -> {
            if (c == 0) ui.command(PlayerScreen.PLAY, 0, e.uri);
            else if (c == 1) { ui.command(PlayerScreen.QUEUE_NEXT, 0, e.uri); Toast.makeText(ui.activity, "Playing next", Toast.LENGTH_SHORT).show(); }
            else if (c == 3) ui.addToPlaylist(e);
            else if (c == 4) ui.downloadsScreen.downloadTapped(e);
            else { ui.command(PlayerScreen.QUEUE_ADD, 0, e.uri); Toast.makeText(ui.activity, "Added to queue", Toast.LENGTH_SHORT).show(); }
        });
    }
}
