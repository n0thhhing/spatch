package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import androidx.recyclerview.widget.RecyclerView;
import java.util.WeakHashMap;

/**
 * Hides chosen cards under the player. Spotify's code never names its cards (most come from one
 * server-driven list), so each card is drawn once off-screen and known by the title text it draws,
 * matched against Spotify's own title strings so any language works.
 */
final class PlayerCards implements ViewTreeObserver.OnPreDrawListener {
    /** Per section: its switch, then the Spotify string names of the titles that card draws. */
    private static final int[] SECTIONS = {Settings.HIDE_CARD_LYRICS, Settings.HIDE_CARD_ARTIST, Settings.HIDE_CARD_CREDITS, Settings.HIDE_CARD_QUEUE,
        Settings.HIDE_CARD_SONGDNA, Settings.HIDE_CARD_QUIZ, Settings.HIDE_CARD_CHAT, Settings.HIDE_CARD_VIDEOS, Settings.HIDE_CARD_TOUR};
    private static final String[][] TITLES = {
        {"lyrics_card_title", "lyrics_card_title_preview"},
        {"artist_bio_card_header", "author_bio_card_header"},
        {"track_credits_title", "audiobook_credits_section_header"},
        {"up_next_card_title"},
        {"show_value_song_dna_logo_title", "about_songdna_title", "song_dna_context_menu_item_label", "song_dna_npv_card_label_discover_people_behind", "song_dna_npv_card_label_discover_people_and_samples_behind"}, // its name is a logo: the line under it is the text
        {"music_quiz_header_title", "music_quiz_gameplay_title"},
        {"martini_chat_entry_card_header_generic", "martini_chat_entry_card_header_audiobook"},
        {"related_music_videos", "album_music_videos", "music_videos_section_title", "in_this_video"},
        {"tour_label", "merch_scroll_card_title", "album_concerts", "event_card_multiple_events_title"},
    };
    /** Server-sent titles with no string in the app. */
    private static final String[][] ENGLISH = {{}, {}, {}, {"Next in queue", "Up next"}, {}, {}, {"Let's talk"}, {}, {"On tour"}};
    private static String[][] resolved;

    private static final int UNSEEN = -2, NONE = -1;
    private static final WeakHashMap<RecyclerView, PlayerCards> WATCHED = new WeakHashMap<RecyclerView, PlayerCards>();
    private final java.lang.ref.WeakReference<RecyclerView> list;
    /** Section of each card view, for the song it was seen on. */
    private final WeakHashMap<View, long[]> seen = new WeakHashMap<View, long[]>();
    /** Original heights of the cards this collapsed. */
    private final WeakHashMap<View, Integer> hidden = new WeakHashMap<View, Integer>();
    private Object song;

    private PlayerCards(RecyclerView list) { this.list = new java.lang.ref.WeakReference<RecyclerView>(list); }

    /** Called with Spotify's widgets_container; watches its card list from then on. */
    static void watch(View cards) {
        RecyclerView list = find(cards, 3);
        if (list == null || WATCHED.containsKey(list)) return;
        PlayerCards watcher = new PlayerCards(list);
        WATCHED.put(list, watcher);
        list.getViewTreeObserver().addOnPreDrawListener(watcher);
    }

    private static RecyclerView find(View v, int depth) {
        if (v instanceof RecyclerView) return (RecyclerView) v;
        if (depth == 0 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) { RecyclerView r = find(g.getChildAt(i), depth - 1); if (r != null) return r; }
        return null;
    }

