package spatch.manager;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Four pages under a navigation bar: Spatch (Spotify install, patch profiles, build options, invites),
 * Clones (multi-instance clone manager), Diagnostics (self-test dashboard & crash log inspect),
 * and Changes (releases, display modes, and phone signing key).
 */
public final class MainActivity extends Activity {
    private static final String SPOTIFY = "com.spotify.music", NOTIFY = "notify_updates", INVITE = "source_invite", DOWNLOADED = "apkm_downloaded";
    private static final String PAGE = "page", CLONE_PKG = "clone_package", CLONE_NAME = "clone_name", CLONE_INSTALLED = "clone_installed:";
    static final String INSTALLED = "installed_bundle", DEBUG = "developer_build", NIGHTLY = "nightly_patches", UPDATE = "start_update";
    private static final String PROFILE = "patch_profile", CLONES_SET = "clones_packages";
    private static final int PICK_APKM = 1, EXPORT_KEY = 2, IMPORT_KEY = 3, PICK_ICON = 4;
    private static final String[] PAGES = {"Spatch", "Clones", "Diagnostics", "Changes"};
    private static final String[] PAGE_ICONS = {"music_note", "content_copy", "tune", "system_update"};
    private static final String[] HEADINGS = {"Spatch", "Clones", "Diagnostics", "Changes"};

    enum PresetProfile {
        STANDARD("Standard (Full)", "Dynamic UI, living controls, lyrics, spectrum visualizers, gestures, ad-free experience.", false, false),
        MINIMAL("Minimal (Clean)", "Lightweight & battery friendly. Reduced motion, essential music playback, no visual clutter.", false, false),
        PLAYFUL("Playful (Dynamic)", "Maximum flair: living controls, reactive particle bursts, wave reflections, newest UI commits.", false, true),
        DEV("Developer & Diagnostics", "Developer options enabled in Spotify: runtime self-tests, frame monitor, crash inspect.", true, false);

        final String label, description;
        final boolean debug, nightly;

        PresetProfile(String label, String description, boolean debug, boolean nightly) {
            this.label = label;
            this.description = description;
            this.debug = debug;
            this.nightly = nightly;
        }
    }

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
    private LinearLayout statusCard, changesBox, clonesListBox;
    private Look.StepTracker stepTracker;

    private TextView spotifyStatus, apkmStatus, patchStatus, whatsNew, keyStatus, managerStatus, status, heading, cloneStatus;
    private TextView profileBadge, profileDesc, diagnosticsText, exitsText;
    private Button displayMode, choose, seeChanges, patch, uninstall, managerUpdate, check, refreshChanges, exportKey, importKey, invite;
    private Button chooseProfile, cloneInstall, cloneIconPick, cloneIconReset;
    private Button runDiagnosticsBtn, copyDiagnosticsBtn, refreshExitsBtn;
    private Button openBatterySettingsBtn, openAppDetailsBtn, launchSpotifyBtn;
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
            stepTracker.setVisibility(View.GONE);
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
    protected void attachBaseContext(android.content.Context context) {
        String mode=context.getSharedPreferences("manager",MODE_PRIVATE).getString("theme_mode","system");
        if(!mode.equals("system")){
            android.content.res.Configuration config=new android.content.res.Configuration(context.getResources().getConfiguration());
            config.uiMode=(config.uiMode&~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                |(mode.equals("dark")?android.content.res.Configuration.UI_MODE_NIGHT_YES:android.content.res.Configuration.UI_MODE_NIGHT_NO);
            context=context.createConfigurationContext(config);
        }
        super.attachBaseContext(context);
    }

    private boolean appearancePending;
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config){
        super.onConfigurationChanged(config);
        if(prefs!=null && prefs.getString("theme_mode","system").equals("system")
            && look.dark!=((config.uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES)){
            if(busy)appearancePending=true;else recreate();
        }
    }

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
        stepTracker = look.stepTracker();
        stepTracker.setVisibility(View.GONE);
        statusCard.addView(stepTracker, look.spaced(10));
        progress = look.progress();
        progress.setVisibility(View.GONE);
        statusCard.addView(progress, look.spaced(8));
        statusCard.setVisibility(View.GONE);
        page.addView(statusCard, look.spaced(12));

        pages = new LinearLayout[PAGES.length];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = look.column();
            page.addView(pages[i]);
        }

        // --- Page 0: Spatch ---
        TextView subtitle = look.text("Patches Spotify on this phone and signs it with this phone's own key.", 15, look.onSurfaceVariant, false);
        subtitle.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        pages[0].addView(subtitle);

