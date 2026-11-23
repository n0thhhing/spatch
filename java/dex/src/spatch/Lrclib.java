package spatch;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Lyrics from lrclib.net, a free community lyrics database that needs no account or key. Used only when Spotify has
 * none for a song. Search is by title and artist (album names differ too often between services, e.g. "Deluxe"), then
 * the song's length picks the right recording so a live cut or remix with the same name isn't shown.
 */
final class Lrclib {
    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:[.:]\\d+)?)\\]");
    /** How far LRCLIB's length may be from Spotify's and still count as the same recording. */
    private static final long SAME_SONG_MS = 4000;

    private Lrclib() {}

    static CoreBridge.Lyrics find(String title, String artist, long durationMs) throws Exception {
        if (title == null || title.isEmpty()) return null;
        CoreBridge.Lyrics got = search(title, artist, durationMs);
        // Several artists arrive joined ("A, B"); LRCLIB usually files the song under the first.
        int comma = artist == null ? -1 : artist.indexOf(", ");
        return got != null || comma <= 0 ? got : search(title, artist.substring(0, comma), durationMs);
    }

    private static CoreBridge.Lyrics search(String title, String artist, long durationMs) throws Exception {
        String url = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(title, "UTF-8")
            + (artist == null || artist.isEmpty() ? "" : "&artist_name=" + URLEncoder.encode(artist, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Spatch"); // LRCLIB asks clients to name themselves
        String body;
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            for (int n; (n = in.read(buf)) > 0;) {
                if (out.size() + n > 4 * 1024 * 1024) return null;
                out.write(buf, 0, n);
            }
            body = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
        return pick(new JSONArray(body), durationMs);
    }

    /** The best match: synced lyrics before plain ones, then the closest length; none if no length is close enough. */
    static CoreBridge.Lyrics pick(JSONArray results, long durationMs) {
        JSONObject best = null;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || r.optBoolean("instrumental")) continue;
            boolean synced = !r.optString("syncedLyrics", "").trim().isEmpty() && !r.isNull("syncedLyrics");
            boolean plain = !r.optString("plainLyrics", "").trim().isEmpty() && !r.isNull("plainLyrics");
            if (!synced && !plain) continue;
            long off = durationMs > 0 ? Math.abs((long) (r.optDouble("duration", 0) * 1000) - durationMs) : 0;
            if (durationMs > 0 && off > SAME_SONG_MS) continue;
            long score = (synced ? 0 : 1_000_000L) + off;
            if (score < bestScore) { bestScore = score; best = r; }
        }
        if (best == null) return null;
        String synced = best.isNull("syncedLyrics") ? "" : best.optString("syncedLyrics", "");
        CoreBridge.Lyrics out = synced.trim().isEmpty() ? plain(best.optString("plainLyrics", "")) : synced(synced, durationMs);
        return out == null || out.words.length == 0 ? null : out;
    }

    /** "[mm:ss.xx] words" lines; one line may carry several stamps when a chorus repeats. Ends are the next line's start. */
    static CoreBridge.Lyrics synced(String lrc, long durationMs) {
        ArrayList<long[]> times = new ArrayList<>();
        ArrayList<String> words = new ArrayList<>();
        for (String line : lrc.split("\r?\n")) {
            Matcher m = STAMP.matcher(line);
            int textAt = 0;
            ArrayList<Long> stamps = new ArrayList<>();
            while (m.find() && m.start() == textAt) {
                stamps.add(Long.parseLong(m.group(1)) * 60000 + Math.round(Double.parseDouble(m.group(2).replace(':', '.')) * 1000));
                textAt = m.end();
            }
            String text = line.substring(textAt).trim();
            for (long at : stamps) { times.add(new long[] { at, words.size() }); words.add(text.isEmpty() ? "♪" : text); }
        }
        times.sort((a, b) -> Long.compare(a[0], b[0]));
        CoreBridge.Lyrics out = new CoreBridge.Lyrics(times.size());
        for (int i = 0; i < times.size(); i++) {
            out.words[i] = words.get((int) times.get(i)[1]);
            out.starts[i] = times.get(i)[0];
            out.ends[i] = i + 1 < times.size() ? times.get(i + 1)[0] : Math.max(out.starts[i] + 5000, durationMs);
        }
        return out;
    }

    /** Untimed lyrics: one row per line, all times 0, which the screens show as a plain scrolling page. */
    static CoreBridge.Lyrics plain(String text) {
        ArrayList<String> lines = new ArrayList<>();
        for (String line : text.split("\r?\n")) lines.add(line.trim());
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        CoreBridge.Lyrics out = new CoreBridge.Lyrics(lines.size());
        for (int i = 0; i < lines.size(); i++) out.words[i] = lines.get(i);
        return out;
    }
}
