package spatch;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PublicCatalogTest {
    @Test public void decodesOnlyRequestedCatalogTracks() throws Exception {
        PublicTrackMetadataTest.main(new String[0]);
    }
}
