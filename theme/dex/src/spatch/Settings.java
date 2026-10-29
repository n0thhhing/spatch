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
import p.vtz;
import p.zsx;

/**
 * Spatch's drawer row and preferences. hf81 (the profile side drawer) passes its row providers through
 * rows(), which adds this provider directly above Spotify's "Settings and privacy" (ft01 kind 1); the
 * drawer's click handler (g7y0) asks open() first and Spatch shows SpatchSettings for its own URI.
 */
public final class Settings implements jg81 {
    static final String URI = "spotify:spatch:settings";
    static final String[] KEYS = {"wave", "buttons", "backdrop", "round_art", "immersive"};
    private static final int CUSTOMIZE = 0x7f131b75; // R.string.mixing_edit_page_customize_button (rewritten to "Spatch" at render time)
    private static final boolean[] on = {true, true, true, true, true};
    private static SharedPreferences prefs;
    private static WeakReference<View> drawer;
    private final jg81 settings;

    private Settings(jg81 settings) {
        this.settings = settings;
    }

    public static List rows(List rows) {
        ArrayList out = new ArrayList(rows);
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            page.back = Back.register(page, page);
        }
        return true;
    }

    public static void load(Context c) {
        Palette.load(c);
        if (prefs != null || c == null) return;
        prefs = c.getSharedPreferences("spatch", 0);
        for (int i = 0; i < KEYS.length; i++) on[i] = prefs.getBoolean(KEYS[i], true);
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
}
