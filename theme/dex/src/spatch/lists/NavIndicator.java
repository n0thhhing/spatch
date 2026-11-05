package spatch;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.view.Gravity;
import android.widget.TextView;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import java.lang.ref.WeakReference;

/**
 * The floating bar's selected-tab indicator, drawn over the Compose tabs at 25% alpha so it can cross
 * tab cells. Moving, the leading edge runs ahead and the trailing edge follows, so it stretches toward
 * the destination and settles around that icon. Pill reports selection. Touches are forwarded to the
 * tabs until a sideways drag starts; the pill then follows the finger and release taps the tab under it.
 */
public final class NavIndicator extends View implements ValueAnimator.AnimatorUpdateListener, Runnable {
    private static final long MILLIS = 340, TAP_MILLIS = 40, CONFIRM_MILLIS = 700;
    private static final float BAR_LIFT = 1.03f, FOLLOW_MS = 45;
    private static final float LIFT_WIDTH = 8, LIFT_HEIGHT = 6, STRETCH = 40, SQUASH = 4; // dp
    private static final int ALPHA = 0x40, LIFT_ALPHA = 0x30, PRESS_ALPHA = 0x24;
    private static final float WIDTH = 64, HEIGHT = 32, TOP = 14; // dp, Pill's padding
    private static WeakReference<NavIndicator> view;
    private static int selected = -1, count = 1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final OvershootInterpolator settle = new OvershootInterpolator(1.4f);
    private ValueAnimator animator;
    private float from, to, t = 1;
    private boolean dragging, forwarding, pressing;
    private int pressTab;
    private float press;
    private float downX, downY, finger, shown, lift;
    private int hover;
    private long drawn;
    private View nav;
    private MotionEvent tapUp;
    private boolean homeHeld;
    private final Runnable homeHold = new Runnable() {
        public void run() {
            if (!pressing || dragging || pressTab != 0 || !isAttachedToWindow() || !isShown() || !hasWindowFocus()) return;
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0);
            forward(cancel, MotionEvent.ACTION_CANCEL);
            cancel.recycle();
            forwarding = pressing = false;
            homeHeld = true;
            invalidate();
            Haptics.navigation(NavIndicator.this);
            homeMenu();
        }
    };
    private Runnable reviewStart;

    private FrameLayout homeMenuOverlay;
    private ViewGroup homeMenuHost;
    private TextView[] homeOptions;
    private Back homeMenuBack;
    private float menuX, menuY, menuWidth, menuRow, menuPad;
    private int menuHover = -1;
    private ValueAnimator menuMotion;
    private float menuSlideFrom=3, menuSlideTo=3, menuSlideT=1, menuPillAlpha;
    private android.graphics.drawable.Drawable menuSurface;

    private void moveMenuPill(int option) {
        float shown=menuSlideFrom+(menuSlideTo-menuSlideFrom)*settle.getInterpolation(menuSlideT);
        if(menuMotion!=null)menuMotion.cancel();
        menuSlideFrom=shown;
        menuSlideTo=option<0?shown:option;
        final float alphaFrom=menuPillAlpha, alphaTo=option<0?0f:1f;
        if(!Wave.animationsEnabled()) {
            menuSlideFrom=menuSlideTo; menuSlideT=1; menuPillAlpha=alphaTo;
            if(menuSurface!=null)menuSurface.invalidateSelf();
            return;
        }
        menuSlideT=0;
        menuMotion=ValueAnimator.ofFloat(0f,1f);
        menuMotion.setDuration(option<0?120:300);
        menuMotion.addUpdateListener(new ValueAnimator.AnimatorUpdateListener(){
            public void onAnimationUpdate(ValueAnimator animation){
                menuSlideT=((Float)animation.getAnimatedValue()).floatValue();
                menuPillAlpha=alphaFrom+(alphaTo-alphaFrom)*Math.min(1f,menuSlideT*2f);
                if(menuSurface!=null)menuSurface.invalidateSelf();
            }
        });
        menuMotion.start();
    }

    private void homeMenu() {
        View root = getRootView();
        if (!(root instanceof ViewGroup)) { homeHeld=false; return; }
        dismissHomeMenu(false);
        homeMenuHost = (ViewGroup)root;
        float dp = (getResources()!=null?getResources():getContext().getResources()).getDisplayMetrics().density;
        int[] anchor = new int[2], origin = new int[2];
        getLocationOnScreen(anchor); root.getLocationOnScreen(origin);
        menuPad = 6*dp;
        menuRow = 48*dp;
        menuWidth = Math.min(224*dp, root.getWidth()-32*dp);
        float height = 3*menuRow+2*menuPad;
        float gap = 12*dp;
        float centre = anchor[0]-origin[0]+(rtl()?getWidth()-getWidth()/(2f*count):getWidth()/(2f*count));
        menuX = Math.max(16*dp,Math.min(root.getWidth()-16*dp-menuWidth,centre-menuWidth/2));
        menuY = Math.max(16*dp,anchor[1]-origin[1]-height-gap);
        homeMenuOverlay = new FrameLayout(getContext());
        homeMenuOverlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dismissHomeMenu(); }
        });
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding((int)menuPad,(int)menuPad,(int)menuPad,(int)menuPad);
        actions.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {}
        });

        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams((int)menuWidth,(int)height,Gravity.TOP|Gravity.LEFT);
        placement.leftMargin=(int)menuX; placement.topMargin=(int)menuY;
        homeMenuOverlay.addView(actions,placement);
        final float menuDp=dp;
        menuSlideFrom=menuSlideTo=3; menuSlideT=1; menuPillAlpha=0;
        menuSurface=new android.graphics.drawable.Drawable(){
            final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF cardRect=new RectF();
            final RectF pillRect=new RectF();
            public void draw(Canvas canvas){
                float height=3*menuRow+2*menuPad, radius=22*menuDp;
                // Soft ambient drop shadow
                fill.setColor(0x35000000);
                cardRect.set(0,2*menuDp,menuWidth,height+4*menuDp);
                canvas.drawRoundRect(cardRect,radius+2*menuDp,radius+2*menuDp,fill);
                // Elevated tonal card surface matching Spatch floating bars
                fill.setColor(Palette.color(0xf22b2640));
                float stroke=1*menuDp;
                cardRect.set(stroke/2f,stroke/2f,menuWidth-stroke/2f,height-stroke/2f);
                canvas.drawRoundRect(cardRect,radius,radius,fill);
                // Contoured highlight outline
                border.setStyle(Paint.Style.STROKE);
                border.setStrokeWidth(stroke);
                border.setColor(Palette.color(0x33d0bcff));
                canvas.drawRoundRect(cardRect,radius,radius,border);
                if(menuPillAlpha<=0)return;
                float eased=settle.getInterpolation(menuSlideT);
                float[] vertical=edges(menuSlideFrom,menuSlideTo,eased,menuRow,menuRow-6*menuDp);
                float top=menuPad+vertical[0], bottom=menuPad+vertical[1];
                int save=canvas.save();
                canvas.clipRect(menuPad,menuPad,menuWidth-menuPad,height-menuPad);
                fill.setColor(Tint.color(Settings.NAV_COLOR));
                fill.setAlpha(Math.round(0x44*menuPillAlpha));
                pillRect.set(menuPad,top,menuWidth-menuPad,bottom);
                float pillRadius=14*menuDp;
                canvas.drawRoundRect(pillRect,pillRadius,pillRadius,fill);
                border.setColor(Tint.color(Settings.NAV_COLOR));
                border.setAlpha(Math.round(0x28*menuPillAlpha));
                canvas.drawRoundRect(pillRect,pillRadius,pillRadius,border);
                canvas.restoreToCount(save);
            }
            public void setAlpha(int alpha){}
            public void setColorFilter(android.graphics.ColorFilter filter){fill.setColorFilter(filter);}
            public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
        actions.setBackground(menuSurface);
        String[] labels = {"Hide Home sections", "Show all Home sections", "Spatch settings"};
        homeOptions = new TextView[labels.length];
        for (int i=0;i<labels.length;i++) {
            final int action=i;
            TextView row=new TextView(getContext());
            row.setText(labels[i]); row.setTextSize(14.5f);
            row.setTextColor(Palette.color(0xfff3eeff));
            row.setTypeface(Fonts.settings(500));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int)(18*dp),0,(int)(18*dp),0);
            row.setClickable(true); row.setFocusable(true);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { dismissHomeMenu(); homeAction(action); }
            });
            homeOptions[i]=row;
            actions.addView(row,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(int)menuRow));
        }
        homeMenuHost.addView(homeMenuOverlay,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        homeMenuOverlay.bringToFront();
        if (Wave.animationsEnabled()) {
            float pivotX = menuX + (rtl()?menuWidth*0.8f:menuWidth*0.2f);
            float pivotY = menuY + height;
            homeMenuOverlay.setPivotX(pivotX);
            homeMenuOverlay.setPivotY(pivotY);
            homeMenuOverlay.setScaleX(.85f); homeMenuOverlay.setScaleY(.85f);
            homeMenuOverlay.setAlpha(0f);
            homeMenuOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(1.2f)).setDuration(220).start();
        }
        if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
        if (android.os.Build.VERSION.SDK_INT>=33) homeMenuBack=Back.register(homeMenuOverlay,new Runnable(){public void run(){dismissHomeMenu();}});
    }

    /** Outside the menu (including its padding) means release-to-cancel. */
    static int menuOption(float x,float y,float width,float rowHeight) {
        return x>=0 && x<width && y>=0 && y<rowHeight*3 ? (int)(y/rowHeight) : -1;
    }

    private void hoverHomeMenu(MotionEvent e) {
        if (homeMenuHost==null || homeOptions==null) return;
        int[] origin=new int[2]; homeMenuOverlay.getLocationOnScreen(origin);
        float scaleX=Math.max(.01f,homeMenuOverlay.getScaleX());
        float scaleY=Math.max(.01f,homeMenuOverlay.getScaleY());
        int option=menuOption((e.getRawX()-origin[0])/scaleX-menuX-menuPad,
            (e.getRawY()-origin[1])/scaleY-menuY-menuPad,menuWidth-2*menuPad,menuRow);
        if (option==menuHover) return;
        menuHover=option;
        moveMenuPill(option);
        float dp=getResources().getDisplayMetrics().density;
        for (int i=0;i<homeOptions.length;i++) {
            boolean on=i==option;
            homeOptions[i].setTextColor(Palette.color(on?0xffffffff:0xfff3eeff));
            if(Wave.animationsEnabled()) homeOptions[i].animate().scaleX(on?1.025f:1f).scaleY(on?1.025f:1f)
                .translationX(on?(rtl()?-4:4)*dp:0).setInterpolator(new OvershootInterpolator(1.2f)).setDuration(220).start();
        }
        if (option>=0) Haptics.navigation(this);
    }

    private void dismissHomeMenu() { dismissHomeMenu(true); }

    private void dismissHomeMenu(boolean animate) {
        if(menuMotion!=null)menuMotion.cancel();
        menuMotion=null; menuSurface=null;
        if (homeMenuBack!=null) homeMenuBack.unregister();
        homeMenuBack=null;
        if (homeMenuHost!=null && homeMenuOverlay!=null) {
            final ViewGroup host=homeMenuHost;
            final FrameLayout overlay=homeMenuOverlay;
            overlay.animate().cancel();
            if (animate && isAttachedToWindow() && Wave.animationsEnabled()) {
                overlay.animate().alpha(0f).scaleX(.88f).scaleY(.88f).setDuration(120).start();
                host.postDelayed(new Runnable(){public void run(){host.removeView(overlay);}},120);
            } else host.removeView(overlay);
            Flourish.navigationTouch(false);
        }
        homeMenuHost=null; homeMenuOverlay=null; homeOptions=null; menuHover=-1;
        if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void homeAction(int action) {
        if (action==0) reviewHome();
        else if (action==1) new AlertDialog.Builder(getContext(),Palette.dialogTheme())
            .setTitle("Show all Home sections?")
            .setMessage("Restore sections you hid manually. Podcast and audiobook filters stay as they are.")
            .setPositiveButton("Show all",new android.content.DialogInterface.OnClickListener(){
                public void onClick(android.content.DialogInterface d,int which){
                    for(String[] section:Settings.sections())
                        for(int j=1;j<section.length;j++)Settings.hideSection(section[j],false);
                    Content.refresh();
                }
            }).setNegativeButton("Cancel",null).show();
        else if (action==2) Settings.show(this,false);
    }

    private void reviewHome() {
        if (selected != 0) {
            float x = getWidth() / (2f * count);
            if (rtl()) x = getWidth()-x;
            long now = SystemClock.uptimeMillis();
            MotionEvent tap = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, getHeight()/2f, 0);
            nav.dispatchTouchEvent(tap);
            tap.setAction(MotionEvent.ACTION_UP);
            nav.dispatchTouchEvent(tap);
            tap.recycle();
        }
        if (reviewStart != null) removeCallbacks(reviewStart);
        reviewStart = new Runnable() {
            int tries;
            public void run() {
                if (!isAttachedToWindow()) return;
                View root = getRootView();
                if (selected == 0 && root instanceof ViewGroup && HomeReview.start((ViewGroup)root)) {
                    reviewStart = null;
                    return;
                }
                if (++tries < 10) postDelayed(this, 150);
                else {
                    reviewStart = null;
                    new AlertDialog.Builder(getContext(), Palette.dialogTheme()).setTitle("Home is still loading")
                        .setMessage("Wait for Home sections to load, then hold Home and choose Hide Home sections again.")
                        .setPositiveButton("OK", null).show();
                }
            }
        };
        postDelayed(reviewStart, 150);
    }

    public NavIndicator(Context c, AttributeSet a) {
        super(c, a);
        view = new WeakReference<NavIndicator>(this);
        from = to = selected;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** A setting changed while the bar sits behind the settings page. */
    static void refresh() {
        NavIndicator v = view == null ? null : view.get();
        if (v != null) v.invalidate();
    }

    /** The tabs the bar shows, from Spotify's own list each time it is built (hidden ones left out). */
    static void tabs(int total) {
        if (total < 1 || total == count) return;
        count = total;
        refresh();
    }

    /** Called once per real selection change; the first call only records where the app started. */
    static void select(int tab) {
        int was = selected;
        selected = tab;
        Flourish.tab(tab);
        NavIndicator v = view == null ? null : view.get();
        if (v == null || v.dragging) return;
        if (v.animator != null && v.animator.isRunning() && v.to == tab) return; // a drag's release already moved there
        if (was < 0 || !Wave.animationsEnabled() || !v.isAttachedToWindow()) v.snap(tab);
        else {
            Haptics.navigation(v);
            v.move(tab);
        }
    }

    private void snap(float tab) {
        if (animator != null) animator.cancel();
        from = to = tab;
        t = 1;
        invalidate();
    }

    private void move(float tab) {
        from = position();
        if (animator != null) animator.cancel();
        to = tab;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(MILLIS);
        animator.addUpdateListener(this);
        animator.start();
    }

    /** Fractional tab under x, in selection order; the ends clamp to the first and last tabs. */
    static float dragPosition(float x, float width, int tabs, boolean rtl) {
        float cell = width / tabs, at = (rtl ? width - x : x) / cell - .5f;
        return Math.max(0, Math.min(tabs - 1, at));
    }

    private boolean calm() {
        return !Wave.animationsEnabled() || Settings.reduceMotion();
    }

    private boolean rail() {
        return getWidth() > getResources().getDisplayMetrics().heightPixels;
    }

    private boolean rtl() {
        return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private void forward(MotionEvent e, int action) {
        MotionEvent copy = MotionEvent.obtain(e);
        copy.setAction(action);
        nav.dispatchTouchEvent(copy);
        copy.recycle();
    }

    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            nav = ((View) getParent()).findViewById(getResources().getIdentifier("navigation_bar", "id", getContext().getPackageName()));
            if (nav == null || selected < 0 || rail() || !Settings.navStyle()) return false;
            Flourish.navigationTouch(true);
            downX = e.getX();
            downY = e.getY();
            removeCallbacks(homeHold);
            homeHeld = false;
            dragging = false;
            forwarding = pressing = true;
            pressTab = Math.round(dragPosition(downX, getWidth(), count, rtl()));
            if (pressTab == 0) postDelayed(homeHold, ViewConfiguration.getLongPressTimeout());
            invalidate();
            forward(e, action);
            return true;
        }
        if (nav == null) return false;
        if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) Flourish.navigationTouch(false);
        if (homeHeld) {
            if (action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP) hoverHomeMenu(e);
            if (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
                int option=action==MotionEvent.ACTION_UP?menuHover:-1;
                homeHeld=false;
                dismissHomeMenu();
                if (option>=0) homeAction(option);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE &&
            (Math.abs(e.getX()-downX) > ViewConfiguration.get(getContext()).getScaledTouchSlop() ||
             Math.abs(e.getY()-downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop())) removeCallbacks(homeHold);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(homeHold);
            pressing = false;
            invalidate();
        }
        if (action == MotionEvent.ACTION_MOVE && !dragging) {
            float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
            if (dx <= ViewConfiguration.get(getContext()).getScaledTouchSlop() || dx <= dy || !Settings.navDrag()) {
                forward(e, action);
                return true;
            }
            forward(e, MotionEvent.ACTION_CANCEL);
            forwarding = false;
            startDrag();
        } else if (dragging) {
            if (action == MotionEvent.ACTION_UP) release(Math.round(dragPosition(e.getX(), getWidth(), count, rtl())));
            else if (action == MotionEvent.ACTION_CANCEL) release(selected);
        } else if (forwarding) {
            forward(e, action);
            return true;
        }
        if (dragging && action == MotionEvent.ACTION_MOVE) {
            finger = dragPosition(e.getX(), getWidth(), count, rtl());
            int over = Math.round(finger);
            if (over != hover) {
                hover = over;
                Haptics.navigation(this);
            }
            invalidate();
        }
        return true;
    }

    private void startDrag() {
        shown = position();
        if (animator != null) animator.cancel();
        removeCallbacks(this);
        dragging = true;
        pressing = false;
        hover = Math.round(shown);
        drawn = SystemClock.uptimeMillis();
        if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
        scaleBar(BAR_LIFT);
    }

    private void scaleBar(float scale) {
        if (calm() || !Settings.navLift()) return;
        nav.animate().scaleX(scale).scaleY(scale).setDuration(180).start();
        animate().scaleX(scale).scaleY(scale).setDuration(180).start();
    }

    private void release(int tab) {
        dragging = false;
        scaleBar(1);
        from = to = shown;
        t = 1;
        if (calm()) snap(tab);
        else move(tab);
        if (tab == selected) return;
        float x = (tab + .5f) * getWidth() / count, y = getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, rtl() ? getWidth() - x : x, y, 0);
        nav.dispatchTouchEvent(down);
        down.recycle();
        tapUp = MotionEvent.obtain(now, now + TAP_MILLIS, MotionEvent.ACTION_UP, rtl() ? getWidth() - x : x, y, 0);
        postDelayed(this, TAP_MILLIS);
    }

    /** Finishes a release's tap, then returns home if that tab doesn't hold selection (Create opens a sheet). */
    public void run() {
        if (tapUp != null) {
            nav.dispatchTouchEvent(tapUp);
            tapUp.recycle();
            tapUp = null;
            postDelayed(this, CONFIRM_MILLIS);
        } else if (!dragging && to != selected) {
            move(selected);
        }
    }

    /** Interrupted moves restart from wherever the centre is now. */
    private float position() {
        return from + (to - from) * t;
    }

    public void onAnimationUpdate(ValueAnimator a) {
        t = ((Float) a.getAnimatedValue()).floatValue();
        invalidate();
    }

    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Flourish.attach(this);
    }

    static int tab() {
        return selected;
    }

    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Flourish.navigationTouch(false);
        removeCallbacks(homeHold);
        dismissHomeMenu(false);
        if (reviewStart != null) removeCallbacks(reviewStart);
        reviewStart = null;
        homeHeld = false;
        if (animator != null) animator.cancel();
        animator = null;
        removeCallbacks(this);
        if (tapUp != null) tapUp.recycle();
        tapUp = null;
        dragging = forwarding = pressing = false;
        lift = press = 0;
        from = to = selected;
        t = 1;
    }

    static float[] edges(float from, float to, float t, float cell, float width) {
        float lead = settleCurve(Math.min(1, t * 1.7f)), trail = settleCurve(Math.max(0, t * 1.7f - 0.7f));
        boolean right = to >= from;
        float a = (from + (to - from) * (right ? trail : lead) + .5f) * cell - width / 2;
        float b = (from + (to - from) * (right ? lead : trail) + .5f) * cell + width / 2;
        return new float[] {a, b};
    }

    /** Keeps the pill's width: both edges follow the centre at tab position at. */
    private static float[] edges(float from, float to, float t, float cell, float width, float at) {
        float centre = (at + .5f) * cell;
        return new float[] {centre - width / 2, centre + width / 2};
    }

    static float settleCurve(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** Waist pinch (0 at rest, up to 1 mid-flight) from how far the pill has stretched past its resting width. */
    static float gooeyPinch(float span, float width) {
        return span <= width ? 0f : Math.min(1f, (span - width) / Math.max(1f, width * 0.85f));
    }

    /** Viscous metaball outline: two rounded end lobes joined by a pinched waist that snaps flat at pinch = 0. */
    static void gooey(Path path, float left, float top, float right, float bottom, float pinch) {
        float r = (bottom - top) / 2f, yM = (top + bottom) / 2f;
        float xL = left + r, xR = Math.max(xL, right - r), xM = (xL + xR) / 2f;
        float dip = r * 0.62f * Math.max(0f, Math.min(1f, pinch));
        path.reset();
        path.moveTo(xL, top);
        path.quadTo(xM, top + dip, xR, top);
        path.quadTo(right, top, right, yM);
        path.quadTo(right, bottom, xR, bottom);
        path.quadTo(xM, bottom - dip, xL, bottom);
        path.quadTo(left, bottom, left, yM);
        path.quadTo(left, top, xL, top);
        path.close();
    }

    protected void onDraw(Canvas canvas) {
        if (selected < 0 || rail() || !Settings.navStyle()) return; // landscape rail: Pill draws it
        paint.setColor(Tint.color(Settings.NAV_COLOR));
        float d = getResources().getDisplayMetrics().density, cell = getWidth() / (float) count;
        float width = Math.min(WIDTH * d, cell - 2 * TOP * d); // five tabs (free accounts) leave narrow slots: keep off the bar's ends
        boolean rtl = rtl();
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(48, now - drawn), stretch = 0, left, right;
        drawn = now;
        boolean calm = calm();
        if (dragging) {
            shown = calm ? finger : shown + (finger - shown) * (1 - (float) Math.exp(-dt / FOLLOW_MS));
            float lag = (finger - shown) * cell * (rtl ? -1 : 1);
            stretch = Math.min(1, Math.abs(lag) / (STRETCH * d));
            float centre = (rtl ? getWidth() - (shown + .5f) * cell : (shown + .5f) * cell);
            left = centre - width / 2 - (lag < 0 ? stretch * STRETCH * d : 0);
            right = centre + width / 2 + (lag > 0 ? stretch * STRETCH * d : 0);
            if (Math.abs(finger - shown) > .002f) invalidate();
        } else {
            float eased = settle.getInterpolation(t);
            float[] edge = Settings.navStretch() ? edges(from, to, eased, cell, width) : edges(from, to, 1, cell, width, from + (to - from) * eased);
            left = rtl ? getWidth() - edge[1] : edge[0];
            right = rtl ? getWidth() - edge[0] : edge[1];
        }
        float aim = dragging && !calm && Settings.navLift() ? 1 : 0;
        lift += (aim - lift) * (1 - (float) Math.exp(-dt / 70f));
        if (Math.abs(aim - lift) > .01f) invalidate();
        else lift = aim;
        float pressAim = pressing && Settings.navPress() ? 1 : 0;
        press = calm ? pressAim : press + (pressAim - press) * (1 - (float) Math.exp(-dt / 60f));
        if (Math.abs(pressAim - press) > .01f) invalidate();
        else press = pressAim;
        boolean onPill = !dragging && pressTab == Math.round(position());
        if (press > 0 && !onPill) { // state layer on a tab without the pill, growing from its icon
            float half = width * (.4f + .1f * press), centre = (pressTab + .5f) * cell;
            if (rtl) centre = getWidth() - centre;
            paint.setAlpha(Math.round(PRESS_ALPHA * press));
            rect.set(centre - half, TOP * d, centre + half, (TOP + HEIGHT) * d);
            canvas.drawRoundRect(rect, HEIGHT * d / 2, HEIGHT * d / 2, paint);
        }
        float height = (HEIGHT + lift * LIFT_HEIGHT - stretch * SQUASH) * d, middle = (TOP + HEIGHT / 2) * d;
        paint.setAlpha(ALPHA + Math.round(lift * LIFT_ALPHA + (onPill ? press * PRESS_ALPHA : 0)));
        rect.set(left - lift * LIFT_WIDTH * d / 2, middle - height / 2, right + lift * LIFT_WIDTH * d / 2, middle + height / 2);
        float pinch = !calm && Settings.navStretch() ? gooeyPinch(right - left, width) : 0f;
        if (pinch > 0.01f) {
            gooey(path, rect.left, rect.top, rect.right, rect.bottom, pinch);
            canvas.drawPath(path, paint);
        } else {
            canvas.drawRoundRect(rect, height / 2, height / 2, paint);
        }
    }
}
