package spatch;

import spatch.ArtworkViews.Art;
import spatch.Controls.PlayButton;
import spatch.SpatchUi.ColorOf;
import spatch.SpatchUi.Icon;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Bottom navigation: the frosted pill, mini player and landscape rail. */
final class Dock {
    private final SpatchUi ui;
    Dock(SpatchUi ui) { this.ui = ui; }

    private static final int[] NAV_TABS = {SpatchUi.HOME, SpatchUi.SEARCH, SpatchUi.LIBRARY, SpatchUi.CREATE};

    private int navHover = -1;

    Navigator dockBar;

    boolean dockHidden, navDragging;

    PlayButton pPlay, mPlay;

    /** GPU effects (Android 12+): the dock's pills are frosted glass over the page; null when they're solid. */
    boolean frosted;
    android.graphics.RenderNode pageNode;

    /**
     * Frosted glass for a dock pill: the page behind it, blurred, under the pill's own colour at partial opacity, with
     * the usual hairline edge. Only the pill's area is blurred; a bitmap snapshot gets the solid colour instead.
     */
    private final class Frost extends android.graphics.drawable.Drawable {
        private final View pill;
        private final float radius;
        private final ColorOf color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final RectF box = new RectF();
        private final int[] at = new int[2], host = new int[2];
        private android.graphics.RenderNode blur;

