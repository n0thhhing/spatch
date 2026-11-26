package spatch;

import spatch.ArtworkViews.Art;
import spatch.SpatchUi.Option;
import spatch.SpatchUi.Row;
import static spatch.PackedStrings.packStrings;
import static spatch.PackedStrings.unpackStrings;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Downloads screen and the per-track download state shown across the app. */
final class Downloads {
    private final SpatchUi ui;
    Downloads(SpatchUi ui) { this.ui = ui; }

    static final int DL_ERROR = 1, DL_REQUESTED = 2, DL_DONE = 3, DL_BUSY = 4, DL_WAITING = 5;
    /** Downloaded or on its way, by uri: {state, percent 0 to 100}. */
    final java.util.Map<String, int[]> downloads = new java.util.HashMap<>();
    final java.util.Map<String, MediaEntry> downloadDetails = new java.util.LinkedHashMap<>();
    long downloadRevision, downloadSetRevision;
    boolean downloadsStarted;
    View downloadButton;
    private LinearLayout downloadsList, downloadsProgress;
    private TextView downloadsSummary, downloadsStorage, downloadsProgressText;
    private Meter downloadsMeter;
    private View removeAllDownloads;
    private final java.util.Map<String, Object[]> downloadRows = new java.util.HashMap<>();
    private long storageFree, storageTotal;

    int downloadState(String uri) {
        int[] d = uri == null ? null : downloads.get(uri);
        return d == null ? 0 : d[0];
    }

    void pullDownloads() {
        String[] f = unpackStrings(SpatchUi.nativeDownloads());
        downloads.clear();
        if (f != null) for (int i = 0; i + 2 < f.length; i += 3) downloads.put(f[i], new int[]{PlaylistScreen.parseInt(f[i + 1]), PlaylistScreen.parseInt(f[i + 2])});
        downloadDetails.clear();
        for (MediaEntry e : ui.entryList(unpackStrings(SpatchUi.nativeCached("download-items")))) if (downloads.containsKey(e.uri)) downloadDetails.put(e.uri, e);
        for (String item : downloads.keySet()) if (SearchScreen.isTrack(item) && !downloadDetails.containsKey(item)) {
            downloadDetails.put(item, new MediaEntry(kindName(item), "", item, "", -1));
        }
    }

    /** Something changed in the core's downloads: progress only, or a download added, removed or finished. */
    void onDownloadsChanged(boolean setChanged) {
        if (setChanged) for (Row row : ui.attachedRows) row.updateDownloadBadge();
        if (downloadButton != null && downloadButton.isAttachedToWindow()) downloadButton.invalidate();
        if (downloadsList != null && downloadsList.isAttachedToWindow()) {
            if (setChanged) { renderDownloads(); loadStorage(); } else updateDownloadProgress();
        }
        if (setChanged && ui.tab != SpatchUi.SETTINGS && ui.tab != SpatchUi.SEARCH && ui.playlist == null && ui.sheetClose == null && !ui.playerOpen
                && !(ui.findFocus() instanceof android.widget.EditText)) ui.showPageKeepingPlace();
    }

    /** A thin progress bar. */
    private final class Meter extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value;
        Meter() { super(ui.activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void set(float v) {
            float next = Math.max(0f, Math.min(1f, v));
            if (next == value) return;
            value = next;
            invalidate();
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            paint.setColor((ui.ink & 0x00ffffff) | 0x24000000);
            c.drawRoundRect(0, 0, w, h, r, r, paint);
            paint.setColor(ui.accent);
            c.drawRoundRect(0, 0, Math.max(h, w * value), h, r, r, paint);
        }
    }

    /** The small round badge on a cover that's downloaded (accent) or downloading (dim). */
    View downloadChip(int state) {
        FrameLayout chip = new FrameLayout(ui.activity);
        boolean done = state == DL_DONE;
        ui.bgRound(chip, 13, () -> done ? ui.accent : (ui.bg & 0x00ffffff) | 0xd0000000);
        chip.addView(ui.icon("download", () -> done ? ui.onAccent : ui.ink), new FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER));
        return chip;
    }