    /** Runs before each frame: a newly bound card is collapsed before it is ever drawn. */
    public boolean onPreDraw() {
        RecyclerView rv = list.get();
        if (rv == null || !rv.isAttachedToWindow()) return true;
        boolean any = false;
        for (int s : SECTIONS) any |= Settings.get(s);
        boolean changed = false;
        Object now = Tint.artwork();
        boolean newSong = now != song;
        song = now;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View card = rv.getChildAt(i);
            long[] known = seen.get(card);
            // A new song re-checks every card: the list may hand a view to a different card.
            if (newSong && known != null) { known[0] = UNSEEN; }
            Integer height = hidden.get(card);
            int section = known == null ? UNSEEN : (int) known[0];
            if (height != null && (section == UNSEEN || !any || !Settings.get(SECTIONS[section]))) { changed |= show(card, height); continue; }
            if (!any || height != null) continue;
            if (section == UNSEEN || section == NONE && known[1] < 8) {
                long t = android.os.SystemClock.uptimeMillis();
                if (known != null && section == NONE && t - known[2] < 600) continue; // still loading: look again shortly
                section = classify(card);
                if (known == null) { known = new long[3]; seen.put(card, known); }
                known[1] = section == NONE ? known[1] + 1 : 0;
                known[0] = section; known[2] = t;
            }
            if (section >= 0 && Settings.get(SECTIONS[section])) changed |= hide(card);
        }
        return !changed; // skip this frame: the list lays out again without the collapsed cards first
    }

    private boolean hide(View card) {
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        if (lp == null) return false;
        hidden.put(card, lp.height);
        lp.height = 0;
        card.setLayoutParams(lp);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return true;
    }

    private boolean show(View card, int height) {
        hidden.remove(card);
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        if (lp == null) return false;
        lp.height = height;
        card.setLayoutParams(lp);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        return true;
    }

    private static Bitmap scratch;

    /** Draws the card (up to 640dp of it) into a canvas that keeps only text, and returns the section of the first title it recognises. */
    private static int classify(View card) {
        int w = card.getWidth(), h = Math.min(card.getHeight(), Math.round(640 * card.getResources().getDisplayMetrics().density));
        if (w <= 0 || h <= 0) return NONE;
        if (resolved == null) resolved = resolve(card);
        try {
            if (scratch == null || scratch.getWidth() < w || scratch.getHeight() < h) scratch = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            TextSpy spy = new TextSpy(scratch);
            spy.clipRect(0, 0, w, h);
            card.draw(spy);
            return spy.found;
        } catch (Throwable e) {
            return NONE; // a draw this canvas can't take (hardware bitmaps through a shader); retried later
        }
    }

    private static String[][] resolve(View v) {
        String[][] out = new String[TITLES.length][];
        android.content.res.Resources r = v.getResources();
        String pkg = v.getContext().getPackageName();
        for (int i = 0; i < TITLES.length; i++) {
            java.util.ArrayList<String> names = new java.util.ArrayList<String>();
            java.util.ArrayList<String> all = new java.util.ArrayList<String>(java.util.Arrays.asList(TITLES[i]));
            if (SECTIONS[i] == Settings.HIDE_CARD_CHAT) // the chat card shows one rotating hint, no title
                for (String kind : new String[] {"music", "podcast", "audiobook"})
                    for (int n = 1; n <= 12; n++) { String name = "martini_chat_entry_card_" + kind + "_hint_" + (n < 10 ? "0" : "") + n; all.add(name); all.add(name + "_fallback"); }
            for (String name : all) {
                int id = r.getIdentifier(name, "string", pkg);
                if (id != 0) try { names.add(r.getString(id).trim().toLowerCase()); } catch (RuntimeException ignored) {}
            }
            for (String s : ENGLISH[i]) names.add(s.toLowerCase());
            out[i] = names.toArray(new String[0]);
        }
        return out;
    }

    static int match(String text, String[][] titles) {
        String t = text.trim().toLowerCase();
        if (t.isEmpty()) return NONE;
        // Hints carry the artist's name (%1$s) and long lines are cut with an ellipsis: compare the parts that are certain.
        String cut = t.endsWith("…") ? t.substring(0, t.length() - 1).trim() : null;
        for (int i = 0; i < titles.length; i++)
            for (String title : titles[i]) {
                int arg = title.indexOf('%');
                String fixed = arg >= 0 ? title.substring(0, arg) : title;
                if (t.equals(title) || title.startsWith("let's talk") && t.startsWith(title)) return i;
                if (arg >= 6 && t.startsWith(fixed)) return i;
                if (cut != null && cut.length() >= 8 && (title.startsWith(cut) || fixed.startsWith(cut) || arg >= 6 && cut.startsWith(fixed))) return i;
            }
        return NONE;
    }

    /** Software canvas that records text and skips bitmaps (Spotify's images are hardware bitmaps, which software drawing refuses). */
    static final class TextSpy extends Canvas {
        int found = NONE;
        TextSpy(Bitmap b) { super(b); }
        private void saw(CharSequence s) { if (found == NONE && s != null) found = match(s.toString(), resolved); }
        public void drawText(String s, float x, float y, Paint p) { saw(s); }
        public void drawText(String s, int start, int end, float x, float y, Paint p) { saw(s.substring(start, end)); }
        public void drawText(CharSequence s, int start, int end, float x, float y, Paint p) { saw(s.subSequence(start, end)); }
        public void drawText(char[] s, int index, int count, float x, float y, Paint p) { saw(new String(s, index, count)); }
        public void drawTextRun(char[] s, int index, int count, int ci, int cc, float x, float y, boolean rtl, Paint p) { saw(new String(s, index, count)); }
        public void drawTextRun(CharSequence s, int start, int end, int cs, int ce, float x, float y, boolean rtl, Paint p) { saw(s.subSequence(start, end)); }
        public void drawBitmap(Bitmap b, float x, float y, Paint p) {}
        public void drawBitmap(Bitmap b, Rect src, RectF dst, Paint p) {}
        public void drawBitmap(Bitmap b, Rect src, Rect dst, Paint p) {}
        public void drawBitmap(Bitmap b, Matrix m, Paint p) {}
        public void drawBitmapMesh(Bitmap b, int mw, int mh, float[] verts, int vo, int[] colors, int co, Paint p) {}
    }
}
