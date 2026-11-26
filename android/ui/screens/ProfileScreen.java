package spatch;

import spatch.ArtworkViews.Art;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Set;

/** Profile and library stats pages, plus cached account loading. */
final class ProfileScreen {
    private final SpatchUi ui;
    ProfileScreen(SpatchUi ui) { this.ui = ui; }

    String profileName = "", profileUsername = "", profileImage = "", profileUri = "", profileError = "";
    boolean profileLoading, profileAnnounce;
    long profileAt, profileStartedAt;
    int profileTicket, profileAttempts;
    /** Asks again for the account until it's known (the library, which it's learned from, may not be ready yet). */
    final Runnable profileRetry = () -> loadProfile(true);
    private final Set<Art> avatars = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    void loadSaved() {
        profileName = ui.prefs.getString("profile_name", "");
        profileUsername = ui.prefs.getString("profile_user", "");
        profileImage = ui.prefs.getString("profile_image", "");
        profileUri = ui.prefs.getString("profile_uri", "");
    }

    Art profilePhoto() {
        Art photo = ui.artwork.new Art(Art.CIRCLE, false);
        photo.letter = SpatchUi.initial(profileName.isEmpty() ? profileUsername.isEmpty() ? "You" : profileUsername : profileName);
        photo.bind(profileImage);
        avatars.add(photo);
        return photo;
    }

    /** A short "updated Xs/Xm/Xh ago" style label from an `uptimeMillis()` timestamp. */
    private static String ago(long at) {
        long secs = (SystemClock.uptimeMillis() - at) / 1000;
        if (secs < 5) return "just now";
        if (secs < 60) return secs + "s ago";
        long mins = secs / 60;
        if (mins < 60) return mins + "m ago";
        return (mins / 60) + "h ago";
    }

    void loadProfile(boolean force) {
        if (ui.closed) return;
        long now = SystemClock.uptimeMillis();
        // A load that has hung for 20 s no longer blocks a new one (it used to, so Refresh did nothing).
        boolean hung = profileLoading && now - profileStartedAt > 20_000;
        if (profileLoading && !hung) return;
        if (!force && profileAt != 0 && now - profileAt < (profileUsername.isEmpty() ? 5_000 : 60_000)) return;
        ui.removeCallbacks(profileRetry);
        profileLoading = true;
        profileStartedAt = now;
        final int ticket = ++profileTicket;
        ui.submit(SpatchUi.PLAYLISTS, () -> {
            String[] result;
            try { result = SpatchUi.nativeProfile(); }
            catch (RuntimeException | LinkageError failed) { result = new String[]{"", "", "", "", failed.toString()}; }
            final String[] info = result;
            ui.post(() -> {
                if (ui.closed || ticket != profileTicket) return; // a newer load or detach took over
                profileLoading = false;
                profileAt = SystemClock.uptimeMillis();
                if (info != null && info.length >= 5) {
                    boolean fallback = !info[4].isEmpty();
                    if (!info[1].isEmpty()) {
                        // A failed profile fetch falls back to the username; keep the saved name/photo for that account,
                        // but let a successful fetch update or clear them, and give a newly discovered username fresh retries.
                        boolean same = info[1].equals(profileUsername);
                        if (!same) profileAttempts = 0;
                        String image = fallback && info[2].isEmpty() && same ? profileImage : info[2];
                        String name = fallback && same && !profileName.isEmpty() && info[0].equals(info[1]) ? profileName : info[0];
                        profileName = name; profileUsername = info[1]; profileImage = image; profileUri = info[3];
                        ui.prefs.edit().putString("profile_name", profileName).putString("profile_user", profileUsername)
                            .putString("profile_image", profileImage).putString("profile_uri", profileUri).apply();
                    }
                    // A failed lookup never blanks the account saved from last time.
                    profileError = !fallback && info[1].isEmpty() ? "Profile information is not available yet" : info[4];
                    if (!profileError.isEmpty() && profileUsername.isEmpty()) profileName = "";
                    for (Art photo : avatars) {
                        photo.letter = SpatchUi.initial(profileName.isEmpty() ? profileUsername.isEmpty() ? "You" : profileUsername : profileName);
                        photo.bind(profileImage);
                        photo.invalidate();
                    }
                } else profileError = "Profile information is not available yet";
                if (!profileError.isEmpty() && (profileUsername.isEmpty() || profileAttempts < 12)) {
                    // Keep trying with a growing pause: 2, 4, 8, 16, then every 30 seconds (twelve times if the
                    // saved account is already showing).
                    profileAttempts++;
                    if (ui.isAttachedToWindow() && ui.getWindowVisibility() == View.VISIBLE) {
                        ui.postDelayed(profileRetry, Math.min(30_000, 2_000L << Math.min(profileAttempts - 1, 4)));
                    }
                } else if (profileError.isEmpty()) profileAttempts = 0;
                if (profileAnnounce) {
                    profileAnnounce = false;
                    String msg = profileUsername.isEmpty()
                        ? "Couldn’t read your account yet. Spatch will keep trying."
                        : profileError.isEmpty() ? "Signed in as @" + profileUsername : "Couldn’t refresh profile: " + profileError;
                    ui.toast(msg);
                }
                if (ui.tab == SpatchUi.PROFILE && ui.playlist == null) ui.showPage();
            });
        });
    }

