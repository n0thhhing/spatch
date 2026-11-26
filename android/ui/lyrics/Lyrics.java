package spatch;

/** One song's lyrics: the text of each line and when it starts and ends (ms; all 0 when the lyrics aren't time-synced). */
public final class Lyrics {
    public final String[] words;
    public final long[] starts, ends;
    /** Per line, when the source times each word: where each word starts in the line's text, and when it is sung (ms). Null for a line without word timing. */
    public final int[][] wordAt;
    public final long[][] wordStart;
    /** Made on the phone by {@link LyricGen}, not from a lyrics service: shown with a note that it may be wrong. */
    public boolean generated;

    Lyrics(int n) { words = new String[n]; starts = new long[n]; ends = new long[n]; wordAt = new int[n][]; wordStart = new long[n][]; }

    boolean wordSynced() { for (int[] w : wordAt) if (w != null) return true; return false; }

    Lyrics copy() {
        Lyrics out = new Lyrics(words.length);
        System.arraycopy(words, 0, out.words, 0, words.length);
        System.arraycopy(starts, 0, out.starts, 0, starts.length);
        System.arraycopy(ends, 0, out.ends, 0, ends.length);
        for (int i = 0; i < words.length; i++) {
            if (wordAt[i] != null) out.wordAt[i] = wordAt[i].clone();
            if (wordStart[i] != null) out.wordStart[i] = wordStart[i].clone();
        }
        out.generated = generated;
        return out;
    }

    /** The UI's settings, so the lyrics fallback can honour the toggle (set by the UI). */
    static volatile android.content.SharedPreferences prefs;
    /** Where every song's last-found lyrics are kept, in app files rather than cache so Android doesn't clear them (set by the UI). */
    static volatile java.io.File dir;

    private static java.io.File file(String id) { return file(id, ""); }

    private static java.io.File file(String id, String suffix) {
        java.io.File d = dir;
        return d == null || id == null || !id.matches("[A-Za-z0-9]{1,64}") ? null : new java.io.File(d, id + suffix);
    }

    /** Generated lyrics live beside the real ones (".gen"), with the song ranges already heard (".cover", "from-to" lines). */
    static void saveGenerated(String id, Lyrics l, java.util.List<long[]> covered) {
        java.io.File f = file(id, ".gen"), cover = file(id, ".cover");
        if (f == null) return;
        save(f, l);
        saveCoverage(cover, covered);
    }

    static Lyrics savedGenerated(String id) {
        Lyrics l = read(file(id, ".gen"));
        if (l != null) l.generated = true;
        return l;
    }

    static java.util.List<long[]> savedCoverage(String id) { return readCoverage(file(id, ".cover")); }

    /** Word-aligned copies live beside the source lyrics (".words") so the original lyrics stay intact. */
    static void saveWords(String id, Lyrics l, java.util.List<long[]> covered) {
        java.io.File f = file(id, ".words"), cover = file(id, ".wcover");
        if (f == null) return;
        save(f, l);
        saveCoverage(cover, covered);
    }

    static Lyrics savedWords(String id) { return read(file(id, ".words")); }

    static java.util.List<long[]> savedWordsCoverage(String id) { return readCoverage(file(id, ".wcover")); }

    private static void saveCoverage(java.io.File cover, java.util.List<long[]> covered) {
        if (cover == null) return;
        StringBuilder ranges = new StringBuilder();
        for (long[] r : covered) ranges.append(r[0]).append('-').append(r[1]).append('\n');
        try { java.nio.file.Files.write(cover.toPath(), ranges.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.io.IOException failed) { android.util.Log.w("Lyrics", "saving lyric coverage failed", failed); }
    }

    private static java.util.List<long[]> readCoverage(java.io.File cover) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        if (cover == null || !cover.isFile()) return out;
        try {
            for (String line : java.nio.file.Files.readAllLines(cover.toPath())) {
                String[] r = line.split("-");
                if (r.length == 2) out.add(new long[] { Long.parseLong(r[0]), Long.parseLong(r[1]) });
            }
        } catch (java.io.IOException | NumberFormatException damaged) { /* start over */ }
        return out;
    }

    // shortcut: saved lyrics are never pruned; a few KB per song, add pruning if the folder ever grows large.
    static void save(String id, Lyrics l) { save(file(id), l); }

    private static void save(java.io.File f, Lyrics l) {
        if (f == null) return;
        try {
            java.io.File part = java.io.File.createTempFile(f.getName(), ".part", f.getParentFile());
            try {
                try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(part)))) {
                    out.writeInt(l.words.length);
                    for (int i = 0; i < l.words.length; i++) { out.writeUTF(l.words[i] == null ? "" : l.words[i]); out.writeLong(l.starts[i]); out.writeLong(l.ends[i]); }
                    // Word timing follows the lines, so copies saved before it existed still read.
                    for (int i = 0; i < l.words.length; i++) {
                        int k = l.wordAt[i] == null ? 0 : l.wordAt[i].length;
                        out.writeInt(k);
                        for (int w = 0; w < k; w++) { out.writeInt(l.wordAt[i][w]); out.writeLong(l.wordStart[i][w]); }
                    }
                }
                part.renameTo(f);
            } finally {
                part.delete();
            }
        } catch (java.io.IOException failed) {
            android.util.Log.w("Lyrics", "saving lyrics failed", failed);
        }
    }

    static Lyrics saved(String id) { return read(file(id)); }

    private static Lyrics read(java.io.File f) {
        if (f == null || !f.isFile()) return null;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            int n = in.readInt();
            if (n <= 0 || n > 10000) return null;
            Lyrics out = new Lyrics(n);
            for (int i = 0; i < n; i++) { out.words[i] = in.readUTF(); out.starts[i] = in.readLong(); out.ends[i] = in.readLong(); }
            if (in.available() > 0) for (int i = 0; i < n; i++) {
                int k = in.readInt();
                if (k < 0 || k > 1000) return out;
                if (k == 0) continue;
                out.wordAt[i] = new int[k];
                out.wordStart[i] = new long[k];
                for (int w = 0; w < k; w++) { out.wordAt[i][w] = in.readInt(); out.wordStart[i][w] = in.readLong(); }
            }
            return out;
        } catch (java.io.IOException damaged) {
            return null;
        }
    }
}
