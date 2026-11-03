package spatch.manager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a patched Spotify into a second app beside the first: its own package name, with the
 * content providers renamed under it so the two don't clash, its own label, and optionally its own
 * launcher icon. Works on the compiled files (binary XML manifests and resources.arsc) after the
 * patches apply and before signing. Permissions keep their names: apps signed with the same key may
 * share them, so a clone installs beside Spatch, but not beside Spotify's own app.
 */
final class Clone {
    private static final int STRING_POOL = 0x0001, XML_START_ELEMENT = 0x0102, RESOURCE_MAP = 0x0180;
    private static final int AUTHORITIES = 0x01010018, LABEL = 0x01010001, TYPE_STRING = 0x03;
    private static final int UTF8 = 0x100, SORTED = 0x1;
    static final String ORIGINAL = "com.spotify.music", ICON_PATH = "res/spatch/clone_icon.png";

    final String pkg, label;
    final byte[] icon; // the launcher icon's foreground as a PNG, or null to keep Spotify's

    Clone(String pkg, String label) {
        this(pkg, label, null);
    }

    Clone(String pkg, String label, byte[] icon) {
        this.pkg = pkg;
        this.label = label;
        this.icon = icon;
    }

    /** Why a package name can't be used, or null when it can. */
    static String problem(String pkg) {
        if (pkg == null || !pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")) return "Use dots between parts, each starting with a letter, like com.spotify.music.two.";
        if (pkg.equals(ORIGINAL)) return "That's Spotify's own name; a clone needs a different one.";
        if (pkg.length() > 200) return "That name is too long.";
        return null;
    }

    /** A provider authority renamed under the clone's package: Spotify's own prefix swapped, anything else prefixed. */
    String authority(String value) {
        StringBuilder out = new StringBuilder();
        for (String a : value.split(";")) {
            if (out.length() > 0) out.append(';');
            out.append(a.startsWith(ORIGINAL) ? pkg + a.substring(ORIGINAL.length()) : pkg + "." + a);
        }
        return out.toString();
    }

    /**
     * A compiled AndroidManifest.xml with the package renamed. For base.apk (base is true) its
     * providers' literal authorities are renamed and the application gets the clone's label.
     */
    byte[] manifest(byte[] axml, boolean base) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getShort(0) != 0x0003) throw new IOException("Not a compiled manifest.");
        List<int[]> chunks = new ArrayList<>(); // {offset, type, size}
        for (int at = in.getShort(2) & 0xffff; at < axml.length; ) {
            int type = in.getShort(at) & 0xffff, size = in.getInt(at + 4);
            if (size <= 0 || at + size > axml.length) throw new IOException("Damaged manifest.");
            chunks.add(new int[] {at, type, size});
            at += size;
        }
        int[] poolChunk = chunks.stream().filter(c -> c[1] == STRING_POOL).findFirst().orElseThrow(() -> new IOException("Manifest has no strings."));
        StringPool pool = StringPool.read(axml, poolChunk[0]);
        int[] ids = new int[0];
        for (int[] c : chunks) {
            if (c[1] == RESOURCE_MAP) {
                ids = new int[(c[2] - 8) / 4];
                for (int i = 0; i < ids.length; i++) ids[i] = in.getInt(c[0] + 8 + 4 * i);
            }
        }
        byte[] out = axml.clone();
        ByteBuffer o = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        boolean renamed = false;
        for (int[] c : chunks) {
            if (c[1] != XML_START_ELEMENT) continue;
            int ext = c[0] + (in.getShort(c[0] + 2) & 0xffff);
            String element = pool.get(in.getInt(ext + 4));
            int attrStart = in.getShort(ext + 8) & 0xffff, attrSize = in.getShort(ext + 10) & 0xffff, count = in.getShort(ext + 12) & 0xffff;
            for (int k = 0; k < count; k++) {
                int a = ext + attrStart + k * attrSize;
                int nameIdx = in.getInt(a + 4), type = out[a + 15] & 0xff, data = in.getInt(a + 16);
                int id = nameIdx >= 0 && nameIdx < ids.length ? ids[nameIdx] : 0;
                String name = pool.get(nameIdx);
                String value = null;
                if ("manifest".equals(element) && "package".equals(name) && in.getInt(a) == -1) {
                    value = pkg;
                    renamed = true;
                } else if (base && "provider".equals(element) && id == AUTHORITIES && type == TYPE_STRING) {
                    value = authority(pool.get(data));
                } else if (base && "application".equals(element) && id == LABEL && label != null && !label.isEmpty()) {
                    value = label;
                }
                if (value == null) continue;
                int index = pool.add(value);
                o.putInt(a + 8, index);
                out[a + 15] = TYPE_STRING;
                o.putInt(a + 16, index);
            }
        }
        if (!renamed) throw new IOException("The manifest has no package name to change.");
        return replaceChunk(out, poolChunk[0], poolChunk[2], pool.write());
    }

    /**
     * resources.arsc with the given strings swapped in its global string pool: the authorities Spotify
     * reads from resources, and the launcher icon's foreground pointed at the clone's own picture.
     */
    byte[] table(byte[] arsc, Map<String, String> swaps) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getShort(0) != 0x0002) throw new IOException("Not a resource table.");
        int at = in.getShort(2) & 0xffff;
        if ((in.getShort(at) & 0xffff) != STRING_POOL) throw new IOException("The resource table has no strings first.");
        StringPool pool = StringPool.read(arsc, at);
        for (Map.Entry<String, String> s : swaps.entrySet()) {
            int i = pool.indexOf(s.getKey());
            if (i < 0) throw new IOException("This Spotify has no " + s.getKey() + ".");
            pool.set(i, s.getValue());
        }
        return replaceChunk(arsc, at, in.getInt(at + 4), pool.write());
    }

    /** The file with one chunk replaced and its total size updated. */
    private static byte[] replaceChunk(byte[] file, int at, int oldSize, byte[] chunk) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + chunk.length - oldSize);
        out.write(file, 0, at);
        out.write(chunk, 0, chunk.length);
        out.write(file, at + oldSize, file.length - at - oldSize);
        byte[] b = out.toByteArray();
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(4, b.length);
        return b;
    }

    /**
     * A ResStringPool: strings by index, UTF-8 or UTF-16, styles kept as they were. Strings that aren't
     * changed are written back with their exact original bytes; new ones are encoded the way Android's
     * tools do, UTF-8 pools writing each UTF-16 unit on its own (an emoji as two 3-byte halves).
     */
    static final class StringPool {
        private final List<String> strings = new ArrayList<>();
        private final List<byte[]> raw = new ArrayList<>(); // original encoded string, or null once changed
        private boolean utf8;
        private int flags;
        private int[] styleOffsets;
        private byte[] styles;

        static StringPool read(byte[] b, int at) throws IOException {
            ByteBuffer in = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            StringPool p = new StringPool();
            int count = in.getInt(at + 8), styleCount = in.getInt(at + 12);
            p.flags = in.getInt(at + 16);
            p.utf8 = (p.flags & UTF8) != 0;
            int stringsStart = in.getInt(at + 20), stylesStart = in.getInt(at + 24), size = in.getInt(at + 4);
            int header = in.getShort(at + 2) & 0xffff;
            for (int i = 0; i < count; i++) {
                int s = at + stringsStart + in.getInt(at + header + 4 * i);
                if (p.utf8) {
                    int[] n = utf8Length(b, s);          // UTF-16 length, then bytes used
                    int[] len = utf8Length(b, s + n[1]); // UTF-8 byte length
                    int start = s + n[1] + len[1];
                    p.strings.add(decodeUtf8(b, start, len[0]));
                    p.raw.add(java.util.Arrays.copyOfRange(b, s, start + len[0] + 1));
                } else {
                    int len = in.getShort(s) & 0xffff, skip = 2;
                    if ((len & 0x8000) != 0) {
                        len = ((len & 0x7fff) << 16) | (in.getShort(s + 2) & 0xffff);
                        skip = 4;
                    }
                    p.strings.add(new String(b, s + skip, len * 2, StandardCharsets.UTF_16LE));
                    p.raw.add(java.util.Arrays.copyOfRange(b, s, s + skip + len * 2 + 2));
                }
            }
            p.styleOffsets = new int[styleCount];
            for (int i = 0; i < styleCount; i++) p.styleOffsets[i] = in.getInt(at + header + 4 * count + 4 * i);
            p.styles = styleCount == 0 ? new byte[0] : java.util.Arrays.copyOfRange(b, at + stylesStart, at + size);
            return p;
        }

        /** UTF-8 as Android writes it: sequences of up to three bytes, each one UTF-16 unit. */
        private static String decodeUtf8(byte[] b, int at, int len) {
            StringBuilder out = new StringBuilder(len);
            for (int i = at, end = at + len; i < end; ) {
                int c = b[i] & 0xff;
                if (c < 0x80) {
                    out.append((char) c);
                    i++;
                } else if (c < 0xe0) {
                    out.append((char) (((c & 0x1f) << 6) | (b[i + 1] & 0x3f)));
                    i += 2;
                } else if (c < 0xf0) {
                    out.append((char) (((c & 0x0f) << 12) | ((b[i + 1] & 0x3f) << 6) | (b[i + 2] & 0x3f)));
                    i += 3;
                } else { // a four-byte sequence, as standard UTF-8 writes it
                    out.appendCodePoint(((c & 0x07) << 18) | ((b[i + 1] & 0x3f) << 12) | ((b[i + 2] & 0x3f) << 6) | (b[i + 3] & 0x3f));
                    i += 4;
                }
            }
            return out.toString();
        }

        private static byte[] encodeUtf8(String s) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x80) {
                    out.write(c);
                } else if (c < 0x800) {
                    out.write(0xc0 | c >> 6);
                    out.write(0x80 | c & 0x3f);
                } else {
                    out.write(0xe0 | c >> 12);
                    out.write(0x80 | c >> 6 & 0x3f);
                    out.write(0x80 | c & 0x3f);
                }
            }
            return out.toByteArray();
        }

        private static int[] utf8Length(byte[] b, int at) {
            int n = b[at] & 0xff;
            return (n & 0x80) != 0 ? new int[] {((n & 0x7f) << 8) | (b[at + 1] & 0xff), 2} : new int[] {n, 1};
        }

        String get(int i) {
            return i >= 0 && i < strings.size() ? strings.get(i) : null;
        }

        int indexOf(String s) {
            return strings.indexOf(s);
        }

        void set(int i, String s) {
            strings.set(i, s);
            raw.set(i, null);
        }

        /** Appends a string (styles only point at earlier ones, so indices stay valid) and returns its index. */
        int add(String s) {
            strings.add(s);
            raw.add(null);
            flags &= ~SORTED;
            return strings.size() - 1;
        }

        byte[] write() {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int[] offsets = new int[strings.size()];
            for (int i = 0; i < strings.size(); i++) {
                offsets[i] = data.size();
                String s = strings.get(i);
                if (raw.get(i) != null) {
                    data.write(raw.get(i), 0, raw.get(i).length);
                } else if (utf8) {
                    byte[] bytes = encodeUtf8(s);
                    writeUtf8Length(data, s.length());
                    writeUtf8Length(data, bytes.length);
                    data.write(bytes, 0, bytes.length);
                    data.write(0);
                } else {
                    int n = s.length();
                    if (n > 0x7fff) writeU16(data, 0x8000 | (n >> 16));
                    writeU16(data, n & (n > 0x7fff ? 0xffff : 0x7fff));
                    byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE);
                    data.write(bytes, 0, bytes.length);
                    data.write(0);
                    data.write(0);
                }
            }
            while (data.size() % 4 != 0) data.write(0);
            int header = 28, offsetsSize = 4 * (strings.size() + styleOffsets.length);
            int stringsStart = header + offsetsSize, stylesStart = styles.length == 0 ? 0 : stringsStart + data.size();
            int size = stringsStart + data.size() + styles.length;
            ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            out.putShort((short) STRING_POOL).putShort((short) header).putInt(size).putInt(strings.size()).putInt(styleOffsets.length)
                .putInt(flags).putInt(stringsStart).putInt(stylesStart);
            for (int off : offsets) out.putInt(off);
            for (int off : styleOffsets) out.putInt(off);
            out.put(data.toByteArray()).put(styles);
            return out.array();
        }

        private static void writeU16(ByteArrayOutputStream out, int v) {
            out.write(v & 0xff);
            out.write(v >> 8 & 0xff);
        }

        private static void writeUtf8Length(ByteArrayOutputStream out, int n) {
            if (n > 0x7f) out.write(0x80 | (n >> 8));
            out.write(n & 0xff);
        }
    }
}
