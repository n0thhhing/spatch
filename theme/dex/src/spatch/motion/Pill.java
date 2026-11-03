package spatch;

import android.animation.ValueAnimator;
import android.view.animation.OvershootInterpolator;
import java.util.HashMap;
import p.S_ComposeRotate;
import p.S_ComposePadding;
import p.S_ComposeMutableState;
import p.S_ComposeBackground;
import p.S_ComposeSize;
import p.S_NavBarTabState;
import p.S_ComposeModifier;
import p.S_ComposeShapes;
import p.S_ComposeGraphicsLayer;
import p.S_ComposeSnapshot;

/**
 * Floating, icon-only bottom-navigation destination. Replaces the tab icon's Modifier.size(24.dp) call
 * in p.S_NavBarTabState.a(): every tab has identical geometry and only a 64dp x 32dp indicator differs. Selection
 * springs on a snapshot state per tab position, so only that tab recomposes, only while it moves;
 * S_NavBarTabState instances are rebuilt on every recomposition and can't hold it.
 */
public final class Pill implements ValueAnimator.AnimatorUpdateListener {
    private static int container() { return 0x40d0bcff; } // theme accent token at 25% alpha
    private static final float TOP = 10f, BOTTOM = 15f; // with k1u.d's 4dp and the indicator, a 64dp bar
    private static final long MILLIS = 320;
    private static final float POP = 0.1f; // the selected icon sits this much larger
    private static final HashMap<Integer, Pill> TABS = new HashMap<Integer, Pill>();
    private static final float CLOSE_PILL = 32f;
    private static final S_ComposeMutableState CLOSE_ANGLE = S_ComposeSnapshot.q(Float.valueOf(0f));

    private final S_ComposeMutableState shown;
    private final int position;
    private boolean target;
    private ValueAnimator animator;
    private long on, off;

    private Pill(int tab, boolean selected) {
        position = tab;
        target = selected;
        if (selected) NavIndicator.select(tab);
        shown = S_ComposeSnapshot.q(Float.valueOf(selected ? 1f : 0f));
    }

    private static Pill tab(S_NavBarTabState item) {
        Pill tab = TABS.get(item.b);
        if (tab == null) {
            tab = new Pill(Content.visualTab(item.b), item.g);
            TABS.put(item.b, tab);
        } else if (tab.target != item.g) {
            tab.select(item.g);
        }
        return tab;
    }

    private void select(boolean selected) {
        target = selected;
        if (selected) NavIndicator.select(position);
        if (animator != null) animator.cancel();
        animator = null;
        float to = selected ? 1f : 0f;
        if (!Wave.animationsEnabled()) {
            shown.setValue(Float.valueOf(to));
            return;
        }
        animator = ValueAnimator.ofFloat(((Float) shown.getValue()).floatValue(), to);
        animator.setDuration(MILLIS);
        animator.setInterpolator(new OvershootInterpolator(2f));
        animator.addUpdateListener(this);
        animator.start();
    }

    @Override
    public void onAnimationUpdate(ValueAnimator a) {
        shown.setValue(a.getAnimatedValue());
    }

    private float progress() {
        return ((Float) shown.getValue()).floatValue();
    }

    /** The icon's modifier. S_ComposePadding.D = padding(horizontal, vertical); S_ComposePadding.G masks 13/7 = top/bottom only. */
    public static S_ComposeModifier a(S_ComposeModifier modifier, S_NavBarTabState item) {
        float spring = tab(item).progress(), t = Math.max(0f, Math.min(1f, spring));
        modifier = rail() ? S_ComposePadding.D(modifier, 0f, 3f) : S_ComposePadding.G(S_ComposePadding.G(modifier, 0f, TOP, 0f, 0f, 13), 0f, 0f, 0f, BOTTOM, 7);
        // The indicator grows from the icon's centre; the outer and inner padding always sum to 20dp.
        modifier = S_ComposePadding.D(modifier, 20f * (1 - t), 0f);
        if (t > 0 && rail()) { // portrait: NavIndicator draws the moving indicator
            int alpha = Math.round((container() >>> 24) * t);
            long color = (long) (alpha << 24 | container() & 0xffffff) << 32; // Compose Color: sRGB ARGB, high 32 bits
            modifier = S_ComposeBackground.n(modifier, color, S_ComposeShapes.a); // background(color, pill shape)
        }
        float scale = Settings.navPop() ? 1 + POP * spring : 1;
        // graphicsLayer(scaleX, scaleY); the mask leaves every other parameter at its default.
        modifier = S_ComposeGraphicsLayer.q(S_ComposePadding.D(modifier, 20f * t, 4f), scale, scale, 0f, 0f, 0f, 0f, 0f, 0L, null, false, 0L, 0L, 0, null, 1048572);
        if (Content.visualTab(item.b) == item.c - 1) modifier = S_ComposeRotate.t(modifier, ((Float) CLOSE_ANGLE.getValue()).floatValue()); // Create, the last tab shown
        return S_ComposeSize.r(modifier, 24f); // S_ComposeSize.r = size(dp), the call this replaces
    }

    /** jub's Create-open disc becomes a tonal pill behind the icon; it tops out at the indicator's top. */
    public static float closeDiscTop(float top) {
        return top + (rail() ? 3f : TOP);
    }

    public static S_ComposeModifier closePill(S_ComposeModifier modifier, float open) {
        return S_ComposeSize.s(modifier, CLOSE_PILL * (1 + open), CLOSE_PILL);
    }

    public static long closeColor() {
        return (long) container() << 32;
    }

    /** jub rotates the whole padded tab, off the icon's centre; a() turns just the icon instead. */
    public static float closeAngle(float degrees) {
        if (degrees != ((Float) CLOSE_ANGLE.getValue()).floatValue()) CLOSE_ANGLE.setValue(Float.valueOf(degrees));
        return 0f;
    }

    public static void closeRest() {
        closeAngle(0f);
    }

    /** Icon colour; also remembers the pair for the label, which composes right after the icon. */
    public static long tint(S_NavBarTabState item, long selected, long unselected) {
        Pill tab = tab(item);
        tab.on = selected;
        tab.off = unselected;
        return mix(unselected, selected, tab.progress());
    }

    public static long labelTint(S_NavBarTabState item, long fallback) {
        Pill tab = tab(item);
        return tab.on == tab.off ? fallback : mix(tab.off, tab.on, tab.progress());
    }

    /** Landscape hosts these tabs in a side rail: icon and label share a row. */
    private static boolean rail() {
        if (Settings.app == null) return false;
        android.util.DisplayMetrics screen = Settings.app.getResources().getDisplayMetrics();
        return screen.widthPixels > screen.heightPixels;
    }

    /** Interpolates ARGB view colours. */
    static int blend(int from, int to, float t) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int x = from >>> shift & 255, y = to >>> shift & 255;
            out |= Math.round(x + (y - x) * t) << shift;
        }
        return out;
    }

    /** Interpolates sRGB Compose colours; anything else (non-zero colour-space bits) just switches. */
    static long mix(long from, long to, float t) {
        if (t <= 0) return from;
        if (t >= 1 || ((from | to) & 0x3f) != 0) return to;
        int a = (int) (from >>> 32), b = (int) (to >>> 32), out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int x = a >>> shift & 255, y = b >>> shift & 255;
            out |= Math.round(x + (y - x) * t) << shift;
        }
        return (long) out << 32;
    }
}
