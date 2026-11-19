package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w412dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlaylistPickerTest {
    @Test public void catalogTrackDurationsAreVisible() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = findUi(root);
        java.lang.reflect.Field searchTab = SpatchUi.class.getDeclaredField("SEARCH");
        searchTab.setAccessible(true);
        java.lang.reflect.Method go = SpatchUi.class.getDeclaredMethod("go", int.class);
        go.setAccessible(true);
        go.invoke(ui, searchTab.getInt(null));
        java.lang.reflect.Field query = SpatchUi.class.getDeclaredField("searchQuery");
        query.setAccessible(true);
        query.set(ui, "Known");
        java.lang.reflect.Field hits = SpatchUi.class.getDeclaredField("searchHits");
        hits.setAccessible(true);
        hits.set(ui, new String[]{"Songs", "Known", "Artist", "spotify:track:known", "demo:1", "-1", "221000"});
        java.lang.reflect.Method render = SpatchUi.class.getDeclaredMethod("renderSearch");
        render.setAccessible(true);
        render.invoke(ui);
        View duration = find(root, "3:41");
        assertNotNull(duration);
        assertEquals(View.VISIBLE, duration.getVisibility());
        activity.finish();
    }

    @Test public void trackMenusOpenSearchableEditablePlaylistsAndSeedNewOnes() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = findUi(root);
        assertNotNull(ui);
        java.lang.reflect.Method visibility = SpatchUi.class.getDeclaredMethod("onWindowVisibilityChanged", int.class);
        visibility.setAccessible(true);
        java.lang.reflect.Field request = SpatchUi.class.getDeclaredField("playlistRequest");
        request.setAccessible(true);
        int before = request.getInt(ui);
        visibility.invoke(ui, View.INVISIBLE);
        visibility.invoke(ui, View.VISIBLE);
        assertEquals("Backgrounding must preserve outstanding playlist loads", before, request.getInt(ui));
        Class<?> entry = Class.forName("spatch.SpatchUi$Entry");
        java.lang.reflect.Constructor<?> constructor = entry.getDeclaredConstructor(String.class, String.class, String.class, String.class, int.class);
        constructor.setAccessible(true);
        Object track = constructor.newInstance("Picker song", "Artist", "spotify:track:picker", "demo:1", -1);
        java.lang.reflect.Method actions = SpatchUi.class.getDeclaredMethod("actions", entry);
        actions.setAccessible(true);
        actions.invoke(ui, track);
        settle();
        View add = find(root, "Add to playlist");
        assertNotNull("Track actions include adding to playlists outside a playlist page", add);
        click(add);
        settle();
        java.lang.reflect.Method targets = SpatchUi.class.getDeclaredMethod("nativePlaylistTargets");
        targets.setAccessible(true);
        String[] fields = SpatchUi.unpackStrings((byte[]) targets.invoke(null));
        assertTrue(fields.length >= 5);
        for (int i = 0; i < fields.length; i += 5) assertFalse(fields[i + 2].contains("collection"));
        EditText search = (EditText) find(root, "Search playlists");
        assertNotNull(search);
        View target = find((View) search.getParent(), fields[0]);
        assertNotNull(target);
        ui.dispatchApplyWindowInsets(new android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(0, 30, 0, 20)).build());
        assertSame("Insets must preserve an open sheet", search, find(root, "Search playlists"));
        search.setText("no playlist matches this");
        settle();
        assertFalse(target.isShown());
        assertTrue(find(root, "New playlist").isShown());
        search.setText("");
        settle();
        root.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(820, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 412, 820);
        Bitmap bitmap = Bitmap.createBitmap(412, 820, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File file = new File("build/reports/preview/add-to-playlist.png");
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        click(find(root, "New playlist"));
        settle();
        assertNotNull(find(root, "Includes “Picker song”"));
        assertNotNull(find(root, "Create playlist"));
        java.lang.reflect.Method close = SpatchUi.class.getDeclaredMethod("closeCreateMenu");
        close.setAccessible(true);
        close.invoke(ui);
        java.lang.reflect.Field pending = SpatchUi.class.getDeclaredField("pendingPlaylistSong");
        pending.setAccessible(true);
        assertNull("Canceling must not seed a later unrelated playlist", pending.get(ui));
        java.lang.reflect.Method openPlayer = SpatchUi.class.getDeclaredMethod("openPlayer", boolean.class);
        openPlayer.setAccessible(true);
        openPlayer.invoke(ui, false);
        settle();
        View playerAdd = find(root, "Add current song to playlist");
        assertNotNull(playerAdd);
        click(playerAdd);
        settle();
        assertNotNull(find(root, "Search playlists"));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        try {
            executor.execute(() -> { try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            java.lang.reflect.Method submit = SpatchUi.class.getDeclaredMethod("submit", java.util.concurrent.ExecutorService.class, Runnable.class);
            submit.setAccessible(true);
            submit.invoke(ui, executor, (Runnable) () -> writes.incrementAndGet());
            ((ViewGroup) ui.getParent()).removeView(ui);
            release.countDown();
            executor.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("Detached account screens cannot execute queued mutations", 0, writes.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        activity.finish();
    }

    private static void click(View view) {
        while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
        assertTrue(view.performClick());
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 12; i++) {
            Thread.sleep(25);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(40));
        }
    }

    private static SpatchUi findUi(View view) {
        if (view instanceof SpatchUi) return (SpatchUi) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            SpatchUi found = findUi(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static View find(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                || view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
}
