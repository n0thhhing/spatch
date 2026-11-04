package spatch;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/** The Developer options page, present only in builds patched with --debug. */
public final class DevOptions implements View.OnClickListener, DialogInterface.OnClickListener {
    private static final String CRASH_FILE = "spatch_last_crash.txt";
    private static final int LOG_CHARS = 8000;
    static File crashFile;

    private final SpatchSettings page;
    private final Context context;
    private final TextView status, results, crash, log;
    private final View run, copyReport, refreshLog, copyLog, clearCrash, testCrash, restart, frames, inspect, sampler;
    private final M3Switch framesSwitch, inspectSwitch, samplerSwitch;
    private String report = "Not run yet";

    /** Called from Settings.load. Records an uncaught crash, then lets Android handle it as usual. */
    public static void start(Context c) {
        File dir = c.getFilesDir();
        if (dir == null) return;
        crashFile = new File(dir, CRASH_FILE);
        final Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable error) {
                try (FileOutputStream out = new FileOutputStream(crashFile)) {
                    out.write(trace(thread, error).getBytes("UTF-8"));
                } catch (IOException | RuntimeException unwritable) {
                    // never let the recorder replace the crash being reported
                }
                if (next != null) next.uncaughtException(thread, error);
            }
        });
    }

    static String trace(Thread thread, Throwable error) {
        StringWriter text = new StringWriter();
        text.write("Thread " + thread.getName() + "\n");
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    public static void install(SpatchSettings page, LinearLayout categories) {
        new DevOptions(page, categories);
    }

    private DevOptions(SpatchSettings page, LinearLayout categories) {
        this.page = page;
        context = page.getContext();
        View root = page.category(categories, "edit", "Developer options", "Debug build: tests, logs and inspection", "Developer options");

        page.addSectionHeader("Self tests");
        LinearLayout tests = page.addGroup();
        run = row(tests, "check", "Run all tests", "Checks this install from the inside");
        copyReport = row(tests, "copy_alt", "Copy report", "Status, changed settings, tests and the last crash");
        page.finish(tests);
        results = block("Not run yet");

        page.addSectionHeader("Live tools");
        LinearLayout live = page.addGroup();
        framesSwitch = new M3Switch(context);
        frames = toggle(live, framesSwitch, "time", "Frame monitor", "Frame rate, slow frames, memory and audio, over the app");
        samplerSwitch = new M3Switch(context);
        sampler = toggle(live, samplerSwitch, "time", "Main thread sampler", "Logs where the main thread spends its time to logcat");
        inspectSwitch = new M3Switch(context);
        inspect = toggle(live, inspectSwitch, "search", "Touch inspector", "Shows the view under your finger. Compose content shows as one view.");
        page.finish(live);

        page.addSectionHeader("Status");
        status = block("");

        page.addSectionHeader("Last crash");
        crash = block("");
        LinearLayout crashes = page.addGroup();
        clearCrash = row(crashes, "delete", "Clear crash record", null);
        testCrash = row(crashes, "exclamation_circle", "Crash on purpose", "Tests the recorder. Spotify closes.");
        page.finish(crashes);

        page.addSectionHeader("Log");
        LinearLayout logs = page.addGroup();
        refreshLog = row(logs, "repeat", "Read warnings and errors", "Spotify's own recent log. It can include account details.");
        copyLog = row(logs, "copy_alt", "Copy log", null);
        page.finish(logs);
        log = block("Not read yet");

        page.addSectionHeader("App");
        LinearLayout app = page.addGroup();
        restart = row(app, "repeat", "Restart Spotify", null);
        page.finish(app);
        page.finishCategory(root);
        refresh();
    }

    private View row(LinearLayout group, String icon, String title, String subtitle) {
        View row = page.createSettingRow(icon, title, subtitle, page.icon("chevron_right"));
        row.setOnClickListener(this);
        row.setContentDescription(subtitle == null ? title : title + ", " + subtitle);
        group.addView(row);
        return row;
    }

    private View toggle(LinearLayout group, M3Switch toggle, String icon, String title, String subtitle) {
        View row = page.createSettingRow(icon, title, subtitle, toggle);
        row.setOnClickListener(this);
        row.setContentDescription(title + ", Off");
        group.addView(row);
        return row;
    }

    /** Plain text on a card; everything here can run long, so it wraps and scrolls with the page. */
    private TextView block(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(12f);
        view.setTextColor(page.text);
        view.setTypeface(Fonts.settings(400));
        view.setPadding(page.dp(16), page.dp(12), page.dp(16), page.dp(12));
        view.setBackground(page.rounded(page.card, page.dp(16)));
        page.currentPage().addView(view);
        return view;
    }

    private String statusText() {
        StringBuilder out = new StringBuilder();
        out.append("Spotify 9.1.98.521, debug build\n");
        out.append("Theme ").append(Palette.activeName());
        if (!Palette.activeName().equals(Palette.requested())) out.append(", ").append(Palette.requested()).append(" after restart");
        out.append('\n');
        out.append("Android API ").append(Build.VERSION.SDK_INT).append(" on ").append(Build.MODEL).append('\n');
        android.util.DisplayMetrics screen = context.getResources().getDisplayMetrics();
        out.append(screen.widthPixels).append(" x ").append(screen.heightPixels).append(" px at ").append(screen.density).append("x\n");
        out.append("Animations ").append(Wave.animationsEnabled() ? "on" : "off").append('\n');
        out.append("Audio permission ").append(context.checkSelfPermission(AudioSpectrum.PERMISSION) == 0 ? "granted" : "not granted").append('\n');
        out.append("Audio session ").append(AudioSpectrum.session());
        if (AudioSpectrum.SHARED.status != null) out.append(", ").append(AudioSpectrum.SHARED.status);
        out.append('\n');
        out.append("Home sections ").append(Content.onHome().size()).append('\n');
        return out.toString();
    }

    /** Only what differs from defaults, which is what explains a bug report. */
    static String changedSettings() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Settings.KEYS.length; i++) {
            if (Settings.get(i) != Settings.DEFAULTS[i]) out.append(Settings.KEYS[i]).append(" = ").append(Settings.get(i)).append('\n');
        }
        for (int i = 0; i < Settings.CHOICES.length; i++) {
            if (!Settings.choice(i).equals(Settings.CHOICES[i][0])) out.append(Settings.CHOICE_KEYS[i]).append(" = ").append(Settings.choice(i)).append('\n');
        }
        return out.length() == 0 ? "All defaults\n" : out.toString();
    }

    private static String lastCrash() {
        if (crashFile == null || !crashFile.isFile()) return "None recorded";
        try (FileInputStream in = new FileInputStream(crashFile)) {
            byte[] bytes = new byte[(int) Math.min(crashFile.length(), 20000)];
            int read = in.read(bytes);
            return new String(bytes, 0, Math.max(0, read), "UTF-8");
        } catch (IOException e) {
            return "Unreadable: " + e;
        }
    }

    private void refresh() {
        status.setText(statusText() + "\nChanged settings\n" + changedSettings());
        crash.setText(lastCrash());
    }

    private void runTests() {
        final List<DevTests.Result> outcome = DevTests.run(context, page.getRootView());
        show(outcome, "\nTimed tests running, hands off...\n");
        Haptics.toggle(run, true);
        DevTests.timed(context, page.getRootView(), outcome, new Runnable() {
            public void run() {
                show(outcome, "");
                refresh();
            }
        });
    }

    private void show(List<DevTests.Result> outcome, String note) {
        StringBuilder out = new StringBuilder(DevTests.summary(outcome)).append(note).append("\n\n");
        for (DevTests.Result r : outcome) if (r.failed()) out.append(r).append('\n');
        for (DevTests.Result r : outcome) if (!r.failed()) out.append(r).append('\n');
        report = out.toString();
        if (note.isEmpty()) for (String line : report.split("\n")) android.util.Log.i("SpatchTest", line);
        results.setText(report);
    }

    private void copy(String label, String text) {
        ((ClipboardManager) context.getSystemService("clipboard")).setPrimaryClip(ClipData.newPlainText(label, text));
    }

    /** logcat limited to this process; run off the main thread because it blocks. */
    private void readLog() {
        log.setText("Reading...");
        new Thread(new Runnable() {
            public void run() {
                final String text = logcat();
                log.post(new Runnable() {
                    public void run() {
                        log.setText(text);
                    }
                });
            }
        }, "spatch-dev-log").start();
    }

    private static String logcat() {
        StringBuilder out = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-t", "400", "--pid=" + android.os.Process.myPid(), "*:W"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) out.append(line).append('\n');
            }
        } catch (IOException e) {
            return "Could not read the log: " + e;
        }
        int start = Math.max(0, out.length() - LOG_CHARS);
        return out.length() == 0 ? "Nothing logged" : out.substring(start);
    }

    public void onClick(View v) {
        if (v == run) {
            runTests();
        } else if (v == copyReport) {
            copy("Spatch report", statusText() + "\nChanged settings\n" + changedSettings() + "\nTests\n" + report + "\nLast crash\n" + lastCrash());
        } else if (v == sampler) {
            samplerSwitch.toggle();
            DevSampler.set(samplerSwitch.isChecked());
        } else if (v == frames || v == inspect) {
            ((v == frames) ? framesSwitch : inspectSwitch).toggle();
            ViewGroup root = (ViewGroup) page.getRootView();
            DevOverlay.set(root, framesSwitch.isChecked(), inspectSwitch.isChecked());
            frames.setContentDescription("Frame monitor, " + (framesSwitch.isChecked() ? "On" : "Off"));
            inspect.setContentDescription("Touch inspector, " + (inspectSwitch.isChecked() ? "On" : "Off"));
        } else if (v == refreshLog) {
            readLog();
        } else if (v == copyLog) {
            copy("Spotify log", String.valueOf(log.getText()));
        } else if (v == clearCrash) {
            if (crashFile != null) crashFile.delete();
            refresh();
        } else if (v == testCrash) {
            new AlertDialog.Builder(context, Palette.dialogTheme()).setTitle("Crash Spotify now?").setMessage("It closes straight away. Reopen it to see the record here.")
                .setPositiveButton("Crash", this).setNegativeButton("Cancel", this).show();
        } else if (v == restart) {
            Palette.restart(context);
        }
    }

    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) throw new IllegalStateException("Crash on purpose from Developer options");
        dialog.dismiss();
    }
}
