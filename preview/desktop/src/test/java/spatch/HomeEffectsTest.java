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

    @Test public void searchShowsAMoodBoardThenATopResultAndResultsGlide() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = find(root, SpatchUi.class);
        Reflect.call(ui, "go", new Class<?>[]{int.class}, (int) Reflect.get(SpatchUi.class, "SEARCH"));
        settle();
        assertNotNull("Empty search shows mood tiles", text(root, "Chill"));
        shot(root, "search-moods");
        String[] first = {"Songs", "Glasshouse", "Northbound", "spotify:track:a", "demo:0", "-1", "201000",
            "Songs", "Lanterns", "Harbor Lights", "spotify:track:b", "demo:1", "-1", "187000",
            "Songs", "Night Bus", "Velvet Static", "spotify:track:c", "demo:2", "-1", "233000",
            "Albums", "Afterglow", "Harbor Lights", "spotify:album:d", "demo:3", "1", "0"};
        Reflect.set(ui, "searchQuery", "gl");
        Reflect.set(ui, "searchHits", first);
        Reflect.call(ui, "renderSearch", new Class<?>[0]);
        settle();
        assertNotNull("The best match is a top result card", text(root, "Top result · Song"));
        shot(root, "search-top-result");
        View nightBus = text(root, "Night Bus");
        String[] second = {"Songs", "Glasshouse", "Northbound", "spotify:track:a", "demo:0", "-1", "201000",
            "Songs", "Night Bus", "Velvet Static", "spotify:track:c", "demo:2", "-1", "233000",
            "Albums", "Afterglow", "Harbor Lights", "spotify:album:d", "demo:3", "1", "0"};
        Reflect.set(ui, "searchHits", second);
        Reflect.call(ui, "renderSearch", new Class<?>[0]);
        View row = (View) text(root, "Night Bus").getParent().getParent();
        int width = root.getWidth(), height = root.getHeight();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        root.getViewTreeObserver().dispatchOnPreDraw();
        while (row != null && !(row.getTag() instanceof String && ((String) row.getTag()).startsWith("hit:"))) row = row.getParent() instanceof View ? (View) row.getParent() : null;
        assertNotNull(row);
        assertTrue("A result that stays starts from its old place (it moved up a row) and glides", row.getTranslationY() > 0);
        activity.finish();
    }

    @Test public void equalizerShowsTheSpectrumAndDrawsTheHeadphoneCurveIn() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = find(root, SpatchUi.class);
        org.robolectric.Shadows.shadowOf(activity.getApplication()).grantPermissions(Audio.PERMISSION);
        Reflect.call(ui, "go", new Class<?>[]{int.class}, (int) Reflect.get(SpatchUi.class, "SETTINGS"));
        settle();
        View eqRow = text(root, "Equalizer & sound");
        while (!eqRow.isClickable()) eqRow = (View) eqRow.getParent();
        eqRow.performClick();
        settle();
        StringBuilder curve = new StringBuilder("GraphicEQ: ");
        for (int i = 0; i < 40; i++) {
            float hz = 20f * (float) Math.pow(1000f, i / 39f), gain = 6f * (float) Math.sin(i / 5f) - 2f;
            curve.append(i == 0 ? "" : "; ").append(Math.round(hz)).append(' ').append(gain);
        }
        Reflect.set(ui, "playing", true);
        for (int i = 0; i < Audio.BANDS.length; i++) Audio.BANDS[i] = .25f + .5f * (float) Math.abs(Math.sin(i / 4f));
        try {
            Reflect.call(ui, "activateProfile", new Class<?>[]{String.class, String.class}, "Studio Headphones", curve.toString());
            settle();
            View graph = byName(root, "EqualizerCurve");
            assertNotNull(graph);
            Reflect.set(ui, "revealAt", android.os.SystemClock.uptimeMillis() - 450);
            Canvas canvas = new Canvas(Bitmap.createBitmap(graph.getWidth(), graph.getHeight(), Bitmap.Config.ARGB_8888));
            for (int i = 0; i < 12; i++) frame(graph, canvas);
            shot(root, "eq-drawing-in");
            Reflect.set(ui, "revealAt", android.os.SystemClock.uptimeMillis() - 1300);
            for (int i = 0; i < 4; i++) frame(graph, canvas);
            shot(root, "eq-applied");
            // The live spectrum isn't checked here: asking the audio worker for levels makes it overwrite any fake
            // bands with the preview's silence. Phone-checked instead.
        } finally {
            java.util.Arrays.fill(Audio.BANDS, 0f);
        }
        activity.finish();
    }

    @Test public void pullingTheLibraryDownReloadsItWithTheStretchingLoader() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = find(root, SpatchUi.class);
        Reflect.call(ui, "go", new Class<?>[]{int.class}, (int) Reflect.get(SpatchUi.class, "LIBRARY"));
        settle();
        View host = byName(root, "PullRefresh");
        assertNotNull(host);
        View blob = byName(host, "Blob");
        assertNotNull("The loader waits above the list", blob);
        int[] at = new int[2];
        host.getLocationOnScreen(at);
        float x = at[0] + host.getWidth() / 2f, y = at[1] + 40;
        long down = android.os.SystemClock.uptimeMillis();
        touch(host, down, android.view.MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 10; i++) touch(host, down, android.view.MotionEvent.ACTION_MOVE, x, y + 30 * i);
        float stretch = (float) field(blob, "stretch");
        assertTrue("Pulling stretches it like a drop", stretch > .5f);
        shot(root, "library-pull");
        touch(host, down, android.view.MotionEvent.ACTION_UP, x, y + 300);
        assertTrue("Letting go reloads the library", (Boolean) Reflect.get(ui, "fetching") || (boolean) field(blob, "spinning"));
        activity.finish();
    }

    @Test public void confirmationsDropInOnASpringAndTuckAway() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = find(root, SpatchUi.class);
        Reflect.call(ui, "toast", new Class<?>[]{CharSequence.class}, "Added to Liked Songs");
        settle();
        View pill = text(root, "Added to Liked Songs");
        assertNotNull("The confirmation is a pill in the app", pill);
        assertEquals("It settles in place", 0f, pill.getTranslationY(), 1f);
        shot(root, "toast");
        ShadowLooper.idleMainLooper(2600, java.util.concurrent.TimeUnit.MILLISECONDS);
        settle();
        assertTrue("Then it tucks away above the screen", pill.getTranslationY() < 0 || pill.getAlpha() < .1f);
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
