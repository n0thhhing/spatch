package spatch;

import spatch.Controls.Switch;
import spatch.SpatchUi.Option;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The settings hub, its pages and the switch, choice and card rows they are built from. */
final class SettingsScreen {
    private final SpatchUi ui;
    SettingsScreen(SpatchUi ui) { this.ui = ui; }

    private static final String[] SETTINGS_GROUPS = {"Appearance", "Playback", "Equalizer & sound", "Navigation & gestures", "Downloads & storage", "Developer", "About"};

    private final java.util.Map<String, Integer> segPrev = new java.util.HashMap<>();

    private static final String[] ACCENT_NAMES = {"Default", "Lavender", "Rose", "Sky", "Mint", "Amber", "Coral"};

    // Fixed palettes from the earlier Spatch: bg, surf, surfHi, ink, accent2 (secondary text), inkDim, accent.
    static final String[] PALETTE_NAMES = {"Dynamic", "Lavender", "Gruvbox", "Catppuccin Mocha", "Catppuccin Macchiato", "Catppuccin Frappé",
        "Rosé Pine", "Rosé Pine Moon", "Nord", "Tokyo Night", "Tokyo Night Storm", "Dracula", "Gruvbox Material", "Everforest", "Kanagawa",
        "Solarized", "One", "Spotify", "Graphite"};

    private View settingsHub() {
        LinearLayout page = ui.column();
        ui.pageHeading(page, "Settings", false);
        LinearLayout categories = card(page, "Make it yours");
        String[] summaries = {"Colour, artwork, motion and celebrations", "Quality, crossfade and listening controls",
            "Headphone correction, tone and dynamics", "Navigation, scroll behaviour and gestures",
            "Downloads, artwork cache and storage", "Diagnostics and connection details", "Version and app information"};
        for (int i = 0; i < SETTINGS_GROUPS.length; i++) {
            final int section = i;
            settingsAction(categories, SETTINGS_GROUPS[i], summaries[i], () -> { ui.settingsSection = section; ui.sound.panel = -1; ui.showPage(); ui.enter(ui.dp(28), 0); });
        }
        return ui.scrolling(page);
    }

