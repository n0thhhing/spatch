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

    @Test public void libraryFilterPillSpringsToTheChosenChip() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = find(root, SpatchUi.class);
        Reflect.call(ui, "go", new Class<?>[]{int.class}, (int) Reflect.get(SpatchUi.class, "LIBRARY"));
        settle();
        shot(root, "library-chips-before");
        View albums = text(root, "Albums");
        assertNotNull(albums);
        albums.performClick();
        ShadowLooper.idleMainLooper(60, java.util.concurrent.TimeUnit.MILLISECONDS);
        shot(root, "library-chips-moving");
        settle();
        settle();
        shot(root, "library-chips-after");
        View track = (View) albums.getParent().getParent();
        float[] edges = null;
        for (java.lang.reflect.Field f : track.getClass().getDeclaredFields()) {
            if (f.getName().contains("pillEdges")) { f.setAccessible(true); edges = (float[]) f.get(track); }
        }
        assertNotNull("The track holds the pill's edges", edges);
        assertEquals("Settled on the chip's left edge", albums.getLeft(), edges[0], 1f);
        assertEquals("And its right edge", albums.getRight(), edges[1], 1f);
        activity.finish();
    }

    @Test public void letterRailJumpsTheListAndShowsTheLetter() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = find(root, SpatchUi.class);
        activity.getSharedPreferences("spatch_ui", 0).edit().putBoolean("library_az", true).apply();
        Reflect.call(ui, "go", new Class<?>[]{int.class}, (int) Reflect.get(SpatchUi.class, "LIBRARY"));
        settle();
        View rail = described(root, "Letter index");
        assertNotNull("A–Z sorting shows the letter rail", rail);
        android.widget.AbsListView list = (android.widget.AbsListView) Reflect.get(ui, "libraryList");
        int[] at = new int[2];
        rail.getLocationOnScreen(at);
        float x = at[0] + rail.getWidth() / 2f, top = at[1] + rail.getPaddingTop(), step = (rail.getHeight() - rail.getPaddingTop() - rail.getPaddingBottom()) / 27f;
        long down = android.os.SystemClock.uptimeMillis();
        float y = top + step * ("#ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf('S') + .5f);
        touch(rail, down, android.view.MotionEvent.ACTION_DOWN, x, y);
        ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        shot(root, "library-letter-rail");
        View bubble = text(root, "S");
        assertNotNull("The floating letter shows S", bubble);
        assertTrue("It's visible", bubble.getAlpha() > .5f);
        // The first title filed under S (or the next letter after it) is now on screen; a short list may not be able
        // to scroll it right to the top.
        int target = -1;
        for (int i = 0; i < list.getCount() && target < 0; i++) {
            String t = (String) titleOf(list.getItemAtPosition(i));
            char c = t.isEmpty() ? '#' : Character.toUpperCase(t.charAt(0));
            if (c >= 'S' && c <= 'Z') target = i;
        }
        assertTrue("The demo library has a title from S on", target >= 0);
        assertTrue("The list shows it", list.getFirstVisiblePosition() <= target && target <= list.getLastVisiblePosition());
        assertTrue("And it moved from the top", list.getFirstVisiblePosition() > 0 || target < list.getChildCount());
        touch(rail, down, android.view.MotionEvent.ACTION_UP, x, y);
        activity.finish();
    }

    private static Object titleOf(Object entry) throws Exception {
        for (Class<?> c = entry.getClass(); c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Field f = c.getDeclaredField("title"); f.setAccessible(true); return f.get(entry); } catch (NoSuchFieldException next) { }
        }
        return null;
    }

    private static void touch(View view, long down, int action, float x, float y) {
        android.view.MotionEvent e = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0);
        view.getRootView().dispatchTouchEvent(e);
        e.recycle();
    }

    private static View described(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = described(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private static void shot(View root, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File file = new File("build/reports/preview/" + name + ".png");
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); }
    }

    private static View text(View view, String label) {
        if (view instanceof android.widget.TextView && label.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = text(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
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
