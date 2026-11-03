package spatch.manager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Material 3 colour roles from the phone's wallpaper palette (Android 12+; Material's baseline
 * purple before that), and the few components the manager's one screen is built from.
 */
final class Look {
    enum Kind { FILLED, TONAL, TEXT }

    final boolean dark;
    final int primary, onPrimary, primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer;
    final int surface, container, containerHigh, onSurface, onSurfaceVariant, outline;
    private final Context context;
    private final float density;

    Look(Context context) {
        this.context = context;
        density = context.getResources().getDisplayMetrics().density;
        dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            primary = sys(android.R.color.system_accent1_200, 0xffd0bcff);
            onPrimary = sys(android.R.color.system_accent1_800, 0xff381e72);
            primaryContainer = sys(android.R.color.system_accent1_700, 0xff4f378b);
            onPrimaryContainer = sys(android.R.color.system_accent1_100, 0xffeaddff);
            secondaryContainer = sys(android.R.color.system_accent2_700, 0xff4a4458);
            onSecondaryContainer = sys(android.R.color.system_accent2_100, 0xffe8def8);
            surface = sys(android.R.color.system_neutral1_900, 0xff141218);
            int step = sys(android.R.color.system_neutral1_800, 0xff322f35);
            container = blend(surface, step, 0.35f);
            containerHigh = blend(surface, step, 0.7f);
            onSurface = sys(android.R.color.system_neutral1_100, 0xffe6e0e9);
            onSurfaceVariant = sys(android.R.color.system_neutral2_200, 0xffcac4d0);
            outline = sys(android.R.color.system_neutral2_400, 0xff938f99);
        } else {
            primary = sys(android.R.color.system_accent1_600, 0xff6750a4);
            onPrimary = sys(android.R.color.system_accent1_0, 0xffffffff);
            primaryContainer = sys(android.R.color.system_accent1_100, 0xffeaddff);
            onPrimaryContainer = sys(android.R.color.system_accent1_900, 0xff21005d);
            secondaryContainer = sys(android.R.color.system_accent2_100, 0xffe8def8);
            onSecondaryContainer = sys(android.R.color.system_accent2_900, 0xff1d192b);
            surface = sys(android.R.color.system_neutral1_10, 0xfffef7ff);
            container = sys(android.R.color.system_neutral1_50, 0xfff3edf7);
            containerHigh = sys(android.R.color.system_neutral1_100, 0xffece6f0);
            onSurface = sys(android.R.color.system_neutral1_900, 0xff1d1b20);
            onSurfaceVariant = sys(android.R.color.system_neutral2_700, 0xff49454f);
            outline = sys(android.R.color.system_neutral2_500, 0xff79747e);
        }
    }

    private int sys(int id, int fallback) {
        return Build.VERSION.SDK_INT >= 31 ? context.getColor(id) : fallback;
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    LinearLayout card(LinearLayout page, String title) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(shape(container, 28));
        TextView heading = text(title, 18, onSurface, true);
        heading.setPadding(0, 0, 0, dp(6));
        card.addView(heading);
        page.addView(card, spaced(12));
        return card;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    TextView text(String s, float sp, int color, boolean medium) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(2), 1f);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    /** A button led by a Material Symbol from res/drawable, kept centred with its label. */
    Button button(String label, String icon, Kind kind, View.OnClickListener click) {
        Button b = button(label, kind, click);
        android.graphics.drawable.Drawable d = context.getDrawable(context.getResources().getIdentifier("ic_" + icon, "drawable", context.getPackageName())).mutate();
        d.setTint(b.getCurrentTextColor());
        d.setBounds(0, 0, dp(18), dp(18));
        b.setTag(d);
        label(b, label);
        return b;
    }

    /** Sets a button's label, keeping its icon. */
    static void label(Button b, String label) {
        if (!(b.getTag() instanceof android.graphics.drawable.Drawable)) {
            b.setText(label);
            return;
        }
        android.text.SpannableString text = new android.text.SpannableString("\u00a0\u00a0" + label);
        text.setSpan(new android.text.style.ImageSpan((android.graphics.drawable.Drawable) b.getTag(), android.text.style.ImageSpan.ALIGN_CENTER), 0, 1, 0);
        b.setText(text);
    }

    /** A pill button: filled for the one main action, tonal for the rest, text for minor links. */
    Button button(String label, Kind kind, View.OnClickListener click) {
        Button b = new Button(context);
        int fill = kind == Kind.FILLED ? primary : kind == Kind.TONAL ? secondaryContainer : 0;
        int ink = kind == Kind.FILLED ? onPrimary : kind == Kind.TONAL ? onSecondaryContainer : primary;
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(ink);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(kind == Kind.TEXT ? 12 : 24), 0, dp(kind == Kind.TEXT ? 12 : 24), 0);
        b.setSingleLine(true);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(ink & 0xffffff | 0x1f000000), fill == 0 ? null : shape(fill, 24), shape(0xffffffff, 24)));
        b.setOnClickListener(click);
        return b;
    }

    /** Buttons side by side, sharing the width. */
    LinearLayout row(Button... buttons) {
        LinearLayout row = new LinearLayout(context);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(8));
            row.addView(buttons[i], lp);
        }
        return row;
    }

    /** A list row: title and supporting text on the left, the switch on the right; the whole row toggles. */
    LinearLayout switchRow(String title, String detail, Switch toggle) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout words = column();
        words.addView(text(title, 16, onSurface, false));
        words.addView(text(detail, 14, onSurfaceVariant, false));
        row.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states, new int[] {onPrimary, outline}));
        toggle.setTrackTintList(new ColorStateList(states, new int[] {primary, blend(container, outline, 0.45f)})); // an off track still shows against the card
        row.addView(toggle);
        row.setOnClickListener(v -> {
            if (toggle.isEnabled()) toggle.toggle();
        });
        return row;
    }

    /** A filled text field with its label above. */
    LinearLayout field(String label, android.widget.EditText input) {
        LinearLayout box = column();
        box.addView(text(label, 13, onSurfaceVariant, true));
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(onSurface);
        input.setHintTextColor(onSurfaceVariant);
        input.setBackground(shape(containerHigh, 12));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.addView(input, spaced(6));
        return box;
    }

    interface Pick {
        void page(int index);
    }

    /** A Material 3 navigation bar: icon and label per page, a pill behind the chosen page's icon. */
    LinearLayout navBar(String[] labels, String[] icons, Pick pick) {
        LinearLayout bar = new LinearLayout(context);
        bar.setBackgroundColor(container);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, dp(12), 0, dp(16));
            android.widget.ImageView icon = new android.widget.ImageView(context);
            icon.setImageDrawable(context.getDrawable(context.getResources().getIdentifier("ic_" + icons[i], "drawable", context.getPackageName())).mutate());
            icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(dp(20), dp(4), dp(20), dp(4)); // a 24dp icon in the 64x32 pill
            item.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(32)));
            TextView label = text(labels[i], 12, onSurfaceVariant, true);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            item.addView(label, lp);
            item.setContentDescription(labels[i]);
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(onSurface & 0xffffff | 0x1f000000), null, null));
            item.setOnClickListener(v -> {
                select(bar, index);
                pick.page(index);
            });
            bar.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    /** Marks one page of a navBar as chosen. */
    void select(LinearLayout bar, int index) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) bar.getChildAt(i);
            android.widget.ImageView icon = (android.widget.ImageView) item.getChildAt(0);
            TextView label = (TextView) item.getChildAt(1);
            boolean on = i == index;
            icon.setBackground(on ? shape(secondaryContainer, 16) : null);
            icon.getDrawable().setTint(on ? onSecondaryContainer : onSurfaceVariant);
            label.setTextColor(on ? onSurface : onSurfaceVariant);
            item.setSelected(on);
        }
    }

    ProgressBar progress() {
        ProgressBar p = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        p.setProgressTintList(ColorStateList.valueOf(primary));
        p.setProgressBackgroundTintList(ColorStateList.valueOf(secondaryContainer));
        p.setIndeterminateTintList(ColorStateList.valueOf(primary));
        return p;
    }

    void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.38f);
    }

    GradientDrawable shape(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }

    static int blend(int a, int b, float t) {
        int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
}
