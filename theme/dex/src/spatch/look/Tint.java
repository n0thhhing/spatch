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
        follow(vivid(pixels));
    }

    /** Artwork from the session reaches the mini player even when the full player is closed. */
    static void follow(int color) {
        if (color == to) return;
        from = album();
        to = color;
        changedAt = SystemClock.uptimeMillis();
        glowLyrics();
    }

    /** The artwork last followed: a new one means a new song for whoever is watching. */
    static Object artwork() { return source; }

    static boolean fading() {
        return Wave.animationsEnabled() && from != to && SystemClock.uptimeMillis() - changedAt < FADE_MILLIS;
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

    /**
     * A theme colour for the player and immersive player: with Artwork colours on, the theme's roles are
     * rebuilt around the cover's colour (surfaces deep in its hue, text faintly tinted, the accent itself).
     * Literals reach here already themed (the patcher wraps them), so the colour is matched by its role.
     */
    static int player(int token) {
        int role = Settings.get(Settings.ART_THEME) ? Palette.role(token) : -1;
        return role < 0 ? Palette.color(token) : token & 0xff000000 | artRole(role, album(), Palette.isLight()) & 0xffffff;
    }

    /** One of the theme's nine roles (Palette's order: background, surface, card, text, ..., accent) made from art. */
    static int artRole(int role, int art, boolean light) {
        int ground = light ? 0xffffffff : 0xff000000, ink = light ? 0xff000000 : 0xffffffff;
        if (light) art = Pill.blend(art, 0xff000000, .35f); // the lifted cover colour is too pale to read on white
        switch (role) {
            case 0: return Pill.blend(ground, art, .12f); // background
            case 1: return Pill.blend(ground, art, .18f); // raised surface
            case 2: return Pill.blend(ground, art, .26f); // card
            case 3: return Pill.blend(ink, art, .08f); // text
            case 4: return Pill.blend(art, ink, .2f);
            case 5: return Pill.blend(Pill.blend(ink, ground, .25f), art, .3f); // secondary text
            case 6: return art; // accent
            case 7: return Pill.blend(art, ink, .3f);
            default: return Pill.blend(art, ground, .15f);
        }
    }

    /** The colour picked in a choice made of NAMES. */
    static int color(int choice) {
        String name = Settings.choice(choice);
        if ("album".equals(name)) return album();
        for (int i = 2; i < NAMES.length; i++) if (NAMES[i].equals(name)) return SWATCHES[i];
        boolean player = choice == Settings.PLAYER_COLOR || choice == Settings.RING_COLOR || choice == Settings.GLOW_COLOR;
        return player ? player(0xffd0bcff) : Palette.color(0xffd0bcff);
    }
}
