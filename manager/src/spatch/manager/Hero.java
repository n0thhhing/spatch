package spatch.manager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * The page header: a gradient card in the wallpaper's colours with a slowly drifting wave, the page's
 * title, a line under it and a scalloped cookie that turns on its own and spins when tapped.
 */
final class Hero extends View {
    private final Look look;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();
    private String title = "Spatch", line = "";
    private float angle, spin, press = 1f, pressV, phase, titleShift;
    private long last;

    Hero(Context context, Look look) {
        super(context);
        this.look = look;
        ink.setColor(look.onPrimaryContainer);
        ink.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        ink.setTextSize(look.dp(34));
        small.setColor(Look.blend(look.onPrimaryContainer, look.primaryContainer, 0.25f));
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setTextSize(look.dp(14));
        setContentDescription("Spatch");
        setClickable(true);
    }

    void set(String title, String line) {
        if (!title.equals(this.title) && Look.motion(getContext())) titleShift = look.dp(18);
        this.title = title;
        this.line = line == null ? "" : line;
        setContentDescription(title + ". " + this.line);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            pressV -= 2.5f; // squish under the finger
            invalidate();
        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            spin += 540f; // degrees per second, decaying
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            performClick();
            invalidate();
        }
        return true;
    }

    private void cookie(Canvas c, float cx, float cy, float r, float turn, int bumps, int color) {
        path.reset();
        for (int i = 0; i <= 180; i++) {
            double a = i * Math.PI * 2 / 180;
            float rr = r * (1f + 0.08f * (float) Math.cos(a * bumps));
            double b = a + Math.toRadians(turn);
            float x = cx + rr * (float) Math.cos(b), y = cy + rr * (float) Math.sin(b);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean motion = Look.motion(getContext());
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        float w = getWidth(), h = getHeight(), r = look.dp(28);
        // press: a spring back to 1
        pressV += (-(press - 1f) * 320f - pressV * 18f) * dt;
        press += pressV * dt;
        spin *= (float) Math.exp(-dt * 2.2f);
        angle = (angle + (motion ? 8f + spin : 0f) * dt) % 360f;
        phase += motion ? dt * 0.6f : 0f;
        titleShift *= (float) Math.exp(-dt * 12f);

        c.save();
        c.scale(press, press, w / 2f, h / 2f);
        box.set(0, 0, w, h);
        fill.setShader(new LinearGradient(0, 0, w, h, look.primaryContainer, Look.blend(look.primaryContainer, look.primary, 0.38f), Shader.TileMode.CLAMP));
        c.drawRoundRect(box, r, r, fill);
        fill.setShader(null);
        c.save();
        path.reset();
        path.addRoundRect(box, r, r, Path.Direction.CW);
        c.clipPath(path);
        for (int layer = 0; layer < 2; layer++) {
            path.reset();
            float base = h * (layer == 0 ? 0.74f : 0.84f), amp = look.dp(layer == 0 ? 9 : 6);
            path.moveTo(0, h);
            for (float x = 0; x <= w; x += look.dp(6)) {
                path.lineTo(x, base + amp * (float) Math.sin(x / w * Math.PI * 2 * (layer == 0 ? 1.2 : 1.7) + phase * (layer == 0 ? 1 : 1.3)));
            }
            path.lineTo(w, h);
            path.close();
            fill.setColor(look.onPrimaryContainer & 0xffffff | (layer == 0 ? 0x10000000 : 0x18000000));
            c.drawPath(path, fill);
        }
        // the cookie: two scalloped layers turning against each other
        float cx = w - h * 0.42f, cy = h * 0.46f, cr = h * 0.36f;
        cookie(c, cx, cy, cr, angle, 9, look.primary & 0xffffff | 0x48000000);
        cookie(c, cx, cy, cr * 0.58f, -angle * 1.6f, 6, look.onPrimaryContainer & 0xffffff | 0x30000000);
        c.restore();

        float left = look.dp(22);
        ink.setAlpha(Math.round(255 * (1f - titleShift / look.dp(18) * 0.8f)));
        c.drawText(title, left, h * 0.42f + titleShift, ink);
        c.drawText(line, left, h * 0.42f + look.dp(24), small);
        c.restore();

        boolean settling = Math.abs(pressV) > 0.01f || Math.abs(press - 1f) > 0.001f || spin > 1f || titleShift > 0.3f;
        if (settling) postInvalidateOnAnimation();
        else if (motion && isShown()) postInvalidateDelayed(40); // the slow drift doesn't need every frame
        else last = 0;
    }
}
