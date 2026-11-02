package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import androidx.compose.ui.graphics.Shadow;

/** The colours a user can pick for decorations: the theme's accent, the artwork's, or a fixed one. */
public final class Tint {
    static final String[] NAMES = {"accent", "album", "rose", "amber", "mint", "sky", "white"};
    static final int[] SWATCHES = {0, 0, 0xfff2a1b8, 0xffffc777, 0xff8ee6c4, 0xff8fc7ff, 0xfff3eeff};
    private static final float FADE_MILLIS = 900f;
    private static final int HUES = 12;
    private static final float GLOW_BLUR = 12f; // px: Spotify clips each line to its text, so a wide glow shows a box
    public static Shadow lyricGlow;
    private static final Bitmap SAMPLE = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
    private static Drawable source;
    private static int from = 0xffd0bcff, to = 0xffd0bcff;
    private static long changedAt;

    private Tint() {}

    /** Called with the centred page's artwork each frame; samples once per new image. */
    static void follow(Drawable art) {
        if (art == null || art == source) return;
        source = art;
        SAMPLE.eraseColor(0xff14121c);
        PlayerArt.drawArtwork(new Canvas(SAMPLE), art, 0, 0, 12, 12);
        int[] pixels = new int[144];
        SAMPLE.getPixels(pixels, 0, 12, 0, 0, 12, 12);
        from = album();
        to = vivid(pixels);
        changedAt = SystemClock.uptimeMillis();
        glowLyrics();
    }

    /** Spotify's lyric code reads its glow from a field: classes9 has no method references to spare. */
    static void glowLyrics() {
        lyricGlow = Settings.get(Settings.LYRICS_FOCUS) ? new Shadow(GLOW_BLUR, (to & 0xffffffL | 0x80000000L) << 32, 0L) : null;
    }

    /** The artwork's colour, fading from the last song's. */
    static int album() {
        float t = Wave.animationsEnabled() ? Math.min(1f, (SystemClock.uptimeMillis() - changedAt) / FADE_MILLIS) : 1;
        return Pill.blend(from, to, t * t * (3 - 2 * t));
    }

    /** The cover's main hue at its most colourful, lifted so it reads on a dark surface; a grey or black cover gives pale grey. */
    static int vivid(int[] pixels) {
        // Whole hues are weighed so a stray speck of colour can't outvote the cover.
        int[] weight = new int[HUES];
        for (int pixel : pixels) weight[hue(pixel)] += chroma(pixel);
        int main = 0;
        for (int i = 1; i < HUES; i++) if (weight[i] > weight[main]) main = i;
        int best = pixels[0], chroma = -1;
        for (int pixel : pixels) {
            int c = chroma(pixel);
            if (hue(pixel) == main && c > chroma) {
                chroma = c;
                best = pixel;
            }
        }
        int r = best >> 16 & 255, g = best >> 8 & 255, b = best & 255, top = Math.max(r, Math.max(g, b));
        if (top == 0) return 0xffdcdcdc; // an all-black cover would give an invisible glow
        float lift = Math.max(1f, 220f / top);
        return 0xff000000 | Math.min(255, Math.round(r * lift)) << 16 | Math.min(255, Math.round(g * lift)) << 8 | Math.min(255, Math.round(b * lift));
    }

    private static int chroma(int pixel) {
        int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static int hue(int pixel) {
        int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
        double angle = Math.atan2(Math.sqrt(3) * (g - b), 2 * r - g - b) + Math.PI;
        return Math.min(HUES - 1, (int) (angle / (2 * Math.PI) * HUES));
    }

    /** The colour picked in a choice made of NAMES. */
    static int color(int choice) {
        String name = Settings.choice(choice);
        if ("album".equals(name)) return album();
        for (int i = 2; i < NAMES.length; i++) if (NAMES[i].equals(name)) return SWATCHES[i];
        return Palette.color(0xffd0bcff);
    }
}
