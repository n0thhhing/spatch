package spatch;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs on Android, where org.json and LruCache have their platform implementations. */
public final class PublicTrackMetadataTest {
    private static final String URI = "spotify:track:266Vb7ItE3o1q8HdJ9QOvn";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        JSONArray images = new JSONArray()
            .put(new JSONObject().put("width", 100).put("url", "https://i.scdn.co/image/small"))
            .put(new JSONObject().put("width", 640).put("url", "https://i.scdn.co/image/large"))
            .put(new JSONObject().put("width", 900).put("url", "https://unexpected.example/art"));
        JSONArray artists = new JSONArray()
            .put(JSONObject.NULL)
            .put(new JSONObject().put("uri", "artist").put("profile", new JSONObject().put("name", "Mother Mother")))
            .put(new JSONObject().put("profile", new JSONObject().put("name", "Another")));
        JSONObject album = new JSONObject().put("name", "Touch Up").put("uri", "album")
            .put("coverArt", new JSONObject().put("sources", images));
        JSONObject track = new JSONObject().put("__typename", "Track").put("uri", "relinked").put("name", "Verbatim")
            .put("albumOfTrack", album).put("artists", new JSONObject().put("items", artists))
            .put("duration", new JSONObject().put("totalMilliseconds", 168000));
        JSONObject entity = new JSONObject().put("uri", URI).put("typedEntity", new JSONObject().put("data", track));

        check(PublicTrackMetadata.decodeTrack(null, Collections.singleton(URI)) == null, "Ignore null entries");
        check(PublicTrackMetadata.decodeTrack(entity, Collections.singleton("foreign")) == null, "Ignore unrequested entities");
        check(PublicTrackMetadata.decodeTrack(new JSONObject().put("uri", URI), Collections.singleton(URI)) == null, "Ignore missing tracks");
        String[] decoded = PublicTrackMetadata.decodeTrack(entity, Collections.singleton(URI));
        check(Arrays.equals(decoded, new String[]{"Verbatim", "Mother Mother, Another", URI,
            "https://i.scdn.co/image/large", "Touch Up", "album", "artist", "168000"}), "Keep original link and largest Spotify cover");
        System.out.println("PublicTrackMetadataTest: all checks passed");
    }
}
