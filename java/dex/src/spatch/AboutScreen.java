package spatch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Project information and support links using the shared Settings components. */
final class AboutScreen {
    private static final String PROJECT = "https://github.com/n0thhhing/spatch";

    static void render(SpatchUi ui, LinearLayout page, String build) {
        LinearLayout intro = ui.card(page, "Spatch");
        note(ui, intro, "Your music, your way. A custom interface over Spotify’s playback, library and Connect services.");
        note(ui, intro, "Spatch is an independent open-source project and is not affiliated with Spotify.");

        String host = ui.activity.getPackageName(), version = "Unavailable";
        try {
            PackageInfo info = ui.activity.getPackageManager().getPackageInfo(host, 0);
            if (info.versionName != null && !info.versionName.isEmpty()) version = info.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        String android = Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT;
        LinearLayout details = ui.card(page, "Build & device");
        detail(ui, details, "Spatch build", build);
        detail(ui, details, "Host app", host + "\nVersion " + version);
        detail(ui, details, "Android", android);
        String report = "Spatch build " + build + "\nHost " + host + "\nHost version " + version + "\nAndroid " + android;
        ui.settingsAction(details, "Copy build details", "Include these when reporting a problem", () -> {
            ClipboardManager clipboard = (ClipboardManager) ui.activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Spatch build details", report));
                Toast.makeText(ui.activity, "Build details copied", Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout project = ui.card(page, "Project & support");
        ui.settingsAction(project, "Source code", "Browse the project on GitHub", () -> open(ui, PROJECT));
        ui.settingsAction(project, "Releases & updates", "Download the latest fresh build", () -> open(ui, PROJECT + "/releases/tag/branch-fresh"));
        ui.settingsAction(project, "Report a problem", "Check existing issues or describe a bug", () -> open(ui, PROJECT + "/issues"));
        ui.settingsAction(project, "License", "MIT License · © 2026 n0thhhing", () -> open(ui, PROJECT + "/blob/fresh/LICENSE"));

        LinearLayout credits = ui.card(page, "Credits");
        note(ui, credits, "Built with Rust and Android. The native desktop preview uses Robolectric and the same Android screens.");
        ui.settingsAction(credits, "AutoEq", "Jaakko Pasanen and the headphone measurement contributors", () -> open(ui, "https://github.com/jaakkopasanen/AutoEq"));

        LinearLayout privacy = ui.card(page, "Your account & data");
        note(ui, privacy, "Sign-in and playback use Spotify’s existing session. Saved screen answers are cached on this device and kept separate by verified account identity.");
        note(ui, privacy, "Artwork and headphone profiles are fetched when needed. Manage saved covers and offline music in Downloads & storage. Spotify manages offline playback and account eligibility.");
    }

    private static void detail(SpatchUi ui, LinearLayout box, String label, String value) {
        TextView title = ui.text(label, 15, () -> ui.ink, true);
        title.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), 0);
        box.addView(title);
        note(ui, box, value);
    }

    private static void note(SpatchUi ui, LinearLayout box, String message) {
        TextView text = ui.text(message, 13, () -> ui.inkDim, false);
        text.setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(12));
        box.addView(text);
    }

    private static void open(SpatchUi ui, String url) {
        try { ui.activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException missing) { Toast.makeText(ui.activity, "No app available to open this link", Toast.LENGTH_LONG).show(); }
    }
}
