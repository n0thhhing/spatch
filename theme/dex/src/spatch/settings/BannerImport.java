package spatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** System image picker (no storage permission) for ProfileBanner; the image is kept app-private. */
public final class BannerImport extends Fragment implements Runnable {
    private static final int PICK = 0x5f02, MAX_BYTES = 30 * 1024 * 1024, MAX_WIDTH = 1600, QUALITY = 88;
    static Runnable done;
    private Uri uri;
    private volatile String error;

    public BannerImport() {}

    static void start(Context c) {
        while (c instanceof ContextWrapper && !(c instanceof Activity)) c = ((ContextWrapper) c).getBaseContext();
        if (c instanceof Activity) ((Activity) c).getFragmentManager().beginTransaction().add(new BannerImport(), "spatch-banner-import").commit();
    }

    @Override
    public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            finish();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, PICK);
        } catch (RuntimeException e) {
            fail("No file picker is available.");
        }
    }

    @Override
    public void onActivityResult(int request, int result, Intent data) {
        if (request != PICK) return;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        uri = data.getData();
        new Thread(this, "spatch-banner-import").start();
    }

    /** Sample size that brings a width down to the limit; decoding is what bounds memory. */
    static int sample(int width, int limit) {
        int size = 1;
        while (width / (size * 2) >= limit) size *= 2;
        return size;
    }

    public void run() {
        final Activity activity = getActivity();
        if (activity == null) return;
        try {
            store(activity);
        } catch (IOException e) {
            error = "That image could not be read.";
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        } catch (RuntimeException | OutOfMemoryError e) {
            error = "That image cannot be used.";
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (error != null) {
                    fail(error);
                    return;
                }
                ProfileBanner.changed();
                if (done != null) done.run();
                finish();
            }
        });
    }

    private void store(Context context) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no stream");
            byte[] buffer = new byte[16384];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (all.size() + n > MAX_BYTES) throw new IllegalArgumentException("That image is over the 30 MB limit.");
                all.write(buffer, 0, n);
            }
        }
        byte[] bytes = all.toByteArray();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("That file is not an image.");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample(bounds.outWidth, MAX_WIDTH);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IllegalArgumentException("That file is not an image.");
        File target = ProfileBanner.file(), partial = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(partial)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) throw new IOException("not written");
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IOException("not moved");
        }
    }

    private void fail(String message) {
        Activity activity = getActivity();
        if (activity != null) new AlertDialog.Builder(activity, Palette.dialogTheme()).setTitle("Banner not added").setMessage(message).setPositiveButton("OK", null).show();
        finish();
    }

    private void finish() {
        if (getFragmentManager() != null) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
    }
}
