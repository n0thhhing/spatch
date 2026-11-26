package spatch;

import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Catalog details from Spotify's anonymous embed session. Playlist membership still comes from the account. */
final class PublicTrackMetadata {
    private static final String TOKEN_URL = "https://open.spotify.com/embed/api/token";
    private static final String LOOKUP_URL = "https://api-partner.spotify.com/pathfinder/v1/query";
    // Spotify Web Player's persisted lookupEntities query used by its track decorator.
    private static final String QUERY = "f952da037440f694cc6925b9e3f649d39077a744c4db7dfba01cb883723f4f77";
    private static final LruCache<String, String[]> TRACKS = new LruCache<>(4096);
    private static String token;
    private static long expiresAt, retryAt;

    public static synchronized byte[] lookup(byte[] packedUris) {
        String[] uris = PackedStrings.unpackStrings(packedUris);
        if (uris == null || uris.length > 100) return new byte[0];

        ArrayList<String> found = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        for (String uri : new LinkedHashSet<>(Arrays.asList(uris))) {
            if (!uri.matches("spotify:track:[0-9A-Za-z]{22}")) continue;
            String[] cached = TRACKS.get(uri);
            if (cached == null) missing.add(uri);
            else found.addAll(Arrays.asList(cached));
        }
        if (!missing.isEmpty() && SystemClock.elapsedRealtime() >= retryAt) {
            try {
                JSONObject request = new JSONObject()
                    .put("variables", new JSONObject().put("uris", new JSONArray(missing)))
                    .put("extensions", new JSONObject().put("persistedQuery",
                        new JSONObject().put("version", 1).put("sha256Hash", QUERY)));
                JSONObject response = getJson(LOOKUP_URL, request, anonymousToken());
                if (response == null) {
                    token = null;
                    response = getJson(LOOKUP_URL, request, anonymousToken());
                }
                JSONObject data = response == null ? null : response.optJSONObject("data");
                JSONArray entries = data == null ? null : data.optJSONArray("lookupEntities");
                if (entries == null) throw new IOException("no catalog data");
                for (int i = 0; i < entries.length(); i++) {
                    String[] track = decodeTrack(entries.optJSONObject(i), missing);
                    if (track == null) continue;
                    TRACKS.put(track[2], track);
                    found.addAll(Arrays.asList(track));
                }
            } catch (IOException | JSONException failed) {
                // Stop requesting pages for a while if the catalog is unavailable or rate-limiting the app.
                retryAt = SystemClock.elapsedRealtime() + 60_000;
                Log.w("SpatchBridge", "Public track lookup failed (" + failed.getClass().getSimpleName() + ")");
            }
        }
        return PackedStrings.packStrings(found.toArray(new String[0]));
    }

    // synchronized: lookup() already serializes on this monitor, and WebAuth calls this directly.
    static synchronized String anonymousToken() throws IOException, JSONException {
        long now = System.currentTimeMillis();
        if (token != null && expiresAt > now + 30_000) return token;
        if (SystemClock.elapsedRealtime() < retryAt) throw new IOException("anonymous token backoff");
        try {
            JSONObject session = getJson(TOKEN_URL, null, null);
            String access = field(session, "accessToken");
            if (session == null || !session.optBoolean("isAnonymous") || access.isEmpty()) {
                throw new IOException("no anonymous catalog session");
            }
            long rawExpiry = session.optLong("accessTokenExpirationTimestampMs");
            expiresAt = rawExpiry > now ? rawExpiry : now + 600_000;
            return token = access;
        } catch (IOException | JSONException failed) {
            retryAt = SystemClock.elapsedRealtime() + 60_000;
            throw failed;
        }
    }

    /** Null is returned only for a 401, so lookup can refresh an expired token once. */
    private static JSONObject getJson(String url, JSONObject body, String access) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4_000);
        connection.setReadTimeout(4_000);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Bearer " + access);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status == 401) return null;
            if (status != 200) throw new IOException("catalog HTTP " + status);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream response = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[8192];
                for (int count; (count = input.read(bytes)) != -1;) {
                    if (response.size() + count > 1024 * 1024) throw new IOException("catalog response too large");
                    response.write(bytes, 0, count);
                }
                return new JSONObject(response.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }

    static String[] decodeTrack(JSONObject entity, Set<String> wanted) {
        String uri = field(entity, "uri");
        if (!wanted.contains(uri)) return null;
        JSONObject typed = entity.optJSONObject("typedEntity");
        JSONObject track = typed == null ? null : typed.optJSONObject("data");
        if (!"Track".equals(field(track, "__typename"))) return null;
        String title = field(track, "name");
        if (title.isEmpty()) return null;
        JSONObject album = track.optJSONObject("albumOfTrack");
        JSONObject art = album == null ? null : album.optJSONObject("coverArt");
        JSONArray images = art == null ? null : art.optJSONArray("sources");
        String image = "";
        int width = 0;
        for (int i = 0; images != null && i < images.length(); i++) {
            JSONObject source = images.optJSONObject(i);
            String url = field(source, "url");
            if (url.startsWith("https://i.scdn.co/image/") && source.optInt("width") >= width) {
                image = url;
                width = source.optInt("width");
            }
        }

        JSONObject credits = track.optJSONObject("artists");
        JSONArray people = credits == null ? null : credits.optJSONArray("items");
        ArrayList<String> artists = new ArrayList<>();
        String artistUri = "";
        for (int i = 0; people != null && i < people.length(); i++) {
            JSONObject artist = people.optJSONObject(i);
            if (artist == null) continue;
            String name = field(artist.optJSONObject("profile"), "name");
            if (name.isEmpty()) continue;
            if (artists.isEmpty()) artistUri = field(artist, "uri");
            artists.add(name);
        }
        JSONObject duration = track.optJSONObject("duration");
        return new String[]{title, android.text.TextUtils.join(", ", artists), uri, image, field(album, "name"),
            field(album, "uri"), artistUri, duration == null ? "" : Long.toString(duration.optLong("totalMilliseconds"))};
    }

    private static String field(JSONObject object, String name) {
        Object value = object == null ? null : object.opt(name);
        return value instanceof String ? (String) value : "";
    }
}
