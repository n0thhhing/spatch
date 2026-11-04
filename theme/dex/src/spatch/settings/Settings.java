package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import p.S_KotlinFlow;
import p.S_DrawerRowContent;
import p.S_DrawerRowLogSpec;
import p.S_DrawerRowWrapper;
import p.S_DrawerRowProvider;
import p.S_DrawerRowSource;
import p.S_MessagesRowProvider;
import p.S_TasteProfileRowProvider;
import p.S_MessagesRowSource;
import p.S_ListeningStatsRowProvider;
import p.S_KotlinFlowOf;
import p.S_YourPlanRowProvider;
import p.S_DrawerRowIcon;

/**
 * Spatch's drawer row and preferences. hf81 (the profile side drawer) passes its row providers through
 * rows(), which adds this provider directly above Spotify's "Settings and privacy" (S_DrawerRowProvider kind 1); the
 * drawer's click handler (g7y0) asks open() first and Spatch shows SpatchSettings for its own URI.
 */
public final class Settings implements S_DrawerRowSource {
    static final String URI = "spotify:spatch:settings";
    static final String[] KEYS = {"wave", "buttons", "backdrop", "round_art", "immersive", "shapes", "reduce_motion", "visualizer", "viz_mirrored", "viz_smooth", "viz_thin", "viz_floating", "viz_side_margin", "hide_podcasts", "hide_audiobooks", "hide_messages", "mini_visualizer", "haptics", "haptic_playback", "haptic_navigation", "haptic_sliders", "hide_recents", "hide_listening_stats", "hide_your_plan", "hide_your_updates", "hide_taste_profile", "hide_company_info", "page_motion", "filter_slide", "side_style", "side_cards", "side_wash", "filter_custom", "filter_glow", "filter_drag", "soft_press", "playlist_motion", "profile_banner", "profile_theme_header", "library_style", "library_cards", "library_chips", "playlist_cards", "nav_style", "nav_drag", "nav_stretch", "nav_lift", "nav_press", "nav_pop", "art_press", "spring_press", "wave_thick", "buttons_bounce", "skip_nudge", "play_spin", "play_morph", "imm_titles", "imm_flight", "side_round", "side_scrim", "mini_pop", "mini_flight", "art_drift", "filter_anim", "library_cascade", "library_hold", "library_glow", "playlist_cascade", "playlist_hold", "playlist_glow", "burst", "burst_player", "burst_mini", "burst_immersive", "burst_lists", "art_tilt", "art_scratch", "hold_seek", "seek_bubble", "mini_album", "tap_ripple", "hero_touch", "bouncy_press", "hide_bars", "beat_bounce", "mini_hold_like", "mini_spin", "imm_swipe", "list_cards", "soft_headers", "list_rise", "wave_audio", "title_album", "lyrics_focus", "queue_shimmer", "widget_wallpaper", "hide_premium_tab", "hide_account_switcher", "hide_parental_controls", "seek_splash", "bass_shockwave", "mini_fling", "card_glint", "hide_player_lyrics"};
    static final boolean[] DEFAULTS = {true, true, true, true, true, true, false, true, true, false, false, false, true, false, false, false, true, true, true, true, true, false, false, false, false, false, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, false, false, false, false, true, true, true, true, false};
    private static final int CUSTOMIZE = R_string_mixing_edit_page_customize_button; // R.string.mixing_edit_page_customize_button (rewritten to "Spatch" at render time)
    /** Multi-choice settings: stable stored values, first is the default (today's look). */
    static final int MINI_VISUALIZER = 16, HAPTICS = 17, LIBRARY_CASCADE = 64, LIBRARY_HOLD = 65, LIBRARY_GLOW = 66, PLAYLIST_CASCADE = 67, PLAYLIST_HOLD = 68, PLAYLIST_GLOW = 69, BURST = 70, BURST_PLAYER = 71, BURST_MINI = 72, BURST_IMMERSIVE = 73, BURST_LISTS = 74, ART_TILT = 75, ART_SCRATCH = 76, HOLD_SEEK = 77, SEEK_BUBBLE = 78, MINI_ALBUM = 79, TAP_RIPPLE = 80, HERO_TOUCH = 81, BOUNCY_PRESS = 82, HIDE_BARS = 83, BEAT_BOUNCE = 84, MINI_HOLD_LIKE = 85, MINI_SPIN = 86, IMM_SWIPE = 87, LIST_CARDS = 88, SOFT_HEADERS = 89, LIST_RISE = 90, WAVE_AUDIO = 91, TITLE_ALBUM = 92, LYRICS_FOCUS = 93, QUEUE_SHIMMER = 94, WIDGET_WALLPAPER = 95, HIDE_PREMIUM_TAB = 96, HIDE_ACCOUNT_SWITCHER = 97, HIDE_PARENTAL_CONTROLS = 98, SEEK_SPLASH = 99, BASS_SHOCKWAVE = 100, MINI_FLING = 101, CARD_GLINT = 102, HIDE_PLAYER_LYRICS = 103;
    static final int VIZ_SHAPE = 0, VIZ_TAPER = 1, ART_OUTLINE = 2, ART_GLOW = 3, ART_SPECTRUM = 4, PROFILE_POSITION = 5, ART_SKIP = 6, IMM_CHANGE = 7, RING_COLOR = 8, GLOW_COLOR = 9, GLOW_STYLE = 10, RING_WIDTH = 11, RING_SPEED = 12, WAVE_SPEED = 13, NAV_COLOR = 14, BURST_SHAPE = 15, BURST_AMOUNT = 16, BURST_SIZE = 17, BURST_TONES = 18, BURST_COLOR = 19, BURST_WHEN = 20, ART_SHAPE = 21, RIPPLE_COLOR = 22, BOUNCE = 23, BACKDROP_FX = 24, SEEK_STYLE = 25, PLAYER_COLOR = 26, WAVE_REACTS = 27, WAVE_FOLLOWS = 28, WAVE_STRENGTH = 29;
    static final String[] CHOICE_KEYS = {"viz_shape", "viz_taper", "art_outline", "art_glow", "art_spectrum", "profile_position", "art_skip", "imm_change", "ring_color", "glow_color", "glow_style", "ring_width", "ring_speed", "wave_speed", "nav_color", "burst_shape", "burst_amount", "burst_size", "burst_tones", "burst_color", "burst_when", "art_shape", "ripple_color", "bounce", "backdrop_fx", "seek_style", "player_color", "wave_reacts", "wave_follows", "wave_strength"};
    static final String[][] CHOICES = {
        {"bars", "centre", "contour", "dots", "scope"},
        {"subtle", "none"},
        {"wavy", "simple", "none", "audio"},
        {"current", "soft", "off"},
        {"off", "fine", "bars", "dots", "ribbon"},
        {"centre", "top", "bottom"},
        {"slide", "spin", "shrink", "fade"},
        {"pop", "spin", "fade", "none"},
        Tint.NAMES,
        Tint.NAMES,
        {"halo", "wide", "fade", "sweep"},
        {"normal", "thin", "bold"},
        {"normal", "slow", "fast", "still"},
        {"normal", "slow", "fast"},
        Tint.NAMES,
        {"cookies", "hearts", "stars", "dots", "mixed"},
        {"normal", "few", "lots"},
        {"normal", "small", "big"},
        {"mix", "solid", "rainbow"},
        Tint.NAMES,
        {"both", "play"},
        {"circle", "scallop", "squircle", "soft"},
        Tint.NAMES,
        {"normal", "soft", "wild"},
        {"none", "dust", "stars"},
        {"wave", "beads", "pulse", "line", "scope"},
        Tint.NAMES,
        {"height", "thickness", "speed", "all"},
        {"mix", "beat", "melody"},
        {"normal", "subtle", "strong"},
    };
    private static final String[] chosen = new String[CHOICES.length];
    static final int TILT_STRENGTH = 0, RIPPLE_SIZE = 1, HIDE_DISTANCE = 2, SHOCK_STRENGTH = 3, SPLASH_SIZE = 4;
    static final String[] STRENGTH_KEYS = {"tilt_strength", "ripple_size", "hide_distance", "shock_strength", "splash_size"};
    static final int[] STRENGTH_DEFAULTS = {11, 54, 40, 100, 100}, STRENGTH_MIN = {0, 16, 16, 0, 50}, STRENGTH_MAX = {20, 120, 160, 200, 200};
    static final int[] STRENGTH_FEATURES = {ART_TILT, TAP_RIPPLE, HIDE_BARS, BASS_SHOCKWAVE, SEEK_SPLASH};
    private static final int[] strengths = STRENGTH_DEFAULTS.clone();
    public static final boolean[] on = DEFAULTS.clone(); // public: classes9 reads it directly, having no method references to spare
    private static SharedPreferences prefs;
    private static WeakReference<View> drawer;
    private final S_DrawerRowSource settings;

