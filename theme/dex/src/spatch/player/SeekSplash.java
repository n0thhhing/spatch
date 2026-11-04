package spatch;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

/** A bounded, 320 ms overlay: no frame allocations, and no callbacks after it finishes or hides. */
final class SeekSplash extends Drawable implements Runnable {
    static int live;
    private static final long MILLIS = 320;
    private final View root;
    private final Paint paint = new Paint(3);
    private final float x, y, dp;
    private final long started = SystemClock.uptimeMillis();
    private boolean finished;

    private SeekSplash(View root, float x, float y, float dp) {
        this.root = root; this.x = x; this.y = y; this.dp = dp;
    }

    static void at(View host, float x, float y) {
        if (!Settings.get(Settings.SEEK_SPLASH) || !Wave.animationsEnabled() || !host.isShown() || !host.hasWindowFocus() || live >= 2) return;
        View root = host.getRootView();
        int[] at = new int[2], origin = new int[2];
        host.getLocationOnScreen(at); root.getLocationOnScreen(origin);
        SeekSplash splash = new SeekSplash(root, x+at[0]-origin[0], y+at[1]-origin[1], host.getResources().getDisplayMetrics().density*Settings.strength(Settings.SPLASH_SIZE)/100f);
        splash.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(splash);
        live++;
        splash.run();
    }

    static float dropY(float seconds, float velocity) {
        return velocity*seconds + 450*seconds*seconds;
    }

    public void run() {
        if (finished) return;
        if (SystemClock.uptimeMillis()-started >= MILLIS || !Wave.animationsEnabled() || !Settings.get(Settings.SEEK_SPLASH) || !root.isShown() || !root.hasWindowFocus()) {
            finish();
            return;
        }
        invalidateSelf();
        root.postDelayed(this, 16);
    }

    void finish() {
        if (finished) return;
        finished = true;
        root.removeCallbacks(this);
        root.getOverlay().remove(this);
        live--;
    }

    public void draw(Canvas c) {
        float t = Math.max(0, Math.min(1, (SystemClock.uptimeMillis()-started)/(float)MILLIS)), seconds = t*.32f;
        paint.setColor(Tint.color(Settings.PLAYER_COLOR));
        paint.setAlpha(Math.round(210*(1-t)));
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(-140 + i*100f/7);
            float speed = 150+i*5;
            c.drawCircle(x+(float)Math.cos(angle)*speed*seconds*dp, y+dropY(seconds, (float)Math.sin(angle)*speed)*dp, 2.5f*(1-t)*dp, paint);
        }
        float radius = 14*(1-(1-t)*(1-t))*dp;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth((1.5f-t)*dp);
        c.drawRoundRect(x-radius, y-radius*.3f, x+radius, y+radius*.3f, radius, radius*.3f, paint);
        radius *= .6f;
        c.drawRoundRect(x-radius, y-radius*.3f, x+radius, y+radius*.3f, radius, radius*.3f, paint);
    }

    public void setAlpha(int alpha) {}
    public void setColorFilter(ColorFilter filter) {}
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
