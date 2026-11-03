package spatch;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import p.S_SearchChipCopy;
import p.S_HomeItemSection;
import p.S_HomeShelfSection;
import p.S_LibraryItem;
import p.S_LibraryPodcastChip;
import p.S_HomeListItem;
import p.S_HomeItemList;
import p.S_LibraryAudiobookChip;
import p.S_LibraryContentType;
import p.S_SearchChip;
import p.S_HomeTileSection;
import p.S_KotlinPair;
import p.S_HomeList;
import p.S_HomeFeedModel;
import p.S_LibraryAudiobookChipB;
import p.S_BrowseTile;
import p.S_HomeShelf;
import p.S_HomeTile;
import p.S_LibraryPodcastChipB;
import p.S_HomeTileShelf;

/**
 * Presentation filters for Home. Every Home update reaches the list as (model, section URI) pairs in
 * S_HomeFeedModel's constructor; section URIs ("spotify:section:<id>|<slug>") are stable identity and titles are
 * labels only. Nothing is deleted: the unfiltered data stays upstream, so turning a filter off
 * restores it on the next update.
 */
public final class Content {
    private static final String SECTION = "spotify:section:";
    private static volatile List<String> onHome = new ArrayList<String>();
    private static volatile List homeSource; // before filtering, so a section can come back
    private static WeakReference<S_HomeList> homeList;
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

    /** S_HomeFeedModel's list. Anything that is not a Home section pair passes untouched. */
    /** How many tabs are hidden right now: the bar's layout (xjm0, classes4, no method references to spare) reads it. */
    public static volatile int hiddenTabs;

    /** Position of the bottom-navigation tab "Hide the Premium tab" took away, or -1. */
    static volatile int hiddenTab = -1;

    /**
     * tsn0 asks per tab: a free account's Premium tab (its page is an upsell destination) is skipped
     * while the switch is on. Its position stays Spotify's, so taps still reach the right page.
     */
    public static boolean skipTab(p.S_NavTab tab) {
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
        for (Object tab : tabs) if (!premium((p.S_NavTab) tab)) count++;
        hiddenTabs = tabs.size() - count;
        NavIndicator.tabs(count);
        return count;
    }

    private static boolean premium(p.S_NavTab tab) {
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
            S_KotlinPair pair = (S_KotlinPair) o;
            String id = (String) pair.b;
            if (!showsOnly(pair.a, id)) Settings.seeSection(id, title(pair.a, id));
            Object kept = Settings.sectionHidden(id) ? null : filter(pair.a, id);
            if (kept == pair.a) {
                out.add(o);
            } else {
                changed = true;
                if (kept != null) out.add(new S_KotlinPair(kept, id));
            }
        }
        ArrayList<String> ids = new ArrayList<String>();
        for (Object o : out) if (isSection(o)) ids.add((String) ((S_KotlinPair) o).b);
        onHome = ids;
        return changed ? out : list;
    }

    /** S_HomeList.b, the list Home's model is submitted to. */
    public static void submit(S_HomeList list, S_HomeFeedModel model) {
        if (model.b.isEmpty() || !isSection(model.b.get(0))) return;
        homeList = new WeakReference<S_HomeList>(list);
        homeModel = model.a;
        FilterRow.arrived();
    }

    /** Filters the same model again, so Home follows a hidden section without a restart. */
    static void refresh() {
        S_HomeList list = homeList == null ? null : homeList.get();
        if (list != null && homeSource != null) list.b(new S_HomeFeedModel(homeModel, homeSource));
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
        return o instanceof S_KotlinPair && ((S_KotlinPair) o).b instanceof String && ((String) ((S_KotlinPair) o).b).startsWith(SECTION);
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
        if (model instanceof S_HomeShelfSection) {
            S_HomeShelfSection m = (S_HomeShelfSection) model;
            ArrayList kept = new ArrayList();
            for (Object uri : m.a.c) if (!blocked((String) uri, slug)) kept.add(uri);
            if (kept.size() == m.a.c.size()) return model;
            return kept.isEmpty() ? null : new S_HomeShelfSection(new S_HomeShelf(m.a.a, m.a.b, kept, m.a.e, m.a.f, 0), m.b, m.c);
        }
        if (model instanceof S_HomeTileSection) {
            S_HomeTileSection m = (S_HomeTileSection) model;
            ArrayList kept = new ArrayList();
            for (Object tile : m.a.c) if (!blocked(((S_HomeTile) tile).a, slug)) kept.add(tile);
            if (kept.size() == m.a.c.size()) return model;
            return kept.isEmpty() ? null : new S_HomeTileSection(new S_HomeTileShelf(m.a.a, kept, m.a.b), m.b, m.c);
        }
        if (model instanceof S_HomeItemSection) {
            S_HomeItemSection m = (S_HomeItemSection) model;
            ArrayList kept = new ArrayList();
            for (Object item : m.c.d) if (!blocked(((S_HomeListItem) item).c, slug)) kept.add(item);
            if (kept.size() == m.c.d.size()) return model;
            if (kept.isEmpty()) return null;
            return new S_HomeItemSection(m.a, m.b, new S_HomeItemList(m.c.a, m.c.b, m.c.c, kept, m.c.e, m.c.f, m.c.g));
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
        for (Class<?> i : c.getInterfaces()) text |= i.getName().equals("p.S_HomeTextTitle");
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
            String uri = ((S_BrowseTile) tile).a;
            if (PODCAST_PAGE.equals(uri) && Settings.hidePodcasts() || AUDIOBOOK_PAGE.equals(uri) && Settings.hideAudiobooks()) continue;
            out.add(tile);
        }
        return out;
    }

    /** The Home chips: Spotify's mapping, minus hidden categories (the rest reflow by themselves). */
    public static ArrayList feeds(List chips) {
        ArrayList mapped = S_SearchChipCopy.a(chips);
        if (!Settings.hidePodcasts() && !Settings.hideAudiobooks()) return mapped;
        ArrayList out = new ArrayList(mapped.size());
        for (Object chip : mapped) {
            String id = ((S_SearchChip) chip).a;
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
            S_LibraryContentType type = ((S_LibraryItem) item).e;
            boolean podcast = type == S_LibraryContentType.b || type == S_LibraryContentType.Y;
            boolean book = type == S_LibraryContentType.a || type == S_LibraryContentType.g;
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
            boolean podcast = chip instanceof S_LibraryPodcastChip || chip instanceof S_LibraryPodcastChipB;
            boolean book = chip instanceof S_LibraryAudiobookChip || chip instanceof S_LibraryAudiobookChipB;
            if (podcast && Settings.hidePodcasts() || book && Settings.hideAudiobooks()) continue;
            out.add(chip);
        }
        return out;
    }
}
