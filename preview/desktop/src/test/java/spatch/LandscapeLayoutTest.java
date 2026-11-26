package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
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
import static org.junit.Assert.*;

/** A phone held sideways, with a camera cutout down the left edge, the way the Pixel reports it. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w850dp-h384dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LandscapeLayoutTest {
    @Test public void playlistFitsBesideItsTracks() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        Reflect.settle();
        SpatchUi ui = findUi(root);
        ((Rect) field(ui, "inset")).set(55, 24, 0, 20);
        java.lang.reflect.Method targets = SpatchUi.class.getDeclaredMethod("nativePlaylistTargets");
        targets.setAccessible(true);
        String[] playlist = PackedStrings.unpackStrings((byte[]) targets.invoke(null));
        java.lang.reflect.Constructor<?> entry = Class.forName("spatch.MediaEntry").getDeclaredConstructor(String.class, String.class, String.class, String.class, int.class);
        entry.setAccessible(true);
        java.lang.reflect.Method build = SpatchUi.class.getDeclaredMethod("build");
        build.setAccessible(true);
        build.invoke(ui);
        Reflect.call(ui, "openPlaylist", new Class<?>[]{MediaEntry.class}, entry.newInstance(playlist[0], "", playlist[2], "demo:1", 1));
        Reflect.settle();
        int width = root.getWidth(), height = root.getHeight();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        screenshot(root, width, height, "landscape-playlist");
        View play = (View) field(ui, "listFab");
        Rect seen = new Rect();
        assertTrue("The play button is on screen", play.getGlobalVisibleRect(seen));
        assertEquals("The play button is not cut off", play.getWidth(), seen.width());
        View miniPlay = (View) field(ui, "mPlay");
        assertTrue("The rail's play button is on screen", miniPlay.getGlobalVisibleRect(seen));
        assertEquals("The rail's play button is whole", miniPlay.getHeight(), seen.height());
        activity.finish();
    }

    @Test public void recreatingKeepsThePlaylistAndPlayer() throws Exception {
        org.robolectric.android.controller.ActivityController<PreviewActivity> controller = Robolectric.buildActivity(PreviewActivity.class).setup().visible();
        ViewGroup root = (ViewGroup) controller.get().getWindow().getDecorView();
        Reflect.settle();
        SpatchUi ui = findUi(root);
        java.lang.reflect.Method targets = SpatchUi.class.getDeclaredMethod("nativePlaylistTargets");
        targets.setAccessible(true);
        String[] playlist = PackedStrings.unpackStrings((byte[]) targets.invoke(null));
        java.lang.reflect.Constructor<?> entry = Class.forName("spatch.MediaEntry").getDeclaredConstructor(String.class, String.class, String.class, String.class, int.class);
        entry.setAccessible(true);
        Reflect.call(ui, "openPlaylist", new Class<?>[]{MediaEntry.class}, entry.newInstance(playlist[0], "", playlist[2], "demo:1", 1));
        Reflect.call(ui, "openPlayer", new Class<?>[]{boolean.class}, false);
        Reflect.settle();
        controller.recreate();
        Reflect.settle();
        SpatchUi again = findUi((ViewGroup) controller.get().getWindow().getDecorView());
        assertNotSame("Android made a new screen", ui, again);
        assertEquals("The player is still open", Boolean.TRUE, field(again, "playerOpen"));
        assertEquals("The playlist is still showing behind it", playlist[2], ((MediaEntry) field(again, "playlist")).uri);
    }

    private static void screenshot(View root, int width, int height, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File file = new File("build/reports/preview/" + name + ".png");
        file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
    }

    private static Object field(Object object, String name) throws Exception {
        return Reflect.get(object, name);
    }

    private static SpatchUi findUi(View view) {
        if (view instanceof SpatchUi) return (SpatchUi) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            SpatchUi found = findUi(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
