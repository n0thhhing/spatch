package spatch;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SpringTest {
    @Test public void overshootsThenSettlesTheSameAtAnyFrameRate() {
        for (float hz : new float[] { 60f, 120f }) {
            SpatchUi.Spring s = new SpatchUi.Spring(0f, 14f, .35f, v -> {});
            s.to(1f);
            float peak = 0, v = 0;
            for (int i = 0; i < hz * 2; i++) { v = s.advance(1 / hz); peak = Math.max(peak, v); }
            assertTrue(hz + " Hz: wobbles past the target", peak > 1.2f && peak < 1.45f);
            assertEquals(hz + " Hz: settles on it", 1f, v, 1e-6f);
        }
    }
}
