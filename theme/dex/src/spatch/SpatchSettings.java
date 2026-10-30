package spatch;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Spatch settings in Material 3: an animated hero, theme swatches, and connected groups of rows that
 * round at the group's ends. Opened from the profile side drawer; handles system back gesture,
 * hardware back, and the header back button.
 */
public final class SpatchSettings extends FrameLayout implements View.OnClickListener, View.OnScrollChangeListener, DialogInterface.OnClickListener, Runnable {
    Back back;

    // Indexed like Settings.KEYS: wave, buttons, backdrop, round_art, immersive, shapes, reduce_motion, visualizer,
    // viz_mirrored, viz_smooth, viz_thin, viz_floating, viz_side_margin, hide_podcasts, hide_audiobooks,
    // hide_messages.
    private static final String[] FEATURE_TITLES = {
        "Wavy progress",
        "Button animations",
        "Album art backdrop",
        "Circular album art",
        "Double-tap for immersive",
        "Expressive shapes",
        "Reduce motion",
        "Full-screen visualizer",
        "Mirrored layout",
        "Smooth motion",
        "Thin bars",
        "Floating bars",
        "Side margin",
        "Hide podcasts",
        "Hide audiobooks",
        "Hide Messages"
    };

    private static final String[] FEATURE_SUBTITLES = {
        "The played part of the progress bar waves while music plays",
        "Bounce and nudge feedback on the player controls",
        "Blurred album art behind Now Playing",
        "Round artwork with a slowly turning ring",
        "Double tap the player artwork for a full-screen view",
        "Scalloped play buttons instead of plain circles",
        "Stills Spatch animations: waves, springs and spins",
        "Spectrum of what's playing under the immersive player (uses the microphone permission)",
        "Bass in the centre, spreading out to both sides; off runs bass to treble left to right",
        "Softer, slower bars; off is snappy",
        "48 slim bars instead of 24 thick ones",
        "Lift the bars off the bottom edge; quiet bars rest as dots",
        "A small gap at the left and right; off runs edge to edge",
        "From Home, Search, top chips and Your Library",
        "From Home, Search, top chips and Your Library",
        "Removes Messages from the profile menu; your messages stay"
    };

    /** encore_icon_<name>_24 for each feature row; a missing name just leaves its tile empty. */
    private static final String[] FEATURE_ICONS = {
        "soundwave", "enhance", "album", "album", "now_playing", "play", "pause", "soundwave",
        "repeat", "volume", "filter", "soundwave", "filter", "podcasts", "audiobook", "hidden"
    };

    /** Section layout: Settings indices per group. */
    private static final int[] ARTWORK = {3, 2, 5}, PLAYER = {0, 4}, VISUALIZER = {7, 8, 9, 10, 11, 12}, CONTENT = {13, 14, 15}, MOTION = {1, 6};

    private static final String[] THEME_TITLES = {
        "Lavender",
        "Gruvbox",
        "Catppuccin",
        "Rosé Pine",
        "Nord",
        "Tokyo Night",
        "Dracula"
    };

    private static final int VIZ_THIN = 10, VIZ_FLOATING = 11;
    private static final int STAGGERED = 8; // entrance animation covers what is on screen at first

    private final int surface = Palette.color(0xff14121c), card = Palette.color(0xff1d1a2a), high = Palette.color(0xff2b2640),
        text = Palette.color(0xfff3eeff), subdued = Palette.color(0xffbcb3dc), accent = Palette.color(0xffd0bcff);
    private final java.util.ArrayList<TextView> pillViews = new java.util.ArrayList<TextView>();
    private final View[] choiceRows = new View[Settings.CHOICES.length];
    private SpectrumPreview preview;
    private final java.util.ArrayList<View> homeRows = new java.util.ArrayList<View>();
    private final View backButton;
    private final SettingsHero hero;
    private final View barTitle;
    private final LinearLayout content;
    private View resetRow, homeToggle, homeChevron;
    private LinearLayout homeList;
    private TextView homeCount, themeNote;
    private boolean closing;
    private final M3Switch[] featureSwitches = new M3Switch[FEATURE_TITLES.length];
    private final View[] featureRows = new View[FEATURE_TITLES.length];
    private final View[] themeRows = new View[Palette.NAMES.length];
    private final float density;

    public SpatchSettings(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        setBackgroundColor(surface);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);

        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBarHeight = resId > 0 ? context.getResources().getDimensionPixelSize(resId) : dp(32);
        if (statusBarHeight < dp(24)) statusBarHeight = dp(24);

