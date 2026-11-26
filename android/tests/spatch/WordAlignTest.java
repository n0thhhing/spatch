package spatch;

import java.util.Arrays;
import java.util.List;

/** Pure-Java unit tests for WordAlign (line-timed and plain-text forced alignment). */
public final class WordAlignTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Lyrics lineTimed(String[] lines, long[] starts, long[] ends) {
        Lyrics l = new Lyrics(lines.length);
        for (int i = 0; i < lines.length; i++) {
            l.words[i] = lines[i];
            l.starts[i] = starts[i];
            l.ends[i] = ends[i];
        }
        return l;
    }

    private static Lyrics plain(String... lines) {
        Lyrics l = new Lyrics(lines.length);
        System.arraycopy(lines, 0, l.words, 0, lines.length);
        return l;
    }

    private static WordAlign.Word w(long start, long end, String text) {
        return new WordAlign.Word(start, end, text);
    }

    public static void main(String[] args) {
        // 1. Exact match + Whisper output parsing
        Lyrics exact = lineTimed(
            new String[] { "Hello, dark world!" },
            new long[] { 1000 },
            new long[] { 4000 }
        );
        List<WordAlign.Word> parsed = WordAlign.parse("1100\t1500\thello\n2000\t2400\tdark\n3100\t3700\tworld\n", 0);
        check(WordAlign.align(exact, parsed, 0, 10000) == 1, "Exact line should align");
        check(exact.wordAt[0] != null && exact.wordAt[0].length == 3, "Three words indexed");
        check(exact.wordAt[0][0] == 0 && exact.wordAt[0][1] == 7 && exact.wordAt[0][2] == 12, "Char offsets match original text");
        check(exact.wordStart[0][0] == 1100 && exact.wordStart[0][1] == 2000 && exact.wordStart[0][2] == 3100, "Exact word times copied");

        // 2. One misheard word (within edit-distance tolerance)
        Lyrics misheard = lineTimed(
            new String[] { "Dancing together tonight" },
            new long[] { 5000 },
            new long[] { 8000 }
        );
        List<WordAlign.Word> heardMis = Arrays.asList(
            w(5100, 5600, "dancin"),
            w(6000, 6700, "togetha"),
            w(7100, 7800, "tonigt")
        );
        check(WordAlign.align(misheard, heardMis, 4000, 10000) == 1, "Slightly misheard words should still align");
        check(misheard.wordStart[0][0] == 5100 && misheard.wordStart[0][1] == 6000 && misheard.wordStart[0][2] == 7100, "Fuzzy matches keep recognised times");

        // 3. Missing words interpolated (interior, leading, trailing) and non-decreasing
        Lyrics missing = lineTimed(
            new String[] { "We walk through the night alone" },
            new long[] { 10000 },
            new long[] { 16000 }
        );
        List<WordAlign.Word> heardMiss = Arrays.asList(
            w(11000, 11400, "walk"),
            w(14000, 14500, "night"),
            w(15000, 15500, "alone")
        );
        check(WordAlign.align(missing, heardMiss, 9000, 17000) == 1, "50% matched line should align");
        long[] ws = missing.wordStart[0];
        check(ws[0] == 10000 && ws[1] == 11000, "Leading unmatched word starts at line start");
        check(ws[2] > 11000 && ws[2] < ws[3] && ws[3] < 14000 && ws[4] == 14000 && ws[5] == 15000, "Interior missing words interpolated strictly between neighbours");
        for (int i = 1; i < ws.length; i++) check(ws[i] >= ws[i - 1], "Word starts stay non-decreasing");

        // 4. Too few matches (< 40%) left unaligned
        Lyrics sparse = lineTimed(
            new String[] { "One two three four five" },
            new long[] { 20000 },
            new long[] { 24000 }
        );
        List<WordAlign.Word> heardSparse = Arrays.asList(w(21000, 21500, "two"));
        check(WordAlign.align(sparse, heardSparse, 19000, 25000) == 0, "1 of 5 words (20%) must not align");
        check(sparse.wordAt[0] == null && sparse.wordStart[0] == null, "Unaligned line leaves wordAt/wordStart null");

        // 5. Recognised words outside the line ignored; 300 ms slack accepted and clamped; partial window skipped
        Lyrics bounds = lineTimed(
            new String[] { "Stay inside the line", "Outside window line" },
            new long[] { 30000, 35000 },
            new long[] { 33000, 40000 }
        );
        List<WordAlign.Word> heardBounds = Arrays.asList(
            w(29000, 29300, "stay"),    // 1000 ms early -> ignored
            w(29800, 30200, "stay"),    // 200 ms early (within 300 ms slack) -> clamped to 30000
            w(31000, 31400, "inside"),
            w(33200, 33500, "line"),    // 200 ms late (within 300 ms slack) -> clamped to 33000
            w(34000, 34500, "line"),    // 1000 ms late -> ignored
            w(36000, 36500, "outside"),
            w(37000, 37500, "window")
        );
        check(WordAlign.align(bounds, heardBounds, 29000, 38000) == 1, "Only the line fully inside the window aligns");
        check(bounds.wordAt[0] != null && bounds.wordStart[0][0] == 30000 && bounds.wordStart[0][3] == 33000, "Slack words clamped inside [lineStart, lineEnd]");
        check(bounds.wordAt[1] == null, "Line extending past window end is skipped");

        // 6. Plain-text song timed from two windows
        Lyrics plainSong = plain(
            "Morning light across the floor",
            "Coffee warm beside the door",
            "Evening falls and stars appear",
            "Quiet night is finally here"
        );
        check(!WordAlign.isSynced(plainSong), "Plain lyrics start unsynced");
        List<WordAlign.Word> win1 = Arrays.asList(
            w(2000, 2400, "morning"), w(2500, 2900, "light"), w(3000, 3500, "across"), w(3600, 3900, "the"), w(4000, 4500, "floor"),
            w(6000, 6400, "coffee"), w(6500, 6900, "warm"), w(7000, 7400, "beside"), w(7500, 7800, "the"), w(8000, 8500, "door")
        );
        int cursor = WordAlign.alignPlain(plainSong, win1, 0, 12000, 24000, 0);
        check(cursor == 2, "Cursor advances past the first two aligned lines");
        check(plainSong.starts[0] == 2000 && plainSong.starts[1] == 6000, "First two lines gained timestamps");
        check(plainSong.starts[2] == 0 && plainSong.starts[3] == 0, "Later lines stay untimed until heard");
        check(!WordAlign.isSynced(plainSong), "Still treated as plain until the last line is timed");

        List<WordAlign.Word> win2 = Arrays.asList(
            w(13000, 13400, "evening"), w(13500, 13900, "falls"), w(14000, 14300, "and"), w(14400, 14800, "stars"), w(14900, 15500, "appear"),
            w(17000, 17400, "quiet"), w(17500, 17900, "night"), w(18000, 18300, "is"), w(18400, 18900, "finally"), w(19000, 19600, "here")
        );
        cursor = WordAlign.alignPlain(plainSong, win2, 12000, 24000, 24000, cursor);
        check(cursor == 4 && WordAlign.isSynced(plainSong), "Second window completes plain-text timing");
        check(plainSong.starts[2] == 13000 && plainSong.starts[3] == 17000, "Second-half lines timed accurately");
        check(plainSong.wordAt[0] != null && plainSong.wordAt[3] != null, "Plain alignment also fills word timings");

        // 7. Repeated chorus gets the right occurrence by position (both with cursor and mid-song start)
        Lyrics chorusSong = plain(
            "Verse one starts the story",
            "Take me home tonight",
            "Verse two keeps it going",
            "Take me home tonight"
        );
        // Mid-song window hearing only the second chorus near the end of a 60 s song
        List<WordAlign.Word> lateChorus = Arrays.asList(
            w(48000, 48400, "take"), w(48500, 48800, "me"), w(49000, 49500, "home"), w(49600, 50200, "tonight")
        );
        WordAlign.alignPlain(chorusSong, lateChorus, 45000, 60000, 60000, 0);
        check(chorusSong.starts[1] == 0 && chorusSong.starts[3] == 48000, "Late window matches second chorus occurrence, not first");

        // Earlier window then matches the first verse + first chorus cleanly
        List<WordAlign.Word> earlyPart = Arrays.asList(
            w(5000, 5400, "verse"), w(5500, 5800, "one"), w(5900, 6300, "starts"), w(6400, 6700, "the"), w(6800, 7300, "story"),
            w(15000, 15400, "take"), w(15500, 15800, "me"), w(16000, 16500, "home"), w(16600, 17200, "tonight")
        );
        WordAlign.alignPlain(chorusSong, earlyPart, 0, 25000, 60000, 0);
        check(chorusSong.starts[0] == 5000 && chorusSong.starts[1] == 15000, "Early window matches first verse and first chorus");

        // 8. Leftover untimed short lines interpolated with strictly increasing starts
        Lyrics withLeftover = plain(
            "First full line of song",
            "Second full line of song",
            "Oh yeah",
            "Fourth full line of song",
            "Fifth full line of song"
        );
        List<WordAlign.Word> heardMostly = Arrays.asList(
            w(2000, 2300, "first"), w(2400, 2700, "full"), w(2800, 3100, "line"), w(3200, 3400, "of"), w(3500, 3900, "song"),
            w(6000, 6300, "second"), w(6400, 6700, "full"), w(6800, 7100, "line"), w(7200, 7400, "of"), w(7500, 7900, "song"),
            w(14000, 14300, "fourth"), w(14400, 14700, "full"), w(14800, 15100, "line"), w(15200, 15400, "of"), w(15500, 15900, "song"),
            w(18000, 18300, "fifth"), w(18400, 18700, "full"), w(18800, 19100, "line"), w(19200, 19400, "of"), w(19500, 19900, "song")
        );
        WordAlign.alignPlain(withLeftover, heardMostly, 0, 22000, 24000, 0);
        check(withLeftover.starts[2] == 0, "Unheard short line stays untimed before finalization");
        check(WordAlign.readyToFinalize(withLeftover, false), "4 of 5 lines timed with only a short leftover is ready to finalize");
        check(WordAlign.interpolateUntimed(withLeftover, 24000), "Interpolation succeeds");
        check(withLeftover.starts[2] > withLeftover.starts[1] && withLeftover.starts[2] < withLeftover.starts[3], "Leftover start interpolated between neighbours");
        check(withLeftover.wordAt[2] == null, "Interpolated leftover line stays without fake word timing");
        for (int i = 1; i < withLeftover.starts.length; i++) {
            check(withLeftover.starts[i] > withLeftover.starts[i - 1], "All finalized starts are strictly increasing");
        }
    }
}
