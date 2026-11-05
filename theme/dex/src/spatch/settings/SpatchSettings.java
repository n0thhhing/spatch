package spatch;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
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
public final class SpatchSettings extends FrameLayout implements View.OnClickListener, View.OnScrollChangeListener, DialogInterface.OnClickListener, DialogInterface.OnDismissListener, Runnable {
    Back back;

    // Indexed like Settings.KEYS: wave, buttons, backdrop, round_art, immersive, shapes, reduce_motion, visualizer,
    // viz_mirrored, viz_smooth, viz_thin, viz_floating, viz_side_margin, hide_podcasts, hide_audiobooks,
    // hide_messages, mini_visualizer, haptics..., hide_recents to hide_company_info.
    static final String[] FEATURE_TITLES = {
        "Wavy progress",
        "Button animations",
        "Album art backdrop",
        "Circular album art",
        "Immersive player",
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
        "Playlist track cards",
        "Navigation bar",
        "Hold and drag",
        "Stretch animation",
        "Lift while dragging",
        "Press highlight",
        "Icon pop",
        "Springy press",
        "Springy press",
        "Thicken on touch",
        "Shuffle and repeat bounce",
        "Skip arrow nudge",
        "Turn while playing",
        "Morph on play and pause",
        "Title ease",
        "Opening flight",
        "Rounded corners",
        "Theme-coloured dim",
        "New song pop",
        "Artwork flight",
        "Drifting colours",
        "Pill slide",
        "Rise in",
        "Hold ring",
        "Touch glow",
        "Rise in",
        "Hold ring",
        "Touch glow",
        "Play button burst",
        "Full player",
        "Mini player",
        "Immersive player",
        "Playlists",
        "Artwork tilt",
        "Scratch the record",
        "Hold to seek",
        "Seek time bubble",
        "Album colour bar",
        "Tap ripples",
        "Playful banner",
        "Bouncy press",
        "Hide bars while scrolling",
        "Beat bounce",
        "Hold to like",
        "Spinning cover",
        "Swipe to skip",
        "Cards on every list",
        "Soft headers",
        "Lists rise in",
        "Follow the music",
        "Title in album colour",
        "Focus the current lyric",
        "Queue shimmer",
        "Widget follows wallpaper",
        "Hide the Premium tab",
        "Hide account switcher",
        "Hide Parental controls",
        "Seek splash",
        "Bass shockwaves",
        "Fling cover to skip",
        "Card tilt and glint",
        "Hide player lyric preview",
        "Immersive lyrics",
        "Landscape layout",
        "Playing track equalizer",
        "Vinyl grooves & sheen",
        "Liquid scrubber",
        "Frosted glass pill"
    };

    static final String[] FEATURE_SUBTITLES = {
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
        "Each track sits on its own rounded card",
        "The sliding pill behind the selected tab",
        "Drag along the bar to switch tabs",
        "The pill stretches toward the new tab",
        "The bar grows a little under your finger",
        "A soft pill on the tab you press",
        "The selected icon sits a little larger",
        "The artwork squishes and bounces when held",
        "Hero card, settings rows, filter chips and mini-player art bounce when pressed",
        "The bar grows while you drag it",
        "They pop on tap",
        "A soft arrow slides on next and previous",
        "The button slowly rotates",
        "Five bumps paused, nine playing",
        "A new song's title fades and slides in",
        "Artwork flies between the player and immersive",
        "The panel's right corners",
        "Dims the page behind the panel in the theme's colour",
        "The cover pops in on a new track",
        "The cover flies into the full player",
        "The backdrop slowly moves",
        "The pill slides between filters",
        "Rows arrive one after another when the page opens",
        "A ring charges around your finger and pops as the menu opens",
        "A soft light follows your finger while you scroll",
        "Tracks arrive one after another when the page opens",
        "A ring charges around your finger and pops as the menu opens",
        "A soft light follows your finger while you scroll",
        "Shapes pop out when you press play or pause",
        "The play button in Now Playing",
        "The play button on the bar",
        "The double-tap full-screen player",
        "The big play button on playlist pages",
        "Soft glare under your finger",
        "Hold the art, then spin it to seek",
        "Hold Next or Previous to run through the song",
        "Shows the time above your finger",
        "Takes on a little of the cover's colour",
        "A soft ring wherever you tap",
        "Drag the shape on the Spatch banner",
        "Tiles and rows spring back when you let go",
        "The bars slide away going down and return going up",
        "The artwork pulses with the bass",
        "Hold the mini player's cover to like the song",
        "The mini player's cover turns like a record while playing",
        "Drag the artwork sideways in the immersive player",
        "Search results, albums and artist pages get row cards",
        "Bright page headers become a tint of the theme",
        "Results and album rows arrive one after another",
        "The wave swells with the song; uses the audio permission",
        "The Now Playing title takes the cover's main colour",
        "Other lines shrink and the current one glows in the cover's colour",
        "A soft light passes over the playing song in the queue",
        "The home screen widget takes your wallpaper's colours, light or dark (Android 12+)",
        "Free accounts: takes the Premium tab out of the bottom bar",
        "Your other accounts and Add account, from the profile card",
        "From the profile card; the controls themselves stay as they are",
        "Droplets and a ripple when a seek or record scratch lands",
        "Bass kicks send rings through the backdrop; uses the audio permission",
        "Swipe the mini-player cover sideways to fling it off and skip",
        "Rows and cards tilt in 3D with a diagonal sheen when pressed",
        "Collapses the lyrics between artwork and controls; full lyrics stay available",
        "Spotify’s current lyric beneath the cover; tap for nearby lines",
        "Artwork beside the controls when the screen is wider",
        "Dancing mini equalizer bars on currently playing tracks",
        "Concentric micro-grooves and specular shimmer on circular art",
        "Fluid meniscus and audio droplets when seeking",
        "Translucent acrylic surface with a luminous specular rim"
    };

    /** Icon names for each feature row: a bundled Material Symbol (spatch_ms_<name>), else encore_icon_<name>_24; a missing name leaves the tile empty. */
    static final String[] FEATURE_ICONS = {
        "soundwave", "enhance", "image", "album", "fullscreen", "star", "pause", "soundwave",
        "repeat", "time", "list_view", "arrow_up", "minimize", "podcasts", "audiobook", "group", "now_playing", "notifications_active", "play", "home", "filter", "time", "sort", "gem", "notification_new", "heart", "information_alt",
        "external_link", "arrow_left", "list_view", "track", "enhance", "filter", "enhance", "arrow_top_left", "t_shirt", "play", "user", "t_shirt", "collection_active", "list_view", "filter_active", "playlist",
        "device_mobile", "arrow_left", "fullscreen", "arrow_up", "enhance", "star", "heart", "star", "track", "shuffle", "skip_forward", "repeat", "enhance", "edit", "fullscreen", "minimize", "t_shirt", "star", "fullscreen", "time", "arrow_left",
        "arrow_up", "time", "enhance", "arrow_up", "time", "enhance", "star", "now_playing", "device_mobile", "fullscreen", "playlist",
        "enhance", "repeat", "skip_forward", "time", "device_mobile", "star", "enhance", "enhance", "arrow_up", "soundwave", "heart", "album", "skip_forward", "list_view", "image", "arrow_up", "soundwave", "image", "enhance", "list_view", "home", "gem", "group", "user", "star", "soundwave", "skip_forward", "enhance", "track", "track", "fullscreen", "soundwave", "album", "soundwave", "enhance"
    };

