package spatch;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;

/**
 * A live theme change as one smooth reveal: the screen is copied just before every page rebuilds in
 * the new colours, the copy is laid back over the rebuilt screen, and a circle opens in it from the
 * swatch you tapped until only the new theme shows. The settings page comes back where it was, so the
 * two line up and nothing seems to reload.
 */
final class ThemeSwitch extends View {
    private static final long MILLIS = 560;
    private static Bitmap shot;
    private static float fromX, fromY;
    /** The theme page's scroll when the switch began, for the page that replaces it; -1 when none. */
    static int scroll = -1;

    private final Bitmap picture;
    private final float x, y, reach;
    private final Path hole = new Path();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long start;

    private ThemeSwitch(android.content.Context context, Bitmap picture, float x, float y, float width, float height) {
        super(context);
        this.picture = picture;
        this.x = x;
        this.y = y;
        reach = ThemeReveal.reach(width, height, x, y);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * context.getResources().getDisplayMetrics().density);
        ring.setColor(Palette.color(0xffd0bcff)); // drawn after the switch: the new accent
    }

    /** Copies the window as it is, then runs then; without a copy the switch simply happens. */
    static void capture(Activity activity, float x, float y, final Runnable then) {
        shot = null;
        View decor = activity == null ? null : activity.getWindow().getDecorView();
        if (decor == null || decor.getWidth() == 0 || android.os.Build.VERSION.SDK_INT < 26 || !Wave.animationsEnabled()) {
            then.run();
            return;
        }
        fromX = x;
        fromY = y;
        final Bitmap copy = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(activity.getWindow(), copy, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                public void onPixelCopyFinished(int result) {
                    if (result == android.view.PixelCopy.SUCCESS) shot = copy;
                    then.run();
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (RuntimeException e) {
            then.run();
        }
    }

    /** Lays the copy over a rebuilt window and opens it; the copy is spent once shown. */
    static void cover(ViewGroup root) {
        Bitmap picture = shot;
        shot = null;
        if (picture == null || root == null) return;
        ThemeSwitch view = new ThemeSwitch(root.getContext(), picture, fromX, fromY, picture.getWidth(), picture.getHeight());
        root.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.bringToFront();
    }

    /** Drops a copy that was never shown, as when the switch fell back to a restart. */
    static void forget() {
        shot = null;
        scroll = -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = android.os.SystemClock.uptimeMillis();
        if (start == 0) start = now;
        float t = Math.min(1f, (now - start) / (float) MILLIS);
        float eased = 1 - (1 - t) * (1 - t) * (1 - t);
        float r = reach * eased;
        hole.reset();
        hole.addCircle(x, y, r, Path.Direction.CW);
        int saved = canvas.save();
        canvas.clipOutPath(hole);
        canvas.drawBitmap(picture, 0, 0, null);
        canvas.restoreToCount(saved);
        if (r > 0) {
            ring.setAlpha(Math.round(255 * (1 - t)));
            canvas.drawCircle(x, y, r, ring);
        }
        if (t < 1) {
            postInvalidateOnAnimation();
        } else {
            post(new Runnable() {
                public void run() {
                    if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(ThemeSwitch.this);
                    picture.recycle();
                }
            });
        }
    }
}
