package spatch;

import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;
import static org.robolectric.Shadows.shadowOf;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w412dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlaybackProgressTest {
    @Test public void jumpsEaseWithoutDelayingActualPlayback() throws Exception {
        SpatchUi ui = create();
        // New track, repeat/restart, remote seek, duration correction and local seek.
        for (String[] change : new String[][]{
                {"next", "0", "180000"}, {"same", "0", "240000"},
                {"same", "60000", "240000"}, {"same", "120000", "300000"}}) {
            apply(ui, "same", 120000, 240000, false);
            finish(ui);
            apply(ui, change[0], Long.parseLong(change[1]), Long.parseLong(change[2]), false);
            float target = Long.parseLong(change[1]) / (float) Long.parseLong(change[2]);
            assertEquals("The displayed tip stays continuous at a jump", .5f, progress(ui), .001f);
            assertEquals(Long.parseLong(change[1]), ((Long) field(ui, "position")).longValue());
            ValueAnimator animator = (ValueAnimator) field(field(ui, "progress"), "animator");
            assertNotNull(animator);
            animator.setCurrentPlayTime(animator.getDuration() / 2);
            float middle = progress(ui);
            assertTrue("The tip moves between the endpoints", middle > Math.min(.5f, target) && middle < Math.max(.5f, target));
            finish(ui);
            assertEquals(target, progress(ui), .001f);
        }
        apply(ui, "same", 120000, 240000, false);
        finish(ui);
        call(ui, "setPosition", new Class<?>[]{long.class}, 180000L);
        assertEquals(.5f, progress(ui), .001f);
        assertEquals(180000L, ((Long) field(ui, "position")).longValue());
        ValueAnimator first = (ValueAnimator) field(field(ui, "progress"), "animator");
        first.setCurrentPlayTime(first.getDuration() / 2);
        float interrupted = progress(ui);
        apply(ui, "another", 0, 180000, false);
        assertEquals("Rapid skips continue from the visible tip", interrupted, progress(ui), .001f);
        finish(ui);
        assertEquals(0, progress(ui), .001f);
        apply(ui, "unknown-length", 60000, 0, false);
        finish(ui);
        apply(ui, "unknown-length", 60000, 240000, false);
        assertEquals("A late duration must not snap the line", 0, progress(ui), .001f);
        finish(ui);
        assertEquals(.25f, progress(ui), .001f);
    }

    @Test public void scrubbingAndHoldingFlattenAndCanceledScrubsEaseBack() throws Exception {
        SpatchUi ui = create();
        apply(ui, "same", 120000, 240000, true);
        finish(ui);
        call(ui, "openPlayer", new Class<?>[]{boolean.class}, false);
        ui.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(820, View.MeasureSpec.EXACTLY));
        ui.layout(0, 0, 412, 820);
        View wave = (View) field(ui, "wave");
        draw(wave);
        assertTrue(((Float) field(wave, "amp")) > 2.5f);
        touch(wave, MotionEvent.ACTION_DOWN, .8f);
        draw(wave);
        assertTrue("Seeking straightens the wave", ((Float) field(wave, "amp")) < .05f);
        assertEquals("The thumb follows the finger directly", .8f, progress(ui), .001f);
        touch(wave, MotionEvent.ACTION_MOVE, .25f);
        assertEquals(.25f, progress(ui), .001f);
        touch(wave, MotionEvent.ACTION_CANCEL, .25f);
        assertEquals("Cancel starts from the finger's last position", .25f, progress(ui), .001f);
        finish(ui);
        assertEquals(.5f, progress(ui), .001f);
        draw(wave);
        assertTrue(((Float) field(wave, "amp")) > 2.5f);
        touch(wave, MotionEvent.ACTION_DOWN, .8f);
        touch(wave, MotionEvent.ACTION_UP, .8f);
        assertEquals("Releasing a scrub must not jump back", .8f, progress(ui), .001f);
        assertEquals(192000L, ((Long) field(ui, "position")).longValue());
        View next = find(ui, "Next, hold to fast forward");
        assertNotNull(next);
        assertTrue(next.performLongClick());
        draw(wave);
        assertTrue("Hold-to-seek also straightens the wave", ((Float) field(wave, "amp")) < .05f);
        apply(ui, "new-track", 0, 180000, true);
        assertEquals(false, field(ui, "holdingSeek"));
        assertNull(field(ui, "cancelHeldSeek"));
        touch(wave, MotionEvent.ACTION_DOWN, .6f);
        apply(ui, "newer-track", 0, 180000, true);
        assertFalse("A drag from the old track cannot seek the new one", touch(wave, MotionEvent.ACTION_UP, .6f));
    }

    @Test public void reduceMotionUsesExactPositionsAndDetachStopsAnimations() throws Exception {
        SpatchUi ui = create();
        apply(ui, "same", 120000, 240000, false);
        finish(ui);
        SharedPreferences prefs = (SharedPreferences) field(ui, "prefs");
        prefs.edit().putBoolean("reduce_motion", true).apply();
        apply(ui, "next", 0, 180000, false);
        assertEquals(0, progress(ui), .001f);
        assertNull(field(field(ui, "progress"), "animator"));
        prefs.edit().putBoolean("reduce_motion", false).apply();
        call(ui, "setPosition", new Class<?>[]{long.class}, 90000L);
        assertNotNull(field(field(ui, "progress"), "animator"));
        ((ViewGroup) ui.getParent()).removeView(ui);
        assertNull(field(field(ui, "progress"), "animator"));
    }

    @Test public void pausedArtworkRingSettlesAndResumes() throws Exception {
        SpatchUi ui = create();
        SharedPreferences prefs = (SharedPreferences) field(ui, "prefs");
        prefs.edit().putBoolean("audio", false).apply();
        apply(ui, "same", 120000, 240000, true);
        call(ui, "openPlayer", new Class<?>[]{boolean.class}, false);
        ui.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(820, View.MeasureSpec.EXACTLY));
        ui.layout(0, 0, 412, 820);
        View disc = (View) field(ui, "disc");
        disc.layout(0, 0, 360, 360);
        Canvas canvas = new Canvas(Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888));
        for (int i = 0; i < 15; i++) ringFrame(disc, canvas);
        float[] levels = (float[]) field(disc, "level");
        float peak = max(levels);
        assertTrue(peak > .1f);
        apply(ui, "same", 120000, 240000, false);
        // Capture can retain old FFT data after pausing; it must not keep driving the ring.
        prefs.edit().putBoolean("audio", true).apply();
        shadowOf(ui.activity.getApplication()).grantPermissions(Audio.PERMISSION);
        java.util.Arrays.fill(Audio.BANDS, 1f);
        long wanted = (Long) field(Audio.class, "wantedUntil");
        try {
            ringFrame(disc, canvas);
            assertTrue("Pause eases down instead of snapping", max(levels) > .02f && max(levels) < peak);
            assertEquals("Paused frames must not renew audio capture", wanted, ((Long) field(Audio.class, "wantedUntil")).longValue());
            shadowOf(disc).clearWasInvalidated();
            ShadowLooper.idleMainLooper(32, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue("The paused ring requests another frame while settling", shadowOf(disc).wasInvalidated());
            for (int i = 0; i < 75; i++) ringFrame(disc, canvas);
            for (float level : levels) assertEquals("Every bar returns to rest", .02f, level, .001f);
            prefs.edit().putBoolean("audio", false).apply();
            apply(ui, "same", 120000, 240000, true);
            for (int i = 0; i < 10; i++) ringFrame(disc, canvas);
            assertTrue("Resume restarts the ring", max(levels) > .1f);
            prefs.edit().putBoolean("reduce_motion", true).apply();
            apply(ui, "same", 120000, 240000, false);
            ringFrame(disc, canvas);
            for (float level : levels) assertEquals("Reduce motion settles immediately", .02f, level, .001f);
        } finally { java.util.Arrays.fill(Audio.BANDS, 0f); }
    }

    private static void ringFrame(View disc, Canvas canvas) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(16));
        disc.draw(canvas);
    }

    private static float max(float[] values) {
        float max = 0;
        for (float value : values) max = Math.max(max, value);
        return max;
    }

    private static SpatchUi create() {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        return (SpatchUi) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
    }

    private static void apply(SpatchUi ui, String uri, long position, long duration, boolean playing) throws Exception {
        // Stamped with the demo core's own revision, so the periodic sync doesn't re-apply the core's (playing) state over it.
        long[] revisions = new long[5];
        java.lang.reflect.Method read = SpatchUi.class.getDeclaredMethod("nativeRevisions", long[].class);
        read.setAccessible(true);
        read.invoke(null, (Object) revisions);
        String[] state = {Long.toString(revisions[0]), "spotify:track:" + uri, "Song", "Artist", "Album", "demo:0",
            Boolean.toString(playing), "false", "0", Long.toString(position), Long.toString(duration), "Test",
            "", "", "", "false", "true", "true", "", ""};
        call(ui, "applyPlayer", new Class<?>[]{String[].class}, (Object) state);
    }

    private static void finish(SpatchUi ui) throws Exception {
        ValueAnimator animator = (ValueAnimator) field(field(ui, "progress"), "animator");
        if (animator != null) animator.setCurrentPlayTime(animator.getDuration());
    }

    private static float progress(SpatchUi ui) throws Exception {
        return (Float) call(ui, "shownProgress", new Class<?>[0]);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return Reflect.call(target, name, types, args);
    }

    private static Object field(Object target, String name) throws Exception {
        return Reflect.get(target, name);
    }

    private static void draw(View wave) {
        wave.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(58, View.MeasureSpec.EXACTLY));
        wave.layout(0, 0, 360, 58);
        Canvas canvas = new Canvas(Bitmap.createBitmap(360, 58, Bitmap.Config.ARGB_8888));
        for (int i = 0; i < 45; i++) wave.draw(canvas);
    }

    private static boolean touch(View wave, int action, float fraction) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 6 + (wave.getWidth() - 12) * fraction, 20, 0);
        try { return wave.onTouchEvent(event); } finally { event.recycle(); }
    }

    private static View find(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }
}
