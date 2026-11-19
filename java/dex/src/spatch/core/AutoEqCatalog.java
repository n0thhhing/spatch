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
    static final class Headphone {
        final String name, query, url;
        Headphone(String name, String url) { this.name = name; this.query = name.toLowerCase(Locale.ROOT); this.url = url; }
    }

    static List<Headphone> parse(String markdown) {
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
        URI uri = URI.create(address);
        if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || !uri.getPath().startsWith(ROOT))
            throw new IllegalArgumentException("Unsupported headphone profile address");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("The headphone download failed (HTTP " + connection.getResponseCode() + ")");
            try (InputStream stream = connection.getInputStream()) { return read(stream, limit); }
        } finally { connection.disconnect(); }
    }

    private static String read(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = stream.read(buffer)) != -1;) {
            if (bytes.size() + count > limit) throw new IllegalArgumentException("The headphone data is too large");
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
