package spatch;

import java.nio.charset.StandardCharsets;

/** Bounds-checked sfnt reader: a file only reaches Android's font APIs after passing here. */
final class FontFile {
    static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final int NAME_LIMIT = 48;
    private static final String[] REQUIRED = {"cmap", "head", "hhea", "hmtx", "maxp"};

    final String family;
    final int weight; // OS/2 usWeightClass, 400 when absent
    final boolean variable; // has a usable wght axis
    final int minWeight, maxWeight;

    private FontFile(String family, int weight, boolean variable, int minWeight, int maxWeight) {
        this.family = family;
        this.weight = weight;
        this.variable = variable;
        this.minWeight = minWeight;
        this.maxWeight = maxWeight;
    }

    /** The message is shown to the user as it is. */
    static FontFile parse(byte[] b) {
        if (b.length > MAX_BYTES) throw new IllegalArgumentException("That font is over the 25 MB limit.");
        if (b.length < 12) throw new IllegalArgumentException("That file is not a font.");
        int magic = i32(b, 0);
        if (magic != 0x00010000 && magic != 0x74727565 && magic != 0x4f54544f) {
            boolean other = magic == 0x74746366 || magic == 0x774f4646 || magic == 0x774f4632;
            throw new IllegalArgumentException(other ? "Only single .ttf and .otf fonts are supported." : "That file is not a font.");
        }
        int tables = u16(b, 4);
        if (tables == 0 || tables > 256 || 12 + 16L * tables > b.length) throw corrupt();
        int head = 0, name = 0, os2 = 0, fvar = 0, cmap = 0;
        int[] found = new int[REQUIRED.length];
        for (int i = 0; i < tables; i++) {
            int entry = 12 + i * 16;
            String tag = new String(b, entry, 4, StandardCharsets.ISO_8859_1);
            long offset = u32(b, entry + 8), length = u32(b, entry + 12);
            if (offset + length > b.length) throw corrupt();
            for (int r = 0; r < REQUIRED.length; r++) if (REQUIRED[r].equals(tag) && length > 0) found[r] = 1;
            if (tag.equals("head") && length >= 54) head = (int) offset;
            else if (tag.equals("name") && length >= 6) name = (int) offset;
            else if (tag.equals("OS/2") && length >= 6) os2 = (int) offset;
            else if (tag.equals("fvar") && length >= 16) fvar = (int) offset;
            else if (tag.equals("cmap") && length >= 4) cmap = (int) offset;
        }
        for (int f : found) if (f == 0) throw corrupt();
        if (head == 0 || cmap == 0 || i32(b, head + 12) != 0x5f0f3cf5) throw corrupt();
        int units = u16(b, head + 18);
        if (units < 16 || units > 16384 || u16(b, cmap + 2) == 0) throw corrupt();

        int weight = os2 == 0 ? 400 : u16(b, os2 + 4);
        if (weight < 1 || weight > 1000) weight = 400;
        boolean variable = false;
        int min = 400, max = 400;
        if (fvar != 0) {
            int axes = fvar + u16(b, fvar + 4), count = u16(b, fvar + 8), size = u16(b, fvar + 10);
            if (size < 20 || axes + (long) count * size > b.length) throw corrupt();
            for (int i = 0; i < count; i++) {
                int axis = axes + i * size;
                if (i32(b, axis) != 0x77676874) continue; // 'wght'
                min = Math.max(1, Math.min(1000, i32(b, axis + 4) >> 16));
                max = Math.max(min, Math.min(1000, i32(b, axis + 12) >> 16));
                variable = max > min;
            }
        }
        return new FontFile(name == 0 ? null : family(b, name), weight, variable, min, max);
    }

    /** Typographic family (16), else family (1); Windows Unicode, else Mac Roman ASCII. */
    private static String family(byte[] b, int table) {
        int count = u16(b, table + 2), strings = table + u16(b, table + 4);
        if (table + 6 + 12L * count > b.length) return null;
        String typographic = null, plain = null;
        for (int i = 0; i < count; i++) {
            int record = table + 6 + i * 12;
            int platform = u16(b, record), id = u16(b, record + 6), length = u16(b, record + 8);
            long at = (long) strings + u16(b, record + 10);
            if ((id != 1 && id != 16) || (platform != 3 && platform != 1) || length == 0 || at + length > b.length) continue;
            String text = cleanName(new String(b, (int) at, length, platform == 3 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1));
            if (text == null) continue;
            if (id == 16 && typographic == null) typographic = text;
            if (id == 1 && plain == null) plain = text;
        }
        return typographic != null ? typographic : plain;
    }

    /** A display label only, never a path: controls go and it stays short. Null when nothing is left. */
    static String cleanName(String name) {
        if (name == null) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            if (!Character.isISOControl(c)) out.appendCodePoint(c);
            i += Character.charCount(c);
        }
        String clean = out.toString().trim();
        if (clean.isEmpty()) return null;
        return clean.length() <= NAME_LIMIT ? clean : clean.substring(0, clean.offsetByCodePoints(0, clean.codePointCount(0, NAME_LIMIT))).trim();
    }

    private static IllegalArgumentException corrupt() {
        return new IllegalArgumentException("That font file is damaged or incomplete.");
    }

    private static int u16(byte[] b, int p) {
        if (p < 0 || p + 2 > b.length) throw corrupt();
        return (b[p] & 255) << 8 | b[p + 1] & 255;
    }

    private static int i32(byte[] b, int p) {
        return u16(b, p) << 16 | u16(b, p + 2);
    }

    private static long u32(byte[] b, int p) {
        return i32(b, p) & 0xffffffffL;
    }
}
