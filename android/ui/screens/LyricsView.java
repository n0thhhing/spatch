package spatch;

import spatch.ArtworkViews.Backdrop;

import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The player's three-line lyrics block, the full lyrics panel, and word-timed highlight walking. */
final class LyricsView {
    private final SpatchUi ui;
    LyricsView(SpatchUi ui) { this.ui = ui; }

    /** The optional one-line lyric under the player's cover, and the lyrics it follows. */
    private LinearLayout pLyric;
    private String lyricsUri = "", lyricKey = "";
    private boolean lyricShown;
    private Lyrics lyricsData;

    FrameLayout panel;
    private Backdrop lyricsBackdrop;

    void attachPlayerBlock(LinearLayout col, boolean land) {
        pLyric = null;
        if (!ui.pref("player_lyrics", false) || land) return;
        // Three quiet lines (the one before, the sung one, the one after), always three lines tall so nothing
        // shifts when they fill in or empty; tap for all of it.
        LinearLayout box = pLyric = ui.column();
        TextView[] rows = new TextView[3];
        for (int k = 0; k < 3; k++) {
            final boolean sung = k == 1;
            // Left-aligned with the title below it, so the lyrics read as part of the same column; the sung line is bright and glows.
            rows[k] = ui.text("", sung ? 22 : 15, () -> ui.ink, true);
            rows[k].setLineSpacing(0, 1.05f);
            rows[k].setGravity(Gravity.START);
            rows[k].setSingleLine(true);
            rows[k].setEllipsize(null);
            rows[k].setHorizontallyScrolling(true);
            rows[k].setHorizontalFadingEdgeEnabled(true);
            rows[k].setFadingEdgeLength(ui.dp(24));
            rows[k].setPadding(0, ui.dp(4), 0, ui.dp(2));
            rows[k].setAlpha(sung ? 1f : .38f);
            box.addView(rows[k], ui.fill(-2));
        }
        // How many lines show is the user's call (default two: the sung line and what comes next); the rest are hidden, not removed.
        int shownLines = Math.max(1, Math.min(3, ui.prefs.getInt("player_lyric_lines", 1) + 1));
        rows[0].setVisibility(shownLines >= 3 ? View.VISIBLE : View.GONE);
        rows[2].setVisibility(shownLines >= 2 ? View.VISIBLE : View.GONE);
        box.setTag(rows);
        // Its rows slide inside it; the card's outline clips them (the column around it no longer clips, for the
        // visualizer glow), and rows aren't clipped one by one so the sung word's glow can spill into the padding.
        box.setClipChildren(false);
        box.setClipToOutline(true);
        // The lines sit on a quiet glass card; left padding is inset 10 dp because LeadingMarginSpan adds 10 dp inside
        // each TextView's own clip rect so the leftmost sung word's glow never clips on the left.
        box.setPadding(ui.dp(10), ui.dp(10), ui.dp(14), ui.dp(10));
        ui.bgRound(box, 26, () -> (ui.ink & 0x00ffffff) | 0x16000000);
        ui.tappable(box, "Open lyrics", () -> ui.playerScreen.openLyrics());
        LinearLayout.LayoutParams lineParams = ui.lp(-1, 0); // collapsed until the song's lyrics are about to start
        // No margins while collapsed: a song without lyrics must not move anything (lyricBlock grows them with the height).
        box.setAlpha(0f);
        col.addView(box, lineParams);
        lyricShown = false;
        lyricsUri = "";
        lyricKey = "";
        lyricsData = null;
        playerLyrics(box);
    }

    void clearPlayerBlock() {
        pLyric = null;
    }

