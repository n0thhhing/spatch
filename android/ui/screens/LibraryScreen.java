package spatch;

import spatch.Dock.DockScroll;
import spatch.SpatchUi.Row;
import spatch.SpatchUi.Tile;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;

/** Library page with its category filters and recovery actions. */
final class LibraryScreen {
    private final SpatchUi ui;
    LibraryScreen(SpatchUi ui) { this.ui = ui; }

    void libraryRecoveryActions(LinearLayout page, boolean edgeToEdge) {
        // A pending/failed library request does not establish that the account is signed out.
        if (ui.libraryReady || ui.fetching || ui.libraryStatus.startsWith("Connecting")) return;
        LinearLayout actions = ui.row();
        if (edgeToEdge) actions.setPadding(ui.dp(22), 0, ui.dp(22), 0);
        actions.addView(ui.pill("Retry", () -> {
            if (ui.fetching) return;
            ui.libraryStatus = "Connecting to your library…";
            ui.loadLibrary();
            ui.showPageKeepingPlace();
        }));
        actions.addView(ui.space(ui.dp(10), 1));
        actions.addView(ui.pill("Open Spotify", ui::openNativeSpotify));
        page.addView(actions, ui.lp(-2, -2));
    }

    /** Scrolls a horizontal filter strip only if `chip` is clipped near an edge, avoiding jarring recentering jumps. */
    private void ensureChipVisible(HorizontalScrollView scroll, View chip, View container) {
        if (scroll == null || chip == null || scroll.getWidth() <= 0 || chip.getWidth() <= 0) return;
        int origin = container != null ? container.getLeft() : 0;
        int left = origin + chip.getLeft(), right = left + chip.getWidth();
        int cur = scroll.getScrollX(), viewW = scroll.getWidth(), pad = ui.dp(16);
        if (left < cur + pad) scroll.smoothScrollTo(Math.max(0, left - pad), 0);
        else if (right > cur + viewW - pad) scroll.smoothScrollTo(Math.max(0, right - viewW + pad), 0);
    }

