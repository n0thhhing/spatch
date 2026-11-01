package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import kotlin.jvm.functions.Function0;
import p.rhm0;

/** Self tests run inside the patched app, where they see the real classes, resources and audio. */
final class DevTests {
    private static final String PASS = "PASS", FAIL = "FAIL", SKIP = "SKIP";
    private static final String[] VIEW_IDS = {
        "navigation_bar", "cover_image", "tracks_carousel_view", "recycler_view", "overlay_controls_layout",
        "playback_controls_container", "track_info_view_title", "track_info_view_subtitle", "seekbar",
        "nowplaying_elements_playpause_button",
    };

    /** One outcome. A SKIP means this test needs a state the app isn't in, such as music playing. */
    static final class Result {
        final String name, state, detail;

        Result(String name, String state, String detail) {
            this.name = name;
            this.state = state;
            this.detail = detail;
        }

        boolean failed() {
            return FAIL.equals(state);
        }

        public String toString() {
            return state + "  " + name + (detail.isEmpty() ? "" : "\n      " + detail);
        }
    }

    private interface Test {
        String run() throws Exception;
    }

    /** Returns a detail to report; a thrown Skip skips, and anything else thrown fails. */
    private static final class Skip extends RuntimeException {
        Skip(String why) {
            super(why);
        }
    }

    private final Context context;
    private final View root;
    private final ArrayList<Result> results = new ArrayList<Result>();

    private DevTests(Context context, View root) {
        this.context = context;
        this.root = root;
    }

    static List<Result> run(Context context, View root) {
        return new DevTests(context, root).all();
    }

    static String summary(List<Result> results) {
        int passed = 0, failed = 0, skipped = 0;
        for (Result r : results) {
            if (r.failed()) failed++;
            else if (SKIP.equals(r.state)) skipped++;
            else passed++;
        }
        return passed + " passed, " + failed + " failed, " + skipped + " skipped";
    }

