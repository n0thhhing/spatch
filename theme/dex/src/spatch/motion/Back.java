package spatch;

import android.view.View;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * Back closes an overlay on API 33+, where apps targeting API 36 never see KEYCODE_BACK. Its own
 * class so older devices never load the missing interface (callers check the SDK level first).
 */
final class Back implements OnBackInvokedCallback {
    private final Runnable close;
    private final OnBackInvokedDispatcher dispatcher;

    private Back(Runnable close, OnBackInvokedDispatcher dispatcher) {
        this.close = close;
        this.dispatcher = dispatcher;
    }

    /** Call once the overlay is attached; null if there is no dispatcher. */
    static Back register(View overlay, Runnable close) {
        OnBackInvokedDispatcher d = overlay.findOnBackInvokedDispatcher();
        if (d == null) return null;
        Back b = new Back(close, d);
        d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, b);
        return b;
    }

    void unregister() {
        dispatcher.unregisterOnBackInvokedCallback(this);
    }

    public void onBackInvoked() {
        unregister();
        close.run();
    }
}
