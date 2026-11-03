package spatch;

import p.bv61;
import p.i7h;
import p.shc0;
import p.ud5;
import p.w6b1;
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

    private static final int VIVID = 70, GREY_CHROMA = 20;
    private static final java.util.WeakHashMap<Object, Boolean> MADE = new java.util.WeakHashMap<Object, Boolean>();
    private static androidx.compose.ui.graphics.Brush softenedFrom, softened;

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

    /**
     * Page header gradients (yw9 backgrounds and the playlist header's own draw, j060) take the theme:
     * bright server colours are quietened, Spotify's flat greys (black-and-white covers) become a tint of
     * the theme surface, and its fixed blacks the theme background, at their own opacity. A brush made
     * here is never reshaded again, as j060 hands its brushes back in.
     */
    public static androidx.compose.ui.graphics.Brush headerBrush(androidx.compose.ui.graphics.Brush brush) {
        if (!(brush instanceof shc0) || !Settings.get(Settings.SOFT_HEADERS)) return brush;
        if (brush.equals(softenedFrom)) return softened;
        synchronized (MADE) {
            if (MADE.containsKey(brush)) return brush;
        }
        shc0 gradient = (shc0) brush;
        java.util.ArrayList<i7h> colors = new java.util.ArrayList<i7h>();
        boolean changed = false;
        for (Object color : gradient.d) {
            long value = ((i7h) color).a;
            int argb = (int) (value >>> 32);
            int themed = !plain(value) || Palette.ours(argb) ? argb
                : chroma(argb) >= VIVID ? soften(argb)
                : black(argb) ? argb & 0xff000000 | Palette.color(0xff14121c) & 0xffffff
                : grey(argb) ? tintGrey(argb) : argb;
            changed |= themed != argb;
            colors.add(themed == argb ? (i7h) color : new i7h((long) themed << 32));
        }
        softenedFrom = brush;
        softened = changed ? new shc0(gradient.h, gradient.f, gradient.g, colors, gradient.e) : brush;
        synchronized (MADE) {
            MADE.put(softened, Boolean.TRUE);
        }
        return softened;
    }

    static int chroma(int argb) {
        int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    /** Spotify's fixed page blacks, as header gradients and their scrims use them. */
    static boolean black(int argb) {
        int rgb = argb & 0xffffff;
        return rgb == 0x121212 || rgb == 0x181818;
    }

    /** An opaque neutral grey lighter than a scrim, as Spotify picks for a black-and-white cover. */
    static boolean grey(int argb) {
        int max = Math.max(argb >> 16 & 255, Math.max(argb >> 8 & 255, argb & 255));
        return argb >>> 24 == 0xff && chroma(argb) < GREY_CHROMA && max > 0x24 && max < 0xb0;
    }

    static int tintGrey(int argb) {
        return 0xff000000 | Pill.blend(argb, Palette.color(0xff2b2640), 0.6f) & 0xffffff;
    }

    static int soften(int argb) {
        return argb & 0xff000000 | Pill.blend(argb, Palette.color(0xff14121c), 0.68f) & 0xffffff;
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

    /**
     * Your Library rows on their own rounded cards, the cover inset inside: 24dp corners less the 8dp
     * inset matches the cover's own 16dp corners, so the two curves run parallel.
     */
    public static rhm0 libraryRow(rhm0 row) {
        return Flourish.libraryRow(Settings.libraryCards() ? cxn1.D(card(row, 8f, 2f, 0xff1d1a2a, 24f), 8f, 8f) : row);
    }

    public static rhm0 trackRow(rhm0 row) {
        return Flourish.trackRow(Settings.playlistCards() ? card(row, 8f, 3f, 0xff1d1a2a) : row);
    }

    private static rhm0 card(rhm0 row, float sides, float ends, int color) {
        return card(row, sides, ends, color, 20f);
    }

    private static rhm0 card(rhm0 row, float sides, float ends, int color, float radius) {
        vp31 shape = xp31.b(radius);
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

    /** if41's release: everything Encore makes pressable springs back past rest, or settles as stock. */
    public static ud5 pressSpring() {
        if (!Settings.get(Settings.BOUNCY_PRESS) || !Wave.animationsEnabled()) return new w6b1(1f, 1500f, null);
        String bounce = Settings.choice(Settings.BOUNCE);
        return "soft".equals(bounce) ? new w6b1(0.6f, 300f, null) : "wild".equals(bounce) ? new w6b1(0.24f, 520f, null) : new w6b1(0.38f, 420f, null);
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