        LinearLayout manager = look.card(pages[0], "Spatch Manager");
        managerStatus = look.text("", 14, look.onSurfaceVariant, false);
        manager.addView(managerStatus, look.spaced(6));
        managerUpdate = look.button("Update manager", "system_update", Look.Kind.FILLED, v -> updateManager());
        manager.addView(managerUpdate, look.spaced(10));
        check = look.button("Check for updates", "refresh", Look.Kind.TEXT, v -> checkUpdates());
        manager.addView(check, look.spaced(4));

        LinearLayout spotify = look.card(pages[0], "Spotify");
        spotifyStatus = look.text("", 16, look.onSurface, false);
        spotify.addView(spotifyStatus);
        apkmStatus = look.text("", 14, look.onSurfaceVariant, false);
        spotify.addView(apkmStatus, look.spaced(6));
        patchStatus = look.text("", 14, look.onSurfaceVariant, false);
        spotify.addView(patchStatus, look.spaced(2));
        profileBadge = look.badge("Profile: Standard", look.onPrimaryContainer, look.primaryContainer);
        spotify.addView(profileBadge, look.spaced(6));
        whatsNew = look.text("", 13, look.primary, false);
        whatsNew.setVisibility(View.GONE);
        spotify.addView(whatsNew, look.spaced(4));
        patch = look.button("Install Spatch", "download", Look.Kind.FILLED, v -> patch());
        spotify.addView(patch, look.spaced(12));
        choose = look.button("Choose APKM", "folder_open", Look.Kind.TONAL, v -> pickApkm());
        seeChanges = look.button("What's new", "system_update", Look.Kind.TONAL, v -> show(3));
        spotify.addView(look.row(choose, seeChanges), look.spaced(8));
        invite = look.button("Add an invite", "link", Look.Kind.TEXT, v -> editInvite());
        spotify.addView(invite, look.spaced(4));
        uninstall = look.button("Uninstall Spotify", "delete", Look.Kind.TONAL, v -> startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + SPOTIFY))));
        spotify.addView(uninstall, look.spaced(8));

        LinearLayout profileCard = look.card(pages[0], "Patch profile & options");
        profileDesc = look.text("Dynamic UI, living controls, lyrics, spectrum visualizers, gestures, ad-free experience.", 14, look.onSurfaceVariant, false);
        profileCard.addView(profileDesc);
        chooseProfile = look.button("Patch preset: Standard", "tune", Look.Kind.TONAL, v -> pickProfile());
        profileCard.addView(chooseProfile, look.spaced(10));

        developer = new Switch(this);
        developer.setChecked(prefs.getBoolean(DEBUG, false));
        developer.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(DEBUG, on).apply();
            refresh();
        });
        profileCard.addView(look.switchRow("Developer build", "Adds Developer options: self tests, frame monitor, logs", developer));

        nightly = new Switch(this);
        nightly.setChecked(prefs.getBoolean(NIGHTLY, false));
        nightly.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(NIGHTLY, on).apply();
            refresh();
        });
        profileCard.addView(look.switchRow("Nightly patches", "Built from every change before it's released; less tested", nightly));

        notify = new Switch(this);
        notify.setChecked(prefs.getBoolean(NOTIFY, true));
        notify.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(NOTIFY, on).apply();
            UpdateCheck.schedule(this, on);
            if (on && Build.VERSION.SDK_INT >= 33) requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 0);
        });
        profileCard.addView(look.switchRow("Tell me about new patches", "Checks twice a day; tap the notification to update", notify));

        // --- Page 1: Clones ---
        cloneLayout(pages[1]);

        // --- Page 2: Diagnostics ---
        diagnosticsLayout(pages[2]);

        // --- Page 3: Changes ---
        changesLayout(pages[3]);

        scroll = new ScrollView(this);
        scroll.addView(page);
        nav = look.navBar(PAGES, PAGE_ICONS, this::show);
        LinearLayout root = look.column();
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(nav);
        // Android 15 draws apps edge to edge: keep the page clear of the status bar, the bar clear of navigation.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
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

    private void pickProfile() {
        PresetProfile[] profiles = PresetProfile.values();
        String[] titles = new String[profiles.length];
        String cur = prefs.getString(PROFILE, "standard");
        int sel = 0;
        for (int i = 0; i < profiles.length; i++) {
            titles[i] = profiles[i].label + "\n" + profiles[i].description;
            if (profiles[i].name().equalsIgnoreCase(cur)) sel = i;
        }
        new AlertDialog.Builder(this)
            .setTitle("Patch preset profile")
            .setSingleChoiceItems(titles, sel, (dialog, which) -> {
                PresetProfile p = profiles[which];
                prefs.edit().putString(PROFILE, p.name().toLowerCase()).apply();
                developer.setChecked(p.debug);
                nightly.setChecked(p.nightly);
                dialog.dismiss();
                refresh();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    static final class CloneInfo {
        final String pkg;
        final String label;
        final PackageInfo info;
        final boolean signedByUs;
        final String tag;

        CloneInfo(String pkg, String label, PackageInfo info, boolean signedByUs, String tag) {
            this.pkg = pkg;
            this.label = label;
            this.info = info;
            this.signedByUs = signedByUs;
            this.tag = tag;
        }
    }

    private List<CloneInfo> scanClones() {
        List<CloneInfo> list = new ArrayList<>();
        Set<String> pkgs = new HashSet<>();
        PackageManager pm = getPackageManager();
        try {
            for (PackageInfo pi : pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)) {
                String p = pi.packageName;
                if (p != null && p.startsWith("com.spotify.music.") && !p.equals(SPOTIFY)) {
                    pkgs.add(p);
                }
            }
        } catch (Exception ignored) {}
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(CLONE_INSTALLED)) {
                pkgs.add(key.substring(CLONE_INSTALLED.length()));
            }
        }
        for (String p : prefs.getStringSet(CLONES_SET, Collections.emptySet())) {
            pkgs.add(p);
        }
        String lastPkg = prefs.getString(CLONE_PKG, null);
        if (lastPkg != null && !lastPkg.equals(SPOTIFY)) pkgs.add(lastPkg);

        List<String> sorted = new ArrayList<>(pkgs);
        Collections.sort(sorted);
        for (String p : sorted) {
            PackageInfo pi = installed(p);
            String label = p;
            if (pi != null) {
                try {
                    CharSequence cs = pm.getApplicationLabel(pi.applicationInfo);
                    if (cs != null && cs.length() > 0) label = cs.toString();
                } catch (Exception ignored) {}
            } else if (p.equals(prefs.getString(CLONE_PKG, ""))) {
                label = prefs.getString(CLONE_NAME, p);
            }
            boolean ours = pi != null && keys != null && signedByUs(pi);
            String tag = prefs.getString(CLONE_INSTALLED + p, null);
            list.add(new CloneInfo(p, label, pi, ours, tag));
        }
        return list;
    }

    private String nextClonePkg() {
        String[] suggestions = {"two", "three", "four", "five", "six", "work", "alt"};
        for (String s : suggestions) {
            String candidate = "com.spotify.music." + s;
            if (installed(candidate) == null) return candidate;
        }
        return "com.spotify.music.clone" + (System.currentTimeMillis() % 1000);
    }

    private void cloneLayout(LinearLayout page) {
        TextView note = look.text("Manage multiple isolated Spotify instances on this phone. Each clone has its own separate login, local downloads, and launcher icon.", 15, look.onSurfaceVariant, false);
        note.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        page.addView(note);

        LinearLayout listCard = look.card(page, "Installed Clones");
        clonesListBox = look.column();
        listCard.addView(clonesListBox);

        LinearLayout createCard = look.card(page, "Create New Clone");
        cloneStatus = look.text("", 14, look.onSurfaceVariant, false);
        createCard.addView(cloneStatus, look.spaced(2));

        cloneName = new EditText(this);
        cloneName.setText(prefs.getString(CLONE_NAME, "Spotify Two"));
        cloneName.setHint("Spotify Two");
        createCard.addView(look.field("App name", cloneName), look.spaced(12));

        clonePkg = new EditText(this);
        String suggestedPkg = prefs.getString(CLONE_PKG, nextClonePkg());
        clonePkg.setText(suggestedPkg);
        clonePkg.setHint("com.spotify.music.two");
        clonePkg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        clonePkg.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                refresh();
            }
        });
        createCard.addView(look.field("Package name", clonePkg), look.spaced(10));

        TextView iconLabel = look.text("Icon", 13, look.onSurfaceVariant, true);
        createCard.addView(iconLabel, look.spaced(10));
        cloneIcon = new android.widget.ImageView(this);
        cloneIcon.setClipToOutline(true);
        cloneIcon.setBackground(look.shape(look.containerHigh, 28));
        cloneIconPick = look.button("Choose", "image", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_ICON));
        cloneIconReset = look.button("Spotify's", Look.Kind.TEXT, v -> {
            cloneIconFile().delete();
            refresh();
        });
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(look.dp(56), look.dp(56));
        iconSize.setMarginEnd(look.dp(16));
        iconRow.addView(cloneIcon, iconSize);
        iconRow.addView(look.row(cloneIconPick, cloneIconReset), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        createCard.addView(iconRow, look.spaced(6));

        cloneInstall = look.button("Install clone", "content_copy", Look.Kind.FILLED, v -> installClone());
        createCard.addView(cloneInstall, look.spaced(16));
    }

    private void renderClones() {
        if (clonesListBox == null) return;
        clonesListBox.removeAllViews();
        List<CloneInfo> clones = scanClones();
        int installedCount = 0;
        for (CloneInfo c : clones) {
            if (c.info == null) continue;
            installedCount++;
            LinearLayout item = look.column();
            item.setPadding(look.dp(16), look.dp(12), look.dp(16), look.dp(12));
            item.setBackground(look.shape(look.containerHigh, 20, Look.blend(look.containerHigh, look.outline, 0.22f), 1f));

            LinearLayout header = new LinearLayout(this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setClipToOutline(true);
            icon.setBackground(look.shape(look.container, 14));
            try {
                icon.setImageDrawable(c.info.applicationInfo.loadIcon(getPackageManager()));
            } catch (Exception e) {
                icon.setImageDrawable(spotifyIcon());
            }
            header.addView(icon, new LinearLayout.LayoutParams(look.dp(44), look.dp(44)));

            LinearLayout words = look.column();
            words.setPadding(look.dp(12), 0, look.dp(8), 0);
            words.addView(look.text(c.label, 16, look.onSurface, true));
            String sub = c.pkg + " • " + c.info.versionName + (c.tag != null ? " (" + c.tag.replaceFirst("^(patches-v|bundle-)", "") + ")" : "");
            words.addView(look.text(sub, 13, look.onSurfaceVariant, false));
            header.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView badge = c.signedByUs ? look.badge("Phone Key", look.onTertiaryContainer, look.tertiaryContainer)
                                          : look.badge("Other Key", look.onPrimary, look.primary);
            header.addView(badge);
            item.addView(header);

            Button open = look.button("Open", "open_in_new", Look.Kind.TEXT, v -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage(c.pkg);
                if (launch != null) startActivity(launch);
                else say("Couldn't open " + c.label);
            });
            Button upd = look.button("Update", "system_update", Look.Kind.TONAL, v -> {
                try {
                    File ic = cloneIconFile();
                    patch(new Clone(c.pkg, c.label, ic.isFile() ? java.nio.file.Files.readAllBytes(ic.toPath()) : null));
                } catch (Exception e) {
                    say("Update failed: " + e.getMessage());
                }
            });
            look.enable(upd, !busy && patch.isEnabled());
            Button uninst = look.button("Uninstall", "delete", Look.Kind.TEXT, v -> {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + c.pkg)));
            });
            item.addView(look.row(open, upd, uninst), look.spaced(8));

            clonesListBox.addView(item, look.spaced(8));
        }
        if (installedCount == 0) {
            clonesListBox.addView(look.text("No clones are installed yet. Use the form below to create your first clone.", 14, look.onSurfaceVariant, false));
        }
    }

    private void diagnosticsLayout(LinearLayout page) {
        TextView note = look.text("Verify Spatch installation health, keystore signatures, split APK architecture, and inspect system crash reports.", 15, look.onSurfaceVariant, false);
        note.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        page.addView(note);

        LinearLayout testsCard = look.card(page, "Diagnostics & Self-Tests");
        runDiagnosticsBtn = look.button("Run self-tests", "tune", Look.Kind.FILLED, v -> runDiagnostics());
        copyDiagnosticsBtn = look.button("Copy report", "content_copy", Look.Kind.TONAL, v -> copyDiagnostics());
        testsCard.addView(look.row(runDiagnosticsBtn, copyDiagnosticsBtn));
        diagnosticsText = look.text("Tap 'Run self-tests' to verify signing keys, split packages, and system permissions.", 13, look.onSurfaceVariant, false);
        diagnosticsText.setTypeface(android.graphics.Typeface.MONOSPACE);
        diagnosticsText.setPadding(look.dp(12), look.dp(12), look.dp(12), look.dp(12));
        diagnosticsText.setBackground(look.shape(look.containerHigh, 16, Look.blend(look.containerHigh, look.outline, 0.22f), 1f));
        testsCard.addView(diagnosticsText, look.spaced(10));

        LinearLayout exitsCard = look.card(page, "Spotify Process Exits & Crashes");
        TextView exitsNote = look.text("OS-level historical process terminations from ActivityManager.", 14, look.onSurfaceVariant, false);
        exitsCard.addView(exitsNote);
        exitsText = look.text("Loading process history…", 13, look.onSurfaceVariant, false);
        exitsText.setTypeface(android.graphics.Typeface.MONOSPACE);
        exitsText.setPadding(look.dp(12), look.dp(12), look.dp(12), look.dp(12));
        exitsText.setBackground(look.shape(look.containerHigh, 16, Look.blend(look.containerHigh, look.outline, 0.22f), 1f));
        exitsCard.addView(exitsText, look.spaced(10));
        refreshExitsBtn = look.button("Refresh crash history", "refresh", Look.Kind.TONAL, v -> updateExitReasons());
        exitsCard.addView(refreshExitsBtn, look.spaced(10));

        LinearLayout shortcutsCard = look.card(page, "System Settings & Shortcuts");
        openBatterySettingsBtn = look.button("Battery whitelist", "tune", Look.Kind.TONAL, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                say("Settings unavailable.");
            }
        });
        openAppDetailsBtn = look.button("Spotify details", "open_in_new", Look.Kind.TONAL, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + SPOTIFY)));
            } catch (Exception e) {
                say("App details unavailable.");
            }
        });
        launchSpotifyBtn = look.button("Launch Spotify", "music_note", Look.Kind.TONAL, v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(SPOTIFY);
            if (i != null) startActivity(i);
            else say("Spotify isn't installed.");
        });
        shortcutsCard.addView(look.row(openBatterySettingsBtn, openAppDetailsBtn));
        shortcutsCard.addView(launchSpotifyBtn, look.spaced(8));
    }

    private void runDiagnostics() {
        diagnosticsText.setText("Running self-tests…");
        worker.execute(() -> {
            StringBuilder sb = new StringBuilder();
            int pass = 0, warn = 0, fail = 0;

            if (keys != null) {
                pass++;
                sb.append("PASS Phone Signing Keystore\n");
                try {
                    sb.append("     Fingerprint: ").append(keys.fingerprint().substring(0, 32)).append("\n");
                } catch (Exception e) {
                    sb.append("     Fingerprint: unreadable\n");
                }
            } else {
                fail++;
                sb.append("FAIL Phone Signing Keystore: missing or unreadable\n");
            }

            PackageInfo info = spotify();
            if (info != null) {
                boolean ours = keys != null && signedByUs(info);
                if (ours) {
                    pass++;
                    sb.append("PASS Spotify Signature Match\n");
                    sb.append("     Installed: Spotify ").append(info.versionName).append(" (signed with phone key)\n");
                } else {
                    warn++;
                    sb.append("WARN Spotify Signature Mismatch\n");
                    sb.append("     Installed with different key; import earlier key or reinstall to update\n");
                }
                if (info.applicationInfo != null && info.applicationInfo.splitNames != null && info.applicationInfo.splitNames.length > 0) {
                    pass++;
                    sb.append("PASS Split APK Architecture\n");
                    sb.append("     ").append(info.applicationInfo.splitNames.length).append(" splits installed: ").append(String.join(", ", info.applicationInfo.splitNames)).append("\n");
                } else {
                    warn++;
                    sb.append("WARN No Split APKs detected (monolithic base)\n");
                }
            } else {
                sb.append("INFO Spotify is not currently installed\n");
            }

            File base = originalBase();
            if (base.isFile()) {
                pass++;
                sb.append("PASS Preserved Original base.apk\n");
                sb.append("     ").append(base.length() / (1 << 20)).append(" MB cached for fast updates\n");
            } else {
                sb.append("INFO Original base.apk not cached yet\n");
            }

            File apkm = apkm();
            if (apkm.isFile()) {
                pass++;
                sb.append("PASS Spotify APKM Saved\n");
                sb.append("     ").append(apkm.length() / (1 << 20)).append(" MB available\n");
            } else {
                sb.append("INFO No APKM cached on device\n");
            }

            if (getPackageManager().canRequestPackageInstalls()) {
                pass++;
                sb.append("PASS App Install Permission: Allowed\n");
            } else {
                fail++;
                sb.append("FAIL App Install Permission: Not granted\n");
            }

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                pass++;
                sb.append("PASS Battery Optimization: Unrestricted (background checks active)\n");
            } else {
                warn++;
                sb.append("WARN Battery Optimization: Optimized (Android may delay background checks)\n");
            }

            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
                    pass++;
                    sb.append("PASS Notifications: Granted\n");
                } else {
                    warn++;
                    sb.append("WARN Notifications: Not granted\n");
                }
            }

            if (releases != null && releases.bundle != null) {
                pass++;
                sb.append("PASS Releases API: Connected\n");
                sb.append("     Latest version: ").append(Releases.version(releases.bundle)).append("\n");
            } else if (releaseError != null) {
                warn++;
                sb.append("WARN Releases API: ").append(releaseError).append("\n");
            }

            sb.append("\nResults: ").append(pass).append(" passed, ").append(warn).append(" warnings, ").append(fail).append(" failed");
            String resultText = sb.toString();
            main.post(() -> {
                if (diagnosticsText != null) diagnosticsText.setText(resultText);
            });
        });
    }

    private void copyDiagnostics() {
        android.content.ClipboardManager clips = getSystemService(android.content.ClipboardManager.class);
        if (clips != null && diagnosticsText != null) {
            clips.setPrimaryClip(android.content.ClipData.newPlainText("Spatch Diagnostics", diagnosticsText.getText()));
            say("Diagnostics report copied to clipboard.");
        }
    }

    private void updateExitReasons() {
        if (Build.VERSION.SDK_INT < 30) {
            if (exitsText != null) exitsText.setText("Historical process exit monitoring requires Android 11+ (API 30).");
            return;
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                if (exitsText != null) exitsText.setText("ActivityManager is not available.");
                return;
            }
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(SPOTIFY, 0, 8);
            if (exits == null || exits.isEmpty()) {
                if (exitsText != null) exitsText.setText("No process exits or crashes recorded by the OS for Spotify.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            for (ApplicationExitInfo exit : exits) {
                String reasonStr;
                switch (exit.getReason()) {
                    case ApplicationExitInfo.REASON_CRASH: reasonStr = "CRASH (Java Exception)"; break;
                    case ApplicationExitInfo.REASON_CRASH_NATIVE: reasonStr = "NATIVE CRASH (Signal)"; break;
                    case ApplicationExitInfo.REASON_ANR: reasonStr = "ANR (Freeze / Not Responding)"; break;
                    case ApplicationExitInfo.REASON_LOW_MEMORY: reasonStr = "LOW MEMORY (LMK)"; break;
                    case ApplicationExitInfo.REASON_USER_REQUESTED: reasonStr = "USER REQUESTED (Force stop / Swiped)"; break;
                    case ApplicationExitInfo.REASON_EXIT_SELF: reasonStr = "EXIT SELF (Clean exit)"; break;
                    default: reasonStr = "REASON #" + exit.getReason(); break;
                }
                sb.append("• ").append(sdf.format(new Date(exit.getTimestamp()))).append("\n");
                sb.append("  ").append(reasonStr).append(" (PID ").append(exit.getPid()).append(")\n");
                String desc = exit.getDescription();
                if (desc != null && !desc.trim().isEmpty()) {
                    sb.append("  ").append(desc.trim()).append("\n");
                }
                sb.append("\n");
            }
            if (exitsText != null) exitsText.setText(sb.toString().trim());
        } catch (SecurityException e) {
            if (exitsText != null) {
                exitsText.setText("Cross-app exit history requires android.permission.DUMP.\n\n"
                    + "To grant via ADB:\n"
                    + "adb shell pm grant spatch.manager android.permission.DUMP\n\n"
                    + "Or view Spotify's last crash directly in:\n"
                    + "Spotify -> Settings -> Spatch -> Developer options -> Last crash");
            }
        } catch (Exception e) {
            if (exitsText != null) exitsText.setText("Couldn't read exit history: " + e.getMessage());
        }
    }

    private void changesLayout(LinearLayout page) {
        TextView note = look.text("Recent commits in Nightly and release notes from published Spatch updates.", 15, look.onSurfaceVariant, false);
        note.setPadding(look.dp(8), look.dp(4), look.dp(8), 0);
        page.addView(note);
        refreshChanges = look.button("Refresh", "refresh", Look.Kind.TONAL, v -> checkUpdates());
        Button openGithub = look.button("GitHub releases", "open_in_new", Look.Kind.TONAL, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Releases.PAGE))));
        page.addView(look.row(refreshChanges, openGithub), look.spaced(10));
        changesBox = look.column();
        page.addView(changesBox);

        LinearLayout options = look.card(page, "Appearance");
        displayMode = look.button("Display mode: " + displayModeLabel(), "tune", Look.Kind.TONAL, v -> {
            String[] modes = {"system", "light", "dark", "black"};
            String[] labels = {"System (Dynamic)", "Light", "Dark", "Pitch Black (AMOLED)"};
            int selected = Arrays.asList(modes).indexOf(prefs.getString("theme_mode", "system"));
            if (selected < 0) selected = 0;
            new AlertDialog.Builder(this).setTitle("Display mode").setSingleChoiceItems(labels, selected, (dialog, index) -> {
                prefs.edit().putString("theme_mode", modes[index]).apply();
                dialog.dismiss();
                recreate();
            }).setNegativeButton("Cancel", null).show();
        });
        options.addView(displayMode, look.spaced(6));

        LinearLayout key = look.card(page, "Signing key");
        TextView keyNote = look.text("Spatch and its clones are signed with this phone's key. Export it to keep it safe, or import the key an earlier Spatch was signed with so updates install over it.", 14, look.onSurfaceVariant, false);
        key.addView(keyNote);
        keyStatus = look.text("", 13, look.onSurfaceVariant, false);
        keyStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.addView(keyStatus, look.spaced(8));
        exportKey = look.button("Export", "key", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-pkcs12").putExtra(Intent.EXTRA_TITLE, "spatch-key.p12"), EXPORT_KEY));
        importKey = look.button("Import", "file_open", Look.Kind.TONAL, v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_KEY));
        key.addView(look.row(exportKey, importKey), look.spaced(12));
    }

    private String displayModeLabel() {
        String mode = prefs.getString("theme_mode", "system");
        switch (mode) {
            case "light": return "Light";
            case "dark": return "Dark";
            case "black": return "Pitch Black";
            default: return "System";
        }
    }

    private void renderChanges(String installed) {
        changesBox.removeAllViews();
        if (releases == null) {
            LinearLayout card = look.card(changesBox, "Changes");
            card.addView(look.text(releaseError != null ? releaseError : "Looking for changes…", 14, look.onSurfaceVariant, false));
            return;
        }
        if (!releases.commits.isEmpty() || releases.nightly != null) {
            LinearLayout card = look.card(changesBox, "Nightly commits");
            if (releases.commits.isEmpty()) {
                card.addView(look.text(releases.nightly.tag, 13, look.primary, true));
                if (!releases.nightly.notes.isEmpty()) card.addView(look.text(releases.nightly.notes, 14, look.onSurfaceVariant, false), look.spaced(4));
            } else {
                for (int i = 0; i < releases.commits.size(); i++) {
                    Releases.Note c = releases.commits.get(i);
                    if (i > 0) {
                        View div = new View(this);
                        div.setBackgroundColor(Look.blend(look.container, look.outline, 0.22f));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, look.dp(1)));
                        lp.topMargin = look.dp(10);
                        lp.bottomMargin = look.dp(10);
                        card.addView(div, lp);
                    }
                    boolean onPhone = (installed != null && installed.startsWith(c.tag))
                        || ownName().endsWith("-" + c.tag.replaceFirst("^nightly-", ""));
                    boolean newest = releases.nightly != null ? releases.nightly.tag.equals(c.tag) : i == 0;
                    String meta = c.tag.replaceFirst("^nightly-", "")
                        + (c.date.isEmpty() ? "" : "  •  " + c.date)
                        + (onPhone ? "  •  Installed" : newest ? "  •  Newest nightly" : "");
                    card.addView(look.text(c.title, 15, look.onSurface, true));
                    card.addView(look.text(meta, 13, onPhone || newest ? look.primary : look.onSurfaceVariant, onPhone || newest), look.spaced(2));
                    if (!c.body.isEmpty()) card.addView(look.text(c.body, 13, look.onSurfaceVariant, false), look.spaced(4));
                }
            }
        }
        String own = ownName();
        for (Releases.Note n : releases.notes) {
            LinearLayout card = look.card(changesBox, n.title);
            boolean onPhone = (installed != null && installed.startsWith(n.tag))
                || (n.tag.startsWith("manager-v") && n.tag.substring("manager-v".length()).equals(own));
            String meta = n.tag + (n.date.isEmpty() ? "" : "  •  " + n.date) + (onPhone ? "  •  Installed" : "");
            card.addView(look.text(meta, 13, onPhone ? look.primary : look.onSurfaceVariant, onPhone));
            card.addView(look.text(n.body, 14, look.onSurfaceVariant, false), look.spaced(6));
        }
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
        Set<String> set = new HashSet<>(prefs.getStringSet(CLONES_SET, Collections.emptySet()));
        set.add(pkg);
        prefs.edit().putStringSet(CLONES_SET, set).apply();
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
        if(appearancePending && !busy){appearancePending=false;recreate();return;}
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

        // Profile display
        String profName = prefs.getString(PROFILE, "standard");
        PresetProfile matched = null;
        for (PresetProfile p : PresetProfile.values()) {
            if (p.name().equalsIgnoreCase(profName)) { matched = p; break; }
        }
        boolean isDev = developer.isChecked();
        boolean isNight = nightly.isChecked();
        if (matched != null && (matched.debug != isDev || matched.nightly != isNight)) {
            profName = "custom";
        }
        String pLabel = (matched != null && !profName.equals("custom")) ? matched.label : "Custom";
        if (profileBadge != null) profileBadge.setText("Profile: " + pLabel);
        if (chooseProfile != null) chooseProfile.setText("Patch preset: " + pLabel);
        if (profileDesc != null) profileDesc.setText(matched != null && !profName.equals("custom") ? matched.description : "Custom configuration toggled below.");

        String headline = nightly.isChecked() && releases != null && !releases.commits.isEmpty()
            ? "Latest change: " + releases.commits.get(0).title
            : bundle != null && !bundle.notes.isEmpty() ? "What's new: " + Releases.summary(bundle.notes) : "";
        whatsNew.setText(headline);
        whatsNew.setVisibility(headline.isEmpty() ? View.GONE : View.VISIBLE);
        String wanted = bundle == null ? null : variant(bundle.tag, debug);
        Look.label(patch, !ours ? "Install Spatch" : wanted != null && !wanted.equals(installed) ? (installed != null && installed.startsWith(bundle.tag) ? "Switch build" : "Update Spatch") : "Reinstall Spatch");
        Look.label(choose, apkm.isFile() ? "Another APKM" : "Choose APKM");
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
        Releases.Release mgrUpdate = managerUpdateTarget();
        managerStatus.setText("Spatch Manager " + ownName() + (mgrUpdate != null
            ? " (" + (mgrUpdate.tag.startsWith("nightly-") ? Releases.version(mgrUpdate) : "version " + Releases.version(mgrUpdate)) + " is available)."
            : " is up to date."));
        managerUpdate.setVisibility(mgrUpdate != null ? View.VISIBLE : View.GONE);
        for (Button b : Arrays.asList(displayMode, choose, seeChanges, managerUpdate, check, refreshChanges, importKey, invite, cloneIconPick, cloneIconReset, chooseProfile, runDiagnosticsBtn, copyDiagnosticsBtn, refreshExitsBtn)) {
            if (b != null) look.enable(b, !busy);
        }
        look.enable(exportKey, !busy && keys != null);
        renderChanges(installed);
        renderClones();

        String pkg = clonePkg.getText().toString().trim();
        String problem = Clone.problem(pkg);
        PackageInfo clone = problem == null ? installed(pkg) : null;
        boolean cloneOurs = clone != null && keys != null && signedByUs(clone);
        String cloneTag = prefs.getString(CLONE_INSTALLED + pkg, null);
        cloneStatus.setText(problem != null ? problem
            : clone == null ? "Ready to create " + pkg + "."
            : cloneOurs ? "Installed" + (cloneTag != null ? ", patches " + cloneTag.replaceFirst("^(patches-v|bundle-)", "") : "") + ". Spotify " + clone.versionName + "."
            : pkg + " is another app, signed with another key.");
        Look.label(cloneInstall, cloneOurs ? "Update clone" : "Install clone");
        look.enable(cloneInstall, problem == null && (clone == null || cloneOurs) && patch.isEnabled());
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

    private Releases.Release managerUpdateTarget() {
        if (releases == null) return null;
        if (nightly.isChecked() && releases.nightlyManager != null) {
            String sha = releases.nightlyManager.tag.substring("nightly-".length());
            if (!ownName().endsWith("-" + sha)) return releases.nightlyManager;
        }
        return Releases.code(releases.manager) > ownCode() ? releases.manager : null;
    }

    private void updateManager() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        final Releases.Release release = managerUpdateTarget();
        if (release == null) return;
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
        stepTracker.setStage(0);
        stepTracker.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        refresh();
        worker.execute(() -> {
            try {
                job.run();
                if (install) main.post(() -> {
                    progress.setVisibility(View.GONE);
                    stepTracker.setStage(3);
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
            int stage = 0;
            if (what.contains("Patch") || what.contains("base.apk") || what.contains("Preparing")) stage = 1;
            else if (what.contains("Sign")) stage = 2;
            else if (what.contains("Install") || what.contains("Waiting")) stage = 3;
            stepTracker.setStage(stage);
            stepTracker.setVisibility(View.VISIBLE);
            progress.setIndeterminate(false);
            progress.setMax(total);
            progress.setProgress(done);
        });
    }

    private void done(String message) {
        main.post(() -> {
            busy = false;
            progress.setVisibility(View.GONE);
            stepTracker.setVisibility(View.GONE);
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