    private String tabLabel(int t) {
        switch (t) {
            case SpatchUi.HOME: return "Home";
            case SpatchUi.SEARCH: return "Search";
            case SpatchUi.LIBRARY: return "Library";
            case SpatchUi.SETTINGS: return "Settings";
            case SpatchUi.PROFILE: return "Profile";
            case SpatchUi.STATS: return "Your library";
            default: return "Back";
        }
    }

    /**
     * Spotify's own screens do the sign-in. Starting its login activity from here with hand-built extras hung after the
     * credentials were entered, while Spotify's own Add account (account switching) works, so this reveals Spotify's
     * UI instead; its "Back to Spatch" pill returns here.
     */
    private void openLogin() {
        ui.toast("Open your profile in Spotify and choose Add account", true);
        ui.openNativeSpotify();
    }

    /** What Spatch knows about the connection to Spotify, with a button to copy it for whoever is helping. */
    private void connectionCard(LinearLayout page) {
        LinearLayout box = ui.settingsScreen.card(page, "Connection");
        String[] c;
        try { c = SpatchUi.nativeConnection(); } catch (RuntimeException | LinkageError failed) { c = null; }
        String library = c != null && c.length > 0 ? c[0] : "unknown";
        String username = !profileUsername.isEmpty() ? "@" + profileUsername : c != null && c.length > 1 && !c[1].isEmpty() ? "@" + c[1] : "not found yet";
        String lookup = c != null && c.length > 2 ? c[2] : "";
        StringBuilder report = new StringBuilder("Spatch build ").append(SpatchUi.nativeBuild())
            .append("\nLibrary: ").append(library)
            .append("\nUsername: ").append(username)
            .append("\nProfile: ").append(profileError.isEmpty() ? (profileLoading ? "loading" : "ok") : profileError);
        if (!lookup.isEmpty()) report.append("\nLast lookup: ").append(lookup);
        TextView facts = ui.text(report.toString(), 13, () -> ui.inkDim, false);
        facts.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(6));
        box.addView(facts, ui.fill(-2));
        final String copy = report.toString();
        ui.settingsScreen.settingsAction(box, "Copy details", "For troubleshooting: paste it to whoever is helping you", () -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ui.activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spatch connection details", copy));
            ui.toast("Details copied");
        });
    }

    View profilePage() {
        LinearLayout page = ui.column();
        page.addView(ui.backHeader(ui.tabHistory.isEmpty() ? "Back" : tabLabel(ui.tabHistory.peek()), false), ui.lp(-2, ui.dp(36)));
        ui.pageHeading(page, "Your profile", false);
        LinearLayout identity = ui.settingsScreen.card(page, "Spotify account");
        LinearLayout photo = ui.row();
        photo.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        photo.addView(profilePhoto(), ui.lp(ui.dp(88), ui.dp(88)));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(18), 0, 0, 0);
        words.addView(ui.text(profileName.isEmpty() ? "Your profile" : profileName, 24, () -> ui.ink, true));
        TextView profileUsernameLine = ui.text(profileUsername.isEmpty() ? (profileLoading ? "Connecting to your Spotify account…" : "Couldn’t read your username yet. Still trying.") : "@" + profileUsername, 13, () -> ui.inkDim, false);
        if (!profileUsername.isEmpty()) ui.oneLine(profileUsernameLine);
        words.addView(profileUsernameLine);
        if (profileUsername.isEmpty() && !profileError.isEmpty() && !profileLoading) words.addView(ui.text(profileError, 12, () -> ui.inkDim, false));
        photo.addView(words, ui.weight());
        identity.addView(photo, ui.fill(-2));
        if (!profileUri.isEmpty()) ui.settingsScreen.settingsAction(identity, "Copy profile link", "Share your Spotify profile", () -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ui.activity.getSystemService(Context.CLIPBOARD_SERVICE);
            String link = profileUri.startsWith("spotify:user:") ? "https://open.spotify.com/user/" + android.net.Uri.encode(profileUri.substring(13)) : profileUri;
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Spotify profile", link));
            ui.toast("Profile link copied");
        });
        String refreshSubtitle = profileUsername.isEmpty() ? "Spatch is still working out who you are. Tap to check now."
            : profileLoading ? "Checking your Spotify account…"
            : profileAt == 0 ? "Reload your name and photo from Spotify"
            : "Reload your name and photo from Spotify · Updated " + ago(profileAt);
        ui.settingsScreen.settingsAction(identity, "Refresh profile", refreshSubtitle, () -> {
            profileAnnounce = true;
            ui.toast(profileLoading ? "Still checking your Spotify account…" : "Checking your Spotify account…");
            loadProfile(true);
        });
        ui.settingsScreen.settingsAction(identity, "Sign in or switch account", "Opens Spotify’s own screens to sign in or add an account. If you’re already signed in, you don’t need this.", this::openLogin);
        ui.settingsScreen.settingsAction(identity, "Sign in here (test)", "Spotify’s own sign-in screen, opened the way Spotify opens it", () -> {
            String error = CoreBridge.openSpotifyLogin(ui.activity);
            if (error != null && !error.isEmpty()) ui.toast(error, true);
        });
        ui.settingsScreen.settingsAction(identity, "Open native Spotify", "Temporarily reveal Spotify’s own interface to sign in or manage account", ui::openNativeSpotify);
        connectionCard(page);
        LinearLayout music = ui.settingsScreen.card(page, "Your music");
        ui.settingsScreen.settingsAction(music, "Your playlists", "Open your library", () -> { ui.filter = 0; ui.goFromLink(SpatchUi.LIBRARY); });
        ui.settingsScreen.settingsAction(music, "Create a playlist", "Start a new collection", () -> { ui.creation.pendingPlaylistSong = null; ui.creation.openCreatePlaylist(false); });
        ui.settingsScreen.settingsAction(music, "Your library, at a glance", "What's saved, and what you've played lately", () -> ui.goFromLink(SpatchUi.STATS));
        ui.settingsScreen.settingsAction(music, "Settings", "Personalise Spatch", () -> ui.openSettings(true));
        return ui.scrolling(page);
    }

    /** A simple recap built only from what Spatch already has cached: counts by kind, and the recent shelf. */
    View statsPage() {
        LinearLayout page = ui.column();
        page.addView(ui.backHeader(ui.tabHistory.isEmpty() ? "Back" : tabLabel(ui.tabHistory.peek()), false), ui.lp(-2, ui.dp(36)));
        ui.pageHeading(page, "Your library", false);
        int[] counts = new int[4]; // playlists, albums, artists, podcasts, by chip index
        for (MediaEntry e : ui.entries(-1, "", false, 4000)) if (e.category >= 0 && e.category < counts.length) counts[e.category]++;
        LinearLayout glance = ui.settingsScreen.card(page, "Saved");
        if (ui.motion()) {
            android.view.animation.AnimationSet rise = new android.view.animation.AnimationSet(true);
            rise.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
            rise.addAnimation(new android.view.animation.TranslateAnimation(0, 0, ui.dp(18), 0));
            rise.setDuration(320);
            rise.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
            glance.setLayoutAnimation(new android.view.animation.LayoutAnimationController(rise, .07f));
        }
        String[] kindNames = {"Playlists", "Albums", "Artists", "Podcasts"};
        for (int i = 0; i < counts.length; i++) {
            LinearLayout row = ui.row();
            row.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10));
            row.addView(ui.text(kindNames[i], 15, () -> ui.ink, false), ui.weight());
            row.addView(ui.text(String.valueOf(counts[i]), 15, () -> ui.inkDim, true));
            glance.addView(row, ui.fill(-2));
        }
        int total = counts[0] + counts[1] + counts[2] + counts[3];
        TextView totalNote = ui.text(total == 1 ? "1 item saved" : total + " items saved, across everything Spatch can see in your library", 12, () -> ui.inkDim, false);
        totalNote.setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(12));
        glance.addView(totalNote);
        ui.homeScreen.shelf(page, "Recently played", ui.homeScreen.recent, null);
        return ui.scrolling(page);
    }
}
