package spatch.manager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.Switch;

/**
 * A Material 3 switch drawn like Spatch's own: a filled track when on, an outlined one when off, a
 * thumb that grows and shows a check. With playful motion the thumb springs across and stretches
 * like jelly on the way. It stays a Switch, so callers check and listen as before.
 */
final class M3Switch extends Switch {
    private final Look look;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float shown, velocity;
    private long last;

    M3Switch(Context context, Look look) {
        super(context);
        this.look = look;
        setBackground(null);
        setThumbDrawable(null);
        setTrackDrawable(null);
    }

    @Override
    public void onMeasure(int w, int h) {
        setMeasuredDimension(look.dp(52), look.dp(32));
    }

    @Override
    public void setChecked(boolean checked) {
        boolean was = isChecked();
        super.setChecked(checked);
        if (look == null) return; // the constructor's own first call
        if (!isLaidOut() || !Look.motion(getContext())) shown = checked ? 1 : 0;
        else if (was != checked) last = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float target = isChecked() ? 1 : 0;
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.032f, (now - last) / 1e9f);
        last = now;
        velocity += (500f * (target - shown) - 2f * 0.5f * (float) Math.sqrt(500f) * velocity) * dt;
        shown += velocity * dt;
        boolean moving = Math.abs(target - shown) > 0.002f || Math.abs(velocity) > 0.01f;
        if (!moving) {
            shown = target;
            velocity = 0;
        }
        float t = Math.max(0, Math.min(1, shown)), w = getWidth(), h = getHeight(), d = look.dp(1);
        float alpha = isEnabled() ? 1f : 0.38f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Look.blend(look.containerHigh, look.primary, t));
        paint.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(d, d, w - d, h - d, h / 2, h / 2, paint);
        if (t < 1) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * d);
            paint.setColor(look.outline);
            paint.setAlpha(Math.round(255 * alpha * (1 - t)));
            c.drawRoundRect(2 * d, 2 * d, w - 2 * d, h - 2 * d, h / 2, h / 2, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        float cx = h / 2 + (w - h) * shown, cy = h / 2, r = (8 + 4 * t) * d;
        paint.setColor(t > 0.5f ? look.onPrimary : look.outline);
        paint.setAlpha(Math.round(255 * alpha));
        float stretch = Math.min(1.6f, 1f + Math.abs(velocity) * 0.05f), rx = r * stretch, ry = r / (float) Math.sqrt(stretch);
        c.drawRoundRect(cx - rx, cy - ry, cx + rx, cy + ry, ry, ry, paint);
        if (t > 0.5f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(look.primary);
            paint.setAlpha(Math.round(255 * alpha * (t - 0.5f) * 2));
            c.drawLine(cx - 4.5f * d, cy, cx - 1.5f * d, cy + 3 * d, paint);
            c.drawLine(cx - 1.5f * d, cy + 3 * d, cx + 4.5f * d, cy - 3 * d, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        if (moving) postInvalidateOnAnimation();
        else last = 0;
    }
}
