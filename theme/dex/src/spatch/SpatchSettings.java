package spatch;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
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
    // hide_messages, mini_visualizer, haptics..., hide_recents to hide_company_info.
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
        "Hide Messages",
        "Mini-player visualizer",
        "Haptics",
        "Playback controls",
        "Navigation & filters",
        "Sliders & switches",
        "Hide Recents",
        "Hide Listening stats",
        "Hide Your plan",
        "Hide Your Updates",
        "Hide Taste Profile",
        "Hide Company Information",
        "Page transitions",
        "Slide between filters",
        "Side panel style",
        "Row cards",
        "Accent glow",
        "Expressive filters",
        "Pill glow",
        "Drag between filters",
        "Theme-coloured press",
        "Living play button",
        "Profile header",
        "Theme-coloured fallback",
        "Your Library style",
        "Row cards",
        "Pill filter chips",
        "Playlist track cards"
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
        "Removes Messages and the chat list; your messages stay",
        "Tiny real-audio bars on the mini-player artwork",
        "Follows Android's touch feedback setting",
        "Play, pause, skip and save",
        "Tab changes",
        "Toggles here and slider steps",
        "Your history stays",
        "Also covers Your Sound Capsule",
        "Your plan or Your Premium",
        "From the profile menu",
        "From the profile menu",
        "Only shown in some countries",
        "New pages fade in with a small rise",
        "Sections slide in from the chosen filter's side",
        "Rounded panel in the theme's colours",
        "Each row sits on its own rounded card",
        "A soft wash of colour at the top of the panel",
        "The sliding pill row on Home",
        "A soft halo around the selected filter",
        "Hold the selected filter and drag it to another",
        "Held rows use a theme tone instead of black",
        "The playlist button morphs and turns while playing",
        "Your own profile only. Only visible in Spatch.",
        "With no banner, your profile uses the theme instead of Spotify's colour",
        "Cards and filter chips in Your Library",
        "Each item sits on its own rounded card",
        "The selected chip turns into a pill",
        "Each track sits on its own rounded card"
    };

    /** encore_icon_<name>_24 for each feature row; a missing name just leaves its tile empty. */
    private static final String[] FEATURE_ICONS = {
        "soundwave", "enhance", "album", "album", "now_playing", "play", "pause", "soundwave",
        "repeat", "volume", "filter", "soundwave", "filter", "podcasts", "audiobook", "hidden", "soundwave", "notifications_active", "play", "home", "filter", "hidden", "hidden", "hidden", "hidden", "hidden", "hidden",
        "enhance", "filter", "enhance", "filter", "enhance", "filter", "enhance", "filter", "check", "play", "enhance", "enhance", "library", "enhance", "filter", "enhance"
    };

    /** Section layout: Settings indices per group. */
    private static final int[] ARTWORK = {2, 5}, PLAYER = {0, 4, 36}, VISUALIZER = {8, 9, 10, 11, 12}, CONTENT = {13, 14}, SIDE_MENU = {15, 21, 22, 23, 24, 25, 26}, MOTION = {1, 27, 28, 35, 6};
    private static final int ROUND_ART = 3, FULL_VISUALIZER = 7, MINI_VISUALIZER = 16, SIDE_STYLE = 29, FILTERS = 32;
    private static final int[] SIDE_PARTS = {30, 31}, FILTER_PARTS = {33, 34};
    /** Changes read once per process: chips and drawer rows at startup, press colours by the palette. */
    private static final int[] NEEDS_RESTART = {13, 14, 15, 21, 22, 23, 24, 25, 26, 29, 30, 31, 35};

    private static final String[] THEME_TITLES = {
        "Lavender",
        "Gruvbox",
        "Catppuccin",
        "Rosé Pine",
        "Nord",
        "Tokyo Night",
        "Dracula",
        "Gruvbox Material"
    };

    private static final int VIZ_THIN = 10, VIZ_FLOATING = 11;
    private static final int STAGGERED = 8; // entrance animation covers what is on screen at first

    final int surface = Palette.color(0xff14121c), card = Palette.color(0xff1d1a2a), high = Palette.color(0xff2b2640),
        text = Palette.color(0xfff3eeff), subdued = Palette.color(0xffbcb3dc), accent = Palette.color(0xffd0bcff);
    private final java.util.ArrayList<TextView> pillViews = new java.util.ArrayList<TextView>();
    private final View[] choiceRows = new View[Settings.CHOICES.length];
    private SpectrumPreview preview;
    private final java.util.ArrayList<View> homeRows = new java.util.ArrayList<View>();
    private final View backButton;
    private final SettingsHero hero;
    private final View barTitle;
    private LinearLayout content;
    private View resetRow, homeToggle, reviewRow;
    private LinearLayout homeList;
    private ImageView bannerPreview;
    private View chooseBanner, removeBanner;
    private static final float INACTIVE = 0.5f;
    private static final long SWAP = 220;
    private final FrameLayout pages;
    private final int bottom;
    private final java.util.ArrayList<View> stack = new java.util.ArrayList<View>();
    private final java.util.ArrayList<View> masters = new java.util.ArrayList<View>();
    private final java.util.HashMap<Integer, LinearLayout> masterPages = new java.util.HashMap<Integer, LinearLayout>();
    private TextView homeCount, themeNote;
    private boolean closing;
    private final M3Switch[] featureSwitches = new M3Switch[FEATURE_TITLES.length];
    private static final int[] HAPTICS = {17, 18, 19, 20};
    private View tryHaptics;
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
        title.setTypeface(Fonts.settings(700));
        title.setPadding(dp(16), 0, 0, 0);
        title.setAlpha(0f);
        header.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        barTitle = title;

        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        pages = new FrameLayout(context);
        root.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bottom = dp(32) + navBarHeight;
        LinearLayout home = addPage(null, null);
        LinearLayout categories = new LinearLayout(context);
        categories.setOrientation(LinearLayout.VERTICAL);

        hero = new SettingsHero(context);
        home.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(164)));
        home.addView(categories);

        for (int i = 0; i < FEATURE_TITLES.length; i++) {
            featureSwitches[i] = new M3Switch(context);
            featureSwitches[i].setChecked(Settings.get(i));
            featureRows[i] = createSettingRow(FEATURE_ICONS[i], FEATURE_TITLES[i], FEATURE_SUBTITLES[i], featureSwitches[i]);
            featureRows[i].setOnClickListener(this);
            describe(featureRows[i], FEATURE_TITLES[i], Settings.get(i) ? "On" : "Off");
        }

        View appearance = category(categories, "enhance", "Appearance", "Themes and typography", "Appearance");
        addSectionHeader("Theme");
        for (int row = 0; row < 3; row++) { // Palette.NAMES fills the first rows, blanks keep widths even
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

        addSectionHeader("Typography");
        new Typography(this, content);

        finishCategory(appearance);

        View playerPage = category(categories, "now_playing", "Player", "Full player, artwork and controls", "Player");
        LinearLayout artworkPage = subPage("Artwork");
        addSectionHeader("Shape");
        LinearLayout artwork = addGroup();
        addChoice(artwork, Settings.ART_OUTLINE, "Outline", "The ring around the artwork", new String[] {"Wavy", "Simple", "None"});
        addChoice(artwork, Settings.ART_GLOW, "Glow", null, new String[] {"Full", "Soft", "Off"});
        finish(artwork);
        resetFor(new int[] {ROUND_ART}, new int[] {Settings.ART_OUTLINE, Settings.ART_GLOW});
        inside(playerPage);
        addSectionHeader("Artwork");
        LinearLayout artRows = addGroup();
        artRows.addView(master(ROUND_ART, artworkPage));
        for (int i : ARTWORK) artRows.addView(featureRows[i]);
        finish(artRows);
        addSectionHeader("Controls");
        LinearLayout player = addGroup();
        for (int i : PLAYER) player.addView(featureRows[i]);
        finish(player);
        resetFor(new int[] {2, 5, 0, 4, 36}, new int[0]);
        finishCategory(playerPage);

        View visualizers = category(categories, "soundwave", "Visualizers", "Spectrum styles and where they show", "Visualizers");
        LinearLayout barsPage = subPage("Full-screen visualizer");
        LinearLayout visualizer = addGroup();
        preview = new SpectrumPreview(context);
        visualizer.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));
        addChoice(visualizer, Settings.VIZ_SHAPE, "Shape", null, new String[] {"Bars", "Centre", "Contour", "Dots"});
        addChoice(visualizer, Settings.VIZ_TAPER, "Edge taper", "Eases the bars off toward the sides", new String[] {"Subtle", "None"});
        for (int i : VISUALIZER) visualizer.addView(featureRows[i]);
        finish(visualizer);
        resetFor(concat(new int[] {FULL_VISUALIZER}, VISUALIZER), new int[] {Settings.VIZ_SHAPE, Settings.VIZ_TAPER});
        inside(visualizers);
        LinearLayout places = addGroup();
        places.addView(master(FULL_VISUALIZER, barsPage));
        places.addView(featureRows[MINI_VISUALIZER]);
        addChoice(places, Settings.ART_SPECTRUM, "Around the artwork", "Real audio around circular art; uses the microphone permission", new String[] {"Off", "Fine", "Bars", "Dots"});
        finish(places);
        refreshChoices();
        resetFor(new int[] {MINI_VISUALIZER}, new int[] {Settings.ART_SPECTRUM});
        finishCategory(visualizers);

        View screens = category(categories, "home", "Screens", "Home filters and the side panel", "Screens");
        LinearLayout filterPage = subPage("Expressive filters");
        LinearLayout filterRows = addGroup();
        for (int i : FILTER_PARTS) filterRows.addView(featureRows[i]);
        finish(filterRows);
        resetFor(concat(new int[] {FILTERS}, FILTER_PARTS), new int[0]);
        LinearLayout sidePage = subPage("Side panel style");
        LinearLayout sideRows = addGroup();
        for (int i : SIDE_PARTS) sideRows.addView(featureRows[i]);
        finish(sideRows);
        resetFor(concat(new int[] {SIDE_STYLE}, SIDE_PARTS), new int[0]);
        LinearLayout profilePage = subPage("Profile header");
        TextView local = new TextView(context);
        local.setText("Only visible in Spatch. Your banner stays on this phone and never changes your public profile.");
        local.setTextSize(13f);
        local.setTextColor(subdued);
        local.setPadding(dp(8), dp(8), dp(8), dp(12));
        content.addView(local);
        bannerPreview = new ImageView(context, null);
        bannerPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bannerPreview.setBackground(rounded(card, dp(24)));
        bannerPreview.setClipToOutline(true);
        bannerPreview.setContentDescription("Banner preview");
        content.addView(bannerPreview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        addSectionHeader("Banner");
        LinearLayout bannerRows = addGroup();
        chooseBanner = createSettingRow("plus", "Choose image", "From your files, up to 30 MB", icon("chevron_right"));
        removeBanner = createSettingRow("hidden", "Remove banner", "Goes back to the fallback header", icon("chevron_right"));
        chooseBanner.setOnClickListener(this);
        removeBanner.setOnClickListener(this);
        bannerRows.addView(chooseBanner);
        bannerRows.addView(removeBanner);
        addChoice(bannerRows, Settings.PROFILE_POSITION, "Position", "Which part of a tall image shows", new String[] {"Centre", "Top", "Bottom"});
        bannerRows.addView(featureRows[38]);
        finish(bannerRows);
        refreshChoices();
        refreshBanner();
        BannerImport.done = new Runnable() {
            public void run() {
                refreshBanner();
            }
        };
        resetFor(new int[] {37, 38}, new int[] {Settings.PROFILE_POSITION});
        LinearLayout libraryPage = subPage("Your Library style");
        LinearLayout libraryRows = addGroup();
        libraryRows.addView(featureRows[40]);
        libraryRows.addView(featureRows[41]);
        finish(libraryRows);
        resetFor(new int[] {39, 40, 41}, new int[0]);
        inside(screens);
        LinearLayout screenRows = addGroup();
        screenRows.addView(master(39, libraryPage));
        screenRows.addView(featureRows[42]);
        screenRows.addView(master(37, profilePage));
        screenRows.addView(master(FILTERS, filterPage));
        screenRows.addView(master(SIDE_STYLE, sidePage));
        finish(screenRows);
        finishCategory(screens);

        View navigation = category(categories, "filter", "Navigation & interactions", "Motion and haptics", "Navigation & interactions");
        addSectionHeader("Motion");
        LinearLayout motion = addGroup();
        for (int i : MOTION) motion.addView(featureRows[i]);
        finish(motion);
        addSectionHeader("Haptics");
        LinearLayout haptics = addGroup();
        for (int i : HAPTICS) haptics.addView(featureRows[i]);
        tryHaptics = createSettingRow("check", "Try feedback", "One sample tap", icon("chevron_right"));
        tryHaptics.setOnClickListener(this);
        haptics.addView(tryHaptics);
        finish(haptics);
        resetFor(concat(MOTION, HAPTICS), new int[0]);
        finishCategory(navigation);

        View contentPage = category(categories, "hidden", "Content", "Hidden sections, podcasts and the side menu", "Content");
        LinearLayout sectionsPage = subPage("Home sections");
        homeList = addGroup();
        reviewRow = createSettingRow("hidden", "Review on Home", "Swipe away sections right on Home", icon("chevron_right"));
        reviewRow.setOnClickListener(this);
        describe(reviewRow, "Review on Home", "Swipe away sections right on Home");
        homeList.addView(reviewRow);
        for (String[] section : Settings.sections()) {
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
        inside(contentPage);
        LinearLayout hiding = addGroup();
        for (int i : CONTENT) hiding.addView(featureRows[i]);
        homeToggle = createSettingRow("home", "Home sections", null, icon("chevron_right"));
        homeToggle.setTag(sectionsPage);
        homeCount = (TextView) ((ViewGroup) ((ViewGroup) homeToggle).getChildAt(1)).getChildAt(1);
        homeToggle.setOnClickListener(this);
        hiding.addView(homeToggle);
        finish(hiding);
        refreshHomeCount();
        addSectionHeader("Side menu");
        LinearLayout sideMenu = addGroup();
        for (int i : SIDE_MENU) sideMenu.addView(featureRows[i]);
        finish(sideMenu);
        resetFor(concat(CONTENT, SIDE_MENU), new int[0]);
        finishCategory(contentPage);
        finish(categories);

        content = home;
        TextView reset = new TextView(context);
        reset.setText("Reset everything");
        reset.setTextSize(14f);
        reset.setTextColor(accent);
        reset.setTypeface(Fonts.settings(700));
        reset.setGravity(Gravity.CENTER);
        reset.setClickable(true);
        reset.setFocusable(true);
        reset.setBackground(ripple(rounded(high, dp(24))));
        reset.setOnClickListener(this);
        describe(reset, "Reset everything", "Button");
        resetRow = reset;
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        resetLp.topMargin = dp(28);
        content.addView(reset, resetLp);

        TextView about = new TextView(context);
        about.setText("Keeps your theme and font. Theme and font changes apply after a restart; everything else straight away.");
        about.setTextSize(12f);
        about.setTextColor(subdued);
        about.setGravity(Gravity.CENTER);
        about.setPadding(dp(16), dp(10), dp(16), 0);
        content.addView(about);
        Fonts.restyle(this);
    }

    /** A scrolling page, hidden until opened; rows added next land on it. Pages keep their scroll. */
    private LinearLayout addPage(String title, View opener) {
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout page = new LinearLayout(getContext());
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(4), dp(16), bottom);
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.setTag(title);
        pages.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (title == null) {
            scroll.setOnScrollChangeListener(this);
            stack.add(scroll);
        } else {
            scroll.setVisibility(View.GONE);
        }
        content = page;
        return page;
    }

    private LinearLayout subPage(String title) {
        return addPage(title, null);
    }

    /** Adds a category row to the root list and starts its page. */
    private View category(LinearLayout list, String iconName, String title, String subtitle, String pageTitle) {
        LinearLayout page = addPage(pageTitle, null);
        View row = createSettingRow(iconName, title, subtitle, icon("chevron_right"));
        row.setTag(page);
        row.setOnClickListener(this);
        describe(row, title, subtitle);
        list.addView(row);
        return page;
    }

    private void inside(View page) {
        content = (LinearLayout) page;
    }

    private void finishCategory(View page) {
        content = null;
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Title and text open the submenu; the switch is its own target and keeps children's values. */
    private View master(int setting, LinearLayout page) {
        Context c = getContext();
        LinearLayout row = (LinearLayout) createSettingRow(FEATURE_ICONS[setting], FEATURE_TITLES[setting], FEATURE_SUBTITLES[setting], icon("chevron_right"));
        row.setTag(page);
        row.setOnClickListener(this);
        row.setPadding(dp(16), dp(12), 0, dp(12));
        describe(row, FEATURE_TITLES[setting], "Open");
        View divider = new View(c, null);
        divider.setBackgroundColor(text & 0x33ffffff);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(1), dp(32));
        line.leftMargin = dp(8);
        row.addView(divider, line);
        FrameLayout box = new FrameLayout(c);
        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(this);
        box.setTag(Integer.valueOf(setting));
        ViewGroup old = (ViewGroup) featureSwitches[setting].getParent();
        if (old != null) old.removeView(featureSwitches[setting]);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(52), dp(32), Gravity.CENTER);
        box.addView(featureSwitches[setting], lp);
        box.setContentDescription(FEATURE_TITLES[setting] + ", " + (Settings.get(setting) ? "On" : "Off"));
        row.addView(box, new LinearLayout.LayoutParams(dp(76), dp(56)));
        masterPages.put(Integer.valueOf(setting), page);
        masters.add(box);
        page.setAlpha(Settings.get(setting) ? 1f : INACTIVE);
        return row;
    }

    /** One reset per page, covering just what the page shows. */
    private void resetFor(final int[] features, final int[] choices) {
        TextView reset = new TextView(getContext());
        reset.setText("Reset this page");
        reset.setTextSize(14f);
        reset.setTextColor(accent);
        reset.setTypeface(Fonts.settings(700));
        reset.setGravity(Gravity.CENTER);
        reset.setClickable(true);
        reset.setFocusable(true);
        reset.setBackground(ripple(rounded(high, dp(24))));
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                for (int i : features) Settings.set(i, Settings.DEFAULTS[i]);
                for (int i : choices) Settings.setChoice(i, Settings.CHOICES[i][0]);
                syncFeatures();
                MiniBar.settingsChanged();
                Haptics.toggle(v, true);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(20);
        content.addView(reset, lp);
    }

    private void syncFeatures() {
        for (int i = 0; i < featureRows.length; i++) {
            if (featureSwitches[i].isChecked() != Settings.get(i)) featureSwitches[i].toggle();
            describe(featureRows[i], FEATURE_TITLES[i], Settings.get(i) ? "On" : "Off");
        }
        for (java.util.Map.Entry<Integer, LinearLayout> e : masterPages.entrySet()) e.getValue().setAlpha(Settings.get(e.getKey().intValue()) ? 1f : INACTIVE);
        for (View box : masters) {
            int i = ((Integer) box.getTag()).intValue();
            box.setContentDescription(FEATURE_TITLES[i] + ", " + (Settings.get(i) ? "On" : "Off"));
        }
        refreshChoices();
    }

    private void open(LinearLayout page) {
        final View to = (View) page.getParent(), from = stack.get(stack.size() - 1);
        stack.add(to);
        barTitle.setAlpha(1f);
        ((TextView) barTitle).setText((String) page.getTag());
        hero.setOffscreen(true);
        swap(from, to, 1);
    }

    private void swap(final View from, View to, int direction) {
        to.setVisibility(View.VISIBLE);
        if (!Wave.animationsEnabled()) {
            from.setVisibility(View.GONE);
            return;
        }
        to.setAlpha(0f);
        to.setTranslationX(direction * density * 40);
        to.animate().alpha(1f).translationX(0f).setDuration(SWAP).start();
        from.animate().alpha(0f).translationX(-direction * density * 24).setDuration(SWAP).start();
        postDelayed(new Runnable() {
            public void run() {
                from.setVisibility(View.GONE);
                from.setAlpha(1f);
                from.setTranslationX(0f);
            }
        }, SWAP);
    }

    private boolean popPage() {
        if (stack.size() < 2) return false;
        View from = stack.remove(stack.size() - 1), to = stack.get(stack.size() - 1);
        Object title = ((ViewGroup) to).getChildAt(0).getTag();
        if (title == null) {
            ((TextView) barTitle).setText("Spatch");
            onScrollChange(to, 0, ((ScrollView) to).getScrollY(), 0, 0);
        } else {
            ((TextView) barTitle).setText((String) title);
        }
        swap(from, to, -1);
        return true;
    }

    GradientDrawable rounded(int color, float radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius);
        return bg;
    }

    private RippleDrawable ripple(GradientDrawable shape) {
        return new RippleDrawable(ColorStateList.valueOf(text & 0x24ffffff), shape, null);
    }

    ImageView icon(String name) {
        ImageView image = new ImageView(getContext(), null);
        image.setScaleType(ImageView.ScaleType.CENTER);
        int id = getContext().getResources().getIdentifier("encore_icon_" + name + "_24", "drawable", getContext().getPackageName());
        if (id != 0) {
            image.setImageResource(id);
            image.setColorFilter(accent);
        }
        return image;
    }

    LinearLayout addGroup() {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(LinearLayout.VERTICAL);
        content.addView(group);
        return group;
    }

    /** Rows connect with a 2dp gap; only the group's outer corners are fully round. */
    void finish(LinearLayout group) {
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
            pill.setTypeface(Fonts.settings(selected ? 700 : 400));
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
        for (int i = 0; i < Math.min(STAGGERED, content.getChildCount()); i++) { // the root list
            View child = content.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(density * 28);
            child.animate().alpha(1f).translationY(0f).setDuration(340).setStartDelay(60 + i * 40).start();
        }
    }

    /** The hero stops animating once it has scrolled away, and the bar takes over its title. */
    public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
        if (stack.size() > 1) return;
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
        askRestart("Restart to apply everywhere?", "Spotify restarts on All, with the top chips and profile menu updated. Later applies on the next start.");
    }

    void askRestart(String title, String message) {
        new AlertDialog.Builder(getContext(), 0x010302d1)
            .setTitle(title)
            .setMessage(message)
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
        header.setTypeface(Fonts.settings(700));
        header.setPadding(dp(8), dp(24), dp(8), dp(10));
        content.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Icon tile, title and subtitle, trailing control. refreshHomeCount relies on this child order. */
    View createSettingRow(String iconName, String titleText, String subtitleText, View trailing) {
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

    int dp(int value) {
        return (int) (value * density + 0.5f);
    }

    public void onClick(View v) {
        if (v == backButton) {
            close();
            return;
        }
        if (v.getTag() instanceof LinearLayout) {
            open((LinearLayout) v.getTag());
            return;
        }
        if (v.getTag() instanceof Integer) {
            int i = ((Integer) v.getTag()).intValue();
            featureSwitches[i].toggle();
            changed(i, featureSwitches[i].isChecked(), v);
            syncFeatures();
            return;
        }
        if (v == chooseBanner) {
            BannerImport.start(getContext());
            return;
        }
        if (v == removeBanner) {
            ProfileBanner.file().delete();
            ProfileBanner.changed();
            refreshBanner();
            return;
        }
        if (v == reviewRow) {
            if (HomeReview.start((ViewGroup) getParent())) close();
            else new AlertDialog.Builder(getContext(), 0x010302d1).setTitle("Open Home first").setMessage("Review works on the Home tab, once its sections have loaded.").setPositiveButton("OK", null).show();
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
            syncFeatures();
            for (View row : homeRows) {
                Object[] tag = (Object[]) row.getTag();
                ((M3Switch) tag[1]).setChecked(false);
                describe(row, (String) tag[2], "Shown");
            }
            refreshHomeCount();
            return;
        }
        if (v == tryHaptics) {
            Haptics.preview(v);
            return;
        }
        for (int i = 0; i < featureRows.length; i++) {
            if (v == featureRows[i]) {
                featureSwitches[i].toggle();
                changed(i, featureSwitches[i].isChecked(), v);
                describe(featureRows[i], FEATURE_TITLES[i], Settings.get(i) ? "On" : "Off");
                refreshChoices();
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

    private void changed(int setting, boolean value, View v) {
        Settings.set(setting, value);
        Haptics.toggle(v, value);
        MiniBar.settingsChanged();
        for (int i : NEEDS_RESTART) if (i == setting) askRestart();
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

    private void refreshBanner() {
        android.graphics.Bitmap banner = ProfileBanner.banner();
        bannerPreview.setImageBitmap(banner);
        removeBanner.setEnabled(banner != null);
        removeBanner.setAlpha(banner != null ? 1f : INACTIVE);
    }

    /** Slides out, then detaches (a second call, from the delayed run(), detaches at once). */
    public void close() {
        if (popPage()) {
            if (back != null) back.unregister();
            if (android.os.Build.VERSION.SDK_INT >= 33) back = Back.register(this, this); // a Back gesture spends its callback
            return;
        }
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
