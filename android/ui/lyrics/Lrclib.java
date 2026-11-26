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
            body = new String(AutoEqCatalog.readBytes(in, 4 * 1024 * 1024), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException tooLarge) {
            return null;
        } finally {
            c.disconnect();
        }
        return pick(new JSONArray(body), durationMs);
    }

    /** The best match: word-timed, then line-timed, then plain lyrics, then the closest length; none if no length is close enough. */
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
            boolean words = synced && r.optBoolean("hasWordSync") && !r.isNull("lyricsfile");
            long score = (words ? 0 : synced ? 1_000_000L : 2_000_000L) + off;
            if (score < bestScore) { bestScore = score; best = r; }
        }
        if (best == null) return null;
        CoreBridge.Lyrics timed = best.optBoolean("hasWordSync") && !best.isNull("lyricsfile") ? wordSynced(best.optString("lyricsfile", "")) : null;
        if (timed != null && timed.words.length > 0) return timed;
        String synced = best.isNull("syncedLyrics") ? "" : best.optString("syncedLyrics", "");
        CoreBridge.Lyrics out = synced.trim().isEmpty() ? plain(best.optString("plainLyrics", "")) : synced(synced, durationMs);
        return out == null || out.words.length == 0 ? null : out;
    }

    /**
     * LRCLIB's word-timed "lyricsfile": a small YAML document whose `lines:` list holds each line's text, start_ms and
     * end_ms and a `words:` list of {text, start_ms}. Read line by line, since the layout is fixed; anything unexpected
     * returns null and the caller falls back to the line-timed lyrics.
     */
    static CoreBridge.Lyrics wordSynced(String file) {
        ArrayList<String[]> lines = new ArrayList<>(); // text, start, end
        ArrayList<ArrayList<String[]>> words = new ArrayList<>(); // text, start
        String[] word = null;
        boolean inLines = false;
        for (String raw : file.split("\r?\n")) {
            if (!inLines) { inLines = raw.equals("lines:"); continue; }
            if (raw.trim().isEmpty()) continue;
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
            if (indent == 0 && !raw.startsWith("- ")) break; // the next top-level key
            String t = raw.substring(indent);
            boolean item = t.startsWith("- ");
            if (item) t = t.substring(2);
            int colon = t.indexOf(':');
            if (colon <= 0 || (colon + 1 < t.length() && t.charAt(colon + 1) != ' ')) return null;
            String key = t.substring(0, colon), value = unquote(t.substring(colon + 1).trim());
            if (value == null) return null;
            if (item && indent == 0) { lines.add(new String[3]); words.add(new ArrayList<>()); word = null; }
            else if (item) { if (lines.isEmpty()) return null; word = new String[2]; words.get(words.size() - 1).add(word); }
            if (lines.isEmpty()) return null;
            boolean ofWord = word != null && (item ? indent > 0 : indent > 2);
            String[] into = ofWord ? word : lines.get(lines.size() - 1);
            int slot = key.equals("text") ? 0 : key.equals("start_ms") ? 1 : key.equals("end_ms") && !ofWord ? 2 : -1;
            if (slot >= 0) into[slot] = value;
        }
        CoreBridge.Lyrics out = new CoreBridge.Lyrics(lines.size());
        try {
            for (int i = 0; i < lines.size(); i++) {
                String[] l = lines.get(i);
                out.starts[i] = Math.max(1, Long.parseLong(l[1]));
                ArrayList<String[]> ws = words.get(i);
                StringBuilder text = new StringBuilder();
                if (!ws.isEmpty()) {
                    out.wordAt[i] = new int[ws.size()];
                    out.wordStart[i] = new long[ws.size()];
                    for (int w = 0; w < ws.size(); w++) {
                        out.wordAt[i][w] = text.length();
                        out.wordStart[i][w] = Long.parseLong(ws.get(w)[1]);
                        text.append(ws.get(w)[0] == null ? "" : ws.get(w)[0]);
                    }
                }
                // The words, joined, are the line; their offsets index into exactly that text.
                String joined = text.toString().replaceAll("\\s+$", "");
                out.words[i] = ws.isEmpty() ? (l[0] == null ? "" : l[0]) : joined;
                if (out.words[i].isEmpty()) { out.words[i] = "♪"; out.wordAt[i] = null; out.wordStart[i] = null; }
            }
            for (int i = 0; i < lines.size(); i++) {
                String end = lines.get(i)[2];
                out.ends[i] = end != null ? Long.parseLong(end) : i + 1 < lines.size() ? out.starts[i + 1] : out.starts[i] + 5000;
            }
        } catch (NumberFormatException | NullPointerException broken) {
            return null;
        }
        return out;
    }

    /** A YAML scalar: 'single' (with '' for a quote), "double" (common escapes), or plain. Null for a form not handled. */
    private static String unquote(String v) {
        if (v.startsWith("'")) return v.length() >= 2 && v.endsWith("'") ? v.substring(1, v.length() - 1).replace("''", "'") : null;
        if (!v.startsWith("\"")) return v;
        if (v.length() < 2 || !v.endsWith("\"")) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < v.length() - 1; i++) {
            char c = v.charAt(i);
            if (c != '\\' || i + 1 >= v.length() - 1) { out.append(c); continue; }
            char e = v.charAt(++i);
            if (e == 'u' && i + 4 < v.length() - 1) {
                try { out.append((char) Integer.parseInt(v.substring(i + 1, i + 5), 16)); } catch (NumberFormatException bad) { return null; }
                i += 4;
            }
            else out.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
        }
        return out.toString();
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
                stamps.add(Math.max(1L, Long.parseLong(m.group(1)) * 60000 + Math.round(Double.parseDouble(m.group(2).replace(':', '.')) * 1000)));
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
