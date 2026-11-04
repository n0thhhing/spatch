package spatch;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;

/** Settings' Typography section. A choice previews at once and is applied by a restart. */
final class Typography implements View.OnClickListener, Runnable {
    private static final String DEFAULT_TITLE = "Default", SYSTEM_TITLE = "System";
    private final SpatchSettings page;
    private final LinearLayout parent;
    private final Context context;
    private LinearLayout group, preview;
    private TextView previewHeading, previewTitle, previewArtist, previewBody;
    private final ArrayList<TextView> previewChips = new ArrayList<TextView>();
    private View importRow, resetRow, licenseRow, restartRow;
    private final ArrayList<String[]> shown = new ArrayList<String[]>();

    Typography(SpatchSettings page, LinearLayout parent) {
        this.page = page;
        this.parent = parent;
        context = page.getContext();
        Fonts.sweep();
        FontImport.done = this;
        buildPreview();
        group = page.addGroup();
        run();
    }

    private void buildPreview() {
        preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(page.dp(20), page.dp(18), page.dp(20), page.dp(18));
        preview.setBackground(page.rounded(page.card, page.dp(24)));
        previewHeading = previewText("Made for you", 24f, page.text);
        previewTitle = previewText("Northern Lights", 16f, page.text);
        previewTitle.setPadding(0, page.dp(10), 0, 0);
        previewArtist = previewText("Aurora Bay", 13f, page.subdued);
        previewBody = previewText("Songs you love, and new ones to find. Новые песни, 新しい音楽 🎧", 14f, page.subdued);
        previewBody.setPadding(0, page.dp(8), 0, page.dp(12));
        preview.addView(previewHeading);
        preview.addView(previewTitle);
        preview.addView(previewArtist);
        preview.addView(previewBody);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"All", "Music", "Podcasts"};
        for (int i = 0; i < labels.length; i++) {
            TextView chip = previewText(labels[i], 13f, i == 0 ? page.surface : page.text);
            chip.setPadding(page.dp(14), page.dp(6), page.dp(14), page.dp(6));
            chip.setBackground(page.rounded(i == 0 ? page.accent : page.high, page.dp(i == 0 ? 16 : 10)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(page.dp(6));
            chips.addView(chip, lp);
            previewChips.add(chip);
        }
        preview.addView(chips);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = page.dp(8);
        parent.addView(preview, lp);
    }

    private TextView previewText(String text, float size, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTag(Fonts.PINNED);
        return view;
    }

    private void paintPreview() {
        String id = Fonts.selected();
        previewHeading.setTypeface(face(id, 800));
        previewTitle.setTypeface(face(id, 700));
        previewArtist.setTypeface(face(id, 400));
        previewBody.setTypeface(face(id, 400));
        for (TextView chip : previewChips) chip.setTypeface(face(id, 500));
    }

    private static Typeface face(String id, int weight) {
        Typeface face = Fonts.face(id, weight);
        return face != null ? face : Typeface.DEFAULT;
    }

    /** Rebuilds the list: selection, imports and the restart prompt all change it. */
    public void run() {
        group.removeAllViews();
        shown.clear();
        shown.add(new String[] {Fonts.DEFAULT, DEFAULT_TITLE, "Spotify's own typography"});
        shown.add(new String[] {Fonts.SYSTEM, SYSTEM_TITLE, "Your Android font"});
        for (String[] font : Fonts.BUILT_IN) shown.add(new String[] {font[0], font[1], font[1].startsWith("Monaspace") ? "Monospace" : "Sans serif"});
        for (String[] custom : Fonts.custom()) shown.add(new String[] {custom[0], custom[1], "Custom"});
        String selected = Fonts.selected();
        for (String[] font : shown) {
            boolean on = font[0].equals(selected), custom = font[0].startsWith(Fonts.CUSTOM);
            ImageView check = page.icon(custom ? "more_android" : "check");
            if (!custom) check.setAlpha(on ? 1f : 0f);
            View row = page.createSettingRow(null, font[1], on && custom ? "Custom · Selected" : font[2], check);
            row.setTag(font);
            row.setOnClickListener(this);
            TextView title = (TextView) ((ViewGroup) ((ViewGroup) row).getChildAt(0)).getChildAt(0);
            title.setTag(Fonts.PINNED);
            title.setTypeface(face(font[0], 500));
            row.setContentDescription(font[1] + ", " + font[2] + ", " + (on ? "Selected" : "Not selected"));
            if (custom) {
                check.setClickable(true);
                check.setFocusable(true);
                check.setContentDescription("Manage " + font[1]);
                check.setTag(font);
                check.setOnClickListener(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(page.dp(48), page.dp(48));
                check.setLayoutParams(lp);
            }
            group.addView(row);
        }
        importRow = action("plus", "Import font", ".ttf or .otf, up to 25 MB");
        resetRow = action("repeat", "Reset to default", "Keeps imported fonts");
        licenseRow = action("information_alt", "Font licenses", "SIL Open Font License 1.1");
        if (Fonts.pendingRestart()) restartRow = action("check", "Restart now", "Applies your font everywhere. Later applies on the next start.");
        page.finish(group);
        paintPreview();
        Fonts.restyle(page);
    }

    private View action(String icon, String title, String subtitle) {
        View row = page.createSettingRow(icon, title, subtitle, page.icon("chevron_right"));
        row.setOnClickListener(this);
        row.setContentDescription(title + ", " + subtitle);
        group.addView(row);
        return row;
    }

    private void choose(View v, String id) {
        if (id.equals(Fonts.selected())) return;
        Fonts.select(id);
        Haptics.navigation(v);
        run();
    }

    public void onClick(View v) {
        if (v == importRow) {
            FontImport.start(context);
        } else if (v == resetRow) {
            choose(v, Fonts.DEFAULT);
        } else if (v == restartRow) {
            Palette.restart(context);
        } else if (v == licenseRow) {
            new AlertDialog.Builder(context, Palette.dialogTheme()).setTitle("Font licenses").setMessage(Fonts.licenses()).setPositiveButton("Close", null).show();
        } else if (v instanceof ImageView) {
            manage((String[]) v.getTag());
        } else if (v.getTag() instanceof String[]) {
            choose(v, ((String[]) v.getTag())[0]);
        }
    }

    private void manage(final String[] font) {
        new AlertDialog.Builder(context, Palette.dialogTheme()).setTitle(font[1])
            .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    rename(font);
                }
            })
            .setNegativeButton("Delete", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    boolean active = Fonts.delete(font[0]);
                    run();
                    if (active) page.askRestart("Font deleted", "Spotify is back on Default. Restart now to update the text on screen.");
                }
            })
            .setNeutralButton("Cancel", null)
            .show();
    }

    private void rename(final String[] font) {
        final EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setText(font[1]);
        int pad = page.dp(20);
        LinearLayout box = new LinearLayout(context);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(context, Palette.dialogTheme()).setTitle("Rename font").setView(box)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    String name = FontFile.cleanName(input.getText().toString());
                    if (name == null) return;
                    try {
                        Fonts.rename(font[0], name);
                    } catch (IOException ignored) {} // the old name stays
                    run();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
