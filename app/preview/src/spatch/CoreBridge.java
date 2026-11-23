package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/** Offline art for the separately packaged preview. */
final class CoreBridge {
    // PreviewActivity has already initialized the sample core.
    static void nativeInit() {}
    static void resetSession() {}
    static String diagnostics() { return "Offline preview"; }
    // The preview nativeCatalog adapter reads this query and uses the existing Rust demo data.
    static byte[] search(String query, String pageToken) { return query.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    /** Demo lyrics for the preview, spread over a whole song with times so the synced view can be tried offline. */
    public static final class Lyrics {
        private static final String[] SAMPLE = { "Demo lyrics for the preview", "Each line lights as it is sung", "The column glides to keep it centred", "Tap a line to jump there", "♪", "Dim lines melt into the edges", "The glow follows the cover", "Take it for a spin" };
        public final String[] words;
        public final long[] starts, ends;
        public final int[][] wordAt;
        public final long[][] wordStart;
        Lyrics(int n) { words = new String[n]; starts = new long[n]; ends = new long[n]; wordAt = new int[n][]; wordStart = new long[n][]; }
        boolean wordSynced() { for (int[] w : wordAt) if (w != null) return true; return false; }
        Lyrics() {
            this(40);
            for (int i = 0; i < words.length; i++) {
                words[i] = SAMPLE[i % SAMPLE.length]; starts[i] = 3000L + i * 6000L; ends[i] = starts[i] + 5500L;
                String[] split = words[i].split(" "); // every word timed, spread over the first four seconds, to try the word highlight
                wordAt[i] = new int[split.length]; wordStart[i] = new long[split.length];
                for (int w = 0, at = 0; w < split.length; at += split[w].length() + 1, w++) { wordAt[i][w] = at; wordStart[i][w] = starts[i] + w * 4000L / split.length; }
            }
        }
    }

    static Lyrics lyrics(String id, String title, String artist, long durationMs) { return new Lyrics(); }
    static volatile android.content.SharedPreferences prefs;
    static volatile java.io.File lyricsDir;

    public static String openCreation(android.app.Activity activity, String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "collaborative":
            case "mixed":
            case "blend":
            case "prompted":
            case "jam":
                return "This Spotify creation flow needs the signed-in Android Spotify app.";
            default:
                return "Unsupported creation flow: " + (normalized.isEmpty() ? "unknown" : kind);
        }
    }

    public static String openLogin(android.app.Activity activity) {
        return "Spotify login runs inside the Android Spotify app.";
    }

    public static String openSpotifyLogin(android.app.Activity activity) {
        return "Spotify isn’t part of the preview";
    }

    public static String openNativeSpotify(android.app.Activity activity) {
        return "Native Spotify UI is only available in the Android Spotify app.";
    }

    static volatile java.io.File cacheDir;

    static void pruneCache() {}

    static Bitmap artwork(String url, int size) {
        if (!url.startsWith("demo:")) return null;
        int index = Integer.parseInt(url.substring(5));
        float hue = (190 + 137 * index) % 360;
        int first = Color.HSVToColor(new float[]{hue, .76f, .91f});
        int last = Color.HSVToColor(new float[]{(hue + 58) % 360, .66f, .24f});
        Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, size, size, first, last, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, size, size, paint);
        paint.setShader(null);
        paint.setColor(0x66ffffff);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * .0035f);
        for (int ring = 1; ring <= 25; ring++) {
            float fraction = ring / 25f;
            float radius = size * (.07f + fraction * .47f);
            float x = size * (.33f + fraction * .29f);
            float y = size * (.63f - fraction * .27f);
            canvas.drawCircle(x, y, radius, paint);
        }
        return image;
    }
}
