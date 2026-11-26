package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w412dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeEffectsTest {
    @Test public void heroWaveFollowsPlaybackAndStopsWhenFlat() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = find(root, SpatchUi.class);
        activity.getSharedPreferences("spatch_ui", 0).edit().putBoolean("hero_wave", true).putBoolean("audio", false).apply();
        Reflect.set(ui, "playing", true);
        Reflect.call(ui, "showPage", new Class<?>[0]);
        settle();
        View wave = byName(root, "HeroWave");
        assertNotNull("The setting adds the wave to the hero", wave);
        Canvas canvas = new Canvas(Bitmap.createBitmap(wave.getWidth(), wave.getHeight(), Bitmap.Config.ARGB_8888));
        for (int i = 0; i < 20; i++) frame(wave, canvas);
        float[] levels = (float[]) field(wave, "levels");
        assertTrue("Playing, it rises", max(levels) > .1f);
        int width = root.getWidth(), height = root.getHeight();
        Bitmap shot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(shot));
        File file = new File("build/reports/preview/home-hero-wave.png");
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) { assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        Reflect.set(ui, "playing", false);
        for (int i = 0; i < 90; i++) frame(wave, canvas);
        assertTrue("Paused, it flattens", max(levels) < .004f);
        frame(wave, canvas);
        assertEquals("Flat, it takes the early return that asks for no next frame", 0L, (long) (Long) field(wave, "lastFrame"));
        activity.finish();
    }

    private static void frame(View view, Canvas canvas) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(16));
        view.draw(canvas);
    }

    private static float max(float[] values) {
        float max = 0;
        for (float v : values) max = Math.max(max, v);
        return max;
    }

    private static Object field(Object owner, String name) throws Exception {
        java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private static View byName(View view, String simpleName) {
        if (view.getClass().getSimpleName().equals(simpleName)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = byName(((ViewGroup) view).getChildAt(i), simpleName);
            if (found != null) return found;
        }
        return null;
    }

    private static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(25);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(25));
        }
    }
}
