package spatch.manager;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A Spatch patch bundle (format in src/bundle.rs): checks the user's APKM and writes the patched,
 * unsigned APKs. Every input and every patched entry is checked against its SHA-256, so the result
 * is exactly what the desktop patcher builds. No Android classes, so it runs in the desktop test.
 */
final class Bundle implements AutoCloseable {
    interface Progress {
        void step(String what, int done, int total);
    }

    private static final String BASIS = "resources.arsc";

    final String spotify, apkmSha;
    final long spotifyCode;
    final List<String[]> apks = new ArrayList<>(); // {name, sha256}
    private final Map<String, String[]> replace = new HashMap<>(); // entry -> {"replace", entry, old sha, new sha, patch}
    private final List<String[]> add = new ArrayList<>(); // {entry, S|D, sha, patch}
    private String coverEntry, coverTrack; // the widget preview's cover, fetched here rather than shipped
    private final ZipFile zip;

    Bundle(File file) throws IOException {
        zip = new ZipFile(file);
        String spotify = null, apkm = null;
        long code = 0;
        String[] lines = new String(read(zip, zip.getEntry("bundle.txt")), StandardCharsets.UTF_8).split("\n");
        if (lines.length == 0 || !lines[0].equals("spatch-bundle 1")) throw new IOException("This patch bundle needs a newer Spatch Manager.");
        for (String line : lines) {
            String[] f = line.split(" ");
            switch (f[0]) {
                case "spotify": spotify = f[1]; code = Long.parseLong(f[2]); break;
                case "apkm": apkm = f[1]; break;
                case "apk": apks.add(new String[] {f[1], f[2]}); break;
                case "replace": replace.put(f[1], f); break;
                case "add": add.add(new String[] {f[1], f[2], f[3], f[4]}); break;
                case "cover": coverEntry = f[1]; coverTrack = f[2]; break;
                default: break;
            }
        }
        if (spotify == null || apkm == null || apks.isEmpty()) throw new IOException("The patch bundle is incomplete.");
        this.spotify = spotify;
        this.apkmSha = apkm;
        this.spotifyCode = code;
    }

    /** The original base.apk's SHA-256: any copy with it can stand in for the APKM. */
    String baseSha() throws IOException {
        for (String[] apk : apks) if (apk[0].equals("base.apk")) return apk[1];
        throw new IOException("The patch bundle is incomplete.");
    }

    /**
     * Without an APKM: patches an original base.apk and takes the splits from an installed Spotify of
     * the same version (any device configuration; only base.apk is changed, splits are re-signed).
     */
    List<File> apply(File base, List<File> splits, File dir, Progress progress) throws IOException {
        int total = splits.size() + 2, done = 0;
        progress.step("Checking Spotify", done++, total);
        if (!sha256(base).equals(baseSha())) throw new IOException("That copy of Spotify isn't the original " + spotify + ".");
        List<File> out = new ArrayList<>();
        progress.step("Patching base.apk", done++, total);
        File patched = new File(dir, "base.apk");
        rewrite(base, patched, true);
        out.add(patched);
        for (File split : splits) {
            progress.step("Preparing " + split.getName(), done++, total);
            File copy = new File(dir, split.getName());
            rewrite(split, copy, false);
            out.add(copy);
        }
        return out;
    }

    /**
     * Writes each APK, unsigned, into dir and returns them in install order. baseOnly writes just
     * base.apk, for an update that keeps the splits already installed (they never change).
     */
    List<File> apply(File apkm, File dir, boolean baseOnly, Progress progress) throws IOException {
        int total = (baseOnly ? 1 : apks.size()) * 2 + 1, done = 0;
        progress.step("Checking the APKM", done++, total);
        if (!sha256(apkm).equals(apkmSha)) throw new IOException("That file isn't the Spotify " + spotify + " APKM this version of Spatch is made for.");
        List<File> out = new ArrayList<>();
        try (ZipFile in = new ZipFile(apkm)) {
            for (String[] apk : apks) {
                if (baseOnly && !apk[0].equals("base.apk")) continue;
                File original = new File(dir, apk[0] + ".original");
                progress.step("Unpacking " + apk[0], done++, total);
                try (InputStream s = in.getInputStream(entry(in, apk[0])); OutputStream o = new FileOutputStream(original)) {
                    copy(s, o);
                }
                if (!sha256(original).equals(apk[1])) throw new IOException(apk[0] + " in the APKM is damaged.");
                progress.step("Patching " + apk[0], done++, total);
                File patched = new File(dir, apk[0]);
                rewrite(original, patched, apk[0].equals("base.apk"));
                original.delete();
                out.add(patched);
            }
        }
        return out;
    }