    /** Opens or closes the player's lyric block: the cover area gives up its height smoothly and takes it back after. */
    private void lyricBlock(LinearLayout box, boolean open) {
        box.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, ui.getWidth() - ui.dp(36)), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED);
        int full = box.getMeasuredHeight();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) box.getLayoutParams();
        int from = params.height < 0 ? full : params.height, to = open ? full : 0;
        if (!ui.motion()) {
            params.height = open ? -2 : 0;
            params.topMargin = open ? ui.dp(12) : 0;
            params.bottomMargin = open ? ui.dp(20) : 0;
            box.setLayoutParams(params);
            box.setAlpha(open ? 1f : 0f);
            return;
        }
        android.animation.ValueAnimator grow = android.animation.ValueAnimator.ofInt(from, to);
        grow.setDuration(420);
        grow.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f));
        grow.addUpdateListener(a -> {
            params.height = (int) a.getAnimatedValue();
            float shown = full == 0 ? 0 : Math.min(1f, params.height / (float) full);
            params.topMargin = Math.round(ui.dp(12) * shown);
            params.bottomMargin = Math.round(ui.dp(20) * shown);
            box.setLayoutParams(params);
        });
        grow.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator done) {
                params.topMargin = open ? ui.dp(12) : 0;
                params.bottomMargin = open ? ui.dp(20) : 0;
                if (open) params.height = -2;
                box.setLayoutParams(params);
            }
        });
        grow.start();
        box.animate().alpha(open ? 1f : 0f).setDuration(open ? 420 : 260).start();
    }

    /** Keeps the player's three lyric lines on the line being sung: fetches the song's lyrics once, then follows the playhead. */
    private CharSequence lyricText(String text) {
        if (text == null || text.isEmpty()) return "";
        android.text.SpannableString s = new android.text.SpannableString(text);
        s.setSpan(new android.text.style.LeadingMarginSpan.Standard(ui.dp(10)), 0, text.length(), android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return s;
    }

    private void playerLyrics(LinearLayout box) {
        if (ui.player == null || pLyric != box) return;
        TextView[] rows = (TextView[]) box.getTag();
        if (!ui.uri.equals(lyricsUri)) {
            String forUri = lyricsUri = ui.uri;
            lyricsData = null;
            if (!forUri.isEmpty()) {
                String track = forUri.substring(forUri.lastIndexOf(':') + 1), songTitle = ui.title, songArtist = ui.artist;
                long songLength = ui.duration;
                ui.submit(SpatchUi.ART, () -> {
                    Lyrics got = null;
                    try { got = CoreBridge.lyrics(track, songTitle, songArtist, songLength); } catch (Throwable none) { /* no lyrics, or offline: the lines just stay empty */ }
                    Lyrics found = got;
                    ui.post(() -> { if (forUri.equals(lyricsUri)) lyricsData = found; });
                });
            }
        }
        String[] want = { "", "", "" };
        Lyrics l = lyricsData;
        boolean visible = false;
        int current = -1;
        long now = ui.playhead();
        if (l != null && l.words.length > 0 && l.starts[l.starts.length - 1] > 0) {
            long last = l.starts.length - 1;
            long over = (l.ends[(int) last] > l.starts[(int) last] ? l.ends[(int) last] : l.starts[(int) last] + 6000) + 1500;
            visible = now >= l.starts[0] - 1500 && now <= over; // the block lives only while there is something to sing
            for (int i = 0; i < l.starts.length; i++) if (l.starts[i] > 0 && l.starts[i] <= now) current = i;
            if (current > 0) want[0] = l.words[current - 1];
            if (current >= 0) want[1] = l.words[current];
            if (current + 1 < l.words.length) want[2] = l.words[current + 1];
        }
        if (visible != lyricShown) {
            lyricShown = visible;
            lyricBlock(box, visible);
        }
        String key = want[0] + '\n' + want[1] + '\n' + want[2];
        boolean walking = visible && current >= 0 && l.wordAt[current] != null;
        if (visible && !key.equals(lyricKey)) {
            lyricKey = key;
            for (int i = 0; i < 3; i++) { rows[i].setText(lyricText(want[i])); rows[i].scrollTo(0, 0); }
            float[] from = { 1f, .38f, 0f }, to = { .38f, 1f, .38f };
            if (ui.motion() && !want[1].isEmpty()) {
                // The lines step up: each fades to the look of the one above it while the block slides up into place.
                // Moved row by row inside the box (which clips its children), so nothing slides over the title below.
                for (int i = 0; i < 3; i++) {
                    rows[i].setAlpha(from[i]);
                    rows[i].setTranslationY(ui.dp(24));
                    rows[i].animate().alpha(to[i]).translationY(0).setDuration(520).setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start();
                }
            } else {
                for (int i = 0; i < 3; i++) { rows[i].setAlpha(to[i]); rows[i].setTranslationY(0); }
            }
        }
        long next = walking ? walkWords(rows[1], l, current, now, rows[1].getCurrentTextColor()) : 250;
        if (visible && current >= 0 && followLyricScroll(rows[1], l, current, now, walking)) next = Math.min(next, 16);
        if (!ui.playing || box.getWindowVisibility() != View.VISIBLE || !box.isShown()) next = 250;
        box.postDelayed(() -> playerLyrics(box), next);
    }

    /** Keeps a long single-line lyric smoothly scrolled to the active word (or playhead fraction) without ellipsizing it. */
    private boolean followLyricScroll(TextView view, Lyrics l, int line, long now, boolean walking) {
        android.text.Layout layout = view.getLayout();
        if (layout == null || layout.getLineCount() == 0) return false;
        int avail = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        if (avail <= 0) return false;
        int maxScroll = Math.max(0, Math.round(layout.getLineWidth(0) + ui.dp(10) - avail));
        int targetX = 0;
        if (maxScroll > 0) {
            if (walking && l.wordAt[line] != null && l.wordStart[line] != null) {
                int[] at = l.wordAt[line];
                long[] when = l.wordStart[line];
                String text = l.words[line] == null ? "" : l.words[line];
                int k = -1;
                for (int i = 0; i < at.length && i < when.length && when[i] <= now; i++) k = i;
                if (k >= 0) {
                    int s = Math.min(at[k], text.length());
                    int e = k + 1 < at.length ? Math.min(at[k + 1], text.length()) : text.length();
                    float mid = (layout.getPrimaryHorizontal(s) + layout.getPrimaryHorizontal(e)) * .5f;
                    targetX = Math.max(0, Math.min(maxScroll, Math.round(mid - avail * .42f)));
                }
            } else {
                long start = l.starts[line];
                long nextStart = line + 1 < l.starts.length && l.starts[line + 1] > start ? l.starts[line + 1] : 0;
                long end = l.ends[line] > start ? l.ends[line] : (nextStart > 0 ? nextStart : start + 4000);
                long span = Math.max(800L, Math.min(end - start, 8000L));
                float frac = Math.max(0f, Math.min(1f, ((now - start) / (float) span - .15f) / .70f));
                targetX = Math.round(maxScroll * frac);
            }
        }
        int cur = view.getScrollX();
        int nextX = !ui.motion() || Math.abs(targetX - cur) <= 1 ? targetX : cur + Math.round((targetX - cur) * .18f);
        if (nextX != cur) view.scrollTo(nextX, 0);
        return Math.abs(targetX - nextX) > 1 || (maxScroll > 0 && !walking && ui.playing);
    }

    /**
     * The word being sung: raised a couple of pixels and glowing in the accent. Only how it's drawn changes (no new
     * metrics), so moving it repaints the line without laying it out again.
     */
    static final class SungWord extends android.text.style.CharacterStyle implements android.text.style.UpdateAppearance {
        float lift, glow;
        int accent;
        @Override public void updateDrawState(android.text.TextPaint tp) {
            tp.baselineShift -= Math.round(lift);
            if (glow > .5f) tp.setShadowLayer(glow, 0, 0, accent);
        }
    }

    /** Per lyric line view: the spans of the word arriving and the word leaving, and which word they were built for. */
    private static final class Walk {
        final SungWord arriving = new SungWord(), leaving = new SungWord();
        int line = -1, word = -2;
        long leftAt;
        float leftLift, leftGlow; // where the leaving word was when the next one began
    }

    private final java.util.WeakHashMap<TextView, Walk> walks = new java.util.WeakHashMap<>();

    /**
     * Walks the highlight along a word-timed line: words already sung are full ink, the rest dimmed, the word being sung
     * lifts and glows (a long held note's glow swells while it lasts) and the word just left settles back. Returns how
     * soon to look again: every frame while something moves, else 40 ms.
     */
    long walkWords(TextView view, Lyrics l, int line, long now, int ink) {
        Walk w = walks.computeIfAbsent(view, v -> new Walk());
        int[] at = l.wordAt[line];
        long[] when = l.wordStart[line];
        String text = l.words[line] == null ? "" : l.words[line];
        int k = -1;
        for (int i = 0; i < at.length && i < when.length && when[i] <= now; i++) k = i;
        boolean wordChanged = w.line != line || w.word != k;
        if (wordChanged) {
            int before = w.line == line ? w.word : -1;
            w.line = line;
            w.word = k;
            w.leftAt = SystemClock.uptimeMillis();
            android.text.SpannableString s = new android.text.SpannableString(text);
            if (!text.isEmpty()) s.setSpan(new android.text.style.LeadingMarginSpan.Standard(ui.dp(10)), 0, text.length(), android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            int sung = k < 0 ? 0 : k + 1 < at.length ? Math.min(at[k + 1], text.length()) : text.length();
            if (sung < text.length()) s.setSpan(new android.text.style.ForegroundColorSpan((ink & 0x00ffffff) | 0x5c000000), sung, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (k >= 0 && at[k] < sung) s.setSpan(w.arriving, at[k], sung, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            boolean settle = before >= 0 && before < k && at[before] < at[k];
            if (settle) s.setSpan(w.leaving, at[before], at[before + 1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            w.leftLift = settle ? w.arriving.lift : 0;
            w.leftGlow = settle ? w.arriving.glow : 0;
            w.arriving.lift = w.arriving.glow = 0;
            view.setText(s);
        }
        boolean motion = ui.motion();
        float rise = ui.dp(2.5f), shine = ui.dp(4); // at most 8 dp of glow: inside a lyric line's padding
        long since = k < 0 ? 0 : now - when[k];
        long wordEnd = 0;
        if (k >= 0) {
            if (k + 1 < when.length) {
                wordEnd = when[k + 1];
            } else {
                long rawEnd = l.ends[line] > when[k] ? l.ends[line] : 0;
                boolean nextLineEnd = line + 1 < l.starts.length && rawEnd == l.starts[line + 1];
                if (rawEnd == 0 || nextLineEnd) {
                    long avg = k > 0 ? Math.max(350L, Math.min(1100L, (when[k] - when[0]) / k)) : 900L;
                    long est = when[k] + Math.min(1800L, avg * 2);
                    wordEnd = rawEnd > when[k] ? Math.min(rawEnd, est) : est;
                } else {
                    wordEnd = Math.min(rawEnd, when[k] + 4500L);
                }
            }
        }
        long held = k < 0 ? 0 : Math.max(0L, wordEnd - when[k]);
        float up = !motion ? 1f : Math.min(1f, since / 160f);
        up = 1f - (1f - up) * (1f - up) * (1f - up); // ease out: quick to rise, gentle to land
        // A held note (over 0.6 s) keeps swelling until its end; a quick word just lifts and glows.
        float swell = held > 600 ? Math.min(1f, Math.min(since, held) / (float) held) : 0f;
        // Once the last word of a line finishes (e.g. before an instrumental break), ease its lift and glow back down.
        float settleOut = k < 0 || now <= wordEnd ? 1f : !motion ? 0f : Math.max(0f, 1f - (now - wordEnd) / 300f);
        settleOut = settleOut * settleOut;
        float prevArrivingLift = w.arriving.lift, prevArrivingGlow = w.arriving.glow;
        float prevLeavingLift = w.leaving.lift, prevLeavingGlow = w.leaving.glow;
        int prevAccent = w.arriving.accent;
        w.arriving.accent = (ui.accent & 0x00ffffff) | 0xd0000000;
        w.arriving.lift = k < 0 ? 0 : rise * up * (1f + .4f * swell) * settleOut;
        w.arriving.glow = k < 0 ? 0 : shine * up * (1f + swell) * settleOut;
        float down = !motion ? 1f : Math.min(1f, (SystemClock.uptimeMillis() - w.leftAt) / 200f);
        w.leaving.accent = w.arriving.accent;
        w.leaving.lift = w.leftLift * (1f - down);
        w.leaving.glow = w.leftGlow * (1f - down);
        boolean changed = wordChanged
            || w.arriving.accent != prevAccent
            || w.arriving.lift != prevArrivingLift
            || w.arriving.glow != prevArrivingGlow
            || w.leaving.lift != prevLeavingLift
            || w.leaving.glow != prevLeavingGlow;
        if (changed) view.invalidate();
        boolean settlingOut = settleOut > 0f && settleOut < 1f;
        boolean moving = motion && (up < 1f || down < 1f || settlingOut || (swell > 0f && swell < 1f && now <= wordEnd));
        return moving ? 16 : 40;
    }

    /** How much of a word-timed line has been sung: the text offset where the first word not yet reached begins. */
    static int sungUpTo(Lyrics l, int line, long now) {
        int[] at = l.wordAt[line];
        long[] when = l.wordStart[line];
        String text = l.words[line] == null ? "" : l.words[line];
        int upTo = 0;
        for (int w = 0; w < at.length && w < when.length && when[w] <= now; w++) upTo = w + 1 < at.length ? at[w + 1] : text.length();
        return Math.max(0, Math.min(upTo, text.length()));
    }

    void drifting(boolean playing) {
        if (lyricsBackdrop != null) lyricsBackdrop.drifting(playing);
    }

    void close() {
        if (panel != null) ui.removeView(panel);
        panel = null;
        lyricsBackdrop = null;
        if (ui.backdrop != null) ui.backdrop.drifting(ui.playing);
    }

    /** The song's lyrics from Spotify's lyrics service; time-synced ones light the current line. */
    FrameLayout open() {
        if (panel != null || ui.uri.isEmpty()) return panel;
        String track = ui.uri.substring(ui.uri.lastIndexOf(':') + 1);
        String name = ui.title;
        panel = new FrameLayout(ui.activity);
        panel.setClickable(true);
        panel.setBackgroundColor(ui.bg);
        ui.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        if (ui.backdrop != null) ui.backdrop.drifting(false);
        if (ui.pref("backdrop", true)) {
            lyricsBackdrop = ui.artwork.new Backdrop();
            panel.addView(lyricsBackdrop, new FrameLayout.LayoutParams(-1, -1)); // the player's blurred cover, so the two screens match
        }
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(18), ui.inset.top, ui.dp(18), ui.inset.bottom);
        LinearLayout header = ui.row();
        header.addView(ui.iconButton(ui.icon("back", () -> ui.ink), "Close lyrics", () -> ui.playerScreen.closeLyrics()), ui.lp(ui.dp(48), ui.dp(48)));
        header.addView(ui.text(name, 22, () -> ui.ink, true), ui.weight());
        LinearLayout nudge = ui.row();
        nudge.setGravity(Gravity.CENTER_VERTICAL);
        ui.bgRound(nudge, 16, () -> ui.surfHi);
        nudge.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
        TextView offsetLabel = ui.text("", 12, () -> ui.inkDim, true);
        Runnable refreshOffset = () -> {
            long ms = lyricOffsetMs(track);
            offsetLabel.setText(ms == 0 ? "0s" : String.format(java.util.Locale.ROOT, "%+.1fs", ms / 1000f));
        };
        refreshOffset.run();
        TextView minus = ui.text("−", 16, () -> ui.ink, true);
        minus.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        minus.setOnClickListener(v -> { nudgeLyricOffset(track, -500); refreshOffset.run(); });
        TextView plus = ui.text("+", 16, () -> ui.ink, true);
        plus.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        plus.setOnClickListener(v -> { nudgeLyricOffset(track, 500); refreshOffset.run(); });
        offsetLabel.setOnClickListener(v -> { ui.prefs.edit().remove("lyric_offset_" + track).apply(); refreshOffset.run(); });
        nudge.addView(minus);
        nudge.addView(offsetLabel);
        nudge.addView(plus);
        header.addView(nudge, ui.lp(-2, -2));
        body.addView(header, ui.fill(ui.dp(64)));
        FrameLayout host = new FrameLayout(ui.activity);
        body.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
        panel.addView(body, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout opened = panel;
        fillLyrics(host, () -> panel == opened, track);
        return panel;
    }

    /** Fills `host` with the song's lyrics (loading, none, or the lines); `alive` says the host is still on screen. */
    void fillLyrics(FrameLayout host, java.util.function.BooleanSupplier alive, String track) {
        fillLyrics(host, alive, track, null);
    }

    void fillLyrics(FrameLayout host, java.util.function.BooleanSupplier alive, String track, Runnable onEmpty) {
        String songTitle = ui.title, songArtist = ui.artist;
        long songLength = ui.duration;
        ScrollView scroll = new ScrollView(ui.activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true); // lines melt into the top and bottom edges instead of being cut
        scroll.setFadingEdgeLength(ui.dp(96));
        long[] touched = { 0 }; // while the reader is dragging, the screen must not scroll under their finger
        scroll.setOnTouchListener((v, e) -> { touched[0] = SystemClock.uptimeMillis(); return false; });
        LinearLayout lines = ui.column();
        lines.setPadding(0, ui.dp(12), 0, ui.dp(32));
        View status = ui.controls.loader("Loading lyrics");
        lines.addView(status, ui.fill(-2));
        scroll.addView(lines, new ScrollView.LayoutParams(-1, -2));
        host.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        ui.submit(SpatchUi.ART, () -> {
            Lyrics got = null;
            boolean missing = false;
            try { got = CoreBridge.lyrics(track, songTitle, songArtist, songLength); }
            catch (Throwable failed) {
                // The service answers 404 for a song with no lyrics; anything else is a real failure.
                if (String.valueOf(failed.getMessage()).contains("HTTP 404")) missing = true;
                else android.util.Log.w("SpatchUi", "lyrics failed", failed);
            }
            Lyrics found = got;
            String empty = missing ? "No lyrics for this song" : "Lyrics aren’t available right now",
                hint = !missing ? "Try again in a moment" : LyricGen.enabled() ? "Being written on this phone as the song plays; they’ll be here next time" : null;
            ui.post(() -> {
                if (!alive.getAsBoolean()) return;
                if (found == null || found.words.length == 0) {
                    if (onEmpty != null) {
                        onEmpty.run();
                        if (!alive.getAsBoolean()) return;
                    }
                    lines.removeView(status);
                    lines.addView(ui.emptyState("note", "badge6", empty, hint), ui.fill(-2));
                    return;
                }
                lines.removeView(status);
                if (found.generated) {
                    TextView note = ui.text("Generated on this phone · may be wrong", 12, () -> ui.inkDim, false);
                    note.setGravity(Gravity.CENTER);
                    note.setPadding(0, ui.dp(4), 0, ui.dp(4));
                    host.addView(note, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
                }
                boolean synced = found.starts[found.starts.length - 1] > 0;
                // Synced lyrics are read one line at a time: the sung line sits in the middle, so room above and below it.
                int room = host.getHeight() > 0 ? host.getHeight() : ui.getHeight();
                if (synced) lines.setPadding(0, room * 2 / 5, 0, room / 2);
                TextView[] views = new TextView[found.words.length];
                for (int i = 0; i < views.length; i++) {
                    String line = found.words[i];
                    String shownLine = line.isEmpty() ? " " : line;
                    views[i] = ui.text(shownLine, synced ? 30 : 22, () -> ui.ink, true);
                    views[i].setLineSpacing(0, 1.05f);
                    if (synced) {
                        views[i].setText(lyricText(shownLine));
                        views[i].setPadding(ui.dp(6), ui.dp(10), ui.dp(16), ui.dp(10));
                    }
                    LinearLayout.LayoutParams at = ui.fill(-2);
                    if (synced) at.bottomMargin = ui.dp(14);
                    lines.addView(views[i], at);
                    final long start = found.starts[i];
                    if (synced) {
                        views[i].setPivotX(0);
                        views[i].addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.setPivotY((b - t) / 2f));
                        final TextView tapped = views[i];
                        ui.tappable(views[i], null, () -> {
                            ui.setPosition(start);
                            ui.command(PlayerScreen.SEEK, start, ""); // tap a line to jump there
                            GradientDrawable flash = ui.round(0x33ffffff, 24); // the tap shows where it landed: a pill that fades
                            tapped.setBackground(flash);
                            tapped.postDelayed(() -> {
                                android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(0x33, 0);
                                fade.setDuration(220);
                                fade.addUpdateListener(f -> flash.setColor(((int) f.getAnimatedValue() << 24) | 0xffffff));
                                fade.start();
                            }, 80);
                        });
                    }
                }
                if (synced) syncLyrics(alive, scroll, views, found, new int[] { -2, -1, 0 }, touched);
            });
        });
    }

    /**
     * Keeps the sung line large, bright and centred while the lyrics screen is open (the others dim and shrink); `shown` holds the current line, -2 before the first pass, which snaps instead of animating.
     */
    private long lyricOffsetMs(String track) {
        return track == null || track.isEmpty() ? 0L : ui.prefs.getLong("lyric_offset_" + track, 0L);
    }

    private void nudgeLyricOffset(String track, long deltaMs) {
        if (track == null || track.isEmpty()) return;
        long next = Math.max(-10_000L, Math.min(10_000L, lyricOffsetMs(track) + deltaMs));
        ui.prefs.edit().putLong("lyric_offset_" + track, next).apply();
    }

    private void syncLyrics(java.util.function.BooleanSupplier alive, ScrollView scroll, TextView[] views, Lyrics lyrics, int[] shown, long[] touched) {
        if (!alive.getAsBoolean()) return;
        long[] starts = lyrics.starts;
        if (shown[0] == -2 && (scroll.getHeight() == 0 || views[0].getHeight() == 0)) { // not laid out yet: the centring needs real sizes
            ui.postDelayed(() -> syncLyrics(alive, scroll, views, lyrics, shown, touched), 30);
            return;
        }
        String track = ui.uri.contains(":") ? ui.uri.substring(ui.uri.lastIndexOf(':') + 1) : "";
        long now = ui.playhead() + lyricOffsetMs(track);
        int current = -1;
        for (int i = 0; i < starts.length; i++) if (starts[i] > 0 && starts[i] <= now) current = i;
        if (current != shown[0]) {
            boolean first = shown[0] == -2, glide = ui.motion() && !first;
            if (shown[0] >= 0 && lyrics.wordAt[shown[0]] != null) views[shown[0]].setText(lyricText(lyrics.words[shown[0]])); // the line just left is whole again
            shown[0] = current;
            shown[1] = -1;
            for (int i = 0; i < views.length; i++) {
                boolean on = i == current;
                float alpha = on ? 1f : .3f, size = on ? 1f : .95f;
                // The sung line glows in the cover's accent and sits on a soft pill.
                views[i].setBackground(on ? ui.round(0x14ffffff, 24) : null);
                if (glide) views[i].animate().alpha(alpha).scaleX(size).scaleY(size).setDuration(320).start();
                else { views[i].setAlpha(alpha); views[i].setScaleX(size); views[i].setScaleY(size); }
            }
            if (SystemClock.uptimeMillis() - touched[0] > 4000) {
                View focus = views[Math.max(current, 0)]; // in the intro, the first line waits in the middle
                int to = Math.max(0, focus.getTop() + focus.getHeight() / 2 - scroll.getHeight() / 2), before = scroll.getScrollY();
                scroll.scrollTo(0, to);
                int delta = scroll.getScrollY() - before;
                // The column jumps and every line springs from where it was to its place, nearest the sung line first:
                // it arrives as a ripple rather than a block.
                if (glide && delta != 0) for (int i = 0; i < views.length; i++) {
                    if (views[i].getTag() instanceof android.animation.ObjectAnimator) ((android.animation.ObjectAnimator) views[i].getTag()).cancel();
                    float lag = views[i].getTranslationY() + delta;
                    views[i].setTranslationY(lag);
                    android.animation.ObjectAnimator settle = android.animation.ObjectAnimator.ofFloat(views[i], "translationY", lag, 0f);
                    settle.setStartDelay(Math.min(Math.abs(i - Math.max(current, 0)), 8) * 16L);
                    settle.setDuration(560);
                    settle.setInterpolator(new android.view.animation.OvershootInterpolator(.6f));
                    views[i].setTag(settle);
                    settle.start();
                }
            }
        }
        boolean walking = current >= 0 && lyrics.wordAt[current] != null;
        long next = walking ? walkWords(views[current], lyrics, current, now, ui.ink) : 250;
        focusLyrics(views, current, shown, SystemClock.uptimeMillis() - touched[0] < 4000);
        if (!ui.playing || scroll.getWindowVisibility() != View.VISIBLE || !scroll.isShown()) next = 250;
        ui.postDelayed(() -> syncLyrics(alive, scroll, views, lyrics, shown, touched), next);
    }

    private final android.graphics.RenderEffect[] lyricBlurs = new android.graphics.RenderEffect[5];

    /**
     * GPU mode: lines away from the sung one go softly out of focus, more the further they are, like a camera focused on
     * the current line. While the reader is scrolling by hand everything is sharp. shown[2] holds the line it was last
     * laid out for (+1), or 0 when sharp; nothing is redone between line changes.
     */
    private void focusLyrics(TextView[] views, int current, int[] shown, boolean reading) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        int want = !GpuFx.blur(ui.prefs) || reading || current < 0 ? 0 : current + 1;
        if (want == shown[2]) return;
        shown[2] = want;
        for (int i = 0; i < views.length; i++) {
            int far = want == 0 ? 0 : Math.min(4, Math.abs(i - current));
            if (far > 0 && lyricBlurs[far] == null) {
                float r = ui.dp(1.1f * far);
                lyricBlurs[far] = android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL);
            }
            views[i].setRenderEffect(far == 0 ? null : lyricBlurs[far]);
        }
    }
}
