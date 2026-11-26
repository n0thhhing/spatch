package spatch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Recommended headphone corrections from the AutoEq project; no bundled copy of its database. */
final class AutoEqCatalog {
    private static final String ROOT = "/jaakkopasanen/AutoEq/master/results/";
    private static final String HOST = "raw.githubusercontent.com";

    static final class Headphone {
        final String name, url;
        Headphone(String name, String url) { this.name = name; this.url = url; }
    }

    static native int nativeLoad(String dir, byte[] markdown);
    private static native byte[] nativeSearch(String query, int limit);

    private static List<Headphone> unpackPairs(byte[] packed) {
        String[] flat = PackedStrings.unpackStrings(packed);
        List<Headphone> result = new ArrayList<>(flat.length / 2);
        for (int i = 0; i + 1 < flat.length; i += 2) {
            result.add(new Headphone(flat[i], flat[i + 1]));
        }
        return result;
    }

    /** Loads the catalog into Rust's binary index (downloading once if absent) and returns the model count. */
    static int loadIndex(File directory) throws Exception {
        String dir = directory.getAbsolutePath();
        int count = nativeLoad(dir, null);
        if (count > 0) return count;
        byte[] bytes = downloadBytes("https://" + HOST + ROOT + "README.md", 4 * 1024 * 1024);
        count = nativeLoad(dir, bytes);
        if (count <= 0) throw new IllegalStateException("The headphone database returned no models");
        return count;
    }

    static List<Headphone> search(String query, int limit) {
        return unpackPairs(nativeSearch(query, limit));
    }

    static String profile(Headphone headphone) throws Exception { return download(headphone.url, 256 * 1024); }

    private static String download(String address, int limit) throws Exception {
        return new String(downloadBytes(address, limit), StandardCharsets.UTF_8);
    }

    private static byte[] downloadBytes(String address, int limit) throws Exception {
        URI uri = URI.create(address);
        if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || !uri.getPath().startsWith(ROOT))
            throw new IllegalArgumentException("Unsupported headphone profile address");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("The headphone download failed (HTTP " + connection.getResponseCode() + ")");
            try (InputStream stream = connection.getInputStream()) { return readBytes(stream, limit); }
        } finally { connection.disconnect(); }
    }

    static byte[] readBytes(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = stream.read(buffer)) != -1;) {
            if (bytes.size() + count > limit) throw new IllegalArgumentException("The headphone data is too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
}
