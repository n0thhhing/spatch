package spatch;

import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;

/** Collapses only the inline lyric slot; Spotify still owns its visibility and lyric availability. */
final class PlayerLyrics implements Runnable {
    private static final PlayerLyrics CURRENT = new PlayerLyrics();
    private WeakReference<View> root = new WeakReference<View>(null);
    private WeakReference<View> slot = new WeakReference<View>(null);
    private boolean hidden, clipped;
    private int height, accessibility;
    private ViewGroup.LayoutParams savedParams;

    static void bind(View root) {
        if (root != CURRENT.root.get()) CURRENT.root = new WeakReference<View>(root);
        CURRENT.run();
    }

    static void changed() {
        View view = CURRENT.root.get();
        if (view != null) view.post(CURRENT);
    }

    public void run() {
        View owner = root.get();
        if (owner == null) return;
        View view = owner.findViewById(R_id_lyrics_element);
        if (view != slot.get()) {
            restore();
            slot = new WeakReference<View>(view);
        }
        if (view == null || view.getLayoutParams() == null) return;
        if (!Settings.get(Settings.HIDE_PLAYER_LYRICS)) { restore(); return; }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!hidden) {
            height = params.height;
            accessibility = view.getImportantForAccessibility();
            clipped = view instanceof ViewGroup && ((ViewGroup)view).getClipChildren();
            hidden = true;
        }
        savedParams = params;
        if (params.height != 0) {
            height = params.height; // keep any new size Spotify asks for while the slot is hidden
            params.height = 0;
            view.setLayoutParams(params);
        }
        if (view instanceof ViewGroup) ((ViewGroup)view).setClipChildren(true);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private void restore() {
        View view = slot.get();
        if (hidden && savedParams != null && savedParams.height == 0) savedParams.height = height;
        if (hidden && view != null) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params == savedParams) view.setLayoutParams(params);
            view.setImportantForAccessibility(accessibility);
            if (view instanceof ViewGroup) ((ViewGroup)view).setClipChildren(clipped);
        }
        hidden = false;
        savedParams = null;
    }
}
