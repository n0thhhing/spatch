package spatch;

import p.S_ComposeShape;
import p.S_ComposeColor;
import p.S_ComposeLinearGradient;
import p.S_ComposeAnimationSpec;
import p.S_ComposeSpringSpec;
import p.S_ComposePadding;
import p.S_SidePanelItemStyle;
import p.S_ComposeBackground;
import p.S_ComposeModifier;
import p.S_ComposeRoundedCornerShape;
import p.S_ComposeShapes;
import p.S_ComposeGraphicsLayer;
import p.S_ComposePaddingSides;

/**
 * Compose surface policy, called from yw9 (every background modifier), S_ComposeShapes.b (rounded shapes) and the
 * Search category tile o6e. Compose colours here are sRGB ARGB in the high 32 bits.
 */
public final class Surfaces {
    private static final int GREY_CARD = 0xff292929, GREY_TILE = 0xff282828, SKELETON = 0x1affffff; // GREY_TILE: Library's "Add artists"-style placeholder tiles

    private static final int VIVID = 70, GREY_CHROMA = 20;
    private static final java.util.WeakHashMap<Object, Boolean> MADE = new java.util.WeakHashMap<Object, Boolean>();
    private static androidx.compose.ui.graphics.Brush softenedFrom, softened;

    private Surfaces() {}

    private static boolean plain(long c) {
        return (c & 0xffffffffL) == 0;
    }

    /** Spotify's fixed grey cards, white skeletons, and page blacks take the theme's palette. */
    public static long bg(long c) {
        if (!plain(c)) return c;
        int argb = (int) (c >>> 32);
        if (argb == GREY_CARD || argb == GREY_TILE) return (long) Palette.color(0xff2b2640) << 32;
        if (argb == SKELETON) return (long) (Palette.color(0xffd0bcff) & 0xffffff | 0x24000000) << 32;
        if (black(argb)) return (long) (argb & 0xff000000 | Palette.color(0xff14121c) & 0xffffff) << 32;
        return c;
    }

    /**
     * Page header gradients (yw9 backgrounds and the playlist header's own draw, j060) take the theme:
     * bright server colours are quietened, Spotify's flat greys (black-and-white covers) become a tint of
     * the theme surface, and its fixed blacks the theme background, at their own opacity. A brush made
     * here is never reshaded again, as j060 hands its brushes back in.
     */
    public static androidx.compose.ui.graphics.Brush headerBrush(androidx.compose.ui.graphics.Brush brush) {
        if (!(brush instanceof S_ComposeLinearGradient) || !Settings.get(Settings.SOFT_HEADERS)) return brush;
        if (brush.equals(softenedFrom)) return softened;
        synchronized (MADE) {
            if (MADE.containsKey(brush)) return brush;
        }
        S_ComposeLinearGradient gradient = (S_ComposeLinearGradient) brush;
        java.util.ArrayList<S_ComposeColor> colors = new java.util.ArrayList<S_ComposeColor>();
        boolean changed = false;
        for (Object color : gradient.d) {
            long value = ((S_ComposeColor) color).a;
            int argb = (int) (value >>> 32);
            int themed = plain(value) ? shade(argb) : argb;
            changed |= themed != argb;
            colors.add(themed == argb ? (S_ComposeColor) color : new S_ComposeColor((long) themed << 32));
        }
        softenedFrom = brush;
        softened = changed ? new S_ComposeLinearGradient(gradient.h, gradient.f, gradient.g, colors, gradient.e) : brush;
        synchronized (MADE) {
            MADE.put(softened, Boolean.TRUE);
        }
        return softened;
    }

