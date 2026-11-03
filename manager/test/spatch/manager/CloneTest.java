package spatch.manager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Clone on real Spotify files: string pools round-trip byte for byte, and the rewritten APKs are
 * written out for aapt2 to read back (see the script in the commit that added this).
 *
 *   javac -d /tmp/ct manager/src/spatch/manager/Clone.java manager/test/spatch/manager/CloneTest.java
 *   java -cp /tmp/ct spatch.manager.CloneTest <base.apk> <split.apk> <out-dir>
 */
public final class CloneTest {
    public static void main(String[] args) throws Exception {
        File base = new File(args[0]), split = new File(args[1]), out = new File(args[2]);
        out.mkdirs();
        byte[] manifest = read(base, "AndroidManifest.xml"), arsc = read(base, "resources.arsc");
        check(roundTrips(manifest, 8), "the manifest's string pool round-trips");
        check(roundTrips(arsc, ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN).getShort(2) & 0xffff), "the resource table's string pool round-trips");

        Clone c = new Clone("com.spotify.music.two", "Spotify Two");
        check(Clone.problem("com.spotify.music.two") == null && Clone.problem("com.spotify.music") != null && Clone.problem("nodots") != null && Clone.problem("a.1b") != null, "package names are checked");
        check(c.authority("com.spotify.music.share").equals("com.spotify.music.two.share") && c.authority("amzn_appstore").equals("com.spotify.music.two.amzn_appstore")
            && c.authority("a;com.spotify.music.b").equals("com.spotify.music.two.a;com.spotify.music.two.b"), "authorities move under the clone's package");
        Map<String, String> swaps = new LinkedHashMap<>();
        swaps.put("com.spotify.mobile.android.mediaapi", c.authority("com.spotify.mobile.android.mediaapi"));
        swaps.put("com.spotify.music.partnerai", c.authority("com.spotify.music.partnerai"));
        swaps.put("res/drawable/ic_launcher_renaissance_foreground.xml", Clone.ICON_PATH);
        Map<String, byte[]> baseChanges = new LinkedHashMap<>();
        baseChanges.put("AndroidManifest.xml", c.manifest(manifest, true));
        baseChanges.put("resources.arsc", c.table(arsc, swaps));
        baseChanges.put(Clone.ICON_PATH, read(base, "res/mipmap-mdpi-v4/ic_launcher_renaissance.webp"));
        write(base, new File(out, "base.apk"), baseChanges);
        Map<String, byte[]> splitChanges = new LinkedHashMap<>();
        splitChanges.put("AndroidManifest.xml", c.manifest(read(split, "AndroidManifest.xml"), false));
        write(split, new File(out, split.getName()), splitChanges);
        try {
            c.table(arsc, java.util.Collections.singletonMap("no such string anywhere", "x"));
            check(false, "a missing string is refused");
        } catch (java.io.IOException e) {
            check(true, "a missing string is refused");
        }
        System.out.println("wrote " + out);
    }

    private static boolean roundTrips(byte[] file, int at) throws Exception {
        int size = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).getInt(at + 4);
        byte[] again = Clone.StringPool.read(file, at).write();
        return java.util.Arrays.equals(again, java.util.Arrays.copyOfRange(file, at, at + size));
    }

    private static byte[] read(File apk, String name) throws Exception {
        try (ZipFile z = new ZipFile(apk); InputStream in = z.getInputStream(z.getEntry(name))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    /** A copy of the APK with some entries replaced or added; resources.arsc stays stored, as Android requires. */
    private static void write(File from, File to, Map<String, byte[]> changes) throws Exception {
        try (ZipFile z = new ZipFile(from); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(to))) {
            Map<String, byte[]> left = new LinkedHashMap<>(changes);
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.getName().startsWith("META-INF/")) continue;
                byte[] data = left.containsKey(entry.getName()) ? left.remove(entry.getName()) : z.getInputStream(entry).readAllBytes();
                put(out, entry.getName(), data, entry.getMethod() == ZipEntry.STORED);
            }
            for (Map.Entry<String, byte[]> add : left.entrySet()) put(out, add.getKey(), add.getValue(), true);
        }
    }

    private static void put(ZipOutputStream out, String name, byte[] data, boolean stored) throws Exception {
        ZipEntry e = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        }
        out.putNextEntry(e);
        out.write(data);
        out.closeEntry();
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) System.exit(1);
    }
}
