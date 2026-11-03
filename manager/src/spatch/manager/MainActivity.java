package spatch.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One screen: the user's APKM, the newest patch bundle, the phone's signing key and the manager's
 * own updates. Patching, signing and installing all happen here; nothing leaves the phone.
 */
public final class MainActivity extends Activity {
    private static final String SPOTIFY = "com.spotify.music", INSTALLED = "installed_bundle";
    private static final int PICK_APKM = 1, EXPORT_KEY = 2, IMPORT_KEY = 3;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Keys keys;
    private Releases releases;
    private String releaseError, pendingTag;
    private boolean busy, installingSelf, conflict;
    private int accent, text, muted, surface;

    private TextView spotifyStatus, apkmStatus, patchStatus, keyStatus, managerStatus, status;
    private Button choose, patch, uninstall, managerUpdate, check, exportKey, importKey;
    private ProgressBar progress;

    private final BroadcastReceiver installResult = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            busy = false;
            delete(new File(getCacheDir(), "build"));
            if (code == PackageInstaller.STATUS_SUCCESS) {
                if (!installingSelf && pendingTag != null) prefs.edit().putString(INSTALLED, pendingTag).apply();
                say(installingSelf ? "Spatch Manager is up to date." : "Spatch is installed. Open Spotify to use it.");
            } else if (code == PackageInstaller.STATUS_FAILURE_CONFLICT || code == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                conflict = !installingSelf;
                say(installingSelf ? "This manager build is signed with a different key; install the new one from GitHub by hand."
                    : "Spotify on this phone is signed with a different key. Uninstall it first (that removes its downloads), or import the key it was signed with.");
            } else if (code == PackageInstaller.STATUS_FAILURE_ABORTED) {
                say("Install cancelled.");
            } else {
                say("Install failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
            refresh();
        }
    };

