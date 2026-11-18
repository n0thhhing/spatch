package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.simulator.Simulator;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w412dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MacPreviewTest {
    @Test public void sharedScreen() throws Exception {
        boolean interactive = Boolean.getBoolean("spatch.preview.interactive");
        if (interactive) {
            // Let the host enable real draw traversals before creating the activity's surface.
            new Simulator(PreviewActivity.class).start();
            return;
        }
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class)
            .create().start().resume().visible().get();
        View root = activity.getWindow().getDecorView();
        settle();
        assertNotNull(find(root, "Here’s your music"));
        View settings = find(root, "Settings");
        assertNotNull(settings);
        settings.performClick();
        settle();
        assertNotNull(find(root, "Equalizer"));
        assertNotNull(find(root, "Developer"));
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(image));
        File screenshot = new File("build/reports/preview/settings.png");
        screenshot.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(screenshot)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        activity.finish();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(25);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(25));
        }
    }

    private static View find(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
            || view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
