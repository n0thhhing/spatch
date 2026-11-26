package spatch;

import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/**
 * Experimental: makes lyrics for songs that neither Spotify nor LRCLIB has, by running Whisper (speech recognition,
 * on the phone) over the song's own audio while it plays. Audio is taken in ~28 s windows; a window only counts if
 * the song played straight through it (no seek, pause or skip), so its times map onto the song exactly. Each window's
 * lines are saved as they come, and a song stops being worked on once almost all of it is covered.
 */
final class LyricGen {
    static final String MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin";
    /** Whisper base, 5-bit quantized: multilingual, about 60 MB, several times faster than real time on a recent phone. */
    private static final String MODEL_SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898";
    private static final long MODEL_BYTES = 59_707_625L;
    private static final long WINDOW_MS = 28_000;
    /** Lines Whisper is known to invent over music or silence. */
    private static final java.util.regex.Pattern INVENTED = java.util.regex.Pattern.compile(
        "(?i)^[\\s.♪♫*-]*$|^(thank you|thanks for watching|you|subtitles by.*|please subscribe.*)[.!]*$");

    static volatile File dir;
    static volatile SharedPreferences prefs;
    /** For the settings screen: what the model download is doing. */
    static volatile String status = "";

    private LyricGen() {}

    static File model() { File d = dir; return d == null ? null : new File(d, "ggml-base-q5_1.bin"); }

    static boolean modelReady() {
        File m = model();
        return m != null && m.length() == MODEL_BYTES;
    }

    static boolean enabled() {
        SharedPreferences p = prefs;
        return p != null && p.getBoolean("lyric_gen", false) && modelReady();
    }

    static boolean wordsEnabled() {
        SharedPreferences p = prefs;
        return p != null && p.getBoolean("lyric_words", false) && modelReady();
    }

    // What is playing, as the UI last reported it, and a count of the jumps (seek, pause, skip) that break a window.
    private static String track = "", wanted = null;
    private static Lyrics wantedWords = null;
    private static long position, duration, reportedAt;
    private static boolean playing;
    private static int jumps;
    private static Thread worker;
    private static volatile String progressId = "";
    private static volatile int progressSeq = 0;

    static int progressFor(String id) {
        return id != null && id.equals(progressId) ? progressSeq : 0;
    }

    private static void markProgress(String id) {
        progressId = id;
        progressSeq++;
    }

