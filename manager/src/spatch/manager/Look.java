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
    final int tertiaryContainer, onTertiaryContainer;
    final int surface, container, containerHigh, onSurface, onSurfaceVariant, outline;
    private final Context context;
    private final float density;

    Look(Context context) {
        this.context = context;
        density = context.getResources().getDisplayMetrics().density;
        String mode = context.getSharedPreferences("manager", Context.MODE_PRIVATE).getString("theme_mode", "system");
        boolean isNight = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        dark = mode.equals("dark") || mode.equals("black") || (mode.equals("system") && isNight);
        boolean amoled = mode.equals("black");
        if (dark) {
            primary = sys(android.R.color.system_accent1_200, 0xffd0bcff);
            onPrimary = sys(android.R.color.system_accent1_800, 0xff381e72);
            primaryContainer = sys(android.R.color.system_accent1_700, 0xff4f378b);
            onPrimaryContainer = sys(android.R.color.system_accent1_100, 0xffeaddff);
            secondaryContainer = sys(android.R.color.system_accent2_700, 0xff4a4458);
            onSecondaryContainer = sys(android.R.color.system_accent2_100, 0xffe8def8);
            tertiaryContainer = sys(android.R.color.system_accent3_700, 0xff3b4a36);
            onTertiaryContainer = sys(android.R.color.system_accent3_100, 0xffd8e8cf);
            if (amoled) {
                surface = 0xff000000;
                container = 0xff121212;
                containerHigh = 0xff1e1e1e;
                outline = 0xff383838;
            } else {
                surface = sys(android.R.color.system_neutral1_900, 0xff141218);
                int step = sys(android.R.color.system_neutral1_800, 0xff322f35);
                container = blend(surface, step, 0.35f);
                containerHigh = blend(surface, step, 0.7f);
                outline = sys(android.R.color.system_neutral2_400, 0xff938f99);
            }
            onSurface = sys(android.R.color.system_neutral1_100, 0xffe6e0e9);
            onSurfaceVariant = sys(android.R.color.system_neutral2_200, 0xffcac4d0);
        } else {
            primary = sys(android.R.color.system_accent1_600, 0xff6750a4);
            onPrimary = sys(android.R.color.system_accent1_0, 0xffffffff);
            primaryContainer = sys(android.R.color.system_accent1_100, 0xffeaddff);
            onPrimaryContainer = sys(android.R.color.system_accent1_900, 0xff21005d);
            secondaryContainer = sys(android.R.color.system_accent2_100, 0xffe8def8);
            onSecondaryContainer = sys(android.R.color.system_accent2_900, 0xff1d192b);
            tertiaryContainer = sys(android.R.color.system_accent3_100, 0xffd8e8cf);
            onTertiaryContainer = sys(android.R.color.system_accent3_900, 0xff111f0d);
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
        GradientDrawable bg = shape(container, 24);
        bg.setStroke(dp(1), blend(container, outline, 0.22f));
        card.setBackground(bg);
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
        int pad = dp(kind == Kind.TEXT ? 8 : 14);
        b.setPadding(pad, 0, pad, 0);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(ink & 0xffffff | 0x1f000000), fill == 0 ? null : shape(fill, 24), shape(0xffffffff, 24)));
        b.setOnClickListener(click);
        squish(b);
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
        JellyBar bar = new JellyBar(context, secondaryContainer, pick);
        bar.setBackgroundColor(container);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, dp(12), 0, dp(16));
            android.widget.ImageView icon = new android.widget.ImageView(context);
            icon.setImageDrawable(context.getDrawable(context.getResources().getIdentifier("ic_" + icons[i], "drawable", context.getPackageName())).mutate());
            icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            icon.setTag(icons[i]);
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
            String name = (String) icon.getTag();
            int filled = name == null ? 0 : context.getResources().getIdentifier("ic_" + name + (on ? "_fill" : ""), "drawable", context.getPackageName());
            if (filled != 0) icon.setImageDrawable(context.getDrawable(filled).mutate()); // filled when chosen, outlined otherwise
            icon.getDrawable().setTint(on ? onSecondaryContainer : onSurfaceVariant);
            label.setTextColor(on ? onSurface : onSurfaceVariant);
            if (on && !item.isSelected() && motion(context)) {
                icon.setScaleX(0.7f);
                icon.setScaleY(0.7f);
                icon.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
            }
            item.setSelected(on);
        }
        if (bar instanceof JellyBar) ((JellyBar) bar).moveTo(index, true);
    }

    /** Whether to play the manager's playful motion: the user's switch, and Android's own animation setting. */
    static boolean motion(Context context) {
        return android.animation.ValueAnimator.areAnimatorsEnabled() && context.getSharedPreferences("manager", Context.MODE_PRIVATE).getBoolean(MOTION, true);
    }

    static final String MOTION = "playful_motion";

    /** Presses sink a little and spring back past their size, like jelly. */
    static void squish(View v) {
        v.setOnTouchListener((view, e) -> {
            if (!motion(view.getContext())) return false;
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.94f).scaleY(0.9f).setDuration(120).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(new android.view.animation.OvershootInterpolator(4f)).start();
            }
            return false; // the click still happens as usual
        });
    }

    TextView badge(String text, int fg, int bg) {
        TextView b = new TextView(context);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(fg);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable d = shape(bg, 10);
        d.setStroke(dp(1), blend(bg, fg, 0.25f));
        b.setBackground(d);
        return b;
    }

    final class StepTracker extends LinearLayout {
        private final TextView[] labels;
        private final TextView[] icons;
        private final String[] titles = {"Prepare", "Patch", "Sign", "Install"};

        StepTracker() {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            labels = new TextView[4];
            icons = new TextView[4];
            for (int i = 0; i < 4; i++) {
                LinearLayout item = new LinearLayout(context);
                item.setOrientation(HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                TextView ic = new TextView(context);
                ic.setText(String.valueOf(i + 1));
                ic.setTextSize(11);
                ic.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                ic.setGravity(Gravity.CENTER);
                ic.setTextColor(onSurfaceVariant);
                ic.setBackground(shape(containerHigh, 10));
                LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(20), dp(20));
                icLp.setMarginEnd(dp(4));
                item.addView(ic, icLp);
                icons[i] = ic;
                TextView lbl = text(titles[i], 12, onSurfaceVariant, false);
                item.addView(lbl);
                labels[i] = lbl;
                LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) itemLp.setMarginStart(dp(4));
                addView(item, itemLp);
            }
        }

        void setStage(int active) {
            for (int i = 0; i < 4; i++) {
                if (i < active) {
                    icons[i].setText("✓");
                    icons[i].setTextColor(onPrimary);
                    icons[i].setBackground(shape(primary, 10));
                    labels[i].setTextColor(onSurface);
                    labels[i].setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                } else if (i == active) {
                    icons[i].setText(String.valueOf(i + 1));
                    icons[i].setTextColor(onPrimaryContainer);
                    icons[i].setBackground(shape(primaryContainer, 10));
                    labels[i].setTextColor(primary);
                    labels[i].setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                } else {
                    icons[i].setText(String.valueOf(i + 1));
                    icons[i].setTextColor(onSurfaceVariant);
                    icons[i].setBackground(shape(containerHigh, 10));
                    labels[i].setTextColor(onSurfaceVariant);
                    labels[i].setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                }
            }
        }
    }

    StepTracker stepTracker() {
        return new StepTracker();
    }

    /** Self-test lines with their verdict as a coloured, bold word: PASS in primary, WARN amber, FAIL red. */
    CharSequence verdicts(String text) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder(text);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^(PASS|WARN|FAIL|INFO)\\b").matcher(text);
        while (m.find()) {
            String v = m.group(1);
            int color = v.equals("PASS") ? primary : v.equals("WARN") ? (dark ? 0xffffcc80 : 0xff8a5a00) : v.equals("FAIL") ? (dark ? 0xffffb4ab : 0xffba1a1a) : onSurfaceVariant;
            out.setSpan(new android.text.style.ForegroundColorSpan(color), m.start(), m.end(), 0);
            out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), m.start(), m.end(), 0);
        }
        return out;
    }

    /** One stop on a vertical timeline: a line through it (not past the ends) and a dot, filled when lit. */
    View timeline(boolean first, boolean last, boolean lit) {
        return new View(context) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                float x = getWidth() / 2f, y = dp(10), r = dp(lit ? 6 : 4.5f);
                paint.setStrokeWidth(dp(2));
                paint.setColor(blend(container, outline, 0.4f));
                if (!first) c.drawLine(x, 0, x, y - r - dp(3), paint);
                if (!last) c.drawLine(x, y + r + dp(3), x, getHeight(), paint);
                paint.setColor(lit ? primary : blend(container, outline, 0.6f));
                paint.setStyle(lit ? android.graphics.Paint.Style.FILL : android.graphics.Paint.Style.STROKE);
                c.drawCircle(x, y, r, paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);
            }
        };
    }

    ProgressBar progress() {
        return new WavyProgress(context, primary, blend(container, onSurfaceVariant, 0.35f));
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

    GradientDrawable shape(int color, float radius, int strokeColor, float strokeWidth) {
        GradientDrawable g = shape(color, radius);
        if (strokeWidth > 0) g.setStroke(dp(strokeWidth), strokeColor);
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