    /** Section layout: Settings indices per group. */
    private static final int[] ARTWORK = {2, 5}, VISUALIZER = {8, 9, 10, 11, 12}, CONTENT_MEDIA = {13, 14, 15}, CONTENT_NAV = {96, 97, 98}, CONTENT_PROFILE = {21, 22, 23, 24, 25, 26};
    private static final int[] CONTENT = CONTENT_MEDIA, SIDE_MENU = {96, 97, 98, 21, 22, 23, 24, 25, 26}, MOTION = {27, 35, 50, 6};
    private static final int ROUND_ART = 3, NAV_STYLE = 43, FULL_VISUALIZER = 7, MINI_VISUALIZER = 16, SIDE_STYLE = 29, FILTERS = 32;
    private static final int[] SIDE_PARTS = {30, 31, 58, 59}, FILTER_PARTS = {33, 34, 63, 28}, NAV_PARTS = {44, 45, 46, 47, 48}, BURST_PLACES = {71, 72, 73, 74};
    /** Changes read once per process: chips and drawer rows at startup, press colours by the palette. */
    private static final int[] NEEDS_RESTART = {13, 14, 96, 15, 97, 98, 21, 22, 23, 24, 25, 26, 29, 30, 31, 35, 58, 59};

    private static final String[] THEME_TITLES = {
        "Lavender",
        "Gruvbox",
        "Catppuccin Mocha",
        "Rosé Pine",
        "Nord",
        "Tokyo Night",
        "Dracula",
        "Gruvbox Material",
        "Matugen (wallpaper)",
        "Default",
        "Spotify",
        "Catppuccin Macchiato",
        "Catppuccin Frappé",
        "Rosé Pine Moon",
        "Tokyo Night Storm",
        "Everforest",
        "Kanagawa",
        "Solarized",
        "One",
        "Imported theme"
    };

    private static final int VIZ_THIN = 10, VIZ_FLOATING = 11;
    private static final int STAGGERED = 8; // entrance animation covers what is on screen at first

    final int surface = Palette.color(0xff14121c), card = Palette.color(0xff2b2640), high = Palette.color(0xff363050),
        text = Palette.color(0xfff3eeff), subdued = Palette.color(0xffbcb3dc), accent = Palette.color(0xffd0bcff);
    private final java.util.ArrayList<TextView> pillViews = new java.util.ArrayList<TextView>();
    private final View[] choiceRows = new View[Settings.CHOICES.length];
    private final android.widget.SeekBar[] strengthSliders = new android.widget.SeekBar[Settings.STRENGTH_KEYS.length];
    private SpectrumPreview preview;
    private final java.util.ArrayList<View> homeRows = new java.util.ArrayList<View>();
    private final View backButton;
    private final SettingsHero hero;
    private final View barTitle;
    private LinearLayout content;
    private View resetRow, homeToggle;
    private LinearLayout homeList;
    private ImageView bannerPreview;
    private View chooseBanner, removeBanner;
    private static final float INACTIVE = 0.5f;
    private static final long SWAP = 220;
    private static final int RISE_ROWS = 8;
    /** The playful extras a preset turns off or on together; choices and everything else stay as they are. */
    static final int[] PRESET = {Settings.ART_TILT, Settings.ART_SCRATCH, Settings.BEAT_BOUNCE, Settings.HOLD_SEEK, Settings.SEEK_BUBBLE, Settings.MINI_ALBUM, Settings.TAP_RIPPLE, Settings.HERO_TOUCH, Settings.BOUNCY_PRESS, Settings.HIDE_BARS, Settings.LIST_RISE, Settings.LIBRARY_CASCADE, Settings.LIBRARY_HOLD, Settings.LIBRARY_GLOW, Settings.PLAYLIST_CASCADE, Settings.PLAYLIST_HOLD, Settings.PLAYLIST_GLOW, Settings.BURST, Settings.SEEK_SPLASH, Settings.BASS_SHOCKWAVE, Settings.MINI_FLING, Settings.CARD_GLINT, Settings.ROW_EQUALIZER, Settings.VINYL_EFFECT, Settings.LIQUID_SCRUBBER, Settings.MINI_FROSTED};
    private static final android.view.animation.DecelerateInterpolator RISE = new android.view.animation.DecelerateInterpolator(2f);
    final FrameLayout pages;
    private final int bottom;
    private final java.util.ArrayList<View> stack = new java.util.ArrayList<View>();
    private final java.util.ArrayList<View> masters = new java.util.ArrayList<View>();
    private final java.util.HashMap<Integer, LinearLayout> masterPages = new java.util.HashMap<Integer, LinearLayout>();
    private TextView homeCount, themeNote;
    private ThemeReveal reveal;
    private LinearLayout appearancePage, palettePage;
    private final java.util.ArrayList<Object[]> searchable=new java.util.ArrayList<Object[]>();
    private boolean creatingResults;
    private boolean closing;
    private final M3Switch[] featureSwitches = new M3Switch[FEATURE_TITLES.length];
    private static final int[] HAPTICS = {17, 18, 19, 20};
    private View tryHaptics, tryBurst;
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
            backBtn.setImageResource(R_drawable_abc_ic_ab_back_material); // abc_ic_ab_back_material
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
        pages.setClipChildren(false);
        root.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bottom = dp(32) + navBarHeight;
        LinearLayout home = addPage(null, null);
        LinearLayout categories = new LinearLayout(context);
        categories.setOrientation(LinearLayout.VERTICAL);

