package spatch.manager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Spatch's public GitHub releases: patch bundles are tagged patches-v<version> (bundle-* before; spatch-bundle.zip, plus
 * spatch-bundle-debug.zip with Developer options), manager builds manager-v<version>. The newest
 * published release of each kind wins.
 */
final class Releases {
    static final String REPO = "n0thhhing/spatch";
    static final String PAGE = "https://github.com/" + REPO + "/releases";

    /** A release's tag and the download URL of its asset. */
    static final class Release {
        final String tag, url, debugUrl; // debugUrl: the developer bundle, or null

        Release(String tag, String url, String debugUrl) {
            this.tag = tag;
            this.url = url;
            this.debugUrl = debugUrl;
        }
    }

    Release bundle, manager, nightly; // nightly: the bundle built from the newest commit, tagged nightly-<commit>

    /** The bundle to install: the nightly when asked for and published, the newest release otherwise. */
    Release bundle(boolean wantNightly) {
        return wantNightly && nightly != null ? nightly : bundle;
    }

    static Releases fetch() throws IOException {
        HttpURLConnection c = open("https://api.github.com/repos/" + REPO + "/releases?per_page=50", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            Bundle.copy(in, body); // InputStream.readAllBytes needs Android 13
            JSONArray list = new JSONArray(body.toString("UTF-8"));
            Releases r = new Releases();
            for (int i = 0; i < list.length(); i++) {
                JSONObject release = list.getJSONObject(i);
                String tag = release.getString("tag_name");
                if (tag.equals("nightly") && release.optBoolean("prerelease") && !release.optBoolean("draft")) {
                    String commit = release.optString("target_commitish", "");
                    String url = asset(release, "spatch-bundle.zip");
                    if (commit.length() >= 7 && url != null) r.nightly = new Release("nightly-" + commit.substring(0, 7), url, asset(release, "spatch-bundle-debug.zip"));
                    continue;
                }
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                if (r.bundle == null && (tag.startsWith("patches-v") || tag.startsWith("bundle-"))) r.bundle = new Release(tag, asset(release, "spatch-bundle.zip"), asset(release, "spatch-bundle-debug.zip"));
                if (r.manager == null && tag.startsWith("manager-v")) r.manager = new Release(tag, asset(release, "spatch-manager.apk"), null);
                if (r.bundle != null && r.bundle.url == null) r.bundle = null;
                if (r.manager != null && r.manager.url == null) r.manager = null;
            }
            return r;
        } catch (JSONException e) {
            throw new IOException("GitHub sent something unexpected.", e);
        } finally {
            c.disconnect();
        }
    }

    /** The version code a manager release's tag stands for (manager-v0.6.0 is 600; the first tags were bare codes), or 0. */
    static int code(Release r) {
        if (r == null) return 0;
        try {
            String[] v = r.tag.substring("manager-v".length()).split("\\.");
            return v.length == 3 ? Integer.parseInt(v[0]) * 10000 + Integer.parseInt(v[1]) * 100 + Integer.parseInt(v[2]) : Integer.parseInt(v[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** What a release is called on screen: its version, without the tag's prefix. */
    static String version(Release r) {
        if (r.tag.startsWith("nightly-")) return "nightly " + r.tag.substring("nightly-".length());
        int dash = r.tag.lastIndexOf("-v");
        return dash >= 0 ? r.tag.substring(dash + 2) : r.tag;
    }

    /** The download URL of the release's asset with that name, or null. */
    private static String asset(JSONObject release, String name) throws JSONException {
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").equals(name)) return a.getString("browser_download_url");
        }
        return null;
    }

    static void download(String url, File to, Bundle.Progress progress, String what) throws IOException {
        HttpURLConnection c = open(url, "application/octet-stream");
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            long total = c.getContentLengthLong(), done = 0;
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0;) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) progress.step(what, (int) (done * 100 / total), 100);
            }
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(to)) throw new IOException("Couldn't save " + to.getName() + ".");
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Accept", accept);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "SpatchManager");
        if (c.getResponseCode() / 100 != 2) {
            int code = c.getResponseCode();
            c.disconnect();
            throw new IOException("GitHub answered " + code + ".");
        }
        return c;
    }
}
