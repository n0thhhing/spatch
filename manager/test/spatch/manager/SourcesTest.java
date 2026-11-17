package spatch.manager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Sources' invite parsing and verified download, on the desktop against an in-process server.
 * Index parsing uses Android's org.json and is checked on a phone instead.
 *
 *   javac -d /tmp/st -cp <android.jar> manager/src/spatch/manager/{Bundle,Clone,Releases,Sources}.java manager/test/spatch/manager/SourcesTest.java
 *   java -cp /tmp/st:<android.jar> spatch.manager.SourcesTest   (android.jar only supplies org.json for the verifier)
 */
public final class SourcesTest {
    private static final byte[] APKM = new byte[300_000];
    private static String sha;
    private static volatile String lastAuth;
    private static int failures;
    private static volatile int bundleDownloads;
    private static byte[] validBundle;

    public static void main(String[] args) throws Exception {
        new java.util.Random(7).nextBytes(APKM);
        sha = Bundle.hex(MessageDigest.getInstance("SHA-256").digest(APKM));
        validBundle = bundleZip();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/", SourcesTest::serve);
        server.start();
        String base = "http://localhost:" + port + "/";
        try {
            check(Sources.parse("https://example.org/spatch/index.json#alex.k7m2p9") != null, "an https invite parses");
            Sources.Invite good = Sources.parse(base + "index.json#alex.secret");
            check(good != null && good.name.equals("alex") && good.code.equals("secret"), "name and code come from after the #");
            check(Sources.parse("http://example.org/index.json#alex.secret") == null, "plain http is refused off this phone");
            check(Sources.parse("https://example.org/index.json") == null, "an invite needs a login");
            check(Sources.parse("https://example.org/index.json#alex.") == null, "and a code");
            check(Sources.parse("https://bob:pw@example.org/index.json#alex.secret") == null, "no credentials in the URL itself");
            check(Sources.parse("ftp://example.org/index.json#alex.secret") == null, "only http(s)");

            File dir = Files.createTempDirectory("sources").toFile(), to = new File(dir, "spotify.apkm");
            ok(good, "good", to, "a matching download is saved");
            check(java.util.Arrays.equals(Files.readAllBytes(to.toPath()), APKM) && !new File(to + ".part").exists(), "with exactly the source's bytes and no leftovers");
            ok(good, "chunked", to, "a missing Content-Length is fine when the bytes check out");

            Files.write(to.toPath(), "previous".getBytes());
            fails(Sources.parse(base + "index.json#alex.wrong"), "good", to, "didn't accept", "a wrong code is refused");
            fails(good, "short", to, "stopped early", "a truncated download fails");
            fails(good, "corrupt", to, "damaged", "a damaged download fails");
            fails(good, "long", to, "wrong size", "a size that doesn't match fails");
            fails(good, "toplain", to, "isn't HTTPS", "a redirect off HTTPS is refused");
            check(new String(Files.readAllBytes(to.toPath())).equals("previous") && !new File(to + ".part").exists(), "and none of them touch the saved file");

            lastAuth = "unset";
            Sources.Invite other = Sources.parse("http://127.0.0.1:" + port + "/index.json#alex.secret");
            ok(other, "elsewhere", to, "a redirect to another host can serve the file");
            check(lastAuth == null, "and the login is not sent to that host");

            File cache = new File(dir, "patches.zip");
            Files.write(cache.toPath(), new byte[] {1, 2, 3});
            try (Bundle bundle = Releases.openBundle(base + "bundle.zip", cache, (w, d, t) -> {})) {
                check(bundle.spotifyCode == 148373686L && bundleDownloads == 1, "invalid cached ZIP is downloaded once");
            }
            try (Bundle bundle = Releases.openBundle(base + "bundle.zip", cache, (w, d, t) -> {})) {
                check(bundle.spotifyCode == 148373686L && bundleDownloads == 1, "valid cache avoids a download");
            }
            byte[] damaged = validBundle.clone();
            int patchOffset = 30 + "p/0".length(); // first entry is stored, so its data follows the local header
            damaged[patchOffset] ^= 1;
            Files.write(cache.toPath(), damaged);
            try {
                Releases.openBundle(base + "bad.zip", cache, (w, d, t) -> {}).close();
                check(false, "invalid replacement is rejected");
            } catch (IOException expected) {
                check(bundleDownloads == 2 && !cache.exists() && !new File(cache + ".part").exists(),
                      "damaged payload triggers one retry and the invalid replacement is removed");
            }

            String cleaned = Releases.cleanBody("## New in 0.9.1\r\n- **Clone** support\n* Fix `MiniArt`\n\n\nDetails");
            check(cleaned.contains("• Clone support") && cleaned.contains("• Fix MiniArt") && !cleaned.contains("`") && !cleaned.contains("**"), "release markdown is cleaned for display");
            check(Releases.summary(cleaned).equals("New in 0.9.1"), "summary extracts the first line");
            check(Releases.version(new Releases.Release("nightly-5cf42ba", "u", "")).equals("nightly 5cf42ba") && Releases.code(new Releases.Release("manager-v0.9.1", "u", "")) == 901, "release version and manager code");
            Releases rels = new Releases();
            rels.manager = new Releases.Release("manager-v0.9.0", "u1", "");
            rels.nightlyManager = new Releases.Release("nightly-3f91c05", "u2", "");
            check(rels.manager(false) == rels.manager && rels.manager(true) == rels.nightlyManager, "manager(wantNightly) selects stable or nightly manager");
        } finally {
            server.stop(0);
        }
        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static byte[] bundleZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            byte[] patch = new byte[] {5, 6, 7};
            CRC32 crc = new CRC32();
            crc.update(patch);
            ZipEntry entry = new ZipEntry("p/0");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(patch.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(patch);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("bundle.txt"));
            zip.write(("spatch-bundle 1\nspotify 9.2.0.26 148373686\napkm hash\napk base.apk hash\n"
                       + "replace classes.dex old new p/0\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static Sources.Entry entry(String kind) {
        Sources.Entry e = new Sources.Entry(148115007L, sha, APKM.length, 29);
        e.urls.add(kind + ".apkm");
        return e;
    }

    private static void ok(Sources.Invite invite, String kind, File to, String what) {
        try {
            Sources.download(invite, entry(kind), to, (w, d, t) -> {});
            check(true, what);
        } catch (IOException e) {
            check(false, what + ": " + e.getMessage());
        }
    }

    private static void fails(Sources.Invite invite, String kind, File to, String message, String what) {
        try {
            Sources.download(invite, entry(kind), to, (w, d, t) -> {});
            check(false, what + ": it succeeded");
        } catch (IOException e) {
            check(e.getMessage().contains(message), what + " (" + e.getMessage() + ")");
        }
    }

    private static void serve(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath(), auth = x.getRequestHeaders().getFirst("Authorization");
        if (path.equals("/bundle.zip") || path.equals("/bad.zip")) {
            bundleDownloads++;
            byte[] body = path.equals("/bundle.zip") ? validBundle : new byte[] {1, 2, 3};
            reply(x, 200, body, body.length);
            return;
        }
        if (path.equals("/elsewhere.apkm")) {
            x.getResponseHeaders().add("Location", "http://localhost:" + x.getLocalAddress().getPort() + "/plain.apkm");
            x.sendResponseHeaders(302, -1);
            x.close();
            return;
        }
        if (path.equals("/plain.apkm")) {
            lastAuth = auth; // the file itself, reached by a redirect from another host
            reply(x, 200, APKM, APKM.length);
            return;
        }
        if (!("Basic " + Base64.getEncoder().encodeToString("alex:secret".getBytes())).equals(auth)) {
            reply(x, 401, new byte[0], 0);
            return;
        }
        switch (path) {
            case "/good.apkm": reply(x, 200, APKM, APKM.length); break;
            case "/chunked.apkm": reply(x, 200, APKM, 0); break;
            case "/short.apkm": reply(x, 200, java.util.Arrays.copyOf(APKM, 1000), 0); break;
            case "/long.apkm": reply(x, 200, java.util.Arrays.copyOf(APKM, APKM.length + 1), APKM.length + 1); break;
            case "/corrupt.apkm": {
                byte[] bad = APKM.clone();
                bad[100] ^= 1;
                reply(x, 200, bad, bad.length);
                break;
            }
            case "/toplain.apkm":
                x.getResponseHeaders().add("Location", "http://example.org/good.apkm");
                x.sendResponseHeaders(302, -1);
                x.close();
                break;
            default: reply(x, 404, new byte[0], 0);
        }
    }

    /** length 0 sends chunked, with no Content-Length. */
    private static void reply(HttpExchange x, int code, byte[] body, long length) throws IOException {
        x.sendResponseHeaders(code, body.length == 0 ? -1 : length);
        if (body.length > 0) try (OutputStream out = x.getResponseBody()) {
            out.write(body);
        }
        x.close();
    }

    private static void check(boolean ok, String what) {
        if (!ok) failures++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }
}
