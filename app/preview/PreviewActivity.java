package spatch;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;

public final class PreviewActivity extends Activity {
    static {
        System.loadLibrary("spatch_app");
        nativePreview();
    }

    private static native void nativePreview();
    private SpatchUi ui;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new SpatchUi(this);
        setContentView(ui);
        if (Build.VERSION.SDK_INT >= 33) Back33.register(this, ui);
    }

    @Override public void onBackPressed() { ui.back(); }

    private static final class Back33 {
        static void register(Activity activity, SpatchUi ui) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, (android.window.OnBackInvokedCallback) ui.backCallback());
        }
    }
}
