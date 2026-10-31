package spatch;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings' Review on Home: Home scrolls as usual and a section swiped sideways is hidden on the spot.
 * This transparent layer owns touches on the sections: vertical drags go on to the list, sideways
 * ones move the section, and taps stop here so a stray one doesn't open anything. Everything outside
 * the sections passes through. TalkBack users have the same choices as switches in Settings.
 */
final class HomeReview extends FrameLayout implements View.OnClickListener, Runnable {
    private static final int ABOVE_BARS = 176; // dp
    private static final int SETTLE = 160, ITEM_ANIMATION = 500; // ms
    private static final float COMMIT = 0.35f; // of the width

    private final ViewGroup host;
    private final int listId;
    private final ArrayList<String> hidden = new ArrayList<String>();
    private final LinearLayout bar;
    private final TextView count, undo, done;
    private final float density;
    private Back backCallback;
    private RecyclerView list;
    private int header; // the adapter's rows ahead of the sections
    private View row;
    private String section;
    private float downX, downY, offsetX, offsetY;
    private boolean sideways, moved;

    private HomeReview(ViewGroup host, int listId) {
        super(host.getContext());
        this.host = host;
        this.listId = listId;
        density = getContext().getResources().getDisplayMetrics().density;
        bar = new LinearLayout(getContext());
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(4), dp(8), dp(4));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Palette.color(0xff2b2640));
        shape.setCornerRadius(28 * density);
        bar.setBackground(shape);
        bar.setClickable(true);
        count = new TextView(getContext());
        count.setTextSize(14f);
        count.setTextColor(Palette.color(0xfff3eeff));
        count.setTypeface(Fonts.settings(700));
        bar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        undo = button("Undo");
        done = button("Done");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(16), 0, dp(16), dp(ABOVE_BARS));
        addView(bar, lp);
        update();
    }

    private TextView button(String label) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextSize(14f);
        view.setTextColor(Palette.color(0xffd0bcff));
        view.setTypeface(Fonts.settings(700));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(this);
        bar.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        return view;
    }

    private int dp(int value) {
        return (int) (value * density + 0.5f);
    }

    /** False when Home isn't the list on screen, so the caller can say so instead of closing. */
    static boolean start(ViewGroup host) {
        int id = host.getResources().getIdentifier("recycler_view", "id", host.getContext().getPackageName());
        HomeReview review = new HomeReview(host, id);
        if (!review.findList()) return false;
        Settings.closeDrawer();
        host.addView(review, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        review.bringToFront();
        if (android.os.Build.VERSION.SDK_INT >= 33) review.backCallback = Back.register(review, review);
        return true;
    }

    /** The list is kept once found: mid-update its count can't be matched against the sections. */
    private boolean findList() {
        if (list != null && list.isShown()) return true;
        int sections = Content.onHome().size();
        list = sections == 0 ? null : list(host, listId, sections);
        if (list != null) header = list.getAdapter().e() - sections;
        return list != null;
    }

    /** Home's list: on screen, and either exactly the sections or the sections plus the chip header. */
    private static RecyclerView list(View view, int id, int sections) {
        if (view instanceof SpatchSettings || view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof RecyclerView && view.getId() == id) {
            RecyclerView list = (RecyclerView) view;
            if (list.getAdapter() != null && list.getAdapter().e() - sections >= 0 && list.getAdapter().e() - sections <= 1 && list.getLayoutManager() instanceof LinearLayoutManager) return list;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            RecyclerView found = list(group.getChildAt(i), id, sections);
            if (found != null) return found;
        }
        return null;
    }

    private static FilterRow chips(View view) {
        if (view instanceof FilterRow) return (FilterRow) view;
        if (!(view instanceof ViewGroup) || view instanceof SpatchSettings) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            FilterRow found = chips(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /** The section at an adapter position, or null for the chip header and anything past the end. */
    static String section(int position, int header, List<String> home) {
        int index = position - header;
        return index >= 0 && index < home.size() ? home.get(index) : null;
    }

    static boolean commits(float dx, int width) {
        return Math.abs(dx) > width * COMMIT;
    }

    private boolean inside(View view, float x, float y) {
        int[] at = new int[2], own = new int[2];
        view.getLocationOnScreen(at);
        getLocationOnScreen(own);
        float left = x + own[0] - at[0], top = y + own[1] - at[1];
        return left >= 0 && top >= 0 && left < view.getWidth() && top < view.getHeight();
    }

    /** True when the touch landed on a section. */
    private boolean begin(float x, float y) {
        if (!findList()) {
            finish(); // left Home
            return false;
        }
        View filters = chips(host);
        if (y >= bar.getTop() || !inside(list, x, y) || filters != null && filters.isShown() && inside(filters, x, y)) return false;
        int[] at = new int[2], own = new int[2];
        list.getLocationOnScreen(at);
        getLocationOnScreen(own);
        offsetX = own[0] - at[0];
        offsetY = own[1] - at[1];
        for (int i = list.getChildCount() - 1; i >= 0; i--) {
            View child = list.getChildAt(i);
            if (!inside(child, x, y)) continue;
            String id = section(RecyclerView.S(child), header, Content.onHome());
            if (id == null) return false;
            row = child;
            section = id;
            downX = x;
            downY = y;
            sideways = moved = false;
            return true;
        }
        return false;
    }

    private void forward(MotionEvent event, int action) {
        MotionEvent copy = MotionEvent.obtain(event);
        copy.setAction(action);
        copy.offsetLocation(offsetX, offsetY);
        list.dispatchTouchEvent(copy);
        copy.recycle();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!begin(event.getX(), event.getY())) return false;
            forward(event, action);
            return true;
        }
        float dx = event.getX() - downX, dy = event.getY() - downY;
        if (action == MotionEvent.ACTION_MOVE) {
            if (!sideways && !moved) {
                if (Math.abs(dx) > dp(12) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    sideways = true;
                    forward(event, MotionEvent.ACTION_CANCEL);
                } else if (Math.abs(dy) > dp(12)) {
                    moved = true;
                }
            }
            if (sideways) {
                row.setTranslationX(dx);
                row.setAlpha(1f - Math.min(0.7f, Math.abs(dx) / getWidth()));
            } else {
                forward(event, action);
            }
        } else if (sideways) {
            release(action == MotionEvent.ACTION_UP && commits(dx, getWidth()), dx);
        } else {
            forward(event, action == MotionEvent.ACTION_UP && moved ? action : MotionEvent.ACTION_CANCEL);
        }
        return true;
    }

    private void release(boolean hide, float dx) {
        final View gone = row;
        final String id = section;
        if (!hide) {
            gone.animate().translationX(0f).alpha(1f).setStartDelay(0).setDuration(SETTLE).start();
            return;
        }
        Haptics.navigation(gone);
        gone.animate().translationX(Math.signum(dx) * getWidth()).alpha(0f).setStartDelay(0).setDuration(SETTLE).start();
        host.postDelayed(new Runnable() {
            public void run() {
                Settings.hideSection(id, true);
                hidden.add(id);
                Content.refresh();
                update();
            }
        }, SETTLE);
        host.postDelayed(new Runnable() {
            public void run() { // the list may reuse this view
                gone.setTranslationX(0f);
                gone.setAlpha(1f);
            }
        }, SETTLE + ITEM_ANIMATION);
    }

    private void update() {
        count.setText(hidden.isEmpty() ? "Swipe a section to hide it" : hidden.size() + " hidden");
        undo.setVisibility(hidden.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public void onClick(View v) {
        Haptics.navigation(v);
        if (v == done) {
            finish();
        } else if (!hidden.isEmpty()) {
            Settings.hideSection(hidden.remove(hidden.size() - 1), false);
            Content.refresh();
            update();
        }
    }

    /** Back callback. */
    public void run() {
        finish();
    }

    private void finish() {
        if (backCallback != null) backCallback.unregister();
        backCallback = null;
        host.removeView(this);
    }
}
