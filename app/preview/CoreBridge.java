package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/** Offline art for the separately packaged preview. */
final class CoreBridge {
    static String diagnostics() { return "Offline preview"; }
    // The preview nativeCatalog adapter reads this query and uses the existing Rust demo data.
    static byte[] search(String query, String pageToken) { return query.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

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
