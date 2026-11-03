package spatch.manager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Desktop check of what the manager does on the phone: apply a bundle to the APKM, sign with a new
 * key, and compare every entry with a set the desktop patcher signed from the same commit.
 * Usage: RoundTrip <apkm> <bundle.zip> <reference-dir> <work-dir>
 */
public final class RoundTrip {
    public static void main(String[] a) throws Exception {
        File apkm = new File(a[0]), ref = new File(a[2]), work = new File(a[3]);
        work.mkdirs();
        check(Bundle.bspatch(new byte[] {1, 2, 3}, new byte[0]).length == 0, "empty patch");

        Keys keys = Keys.loadOrCreate(work);
        check(Keys.loadOrCreate(work).fingerprint().equals(keys.fingerprint()), "key survives a reload");
        File p12 = new File(work, "export.p12");
        try (OutputStream out = new FileOutputStream(p12)) {
            keys.write(out, "pw".toCharArray());
        }
        try (InputStream in = new FileInputStream(p12)) {
            check(Keys.read(in, "pw".toCharArray()).fingerprint().equals(keys.fingerprint()), "export and import keep the key");
        }

        List<File> unsigned;
        try (Bundle bundle = new Bundle(new File(a[1]))) {
            unsigned = bundle.apply(apkm, work, false, (what, done, total) -> System.out.println(done + "/" + total + " " + what));
        }
        for (File apk : unsigned) {
            File signed = new File(work, "signed-" + apk.getName());
            keys.sign(apk, signed);
            same(new File(ref, apk.getName()), signed);
            System.out.println("PASS " + apk.getName());
        }
        System.out.println("PASS bundle reproduces the desktop build");

        // No APKM: an original base.apk and loose splits, as read from an installed Spotify.
        File loose = new File(work, "installed"), out = new File(work, "from-installed");
        loose.mkdirs();
        out.mkdirs();
        List<File> splits = new java.util.ArrayList<>();
        try (ZipFile z = new ZipFile(apkm)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry x = e.nextElement();
                if (!x.getName().endsWith(".apk")) continue;
                File f = new File(loose, x.getName());
                try (InputStream in = z.getInputStream(x); OutputStream o = new FileOutputStream(f)) {
                    Bundle.copy(in, o);
                }
                if (!x.getName().equals("base.apk")) splits.add(f);
            }
        }
        try (Bundle bundle = new Bundle(new File(a[1]))) {
            check(bundle.spotifyCode == 148115007L, "bundle names the Spotify version code");
            unsigned = bundle.apply(new File(loose, "base.apk"), splits, out, (what, done, total) -> {});
            File patchedBase = new File(loose, "patched-base.apk");
            java.nio.file.Files.copy(unsigned.get(0).toPath(), patchedBase.toPath());
            try {
                bundle.apply(patchedBase, splits, new File(work, "refused"), (what, done, total) -> {});
                check(false, "an already patched base.apk must be refused");
            } catch (java.io.IOException expected) {
                // patched base: no original to work from
            }
        }
        check(unsigned.size() == 5, "base and four splits");
        for (File apk : unsigned) {
            File signed = new File(out, "signed-" + apk.getName());
            keys.sign(apk, signed);
            same(new File(ref, apk.getName()), signed);
        }
        System.out.println("PASS installed Spotify without an APKM reproduces the desktop build");

        // An update that keeps the installed splits patches and signs base.apk alone.
        File only = new File(work, "base-only");
        only.mkdirs();
        try (Bundle bundle = new Bundle(new File(a[1]))) {
            unsigned = bundle.apply(apkm, only, true, (what, done, total) -> {});
        }
        check(unsigned.size() == 1 && unsigned.get(0).getName().equals("base.apk"), "base-only writes just base.apk");
        File signedBase = new File(only, "signed-base.apk");
        keys.sign(unsigned.get(0), signedBase);
        same(new File(ref, "base.apk"), signedBase);
        System.out.println("PASS a base-only update reproduces the desktop base.apk");
    }

    /** Same entries with the same bytes, signatures aside. */
    private static void same(File want, File got) throws Exception {
        try (ZipFile w = new ZipFile(want); ZipFile g = new ZipFile(got)) {
            int n = 0;
            for (Enumeration<? extends ZipEntry> e = w.entries(); e.hasMoreElements();) {
                ZipEntry z = e.nextElement();
                if (z.getName().startsWith("META-INF/")) continue;
                ZipEntry y = g.getEntry(z.getName());
                check(y != null, got.getName() + " lacks " + z.getName());
                check(y.getMethod() == z.getMethod(), z.getName() + " compression differs");
                try (InputStream x = w.getInputStream(z); InputStream v = g.getInputStream(y)) {
                    check(java.util.Arrays.equals(x.readAllBytes(), v.readAllBytes()), z.getName() + " differs");
                }
                n++;
            }
            int m = 0;
            for (Enumeration<? extends ZipEntry> e = g.entries(); e.hasMoreElements();) if (!e.nextElement().getName().startsWith("META-INF/")) m++;
            check(n == m, got.getName() + " has " + m + " entries, want " + n);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
