package spatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;

/** Live frame times and a touch inspector, drawn over the app. Touches pass straight through. */
final class DevOverlay extends View implements Choreographer.FrameCallback {
    private static final float JANK_MS = 20f;
    private static DevOverlay shown;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final int[] at = new int[2];
    private final ArrayList<String> lines = new ArrayList<String>();
    private boolean frames, inspect;
    private long last, windowStart;
    private int count, janks;
    private float worst, fps, slowest;
    private int jankTotal;
    private View picked;
    private String[] pickedLines = {};

    private DevOverlay(Context c) {
        super(c, null);
        dp = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    static boolean frames() {
        return shown != null && shown.frames;
    }

    static boolean inspecting() {
        return shown != null && shown.inspect;
    }

    /** Lasts until the app restarts: the overlay lives on the current window. */
    static void set(ViewGroup root, boolean frames, boolean inspect) {
        if (shown == null && (frames || inspect)) {
            shown = new DevOverlay(root.getContext());
            root.addView(shown, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Choreographer.getInstance().postFrameCallback(shown);
        }
        if (shown == null) return;
        shown.frames = frames;
        shown.inspect = inspect;
        shown.picked = null;
        shown.pickedLines = new String[0];
        if (!frames && !inspect) {
            root.removeView(shown);
            shown = null;
        } else {
            shown.bringToFront();
            shown.invalidate();
        }
    }

    public void doFrame(long nanos) {
        if (shown != this) return;
        long now = nanos / 1000000;
        if (last != 0) {
            float gap = now - last;
            worst = Math.max(worst, gap);
            count++;
            if (gap > JANK_MS) {
                janks++;
                jankTotal++;
            }
            if (now - windowStart >= 1000) {
                fps = count * 1000f / (now - windowStart);
                slowest = worst;
                windowStart = now;
                count = 0;
                janks = 0;
                worst = 0;
            }
        } else {
            windowStart = now;
        }
        last = now;
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Sees each new touch, then declines it so the app beneath gets it. */
    public boolean onTouchEvent(MotionEvent e) {
        if (inspect && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            picked = deepest(getRootView(), e.getX(), e.getY());
            pickedLines = describe(picked);
            invalidate();
        }
        return false;
    }

    private View deepest(View view, float x, float y) {
        if (view == this || view.getVisibility() != View.VISIBLE) return null;
        view.getLocationOnScreen(at);
        if (x < at[0] || y < at[1] || x >= at[0] + view.getWidth() || y >= at[1] + view.getHeight()) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View hit = deepest(group.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        return view;
    }

    private String idName(View view) {
        try {
            return view.getId() > 0 ? getResources().getResourceEntryName(view.getId()) : "no id";
        } catch (RuntimeException generated) {
            return "generated id";
        }
    }

    private String[] describe(View view) {
        if (view == null) return new String[0];
        ArrayList<String> out = new ArrayList<String>();
        out.add(view.getClass().getName() + "  " + idName(view));
        out.add(view.getWidth() + " x " + view.getHeight() + " px, " + Math.round(view.getWidth() / dp) + " x " + Math.round(view.getHeight() / dp) + " dp");
        CharSequence label = view.getContentDescription();
        if (label != null) out.add("\"" + label + "\"");
        Object up = view.getParent();
        for (int i = 0; i < 3 && up instanceof View; i++) {
            out.add("in " + up.getClass().getSimpleName());
            up = ((View) up).getParent();
        }
        return out.toArray(new String[0]);
    }

    protected void onDraw(Canvas canvas) {
        lines.clear();
        if (frames) {
            Runtime runtime = Runtime.getRuntime();
            lines.add(Math.round(fps) + " fps, slowest " + Math.round(slowest) + " ms, " + jankTotal + " slow frames");
            lines.add((runtime.totalMemory() - runtime.freeMemory()) / 1048576 + " of " + runtime.maxMemory() / 1048576 + " MB heap");
            lines.add("audio session " + AudioSpectrum.session() + (AudioSpectrum.SHARED.status == null ? "" : ", " + AudioSpectrum.SHARED.status));
        }
        if (inspect && pickedLines.length == 0) lines.add("Touch something to inspect it");
        for (String line : pickedLines) lines.add(line);
        if (picked != null) {
            picked.getLocationOnScreen(at);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * dp);
            paint.setColor(0xffff5ca8);
            canvas.drawRect(at[0], at[1], at[0] + picked.getWidth(), at[1] + picked.getHeight(), paint);
        }
        float size = 11 * dp, top = 44 * dp, gap = size * 1.35f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xcc000000);
        canvas.drawRoundRect(8 * dp, top, getWidth() - 8 * dp, top + gap * lines.size() + 12 * dp, 10 * dp, 10 * dp, paint);
        paint.setColor(0xffffffff);
        paint.setTextSize(size);
        for (int i = 0; i < lines.size(); i++) canvas.drawText(lines.get(i), 16 * dp, top + 4 * dp + gap * (i + 1), paint);
    }
}
