package spatch;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import p.aj00;
import p.bfi0;
import p.cu70;
import p.dsm1;
import p.e5c0;
import p.ifi0;
import p.jfi0;
import p.l4c0;
import p.ndy;
import p.np10;
import p.oz71;
import p.q6k1;
import p.t0d0;
import p.xmc0;
import p.r4c0;
import p.s6e;
import p.tu70;
import p.tz71;
import p.u4c0;
import p.uz71;

/**
 * Presentation filters for Home. Every Home update reaches the list as (model, section URI) pairs in
 * xmc0's constructor; section URIs ("spotify:section:<id>|<slug>") are stable identity and titles are
 * labels only. Nothing is deleted: the unfiltered data stays upstream, so turning a filter off
 * restores it on the next update.
 */
public final class Content {
    private static final String SECTION = "spotify:section:";
    private static volatile List<String> onHome = new ArrayList<String>();
    private static volatile List homeSource; // before filtering, so a section can come back
    private static WeakReference<t0d0> homeList;
    private static int homeModel;

    private Content() {}

    static boolean hidesShowsIn(String slug) {
        return Settings.hidePodcasts() && slug.contains("podcast");
    }

    static boolean audiobookShelf(String slug) {
        return slug.contains("audiobook") || slug.contains("-books");
    }

    /** A content URI to remove. Audiobooks share show URIs, so shelves identify them by slug. */
    static boolean blocked(String uri, String slug) {
        if (uri == null) return false;
        boolean show = uri.startsWith("spotify:show:") || uri.startsWith("spotify:episode:");
        if (Settings.hideAudiobooks() && (uri.startsWith("spotify:audiobook:") || show && audiobookShelf(slug))) return true;
        return Settings.hidePodcasts() && show && !audiobookShelf(slug);
    }

    /** xmc0's list. Anything that is not a Home section pair passes untouched. */
    /** How many tabs are hidden right now: the bar's layout (xjm0, classes4, no method references to spare) reads it. */
    public static volatile int hiddenTabs;

    /** Position of the bottom-navigation tab "Hide the Premium tab" took away, or -1. */
    static volatile int hiddenTab = -1;

    /**
     * tsn0 asks per tab: a free account's Premium tab (its page is an upsell destination) is skipped
     * while the switch is on. Its position stays Spotify's, so taps still reach the right page.
     */
    public static boolean skipTab(p.dsn0 tab) {
        if (!Settings.get(Settings.HIDE_PREMIUM_TAB)) {
            hiddenTab = -1;
            return false;
        }
        if (!premium(tab)) return false;
        hiddenTab = tab.b;
        return true;
    }

    /** How many tabs the bar lays out: Spotify sizes each from the list's length, so the hidden one must not count. */
    public static int tabCount(List tabs) {
        int count = 0;
        for (Object tab : tabs) if (!premium((p.dsn0) tab)) count++;
        hiddenTabs = tabs.size() - count;
        return count;
    }

    private static boolean premium(p.dsn0 tab) {
        String page = tab.c == null ? null : tab.c.a;
        return Settings.get(Settings.HIDE_PREMIUM_TAB) && page != null && (page.contains("premium") || page.contains("upsell"));
    }

    /** Where a tab sits on screen once the hidden one is gone, for the selection pill. */
    static int visualTab(int position) {
        int hidden = hiddenTab;
        return hidden >= 0 && position > hidden ? position - 1 : position;
    }

    public static List home(List list) {
        if (list.isEmpty() || !isSection(list.get(0))) return list;
        homeSource = list;
        ArrayList out = new ArrayList(list.size());
        boolean changed = false;
        for (Object o : list) {
            if (!isSection(o)) {
                out.add(o);
                continue;
            }
            q6k1 pair = (q6k1) o;
            String id = (String) pair.b;
            if (!showsOnly(pair.a, id)) Settings.seeSection(id, title(pair.a, id));
            Object kept = Settings.sectionHidden(id) ? null : filter(pair.a, id);
            if (kept == pair.a) {
                out.add(o);
            } else {
                changed = true;
                if (kept != null) out.add(new q6k1(kept, id));
            }
        }
        ArrayList<String> ids = new ArrayList<String>();
        for (Object o : out) if (isSection(o)) ids.add((String) ((q6k1) o).b);
        onHome = ids;
        return changed ? out : list;
    }

