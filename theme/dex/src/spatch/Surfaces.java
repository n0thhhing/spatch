package spatch;

/**
 * Compose surface policy, called from yw9 (every background modifier), xp31.b (rounded shapes) and the
 * Search category tile o6e. Compose colours here are sRGB ARGB in the high 32 bits.
 */
public final class Surfaces {
    private static final int GREY_CARD = 0xff292929, SKELETON = 0x1affffff;

    private Surfaces() {}

    private static boolean plain(long c) {
        return (c & 0xffffffffL) == 0;
    }

    /** Spotify's fixed grey cards and white skeletons take the theme's surface and accent. */
    public static long bg(long c) {
        if (!plain(c)) return c;
        int argb = (int) (c >>> 32);
        if (argb == GREY_CARD) return (long) Palette.color(0xff2b2640) << 32;
        if (argb == SKELETON) return (long) (Palette.color(0xffd0bcff) & 0xffffff | 0x24000000) << 32;
        return c;
    }

    /** Server tile colours keep their hue as a tint on the theme surface, instead of full-saturation panels. */
    public static long tile(long c) {
        if (!plain(c)) return c;
        return (long) Pill.blend(Palette.color(0xff2b2640), (int) (c >>> 32) | 0xff000000, 0.3f) << 32;
    }

    /** Material 3 corner scale for Encore's small radii; 0 and pills are left alone. */
    public static float radius(float dp) {
        if (dp <= 0) return dp;
        if (dp <= 2) return dp; // badges and hairlines: larger radii clip their glyphs
        if (dp <= 6) return 8;
        if (dp <= 8) return 12;
        return dp <= 16 ? 16 : dp;
    }
}
