package spatch;

import spatch.ArtworkViews.Backdrop;
import spatch.ArtworkViews.Disc;
import spatch.ArtworkViews.Motes;
import spatch.Controls.Bars;
import spatch.Controls.PlayButton;
import spatch.Controls.VolumeBar;
import spatch.Controls.Wave;
import spatch.MediaEntry.Song;
import spatch.SpatchUi.Icon;
import spatch.SpatchUi.Option;
import spatch.SpatchUi.Row;
import spatch.SpatchUi.SleepRingView;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** The full-screen player, its lyrics and queue panels, sleep timer and like state. */
final class PlayerScreen {
    private final SpatchUi ui;
    PlayerScreen(SpatchUi ui) { this.ui = ui; }

    static final int PAUSE = 0, RESUME = 1, PREVIOUS = 2, NEXT = 3, SEEK = 4, SHUFFLE = 5, REPEAT = 6, PLAY = 7, SKIP_TO = 8, QUEUE_ADD = 9, QUEUE_NEXT = 13, SPEED = 14;

    private String savedUri = "";
    private int savedRequest;

    /** The optional one-line lyric under the player's cover, and the lyrics it follows. */
    private LinearLayout pLyric;
    private View moreButton;

    private ScrollView sideQueueScroll;
    private String lyricsUri = "", lyricKey = "";
    private boolean lyricShown;
    private CoreBridge.Lyrics lyricsData;

    private void marquee(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        v.setMarqueeRepeatLimit(-1);
        v.setHorizontalFadingEdgeEnabled(true);
        v.setFadingEdgeLength(ui.dp(24));
        v.setSelected(true);
    }

    /** The player's more menu: what is not pinned to the bar, plus share and settings. */
    private void openPlayerMenu(View anchor) {
        if (ui.accountMenu != null) ui.accountMenu.dismiss();
        LinearLayout menu = ui.column();
        menu.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        android.widget.PopupWindow popup = new android.widget.PopupWindow(menu, ui.dp(272), -2, true);
        ui.accountMenu = popup;
        popup.setBackgroundDrawable(ui.round(ui.surf, 24));
        popup.setElevation(ui.dp(14));
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(() -> { if (ui.accountMenu == popup) ui.accountMenu = null; });
        if (!ui.pref("bar_timer", false)) ui.menuAction(menu, SpatchUi.sleepAt > SystemClock.uptimeMillis() ? "Sleep timer (running)" : "Sleep timer", "timer", () -> { popup.dismiss(); openSleepTimer(); });
        if (!ui.pref("bar_devices", false)) ui.menuAction(menu, "Play on another device", "devices", () -> { popup.dismiss(); openDevices(); });
        ui.menuAction(menu, "Share", "share", () -> { popup.dismiss(); share(); });
        ui.menuAction(menu, "Player settings", "settings", () -> { popup.dismiss(); closePlayer(); ui.openSettings(true); });
        popup.showAsDropDown(anchor, 0, ui.dp(4), Gravity.END);
        ui.popIn(menu);
    }

    void openPlayer(boolean animate) {
        if (ui.playerOpen) return;
        ui.playerOpen = true;
        buildPlayer();
        ui.addView(ui.player, new FrameLayout.LayoutParams(-1, -1));
        if (ui.pref("keep_on", true)) ui.activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.refresh();
        if (animate && ui.motion()) {
            ui.player.setTranslationY(ui.getHeight() > 0 ? ui.getHeight() : ui.dp(800));
            ui.player.animate().translationY(0).setDuration(440).setInterpolator(SpatchUi.EMPHASIZED).start();
            // Backdrop first, then each row of the player rises into place a beat after the one above it.
            ui.backdrop.setAlpha(0f);
            ui.backdrop.animate().alpha(1f).setStartDelay(120).setDuration(500).start();
            ViewGroup col = (ViewGroup) ui.player.getChildAt(ui.player.getChildCount() - 1);
            for (int i = 0; i < col.getChildCount(); i++) {
                View row = col.getChildAt(i);
                row.setAlpha(0f);
                row.setTranslationY(ui.dp(28));
                row.animate().alpha(1f).translationY(0).setStartDelay(140 + i * 55L).setDuration(420).setInterpolator(SpatchUi.EMPHASIZED).start();
            }
        }
    }