    @Override
    protected void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("manager", MODE_PRIVATE);
        TypedArray a = obtainStyledAttributes(new int[] {android.R.attr.colorAccent, android.R.attr.textColorPrimary, android.R.attr.textColorSecondary, android.R.attr.colorBackground});
        accent = a.getColor(0, 0xffd0bcff);
        text = a.getColor(1, 0xfff3eeff);
        muted = a.getColor(2, 0xffbcb3dc);
        int background = a.getColor(3, 0xff14121c);
        a.recycle();
        surface = blend(background, text, 0.07f);
        setContentView(layout());
        registerReceiver(installResult, new IntentFilter(Installer.ACTION), Context.RECEIVER_NOT_EXPORTED);
        refresh();
        worker.execute(() -> {
            try {
                Keys k = Keys.loadOrCreate(getFilesDir());
                main.post(() -> {
                    keys = k;
                    refresh();
                });
            } catch (Exception e) {
                main.post(() -> say("Couldn't open the signing key: " + e.getMessage()));
            }
        });
        checkUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // Spotify may have been installed or removed meanwhile
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(installResult);
        worker.shutdownNow();
        super.onDestroy();
    }

    private View layout() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        page.setPadding(pad, dp(48), pad, pad);

        TextView title = label("Spatch Manager", 30, text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        page.addView(title);
        page.addView(label("Patches Spotify on this phone and signs it with this phone's own key.", 15, muted));

        LinearLayout spotify = card(page, "Spotify");
        spotifyStatus = line(spotify);
        apkmStatus = line(spotify);
        patchStatus = line(spotify);
        choose = button(spotify, "Choose Spotify APKM", v -> pickApkm());
        patch = button(spotify, "Install Spatch", v -> patch());
        uninstall = button(spotify, "Uninstall Spotify", v -> startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + SPOTIFY))));

        LinearLayout key = card(page, "Signing key");
        keyStatus = line(key);
        exportKey = button(key, "Export key", v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-pkcs12").putExtra(Intent.EXTRA_TITLE, "spatch-key.p12"), EXPORT_KEY));
        importKey = button(key, "Import key", v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_KEY));

        LinearLayout manager = card(page, "Manager");
        managerStatus = line(manager);
        managerUpdate = button(manager, "Update manager", v -> updateManager());
        check = button(manager, "Check for updates", v -> checkUpdates());
        button(manager, "Releases on GitHub", v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Releases.PAGE))));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        page.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        status = label("", 15, text);
        page.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        return scroll;
    }

    /** Puts every label and button in line with the current state. */
    private void refresh() {
        PackageInfo info = spotify();
        boolean ours = info != null && keys != null && signedByUs(info);
        String installed = prefs.getString(INSTALLED, null);
        spotifyStatus.setText(info == null ? "Spotify isn't installed."
            : ours ? "Spatch " + (installed != null ? "(" + installed + ") " : "") + "is installed, Spotify " + info.versionName + "."
            : "Spotify " + info.versionName + " is installed with another key. To install over it, import the key it was signed with (Signing key, Import key); otherwise it has to be uninstalled first.");
        File apkm = apkm();
        apkmStatus.setText(apkm.isFile() ? "APKM saved (" + apkm.length() / (1 << 20) + " MB)."
            : originalBase().isFile() ? "The original Spotify is kept, so no APKM is needed."
            : info != null && !ours ? "No APKM needed if the installed Spotify is the unmodified version Spatch supports."
            : "Choose the Spotify APKM that Spatch supports.");
        Releases.Release bundle = releases == null ? null : releases.bundle;
        patchStatus.setText(bundle != null ? "Newest patches: " + bundle.tag + "." : releaseError != null ? releaseError : "Looking for patches…");
        patch.setText(!ours ? "Install Spatch" : bundle != null && !bundle.tag.equals(installed) ? "Update Spatch" : "Reinstall Spatch");
        choose.setText(apkm.isFile() ? "Choose another APKM" : "Choose Spotify APKM");
        patch.setEnabled(!busy && keys != null && bundle != null && (apkm.isFile() || info != null));
        uninstall.setVisibility(conflict && info != null && !ours ? View.VISIBLE : View.GONE);
        try {
            keyStatus.setText(keys == null ? "Making this phone's key…" : "Fingerprint " + keys.fingerprint().substring(0, 16) + "…");
        } catch (Exception e) {
            keyStatus.setText("Key unreadable.");
        }
        int own = ownCode(), newest = Releases.code(releases == null ? null : releases.manager);
        managerStatus.setText("Version " + own + (newest > own ? ", version " + newest + " is available." : "."));
        managerUpdate.setVisibility(newest > own ? View.VISIBLE : View.GONE);
        for (Button b : Arrays.asList(choose, managerUpdate, check, exportKey, importKey)) b.setEnabled(!busy);
        exportKey.setEnabled(!busy && keys != null);
    }

    private void checkUpdates() {
        releaseError = null;
        refresh();
        new Thread(() -> {
            try {
                Releases r = Releases.fetch();
                main.post(() -> {
                    releases = r;
                    if (r.bundle == null) releaseError = "No patches are published yet.";
                    refresh();
                });
            } catch (Exception e) {
                main.post(() -> {
                    releaseError = "Couldn't reach GitHub: " + e.getMessage();
                    refresh();
                });
            }
        }).start();
    }

    private void pickApkm() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_APKM);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_APKM) {
            work("Copying the APKM", () -> {
                File tmp = new File(getFilesDir(), "spotify.apkm.part");
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
                    Bundle.copy(in, out);
                }
                if (!tmp.renameTo(apkm())) throw new java.io.IOException("Couldn't save the APKM.");
                done("APKM saved. It's checked when you install.");
            });
        } else if (request == EXPORT_KEY) {
            password("Protect the exported key", "Export", pass -> work("Exporting the key", () -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    keys.write(out, pass);
                }
                done("Key exported. Keep it and its password somewhere safe.");
            }));
        } else if (request == IMPORT_KEY) {
            password("Password of the key file", "Import", pass -> new AlertDialog.Builder(this)
                .setTitle("Replace this phone's key?")
                .setMessage("Spatch installed with the current key won't update until it's reinstalled with the new one.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace", (d, w) -> work("Importing the key", () -> {
                    Keys k;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        k = Keys.read(in, pass);
                    } catch (java.security.GeneralSecurityException | java.io.IOException e) {
                        throw new java.io.IOException("Couldn't read that key: the password is wrong, or the file uses encryption this phone can't read (see the manager README to re-export it).", e);
                    }
                    k.save(getFilesDir());
                    main.post(() -> keys = k);
                    done("Key imported.");
                }))
                .show());
        }
    }

    private void patch() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            say("Allow Spatch Manager to install apps, then tap Install again.");
            return;
        }
        final Releases.Release release = releases.bundle;
        conflict = false;
        work("Getting the patches", () -> {
            File bundle = new File(getCacheDir(), release.tag + ".zip");
            if (!bundle.isFile()) {
                File[] old = getCacheDir().listFiles((dir, name) -> name.endsWith(".zip"));
                if (old != null) for (File f : old) f.delete();
                Releases.download(release.url, bundle, this::step, "Downloading the patches");
            }
            File dir = new File(getCacheDir(), "build"), signedDir = new File(dir, "signed");
            delete(dir);
            signedDir.mkdirs();
            List<File> unsigned;
            try (Bundle b = new Bundle(bundle)) {
                unsigned = apkm().isFile() ? b.apply(apkm(), dir, this::step) : fromInstalled(b, dir);
            }
            List<File> signed = new ArrayList<>();
            for (int i = 0; i < unsigned.size(); i++) {
                File in = unsigned.get(i), out = new File(signedDir, in.getName());
                step("Signing " + in.getName(), i, unsigned.size());
                keys.sign(in, out);
                in.delete();
                signed.add(out);
            }
            step("Installing", 1, 1);
            main.post(() -> {
                pendingTag = release.tag;
                installingSelf = false;
            });
            Installer.install(this, signed);
        }, true);
    }

    /**
     * No APKM: the original base.apk kept from an earlier install, or else the installed Spotify's
     * own when it is still unmodified; the splits always come from the installed Spotify.
     */
    private List<File> fromInstalled(spatch.manager.Bundle b, File dir) throws Exception {
        PackageInfo info = spotify();
        if (info == null) throw new java.io.IOException("Choose the Spotify " + b.spotify + " APKM first.");
        if (info.getLongVersionCode() != b.spotifyCode) {
            throw new java.io.IOException("The Spotify on this phone is version " + info.versionName + ", and Spatch needs " + b.spotify + ". Choose the " + b.spotify + " APKM.");
        }
        File kept = originalBase();
        if (!kept.isFile()) {
            File base = new File(info.applicationInfo.sourceDir);
            step("Checking the installed Spotify", 0, 1);
            if (!spatch.manager.Bundle.sha256(base).equals(b.baseSha())) {
                throw new java.io.IOException("The Spotify on this phone is already patched, so it has no original to patch. Choose the Spotify " + b.spotify + " APKM once; the manager keeps what it needs for later updates.");
            }
            File tmp = new File(getFilesDir(), "original-base.apk.part");
            try (InputStream in = new java.io.FileInputStream(base); OutputStream out = new FileOutputStream(tmp)) {
                spatch.manager.Bundle.copy(in, out);
            }
            if (!tmp.renameTo(kept)) throw new java.io.IOException("Couldn't keep the original Spotify.");
        }
        List<File> splits = new ArrayList<>();
        if (info.applicationInfo.splitSourceDirs != null) for (String path : info.applicationInfo.splitSourceDirs) splits.add(new File(path));
        return b.apply(kept, splits, dir, this::step);
    }

    private void updateManager() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        final Releases.Release release = releases.manager;
        work("Downloading the manager", () -> {
            File apk = new File(getCacheDir(), "manager.apk");
            Releases.download(release.url, apk, this::step, "Downloading the manager");
            main.post(() -> installingSelf = true);
            Installer.install(this, java.util.Collections.singletonList(apk));
        }, true);
    }

    private interface Job {
        void run() throws Exception;
    }

    private void work(String what, Job job) {
        work(what, job, false);
    }

    /** Runs job off the main thread; an install keeps the busy state until its result arrives. */
    private void work(String what, Job job, boolean install) {
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        say(what + "…");
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        refresh();
        worker.execute(() -> {
            try {
                job.run();
                if (install) main.post(() -> {
                    progress.setVisibility(View.GONE);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    say("Waiting for the installer…");
                });
            } catch (Exception e) {
                done(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    private void step(String what, int done, int total) {
        main.post(() -> {
            say(what + "…");
            progress.setIndeterminate(false);
            progress.setMax(total);
            progress.setProgress(done);
        });
    }

    private void done(String message) {
        main.post(() -> {
            busy = false;
            progress.setVisibility(View.GONE);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            say(message);
            refresh();
        });
    }

    private interface Password {
        void accept(char[] password);
    }

    private void password(String title, String action, Password then) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton(action, (d, w) -> {
                if (field.length() == 0) say("A password is needed.");
                else then.accept(field.getText().toString().toCharArray());
            })
            .show();
    }

    private PackageInfo spotify() {
        try {
            return getPackageManager().getPackageInfo(SPOTIFY, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private boolean signedByUs(PackageInfo info) {
        try {
            if (info.signingInfo == null) return false;
            byte[] ours = keys.cert.getEncoded();
            for (Signature s : info.signingInfo.getApkContentsSigners()) if (Arrays.equals(s.toByteArray(), ours)) return true;
        } catch (Exception ignored) {
            // unreadable certificate: treat as someone else's
        }
        return false;
    }

    private int ownCode() {
        try {
            return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private File apkm() {
        return new File(getFilesDir(), "spotify.apkm");
    }

    /** The unmodified base.apk, kept so updates need neither an APKM nor a stock install. */
    private File originalBase() {
        return new File(getFilesDir(), "original-base.apk");
    }

    private static void delete(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) delete(c);
        f.delete();
    }

    private void say(String message) {
        status.setText(message);
    }

    private LinearLayout card(LinearLayout page, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(dp(24));
        card.setBackground(bg);
        TextView heading = label(title, 18, accent);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(heading);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        page.addView(card, lp);
        return card;
    }

    private TextView line(LinearLayout card) {
        TextView t = label("", 15, text);
        card.addView(t);
        return t;
    }

    private Button button(LinearLayout card, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        card.addView(b, lp);
        return b;
    }

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
}
