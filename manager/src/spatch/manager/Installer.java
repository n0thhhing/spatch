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

/** Installs a set of split APKs in one session; the result comes back as an ACTION broadcast. */
final class Installer {
    static final String ACTION = "spatch.manager.INSTALL_RESULT";

    static void install(Context context, List<File> apks) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
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
