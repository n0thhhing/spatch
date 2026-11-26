package spatch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class LyricsProtoTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void varint(ByteArrayOutputStream o, long v) {
        while (v >= 0x80) { o.write((int) (v & 0x7f) | 0x80); v >>>= 7; }
        o.write((int) v);
    }

    private static byte[] field(int tag, byte[] body) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        varint(o, tag);
        varint(o, body.length);
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    private static byte[] num(int tag, long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        varint(o, tag);
        varint(o, v);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    public static void main(String[] args) {
        byte[] first = cat(num(8, 1500), num(16, 4000), field(26, "Hello world".getBytes(StandardCharsets.UTF_8)),
            field(34, cat(num(8, 1500), num(16, 2000), num(24, 6))),
            field(34, cat(num(8, 2500), num(24, 5))));
        byte[] second = cat(num(8, 300), num(16, 5000), field(26, "Café ♪".getBytes(StandardCharsets.UTF_8)), num(40, 7));
        byte[] lyrics = cat(num(8, 1), field(18, first), field(18, second), field(50, new byte[]{1, 2}));
        byte[] reply = cat(field(10, lyrics), num(16, 1));

        Lyrics l = LyricsProto.decode(reply);
        check(l.words.length == 2, "Both lines read");
        check(l.words[0].equals("Hello world") && l.starts[0] == 1500 && l.ends[0] == 4000, "Line text and times");
        check(l.wordAt[0] != null && l.wordAt[0].length == 2 && l.wordAt[0][0] == 0 && l.wordAt[0][1] == 6, "Syllable offsets from char counts");
        check(l.wordStart[0][0] == 1500 && l.wordStart[0][1] == 2500, "Syllable start times");
        check(l.words[1].equals("Café ♪") && l.starts[1] == 300 && l.ends[1] == 5000, "Unicode line, unknown field skipped");
        check(l.wordAt[1] == null && l.wordStart[1] == null, "Lines without syllables stay line-timed");
        check(LyricsProto.decode(new byte[0]).words.length == 0, "Empty reply has no lines");
        System.out.println("Lyrics proto checks passed");
    }
}
