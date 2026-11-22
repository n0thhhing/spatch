package spatch;

import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

/**
 * The extended-metadata service refuses the app's own token (502) but accepts a web-player one.
 * Swaps the bearer on that one webgate request; every other request, and any failure here, keeps
 * the app's own token. Shares PublicTrackMetadata's anonymous embed session rather than minting a
 * second one.
 */
public final class WebAuth {
    private static final String TAG = "SpatchBridge";
    private static Field urlField;
    private static Method pathMethod;
    private static int misses;
    private static boolean hitLogged;

    private WebAuth() {
    }

    public static String auth(Object request, String fallback) {
        try {
            return match(request) ? PublicTrackMetadata.anonymousToken() : fallback;
        } catch (Throwable failed) {
            Log.w(TAG, "web-player auth: " + failed);
            return fallback;
        }
    }

    // The request's URL field has its own toString(), but that returns one raw component, not the
    // assembled URL; its i() method rebuilds the real java.net.URI and is what to read instead.
    // Logs the first hit and the first few misses: startup sends several unrelated webgate requests
    // before any song details are asked for, so a plain first-N window never reaches the real one.
    private static boolean match(Object request) throws ReflectiveOperationException {
        if (urlField == null) urlField = request.getClass().getField("a");
        Object url = urlField.get(request);
        String path = null;
        if (url != null) {
            if (pathMethod == null) pathMethod = url.getClass().getMethod("i");
            Object uri = pathMethod.invoke(url);
            path = uri instanceof URI ? ((URI) uri).getPath() : null;
        }
        boolean hit = path != null && path.contains("extended-metadata");
        if (hit ? !hitLogged : misses < 6) {
            if (hit) hitLogged = true;
            else misses++;
            Log.i(TAG, (hit ? "hit " : "miss ") + path);
        }
        return hit;
    }
}