    private Settings(S_DrawerRowSource settings) {
        this.settings = settings;
    }

    public static List rows(List rows) {
        ArrayList out = new ArrayList(rows);
        for (int i = out.size() - 1; i >= 0; i--) {
            if (hidden(out.get(i))) out.remove(i);
        }
        for (int i = 0; i < out.size(); i++) {
            Object row = out.get(i);
            if (row instanceof S_DrawerRowProvider && ((S_DrawerRowProvider) row).a == 1) {
                out.add(i, new Settings((S_DrawerRowSource) row));
                return out;
            }
        }
        return out;
    }

    /** The provider behind each optional drawer row; Settings and privacy and the profile card stay. */
    private static boolean hidden(Object row) {
        if (row instanceof S_MessagesRowSource) return hideMessages();
        if (row instanceof S_MessagesRowProvider) return ((S_MessagesRowProvider) row).a == 0 ? hideMessages() : on[24]; // chat list; Your Updates
        if (row instanceof S_DrawerRowProvider) return ((S_DrawerRowProvider) row).a == 0 ? on[21] : ((S_DrawerRowProvider) row).a == 2 && on[26]; // Recents; Company Information
        if (row instanceof S_ListeningStatsRowProvider) return on[22];
        if (row instanceof S_YourPlanRowProvider) return on[23];
        return row instanceof S_TasteProfileRowProvider && on[25];
    }

