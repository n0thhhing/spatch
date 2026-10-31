package spatch;

import p.bv61;
import p.cxn1;
import p.evx;
import p.mtg;
import p.rhm0;
import p.vp31;
import p.xp31;
import p.z341;
import p.zyq0;

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

    /** Side drawer rows (x571): tonal rounded surfaces; the clip keeps the row's ripple inside. */
    public static rhm0 drawerRow(rhm0 row) {
        if (!Settings.sideCards()) return row;
        return card(row, 12f, 3f, 0xff2b2640);
    }

    /** Your Library rows on their own rounded cards. */
    public static rhm0 libraryRow(rhm0 row) {
        return Settings.libraryCards() ? card(row, 8f, 3f, 0xff1d1a2a) : row;
    }

    public static rhm0 trackRow(rhm0 row) {
        return Settings.playlistCards() ? card(row, 8f, 3f, 0xff1d1a2a) : row;
    }

    private static rhm0 card(rhm0 row, float sides, float ends, int color) {
        vp31 shape = xp31.b(20f);
        rhm0 inset = cxn1.D(row, sides, ends);
        rhm0 clipped = z341.q(inset, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0L, shape, true, 0L, 0L, 0, null, 0xfe7ff);
        return mtg.n(clipped, (long) Palette.color(color) << 32, shape);
    }

    /** Selected toggle chips are full pills, like the Home filters; Compose eases the colour between them. */
    public static bv61 chipShape(bv61 shape, boolean selected) {
        return selected && Settings.libraryChips() ? xp31.a : shape;
    }

    /** evx.a with mask 47 replaces only the outer padding. */
    public static evx drawerItem(evx item) {
        if (!Settings.sideCards()) return item;
        return evx.a(item, null, 0, null, new zyq0(0f, 0f, 0f, 0f), null, 47);
    }

    /** tsr, Compose's default press overlay: a light veil that reads on any theme surface. */
    public static long pressTint() {
        if (!Settings.softPress()) return 0xff00000000000000L;
        return (long) Palette.color(0xfff3eeff) << 32;
    }

    /** Encore's held-row colour, opaque black in stock: a step above the rows it can sit on. */
    public static long pressed() {
        if (!Settings.softPress()) return 0xff00000000000000L;
        return (long) Pill.blend(Palette.color(0xff2b2640), Palette.color(0xfff3eeff), 0.14f) << 32;
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
