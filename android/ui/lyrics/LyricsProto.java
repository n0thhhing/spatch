package spatch;

import java.nio.charset.StandardCharsets;

/**
 * Reads Spotify's color-lyrics v3 reply (LyricsWrapperResponse) from raw bytes. Field numbers come from the app's
 * generated classes: wrapper.lyrics = 1 (LyricsV3Response); LyricsV3Response.lines = 2 (LyricsLine);
 * LyricsLine.startTimeMs = 1, endTimeMs = 2, words = 3, syncData = 4 (SyncData); SyncData.startTimeMs = 1, numChars = 3.
 */
final class LyricsProto {
    private final byte[] b;
    private int pos;
    private final int end;

    private LyricsProto(byte[] b, int pos, int end) {
        this.b = b;
        this.pos = pos;
        this.end = end;
    }

    static Lyrics decode(byte[] bytes) {
        java.util.ArrayList<int[]> lines = new java.util.ArrayList<>();
        long syncType = -1;
        LyricsProto wrapper = new LyricsProto(bytes, 0, bytes.length);
        while (wrapper.pos < wrapper.end) {
            int tag = (int) wrapper.varint();
            if (tag != 10) { wrapper.skip(tag); continue; }
            LyricsProto lyrics = wrapper.message();
            while (lyrics.pos < lyrics.end) {
                int t = (int) lyrics.varint();
                if (t == 8) syncType = lyrics.varint();
                else if (t == 18) { LyricsProto m = lyrics.message(); lines.add(new int[]{m.pos, m.end}); }
                else lyrics.skip(t);
            }
        }
        Lyrics out = new Lyrics(lines.size());
        int withSync = 0, wordTimed = 0;
        for (int i = 0; i < out.words.length; i++) {
            if (line(new LyricsProto(bytes, lines.get(i)[0], lines.get(i)[1]), out, i)) withSync++;
            if (out.wordAt[i] != null) wordTimed++;
        }
        // Says per song whether Spotify sent word timing at all, and whether the length check kept it, so a song
        // without word-by-word highlighting can be told apart from a decoding problem in logcat (tag SpatchLyrics).
        try {
            android.util.Log.i("SpatchLyrics", "spotify lyrics: lines=" + lines.size() + " syncType=" + syncType
                + " linesWithSyncData=" + withSync + " wordTimed=" + wordTimed);
        } catch (RuntimeException noAndroidLog) {
            // Plain JVM tests have no Android log.
        }
        return out;
    }

    /** Fills line i; true when the line carried word timing (whether or not it passed the length check). */
    private static boolean line(LyricsProto in, Lyrics out, int i) {
        java.util.ArrayList<long[]> syllables = new java.util.ArrayList<>();
        out.words[i] = "";
        while (in.pos < in.end) {
            int tag = (int) in.varint();
            if (tag == 8) out.starts[i] = in.varint();
            else if (tag == 16) out.ends[i] = in.varint();
            else if (tag == 26) { LyricsProto s = in.message(); out.words[i] = new String(s.b, s.pos, s.end - s.pos, StandardCharsets.UTF_8); }
            else if (tag == 34) {
                LyricsProto s = in.message();
                long[] one = new long[2];
                while (s.pos < s.end) {
                    int t = (int) s.varint();
                    if (t == 8) one[0] = s.varint();
                    else if (t == 24) one[1] = s.varint();
                    else s.skip(t);
                }
                syllables.add(one);
            } else in.skip(tag);
        }
        if (syllables.isEmpty()) return false;
        int[] at = new int[syllables.size()];
        long[] when = new long[at.length];
        int offset = 0;
        for (int k = 0; k < at.length; k++) {
            at[k] = offset;
            when[k] = syllables.get(k)[0];
            offset += (int) syllables.get(k)[1];
        }
        if (offset <= out.words[i].length()) { out.wordAt[i] = at; out.wordStart[i] = when; }
        return true;
    }

    private long varint() {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos >= end) throw new IllegalArgumentException("Truncated lyrics reply");
            int c = b[pos++];
            v |= (long) (c & 0x7f) << shift;
            if (c >= 0) return v;
        }
        throw new IllegalArgumentException("Bad varint in lyrics reply");
    }

    private LyricsProto message() {
        long n = varint();
        if (n < 0 || n > end - pos) throw new IllegalArgumentException("Truncated lyrics reply");
        LyricsProto m = new LyricsProto(b, pos, pos + (int) n);
        pos += (int) n;
        return m;
    }

    private void skip(int tag) {
        switch (tag & 7) {
            case 0: varint(); break;
            case 1: pos += 8; break;
            case 2: message(); break;
            case 5: pos += 4; break;
            default: throw new IllegalArgumentException("Unsupported wire type in lyrics reply");
        }
        if (pos > end) throw new IllegalArgumentException("Truncated lyrics reply");
    }
}