    static int chroma(int argb) {
        int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    /** The header policy for one server colour; the theme's own colours are left as they are. */
    static int shade(int argb) {
        return Palette.ours(argb) ? argb
            : chroma(argb) >= VIVID ? soften(argb)
            : black(argb) ? argb & 0xff000000 | Palette.color(0xff14121c) & 0xffffff
            : grey(argb) ? tintGrey(argb) : argb;
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
        return 0xff000000 | Pill.blend(argb, Palette.color(0xff2b2640), 0.45f) & 0xffffff;
    }

    static int soften(int argb) {
        return argb & 0xff000000 | Pill.blend(argb, Palette.color(0xff14121c), 0.38f) & 0xffffff;
    }

    /** Server tile colours keep their hue as a tint on the theme surface, instead of full-saturation panels. */
    public static long tile(long c) {
        if (!plain(c)) return c;
        return (long) Pill.blend(Palette.color(0xff2b2640), (int) (c >>> 32) | 0xff000000, 0.3f) << 32;
    }

    /** Side drawer rows (x571): tonal rounded surfaces; the clip keeps the row's ripple inside. */
    public static S_ComposeModifier drawerRow(S_ComposeModifier row) {
        if (!Settings.sideCards()) return row;
        return card(row, 12f, 2.5f, 0xff2b2640, 16f);
    }

    /**
     * Your Library rows as cards the cover sits evenly in: Spotify's row keeps its 12dp sides and has no
     * vertical padding, so the card adds 10dp above and below; Spotify's own list spacing separates them.
     */
    public static S_ComposeModifier libraryRow(S_ComposeModifier row) {
        if (!Settings.libraryCards()) return Flourish.libraryRow(row);
        // Same row component shows "Liked songs" on artist pages at a fixed height: padding there pushes the cover
        // and its heart badge out of the card and clips them, so only the Library tab gets the inner padding.
        boolean library = NavIndicator.tab() == 2;
        // and that row's content runs past its own height, so there the card doesn't clip
        S_ComposeModifier card = card(row, 8f, 0f, 0xff2b2640, library ? 24f : 16f, library);
        return Flourish.libraryRow(library ? S_ComposePadding.y(card, 0f, 10f) : card);
    }

    /** An artist's "See more" footer sits on the page colour: under it, the row card of its list item would show past Spotify's fade. */
    public static S_ComposeModifier seeMore(S_ComposeModifier footer) {
        if (!Settings.get(Settings.LIST_CARDS)) return footer;
        return S_ComposeBackground.f(footer, (long) Palette.color(0xff14121c) << 32, S_ComposeShapes.b(0f));
    }

    /**
     * Playlist track rows as floating pill cards with 4dp vertical spacing, avoiding concave notches between tracks.
     */
    public static S_ComposeModifier trackRow(S_ComposeModifier row) {
        return Flourish.trackRow(Settings.playlistCards() ? S_ComposePadding.y(card(row, 6f, 1.5f, 0xff2b2640, 14f), 8f, 2f) : row);
    }

    private static S_ComposeModifier card(S_ComposeModifier row, float sides, float ends, int color) {
        return card(row, sides, ends, color, 20f);
    }

    private static S_ComposeModifier card(S_ComposeModifier row, float sides, float ends, int color, float radius) {
        return card(row, sides, ends, color, radius, true);
    }

    private static S_ComposeModifier card(S_ComposeModifier row, float sides, float ends, int color, float radius, boolean clip) {
        S_ComposeRoundedCornerShape shape = S_ComposeShapes.b(radius);
        S_ComposeModifier inset = S_ComposePadding.y(row, sides, ends);
        S_ComposeModifier clipped = S_ComposeGraphicsLayer.D(inset, 1f, 1f, 1f, 0f, 0f, 0f, 0f, 0L, shape, clip, 0L, 0L, 0, null, 0xfe7ff);
        return S_ComposeBackground.f(clipped, (long) Palette.color(color) << 32, shape);
    }

    /** Spotify's Settings page links sit on cards like Spatch's own rows. */
    public static S_ComposeModifier settingsRow(S_ComposeModifier row) {
        if (!Settings.get(Settings.SPOTIFY_SETTINGS)) return row;
        return card(row, 12f, 3f, 0xff2b2640, 22f);
    }

    /** Their icons sit in a tonal circle, as Spatch's settings icons do. */
    public static S_ComposeModifier settingsIcon(S_ComposeModifier icon) {
        if (!Settings.get(Settings.SPOTIFY_SETTINGS)) return icon;
        int tile = Pill.blend(Palette.color(0xff2b2640), Palette.color(0xffd0bcff), 0.16f);
        return S_ComposePadding.y(S_ComposeBackground.f(icon, (long) tile << 32, S_ComposeShapes.a), 8f, 8f);
    }

    /** Toggle chips are full pills across the entire row, matching Home filters. */
    public static S_ComposeShape chipShape(S_ComposeShape shape, boolean selected) {
        return Settings.libraryChips() ? S_ComposeShapes.a : shape;
    }

    /** S_SidePanelItemStyle.a with mask 47 replaces only the outer padding. */
    public static S_SidePanelItemStyle drawerItem(S_SidePanelItemStyle item) {
        if (!Settings.sideCards()) return item;
        return S_SidePanelItemStyle.a(item, null, 0, null, new S_ComposePaddingSides(0f, 0f, 0f, 0f), null, null, 47 | 0x100); // 0x100 keeps the link style
    }

    /** if41's release: everything Encore makes pressable springs back past rest, or settles as stock. */
    public static S_ComposeAnimationSpec pressSpring() {
        if (!Settings.get(Settings.BOUNCY_PRESS) || !Wave.animationsEnabled()) return new S_ComposeSpringSpec(1f, 1500f, null);
        String bounce = Settings.choice(Settings.BOUNCE);
        return "soft".equals(bounce) ? new S_ComposeSpringSpec(0.6f, 300f, null) : "wild".equals(bounce) ? new S_ComposeSpringSpec(0.24f, 520f, null) : new S_ComposeSpringSpec(0.38f, 420f, null);
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
