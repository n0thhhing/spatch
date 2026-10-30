package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import p.br20;
import p.cg81;
import p.eg81;
import p.fg81;
import p.ft01;
import p.jg81;
import p.upf;
import p.vtz;
import p.zsx;

/**
 * Spatch's drawer row and preferences. hf81 (the profile side drawer) passes its row providers through
 * rows(), which adds this provider directly above Spotify's "Settings and privacy" (ft01 kind 1); the
 * drawer's click handler (g7y0) asks open() first and Spatch shows SpatchSettings for its own URI.
 */
public final class Settings implements jg81 {
    static final String URI = "spotify:spatch:settings";
    static final String[] KEYS = {"wave", "buttons", "backdrop", "round_art", "immersive", "shapes", "reduce_motion", "visualizer", "viz_mirrored", "viz_smooth", "viz_thin", "viz_floating", "viz_side_margin", "hide_podcasts", "hide_audiobooks", "hide_messages"};
    static final boolean[] DEFAULTS = {true, true, true, true, true, true, false, true, true, false, false, false, true, false, false, false};
    private static final int CUSTOMIZE = 0x7f131b75; // R.string.mixing_edit_page_customize_button (rewritten to "Spatch" at render time)
    /** Multi-choice settings: stable stored values, first is the default (today's look). */
    static final int VIZ_SHAPE = 0, VIZ_TAPER = 1, ART_OUTLINE = 2, ART_GLOW = 3, ART_SPECTRUM = 4;
    static final String[] CHOICE_KEYS = {"viz_shape", "viz_taper", "art_outline", "art_glow", "art_spectrum"};
    static final String[][] CHOICES = {
        {"bars", "centre", "contour", "dots"},
        {"subtle", "none"},
        {"wavy", "simple", "none"},
        {"current", "soft", "off"},
        {"off", "bars", "dots"},
    };
    private static final String[] chosen = new String[CHOICES.length];
    private static final boolean[] on = DEFAULTS.clone();
    private static SharedPreferences prefs;
    private static WeakReference<View> drawer;
    private final jg81 settings;

    private Settings(jg81 settings) {
        this.settings = settings;
    }

    public static List rows(List rows) {
        ArrayList out = new ArrayList(rows);
        for (int i = out.size() - 1; i >= 0; i--) {
            if (out.get(i) instanceof upf && hideMessages()) out.remove(i); // the drawer's Messages block
        }
        for (int i = 0; i < out.size(); i++) {
            Object row = out.get(i);
            if (row instanceof ft01 && ((ft01) row).a == 1) {
                out.add(i, new Settings((jg81) row));
                return out;
            }
        }
        return rows;
    }

    /** Same row shape as Settings and privacy, with Spatch's icon, title and URI. */
    public br20 a() {
        eg81 logSpec = null;
        try {
            br20 parentFlow = settings != null ? settings.a() : null;
            if (parentFlow instanceof vtz && ((vtz) parentFlow).b instanceof fg81) {
                fg81 fg = (fg81) ((vtz) parentFlow).b;
                if (fg.a instanceof cg81) {
                    logSpec = ((cg81) fg.a).e;
                }
            }
        } catch (Throwable ignored) {}
        return new vtz(new fg81(zsx.c, CUSTOMIZE, URI, null, logSpec, null, 82), (byte) 10);
    }

    public static void attach(View drawerView) {
        drawer = new WeakReference<View>(drawerView);
        load(drawerView.getContext());
    }

    /** Called with every drawer row's URI before Spotify navigates; true = handled here. */
    public static boolean open(String uri) {
        if (!URI.equals(uri)) return false;
        View v = drawer == null ? null : drawer.get();
        if (v == null) return false;
        View root = v.getRootView();
        if (!(root instanceof ViewGroup)) return false;

        ViewParent p = v.getParent();
        while (p != null) {
            if (p.getClass().getName().contains("DrawerLayout")) {
                try {
                    p.getClass().getMethod("closeDrawers").invoke(p);
                } catch (Throwable ignored) {}
                break;
            }
            p = p.getParent();
        }

        SpatchSettings page = new SpatchSettings(v.getContext());
        ((ViewGroup) root).addView(page, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.bringToFront();
        page.requestFocus();
        page.enter();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            page.back = Back.register(page, page);
        }
        return true;
    }

    /** The application (load() runs from Application.attachBaseContext); used by SpatchWidget. */
    static Context app;

    public static void load(Context c) {
        if (app == null) app = c;
        Palette.load(c);
        if (prefs != null || c == null) return;
        prefs = c.getSharedPreferences("spatch", 0);
        for (int i = 0; i < KEYS.length; i++) on[i] = prefs.getBoolean(KEYS[i], DEFAULTS[i]);
        for (int i = 0; i < CHOICES.length; i++) chosen[i] = valid(i, prefs.getString(CHOICE_KEYS[i], null));
        loadSections();
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

    static boolean get(int i) {
        return on[i];
    }

    static void set(int i, boolean value) {
        on[i] = value;
        if (prefs != null) prefs.edit().putBoolean(KEYS[i], value).apply();
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

    static synchronized void seeSection(String id, String title) {
        if (title.equals(seen.put(id, title)) || prefs == null) return;
        java.util.HashSet<String> stored = new java.util.HashSet<String>();
        for (java.util.Map.Entry<String, String> e : seen.entrySet()) stored.add(e.getKey() + SEP + e.getValue());
        prefs.edit().putStringSet("home_seen", stored).apply();
    }

    static synchronized boolean sectionHidden(String id) {
        return hidden.contains(id);
    }

    static synchronized void hideSection(String id, boolean hide) {
        if (hide) hidden.add(id);
        else hidden.remove(id);
        if (prefs != null) prefs.edit().putStringSet("home_hidden", new java.util.HashSet<String>(hidden)).apply();
    }

    /** Sections by title, for the settings list. */
    static synchronized java.util.ArrayList<String[]> sections() {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        for (java.util.Map.Entry<String, String> e : seen.entrySet()) out.add(new String[] {e.getKey(), e.getValue()});
        java.util.Collections.sort(out, BY_TITLE);
        return out;
    }

    private static final java.util.Comparator<String[]> BY_TITLE = new SectionOrder();

    private static synchronized void loadSections() {
        for (String row : prefs.getStringSet("home_seen", new java.util.HashSet<String>())) {
            int at = row.indexOf(SEP);
            if (at > 0) seen.put(row.substring(0, at), row.substring(at + 1));
        }
        hidden.addAll(prefs.getStringSet("home_hidden", new java.util.HashSet<String>()));
    }

    static void resetAll() {
        for (int i = 0; i < KEYS.length; i++) set(i, DEFAULTS[i]);
        for (int i = 0; i < CHOICES.length; i++) setChoice(i, CHOICES[i][0]);
        for (String id : new java.util.ArrayList<String>(hidden)) hideSection(id, false);
    }
}
