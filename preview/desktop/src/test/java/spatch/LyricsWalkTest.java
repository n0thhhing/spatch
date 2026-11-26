package spatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.Spanned;
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
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w412dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LyricsWalkTest {
    @Test public void theSungWordLiftsAndGlows() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = find(root, SpatchUi.class);
        // The demo's first line starts at 3 s and times its five words across 4 s: stand 80 ms into the second word.
        Reflect.set(ui, "playing", false);
        Reflect.set(ui, "position", 3000L + 800L + 80L);
        Reflect.call(ui, "openLyrics", new Class<?>[0]);
        settle();
        LyricsView.SungWord word = null;
        for (TextView line : texts(root)) {
            if (!(line.getText() instanceof Spanned)) continue;
            Spanned text = (Spanned) line.getText();
            LyricsView.SungWord[] found = text.getSpans(0, text.length(), LyricsView.SungWord.class);
            for (LyricsView.SungWord w : found) if (w.lift > 0) word = w;
        }
        assertNotNull("The word being sung carries the lift", word);
        assertTrue("It glows", word.glow > 0);
        int width = root.getWidth(), height = root.getHeight();
        Bitmap shot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(shot));
        File file = new File("build/reports/preview/lyrics-walk.png");
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) { assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, out)); }
        // Past the end of the first line (ends[0] = 8500 ms, next line at 9000 ms), the last word settles back down.
        Reflect.set(ui, "position", 3000L + 5500L + 350L);
        settle();
        LyricsView.SungWord settled = null;
        for (TextView line : texts(root)) {
            if (!(line.getText() instanceof Spanned)) continue;
            Spanned text = (Spanned) line.getText();
            LyricsView.SungWord[] found = text.getSpans(0, text.length(), LyricsView.SungWord.class);
            for (LyricsView.SungWord w : found) if (w.lift > .05f || w.glow > .05f) settled = w;
        }
        assertNull("Past the end of the last word on a line, lift and glow settle back to zero", settled);
        activity.finish();
    }

    @Test public void pausedLyricsStopRedrawing() throws Exception {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        settle();
        SpatchUi ui = find(root, SpatchUi.class);
        Reflect.set(ui, "playing", false);
        Reflect.set(ui, "position", 3000L + 800L + 80L);
        Reflect.call(ui, "openLyrics", new Class<?>[0]);
        settle();
        TextView sung = null;
        for (TextView line : texts(root)) {
            if (!(line.getText() instanceof Spanned)) continue;
            Spanned text = (Spanned) line.getText();
            for (LyricsView.SungWord w : text.getSpans(0, text.length(), LyricsView.SungWord.class)) {
                if (w.lift > 0) sung = line;
            }
        }
        assertNotNull("The sung line is on screen", sung);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(300));
        int redraws = 0;
        for (int i = 0; i < 40; i++) {
            shadowOf(sung).clearWasInvalidated();
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(25));
            if (shadowOf(sung).wasInvalidated()) redraws++;
        }
        assertEquals("Paused lyrics should not request redraws", 0, redraws);
        activity.finish();
    }

    private static java.util.List<TextView> texts(View view) {
        java.util.List<TextView> out = new java.util.ArrayList<>();
        if (view instanceof TextView) out.add((TextView) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out.addAll(texts(((ViewGroup) view).getChildAt(i)));
        return out;
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