    void closePlayer() {
        if (!ui.playerOpen) return;
        ui.stopHeldSeek();
        if (ui.wave != null && ui.wave.preview >= 0) {
            float from = ui.shownProgress();
            ui.wave.cancelScrub();
            ui.animateProgress(from);
        }
        ui.playerOpen = false;
        // The play buttons' spin draws itself frame to frame only while the player is closed; restart it now.
        for (PlayButton b : ui.playButtons) b.invalidate();
        if (ui.playlistScreen.listFab != null) ui.playlistScreen.listFab.invalidate();
        ui.dockUi.setDockHidden(false);
        ui.activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View p = ui.player;
        if (ui.motion()) p.animate().translationY(ui.getHeight()).setDuration(300).setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f)).withEndAction(() -> ui.removeView(p)).start();
        else ui.removeView(p);
        ui.player = null;
        ui.disc = null; ui.wave = null; ui.backdrop = null; ui.motes = null; ui.nextPill = null;
        ui.pTitle = ui.pArtist = ui.pSource = ui.pBadge = ui.pQuality = ui.pSpeed = null;
        ui.pLike = ui.pShuffle = ui.pRepeat = ui.pSparkle = null;
        ui.dockUi.pPlay = null;
        pLyric = null;
        ui.sideHost = null;
        ui.sideTabSelect = null;
        ui.sideTabManual = false;
        sideQueueScroll = null;
        ui.sideQueueOffset = 0;
        ui.nextKey = "";
        for (PlayButton b : ui.playButtons) b.invalidate();
        for (Bars b : ui.controls.attachedBars) b.invalidate();
        if (ui.mArt != null) ui.mArt.invalidate();
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
                    CoreBridge.Lyrics got = null;
                    try { got = CoreBridge.lyrics(track, songTitle, songArtist, songLength); } catch (Throwable none) { /* no lyrics, or offline: the lines just stay empty */ }
                    CoreBridge.Lyrics found = got;
                    ui.post(() -> { if (forUri.equals(lyricsUri)) lyricsData = found; });
                });
            }
        }
        String[] want = { "", "", "" };
        CoreBridge.Lyrics l = lyricsData;
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
            lyricSung = -1;
            for (int i = 0; i < 3; i++) rows[i].setText(want[i]);
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
        if (walking) {
            int upTo = sungUpTo(l, current, now);
            if (upTo != lyricSung) { lyricSung = upTo; rows[1].setText(sungWords(want[1], upTo, rows[1].getCurrentTextColor())); }
        }
        box.postDelayed(() -> playerLyrics(box), walking ? 40 : 250); // word timing needs a quicker look, only while a timed line is sung
    }

    private int lyricSung = -1;

    /** How much of a word-timed line has been sung: the text offset where the first word not yet reached begins. */
    static int sungUpTo(CoreBridge.Lyrics l, int line, long now) {
        int[] at = l.wordAt[line];
        long[] when = l.wordStart[line];
        String text = l.words[line] == null ? "" : l.words[line];
        int upTo = 0;
        for (int w = 0; w < at.length && w < when.length && when[w] <= now; w++) upTo = w + 1 < at.length ? at[w + 1] : text.length();
        return Math.max(0, Math.min(upTo, text.length()));
    }

    /** A line with the words still to come dimmed, so the highlight walks along it word by word as they are sung. */
    static CharSequence sungWords(String text, int upTo, int color) {
        if (text == null || upTo >= text.length()) return text;
        android.text.SpannableString s = new android.text.SpannableString(text);
        s.setSpan(new android.text.style.ForegroundColorSpan((color & 0x00ffffff) | 0x5c000000), Math.max(0, upTo), text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    // Optional idle chrome: the top bar, controls and Next-up pill fade out after a quiet spell while music plays.
    View[] idleViews = new View[0];
    boolean idleHidden, idleSwallow;
    private final Runnable idleHide = new Runnable() {
        @Override public void run() {
            if (ui.player == null || !ui.pref("idle_hide", false) || idleViews.length == 0) return;
            if (!ui.playing || queuePanel != null || lyricsPanel != null) { ui.player.postDelayed(this, 5000); return; }
            idleHidden = true;
            for (View v : idleViews) v.animate().alpha(0f).setDuration(ui.motion() ? 400 : 0).start();
        }
    };

    private void idleTouched() {
        if (ui.player == null) return;
        ui.player.removeCallbacks(idleHide);
        if (!ui.pref("idle_hide", false) || idleViews.length == 0) return;
        if (idleHidden) {
            idleHidden = false;
            for (View v : idleViews) v.animate().alpha(1f).setDuration(ui.motion() ? 200 : 0).start();
        }
        ui.player.postDelayed(idleHide, 5000);
    }

    private void buildPlayer() {
        idleHidden = false;
        ui.player = new FrameLayout(ui.activity) {
            @Override public boolean dispatchTouchEvent(MotionEvent e) {
                // A touch while the chrome is hidden only brings it back; it must not press an invisible button.
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { idleSwallow = idleHidden; idleTouched(); }
                if (idleSwallow) { if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) idleSwallow = false; return true; }
                return super.dispatchTouchEvent(e);
            }
        };
        ui.player.setClickable(true);
        ui.backdrop = ui.artwork.new Backdrop();
        ui.player.addView(ui.backdrop, new FrameLayout.LayoutParams(-1, -1));
        ui.motes = ui.pref("motes", true) ? ui.artwork.new Motes() : null;
        if (ui.motes != null) ui.player.addView(ui.motes, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = ui.column();
        boolean land = ui.landscape();
        LinearLayout sideColumn = null;
        if (land) {
            // Cover, title and controls on the left; the lyrics, with the top buttons above them, take the right.
            col.setPadding(ui.dp(18) + ui.inset.left, ui.dp(2) + ui.inset.top, ui.dp(10), ui.dp(4) + ui.inset.bottom);
            LinearLayout split = ui.row();
            split.addView(col, new LinearLayout.LayoutParams(0, -1, 1f));
            sideColumn = ui.column();
            sideColumn.setPadding(ui.dp(10), ui.dp(6) + ui.inset.top, ui.dp(18) + ui.inset.right, ui.dp(8) + ui.inset.bottom);
            split.addView(sideColumn, new LinearLayout.LayoutParams(0, -1, 1.15f));
            ui.player.addView(split, new FrameLayout.LayoutParams(-1, -1));
        } else {
            col.setPadding(ui.dp(18) + ui.inset.left, ui.dp(6) + ui.inset.top, ui.dp(18) + ui.inset.right, ui.dp(14) + ui.inset.bottom);
            ui.player.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }

        LinearLayout top = ui.row();
        top.addView(ui.iconButton(ui.icon("down", () -> ui.ink), "Close player", ui::back), ui.lp(ui.dp(48), ui.dp(48)));
        LinearLayout from = ui.column();
        TextView cap = ui.text("PLAYING FROM", 11, () -> ui.inkDim, true);
        cap.setLetterSpacing(.12f);
        cap.setGravity(Gravity.CENTER);
        from.addView(cap, ui.fill(-2));
        ui.pSource = ui.text("", 15, () -> ui.ink, true);
        ui.pSource.setGravity(Gravity.CENTER);
        ui.oneLine(ui.pSource);
        from.addView(ui.pSource, ui.fill(-2));
        top.addView(from, ui.weight());
        // The timer icon carries a small badge with the minutes left while a sleep timer runs.
        FrameLayout timerBox = new FrameLayout(ui.activity);
        timerBox.setClipChildren(false);
        timerBox.addView(ui.iconButton(ui.icon("timer", () -> SpatchUi.sleepAt > SystemClock.uptimeMillis() ? ui.accent : ui.ink), "Sleep timer", this::openSleepTimer), new FrameLayout.LayoutParams(-1, -1));
        TextView left = ui.text("", 10, () -> ui.onAccent, true);
        left.setGravity(Gravity.CENTER);
        left.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        ui.bgRound(left, 8, () -> ui.accent);
        timerBox.addView(left, new FrameLayout.LayoutParams(-2, ui.dp(16), Gravity.BOTTOM | Gravity.END));
        sleepBadge = new Runnable() {
            @Override public void run() {
                left.removeCallbacks(this);
                long ms = SpatchUi.sleepAt - SystemClock.uptimeMillis();
                left.setVisibility(ms > 0 ? View.VISIBLE : View.GONE);
                SpatchUi.recolorView(timerBox);
                if (moreButton != null) SpatchUi.recolorView(moreButton);
                if (ms <= 0) return;
                left.setText(Math.max(1, (ms + 59_999) / 60_000) + "m");
                left.postDelayed(this, 15_000);
            }
        };
        sleepBadge.run();
        // Playback speed, for podcasts: a small chip that opens the choices.
        ui.pSpeed = ui.text("1×", 13, () -> ui.ink, true);
        ui.pSpeed.setGravity(Gravity.CENTER);
        ui.pSpeed.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        ui.bgRound(ui.pSpeed, 16, () -> ui.surfHi);
        ui.tappable(ui.pSpeed, "Playback speed", this::openSpeed);
        ui.pSpeed.setVisibility(View.GONE);
        LinearLayout.LayoutParams speedParams = ui.lp(-2, ui.dp(32));
        speedParams.rightMargin = ui.dp(6);
        top.addView(ui.pSpeed, speedParams);
        // The bar keeps only what is used every song; the rest lives in the more menu unless pinned back in settings.
        if (ui.pref("bar_timer", false)) top.addView(timerBox, ui.lp(ui.dp(48), ui.dp(48)));
        if (ui.pref("bar_devices", false)) top.addView(ui.iconButton(ui.icon("devices", () -> ui.ink), "Play on another device", this::openDevices), ui.lp(ui.dp(48), ui.dp(48)));
        if (ui.pref("bar_lyrics", true) && !land) top.addView(ui.iconButton(ui.icon("note", () -> ui.ink), "Lyrics", this::openLyrics), ui.lp(ui.dp(48), ui.dp(48)));
        if (ui.pref("bar_queue", true)) top.addView(ui.iconButton(ui.icon("queue", () -> ui.ink), "Up next", this::openQueue), ui.lp(ui.dp(48), ui.dp(48)));
        View[] more = new View[1];
        more[0] = ui.iconButton(ui.icon("more", () -> SpatchUi.sleepAt > SystemClock.uptimeMillis() ? ui.accent : ui.ink), "More", () -> openPlayerMenu(more[0]));
        moreButton = more[0];
        top.addView(more[0], ui.lp(ui.dp(48), ui.dp(48)));
        (land ? sideColumn : col).addView(top, ui.fill(-2));

        ui.disc = ui.artwork.new Disc();
        col.addView(ui.disc, new LinearLayout.LayoutParams(-1, 0, 1));
        // The visualizer's glow reaches past the disc's own square; let every container up to the player draw it whole.
        View discView = ui.disc;
        ui.disc.post(() -> {
            for (android.view.ViewParent up = discView.getParent(); up instanceof ViewGroup && up != ui; up = up.getParent()) {
                ((ViewGroup) up).setClipChildren(false);
                ((ViewGroup) up).setClipToPadding(false);
            }
        });
        gestures(ui.disc);
        pLyric = null;
        if (ui.pref("player_lyrics", false) && !land) {
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
                rows[k].setEllipsize(android.text.TextUtils.TruncateAt.END);
                rows[k].setAlpha(sung ? 1f : .38f);
                box.addView(rows[k], ui.fill(-2));
            }
            // How many lines show is the user's call (default two: the sung line and what comes next); the rest are hidden, not removed.
            int shownLines = Math.max(1, Math.min(3, ui.prefs.getInt("player_lyric_lines", 1) + 1));
            rows[0].setVisibility(shownLines >= 3 ? View.VISIBLE : View.GONE);
            rows[2].setVisibility(shownLines >= 2 ? View.VISIBLE : View.GONE);
            box.setTag(rows);
            box.setClipChildren(true);
            // Its rows slide inside it; it clips them to its own card, since the column around it no longer clips (for the visualizer glow).
            box.setClipToOutline(true);
            // The lines sit on a quiet glass card (rounded, a touch lighter than the backdrop) so they read as a part of the player.
            box.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14));
            ui.bgRound(box, 26, () -> (ui.ink & 0x00ffffff) | 0x16000000);
            ui.tappable(box, "Open lyrics", this::openLyrics);
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

        LinearLayout titleRow = ui.row();
        LinearLayout words = ui.column();
        ui.pTitle = ui.text("", land ? 22 : 28, () -> ui.ink, true);
        marquee(ui.pTitle);
        ui.pTitle.setOnLongClickListener(v -> share());
        // The title opens the song's album, the artist line its artist, as in Spotify's player.
        ui.pTitle.setOnClickListener(v -> openFromPlayer(ui.albumUri, ui.album, ui.artist));
        words.addView(ui.pTitle, ui.fill(-2));
        LinearLayout byline = ui.row();
        ui.pBadge = ui.text("E", 11, ui.bg, true);
        ui.pBadge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = ui.round(ui.inkDim, 4);
        ui.pBadge.setBackground(badgeBg);
        ui.colored(ui.pBadge, () -> { ui.pBadge.setTextColor(ui.bg); badgeBg.setColor(ui.inkDim); });
        LinearLayout.LayoutParams bp = ui.lp(ui.dp(17), ui.dp(17));
        bp.rightMargin = ui.dp(7);
        byline.addView(ui.pBadge, bp);
        ui.pArtist = ui.text("", 17, () -> ui.inkDim, false);
        ui.pArtist.setOnClickListener(v -> openFromPlayer(ui.artistUri, ui.artist, "Artist"));
        ui.oneLine(ui.pArtist);
        byline.addView(ui.pArtist, ui.weight());
        LinearLayout.LayoutParams byp = ui.fill(-2);
        byp.topMargin = ui.dp(4);
        words.addView(byline, byp);
        // What Spotify says is playing. The line always keeps its space, so the layout never shifts when it arrives.
        ui.pQuality = ui.text("", 12, () -> ui.accent, true);
        ui.pQuality.setLetterSpacing(.04f);
        ui.pQuality.setSingleLine(true);
        ui.pQuality.setText(" ");
        ui.pQuality.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams qp = ui.lp(-2, -2);
        qp.topMargin = ui.dp(6);
        if (!land && ui.pref("player_quality", true)) words.addView(ui.pQuality, qp);
        titleRow.addView(words, ui.weight());
        ui.pLike = ui.new Icon("heart-outline", ui.ink);
        ui.colored(ui.pLike, () -> {
            boolean isLiked = Boolean.TRUE.equals(ui.saved);
            ui.pLike.symbol = isLiked ? "heart" : "heart-outline";
            ui.pLike.color = isLiked ? ui.accent : ui.ink;
            ui.pLike.invalidate();
        });
        if (ui.pref("player_heart", true)) titleRow.addView(ui.iconButton(ui.pLike, "Save to Liked Songs", .90f, this::toggleSaved), ui.lp(ui.dp(52), ui.dp(52)));
        col.addView(titleRow, ui.fill(-2));

        ui.wave = ui.controls.new Wave();
        LinearLayout.LayoutParams wp = ui.fill(ui.dp(land ? 60 : 62));
        wp.topMargin = ui.dp(land ? 2 : 6);
        col.addView(ui.wave, wp);
        LinearLayout controls = ui.row();
        controls.setGravity(Gravity.CENTER);
        ui.pShuffle = ui.new Icon("shuffle", ui.inkDim);
        ui.colored(ui.pShuffle, () -> { ui.pShuffle.color = ui.shuffle ? ui.accent : ui.inkDim; ui.pShuffle.invalidate(); });
        ui.pRepeat = ui.new Icon("repeat", ui.inkDim);
        ui.colored(ui.pRepeat, () -> { ui.pRepeat.symbol = ui.repeat == 2 ? "repeat-one" : "repeat"; ui.pRepeat.color = ui.repeat != 0 ? ui.accent : ui.inkDim; ui.pRepeat.invalidate(); });
        // Smart Shuffle shows as a small sparkle on the shuffle icon.
        FrameLayout shuffleBox = new FrameLayout(ui.activity);
        shuffleBox.addView(ui.iconButton(ui.pShuffle, "Shuffle: off, on or Smart Shuffle", .90f, this::toggleShuffle), new FrameLayout.LayoutParams(-1, -1));
        ui.pSparkle = ui.new Icon("sparkle", ui.accent);
        ui.colored(ui.pSparkle, () -> { ui.pSparkle.color = ui.accent; ui.pSparkle.setVisibility(ui.shuffle && ui.smartShuffle ? View.VISIBLE : View.GONE); ui.pSparkle.invalidate(); });
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ui.dp(11), ui.dp(11), Gravity.TOP | Gravity.END);
        sp.setMargins(0, ui.dp(11), ui.dp(9), 0);
        shuffleBox.addView(ui.pSparkle, sp);
        boolean modes = ui.pref("player_modes", true);
        if (modes) controls.addView(shuffleBox, ui.lp(ui.dp(land ? 44 : 52), ui.dp(land ? 44 : 52)));
        controls.addView(skip(holdToSeek(ui.iconButton(ui.icon("previous", () -> ui.ink), "Previous, hold to rewind", ui::previousTrack), false), false), ui.lp(ui.dp(land ? 48 : 60), ui.dp(land ? 48 : 60)));
        ui.dockUi.pPlay = ui.controls.new PlayButton();
        controls.addView(ui.dockUi.pPlay, ui.lp(ui.dp(land ? 66 : 86), ui.dp(land ? 66 : 86)));
        controls.addView(skip(holdToSeek(ui.iconButton(ui.icon("next", () -> ui.ink), "Next, hold to fast forward", () -> ui.command(NEXT, 0, "")), true), true), ui.lp(ui.dp(land ? 48 : 60), ui.dp(land ? 48 : 60)));
        if (modes) controls.addView(ui.iconButton(ui.pRepeat, "Repeat", .90f, this::cycleRepeat), ui.lp(ui.dp(land ? 44 : 52), ui.dp(land ? 44 : 52)));
        col.addView(controls, ui.fill(-2));

        ui.nextPill = ui.row();
        ui.nextPill.setPadding(ui.dp(6), ui.dp(6), ui.dp(16), ui.dp(6));
        ui.bgRound(ui.nextPill, 24, () -> (ui.ink & 0x00ffffff) | 0x1a000000);
        LinearLayout.LayoutParams np = ui.fill(-2);
        np.topMargin = ui.dp(12);
        if (!land && ui.pref("player_next", true)) col.addView(ui.nextPill, np);
        ui.tappable(ui.nextPill, "Up next", this::openQueue);
        List<View> hide = new ArrayList<>();
        if (ui.pref("idle_hide_top", true)) hide.add(top);
        if (ui.pref("idle_hide_title", false)) hide.add(titleRow);
        if (ui.pref("idle_hide_wave", false)) hide.add(ui.wave);
        if (ui.pref("idle_hide_controls", true)) hide.add(controls);
        if (!land && ui.pref("player_next", true) && ui.pref("idle_hide_next", true)) hide.add(ui.nextPill);
        idleViews = hide.toArray(new View[0]);
        idleTouched();
        if (land) {
            ui.sideTabManual = false;
            ui.sideTab = (ui.uri.isEmpty() || ui.uri.equals(ui.noLyricsUri)) ? 1 : 0;
            View pillInd = new View(ui.activity);
            GradientDrawable indBg = ui.round(ui.accent, 15);
            pillInd.setBackground(indBg);
            FrameLayout tabContainer = new FrameLayout(ui.activity) {
                private int lastHalf = -1;
                @Override protected void onMeasure(int wSpec, int hSpec) {
                    int innerW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
                    int halfW = Math.max(0, innerW / 2);
                    if (halfW > 0 && halfW != lastHalf) {
                        lastHalf = halfW;
                        pillInd.getLayoutParams().width = halfW;
                        pillInd.setTranslationX(ui.sideTab * (float) halfW);
                    }
                    super.onMeasure(wSpec, hSpec);
                }
            };
            tabContainer.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
            GradientDrawable tabTrack = ui.round((ui.ink & 0x00ffffff) | 0x16000000, 18);
            tabContainer.setBackground(tabTrack);
            tabContainer.addView(pillInd, new FrameLayout.LayoutParams(0, -1));
            LinearLayout tabRow = ui.row();
            TextView[] tabViews = new TextView[2];
            String[] tabNames = { "Lyrics", "Up next" };
            Runnable syncTabs = () -> {
                tabTrack.setColor((ui.ink & 0x00ffffff) | 0x16000000);
                indBg.setColor(ui.accent);
                for (int i = 0; i < 2; i++) tabViews[i].setTextColor(ui.sideTab == i ? ui.onAccent : ui.inkDim);
            };
            ui.sideTabSelect = (target, animate) -> {
                if (ui.sideTab == target && !animate) return;
                int prev = ui.sideTab;
                ui.sideTab = target;
                syncTabs.run();
                int halfW = pillInd.getWidth() > 0 ? pillInd.getWidth() : Math.max(0, (tabContainer.getWidth() - ui.dp(6)) / 2);
                float targetX = ui.sideTab * (float) halfW;
                if (animate && ui.motion()) pillInd.animate().translationX(targetX).setDuration(280).setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start(); // no overshoot: the end tab sits flush with the track, so any overshoot was clipped
                else pillInd.setTranslationX(targetX);
                loadSidePanel(animate, prev != target ? (target > prev ? 1 : -1) : 0);
            };
            for (int i = 0; i < 2; i++) {
                final int pick = i;
                tabViews[i] = ui.text(tabNames[i], 13, () -> ui.sideTab == pick ? ui.onAccent : ui.inkDim, true);
                tabViews[i].setGravity(Gravity.CENTER);
                ui.tappable(tabViews[i], tabNames[i], .94f, () -> {
                    if (ui.sideTab == pick) return;
                    ui.sideTabManual = true;
                    if (ui.sideTabSelect != null) ui.sideTabSelect.accept(pick, true);
                });
                tabRow.addView(tabViews[i], new LinearLayout.LayoutParams(0, -1, 1));
            }
            tabContainer.addView(tabRow, new FrameLayout.LayoutParams(-1, -1));
            ui.colored(tabContainer, syncTabs);
            LinearLayout.LayoutParams tp = ui.lp(-1, ui.dp(36));
            tp.topMargin = ui.dp(2);
            tp.bottomMargin = ui.dp(6);
            sideColumn.addView(tabContainer, tp);

            FrameLayout host = new FrameLayout(ui.activity);
            sideColumn.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
            ui.sideHost = host;
            loadSidePanel(false, 0);
        } else {
            ui.sideTabSelect = null;
        }
    }

    /** Landscape: fills the right panel with synced lyrics or the interactive Up next queue. */
    void loadSidePanel(boolean cascade) { loadSidePanel(cascade, 0); }

    void loadSidePanel(boolean cascade, int slideDir) {
        FrameLayout host = ui.sideHost;
        if (host == null) return;
        if (sideQueueScroll != null) ui.sideQueueOffset = sideQueueScroll.getScrollY();
        sideQueueScroll = null;
        host.removeAllViews();
        if (ui.sideTab == 1) {
            ScrollView scroll = sideQueueScroll = new ScrollView(ui.activity);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setVerticalFadingEdgeEnabled(true);
            scroll.setFadingEdgeLength(ui.dp(56));
            LinearLayout tracks = ui.column();
            tracks.setPadding(0, ui.dp(4), 0, ui.dp(24));
            fillQueueList(tracks, cascade, false);
            scroll.addView(tracks, new ScrollView.LayoutParams(-1, -2));
            host.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            SpatchUi.keepScroll(scroll, ui.sideQueueOffset);
        } else if (!ui.uri.isEmpty()) {
            String trackUri = ui.uri;
            fillLyrics(host, () -> ui.sideHost == host && ui.sideTab == 0 && ui.player != null, ui.uri.substring(ui.uri.lastIndexOf(':') + 1), () -> {
                if (trackUri.equals(ui.uri)) ui.noLyricsUri = trackUri;
                if (!ui.sideTabManual && ui.sideHost == host && ui.sideTab == 0 && ui.sideTabSelect != null) ui.sideTabSelect.accept(1, true);
            });
        }
        if (slideDir != 0 && ui.motion() && host.getChildCount() > 0) {
            View child = host.getChildAt(0);
            child.setAlpha(0f);
            child.setTranslationX(slideDir * ui.dp(24));
            child.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(SpatchUi.EMPHASIZED).start();
        }
    }

    /** Tap skips; holding the button jumps ten seconds at a time until it is released. */
    View skip(View button, boolean next) {
        // Previous also restarts the song, which Spotify allows even when skipping back isn't, so only Next dims.
        if (!next) return button;
        ui.skipButtons.put(button, true);
        boolean allowed = next ? ui.canNext : ui.canPrevious;
        button.setEnabled(allowed);
        button.setAlpha(allowed ? 1f : .35f);
        return button;
    }

    private View holdToSeek(View button, boolean forward) {
        boolean[] held = new boolean[1];
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (!held[0] || ui.duration <= 0 || !button.isAttachedToWindow()) return;
            long jump = new long[]{5_000, 10_000, 30_000}[Math.max(0, Math.min(2, ui.prefs.getInt("seek_step", 1)))];
            long target = Math.max(0, Math.min(ui.duration - 1500, ui.playhead() + (forward ? jump : -jump)));
            ui.setPosition(target);
            ui.command(SEEK, target, "");
            ui.tick(button);
            button.postDelayed(step[0], 480);
        };
        button.setOnLongClickListener(v -> {
            ui.stopHeldSeek();
            held[0] = true;
            ui.holdingSeek = true;
            ui.cancelHeldSeek = () -> { held[0] = false; button.removeCallbacks(step[0]); };
            step[0].run();
            ui.invalidateProgress();
            return true;
        });
        // iconButton installs its own touch listener for the press animation; wrap it so releasing still ends the hold.
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN && ui.motion()) v.animate().scaleX(.90f).scaleY(.90f).setDuration(90).start();
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    if (held[0]) ui.stopHeldSeek();
                    if (ui.motion()) v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                }
                return false;
            }
        });
        return button;
    }

    /** On the disc: pull down to close, flick sideways to skip, double tap to play or pause. */
    private void gestures(View v) {
        float[] start = new float[2];
        boolean[] drag = new boolean[1], menuOpened = new boolean[1];
        v.setContentDescription("Artwork, hold for song options");
        v.setOnLongClickListener(view -> {
            if (ui.uri.isEmpty()) return false;
            menuOpened[0] = true;
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            ui.currentSongActions();
            return true;
        });
        android.view.GestureDetector taps = new android.view.GestureDetector(ui.activity, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) { if (!drag[0]) v.performLongClick(); }
            @Override public boolean onDoubleTap(MotionEvent e) { if (ui.pref("double_tap", true)) togglePlay(); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || !ui.pref("swipe_skip", true) || Math.abs(b.getX() - a.getX()) < ui.dp(60) || Math.abs(b.getX() - a.getX()) < Math.abs(b.getY() - a.getY())) return false;
                if (b.getX() < a.getX()) ui.command(NEXT, 0, ""); else ui.previousTrack();
                ui.tick(v);
                return true;
            }
        });
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) menuOpened[0] = false;
            if (menuOpened[0]) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) taps.onTouchEvent(e);
                return true;
            }
            if (ui.player != null && ui.motion()) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: start[0] = e.getRawX(); start[1] = e.getRawY(); drag[0] = false; break;
                    case MotionEvent.ACTION_MOVE: {
                        float dy = e.getRawY() - start[1], dx = e.getRawX() - start[0];
                        if (!drag[0] && dy > ui.dp(16) && dy > Math.abs(dx) * 1.4f) {
                            drag[0] = true;
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                            MotionEvent cancel = MotionEvent.obtain(e);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            taps.onTouchEvent(cancel);
                            cancel.recycle();
                        }
                        if (drag[0]) { ui.player.setTranslationY(Math.max(0, dy) * .8f); return true; }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (drag[0]) {
                            drag[0] = false;
                            if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getRawY() - start[1] > ui.dp(140)) ui.back();
                            else ui.player.animate().translationY(0).setDuration(300).setInterpolator(new android.view.animation.OvershootInterpolator(.8f)).start();
                            return true;
                        }
                        break;
                }
            }
            taps.onTouchEvent(e);
            return true;
        });
    }

    void togglePlay() {
        if (ui.uri.isEmpty() || ui.pending != null) return;
        boolean want = !ui.playing;
        ui.pending = want;
        ui.refresh();
        ui.submit(SpatchUi.COMMANDS, () -> {
            boolean ok = SpatchUi.playerCommand(want ? RESUME : PAUSE, 0, "");
            ui.post(() -> {
                if (!ok) { ui.pending = null; ui.refresh(); }
                else ui.postDelayed(() -> { if (ui.pending != null && ui.pending == want) { ui.pending = null; ui.refresh(); } }, 2500);
            });
        });
    }

    static boolean isPlaylistLink(String u) {
        return u.startsWith("spotify:playlist:") || (u.startsWith("spotify:user:") && u.contains(":playlist:"));
    }

    /** Off → Shuffle → Smart Shuffle (playlists only) → Off, as Spotify's own shuffle button cycles. */
    private void toggleShuffle() {
        boolean before = ui.shuffle, beforeSmart = ui.smartShuffle;
        int mode = !ui.shuffle ? 1 : !ui.smartShuffle && isPlaylistLink(ui.context) ? 2 : 0;
        boolean want = mode > 0;
        ui.shuffle = want;
        ui.smartShuffle = mode == 2;
        ui.refresh();
        String list = ui.context;
        ui.submit(SpatchUi.COMMANDS, () -> {
            boolean ok = SpatchUi.playerCommand(SHUFFLE, want ? 1 : 0, "");
            if (ok && isPlaylistLink(list)) {
                String error = SpatchUi.nativeSetShuffleMode(list, mode);
                if (error != null && !error.isEmpty()) android.util.Log.w("SpatchUi", "Shuffle mode: " + error);
            }
            if (!ok) ui.post(() -> {
                if (ui.shuffle == want) { ui.shuffle = before; ui.smartShuffle = beforeSmart; ui.refresh(); }
                if (ui.isAttachedToWindow()) Toast.makeText(ui.activity, SpatchUi.playbackFailure(), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void cycleRepeat() {
        int before = ui.repeat, want = (before + 1) % 3;
        ui.repeat = want;
        ui.refresh();
        ui.submit(SpatchUi.COMMANDS, () -> {
            if (!SpatchUi.playerCommand(REPEAT, want, "")) ui.post(() -> {
                if (ui.repeat == want) { ui.repeat = before; ui.refresh(); }
                if (ui.isAttachedToWindow()) Toast.makeText(ui.activity, SpatchUi.playbackFailure(), Toast.LENGTH_SHORT).show();
            });
        });
    }

    FrameLayout queuePanel;
    private ScrollView queueScroll;

    void closeQueue() {
        if (queuePanel != null) ui.removeView(queuePanel);
        queuePanel = null;
        queueScroll = null;
    }

    FrameLayout lyricsPanel;

    void closeLyrics() {
        if (lyricsPanel != null) ui.removeView(lyricsPanel);
        lyricsPanel = null;
    }

    /** The song's lyrics from Spotify's lyrics service; time-synced ones light the current line. */
    void openLyrics() {
        if (lyricsPanel != null || ui.uri.isEmpty()) return;
        String track = ui.uri.substring(ui.uri.lastIndexOf(':') + 1);
        String name = ui.title;
        lyricsPanel = new FrameLayout(ui.activity);
        lyricsPanel.setClickable(true);
        lyricsPanel.setBackgroundColor(ui.bg);
        ui.addView(lyricsPanel, new FrameLayout.LayoutParams(-1, -1));
        if (ui.pref("backdrop", true)) lyricsPanel.addView(ui.artwork.new Backdrop(), new FrameLayout.LayoutParams(-1, -1)); // the player's blurred cover, so the two screens match
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(18), ui.inset.top, ui.dp(18), ui.inset.bottom);
        LinearLayout header = ui.row();
        header.addView(ui.iconButton(ui.icon("back", () -> ui.ink), "Close lyrics", this::closeLyrics), ui.lp(ui.dp(48), ui.dp(48)));
        header.addView(ui.text(name, 22, () -> ui.ink, true), ui.weight());
        body.addView(header, ui.fill(ui.dp(64)));
        FrameLayout host = new FrameLayout(ui.activity);
        body.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
        lyricsPanel.addView(body, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout panel = lyricsPanel;
        fillLyrics(host, () -> lyricsPanel == panel, track);
    }

    /** Fills `host` with the song's lyrics (loading, none, or the lines); `alive` says the host is still on screen. */
    private void fillLyrics(FrameLayout host, java.util.function.BooleanSupplier alive, String track) {
        fillLyrics(host, alive, track, null);
    }

    private void fillLyrics(FrameLayout host, java.util.function.BooleanSupplier alive, String track, Runnable onEmpty) {
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
            CoreBridge.Lyrics got = null;
            boolean missing = false;
            try { got = CoreBridge.lyrics(track, songTitle, songArtist, songLength); }
            catch (Throwable failed) {
                // The service answers 404 for a song with no lyrics; anything else is a real failure.
                if (String.valueOf(failed.getMessage()).contains("HTTP 404")) missing = true;
                else android.util.Log.w("SpatchUi", "lyrics failed", failed);
            }
            CoreBridge.Lyrics found = got;
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
                    views[i] = ui.text(line.isEmpty() ? " " : line, synced ? 30 : 22, () -> ui.ink, true);
                    views[i].setLineSpacing(0, 1.05f);
                    if (synced) views[i].setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
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
                            ui.command(SEEK, start, ""); // tap a line to jump there
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
    private void syncLyrics(java.util.function.BooleanSupplier alive, ScrollView scroll, TextView[] views, CoreBridge.Lyrics lyrics, int[] shown, long[] touched) {
        if (!alive.getAsBoolean()) return;
        long[] starts = lyrics.starts;
        if (shown[0] == -2 && (scroll.getHeight() == 0 || views[0].getHeight() == 0)) { // not laid out yet: the centring needs real sizes
            ui.postDelayed(() -> syncLyrics(alive, scroll, views, lyrics, shown, touched), 30);
            return;
        }
        long now = ui.playhead();
        int current = -1;
        for (int i = 0; i < starts.length; i++) if (starts[i] > 0 && starts[i] <= now) current = i;
        if (current != shown[0]) {
            boolean first = shown[0] == -2, glide = ui.motion() && !first;
            if (shown[0] >= 0 && lyrics.wordAt[shown[0]] != null) views[shown[0]].setText(lyrics.words[shown[0]]); // the line just left is whole again
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
        if (walking) {
            int upTo = sungUpTo(lyrics, current, now);
            if (upTo != shown[1]) { shown[1] = upTo; views[current].setText(sungWords(lyrics.words[current], upTo, ui.ink)); }
        }
        focusLyrics(views, current, shown, SystemClock.uptimeMillis() - touched[0] < 4000);
        ui.postDelayed(() -> syncLyrics(alive, scroll, views, lyrics, shown, touched), walking ? 40 : 250);
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

    /** Rotation is opt-in: off locks portrait while on unlocks full landscape rotation. */
    void applyRotation() {
        if (ui.originalOrientation == -2) return;
        ui.activity.setRequestedOrientation(ui.pref("rotate", false) ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    void openQueue() {
        ui.hideKeyboard();
        if (queuePanel == null) {
            queuePanel = new FrameLayout(ui.activity);
            queuePanel.setClickable(true);
            ui.addView(queuePanel, new FrameLayout.LayoutParams(-1, -1));
            queueFresh = true;
        }
        renderQueue();
    }

    private boolean queueFresh;

    void renderQueue() {
        if (queuePanel == null) return;
        int offset = queueScroll == null ? 0 : queueScroll.getScrollY();
        queuePanel.removeAllViews();
        queuePanel.setBackgroundColor(ui.bg);
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(18), ui.inset.top, ui.dp(18), ui.inset.bottom);
        LinearLayout header = ui.row();
        header.addView(ui.iconButton(ui.icon("back", () -> ui.ink), "Close queue", this::closeQueue), ui.lp(ui.dp(48), ui.dp(48)));
        header.addView(ui.text("Queue", 30, () -> ui.ink, true), ui.weight());
        body.addView(header, ui.fill(ui.dp(64)));
        queueScroll = new ScrollView(ui.activity);
        queueScroll.setVerticalScrollBarEnabled(false);
        queueScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        queueScroll.setVerticalFadingEdgeEnabled(true); // rows melt into the top and bottom
        queueScroll.setFadingEdgeLength(ui.dp(56));
        LinearLayout tracks = ui.column();
        tracks.setPadding(0, ui.dp(12), 0, ui.dp(24));
        fillQueueList(tracks, queueFresh, true);
        queueScroll.addView(tracks, new ScrollView.LayoutParams(-1, -2));
        queueFresh = false;
        body.addView(queueScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        queuePanel.addView(body, new FrameLayout.LayoutParams(-1, -1));
        SpatchUi.keepScroll(queueScroll, offset);
    }

    private void fillQueueList(LinearLayout tracks, boolean cascade, boolean showNowPlaying) {
        if (showNowPlaying) {
            tracks.addView(ui.text("Now playing", 14, () -> ui.accent, true), ui.fill(-2));
            tracks.addView(ui.text(ui.title.isEmpty() ? "Nothing playing" : ui.title, 22, () -> ui.ink, true), ui.fill(-2));
            tracks.addView(ui.text(ui.artist, 14, () -> ui.inkDim, false), ui.fill(-2));
        }
        tracks.addView(ui.text(ui.source.isEmpty() ? "Up next" : "Next from " + ui.source, showNowPlaying ? 16 : 14, () -> ui.accent, true), ui.fill(ui.dp(showNowPlaying ? 48 : 36)));
        for (int i = 0; i < ui.upcoming.size(); i++) {
            final int index = i;
            MediaEntry entry = ui.upcoming.get(i);
            LinearLayout line = ui.row();
            Row song = ui.new Row();
            song.bind(entry);
            song.setOnClickListener(v -> ui.command(SKIP_TO, index, entry.queueKey));
            line.addView(song, ui.weight());
            View options = ui.iconButton(ui.icon("more", () -> ui.inkDim), "Queue options for " + entry.title, .90f, () -> queueActions(index, entry));
            line.addView(options, ui.lp(ui.dp(48), ui.dp(48)));
            song.setOnLongClickListener(v -> { queueActions(index, entry); return true; });
            tracks.addView(line, ui.fill(-2));
            if (cascade && ui.motion() && i < 12) { // the first rows cascade in when the queue opens
                line.setAlpha(0f);
                line.setTranslationY(ui.dp(18));
                line.animate().alpha(1f).translationY(0).setStartDelay(60L + i * 30L).setDuration(380).setInterpolator(new android.view.animation.PathInterpolator(.38f, 1.21f, .22f, 1f)).start();
            }
        }
        if (ui.upcoming.isEmpty()) tracks.addView(ui.emptyState("queue", "badge4", "Nothing queued after this", "Add songs from any list with Add to queue"), ui.fill(-2));
    }

    private void queueActions(int index, MediaEntry entry) {
        List<String> options = new ArrayList<>();
        List<Integer> commands = new ArrayList<>();
        if (entry.editable && index > 0 && ui.upcoming.get(index - 1).editable) { options.add("Move up"); commands.add(11); }
        if (entry.editable && index + 1 < ui.upcoming.size() && ui.upcoming.get(index + 1).editable) { options.add("Move down"); commands.add(12); }
        options.add("Add to playlist"); commands.add(-1);
        options.add(ui.downloadsScreen.downloadLabel(entry.uri)); commands.add(-2);
        if (entry.editable) { options.add("Remove from queue"); commands.add(10); }
        ui.sheet(entry.title, entry.subtitle, options.toArray(new String[0]), choice -> { if (commands.get(choice) == -1) ui.addToPlaylist(entry); else if (commands.get(choice) == -2) ui.downloadsScreen.downloadTapped(entry); else ui.command(commands.get(choice), index, entry.queueKey); });
    }

    static String speedLabel(float v) {
        return (Math.abs(v - Math.round(v)) < .01f ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%s", Float.toString(Math.round(v * 100) / 100f))) + "×";
    }

    private void openSpeed() {
        float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f};
        Option[] options = new Option[speeds.length];
        for (int i = 0; i < speeds.length; i++) options[i] = new Option(speedLabel(speeds[i]) + (speeds[i] == 1f ? "  (normal)" : ""), "", "", "play", Math.abs(ui.speed - speeds[i]) < .01f);
        ui.sheet("Playback speed", "For podcasts and audiobooks", options, c -> {
            ui.speed = speeds[c];
            if (ui.pSpeed != null) ui.pSpeed.setText(speedLabel(ui.speed));
            ui.command(SPEED, Math.round(speeds[c] * 100), "");
        });
    }

    private int sleepChoice;
    private Runnable sleepBadge;

    private static String formatSleepLeft(long ms) {
        if (ms < 600_000L) {
            long s = Math.max(1, (ms + 999) / 1000);
            return (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60);
        }
        return Math.max(1, (ms + 59_999) / 60_000) + " min";
    }

    private void scheduleSleep(long delayMs, int choice, String toast) {
        sleepChoice = choice;
        SpatchUi.sleepTotalMs = delayMs <= 0 ? 0 : (choice >= 0 ? delayMs : Math.max(SpatchUi.sleepTotalMs, delayMs));
        SpatchUi.MAIN.removeCallbacks(SpatchUi.SLEEP);
        SpatchUi.sleepAt = delayMs <= 0 ? 0 : SystemClock.uptimeMillis() + delayMs;
        if (delayMs > 0) SpatchUi.MAIN.postDelayed(SpatchUi.SLEEP, delayMs);
        if (sleepBadge != null) sleepBadge.run();
        Toast.makeText(ui.activity, toast, Toast.LENGTH_SHORT).show();
    }

    private void openSleepTimer() {
        String[] names = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "End of this track"};
        long left = SpatchUi.sleepAt - SystemClock.uptimeMillis();
        String note = left > 0 ? "Pausing in " + formatSleepLeft(left) : "Pause playback later";
        int current = left > 0 ? sleepChoice : 0;
        List<Option> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            options.add(new Option(names[i], "", "", i == 0 ? "close" : "timer", i == current));
            actions.add(() -> {
                long delay = idx == 0 ? 0 : idx == 5 ? Math.max(1000, ui.duration - ui.playhead() - 500) : new long[]{0, 15, 30, 45, 60}[idx] * 60_000L;
                scheduleSleep(delay, idx, idx == 0 ? "Sleep timer off" : "Pausing " + (idx == 5 ? "after this track" : "in " + names[idx]));
            });
        }
        if (left > 0) {
            options.add(new Option("+5 minutes", "Add 5 minutes", "", "timer", false));
            actions.add(() -> {
                long nextDelay = Math.max(60_000L, SpatchUi.sleepAt - SystemClock.uptimeMillis() + 5 * 60_000L);
                scheduleSleep(nextDelay, -1, "Pausing in " + formatSleepLeft(nextDelay));
            });
            if (left > 5 * 60_000L) {
                options.add(new Option("-5 minutes", "Subtract 5 minutes", "", "timer", false));
                actions.add(() -> {
                    long nextDelay = Math.max(60_000L, SpatchUi.sleepAt - SystemClock.uptimeMillis() - 5 * 60_000L);
                    scheduleSleep(nextDelay, -1, "Pausing in " + formatSleepLeft(nextDelay));
                });
            }
        }
        if (left > 0) ui.sheetExtra = ui.new SleepRingView();
        ui.sheet("Sleep timer", note, options.toArray(new Option[0]), choice -> actions.get(choice).run());
    }

    /** Lists the devices Spotify can play on and moves playback to the one you pick. */
    private void openDevices() {
        if (OfficialPlayback.enabled()) {
            Toast.makeText(ui.activity, "Choose playback devices in official Spotify", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(ui.activity, "Looking for devices…", Toast.LENGTH_SHORT).show();
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            String[] f = SpatchUi.nativeDevices();
            final int level = SpatchUi.nativeVolume();
            ui.post(() -> {
                if (f == null || f.length < 5) { Toast.makeText(ui.activity, "No devices found", Toast.LENGTH_SHORT).show(); return; }
                if (level >= 0) ui.sheetExtra = ui.controls.new VolumeBar(level);
                int count = f.length / 5;
                Option[] devices = new Option[count];
                for (int i = 0; i < count; i++) {
                    boolean active = Boolean.parseBoolean(f[i * 5 + 3]), here = Boolean.parseBoolean(f[i * 5 + 4]);
                    String kind = f[i * 5 + 1];
                    String detail = (here ? "This phone" : kind) + (active ? " • Playing" : "");
                    devices[i] = new Option(f[i * 5], detail, "", here ? "devices" : "volume", active);
                }
                ui.sheet("Play on", "Pick where your music should play", devices, i -> {
                    String id = f[i * 5 + 2];
                    ui.submit(SpatchUi.PLAYLISTS, () -> {
                        String error = SpatchUi.nativeTransfer(id);
                        ui.post(() -> Toast.makeText(ui.activity, error == null || error.isEmpty() ? "Playing on " + f[i * 5] : "Couldn’t switch: " + error, Toast.LENGTH_LONG).show());
                    });
                });
            });
        });
    }

    private boolean share() {
        for (String kind : new String[]{"track", "episode"}) {
            String prefix = "spotify:" + kind + ":";
            if (!ui.uri.startsWith(prefix)) continue;
            String link = "https://open.spotify.com/" + kind + "/" + ui.uri.substring(prefix.length());
            ui.sheet(ui.title, ui.artist, new String[]{"Share song link", "Copy song link"}, c -> {
                if (c == 0) {
                    android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(android.content.Intent.EXTRA_TEXT, link);
                    ui.activity.startActivity(android.content.Intent.createChooser(send, ui.title));
                } else {
                    android.content.ClipboardManager clip = (android.content.ClipboardManager) ui.activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clip != null) clip.setPrimaryClip(android.content.ClipData.newPlainText(ui.title, link));
                }
            });
            return true;
        }
        return false;
    }

    void checkSaved(String track) {
        int request = ++savedRequest;
        savedUri = track;
        ui.saved = null;
        if (!track.startsWith("spotify:track:") && !track.startsWith("spotify:episode:") && !track.startsWith("spotify:preview:")) return;
        ui.submit(SpatchUi.DATA, () -> {
            int a = SpatchUi.nativeSaved(track);
            ui.post(() -> { if (request == savedRequest && track.equals(savedUri)) { ui.saved = a < 0 ? null : a == 1; ui.refresh(); } });
        });
    }

    /** A like's little burst: a ring pops outward and six dots fly off it, fading, over the heart. */
    private void burst(View host) {
        final float[] t = {0f};
        final int ringColour = ui.accent, dotColour = ui.accent2;
        android.graphics.drawable.Drawable pop = new android.graphics.drawable.Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                float f = t[0], cx = host.getWidth() / 2f, cy = host.getHeight() / 2f, max = Math.min(cx, cy);
                int alpha = Math.round(255 * (1f - f));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.dp(2.5f) * (1f - f) + 1);
                paint.setColor(ringColour);
                paint.setAlpha(alpha);
                c.drawCircle(cx, cy, max * (.35f + .6f * f), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(dotColour);
                paint.setAlpha(alpha);
                for (int i = 0; i < 6; i++) {
                    double a = Math.PI * 2 * i / 6 - Math.PI / 2;
                    float d = max * (.45f + .5f * f);
                    c.drawCircle(cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d, ui.dp(2.6f) * (1f - f * .6f), paint);
                }
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
        pop.setBounds(0, 0, host.getWidth(), host.getHeight());
        host.getOverlay().add(pop);
        android.animation.ValueAnimator run = android.animation.ValueAnimator.ofFloat(0f, 1f);
        run.setDuration(520);
        run.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        run.addUpdateListener(a -> { t[0] = (float) a.getAnimatedValue(); pop.invalidateSelf(); host.invalidate(); });
        run.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { host.getOverlay().remove(pop); }
        });
        run.start();
    }

    private void openFromPlayer(String link, String name, String detail) {
        if (link.isEmpty()) return;
        ui.tick(ui.player);
        closePlayer();
        ui.playlistScreen.openPlaylist(new MediaEntry(name, detail, link, link.startsWith("spotify:album:") ? ui.image : "", -1));
    }

    private void toggleSaved() {
        if (!ui.uri.isEmpty()) setLikedSong(new MediaEntry(ui.title, ui.artist, ui.uri, ui.image, -1), !Boolean.TRUE.equals(ui.saved));
    }

    void setLikedSong(MediaEntry song, boolean want) {
        String track = song.uri;
        boolean current = track.equals(ui.uri);
        Boolean before = current ? ui.saved : song instanceof Song ? ((Song) song).liked : null;
        int request = current ? ++savedRequest : -1;
        if (current) { savedUri = track; ui.saved = want; }
        if (song instanceof Song) ((Song) song).liked = want;
        ui.refresh();
        if (want && current && ui.motion() && ui.pLike != null) {
            ui.pLike.setScaleX(.5f);
            ui.pLike.setScaleY(.5f);
            ui.pLike.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(3.2f)).start();
            if (ui.pLike.getParent() instanceof View) burst((View) ui.pLike.getParent());
            Confetti.hearts(ui.pLike, ui.prefs, ui.accent, ui.accent2, true);
        }
        ui.submit(SpatchUi.COMMANDS, () -> {
            boolean ok = SpatchUi.nativeSave(track, want);
            ui.post(() -> {
                if (ui.closed) return;
                if (!ok) {
                    if (request == savedRequest && track.equals(ui.uri)) ui.saved = before;
                    if (song instanceof Song && ((Song) song).liked == want) ((Song) song).liked = Boolean.TRUE.equals(before);
                }
                ui.refresh();
                Toast.makeText(ui.activity, !ok ? "Couldn’t update Liked Songs" : want ? "Added to Liked Songs" : "Removed from Liked Songs", Toast.LENGTH_SHORT).show();
            });
        });
    }
}
