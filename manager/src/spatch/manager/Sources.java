package spatch.manager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A private Spatch source: someone's server holding the exact APKM a patch bundle needs, behind a
 * login per person. An invite is the source's index URL with that person's name and code after a
 * #, so one paste gives the manager both; neither is built into the app. The bundle pins the
 * APKM's hash, so a source can only offer the file the patches were made for.
 */
final class Sources {
    private static final int MAX_INDEX = 64 * 1024, MAX_REDIRECTS = 5, TRIES = 2;
    private static final long MAX_APKM = 1L << 30;

    /** A pasted invite: where the index is and whose login to send there. */
    static final class Invite {
        final URL index;
        final String name, code;

        private Invite(URL index, String name, String code) {
            this.index = index;
            this.name = name;
            this.code = code;
        }

        String host() {
            return index.getHost();
        }

        String basic() {
            return "Basic " + java.util.Base64.getEncoder().encodeToString((name + ":" + code).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** One APKM the index offers. */
    static final class Entry {
        final long versionCode, size;
        final String sha256;
        final int minSdk;
        final List<String> abis = new ArrayList<>(), urls = new ArrayList<>();

        Entry(long versionCode, String sha256, long size, int minSdk) {
            this.versionCode = versionCode;
            this.sha256 = sha256;
            this.size = size;
            this.minSdk = minSdk;
        }
    }

    /** Reads an invite such as https://host/spatch/index.json#alex.k7m2p9; null when it isn't one. */
    static Invite parse(String text) {
        if (text == null) return null;
        text = text.trim();
        int hash = text.indexOf('#');
        if (hash < 0) return null;
        String login = text.substring(hash + 1);
        int dot = login.indexOf('.');
        if (dot <= 0 || dot == login.length() - 1) return null;
        try {
            URL index = new URL(text.substring(0, hash));
            if (!allowed(index) || index.getUserInfo() != null) return null;
            return new Invite(index, login.substring(0, dot), login.substring(dot + 1));
        } catch (java.net.MalformedURLException e) {
            return null;
        }
    }

    /** HTTPS only; plain HTTP just for this phone itself, which is how a source is tested over USB. */
    static boolean allowed(URL url) {
        String host = url.getHost();
        return "https".equals(url.getProtocol()) || "http".equals(url.getProtocol()) && ("localhost".equals(host) || "127.0.0.1".equals(host));
    }

    /** The index entry for exactly the bundle's APKM, checked against this phone. */
    static Entry find(Invite invite, Bundle bundle) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpURLConnection c = open(invite, invite.index);
        try (InputStream in = c.getInputStream()) {
            copy(in, body, MAX_INDEX, null, null);
        } finally {
            c.disconnect();
        }
        Entry match;
        try {
            match = match(new JSONObject(body.toString("UTF-8")), bundle.apkmSha, bundle.spotifyCode);
        } catch (JSONException e) {
            throw new IOException("The Spatch source sent an index this manager can't read.", e);
        }
        if (match == null) throw new IOException("The Spatch source doesn't have Spotify " + bundle.spotify + " yet, which these patches need. Ask whoever runs it to add it.");
        if (android.os.Build.VERSION.SDK_INT < match.minSdk) throw new IOException("This Spotify needs a newer version of Android than this phone has.");
        boolean abi = false;
        for (String a : android.os.Build.SUPPORTED_ABIS) abi |= match.abis.contains(a);
        if (!abi) throw new IOException("This Spotify is built for " + String.join(", ", match.abis) + " phones, and this one isn't.");
        return match;
    }

    /** Only an entry for the bundle's own hash and version counts; anything else in the index is ignored. */
    static Entry match(JSONObject index, String sha256, long versionCode) throws JSONException {
        if (index.getInt("schema") != 1) throw new JSONException("schema " + index.get("schema"));
        if (!"com.spotify.music".equals(index.getString("package"))) return null;
        JSONArray sources = index.getJSONArray("sources");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.getJSONObject(i);
            if (!sha256.equals(s.getString("sha256")) || s.getLong("versionCode") != versionCode) continue;
            long size = s.getLong("size");
            if (size <= 0 || size > MAX_APKM) throw new JSONException("size " + size);
            Entry e = new Entry(versionCode, sha256, size, s.getInt("minSdk"));
            JSONArray abis = s.getJSONArray("abis"), urls = s.getJSONArray("urls");
            for (int j = 0; j < abis.length(); j++) e.abis.add(abis.getString(j));
            for (int j = 0; j < urls.length(); j++) e.urls.add(urls.getString(j));
            if (e.urls.isEmpty()) throw new JSONException("no urls");
            return e;
        }
        return null;
    }

