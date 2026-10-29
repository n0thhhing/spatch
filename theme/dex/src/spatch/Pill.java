package spatch;

import p.cxn1;
import p.mtg;
import p.n191;
import p.rhm0;
import p.xp31;

/**
 * Swiftify-style selected-tab indicator. Replaces the tab icon's Modifier.size(24.dp) call in
 * p.psn0.a(): every tab icon gets the same 3dp outer margin and 20dp x 4dp inner padding so labels stay
 * aligned; only the selected one paints the pill (accent at 25% alpha), a 64dp x 32dp indicator.
 */
public final class Pill {
    private static final int CONTAINER = 0x40d0bcff; // theme accent token at 25% alpha

    private Pill() {}

    public static rhm0 a(rhm0 modifier, boolean selected) {
        // cxn1.D = padding(horizontal, vertical); mtg.n = background(color, shape); xp31.a = 50% rounded
        // (pill) shape; Compose Color = sRGB ARGB in the high 32 bits (as y2p1.d builds it).
        modifier = cxn1.D(modifier, 0f, 3f);
        if (selected) {
            modifier = mtg.n(modifier, ((long) CONTAINER) << 32, xp31.a);
        }
        return n191.r(cxn1.D(modifier, 20f, 4f), 24f); // n191.r = size(dp), the call this replaces
    }
}
