package spatch;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * A settings icon's tonal tile in one of Material 3 Expressive's shapes, picked per icon so a list
 * reads varied rather than a column of circles. Held, it springs round and turns a little; let go,
 * it wobbles back. Plain circles when the setting is off or motion is reduced.
 */
final class IconTile extends Drawable {
    // {bumps, depth}: cookie, clover, flower, gem, scallop; 0 bumps is the soft square
    private static final float[][] SHAPES = {{9, .07f}, {4, .16f}, {6, .1f}, {0, 0}, {12, .045f}, {5, .12f}};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float bumps, depth;
    private float morph, velocity, target;
    private long last;

    IconTile(int color, String name) {
        paint.setColor(color);
        float[] shape = SHAPES[Math.abs(name == null ? 0 : name.hashCode()) % SHAPES.length];
        bumps = shape[0];
        depth = shape[1];
    }

    static boolean on() {
        return Settings.get(Settings.SHAPED_TILES);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean down = false;
        for (int s : state) if (s == 0x010100a7) down = true; // android.R.attr.state_pressed
        float next = down ? 1f : 0f;
        if (next == target) return false;
        target = next;
        last = 0;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY(), half = Math.min(b.width(), b.height()) / 2f;
        boolean motion = Wave.animationsEnabled();
        if (!on()) {
            c.drawCircle(cx, cy, half, paint);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.032f, (now - last) / 1000f);
        last = now;
        if (motion) { // an underdamped spring: it overshoots a touch on the way back
            velocity += (420f * (target - morph) - 2f * 0.45f * (float) Math.sqrt(420f) * velocity) * dt;
            morph += velocity * dt;
        } else morph = target;
        float d = depth * (1 - Math.max(0, Math.min(1, morph))), turn = morph * 0.6f; // radians
        path.reset();
        int steps = 96;
        for (int i = 0; i <= steps; i++) {
            double a = i * Math.PI * 2 / steps;
            float r;
            if (bumps == 0) { // a squircle that rounds out as it's pressed
                double n = 4 - 2 * Math.max(0, Math.min(1, morph));
                double cos = Math.abs(Math.cos(a)), sin = Math.abs(Math.sin(a));
                r = half * (float) Math.pow(Math.pow(cos, n) + Math.pow(sin, n), -1 / n) * 0.9f;
            } else {
                r = half / (1 + depth) * (1 + d * (float) Math.cos(bumps * a));
            }
            float x = cx + r * (float) Math.cos(a + turn), y = cy + r * (float) Math.sin(a + turn);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        c.drawPath(path, paint);
        if (motion && (Math.abs(target - morph) > 0.002f || Math.abs(velocity) > 0.01f)) invalidateSelf();
        else last = 0;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
