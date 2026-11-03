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
 * Four pages under a navigation bar: Spotify (the user's APKM and the newest patch bundle), Clone (a
 * second Spatch with its own package, name and icon), the phone's signing key and the manager's own
 * updates. Patching, signing and installing all happen here; nothing leaves the phone.
 */
public final class MainActivity extends Activity {
    private static final String SPOTIFY = "com.spotify.music", NOTIFY = "notify_updates", INVITE = "source_invite", DOWNLOADED = "apkm_downloaded";
    private static final String PAGE = "page", CLONE_PKG = "clone_package", CLONE_NAME = "clone_name", CLONE_INSTALLED = "clone_installed:";
    static final String INSTALLED = "installed_bundle", DEBUG = "developer_build", NIGHTLY = "nightly_patches", UPDATE = "start_update";
    private static final int PICK_APKM = 1, EXPORT_KEY = 2, IMPORT_KEY = 3, PICK_ICON = 4;
    private static final String[] PAGES = {"Spotify", "Clone", "Key", "Manager"}, PAGE_ICONS = {"music_note", "content_copy", "key", "tune"};
    private static final String[] HEADINGS = {"Spatch", "Clone", "Signing key", "Manager"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Keys keys;
    private Releases releases;
    private String releaseError, pendingTag, pendingKey, pendingDone;
    private boolean busy, installingSelf, conflict, autoUpdate;
    private volatile boolean replaceImport; // the chosen APKM was the wrong one and the user tapped Install again
    private volatile boolean inheriting, inheritFailed; // an update replacing base.apk alone; a failed one goes back to all five
    private Look look;
    private LinearLayout statusCard;

    private TextView spotifyStatus, apkmStatus, patchStatus, keyStatus, managerStatus, status, heading, cloneStatus;
    private Button choose, patch, uninstall, managerUpdate, check, exportKey, importKey, invite;
    private Button cloneInstall, cloneUninstall, cloneIconPick, cloneIconReset;
    private EditText clonePkg, cloneName;
    private android.widget.ImageView cloneIcon;
    private LinearLayout[] pages;
    private LinearLayout nav;
    private ScrollView scroll;
    private ProgressBar progress;
    private Switch developer, notify, nightly;

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
            boolean cloning = !installingSelf && !INSTALLED.equals(pendingKey);
            if (code == PackageInstaller.STATUS_SUCCESS) {
                inheritFailed = false;
                if (!installingSelf && pendingTag != null) prefs.edit().putString(pendingKey, pendingTag).apply();
                say(installingSelf ? "Spatch Manager is up to date." : pendingDone);
            } else if (code == PackageInstaller.STATUS_FAILURE_CONFLICT || code == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                conflict = !installingSelf && !cloning;
                say(installingSelf ? "This manager build is signed with a different key; install the new one from GitHub by hand."
                    : cloning ? "The clone clashes with an app signed with another key: another app has that package name, or Spotify's own app is installed (a clone can only sit beside Spatch). "
                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    : "Spotify on this phone is signed with a different key. Uninstall it first (that removes its downloads), or import the key it was signed with.");
            } else if (code == PackageInstaller.STATUS_FAILURE_ABORTED) {
                say("Install cancelled.");
            } else if (inheriting && !installingSelf) {
                inheritFailed = true;
                say("The quick update didn't take (" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "). Tap again to reinstall all of Spatch.");
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
        autoUpdate = getIntent().getBooleanExtra(UPDATE, false);
        shared(getIntent());
        UpdateCheck.schedule(this, notify.isChecked());
        if (notify.isChecked() && android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 0);
        }
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

    /** The update notification, tapped while the manager was already open. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        shared(intent);
        if (intent.getBooleanExtra(UPDATE, false)) {
            autoUpdate = true;
            checkUpdates();
        }
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

        heading = look.text("Spatch", 36, look.onSurface, false);
        heading.setPadding(look.dp(8), 0, look.dp(8), look.dp(4));
        page.addView(heading);

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
        pages = new LinearLayout[PAGES.length];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = look.column();
            page.addView(pages[i]);
        }

        TextView subtitle = look.text("Patches Spotify on this phone and signs it with this phone's own key.", 15, look.onSurfaceVariant, false);
        subtitle.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        pages[0].addView(subtitle);
        LinearLayout spotify = look.card(pages[0], "Spotify");
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
        nightly = new Switch(this);
        nightly.setChecked(prefs.getBoolean(NIGHTLY, false));
        nightly.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(NIGHTLY, on).apply();
            refresh();
        });
        spotify.addView(look.switchRow("Nightly patches", "Built from every change before it's released; less tested", nightly));
        patch = look.button("Install Spatch", "download", Look.Kind.FILLED, v -> patch());
        spotify.addView(patch, look.spaced(8));
        choose = look.button("Choose Spotify APKM", "folder_open", Look.Kind.TONAL, v -> pickApkm());
        spotify.addView(choose, look.spaced(8));
        invite = look.button("Add an invite", "link", Look.Kind.TEXT, v -> editInvite());
        spotify.addView(invite, look.spaced(4));
        uninstall = look.button("Uninstall Spotify", "delete", Look.Kind.TONAL, v -> startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + SPOTIFY))));
        spotify.addView(uninstall, look.spaced(8));

        cloneLayout(pages[1]);

        TextView keyNote = look.text("Spatch and its clones are signed with this key. Export it to keep it safe, or import the key an earlier Spatch was signed with so updates install over it.", 15, look.onSurfaceVariant, false);
        keyNote.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        pages[2].addView(keyNote);
        LinearLayout key = look.card(pages[2], "This phone's key");
        keyStatus = look.text("", 14, look.onSurfaceVariant, false);
        keyStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.addView(keyStatus);
        exportKey = look.button("Export", "key", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-pkcs12").putExtra(Intent.EXTRA_TITLE, "spatch-key.p12"), EXPORT_KEY));
        importKey = look.button("Import", "file_open", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_KEY));
        key.addView(look.row(exportKey, importKey), look.spaced(12));

        LinearLayout manager = look.card(pages[3], "Spatch Manager");
        managerStatus = look.text("", 14, look.onSurfaceVariant, false);
        manager.addView(managerStatus);
        notify = new Switch(this);
        notify.setChecked(prefs.getBoolean(NOTIFY, true));
        notify.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(NOTIFY, on).apply();
            UpdateCheck.schedule(this, on);
            if (on && android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 0);
        });
        manager.addView(look.switchRow("Tell me about new patches", "Checks twice a day; tap the notification to update", notify), look.spaced(4));
        managerUpdate = look.button("Update manager", "system_update", Look.Kind.FILLED, v -> updateManager());
        manager.addView(managerUpdate, look.spaced(12));
        check = look.button("Check for updates", "refresh", Look.Kind.TEXT, v -> checkUpdates());
        Button page2 = look.button("Releases", "open_in_new", Look.Kind.TEXT, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Releases.PAGE))));
        manager.addView(look.row(check, page2), look.spaced(4));

        scroll = new ScrollView(this);
        scroll.addView(page);
        nav = look.navBar(PAGES, PAGE_ICONS, this::show);
        LinearLayout root = look.column();
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(nav);
        // Android 15 draws apps edge to edge: keep the page clear of the status bar, the bar clear of navigation.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            scroll.setPadding(0, top, 0, 0);
            page.setPadding(look.dp(16), look.dp(24), look.dp(16), look.dp(32));
            nav.setPadding(0, 0, 0, bottom);
            return insets;
        });
        getWindow().setNavigationBarColor(look.container);
        show(Math.min(prefs.getInt(PAGE, 0), PAGES.length - 1));
        return root;
    }

    private void show(int index) {
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        heading.setText(HEADINGS[index]);
        look.select(nav, index);
        scroll.scrollTo(0, 0);
        prefs.edit().putInt(PAGE, index).apply();
    }

    private void cloneLayout(LinearLayout page) {
        TextView note = look.text("A second Spatch beside the first, with its own name, icon and account. It's signed with this phone's key, so it sits beside Spatch but not beside Spotify's own app.", 15, look.onSurfaceVariant, false);
        note.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        page.addView(note);
        LinearLayout card = look.card(page, "Second Spotify");
        cloneStatus = look.text("", 14, look.onSurfaceVariant, false);
        card.addView(cloneStatus);
        cloneName = new EditText(this);
        cloneName.setText(prefs.getString(CLONE_NAME, "Spotify Two"));
        cloneName.setHint("Spotify Two");
        card.addView(look.field("App name", cloneName), look.spaced(14));
        clonePkg = new EditText(this);
        clonePkg.setText(prefs.getString(CLONE_PKG, "com.spotify.music.two"));
        clonePkg.setHint("com.spotify.music.two");
        clonePkg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        clonePkg.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                refresh(); // the status follows the package typed
            }
        });
        card.addView(look.field("Package name", clonePkg), look.spaced(12));

        TextView iconLabel = look.text("Icon", 13, look.onSurfaceVariant, true);
        card.addView(iconLabel, look.spaced(12));
        cloneIcon = new android.widget.ImageView(this);
        cloneIcon.setClipToOutline(true);
        cloneIcon.setBackground(look.shape(look.containerHigh, 28));
        cloneIconPick = look.button("Choose", "image", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_ICON));
        cloneIconReset = look.button("Spotify's", Look.Kind.TEXT, v -> {
            cloneIconFile().delete();
            refresh();
        });
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(look.dp(56), look.dp(56));
        iconSize.setMarginEnd(look.dp(16));
        iconRow.addView(cloneIcon, iconSize);
        iconRow.addView(look.row(cloneIconPick, cloneIconReset), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(iconRow, look.spaced(6));

        cloneInstall = look.button("Install clone", "content_copy", Look.Kind.FILLED, v -> installClone());
        card.addView(cloneInstall, look.spaced(16));
        cloneUninstall = look.button("Uninstall clone", "delete", Look.Kind.TONAL, v -> startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + clonePkg.getText().toString().trim()))));
        card.addView(cloneUninstall, look.spaced(8));
    }

    private void installClone() {
        String pkg = clonePkg.getText().toString().trim(), name = cloneName.getText().toString().trim();
        String problem = Clone.problem(pkg);
        if (problem != null) {
            say(problem);
            return;
        }
        if (name.isEmpty()) {
            say("Give the clone a name.");
            return;
        }
        prefs.edit().putString(CLONE_PKG, pkg).putString(CLONE_NAME, name).apply();
        try {
            File icon = cloneIconFile();
            patch(new Clone(pkg, name, icon.isFile() ? java.nio.file.Files.readAllBytes(icon.toPath()) : null));
        } catch (java.io.IOException e) {
            say("Couldn't read the icon: " + e.getMessage());
        }
    }

    /** The chosen picture as an adaptive icon's foreground: centred in the 72dp a launcher shows, on its own average colour. */
    static byte[] iconPng(android.graphics.Bitmap picture) {
        int size = 432, inset = 72; // 108dp at 4x; the mask shows the middle 72dp
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(android.graphics.Bitmap.createScaledBitmap(picture, 1, 1, true).getPixel(0, 0) | 0xff000000);
        int w = picture.getWidth(), h = picture.getHeight(), side = Math.min(w, h);
        android.graphics.Rect from = new android.graphics.Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
        canvas.drawBitmap(picture, from, new android.graphics.Rect(inset, inset, size - inset, size - inset), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
        return png.toByteArray();
    }

    private File cloneIconFile() {
        return new File(getFilesDir(), "clone_icon.png");
    }

    private PackageInfo installed(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
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
        Sources.Invite source = invite();
        apkmStatus.setText(apkm.isFile() ? (prefs.getBoolean(DOWNLOADED, false) ? "Spotify downloaded (" : "APKM saved (") + apkm.length() / (1 << 20) + " MB)."
            : originalBase().isFile() ? "The original Spotify is kept, so no APKM is needed."
            : source != null ? "Spotify downloads from " + source.host() + " when you install."
            : info != null && !ours ? "No APKM needed if the installed Spotify is the unmodified version Spatch supports."
            : "Choose the Spotify APKM that Spatch supports, or add an invite to a Spatch source.");
        Releases.Release bundle = releases == null ? null : releases.bundle(nightly.isChecked());
        patchStatus.setText(bundle != null ? "Newest patches: " + Releases.version(bundle) + (debug && bundle.debugUrl == null ? ", which has no developer build." : ".")
            : releaseError != null ? releaseError : "Looking for patches…");
        String wanted = bundle == null ? null : variant(bundle.tag, debug);
        Look.label(patch, !ours ? "Install Spatch" : wanted != null && !wanted.equals(installed) ? (installed != null && installed.startsWith(bundle.tag) ? "Switch build" : "Update Spatch") : "Reinstall Spatch");
        Look.label(choose, apkm.isFile() ? "Choose another APKM" : "Choose Spotify APKM");
        look.enable(patch, !busy && keys != null && bundle != null && (!debug || bundle.debugUrl != null) && (apkm.isFile() || info != null || source != null));
        Look.label(invite, source == null ? "Add an invite" : "Invite for " + source.host());
        developer.setEnabled(!busy);
        nightly.setEnabled(!busy);
        uninstall.setVisibility(conflict && info != null && !ours ? View.VISIBLE : View.GONE);
        try {
            keyStatus.setText(keys == null ? "Making this phone's key…" : "Fingerprint\n" + keys.fingerprint().substring(0, 32).replaceAll("(.{4})(?!$)", "$1 "));
        } catch (Exception e) {
            keyStatus.setText("Key unreadable.");
        }
        int own = ownCode(), newest = Releases.code(releases == null ? null : releases.manager);
        managerStatus.setText("Version " + ownName() + (newest > own ? ". Version " + Releases.version(releases.manager) + " is available." : "."));
        managerUpdate.setVisibility(newest > own ? View.VISIBLE : View.GONE);
        for (Button b : Arrays.asList(choose, managerUpdate, check, importKey, invite, cloneIconPick, cloneIconReset)) look.enable(b, !busy);
        look.enable(exportKey, !busy && keys != null);

        String pkg = clonePkg.getText().toString().trim();
        String problem = Clone.problem(pkg);
        PackageInfo clone = problem == null ? installed(pkg) : null;
        boolean cloneOurs = clone != null && keys != null && signedByUs(clone);
        String cloneTag = prefs.getString(CLONE_INSTALLED + pkg, null);
        cloneStatus.setText(problem != null ? problem
            : clone == null ? "No clone is installed as " + pkg + "."
            : cloneOurs ? "Installed" + (cloneTag != null ? ", patches " + cloneTag.replaceFirst("^(patches-v|bundle-)", "") : "") + ". Spotify " + clone.versionName + "."
            : pkg + " is another app, signed with another key.");
        Look.label(cloneInstall, cloneOurs ? "Update clone" : "Install clone");
        look.enable(cloneInstall, problem == null && (clone == null || cloneOurs) && patch.isEnabled());
        cloneUninstall.setVisibility(cloneOurs ? View.VISIBLE : View.GONE);
        look.enable(cloneUninstall, !busy);
        File icon = cloneIconFile();
        if (icon.isFile()) cloneIcon.setImageBitmap(android.graphics.BitmapFactory.decodeFile(icon.getPath()));
        else cloneIcon.setImageDrawable(spotifyIcon());
        cloneIconReset.setVisibility(icon.isFile() ? View.VISIBLE : View.INVISIBLE);
        clonePkg.setEnabled(!busy);
        cloneName.setEnabled(!busy);
    }

    /** Spotify's launcher icon as installed, or the manager's own when Spotify isn't. */
    private android.graphics.drawable.Drawable spotifyIcon() {
        PackageInfo info = spotify();
        return info != null ? info.applicationInfo.loadIcon(getPackageManager()) : getApplicationInfo().loadIcon(getPackageManager());
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
                    if (autoUpdate && !busy && patch.isEnabled()) patch(); // from the notification
                    autoUpdate = false;
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
        if (request == PICK_ICON) {
            work("Making the icon", () -> {
                android.graphics.Bitmap picture = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(getContentResolver(), uri), (d, info, s) -> {
                    d.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    int side = Math.min(info.getSize().getWidth(), info.getSize().getHeight());
                    if (side > 1024) d.setTargetSampleSize(side / 1024); // big photos: no need for more than the icon shows
                });
                byte[] png = iconPng(picture);
                File tmp = new File(getFilesDir(), "clone_icon.png.part");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(png);
                }
                if (!tmp.renameTo(cloneIconFile())) throw new java.io.IOException("Couldn't save the icon.");
                done("Icon chosen. It's used the next time you install the clone.");
            });
        } else if (request == PICK_APKM) {
            work("Copying the APKM", () -> {
                File tmp = new File(getFilesDir(), "spotify.apkm.part");
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
                    Bundle.copy(in, out);
                }
                if (!tmp.renameTo(apkm())) throw new java.io.IOException("Couldn't save the APKM.");
                prefs.edit().putBoolean(DOWNLOADED, false).apply();
                replaceImport = false;
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
        patch(null);
    }

    /** Installs Spatch, or with a clone, a second Spatch under the clone's package. */
    private void patch(Clone clone) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            say("Allow Spatch Manager to install apps, then tap Install again.");
            return;
        }
        final Releases.Release release = releases.bundle(nightly.isChecked());
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
                if (clone != null && !b.clonable()) throw new java.io.IOException("These patches can't make a clone yet; clones need patches from Spatch 0.8.1 or later.");
                inheriting = clone == null && keepsSplits(b);
                File input = input(b);
                unsigned = input != null ? b.apply(input, dir, inheriting, clone, this::step) : fromInstalled(b, dir, inheriting, clone);
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
                pendingKey = clone == null ? INSTALLED : CLONE_INSTALLED + clone.pkg;
                pendingDone = clone == null ? "Spatch is installed. Open Spotify to use it." : clone.label + " is installed. Find it in your apps.";
                installingSelf = false;
            });
            Installer.install(this, signed, inheriting ? SPOTIFY : null);
        }, true);
    }

    /**
     * The APKM to patch, or null to patch the installed Spotify instead. In order: a saved APKM
     * that matches the bundle, an unmodified installed Spotify of the right version (or its kept
     * original), then a download from the invite's source. A chosen APKM that doesn't match is never
     * replaced silently: the first tap explains, a second one downloads over it.
     */
    private File input(spatch.manager.Bundle b) throws Exception {
        File saved = apkm();
        Sources.Invite source = invite();
        if (saved.isFile()) {
            step("Checking the saved APKM", 0, 1);
            if (spatch.manager.Bundle.sha256(saved).equals(b.apkmSha)) return saved;
            if (!prefs.getBoolean(DOWNLOADED, false) && !replaceImport) {
                if (source == null) throw new java.io.IOException("The APKM you chose isn't the Spotify " + b.spotify + " one these patches need. Choose that one.");
                replaceImport = true;
                throw new java.io.IOException("The APKM you chose isn't the Spotify " + b.spotify + " one these patches need. Tap Install again to download the right one from " + source.host() + " instead.");
            }
        }
        if (installedOriginal(b)) return null;
        if (source == null) throw new java.io.IOException("Choose the Spotify " + b.spotify + " APKM, or add an invite to a Spatch source.");
        step("Asking " + source.host() + " for Spotify " + b.spotify, 0, 1);
        Sources.Entry entry = Sources.find(source, b);
        Sources.download(source, entry, saved, this::step);
        prefs.edit().putBoolean(DOWNLOADED, true).commit();
        replaceImport = false;
        return saved;
    }

    /** The installed Spotify is the right version and its original base.apk is at hand, kept or still installed. */
    private boolean installedOriginal(spatch.manager.Bundle b) throws java.io.IOException {
        PackageInfo info = spotify();
        if (info == null || info.getLongVersionCode() != b.spotifyCode) return false;
        if (originalBase().isFile()) return true;
        step("Checking the installed Spotify", 0, 1);
        return spatch.manager.Bundle.sha256(new File(info.applicationInfo.sourceDir)).equals(b.baseSha());
    }

    private Sources.Invite invite() {
        return Sources.parse(prefs.getString(INVITE, null));
    }

    /** An invite shared to the manager (long-press the link, Share, Spatch Manager) is saved as it is. */
    private void shared(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("https?://\\S+").matcher(text == null ? "" : text);
        text = link.find() ? link.group() : null; // chat apps share the link with words around it
        Sources.Invite shared = Sources.parse(text);
        if (shared == null) {
            say("That isn't an invite link. It looks like https://…/index.json#name.code.");
            return;
        }
        prefs.edit().putString(INVITE, text.trim()).apply();
        say("Invite saved. Install Spatch downloads Spotify from " + shared.host() + " when it needs to.");
        refresh();
    }

    /** Paste, replace or remove the invite; the clipboard's is offered when it holds one. */
    private void editInvite() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("https://…/index.json#name.code");
        String current = prefs.getString(INVITE, null);
        android.content.ClipboardManager clips = getSystemService(android.content.ClipboardManager.class);
        android.content.ClipData clip = clips == null ? null : clips.getPrimaryClip();
        CharSequence pasted = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).getText() : null;
        field.setText(current != null ? current : pasted != null && Sources.parse(pasted.toString()) != null ? pasted : "");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(look.dp(24), look.dp(8), look.dp(24), 0);
        box.addView(field, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Invite to a Spatch source")
            .setMessage("Whoever runs the source gives you this link. With it, Install Spatch downloads Spotify for you.")
            .setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (d, w) -> {
                if (Sources.parse(field.getText().toString()) == null) {
                    say("That isn't an invite link. It looks like https://…/index.json#name.code.");
                    return;
                }
                prefs.edit().putString(INVITE, field.getText().toString().trim()).apply();
                say("Invite saved. Install Spatch downloads Spotify from " + invite().host() + " when it needs to.");
                refresh();
            });
        if (current != null) dialog.setNeutralButton("Remove", (d, w) -> {
            prefs.edit().remove(INVITE).apply();
            say("Invite removed.");
            refresh();
        });
        dialog.show();
    }

    /** Our own Spatch of the same Spotify version is installed, so its splits (never patched) can stay. */
    private boolean keepsSplits(spatch.manager.Bundle b) {
        PackageInfo info = spotify();
        return !inheritFailed && info != null && keys != null && signedByUs(info) && info.getLongVersionCode() == b.spotifyCode
            && info.applicationInfo.splitNames != null && info.applicationInfo.splitNames.length > 0;
    }

    /**
     * No APKM: the original base.apk kept from an earlier install, or else the installed Spotify's
     * own when it is still unmodified; the splits always come from the installed Spotify.
     */
    private List<File> fromInstalled(spatch.manager.Bundle b, File dir, boolean baseOnly, Clone clone) throws Exception {
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
        if (!baseOnly && info.applicationInfo.splitSourceDirs != null) for (String path : info.applicationInfo.splitSourceDirs) splits.add(new File(path));
        return b.apply(kept, splits, dir, clone, this::step);
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
            Installer.install(this, java.util.Collections.singletonList(apk), null);
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
        return installed(SPOTIFY);
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
    static String variant(String tag, boolean debug) {
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