        int navBarId = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        int navBarHeight = navBarId > 0 ? context.getResources().getDimensionPixelSize(navBarId) : dp(16);

        setPadding(0, statusBarHeight, 0, 0);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(16), 0);

        ImageView backBtn = new ImageView(context, null);
        try {
            backBtn.setImageResource(0x7f08008b); // abc_ic_ab_back_material
            backBtn.setColorFilter(text);
            backBtn.setScaleType(ImageView.ScaleType.CENTER);
        } catch (Throwable ignored) {}
        backBtn.setContentDescription("Back");
        backBtn.setClickable(true);
        backBtn.setFocusable(true);
        backBtn.setBackground(ripple(rounded(high, dp(24))));
        header.addView(backBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        backBtn.setOnClickListener(this);
        this.backButton = backBtn;

        // Fades in once the hero's own title has scrolled away.
        TextView title = new TextView(context);
        title.setText("Spatch");
        title.setTextSize(20f);
        title.setTextColor(text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(16), 0, 0, 0);
        title.setAlpha(0f);
        header.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        barTitle = title;

        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        ScrollView scroll = new ScrollView(context);
        scroll.setOnScrollChangeListener(this);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(32) + navBarHeight);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        hero = new SettingsHero(context);
        content.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(164)));

        for (int i = 0; i < FEATURE_TITLES.length; i++) {
            featureSwitches[i] = new M3Switch(context);
            featureSwitches[i].setChecked(Settings.get(i));
            featureRows[i] = createSettingRow(FEATURE_ICONS[i], FEATURE_TITLES[i], FEATURE_SUBTITLES[i], featureSwitches[i]);
            featureRows[i].setOnClickListener(this);
            describe(featureRows[i], FEATURE_TITLES[i], Settings.get(i) ? "On" : "Off");
        }

        addSectionHeader("Theme");
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = row * 4; i < row * 4 + 4; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(112), 1f);
                if (i >= themeRows.length) { // keeps the last row's swatches the same width
                    line.addView(new View(context, null), lp);
                    continue;
                }
                themeRows[i] = new ThemeSwatch(context, i, THEME_TITLES[i]);
                themeRows[i].setOnClickListener(this);
                line.addView(themeRows[i], lp);
            }
            content.addView(line);
        }
        themeNote = new TextView(context);
        themeNote.setTextSize(13f);
        themeNote.setTextColor(subdued);
        themeNote.setPadding(dp(8), dp(6), dp(8), 0);
        content.addView(themeNote);
        refreshThemes(Palette.requested(), false);

        addSectionHeader("Artwork");
        LinearLayout artwork = addGroup();
        for (int i : ARTWORK) artwork.addView(featureRows[i]);
        addChoice(artwork, Settings.ART_OUTLINE, "Outline", "On circular art in the player", new String[] {"Wavy", "Simple", "None"});
        addChoice(artwork, Settings.ART_GLOW, "Glow", null, new String[] {"Full", "Soft", "Off"});
        addChoice(artwork, Settings.ART_SPECTRUM, "Spectrum", "Real audio around the art; uses the microphone permission", new String[] {"Off", "Bars", "Dots"});
        finish(artwork);

        addSectionHeader("Player");
        LinearLayout player = addGroup();
        for (int i : PLAYER) player.addView(featureRows[i]);
        finish(player);

        addSectionHeader("Visualizer");
        LinearLayout visualizer = addGroup();
        preview = new SpectrumPreview(context);
        visualizer.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));
        addChoice(visualizer, Settings.VIZ_SHAPE, "Shape", null, new String[] {"Bars", "Centre", "Contour", "Dots"});
        addChoice(visualizer, Settings.VIZ_TAPER, "Edge taper", "Eases the bars off toward the sides", new String[] {"Subtle", "None"});
        for (int i : VISUALIZER) visualizer.addView(featureRows[i]);
        finish(visualizer);
        refreshChoices();

        addSectionHeader("Content");
        LinearLayout hiding = addGroup();
        for (int i : CONTENT) hiding.addView(featureRows[i]);
        java.util.ArrayList<String[]> sections = Settings.sections();
        ImageView chevron = icon("chevron_right");
        homeChevron = chevron;
        homeToggle = createSettingRow("home", "Home sections", null, chevron);
        homeCount = (TextView) ((ViewGroup) ((ViewGroup) homeToggle).getChildAt(1)).getChildAt(1);
        homeToggle.setOnClickListener(this);
        hiding.addView(homeToggle);
        finish(hiding);

        homeList = new LinearLayout(context);
        homeList.setOrientation(LinearLayout.VERTICAL);
        homeList.setVisibility(View.GONE);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(8);
        content.addView(homeList, listLp);
        for (String[] section : sections) {
            M3Switch s = new M3Switch(context);
            boolean hidden = false;
            for (int i = 1; i < section.length; i++) hidden |= Settings.sectionHidden(section[i]);
            s.setChecked(hidden);
            View row = createSettingRow(null, section[0], null, s);
            row.setTag(new Object[] {section, s, section[0]});
            row.setOnClickListener(this);
            describe(row, section[0], hidden ? "Hidden" : "Shown");
            homeRows.add(row);
            homeList.addView(row);
        }
        finish(homeList);
        refreshHomeCount();

        addSectionHeader("Motion");
        LinearLayout motion = addGroup();
        for (int i : MOTION) motion.addView(featureRows[i]);
        finish(motion);

        TextView reset = new TextView(context);
        reset.setText("Reset to defaults");
        reset.setTextSize(14f);
        reset.setTextColor(accent);
        reset.setTypeface(Typeface.DEFAULT_BOLD);
        reset.setGravity(Gravity.CENTER);
        reset.setClickable(true);
        reset.setFocusable(true);
        reset.setBackground(ripple(rounded(high, dp(24))));
        reset.setOnClickListener(this);
        describe(reset, "Reset to defaults", "Button");
        resetRow = reset;
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        resetLp.topMargin = dp(28);
        content.addView(reset, resetLp);

        TextView about = new TextView(context);
        about.setText("Keeps your theme. Theme changes apply after a restart; everything else straight away.");
        about.setTextSize(12f);
        about.setTextColor(subdued);
        about.setGravity(Gravity.CENTER);
        about.setPadding(dp(16), dp(10), dp(16), 0);
        content.addView(about);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius);
        return bg;
    }

    private RippleDrawable ripple(GradientDrawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(text & 0x24ffffff), shape, null);
    }

    private ImageView icon(String name) {
        ImageView image = new ImageView(getContext(), null);
        image.setScaleType(ImageView.ScaleType.CENTER);
        int id = getContext().getResources().getIdentifier("encore_icon_" + name + "_24", "drawable", getContext().getPackageName());
        if (id != 0) {
            image.setImageResource(id);
            image.setColorFilter(accent);
        }
        return image;
    }

    private LinearLayout addGroup() {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(LinearLayout.VERTICAL);
        content.addView(group);
        return group;
    }

    /** Rows connect with a 2dp gap; only the group's outer corners are fully round. */
    private void finish(LinearLayout group) {
        int last = group.getChildCount() - 1;
        for (int i = 0; i <= last; i++) {
            View row = group.getChildAt(i);
            float top = i == 0 ? dp(24) : dp(6), bottom = i == last ? dp(24) : dp(6);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(card);
            bg.setCornerRadii(new float[] {top, top, top, top, bottom, bottom, bottom, bottom});
            row.setBackground(row.isClickable() ? ripple(bg) : bg);
            if (i < last) {
                ViewGroup.LayoutParams old = row.getLayoutParams();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    old == null ? ViewGroup.LayoutParams.WRAP_CONTENT : old.height);
                lp.bottomMargin = dp(2);
                row.setLayoutParams(lp);
            }
        }
    }

    /** A titled connected button group, one button per stable option in Settings.CHOICES[setting]. */
    private void addChoice(LinearLayout parent, int setting, String title, String subtitle, String[] labels) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(16f);
        t.setTextColor(text);
        row.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(c);
            s.setText(subtitle);
            s.setTextSize(13f);
            s.setTextColor(subdued);
            row.addView(s);
        }
        LinearLayout pills = new LinearLayout(c);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setPadding(0, dp(10), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            TextView pill = new TextView(c);
            pill.setText(labels[i]);
            pill.setTextSize(14f);
            pill.setGravity(Gravity.CENTER);
            pill.setClickable(true);
            pill.setFocusable(true);
            pill.setTag(new String[] {String.valueOf(setting), Settings.CHOICES[setting][i], title + ": " + labels[i]});
            pill.setOnClickListener(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (i > 0) lp.leftMargin = dp(3);
            pills.addView(pill, lp);
            pillViews.add(pill);
        }
        row.addView(pills);
        choiceRows[setting] = row;
        parent.addView(row);
    }

    /** Selected buttons, and rows and switches that do not apply to the current choices. */
    private void refreshChoices() {
        for (TextView pill : pillViews) {
            String[] tag = (String[]) pill.getTag();
            boolean selected = tag[1].equals(Settings.choice(Integer.parseInt(tag[0])));
            pill.setBackground(ripple(rounded(selected ? accent : high, dp(selected ? 24 : 10))));
            pill.setTextColor(selected ? surface : text);
            pill.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            pill.setContentDescription(tag[2] + (selected ? ", Selected" : ", Not selected"));
        }
        String shape = Settings.choice(Settings.VIZ_SHAPE);
        dim(featureRows[VIZ_THIN], "contour".equals(shape), 10, "Not used by Contour");
        dim(featureRows[VIZ_FLOATING], "centre".equals(shape), 11, "Not used by Centre: it sits on a middle line");
        boolean round = Settings.roundArt();
        choiceRows[Settings.ART_OUTLINE].setAlpha(round ? 1f : 0.5f);
        preview.invalidate();
    }

    private void dim(View row, boolean off, int setting, String why) {
        row.setAlpha(off ? 0.5f : 1f);
        row.setEnabled(!off);
        describe(row, FEATURE_TITLES[setting], off ? why : Settings.get(setting) ? "On" : "Off");
    }

    /** Slides in from the right while the top of the page rises into place; called once attached. */
    void enter() {
        if (!Wave.animationsEnabled()) return;
        setAlpha(0f);
        setTranslationX(density * 48);
        animate().alpha(1f).translationX(0f).setDuration(240).start();
        for (int i = 0; i < Math.min(STAGGERED, content.getChildCount()); i++) {
            View child = content.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(density * 28);
            child.animate().alpha(1f).translationY(0f).setDuration(340).setStartDelay(60 + i * 40).start();
        }
    }

    /** The hero stops animating once it has scrolled away, and the bar takes over its title. */
    public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
        float over = dp(164);
        hero.setOffscreen(y >= over);
        barTitle.setAlpha(Math.max(0f, Math.min(1f, (y - over * 0.45f) / (over * 0.3f))));
    }

    /** Tiny bounce for whatever was just chosen. */
    private void pop(View v) {
        if (!Wave.animationsEnabled()) return;
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private void refreshThemes(String selected, boolean animate) {
        for (int i = 0; i < themeRows.length; i++) {
            boolean on = Palette.NAMES[i].equals(selected);
            boolean was = themeRows[i].isSelected();
            themeRows[i].setSelected(on);
            if (on && !was && animate) pop(themeRows[i]);
            describe(themeRows[i], THEME_TITLES[i], on ? "Selected" : "Not selected");
        }
        themeNote.setText(selected.equals(Palette.activeName()) ? "Applied" : "Applies after a restart");
    }

    private void refreshHomeCount() {
        int hidden = 0;
        for (View row : homeRows) if (((M3Switch) ((Object[]) row.getTag())[1]).isChecked()) hidden++;
        String count = homeRows.isEmpty() ? "Open Home once and its sections appear here"
            : hidden == 0 ? homeRows.size() + " sections shown" : hidden + " of " + homeRows.size() + " hidden";
        homeCount.setText(count);
        describe(homeToggle, "Home sections", count);
    }

    /** TalkBack reads the whole row as one control, including its state. */
    private static void describe(View row, String title, String state) {
        row.setContentDescription(title + ", " + state);
    }

    /** Chips and drawer rows are read at startup, and a restart also leaves a hidden chip selected safely. */
    private void askRestart() {
        new AlertDialog.Builder(getContext(), 0x010302d1)
            .setTitle("Restart to apply everywhere?")
            .setMessage("Spotify restarts on All, with the top chips and profile menu updated. Later applies on the next start.")
            .setPositiveButton("Restart now", this)
            .setNegativeButton("Later", this)
            .show();
    }

    private void onThemeSelected(int index, String themeName) {
        String current = Palette.requested();
        if (themeName.equals(current)) return;

        if (!Palette.choose(themeName)) return; // not saved: leave the selection as it was
        refreshThemes(themeName, true);

        new AlertDialog.Builder(getContext(), 0x010302d1) // android.R.style.Theme_DeviceDefault_Dialog_Alert: system Material dialog, not Holo
            .setTitle("Restart to apply theme?")
            .setMessage("Spotify needs to restart to apply the new color palette.")
            .setPositiveButton("Restart now", this)
            .setNegativeButton("Later", this)
            .show();
    }

    private void addSectionHeader(String title) {
        TextView header = new TextView(getContext());
        header.setText(title);
        header.setTextSize(14f);
        header.setTextColor(accent);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(8), dp(24), dp(8), dp(10));
        content.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Icon tile, title and subtitle, trailing control. refreshHomeCount relies on this child order. */
    private View createSettingRow(String iconName, String titleText, String subtitleText, View trailing) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        if (iconName != null) {
            ImageView tile = icon(iconName);
            tile.setBackground(rounded(high, dp(20)));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(40), dp(40));
            iconLp.rightMargin = dp(14);
            row.addView(tile, iconLp);
        }

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(titleText);
        titleView.setTextSize(16f);
        titleView.setTextColor(text);
        textLayout.addView(titleView);

        if (subtitleText != null || trailing instanceof ImageView) {
            TextView subView = new TextView(context);
            subView.setText(subtitleText);
            subView.setTextSize(13f);
            subView.setTextColor(subdued);
            textLayout.addView(subView);
        }

        row.addView(textLayout, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams trailingLp = trailing instanceof M3Switch
            ? new LinearLayout.LayoutParams(dp(52), dp(32)) : new LinearLayout.LayoutParams(dp(24), dp(24));
        trailingLp.leftMargin = dp(12);
        row.addView(trailing, trailingLp);
        return row;
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }

    public void onClick(View v) {
        if (v == backButton) {
            close();
            return;
        }
        if (v == homeToggle) {
            boolean open = homeList.getVisibility() != View.VISIBLE;
            homeList.setVisibility(open ? View.VISIBLE : View.GONE);
            if (Wave.animationsEnabled()) homeChevron.animate().rotation(open ? 90f : 0f).setDuration(200).start();
            else homeChevron.setRotation(open ? 90f : 0f);
            return;
        }
        if (v.getTag() instanceof Object[] && !(v.getTag() instanceof String[])) {
            Object[] tag = (Object[]) v.getTag();
            M3Switch s = (M3Switch) tag[1];
            String[] section = (String[]) tag[0];
            s.toggle();
            for (int i = 1; i < section.length; i++) Settings.hideSection(section[i], s.isChecked());
            describe(v, (String) tag[2], s.isChecked() ? "Hidden" : "Shown");
            refreshHomeCount();
            return;
        }
        if (v.getTag() instanceof String[]) {
            String[] tag = (String[]) v.getTag();
            int setting = Integer.parseInt(tag[0]);
            Settings.setChoice(setting, tag[1]);
            if (setting == Settings.ART_SPECTRUM && !"off".equals(tag[1])
                && getContext().checkSelfPermission(AudioSpectrum.PERMISSION) != 0) {
                AudioSpectrum.requestPermission(getContext());
            }
            refreshChoices();
            pop(v);
            return;
        }
        if (v == resetRow) {
            Settings.resetAll();
            for (int i = 0; i < featureRows.length; i++) {
                if (featureSwitches[i].isChecked() != Settings.get(i)) featureSwitches[i].toggle();
                describe(featureRows[i], FEATURE_TITLES[i], Settings.get(i) ? "On" : "Off");
            }
            refreshChoices();
            for (View row : homeRows) {
                Object[] tag = (Object[]) row.getTag();
                ((M3Switch) tag[1]).setChecked(false);
                describe(row, (String) tag[2], "Shown");
            }
            refreshHomeCount();
            return;
        }
        for (int i = 0; i < featureRows.length; i++) {
            if (v == featureRows[i]) {
                featureSwitches[i].toggle();
                boolean next = featureSwitches[i].isChecked();
                Settings.set(i, next);
                describe(featureRows[i], FEATURE_TITLES[i], next ? "On" : "Off");
                refreshChoices();
                for (int content : CONTENT) if (i == content) askRestart();
                return;
            }
        }
        for (int i = 0; i < themeRows.length; i++) {
            if (v == themeRows[i]) {
                onThemeSelected(i, Palette.NAMES[i]);
                return;
            }
        }
    }

    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            Palette.restart(getContext());
        } else {
            dialog.dismiss();
        }
    }

    /** Back callback and the end of the exit animation. */
    public void run() {
        close();
    }

    /** Slides out, then detaches (a second call, from the delayed run(), detaches at once). */
    public void close() {
        if (back != null) {
            back.unregister();
            back = null;
        }
        if (!closing && Wave.animationsEnabled()) {
            closing = true;
            animate().alpha(0f).translationX(density * 48).setDuration(180).start();
            postDelayed(this, 180);
            return;
        }
        ViewParent p = getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(this);
        }
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            close();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }
}
