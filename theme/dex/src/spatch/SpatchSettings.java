package spatch;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Spatch settings screen matching Spotify's native layout, typography, controls, and colors.
 * Opened from the profile side drawer; handles system back gesture, hardware back, and header back button.
 */
public final class SpatchSettings extends FrameLayout implements View.OnClickListener, DialogInterface.OnClickListener, Runnable {
    Back back;

    // Indexed like Settings.KEYS: wave, buttons, backdrop, round_art, immersive, shapes, reduce_motion, visualizer.
    private static final String[] FEATURE_TITLES = {
        "Wavy progress",
        "Button animations",
        "Album art backdrop",
        "Circular album art",
        "Double-tap for immersive",
        "Expressive shapes",
        "Reduce motion",
        "Full-screen visualizer"
    };

    private static final String[] FEATURE_SUBTITLES = {
        "The played part of the progress bar waves while music plays",
        "Bounce and nudge feedback on the player controls",
        "Blurred album art behind Now Playing",
        "Round artwork with a slowly turning ring",
        "Double tap the player artwork for a full-screen view",
        "Scalloped play buttons instead of plain circles",
        "Stills Spatch animations: waves, springs and spins",
        "Spectrum of what's playing under the immersive player (uses the microphone permission)"
    };

    /** Section layout: Settings indices per section (the theme list sits at the top of Appearance). */
    private static final int[] APPEARANCE = {3, 2, 5}, PLAYER = {0, 4, 7}, MOTION = {1, 6};

    private static final String[] THEME_TITLES = {
        "Lavender",
        "Gruvbox",
        "Catppuccin Mocha",
        "Rosé Pine",
        "Nord",
        "Tokyo Night",
        "Dracula"
    };

    private static final String[] THEME_SUBTITLES = {
        "Default • M3 Purple",
        "Warm retro orange",
        "Soft pastel mauve",
        "Warm pine & iris",
        "Arctic cold frost blue",
        "Deep neon night blue",
        "Vampire dark purple"
    };

    private static final int[] THEME_ACCENTS = {
        0xFFD0BCFF, // Lavender
        0xFFFE8019, // Gruvbox
        0xFFCBA6F7, // Catppuccin Mocha
        0xFFC4A7E7, // Rosé Pine
        0xFF88C0D0, // Nord
        0xFF7AA2F7, // Tokyo Night
        0xFFBD93F9  // Dracula
    };

    private final View backButton;
    private View resetRow;
    private boolean closing;
    private final Switch[] featureSwitches = new Switch[FEATURE_TITLES.length];
    private final View[] featureRows = new View[FEATURE_TITLES.length];
    private final View[] themeMarks = new View[Palette.NAMES.length];
    private final View[] themeDots = new View[Palette.NAMES.length];
    private final View[] themeRows = new View[Palette.NAMES.length];
    private final float density;

    public SpatchSettings(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        setBackgroundColor(0xff14121c);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);

        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBarHeight = resId > 0 ? context.getResources().getDimensionPixelSize(resId) : dp(32);
        if (statusBarHeight < dp(24)) statusBarHeight = dp(24);

        int navBarId = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        int navBarHeight = navBarId > 0 ? context.getResources().getDimensionPixelSize(navBarId) : dp(16);

