package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import java.time.Duration;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w412dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ArtworkEffectsTest {
    @Test public void motesCoastToRestAndPlaybackWakesThemUp() throws Exception {
        SpatchUi ui = create();
        playback(ui, true);
        call(ui, "openPlayer", new Class<?>[]{boolean.class}, false);
        View motes = (View) field(ui, "motes");
        Canvas canvas = canvas(motes);
        for (int i = 0; i < 20; i++) frame(motes, canvas);
        float[] y = (float[]) field(motes, "y");
        float moving = y[0], speed = (Float) field(motes, "speed");
        shadowOf(motes).clearWasInvalidated();
        playback(ui, false);
        assertTrue("Pausing wakes the motes to begin settling", shadowOf(motes).wasInvalidated());
        frame(motes, canvas);
        assertTrue("Pause coasts rather than freezing", y[0] < moving);
        assertTrue((Float) field(motes, "speed") > 0 && (Float) field(motes, "speed") < speed);
        for (int i = 0; i < 100; i++) frame(motes, canvas);
        assertEquals(0f, (Float) field(motes, "speed"), 0f);
        float restingY = y[0], restingPhase = (Float) field(motes, "phase");
        for (int i = 0; i < 10; i++) frame(motes, canvas);
        assertEquals("Paused particles stay at rest", restingY, y[0], 0f);
        assertEquals(restingPhase, (Float) field(motes, "phase"), 0f);
        shadowOf(motes).clearWasInvalidated();
        playback(ui, true);
        assertTrue("Playback must restart a stopped frame loop", shadowOf(motes).wasInvalidated());
        frame(motes, canvas);
        assertTrue(y[0] < restingY);
        assertTrue((Float) field(motes, "phase") > restingPhase);
        ui.prefs.edit().putBoolean("reduce_motion", true).apply();
        float before = y[0];
        for (int i = 0; i < 10; i++) frame(motes, canvas);
        assertEquals("Reduce motion holds the particles still", before, y[0], 0f);
        assertEquals(0f, (Float) field(motes, "speed"), 0f);
        shadowOf(motes).clearWasInvalidated();
        call(motes, "onWindowVisibilityChanged", new Class<?>[]{int.class}, View.VISIBLE);
        assertTrue("Returning to the window also restarts drawing", shadowOf(motes).wasInvalidated());
        ui.activity.finish();
    }

    @Test public void amountAndShapesChangeTheParticlesActuallyDrawn() throws Exception {
        SpatchUi ui = create();
        playback(ui, true);
        ArtworkViews.Motes motes = ui.artwork.new Motes();
        Canvas canvas = canvas(motes);
        float[] y = (float[]) field(motes, "y");
        int[] amounts = {12, 30, 64};
        for (int amount = 0; amount < amounts.length; amount++) {
            ui.prefs.edit().putInt("motes_amount", amount).apply();
            Arrays.fill(y, .5f);
            for (int i = 0; i < 4; i++) frame(motes, canvas);
            for (int i = 0; i < y.length; i++) assertEquals("Only the selected number moves", i < amounts[amount], y[i] < .5f);
        }
        ui.prefs.edit().putBoolean("reduce_motion", true).apply();
        Bitmap[] shapes = new Bitmap[4];
        for (int shape = 0; shape < shapes.length; shape++) {
            ui.prefs.edit().putInt("motes_shape", shape).apply();
            shapes[shape] = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888);
            frame(motes, new Canvas(shapes[shape]));
            for (int previous = 0; previous < shape; previous++) assertFalse("Shape choices must render differently", shapes[shape].sameAs(shapes[previous]));
        }
        // Stored values outside the option range must still render safely.
        ui.prefs.edit().putInt("motes_amount", Integer.MAX_VALUE).putInt("motes_shape", -1).apply();
        frame(motes, canvas);
        ui.activity.finish();
    }

    @Test public void backdropBlurStylesUseDistinctCachedTextures() throws Exception {
        SpatchUi ui = create();
        ui.prefs.edit().putBoolean("reduce_motion", true).apply();
        Bitmap cover = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) cover.setPixel(x, y, 0xff000000 | ((x * 37 % 256) << 16) | ((y * 29 % 256) << 8) | ((x + y) * 13 % 256));
        ui.image = "test:blur";
        SpatchUi.IMAGES.put(ui.image + ":440", cover);
        ArtworkViews.cacheBlurs(ui.image + ":440", cover);
        ArtworkViews.Backdrop backdrop = ui.artwork.new Backdrop();
        canvas(backdrop);
        Bitmap[] rendered = new Bitmap[3];
        int[][] sizes = {{56, 56}, {40, 40}, {2, 16}};
        for (int style = 0; style < rendered.length; style++) {
            ui.prefs.edit().putInt("backdrop_blur", style).apply();
            rendered[style] = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888);
            backdrop.draw(new Canvas(rendered[style]));
            Bitmap shown = (Bitmap) field(backdrop, "shown");
            assertSame(SpatchUi.IMAGES.get(ui.image + ":440/blur/" + style), shown);
            assertEquals(sizes[style][0], shown.getWidth());
            assertEquals(sizes[style][1], shown.getHeight());
            for (int previous = 0; previous < style; previous++) assertFalse("Blur choices must affect the rendered backdrop", rendered[style].sameAs(rendered[previous]));
        }
        Bitmap stripe = Bitmap.createBitmap(2, 16, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 16; y++) { stripe.setPixel(0, y, 0xff000000); stripe.setPixel(1, y, 0xffffffff); }
        SpatchUi.IMAGES.put(ui.image + ":440/blur/2", stripe);
        Bitmap smooth = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888);
        backdrop.draw(new Canvas(smooth));
        int left = (smooth.getPixel(119, 120) >> 16) & 255, right = (smooth.getPixel(120, 120) >> 16) & 255;
        assertTrue("Upscaling a blur texture must interpolate instead of making block edges", Math.abs(left - right) < 10);
        ui.prefs.edit().putBoolean("backdrop", false).apply();
        Bitmap off = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888);
        backdrop.draw(new Canvas(off));
        assertEquals(ui.bg, off.getPixel(120, 200));
        ui.activity.finish();
    }

    @Test public void backdropDitheringBreaksUpDarkGradientBands() throws Exception {
        SpatchUi ui = create();
        ui.prefs.edit().putBoolean("reduce_motion", true).apply();
        ui.image = "test:banding";
        Bitmap cover = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        cover.eraseColor(0xff204455);
        SpatchUi.IMAGES.put(ui.image + ":440", cover);
        ArtworkViews.cacheBlurs(ui.image + ":440", cover);
        ArtworkViews.Backdrop backdrop = ui.artwork.new Backdrop();
        canvas(backdrop);
        Bitmap dithered = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888);
        backdrop.draw(new Canvas(dithered));
        android.graphics.Paint paint = (android.graphics.Paint) field(backdrop, "paint");
        paint.setDither(false);
        Bitmap banded = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888);
        backdrop.draw(new Canvas(banded));
        assertFalse("Dithering must change the rendered gradient, not just a setting", dithered.sameAs(banded));
        int brokenBands = 0;
        for (int y = 50; y < 350; y++) {
            for (int x = 1; x < 240; x++) if (dithered.getPixel(x, y) != dithered.getPixel(x - 1, y)) { brokenBands++; break; }
        }
        assertTrue("Dithering breaks up long solid colour steps", brokenBands > 100);
        paint.setDither(true);
        Bitmap again = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888);
        backdrop.draw(new Canvas(again));
        assertTrue("Dithering stays still when motion is reduced", again.sameAs(dithered));
        ui.activity.finish();
    }

    private static SpatchUi create() {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        SpatchUi ui = (SpatchUi) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        ui.prefs.edit().clear().putBoolean("audio", false).apply();
        return ui;
    }

    private static void playback(SpatchUi ui, boolean playing) throws Exception {
        String[] state = {"999", "spotify:track:test", "Song", "Artist", "Album", "demo:0", Boolean.toString(playing),
            "false", "0", "120000", "240000", "Test", "", "", "", "false", "true", "true", "", ""};
        call(ui, "applyPlayer", new Class<?>[]{String[].class}, (Object) state);
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Canvas canvas(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 240, 400);
        return new Canvas(Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888));
    }

    private static void frame(View view, Canvas canvas) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(16));
        view.draw(canvas);
    }
}
