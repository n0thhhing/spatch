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
    private boolean offscreen, held;
    private float touchX, touchY, shapeX, shapeY, extraSpin;

    SettingsHero(Context c) {
        super(c, null);
        setContentDescription("Spatch for Spotify " + Palette.spotifyVersion(c));
        setClickable(true); // a press needs its release for the spring
        Springy.attach(this);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (Settings.get(Settings.HERO_TOUCH)) {
            held = e.getActionMasked() != android.view.MotionEvent.ACTION_UP && e.getActionMasked() != android.view.MotionEvent.ACTION_CANCEL;
            touchX = e.getX();
            touchY = e.getY();
            if (held) getParent().requestDisallowInterceptTouchEvent(true); // the page's scroll would cancel the drag
        }
        return super.dispatchTouchEvent(e);
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
        float restX = w - 64 * dp, restY = h * 0.42f, base = 54 * dp;
        if (shapeX == 0) {
            shapeX = restX;
            shapeY = restY;
        }
        // The shape chases a held finger, spins up with the chase, then drifts home.
        float wantX = held ? touchX : restX, wantY = held ? touchY : restY, chase = motion ? 0.16f : 1;
        extraSpin += (Math.abs(wantX - shapeX) + Math.abs(wantY - shapeY)) / (200 * dp);
        shapeX += (wantX - shapeX) * chase;
        shapeY += (wantY - shapeY) * chase;
        float cx = shapeX, cy = shapeY, spin = time * 0.25f + extraSpin;
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
        paint.setTextSize(36 * dp);
        c.drawText("Spatch", 22 * dp, 56 * dp, paint);

        // Expressive Material 3 version pill badge
        String badge = "v@VERSION@ • Material You"; // the patcher's version, stamped in at build time
        paint.setTypeface(Fonts.settings(700));
        paint.setTextSize(11 * dp);
        float badgeX = 22 * dp, badgeY = 68 * dp, badgeW = paint.measureText(badge) + 24 * dp, badgeH = 22 * dp, badgeR = 11 * dp;
        paint.setColor(ink);
        paint.setAlpha(36);
        c.drawRoundRect(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, badgeR, badgeR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1 * dp);
        paint.setColor(0x22ffffff);
        c.drawRoundRect(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH, badgeR, badgeR, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ink);
        paint.setAlpha(210);
        paint.setTypeface(Fonts.settings(700));
        paint.setTextSize(11 * dp);
        c.drawText(badge, badgeX + 12 * dp, badgeY + 15 * dp, paint);
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
