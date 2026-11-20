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
        check(c.permission("com.spotify.music.permission.SECURED_BROADCAST").equals("com.spotify.music.two.permission.SECURED_BROADCAST"), "app-owned permissions move under the clone");
        check(c.permission("android.permission.INTERNET").equals("android.permission.INTERNET")
            && c.permission("com.google.android.c2dm.permission.RECEIVE").equals("com.google.android.c2dm.permission.RECEIVE")
            && c.permission("com.spotify.music2.permission.OTHER").equals("com.spotify.music2.permission.OTHER"), "external permissions keep their names");
        byte[] clonedManifest = c.manifest(manifest, true);
        Map<String, String> beforePermissions = permissions(manifest), afterPermissions = permissions(clonedManifest);
        check(beforePermissions.containsValue("com.spotify.music.permission.SECURED_BROADCAST"), "fixture contains the phone's conflicting permission");
        check(beforePermissions.size() == afterPermissions.size(), "permission declarations and guards are retained");
        for (Map.Entry<String, String> permission : beforePermissions.entrySet()) {
            check(c.permission(permission.getValue()).equals(afterPermissions.get(permission.getKey())), "permission declaration/reference renamed consistently: " + permission.getKey());
        }
        Map<String, String> otherPermissions = permissions(new Clone("com.spotify.music.three", "Spotify Three").manifest(manifest, true));
        for (Map.Entry<String, String> permission : afterPermissions.entrySet()) {
            if (permission.getKey().startsWith("permission/name/")) check(!otherPermissions.containsValue(permission.getValue()), "two clones do not declare the same permission");
        }
        try { c.manifest(clonedManifest, true); check(false, "already-cloned manifests are refused"); }
        catch (java.io.IOException expected) { check(true, "already-cloned manifests are refused"); }
        Map<String, String> swaps = new LinkedHashMap<>();
        swaps.put("com.spotify.mobile.android.mediaapi", c.authority("com.spotify.mobile.android.mediaapi"));
        swaps.put("com.spotify.music.partnerai", c.authority("com.spotify.music.partnerai"));
        swaps.put("res/drawable/ic_launcher_renaissance_foreground.xml", Clone.ICON_PATH);
        Map<String, byte[]> baseChanges = new LinkedHashMap<>();
        baseChanges.put("AndroidManifest.xml", clonedManifest);
        byte[] table = c.table(arsc, swaps);
        check(new String(table, 0, table.length, java.nio.charset.StandardCharsets.UTF_16LE).contains("com.spotify.music.two\0"), "the resource table's package is the clone's");
        baseChanges.put("resources.arsc", table);
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

    /** Inspect attribute values, not unused copies retained in the manifest's string pool. */
    private static Map<String, String> permissions(byte[] file) throws Exception {
        ByteBuffer xml = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        Clone.StringPool pool = Clone.StringPool.read(file, 8);
        Map<String, String> values = new LinkedHashMap<>();
        for (int at = xml.getShort(2) & 0xffff; at < file.length; at += xml.getInt(at + 4)) {
            if ((xml.getShort(at) & 0xffff) != 0x0102) continue;
            int node = at + (xml.getShort(at + 2) & 0xffff);
            String element = pool.get(xml.getInt(node + 4));
            int start = xml.getShort(node + 8) & 0xffff, size = xml.getShort(node + 10) & 0xffff, count = xml.getShort(node + 12) & 0xffff;
            for (int i = 0; i < count; i++) {
                int attr = node + start + i * size;
                if ((file[attr + 15] & 0xff) != 3) continue;
                String name = pool.get(xml.getInt(attr + 4));
                if (("name".equals(name) && element.startsWith("permission")) || ("name".equals(name) && element.startsWith("uses-permission"))
                    || "permission".equals(name) || "readPermission".equals(name) || "writePermission".equals(name) || "permissionGroup".equals(name)) {
                    values.put(element + "/" + name + "/" + values.size(), pool.get(xml.getInt(attr + 16)));
                }
            }
        }
        return values;
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
