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
    @Test public void nativeCreationRestoresViewsAndBackHandling() throws Exception {
        org.robolectric.android.controller.ActivityController<android.app.Activity> controller = Robolectric.buildActivity(android.app.Activity.class).setup();
        android.app.Activity activity = controller.get();
        activity.setContentView(new TextView(activity));
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(activity);
        overlay.setTag("spatch-root"); decor.addView(overlay);
        java.lang.reflect.Field callbacksField = SpatchApp.class.getDeclaredField("BACK_CALLBACKS");
        callbacksField.setAccessible(true);
        java.util.Map<android.app.Activity,Object> callbacks = (java.util.Map<android.app.Activity,Object>) callbacksField.get(null);
        callbacks.put(activity, (android.window.OnBackInvokedCallback) () -> {});
        java.lang.reflect.Field registeredField = SpatchApp.class.getDeclaredField("BACK_REGISTERED");
        registeredField.setAccessible(true);
        java.util.Map<?,?> registered = (java.util.Map<?,?>) registeredField.get(null);
        SpatchApp.resumed(activity);
        View nativeContent = decor.findViewById(android.R.id.content);
        assertEquals(View.INVISIBLE, nativeContent.getVisibility());
        assertEquals(Boolean.TRUE, registered.get(activity));
        SpatchApp.enterNativeFeature(activity);
        assertEquals(View.GONE, overlay.getVisibility());
        assertEquals(View.VISIBLE, nativeContent.getVisibility());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, nativeContent.getImportantForAccessibility());
        assertEquals(Boolean.FALSE, registered.get(activity));
        assertNotNull(decor.findViewWithTag("spatch-return"));
        decor.findViewWithTag("spatch-return").performClick();
        assertFalse(SpatchApp.isNativeFeatureMode());
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(View.INVISIBLE, nativeContent.getVisibility());
        assertEquals(Boolean.TRUE, registered.get(activity));
        assertNull(decor.findViewWithTag("spatch-return"));
        controller.pause().stop().destroy();
        assertFalse(callbacks.containsKey(activity));
        assertFalse(registered.containsKey(activity));
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
        ViewGroup popupList = (ViewGroup) popup.getContentView();
        assertEquals("Settings is at the bottom of the account menu", "Settings", popupList.getChildAt(popupList.getChildCount() - 1).getContentDescription());
        View settings = find(popupList, "Settings");
        assertNotNull(settings);
        // The whole menu row owns the click, while find() can return its text child.
        if (!settings.isClickable()) settings = (View) settings.getParent();
        settings.performClick();
        settle();
        assertNotNull(find(root, "Equalizer & sound"));
        assertNotNull(find(root, "Developer"));
        assertNull("Profile belongs in the account menu", find(root, "Account"));
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
        assertEquals("Bottom navigator has Home, Search, Library and +", 4, nav.getChildCount());
        assertNull("Settings is in the account menu, not the bottom navigator", find(nav, "Settings"));
        // Settings -> Profile -> Your playlists -> Back -> Profile -> Back -> Settings
        find(root, "Account menu").performClick();
        settle();
        popup = (android.widget.PopupWindow) popupField.get(ui);
        View profileRow = find(popup.getContentView(), "Profile");
        if (!profileRow.isClickable()) profileRow = (View) profileRow.getParent();
        profileRow.performClick();
        settle();
        assertEquals("Opened Profile from Settings", 5, field(ui, "tab"));
        View playlistsRow = find(root, "Your playlists");
        if (!playlistsRow.isClickable()) playlistsRow = (View) playlistsRow.getParent();
        playlistsRow.performClick();
        settle();
        assertEquals("Your playlists opens Library", 2, field(ui, "tab"));
        assertNotNull("Library shows a back link to Profile", find(root, "Back to Profile"));
        ((SpatchUi) ui).back();
        settle();
        assertEquals("Back from Your playlists returns to Profile", 5, field(ui, "tab"));
        ((SpatchUi) ui).back();
        settle();
        assertEquals("Back from Profile returns to Settings", 3, field(ui, "tab"));
        android.content.SharedPreferences prefs = activity.getSharedPreferences("spatch_ui", 0);
        prefs.edit().putBoolean("reduce_motion", true).apply();
        drag(nav, nav.getWidth() - 20, nav.getHeight() / 2f);
        assertEquals("Create menu keeps the current screen behind it", 3, field(ui, "tab"));
        View menu = (View) field(ui, "createOverlay");
        assertNotNull(menu);
        View addIcon = ((ViewGroup) nav.getChildAt(3)).getChildAt(0);
        assertEquals("Create icon rotates to x when open", 45f, addIcon.getRotation(), .01f);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        android.graphics.Rect visible = new android.graphics.Rect();
        assertTrue("Last creation option fits in the portrait menu", find(menu, "Jam").getGlobalVisibleRect(visible));
        assertTrue(visible.height() >= 48);
        screenshot(root, width, height, "create-menu");
        for (String option : new String[]{"Playlist", "Collaborative playlist", "Mixed playlist", "Blend", "Prompted Playlist", "Jam"}) assertNotNull(find(menu, option));
        find(menu, "Playlist").performClick();
        assertEquals("Playlist composer stays in the floating card over the current screen", 3, field(ui, "tab"));
        assertNotNull("Create overlay remains open for the inline playlist composer", field(ui, "createOverlay"));
        assertEquals("Create icon stays rotated to x while composer is open", 45f, addIcon.getRotation(), .01f);
        assertNotNull(find(menu, "Playlist name"));
        assertNotNull(find(menu, "Cancel"));
        assertNotNull(find(menu, "Create playlist"));
        settle();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        screenshot(root, width, height, "create-playlist");
        find(menu, "Cancel").performClick();
        assertNull("Cancel dismisses the floating composer", field(ui, "createOverlay"));
        assertEquals("Create icon rotates back to + when closed", 0f, addIcon.getRotation(), .01f);
        java.util.Map<?, ?> glyphs = (java.util.Map<?, ?>) field(ui, "GLYPHS");
        assertTrue(((float[]) glyphs.get("add")).length > 0);
        drag(nav, 20, -30);
        assertEquals("Releasing outside the bar cancels navigation", 3, field(ui, "tab"));
        find(nav, "Library").performClick();
        settle();
        prefs.edit().putBoolean("reduce_motion", false).apply();
        java.lang.reflect.Field position = nav.getClass().getDeclaredField("indicatorX"); position.setAccessible(true);
        float before = position.getFloat(nav);
        long gesture = android.os.SystemClock.uptimeMillis();
        touch(nav, gesture, 0, nav.getWidth() - 40, nav.getHeight()/2f);
        touch(nav, gesture, 2, 45, nav.getHeight()/2f);
        assertEquals("Hover starts a transition instead of teleporting the pill", before, position.getFloat(nav), .01f);
        java.lang.reflect.Field animation = nav.getClass().getDeclaredField("slide"); animation.setAccessible(true);
        android.animation.ValueAnimator hover = (android.animation.ValueAnimator) animation.get(nav);
        assertNotNull(hover);
        touch(nav, gesture, 2, 42, nav.getHeight()/2f);
        assertSame("Continuous drag updates target without cancelling and restarting the animator", hover, animation.get(nav));
        hover.setCurrentPlayTime(16);
        float midway = position.getFloat(nav);
        assertTrue("Indicator travels through intermediate positions: " + before + " → " + midway, midway < before && midway > 42);
        hover.setCurrentPlayTime(32);
        assertTrue("Indicator keeps advancing across frames", position.getFloat(nav) < midway);
        touch(nav, gesture, 3, 45, -10);
        prefs.edit().putBoolean("reduce_motion", true).apply();
        find(root, "Account menu").performClick();
        settle();
        popup = (android.widget.PopupWindow) popupField.get(ui);
        View backToSettings = find(popup.getContentView(), "Settings");
        if (!backToSettings.isClickable()) backToSettings = (View) backToSettings.getParent();
        backToSettings.performClick();
        settle();
        java.lang.reflect.Method scroll = SpatchUi.class.getDeclaredMethod("scrollDock", int.class, boolean.class);
        scroll.setAccessible(true);
        scroll.invoke(ui, 100, false);
        assertEquals(false, field(ui, "dockHidden"));
        prefs.edit().putBoolean("auto_hide_dock", true).apply();
        scroll.invoke(ui, 100, false);
        assertEquals(true, field(ui, "dockHidden"));
        scroll.invoke(ui, -100, false);
        assertEquals(false, field(ui, "dockHidden"));
        View eqRow = find(root, "Equalizer & sound");
        assertNotNull(eqRow);
        if (!eqRow.isClickable()) eqRow = (View) eqRow.getParent();
        eqRow.performClick();
        settle();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        View curve = findClass(root, "EqualizerCurve");
        assertNotNull("Curve is directly on the sound overview", curve);
        long now = android.os.SystemClock.uptimeMillis();
        float x = 36 + (curve.getWidth() - 58) * 5 / 9f;
        touch(curve, now, 0, x, 100);
        touch(curve, now, 2, x, 61);
        touch(curve, now, 1, x, 61);
        float adjusted = Audio.gain(prefs, 5);
        assertTrue("Dragging a point adjusts the real saved band", adjusted > 0 && adjusted < 12);
        assertEquals(Math.round((adjusted + 12) * 10), ((android.widget.SeekBar)findClass(root, "SeekBar")).getProgress());
        find(root, "Save").performClick();
        find(root, "Reset").performClick();
        assertEquals(0, Audio.gain(prefs, 5), .001f);
        find(root, "Equalizer presets").performClick();
        find(root, "Saved curve").performClick();
        assertEquals(adjusted, Audio.gain(prefs, 5), .001f);
        settle();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        screenshot(root, width, height, "equalizer");
        prefs.edit().clear().apply();
        activity.finish();
    }

    private static void touch(View view, long down, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + action * 40, action, x, y, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }

    private static View findClass(View view, String name) {
        if (view.getClass().getSimpleName().equals(name)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            View result = findClass(((ViewGroup)view).getChildAt(i), name);
            if (result != null) return result;
        }
        return null;
    }

    private static void screenshot(View root, int width, int height, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File file = new File("build/reports/preview/" + name + ".png");
        file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
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
        int[] actions = {0, 2, 1};
        float[] xs = {startX, startX + (endX - startX) / 2f, endX};
        for (int i=0; i<actions.length; i++) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now + i * 50, actions[i], xs[i], i == actions.length - 1 ? endY : y, 0);
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
