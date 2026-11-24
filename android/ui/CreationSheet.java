package spatch;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import spatch.ArtworkViews.Art;
import static android.view.View.*;

/** The Create menu and inline playlist composer, including its draft and submission state. */
final class CreationSheet {
    private final SpatchUi ui;
    CreationSheet(SpatchUi ui) { this.ui = ui; }

    private boolean creating;
    private String createName = "", createDescription = "";
    MediaEntry pendingPlaylistSong;
    boolean createFromQueue;
    FrameLayout createOverlay;
    private ScrollView createScroll;
    private LinearLayout createCard;
    private android.animation.ValueAnimator createResize;
    boolean createComposerOpen, createComposerFromMenu;

    void openCreateMenu() {
        pendingPlaylistSong = null;
        createComposerOpen = false;
        createComposerFromMenu = false;
        ensureCreateOverlay();
        showCreateMenuContent(false);
    }

    void openCreatePlaylist(boolean fromMenu) {
        boolean alreadyOpen = createOverlay != null;
        createComposerOpen = true;
        createComposerFromMenu = fromMenu;
        ensureCreateOverlay();
        showCreateComposerContent(alreadyOpen);
    }

    private void ensureCreateOverlay() {
        if (createOverlay != null && createCard != null && createScroll != null) return;
        ui.hideKeyboard();
        if (ui.accountMenu != null) ui.accountMenu.dismiss();
        ui.dockUi.setDockHidden(false);
        FrameLayout overlay = new FrameLayout(ui.activity);
        overlay.setBackgroundColor(0x99000000);
        overlay.setClipChildren(true);
        createOverlay = overlay;
        int[] navAt = new int[2], rootAt = new int[2];
        ui.dockUi.dockBar.getLocationInWindow(navAt); ui.getLocationInWindow(rootAt);
        int available = navAt[1] - rootAt[1];
        if (available <= 0) available = ui.getHeight() - ui.dockRoom();
        LinearLayout card = ui.column();
        ui.bgRound(card, 24, () -> SpatchUi.blend(ui.surf, ui.accent2, .07f));
        card.setClickable(true);
        createCard = card;
        boolean land = ui.landscape();
        final int menuMaxHeight = Math.max(ui.dp(120), available - ui.inset.top - ui.dp(land ? 18 : 24));
        ScrollView scroll = new ScrollView(ui.activity) {
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(menuMaxHeight, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(card);
        createScroll = scroll;
        int menuWidth = land ? Math.min(ui.dp(660), Math.max(ui.dp(320), ui.getWidth() - ui.inset.left - ui.inset.right - ui.dp(32))) : -1;
        LayoutParams menuParams = new LayoutParams(menuWidth, -2, land ? (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) : Gravity.BOTTOM);
        if (land) menuParams.setMargins(ui.inset.left + ui.dp(12), ui.inset.top + ui.dp(8), ui.inset.right + ui.dp(12), ui.dp(10));
        else menuParams.setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(10));
        overlay.addView(scroll, menuParams);
        overlay.setOnClickListener(v -> closeCreateMenu());
        ui.addView(overlay, new LayoutParams(-1, available));
        overlay.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        card.setAccessibilityPaneTitle("Create");
        ui.dockUi.updateDock();
        if (ui.motion()) {
            overlay.setAlpha(0f);
            scroll.setAlpha(0f);
            scroll.setScaleX(.86f);
            scroll.setScaleY(.86f);
            scroll.setTranslationY(ui.dp(32));
            scroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                    scroll.setPivotX(Math.max(0, scroll.getWidth() - ui.dp(36)));
                    scroll.setPivotY(scroll.getHeight());
                    overlay.animate().alpha(1f).setDuration(200).start();
                    scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0)
                        .setDuration(280).setInterpolator(SpatchUi.EMPHASIZED).start();
                    return true;
                }
            });
        }
    }

    private void morphCreateCard(int oldHeight, boolean forward) {
        if (!ui.motion() || createCard == null || createScroll == null || oldHeight <= 0) return;
        if (createResize != null) createResize.cancel();
        int width = createCard.getWidth();
        if (width <= 0) return;
        int maxH = Math.max(ui.dp(120), (createOverlay != null && createOverlay.getHeight() > 0 ? createOverlay.getHeight() : ui.getHeight()) - ui.inset.top - ui.dp(24));
        createCard.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        int targetHeight = Math.min(maxH, createCard.getMeasuredHeight());
        for (int i = 0; i < createCard.getChildCount(); i++) {
            View child = createCard.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationX(forward ? ui.dp(20) : -ui.dp(20));
            child.animate().alpha(1f).translationX(0).setDuration(220).setInterpolator(SpatchUi.EMPHASIZED).start();
        }
        if (targetHeight <= 0 || targetHeight == oldHeight) return;
        final ViewGroup.LayoutParams lp = createCard.getLayoutParams();
        lp.height = oldHeight;
        createCard.setLayoutParams(lp);
        createResize = android.animation.ValueAnimator.ofInt(oldHeight, targetHeight);
        createResize.setDuration(250);
        createResize.setInterpolator(SpatchUi.EMPHASIZED);
        createResize.addUpdateListener(a -> {
            if (createCard == null) { a.cancel(); return; }
            lp.height = (int) a.getAnimatedValue();
            createCard.setLayoutParams(lp);
        });
        createResize.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (createCard != null) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    createCard.setLayoutParams(lp);
                }
            }
        });
        createResize.start();
    }

    void showCreateMenuContent(boolean animateSwap) {
        if (createCard == null) return;
        int oldHeight = animateSwap ? createCard.getHeight() : 0;
        ui.hideKeyboard();
        createComposerOpen = false;
        createComposerFromMenu = false;
        createCard.removeAllViews();
        createCard.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        createCard.setAccessibilityPaneTitle("Create");
        String[] titles = {"Playlist", "Collaborative playlist", "Mixed playlist", "Blend", "Prompted Playlist", "Jam"};
        String[] details = {"Create a playlist with songs or episodes", "Create a playlist together with friends", "Mix songs with smooth transitions",
            "Combine your friends’ tastes into a playlist", "Create a playlist with a prompt", "Listen together from anywhere"};
        String[] glyphs = {"note", "people", "mix", "blend", "prompt", "jam"};
        String[] kinds = {"playlist", "collaborative", "mixed", "blend", "prompted", "jam"};
        boolean land = ui.landscape();
        for (int i = 0; i < titles.length; i++) {
            if (land) {
                LinearLayout row = ui.row();
                row.addView(createMenuOption(titles[i], details[i], glyphs[i], kinds[i], i == 2 || i == 4, i == 0, true), new LinearLayout.LayoutParams(0, -2, 1));
                if (i + 1 < titles.length) {
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
                    rp.leftMargin = ui.dp(8);
                    row.addView(createMenuOption(titles[i + 1], details[i + 1], glyphs[i + 1], kinds[i + 1], i + 1 == 2 || i + 1 == 4, false, true), rp);
                }
                createCard.addView(row, ui.fill(-2));
                i++;
            } else {
                createCard.addView(createMenuOption(titles[i], details[i], glyphs[i], kinds[i], i == 2 || i == 4, i == 0, false), ui.fill(-2));
            }
        }
        if (animateSwap) morphCreateCard(oldHeight, false);
    }

    private LinearLayout createMenuOption(String label, String subtitle, String glyph, String kind, boolean betaTag, boolean openComposer, boolean compact) {
        LinearLayout option = ui.row();
        option.setPadding(ui.dp(8), ui.dp(compact ? 6 : 8), ui.dp(8), ui.dp(compact ? 6 : 8));
        FrameLayout bubble = new FrameLayout(ui.activity);
        ui.bgRound(bubble, compact ? 20 : 25, () -> SpatchUi.blend(ui.surfHi, ui.accent2, .12f));
        bubble.addView(ui.icon(glyph, () -> ui.ink), new LayoutParams(ui.dp(compact ? 20 : 24), ui.dp(compact ? 20 : 24), Gravity.CENTER));
        option.addView(bubble, ui.lp(ui.dp(compact ? 40 : 48), ui.dp(compact ? 40 : 48)));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(compact ? 12 : 14), ui.dp(2), 0, ui.dp(2));
        LinearLayout title = ui.row();
        TextView nameView = ui.text(label, compact ? 15 : 16, () -> ui.ink, true);
        if (compact) ui.oneLine(nameView);
        title.addView(nameView);
        if (betaTag) {
            TextView beta = ui.text("Beta", 10, () -> ui.inkDim, true);
            beta.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
            ui.bgRound(beta, 6, () -> ui.surfHi);
            LinearLayout.LayoutParams bp = ui.lp(-2, -2); bp.leftMargin = ui.dp(8);
            title.addView(beta, bp);
        }
        words.addView(title);
        TextView detail = ui.text(subtitle, compact ? 11 : 12, () -> ui.inkDim, false);
        if (compact) ui.oneLine(detail);
        detail.setPadding(0, ui.dp(compact ? 2 : 4), 0, 0);
        words.addView(detail);
        option.addView(words, ui.weight());
        option.setMinimumHeight(ui.dp(compact ? 54 : 68));
        ui.tappable(option, label, () -> {
            if (openComposer) {
                pendingPlaylistSong = null;
                openCreatePlaylist(true);
            } else {
                closeCreateMenu();
                String error = CoreBridge.openCreation(ui.activity, kind);
                if (error != null && !error.isEmpty()) Toast.makeText(ui.activity, error, Toast.LENGTH_LONG).show();
            }
        });
        return option;
    }

    private void showCreateComposerContent(boolean animateSwap) {
        if (createCard == null) return;
        int oldHeight = animateSwap ? createCard.getHeight() : 0;
        createComposerOpen = true;
        createCard.removeAllViews();
        createCard.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(16));
        createCard.setAccessibilityPaneTitle("New playlist");

        LinearLayout header = ui.row();
        header.setPadding(0, 0, 0, ui.dp(12));
        if (createComposerFromMenu) {
            View backBtn = ui.iconButton(ui.icon("back", () -> ui.ink), "Back to create menu", () -> showCreateMenuContent(true));
            LinearLayout.LayoutParams bp = ui.lp(ui.dp(36), ui.dp(36));
            bp.rightMargin = ui.dp(6);
            header.addView(backBtn, bp);
        }
        header.addView(ui.text("New playlist", 18, () -> ui.ink, true), ui.weight());
        createCard.addView(header, ui.fill(-2));

        int queueCount = (!ui.uri.isEmpty() ? 1 : 0) + ui.upcoming.size();
        String coverUrl = createFromQueue && !ui.image.isEmpty() ? ui.image
            : (pendingPlaylistSong != null && !pendingPlaylistSong.image.isEmpty() ? pendingPlaylistSong.image : "");

        LinearLayout hero = ui.row();
        hero.setPadding(0, 0, 0, ui.dp(14));
        FrameLayout cover = new FrameLayout(ui.activity);
        if (!coverUrl.isEmpty()) {
            Art art = ui.artwork.new Art(Art.ROUNDED, false);
            art.bind(coverUrl);
            cover.addView(art, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ui.bgRound(cover, 18, () -> SpatchUi.blend(ui.surfHi, ui.accent, .2f)).setStroke(Math.max(1, ui.dp(.8f)), (ui.accent & 0x00ffffff) | 0x33000000);
            cover.addView(ui.icon("note", () -> ui.accent), new FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER));
        }
        int seedCount = createFromQueue ? queueCount : (pendingPlaylistSong != null ? 1 : 0);
        if (seedCount > 0) {
            TextView badge = ui.text(String.valueOf(seedCount), 10, () -> ui.onAccent, true);
            badge.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
            ui.bgRound(badge, 999, () -> ui.accent);
            FrameLayout.LayoutParams badgeP = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            badgeP.rightMargin = ui.dp(4); badgeP.bottomMargin = ui.dp(4);
            cover.addView(badge, badgeP);
        }
        LinearLayout.LayoutParams coverP = ui.lp(ui.dp(64), ui.dp(64));
        coverP.rightMargin = ui.dp(14);
        hero.addView(cover, coverP);

        LinearLayout titleCol = ui.column();
        android.widget.EditText name = new android.widget.EditText(ui.activity);
        name.setHint("Give it a name");
        name.setText(createName);
        name.setTextSize(20);
        name.setSingleLine(true);
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        name.setBackground(null);
        name.setPadding(0, ui.dp(4), 0, ui.dp(6));
        if (android.os.Build.VERSION.SDK_INT >= 28) name.setTypeface(Typeface.create(Typeface.SANS_SERIF, 700, false));
        ui.colored(name, () -> { name.setTextColor(ui.ink); name.setHintTextColor(ui.inkDim); });
        name.setContentDescription("Playlist name");
        titleCol.addView(name, ui.fill(-2));

        View underline = new View(ui.activity);
        ui.colored(underline, () -> underline.setBackgroundColor(name.hasFocus() ? ui.accent : ((ui.ink & 0x00ffffff) | 0x26000000)));
        titleCol.addView(underline, ui.fill(Math.max(2, ui.dp(1.5f))));
        name.setOnFocusChangeListener((v, focused) -> underline.setBackgroundColor(focused ? ui.accent : ((ui.ink & 0x00ffffff) | 0x26000000)));

        TextView nameCount = ui.text("", 11, () -> ui.inkDim, false);
        nameCount.setPadding(0, ui.dp(4), 0, 0);
        nameCount.setVisibility(createName.isEmpty() ? GONE : VISIBLE);
        if (!createName.isEmpty()) nameCount.setText(createName.length() + " / 100");
        titleCol.addView(nameCount, ui.fill(-2));
        hero.addView(titleCol, ui.weight());
        createCard.addView(hero, ui.fill(-2));

        android.widget.EditText description = new android.widget.EditText(ui.activity);
        description.setHint("Add a description (optional)");
        description.setText(createDescription);
        description.setTextSize(14);
        description.setMinLines(2);
        description.setMaxLines(2);
        description.setGravity(Gravity.TOP | Gravity.START);
        description.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        ui.bgRound(description, 14, () -> ui.surfHi).setStroke(Math.max(1, ui.dp(.8f)), (ui.ink & 0x00ffffff) | 0x14000000);
        ui.colored(description, () -> { description.setTextColor(ui.ink); description.setHintTextColor(ui.inkDim); });
        description.setContentDescription("Description (optional)");
        LinearLayout.LayoutParams descP = ui.fill(-2);
        descP.bottomMargin = ui.dp(12);
        createCard.addView(description, descP);

        LinearLayout toggleRow = ui.row();
        toggleRow.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        ui.bgRound(toggleRow, 20, () -> ui.surfHi);
        String[] modes = {"Empty", queueCount > 0 ? "From queue · " + queueCount : "From queue"};
        for (int i = 0; i < 2; i++) {
            final boolean wantQueue = i == 1;
            boolean active = createFromQueue == wantQueue;
            TextView chip = ui.text(modes[i], 13, active ? ui.onAccent : ui.ink, true);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
            if (active) ui.bgRound(chip, 16, () -> ui.accent);
            ui.tappable(chip, modes[i], () -> {
                if (createFromQueue == wantQueue) return;
                createFromQueue = wantQueue;
                showCreateComposerContent(false);
            });
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ui.dp(38), 1);
            if (i == 0) mp.rightMargin = ui.dp(4);
            toggleRow.addView(chip, mp);
        }
        createCard.addView(toggleRow, ui.fill(-2));

        String sourceSummary = "";
        if (createFromQueue) {
            if (queueCount == 0) sourceSummary = "Play something first to save the current queue.";
            else {
                StringBuilder sb = new StringBuilder("Saves ");
                if (!ui.title.isEmpty()) sb.append(ui.title);
                if (!ui.upcoming.isEmpty() && !ui.upcoming.get(0).title.isEmpty()) {
                    if (sb.length() > 6) sb.append(", ");
                    sb.append(ui.upcoming.get(0).title);
                }
                int more = queueCount - ((!ui.title.isEmpty() ? 1 : 0) + (!ui.upcoming.isEmpty() && !ui.upcoming.get(0).title.isEmpty() ? 1 : 0));
                if (more > 0) sb.append(" + ").append(more).append(" more");
                sourceSummary = sb.toString();
            }
        } else if (pendingPlaylistSong != null) {
            sourceSummary = "Includes “" + pendingPlaylistSong.title + "”";
        }
        if (!sourceSummary.isEmpty()) {
            TextView summary = ui.text(sourceSummary, 12, () -> ui.inkDim, false);
            summary.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0);
            ui.oneLine(summary);
            createCard.addView(summary, ui.fill(-2));
        }

        TextView error = ui.text("", 12, () -> 0xffff8a80, true);
        error.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0);
        error.setVisibility(GONE);
        createCard.addView(error, ui.fill(-2));

        name.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                createName = s.toString();
                if (createName.isEmpty()) nameCount.setVisibility(GONE);
                else {
                    nameCount.setVisibility(VISIBLE);
                    nameCount.setText(createName.length() + " / 100");
                    nameCount.setTextColor(createName.length() > 100 ? 0xffff8a80 : ui.inkDim);
                }
                if (error.getVisibility() == VISIBLE) { error.setText(""); error.setVisibility(GONE); }
            }
            public void afterTextChanged(android.text.Editable e) {}
        });
        description.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                createDescription = s.toString();
                if (error.getVisibility() == VISIBLE) { error.setText(""); error.setVisibility(GONE); }
            }
            public void afterTextChanged(android.text.Editable e) {}
        });

        LinearLayout actions = ui.row();
        LinearLayout.LayoutParams ap = ui.fill(-2);
        ap.topMargin = ui.dp(14);
        TextView cancel = ui.text("Cancel", 14, () -> ui.ink, true);
        cancel.setGravity(Gravity.CENTER);
        ui.bgRound(cancel, 23, () -> ui.surfHi).setStroke(Math.max(1, ui.dp(.8f)), (ui.ink & 0x00ffffff) | 0x16000000);
        ui.tappable(cancel, "Cancel", this::closeCreateMenu);
        LinearLayout.LayoutParams cancelP = new LinearLayout.LayoutParams(0, ui.dp(46), 1f);
        cancelP.rightMargin = ui.dp(10);
        actions.addView(cancel, cancelP);

        TextView make = ui.text(creating ? "Creating…" : "Create", 14, () -> ui.onAccent, true);
        make.setGravity(Gravity.CENTER);
        ui.bgRound(make, 23, () -> ui.accent);
        make.setEnabled(!creating);
        make.setAlpha(creating ? .55f : 1f);
        ui.tappable(make, "Create playlist", () -> {
            if (creating) return;
            String trimmed = createName.trim(), detail = createDescription.trim();
            if (trimmed.isEmpty()) {
                error.setText("Give your playlist a name");
                error.setVisibility(VISIBLE);
                name.requestFocus();
                return;
            }
            if (createName.length() > 100) {
                error.setText("Playlist name is too long (max 100 characters)");
                error.setVisibility(VISIBLE);
                name.requestFocus();
                return;
            }
            if (createDescription.length() > 300) {
                error.setText("Description is too long (max 300 characters)");
                error.setVisibility(VISIBLE);
                description.requestFocus();
                return;
            }
            ui.hideKeyboard();
            creating = true;
            make.setEnabled(false); make.setAlpha(.55f); make.setText("Creating…");
            error.setText(""); error.setVisibility(GONE);
            boolean fromQueue = createFromQueue;
            MediaEntry pending = pendingPlaylistSong;
            ui.submit(SpatchUi.COMMANDS, () -> {
                String[] response;
                try {
                    response = SpatchUi.nativeCreatePlaylist(trimmed, detail, fromQueue);
                    if (pending != null && response != null && response.length >= 2 && !response[0].isEmpty()) {
                        String addError;
                        try { addError = SpatchUi.nativeAddToPlaylist(response[0], new String[]{pending.uri}); }
                        catch (RuntimeException | LinkageError failed) { addError = failed.toString(); }
                        if (addError == null || !addError.isEmpty()) response[1] = (response[1].isEmpty() ? "" : response[1] + "\n") + "Playlist created, but couldn’t add the song: " + (addError == null ? "No reply from Spotify" : addError);
                    }
                }
                catch (RuntimeException | LinkageError failed) { response = new String[]{"", failed.toString()}; }
                final String[] result = response;
                ui.post(() -> {
                    creating = false;
                    make.setEnabled(true); make.setAlpha(1f); make.setText("Create");
                    if (result == null || result.length < 2 || result[0].isEmpty()) {
                        error.setText(result != null && result.length >= 2 ? result[1] : "Couldn’t create the playlist. Please try again.");
                        error.setVisibility(VISIBLE);
                        return;
                    }
                    createName = createDescription = "";
                    pendingPlaylistSong = null;
                    closeCreateMenu();
                    Toast.makeText(ui.activity, result[1].isEmpty() ? "Created “" + trimmed + "”" : result[1], result[1].isEmpty() ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                    ui.playlistScreen.openPlaylist(new MediaEntry(trimmed, "Your playlist", result[0], "", 0));
                });
            });
        });
        actions.addView(make, new LinearLayout.LayoutParams(0, ui.dp(46), 1.6f));
        createCard.addView(actions, ap);

        if (animateSwap) morphCreateCard(oldHeight, true);
    }

    void closeCreateMenu() {
        FrameLayout overlay = createOverlay;
        ScrollView scroll = createScroll;
        if (overlay == null) return;
        ui.hideKeyboard();
        if (createResize != null) { createResize.cancel(); createResize = null; }
        createOverlay = null;
        createScroll = null;
        createCard = null;
        createComposerOpen = false;
        createComposerFromMenu = false;
        pendingPlaylistSong = null;
        ui.dockUi.updateDock();
        if (ui.motion() && ui.isAttachedToWindow()) {
            overlay.animate().cancel();
            overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> ui.removeView(overlay)).start();
            if (scroll != null) {
                scroll.animate().cancel();
                scroll.setPivotX(Math.max(0, scroll.getWidth() - ui.dp(36)));
                scroll.setPivotY(scroll.getHeight());
                scroll.animate().alpha(0f).scaleX(.90f).scaleY(.90f).translationY(ui.dp(24))
                    .setDuration(180).setInterpolator(SpatchUi.EMPHASIZED).start();
            }
        } else ui.removeView(overlay);
    }

}