    View libraryPage() {
        LinearLayout page = ui.column();
        page.setPadding(0, ui.inset.top, 0, 0);
        if (!ui.tabHistory.isEmpty() && ui.tabHistory.peek() == SpatchUi.PROFILE) {
            page.addView(ui.backHeader("Profile", true), ui.lp(-2, ui.dp(36)));
        }
        ui.pageHeading(page, "Library", true);

        boolean land = ui.landscape();
        LinearLayout search = ui.row();
        search.setPadding(ui.dp(16), 0, ui.dp(8), 0);
        ui.bgRound(search, 26, () -> ui.surf);
        search.addView(ui.icon("search", () -> ui.inkDim), ui.lp(ui.dp(22), ui.dp(22)));
        android.widget.EditText field = new android.widget.EditText(ui.activity);
        field.setBackground(null);
        field.setHint("Search your library");
        ui.colored(field, () -> { field.setHintTextColor(ui.inkDim); field.setTextColor(ui.ink); });
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(ui.dp(12), 0, ui.dp(4), 0);
        field.setText(ui.query);
        search.addView(field, new LinearLayout.LayoutParams(0, ui.dp(land ? 44 : 50), 1));
        ui.clearButton(search, field);

        HorizontalScrollView chipScroll = new HorizontalScrollView(ui.activity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setHorizontalFadingEdgeEnabled(true);
        chipScroll.setFadingEdgeLength(ui.dp(24));
        chipScroll.setPadding(ui.dp(land ? 12 : 18), 0, ui.dp(land ? 4 : 18), ui.dp(land ? 0 : 6));
        LinearLayout chips = ui.row();
        boolean[] azState = { ui.pref("library_az", false) };
        TextView sort = ui.text(azState[0] ? "A–Z" : "Recent", 14, ui.ink, true);
        sort.setGravity(Gravity.CENTER);
        sort.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        GradientDrawable sortBg = ui.round(ui.surfHi, 19);
        sort.setBackground(sortBg);
        ui.colored(sort, () -> { sort.setTextColor(ui.ink); sortBg.setColor(ui.surfHi); });
        chips.addView(sort, ui.lp(-2, ui.dp(38)));
        chips.addView(ui.space(ui.dp(8), 1));
        boolean[] gridState = { ui.pref("library_grid", false) };
        TextView layout = ui.text(gridState[0] ? "Grid" : "List", 14, ui.ink, true);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        GradientDrawable layoutBg = ui.round(ui.surfHi, 19);
        layout.setBackground(layoutBg);
        ui.colored(layout, () -> { layout.setTextColor(ui.ink); layoutBg.setColor(ui.surfHi); });
        chips.addView(layout, ui.lp(-2, ui.dp(38)));
        chips.addView(ui.space(ui.dp(8), 1));

        int count = SpatchUi.SECTIONS.length + 2;
        TextView[] filterChips = new TextView[count];
        LinearLayout filterRow = ui.row();
        // The selected chip's colour is a pill drawn behind the chips. Its two edges each ride a spring, the leading one
        // stiffer, so it stretches toward the new chip and settles there (like the dock's tab indicator) without
        // laying anything out while it moves.
        float[] pillEdges = new float[2];
        FrameLayout[] trackRef = new FrameLayout[1];
        Controls.Spring[] pillSprings = {
            new Controls.Spring(0f, 20f, .62f, v -> { pillEdges[0] = v; if (trackRef[0] != null) trackRef[0].invalidate(); }),
            new Controls.Spring(0f, 20f, .62f, v -> { pillEdges[1] = v; if (trackRef[0] != null) trackRef[0].invalidate(); }) };
        Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        FrameLayout filterTrack = new FrameLayout(ui.activity) {
            private boolean placed;
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                TextView target = filterChips[Math.max(0, Math.min(count - 1, ui.filter + 1))];
                if (!placed && target != null && target.getWidth() > 0) {
                    placed = true;
                    pillSprings[0].snap(target.getLeft());
                    pillSprings[1].snap(target.getRight());
                }
            }
            @Override protected void dispatchDraw(Canvas c) {
                if (placed) {
                    float h = getHeight();
                    pillPaint.setColor(ui.accent);
                    c.drawRoundRect(pillEdges[0], 0, Math.max(pillEdges[0] + h, pillEdges[1]), h, h / 2f, h / 2f, pillPaint);
                }
                super.dispatchDraw(c);
            }
        };
        trackRef[0] = filterTrack;
        ui.bgRound(filterTrack, 19, () -> ui.surf);
        ui.colored(filterTrack, filterTrack::invalidate);
        filterTrack.addView(filterRow, new FrameLayout.LayoutParams(-2, ui.dp(38)));

        for (int i = -1; i <= SpatchUi.SECTIONS.length; i++) {
            final int index = i, slot = i + 1;
            String labelText = i == -1 ? "All" : i == SpatchUi.DOWNLOADED ? "Downloaded" : SpatchUi.SECTIONS[i];
            TextView chip = ui.text(labelText, 14, () -> ui.filter == index ? ui.onAccent : ui.ink, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            chip.setBackground(null);
            filterChips[slot] = chip;
            filterRow.addView(chip, ui.lp(-2, ui.dp(38)));
        }
        chips.addView(filterTrack, ui.lp(-2, ui.dp(38)));
        chipScroll.addView(chips);
        if (ui.filter >= 0) {
            final View picked = filterChips[Math.min(count - 1, ui.filter + 1)];
            chipScroll.post(() -> ensureChipVisible(chipScroll, picked, filterTrack));
        }

        if (land) {
            LinearLayout bar = ui.row();
            bar.addView(search, new LinearLayout.LayoutParams(0, ui.dp(44), .40f));
            bar.addView(chipScroll, new LinearLayout.LayoutParams(0, -2, .60f));
            LinearLayout.LayoutParams bp = ui.fill(-2);
            bp.setMargins(ui.dp(18), 0, ui.dp(18), ui.dp(8));
            page.addView(bar, bp);
        } else {
            LinearLayout.LayoutParams sp = ui.fill(ui.dp(50));
            sp.setMargins(ui.dp(18), 0, ui.dp(18), ui.dp(10));
            page.addView(search, sp);
            LinearLayout.LayoutParams cp = ui.fill(-2);
            cp.bottomMargin = ui.dp(4);
            page.addView(chipScroll, cp);
        }

        FrameLayout contentHost = new FrameLayout(ui.activity);
        page.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1));
        Runnable[] onQuery = new Runnable[1];

