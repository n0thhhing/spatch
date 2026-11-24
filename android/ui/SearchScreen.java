package spatch;

import spatch.SettingsScreen.Pick;
import spatch.SpatchUi.Option;
import spatch.SpatchUi.Row;
import static spatch.PackedStrings.unpackStrings;

import android.view.Gravity;
import android.view.View;
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
        searchHits = new String[0];
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
        box.removeAllViews();
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
        for (int i = 0; i + HIT_FIELDS <= searchHits.length; i += HIT_FIELDS) {
            if (!searchHits[i].equals(section)) {
                section = searchHits[i];
                TextView header = ui.text(section, 13, () -> ui.accent, true);
                header.setLetterSpacing(.04f);
                header.setPadding(ui.dp(4), ui.dp(i == 0 ? 6 : 18), 0, ui.dp(4));
                box.addView(header, ui.fill(-2));
            }
            MediaEntry e = new MediaEntry(searchHits[i + 1], searchHits[i + 2], searchHits[i + 3], searchHits[i + 4], PlaylistScreen.parseInt(searchHits[i + 5]));
            Row row = ui.new Row();
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