    String downloadLabel(String uri) {
        int state = downloadState(uri);
        if (state == DL_DONE) return "Remove download";
        if (state == DL_BUSY || state == DL_REQUESTED || state == DL_WAITING) return "Cancel download";
        return state == DL_ERROR ? "Retry download" : uri.startsWith("spotify:episode:") ? "Download episode" : "Download song";
    }

    Option songDownloadOption(MediaEntry song) {
        int[] state = downloads.get(song.uri);
        String note = state == null ? "Listen offline on this device" : downloadStatus(state);
        return new Option(downloadLabel(song.uri), note, "", "download", state != null && state[0] == DL_DONE);
    }

    void downloadTapped(String uri, String name) {
        downloadTapped(new MediaEntry(name, "", uri, "", -1));
    }

    void downloadTapped(MediaEntry entry) {
        String uri = entry.uri, name = entry.title.isEmpty() ? kindName(uri) : entry.title;
        int state = downloadState(uri);
        if (state == 0 || state == DL_ERROR) {
            ui.submit(SpatchUi.PLAYLISTS, () -> {
                String error = SpatchUi.nativeDownload(uri, 0, packStrings(new String[]{name, entry.subtitle, uri, entry.image, Integer.toString(entry.category)}));
                ui.post(() -> {
                    if (ui.closed) return;
                    if (error != null && error.isEmpty()) { pullDownloads(); onDownloadsChanged(true); }
                    ui.toast(error != null && error.isEmpty() ? "Downloading “" + name + "”" : error == null ? "No reply from Spotify" : error,
                        !(error != null && error.isEmpty()));
                });
            });
            return;
        }
        confirmRemoveDownload(uri, name, state == DL_DONE);
    }

    private void confirmRemoveDownload(String uri, String name, boolean done) {
        ui.sheet(name, done ? "Removes this download from this phone. It stays in your library." : "Stops the download and removes what’s been saved so far.",
            new Option[]{new Option(done ? "Remove download" : "Cancel download", "", "", "close", false)}, c -> ui.submit(SpatchUi.PLAYLISTS, () -> {
                String error = SpatchUi.nativeDownload(uri, 1, null);
                ui.post(() -> ui.toast(error == null || error.isEmpty() ? (done ? "Removed download" : "Download cancelled") : error, true));
            }));
    }

    private static String bytes(long b) {
        if (b >= 1_000_000_000L) return String.format(java.util.Locale.ROOT, "%.1f GB", b / 1e9);
        if (b >= 1_000_000L) return Math.round(b / 1e6) + " MB";
        return Math.max(0, Math.round(b / 1e3)) + " KB";
    }

    private static String timeLeft(long seconds) {
        if (seconds <= 0) return "";
        return seconds < 60 ? "under a minute left" : ((seconds + 59) / 60) + " min left";
    }

    private static String kindName(String uri) {
        return uri.startsWith("spotify:track:") ? "Song" : uri.startsWith("spotify:episode:") ? "Episode" : uri.contains(":album:") ? "Album" : uri.contains(":show:") ? "Podcast" : "Playlist";
    }