    /** Same row shape as Settings and privacy, with Spatch's icon, title and URI. */
    public S_KotlinFlow a() {
        S_DrawerRowLogSpec logSpec = null;
        try {
            S_KotlinFlow parentFlow = settings != null ? settings.a() : null;
            if (parentFlow instanceof S_KotlinFlowOf && ((S_KotlinFlowOf) parentFlow).b instanceof S_DrawerRowWrapper) {
                S_DrawerRowWrapper fg = (S_DrawerRowWrapper) ((S_KotlinFlowOf) parentFlow).b;
                if (fg.a instanceof S_DrawerRowContent) {
                    logSpec = ((S_DrawerRowContent) fg.a).e;
                }
            }
        } catch (Throwable ignored) {}
        return new S_KotlinFlowOf(new S_DrawerRowWrapper(S_DrawerRowIcon.c, CUSTOMIZE, URI, null, logSpec, null, 82), (byte) 10);
    }

    public static void attach(View drawerView) {
        drawer = new WeakReference<View>(drawerView);
        drawerView.setBackgroundColor(Palette.color(0xff14121c));
        load(drawerView.getContext());
        final View view = drawerView;
        view.post(new Runnable() { // the panel only exists once this view is attached
            public void run() {
                styleDrawer(view);
            }
        });
    }

