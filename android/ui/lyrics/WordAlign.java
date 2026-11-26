package spatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Forced alignment of known lyrics against Whisper word timestamps. Keeps the lyric text intact and fills in
 * per-word timings (and line timings when the source lyrics are plain text). Pure Java so it can be unit-tested
 * without Android classes.
 */
final class WordAlign {
    private static final long SLACK_MS = 300;
    private static final int SCORE_EXACT = 12;
    private static final int SCORE_DIST1 = 8;
    private static final int SCORE_DIST2 = 5;
    private static final int GAP_PENALTY = -2;

    private WordAlign() {}

    static final class Word {
        final long startMs, endMs;
        final String text, norm;

        Word(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = Math.max(startMs, endMs);
            this.text = text == null ? "" : text;
            this.norm = normalize(this.text);
        }
    }

    private static final class Tokens {
        final int[] at;
        final String[] norm;
        final int matchable;

        Tokens(int[] at, String[] norm, int matchable) {
            this.at = at;
            this.norm = norm;
            this.matchable = matchable;
        }
    }

    /** Parses Whisper "startMs\tendMs\tword" output into absolute song times. */
    static List<Word> parse(String raw, long windowStartMs) {
        ArrayList<Word> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String[] f = line.split("\t", 3);
            if (f.length < 3) continue;
            long s, e;
            try {
                s = windowStartMs + Long.parseLong(f[0].trim());
                e = windowStartMs + Long.parseLong(f[1].trim());
            } catch (NumberFormatException bad) {
                continue;
            }
            if (e < s) e = s;
            String text = f[2].trim();
            if (text.isEmpty()) continue;
            String[] parts = text.split("\\s+");
            if (parts.length == 1) {
                Word w = new Word(s, e, parts[0]);
                if (!w.norm.isEmpty()) out.add(w);
            } else {
                long span = Math.max(0L, e - s);
                for (int i = 0; i < parts.length; i++) {
                    long ps = s + span * i / parts.length;
                    long pe = s + span * (i + 1) / parts.length;
                    Word w = new Word(ps, pe, parts[i]);
                    if (!w.norm.isEmpty()) out.add(w);
                }
            }
        }
        return out;
    }

    static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    static boolean isSynced(Lyrics l) {
        return l != null && l.words.length > 0 && l.starts[l.starts.length - 1] > 0;
    }

    static boolean sameLines(Lyrics a, Lyrics b) {
        if (a == null || b == null || a.words.length != b.words.length) return false;
        for (int i = 0; i < a.words.length; i++) {
            String sa = a.words[i] == null ? "" : a.words[i];
            String sb = b.words[i] == null ? "" : b.words[i];
            if (!sa.equals(sb)) return false;
        }
        return true;
    }

    static boolean allWordSynced(Lyrics l) {
        if (l == null || l.words.length == 0) return false;
        for (int i = 0; i < l.words.length; i++) {
            if (l.wordAt[i] != null) continue;
            if (splitWords(l.words[i]).matchable > 0) return false;
        }
        return true;
    }

    /**
     * Aligns recognised words onto line-timed lyrics for every line fully covered by [winStartMs, winEndMs].
     * Returns how many lines gained word timings.
     */
    static int align(Lyrics l, List<Word> heard, long winStartMs, long winEndMs) {
        if (l == null || heard == null || heard.isEmpty() || l.words.length == 0) return 0;
        int aligned = 0;
        for (int i = 0; i < l.words.length; i++) {
            if (l.wordAt[i] != null) continue;
            long lineStart = l.starts[i];
            if (lineStart <= 0) continue;
            long lineEnd = lineEnd(l, i);
            if (lineStart < winStartMs || lineEnd > winEndMs) continue;
            Tokens tok = splitWords(l.words[i]);
            if (tok.matchable == 0) continue;

            ArrayList<Word> cand = new ArrayList<>();
            for (Word w : heard) {
                if (!w.norm.isEmpty() && w.startMs >= lineStart - SLACK_MS && w.startMs <= lineEnd + SLACK_MS) {
                    cand.add(w);
                }
            }
            if (cand.isEmpty()) continue;

            long[] matchedTime = alignLineTokens(tok, cand);
            int matched = 0;
            for (long t : matchedTime) if (t >= 0) matched++;
            if (matched == 0 || matched * 5 < tok.matchable * 2) continue;

            l.wordAt[i] = tok.at;
            l.wordStart[i] = fillWordStarts(matchedTime, lineStart, lineEnd);
            aligned++;
        }
        return aligned;
    }

    /**
     * Aligns recognised words onto plain (untimed or partially timed) lyrics across line boundaries.
     * Uses `cursorLine` and where [winStartMs, winEndMs] sits in `songDurationMs` to disambiguate repeated choruses.
     * Returns the updated cursor (next line after the last newly aligned line).
     */
    static int alignPlain(Lyrics l, List<Word> heard, long winStartMs, long winEndMs, long songDurationMs, int cursorLine) {
        if (l == null || heard == null || heard.isEmpty() || l.words.length == 0) return cursorLine;
        int n = l.words.length;
        ArrayList<Word> cand = new ArrayList<>();
        for (Word w : heard) {
            if (!w.norm.isEmpty() && w.startMs >= winStartMs - SLACK_MS && w.startMs <= winEndMs + SLACK_MS) {
                cand.add(w);
            }
        }
        if (cand.isEmpty()) return cursorLine;

        Tokens[] allTokens = new Tokens[n];
        int[] lineGlobalOffset = new int[n];
        int totalMatchable = 0;
        for (int i = 0; i < n; i++) {
            allTokens[i] = splitWords(l.words[i]);
            lineGlobalOffset[i] = totalMatchable;
            totalMatchable += allTokens[i].matchable;
        }
        if (totalMatchable == 0) return cursorLine;

        // Bound the search between already-timed lines before and after this window so repeated choruses match in order.
        int prevTimed = -1, nextTimed = -1;
        for (int i = 0; i < n; i++) {
            if (l.starts[i] <= 0) continue;
            if (l.starts[i] <= winStartMs + 1000) prevTimed = i;
            else if (nextTimed < 0 && l.starts[i] >= winEndMs - 1000) nextTimed = i;
        }
        int searchFrom = Math.max(0, prevTimed + 1);
        int searchTo = nextTimed >= 0 ? nextTimed : n;
        if (cursorLine > searchFrom && cursorLine < searchTo) searchFrom = cursorLine;
        if (searchFrom >= searchTo) return cursorLine;

        ArrayList<String> flatNorm = new ArrayList<>();
        ArrayList<Integer> flatLine = new ArrayList<>();
        ArrayList<Integer> flatTok = new ArrayList<>();
        ArrayList<Integer> flatGlobal = new ArrayList<>();
        for (int i = searchFrom; i < searchTo; i++) {
            if (l.starts[i] > 0) continue;
            Tokens tok = allTokens[i];
            int g = lineGlobalOffset[i];
            for (int t = 0; t < tok.norm.length; t++) {
                if (tok.norm[t].isEmpty()) continue;
                flatNorm.add(tok.norm[t]);
                flatLine.add(i);
                flatTok.add(t);
                flatGlobal.add(g++);
            }
        }
        int mCount = flatNorm.size();
        int kCount = cand.size();
        if (mCount == 0) return cursorLine;

        // Local alignment (Smith-Waterman) with gap penalty keeps matched words contiguous rather than jumping across verses.
        int[][] dp = new int[mCount + 1][kCount + 1];
        byte[][] dir = new byte[mCount + 1][kCount + 1]; // 1 = diag match, 2 = skip lyric, 3 = skip heard
        int bestM = 0, bestK = 0, bestEff = 0;
        double expectedWord = songDurationMs > 0
            ? (double) totalMatchable * Math.min(songDurationMs, Math.max(0L, winEndMs)) / songDurationMs
            : -1.0;

        for (int m = 1; m <= mCount; m++) {
            String lw = flatNorm.get(m - 1);
            int gIdx = flatGlobal.get(m - 1);
            int posPenalty = expectedWord >= 0
                ? (int) Math.round(Math.abs(gIdx - expectedWord) / totalMatchable * 15.0)
                : 0;
            for (int k = 1; k <= kCount; k++) {
                int best = 0;
                byte d = 0;
                int ms = matchScore(lw, cand.get(k - 1).norm);
                if (ms > 0) {
                    int v = dp[m - 1][k - 1] + ms;
                    if (v > best) { best = v; d = 1; }
                }
                int up = dp[m - 1][k] + GAP_PENALTY;
                if (up > best) { best = up; d = 2; }
                int left = dp[m][k - 1] + GAP_PENALTY;
                if (left > best) { best = left; d = 3; }
                dp[m][k] = best;
                dir[m][k] = d;
                if (best > 0) {
                    int eff = best - posPenalty;
                    if (eff > bestEff) {
                        bestEff = eff;
                        bestM = m;
                        bestK = k;
                    }
                }
            }
        }
        if (bestEff <= 0) return cursorLine;

        // Backtrack matched tokens per line.
        long[][] lineMatchedStart = new long[n][];
        long[][] lineMatchedEnd = new long[n][];
        int m = bestM, k = bestK;
        while (m > 0 && k > 0 && dp[m][k] > 0) {
            byte d = dir[m][k];
            if (d == 1) {
                int li = flatLine.get(m - 1);
                int ti = flatTok.get(m - 1);
                if (lineMatchedStart[li] == null) {
                    int len = allTokens[li].at.length;
                    lineMatchedStart[li] = new long[len];
                    lineMatchedEnd[li] = new long[len];
                    java.util.Arrays.fill(lineMatchedStart[li], -1L);
                    java.util.Arrays.fill(lineMatchedEnd[li], -1L);
                }
                Word w = cand.get(k - 1);
                lineMatchedStart[li][ti] = w.startMs;
                lineMatchedEnd[li][ti] = w.endMs;
                m--;
                k--;
            } else if (d == 2) {
                m--;
            } else if (d == 3) {
                k--;
            } else {
                break;
            }
        }

        int lastAligned = -1;
        for (int i = searchFrom; i < searchTo; i++) {
            if (lineMatchedStart[i] == null || l.starts[i] > 0) continue;
            Tokens tok = allTokens[i];
            long[] ms = lineMatchedStart[i];
            long[] me = lineMatchedEnd[i];
            int matched = 0, first = -1, last = -1;
            for (int t = 0; t < ms.length; t++) {
                if (ms[t] >= 0) {
                    matched++;
                    if (first < 0) first = t;
                    last = t;
                }
            }
            if (matched == 0 || matched * 5 < tok.matchable * 2) continue;

            long estStart = Math.max(Math.max(1L, winStartMs), ms[first] - first * 250L);
            int trailing = ms.length - 1 - last;
            long estEnd = Math.max(estStart, me[last] + trailing * 250L);
            if (winEndMs > estStart) estEnd = Math.min(estEnd, winEndMs + SLACK_MS);
            long[] wordStart = fillWordStarts(ms, estStart, estEnd);
            l.starts[i] = Math.max(1L, wordStart[0]);
            l.ends[i] = Math.max(estEnd, wordStart[wordStart.length - 1]);
            l.wordAt[i] = tok.at;
            l.wordStart[i] = wordStart;
            lastAligned = i;
        }

        // Keep timed line starts strictly increasing and ends non-overlapping with the next timed line.
        long prevStart = 0;
        for (int i = 0; i < n; i++) {
            if (l.starts[i] <= 0) continue;
            if (l.starts[i] <= prevStart) {
                l.starts[i] = prevStart + 1;
                if (l.wordStart[i] != null) {
                    for (int t = 0; t < l.wordStart[i].length; t++) {
                        if (l.wordStart[i][t] < l.starts[i]) l.wordStart[i][t] = l.starts[i];
                    }
                }
            }
            prevStart = l.starts[i];
        }
        for (int i = 0; i + 1 < n; i++) {
            if (l.starts[i] > 0 && l.starts[i + 1] > l.starts[i]) {
                if (l.ends[i] <= l.starts[i] || l.ends[i] > l.starts[i + 1]) l.ends[i] = l.starts[i + 1];
            }
        }
        return lastAligned >= 0 ? lastAligned + 1 : cursorLine;
    }

    /**
     * True once enough of a plain-text song has been timed that the remaining untimed lines can be interpolated
     * cleanly (all real lines timed, all but a few short lines timed, or the whole song has been listened to).
     */
    static boolean readyToFinalize(Lyrics l, boolean audioCovered) {
        if (l == null || l.words.length == 0) return false;
        int total = 0, timed = 0, longUntimed = 0;
        for (int i = 0; i < l.words.length; i++) {
            Tokens tok = splitWords(l.words[i]);
            if (tok.matchable == 0) continue;
            total++;
            if (l.starts[i] > 0) timed++;
            else if (tok.matchable > 4) longUntimed++;
        }
        if (timed == 0) return false;
        if (timed == total) return true;
        if (audioCovered && timed * 2 >= total) return true;
        return longUntimed == 0 && timed * 5 >= total * 4;
    }

    /**
     * Fills `starts` and `ends` for any remaining untimed lines by interpolating between timed neighbours so starts
     * stay strictly increasing and the UI can display the song as time-synced.
     */
    static boolean interpolateUntimed(Lyrics l, long songDurationMs) {
        if (l == null || l.words.length == 0) return false;
        int n = l.words.length;
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            if (l.starts[i] > 0) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return false;

        if (first > 0) {
            long base = Math.max(1L, l.starts[first] - first * 2500L);
            for (int j = 0; j < first; j++) {
                l.starts[j] = base + (l.starts[first] - base) * j / first;
            }
        }
        int prev = first;
        for (int i = first + 1; i <= last; i++) {
            if (l.starts[i] > 0) {
                if (i > prev + 1) {
                    for (int j = prev + 1; j < i; j++) {
                        l.starts[j] = l.starts[prev] + (l.starts[i] - l.starts[prev]) * (j - prev) / (i - prev);
                    }
                }
                prev = i;
            }
        }
        if (last < n - 1) {
            long from = Math.max(l.starts[last], l.ends[last]);
            int rem = n - 1 - last;
            long tailEnd = songDurationMs > from ? songDurationMs : from + rem * 2500L;
            if (tailEnd <= from) tailEnd = from + rem * 2000L;
            for (int j = last + 1; j < n; j++) {
                l.starts[j] = from + (tailEnd - from) * (j - last) / (rem + 1);
            }
        }

        for (int i = 0; i < n; i++) {
            if (l.starts[i] <= 0) l.starts[i] = 1;
            if (i > 0 && l.starts[i] <= l.starts[i - 1]) l.starts[i] = l.starts[i - 1] + 1;
        }
        for (int i = 0; i < n; i++) {
            if (l.ends[i] <= l.starts[i]) {
                l.ends[i] = i + 1 < n ? l.starts[i + 1] : Math.max(l.starts[i] + 2000L, songDurationMs);
            } else if (i + 1 < n && l.ends[i] > l.starts[i + 1]) {
                l.ends[i] = l.starts[i + 1];
            }
        }
        return true;
    }

    private static long lineEnd(Lyrics l, int i) {
        if (l.ends[i] > l.starts[i]) return l.ends[i];
        if (i + 1 < l.starts.length && l.starts[i + 1] > l.starts[i]) return l.starts[i + 1];
        return l.starts[i] + 5000L;
    }

    private static Tokens splitWords(String line) {
        if (line == null || line.isEmpty()) return new Tokens(new int[0], new String[0], 0);
        ArrayList<Integer> starts = new ArrayList<>();
        ArrayList<String> norms = new ArrayList<>();
        int matchable = 0;
        int len = line.length(), i = 0;
        while (i < len) {
            while (i < len && Character.isWhitespace(line.charAt(i))) i++;
            if (i >= len) break;
            int s = i;
            while (i < len && !Character.isWhitespace(line.charAt(i))) i++;
            String norm = normalize(line.substring(s, i));
            starts.add(s);
            norms.add(norm);
            if (!norm.isEmpty()) matchable++;
        }
        int[] at = new int[starts.size()];
        String[] normArr = new String[norms.size()];
        for (int j = 0; j < at.length; j++) {
            at[j] = starts.get(j);
            normArr[j] = norms.get(j);
        }
        return new Tokens(at, normArr, matchable);
    }

    /** Monotonic weighted LCS aligning one line's matchable tokens against candidate heard words. */
    private static long[] alignLineTokens(Tokens tok, List<Word> cand) {
        int[] mapIdx = new int[tok.matchable];
        String[] lyricWords = new String[tok.matchable];
        int mCount = 0;
        for (int t = 0; t < tok.norm.length; t++) {
            if (!tok.norm[t].isEmpty()) {
                mapIdx[mCount] = t;
                lyricWords[mCount] = tok.norm[t];
                mCount++;
            }
        }
        int kCount = cand.size();
        int[][] dp = new int[mCount + 1][kCount + 1];
        for (int m = 1; m <= mCount; m++) {
            for (int k = 1; k <= kCount; k++) {
                int best = Math.max(dp[m - 1][k], dp[m][k - 1]);
                int ms = matchScore(lyricWords[m - 1], cand.get(k - 1).norm);
                if (ms > 0) best = Math.max(best, dp[m - 1][k - 1] + ms);
                dp[m][k] = best;
            }
        }
        long[] out = new long[tok.at.length];
        java.util.Arrays.fill(out, -1L);
        int m = mCount, k = kCount;
        while (m > 0 && k > 0) {
            int ms = matchScore(lyricWords[m - 1], cand.get(k - 1).norm);
            if (ms > 0 && dp[m][k] == dp[m - 1][k - 1] + ms) {
                out[mapIdx[m - 1]] = cand.get(k - 1).startMs;
                m--;
                k--;
            } else if (dp[m - 1][k] >= dp[m][k - 1]) {
                m--;
            } else {
                k--;
            }
        }
        return out;
    }

    /** Interpolates unmatched words between matched neighbours and clamps all starts non-decreasing in [lineStart, lineEnd]. */
    private static long[] fillWordStarts(long[] matched, long lineStart, long lineEnd) {
        int n = matched.length;
        long[] out = new long[n];
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            if (matched[i] >= 0) {
                out[i] = Math.max(lineStart, Math.min(lineEnd, matched[i]));
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) {
            for (int i = 0; i < n; i++) out[i] = lineStart + (lineEnd - lineStart) * i / Math.max(1, n);
            return out;
        }
        if (first > 0) {
            for (int i = 0; i < first; i++) out[i] = lineStart + (out[first] - lineStart) * i / first;
        }
        int prev = first;
        for (int i = first + 1; i <= last; i++) {
            if (matched[i] >= 0) {
                if (i > prev + 1) {
                    for (int j = prev + 1; j < i; j++) {
                        out[j] = out[prev] + (out[i] - out[prev]) * (j - prev) / (i - prev);
                    }
                }
                prev = i;
            }
        }
        if (last < n - 1) {
            for (int i = last + 1; i < n; i++) out[i] = out[last] + (lineEnd - out[last]) * (i - last) / (n - last);
        }
        long floor = lineStart;
        for (int i = 0; i < n; i++) {
            long v = Math.max(floor, Math.min(lineEnd, out[i]));
            out[i] = v;
            floor = v;
        }
        return out;
    }

    static int matchScore(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return SCORE_EXACT;
        int minLen = Math.min(a.length(), b.length());
        int maxDist = minLen <= 2 ? 0 : minLen <= 5 ? 1 : 2;
        if (maxDist == 0 || Math.abs(a.length() - b.length()) > maxDist) return 0;
        int d = editDistance(a, b);
        if (d > maxDist) return 0;
        return d == 1 ? SCORE_DIST1 : SCORE_DIST2;
    }

    static int editDistance(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }
}
