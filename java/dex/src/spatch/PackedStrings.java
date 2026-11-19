package spatch;

/** The shared length-prefixed UTF-8 JNI payload, matching Rust cache::encode. */
final class PackedStrings {
    private static final byte[] EMPTY_BYTES = new byte[0];

    /** Unpacks Rust's length-prefixed UTF-8 wire buffer (cache::encode) in one pass after counting fields. */
    static String[] unpackStrings(byte[] raw) {
        if (raw == null) return null;
        int n = 0, pos = 0, end = raw.length;
        while (pos + 4 <= end) {
            int len = (raw[pos] & 0xff) | ((raw[pos + 1] & 0xff) << 8)
                | ((raw[pos + 2] & 0xff) << 16) | ((raw[pos + 3] & 0xff) << 24);
            pos += 4;
            if (len < 0 || pos + len > end) return null;
            pos += len;
            n++;
        }
        if (pos != end) return null;
        String[] out = new String[n];
        pos = 0;
        for (int i = 0; i < n; i++) {
            int len = (raw[pos] & 0xff) | ((raw[pos + 1] & 0xff) << 8)
                | ((raw[pos + 2] & 0xff) << 16) | ((raw[pos + 3] & 0xff) << 24);
            pos += 4;
            out[i] = len == 0 ? "" : new String(raw, pos, len, java.nio.charset.StandardCharsets.UTF_8);
            pos += len;
        }
        return out;
    }

    /** Packs strings into Rust's length-prefixed UTF-8 wire buffer (cache::encode) for a single JNI copy. */
    static byte[] packStrings(String[] values) {
        if (values == null || values.length == 0) return EMPTY_BYTES;
        byte[][] encoded = new byte[values.length][];
        int total = 0;
        for (int i = 0; i < values.length; i++) {
            String s = values[i];
            byte[] b = (s == null || s.isEmpty()) ? EMPTY_BYTES : s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            encoded[i] = b;
            total += 4 + b.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] b : encoded) {
            int len = b.length;
            out[pos] = (byte) len;
            out[pos + 1] = (byte) (len >>> 8);
            out[pos + 2] = (byte) (len >>> 16);
            out[pos + 3] = (byte) (len >>> 24);
            pos += 4;
            if (len > 0) {
                System.arraycopy(b, 0, out, pos, len);
                pos += len;
            }
        }
        return out;
    }
}
