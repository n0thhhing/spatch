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

    /**
     * Soft glowing halo of `src`: center-crops `src` into the inner `(w - 2*pad) x (h - 2*pad)` area of a transparent
     * `w x h` bitmap, blurs both colour and alpha outward into the margin, and un-premultiplies RGB so the fade stays vivid.
     */
    static Bitmap halo(Bitmap src, int w, int h, int pad, int radius) {
        int iw = Math.max(4, w - 2 * pad), ih = Math.max(4, h - 2 * pad);
        Bitmap cropped = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cc = new android.graphics.Canvas(cropped);
        float k = Math.max((float) iw / Math.max(1, src.getWidth()), (float) ih / Math.max(1, src.getHeight()));
        float dw = src.getWidth() * k, dh = src.getHeight() * k;
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        cc.drawBitmap(src, null, new android.graphics.RectF((iw - dw) / 2f, (ih - dh) / 2f, (iw + dw) / 2f, (ih + dh) / 2f), p);
        int[] srcPx = new int[iw * ih];
        cropped.getPixels(srcPx, 0, iw, 0, 0, iw, ih);
        int[] px = new int[w * h];
        for (int y = 0; y < ih; y++) {
            System.arraycopy(srcPx, y * iw, px, (y + pad) * w + pad, iw);
        }
        blur(px, w, h, radius);
        for (int i = 0; i < px.length; i++) {
            int c = px[i], a = c >>> 24;
            if (a == 0) { px[i] = 0; continue; }
            int r = Math.min(255, (((c >> 16) & 255) * 255) / a);
            int g = Math.min(255, (((c >> 8) & 255) * 255) / a);
            int b = Math.min(255, ((c & 255) * 255) / a);
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
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