        Frost(View pill, float radiusDp, ColorOf color) { this.pill = pill; this.radius = ui.dp(radiusDp); this.color = color; }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            box.set(b);
            shape.rewind();
            shape.addRoundRect(box, radius, radius, Path.Direction.CW);
            int tint = color.get();
            paint.setStyle(Paint.Style.FILL);
            if (c.isHardwareAccelerated() && pageNode != null && android.os.Build.VERSION.SDK_INT >= 31) {
                if (blur == null) {
                    blur = new android.graphics.RenderNode("frost");
                    blur.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(ui.dp(18), ui.dp(18), Shader.TileMode.CLAMP));
                }
                pill.getLocationInWindow(at);
                ui.pageHost.getLocationInWindow(host);
                blur.setPosition(0, 0, b.width(), b.height());
                android.graphics.RecordingCanvas into = blur.beginRecording();
                into.drawColor(ui.bg);
                into.translate(host[0] - at[0] - b.left, host[1] - at[1] - b.top);
                into.drawRenderNode(pageNode);
                blur.endRecording();
                c.save();
                c.clipPath(shape);
                c.translate(b.left, b.top);
                c.drawRenderNode(blur);
                c.restore();
                tint = (tint & 0x00ffffff) | 0xb8000000; // the glass: the pill's colour, letting the blurred page through
            }
            paint.setColor(tint);
            c.drawPath(shape, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, ui.dp(.8f)));
            paint.setColor((ui.ink & 0x00ffffff) | 0x16000000);
            c.drawPath(shape, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public void getOutline(android.graphics.Outline outline) { outline.setRoundRect(getBounds(), radius); }
    }

    /** A dock pill's background: frosted glass in GPU mode, else the solid rounded colour with a hairline edge. */
    private void dockPill(View v, float radiusDp, ColorOf color) {
        if (frosted) {
            v.setBackground(new Frost(v, radiusDp, color));
            ui.colored(v, v::invalidate);
        } else ui.bgRound(v, radiusDp, color).setStroke(Math.max(1, ui.dp(.8f)), (ui.ink & 0x00ffffff) | 0x16000000);
    }

    void buildDock() {
        ui.dock.removeAllViews();
        LinearLayout mini = ui.row();
        mini.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        mini.setClipChildren(false);
        mini.setClipToPadding(false);
        // A hairline edge instead of an elevation shadow, which drew unevenly under the rounded, tinted pills.
        dockPill(mini, 30, () -> SpatchUi.blend(ui.surfHi, ui.accent, .12f));
        ui.mArt = ui.artwork.new Art(Art.CIRCLE, true);
        ui.mArt.crossfade = true;
        mini.addView(ui.mArt, ui.lp(ui.dp(46), ui.dp(46)));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(11), 0, ui.dp(6), 0);
        ui.mTitle = ui.text("", 15, () -> ui.ink, true);
        ui.mArtist = ui.text("", 12, () -> ui.inkDim, false);
        ui.oneLine(ui.mTitle); ui.oneLine(ui.mArtist);
        words.addView(ui.mTitle);
        words.addView(ui.mArtist);
        mini.addView(words, ui.weight());
        View prev = ui.playerScreen.skip(ui.iconButton(ui.icon("previous", () -> ui.ink), "Previous", () -> {}), false);
        prev.setOnClickListener(v -> { ui.playerScreen.nudge(prev, false); ui.previousTrack(); });
        mini.addView(prev, ui.lp(ui.dp(44), ui.dp(44)));
        mPlay = ui.controls.new PlayButton();
        mini.addView(mPlay, ui.lp(ui.dp(50), ui.dp(50)));
        View next = ui.playerScreen.skip(ui.iconButton(ui.icon("next", () -> ui.ink), "Next", () -> {}), true);
        next.setOnClickListener(v -> { ui.playerScreen.nudge(next, true); ui.command(PlayerScreen.NEXT, 0, ""); });
        mini.addView(next, ui.lp(ui.dp(44), ui.dp(44)));
        ui.tappable(mini, "Open player", () -> ui.playerScreen.openPlayer(true));
        miniGestures(mini);
        mini.setOnLongClickListener(v -> { ui.currentSongActions(); return true; });
        boolean rail = ui.landscape();
        LinearLayout.LayoutParams mp = ui.fill(-2);
        mp.bottomMargin = ui.dp(8);

        dockBar = new Navigator(rail);
        dockBar.setGravity(Gravity.CENTER);
        dockPill(dockBar, rail ? 32 : 28, () -> ui.surf);
        dockBar.setPadding(ui.dp(5), ui.dp(6), ui.dp(5), ui.dp(6));
        if (rail) {
            // The mini player folds into the rail's foot: the spinning cover (tap for the player, swipe to skip) over play.
            mini.removeAllViews();
            mini.setOrientation(LinearLayout.VERTICAL);
            mini.setGravity(Gravity.CENTER_HORIZONTAL);
            mini.setPadding(0, ui.dp(8), 0, ui.dp(6));
            mini.addView(ui.mArt, ui.lp(ui.dp(42), ui.dp(42)));
            LinearLayout.LayoutParams playAt = ui.lp(ui.dp(46), ui.dp(46));
            playAt.topMargin = ui.dp(4);
            mini.addView(mPlay, playAt);
            ui.dock.addView(dockBar, new LinearLayout.LayoutParams(ui.dp(SpatchUi.RAIL), -2));
            ui.dock.addView(ui.space(1, 0), new LinearLayout.LayoutParams(1, 0, 1));
            ui.dock.addView(mini, new LinearLayout.LayoutParams(ui.dp(SpatchUi.RAIL), -2));
        } else {
            ui.dock.addView(mini, mp);
            ui.dock.addView(dockBar, ui.fill(ui.dp(56)));
        }
        updateDock();
        ui.refresh();
    }

    private void navigate(int destination) {
        if (destination == SpatchUi.CREATE) {
            if (ui.creation.createOverlay == null) ui.creation.openCreateMenu(); else ui.creation.closeCreateMenu();
        } else if (destination == SpatchUi.SETTINGS) ui.openSettings(false);
        else ui.go(destination);
    }

    void updateDock() {
        if (dockBar == null) return;
        String[] names = {"Home", "Search", "Library", "Create"}, glyphs = {"home", "search", "library", "add"};
        if (dockBar.getChildCount() == 0) {
            for (int i = 0; i < names.length; i++) {
                final int index = NAV_TABS[i];
                LinearLayout item = ui.row();
                item.setGravity(Gravity.CENTER);
                item.addView(ui.icon(glyphs[i], () -> ui.ink), ui.lp(ui.dp(25), ui.dp(25)));
                ui.tappable(item, names[i], () -> navigate(index));
                dockBar.addView(item, dockBar.vertical ? new LinearLayout.LayoutParams(-1, ui.dp(46)) : new LinearLayout.LayoutParams(0, ui.dp(46), 1));
            }
        }
        for (int i = 0; i < NAV_TABS.length; i++) if (NAV_TABS[i] == SpatchUi.CREATE) {
            Icon add = (Icon) ((ViewGroup) dockBar.getChildAt(i)).getChildAt(0);
            float targetRotation = ui.creation.createOverlay == null ? 0f : 45f;
            if (!Float.valueOf(targetRotation).equals(add.getTag())) {
                add.setTag(targetRotation);
                add.animate().cancel();
                if (ui.motion()) add.animate().rotation(targetRotation).setDuration(260).setInterpolator(SpatchUi.EMPHASIZED).start();
                else add.setRotation(targetRotation);
            }
            dockBar.getChildAt(i).setContentDescription(ui.creation.createOverlay == null ? "Create" : "Close create menu");
        }
        SpatchUi.recolorView(dockBar);
        dockBar.setDestination(ui.creation.createOverlay != null ? SpatchUi.CREATE : ui.playlist == null ? ui.tab : -1);
    }

    final class DockScroll implements android.widget.AbsListView.OnScrollListener {
        private int lastFirst = -1, lastTop;
        public void onScrollStateChanged(android.widget.AbsListView view, int state) {}
        public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
            if (view.getChildCount() == 0) return;
            int top = view.getChildAt(0).getTop();
            int delta = lastFirst < 0 ? 0 : first == lastFirst ? lastTop - top : first > lastFirst ? ui.dp(48) : -ui.dp(48);
            lastFirst = first; lastTop = top;
            scrollDock(delta, first == 0 && top >= view.getPaddingTop());
        }
    }

    final class Navigator extends LinearLayout {
        private float startX, startY, indicatorX = -1, indicatorTarget = -1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.animation.ValueAnimator slide;
        private boolean draggingSlide;
        private long lastTickMs;
        private int destination = SpatchUi.HOME;
        /** Down a side rail (landscape) instead of across the bottom: the indicator, drags and taps follow the long axis. */
        final boolean vertical;
        Navigator(boolean vertical) { super(ui.activity); this.vertical = vertical; setOrientation(vertical ? VERTICAL : HORIZONTAL); }
        private float along(MotionEvent e) { return vertical ? e.getY() : e.getX(); }
        private float across(MotionEvent e) { return vertical ? e.getX() : e.getY(); }
        private float length() { return vertical ? getHeight() : getWidth(); }
        private float middle(View child) { return vertical ? (child.getTop() + child.getBottom()) / 2f : (child.getLeft() + child.getRight()) / 2f; }

        void setDestination(int target) {
            destination = target;
            if (navDragging || length() == 0) return;
            float x = -1;
            for (int i = 0; i < getChildCount(); i++) if (NAV_TABS[i] == target) {
                x = middle(getChildAt(i));
            }
            settleIndicator(x);
        }
        private void settleIndicator(float x) {
            if (x == indicatorTarget && !draggingSlide) return;
            indicatorTarget = x;
            draggingSlide = false;
            if (slide != null) slide.cancel();
            if (!ui.motion() || indicatorX < 0 || x < 0) { indicatorX = x; invalidate(); return; }
            slide = android.animation.ValueAnimator.ofFloat(indicatorX, x);
            slide.setDuration(240);
            slide.setInterpolator(SpatchUi.EMPHASIZED);
            slide.addUpdateListener(a -> { indicatorX = (float) a.getAnimatedValue(); invalidate(); });
            slide.start();
        }
        private void chaseIndicator(float x) {
            indicatorTarget = x;
            if (!ui.motion() || indicatorX < 0 || x < 0) { indicatorX = x; invalidate(); return; }
            if (slide != null && slide.isRunning() && draggingSlide) return;
            if (slide != null) slide.cancel();
            draggingSlide = true;
            lastTickMs = -1;
            slide = android.animation.ValueAnimator.ofFloat(0f, 1f);
            slide.setDuration(1000);
            slide.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            slide.setInterpolator(new android.view.animation.LinearInterpolator());
            slide.addUpdateListener(a -> {
                long now = a.getCurrentPlayTime();
                if (lastTickMs < 0) {
                    lastTickMs = now;
                    if (now == 0) return;
                }
                long rawDt = now >= lastTickMs ? now - lastTickMs : (1000 - lastTickMs) + now;
                lastTickMs = now;
                if (rawDt == 0) return;
                long dt = Math.min(50, rawDt);
                float alpha = 1f - (float) Math.exp(-dt * 0.032f);
                float next = indicatorX + (indicatorTarget - indicatorX) * alpha;
                if (Math.abs(indicatorTarget - next) < 0.5f) {
                    indicatorX = indicatorTarget;
                    invalidate();
                    if (!navDragging) { draggingSlide = false; a.cancel(); }
                } else {
                    indicatorX = next;
                    invalidate();
                }
            });
            slide.start();
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            setDestination(destination);
        }
        private float indicatorHalf() {
            float room = vertical ? getHeight() - getPaddingTop() - getPaddingBottom() : getWidth() - getPaddingLeft() - getPaddingRight();
            return Math.min(vertical ? ui.dp(26) : ui.dp(34), room / (2f * Math.max(1, getChildCount())) - ui.dp(3));
        }
        @Override protected void dispatchDraw(Canvas canvas) {
            if (indicatorX >= 0) {
                paint.setColor(SpatchUi.blend(ui.surf, ui.accent, navDragging ? .38f : .25f));
                float half = indicatorHalf();
                if (vertical) canvas.drawRoundRect(ui.dp(6), indicatorX - half, getWidth() - ui.dp(6), indicatorX + half, ui.dp(22), ui.dp(22), paint);
                else canvas.drawRoundRect(indicatorX - half, ui.dp(8), indicatorX + half, getHeight() - ui.dp(8), ui.dp(22), ui.dp(22), paint);
            }
            super.dispatchDraw(canvas);
        }
        @Override protected void onDetachedFromWindow() { if (slide != null) slide.cancel(); draggingSlide = false; super.onDetachedFromWindow(); }
        private void follow(MotionEvent event) {
            float edge = Math.max(ui.dp(24), (vertical ? getPaddingTop() : getPaddingLeft()) + indicatorHalf() + ui.dp(3));
            chaseIndicator(Math.max(edge, Math.min(length() - edge, along(event))));
            int target = targetAt(event);
            if (target >= 0 && target != navHover) {
                navHover = target;
                if (ui.pref("haptics", true)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (!ui.pref("nav_drag", true)) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                startX = along(event); startY = across(event); navDragging = false;
            } else if (action == MotionEvent.ACTION_MOVE
                && Math.abs(along(event) - startX) > ui.dp(6)
                && Math.abs(along(event) - startX) >= Math.abs(across(event) - startY)) {
                navDragging = true;
                follow(event);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                navDragging = false;
            }
            return navDragging;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && ui.pref("nav_drag", true)) {
                startX = along(event); startY = across(event); navDragging = false;
                return true;
            }
            if (!navDragging) {
                if (ui.pref("nav_drag", true) && action == MotionEvent.ACTION_MOVE
                    && Math.abs(along(event) - startX) > ui.dp(6)
                    && Math.abs(along(event) - startX) >= Math.abs(across(event) - startY)) {
                    navDragging = true;
                    follow(event);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (action == MotionEvent.ACTION_UP) {
                    int target = targetAt(event);
                    if (target >= 0) navigate(target);
                    return true;
                }
                return super.onTouchEvent(event);
            }
            if (action == MotionEvent.ACTION_MOVE) follow(event);
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                int target = targetAt(event);
                navHover = -1; navDragging = false;
                if (action == MotionEvent.ACTION_UP && target >= 0) navigate(target);
                updateDock();
            }
            return true;
        }
        private int targetAt(MotionEvent event) {
            int count = getChildCount();
            if (count == 0) return -1;
            float slop = ui.dp(16), cross = vertical ? getWidth() : getHeight();
            if (across(event) < -slop || across(event) > cross + slop) return -1;
            if (along(event) < 0 || along(event) > length()) return -1;
            View first = getChildAt(0), last = getChildAt(count - 1);
            float at = Math.max(vertical ? first.getTop() : first.getLeft(), Math.min(vertical ? last.getBottom() : last.getRight(), along(event)));
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (at >= (vertical ? child.getTop() : child.getLeft()) && at <= (vertical ? child.getBottom() : child.getRight())) return NAV_TABS[i];
            }
            return -1;
        }
    }

    void setDockHidden(boolean hidden) {
        if (ui.dock == null) return;
        hidden = hidden && ui.pref("auto_hide_dock", false) && !ui.playerOpen && !ui.landscape(); // the rail covers nothing
        if (dockHidden == hidden) return;
        dockHidden = hidden;
        float target = hidden ? ui.dock.getHeight() + ui.dp(24) : 0;
        ui.dock.animate().cancel();
        ui.dock.animate().translationY(target).alpha(hidden ? 0 : 1).setDuration(ui.motion() ? 240 : 0).setInterpolator(SpatchUi.EMPHASIZED).start();
        if (ui.dockFade != null) ui.dockFade.animate().alpha(hidden ? 0 : 1).setDuration(ui.motion() ? 240 : 0).start();
    }

    void scrollDock(int delta, boolean atTop) {
        if (!ui.pref("auto_hide_dock", false) || ui.playerOpen || ui.sheetClose != null || ui.creation.createOverlay != null || ui.playerScreen.queuePanel != null) return;
        if (atTop) { ui.scrollTravel = 0; setDockHidden(false); return; }
        if (Integer.signum(delta) != Integer.signum(ui.scrollTravel)) ui.scrollTravel = 0;
        ui.scrollTravel += delta;
        if (ui.scrollTravel > ui.dp(32)) setDockHidden(true);
        else if (ui.scrollTravel < -ui.dp(18)) setDockHidden(false);
    }

    /** The mini player follows a sideways drag; a flick left or right skips, a flick up opens the player. */
    private void miniGestures(View mini) {
        final float[] down = new float[2];
        final boolean[] flung = new boolean[1];
        android.view.GestureDetector detector = new android.view.GestureDetector(ui.activity, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (dy < -ui.dp(28) && Math.abs(dy) > Math.abs(dx)) { flung[0] = true; ui.playerScreen.openPlayer(true); }
                else if (Math.abs(dx) > ui.dp(48) && Math.abs(dx) > Math.abs(dy)) {
                    flung[0] = true;
                    if (dx < 0) ui.command(PlayerScreen.NEXT, 0, ""); else ui.previousTrack();
                    ui.tick(mini);
                }
                return flung[0];
            }
        });
        mini.setOnTouchListener((v, e) -> {
            detector.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY(); flung[0] = false;
                    if (ui.motion()) v.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0];
                    if (ui.motion() && Math.abs(dx) > Math.abs(e.getRawY() - down[1])) v.setTranslationX(dx * .35f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (ui.motion()) v.animate().translationX(0).scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
                    else v.setTranslationX(0);
                    if (flung[0]) { v.setPressed(false); return true; } // a flick isn't also a tap
                    break;
            }
            return false;
        });
    }
}
