package spatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * Import font: system document picker (no storage permission), then validation and a preview dialog.
 * Accepted files go to cache first and reach private storage only on Add; a headless framework
 * Fragment receives the result because the settings page is a view inside Spotify's activity.
 */
public final class FontImport extends Fragment implements Runnable, DialogInterface.OnClickListener, View.OnClickListener {
    private static final int PICK = 0x5f01, BUFFER = 16384;
    private static final String[] TYPES = {"font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream"};
    private static final int[] WEIGHTS = {400, 500, 700};
    private static final String[] WEIGHT_NAMES = {"Regular", "Medium", "Bold"};

    /** Survives between pickers while the user adds more weights. */
    private static final TreeMap<Integer, File> picked = new TreeMap<Integer, File>();
    private static String suggested;
    private static boolean variable;

    static Runnable done; // Typography: refresh after a save
    private final ArrayList<Uri> uris = new ArrayList<Uri>();
    private volatile String error;
    private EditText name;
    private AlertDialog dialog;

    public FontImport() {}

    private static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    static void start(Context context) {
        Activity activity = activity(context);
        if (activity == null) return;
        activity.getFragmentManager().beginTransaction().add(new FontImport(), "spatch-font-import").commit();
    }

    @Override
    public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            finish(); // the process was recreated mid-pick; nothing of the import survived
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, TYPES);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, PICK);
        } catch (RuntimeException e) {
            fail("No file picker is available.");
        }
    }

    @Override
    public void onActivityResult(int request, int result, Intent data) {
        if (request != PICK) return;
        if (result != Activity.RESULT_OK || data == null) { // canceled: the selection never changed
            if (picked.isEmpty()) finish();
            else review();
            return;
        }
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        new Thread(this, "spatch-font-import").start();
    }

    /** Worker: copy with a hard limit (the provider's size is not trusted), then validate. */
    public void run() {
        Activity activity = getActivity();
        if (activity == null) return;
        try {
            for (Uri uri : uris) accept(activity, uri);
        } catch (IOException e) {
            error = "That file could not be read.";
        } catch (RuntimeException e) {
            error = e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : "That font cannot be used on this device.";
        }
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (error != null) fail(error);
                else review();
            }
        });
    }

    private void accept(Context context, Uri uri) throws IOException {
        File file = File.createTempFile("spatch-font-", ".tmp", context.getCacheDir());
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IOException("no stream");
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER];
            for (int n; (n = in.read(buffer)) != -1; ) {
                if (all.size() + n > FontFile.MAX_BYTES) throw new IllegalArgumentException("That font is over the 25 MB limit.");
                all.write(buffer, 0, n);
            }
            bytes = all.toByteArray();
            out.write(bytes);
        } catch (IOException | RuntimeException e) {
            file.delete();
            throw e;
        }
        try {
            FontFile info = FontFile.parse(bytes);
            new Font.Builder(file).build(); // Android's own loader has the last word
            synchronized (picked) {
                if (info.variable || variable) {
                    clear();
                    variable = info.variable;
                }
                int weight = info.variable ? 400 : nearest(info.weight);
                File old = picked.put(weight, file);
                if (old != null) old.delete();
                if (suggested == null) suggested = info.family;
            }
        } catch (IOException | RuntimeException e) {
            file.delete();
            throw e;
        }
    }

    private static int nearest(int weight) {
        return weight < 450 ? 400 : weight < 600 ? 500 : 700;
    }

    private static void clear() {
        for (File file : picked.values()) file.delete();
        picked.clear();
        suggested = null;
        variable = false;
    }

    private void fail(String message) {
        Activity activity = getActivity();
        if (activity != null) {
            new AlertDialog.Builder(activity, 0x010302d1).setTitle("Font not imported").setMessage(message).setPositiveButton("OK", null).show();
        }
        clear();
        finish();
    }

    private void finish() {
        if (getFragmentManager() != null) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
    }

    /** Preview, family name and the weights chosen so far. */
    private void review() {
        Activity activity = getActivity();
        if (activity == null || picked.isEmpty()) return;
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad / 2, pad, 0);

        TextView sample = new TextView(activity);
        sample.setText("Listen to the sound of tomorrow\nAaBbCc 0123456789");
        sample.setTextSize(20f);
        sample.setTypeface(preview());
        body.addView(sample);

        name = new EditText(activity);
        name.setSingleLine(true);
        name.setHint("Family name");
        name.setText(suggested == null ? "" : suggested);
        body.addView(name);

        if (!variable) {
            for (int i = 0; i < WEIGHTS.length; i++) {
                TextView row = new TextView(activity);
                File file = picked.get(WEIGHTS[i]);
                row.setText(WEIGHT_NAMES[i] + (file != null ? ": added" : ": uses the nearest weight"));
                row.setTextSize(14f);
                row.setPadding(0, pad / 3, 0, 0);
                body.addView(row);
            }
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, 0x010302d1)
            .setTitle(variable ? "Add variable font" : "Add font")
            .setView(scroll)
            .setPositiveButton("Add", this)
            .setNegativeButton("Cancel", this);
        if (!variable) builder.setNeutralButton("Add another weight", this);
        dialog = builder.create();
        dialog.setCancelable(false);
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(this); // stays open on a bad name
    }

    private static Typeface preview() {
        try {
            File first = picked.firstEntry().getValue();
            FontFamily family = new FontFamily.Builder(new Font.Builder(first).build()).build();
            return new Typeface.CustomFallbackBuilder(family).setStyle(new FontStyle(400, 0)).setSystemFallback("sans-serif").build();
        } catch (IOException | RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    public void onClick(DialogInterface d, int which) {
        if (which == DialogInterface.BUTTON_NEUTRAL) {
            suggested = FontFile.cleanName(name.getText().toString());
            uris.clear();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, TYPES);
            startActivityForResult(intent, PICK);
        } else {
            clear();
            finish();
        }
    }

    /** Add. */
    public void onClick(View v) {
        String family = FontFile.cleanName(name.getText().toString());
        if (family == null) {
            name.setError("Enter a name");
            return;
        }
        try {
            Fonts.select(Fonts.save(family, picked));
        } catch (IOException e) {
            dialog.dismiss();
            fail("The font could not be saved.");
            return;
        }
        picked.clear();
        suggested = null;
        variable = false;
        dialog.dismiss();
        if (done != null) done.run();
        finish();
    }
}
