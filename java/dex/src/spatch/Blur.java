package spatch;

import android.graphics.Bitmap;

/** A smooth blur of a cover: shrunk to a small bitmap and box-blurred three times (close to a Gaussian), cheap at this size. */
final class Blur {
    private Blur() {}

    static Bitmap soft(Bitmap src, int w, int h, int radius) {
        // Shrinking in two steps keeps the result from aliasing.
        Bitmap small = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src, w * 4, h * 4, true), w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        blur(px, w, h, radius);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** Three box blurs (horizontal then vertical) over ARGB pixels in place: close to a Gaussian of the same reach. */
    static void blur(int[] px, int w, int h, int radius) {
        for (int pass = 0; pass < 3; pass++) {
            box(px, w, h, radius, true);
            box(px, w, h, radius, false);
        }
    }

    /** One box-blur pass along every row (horizontal) or column, with the edges clamped. */
    private static void box(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w, span = 2 * r + 1;
        int[] line = new int[len];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int a = 0, red = 0, green = 0, blue = 0;
            for (int k = -r; k <= r; k++) {
                int c = line[Math.max(0, Math.min(len - 1, k))];
                a += c >>> 24; red += (c >> 16) & 255; green += (c >> 8) & 255; blue += c & 255;
            }
            for (int i = 0; i < len; i++) {
                int out = ((a / span) << 24) | ((red / span) << 16) | ((green / span) << 8) | (blue / span);
                px[horizontal ? l * w + i : i * w + l] = out;
                int gone = line[Math.max(0, i - r)], come = line[Math.min(len - 1, i + r + 1)];
                a += (come >>> 24) - (gone >>> 24);
                red += ((come >> 16) & 255) - ((gone >> 16) & 255);
                green += ((come >> 8) & 255) - ((gone >> 8) & 255);
                blue += (come & 255) - (gone & 255);
            }
        }
    }
}