    View settingsPage() {
        if (ui.settingsSection < 0 || ui.settingsSection >= SETTINGS_GROUPS.length) return settingsHub();
        LinearLayout page = ui.column();
        LinearLayout up = ui.row();
        up.setPadding(ui.dp(2), ui.dp(6), 0, 0);
        up.addView(ui.icon("back", () -> ui.accent), ui.lp(ui.dp(20), ui.dp(20)));
        TextView parent = ui.text(ui.settingsSection == 2 && ui.sound.panel >= 6 ? "Headphone correction" : ui.settingsSection == 2 && ui.sound.panel >= 0 ? "Equalizer & sound" : "Settings", 14, () -> ui.accent, true);
        parent.setPadding(ui.dp(8), 0, 0, 0);
        up.addView(parent);
        ui.tappable(up, "Back to settings", () -> {
            if (ui.settingsSection == 2 && ui.sound.panel >= 0) ui.sound.panel = ui.sound.panel >= 6 ? 1 : -1; else ui.settingsSection = -1;
            ui.showPage();
        });
        page.addView(up, ui.fill(ui.dp(34)));
        ui.pageHeading(page, ui.settingsSection == 2 && ui.sound.panel >= 0 ? SoundSettings.SOUND_GROUPS[ui.sound.panel] : SETTINGS_GROUPS[ui.settingsSection], false).setTextSize(28);
        switch (ui.settingsSection) {
        case 0: {
            LinearLayout look = card(page, "Look");
            paletteRow(look);
            if (ui.fixedPalette() == null) {
                choice(look, "Theme", new String[]{"System", "Dark", "Light"}, "theme", 1, true);
                toggle(look, "Artwork colours", "Tint the whole app with the song’s cover", "art_colors", true, true);
                accentRow(look);
            }
            toggle(look, "Expressive type", "Google Sans Flex on Pixel; off uses Roboto", "expressive_type", true, true);
            toggle(look, "Material icons", "Use Material Symbols Rounded icons instead of Spatch’s geometric icons", "material_icons", false, true);

            LinearLayout visuals = card(page, "Artwork & visualizer");
            choice(visuals, "Visualizer", new String[]{"Bars", "Ring", "Off", "Contour"}, "viz", 0, false);
            toggle(visuals, "Follow the music", "The bars react to Spotify’s playback; if it can’t be read they pulse on their own. Android lists this as microphone access", "audio", true, false);
            toggle(visuals, "Read audio directly", "Experimental: take Spotify’s audio before the volume, so the bars keep moving at volume 0 and no microphone permission is needed", "audio_direct", false, false);
            TextView audioStatus = ui.text("Status: " + Audio.status, 12, () -> ui.inkDim, false);
            audioStatus.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(10));
            visuals.addView(audioStatus);
            toggle(visuals, "Spinning artwork", "The cover turns slowly while music plays", "spin", true, false);
            toggle(visuals, "Artwork pulse", "Let the cover gently breathe with the bass", "cover_pulse", true, false);
            toggle(visuals, "Cover glow", "A soft pool of the palette's colours behind the cover", "cover_glow", true, false);
            toggle(visuals, "Blurred backdrop", "The cover, blurred and drifting, behind the player", "backdrop", true, false);
            if (ui.pref("backdrop", true)) choice(visuals, "Backdrop blur", GpuFx.shaders(ui.prefs) ? new String[]{"Soft", "Deep", "Streaks", "Flowing"} : new String[]{"Soft", "Deep", "Streaks"}, "backdrop_blur", 0, false);
            if (android.os.Build.VERSION.SDK_INT >= 31) toggle(visuals, "GPU effects", "Drawn by the graphics chip: a smoother blurred backdrop, glowing visualizer bars, richer playlist colours and lyrics that focus on the sung line. Off uses the plain look", "gpu_fx", true, false);
            if (android.os.Build.VERSION.SDK_INT >= 31 && ui.pref("gpu_fx", true)) toggle(visuals, "Frosted bars", "The mini player and tabs as frosted glass over the page. Uses noticeably more battery while scrolling", "frost", false, false);
            toggle(visuals, "Floating motes", "Particles coast to a quiet stop on pause and drift again on play", "motes", true, false);
            if (ui.pref("motes", true)) {
                choice(visuals, "Mote amount", new String[]{"Few", "Normal", "Many"}, "motes_amount", 1, false);
                choice(visuals, "Mote shapes", new String[]{"Dots", "Stars", "Hearts", "Mix"}, "motes_shape", 0, false);
            }
            toggle(visuals, "Rotating stickers", "The shapes behind playlist headers turn slowly. Keeps the page redrawing, so it uses some battery", "sticker_spin", false, false);

            LinearLayout playerLayout = card(page, "Player layout & buttons");
            toggle(playerLayout, "Allow rotation", "Rotate into landscape with lyrics and Up next beside the cover", "rotate", false, false);
            toggle(playerLayout, "Hide controls when idle", "While music plays, chosen player elements fade after five seconds; touch to bring them back", "idle_hide", false, false);
            if (ui.pref("idle_hide", false)) {
                multiChips(playerLayout, "Fade when idle", new String[][]{
                    {"Top bar", "idle_hide_top", "1"},
                    {"Title & artist", "idle_hide_title", "0"},
                    {"Seek bar", "idle_hide_wave", "0"},
                    {"Controls", "idle_hide_controls", "1"},
                    {"Up next", "idle_hide_next", "1"}
                });
            }
            toggle(playerLayout, "Quality line", "What Spotify says is playing, under the artist", "player_quality", true, false);
            toggle(playerLayout, "Heart button", "Save to Liked Songs from the player", "player_heart", true, false);
            toggle(playerLayout, "Shuffle and repeat", "The buttons on either side of play", "player_modes", true, false);
            toggle(playerLayout, "Next-up pill", "What plays next, under the controls", "player_next", true, false);
            toggle(playerLayout, "Lyrics button", "In the player's top bar", "bar_lyrics", true, false);
            toggle(playerLayout, "Queue button", "In the player's top bar", "bar_queue", true, false);
            toggle(playerLayout, "Sleep timer button", "In the top bar instead of the more menu", "bar_timer", false, false);
            toggle(playerLayout, "Devices button", "In the top bar instead of the more menu", "bar_devices", false, false);

            LinearLayout lyrics = card(page, "Lyrics");
            toggle(lyrics, "Lyrics in the player", "Three lines under the cover, tucked away when a song has none", "player_lyrics", false, false);
            if (ui.pref("player_lyrics", false)) choice(lyrics, "Lyric lines", new String[]{"One", "Two", "Three"}, "player_lyric_lines", 1, false);
            toggle(lyrics, "More lyrics from LRCLIB", "Look songs up on lrclib.net, a free lyrics site, when Spotify has no lyrics or only times whole lines (for word-by-word highlighting)", "lrclib_lyrics", true, false);
            toggle(lyrics, "Generate missing lyrics", "Experimental: when no service has a song's lyrics, listen to it on the phone and write rough timed lyrics, saved for next time. Downloads a 60 MB speech model once; uses extra battery while it listens", "lyric_gen", false, false);
            TextView genStatus = ui.text("", 12, () -> ui.inkDim, false);
            genStatus.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(10));
            lyrics.addView(genStatus);
            Runnable showGen = new Runnable() {
                @Override public void run() {
                    String now = ui.pref("lyric_gen", false) ? (LyricGen.enabled() ? "Model ready" : LyricGen.status) : "";
                    genStatus.setText(now);
                    genStatus.setVisibility(now.isEmpty() ? View.GONE : View.VISIBLE);
                    if (ui.pref("lyric_gen", false) && !LyricGen.enabled() && genStatus.isAttachedToWindow()) genStatus.postDelayed(this, 500); // follow the download
                }
            };
            genStatus.post(showGen);
            genStatus.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { v.post(showGen); }
                @Override public void onViewDetachedFromWindow(View v) {}
            });


            LinearLayout celebrations = card(page, "Motion & celebrations");
            toggle(celebrations, "Reduce motion", "A calmer app, without animated transitions or celebrations", "reduce_motion", false, true);
            toggle(celebrations, "Playback confetti", "A burst of shapes when you tap play or pause", "confetti", true, false);
            choice(celebrations, "Celebrate", new String[]{"Play & pause", "Play only"}, "confetti_when", 0, false);
            choice(celebrations, "Amount", new String[]{"Subtle", "Normal", "Festive"}, "confetti_amount", 1, false);
            choice(celebrations, "Shapes", new String[]{"Mix", "Cookies", "Hearts", "Stars", "Dots"}, "confetti_shape", 0, false);
            choice(celebrations, "Size", new String[]{"Small", "Normal", "Large"}, "confetti_size", 1, false);
            choice(celebrations, "Colours", new String[]{"Artwork", "Rainbow", "Solid"}, "confetti_tones", 0, false);

            LinearLayout home = card(page, "Home");
            toggle(home, "Recently played", "Spotify’s own listening history", "home_recent", true, false);
            toggle(home, "Hero visualizer", "A soft wave of the music along the foot of the big now-playing card; it flattens and stops when paused", "hero_wave", false, false);
            for (int i = 0; i < SpatchUi.SECTIONS.length; i++) toggle(home, SpatchUi.SECTIONS[i], "Show the " + SpatchUi.SECTIONS[i].toLowerCase(java.util.Locale.ROOT) + " shelf", "home_shelf_" + i, true, false);

            LinearLayout library = card(page, "Library");
            toggle(library, "Sort A to Z", "Alphabetical, with Liked Songs first; off shows Spotify’s recent order", "library_az", false, false);
            toggle(library, "Show as a grid", "Big covers two to a row instead of a list", "library_grid", false, false);
            break;
        }
        case 1: {
            if (!OfficialPlayback.enabled()) {
                LinearLayout spotify = card(page, "Spotify");
                spotifyCard(spotify);
            }

            LinearLayout backend = card(page, "Playback backend");
            settingsAction(backend, "Playback backend", OfficialPlayback.enabled() ? "Official Spotify fallback" : "Built-in player", () -> {
                ui.sheet("Playback backend", "Official Spotify plays audio while Spatch shows your library and controls. Keep it installed and signed in; normal ads and free-account restrictions apply. Some selections may open Spotify.",
                    new String[]{"Built-in player", "Official Spotify fallback"}, selected -> {
                        boolean official = selected == 1;
                        if (official && !OfficialPlayback.installed()) {
                            ui.toast(OfficialPlayback.status(), true);
                            return;
                        }
                        if (official) SpatchUi.COMMANDS.execute(() -> SpatchUi.nativeCommand(PlayerScreen.PAUSE, 0, ""));
                        OfficialPlayback.setEnabled(official);
                        ui.revision = -1;
                        ui.syncNativeState();
                        settingsChanged(false);
                        if (official && !OfficialPlayback.allowed()) OfficialPlayback.configure(ui.activity);
                    });
            });
            if (OfficialPlayback.enabled()) {
                settingsAction(backend, "Notification access", OfficialPlayback.status(), () -> OfficialPlayback.configure(ui.activity));
                settingsAction(backend, "Open official Spotify", "Sign in or start a session, then return to Spatch", () -> {
                    android.content.Intent launch = ui.activity.getPackageManager().getLaunchIntentForPackage(OfficialPlayback.PACKAGE);
                    if (launch != null) ui.activity.startActivity(launch);
                    else ui.toast(OfficialPlayback.status(), true);
                });
            }

            LinearLayout playback = card(page, "Player");
            toggle(playback, "Keep screen on", "While the player is open", "keep_on", true, false);
            break;
        }
        case 2: {
            if (OfficialPlayback.enabled()) {
                LinearLayout note = card(page, "Official Spotify audio");
                settingsAction(note, "Audio settings", "Manage sound in official Spotify while using the fallback", () -> {
                    android.content.Intent launch = ui.activity.getPackageManager().getLaunchIntentForPackage(OfficialPlayback.PACKAGE);
                    if (launch != null) ui.activity.startActivity(launch);
                });
            } else ui.sound.render(page);
            break;
        }
        case 3: {
            LinearLayout navigation = card(page, "Navigation");
            toggle(navigation, "Slide to switch", "Press and drag across the navigation bar, then release on a destination", "nav_drag", true, false);
            toggle(navigation, "Hide controls while scrolling", "Hide the navigation bar and mini player on the way down; show them when you scroll up", "auto_hide_dock", false, false);

            LinearLayout gestures = card(page, "Gestures and controls");
            toggle(gestures, "Double-tap to play or pause", "On the artwork in the player", "double_tap", true, false);
            toggle(gestures, "Swipe artwork to skip", "Flick the artwork left for next, right for previous", "swipe_skip", true, false);
            choice(gestures, "Hold-to-seek step", new String[]{"5 s", "10 s", "30 s"}, "seek_step", 1, false);
            toggle(gestures, "Haptics", "Little taps on buttons", "haptics", true, false);
            break;
        }
        case 4: {
            LinearLayout downloadsBox = card(page, "Downloads");
            ui.downloadsScreen.downloadsCard(downloadsBox);

            LinearLayout storage = card(page, "Storage");
            cacheRow(storage);
            break;
        }
        case 5: {
            LinearLayout developer = card(page, "Developer");
            toggle(developer, "Skip integrity attestation", "Experiment only. Restart Spotify after changing; verification may run only once a day.",
                "integrity_skip", false, false);
            choice(developer, "Integrity experiment", new String[]{"Skip call", "Empty token"}, "integrity_variant", 0, false);
            toggle(developer, "Trace signature reads", "Log certificate digests and matching request bodies. Restart to trace startup reads.",
                "integrity_trace", false, false);
            settingsAction(developer, "Playback diagnostics", "Connection, playback and audio effects", () -> {
                String report = "Build " + SpatchUi.nativeBuild() + "\nAndroid " + android.os.Build.VERSION.SDK_INT
                    + "\nPlaying " + ui.playing + " · buffering " + ui.buffering
                    + "\nPosition " + ui.position + " ms · duration " + ui.duration + " ms"
                    + "\nLibrary loaded " + ui.libraryReady + " · upcoming " + ui.upcoming.size()
                    + "\nEqualizer " + Audio.eqStatus + "\nEQ detail " + Audio.eqError + "\nVisualizer " + Audio.status
                    + "\nSearch " + (ui.searchScreen.catalogError.isEmpty() ? "No error" : ui.searchScreen.catalogError)
                    + "\n" + CoreBridge.diagnostics();
                ui.sheet("Diagnostics", report, new String[]{"Copy diagnostics", "Close"}, choice -> {
                    if (choice == 0) {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ui.activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spatch diagnostics", report));
                    }
                });
            });
            settingsAction(developer, "Automation & intents", "View and copy broadcast intents for Tasker, MacroDroid and shortcuts", () -> {
                String pkg = ui.activity.getPackageName();
                String component = pkg + "/spatch.SpatchWidget";
                String reference = "Package: " + pkg
                    + "\nReceiver: " + component
                    + "\nTarget: Broadcast Receiver"
                    + "\n\nActions:"
                    + "\n• spatch.action.PLAY — resume playback (wakes in background if closed)"
                    + "\n• spatch.action.PAUSE — pause playback"
                    + "\n• spatch.action.TOGGLE — play / pause"
                    + "\n• spatch.action.NEXT — next track"
                    + "\n• spatch.action.PREVIOUS — previous track"
                    + "\n• spatch.action.SEEK — extra position_ms (long/int)"
                    + "\n• spatch.action.PLAY_URI — extra uri (String), optional context (String), shuffle (boolean), position_ms (long)"
                    + "\n• spatch.action.SHUFFLE — extra enabled (boolean) or mode (0/1)"
                    + "\n• spatch.action.REPEAT — extra mode (0 off, 1 all, 2 one)"
                    + "\n• spatch.action.QUEUE — extra uri (String)";
                ui.sheet("Automation & intents", reference, new String[]{"Copy all", "Copy receiver", "Close"}, choice -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ui.activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard == null) return;
                    if (choice == 0) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spatch intents", reference));
                        ui.toast("Intents copied");
                    } else if (choice == 1) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spatch receiver", component));
                        ui.toast("Receiver copied: " + component);
                    }
                });
            });
            settingsAction(developer, "Refresh library", "Reload your saved music from Spotify", () -> {
                ui.submit(SpatchUi.DATA, () -> {
                    SpatchUi.nativeRefreshLibrary();
                    ui.post(() -> ui.toast("Library refresh finished"));
                });
            });
            break;
        }
        case 6: {
            AboutScreen.render(ui, page, SpatchUi.nativeBuild());
            break;
        }
        }
        return ui.scrolling(page);
    }

    java.util.Map<String, Integer> spotifySettings;
    private String spotifyError = "";
    private boolean spotifyLoading;

    int spotifyValue(String key) { Integer v = spotifySettings == null ? null : spotifySettings.get(key); return v == null ? 0 : v; }

    /** Loads Spotify's own settings once; every card that shows one calls this and is redrawn when they arrive. */
    void ensureSpotifySettings() {
        if (spotifySettings != null || spotifyLoading || !spotifyError.isEmpty()) return;
        spotifyLoading = true;
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            String[] f = SpatchUi.nativeSpotifySettings();
            ui.post(() -> {
                spotifyLoading = false;
                if (f == null || f.length < 2 || f[0].equals("!")) spotifyError = f == null || f.length < 2 ? "no reply" : f[1];
                else {
                    java.util.Map<String, Integer> read = new java.util.HashMap<>();
                    for (int i = 0; i + 1 < f.length; i += 2) read.put(f[i], Integer.parseInt(f[i + 1]));
                    spotifySettings = read;
                }
                if (ui.tab == SpatchUi.SETTINGS && ui.playlist == null) settingsChanged(false);
            });
        });
    }

    private void spotifyCard(LinearLayout box) {
        if (spotifySettings == null) {
            TextView note = ui.text(spotifyError.isEmpty() ? "Loading Spotify’s settings…" : "Couldn’t read Spotify’s settings: " + spotifyError, 14, () -> ui.inkDim, false);
            note.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
            box.addView(note);
            if (!spotifyError.isEmpty()) {
                TextView retry = ui.pill("Retry", () -> { spotifyError = ""; settingsChanged(false); });
                retry.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10));
                LinearLayout.LayoutParams rp = ui.lp(-2, -2);
                rp.setMargins(ui.dp(18), 0, 0, ui.dp(12));
                box.addView(retry, rp);
            } else ensureSpotifySettings();
            return;
        }
        // Spotify's scale: 0 Auto, 1 Low, 2 Normal, 3 High, 4 Very high, 5 Lossless (Premium where offered).
        String[] qualities = {"Auto", "Low", "Normal", "High", "Very high", "Lossless"};
        int streaming = spotifyValue("stream_quality");
        segmented(box, "Streaming quality", qualities, streaming >= 0 && streaming <= 5 ? streaming : 0, i -> setSpotify("stream_quality", i));
        spotifySwitch(box, "Allow quality downgrade", "Lower the quality when your connection is slow", "allow_audio_quality_downgrade");
        spotifySwitch(box, "Download over cellular", "Let downloads use mobile data", "download_over_3g");
        spotifySwitch(box, "Normalise volume", "Play songs at the same level", "normalize");
        spotifySwitch(box, "Gapless playback", "No pause between songs", "gapless");
        spotifySwitch(box, "Automix", "Blend songs together in playlists", "automix");
        int fade = spotifyValue("crossfade") == 0 ? 0 : spotifyValue("crossfade_time_seconds");
        int[] seconds = {0, 3, 6, 12};
        // Spotify's crossfade is any number of seconds; light the nearest of the offered lengths.
        int selected = 0;
        for (int i = 0; i < seconds.length; i++) if (Math.abs(seconds[i] - fade) < Math.abs(seconds[selected] - fade)) selected = i;
        if (fade > 0 && selected == 0) selected = 1;
        segmented(box, "Crossfade", new String[]{"Off", "3 s", "6 s", "12 s"}, selected, i -> {
            if (seconds[i] == 0) setSpotify("crossfade", 0, false);
            else { setSpotify("crossfade_time_seconds", seconds[i], false); setSpotify("crossfade", 1, false); }
            settingsChanged(false);
        });
        spotifySwitch(box, "Play explicit content", "Off hides songs marked explicit", "play_explicit_content");
        spotifySwitch(box, "Private session", "Listen without it showing in your activity", "private_session");
        spotifySwitch(box, "Show unavailable songs", "Keep songs that can’t be played in lists", "show_unavailable_tracks");
        TextView note = ui.text("These are Spotify’s own settings: changing them here changes them in Spotify too.", 12, () -> ui.inkDim, false);
        note.setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(10));
        box.addView(note);
    }

    /** Spotify's offline mode: it stops using the internet and plays only downloads, so turning it on asks first. */
    void offlineModeRow(LinearLayout box) {
        boolean on = spotifyValue("offline_mode") != 0;
        LinearLayout line = ui.row();
        line.setPadding(ui.dp(18), ui.dp(12), ui.dp(16), ui.dp(12));
        LinearLayout words = ui.column();
        words.addView(ui.text("Offline mode", 16, () -> ui.ink, true));
        TextView d = ui.text("Play only what’s downloaded, without using the internet", 13, () -> ui.inkDim, false);
        d.setPadding(0, ui.dp(2), ui.dp(8), 0);
        words.addView(d);
        line.addView(words, ui.weight());
        Switch sw = ui.controls.new Switch(on);
        line.addView(sw, ui.lp(ui.dp(52), ui.dp(32)));
        if (android.os.Build.VERSION.SDK_INT >= 30) line.setStateDescription(on ? "On" : "Off");
        ui.tappable(line, "Offline mode", () -> {
            if (on) { setSpotify("offline_mode", 0); return; }
            ui.sheet("Turn on offline mode?", "Spotify stops using the internet and plays only what you’ve downloaded. Browsing and search won’t load anything new until you turn it off here.",
                new Option[]{new Option("Turn on offline mode", "", "", "download", false)}, c -> setSpotify("offline_mode", 1));
        });
        box.addView(line, ui.fill(-2));
    }

    private void spotifySwitch(LinearLayout box, String name, String detail, String key) {
        LinearLayout line = ui.row();
        line.setPadding(ui.dp(18), ui.dp(12), ui.dp(16), ui.dp(12));
        LinearLayout words = ui.column();
        words.addView(ui.text(name, 16, () -> ui.ink, true));
        TextView d = ui.text(detail, 13, () -> ui.inkDim, false);
        d.setPadding(0, ui.dp(2), ui.dp(8), 0);
        words.addView(d);
        line.addView(words, ui.weight());
        Switch sw = ui.controls.new Switch(spotifyValue(key) != 0);
        line.addView(sw, ui.lp(ui.dp(52), ui.dp(32)));
        if (android.os.Build.VERSION.SDK_INT >= 30) line.setStateDescription(sw.on ? "On" : "Off");
        ui.tappable(line, name, () -> { sw.set(!sw.on); setSpotify(key, sw.on ? 1 : 0, false); });
        box.addView(line, ui.fill(-2));
    }

    /** Shows the new value at once, asks Spotify to change it, and puts the old one back if it refuses. */
    void setSpotify(String key, int value) { setSpotify(key, value, true); }

    void setSpotify(String key, int value, boolean redraw) {
        if (spotifySettings == null) return;
        int before = spotifyValue(key);
        spotifySettings.put(key, value);
        if (redraw) settingsChanged(false);
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            String error = SpatchUi.nativeSetSpotifySetting(key, value);
            if (error != null && !error.isEmpty()) ui.post(() -> {
                spotifySettings.put(key, before);
                ui.toast("Spotify didn’t change that: " + error, true);
                if (ui.tab == SpatchUi.SETTINGS && ui.playlist == null) settingsChanged(false);
            });
        });
    }

    /** Redraws Settings in place after a change, keeping the scroll position. */
    /**
     * A new palette spreads from what you tapped: the screen as it was (copied from the window, GPU effects and all)
     * is laid over the app, the new colours land at once underneath, and a circle growing out of the tap cuts through
     * the old picture until it's gone. `from` is the tapped view, or null for wherever the finger went down.
     */
    void revealPalette(View from, Runnable change) {
        android.view.Window window = ui.activity.getWindow();
        View decor = window.getDecorView();
        if (!ui.motion() || decor.getWidth() == 0) { change.run(); return; }
        float cx = ui.touchX, cy = ui.touchY;
        if (from != null) {
            int[] at = new int[2];
            from.getLocationInWindow(at);
            cx = at[0] + from.getWidth() / 2f;
            cy = at[1] + from.getHeight() / 2f;
        }
        float x = cx, y = cy;
        android.graphics.Bitmap before = android.graphics.Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.view.PixelCopy.request(window, before, result -> {
            if (result != android.view.PixelCopy.SUCCESS) { before.recycle(); change.run(); return; }
            float reach = (float) Math.hypot(Math.max(x, decor.getWidth() - x), Math.max(y, decor.getHeight() - y));
            float[] radius = {0f};
            android.graphics.Path hole = new android.graphics.Path();
            android.graphics.drawable.Drawable old = new android.graphics.drawable.Drawable() {
                @Override public void draw(android.graphics.Canvas c) {
                    hole.rewind();
                    hole.addCircle(x, y, radius[0], android.graphics.Path.Direction.CW);
                    c.save();
                    c.clipOutPath(hole);
                    c.drawBitmap(before, 0, 0, null);
                    c.restore();
                }
                @Override public void setAlpha(int alpha) {}
                @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
                @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            };
            old.setBounds(0, 0, decor.getWidth(), decor.getHeight());
            decor.getOverlay().add(old);
            ui.snapPalette = true;
            change.run();
            ui.snapPalette = false; // in case the change didn't touch the palette after all
            android.animation.ValueAnimator grow = android.animation.ValueAnimator.ofFloat(0f, reach);
            grow.setDuration(620);
            grow.setInterpolator(new android.view.animation.PathInterpolator(.4f, 0f, .2f, 1f));
            grow.addUpdateListener(a -> { radius[0] = (float) a.getAnimatedValue(); old.invalidateSelf(); });
            grow.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { decor.getOverlay().remove(old); before.recycle(); }
            });
            grow.start();
        }, new android.os.Handler(android.os.Looper.getMainLooper()));
    }

    void settingsChanged(boolean palette) {
        int y = ui.pageScroll != null ? ui.pageScroll.getScrollY() : 0;
        ui.showPage();
        SpatchUi.keepScroll(ui.pageScroll, y);
        if (palette) { ui.paletteKey = "?"; ui.refreshPalette(); }
    }

    /** A title over a connected button group; the pick is stored as an index under `key`. */
    void choice(LinearLayout box, String name, String[] names, String key, int fallback, boolean palette) {
        segmented(box, name, names, ui.prefs.getInt(key, fallback), pick -> {
            ui.prefs.edit().putInt(key, pick).apply();
            if (key.startsWith("eq_")) Audio.refreshEqualizer();
            if (palette) revealPalette(null, () -> settingsChanged(true)); // theme: from where the finger went down
            else settingsChanged(false);
        });
    }

    interface Pick { void picked(int index); }

    /** A title over a connected sliding pill group with `selected` highlighted (-1 for none). */
    void segmented(LinearLayout box, String name, String[] names, int selected, Pick onPick) {
        TextView label = ui.text(name, 16, () -> ui.ink, true);
        label.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(8));
        box.addView(label);
        final int current = selected, n = names.length;
        View pill = new View(ui.activity);
        ui.bgRound(pill, 19, () -> ui.accent);
        pill.setVisibility(current >= 0 && current < n ? View.VISIBLE : View.GONE);
        FrameLayout track = new FrameLayout(ui.activity) {
            private boolean placed;
            private int lastW;
            private float slotStep(int innerW, int slotW) { return n > 1 ? (innerW - slotW) / (float) (n - 1) : 0f; }
            @Override protected void onMeasure(int wSpec, int hSpec) {
                int innerW = Math.max(0, MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight());
                int slotW = n > 0 ? innerW / n : 0;
                if (pill.getLayoutParams().width != slotW) pill.getLayoutParams().width = slotW;
                super.onMeasure(wSpec, hSpec);
            }
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                int innerW = Math.max(0, (r - l) - getPaddingLeft() - getPaddingRight());
                int slotW = n > 0 ? innerW / n : 0;
                if (slotW <= 0 || current < 0 || current >= n) return;
                int width = getWidth();
                if (!placed || lastW != width) {
                    placed = true;
                    lastW = width;
                    float step = slotStep(innerW, slotW), targetX = current * step;
                    Integer prev = segPrev.remove(name);
                    if (prev != null && prev != current && prev >= 0 && prev < n && ui.motion()) {
                        pill.setTranslationX(prev * step);
                        pill.animate().cancel();
                        pill.animate().translationX(targetX).setDuration(240)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(.85f)).start();
                    } else {
                        pill.setTranslationX(targetX);
                    }
                }
            }
        };
        ui.bgRound(track, 22, () -> ui.surfHi);
        track.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        track.setClipChildren(false);
        track.setClipToPadding(false);
        track.addView(pill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout group = ui.row();
        for (int i = 0; i < n; i++) {
            final int pick = i;
            boolean on = i == current;
            TextView option = ui.text(names[i], n >= 6 ? 11 : n > 4 ? 12 : 13, on ? ui.onAccent : ui.ink, true);
            option.setGravity(Gravity.CENTER);
            option.setSingleLine(true);
            option.setBackground(null);
            ui.colored(option, () -> option.setTextColor(on ? ui.onAccent : ui.ink));
            ui.tappable(option, name + ": " + names[i], () -> { segPrev.put(name, current); onPick.picked(pick); });
            group.addView(option, new LinearLayout.LayoutParams(0, -1, 1));
        }
        track.addView(group, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tp = ui.fill(ui.dp(46));
        tp.setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(12));
        box.addView(track, tp);
    }

    /** Multi-select filter chips in a wrapping row; each item is {label, prefKey, "1"|"0" default}. */
    private void multiChips(LinearLayout box, String name, String[][] items) {
        TextView label = ui.text(name, 14, () -> ui.inkDim, true);
        label.setPadding(ui.dp(18), ui.dp(2), ui.dp(18), ui.dp(8));
        box.addView(label);
        final int gap = ui.dp(8);
        android.view.ViewGroup wrap = new android.view.ViewGroup(ui.activity) {
            @Override protected void onMeasure(int wSpec, int hSpec) {
                int max = MeasureSpec.getSize(wSpec), padL = getPaddingLeft(), padR = getPaddingRight();
                int avail = Math.max(1, max - padL - padR), x = 0, y = getPaddingTop(), rowH = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    child.measure(MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
                    if (x > 0 && x + cw > avail) { y += rowH + gap; x = 0; rowH = 0; }
                    x += cw + gap;
                    rowH = Math.max(rowH, ch);
                }
                setMeasuredDimension(max, y + rowH + getPaddingBottom());
            }
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int avail = Math.max(1, r - l - getPaddingLeft() - getPaddingRight());
                int x = getPaddingLeft(), y = getPaddingTop(), rowH = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    int cw = child.getMeasuredWidth(), ch = child.getMeasuredHeight();
                    if (x > getPaddingLeft() && (x - getPaddingLeft()) + cw > avail) { y += rowH + gap; x = getPaddingLeft(); rowH = 0; }
                    child.layout(x, y, x + cw, y + ch);
                    x += cw + gap;
                    rowH = Math.max(rowH, ch);
                }
            }
        };
        wrap.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(12));
        for (String[] item : items) {
            String title = item[0], key = item[1];
            boolean on = ui.pref(key, "1".equals(item[2]));
            LinearLayout chip = ui.row();
            chip.setPadding(ui.dp(12), ui.dp(8), ui.dp(14), ui.dp(8));
            ui.bgRound(chip, 18, () -> on ? ui.accent : ui.surfHi);
            if (on) {
                View check = ui.icon("check", () -> ui.onAccent);
                LinearLayout.LayoutParams cp = ui.lp(ui.dp(14), ui.dp(14));
                cp.rightMargin = ui.dp(6);
                chip.addView(check, cp);
            }
            TextView tv = ui.text(title, 13, () -> on ? ui.onAccent : ui.ink, true);
            chip.addView(tv);
            ui.tappable(chip, name + ": " + title + (on ? ", on" : ", off"), () -> {
                ui.prefs.edit().putBoolean(key, !on).apply();
                settingsChanged(false);
            });
            wrap.addView(chip);
        }
        box.addView(wrap, ui.fill(-2));
    }

    /** A strip of miniature app-window palette cards: Dynamic follows the cover and accent, the rest are fixed palettes. */
    private void paletteRow(LinearLayout box) {
        TextView label = ui.text("Palette", 16, () -> ui.ink, true);
        label.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(8));
        box.addView(label);
        HorizontalScrollView strip = new HorizontalScrollView(ui.activity);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setClipToPadding(false);
        strip.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(12));
        LinearLayout cards = ui.row();
        int chosen = Math.max(0, Math.min(SpatchUi.PALETTES.length - 1, ui.prefs.getInt("palette", 0)));
        for (int i = 0; i < SpatchUi.PALETTES.length; i++) {
            final int pick = i;
            int[] p = SpatchUi.PALETTES[i];
            View card = new View(ui.activity) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final Path glyphPath = new Path();
                private final RectF box = new RectF();
                @Override protected void onDraw(Canvas c) {
                    float w = getWidth(), h = getHeight(), r = ui.dp(16);
                    int back = p == null ? SpatchUi.blend(ui.bg, ui.accent, .18f) : p[0] | 0xff000000;
                    int cardSurf = p == null ? ui.surf : p[1] | 0xff000000;
                    int cardHi = p == null ? ui.surfHi : p[2] | 0xff000000;
                    int words = p == null ? ui.ink : p[3] | 0xff000000;
                    int second = p == null ? ui.accent2 : p[4] | 0xff000000;
                    int dim = p == null ? ui.inkDim : p[5] | 0xff000000;
                    int fore = p == null ? ui.accent : p[6] | 0xff000000;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(back);
                    box.set(0, 0, w, h);
                    c.drawRoundRect(box, r, r, paint);
                    // Miniature hero card preview: cover swatch, two text lines and a play badge.
                    paint.setColor(cardSurf);
                    box.set(ui.dp(10), ui.dp(10), w - ui.dp(10), ui.dp(50));
                    c.drawRoundRect(box, ui.dp(10), ui.dp(10), paint);
                    paint.setColor(fore);
                    box.set(ui.dp(15), ui.dp(15), ui.dp(43), ui.dp(43));
                    c.drawRoundRect(box, ui.dp(6), ui.dp(6), paint);
                    paint.setColor(second);
                    c.drawCircle(ui.dp(34), ui.dp(24), ui.dp(4), paint);
                    float textL = ui.dp(49), textR = w - ui.dp(35);
                    if (textR > textL + ui.dp(12)) {
                        paint.setColor(words);
                        box.set(textL, ui.dp(19), textR, ui.dp(24));
                        c.drawRoundRect(box, ui.dp(2.5f), ui.dp(2.5f), paint);
                        paint.setColor(dim);
                        box.set(textL, ui.dp(28), textL + (textR - textL) * .68f, ui.dp(32));
                        c.drawRoundRect(box, ui.dp(2), ui.dp(2), paint);
                    }
                    paint.setColor(fore);
                    c.drawCircle(w - ui.dp(21), ui.dp(37), ui.dp(6.5f), paint);
                    // Miniature dock bar preview with active pill indicator.
                    paint.setColor(cardHi);
                    box.set(ui.dp(10), ui.dp(55), w - ui.dp(10), ui.dp(67));
                    c.drawRoundRect(box, ui.dp(6), ui.dp(6), paint);
                    paint.setColor(fore);
                    box.set(ui.dp(14), ui.dp(58), ui.dp(42), ui.dp(64));
                    c.drawRoundRect(box, ui.dp(3), ui.dp(3), paint);
                    paint.setColor(second);
                    c.drawCircle(w - ui.dp(18), ui.dp(61), ui.dp(2.5f), paint);
                    // Palette label and selected checkmark badge.
                    paint.setColor(words);
                    paint.setTextSize(ui.dp(12));
                    paint.setFakeBoldText(true);
                    c.drawText(PALETTE_NAMES[pick], ui.dp(12), h - ui.dp(11), paint);
                    paint.setFakeBoldText(false);
                    if (pick == chosen) {
                        float cx = w - ui.dp(14), cy = ui.dp(14), cr = ui.dp(7.5f);
                        paint.setColor(fore);
                        c.drawCircle(cx, cy, cr, paint);
                        int onDot = SpatchUi.luminance(fore) > .55f ? 0xff101014 : 0xffffffff;
                        c.save();
                        c.translate(cx - ui.dp(5), cy - ui.dp(5));
                        SpatchUi.drawGlyph(c, paint, glyphPath, "check", onDot, ui.dp(10));
                        c.restore();
                    }
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(ui.dp(pick == chosen ? 2.5f : 1));
                    paint.setColor(pick == chosen ? fore : SpatchUi.blend(back, words, .15f));
                    float in = paint.getStrokeWidth() / 2;
                    box.set(in, in, w - in, h - in);
                    c.drawRoundRect(box, r - in, r - in, paint);
                }
            };
            ui.tappable(card, PALETTE_NAMES[i] + " palette" + (i == chosen ? ", selected" : ""), () -> {
                ui.prefs.edit().putInt("palette", pick).apply();
                revealPalette(card, () -> settingsChanged(true));
            });
            LinearLayout.LayoutParams at = ui.lp(ui.dp(146), ui.dp(98));
            at.setMarginEnd(ui.dp(8));
            cards.addView(card, at);
        }
        strip.addView(cards);
        box.addView(strip, ui.fill(-2));
        if (chosen > 1) strip.post(() -> strip.scrollTo((chosen - 1) * ui.dp(154), 0));
    }

    /** Accent swatches: used when the cover can’t colour the app (or Artwork colours is off). */
    private void accentRow(LinearLayout box) {
        TextView label = ui.text("Accent colour", 16, () -> ui.ink, true);
        label.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(2));
        box.addView(label);
        TextView hint = ui.text("Used when Artwork colours is off or the cover has none", 13, () -> ui.inkDim, false);
        hint.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(8));
        box.addView(hint);
        LinearLayout swatches = ui.row();
        swatches.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(12));
        int chosen = ui.prefs.getInt("accent", 0);
        for (int i = 0; i < SpatchUi.ACCENTS.length; i++) {
            final int pick = i;
            View dot = new View(ui.activity) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas c) {
                    int colour = pick == 0 ? SpatchUi.blend(ui.surfHi, ui.accent, .6f) : SpatchUi.ACCENTS[pick];
                    float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - ui.dp(5);
                    if (pick == chosen) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(ui.dp(2.5f));
                        paint.setColor(ui.ink);
                        c.drawCircle(cx, cy, r + ui.dp(2), paint);
                        r -= ui.dp(2);
                    }
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(colour);
                    c.drawCircle(cx, cy, r, paint);
                }
            };
            ui.tappable(dot, ACCENT_NAMES[i] + " accent", () -> { ui.prefs.edit().putInt("accent", pick).apply(); revealPalette(dot, () -> settingsChanged(true)); });
            swatches.addView(dot, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
        }
        box.addView(swatches, ui.fill(-2));
    }

    /** Shows how much artwork is stored on the phone and clears it when tapped. */
    private void cacheRow(LinearLayout box) {
        LinearLayout line = ui.row();
        line.setPadding(ui.dp(18), ui.dp(12), ui.dp(16), ui.dp(12));
        LinearLayout words = ui.column();
        words.addView(ui.text("Artwork cache", 16, () -> ui.ink, true));
        TextView size = ui.text("Counting…", 13, () -> ui.inkDim, false);
        size.setPadding(0, ui.dp(2), 0, 0);
        words.addView(size);
        line.addView(words, ui.weight());
        TextView clear = ui.text("Clear", 14, ui.onAccent, true);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(ui.dp(18), 0, ui.dp(18), 0);
        GradientDrawable clearBg = ui.round(ui.accent, 20);
        clear.setBackground(clearBg);
        ui.colored(clear, () -> { clear.setTextColor(ui.onAccent); clearBg.setColor(ui.accent); });
        line.addView(clear, ui.lp(-2, ui.dp(40)));
        box.addView(line, ui.fill(-2));
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            long bytes = 0;
            java.io.File[] files = CoreBridge.cacheDir == null ? null : CoreBridge.cacheDir.listFiles();
            if (files != null) for (java.io.File f : files) bytes += f.length();
            final String text = bytes < 1024 * 1024 ? (bytes / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
            ui.post(() -> size.setText(text + " of saved covers; they download again when needed"));
        });
        ui.tappable(clear, "Clear the artwork cache", () -> ui.submit(SpatchUi.PLAYLISTS, () -> {
            java.io.File[] files = CoreBridge.cacheDir == null ? null : CoreBridge.cacheDir.listFiles();
            if (files != null) for (java.io.File f : files) f.delete();
            ui.post(() -> {
                SpatchUi.IMAGES.evictAll();
                ui.failed.clear();
                size.setText("0 KB of saved covers; they download again when needed");
                ui.toast("Artwork cache cleared");
                SpatchUi.invalidateArt(ui);
            });
        }));
    }

    LinearLayout card(LinearLayout page, String title) {
        TextView label = ui.text(title, 14, () -> ui.accent, true);
        label.setPadding(ui.dp(6), ui.dp(18), 0, ui.dp(8));
        page.addView(label);
        LinearLayout box = ui.column();
        ui.bgRound(box, 26, () -> ui.surf);
        box.setPadding(0, ui.dp(6), 0, ui.dp(6));
        page.addView(box, ui.fill(-2));
        return box;
    }

    void settingsAction(LinearLayout box, String title, String detail, Runnable action) {
        LinearLayout line = ui.row();
        line.setPadding(ui.dp(18), ui.dp(12), ui.dp(16), ui.dp(12));
        LinearLayout words = ui.column();
        words.addView(ui.text(title, 16, () -> ui.ink, true));
        TextView hint = ui.text(detail, 13, () -> ui.inkDim, false);
        hint.setPadding(0, ui.dp(2), ui.dp(8), 0);
        words.addView(hint);
        line.addView(words, ui.weight());
        line.addView(ui.icon("chevron", () -> ui.inkDim), ui.lp(ui.dp(24), ui.dp(24)));
        ui.tappable(line, title, action);
        box.addView(line, ui.fill(-2));
    }

    private static String db(int gain) { return (gain > 0 ? "+" : "") + gain + " dB"; }

    /**
     * Turning a switch on sends a soft ring of the accent out from the switch across its row, a small sign that it
     * took. Drawn on the row's overlay, so the row clips it and nothing is laid out.
     */
    void ripple(View row, View from) {
        if (!ui.motion() || row.getWidth() == 0) return;
        float cx = from.getLeft() + from.getWidth() / 2f, cy = from.getTop() + from.getHeight() / 2f;
        float reach = Math.max(cx, row.getWidth() - cx) + ui.dp(24);
        int accent = ui.accent;
        float[] t = {0f};
        android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(android.graphics.Paint.Style.STROKE);
        ring.setStrokeWidth(ui.dp(2));
        android.graphics.drawable.Drawable wave = new android.graphics.drawable.Drawable() {
            @Override public void draw(android.graphics.Canvas c) {
                float r = reach * t[0], fade = 1f - t[0];
                fill.setColor((accent & 0x00ffffff) | Math.round(0x30 * fade) << 24);
                ring.setColor((accent & 0x00ffffff) | Math.round(0x90 * fade) << 24);
                c.drawCircle(cx, cy, r, fill);
                c.drawCircle(cx, cy, r, ring);
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
        wave.setBounds(0, 0, row.getWidth(), row.getHeight());
        row.getOverlay().add(wave);
        android.animation.ValueAnimator spread = android.animation.ValueAnimator.ofFloat(0f, 1f);
        spread.setDuration(620);
        spread.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        spread.addUpdateListener(a -> { t[0] = (float) a.getAnimatedValue(); wave.invalidateSelf(); });
        spread.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { row.getOverlay().remove(wave); }
        });
        spread.start();
    }

    void toggle(LinearLayout box, String name, String detail, String key, boolean fallback, boolean rebuild) {
        LinearLayout line = ui.row();
        line.setPadding(ui.dp(18), ui.dp(12), ui.dp(16), ui.dp(12));
        LinearLayout words = ui.column();
        words.addView(ui.text(name, 16, () -> ui.ink, true));
        TextView d = ui.text(detail, 13, () -> ui.inkDim, false);
        d.setPadding(0, ui.dp(2), ui.dp(8), 0);
        words.addView(d);
        line.addView(words, ui.weight());
        Switch sw = ui.controls.new Switch(ui.pref(key, fallback));
        line.addView(sw, ui.lp(ui.dp(52), ui.dp(32)));
        if (android.os.Build.VERSION.SDK_INT >= 30) line.setStateDescription(sw.on ? "On" : "Off");
        ui.tappable(line, name, () -> {
            sw.set(!sw.on);
            if (sw.on) ripple(line, sw);
            ui.prefs.edit().putBoolean(key, sw.on).apply();
            if (android.os.Build.VERSION.SDK_INT >= 30) line.setStateDescription(sw.on ? "On" : "Off");
            if (key.equals("equalizer") || key.startsWith("eq_")) Audio.refreshEqualizer();
            if (key.equals("auto_hide_dock") && !sw.on) ui.dockUi.setDockHidden(false);
            if (key.equals("audio_direct")) Audio.setDirect(sw.on);
            if (key.equals("lyric_gen") && sw.on) ui.submit(SpatchUi.ART, () -> {
                try { LyricGen.download(); }
                catch (Exception failed) { LyricGen.status = "Couldn’t get the model: " + failed.getMessage() + ". Turn this off and on to retry."; }
            });
            if (key.equals("audio") && sw.on && !Audio.directOn && !Audio.permitted(ui.activity)) ui.activity.requestPermissions(new String[]{Audio.PERMISSION}, 7341);
            if (key.equals("art_colors")) { ui.paletteKey = "?"; ui.refreshPalette(); }
            else if (key.equals("gpu_fx") || key.equals("frost")) { // the dock's pills and the page capture are set up by build()
                int y = ui.pageScroll != null ? ui.pageScroll.getScrollY() : 0;
                ui.build();
                SpatchUi.keepScroll(ui.pageScroll, y);
            }
            else if (key.equals("motes") || key.equals("backdrop")) settingsChanged(false);
            else if (key.equals("idle_hide")) {
                if (!sw.on) { ui.playerScreen.idleHidden = false; for (View v : ui.playerScreen.idleViews) v.setAlpha(1f); }
                settingsChanged(false);
            }
            else if (key.equals("rotate")) ui.playerScreen.applyRotation();
            else if (rebuild) { int y = ui.pageScroll != null ? ui.pageScroll.getScrollY() : 0; ui.build(); SpatchUi.keepScroll(ui.pageScroll, y); }
        });
        box.addView(line, ui.fill(-2));
    }
}
