package spatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
        final boolean[] switches = new boolean[Settings.KEYS.length];
        final String[] choices = new String[Settings.CHOICES.length];
        for (int i = 0; i < switches.length; i++) switches[i] = Settings.get(i);
        for (int i = 0; i < choices.length; i++) choices[i] = Settings.choice(i);
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
                int[] cover = new int[144];
                java.util.Arrays.fill(cover, 0xff802020);
                cover[0] = 0xff1060ff;
                int main = Tint.vivid(cover);
                check((main >> 16 & 255) > (main & 255), "a speck of blue beat a red cover");
                return "";
            }
        });
        test("Audio wave stays inside the plain wave", new Test() {
            public String run() {
                float[] bands = new float[24];
                check(Wave.swell(bands, .5f, 1f) == .25f, "silence should leave a small wave");
                java.util.Arrays.fill(bands, 9f);
                check(Wave.swell(bands, 1f, 9f) == 1f, "loud music went past the bounds");
                return "";
            }
        });
        test("Queue finds the playing row", new Test() {
            public String run() {
                android.widget.TextView text = new android.widget.TextView(context);
                text.setText("Song");
                android.widget.LinearLayout row = new android.widget.LinearLayout(context);
                row.addView(text);
                check(RowCards.shows(row, "Song") && !RowCards.shows(row, "Other") && !RowCards.shows(row, null), "wrong row");
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
        test("Nothing clips the ring on a loud beat", new Test() {
            public String run() {
                PlayerArt page = art();
                int screen = page.getRootView().getWidth();
                float reach = (1.045f + .012f + 1.12f * PlayerArt.AUDIO_REACH + .012f * PlayerArt.BOLD / 2) * (1 + PlayerArt.BEAT);
                check(reach * .88f * page.getWidth() / 2 < screen / 2f, "reaches past the screen");
                PlayerArt.unclip(page, screen);
                for (android.view.ViewParent p = page.getParent(); p instanceof ViewGroup; p = p.getParent()) {
                    check(!((ViewGroup) p).getClipChildren(), p + " clips the ring");
                    if (((ViewGroup) p).getWidth() >= screen) break;
                }
                return "";
            }
        });
        test("Theme colours rebuild in place", new Test() {
            public String run() {
                p.gsc before = p.kex.a;
                p.kex.rebuild();
                check(p.kex.a != before && p.kex.a != null, "the colour object stayed");
                return "";
            }
        });
        test("Player preview leaves the player alone", new Test() {
            public String run() {
                PlayerArt sample = PlayerArt.preview(context, new android.graphics.drawable.ColorDrawable(0xff336699));
                check(!sample.isCurrentPage(), "it would capture audio and set the backdrop");
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10, 10, 0);
                check(!sample.dispatchTouchEvent(down), "it would scratch the real song");
                down.recycle();
                return "";
            }
        });
        test("Theme reveal covers the whole screen", new Test() {
            public String run() {
                check(Math.abs(ThemeReveal.reach(300, 400, 0, 0) - 500) < .01f, "from a corner");
                check(Math.abs(ThemeReveal.reach(300, 400, 300, 400) - 500) < .01f, "from the far corner");
                check(ThemeReveal.reach(300, 400, 150, 200) >= 250, "from the middle");
                return "";
            }
        });
        test("Motes move every frame after weeks of uptime", new Test() {
            public String run() {
                double weeks = 21 * 86400;
                check(Atmosphere.rise(weeks, .012f, 0) != Atmosphere.rise(weeks + .016, .012f, 0), "stuck for a frame");
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
        test("Spotify members bind", new Test() {
            public String run() {
                inApp();
                String[] members = "@MEMBERS@".split(",");
                ArrayList<String> missing = new ArrayList<String>();
                for (String member : members) {
                    String[] parts = member.split("\\|", -1);
                    try {
                        if (!declares(Class.forName(parts[0], false, DevTests.class.getClassLoader()), parts[1], parts[2])) missing.add(member);
                    } catch (ClassNotFoundException | LinkageError e) {
                        missing.add(member);
                    }
                }
                check(missing.isEmpty(), "missing " + missing);
                return members.length + " members";
            }
        });
        test("On the main thread", new Test() {
            public String run() {
                inApp();
                check(Looper.myLooper() == Looper.getMainLooper(), "view tests would mean nothing elsewhere");
                return "";
            }
        });
        test("Every theme stays readable", new Test() {
            public String run() {
                inApp();
                for (int t = 0; t < Palette.NAMES.length; t++) {
                    check(MiniEq.contrast(Palette.tone(t, 3), Palette.tone(t, 0)) >= 4.5, Palette.NAMES[t] + " text");
                    check(MiniEq.contrast(Palette.tone(t, 6), Palette.tone(t, 0)) >= 4.5, Palette.NAMES[t] + " accent");
                }
                return Palette.NAMES.length + " themes";
            }
        });
        test("Theme colours map", new Test() {
            public String run() {
                inApp();
                int theme = Arrays.asList(Palette.NAMES).indexOf(Palette.activeName());
                check(theme >= 0, "unknown theme");
                check(Palette.color(0xff14121c) == Palette.tone(theme, 0), "surface");
                check(Palette.color(0xff1d1a2a) == Palette.tone(theme, 1), "cards");
                check(Palette.color(0xffd0bcff) == Palette.tone(theme, 6), "accent");
                check((Palette.color(0x33d0bcff) >>> 24) == 0x33, "colours must keep their opacity");
                return "";
            }
        });
        test("Spotify's grey cards take the theme", new Test() {
            public String run() {
                inApp();
                check(Surfaces.bg((long) 0xff292929 << 32) == (long) Palette.color(0xff2b2640) << 32, "grey card");
                check(Surfaces.bg((long) 0xff123456 << 32) == (long) 0xff123456 << 32, "other colours must pass through");
                return "";
            }
        });
        test("Album colours all resolve", new Test() {
            public String run() {
                inApp();
                String ring = Settings.choice(Settings.RING_COLOR);
                try {
                    for (String name : Tint.NAMES) {
                        Settings.setChoice(Settings.RING_COLOR, name);
                        check((Tint.color(Settings.RING_COLOR) >>> 24) == 255, name + " is see-through");
                    }
                } finally {
                    Settings.setChoice(Settings.RING_COLOR, ring);
                }
                return Tint.NAMES.length + " colours";
            }
        });
        test("Spatch drawables draw", new Test() {
            public String run() {
                inApp();
                Canvas canvas = new Canvas(Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888));
                View owner = new View(context);
                Atmosphere backdrop = new Atmosphere();
                backdrop.capture(new ColorDrawable(0xff336699));
                Drawable[] all = {new Wave(), new ScallopFill(), new MiniRing(0xffd0bcff, 3), new Transport(owner, 3, new ColorDrawable(0)), backdrop};
                for (Drawable d : all) {
                    d.setBounds(0, 0, ART, ART);
                    d.setLevel(5000);
                    d.draw(canvas);
                }
                return all.length + " drawables";
            }
        });
        test("Spatch views draw", new Test() {
            public String run() {
                inApp();
                Canvas canvas = new Canvas(Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888));
                View[] all = {new MiniArt(context, null), new MiniEq(context, null), new MiniSkip(context, null), new M3Switch(context)};
                for (View v : all) {
                    v.layout(0, 0, ART, ART);
                    v.draw(canvas);
                }
                return all.length + " views";
            }
        });
        test("Switches flip", new Test() {
            public String run() {
                inApp();
                M3Switch flip = new M3Switch(context);
                flip.setChecked(true);
                check(flip.isChecked(), "set");
                flip.toggle();
                check(!flip.isChecked(), "toggle");
                return "";
            }
        });
        test("Play button outline", new Test() {
            public String run() {
                inApp();
                boolean shapes = Settings.shapes();
                Path outline = new Path();
                ScallopShape.trace(outline, ART, ART);
                Paint paint = new Paint(1);
                paint.setColor(0xffff0000);
                Bitmap pixels = Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888);
                new Canvas(pixels).drawPath(outline, paint);
                check(pixels.getPixel(ART / 2, ART / 2) == 0xffff0000, "the outline isn't filled");
                check(pixels.getPixel(2, 2) == 0, "the outline reaches the corner");
                return shapes ? "scallop" : "circle";
            }
        });
        test("Round artwork leaves its corners", new Test() {
            public String run() {
                inApp();
                if (!Settings.roundArt()) throw new Skip("Circular album art is off");
                Bitmap pixels = Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888);
                art().draw(new Canvas(pixels));
                check(!red(pixels, 8, 8), "the art fills the corner");
                check(red(pixels, ART / 2, ART / 2), "the art is missing");
                return "";
            }
        });
        test("Artwork press springs", new Test() {
            public String run() throws Exception {
                motion();
                if (!Settings.artPress()) throw new Skip("press bounce is off");
                PlayerArt page = art();
                Canvas canvas = new Canvas(Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888));
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 200, 200, 0);
                page.dispatchTouchEvent(down);
                down.recycle();
                page.draw(canvas);
                Thread.sleep(20);
                page.draw(canvas);
                check(page.artScale() < 1, "a press must squish the art");
                return "";
            }
        });
        test("Same title stays still", new Test() {
            public String run() {
                motion();
                TextView title = new TextView(context);
                title.setText("same");
                TextSlide.attach(title);
                PlayerArt.songAt = -100000;
                title.setText("same");
                check(PlayerArt.songAt < 0, "Spotify resetting the same text must not replay the arrival");
                return "";
            }
        });
        test("Burst choices all resolve", new Test() {
            public String run() {
                inApp();
                for (String shape : Settings.CHOICES[Settings.BURST_SHAPE]) check(Confetti.shapes(shape).length > 0, shape);
                for (String amount : Settings.CHOICES[Settings.BURST_AMOUNT]) check(Confetti.count(amount) > 0, amount);
                for (String size : Settings.CHOICES[Settings.BURST_SIZE]) check(Confetti.scale(size) > 0, size);
                return "";
            }
        });
        test("Burst obeys its switches", new Test() {
            public String run() {
                motion();
                boolean master = Settings.get(Settings.BURST), mini = Settings.get(Settings.BURST_MINI);
                String when = Settings.choice(Settings.BURST_WHEN);
                int before = Confetti.live;
                try {
                    Settings.set(Settings.BURST, false);
                    Confetti.wanted(root, 0, 0, true, Settings.BURST_MINI);
                    check(Confetti.live == before, "master switch");
                    Settings.set(Settings.BURST, true);
                    Settings.set(Settings.BURST_MINI, false);
                    Confetti.wanted(root, 0, 0, true, Settings.BURST_MINI);
                    check(Confetti.live == before, "place switch");
                    Settings.set(Settings.BURST_MINI, true);
                    Settings.setChoice(Settings.BURST_WHEN, "play");
                    Confetti.wanted(root, 0, 0, false, Settings.BURST_MINI);
                    check(Confetti.live == before, "Play only must skip pause");
                } finally {
                    Settings.set(Settings.BURST, master);
                    Settings.set(Settings.BURST_MINI, mini);
                    Settings.setChoice(Settings.BURST_WHEN, when);
                }
                return "";
            }
        });
        test("Haptics reach the phone", new Test() {
            public String run() {
                inApp();
                View v = new View(context);
                Haptics.playback(v);
                Haptics.navigation(v);
                Haptics.detent(v);
                Haptics.seekDone(v);
                Haptics.toggle(v, true);
                Haptics.saved(v);
                Haptics.preview(v);
                return "";
            }
        });
        test("Fonts load at every weight", new Test() {
            public String run() {
                inApp();
                for (int weight = 100; weight <= 900; weight += 100) check(Fonts.settings(weight) != null, weight + " settings");
                check(Fonts.resource(Fonts.REGULAR) != null, "regular");
                check(Fonts.resource(Fonts.BOLD) != null, "bold");
                return Fonts.selected();
            }
        });
        test("Bad font files are refused", new Test() {
            public String run() {
                boolean refused = false;
                try {
                    FontFile.parse(new byte[64]);
                } catch (IllegalArgumentException e) {
                    refused = true;
                }
                check(refused, "64 empty bytes passed as a font");
                return "";
            }
        });
        test("Spectrum hears a tone", new Test() {
            public String run() {
                byte[] fft = new byte[1024];
                fft[40] = 127;
                float loudest = 0;
                for (float level : AudioSpectrum.bands(fft, 44100, Spectrum.BANDS)) loudest = Math.max(loudest, level);
                check(loudest > 0, "a loud bin showed nothing");
                return "";
            }
        });
        test("Stored settings have the right types", new Test() {
            public String run() {
                inApp();
                java.util.Map<String, ?> stored = context.getSharedPreferences("spatch", 0).getAll();
                for (String key : Settings.KEYS) check(!stored.containsKey(key) || stored.get(key) instanceof Boolean, key);
                for (String key : Settings.CHOICE_KEYS) check(!stored.containsKey(key) || stored.get(key) instanceof String, key);
                return stored.size() + " stored";
            }
        });
        test("Settings report builds", new Test() {
            public String run() {
                check(!DevOptions.changedSettings().isEmpty(), "empty");
                return "";
            }
        });
        test("Player session", new Test() {
            public String run() {
                inApp();
                if (SpatchWidget.controller() == null) throw new Skip("play something first");
                return "connected";
            }
        });
        test("Drawer rows build on real Compose", new Test() {
            public String run() {
                inApp();
                final int[] added = {0};
                Surfaces.drawerRow(row(added));
                check(!Settings.sideCards() || added[0] > 0, "no card");
                return "";
            }
        });
        test("Reduce motion holds everything still", new Test() {
            public String run() throws Exception {
                inApp();
                boolean calm = Settings.get(REDUCE_MOTION);
                String skip = Settings.choice(Settings.ART_SKIP);
                int before = Confetti.live;
                try {
                    Settings.set(REDUCE_MOTION, true);
                    Settings.setChoice(Settings.ART_SKIP, "shrink");
                    check(red(arrived()), "Shrink moved");
                    Confetti.at(root, 0, 0);
                    check(Confetti.live == before, "a burst played");
                } finally {
                    Settings.set(REDUCE_MOTION, calm);
                    Settings.setChoice(Settings.ART_SKIP, skip);
                    PlayerArt.songAt = -100000;
                }
                return "";
            }
        });
        test("Leaving Fade restores full opacity", new Test() {
            public String run() throws Exception {
                motion();
                String skip = Settings.choice(Settings.ART_SKIP);
                Canvas canvas = new Canvas(Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888));
                try {
                    Settings.setChoice(Settings.ART_SKIP, "fade");
                    PlayerArt page = art();
                    PlayerArt.songChanged();
                    Thread.sleep(2);
                    page.draw(canvas);
                    check(page.getAlpha() < 1, "it never faded");
                    Settings.setChoice(Settings.ART_SKIP, "slide");
                    page.draw(canvas);
                    check(page.getAlpha() == 1, "the art stayed see-through");
                } finally {
                    Settings.setChoice(Settings.ART_SKIP, skip);
                    PlayerArt.songAt = -100000;
                }
                return "";
            }
        });
        test("Only play buttons burst", new Test() {
            public String run() {
                motion();
                String when = Settings.choice(Settings.BURST_WHEN);
                boolean master = Settings.get(Settings.BURST), mini = Settings.get(Settings.BURST_MINI), player = Settings.get(Settings.BURST_PLAYER);
                int before = Confetti.live;
                ClickHaptic clicks = new ClickHaptic();
                View button = new View(context);
                try {
                    Settings.set(Settings.BURST, true);
                    Settings.set(Settings.BURST_MINI, true);
                    Settings.setChoice(Settings.BURST_WHEN, "play");
                    button.setContentDescription("Play");
                    button.setId(id("next_button", "id"));
                    clicks.sendAccessibilityEvent(button, 1);
                    check(Confetti.live == before, "a skip button burst");
                    button.setId(ClickHaptic.MINI_BUTTON);
                    button.setContentDescription("Pause");
                    clicks.sendAccessibilityEvent(button, 1);
                    check(Confetti.live == before, "Play only burst on pause");
                    button.setId(ClickHaptic.PLAYER_BUTTON);
                    button.setContentDescription("Play");
                    button.setAccessibilityDelegate(clicks);
                    Settings.set(Settings.BURST_PLAYER, true);
                    ClickHaptic.forward(button);
                    check(Confetti.live == before, "the immersive player burst a second time");
                } finally {
                    Settings.set(Settings.BURST, master);
                    Settings.set(Settings.BURST_MINI, mini);
                    Settings.set(Settings.BURST_PLAYER, player);
                    Settings.setChoice(Settings.BURST_WHEN, when);
                }
                return "";
            }
        });
        test("Every settings page fits", new Test() {
            public String run() {
                inApp();
                ArrayList<String> problems = new ArrayList<String>();
                SpatchSettings fresh = new SpatchSettings(context);
                int width = root.getWidth(), count = fresh.pages.getChildCount();
                for (int i = 0; i < count; i++) {
                    View scroll = fresh.pages.getChildAt(i);
                    scroll.setVisibility(View.VISIBLE);
                    ViewGroup page = (ViewGroup) ((ViewGroup) scroll).getChildAt(0);
                    page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    page.layout(0, 0, width, page.getMeasuredHeight());
                    audit(page, problems);
                }
                check(problems.isEmpty(), problems.size() + " problems: " + problems.subList(0, Math.min(12, problems.size())));
                return count + " pages";
            }
        });
        test("No crash since this install", new Test() {
            public String run() throws Exception {
                inApp();
                File crash = DevOptions.crashFile;
                check(crash == null || !crash.isFile() || crash.lastModified() < installed(), "see Last crash below");
                return "";
            }
        });
        test("No freeze or native crash since this install", new Test() {
            public String run() throws Exception {
                inApp();
                if (Build.VERSION.SDK_INT < 30) throw new Skip("needs Android 11");
                Object system = context.getSystemService("activity");
                List<?> exits = (List<?>) system.getClass().getMethod("getHistoricalProcessExitReasons", String.class, int.class, int.class).invoke(system, null, 0, 16);
                int seen = 0;
                for (Object exit : exits) {
                    if ((Long) exit.getClass().getMethod("getTimestamp").invoke(exit) < installed()) continue;
                    seen++;
                    int reason = (Integer) exit.getClass().getMethod("getReason").invoke(exit);
                    Object why = exit.getClass().getMethod("getDescription").invoke(exit);
                    check(reason != 6, "frozen (ANR): " + why);
                    check(reason != 5, "native crash: " + why);
                    check(reason != 4, "crash: " + why);
                }
                return seen + " exits checked";
            }
        });
        test("Memory has room", new Test() {
            public String run() {
                Runtime heap = Runtime.getRuntime();
                long used = heap.totalMemory() - heap.freeMemory();
                check(used < heap.maxMemory() * .85, "heap nearly full");
                return used / 1048576 + " of " + heap.maxMemory() / 1048576 + " MB";
            }
        });
        speed("Speed: artwork frame", 33, new Runnable() {
            private PlayerArt page;
            private Canvas canvas;

            public void run() {
                if (page == null) {
                    int size = root.getWidth();
                    page = art();
                    page.layout(0, 0, size, size);
                    canvas = new Canvas(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888));
                }
                page.draw(canvas);
            }
        });
        speed("Speed: progress wave", 8, new Runnable() {
            private final Wave wave = new Wave();
            private final Canvas canvas = new Canvas(Bitmap.createBitmap(ART, 64, Bitmap.Config.ARGB_8888));

            public void run() {
                wave.setBounds(0, 0, ART, 64);
                wave.draw(canvas);
            }
        });
        speed("Speed: one list row", 1, new Runnable() {
            public void run() {
                modifiers(true);
            }
        });
        speed("Speed: audio bands", 2, new Runnable() {
            private final byte[] fft = new byte[1024];

            public void run() {
                AudioSpectrum.bands(fft, 44100, Spectrum.BANDS);
            }
        });
        speed("Speed: press scan per frame", 2, new Runnable() {
            public void run() {
                TapBounce.tick(root);
            }
        });
        speed("Speed: opening Spatch settings", 250, new Runnable() {
            public void run() {
                new SpatchSettings(context);
            }
        });
        test("Scratch and hold seek stay inside the song", new Test() {
            public String run() {
                check(ArtMotion.angleStep(170, -170) == 20, "crossing the seam must be a small step");
                check(ArtMotion.scrubbed(1000, -3600, 60000) == 0 && ArtMotion.scrubbed(59000, 3600, 60000) == 60000, "scrub past the ends");
                check(HoldSeek.landing(5000, -10000, 60000) == 0 && HoldSeek.landing(58000, 10000, 60000) == 59000, "hold seek past the ends");
                check(SeekBubble.time(61000).equals("1:01") && SeekBubble.time(-5).equals("0:00"), "time text");
                return "";
            }
        });
        test("Every artwork shape stays in its view", new Test() {
            public String run() {
                inApp();
                Paint paint = new Paint(1);
                paint.setColor(0xffff0000);
                for (String shape : Settings.CHOICES[Settings.ART_SHAPE]) {
                    Path outline = new Path();
                    PlayerArt.trace(outline, shape, ART / 2f, ART / 2f, ART * .44f);
                    Bitmap pixels = Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888);
                    new Canvas(pixels).drawPath(outline, paint);
                    check(red(pixels, ART / 2, ART / 2), shape + " is hollow");
                    check(pixels.getPixel(1, ART / 2) == 0 && pixels.getPixel(ART / 2, 1) == 0, shape + " reaches the edge of the view");
                }
                return Settings.CHOICES[Settings.ART_SHAPE].length + " shapes";
            }
        });
        test("Backdrop draws in every style", new Test() {
            public String run() {
                inApp();
                String fx = Settings.choice(Settings.BACKDROP_FX);
                Canvas canvas = new Canvas(Bitmap.createBitmap(ART, ART, Bitmap.Config.ARGB_8888));
                Atmosphere backdrop = new Atmosphere();
                backdrop.capture(new ColorDrawable(0xff336699));
                backdrop.setBounds(0, 0, ART, ART);
                try {
                    for (String style : Settings.CHOICES[Settings.BACKDROP_FX]) {
                        Settings.setChoice(Settings.BACKDROP_FX, style);
                        backdrop.draw(canvas);
                    }
                } finally {
                    Settings.setChoice(Settings.BACKDROP_FX, fx);
                }
                return "";
            }
        });
        test("Settings search finds the new switches", new Test() {
            public String run() {
                check(SpatchSettings.matches(Settings.ART_SCRATCH, "scratch"), "by title");
                check(SpatchSettings.matches(Settings.HIDE_BARS, "slide away"), "by subtitle");
                check(!SpatchSettings.matches(Settings.ART_SCRATCH, "zzz"), "nonsense");
                return "";
            }
        });
        test("Presets only hold switches that exist", new Test() {
            public String run() {
                for (int setting : SpatchSettings.PRESET) check(setting >= 0 && setting < Settings.KEYS.length, "index " + setting);
                check(new HashSet<Integer>(boxed(SpatchSettings.PRESET)).size() == SpatchSettings.PRESET.length, "listed twice");
                return SpatchSettings.PRESET.length + " switches";
            }
        });
        test("An unknown widget still gets Glance", new Test() {
            public String run() {
                check(!SpatchWidget.skips(-12345), "a widget Spatch doesn't own must still be drawn");
                return "";
            }
        });
        speed("Speed: audio request stays off the main thread", 1, new Runnable() {
            public void run() {
                AudioSpectrum.request(context, AudioSpectrum.MINI, false);
            }
        });
        speed("Speed: list scan", 3, new Runnable() {
            public void run() {
                RowCards.scan(root);
            }
        });
        speed("Speed: backdrop frame", 16, new Runnable() {
            private final Atmosphere backdrop = new Atmosphere();
            private Canvas canvas;

            public void run() {
                if (canvas == null) {
                    int size = root.getWidth();
                    canvas = new Canvas(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888));
                    backdrop.capture(new ColorDrawable(0xff336699));
                    backdrop.setBounds(0, 0, size, size);
                }
                backdrop.draw(canvas);
            }
        });
        test("Headers soften bright gradients only", new Test() {
            public String run() {
                inApp();
                ArrayList<p.i7h> colors = new ArrayList<p.i7h>();
                colors.add(new p.i7h((long) 0xffe91e8c << 32));
                colors.add(new p.i7h((long) 0xff101010 << 32));
                boolean on = Settings.get(Settings.SOFT_HEADERS);
                try {
                    Settings.set(Settings.SOFT_HEADERS, true);
                    p.shc0 soft = (p.shc0) Surfaces.headerBrush(new p.shc0(0, 0, 0, colors, null));
                    long bright = ((p.i7h) soft.d.get(0)).a, dark = ((p.i7h) soft.d.get(1)).a;
                    check(Surfaces.chroma((int) (bright >>> 32)) < Surfaces.chroma(0xffe91e8c), "pink stayed loud");
                    check(dark == (long) 0xff101010 << 32, "a dark scrim changed");
                } finally {
                    Settings.set(Settings.SOFT_HEADERS, on);
                }
                return "";
            }
        });
        test("Tests left settings alone", new Test() {
            public String run() {
                for (int i = 0; i < switches.length; i++) check(Settings.get(i) == switches[i], Settings.KEYS[i]);
                for (int i = 0; i < choices.length; i++) check(Settings.choice(i).equals(choices[i]), Settings.CHOICE_KEYS[i]);
                return "";
            }
        });
        return results;
    }

    private static final int ART = 400, EDGE = 150, REDUCE_MOTION = 6, SPEED_RUNS = 15;

    /** Median of a few runs, so one garbage collection doesn't fail a budget; the time shows either way. */
    private void speed(String name, final float budgetMillis, final Runnable work) {
        test(name, new Test() {
            public String run() {
                inApp();
                long[] runs = new long[SPEED_RUNS];
                for (int i = 0; i < SPEED_RUNS; i++) {
                    long start = System.nanoTime();
                    work.run();
                    runs[i] = System.nanoTime() - start;
                }
                Arrays.sort(runs);
                float millis = runs[SPEED_RUNS / 2] / 1e6f;
                check(millis <= budgetMillis, millis + " ms, budget " + budgetMillis);
                return millis + " ms";
            }
        });
    }

    private long installed() throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
    }

    /** Layout faults a screenshot would show: cut-off text, tiny or unnamed buttons, faint text, overflow. */
    private void audit(View view, ArrayList<String> problems) {
        if (view.getVisibility() == View.GONE) return;
        float dp = context.getResources().getDisplayMetrics().density;
        String name = label(view);
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        if (parent != null && parent.getWidth() > 0 && !(parent instanceof HorizontalScrollView) && view.getRight() > parent.getWidth() + dp) {
            problems.add("past its parent: " + name);
        }
        if (view.isClickable()) {
            if (view.getHeight() < 40 * dp) problems.add("small button: " + name);
            if (name.isEmpty()) problems.add("unnamed button under " + label((View) view.getParent()));
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Layout layout = text.getLayout();
            if (layout != null && text.getLineCount() > 0 && layout.getEllipsisCount(text.getLineCount() - 1) > 0) problems.add("cut off: " + name);
            if (layout != null && layout.getHeight() > text.getHeight() + dp) problems.add("too tall: " + name);
            int surface = Palette.tone(Arrays.asList(Palette.NAMES).indexOf(Palette.activeName()), 1);
            if (name.length() > 0 && text.getBackground() == null && MiniEq.contrast(text.getCurrentTextColor() | 0xff000000, surface) < 3) problems.add("faint: " + name);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) audit(group.getChildAt(i), problems);
        }
    }

    private static List<Integer> boxed(int[] values) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (int value : values) out.add(Integer.valueOf(value));
        return out;
    }

    private static String label(View view) {
        if (view == null) return "";
        CharSequence said = view.getContentDescription();
        if (said != null && said.length() > 0) return said.toString();
        if (view instanceof TextView) return String.valueOf(((TextView) view).getText());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String inner = label(group.getChildAt(i));
                if (!inner.isEmpty()) return inner;
            }
        }
        return "";
    }

    /** A test that watches the running app for a while, on the main thread between frames. */
    private abstract class Timed {
        final String name;
        final long millis;

        Timed(String name, long millis) {
            this.name = name;
            this.millis = millis;
        }

        abstract void start() throws Exception;

        abstract String finish() throws Exception;
    }

    /** Measures the gaps between real frames. */
    static final class Frames implements Choreographer.FrameCallback {
        private long last;
        private boolean watching;
        float worst;
        int total, slow;

        void begin() {
            watching = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void end() {
            watching = false;
        }

        public void doFrame(long now) {
            if (!watching) return;
            if (last != 0) {
                float gap = (now - last) / 1e6f;
                worst = Math.max(worst, gap);
                total++;
                if (gap > 34) slow++;
            }
            last = now;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** The second stage: tests that need time to pass, one after another, then {@code done}. */
    static void timed(Context context, View root, List<Result> into, Runnable done) {
        if (root == null) {
            done.run();
            return;
        }
        DevTests tests = new DevTests(context, root);
        tests.chain(tests.timedTests(), 0, into, done);
    }

    private void chain(final List<Timed> tests, final int next, final List<Result> into, final Runnable done) {
        if (next == tests.size()) {
            done.run();
            return;
        }
        final Timed timed = tests.get(next);
        String failed = null;
        try {
            timed.start();
        } catch (Throwable t) {
            failed = t.toString();
        }
        final String startFailure = failed;
        root.postDelayed(new Runnable() {
            public void run() {
                if (startFailure != null) {
                    into.add(new Result(timed.name, FAIL, startFailure));
                } else {
                    try {
                        into.add(new Result(timed.name, PASS, timed.finish()));
                    } catch (Skip s) {
                        into.add(new Result(timed.name, SKIP, s.getMessage()));
                    } catch (Throwable t) {
                        into.add(new Result(timed.name, FAIL, t.toString()));
                    }
                }
                chain(tests, next + 1, into, done);
            }
        }, timed.millis);
    }

    private List<Timed> timedTests() {
        ArrayList<Timed> tests = new ArrayList<Timed>();
        tests.add(new Timed("Earlier bursts finish", 3500) {
            void start() {}

            String finish() {
                check(Confetti.live == 0, Confetti.live + " still playing");
                return "";
            }
        });
        tests.add(new Timed("Idle screen stops drawing", 1500) {
            private final int[] draws = {0};
            private final ViewTreeObserver.OnDrawListener counter = new ViewTreeObserver.OnDrawListener() {
                public void onDraw() {
                    draws[0]++;
                }
            };

            void start() {
                root.getViewTreeObserver().addOnDrawListener(counter);
            }

            String finish() {
                root.getViewTreeObserver().removeOnDrawListener(counter);
                check(draws[0] <= 30, draws[0] + " draws in 1.5s with nothing moving, which drains battery");
                return draws[0] + " draws";
            }
        });
        tests.add(new Timed("Title fade finishes", 700) {
            private TextView title;

            void start() {
                if (!Wave.animationsEnabled()) throw new Skip("reduce motion is on");
                title = new TextView(context);
                TextSlide.attach(title);
                title.setText("first");
                title.setText("second");
                PlayerArt.songAt = -100000;
            }

            String finish() {
                check(title.getAlpha() == 1 && title.getTranslationX() == 0, "the title stuck at opacity " + title.getAlpha());
                return "";
            }
        });
        tests.add(new Timed("Burst stays smooth", 1000) {
            private final Frames frames = new Frames();

            void start() {
                if (!Wave.animationsEnabled()) throw new Skip("reduce motion is on");
                Confetti.at(root, root.getWidth() / 2f, root.getHeight() / 2f);
                frames.begin();
            }

            String finish() {
                frames.end();
                String seen = frames.total + " frames, " + frames.slow + " slow, worst " + frames.worst + " ms";
                check(frames.total > 20 && frames.worst < 120 && frames.slow * 10 <= frames.total, seen);
                return seen;
            }
        });
        tests.add(new Timed("Bursts clean up after themselves", 3500) {
            void start() {
                if (!Wave.animationsEnabled()) throw new Skip("reduce motion is on");
                for (int i = 0; i < 10; i++) Confetti.at(root, root.getWidth() / 2f, root.getHeight() / 2f);
                check(Confetti.live <= 4, Confetti.live + " at once, over the cap");
            }

            String finish() {
                check(Confetti.live == 0, Confetti.live + " never finished");
                return "";
            }
        });
        return tests;
    }

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
        return red(pixels, ART / 2 + EDGE, ART / 2);
    }

    private static boolean red(Bitmap pixels, int x, int y) {
        int c = pixels.getPixel(x, y);
        return (c >> 16 & 255) > 200 && (c >> 8 & 255) < 80;
    }

    /** Runs a row through Spotify's real shape, padding and layer code; returns how many modifiers it added. */
    private static int modifiers(boolean library) {
        final int[] added = {0};
        if (library) Surfaces.libraryRow(row(added)); else Surfaces.trackRow(row(added));
        return added[0];
    }

    /** A Compose modifier that counts what joins it, so Spotify's real code builds on it. */
    private static rhm0 row(final int[] added) {
        return (rhm0) Proxy.newProxyInstance(rhm0.class.getClassLoader(), new Class<?>[] {rhm0.class}, new InvocationHandler() {
            public Object invoke(Object self, Method method, Object[] args) {
                if ("hashCode".equals(method.getName())) return Integer.valueOf(0);
                if ("equals".equals(method.getName())) return Boolean.valueOf(self == args[0]);
                if ("toString".equals(method.getName())) return "row";
                if (method.getReturnType() == boolean.class) return Boolean.FALSE;
                added[0]++;
                return self;
            }
        });
    }

    /** {@code type} is a field's "=", or parameter types; inherited members count. */
    private static boolean declares(Class<?> type, String name, String shape) {
        if (type == null) return false;
        if ("=".equals(shape)) {
            for (Field f : type.getDeclaredFields()) if (f.getName().equals(name)) return true;
        } else if ("<init>".equals(name)) {
            for (Constructor<?> c : type.getDeclaredConstructors()) if (shape(c.getParameterTypes()).equals(shape)) return true;
            return false;
        } else {
            for (Method m : type.getDeclaredMethods()) if (m.getName().equals(name) && shape(m.getParameterTypes()).equals(shape)) return true;
        }
        if (declares(type.getSuperclass(), name, shape)) return true;
        for (Class<?> parent : type.getInterfaces()) if (declares(parent, name, shape)) return true;
        return false;
    }

    private static String shape(Class<?>[] parameters) {
        StringBuilder out = new StringBuilder();
        for (Class<?> p : parameters) out.append(out.length() == 0 ? "" : ";").append(p.getSimpleName());
        return out.toString();
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
