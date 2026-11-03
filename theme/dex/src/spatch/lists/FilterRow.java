package spatch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import p.S_HomeFilterClick;
import p.S_HomeFilterGroup;
import p.S_HomeFilterChild;
import p.S_HomeFilterSelection;
import p.S_KotlinFunction1;

/**
 * Home's filter row (p.S_HomeFilterRow). Spotify's chips stay in their ComposeView and keep running; this row
 * covers them once bound, so any failure here leaves the original row in place. Tapping sends the
 * same S_HomeFilterClick event as the Compose chip, and selection only ever comes from the bound model. One pill
 * travels between chips, stretching on the way and nudging its neighbours when it lands; it can be
 * dragged across the row, and letting go on another chip selects that chip.
 */
public final class FilterRow extends HorizontalScrollView implements View.OnClickListener, Runnable {
    static final int CHIPS = R_id_filter_chips_container; // R.id.filter_chips_container
    private static final long TURN_MILLIS = 4000, DIFF_MILLIS = 48;
    private static final int SLIDE = 40; // dp
    private static FilterRow turning;
    private static int turnDirection;
    private static long turnAt;
    private static final long SELECT_MILLIS = 420, PRESS_MILLIS = 90, CONFIRM_MILLIS = 700, EDGE_MILLIS = 16;
    private static final float PRESSED_SCALE = 0.96f;
    private static final int JOINED_GAP = 2, INNER = 4; // dp: a sub-filter sits against its parent as one group
    private static final int PILL = 40, PUSH = 7, SQUASH = 6, LIFT = 4, EDGE = 56, EDGE_STEP = 7, GLOW = 6; // dp

    private final int surface = Palette.color(0xff2b2640), onSurface = Palette.color(0xfff3eeff),
        selected = Palette.color(0xffd0bcff), onSelected = Palette.color(0xff14121c);
    private final Track chips;
    private final HashMap<String, Chip> byId = new HashMap<String, Chip>();
    private final float density;
    private S_KotlinFunction1 click;
    private Chip shown;
    private String openParent, openChild;
    private boolean revealAnimated, grabbing;
    private ValueAnimator resizing;
    private float downX, downY;

