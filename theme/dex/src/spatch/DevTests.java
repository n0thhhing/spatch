package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

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
        test("Home sections are tracked", new Test() {
            public String run() {
                if (Content.onHome().isEmpty()) throw new Skip("open Home first");
                return Content.onHome().size() + " on Home";
            }
        });
        return results;
    }

    private int id(String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }
}
