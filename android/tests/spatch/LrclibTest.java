package spatch;

import org.json.JSONArray;
import org.json.JSONObject;

/** Runs on Android (Robolectric), where org.json has its platform implementation. */
public final class LrclibTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        CoreBridge.Lyrics l = Lrclib.synced("[00:01.50] One\n[00:05.00][00:20.00] Chorus\n[00:10.25]\nno stamp here", 30000);
        check(l.words.length == 4, "One row per stamp, unstamped lines dropped");
        check(l.starts[0] == 1500 && l.words[0].equals("One"), "Stamps become ms");
        check(l.words[1].equals("Chorus") && l.words[3].equals("Chorus") && l.starts[3] == 20000, "Repeated chorus sorted into place");
        check(l.words[2].equals("♪") && l.ends[1] == 10250, "Empty line is a gap; ends are the next start");
        check(l.ends[3] == 30000, "Last line runs to the song's end");

        CoreBridge.Lyrics p = Lrclib.plain("a\n\nb\n\n");
        check(p.words.length == 3 && p.starts[2] == 0, "Plain lyrics keep inner blanks, drop trailing ones, no times");

        JSONArray results = new JSONArray()
            .put(new JSONObject().put("duration", 200).put("syncedLyrics", "[00:01.00] live cut").put("plainLyrics", "live cut"))
            .put(new JSONObject().put("duration", 180).put("syncedLyrics", JSONObject.NULL).put("plainLyrics", "plain"))
            .put(new JSONObject().put("duration", 181).put("syncedLyrics", "[00:01.00] studio").put("plainLyrics", "studio"))
            .put(new JSONObject().put("duration", 180).put("instrumental", true));
        check(Lrclib.pick(results, 180000).words[0].equals("studio"), "Synced within the length window beats plain and far-off cuts");
        check(Lrclib.pick(results, 120000) == null, "No recording near the song's length: no lyrics");
        check(Lrclib.pick(new JSONArray().put(results.get(1)), 180000).words[0].equals("plain"), "Plain when nothing is synced");

        String file = "version: '1.0'\nmetadata:\n  title: T\n  album: '2022: a ''live'' one'\nlines:\n"
            + "- text: I wear it\n  start_ms: 24280\n  end_ms: 25734\n  words:\n  - text: 'I '\n    start_ms: 24280\n  - text: 'wear '\n    start_ms: 24455\n  - text: it\n    start_ms: 25048\n"
            + "- text: \"Say \\\"hi\\\"\"\n  start_ms: 26950\n  words:\n  - text: \"Say \"\n    start_ms: 26950\n  - text: \"\\\"hi\\\"\"\n    start_ms: 27000\n";
        CoreBridge.Lyrics w = Lrclib.wordSynced(file);
        check(w != null && w.words.length == 2, "Two word-timed lines");
        check(w.words[0].equals("I wear it") && w.starts[0] == 24280 && w.ends[0] == 25734, "Line text and times");
        check(w.wordAt[0][1] == 2 && w.wordStart[0][2] == 25048, "Word offsets index the joined line");
        check(w.words[1].equals("Say \"hi\"") && w.ends[1] == 31950, "Double-quoted escapes; missing end falls back");
        check(PlayerScreen.sungUpTo(w, 0, 24300) == 2 && PlayerScreen.sungUpTo(w, 0, 24000) == 0 && PlayerScreen.sungUpTo(w, 0, 26000) == 9, "Sung part grows word by word");
        check(Lrclib.wordSynced("lines:\n- text: a\n  start_ms: soon\n") == null, "Bad numbers fall back to line timing");

        JSONArray both = new JSONArray()
            .put(new JSONObject().put("duration", 180).put("syncedLyrics", "[00:01.00] lines").put("plainLyrics", "lines"))
            .put(new JSONObject().put("duration", 182).put("hasWordSync", true).put("syncedLyrics", "[00:24.28] I wear it").put("plainLyrics", "x").put("lyricsfile", file));
        check(Lrclib.pick(both, 180000).wordAt[0] != null, "Word-timed match preferred over a closer line-timed one");

        java.util.List<long[]> ranges = java.util.Arrays.asList(new long[] { 0, 28000 }, new long[] { 20000, 50000 }, new long[] { 90000, 100000 });
        check(LyricGen.coveredMs(ranges) == 60000, "Overlapping listens count once");
        java.util.TreeMap<Long, String[]> heard = new java.util.TreeMap<>();
        heard.put(0L, new String[] { "2000", "first" });
        heard.put(5000L, new String[] { "7000", "second" });
        CoreBridge.Lyrics made = LyricGen.toLyrics(heard);
        check(made.generated && made.words.length == 2 && made.starts[0] == 1 && made.starts[1] == 5000, "Generated lines stay time-synced, even at 0");
    }
}