        hero = new SettingsHero(context);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(164));
        heroLp.bottomMargin = dp(16);
        home.addView(hero, heroLp);
        addSearch(home);
        unclip(categories);
        home.addView(categories);

        for (int i = 0; i < FEATURE_TITLES.length; i++) {
            featureSwitches[i] = new M3Switch(context);
            featureSwitches[i].setChecked(Settings.get(i));
            featureRows[i] = createSettingRow(FEATURE_ICONS[i], FEATURE_TITLES[i], FEATURE_SUBTITLES[i], featureSwitches[i]);
            featureRows[i].setOnClickListener(this);
            describe(featureRows[i], FEATURE_TITLES[i], Settings.get(i) ? "On" : "Off");
        }

        View appearance = appearancePage = (LinearLayout) category(categories, "enhance", "Appearance", "Palettes, light/dark, imports and fonts", "Appearance");
        palettePage=subPage("Themes & display");
        addSectionHeader("Display mode");
        LinearLayout modeRows=addGroup();
        final M3Switch[] modeSwitches=new M3Switch[3];
        final String[] modes={"system","light","dark"};
        String[] modeNames={"System","Light","Dark"};
        for(int i=0;i<modes.length;i++){
            final int selected=i;
            modeSwitches[i]=new M3Switch(context);modeSwitches[i].setChecked(Palette.mode().equals(modes[i]));
            View row=createSettingRow("t_shirt",modeNames[i],i==0?"Follow the phone’s light or dark setting":null,modeSwitches[i]);
            row.setOnClickListener(new View.OnClickListener(){public void onClick(View view){
                if(!Palette.mode(modes[selected]))return;
                for(int n=0;n<modeSwitches.length;n++)modeSwitches[n].setChecked(n==selected);
                applyTheme(Palette.requested());
            }});
            modeRows.addView(row);
        }
        finish(modeRows);
        addSectionHeader("Import");
        LinearLayout imports=addGroup();
        View importTheme=createSettingRow("plus","Import theme","JSON or TOML, with dark and light colours",icon("chevron_right"));
        importTheme.setOnClickListener(new View.OnClickListener(){public void onClick(View view){ThemeImport.start(getContext(),new Runnable(){public void run(){refreshThemes(Palette.requested(),false);}});}});
        imports.addView(importTheme);finish(imports);
        addSectionHeader("Palettes");
        for (int row = 0; row < (themeRows.length + 2) / 3; row++) { // blanks keep the last row's widths even
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = row * 3; i < row * 3 + 3; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(112), 1f);
                if (i >= themeRows.length) { // keeps the last row's swatches the same width
                    line.addView(new View(context, null), lp);
                    continue;
                }
                themeRows[i] = new ThemeSwatch(context, i, themeTitle(i));
                index(themeRows[i],themeTitle(i),"Theme palette");
                themeRows[i].setOnClickListener(this);
                Springy.attach(themeRows[i]);
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

        LinearLayout fontsPage=subPage("Fonts");
        new Typography(this, content);
        content=(LinearLayout)appearance;
        LinearLayout appearanceLinks=addGroup();
        link(appearanceLinks,"t_shirt","Themes & display","Palettes, imports and light / dark / system",palettePage);
        link(appearanceLinks,"track","Fonts","Typefaces, weights and font import",fontsPage);
        finish(appearanceLinks);
        finishCategory(appearance);

        View playerPage = category(categories, "now_playing", "Player", "Artwork, lyrics, gestures and immersive", "Player");
        addPlayerPreview((LinearLayout) playerPage);
        LinearLayout artworkPage = subPage("Artwork");
        addPlayerPreview(artworkPage);
        addSectionHeader("Ring");
        LinearLayout ringRows = addGroup();
        addChoice(ringRows, Settings.ART_OUTLINE, "Style", "Audio moves the ring with the music; uses the microphone permission", new String[] {"Wavy", "Simple", "None", "Audio"});
        addColors(ringRows, Settings.RING_COLOR, "Colour");
        addChoice(ringRows, Settings.RING_WIDTH, "Thickness", null, new String[] {"Normal", "Thin", "Bold"});
        addChoice(ringRows, Settings.RING_SPEED, "Turning speed", null, new String[] {"Normal", "Slow", "Fast", "Still"});
        finish(ringRows);
        addSectionHeader("Glow");
        LinearLayout glowRows = addGroup();
        addChoice(glowRows, Settings.ART_GLOW, "Strength", null, new String[] {"Full", "Soft", "Off"});
        addChoice(glowRows, Settings.GLOW_STYLE, "Style", "Fade breathes in and out; Sweep circles the artwork", new String[] {"Halo", "Wide", "Fade", "Sweep", "Aura"});
        addColors(glowRows, Settings.GLOW_COLOR, "Colour");
        finish(glowRows);
        addSectionHeader("Feel");
        LinearLayout artFeel = addGroup();
        artFeel.addView(featureRows[49]);
        artFeel.addView(featureRows[Settings.VINYL_EFFECT]);
        finish(artFeel);
        resetFor(new int[] {ROUND_ART, 49, Settings.VINYL_EFFECT}, new int[] {Settings.ART_OUTLINE, Settings.RING_COLOR, Settings.RING_WIDTH, Settings.RING_SPEED, Settings.ART_GLOW, Settings.GLOW_STYLE, Settings.GLOW_COLOR});
        LinearLayout backdropPage = subPage("Album art backdrop");
        LinearLayout backdropRows = addGroup();
        backdropRows.addView(featureRows[62]);
        backdropRows.addView(featureRows[Settings.BASS_SHOCKWAVE]);
        addStrength(backdropRows, Settings.SHOCK_STRENGTH, "Shockwave strength", "Opacity of the bass rings", "%");
        finish(backdropRows);
        resetFor(new int[] {2, 62, Settings.BASS_SHOCKWAVE}, new int[0]);
        LinearLayout wavePage = subPage("Wavy progress");
        LinearLayout waveRows = addGroup();
        addChoice(waveRows, Settings.SEEK_STYLE, "Style", "Scope uses Follow the music", new String[] {"Wave", "Beads", "Pulse", "Line", "Scope"});
        addChoice(waveRows, Settings.WAVE_SPEED, "Speed", null, new String[] {"Normal", "Slow", "Fast"});
        waveRows.addView(featureRows[51]);
        waveRows.addView(featureRows[Settings.SEEK_SPLASH]);
        addStrength(waveRows, Settings.SPLASH_SIZE, "Seek splash size", "Droplet spread and ripple size", "%");
        waveRows.addView(featureRows[Settings.LIQUID_SCRUBBER]);
        waveRows.addView(featureRows[Settings.WAVE_AUDIO]);
        finish(waveRows);
        addSectionHeader("With the music");
        LinearLayout musicRows = addGroup();
        addChoice(musicRows, Settings.WAVE_REACTS, "What moves", "Wave style, with Follow the music on", new String[] {"Height", "Thickness", "Speed", "All three"});
        addChoice(musicRows, Settings.WAVE_FOLLOWS, "Follows", "Melody lets each part of the bar follow its own notes", new String[] {"Beat and melody", "Beat", "Melody"});
        addChoice(musicRows, Settings.WAVE_STRENGTH, "Strength", null, new String[] {"Normal", "Subtle", "Strong"});
        finish(musicRows);
        resetFor(new int[] {0, 51, Settings.WAVE_AUDIO, Settings.SEEK_SPLASH, Settings.LIQUID_SCRUBBER}, new int[] {Settings.WAVE_SPEED, Settings.SEEK_STYLE, Settings.WAVE_REACTS, Settings.WAVE_FOLLOWS, Settings.WAVE_STRENGTH});
        LinearLayout buttonPage = subPage("Button animations");
        LinearLayout buttonRows = addGroup();
        buttonRows.addView(featureRows[52]);
        buttonRows.addView(featureRows[53]);
        finish(buttonRows);
        resetFor(new int[] {1, 52, 53}, new int[0]);
        LinearLayout immersivePage = subPage("Immersive player");
        LinearLayout immersiveRows = addGroup();
        addChoice(immersiveRows, Settings.IMM_CHANGE, "New song", "How the artwork changes", new String[] {"Pop", "Spin", "Fade", "Flip", "None"});
        immersiveRows.addView(featureRows[56]);
        immersiveRows.addView(featureRows[57]);
        immersiveRows.addView(featureRows[Settings.IMM_LYRICS]);
        immersiveRows.addView(featureRows[Settings.IMM_LANDSCAPE]);
        finish(immersiveRows);
        addSectionHeader("Immersive backdrop");
        LinearLayout immersiveBackdrop = addGroup();
        addStrength(immersiveBackdrop, Settings.IMM_BLUR, "Blur", "Softness of the album colours", "%");
        addStrength(immersiveBackdrop, Settings.IMM_COLOUR, "Colour intensity", "Strength of the album colours", "%");
        addStrength(immersiveBackdrop, Settings.IMM_DARKNESS, "Darkness", "Dim the backdrop behind the player", "%");
        addStrength(immersiveBackdrop, Settings.IMM_FLOW, "Flow speed", "Movement of the colour fields and particles", "%");
        finish(immersiveBackdrop);
        resetFor(new int[] {4, 56, 57, Settings.IMM_LYRICS, Settings.IMM_LANDSCAPE}, new int[] {Settings.IMM_CHANGE});
        LinearLayout burstPage = subPage("Play button burst");
        LinearLayout burstLook = addGroup();
        tryBurst = createSettingRow("star", "Try it", "Throws a burst from this row", icon("chevron_right"));
        tryBurst.setOnClickListener(this);
        burstLook.addView(tryBurst);
        addChoice(burstLook, Settings.BURST_SHAPE, "Shapes", null, new String[] {"Cookies", "Hearts", "Stars", "Dots", "All"});
        addChoice(burstLook, Settings.BURST_AMOUNT, "Amount", null, new String[] {"Normal", "Few", "Lots"});
        addChoice(burstLook, Settings.BURST_SIZE, "Size", null, new String[] {"Normal", "Small", "Big"});
        addChoice(burstLook, Settings.BURST_TONES, "Colours", "Mix uses shades of the colour below", new String[] {"Mix", "One", "Rainbow"});
        addColors(burstLook, Settings.BURST_COLOR, "Colour");
        addChoice(burstLook, Settings.BURST_WHEN, "When", null, new String[] {"Play and pause", "Play only"});
        finish(burstLook);
        addSectionHeader("Where");
        LinearLayout burstWhere = addGroup();
        for (int i : BURST_PLACES) burstWhere.addView(featureRows[i]);
        finish(burstWhere);
        resetFor(concat(new int[] {Settings.BURST}, BURST_PLACES), new int[] {Settings.BURST_SHAPE, Settings.BURST_AMOUNT, Settings.BURST_SIZE, Settings.BURST_TONES, Settings.BURST_COLOR, Settings.BURST_WHEN});
        LinearLayout miniPage = subPage("Mini player");
        LinearLayout miniRows = addGroup();
        miniRows.addView(featureRows[60]);
        miniRows.addView(featureRows[61]);
        miniRows.addView(featureRows[Settings.MINI_ALBUM]);
        miniRows.addView(featureRows[Settings.MINI_FROSTED]);
        miniRows.addView(featureRows[Settings.MINI_HOLD_LIKE]);
        miniRows.addView(featureRows[Settings.MINI_SPIN]);
        miniRows.addView(featureRows[Settings.MINI_FLING]);
        finish(miniRows);
        resetFor(new int[] {60, 61, Settings.MINI_ALBUM, Settings.MINI_HOLD_LIKE, Settings.MINI_SPIN, Settings.MINI_FLING, Settings.MINI_FROSTED}, new int[0]);
        LinearLayout gesturesPage=subPage("Gestures & touch");
        LinearLayout gestures=addGroup();
        gestures.addView(featureRows[Settings.IMM_SWIPE]);
        gestures.addView(featureRows[Settings.ART_TILT]);
        addStrength(gestures,Settings.TILT_STRENGTH,"Tilt strength","Maximum artwork lean","°");
        gestures.addView(featureRows[Settings.ART_SCRATCH]);
        gestures.addView(featureRows[Settings.HOLD_SEEK]);
        finish(gestures);
        resetFor(new int[]{Settings.IMM_SWIPE,Settings.ART_TILT,Settings.ART_SCRATCH,Settings.HOLD_SEEK},new int[0]);
        inside(playerPage);
        addSectionHeader("Artwork");
        LinearLayout artRows = addGroup();
        artRows.addView(master(ROUND_ART, artworkPage));
        addChoice(artRows, Settings.ART_SHAPE, "Artwork shape", "With Circular album art on", new String[] {"Circle", "Scallop", "Squircle", "Soft"});
        artRows.addView(master(2, backdropPage));
        addChoice(artRows, Settings.BACKDROP_FX, "Backdrop motes", "Specks drifting behind the player", new String[] {"None", "Dust", "Stars", "Embers"});
        addChoice(artRows, Settings.ART_SKIP, "Skip animation", "Spin rolls the artwork over like a record", new String[] {"Slide", "Spin", "Shrink", "Fade", "Flip"});
        artRows.addView(featureRows[Settings.BEAT_BOUNCE]);
        finish(artRows);
        addSectionHeader("Controls");
        LinearLayout player = addGroup();
        player.addView(master(0, wavePage));
        player.addView(master(1, buttonPage));
        player.addView(master(Settings.BURST, burstPage));
        player.addView(featureRows[5]);
        player.addView(featureRows[Settings.SEEK_BUBBLE]);
        addColors(player, Settings.PLAYER_COLOR, "Player colour");
        finish(player);
        addSectionHeader("Gestures");
        LinearLayout gestureRows = addGroup();
        link(gestureRows, "arrow_left", "Gestures & touch", "Swipe to skip, tilt, scratch and hold to seek", gesturesPage);
        finish(gestureRows);
        addSectionHeader("Lyrics");
        LinearLayout lyrics = addGroup();
        lyrics.addView(featureRows[Settings.HIDE_PLAYER_LYRICS]);
        lyrics.addView(featureRows[Settings.LYRICS_FOCUS]);
        finish(lyrics);
        addSectionHeader("Other surfaces");
        LinearLayout others = addGroup();
        others.addView(master(4, immersivePage));
        link(others, "device_mobile", "Mini player", "New song pop and artwork flight", miniPage);
        finish(others);
        resetFor(new int[] {5, Settings.BEAT_BOUNCE, Settings.SEEK_BUBBLE, Settings.HIDE_PLAYER_LYRICS, Settings.LYRICS_FOCUS}, new int[] {Settings.ART_SKIP, Settings.ART_SHAPE, Settings.BACKDROP_FX, Settings.PLAYER_COLOR});
        finishCategory(playerPage);

        View visualizers = category(categories, "soundwave", "Visualizers", "Spectrum styles and where they show", "Visualizers");
        LinearLayout barsPage = subPage("Full-screen visualizer");
        LinearLayout visualizer = addGroup();
        preview = new SpectrumPreview(context);
        visualizer.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));
        addChoice(visualizer, Settings.VIZ_SHAPE, "Shape", "Scope draws a looping trace with the music", new String[] {"Bars", "Centre", "Contour", "Dots", "Scope"});
        addChoice(visualizer, Settings.VIZ_TAPER, "Edge taper", "Eases the bars off toward the sides", new String[] {"Subtle", "None"});
        for (int i : VISUALIZER) visualizer.addView(featureRows[i]);
        finish(visualizer);
        resetFor(concat(new int[] {FULL_VISUALIZER}, VISUALIZER), new int[] {Settings.VIZ_SHAPE, Settings.VIZ_TAPER});
        inside(visualizers);
        LinearLayout places = addGroup();
        places.addView(master(FULL_VISUALIZER, barsPage));
        places.addView(featureRows[MINI_VISUALIZER]);
        addChoice(places, Settings.ART_SPECTRUM, "Around the artwork", "Real audio follows the artwork shape; uses the audio permission", new String[] {"Off", "Fine", "Bars", "Dots", "Ribbon"});
        finish(places);
        refreshChoices();
        resetFor(new int[] {MINI_VISUALIZER}, new int[] {Settings.ART_SPECTRUM});
        finishCategory(visualizers);

        View screens = category(categories, "home", "Screens", "Bars, panels, lists, profile and widget", "Screens");
        LinearLayout navPage = subPage("Navigation bar");
        LinearLayout navRows = addGroup();
        for (int i : NAV_PARTS) navRows.addView(featureRows[i]);
        addColors(navRows, Settings.NAV_COLOR, "Pill colour");
        finish(navRows);
        resetFor(concat(new int[] {NAV_STYLE}, NAV_PARTS), new int[] {Settings.NAV_COLOR});
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
        libraryRows.addView(featureRows[Settings.LIBRARY_CASCADE]);
        libraryRows.addView(featureRows[Settings.LIBRARY_HOLD]);
        libraryRows.addView(featureRows[Settings.LIBRARY_GLOW]);
        finish(libraryRows);
        resetFor(new int[] {39, 40, 41, Settings.LIBRARY_CASCADE, Settings.LIBRARY_HOLD, Settings.LIBRARY_GLOW}, new int[0]);
        LinearLayout playPage = subPage("Living play button");
        LinearLayout playRows = addGroup();
        playRows.addView(featureRows[54]);
        playRows.addView(featureRows[55]);
        finish(playRows);
        resetFor(new int[] {36, 54, 55}, new int[0]);
        LinearLayout playlistPage = subPage("Playlists");
        LinearLayout playlistRows = addGroup();
        playlistRows.addView(featureRows[42]);
        playlistRows.addView(featureRows[Settings.PLAYLIST_CASCADE]);
        playlistRows.addView(featureRows[Settings.PLAYLIST_HOLD]);
        playlistRows.addView(featureRows[Settings.PLAYLIST_GLOW]);
        playlistRows.addView(master(36, playPage));
        finish(playlistRows);
        resetFor(new int[] {42, Settings.PLAYLIST_CASCADE, Settings.PLAYLIST_HOLD, Settings.PLAYLIST_GLOW}, new int[0]);
        inside(screens);
        addSectionHeader("Bars & panels");
        LinearLayout barRows = addGroup();
        barRows.addView(master(NAV_STYLE, navPage));
        barRows.addView(master(FILTERS, filterPage));
        barRows.addView(master(SIDE_STYLE, sidePage));
        finish(barRows);
        addSectionHeader("Library & playlists");
        LinearLayout libraryRowsGroup = addGroup();
        libraryRowsGroup.addView(master(39, libraryPage));
        link(libraryRowsGroup, "playlist", "Playlists", "Track cards and the play button", playlistPage);
        finish(libraryRowsGroup);
        addSectionHeader("Cards & lists");
        LinearLayout cardRows = addGroup();
        cardRows.addView(featureRows[Settings.LIST_CARDS]);
        cardRows.addView(featureRows[Settings.CARD_GLINT]);
        cardRows.addView(featureRows[Settings.SOFT_HEADERS]);
        cardRows.addView(featureRows[Settings.LIST_RISE]);
        cardRows.addView(featureRows[Settings.ROW_EQUALIZER]);
        cardRows.addView(featureRows[Settings.TITLE_ALBUM]);
        cardRows.addView(featureRows[Settings.QUEUE_SHIMMER]);
        finish(cardRows);
        addSectionHeader("Profile");
        LinearLayout profileRows = addGroup();
        profileRows.addView(master(37, profilePage));
        finish(profileRows);
        addSectionHeader("Home screen widget");
        LinearLayout widgetRows = addGroup();
        widgetRows.addView(featureRows[Settings.WIDGET_WALLPAPER]);
        finish(widgetRows);
        resetFor(new int[] {Settings.LIST_CARDS, Settings.CARD_GLINT, Settings.SOFT_HEADERS, Settings.LIST_RISE, Settings.ROW_EQUALIZER, Settings.TITLE_ALBUM, Settings.QUEUE_SHIMMER, Settings.WIDGET_WALLPAPER}, new int[0]);
        finishCategory(screens);

        View navigation = category(categories, "notifications_active", "Motion & haptics", "Transitions, springs and feedback", "Motion & haptics");
        addSectionHeader("Motion");
        LinearLayout motion = addGroup();
        motion.addView(featureRows[6]);
        motion.addView(featureRows[27]);
        motion.addView(featureRows[35]);
        motion.addView(featureRows[50]);
        motion.addView(featureRows[Settings.HIDE_BARS]);
        addStrength(motion, Settings.HIDE_DISTANCE, "Hide distance", "Scroll this far before the bottom bars slide away", " dp");
        motion.addView(featureRows[Settings.BOUNCY_PRESS]);
        addChoice(motion, Settings.BOUNCE, "Bounce", "How far tiles spring back", new String[] {"Normal", "Soft", "Wild"});
        motion.addView(featureRows[Settings.HERO_TOUCH]);
        motion.addView(featureRows[Settings.TAP_RIPPLE]);
        addStrength(motion, Settings.RIPPLE_SIZE, "Ripple size", "Radius at the end of a tap ripple", " dp");
        addColors(motion, Settings.RIPPLE_COLOR, "Ripple colour");
        refreshChoices();
        finish(motion);
        addSectionHeader("Haptics");
        LinearLayout haptics = addGroup();
        for (int i : HAPTICS) haptics.addView(featureRows[i]);
        tryHaptics = createSettingRow("check", "Try feedback", "One sample tap", icon("chevron_right"));
        tryHaptics.setOnClickListener(this);
        haptics.addView(tryHaptics);
        finish(haptics);
        resetFor(concat(concat(MOTION, HAPTICS), new int[] {Settings.TAP_RIPPLE, Settings.HERO_TOUCH, Settings.BOUNCY_PRESS, Settings.HIDE_BARS}), new int[] {Settings.BOUNCE, Settings.RIPPLE_COLOR});
        finishCategory(navigation);

        View contentPage = category(categories, "hidden", "Content", "Hidden sections, media, navigation and profile", "Content");
        LinearLayout sectionsPage = subPage("Home sections");
        homeList = addGroup();
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
        addSectionHeader("Home & media");
        LinearLayout mediaRows = addGroup();
        homeToggle = createSettingRow("home", "Home sections", null, icon("chevron_right"));
        homeToggle.setTag(sectionsPage);
        homeCount = (TextView) ((ViewGroup) ((ViewGroup) homeToggle).getChildAt(1)).getChildAt(1);
        homeToggle.setOnClickListener(this);
        mediaRows.addView(homeToggle);
        for (int i : CONTENT_MEDIA) mediaRows.addView(featureRows[i]);
        finish(mediaRows);
        refreshHomeCount();
        addSectionHeader("Navigation & drawer");
        LinearLayout contentNav = addGroup();
        for (int i : CONTENT_NAV) contentNav.addView(featureRows[i]);
        finish(contentNav);
        addSectionHeader("Account & profile");
        LinearLayout contentProfiles = addGroup();
        for (int i : CONTENT_PROFILE) contentProfiles.addView(featureRows[i]);
        finish(contentProfiles);
        resetFor(concat(CONTENT, SIDE_MENU), new int[0]);
        finishCategory(contentPage);
        Settings.dev("install", new Class<?>[] {SpatchSettings.class, LinearLayout.class}, this, categories);
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
        about.setText("Keeps your theme and font. Font changes apply after a restart; everything else straight away.");
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
        unclip(page);
        scroll.setClipChildren(false);
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

    /** The real artwork drawing over a real backdrop and seek bar, so every change shows as it's made. */
    private void addPlayerPreview(LinearLayout page) {
        Context context = getContext();
        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(rounded(card, dp(24)));
        frame.setClipToOutline(true);
        frame.setContentDescription("Preview of the player");
        Drawable art = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {accent, high});
        Atmosphere backdrop = new Atmosphere();
        backdrop.capture(art);
        View behind = new View(context, null);
        behind.setBackground(backdrop);
        frame.addView(behind, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(dp(196), dp(196), Gravity.CENTER_HORIZONTAL);
        artLp.topMargin = dp(8);
        frame.addView(PlayerArt.preview(context, art), artLp);
        Wave wave = new Wave();
        wave.setLevel(4000);
        View seek = new View(context, null);
        seek.setBackground(wave);
        FrameLayout.LayoutParams seekLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12), Gravity.BOTTOM);
        seekLp.leftMargin = seekLp.rightMargin = dp(28);
        seekLp.bottomMargin = dp(16);
        frame.addView(seek, seekLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(244));
        lp.bottomMargin = dp(8);
        page.addView(frame, lp);
    }

    /** Springy rows overshoot their bounds; a parent must not cut that off. */
    private static void unclip(ViewGroup group) {
        group.setClipChildren(false);
        group.setClipToPadding(false);
    }

    private LinearLayout subPage(String title) {
        return addPage(title, null);
    }

    /** Adds a category row to the root list and starts its page. */
    View category(LinearLayout list, String iconName, String title, String subtitle, String pageTitle) {
        LinearLayout page = addPage(pageTitle, null);
        link(list, iconName, title, subtitle, page);
        return page;
    }

    /** A row that opens a page. */
    private void link(LinearLayout list, String iconName, String title, String subtitle, LinearLayout page) {
        View row = createSettingRow(iconName, title, subtitle, icon("chevron_right"));
        row.setTag(page);
        row.setOnClickListener(this);
        describe(row, title, subtitle);
        list.addView(row);
    }

    /** Finds a setting by name and opens the page it lives on; presets sit underneath, ready when the box is empty. */
    private void addSearch(LinearLayout home) {
        android.widget.EditText box = new android.widget.EditText(getContext());
        box.setHint("Search settings");
        box.setContentDescription("Search settings");
        box.setHintTextColor(subdued);
        box.setTextColor(text);
        box.setTextSize(15f);
        box.setTypeface(Fonts.settings(400));
        box.setSingleLine(true);
        box.setPadding(dp(18), dp(12), dp(18), dp(12));
        box.setBackground(rounded(card, dp(24)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(14);
        home.addView(box, lp);
        final LinearLayout found = new LinearLayout(getContext());
        found.setOrientation(LinearLayout.VERTICAL);
        home.addView(found);
        box.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            public void afterTextChanged(android.text.Editable s) {
                found.removeAllViews();
                String wanted = s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                if (wanted.isEmpty()) return;
                search(found,wanted);
                Fonts.restyle(found); // rows made after the page was styled
            }
        });
        LinearLayout presets = new LinearLayout(getContext());
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, dp(10), 0, 0);
        String[] names = {"Calm", "Everything", "Copy", "Paste"};
        for (int i = 0; i < names.length; i++) {
            final int action = i;
            TextView pill = new TextView(getContext());
            pill.setText(names[i]);
            pill.setTextSize(14f);
            pill.setTextColor(text);
            pill.setTypeface(Fonts.settings(500));
            pill.setGravity(Gravity.CENTER);
            pill.setBackground(ripple(rounded(card, dp(24))));
            pill.setClickable(true);
            pill.setContentDescription(i < 2 ? names[i] + " preset" : names[i] + " settings");
            pill.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    android.content.ClipboardManager clips = (android.content.ClipboardManager) getContext().getSystemService("clipboard");
                    if (action == 2) {
                        clips.setPrimaryClip(android.content.ClipData.newPlainText("Spatch settings", Settings.export()));
                    } else if (action == 3) {
                        android.content.ClipData clip = clips.getPrimaryClip();
                        if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) Settings.importText(clip.getItemAt(0).getText().toString());
                        refreshChoices();
                    } else {
                        for (int setting : PRESET) Settings.set(setting, action == 1);
                    }
                    syncFeatures();
                    pop(v);
                }
            });
            LinearLayout.LayoutParams each = new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (i > 0) each.leftMargin = dp(8);
            presets.addView(pill, each);
        }
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        under.bottomMargin = dp(14);
        home.addView(presets, under);
        LinearLayout savedPage = subPage("Your presets");
        renderPresets(savedPage);
        link(home, "playlist", "Your presets", "Save and restore your own settings", savedPage);
        inside(home);
    }

    private void renderPresets(final LinearLayout page) {
        page.removeAllViews();
        View save = createSettingRow("plus", "Save current settings", "Toggles, choices and strengths; themes and fonts stay as they are", icon("chevron_right"));
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final android.widget.EditText name = new android.widget.EditText(getContext());
                name.setHint("Preset name");
                name.setContentDescription("Preset name");
                name.setSingleLine(true);
                name.setTextColor(text);
                name.setHintTextColor(subdued);
                name.setTypeface(Fonts.settings(400));
                name.setTextSize(16f);
                name.setMinimumHeight(dp(48));
                name.setPadding(dp(16), dp(12), dp(16), dp(12));
                name.setBackground(rounded(high, dp(12)));
                FrameLayout input = new FrameLayout(getContext());
                input.setPadding(dp(24), dp(8), dp(24), dp(8));
                input.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                final AlertDialog dialog = new AlertDialog.Builder(getContext(), Palette.dialogTheme())
                    .setTitle("Save preset").setMessage("Use a new name, or an existing name to replace its saved settings.")
                    .setView(input).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
                showPresetDialog(dialog);
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View button) {
                        if (!Settings.savePreset(name.getText().toString())) {
                            name.setError("Use 1–40 characters without line breaks");
                            return;
                        }
                        dialog.dismiss();
                        renderPresets(page);
                    }
                });
            }
        });
        page.addView(save);
        for (final String name : Settings.presets()) {
            View row = createSettingRow("playlist", name, "Apply, update or delete", icon("chevron_right"));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    AlertDialog dialog = new AlertDialog.Builder(getContext(), Palette.dialogTheme()).setTitle(name)
                        .setMessage("Apply the saved settings, update this preset with your current settings, or delete it.")
                        .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                Settings.applyPreset(name);
                                syncFeatures();
                                MiniBar.settingsChanged();
                            }
                        }).setNeutralButton("Update", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) { Settings.savePreset(name); }
                        }).setNegativeButton("Delete", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                Settings.deletePreset(name);
                                renderPresets(page);
                            }
                        }).create();
                    showPresetDialog(dialog);
                }
            });
            page.addView(row);
        }
        Fonts.restyle(page);
    }

    private void showPresetDialog(AlertDialog dialog) {
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(rounded(card, dp(28)));
            tintDialogText(window.getDecorView());
            Fonts.restyle(window.getDecorView());
        }
        for (int which : new int[] {DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL}) {
            android.widget.Button button = dialog.getButton(which);
            if (button != null) button.setTextColor(accent);
        }
    }

    private void tintDialogText(View view) {
        if (view instanceof TextView) ((TextView) view).setTextColor(text);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintDialogText(group.getChildAt(i));
        }
    }

    private void index(View row,String title,String subtitle){
        if(!creatingResults)searchable.add(new Object[]{row,title,subtitle});
    }

    private void search(LinearLayout results,String wanted){
        creatingResults=true;
        try{
            int shown=0;
            java.util.HashSet<String> seen=new java.util.HashSet<String>();
            for(Object[] item:searchable){
                String title=(String)item[1],subtitle=(String)item[2];
                if(!(title+" "+(subtitle==null?"":subtitle)).toLowerCase(java.util.Locale.ROOT).contains(wanted))continue;
                View row=(View)item[0];
                LinearLayout page=row.getTag() instanceof LinearLayout?(LinearLayout)row.getTag():pageOf(row);
                if(page==null || !seen.add(page.getTag()+":"+title))continue;
                link(results,"enhance",title,page.getTag()+(subtitle==null?"":" · "+subtitle),page);
                if(++shown==12)break;
            }
            if(shown==0){TextView empty=new TextView(getContext());empty.setText("No matching settings");empty.setTextColor(subdued);results.addView(empty);}
        }finally{creatingResults=false;}
    }

    static boolean matches(int setting, String wanted) {
        return FEATURE_TITLES[setting].toLowerCase(java.util.Locale.ROOT).contains(wanted) || FEATURE_SUBTITLES[setting].toLowerCase(java.util.Locale.ROOT).contains(wanted);
    }

    /** The page a row is on, or null for rows that were never placed. */
    private LinearLayout pageOf(View row) {
        for (View at = row; at != null && at.getParent() instanceof View; at = (View) at.getParent()) {
            if (at.getParent() instanceof ScrollView) return at instanceof LinearLayout && at.getTag() != null ? (LinearLayout) at : null;
        }
        return null;
    }

    private void inside(View page) {
        content = (LinearLayout) page;
    }

    LinearLayout currentPage() {
        return content;
    }

    void finishCategory(View page) {
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
                for (int i : features) {
                    for (int strength = 0; strength < Settings.STRENGTH_FEATURES.length; strength++) {
                        if (Settings.STRENGTH_FEATURES[strength] == i) Settings.setStrength(strength, Settings.STRENGTH_DEFAULTS[strength]);
                    }
                }
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

    private void hideKeyboard() {
        Object keyboard = getContext().getSystemService("input_method");
        if (keyboard instanceof android.view.inputmethod.InputMethodManager) ((android.view.inputmethod.InputMethodManager) keyboard).hideSoftInputFromWindow(getWindowToken(), 0);
    }

    private void open(LinearLayout page) {
        hideKeyboard();
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
        if (direction > 0) rise(((ViewGroup) to).getChildAt(0));
        from.animate().alpha(0f).translationX(-direction * density * 24).setDuration(SWAP).start();
        postDelayed(new Runnable() {
            public void run() {
                from.setVisibility(View.GONE);
                from.setAlpha(1f);
                from.setTranslationX(0f);
            }
        }, SWAP);
    }

    /** A page's groups arrive in turn, each from a little lower. */
    private void rise(View page) {
        if (!(page instanceof ViewGroup)) return;
        ViewGroup groups = (ViewGroup) page;
        for (int i = 0; i < Math.min(groups.getChildCount(), RISE_ROWS); i++) {
            View group = groups.getChildAt(i);
            group.setTranslationY(density * 18);
            group.animate().translationY(0f).setStartDelay(i * 28L).setDuration(300).setInterpolator(RISE).start();
        }
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
        String pkg = getContext().getPackageName();
        int id = getContext().getResources().getIdentifier("spatch_ms_" + name, "drawable", pkg); // Material Symbols, bundled
        if (id == 0) id = getContext().getResources().getIdentifier("encore_icon_" + name + "_24", "drawable", pkg);
        if (id != 0) {
            image.setImageResource(id);
            image.setColorFilter(accent);
        }
        return image;
    }

    LinearLayout addGroup() {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(LinearLayout.VERTICAL);
        unclip(group);
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

    /** Native range and keyboard handling, with the same labels and spacing as choice rows. */
    private void addStrength(LinearLayout parent, final int setting, final String title, String subtitle, final String unit) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        final TextView label = new TextView(getContext());
        label.setTextSize(16f);
        label.setTextColor(text);
        row.addView(label);
        TextView note = new TextView(getContext());
        note.setText(subtitle);
        note.setTextSize(13f);
        note.setTextColor(subdued);
        row.addView(note);
        final android.widget.SeekBar slider = new android.widget.SeekBar(getContext(), null);
        slider.setMax(Settings.STRENGTH_MAX[setting] - Settings.STRENGTH_MIN[setting]);
        slider.setProgressTintList(ColorStateList.valueOf(accent));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                int amount = progress + Settings.STRENGTH_MIN[setting];
                if (fromUser) Settings.setStrength(setting, amount);
                label.setText(title + ": " + amount + unit);
                slider.setContentDescription(title + ", " + amount + unit);
            }
            public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            public void onStopTrackingTouch(android.widget.SeekBar bar) { Haptics.detent(bar); }
        });
        int amount = Settings.strength(setting);
        label.setText(title + ": " + amount + unit);
        slider.setContentDescription(title + ", " + amount + unit);
        slider.setProgress(amount - Settings.STRENGTH_MIN[setting]);
        row.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        strengthSliders[setting] = slider;
        index(row,title,subtitle);
        parent.addView(row);
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
            pill.setMinimumHeight(dp(48));
            pill.setPadding(dp(6), dp(12), dp(6), dp(12));
            pill.setTag(new String[] {String.valueOf(setting), Settings.CHOICES[setting][i], title + ": " + labels[i]});
            pill.setOnClickListener(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.leftMargin = dp(3);
            pills.addView(pill, lp);
            pillViews.add(pill);
        }
        row.addView(pills);
        choiceRows[setting] = row;
        index(row,title,subtitle);
        parent.addView(row);
    }

    /** Tint.NAMES as swatches: only Album keeps a word, the rest show their colour. */
    private void addColors(LinearLayout parent, int setting, String title) {
        addChoice(parent, setting, title, null, new String[] {"Theme", "Art", "Rose", "Amber", "Mint", "Sky", "White"});
        for (int i = 0; i < Tint.NAMES.length; i++) {
            if (i == 1) continue;
            TextView pill = pillViews.get(pillViews.size() - Tint.NAMES.length + i);
            String[] tag = (String[]) pill.getTag();
            pill.setTag(new String[] {tag[0], tag[1], tag[2], String.valueOf(i == 0 ? accent : Tint.SWATCHES[i])});
        }
    }

    /** Selected buttons, and rows and switches that do not apply to the current choices. */
    private void refreshChoices() {
        for (int i = 0; i < strengthSliders.length; i++) {
            if (strengthSliders[i] != null) strengthSliders[i].setProgress(Settings.strength(i) - Settings.STRENGTH_MIN[i]);
        }
        for (TextView pill : pillViews) {
            String[] tag = (String[]) pill.getTag();
            boolean selected = tag[1].equals(Settings.choice(Integer.parseInt(tag[0])));
            pill.setBackground(ripple(rounded(tag.length > 3 ? Integer.parseInt(tag[3]) : selected ? accent : high, dp(selected ? 24 : 10))));
            pill.setTextColor(selected ? surface : text);
            if (tag.length > 3) pill.setText(selected ? "\u2713" : "");
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
        boolean motion = Wave.animationsEnabled();
        postDelayed(cover, motion ? 240 : 0);
        if (!motion) return;
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

    private static String themeTitle(int index) {
        return index==Palette.CUSTOM ? Palette.customName() : THEME_TITLES[index];
    }

    private void refreshThemes(String selected, boolean animate) {
        ((ThemeSwatch)themeRows[Palette.CUSTOM]).setName(Palette.customName());
        for (int i = 0; i < themeRows.length; i++) {
            boolean on = Palette.NAMES[i].equals(selected);
            themeRows[i].setEnabled(i!=Palette.CUSTOM || Palette.hasCustom());
            themeRows[i].setAlpha(themeRows[i].isEnabled()?1f:.4f);
            boolean was = themeRows[i].isSelected();
            themeRows[i].setSelected(on);
            if (on && !was && animate) pop(themeRows[i]);
            describe(themeRows[i], themeTitle(i), on ? "Selected" : "Not selected");
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
        new AlertDialog.Builder(getContext(), Palette.dialogTheme())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Restart now", this)
            .setNegativeButton("Later", this)
            .show();
    }

    private void onThemeSelected(int index, final String themeName) {
        String current = Palette.requested();
        if (themeName.equals(current) || reveal != null || index==Palette.CUSTOM && !Palette.hasCustom()) return;

        if (!Palette.choose(themeName)) return; // not saved: leave the selection as it was
        refreshThemes(themeName, true);
        reveal = ThemeReveal.open(this, themeRows[index], index, themeTitle(index));
        if (reveal == null) { applyTheme(themeName); return; }
        postDelayed(new Runnable() {
            public void run() {
                if (isAttachedToWindow()) applyTheme(themeName);
            }
        }, ThemeReveal.MILLIS);
    }

    private void applyTheme(String themeName) {
        boolean live = false;
        try {
            live = Palette.apply(getContext(), themeName);
        } catch (RuntimeException | LinkageError e) { // the saved choice still applies on a restart
            android.util.Log.i("Spatch", "live theme failed: " + e);
        }
        if (!live) askThemeRestart();
    }

    void showThemes() {
        open(palettePage);
    }

    private void askThemeRestart() {
        new AlertDialog.Builder(getContext(), Palette.dialogTheme()) // android.R.style.Theme_DeviceDefault_Dialog_Alert: system Material dialog, not Holo
            .setTitle("Restart to apply theme?")
            .setMessage("Spotify needs to restart to apply the new color palette.")
            .setPositiveButton("Restart now", this)
            .setNegativeButton("Later", this)
            .setOnDismissListener(this)
            .show();
    }

    public void onDismiss(DialogInterface dialog) {
        if (reveal != null) reveal.close();
        reveal = null;
    }

    void addSectionHeader(String title) {
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
        Springy.attach(row);

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
        index(row,titleText,subtitleText);
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
            if ((i == Settings.WAVE_AUDIO || i == Settings.BASS_SHOCKWAVE) && Settings.get(i) && getContext().checkSelfPermission(AudioSpectrum.PERMISSION) != 0) AudioSpectrum.requestPermission(getContext());
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
            if ((setting == Settings.ART_SPECTRUM && !"off".equals(tag[1]) || setting == Settings.ART_OUTLINE && "audio".equals(tag[1]))
                && getContext().checkSelfPermission(AudioSpectrum.PERMISSION) != 0) {
                AudioSpectrum.requestPermission(getContext());
            }
            NavIndicator.refresh();
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
        if (v == tryBurst) {
            int[] at = new int[2];
            v.getLocationOnScreen(at);
            Confetti.at(v, at[0] + v.getWidth() / 2f, at[1] + v.getHeight() / 2f);
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
        NavIndicator.refresh();
        for (int i : NEEDS_RESTART) if (i == setting) askRestart();
    }

    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            reveal = null; // stays over the screen through the restart
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
        hideKeyboard();
        if (popPage()) {
            if (back != null) back.unregister();
            if (android.os.Build.VERSION.SDK_INT >= 33) back = Back.register(this, this); // a Back gesture spends its callback
            return;
        }
        if (back != null) {
            back.unregister();
            back = null;
        }
        uncover();
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

    /** Spotify's own views under the page, hidden while it covers them so their animations stop drawing. */
    private final java.util.ArrayList<View> covered = new java.util.ArrayList<View>();

    private final Runnable cover = new Runnable() {
        public void run() {
            cover();
        }
    };

    private void cover() {
        if (closing || !(getParent() instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) getParent();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && !child.getClass().getName().startsWith("spatch.")) {
                child.setVisibility(View.INVISIBLE);
                covered.add(child);
            }
        }
    }

    private void uncover() {
        for (View child : covered) {
            child.setVisibility(View.VISIBLE);
            wake(child); // loops that stopped while hidden restart from their next draw
        }
        covered.clear();
        MiniBar.settingsChanged();
    }

    private static void wake(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) wake(((ViewGroup) view).getChildAt(i));
    }

    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        uncover();
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