    private FilterRow(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        setClipToPadding(false);
        setClipChildren(false); // the pill's overshoot and glow pass the row's edges
        setFillViewport(false);
        chips = new Track(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPaddingRelative(dp(20), 0, dp(16), 0);
        addView(chips, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }

    /** my50.b, on the main thread: list of S_HomeFilterGroup, selection mn60, click S_KotlinFunction1. */
    public static void bind(View header, List items, Object selection, Object click) {
        try {
            View compose = header.findViewById(CHIPS);
            if (compose == null || items.isEmpty()) return;
            ViewGroup parent = (ViewGroup) compose.getParent();
            FilterRow row = null;
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (parent.getChildAt(i) instanceof FilterRow) row = (FilterRow) parent.getChildAt(i);
            }
            if (!Settings.filtersCustom()) {
                if (row != null) parent.removeView(row);
                compose.setVisibility(View.VISIBLE);
                return;
            }
            boolean first = row == null;
            if (first) {
                row = new FilterRow(header.getContext());
                parent.addView(row, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            row.click = (S_KotlinFunction1) click;
            row.show(items, selection instanceof S_HomeFilterSelection ? (S_HomeFilterSelection) selection : null, !first);
            compose.setVisibility(View.INVISIBLE);
        } catch (RuntimeException e) {
            View compose = header.findViewById(CHIPS);
            if (compose != null) compose.setVisibility(View.VISIBLE);
        }
    }

    private void show(List items, S_HomeFilterSelection selection, boolean animate) {
        String parent = selection != null && selection.a != null ? selection.a.f : null;
        String child = selection != null && selection.b != null ? selection.b.f : null;
        openParent = parent;
        openChild = child;
        ArrayList<String[]> rows = rows(items, parent, child);
        ArrayList<Chip> order = new ArrayList<Chip>();
        Chip selected = null;
        for (String[] row : rows) {
            Chip chip = byId.get(row[0]);
            if (chip == null) {
                chip = new Chip(getContext());
                byId.put(row[0], chip);
            }
            boolean on = row[0].equals(child != null ? child : parent);
            chip.bind(row[0], row[1], on);
            chip.joinStart = row.length > 2;
            chip.joinEnd = false;
            if (chip.joinStart && !order.isEmpty()) order.get(order.size() - 1).joinEnd = true;
            if (on) selected = chip;
            order.add(chip);
        }
        boolean same = order.size() == chips.getChildCount();
        for (int i = 0; same && i < order.size(); i++) same = chips.getChildAt(i) == order.get(i);
        if (!same) {
            ArrayList<Chip> merged = new ArrayList<Chip>(order);
            boolean motion = animate && Wave.animationsEnabled();
            for (int i = 0; motion && i < chips.getChildCount(); i++) { // a chip on its way out stays next to its neighbour
                Chip old = (Chip) chips.getChildAt(i);
                if (!merged.contains(old)) merged.add(i == 0 ? 0 : merged.indexOf(chips.getChildAt(i - 1)) + 1, old);
            }
            ArrayList<Chip> present = new ArrayList<Chip>();
            for (int i = 0; i < chips.getChildCount(); i++) present.add((Chip) chips.getChildAt(i));
            chips.removeAllViews();
            for (int i = 0; i < merged.size(); i++) {
                Chip chip = merged.get(i);
                boolean stays = order.contains(chip);
                chip.settleFrom(present.contains(chip) || !motion ? chip.open : 0f, stays ? 1f : 0f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
                chips.addView(chip, lp);
            }
            resize(motion, keys(rows));
        }
        if (chips.dragging && chips.hover.getParent() != chips) chips.release(true);
        if (selected != shown) {
            shown = selected;
            if (!chips.dragging && selected != chips.aim) chips.move(selected, animate);
            else chips.removeCallbacks(chips);
            if (!chips.dragging) chips.aim = selected;
            revealAnimated = animate;
            removeCallbacks(this);
            post(this);
        }
    }

    /** Chips open and close with the pill, so the row never loses width under a pill still crossing it. */
    private void resize(boolean motion, final ArrayList<String> live) {
        if (resizing != null) resizing.cancel();
        if (!motion) {
            openChips(1f);
            finishResize(live);
            return;
        }
        resizing = ValueAnimator.ofFloat(0f, 1f);
        resizing.setDuration(SELECT_MILLIS);
        resizing.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = ((Float) a.getAnimatedValue()).floatValue();
                openChips(t);
                if (t >= 1) finishResize(live);
            }
        });
        resizing.start();
        openChips(0f);
    }

    private void openChips(float t) {
        float eased = NavIndicator.settleCurve(t);
        for (int i = 0; i < chips.getChildCount(); i++) {
            Chip chip = (Chip) chips.getChildAt(i);
            chip.open = chip.openFrom + (chip.openTo - chip.openFrom) * eased;
            chip.setAlpha(chip.open);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
            lp.setMarginStart(i == 0 ? 0 : Math.round(dp(chip.joinStart ? JOINED_GAP : 8) * chip.open));
            chip.setLayoutParams(lp); // re-resolves the start margin; requestLayout alone kept the first one
        }
    }

    private void finishResize(ArrayList<String> live) {
        resizing = null;
        for (int i = chips.getChildCount() - 1; i >= 0; i--) if (((Chip) chips.getChildAt(i)).openTo == 0) chips.removeViewAt(i);
        byId.keySet().retainAll(live);
    }

    /** {id, label}, plus a marker for children: top-level chips, with the open chip's children straight after it. */
    static ArrayList<String[]> rows(List items, String parent, String child) {
        ArrayList<String[]> out = new ArrayList<String[]>();
        for (Object item : items) {
            S_HomeFilterGroup chip = (S_HomeFilterGroup) item;
            out.add(new String[] {chip.f, chip.g});
            if (!chip.f.equals(parent) || chip.h == null) continue;
            for (Object sub : chip.h) out.add(new String[] {((S_HomeFilterChild) sub).f, ((S_HomeFilterChild) sub).g, "sub"});
        }
        return out;
    }

    private static ArrayList<String> keys(ArrayList<String[]> rows) {
        ArrayList<String> out = new ArrayList<String>();
        for (String[] row : rows) out.add(row[0]);
        return out;
    }

    /** Keeps the selected chip in view with a little of its neighbour showing. */
    public void run() {
        if (shown == null) return;
        int margin = dp(40), from = getScrollX(), width = getWidth();
        int left = shown.getLeft() - margin, right = shown.getRight() + margin;
        int to = left < from ? left : right > from + width ? right - width : from;
        if (to == from) return;
        if (revealAnimated && Wave.animationsEnabled()) smoothScrollTo(to, 0);
        else scrollTo(to, 0);
    }

    /** The pill leaves the scroller once a sideways drag starts on it; anywhere else the row scrolls. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
            grabbing = shown != null && click != null && Settings.filterDrag() && Wave.animationsEnabled() && chips.nearest(downX + getScrollX()) == shown;
        } else if (action == MotionEvent.ACTION_MOVE && grabbing) {
            float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (dx > slop && dx > dy) {
                grabbing = false;
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                getParent().requestDisallowInterceptTouchEvent(true);
                chips.grab();
            } else if (dy > slop) {
                grabbing = false;
            }
        }
        if (!chips.dragging) return super.dispatchTouchEvent(e);
        chips.finger(e.getX());
        if (action == MotionEvent.ACTION_UP) chips.release(false);
        else if (action == MotionEvent.ACTION_CANCEL) chips.release(true);
        return true;
    }

    /** Spotify's reducer falls back to All for the open parent, but re-tapping its child lands on the parent. */
    static String event(String tapped, String parent, String child) {
        return tapped.equals(parent) && child != null ? child : tapped;
    }

    /** Where the pill's edges are after t of a move: the edge in front runs ahead of the one behind. */
    static float[] blob(float fromLeft, float fromRight, float toLeft, float toRight, float t) {
        boolean forward = toLeft + toRight >= fromLeft + fromRight;
        float lead = NavIndicator.settleCurve(Math.min(1, t * 1.7f)), trail = NavIndicator.settleCurve(Math.max(0, t * 1.7f - 0.7f));
        return new float[] {fromLeft + (toLeft - fromLeft) * (forward ? trail : lead), fromRight + (toRight - fromRight) * (forward ? lead : trail)};
    }

    /** Neighbours' kick after t of a move, -1 to 1; it dies away to exactly zero. */
    static float nudge(float t) {
        return (float) Math.sin(2 * Math.PI * t) * (1 - t);
    }

    public void onClick(View v) {
        if (click == null) return;
        Haptics.navigation(v);
        turn((Chip) v);
        click.invoke(new S_HomeFilterClick(event(((Chip) v).id, openParent, openChild)));
    }

    private void turn(Chip to) {
        if (to == shown || shown == null || !Settings.filterSlide()) return;
        turning = this;
        turnDirection = to.getLeft() > shown.getLeft() ? 1 : -1;
        turnAt = SystemClock.uptimeMillis();
    }

    /** The new filter's sections came in: they slide from the side the chosen chip lies on. */
    static void arrived() {
        final FilterRow row = turning;
        turning = null;
        if (row == null || SystemClock.uptimeMillis() - turnAt > TURN_MILLIS) return;
        row.postDelayed(new Runnable() { // the list diffs off the main thread
            public void run() {
                row.slideIn();
            }
        }, DIFF_MILLIS);
    }

    private void slideIn() {
        ViewGroup list = list(getRootView(), getResources().getIdentifier("recycler_view", "id", getContext().getPackageName()));
        if (list == null) return;
        for (int i = 0, n = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (holds(child)) continue;
            child.setTranslationX(turnDirection * dp(SLIDE));
            child.setAlpha(0f);
            child.animate().translationX(0f).alpha(1f).setStartDelay(n++ * 24L).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    private boolean holds(View child) {
        for (View view = this; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) if (view == child) return true;
        return false;
    }

    private static RecyclerView list(View view, int id) {
        if (view instanceof SpatchSettings || !view.isShown()) return null;
        if (view instanceof RecyclerView && view.getId() == id) return (RecyclerView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            RecyclerView found = list(group.getChildAt(i), id);
            if (found != null) return found;
        }
        return null;
    }

    /** Paints what sits under the chips' labels: their fills, then the travelling pill. */
    private final class Track extends LinearLayout implements ValueAnimator.AnimatorUpdateListener, Runnable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path shape = new Path();
        private final float[] radii = new float[8];
        private final OvershootInterpolator settle = new OvershootInterpolator(1.4f);
        private final int light = Pill.blend(selected, onSurface, 0.4f);
        private Shader pillShader;
        private ValueAnimator animator;
        private float left, right, fromLeft, fromRight, toLeft, toRight, t = 1, lift, finger, screen;
        private boolean appearing;
        private long drawn;
        boolean dragging;
        Chip aim, hover; // aim leads shown by a beat after a drag; hover is the chip under the finger

        Track(Context context) {
            super(context);
            setWillNotDraw(false);
            fill.setColor(surface);
            if (Settings.filterGlow()) pill.setShadowLayer(dp(GLOW), 0, dp(1), selected & 0xffffff | 0x59000000);
        }

        void move(Chip to, boolean animate) {
            animate &= Settings.filterAnimated();
            aim = to;
            removeCallbacks(this);
            if (animator != null) animator.cancel();
            appearing = right - left < 1;
            fromLeft = left;
            fromRight = right;
            t = 1;
            if (animate && Wave.animationsEnabled()) {
                t = 0;
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(SELECT_MILLIS);
                animator.addUpdateListener(this);
                animator.start();
            }
            invalidate();
        }

        public void onAnimationUpdate(ValueAnimator a) {
            t = ((Float) a.getAnimatedValue()).floatValue();
            invalidate();
        }

        Chip nearest(float x) {
            Chip best = null;
            float distance = Float.MAX_VALUE;
            for (int i = 0; i < getChildCount(); i++) {
                Chip chip = (Chip) getChildAt(i);
                float d = Math.abs((chip.getLeft() + chip.getRight()) / 2f - x);
                if (d < distance && chip.openTo > 0) {
                    distance = d;
                    best = chip;
                }
            }
            return best;
        }

        void grab() {
            if (animator != null) animator.cancel();
            removeCallbacks(this);
            t = 1;
            dragging = true;
            hover = shown;
            drawn = SystemClock.uptimeMillis();
            Haptics.navigation(this);
            post(this);
        }

        void finger(float x) {
            screen = x;
            finger = x + FilterRow.this.getScrollX();
            Chip over = nearest(finger);
            if (over != null && over != hover) {
                hover = over;
                Haptics.navigation(this);
            }
            invalidate();
        }

        void release(boolean cancelled) {
            dragging = false;
            Chip to = cancelled ? shown : hover;
            hover = null;
            move(to, true);
            if (to == shown || click == null) return;
            turn(to);
            click.invoke(new S_HomeFilterClick(event(to.id, openParent, openChild)));
            postDelayed(this, CONFIRM_MILLIS);
        }

        /** Dragging: keeps the row scrolling under a finger held near its edge. After: the model refused, so go home. */
        public void run() {
            if (!dragging) {
                if (aim != shown) move(shown, true);
                return;
            }
            int edge = dp(EDGE), width = FilterRow.this.getWidth();
            int step = screen < edge ? -dp(EDGE_STEP) : screen > width - edge ? dp(EDGE_STEP) : 0;
            if (step != 0) {
                FilterRow.this.scrollTo(FilterRow.this.getScrollX() + step, 0);
                finger(screen);
            }
            postDelayed(this, EDGE_MILLIS);
        }

        private float follow(float value, float target, float dt, float millis) {
            return value + (target - value) * (1 - (float) Math.exp(-dt / millis));
        }

        private void step() {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(48, now - drawn);
            drawn = now;
            if (dragging) {
                float half = (hover.getRight() - hover.getLeft()) / 2f;
                float first = getChildAt(0).getLeft(), last = getChildAt(getChildCount() - 1).getRight();
                float aimLeft = Math.max(first, Math.min(last - 2 * half, finger - half)), aimRight = aimLeft + 2 * half;
                boolean forward = aimLeft > left;
                left = follow(left, aimLeft, dt, forward ? 70 : 30);
                right = follow(right, aimRight, dt, forward ? 30 : 70);
                if (Math.abs(aimLeft - left) + Math.abs(aimRight - right) > .5f) invalidate();
            } else {
                boolean live = aim != null && aim.getParent() == this;
                float middle = (fromLeft + fromRight) / 2;
                toLeft = live ? aim.getLeft() : middle;
                toRight = live ? aim.getRight() : middle;
                if (appearing) fromLeft = fromRight = (toLeft + toRight) / 2;
                float[] edges = blob(fromLeft, fromRight, toLeft, toRight, settle.getInterpolation(t));
                left = edges[0];
                right = edges[1];
            }
            float raised = dragging ? 1 : 0;
            lift = follow(lift, raised, dt, 70);
            if (Math.abs(raised - lift) > .01f) invalidate();
            else lift = raised;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (getChildCount() == 0) return;
            step();
            float middle = getHeight() / 2f, half = dp(PILL) / 2f;
            Chip anchor = dragging ? hover : aim;
            float kick = dp(PUSH) * nudge(t);
            for (int i = 0; i < getChildCount(); i++) {
                Chip chip = (Chip) getChildAt(i);
                int side = anchor == null || anchor == chip ? 0 : chip.getLeft() < anchor.getLeft() ? -1 : 1;
                chip.setTranslationX(dragging || side == 0 ? 0 : side * kick);
                float overlap = Math.min(right, chip.getRight()) - Math.max(left, chip.getLeft());
                chip.ink(Math.max(0, Math.min(1, overlap / Math.max(1, chip.getRight() - chip.getLeft()))));
                float scale = chip.getScaleX(), inset = (1 - scale) * (chip.getRight() - chip.getLeft()) / 2;
                float x = chip.getTranslationX();
                rect.set(chip.getLeft() + x + inset, middle - half * scale, chip.getRight() + x - inset, middle + half * scale);
                chip.corners(radii);
                shape.reset();
                shape.addRoundRect(rect, radii, Path.Direction.CW);
                canvas.drawPath(shape, fill);
            }
            if (right - left < 1) return;
            if (pillShader == null) {
                pillShader = new LinearGradient(0, 0, dp(220), 0, new int[] {selected, light}, null, Shader.TileMode.MIRROR);
                pill.setShader(pillShader);
            }
            float natural = anchor == null ? right - left : anchor.getRight() - anchor.getLeft();
            float stretch = Math.max(0, Math.min(1, (right - left - natural) / dp(80)));
            float scale = !dragging && anchor != null ? anchor.getScaleX() : 1, inset = (1 - scale) * (right - left) / 2;
            float height = half + density * (lift * LIFT - stretch * SQUASH) / 2;
            if (t < 1) { // a soft comet tail on the way over
                float[] tail = blob(fromLeft, fromRight, toLeft, toRight, settle.getInterpolation(Math.max(0, t - 0.12f)));
                pill.setAlpha(Math.round(0x50 * (1 - t)));
                rect.set(Math.min(left, tail[0]), middle - height, Math.max(right, tail[1]), middle + height);
                canvas.drawRoundRect(rect, height, height, pill);
            }
            pill.setAlpha(255);
            rect.set(left + inset, middle - height * scale, right - inset, middle + height * scale);
            canvas.drawRoundRect(rect, height, height, pill);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(this);
            if (animator != null) animator.cancel();
            animator = null;
            dragging = false;
            hover = null;
            aim = shown;
            t = 1;
            lift = 0;
        }
    }

    /** One label. isSelected carries the state to TalkBack; the pill under it carries it to the eye. */
    private final class Chip extends TextView implements ValueAnimator.AnimatorUpdateListener {
        private final GradientDrawable shape = new GradientDrawable();
        private ValueAnimator press;
        float ink = -1, open = 1, openFrom = 1, openTo = 1;
        boolean joinStart, joinEnd;
        private final float[] own = new float[8];
        String id;

        Chip(Context context) {
            super(context);
            setSingleLine(true);
            setGravity(Gravity.CENTER);
            setTextSize(14f);
            setTypeface(Fonts.applied(500));
            setPaddingRelative(dp(16), 0, dp(16), 0);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(FilterRow.this);
            Springy.attach(this);
            // 40dp pill in a 48dp target; the ripple starts at the touch and stays inside the pill.
            shape.setColor(0xffffffff);
            RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(selected & 0xffffff | 0x33000000), null, shape);
            setBackground(new InsetDrawable(ripple, 0, dp(4), 0, dp(4)));
            ink(0f);
        }

        void settleFrom(float from, float to) {
            openFrom = from;
            openTo = to;
            setEnabled(to > 0);
            setImportantForAccessibility(to > 0 ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onMeasure(int width, int height) {
            super.onMeasure(width, height);
            if (open < 1) setMeasuredDimension(Math.round(getMeasuredWidth() * open), getMeasuredHeight());
        }

        void bind(String id, String label, boolean on) {
            this.id = id;
            setText(label);
            setSelected(on);
        }

        /** How much of this chip the pill covers: the label turns as the pill crosses it. */
        void ink(float t) {
            if (t == ink) return;
            ink = t;
            corners(own);
            shape.setCornerRadii(own);
            setTextColor(Pill.blend(onSurface, onSelected, t));
        }

        /** Path radii: joined sides stay tight until the pill covers the chip, then round out with it. */
        void corners(float[] out) {
            float outer = density * (12 + 8 * ink), inner = density * (INNER + (20 - INNER) * ink);
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float l = (rtl ? joinEnd : joinStart) ? inner : outer, r = (rtl ? joinStart : joinEnd) ? inner : outer;
            out[0] = out[1] = out[6] = out[7] = l;
            out[2] = out[3] = out[4] = out[5] = r;
        }

        public void onAnimationUpdate(ValueAnimator a) {
            float scale = ((Float) a.getAnimatedValue()).floatValue();
            setScaleX(scale);
            setScaleY(scale);
            chips.invalidate();
        }

        /** Runs for up, cancel and scroll takeover alike, so the chip always comes back. */
        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            if (press != null) press.cancel();
            if (Wave.animationsEnabled()) {
                press = ValueAnimator.ofFloat(getScaleX(), pressed ? PRESSED_SCALE : 1f);
                press.setDuration(PRESS_MILLIS);
                press.addUpdateListener(this);
                press.start();
            } else {
                setScaleX(1f);
                setScaleY(1f);
            }
        }
    }
}
