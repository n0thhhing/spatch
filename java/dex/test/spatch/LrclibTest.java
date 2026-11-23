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
    }
}