        java.util.function.IntConsumer renderContent = dir -> {
            contentHost.removeAllViews();
            ui.pageList = null;
            ui.libraryList = null;
            onQuery[0] = null;
            List<MediaEntry> shown = ui.entries(ui.filter, ui.query, azState[0], 400);
            View shownView;
            if (shown.isEmpty() && ui.query.isEmpty()) {
                LinearLayout emptyBox = ui.column();
                TextView note = ui.text(ui.filter == SpatchUi.DOWNLOADED && ui.libraryReady ? "Nothing downloaded yet. Open a playlist, album or podcast and tap its download button." : ui.libraryStatus, 14, () -> ui.inkDim, false);
                note.setPadding(ui.dp(22), ui.dp(20), ui.dp(22), 0);
                emptyBox.addView(note);
                libraryRecoveryActions(emptyBox, true);
                shownView = emptyBox;
            } else {
                LinearLayout listBox = ui.column();
                TextView noMatch = ui.text("Nothing in your library matches “" + ui.query.trim() + "”", 15, () -> ui.inkDim, false);
                noMatch.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), 0);
                noMatch.setVisibility(shown.isEmpty() && !ui.query.trim().isEmpty() ? View.VISIBLE : View.GONE);
                listBox.addView(noMatch);
                android.widget.AbsListView list = gridState[0] ? entryGrid(shown) : (land ? entryListGrid(shown) : ui.entryList(shown, null));
                list.setOnScrollListener(ui.dockUi.new DockScroll());
                list.setPadding(ui.dp(gridState[0] ? 14 : 18), ui.dp(4), ui.dp(gridState[0] ? 14 : 18), ui.dockRoom());
                list.setClipToPadding(false);
                listBox.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
                if (list instanceof ListView) ui.pageList = (ListView) list;
                ui.libraryList = list;
                onQuery[0] = () -> {
                    shown.clear();
                    shown.addAll(ui.entries(ui.filter, ui.query, azState[0], 400));
                    ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
                    noMatch.setText("Nothing in your library matches “" + ui.query.trim() + "”");
                    noMatch.setVisibility(shown.isEmpty() && !ui.query.trim().isEmpty() ? View.VISIBLE : View.GONE);
                };
                shownView = listBox;
            }
            contentHost.addView(shownView, new FrameLayout.LayoutParams(-1, -1));
            if (dir != 0 && ui.motion()) {
                shownView.setAlpha(0f);
                shownView.setTranslationX(dir * ui.dp(28));
                shownView.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(SpatchUi.EMPHASIZED).start();
            }
        };

        ui.tappable(sort, azState[0] ? "Sorted A to Z, tap for recent" : "Sorted by recent, tap for A to Z", () -> {
            azState[0] = !azState[0];
            ui.prefs.edit().putBoolean("library_az", azState[0]).apply();
            sort.setText(azState[0] ? "A–Z" : "Recent");
            sort.setContentDescription(azState[0] ? "Sorted A to Z, tap for recent" : "Sorted by recent, tap for A to Z");
            renderContent.accept(0);
        });
        ui.tappable(layout, gridState[0] ? "Shown as a grid, tap for a list" : "Shown as a list, tap for a grid", () -> {
            gridState[0] = !gridState[0];
            ui.prefs.edit().putBoolean("library_grid", gridState[0]).apply();
            layout.setText(gridState[0] ? "Grid" : "List");
            layout.setContentDescription(gridState[0] ? "Shown as a grid, tap for a list" : "Shown as a list, tap for a grid");
            renderContent.accept(0);
        });

        for (int i = -1; i <= SpatchUi.SECTIONS.length; i++) {
            final int index = i, slot = i + 1;
            TextView chip = filterChips[slot];
            ui.tappable(chip, chip.getText().toString(), () -> {
                if (ui.filter == index) return;
                int prev = ui.filter;
                ui.filter = index;
                for (TextView c : filterChips) SpatchUi.recolorView(c);
                if (chip.getWidth() > 0 && ui.motion()) {
                    boolean right = index > prev;
                    // The edge moving toward the new chip leads; the other follows a little looser.
                    pillSprings[right ? 1 : 0].tune(26f, .62f);
                    pillSprings[right ? 0 : 1].tune(17f, .66f);
                    pillSprings[0].to(chip.getLeft());
                    pillSprings[1].to(chip.getRight());
                } else if (chip.getWidth() > 0) {
                    pillSprings[0].snap(chip.getLeft());
                    pillSprings[1].snap(chip.getRight());
                }
                ensureChipVisible(chipScroll, chip, filterTrack);
                renderContent.accept(ui.filter > prev ? 1 : -1);
            });
        }

        renderContent.accept(0);
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                ui.query = e.toString();
                if (onQuery[0] != null) onQuery[0].run();
            }
        });
        return page;
    }

    /** The library as big covers: two columns in portrait, four in landscape. */
    private android.widget.GridView entryGrid(List<MediaEntry> items) {
        return mediaGrid(items, ui.landscape() ? 4 : 2, ui.landscape() ? 14 : 12, 16, true);
    }

    /** Landscape library list: two columns of compact rows so the wider viewport stays dense and scannable. */
    private android.widget.GridView entryListGrid(List<MediaEntry> items) {
        return mediaGrid(items, 2, 16, 2, false);
    }

    private android.widget.GridView mediaGrid(List<MediaEntry> items, int cols, int hGapDp, int vGapDp, boolean tiles) {
        android.widget.GridView grid = new android.widget.GridView(ui.activity);
        grid.setNumColumns(cols);
        grid.setStretchMode(android.widget.GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(ui.dp(hGapDp));
        grid.setVerticalSpacing(ui.dp(vGapDp));
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public MediaEntry getItem(int i) { return items.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View old, ViewGroup parent) {
                if (tiles) {
                    Tile tile = old instanceof Tile ? (Tile) old : ui.new Tile();
                    tile.bind(getItem(i));
                    return tile;
                }
                Row row = old instanceof Row ? (Row) old : ui.new Row();
                row.number = 0;
                row.bind(getItem(i));
                return row;
            }
        });
        grid.setOnItemClickListener((p, v, i, id) -> {
            MediaEntry item = items.get(i);
            if (v instanceof Tile) ui.playlistScreen.captureFlight(((Tile) v).art, item.image, item.category == 2);
            else if (v instanceof Row) {
                ui.playlistScreen.captureFlight(((Row) v).art, item.image, item.category == 2);
                ui.tick(v);
            }
            ui.open(item);
        });
        grid.setOnItemLongClickListener((p, v, i, id) -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); ui.actions(items.get(i)); return true; });
        if (ui.motion()) {
            android.view.animation.AnimationSet rise = new android.view.animation.AnimationSet(true);
            rise.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
            rise.addAnimation(tiles
                ? new android.view.animation.ScaleAnimation(.92f, 1f, .92f, 1f, android.view.animation.Animation.RELATIVE_TO_SELF, .5f, android.view.animation.Animation.RELATIVE_TO_SELF, .5f)
                : new android.view.animation.TranslateAnimation(0, 0, ui.dp(18), 0));
            rise.setDuration(tiles ? 300 : 320);
            rise.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
            grid.setLayoutAnimation(new android.view.animation.GridLayoutAnimationController(rise, tiles ? .12f : .07f, tiles ? .1f : .08f));
        }
        return grid;
    }
}