    /** t0d0.b, the list Home's model is submitted to. */
    public static void submit(t0d0 list, xmc0 model) {
        if (model.b.isEmpty() || !isSection(model.b.get(0))) return;
        homeList = new WeakReference<t0d0>(list);
        homeModel = model.a;
        FilterRow.arrived();
    }

    /** Filters the same model again, so Home follows a hidden section without a restart. */
    static void refresh() {
        t0d0 list = homeList == null ? null : homeList.get();
        if (list != null && homeSource != null) list.b(new xmc0(homeModel, homeSource));
    }

    /** Section ids in the order of the latest Home list, hidden ones already gone. */
    static List<String> onHome() {
        return onHome;
    }

    private static final java.util.HashMap<String, Boolean> SHOWS_ONLY = new java.util.HashMap<String, Boolean>();

    /** Podcast and audiobook shelves: Hide podcasts covers them, so the section list leaves them out. */
    private static synchronized boolean showsOnly(Object model, String id) {
        Boolean known = SHOWS_ONLY.get(id);
        if (known != null) return known.booleanValue();
        String slug = slug(id);
        boolean only = slug.contains("podcast") || audiobookShelf(slug);
        if (!only) {
            ArrayList<String> uris = new ArrayList<String>();
            walk(model, 4, uris);
            only = !uris.isEmpty();
            for (String uri : uris) only &= uri.startsWith("spotify:show:") || uri.startsWith("spotify:episode:") || uri.startsWith("spotify:audiobook:");
        }
        SHOWS_ONLY.put(id, Boolean.valueOf(only));
        return only;
    }

    private static boolean isSection(Object o) {
        return o instanceof q6k1 && ((q6k1) o).b instanceof String && ((String) ((q6k1) o).b).startsWith(SECTION);
    }

    static String slug(String id) {
        int bar = id.indexOf('|');
        return bar < 0 ? "" : id.substring(bar + 1);
    }

    /** The model, a copy without blocked items, or null when nothing would remain. */
    private static Object filter(Object model, String id) {
        if (!Settings.hidePodcasts() && !Settings.hideAudiobooks()) return model;
        String slug = slug(id);
        if (hidesShowsIn(slug) || Settings.hideAudiobooks() && audiobookShelf(slug)) return null;
        if (model instanceof cu70) {
            cu70 m = (cu70) model;
            ArrayList kept = new ArrayList();
            for (Object uri : m.a.c) if (!blocked((String) uri, slug)) kept.add(uri);
            if (kept.size() == m.a.c.size()) return model;
            return kept.isEmpty() ? null : new cu70(new tu70(m.a.a, m.a.b, kept, m.a.e, m.a.f, 0), m.b, m.c);
        }
        if (model instanceof oz71) {
            oz71 m = (oz71) model;
            ArrayList kept = new ArrayList();
            for (Object tile : m.a.c) if (!blocked(((tz71) tile).a, slug)) kept.add(tile);
            if (kept.size() == m.a.c.size()) return model;
            return kept.isEmpty() ? null : new oz71(new uz71(m.a.a, kept, m.a.b), m.b, m.c);
        }
        if (model instanceof bfi0) {
            bfi0 m = (bfi0) model;
            ArrayList kept = new ArrayList();
            for (Object item : m.c.d) if (!blocked(((ifi0) item).c, slug)) kept.add(item);
            if (kept.size() == m.c.d.size()) return model;
            if (kept.isEmpty()) return null;
            return new bfi0(m.a, m.b, new jfi0(m.c.a, m.c.b, m.c.c, kept, m.c.e, m.c.f, m.c.g));
        }
        // Other section types: dropped only when every content URI in them is blocked.
        ArrayList<String> uris = new ArrayList<String>();
        walk(model, 4, uris);
        boolean any = false;
        for (String uri : uris) {
            if (!blocked(uri, slug)) return model;
            any = true;
        }
        return any ? null : model;
    }

