package spatch.manager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Looks for new patches twice a day while the phone has a network. When there are some for the
 * Spatch this manager installed, it posts one notification per release; tapping it opens the
 * manager straight into the update.
 */
public final class UpdateCheck extends JobService {
    private static final int JOB = 1;
    private static final long EVERY = 12L * 60 * 60 * 1000;
    private static final String CHANNEL = "updates", NOTIFIED = "notified_bundle";

    static void schedule(Context context, boolean on) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (!on) {
            jobs.cancel(JOB);
            return;
        }
        if (jobs.getPendingJob(JOB) != null) return;
        jobs.schedule(new JobInfo.Builder(JOB, new ComponentName(context, UpdateCheck.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(EVERY)
            .setPersisted(true)
            .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                check();
            } catch (Exception offline) {
                // the next run tries again
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private void check() throws Exception {
        SharedPreferences prefs = getSharedPreferences("manager", MODE_PRIVATE);
        String installed = prefs.getString(MainActivity.INSTALLED, null);
        if (installed == null) return; // nothing installed by this manager yet
        Releases.Release bundle = Releases.fetch().bundle;
        if (bundle == null) return;
        String wanted = MainActivity.variant(bundle.tag, prefs.getBoolean(MainActivity.DEBUG, false));
        if (wanted.equals(installed) || wanted.equals(prefs.getString(NOTIFIED, null))) return;
        prefs.edit().putString(NOTIFIED, wanted).apply();
        NotificationManager notes = getSystemService(NotificationManager.class);
        notes.createNotificationChannel(new NotificationChannel(CHANNEL, "Spatch updates", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = getResources().getIdentifier("ic_launcher_foreground", "drawable", getPackageName());
        notes.notify(JOB, new Notification.Builder(this, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle("Spatch " + Releases.version(bundle) + " is ready")
            .setContentText("Tap to update")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build());
    }
}