    private void rewrite(File src, File dst, boolean base) throws IOException {
        int replaced = 0;
        try (ZipFile in = new ZipFile(src); ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(dst), 1 << 16))) {
            byte[] basis = null;
            for (Enumeration<? extends ZipEntry> e = in.entries(); e.hasMoreElements();) {
                ZipEntry z = e.nextElement();
                if (signature(z.getName())) continue;
                String[] step = base ? replace.get(z.getName()) : null;
                if (step != null) {
                    byte[] old = read(in, z);
                    if (base && z.getName().equals(BASIS)) basis = old;
                    put(out, z.getName(), z.getMethod(), patched(old, step[2], step[3], step[4], z.getName()));
                    replaced++;
                } else {
                    if (base && z.getName().equals(BASIS)) basis = read(in, z);
                    ZipEntry n = new ZipEntry(z.getName());
                    n.setMethod(z.getMethod());
                    if (z.getMethod() == ZipEntry.STORED) {
                        n.setSize(z.getSize());
                        n.setCompressedSize(z.getSize());
                        n.setCrc(z.getCrc());
                    }
                    out.putNextEntry(n);
                    try (InputStream s = in.getInputStream(z)) {
                        copy(s, out);
                    }
                    out.closeEntry();
                }
            }
            if (!base) return;
            if (replaced != replace.size() || basis == null) throw new IOException("base.apk is missing entries the patches need.");
            for (String[] step : add) {
                byte[] bytes = patched(basis, null, step[2], step[3], step[0]);
                if (step[0].equals(coverEntry)) {
                    byte[] cover = cover(coverTrack);
                    if (cover != null) bytes = cover;
                }
                put(out, step[0], step[1].equals("S") ? ZipEntry.STORED : ZipEntry.DEFLATED, bytes);
            }
        }
    }

    private byte[] patched(byte[] old, String oldSha, String newSha, String patch, String name) throws IOException {
        if (oldSha != null && !sha256(old).equals(oldSha)) throw new IOException(name + " is not the original.");
        byte[] now = bspatch(old, read(zip, zip.getEntry(patch)));
        if (!sha256(now).equals(newSha)) throw new IOException("The patch for " + name + " did not apply cleanly.");
        return now;
    }

    /** A track's cover from Spotify's public oEmbed endpoint, or null (the plain tile stays). */
    static byte[] cover(String track) {
        try {
            String json = new String(fetch("https://open.spotify.com/oembed?url=" + track), StandardCharsets.UTF_8);
            int at = json.indexOf("\"thumbnail_url\"");
            if (at < 0) return null;
            int open = json.indexOf('"', at + 15) + 1, close = json.indexOf('"', open);
            byte[] jpeg = fetch(json.substring(open, close).replace("\\/", "/"));
            return jpeg.length > 2 && (jpeg[0] & 0xff) == 0xff && (jpeg[1] & 0xff) == 0xd8 ? jpeg : null;
        } catch (IOException | RuntimeException offline) {
            return null;
        }
    }

    private static byte[] fetch(String url) throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Raw bsdiff patch as written by the bsdiff crate: (mix, copy, seek) headers, mix added to old. */
    static byte[] bspatch(byte[] old, byte[] patch) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(old.length + patch.length);
        long oldPos = 0;
        int p = 0;
        while (p < patch.length) {
            if (patch.length - p < 24) throw new IOException("Truncated patch.");
            long mix = le64(patch, p), copy = le64(patch, p + 8), seek = le64(patch, p + 16);
            if (seek < 0) seek = -(seek & Long.MAX_VALUE);
            p += 24;
            if (mix < 0 || copy < 0 || mix + copy > patch.length - p || oldPos + mix > old.length) throw new IOException("Corrupt patch.");
            byte[] mixed = new byte[(int) mix];
            for (int i = 0; i < mix; i++) mixed[i] = (byte) (patch[p + i] + old[(int) oldPos + i]);
            out.write(mixed, 0, mixed.length);
            p += (int) mix;
            out.write(patch, p, (int) copy);
            p += (int) copy;
            oldPos += mix + seek;
            if (oldPos < 0 || oldPos > old.length) throw new IOException("Corrupt patch.");
        }
        return out.toByteArray();
    }

    private static long le64(byte[] b, int at) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = v << 8 | (b[at + i] & 0xff);
        return v;
    }

    private static void put(ZipOutputStream out, String name, int method, byte[] bytes) throws IOException {
        ZipEntry n = new ZipEntry(name);
        n.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            n.setSize(bytes.length);
            n.setCompressedSize(bytes.length);
            n.setCrc(crc.getValue());
        }
        out.putNextEntry(n);
        out.write(bytes);
        out.closeEntry();
    }

    /** Old v1 signature files; the phone's key signs everything again. */
    static boolean signature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String rest = name.substring(9);
        return rest.equals("MANIFEST.MF") || rest.startsWith("stamp-cert-sha256")
            || rest.endsWith(".SF") || rest.endsWith(".RSA") || rest.endsWith(".DSA") || rest.endsWith(".EC");
    }

    private static ZipEntry entry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("That APKM has no " + name + ".");
        return e;
    }

    private static byte[] read(ZipFile zip, ZipEntry e) throws IOException {
        if (e == null) throw new IOException("The patch bundle is damaged.");
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, (int) e.getSize()));
            copy(in, out);
            return out.toByteArray();
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0;) out.write(buf, 0, n);
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = digest();
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0;) md.update(buf, 0, n);
            return hex(md.digest());
        }
    }

    static String sha256(byte[] b) {
        return hex(digest().digest(b));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit(x >> 4 & 15, 16)).append(Character.forDigit(x & 15, 16));
        return s.toString();
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
