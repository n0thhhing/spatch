package spatch.manager;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Installs a set of split APKs in one session; the result comes back as an ACTION broadcast. With
 * inherit set, the session replaces only the given APKs of that installed package and keeps the rest.
 * On Android 12+ an app this manager installed updates without the confirmation screen.
 */
final class Installer {
    static final String ACTION = "spatch.manager.INSTALL_RESULT";

    static void install(Context context, List<File> apks, String inherit) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(inherit != null
            ? PackageInstaller.SessionParams.MODE_INHERIT_EXISTING : PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (inherit != null) params.setAppPackageName(inherit);
        if (android.os.Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            for (File apk : apks) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                    Bundle.copy(in, out);
                    session.fsync(out);
                }
            }
            Intent result = new Intent(ACTION).setPackage(context.getPackageName());
            PendingIntent sender = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(sender.getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(id);
            throw e;
        }
    }
}
