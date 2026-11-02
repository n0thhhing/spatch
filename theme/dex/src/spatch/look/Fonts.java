package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Every Spotify Mix load (p.jw21.c, p.n74.K, p.h4b1.a) asks here first; null keeps Spotify's own font.
 * Compose and laid-out text cache faces for the process, so a selection takes effect at the next start
 * (applied) while settings previews it at once (selected). Assets are stored uncompressed and mapped.
 */
public final class Fonts {
    static final String DEFAULT = "default", SYSTEM = "system", CUSTOM = "custom:";
    private static final String ASSETS = "spatch-fonts/", DIR = "spatch-fonts", KEY = "font";
    /** Spotify's text roles, used for previews and for text Spatch draws itself under Default. */
    static final int REGULAR = 0x7f09000d, BOLD = 0x7f090006;
    private static final int SPOTIFY_MIX = 0x7f090002, TITLE_BOLD = 0x7f090012, TITLE_EXTRABOLD = 0x7f090015;
    private static final int[] WEIGHTS = {400, 500, 700, 800};
    /** id, label, then one variable file or Regular and Bold statics. */
    static final String[][] BUILT_IN = {
        {"roboto", "Roboto", "roboto.ttf"},
        {"noto-sans", "Noto Sans", "noto-sans.ttf"},
        {"atkinson", "Atkinson Hyperlegible", "atkinson-regular.ttf", "atkinson-bold.ttf"},
        {"monaspace-neon", "Monaspace Neon", "monaspace-neon.ttf"},
        {"monaspace-argon", "Monaspace Argon", "monaspace-argon.ttf"},
        {"monaspace-xenon", "Monaspace Xenon", "monaspace-xenon.ttf"},
        {"monaspace-radon", "Monaspace Radon", "monaspace-radon.ttf"},
        {"monaspace-krypton", "Monaspace Krypton", "monaspace-krypton.ttf"},
    };

    private static Context app;
    private static SharedPreferences prefs;
    private static String selected = DEFAULT, applied = DEFAULT;
    private static final HashMap<String, Typeface> faces = new HashMap<String, Typeface>();
    private static final HashMap<String, FontFamily> families = new HashMap<String, FontFamily>();
    private static final Set<Typeface> ours = Collections.newSetFromMap(new IdentityHashMap<Typeface, Boolean>());

    private Fonts() {}

    /** Application start, before any text exists. A missing custom family falls back to Default. */
    static synchronized void load(Context c) {
        if (prefs != null || c == null) return;
        app = c;
        prefs = c.getSharedPreferences("spatch", 0);
        selected = known(prefs.getString(KEY, DEFAULT));
        applied = selected;
    }

    private static String known(String id) {
        if (DEFAULT.equals(id) || SYSTEM.equals(id) || builtIn(id) != null) return id;
        return id != null && id.startsWith(CUSTOM) && folder(id) != null && folder(id).isDirectory() ? id : DEFAULT;
    }

    static String selected() {
        return selected;
    }

    static boolean pendingRestart() {
        return !selected.equals(applied);
    }

    static synchronized void select(String id) {
        selected = known(id);
        if (prefs != null) prefs.edit().putString(KEY, selected).apply();
    }

    private static String[] builtIn(String id) {
        for (String[] font : BUILT_IN) if (font[0].equals(id)) return font;
        return null;
    }

    /** The weight behind a Spotify text font, 0 for anything else (the spoticon icon font stays). */
    static int weight(int resource) {
        if (resource == SPOTIFY_MIX || resource == 0x7f090003) return 400;
        if (resource < 0x7f090004 || resource > 0x7f09001b) return 0;
        switch (resource) {
            case 0x7f090005: case 0x7f090008: case 0x7f09000a: case 0x7f09000c: case 0x7f09000d: case 0x7f09000f:
                return 400;
            case 0x7f090011: case 0x7f090014: case 0x7f090015: case 0x7f090017: case 0x7f090019: case 0x7f09001b:
                return 800;
            default:
                return 700;
        }
    }

