package spatch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Recommended headphone corrections from the AutoEq project; no bundled copy of its database. */
final class AutoEqCatalog {
    private static final String ROOT = "/jaakkopasanen/AutoEq/master/results/";
    private static final String HOST = "raw.githubusercontent.com";
    private static volatile List<Headphone> fallbackCatalog;

    static final class Headphone {
        final String name, query, url;
        Headphone(String name, String url) { this.name = name; this.query = name.toLowerCase(Locale.ROOT); this.url = url; }
    }

    private static native int nativeLoad(String dir, byte[] markdown);
    private static native byte[] nativeSearch(String query, int limit);

    private static List<Headphone> unpackPairs(byte[] packed) {
        if (packed == null || packed.length == 0) return new ArrayList<>();
        List<Headphone> result = new ArrayList<>();
        int pos = 0, end = packed.length;
        while (pos + 4 <= end) {
            int nlen = (packed[pos] & 0xff) | ((packed[pos + 1] & 0xff) << 8)
                | ((packed[pos + 2] & 0xff) << 16) | ((packed[pos + 3] & 0xff) << 24);
            pos += 4;
            if (nlen < 0 || pos + nlen + 4 > end) break;
            String name = new String(packed, pos, nlen, StandardCharsets.UTF_8);
            pos += nlen;
            int ulen = (packed[pos] & 0xff) | ((packed[pos + 1] & 0xff) << 8)
                | ((packed[pos + 2] & 0xff) << 16) | ((packed[pos + 3] & 0xff) << 24);
            pos += 4;
            if (ulen < 0 || pos + ulen > end) break;
            String url = new String(packed, pos, ulen, StandardCharsets.UTF_8);
            pos += ulen;
            result.add(new Headphone(name, url));
        }
        return result;
    }

    static List<Headphone> parse(String markdown) {
        return parseJava(markdown);
    }

    static List<Headphone> parseJava(String markdown) {
        List<Headphone> result = new ArrayList<>();
        for (String line : markdown.split("\\r?\\n")) {
            if (!line.startsWith("- [") || !line.endsWith(")")) continue;
            int divider = line.indexOf("](");
            if (divider < 4) continue;
            String name = line.substring(3, divider), link = line.substring(divider + 2, line.length() - 1);
            if (!link.startsWith("./")) continue;
            try {
                URI relative = URI.create(link);
                String path = relative.getPath();
                if (relative.getQuery() != null || relative.getFragment() != null || path.indexOf('\\') >= 0) continue;
                URI folder = new URI("https", HOST, ROOT + path.substring(2), null).normalize();
                String decoded = folder.getPath();
                if (!decoded.startsWith(ROOT) || decoded.substring(ROOT.length()).indexOf('/') < 0) continue;
                String filename = decoded.substring(decoded.lastIndexOf('/') + 1);
                URI profile = new URI("https", HOST, decoded + "/" + filename + " GraphicEQ.txt", null);
                result.add(new Headphone(name, profile.toASCIIString()));
            } catch (IllegalArgumentException | java.net.URISyntaxException ignored) {
                // A malformed listing must not hide the remaining valid models.
            }
        }
        return result;
    }

    /** Loads the catalog into Rust's binary index (downloading once if absent) and returns the model count. */
    static int loadIndex(File directory) throws Exception {
        try {
            String dir = directory.getAbsolutePath();
            int count = nativeLoad(dir, null);
            if (count > 0) return count;
            byte[] bytes = downloadBytes("https://" + HOST + ROOT + "README.md", 4 * 1024 * 1024);
            count = nativeLoad(dir, bytes);
            if (count <= 0) throw new IllegalStateException("The headphone database returned no models");
            return count;
        } catch (UnsatisfiedLinkError ignored) {
            List<Headphone> list = load(directory);
            fallbackCatalog = list;
            return list.size();
        }
    }

    static List<Headphone> search(String query, int limit) {
        try {
            byte[] packed = nativeSearch(query, limit);
            if (packed != null) return unpackPairs(packed);
        } catch (UnsatisfiedLinkError ignored) {
            // Fall back to pure Java if native library is absent.
        }
        List<Headphone> list = fallbackCatalog;
        List<Headphone> out = new ArrayList<>();
        if (list == null) return out;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return out;
        for (Headphone h : list) {
            if (!h.query.contains(q)) continue;
            out.add(h);
            if (out.size() >= limit) break;
        }
        return out;
    }

    static List<Headphone> load(File directory) throws Exception {
        File cache = new File(directory, "autoeq-headphones.md");
        if (cache.isFile()) {
            try (InputStream stream = new FileInputStream(cache)) {
                List<Headphone> cached = parse(read(stream, 4 * 1024 * 1024));
                if (!cached.isEmpty()) return cached;
            } catch (java.io.IOException | IllegalArgumentException ignored) {
                // Recover a truncated cache by downloading a fresh listing.
            }
        }
        String text = download("https://" + HOST + ROOT + "README.md", 4 * 1024 * 1024);
        List<Headphone> result = parse(text);
        if (result.isEmpty()) throw new IllegalStateException("The headphone database returned no models");
        File pending = new File(directory, "autoeq-headphones.tmp");
        try (FileOutputStream stream = new FileOutputStream(pending)) { stream.write(text.getBytes(StandardCharsets.UTF_8)); }
        if (!pending.renameTo(cache)) pending.delete();
        return result;
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

    private static String read(InputStream stream, int limit) throws Exception {
        return new String(readBytes(stream, limit), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = stream.read(buffer)) != -1;) {
            if (bytes.size() + count > limit) throw new IllegalArgumentException("The headphone data is too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
}
