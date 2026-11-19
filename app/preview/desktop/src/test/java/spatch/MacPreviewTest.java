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
    public static void main(String[] args) {
        org.junit.runner.Result result = new org.junit.runner.JUnitCore().run(
            org.junit.runner.Request.method(MacPreviewTest.class, "sharedScreen"));
        if (!result.wasSuccessful()) {
            for (org.junit.runner.notification.Failure failure : result.getFailures()) System.err.println(failure.getTrace());
            System.exit(1);
        }
    }
    @Test public void previewCatalogUsesSharedDemoData() throws Exception {
        Class.forName("spatch.PreviewActivity");
        java.lang.reflect.Method catalog = SpatchUi.class.getDeclaredMethod("nativeCatalog", byte[].class);
        catalog.setAccessible(true);
        String[] fields = (String[]) catalog.invoke(null, (Object) CoreBridge.search("Glasshouse", ""));
        assertEquals(7, fields.length);
        assertEquals("Glasshouse", fields[1]);
        assertEquals("spotify:preview:0", fields[3]);
    }
    @Test public void audioMath() { AudioTest.main(new String[0]); }

    @Test public void catalogAndCelebrationMath() {
        java.util.List<AutoEqCatalog.Headphone> models = AutoEqCatalog.parse(
            "- [A (ANC Off)](./Source/GRAS/A%20(ANC%20Off))\n"
            + "- [Unicode + é](./Source/GRAS/Unicode%20+%20%C3%A9)\n"
            + "- [Bad](./../../../outside)\n- [Bad](./Source/A?query=1)\n- [Bad](https://evil.test/file)\n");
        assertEquals(2, models.size());
        assertTrue(models.get(0).url.endsWith("A%20(ANC%20Off)/A%20(ANC%20Off)%20GraphicEQ.txt"));
        assertTrue(models.get(1).url.contains("Unicode%20+%20%C3%A9"));
        assertEquals(6, Confetti.count("few"));
        assertEquals(24, Confetti.count("lots"));
        assertArrayEquals(new float[]{0, 0}, Confetti.place(0, 1, 0, 100, 50), .001f);
        assertArrayEquals(new float[]{100, 50}, Confetti.place(1, 1, 0, 100, 50), .001f);
        assertEquals(0, Confetti.pieceAlpha(1), .001f);
        assertEquals(1, Confetti.pieceScale(.15f), .001f);
    }

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
        View account = find(root, "Account menu");
        assertNotNull(account);
        account.performClick();
        settle();
        java.lang.reflect.Field popupField = SpatchUi.class.getDeclaredField("accountMenu");
        popupField.setAccessible(true);
        View ui = findType(root, SpatchUi.class);
        android.widget.PopupWindow popup = (android.widget.PopupWindow) popupField.get(ui);
        assertNotNull(popup);
        View settings = find(popup.getContentView(), "Settings");
        assertNotNull(settings);
        // The whole menu row owns the click, while find() can return its text child.
        if (!settings.isClickable()) settings = (View) settings.getParent();
        settings.performClick();
        settle();
        assertNotNull(find(root, "Equalizer & sound"));
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
        // Use real touch dispatch: dragging across children must cancel their original click.
        ViewGroup nav = (ViewGroup) field(ui, "dockBar");
        android.content.SharedPreferences prefs = activity.getSharedPreferences("spatch_ui", 0);
        prefs.edit().putBoolean("reduce_motion", true).apply();
        drag(nav, nav.getWidth() - 20, nav.getHeight() / 2f);
        assertEquals(4, field(ui, "tab"));
        assertNotNull(find(root, "Make something yours."));
        java.util.Map<?, ?> glyphs = (java.util.Map<?, ?>) field(ui, "GLYPHS");
        assertTrue(((float[]) glyphs.get("add")).length > 0);
        drag(nav, 20, -30);
        assertEquals("Releasing outside the bar cancels navigation", 4, field(ui, "tab"));
        java.lang.reflect.Method scroll = SpatchUi.class.getDeclaredMethod("scrollDock", int.class, boolean.class);
        scroll.setAccessible(true);
        scroll.invoke(ui, 100, false);
        assertEquals(false, field(ui, "dockHidden"));
        prefs.edit().putBoolean("auto_hide_dock", true).apply();
        scroll.invoke(ui, 100, false);
        assertEquals(true, field(ui, "dockHidden"));
        scroll.invoke(ui, -100, false);
        assertEquals(false, field(ui, "dockHidden"));
        prefs.edit().clear().apply();
        activity.finish();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(25);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(25));
        }
    }

    private static Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field field = SpatchUi.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void drag(View nav, float endX, float endY) {
        long now = android.os.SystemClock.uptimeMillis();
        float startX = nav.getWidth() / 2f, y = nav.getHeight() / 2f;
        int[] actions = {0, 2, 2, 1};
        float[] xs = {startX, startX + (endX - startX) / 2f, endX, endX};
        for (int i=0; i<actions.length; i++) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now + i * 50, actions[i], xs[i], i == 3 ? endY : y, 0);
            nav.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private static View findType(View view, Class<?> type) {
        if (type.isInstance(view)) return view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            View found = findType(((ViewGroup)view).getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
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