    /** Collects "spotify:" content URIs (not section URIs) from a model's fields. */
    private static void walk(Object o, int depth, ArrayList<String> uris) {
        if (o instanceof String) {
            String s = (String) o;
            if (s.startsWith("spotify:") && !s.startsWith(SECTION)) uris.add(s);
            return;
        }
        if (o instanceof List) {
            for (Object e : (List) o) walk(e, depth, uris);
            return;
        }
        if (o == null || depth == 0 || !o.getClass().getName().startsWith("p.")) return;
        for (Field f : o.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                walk(f.get(o), depth - 1, uris);
            } catch (IllegalAccessException | RuntimeException ignored) {
                // An unreadable field just contributes no URIs.
            }
        }
    }

    /** The section's visible title (the first text heading in its model), else a readable slug. */
    static String title(Object model, String id) {
        String found = heading(model, 4);
        if (found != null && !found.isEmpty() && !found.startsWith("spotify:")) return found.replaceAll("\\[\\d+\\]", "").trim();
        String words = slug(id).replace('-', ' ').replaceAll("\\[\\d+\\]", "").replaceFirst("^musicfeed ", "").trim();
        if (words.equals("pzn main feed")) return "Picked for you";
        if (words.equals("idj entrypoint")) return "DJ";
        if (words.equals("more like artist active") || words.startsWith("more like artist listens")) return "More like your artists";
        return words.isEmpty() ? "Home section" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String heading(Object o, int depth) {
        if (o == null || depth == 0 || o instanceof List || !o.getClass().getName().startsWith("p.")) return null;
        Class<?> c = o.getClass();
        boolean text = false;
        for (Class<?> i : c.getInterfaces()) text |= i.getName().equals("p.j360");
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                if (text && v instanceof String) return (String) v;
                String inner = heading(v, depth - 1);
                if (inner != null) return inner;
            } catch (IllegalAccessException | RuntimeException ignored) {
                // Keep looking in the other fields.
            }
        }
        return null;
    }

    // Search's Podcasts and Audiobooks browse pages.
    private static final String PODCAST_PAGE = "spotify:page:0JQ5DArNBzkmxXHCqFLx2J";
    private static final String AUDIOBOOK_PAGE = "spotify:page:0JQ5DAqbMKFETqK4t8f1n3";

    /** Search tiles, before s8e lays them out in rows, so the rest reflow with no gap. */
    public static ArrayList tiles(ArrayList tiles) {
        if (!Settings.hidePodcasts() && !Settings.hideAudiobooks()) return tiles;
        ArrayList out = new ArrayList(tiles.size());
        for (Object tile : tiles) {
            String uri = ((s6e) tile).a;
            if (PODCAST_PAGE.equals(uri) && Settings.hidePodcasts() || AUDIOBOOK_PAGE.equals(uri) && Settings.hideAudiobooks()) continue;
            out.add(tile);
        }
        return out;
    }

    /** The Home chips: Spotify's mapping, minus hidden categories (the rest reflow by themselves). */
    public static ArrayList feeds(List chips) {
        ArrayList mapped = aj00.a(chips);
        if (!Settings.hidePodcasts() && !Settings.hideAudiobooks()) return mapped;
        ArrayList out = new ArrayList(mapped.size());
        for (Object chip : mapped) {
            String id = ((np10) chip).a;
            // Seen live as "client-native:podcasts"; the chip enum also names "podcasts-chip".
            boolean podcast = id.equals("client-native:podcasts") || id.startsWith("podcasts-");
            boolean book = id.equals("client-native:audiobooks") || id.startsWith("audiobooks-");
            if (podcast && Settings.hidePodcasts() || book && Settings.hideAudiobooks()) continue;
            out.add(chip);
        }
        return out;
    }

    /** Your Library entries (zzo1.D); saved shows and books stay saved, this page just skips them. */
    public static ArrayList library(ArrayList items) {
        if (!Settings.hidePodcasts() && !Settings.hideAudiobooks()) return items;
        ArrayList out = new ArrayList(items.size());
        for (Object item : items) {
            ndy type = ((dsm1) item).e;
            boolean podcast = type == ndy.b || type == ndy.Y;
            boolean book = type == ndy.a || type == ndy.g;
            if (podcast && Settings.hidePodcasts() || book && Settings.hideAudiobooks()) continue;
            out.add(item);
        }
        return out;
    }

    /** Your Library filter chips (oxo1.q), including the downloaded sub-filters. */
    public static ArrayList libraryChips(ArrayList chips) {
        if (!Settings.hidePodcasts() && !Settings.hideAudiobooks()) return chips;
        ArrayList out = new ArrayList(chips.size());
        for (Object chip : chips) {
            boolean podcast = chip instanceof e5c0 || chip instanceof u4c0;
            boolean book = chip instanceof l4c0 || chip instanceof r4c0;
            if (podcast && Settings.hidePodcasts() || book && Settings.hideAudiobooks()) continue;
            out.add(chip);
        }
        return out;
    }
}