    private static void startWorker() {
        LyricGen.class.notifyAll();
        if (worker == null) {
            worker = new Thread(LyricGen::run, "spatch-lyric-gen");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    /** Called by CoreBridge when no service has lyrics for `id`: generate them if the song plays. */
    static synchronized void want(String id) {
        if (id == null || (id.equals(wanted) && wantedWords == null) || prefs == null || !prefs.getBoolean("lyric_gen", false)) return;
        wanted = id;
        wantedWords = null;
        startWorker();
    }

    /** Called by CoreBridge when `id` has line-timed or plain lyrics without word timing: align them while the song plays. */
    static synchronized void wantWords(String id, Lyrics lyrics) {
        if (id == null || lyrics == null || lyrics.words.length == 0 || prefs == null || !prefs.getBoolean("lyric_words", false)) return;
        if (id.equals(wanted) && wantedWords != null && WordAlign.sameLines(wantedWords, lyrics)) return;
        wanted = id;
        wantedWords = lyrics.copy();
        startWorker();
    }

    /** Every player update from the UI. Only real discontinuities count as jumps, not routine state refreshes. */
    static synchronized void playback(String id, long positionMs, boolean isPlaying, long durationMs) {
        long expected = position + (playing ? SystemClock.uptimeMillis() - reportedAt : 0);
        if (!id.equals(track) || isPlaying != playing || Math.abs(positionMs - expected) > 1500) jumps++;
        track = id; position = positionMs; playing = isPlaying; duration = durationMs; reportedAt = SystemClock.uptimeMillis();
    }

    private static synchronized long now() { return position + (playing ? SystemClock.uptimeMillis() - reportedAt : 0); }

    private static void run() {
        String song = null;
        boolean songWordMode = false;
        TreeMap<Long, String[]> lines = new TreeMap<>(); // start → { end, text }
        Lyrics aligned = null;
        boolean plainMode = false;
        int plainCursor = 0;
        ArrayList<long[]> covered = new ArrayList<>();
        long winFrames = -1, winPos = 0, winWall = 0;
        int winJumps = -1;
        boolean capturing = false;
        while (true) {
            try {
                synchronized (LyricGen.class) { while (wanted == null && !capturing) LyricGen.class.wait(); } // asleep until a song needs lyrics
                Thread.sleep(1000);
            } catch (InterruptedException stop) { return; }
            try {
            String id;
            boolean active, wordMode;
            Lyrics sourceWords;
            long songLength;
            int jumpsNow;
            synchronized (LyricGen.class) {
                id = track;
                wordMode = wantedWords != null;
                sourceWords = wantedWords;
                active = (wordMode ? wordsEnabled() : enabled()) && id.equals(wanted) && playing;
                songLength = duration;
                jumpsNow = jumps;
            }
            if (active != capturing) {
                capturing = active;
                nativeCapture(active);
            }
            if (!active) { winFrames = -1; continue; }
            if (!id.equals(song) || wordMode != songWordMode) {
                song = id;
                songWordMode = wordMode;
                lines.clear();
                covered.clear();
                aligned = null;
                plainMode = false;
                plainCursor = 0;
                if (wordMode) {
                    Lyrics before = Lyrics.savedWords(id);
                    aligned = before != null && WordAlign.sameLines(before, sourceWords) ? before : sourceWords.copy();
                    plainMode = !WordAlign.isSynced(sourceWords);
                    if (plainMode) {
                        for (int i = 0; i < aligned.starts.length; i++) if (aligned.starts[i] > 0) plainCursor = i + 1;
                    }
                    covered.addAll(Lyrics.savedWordsCoverage(id));
                    boolean audioDone = songLength > 0 && coveredMs(covered) >= songLength * 9 / 10;
                    if (WordAlign.isSynced(aligned) && WordAlign.allWordSynced(aligned)) { finish(id); continue; }
                    if (audioDone) {
                        if (plainMode && !WordAlign.isSynced(aligned) && WordAlign.readyToFinalize(aligned, true)) {
                            WordAlign.interpolateUntimed(aligned, songLength);
                            Lyrics.saveWords(id, aligned, covered);
                            markProgress(id);
                        }
                        finish(id);
                        continue;
                    }
                } else {
                    Lyrics before = Lyrics.savedGenerated(id);
                    if (before != null) for (int i = 0; i < before.words.length; i++) lines.put(before.starts[i], new String[] { Long.toString(before.ends[i]), before.words[i] });
                    covered.addAll(Lyrics.savedCoverage(id));
                    if (songLength > 0 && coveredMs(covered) >= songLength * 9 / 10) { finish(id); continue; }
                }
            }
            if (winFrames < 0 || winJumps != jumpsNow) { winFrames = nativeFrames(); winPos = now(); winWall = SystemClock.uptimeMillis(); winJumps = jumpsNow; continue; }
            long elapsed = SystemClock.uptimeMillis() - winWall, at = now();
            boolean ending = songLength > 0 && at >= songLength - 1500;
            if (elapsed < WINDOW_MS && !ending) continue;
            long frames = nativeFrames();
            int rate = Math.max(8000, nativeRate());
            long from = winFrames, start = winPos, audioMs = (frames - from) * 1000 / rate;
            winFrames = frames; winPos = at; winWall = SystemClock.uptimeMillis(); // the next window follows on directly
            // Audio must have flowed through this phone the whole time (not a Connect device, not a stalled stream).
            if (audioMs < 4000 || Math.abs(audioMs - elapsed) > 1500) continue;
            float[] pcm = nativeRead(from, frames);
            File m = model();
            if (wordMode) {
                String found = pcm == null || m == null ? null : nativeTranscribeWords(m.getPath(), pcm);
                if (found == null) continue;
                java.util.List<WordAlign.Word> heard = WordAlign.parse(found, start);
                covered.add(new long[] { start, start + audioMs });
                boolean audioDone = songLength > 0 && coveredMs(covered) >= songLength * 9 / 10;
                if (plainMode && !WordAlign.isSynced(aligned)) {
                    plainCursor = WordAlign.alignPlain(aligned, heard, start, start + audioMs, songLength, plainCursor);
                    if (WordAlign.readyToFinalize(aligned, audioDone)) WordAlign.interpolateUntimed(aligned, songLength);
                } else {
                    WordAlign.align(aligned, heard, start, start + audioMs);
                }
                Lyrics.saveWords(id, aligned, covered);
                markProgress(id);
                if (audioDone || (WordAlign.isSynced(aligned) && WordAlign.allWordSynced(aligned))) finish(id);
            } else {
                String found = pcm == null || m == null ? null : nativeTranscribe(m.getPath(), pcm);
                if (found == null) continue;
                lines.subMap(start, start + audioMs).clear(); // this listen replaces what an earlier one heard here
                for (String seg : found.split("\n")) {
                    String[] f = seg.split("\t", 3);
                    if (f.length < 3) continue;
                    String text = f[2].replaceAll("[♪♫]", "").trim();
                    if (INVENTED.matcher(text).matches()) continue;
                    try { lines.put(start + Long.parseLong(f[0]), new String[] { Long.toString(start + Long.parseLong(f[1])), text }); }
                    catch (NumberFormatException bad) { /* skip the segment */ }
                }
                covered.add(new long[] { start, start + audioMs });
                Lyrics.saveGenerated(id, toLyrics(lines), covered);
                markProgress(id);
                if (songLength > 0 && coveredMs(covered) >= songLength * 9 / 10) finish(id);
            }
            } catch (UnsatisfiedLinkError none) {
                return; // no native library (the desktop preview)
            } catch (RuntimeException failed) {
                android.util.Log.w("SpatchUi", "lyric generation window failed", failed);
                winFrames = -1;
            }
        }
    }

    private static synchronized void finish(String id) {
        if (id.equals(wanted)) { wanted = null; wantedWords = null; }
    }

    static Lyrics toLyrics(TreeMap<Long, String[]> lines) {
        Lyrics out = new Lyrics(lines.size());
        int i = 0;
        for (Map.Entry<Long, String[]> e : lines.entrySet()) {
            out.starts[i] = Math.max(1, e.getKey()); // a start of 0 reads as "not time-synced"
            out.ends[i] = Long.parseLong(e.getValue()[0]);
            out.words[i++] = e.getValue()[1];
        }
        out.generated = true;
        return out;
    }

    /** Total time inside the union of the ranges. */
    static long coveredMs(java.util.List<long[]> ranges) {
        ArrayList<long[]> sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> Long.compare(a[0], b[0]));
        long total = 0, reach = Long.MIN_VALUE;
        for (long[] r : sorted) {
            long from = Math.max(r[0], reach);
            if (r[1] > from) total += r[1] - from;
            reach = Math.max(reach, r[1]);
        }
        return total;
    }

    /** Downloads the model once, checking its exact size and SHA-256 before it is used. Blocking; updates `status`. */
    static void download() throws Exception {
        File m = model();
        if (m == null) throw new IllegalStateException("Not ready yet");
        if (m.length() == MODEL_BYTES) { status = "Model ready"; return; }
        m.getParentFile().mkdirs();
        File part = new File(m.getPath() + ".part");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(MODEL_URL).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            long got = 0;
            for (int n; (n = in.read(buf)) > 0;) {
                got += n;
                if (got > MODEL_BYTES) throw new java.io.IOException("The model is larger than expected");
                sha.update(buf, 0, n);
                out.write(buf, 0, n);
                status = "Downloading the model… " + (got * 100 / MODEL_BYTES) + "%";
            }
            if (got != MODEL_BYTES) throw new java.io.IOException("The download stopped early");
        } finally {
            c.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equals(MODEL_SHA256)) { part.delete(); throw new SecurityException("The model didn’t match its checksum"); }
        if (!part.renameTo(m)) throw new java.io.IOException("Couldn’t save the model");
        status = "Model ready";
    }

    static void deleteModel() { File m = model(); if (m != null) { m.delete(); new File(m.getPath() + ".part").delete(); } status = ""; }

    private static native void nativeCapture(boolean on);
    private static native long nativeFrames();
    private static native int nativeRate();
    private static native float[] nativeRead(long from, long to);
    private static native String nativeTranscribe(String model, float[] pcm);
    private static native String nativeTranscribeWords(String model, float[] pcm);
}
