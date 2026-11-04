package spatch.manager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Spatch's public GitHub releases and recent commits: patch bundles are tagged patches-v<version>
 * (bundle-* before; spatch-bundle.zip, plus spatch-bundle-debug.zip with Developer options), manager
 * builds manager-v<version>. The newest published release of each kind wins.
 */
final class Releases {
    static final String REPO = "n0thhhing/spatch";
    static final String PAGE = "https://github.com/" + REPO + "/releases";

    /** A release's tag, the download URL of its asset, and its release notes. */
    static final class Release {
        final String tag, url, debugUrl, notes; // debugUrl: the developer bundle, or null

        Release(String tag, String url, String debugUrl) {
            this(tag, url, debugUrl, "");
        }

        Release(String tag, String url, String debugUrl, String notes) {
            this.tag = tag;
            this.url = url;
            this.debugUrl = debugUrl;
            this.notes = notes == null ? "" : notes;
        }
    }

    /** One entry in the Changes tab: a published release or a recent commit. */
    static final class Note {
        final String tag, title, date, body;

        Note(String tag, String title, String date, String body) {
            this.tag = tag;
            this.title = title;
            this.date = date;
            this.body = body;
        }
    }

    Release bundle, manager, nightly; // nightly: the bundle built from the newest commit, tagged nightly-<commit>
    final List<Note> notes = new ArrayList<>();
    final List<Note> commits = new ArrayList<>();

    /** The bundle to install: the nightly when asked for and published, the newest release otherwise. */
    Release bundle(boolean wantNightly) {
        return wantNightly && nightly != null ? nightly : bundle;
    }

    static Releases fetch() throws IOException {
        String releasesJson = get("https://api.github.com/repos/" + REPO + "/releases?per_page=50");
        String commitsJson = null;
        try {
            commitsJson = get("https://api.github.com/repos/" + REPO + "/commits?per_page=15");
        } catch (IOException ignored) {
            // Release notes still show when the commit log cannot be fetched.
        }
        return parse(releasesJson, commitsJson);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url, "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            Bundle.copy(in, body); // InputStream.readAllBytes needs Android 13
            return body.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static Releases parse(String releasesJson, String commitsJson) throws IOException {
        try {
            JSONArray list = new JSONArray(releasesJson);
            Releases r = new Releases();
            for (int i = 0; i < list.length(); i++) {
                JSONObject release = list.getJSONObject(i);
                String tag = release.getString("tag_name");
                String body = cleanBody(release.optString("body", ""));
                if (tag.equals("nightly") && release.optBoolean("prerelease") && !release.optBoolean("draft")) {
                    String commit = release.optString("target_commitish", "");
                    String url = asset(release, "spatch-bundle.zip");
                    if (commit.length() >= 7 && url != null) r.nightly = new Release("nightly-" + commit.substring(0, 7), url, asset(release, "spatch-bundle-debug.zip"), body);
                    continue;
                }
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                if (r.bundle == null && (tag.startsWith("patches-v") || tag.startsWith("bundle-"))) r.bundle = new Release(tag, asset(release, "spatch-bundle.zip"), asset(release, "spatch-bundle-debug.zip"), body);
                if (r.manager == null && tag.startsWith("manager-v")) r.manager = new Release(tag, asset(release, "spatch-manager.apk"), null, body);
                if (r.bundle != null && r.bundle.url == null) r.bundle = null;
                if (r.manager != null && r.manager.url == null) r.manager = null;
                if (!body.isEmpty() && r.notes.size() < 12) {
                    String title = release.optString("name", "").trim();
                    String published = release.optString("published_at", "");
                    String date = published.length() >= 10 ? published.substring(0, 10) : "";
                    r.notes.add(new Note(tag, title.isEmpty() ? tag : title, date, body));
                }
            }
            if (commitsJson != null && !commitsJson.isEmpty()) {
                JSONArray items = new JSONArray(commitsJson);
                for (int i = 0; i < items.length() && r.commits.size() < 12; i++) {
                    JSONObject item = items.getJSONObject(i);
                    String sha = item.optString("sha", "");
                    String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
                    JSONObject commit = item.optJSONObject("commit");
                    if (commit == null || shortSha.isEmpty()) continue;
                    String msg = commit.optString("message", "").replace("\r", "").trim();
                    if (msg.isEmpty()) continue;
                    int nl = msg.indexOf('\n');
                    String subject = (nl >= 0 ? msg.substring(0, nl) : msg).trim();
                    String detail = nl >= 0 ? cleanBody(msg.substring(nl + 1)) : "";
                    JSONObject who = commit.optJSONObject("committer");
                    String rawDate = who != null ? who.optString("date", "") : "";
                    String date = rawDate.length() >= 10 ? rawDate.substring(0, 10) : "";
                    r.commits.add(new Note("nightly-" + shortSha, subject, date, detail));
                }
            }
            return r;
        } catch (JSONException e) {
            throw new IOException("GitHub sent something unexpected.", e);
        }
    }

    /** Strips basic markdown punctuation so release notes read cleanly in plain TextViews. */
    static String cleanBody(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : raw.replace("\r", "").split("\n")) {
            String s = line.trim();
            if (s.startsWith("- ") || s.startsWith("* ")) s = "• " + s.substring(2).trim();
            else if (s.startsWith("### ")) s = s.substring(4).trim();
            else if (s.startsWith("## ")) s = s.substring(3).trim();
            else if (s.startsWith("# ")) s = s.substring(2).trim();
            s = s.replace("**", "").replace("`", "");
            if (s.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            } else {
                if (out.length() > 0) out.append('\n');
                out.append(s);
            }
        }
        return out.toString().trim();
    }

    /** First line of a release's notes for the inline preview on the Spatch tab. */
    static String summary(String notes) {
        if (notes == null || notes.isEmpty()) return "";
        int nl = notes.indexOf('\n');
        return (nl >= 0 ? notes.substring(0, nl) : notes).trim();
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
