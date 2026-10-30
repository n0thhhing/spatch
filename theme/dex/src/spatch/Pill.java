package spatch;

import android.animation.ValueAnimator;
import java.util.HashMap;
import p.cxn1;
import p.hqr0;
import p.mtg;
import p.n191;
import p.psn0;
import p.rhm0;
import p.xp31;
import p.zpo;

/**
 * Material 3 bottom-navigation destination. Replaces the tab icon's Modifier.size(24.dp) call in
 * p.psn0.a(): every tab has identical geometry and only a 64dp x 32dp indicator differs. Selection
 * runs for about 200 ms on a snapshot state per tab position, so only that tab recomposes, only
 * while it moves; psn0 instances are rebuilt on every recomposition and can't hold it.
 */
public final class Pill implements ValueAnimator.AnimatorUpdateListener {
    private static final int CONTAINER = 0x40d0bcff; // theme accent token at 25% alpha
    private static final float TOP = 12f, BOTTOM = 12f; // with the 15dp label, an 80dp destination
    private static final long MILLIS = 200;
    private static final HashMap<Integer, Pill> TABS = new HashMap<Integer, Pill>();

    private final hqr0 shown;
    private boolean target;
    private ValueAnimator animator;
    private long on, off;

    private Pill(boolean selected) {
        target = selected;
        shown = zpo.q(Float.valueOf(selected ? 1f : 0f));
    }

    private static Pill tab(psn0 item) {
        Pill tab = TABS.get(item.b);
        if (tab == null) {
            tab = new Pill(item.g);
            TABS.put(item.b, tab);
        } else if (tab.target != item.g) {
            tab.select(item.g);
        }
        return tab;
    }

    private void select(boolean selected) {
        target = selected;
        if (animator != null) animator.cancel();
        animator = null;
        float to = selected ? 1f : 0f;
        if (!Wave.animationsEnabled()) {
            shown.setValue(Float.valueOf(to));
            return;
        }
        animator = ValueAnimator.ofFloat(((Float) shown.getValue()).floatValue(), to);
        animator.setDuration(MILLIS);
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

    /** The icon's modifier. cxn1.D = padding(horizontal, vertical); cxn1.G mask 13 = top only. */
    public static rhm0 a(rhm0 modifier, psn0 item) {
        float t = tab(item).progress();
        modifier = rail() ? cxn1.D(modifier, 0f, 3f) : cxn1.G(modifier, 0f, TOP, 0f, 0f, 13);
        // The indicator grows from the icon's centre; the outer and inner padding always sum to 20dp.
        modifier = cxn1.D(modifier, 20f * (1 - t), 0f);
        if (t > 0) {
            int alpha = Math.round((CONTAINER >>> 24) * t);
            long color = (long) (alpha << 24 | CONTAINER & 0xffffff) << 32; // Compose Color: sRGB ARGB, high 32 bits
            modifier = mtg.n(modifier, color, xp31.a); // background(color, pill shape)
        }
        return n191.r(cxn1.D(modifier, 20f * t, 4f), 24f); // n191.r = size(dp), the call this replaces
    }

    /** Icon colour; also remembers the pair for the label, which composes right after the icon. */
    public static long tint(psn0 item, long selected, long unselected) {
        Pill tab = tab(item);
        tab.on = selected;
        tab.off = unselected;
        return mix(unselected, selected, tab.progress());
    }

    public static long labelTint(psn0 item, long fallback) {
        Pill tab = tab(item);
        return tab.on == tab.off ? fallback : mix(tab.off, tab.on, tab.progress());
    }

    public static rhm0 label(rhm0 modifier) {
        return rail() ? modifier : cxn1.G(modifier, 0f, 0f, 0f, BOTTOM, 7);
    }

    /** Landscape hosts these tabs in a side rail: icon and label share a row, so no bar padding. */
    private static boolean rail() {
        if (Settings.app == null) return false;
        android.util.DisplayMetrics screen = Settings.app.getResources().getDisplayMetrics();
        return screen.widthPixels > screen.heightPixels;
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
