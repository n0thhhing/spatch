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
        float fade = (1-t)*(1-t);
        paint.setAlpha(Math.round(150*fade));
        paint.setStyle(Paint.Style.FILL);
        // Uneven, small droplets read as a release, rather than a radial confetti burst.
        for (int i = 0; i < 5; i++) {
            double angle = Math.toRadians(-155 + i*31f);
            float speed = (i % 2 == 0 ? 78 : 108);
            float dx = (float)Math.cos(angle)*speed*seconds*dp;
            float dy = dropY(seconds, (float)Math.sin(angle)*speed)*dp;
            float radius = (i % 2 == 0 ? 1.5f : 2f)*(1-t*.7f)*dp;
            c.drawCircle(x+dx, y+dy, radius, paint);
        }
        // One quiet surface ripple; no stacked rings competing with the playhead.
        float radius = (3 + 12*(1-(1-t)*(1-t)))*dp;
        paint.setAlpha(Math.round(95*fade));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp);
        c.drawRoundRect(x-radius, y-radius*.22f, x+radius, y+radius*.22f,
            radius, radius*.22f, paint);
    }

    public void setAlpha(int alpha) {}
    public void setColorFilter(ColorFilter filter) {}
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