        setPadding(0, statusBarHeight, 0, navBarHeight);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header bar (56dp height)
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(16), 0);

        ImageView backBtn = new ImageView(context, null);
        try {
            backBtn.setImageResource(0x7f08008b); // abc_ic_ab_back_material
            backBtn.setColorFilter(0xfff3eeff);
            backBtn.setScaleType(ImageView.ScaleType.CENTER);
        } catch (Throwable ignored) {}
        backBtn.setContentDescription("Back");
        backBtn.setClickable(true);
        backBtn.setFocusable(true);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        header.addView(backBtn, backLp);
        backBtn.setOnClickListener(this);
        this.backButton = backBtn;

        TextView title = new TextView(context);
        title.setText("Spatch");
        title.setTextSize(20f);
        title.setTextColor(0xfff3eeff);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        // Header bottom divider line
        View headerDivider = new View(context);
        headerDivider.setBackgroundColor(0xff2b2640);
        root.addView(headerDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Scrollable content
        ScrollView scroll = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(32));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        ColorStateList featureSwitchTint = createSwitchTint(Palette.color(0xFFD0BCFF));
        for (int i = 0; i < FEATURE_TITLES.length; i++) {
            featureSwitches[i] = new Switch(context);
            featureSwitches[i].setChecked(Settings.get(i));
            applySwitchTint(featureSwitches[i], featureSwitchTint);
            featureSwitches[i].setClickable(false);
            featureSwitches[i].setFocusable(false);
            featureRows[i] = createSettingRow(context, null, FEATURE_TITLES[i], FEATURE_SUBTITLES[i], featureSwitches[i]);
            featureRows[i].setOnClickListener(this);
            describe(featureRows[i], FEATURE_TITLES[i], Settings.get(i) ? "On" : "Off");
        }

        addSectionHeader(content, "Appearance", dp(20));
        addSectionSubtitle(content, "Theme applies after a restart; everything else applies straight away");
        String activeTheme = Palette.requested();
        for (int i = 0; i < Palette.NAMES.length; i++) {
            // Radio mark like Spotify's own one-of-many pickers: a ring, plus a dot when selected.
            FrameLayout mark = new FrameLayout(context);
            themeDots[i] = new View(context);
            mark.addView(themeDots[i], new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER));
            themeMarks[i] = mark;
            View swatch = new View(context);
            GradientDrawable dot = new GradientDrawable();
            dot.setColor(THEME_ACCENTS[i]);
            dot.setCornerRadius(dp(10));
            swatch.setBackground(dot);
            themeRows[i] = createSettingRow(context, swatch, THEME_TITLES[i], themeSubtitle(i), themeMarks[i]);
            themeRows[i].setOnClickListener(this);
            content.addView(themeRows[i]);
        }
        refreshThemes(activeTheme, false);
        for (int i : APPEARANCE) content.addView(featureRows[i]);

        addSectionHeader(content, "Player", dp(28));
        for (int i : PLAYER) content.addView(featureRows[i]);

        addSectionHeader(content, "Motion", dp(28));
        addSectionSubtitle(content, "Android's own animation setting is respected too");
        for (int i : MOTION) content.addView(featureRows[i]);

        addSectionHeader(content, "About", dp(28));
        addSectionSubtitle(content, "Spatch for Spotify 9.1.98.521. Built with the @THEME@ theme as its default.");
        resetRow = createSettingRow(context, null, "Reset to defaults", "Turns every option above back to its default (keeps your theme)", null);
        resetRow.setOnClickListener(this);
        describe(resetRow, "Reset to defaults", "Button");
        content.addView(resetRow);
    }

    /** Slides in from the right; called once the page is attached. */
    void enter() {
        if (!Wave.animationsEnabled()) return;
        setAlpha(0f);
        setTranslationX(density * 48);
        animate().alpha(1f).translationX(0f).setDuration(240).start();
    }

    /** "Active" for the running palette; the saved-but-not-yet-applied one says so. */
    private String themeSubtitle(int i) {
        if (Palette.NAMES[i].equals(Palette.activeName())) return THEME_SUBTITLES[i] + " \u2022 Active";
        if (Palette.NAMES[i].equals(Palette.requested())) return THEME_SUBTITLES[i] + " \u2022 Applies after restart";
        return THEME_SUBTITLES[i];
    }

    private void refreshThemes(String selected, boolean animate) {
        for (int i = 0; i < themeMarks.length; i++) {
            boolean on = Palette.NAMES[i].equals(selected);
            boolean was = themeDots[i].getVisibility() == View.VISIBLE;
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(12));
            ring.setStroke(dp(2), on ? THEME_ACCENTS[i] : 0xffbcb3dc);
            themeMarks[i].setBackground(ring);
            GradientDrawable dot = new GradientDrawable();
            dot.setCornerRadius(dp(6));
            dot.setColor(THEME_ACCENTS[i]);
            themeDots[i].setBackground(dot);
            themeDots[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (on && !was && animate && Wave.animationsEnabled()) {
                themeDots[i].setScaleX(0f);
                themeDots[i].setScaleY(0f);
                themeDots[i].animate().scaleX(1f).scaleY(1f).setDuration(220).start();
            }
            themeMarks[i].setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            themeRows[i].setSelected(on);
            describe(themeRows[i], THEME_TITLES[i], on ? "Selected" : "Not selected");
        }
    }

    /** TalkBack reads the whole row as one control, including its state. */
    private static void describe(View row, String title, String state) {
        row.setContentDescription(title + ", " + state);
    }

    private void onThemeSelected(int index, String themeName) {
        String current = Palette.requested();
        if (themeName.equals(current)) return;

        if (!Palette.choose(themeName)) return; // not saved: leave the selection as it was
        refreshThemes(themeName, true);

        new AlertDialog.Builder(getContext(), 0x010302d1) // android.R.style.Theme_DeviceDefault_Dialog_Alert: system Material dialog, not Holo
            .setTitle("Restart to apply theme?")
            .setMessage("Spotify needs to restart to apply the new color palette.")
            .setPositiveButton("Restart now", this)
            .setNegativeButton("Later", this)
            .show();
    }

    private void addSectionHeader(LinearLayout parent, String title, int topPadding) {
        TextView header = new TextView(getContext());
        header.setText(title);
        header.setTextSize(14f);
        header.setTextColor(0xfff3eeff);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(16), topPadding, dp(16), dp(4));
        parent.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addSectionSubtitle(LinearLayout parent, String subtitle) {
        TextView sub = new TextView(getContext());
        sub.setText(subtitle);
        sub.setTextSize(13f);
        sub.setTextColor(0xffbcb3dc);
        sub.setPadding(dp(16), 0, dp(16), dp(10));
        parent.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View createSettingRow(Context context, View leadingIcon, String titleText, String subtitleText, View switchWidget) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        if (leadingIcon != null) {
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconLp.rightMargin = dp(16);
            row.addView(leadingIcon, iconLp);
        }

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(titleText);
        titleView.setTextSize(16f);
        titleView.setTextColor(0xfff3eeff);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        textLayout.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (subtitleText != null && !subtitleText.isEmpty()) {
            TextView subView = new TextView(context);
            subView.setText(subtitleText);
            subView.setTextSize(13f);
            subView.setTextColor(0xffbcb3dc);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(2);
            textLayout.addView(subView, subLp);
        }

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textLayout, textLp);

        if (switchWidget != null) { // a bare wrap_content View would take the whole row width
            LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            switchLp.leftMargin = dp(12);
            row.addView(switchWidget, switchLp);
        }

        return row;
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }

    private static ColorStateList createSwitchTint(int accentColor) {
        int[][] states = new int[][] {
            new int[] { 0x010100a0 }, // checked
            new int[] { -0x010100a0 } // unchecked
        };
        int[] colors = new int[] { accentColor, 0xff2b2640 };
        return new ColorStateList(states, colors);
    }

    private static void applySwitchTint(Switch s, ColorStateList tint) {
        if (Build.VERSION.SDK_INT >= 23) {
            s.setThumbTintList(tint);
        }
    }

    public void onClick(View v) {
        if (v == backButton) {
            close();
            return;
        }
        if (v == resetRow) {
            Settings.resetAll();
            for (int i = 0; i < featureRows.length; i++) {
                featureSwitches[i].setChecked(Settings.get(i));
                describe(featureRows[i], FEATURE_TITLES[i], Settings.get(i) ? "On" : "Off");
            }
            return;
        }
        for (int i = 0; i < featureRows.length; i++) {
            if (v == featureRows[i]) {
                boolean next = !featureSwitches[i].isChecked();
                featureSwitches[i].setChecked(next);
                Settings.set(i, next);
                describe(featureRows[i], FEATURE_TITLES[i], next ? "On" : "Off");
                return;
            }
        }
        for (int i = 0; i < themeRows.length; i++) {
            if (v == themeRows[i]) {
                onThemeSelected(i, Palette.NAMES[i]);
                return;
            }
        }
    }

    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            Palette.restart(getContext());
        } else {
            dialog.dismiss();
        }
    }

    /** Back callback and the end of the exit animation. */
    public void run() {
        close();
    }

    /** Slides out, then detaches (a second call, from the delayed run(), detaches at once). */
    public void close() {
        if (back != null) {
            back.unregister();
            back = null;
        }
        if (!closing && Wave.animationsEnabled()) {
            closing = true;
            animate().alpha(0f).translationX(density * 48).setDuration(180).start();
            postDelayed(this, 180);
            return;
        }
        ViewParent p = getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(this);
        }
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            close();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return true;
    }
}
