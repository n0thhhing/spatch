package spatch;

import spatch.ArtworkViews.Art;
import spatch.Controls.Bars;
import spatch.Controls.PlayButton;
import static spatch.PackedStrings.unpackStrings;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Home page with its recently played and library shelves. */
final class HomeScreen {
    private final SpatchUi ui;
    HomeScreen(SpatchUi ui) { this.ui = ui; }

    View homePage() {
        LinearLayout page = ui.column();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        final String shapeKey = hour < 5 ? "badge4" : hour < 12 ? "badge8" : hour < 18 ? "badge6" : "button5";
        View greetBadge = new View(ui.activity) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight(), s = Math.min(w, h), cx = w / 2f, cy = h / 2f;
                if (s <= 0) return;
                float[] pts = SpatchUi.GLYPHS.get(shapeKey);
                if (pts != null && pts.length >= 6) {
                    float r = s * .46f;
                    path.rewind();
                    for (int i = 0; i < pts.length; i += 2) {
                        float x = cx + pts[i] * r, y = cy + pts[i + 1] * r;
                        if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    path.close();
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(SpatchUi.blend(ui.surfHi, ui.accent, ui.dark ? .34f : .24f));
                    c.drawPath(path, paint);
                }
                c.save();
                float gs = s * .48f;
                c.translate(cx - gs / 2f, cy - gs / 2f);
                SpatchUi.drawGlyph(c, paint, path, "sparkle", ui.accent, gs);
                c.restore();
            }
        };
        ui.colored(greetBadge, greetBadge::invalidate);
        TextView h = ui.pageHeading(page, hour < 5 ? "Up late?" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening", false, greetBadge);
        final float[] greetSpin = {0f};
        Runnable spinGreet = () -> {
            ui.tick(greetBadge);
            greetSpin[0] += 360f;
            if (ui.motion()) {
                greetBadge.animate().cancel();
                greetBadge.setScaleX(1.16f);
                greetBadge.setScaleY(1.16f);
                greetBadge.animate().rotation(greetSpin[0]).scaleX(1f).scaleY(1f).setDuration(560)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.15f)).start();
            } else {
                greetBadge.setRotation(greetSpin[0] % 360f);
            }
        };
        greetBadge.setClickable(true);
        greetBadge.setFocusable(true);
        greetBadge.setContentDescription("Spin greeting icon");
        greetBadge.setOnClickListener(v -> spinGreet.run());
        h.setClickable(true);
        h.setOnClickListener(v -> spinGreet.run());
        // Expressive type: the first time Home appears the greeting swells from light to bold (variable fonts only).
        if (!greeted && ui.motion() && ui.pref("expressive_type", true) && h.getFontVariationSettings() != null) {
            greeted = true;
            android.animation.ValueAnimator swell = android.animation.ValueAnimator.ofInt(250, 720);
            swell.setDuration(900);
            swell.setStartDelay(120);
            swell.setInterpolator(SpatchUi.EMPHASIZED);
            swell.addUpdateListener(a -> h.setFontVariationSettings("'wght' " + a.getAnimatedValue() + ", 'ROND' 100"));
            h.setFontVariationSettings("'wght' 250, 'ROND' 100");
            swell.start();
        }
        TextView sub = ui.text("Here’s your music", 15, () -> ui.inkDim, false);
        sub.setPadding(0, 0, 0, ui.dp(16));
        page.addView(sub);

        FrameLayout hero = new FrameLayout(ui.activity);
        ui.bgRound(hero, 30, () -> ui.surf);
        hero.setClipToOutline(true);
        ui.heroArt = ui.artwork.new Art(Art.WIDE, false);
        ui.heroArt.crossfade = true;
        hero.addView(ui.heroArt, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(ui.activity);
        GradientDrawable scrimBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{ui.surf & 0x00ffffff, (ui.surf & 0x00ffffff) | 0xc8000000, ui.surf});
        scrim.setBackground(scrimBg);
        ui.colored(scrim, () -> scrimBg.setColors(new int[]{ui.surf & 0x00ffffff, (ui.surf & 0x00ffffff) | 0xc8000000, ui.surf}));
        hero.addView(scrim, new FrameLayout.LayoutParams(-1, ui.dp(130), Gravity.BOTTOM));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(18), 0, ui.dp(84), ui.dp(16));
        ui.heroTitle = ui.text("", 26, () -> ui.ink, true);
        ui.heroArtist = ui.text("", 14, () -> ui.inkDim, false);
        ui.oneLine(ui.heroTitle); ui.oneLine(ui.heroArtist);
        words.addView(ui.heroTitle);
        words.addView(ui.heroArtist);
        hero.addView(words, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        // Play or pause right from the banner.
        PlayButton heroPlay = ui.controls.new PlayButton();
        heroPlay.set(ui.pending != null ? ui.pending : ui.playing);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ui.dp(58), ui.dp(58), Gravity.BOTTOM | Gravity.END);
        hp.setMargins(0, 0, ui.dp(16), ui.dp(14));
        hero.addView(heroPlay, hp);
        ui.tappable(hero, "Open player", () -> ui.playerScreen.openPlayer(true));

        LinearLayout liked = ui.row();
        liked.setPadding(ui.dp(14), ui.dp(12), ui.dp(16), ui.dp(12));
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{SpatchUi.blend(ui.surfHi, ui.accent2, .28f), SpatchUi.blend(ui.surfHi, ui.accent, .22f)});
        lg.setCornerRadius(ui.dp(26));
        liked.setBackground(lg);
        ui.colored(liked, () -> lg.setColors(new int[]{SpatchUi.blend(ui.surfHi, ui.accent2, .28f), SpatchUi.blend(ui.surfHi, ui.accent, .22f)}));
        FrameLayout badge = new FrameLayout(ui.activity);
        ui.bgRound(badge, 18, () -> (ui.ink & 0x00ffffff) | 0x26000000);
        badge.addView(ui.icon("heart", () -> ui.ink), new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        liked.addView(badge, ui.lp(ui.dp(44), ui.dp(44)));
        LinearLayout lw = ui.column();
        lw.setPadding(ui.dp(14), 0, 0, 0);
        TextView likedTitle = ui.text("Liked Songs", 17, () -> ui.ink, true);
        TextView likedSub = ui.text("Everything you’ve hearted", 13, () -> ui.inkDim, false);
        ui.oneLine(likedTitle); ui.oneLine(likedSub);
        lw.addView(likedTitle);
        lw.addView(likedSub);
        liked.addView(lw, ui.weight());
        ui.tappable(liked, "Open Liked Songs", () -> ui.playlistScreen.openPlaylist(new MediaEntry("Liked Songs", "", "spotify:collection:tracks", "", 0)));

        if (ui.landscape()) {
            LinearLayout topRow = ui.row();
            topRow.addView(hero, new LinearLayout.LayoutParams(0, ui.dp(190), 1.45f));
            LinearLayout quickCol = ui.column();
            quickCol.addView(liked, new LinearLayout.LayoutParams(-1, 0, 1f));
            LinearLayout queueCard = ui.row();
            queueCard.setPadding(ui.dp(14), ui.dp(12), ui.dp(16), ui.dp(12));
            ui.bgRound(queueCard, 26, () -> ui.surfHi);
            FrameLayout qBadge = new FrameLayout(ui.activity);
            ui.bgRound(qBadge, 18, () -> (ui.ink & 0x00ffffff) | 0x1a000000);
            qBadge.addView(ui.icon("queue", () -> ui.ink), new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
            queueCard.addView(qBadge, ui.lp(ui.dp(44), ui.dp(44)));
            LinearLayout qw = ui.column();
            qw.setPadding(ui.dp(14), 0, 0, 0);
            TextView qTitle = ui.text("Up next", 17, () -> ui.ink, true);
            TextView qSub = ui.text(ui.upcoming.isEmpty() ? "Open your queue" : ui.upcoming.get(0).title, 13, () -> ui.inkDim, false);
            ui.oneLine(qTitle); ui.oneLine(qSub);
            qw.addView(qTitle);
            qw.addView(qSub);
            queueCard.addView(qw, ui.weight());
            ui.tappable(queueCard, "Open queue", ui.playerScreen::openQueue);
            LinearLayout.LayoutParams qcp = new LinearLayout.LayoutParams(-1, 0, 1f);
            qcp.topMargin = ui.dp(10);
            quickCol.addView(queueCard, qcp);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(0, ui.dp(190), 1f);
            colParams.leftMargin = ui.dp(14);
            topRow.addView(quickCol, colParams);
            page.addView(topRow, ui.fill(ui.dp(190)));
        } else {
            page.addView(hero, ui.fill(ui.dp(200)));
            LinearLayout.LayoutParams likedParams = ui.fill(-2);
            likedParams.topMargin = ui.dp(12);
            page.addView(liked, likedParams);
        }

        boolean any = false, hidden = false;
        if (!ui.pref("home_recent", true)) hidden = true;
        else if (!recent.isEmpty()) any |= shelf(page, "Recently played", recent, null);
        for (int c = 0; c < SpatchUi.SECTIONS.length; c++) {
            final int category = c;
            if (!ui.pref("home_shelf_" + c, true)) { hidden = true; continue; }
            any |= shelf(page, SpatchUi.SECTIONS[c], ui.entries(c, 14), () -> { ui.filter = category; ui.goFromLink(SpatchUi.LIBRARY); });
        }
        if (!any && (!hidden || !ui.libraryReady)) {
            TextView note = ui.text(ui.libraryStatus, 14, () -> ui.inkDim, false);
            note.setPadding(ui.dp(4), ui.dp(24), 0, 0);
            page.addView(note);
            ui.libraryScreen.libraryRecoveryActions(page, false);
        }
        return ui.scrolling(page);
    }

    List<MediaEntry> recent = new ArrayList<>();
    private long recentAt;
    private boolean loadingRecent;

    void loadRecentIfStale() {
        long now = SystemClock.uptimeMillis();
        if (!ui.libraryReady || loadingRecent || (recentAt != 0 && now - recentAt < 60_000)) return;
        loadingRecent = true;
        boolean first = recentAt == 0 && recent.isEmpty();
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            if (first) {
                String[] saved = unpackStrings(SpatchUi.nativeCached("recent"));
                if (saved != null) ui.post(() -> {
                    if (!recent.isEmpty()) return;
                    recent = ui.entryList(saved);
                    if ((ui.tab == SpatchUi.HOME || ui.tab == SpatchUi.STATS) && ui.playlist == null && ui.sheetClose == null) ui.showPage();
                });
            }
            byte[] freshBytes = SpatchUi.nativeRecent();
            if (freshBytes != null && freshBytes.length > 0) SpatchUi.nativeRemember("recent", freshBytes);
            List<MediaEntry> got = ui.entryList(unpackStrings(freshBytes));
            ui.post(() -> {
                loadingRecent = false;
                recentAt = SystemClock.uptimeMillis();
                if (got.isEmpty() || got.equals(recent)) return;
                recent = got;
                if ((ui.tab == SpatchUi.HOME || ui.tab == SpatchUi.STATS) && ui.playlist == null && ui.sheetClose == null) ui.showPage();
            });
        });
    }

    boolean shelf(LinearLayout page, String heading, List<MediaEntry> items, Runnable seeAll) {
        if (items.isEmpty()) return false;
        LinearLayout head = ui.row();
        head.setPadding(0, ui.dp(24), 0, ui.dp(10));
        head.addView(ui.text(heading, 22, () -> ui.ink, true), ui.weight());
        if (seeAll != null) {
            TextView all = ui.text("See all", 14, () -> ui.accent, true);
            ui.tappable(all, "See all " + heading, seeAll);
            head.addView(all);
        }
        page.addView(head, ui.fill(-2));
        HorizontalScrollView scroller = new HorizontalScrollView(ui.activity);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout tiles = ui.row();
        int width = Math.max(120, Math.min(160, (ui.getResources().getConfiguration().screenWidthDp - 50) / 2));
        for (MediaEntry e : items) {
            LinearLayout tile = ui.column();
            Art art = ui.artwork.new Art(e.category == 2 ? Art.CIRCLE : Art.ROUNDED, false);
            art.letter = SpatchUi.initial(e.title);
            art.heart = SpatchUi.liked(e.uri) && e.image.isEmpty();
            art.bind(e.image);
            FrameLayout cover = new FrameLayout(ui.activity);
            cover.addView(art, new FrameLayout.LayoutParams(-1, -1));
            // What's playing gets the little moving bars in a chip on its cover.
            if (ui.playingFrom(e.uri)) {
                FrameLayout chip = new FrameLayout(ui.activity);
                ui.bgRound(chip, 12, () -> (ui.bg & 0x00ffffff) | 0xcc000000);
                chip.addView(ui.controls.new Bars(), new FrameLayout.LayoutParams(ui.dp(16), ui.dp(14), Gravity.CENTER));
                FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ui.dp(34), ui.dp(26), Gravity.BOTTOM | Gravity.END);
                cp.setMargins(0, 0, ui.dp(e.category == 2 ? 18 : 8), ui.dp(e.category == 2 ? 18 : 8));
                cover.addView(chip, cp);
            }
            int shelfDownload = ui.downloadsScreen.downloadState(e.uri);
            if (shelfDownload != 0) {
                FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.BOTTOM | Gravity.START);
                hp.setMargins(ui.dp(8), 0, 0, ui.dp(8));
                cover.addView(ui.downloadsScreen.downloadChip(shelfDownload), hp);
            }
            tile.addView(cover, ui.lp(ui.dp(width), ui.dp(width)));
            TextView name = ui.text(e.title, 14, () -> ui.playingFrom(e.uri) ? ui.accent : ui.ink, true);
            ui.oneLine(name);
            name.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), 0);
            tile.addView(name);
            if (!e.subtitle.isEmpty()) {
                TextView sub = ui.text(e.subtitle, 12, () -> ui.inkDim, false);
                ui.oneLine(sub);
                sub.setPadding(ui.dp(2), 0, ui.dp(2), 0);
                tile.addView(sub);
            }
            if (!e.uri.isEmpty()) ui.tappable(tile, e.title, () -> { ui.playlistScreen.captureFlight(art, e.image, e.category == 2); ui.open(e); });
            LinearLayout.LayoutParams p = ui.lp(ui.dp(width), -2);
            p.rightMargin = ui.dp(14);
            tiles.addView(tile, p);
        }
        scroller.addView(tiles);
        // Shelves run edge to edge, so cards scroll off the screen rather than being cut at the page margin.
        scroller.setClipToPadding(false);
        scroller.setPadding(ui.dp(18), 0, ui.dp(18), 0);
        LinearLayout.LayoutParams sp = ui.fill(-2);
        sp.leftMargin = -ui.dp(18);
        sp.rightMargin = -ui.dp(18);
        page.addView(scroller, sp);
        return true;
    }

    /** Pages opened from another page (an album from a playlist...), so back retraces them. */
    private static boolean greeted;
}
