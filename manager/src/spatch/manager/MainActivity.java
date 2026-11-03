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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
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
    private static final String SPOTIFY = "com.spotify.music", INSTALLED = "installed_bundle", DEBUG = "developer_build";
    private static final int PICK_APKM = 1, EXPORT_KEY = 2, IMPORT_KEY = 3;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Keys keys;
    private Releases releases;
    private String releaseError, pendingTag;
    private boolean busy, installingSelf, conflict;
    private Look look;
    private LinearLayout statusCard;

    private TextView spotifyStatus, apkmStatus, patchStatus, keyStatus, managerStatus, status;
    private Button choose, patch, uninstall, managerUpdate, check, exportKey, importKey;
    private ProgressBar progress;
    private Switch developer;

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
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); // the page draws its own heading
        prefs = getSharedPreferences("manager", MODE_PRIVATE);
        look = new Look(this);
        getWindow().getDecorView().setBackgroundColor(look.surface);
        getWindow().setStatusBarColor(look.surface);
        getWindow().setNavigationBarColor(look.surface);
        if (!look.dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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
        LinearLayout page = look.column();
        page.setPadding(look.dp(16), look.dp(56), look.dp(16), look.dp(32));

        TextView title = look.text("Spatch", 36, look.onSurface, false);
        title.setPadding(look.dp(8), 0, look.dp(8), 0);
        page.addView(title);
        TextView subtitle = look.text("Patches Spotify on this phone and signs it with this phone's own key.", 15, look.onSurfaceVariant, false);
        subtitle.setPadding(look.dp(8), look.dp(4), look.dp(8), look.dp(8));
        page.addView(subtitle);

        statusCard = look.column();
        statusCard.setPadding(look.dp(20), look.dp(16), look.dp(20), look.dp(16));
        statusCard.setBackground(look.shape(look.secondaryContainer, 28));
        status = look.text("", 15, look.onSecondaryContainer, false);
        statusCard.addView(status);
        progress = look.progress();
        progress.setVisibility(View.GONE);
        statusCard.addView(progress, look.spaced(10));
        statusCard.setVisibility(View.GONE);
        page.addView(statusCard, look.spaced(12));

        LinearLayout spotify = look.card(page, "Spotify");
        spotifyStatus = look.text("", 16, look.onSurface, false);
        spotify.addView(spotifyStatus);
        apkmStatus = look.text("", 14, look.onSurfaceVariant, false);
        spotify.addView(apkmStatus, look.spaced(6));
        patchStatus = look.text("", 14, look.onSurfaceVariant, false);
        spotify.addView(patchStatus, look.spaced(2));
        developer = new Switch(this);
        developer.setChecked(prefs.getBoolean(DEBUG, false));
        developer.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(DEBUG, on).apply();
            refresh();
        });
        spotify.addView(look.switchRow("Developer build", "Adds Developer options: self tests, frame monitor, logs", developer), look.spaced(6));
        patch = look.button("Install Spatch", Look.Kind.FILLED, v -> patch());
        spotify.addView(patch, look.spaced(8));
        choose = look.button("Choose Spotify APKM", Look.Kind.TONAL, v -> pickApkm());
        spotify.addView(choose, look.spaced(8));
        uninstall = look.button("Uninstall Spotify", Look.Kind.TONAL, v -> startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + SPOTIFY))));
        spotify.addView(uninstall, look.spaced(8));

        LinearLayout key = look.card(page, "Signing key");
        keyStatus = look.text("", 14, look.onSurfaceVariant, false);
        keyStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.addView(keyStatus);
        exportKey = look.button("Export", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-pkcs12").putExtra(Intent.EXTRA_TITLE, "spatch-key.p12"), EXPORT_KEY));
        importKey = look.button("Import", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_KEY));
        key.addView(look.row(exportKey, importKey), look.spaced(12));

        LinearLayout manager = look.card(page, "Manager");
        managerStatus = look.text("", 14, look.onSurfaceVariant, false);
        manager.addView(managerStatus);
        managerUpdate = look.button("Update manager", Look.Kind.FILLED, v -> updateManager());
        manager.addView(managerUpdate, look.spaced(12));
        check = look.button("Check for updates", Look.Kind.TEXT, v -> checkUpdates());
        Button page2 = look.button("Releases", Look.Kind.TEXT, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Releases.PAGE))));
        manager.addView(look.row(check, page2), look.spaced(4));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.addView(page);
        return scroll;
    }

    /** Puts every label and button in line with the current state. */
    private void refresh() {
        PackageInfo info = spotify();
        boolean ours = info != null && keys != null && signedByUs(info);
        String installed = prefs.getString(INSTALLED, null);
        boolean debug = developer.isChecked();
        spotifyStatus.setText(info == null ? "Spotify isn't installed."
            : ours ? "Spatch is installed" + (installed != null ? ": patches " + installed.replaceFirst("^(patches-v|bundle-)", "") : "") + ". Spotify " + info.versionName + "."
            : "Spotify " + info.versionName + " is installed with another key. To install over it, import the key it was signed with (Signing key, Import key); otherwise it has to be uninstalled first.");
        File apkm = apkm();
        apkmStatus.setText(apkm.isFile() ? "APKM saved (" + apkm.length() / (1 << 20) + " MB)."
            : originalBase().isFile() ? "The original Spotify is kept, so no APKM is needed."
            : info != null && !ours ? "No APKM needed if the installed Spotify is the unmodified version Spatch supports."
            : "Choose the Spotify APKM that Spatch supports.");
        Releases.Release bundle = releases == null ? null : releases.bundle;
        patchStatus.setText(bundle != null ? "Newest patches: " + Releases.version(bundle) + (debug && bundle.debugUrl == null ? ", which has no developer build." : ".")
            : releaseError != null ? releaseError : "Looking for patches…");
        String wanted = bundle == null ? null : variant(bundle.tag, debug);
        patch.setText(!ours ? "Install Spatch" : wanted != null && !wanted.equals(installed) ? (installed != null && installed.startsWith(bundle.tag) ? "Switch build" : "Update Spatch") : "Reinstall Spatch");
        choose.setText(apkm.isFile() ? "Choose another APKM" : "Choose Spotify APKM");
        look.enable(patch, !busy && keys != null && bundle != null && (!debug || bundle.debugUrl != null) && (apkm.isFile() || info != null));
        developer.setEnabled(!busy);
        uninstall.setVisibility(conflict && info != null && !ours ? View.VISIBLE : View.GONE);
        try {
            keyStatus.setText(keys == null ? "Making this phone's key…" : "Fingerprint\n" + keys.fingerprint().substring(0, 32).replaceAll("(.{4})(?!$)", "$1 "));
        } catch (Exception e) {
            keyStatus.setText("Key unreadable.");
        }
        int own = ownCode(), newest = Releases.code(releases == null ? null : releases.manager);
        managerStatus.setText("Version " + ownName() + (newest > own ? ". Version " + Releases.version(releases.manager) + " is available." : "."));
        managerUpdate.setVisibility(newest > own ? View.VISIBLE : View.GONE);
        for (Button b : Arrays.asList(choose, managerUpdate, check, importKey)) look.enable(b, !busy);
        look.enable(exportKey, !busy && keys != null);
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
        final boolean debug = developer.isChecked();
        final String tag = variant(release.tag, debug);
        conflict = false;
        work("Getting the patches", () -> {
            File bundle = new File(getCacheDir(), release.tag + (debug ? "-debug" : "") + ".zip");
            if (!bundle.isFile()) {
                File[] old = getCacheDir().listFiles((dir, name) -> name.endsWith(".zip"));
                if (old != null) for (File f : old) f.delete();
                Releases.download(debug ? release.debugUrl : release.url, bundle, this::step, "Downloading the patches");
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
                pendingTag = tag;
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
        box.setPadding(look.dp(24), look.dp(8), look.dp(24), 0);
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

    private String ownName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

        private int ownCode() {
        try {
            return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** What is installed: the bundle's tag, marked when it is the developer build. */
    private static String variant(String tag, boolean debug) {
        return debug ? tag + " (developer)" : tag;
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
        statusCard.setVisibility(message.isEmpty() && progress.getVisibility() != View.VISIBLE ? View.GONE : View.VISIBLE);
    }
}
