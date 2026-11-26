package spatch;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.time.Duration;
import java.util.List;
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
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the same sandbox as the other tests: the Rust library loads once per sandbox
public class QueueDragTest {
    @Test public void holdingAndDraggingAQueuedSongMovesIt() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        Reflect.settle();
        SpatchUi ui = Reflect.find(root, SpatchUi.class);
        for (int i = 3; i <= 5; i++) Reflect.call(ui, "command", new Class<?>[]{int.class, long.class, String.class}, 9, 0L, "spotify:preview:" + i);
        Reflect.settle();
        sync(ui);
        Reflect.call(ui, "openQueue", new Class<?>[0]);
        Reflect.settle();
        List<?> before = new java.util.ArrayList<>((List<?>) Reflect.get(ui, "upcoming"));
        assertTrue("Three songs we queued ourselves", rows(root).size() >= 3);
        View row = rows(root).get(0);
        int i = (Integer) findField(row.getClass(), "index").get(row); // other tests may have queued songs before ours
        String first = title(before.get(i)), second = title(before.get(i + 1)), third = title(before.get(i + 2));
        int[] at = new int[2];
        row.getLocationOnScreen(at);
        float x = at[0] + row.getWidth() / 2f, y = at[1] + row.getHeight() / 2f, h = row.getHeight();
        long down = SystemClock.uptimeMillis();
        dispatch(row, down, MotionEvent.ACTION_DOWN, x, y);
        ShadowLooper.idleMainLooper(android.view.ViewConfiguration.getLongPressTimeout() + 50, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue("Holding lifts the row", row.getTranslationZ() > 0 || row.getScaleX() > 1f || row.getBackground() != null);
        for (int step = 1; step <= 10; step++) dispatch(row, down, MotionEvent.ACTION_MOVE, x, y + h * 2 * step / 10f);
        assertEquals("The row it passed slides up out of the way", -h, (float) rows(root).get(1).getTag(), 1f);
        dispatch(row, down, MotionEvent.ACTION_UP, x, y + h * 2);
        Reflect.settle();
        sync(ui);
        List<?> after = (List<?>) Reflect.get(ui, "upcoming");
        assertEquals("Dropped two rows down", first, title(after.get(i + 2)));
        assertEquals(second, title(after.get(i)));
        assertEquals(third, title(after.get(i + 1)));
        activity.finish();
    }

    /** Applies the demo core's player state (the window-visibility check keeps the UI's own poll from running here). */
    private static void sync(SpatchUi ui) throws Exception {
        java.lang.reflect.Method state = SpatchUi.class.getDeclaredMethod("widgetState", long.class);
        state.setAccessible(true);
        Reflect.call(ui, "applyPlayer", new Class<?>[]{String[].class}, (Object) state.invoke(null, -1L));
        Reflect.settle();
    }

    private static void dispatch(View row, long down, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        ((View) row.getParent()).getRootView().dispatchTouchEvent(e);
        e.recycle();
    }

    private static String title(Object entry) throws Exception {
        java.lang.reflect.Field f = entry.getClass().getSuperclass() == Object.class ? entry.getClass().getDeclaredField("title") : findField(entry.getClass(), "title");
        f.setAccessible(true);
        return (String) f.get(entry);
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException next) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static List<View> rows(View view) {
        List<View> out = new java.util.ArrayList<>();
        if (view.getClass().getSimpleName().equals("QueueRow")) out.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out.addAll(rows(((ViewGroup) view).getChildAt(i)));
        return out;
    }

}