    /**
     * Downloads the entry into to. Bytes go to a .part file first and only replace to once their
     * length and hash match the entry, so an interrupted or damaged download never becomes the
     * saved APKM and a working one is kept until its replacement checks out. Each URL gets a couple
     * of tries, then the next one is used.
     */
    static void download(Invite invite, Entry entry, File to, Bundle.Progress progress) throws IOException {
        File part = new File(to.getPath() + ".part");
        File dir = to.getParentFile();
        if (dir != null && dir.getUsableSpace() < entry.size + (64L << 20)) throw new IOException("There isn't enough free space for Spotify (" + (entry.size >> 20) + " MB). Free some up and try again.");
        IOException last = null;
        for (String url : entry.urls) {
            for (int attempt = 0; attempt < TRIES; attempt++) {
                part.delete();
                try {
                    URL from = new URL(invite.index, url);
                    HttpURLConnection c = open(invite, from);
                    String sha;
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                        long declared = c.getContentLengthLong();
                        if (declared >= 0 && declared != entry.size) throw new IOException("The Spatch source offered a file of the wrong size.");
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        long got = copy(in, out, entry.size, digest, progress);
                        if (got != entry.size) throw new IOException("The download stopped early. Try again.");
                        sha = Bundle.hex(digest.digest());
                    } finally {
                        c.disconnect();
                    }
                    if (!sha.equals(entry.sha256)) throw new IOException("The downloaded Spotify is damaged or isn't the right one.");
                    if (!part.renameTo(to)) throw new IOException("Couldn't save the downloaded Spotify.");
                    return;
                } catch (Denied e) {
                    part.delete();
                    throw e; // a refused login won't get better with retries
                } catch (IOException e) {
                    last = e;
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IOException(e);
                }
            }
        }
        part.delete();
        throw last != null ? last : new IOException("The Spatch source lists nowhere to download from.");
    }

    /** The source turned the login down. */
    static final class Denied extends IOException {
        Denied() {
            super("The Spatch source didn't accept this invite. Ask whoever runs it for a new one.");
        }
    }

    /** Follows a few redirects by hand: each must stay allowed, and the login only goes to the invite's own host. */
    private static HttpURLConnection open(Invite invite, URL url) throws IOException {
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!allowed(url)) throw new IOException("The Spatch source pointed somewhere that isn't HTTPS.");
            HttpURLConnection c;
            try {
                c = (HttpURLConnection) url.openConnection();
            } catch (IOException e) {
                throw offline(e);
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "SpatchManager");
            if (url.getHost().equalsIgnoreCase(invite.host()) && url.getPort() == invite.index.getPort()) c.setRequestProperty("Authorization", invite.basic());
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                throw offline(e);
            }
            if (code / 100 == 2) return c;
            String next = c.getHeaderField("Location");
            c.disconnect();
            if (code == 401 || code == 403) throw new Denied();
            if (code / 100 == 3 && next != null) {
                url = new URL(url, next);
                continue;
            }
            if (code == 404) throw new IOException("The Spatch source is missing a file it lists. Ask whoever runs it to check.");
            throw new IOException("The Spatch source answered " + code + ". Try again later.");
        }
        throw new IOException("The Spatch source redirected too many times.");
    }

    private static IOException offline(IOException e) {
        return new IOException("Couldn't reach the Spatch source. Check your connection and try again.", e);
    }

    /** Copies at most limit bytes (more is an error), feeding digest and progress on the way. */
    private static long copy(InputStream in, OutputStream out, long limit, MessageDigest digest, Bundle.Progress progress) throws IOException {
        byte[] buf = new byte[1 << 16];
        long done = 0;
        int shown = -1;
        for (int n; (n = in.read(buf)) > 0;) {
            done += n;
            if (done > limit) throw new IOException("The Spatch source sent more than it should have.");
            out.write(buf, 0, n);
            if (digest != null) digest.update(buf, 0, n);
            int percent = (int) (done * 100 / limit);
            if (progress != null && percent != shown) {
                shown = percent;
                progress.step("Downloading Spotify (" + (done >> 20) + " of " + (limit >> 20) + " MB)", percent, 100);
            }
        }
        return done;
    }
}