    /** One broken test must not stop the rest, or hide what went wrong. */
    void test(String name, Test test) {
        try {
            results.add(new Result(name, PASS, test.run()));
        } catch (Skip s) {
            results.add(new Result(name, SKIP, s.getMessage()));
        } catch (Throwable t) {
            results.add(new Result(name, FAIL, t.toString()));
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private List<Result> all() {
        test("Settings tables match", new Test() {
            public String run() {
                int size = Settings.KEYS.length;
                check(Settings.DEFAULTS.length == size, "defaults");
                check(SpatchSettings.FEATURE_TITLES.length == size, "titles");
                check(SpatchSettings.FEATURE_SUBTITLES.length == size, "subtitles");
                check(SpatchSettings.FEATURE_ICONS.length == size, "icons");
                check(Settings.CHOICE_KEYS.length == Settings.CHOICES.length, "choices");
                check(new HashSet<String>(Arrays.asList(Settings.KEYS)).size() == size, "duplicate key");
                return size + " settings, " + Settings.CHOICES.length + " choices";
            }
        });
        test("Stored choices are valid", new Test() {
            public String run() {
                for (int i = 0; i < Settings.CHOICES.length; i++) {
                    check(Arrays.asList(Settings.CHOICES[i]).contains(Settings.choice(i)), Settings.CHOICE_KEYS[i]);
                }
                return "";
            }
        });
        test("Every icon exists", new Test() {
            public String run() {
                ArrayList<String> missing = new ArrayList<String>();
                for (String icon : new HashSet<String>(Arrays.asList(SpatchSettings.FEATURE_ICONS))) {
                    if (id("encore_icon_" + icon + "_24", "drawable") == 0) missing.add(icon);
                }
                check(missing.isEmpty(), "missing " + missing);
                return "";
            }
        });
        test("Spotify view ids exist", new Test() {
            public String run() {
                ArrayList<String> missing = new ArrayList<String>();
                for (String name : VIEW_IDS) if (id(name, "id") == 0) missing.add(name);
                check(missing.isEmpty(), "missing " + missing);
                return VIEW_IDS.length + " ids";
            }
        });
        test("Obfuscated classes bind", new Test() {
            public String run() {
                String[] names = "@OBFUSCATED@".split(",");
                ArrayList<String> missing = new ArrayList<String>();
                for (String name : names) {
                    try {
                        Class.forName(name, false, DevTests.class.getClassLoader());
                    } catch (ClassNotFoundException e) {
                        missing.add(name);
                    }
                }
                check(missing.isEmpty(), "missing " + missing);
                return names.length + " classes";
            }
        });
        test("Preferences save and read back", new Test() {
            public String run() {
                SharedPreferences scratch = context.getSharedPreferences("spatch_dev_test", 0);
                check(scratch.edit().putBoolean("probe", true).commit(), "commit failed");
                check(scratch.getBoolean("probe", false), "value lost");
                check(scratch.edit().remove("probe").commit(), "cleanup failed");
                return "";
            }
        });
        test("Private storage is writable", new Test() {
            public String run() throws Exception {
                File probe = new File(context.getCacheDir(), "spatch_dev_probe");
                check(probe.createNewFile() || probe.exists(), "could not create a file");
                check(probe.delete(), "could not delete a file");
                return "";
            }
        });
        test("Theme applied", new Test() {
            public String run() {
                check(!Palette.resourceError, "theme resources failed to load, so the build's default is showing");
                return Palette.activeName() + (Palette.activeName().equals(Palette.requested()) ? "" : ", " + Palette.requested() + " after restart");
            }
        });
        test("Settings font loads", new Test() {
            public String run() {
                check(Fonts.settings(400) != null, "no typeface");
                return "";
            }
        });
        test("Spring settles", new Test() {
            public String run() {
                float[] state = {PlayerArt.PRESSED, 0};
                for (int frame = 0; frame < 400; frame++) PlayerArt.spring(state, 1, 16);
                check(state[0] == 1 && state[1] == 0, "ended at " + state[0]);
                return "";
            }
        });
        test("Album colour stays visible", new Test() {
            public String run() {
                for (int color : new int[] {0xff000000, 0xff808080, 0xff400808, 0xffffffff}) {
                    int picked = Tint.vivid(new int[] {color});
                    check(Math.max(picked >> 16 & 255, Math.max(picked >> 8 & 255, picked & 255)) >= 200, Integer.toHexString(color));
                }
                return "";
            }
        });
        test("Audio ring fits its view", new Test() {
            public String run() {
                float reach = 1.045f + .012f + 1.12f * PlayerArt.AUDIO_REACH + .012f * PlayerArt.BOLD / 2;
                check(reach < 1 / .88f, "reaches " + reach);
                return "";
            }
        });
        test("Spectrum of silence is empty", new Test() {
            public String run() {
                for (float level : AudioSpectrum.bands(new byte[1024], 44100, Spectrum.BANDS)) check(level == 0, "level " + level);
                return "";
            }
        });
        test("Audio permission", new Test() {
            public String run() {
                if (context.checkSelfPermission(AudioSpectrum.PERMISSION) != 0) throw new Skip("not granted, so visualizers stay off");
                return "granted";
            }
        });
        test("Audio session", new Test() {
            public String run() {
                if (AudioSpectrum.session() <= 0) throw new Skip("play something on this phone first");
                return "session " + AudioSpectrum.session();
            }
        });
        test("Navigation bar is attached", new Test() {
            public String run() {
                if (root == null) throw new Skip("no window");
                if (root.findViewById(id("navigation_bar", "id")) == null) throw new Skip("open Home first");
                return "";
            }
        });
        test("Touch layer is attached", new Test() {
            public String run() {
                check(Flourish.attached(), "hold ring, touch glow and playlist bursts need it");
                return "page " + Flourish.page();
            }
        });
        test("Home sections are tracked", new Test() {
            public String run() {
                if (Content.onHome().isEmpty()) throw new Skip("open Home first");
                return Content.onHome().size() + " on Home";
            }
        });
        test("Every Spatch hook links", new Test() {
            public String run() {
                inApp();
                String[] hooks = "@HOOKS@".split(",");
                ArrayList<String> broken = new ArrayList<String>();
                for (String hook : hooks) if (!links(hook.split("\\|"))) broken.add(hook);
                check(broken.isEmpty(), "broken " + broken);
                return hooks.length + " hooks";
            }
        });
        test("Hardcoded ids match Spotify's", new Test() {
            public String run() {
                inApp();
                check(ClickHaptic.PLAYER_BUTTON == id("nowplaying_elements_playpause_button", "id"), "player play button");
                check(ClickHaptic.MINI_BUTTON == id("play_pause_button", "id"), "mini player button");
                check(FilterRow.CHIPS == id("filter_chips_container", "id"), "filter chips");
                return "";
            }
        });
        test("Clicks on runtime views", new Test() {
            public String run() {
                inApp();
                View generated = new View(context);
                generated.setId(0x1a); // the id in the crash: it has no resource name
                new ClickHaptic().sendAccessibilityEvent(generated, 1);
                return "";
            }
        });
        test("New title starts the arrival", new Test() {
            public String run() {
                motion();
                TextView title = new TextView(context);
                TextSlide.attach(title);
                title.setText("first");
                PlayerArt.songAt = -100000;
                title.setText("second");
                boolean started = PlayerArt.songAt > 0;
                PlayerArt.songAt = -100000;
                check(started, "the title watcher never reached the artwork");
                return "";
            }
        });
        test("Artwork draws in every style", new Test() {
            public String run() {
                inApp();
                String skip = Settings.choice(Settings.ART_SKIP), outline = Settings.choice(Settings.ART_OUTLINE), style = Settings.choice(Settings.GLOW_STYLE);
                Canvas canvas = new Canvas(Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888));
                int drawn = 0;
                try {
                    for (String s : Settings.CHOICES[Settings.ART_SKIP]) {
                        for (String o : Settings.CHOICES[Settings.ART_OUTLINE]) {
                            for (String g : Settings.CHOICES[Settings.GLOW_STYLE]) {
                                Settings.setChoice(Settings.ART_SKIP, s);
                                Settings.setChoice(Settings.ART_OUTLINE, o);
                                Settings.setChoice(Settings.GLOW_STYLE, g);
                                art().draw(canvas);
                                drawn++;
                            }
                        }
                    }
                } finally {
                    Settings.setChoice(Settings.ART_SKIP, skip);
                    Settings.setChoice(Settings.ART_OUTLINE, outline);
                    Settings.setChoice(Settings.GLOW_STYLE, style);
                }
                return drawn + " styles";
            }
        });
        test("Shrink and Fade change the pixels", new Test() {
            public String run() throws Exception {
                motion();
                String skip = Settings.choice(Settings.ART_SKIP);
                try {
                    Settings.setChoice(Settings.ART_SKIP, "slide");
                    check(red(arrived()), "the art should reach past " + EDGE + " at rest");
                    Settings.setChoice(Settings.ART_SKIP, "shrink");
                    check(!red(arrived()), "a new song's art stayed full size");
                    Settings.setChoice(Settings.ART_SKIP, "fade");
                    PlayerArt fading = art();
                    PlayerArt.songChanged();
                    Thread.sleep(2);
                    fading.draw(new Canvas(Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888)));
                    check(fading.getAlpha() < .2f, "a new song's art stayed visible: " + fading.getAlpha());
                    PlayerArt.songAt = -100000;
                    PlayerArt leaving = art();
                    Canvas canvas = new Canvas(Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888));
                    leaving.draw(canvas);
                    Thread.sleep(2);
                    PlayerArt.songChanged();
                    Thread.sleep(2);
                    leaving.draw(canvas);
                    check(leaving.getAlpha() == 1, "the old art snapped away: " + leaving.getAlpha());
                } finally {
                    Settings.setChoice(Settings.ART_SKIP, skip);
                    PlayerArt.songAt = -100000;
                }
                return "";
            }
        });
        test("Rows build on real Compose", new Test() {
            public String run() {
                inApp();
                boolean rise = Settings.get(Settings.LIBRARY_CASCADE);
                try {
                    Settings.set(Settings.LIBRARY_CASCADE, true);
                    int on = modifiers(true);
                    Settings.set(Settings.LIBRARY_CASCADE, false);
                    int off = modifiers(true);
                    check(on == off && on > 0, "a row must keep the same layers with Rise in on and off, or its card goes: " + on + " and " + off);
                    check(modifiers(false) > 0, "track rows");
                    return on + " modifiers";
                } finally {
                    Settings.set(Settings.LIBRARY_CASCADE, rise);
                }
            }
        });
        test("Play button press still plays", new Test() {
            public String run() {
                inApp();
                final int[] clicks = {0};
                Object answer = Flourish.pressed(new Function0() {
                    public Object invoke() {
                        clicks[0]++;
                        return "played";
                    }
                }, ScallopShape.PLAYING).invoke();
                check(clicks[0] == 1 && "played".equals(answer), "the wrapper lost Spotify's click");
                return "";
            }
        });
        test("Burst draws", new Test() {
            public String run() {
                motion();
                int before = Confetti.live;
                Confetti.at(root, root.getWidth() / 2f, root.getHeight() / 2f);
                check(Confetti.live == before + 1 || before > 0, "nothing joined the window");
                return "";
            }
        });
        test("Artwork page inflates", new Test() {
            public String run() {
                inApp();
                View page = LayoutInflater.from(context).inflate(id("square_cover_art_content", "layout"), null, false);
                check(page.findViewById(id("image", "id")) instanceof PlayerArt, "the art isn't Spatch's view");
                return "";
            }
        });
        return results;
    }

    private static final int ART = 400, EDGE = 150;

    /** Tests of real Spotify and Android behaviour mean nothing outside the app. */
    private void inApp() {
        if (root == null) throw new Skip("needs the running app");
    }

    private void motion() {
        inApp();
        if (!Wave.animationsEnabled()) throw new Skip("turn off reduce motion first");
    }

    private PlayerArt art() {
        PlayerArt page = new PlayerArt(context, null);
        page.setImageDrawable(new ColorDrawable(0xffff0000));
        page.layout(0, 0, ART, ART);
        return page;
    }

    /** The pixels of a page that has just landed on a new song. */
    private Bitmap arrived() throws InterruptedException {
        PlayerArt page = art();
        PlayerArt.songChanged();
        Thread.sleep(2);
        Bitmap pixels = Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888);
        page.draw(new Canvas(pixels));
        return pixels;
    }

    /** Full-size art reaches this point; the smallest Shrink doesn't. */
    private static boolean red(Bitmap pixels) {
        int c = pixels.getPixel(ART / 2 + EDGE, ART / 2);
        return (c >> 16 & 255) > 200 && (c >> 8 & 255) < 80;
    }

    /** Runs a row through Spotify's real shape, padding and layer code; returns how many modifiers it added. */
    private static int modifiers(boolean library) {
        final int[] added = {0};
        rhm0 row = (rhm0) Proxy.newProxyInstance(rhm0.class.getClassLoader(), new Class<?>[] {rhm0.class}, new InvocationHandler() {
            public Object invoke(Object self, Method method, Object[] args) {
                if ("hashCode".equals(method.getName())) return Integer.valueOf(0);
                if ("equals".equals(method.getName())) return Boolean.valueOf(self == args[0]);
                if ("toString".equals(method.getName())) return "row";
                if (method.getReturnType() == boolean.class) return Boolean.FALSE;
                added[0]++;
                return self;
            }
        });
        if (library) Surfaces.libraryRow(row); else Surfaces.trackRow(row);
        return added[0];
    }

    /** {invoke kind, class, method, descriptor}: public and exact, or Spotify's call dies when it first runs. */
    private static boolean links(String[] hook) {
        try {
            Class<?> owner = Class.forName(hook[1], false, DevTests.class.getClassLoader());
            if (!Modifier.isPublic(owner.getModifiers())) return false;
            for (Method m : owner.getDeclaredMethods()) {
                if (!m.getName().equals(hook[2]) || !Modifier.isPublic(m.getModifiers())) continue;
                if (Modifier.isStatic(m.getModifiers()) != "invoke-static".equals(hook[0])) continue;
                StringBuilder shape = new StringBuilder("(");
                for (Class<?> p : m.getParameterTypes()) shape.append(descriptor(p));
                if (shape.append(')').append(descriptor(m.getReturnType())).toString().equals(hook[3])) return true;
            }
        } catch (ClassNotFoundException | LinkageError missing) {
            // a parameter type that isn't in this build counts as broken
        }
        return false;
    }

    private static String descriptor(Class<?> type) {
        if (type.isArray()) return "[" + descriptor(type.getComponentType());
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        return type == int.class ? "I" : type == long.class ? "J" : type == float.class ? "F" : type == boolean.class ? "Z" : type == void.class ? "V"
            : type == double.class ? "D" : type == byte.class ? "B" : type == short.class ? "S" : "C";
    }

    private int id(String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }
}
