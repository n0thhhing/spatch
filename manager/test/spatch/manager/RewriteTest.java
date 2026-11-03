package spatch.manager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Rewrites a real base.apk, checks every entry and reports time and size; needs no Android classes. */
public final class RewriteTest {
    public static void main(String[] args) throws Exception {
        File base = new File(args[0]), work = new File(args[1]);
        work.mkdirs();
        String original = Bundle.sha256(base);
        File bundleFile = new File(work, "bundle.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(bundleFile))) {
            zip.putNextEntry(new ZipEntry("bundle.txt"));
            zip.write(("spatch-bundle 1\nspotify test 1\napkm unused\napk base.apk " + original + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        long start = System.nanoTime();
        File rewritten;
        try (Bundle bundle = new Bundle(bundleFile)) {
            rewritten = bundle.apply(base, Collections.emptyList(), work, null, (what, done, total) -> {}).get(0);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        try (ZipFile before = new ZipFile(base); ZipFile after = new ZipFile(rewritten)) {
            int entries = 0;
            for (Enumeration<? extends ZipEntry> all = before.entries(); all.hasMoreElements();) {
                ZipEntry entry = all.nextElement();
                if (Bundle.signature(entry.getName())) continue;
                ZipEntry got = after.getEntry(entry.getName());
                check(got != null && got.getMethod() == entry.getMethod(), "entry method: " + entry.getName());
                try (java.io.InputStream a = before.getInputStream(entry); java.io.InputStream b = after.getInputStream(got)) {
                    check(java.util.Arrays.equals(a.readAllBytes(), b.readAllBytes()), "entry bytes: " + entry.getName());
                }
                entries++;
            }
            check(entries == after.size(), "no unexpected entries");
        }
        check(original.equals(Bundle.sha256(base)), "the original must stay unchanged");
        System.out.printf(java.util.Locale.ROOT, "PASS rewrite: %.3f s, %d bytes%n", seconds, rewritten.length());
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