    /** A rounded panel with an accent wash at the top, over a scrim in the theme's colour. */
    private static void styleDrawer(View view) {
        if (!on[29]) return;
        View panel = view;
        ViewParent up = view.getParent();
        while (up instanceof View && !(up instanceof p.S_DrawerLayout)) {
            panel = (View) up;
            up = up.getParent();
        }
        if (!(up instanceof p.S_DrawerLayout)) return;
        int surface = Palette.color(0xff14121c);
        float r = on[58] ? 28 * view.getResources().getDisplayMetrics().density : 0;
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {on[31] ? Pill.blend(surface, Palette.color(0xffd0bcff), 0.16f) : surface, surface, surface});
        fill.setCornerRadii(view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? new float[] {r, r, 0, 0, 0, 0, r, r} : new float[] {0, 0, r, r, r, r, 0, 0});
        panel.setBackground(fill);
        panel.setClipToOutline(true);
        view.setBackgroundColor(0);
        if (on[59]) ((p.S_DrawerLayout) up).setScrimColor(surface & 0xffffff | 0xb3000000);
    }

    /** Called with every drawer row's URI before Spotify navigates; true = handled here. */
    public static boolean open(String uri) {
        if (!URI.equals(uri)) return false;
        View v = drawer == null ? null : drawer.get();
        if (v == null) return false;
        return show(v, false);
    }

    /** Opens the page over v's window; themes is true when coming back from a live theme change. */
    static boolean show(View v, boolean themes) {
        View root = v.getRootView();
        if (!(root instanceof ViewGroup)) return false;

        SpatchSettings page = new SpatchSettings(v.getContext());
        ((ViewGroup) root).addView(page, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.bringToFront();
        page.requestFocus();
        page.enter();
        if (themes) page.showThemes();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            page.back = Back.register(page, page);
        }
        return true;
    }

    /** x7v is the renamed DrawerLayout; c(false) is its closeDrawers. */
    static void closeDrawer() {
        View v = drawer == null ? null : drawer.get();
        for (ViewParent p = v == null ? null : v.getParent(); p != null; p = p.getParent()) {
            if (p instanceof p.S_DrawerLayout) {
                ((p.S_DrawerLayout) p).c(false);
                return;
            }
        }
    }

    /** DevOptions exists only in --debug builds; other builds find nothing here and carry no developer code. */
    static void dev(String method, Class<?>[] types, Object... args) {
        try {
            Class.forName("spatch.DevOptions").getMethod(method, types).invoke(null, args);
        } catch (ClassNotFoundException release) {
            return;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The application (load() runs from Application.attachBaseContext); used by SpatchWidget. */
    static Context app;

    public static void load(Context c) {
        if (app == null) app = c;
        Palette.load(c);
        Fonts.load(c);
        if (prefs != null || c == null) return;
        prefs = c.getSharedPreferences("spatch", 0);
        if (c.getApplicationContext() instanceof android.app.Application) RowCards.watch((android.app.Application) c.getApplicationContext());
        dev("start", new Class<?>[] {Context.class}, c);
        for (int i = 0; i < KEYS.length; i++) on[i] = prefs.getBoolean(KEYS[i], DEFAULTS[i]);
        for (int i = 0; i < CHOICES.length; i++) chosen[i] = valid(i, prefs.getString(CHOICE_KEYS[i], null));
        for (int i = 0; i < strengths.length; i++) {
            try {
                strengths[i] = Math.max(STRENGTH_MIN[i], Math.min(STRENGTH_MAX[i], Integer.parseInt(prefs.getString(STRENGTH_KEYS[i], String.valueOf(STRENGTH_DEFAULTS[i])))));
            } catch (NumberFormatException e) {
                strengths[i] = STRENGTH_DEFAULTS[i];
            }
        }
        loadSections();
    }

    static int strength(int i) {
        return strengths[i];
    }

    static void setStrength(int i, int value) {
        strengths[i] = Math.max(STRENGTH_MIN[i], Math.min(STRENGTH_MAX[i], value));
        if (prefs != null) prefs.edit().putString(STRENGTH_KEYS[i], String.valueOf(strengths[i])).apply();
    }

    static String[] presets() {
        java.util.TreeSet<String> names = new java.util.TreeSet<String>();
        if (prefs != null) names.addAll(prefs.getStringSet("preset_names", new java.util.HashSet<String>()));
        return names.toArray(new String[0]);
    }

    /** Presets reuse the portable settings format; their names and snapshots survive resets. */
    static boolean savePreset(String name) {
        name = name.trim();
        if (prefs == null || name.isEmpty() || name.length() > 40) return false;
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) return false;
        java.util.HashSet<String> names = new java.util.HashSet<String>(java.util.Arrays.asList(presets()));
        names.add(name);
        prefs.edit().putStringSet("preset_names", names).putString("preset_value:" + name, export()).apply();
        return true;
    }

    static boolean applyPreset(String name) {
        String saved = prefs == null ? null : prefs.getString("preset_value:" + name, null);
        return saved != null && importText(saved) > 0;
    }

    static void deletePreset(String name) {
        if (prefs == null) return;
        java.util.HashSet<String> names = new java.util.HashSet<String>(java.util.Arrays.asList(presets()));
        names.remove(name);
        prefs.edit().putStringSet("preset_names", names).remove("preset_value:" + name).apply();
    }

    /** Unknown stored values (older or newer builds) fall back to the default. */
    private static String valid(int i, String value) {
        for (String option : CHOICES[i]) if (option.equals(value)) return value;
        return CHOICES[i][0];
    }

    static String choice(int i) {
        return chosen[i] == null ? CHOICES[i][0] : chosen[i];
    }

    static void setChoice(int i, String value) {
        chosen[i] = valid(i, value);
        if (prefs != null) prefs.edit().putString(CHOICE_KEYS[i], chosen[i]).apply();
    }

    /** True the first time it's asked for a key, ever. */
    static boolean firstTime(String key) {
        if (prefs == null || prefs.getBoolean(key, false)) return false;
        prefs.edit().putBoolean(key, true).apply();
        return true;
    }

    static boolean get(int i) {
        return on[i];
    }

    static void set(int i, boolean value) {
        on[i] = value;
        if (prefs != null) prefs.edit().putBoolean(KEYS[i], value).apply();
        if (i == LYRICS_FOCUS) Tint.glowLyrics();
        if (i == HIDE_PLAYER_LYRICS) PlayerLyrics.changed();
        if (i == WIDGET_WALLPAPER) SpatchWidget.schedule();
    }

    static boolean wave() {
        return on[0];
    }

    static boolean buttons() {
        return on[1] && Wave.animationsEnabled();
    }

    static boolean backdrop() {
        return on[2];
    }

    static boolean roundArt() {
        return on[3];
    }

    static boolean immersive() {
        return on[4];
    }

    /** Scalloped play buttons; off = plain circles. */
    static boolean shapes() {
        return on[5];
    }

    static boolean reduceMotion() {
        return on[6];
    }

    /** Spectrum under the immersive player (captures Spotify's own audio session; needs the mic permission). */
    static boolean visualizer() {
        return on[7];
    }

    /** Bass at the centre, mirrored; off = bass on the left to treble on the right. */
    static boolean vizMirrored() {
        return on[8];
    }

    /** Softer bar motion; off = snappy. */
    static boolean vizSmooth() {
        return on[9];
    }

    /** 48 thin bars; off = 24 thick bars. */
    static boolean vizThin() {
        return on[10];
    }

    /** Bars float above a bottom margin and rest as dots; off = they rise from the bottom edge. */
    static boolean vizFloating() {
        return on[11];
    }

    /** 16dp gap at the left and right; off = edge to edge. */
    static boolean vizSideMargin() {
        return on[12];
    }

    static boolean pageMotion() {
        return on[27] && Wave.animationsEnabled();
    }

    public static boolean pageMotionEnabled() {
        return pageMotion();
    }

    static boolean filterSlide() {
        return on[28] && Wave.animationsEnabled();
    }

    static boolean sideCards() {
        return on[29] && on[30];
    }

    static boolean filtersCustom() {
        return on[32];
    }

    static boolean filterGlow() {
        return on[33];
    }

    static boolean filterDrag() {
        return on[34];
    }

    static boolean softPress() {
        return on[35];
    }

    static boolean playlistMotion() {
        return on[36] && Wave.animationsEnabled();
    }

    static boolean libraryCards() {
        return on[39] && on[40];
    }

    static boolean libraryChips() {
        return on[39] && on[41];
    }

    static boolean playlistCards() {
        return on[42];
    }

    static boolean navStyle() { return on[43]; }
    static boolean navDrag() { return on[43] && on[44]; }
    static boolean navStretch() { return on[45]; }
    static boolean navLift() { return on[46]; }
    static boolean navPress() { return on[47]; }
    static boolean navPop() { return on[48]; }
    static boolean artPress() { return on[49]; }
    static boolean springPress() { return on[50] && Wave.animationsEnabled(); }
    static boolean waveThick() { return on[51]; }
    static boolean buttonBounce() { return on[52]; }
    static boolean skipNudge() { return on[53]; }
    static boolean playSpin() { return on[54]; }
    static boolean playMorph() { return on[55]; }
    static boolean immersiveTitles() { return on[56]; }
    static boolean immersiveFlight() { return on[57]; }
    static boolean miniPop() { return on[60]; }
    static boolean miniFlight() { return on[61]; }
    static boolean artDrift() { return on[62]; }
    static boolean filterAnimated() { return on[63]; }

    static boolean profileBanner() {
        return on[37];
    }

    static boolean profileThemeHeader() {
        return on[38];
    }

    /** Where the banner's crop sits, 0 top to 1 bottom. */
    static float profilePosition() {
        String position = choice(PROFILE_POSITION);
        return "top".equals(position) ? 0f : "bottom".equals(position) ? 1f : 0.5f;
    }

    static boolean hidePodcasts() {
        return on[13];
    }

    static boolean hideAudiobooks() {
        return on[14];
    }

    static boolean hideMessages() {
        return on[15];
    }

    /** Home sections seen so far (id -> title) and those the user hid; ids are stable, titles labels. */
    private static final java.util.TreeMap<String, String> seen = new java.util.TreeMap<String, String>();
    private static final java.util.HashSet<String> hidden = new java.util.HashSet<String>();
    private static final char SEP = 0x1f;
    private static final String SEEN = "home_seen3"; // earlier lists held podcast feeds and raw feed ids

    static synchronized void seeSection(String id, String title) {
        if (title.equals(seen.put(id, title)) || prefs == null) return;
        java.util.HashSet<String> stored = new java.util.HashSet<String>();
        for (java.util.Map.Entry<String, String> e : seen.entrySet()) stored.add(e.getKey() + SEP + e.getValue());
        prefs.edit().putStringSet(SEEN, stored).apply();
    }

    static synchronized boolean sectionHidden(String id) {
        return hidden.contains(id);
    }

    static synchronized void hideSection(String id, boolean hide) {
        if (hide) hidden.add(id);
        else hidden.remove(id);
        if (prefs != null) prefs.edit().putStringSet("home_hidden", new java.util.HashSet<String>(hidden)).apply();
    }

    /** The settings list, one {title, ids...} per title: Spotify repeats a title across feeds. */
    static synchronized java.util.ArrayList<String[]> sections() {
        java.util.TreeMap<String, String> byTitle = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        for (java.util.Map.Entry<String, String> e : seen.entrySet()) {
            String ids = byTitle.get(e.getValue());
            byTitle.put(e.getValue(), ids == null ? e.getKey() : ids + SEP + e.getKey());
        }
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        for (java.util.Map.Entry<String, String> e : byTitle.entrySet()) {
            String[] ids = e.getValue().split(String.valueOf(SEP));
            String[] row = new String[ids.length + 1];
            row[0] = e.getKey();
            System.arraycopy(ids, 0, row, 1, ids.length);
            out.add(row);
        }
        return out;
    }

    private static synchronized void loadSections() {
        for (String row : prefs.getStringSet(SEEN, new java.util.HashSet<String>())) {
            int at = row.indexOf(SEP);
            if (at > 0) seen.put(row.substring(0, at), row.substring(at + 1));
        }
        hidden.addAll(prefs.getStringSet("home_hidden", new java.util.HashSet<String>()));
    }

    /** Every setting as key=value lines, for moving a setup between installs. */
    static String export() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) out.append(KEYS[i]).append('=').append(on[i]).append('\n');
        for (int i = 0; i < CHOICES.length; i++) out.append(CHOICE_KEYS[i]).append('=').append(choice(i)).append('\n');
        for (int i = 0; i < strengths.length; i++) out.append(STRENGTH_KEYS[i]).append('=').append(strengths[i]).append('\n');
        return out.toString();
    }

    /** Applies exported text; unknown keys and invalid values are skipped. Returns how many were applied. */
    static int importText(String text) {
        int applied = 0;
        for (String line : text.split("\n")) {
            int split = line.indexOf('=');
            if (split < 1) continue;
            String key = line.substring(0, split).trim(), value = line.substring(split + 1).trim();
            for (int i = 0; i < KEYS.length; i++) {
                if (KEYS[i].equals(key) && ("true".equals(value) || "false".equals(value))) {
                    set(i, "true".equals(value));
                    applied++;
                }
            }
            for (int i = 0; i < CHOICES.length; i++) {
                if (CHOICE_KEYS[i].equals(key) && java.util.Arrays.asList(CHOICES[i]).contains(value)) {
                    setChoice(i, value);
                    applied++;
                }
            }
            for (int i = 0; i < strengths.length; i++) {
                if (!STRENGTH_KEYS[i].equals(key)) continue;
                try {
                    int amount = Integer.parseInt(value);
                    if (amount < STRENGTH_MIN[i] || amount > STRENGTH_MAX[i]) continue;
                    setStrength(i, amount);
                    applied++;
                } catch (NumberFormatException ignored) {}
            }
        }
        return applied;
    }

    static void resetAll() {
        for (int i = 0; i < KEYS.length; i++) set(i, DEFAULTS[i]);
        for (int i = 0; i < CHOICES.length; i++) setChoice(i, CHOICES[i][0]);
        for (int i = 0; i < strengths.length; i++) setStrength(i, STRENGTH_DEFAULTS[i]);
        for (String id : new java.util.ArrayList<String>(hidden)) hideSection(id, false);
    }
}