    void downloadsCard(LinearLayout box) {
        downloadsSummary = ui.text("", 16, () -> ui.ink, true);
        downloadsSummary.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(2));
        box.addView(downloadsSummary);
        downloadsStorage = ui.text("", 13, () -> ui.inkDim, false);
        downloadsStorage.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(8));
        box.addView(downloadsStorage);

        downloadsProgress = ui.column();
        downloadsProgress.setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(12));
        downloadsProgressText = ui.text("", 13, () -> ui.ink, false);
        downloadsProgress.addView(downloadsProgressText);
        downloadsMeter = new Meter();
        LinearLayout.LayoutParams mp = ui.fill(ui.dp(4));
        mp.topMargin = ui.dp(8);
        downloadsProgress.addView(downloadsMeter, mp);
        downloadsProgress.setVisibility(View.GONE);
        box.addView(downloadsProgress, ui.fill(-2));

        downloadsList = ui.column();
        box.addView(downloadsList, ui.fill(-2));

        ui.settingsScreen.ensureSpotifySettings();
        if (ui.settingsScreen.spotifySettings != null) {
            int quality = ui.settingsScreen.spotifyValue("download_quality");
            ui.settingsScreen.segmented(box, "Download quality", new String[]{"Low", "Normal", "High", "Very high", "Lossless"}, quality >= 1 && quality <= 5 ? quality - 1 : -1, i -> ui.settingsScreen.setSpotify("download_quality", i + 1));
        }

        if (ui.settingsScreen.spotifySettings != null) ui.settingsScreen.offlineModeRow(box);

        TextView clear = ui.pill("Remove all downloads", () -> ui.sheet("Remove all downloads?", "Every downloaded song, episode, playlist and album is removed from this phone. Your library stays as it is.",
            new Option[]{new Option("Remove all downloads", "", "", "close", false)}, c -> ui.submit(SpatchUi.PLAYLISTS, () -> {
                String error = SpatchUi.nativeDownload("", 2, null);
                ui.post(() -> ui.toast(error == null || error.isEmpty() ? "Removed all downloads" : error, true));
            })));
        clear.setTextColor(ui.ink);
        GradientDrawable clearBg = ui.round(ui.surfHi, 22);
        clear.setBackground(clearBg);
        ui.colored(clear, () -> { clear.setTextColor(ui.ink); clearBg.setColor(ui.surfHi); });
        LinearLayout.LayoutParams cp = ui.fill(ui.dp(46));
        cp.setMargins(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(14));
        box.addView(clear, cp);
        removeAllDownloads = clear;

        renderDownloads();
        loadStorage();
    }

    /** Android's filesystem API guarantees bytes for this app's storage volume. */
    private void loadStorage() {
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            try {
                android.os.StatFs fs = new android.os.StatFs(ui.activity.getFilesDir().getPath());
                long free = fs.getAvailableBytes(), total = fs.getTotalBytes();
                ui.post(() -> {
                    storageFree = free;
                    storageTotal = total;
                    updateDownloadSummary();
                });
            } catch (RuntimeException unavailable) {
                android.util.Log.w("SpatchUi", "Storage statistics unavailable", unavailable);
            }
        });
    }

    private void updateDownloadSummary() {
        if (downloadsSummary == null) return;
        int n = downloads.size();
        downloadsSummary.setText(n == 0 ? "Nothing downloaded" : n + (n == 1 ? " download" : " downloads"));
        downloadsStorage.setText(storageTotal > 0 ? bytes(storageFree) + " free of " + bytes(storageTotal) + " on this phone" : "");
        downloadsStorage.setVisibility(storageTotal > 0 ? View.VISIBLE : View.GONE);
    }

    private String downloadStatus(int[] d) {
        switch (d[0]) {
            case DL_DONE: return "Downloaded";
            case DL_BUSY: return d[1] > 0 ? "Downloading • " + d[1] + "%" : "Downloading…";
            case DL_ERROR: return "Couldn’t download • tap to retry";
            default: return "Waiting to download";
        }
    }

    /** Rebuilds the list of downloads in Settings. */
    private void renderDownloads() {
        LinearLayout list = downloadsList;
        if (list == null) return;
        list.removeAllViews();
        downloadRows.clear();
        java.util.Map<String, MediaEntry> known = new java.util.HashMap<>();
        known.putAll(downloadDetails);
        for (MediaEntry e : ui.entries(-1, "", false, 400)) known.put(e.uri, e);
        List<String> order = new ArrayList<>(downloads.keySet());
        java.util.Collections.sort(order, (a, b) -> {
            int sa = downloads.get(a)[0] == DL_DONE ? 1 : 0, sb = downloads.get(b)[0] == DL_DONE ? 1 : 0;
            if (sa != sb) return sa - sb; // what's still downloading first
            MediaEntry ea = known.get(a), eb = known.get(b);
            return (ea == null ? kindName(a) : ea.title).compareToIgnoreCase(eb == null ? kindName(b) : eb.title);
        });
        if (order.isEmpty()) {
            TextView none = ui.text("Hold a song or episode and choose Download, or use the download button on a playlist, album or podcast.", 13, () -> ui.inkDim, false);
            none.setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(12));
            list.addView(none, ui.fill(-2));
        }
        for (String uri : order) {
            MediaEntry known1 = known.get(uri);
            String name = known1 != null ? known1.title : kindName(uri);
            MediaEntry entry = known1 != null ? known1 : new MediaEntry(name, "", uri, "", SearchScreen.isTrack(uri) ? -1 : uri.contains(":album:") ? 1 : uri.contains(":show:") ? 3 : 0);
            int[] d = downloads.get(uri);
            LinearLayout line = ui.row();
            line.setPadding(ui.dp(14), ui.dp(8), ui.dp(6), ui.dp(8));
            Art art = ui.artwork.new Art(Art.ROUNDED, false);
            art.letter = SpatchUi.initial(name);
            art.heart = SpatchUi.liked(uri) && entry.image.isEmpty();
            art.bind(entry.image);
            line.addView(art, ui.lp(ui.dp(46), ui.dp(46)));
            LinearLayout words = ui.column();
            words.setPadding(ui.dp(12), 0, ui.dp(6), 0);
            TextView title = ui.text(name, 15, () -> ui.ink, true);
            ui.oneLine(title);
            words.addView(title);
            TextView status = ui.text(downloadStatus(d), 13, () -> d[0] == DL_ERROR ? ui.accent2 : ui.inkDim, false);
            ui.oneLine(status);
            words.addView(status);
            Meter meter = new Meter();
            meter.set(d[1] / 100f);
            meter.setVisibility(d[0] == DL_BUSY ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams mp = ui.fill(ui.dp(3));
            mp.topMargin = ui.dp(6);
            words.addView(meter, mp);
            line.addView(words, ui.weight());
            line.addView(ui.iconButton(ui.icon("close", () -> ui.inkDim), "Remove download", () -> confirmRemoveDownload(uri, name, d[0] == DL_DONE)), ui.lp(ui.dp(40), ui.dp(40)));
            line.setClickable(true);
            line.setOnClickListener(v -> {
                if (d[0] == DL_ERROR) ui.submit(SpatchUi.PLAYLISTS, () -> SpatchUi.nativeDownload(uri, 0, null));
                else ui.open(entry);
            });
            list.addView(line, ui.fill(-2));
            downloadRows.put(uri, new Object[]{status, meter});
        }
        if (removeAllDownloads != null) removeAllDownloads.setVisibility(order.isEmpty() ? View.GONE : View.VISIBLE);
        updateDownloadSummary();
        updateDownloadProgress();
    }

    /** Refreshes the progress lines in place while downloads move, without rebuilding the list. */
    private void updateDownloadProgress() {
        if (downloadsProgress == null) return;
        String[] t = SpatchUi.nativeDownloadTotals();
        boolean busy = t != null && t.length >= 9 && "true".equals(t[4]);
        downloadsProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            String left = timeLeft(PlaylistScreen.parseInt(t[6]));
            downloadsProgressText.setText("Downloading • " + t[0] + " of " + t[1] + " songs" + (left.isEmpty() ? "" : " • " + left));
            downloadsMeter.set(PlaylistScreen.parseInt(t[5]) / 100f);
        }
        for (java.util.Map.Entry<String, Object[]> row : downloadRows.entrySet()) {
            int[] d = downloads.get(row.getKey());
            if (d == null) continue;
            ((TextView) row.getValue()[0]).setText(downloadStatus(d));
            ((Meter) row.getValue()[1]).set(d[1] / 100f);
        }
    }
}