    /** Hook: the replacement for a font resource, or null to load Spotify's. */
    public static Typeface resource(int id) {
        int weight = weight(id);
        return weight == 0 || DEFAULT.equals(applied) ? null : face(applied, weight);
    }

    /** Hook: the variable Spotify Mix has no real weights of ours; pick the role for its weight. */
    public static int variable(int weight) {
        if (DEFAULT.equals(applied)) return SPOTIFY_MIX;
        return weight >= 800 ? TITLE_EXTRABOLD : weight >= 600 ? TITLE_BOLD : weight >= 500 ? BOLD : REGULAR;
    }

    /** Hook: Spotify's width and weight axes must not reach a face built here. */
    public static boolean owned(Typeface typeface) {
        synchronized (Fonts.class) {
            return ours.contains(typeface);
        }
    }

    /** Null when the family cannot load: callers keep what they had. */
    static synchronized Typeface face(String id, int weight) {
        String key = id + "/" + weight;
        Typeface cached = faces.get(key);
        if (cached != null || Build.VERSION.SDK_INT < 29) return cached;
        try {
            Typeface built;
            if (DEFAULT.equals(id)) {
                return app.getResources().getFont(weight >= 600 ? BOLD : REGULAR);
            } else if (SYSTEM.equals(id)) {
                built = Typeface.create(Typeface.DEFAULT, weight, false);
            } else {
                built = new Typeface.CustomFallbackBuilder(family(id)).setStyle(new FontStyle(weight, 0)).setSystemFallback("sans-serif").build();
            }
            faces.put(key, built);
            ours.add(built);
            return built;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** What Spatch's own text outside settings uses. */
    static Typeface applied(int weight) {
        Typeface face = face(applied, weight);
        return face != null ? face : weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    /** Settings text: the selection, previewed before it is applied; Default keeps the system look. */
    static Typeface settings(int weight) {
        Typeface face = DEFAULT.equals(selected) ? null : face(selected, weight);
        return face != null ? face : weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    /** Restyles a view tree in place; views tagged PINNED show their own family. */
    static final Object PINNED = new Object();

    static void restyle(View view) {
        if (view instanceof TextView && view.getTag() != PINNED) {
            Typeface now = ((TextView) view).getTypeface();
            ((TextView) view).setTypeface(settings(now != null && now.isBold() ? 700 : 400));
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) restyle(group.getChildAt(i));
        }
    }

    private static FontFamily family(String id) throws IOException {
        FontFamily family = families.get(id);
        if (family != null) return family;
        String[] font = builtIn(id);
        ArrayList<Font> fonts = new ArrayList<Font>();
        if (font != null && font.length == 3) {
            variable(fonts, asset(font[2]), 100, 1000);
        } else if (font != null) {
            fonts.add(new Font.Builder(asset(font[2])).setWeight(400).build());
            fonts.add(new Font.Builder(asset(font[3])).setWeight(700).build());
        } else {
            File dir = folder(id);
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) throw new IOException("missing family");
            for (File file : files) {
                if (!file.getName().endsWith(".font")) continue;
                byte[] bytes = read(new FileInputStream(file));
                FontFile info = FontFile.parse(bytes);
                ByteBuffer data = map(file);
                if (info.variable) variable(fonts, data, info.minWeight, info.maxWeight);
                else fonts.add(new Font.Builder(data).setWeight(Integer.parseInt(file.getName().replace(".font", ""))).build());
            }
        }
        if (fonts.isEmpty()) throw new IOException("empty family");
        FontFamily.Builder builder = new FontFamily.Builder(fonts.get(0));
        for (int i = 1; i < fonts.size(); i++) builder.addFont(fonts.get(i));
        family = builder.build();
        families.put(id, family);
        return family;
    }

    private static void variable(ArrayList<Font> fonts, ByteBuffer data, int min, int max) throws IOException {
        for (int weight : WEIGHTS) {
            int axis = Math.max(min, Math.min(max, weight));
            fonts.add(new Font.Builder(data).setWeight(weight).setFontVariationSettings("'wght' " + axis).build());
        }
    }

    private static ByteBuffer asset(String name) throws IOException {
        // A plain stream on the descriptor: AssetFileDescriptor's own stream offsets its channel on newer Android.
        try (AssetFileDescriptor fd = app.getAssets().openFd(ASSETS + name)) {
            return new FileInputStream(fd.getFileDescriptor()).getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getLength());
        }
    }

    private static ByteBuffer map(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return in.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, file.length());
        }
    }

    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    static String licenses() {
        StringBuilder out = new StringBuilder();
        for (String file : new String[] {"OFL-Roboto.txt", "OFL-NotoSans.txt", "OFL-Atkinson.txt", "OFL-Monaspace.txt"}) {
            try {
                out.append(new String(read(app.getAssets().open(ASSETS + "licenses/" + file)), StandardCharsets.UTF_8)).append("\n\n");
            } catch (IOException e) {
                out.append(file).append(" could not be read.\n\n");
            }
        }
        return out.toString();
    }

    // Imported families: filesDir/spatch-fonts/<random hex>/{name, <weight>.font}. Nothing a file
    // supplies reaches a path; only ids made here pass valid().

    private static boolean valid(String hex) {
        if (hex.isEmpty() || hex.length() > 32) return false;
        for (char c : hex.toCharArray()) if (Character.digit(c, 16) < 0) return false;
        return true;
    }

    private static File root() {
        return new File(app.getFilesDir(), DIR);
    }

    private static File folder(String id) {
        String hex = id.substring(CUSTOM.length());
        return valid(hex) ? new File(root(), hex) : null;
    }

    /** {id, name} of each imported family, by name. */
    static synchronized ArrayList<String[]> custom() {
        ArrayList<String[]> out = new ArrayList<String[]>();
        File[] dirs = root().listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            if (!dir.isDirectory() || !valid(dir.getName())) continue;
            String name = null;
            try {
                name = new String(read(new FileInputStream(new File(dir, "name"))), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
            out.add(new String[] {CUSTOM + dir.getName(), name == null ? "Custom font" : name});
        }
        Collections.sort(out, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return a[1].compareToIgnoreCase(b[1]);
            }
        });
        return out;
    }

    private static void writeName(File dir, String name) throws IOException {
        File next = new File(dir, "name.tmp");
        try (FileOutputStream out = new FileOutputStream(next)) {
            out.write(name.getBytes(StandardCharsets.UTF_8));
        }
        if (!next.renameTo(new File(dir, "name"))) throw new IOException("name not saved");
    }

    /** Moves validated files (weight -> temp file) in; a partial family never appears. */
    static synchronized String save(String name, java.util.Map<Integer, File> files) throws IOException {
        File root = root();
        root.mkdirs();
        String hex = Long.toHexString(System.nanoTime()) + Integer.toHexString(new java.security.SecureRandom().nextInt() >>> 4);
        File work = new File(root, ".import-" + hex), done = new File(root, hex);
        if (!work.mkdir()) throw new IOException("no storage");
        try {
            for (java.util.Map.Entry<Integer, File> e : files.entrySet()) {
                if (!e.getValue().renameTo(new File(work, e.getKey() + ".font"))) throw new IOException("not moved");
            }
            writeName(work, name);
            if (!work.renameTo(done)) throw new IOException("not saved");
        } catch (IOException e) {
            remove(work);
            throw e;
        }
        return CUSTOM + hex;
    }

    static synchronized void rename(String id, String name) throws IOException {
        File dir = folder(id);
        if (dir != null) writeName(dir, name);
    }

    /** The family's mapped data stays valid until the process ends. Returns whether it was selected. */
    static synchronized boolean delete(String id) {
        boolean active = id.equals(selected);
        if (active) select(DEFAULT);
        File dir = folder(id);
        if (dir != null) remove(dir);
        families.remove(id);
        for (int weight : WEIGHTS) ours.remove(faces.remove(id + "/" + weight));
        return active;
    }

    private static void remove(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) file.delete();
        dir.delete();
    }

    /** Clears imports abandoned by a crash. */
    static synchronized void sweep() {
        File[] dirs = root().listFiles();
        if (dirs != null) for (File dir : dirs) if (dir.getName().startsWith(".import-")) remove(dir);
    }
}
