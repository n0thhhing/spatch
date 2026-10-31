package spatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Decorative card at the top of settings: a slowly turning scallop over two drifting waves. */
final class SettingsHero extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int accent = Palette.color(0xffd0bcff), deep = Palette.color(0xffb7a3ee),
        ink = Palette.color(0xff14121c);
    private boolean offscreen;

    SettingsHero(Context c) {
        super(c, null);
        setContentDescription("Spatch for Spotify 9.1.98.521");
    }

    /** Scrolled out of view: stops the frame loop until it returns. */
    void setOffscreen(boolean value) {
        if (offscreen == value) return;
        offscreen = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float dp = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight(), radius = 28 * dp;
        boolean motion = Wave.animationsEnabled();
        float time = motion ? (SystemClock.uptimeMillis() % 240000L) / 1000f : 0;
        int saved = c.save();
        path.reset();
        path.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
        c.clipPath(path);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, w, h, new int[] {accent, deep}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Nine-lobed scallop, the shape of the play button.
        float cx = w - 64 * dp, cy = h * 0.42f, base = 54 * dp, spin = time * 0.25f;
        path.reset();
        for (int i = 0; i <= 90; i++) {
            double a = i * Math.PI * 2 / 90;
            float r = base * (1 + 0.09f * (float) Math.cos(9 * a));
            float x = cx + r * (float) Math.cos(a + spin), y = cy + r * (float) Math.sin(a + spin);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        paint.setColor(ink);
        paint.setAlpha(36);
        c.drawPath(path, paint);

        wave(c, w, h, h * 0.74f, 10 * dp, 0.016f / dp, time * 0.9f, 44);
        wave(c, w, h, h * 0.84f, 7 * dp, 0.024f / dp, -time * 1.3f, 70);

        paint.setColor(ink);
        paint.setAlpha(255);
        paint.setTypeface(Fonts.settings(700));
        paint.setTextSize(38 * dp);
        c.drawText("Spatch", 22 * dp, 58 * dp, paint);
        paint.setTypeface(Fonts.settings(400));
        paint.setTextSize(13 * dp);
        paint.setAlpha(190);
        c.drawText("for Spotify 9.1.98.521", 23 * dp, 80 * dp, paint);
        c.restoreToCount(saved);
        if (motion && !offscreen) postInvalidateOnAnimation();
    }

    private void wave(Canvas c, float w, float h, float y, float height, float k, float phase, int alpha) {
        path.reset();
        path.moveTo(0, h);
        for (float x = 0; x <= w + 8; x += 8) path.lineTo(x, y + height * (float) Math.sin(x * k + phase));
        path.lineTo(w, h);
        path.close();
        paint.setColor(ink);
        paint.setAlpha(alpha);
        c.drawPath(path, paint);
    }
}
