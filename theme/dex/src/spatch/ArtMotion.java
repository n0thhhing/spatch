package spatch;

import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.media.session.MediaController;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/** Playing with the artwork: it tilts toward a finger, and held still it becomes a record to scratch through the song. */
final class ArtMotion implements Runnable {
    private static final long HOLD = 380, MILLIS_PER_TURN = 30000, DETENT = 5000;
    private static final float TILT = 11f, SPIN_SPRING = 0.18f;

    private final PlayerArt art;
    private final float[] tiltX = {0, 0}, tiltY = {0, 0};
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float targetX, targetY, downX, downY, fingerX, fingerY, lastAngle, spin;
    private long drawnAt, base, duration, preview;
    private int detent;
    private boolean down, moved;
    boolean scratching;

    ArtMotion(PlayerArt art) {
        this.art = art;
    }

    static float angleStep(float from, float to) {
        float step = to - from;
        while (step > 180) step -= 360;
        while (step < -180) step += 360;
        return step;
    }

    static long scrubbed(long base, float degrees, long duration) {
        return Math.max(0, Math.min(duration, base + (long) (degrees / 360f * MILLIS_PER_TURN)));
    }

    /** Sees every touch on the artwork; takes none of them until a hold becomes a scratch. */
    void touch(MotionEvent e) {
        if (!Wave.animationsEnabled()) return;
        int action = e.getActionMasked();
        fingerX = e.getX();
        fingerY = e.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            down = true;
            moved = false;
            downX = fingerX;
            downY = fingerY;
            if (Settings.get(Settings.ART_SCRATCH) && SpatchWidget.controller() != null) art.postDelayed(this, HOLD);
        } else if (action == MotionEvent.ACTION_MOVE && !moved && Math.hypot(fingerX - downX, fingerY - downY) > 24) {
            moved = true;
            art.removeCallbacks(this);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            down = false;
            art.removeCallbacks(this);
            targetX = targetY = 0;
            if (scratching) finish(action == MotionEvent.ACTION_UP);
            return;
        }
        if (scratching) {
            float angle = angle();
            spin += angleStep(lastAngle, angle);
            lastAngle = angle;
            preview = scrubbed(base, spin, duration);
            int step = (int) (preview / DETENT);
            if (step != detent) {
                detent = step;
                Haptics.detent(art);
            }
        }
        float half = Math.max(1, art.getWidth() / 2f);
        if (Settings.get(Settings.ART_TILT) && !scratching) {
            targetX = Math.max(-1, Math.min(1, (fingerY - half) / half)) * -TILT;
            targetY = Math.max(-1, Math.min(1, (fingerX - half) / half)) * TILT;
        }
    }

    /** The finger stayed still long enough. */
    public void run() {
        if (!down || moved) return;
        android.media.MediaMetadata metadata = SpatchWidget.controller() == null ? null : SpatchWidget.controller().getMetadata();
        duration = metadata == null ? 0 : metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        if (duration <= 0) return;
        base = preview = SpatchWidget.position();
        detent = (int) (base / DETENT);
        lastAngle = angle();
        spin = 0;
        targetX = targetY = 0;
        scratching = true;
        ViewParent parent = art.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        Haptics.toggle(art, true);
    }

    private float angle() {
        return (float) Math.toDegrees(Math.atan2(fingerY - art.getHeight() / 2f, fingerX - art.getWidth() / 2f));
    }

    private void finish(boolean seek) {
        scratching = false;
        ViewParent parent = art.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        MediaController controller = SpatchWidget.controller();
        if (seek && controller != null) {
            controller.getTransportControls().seekTo(preview);
            Haptics.seekDone(art);
        }
    }

    /** Applies this frame's lean and turn around the artwork's centre. */
    void apply(Canvas canvas, long now) {
        long step = now - drawnAt;
        drawnAt = now;
        PlayerArt.spring(tiltX, targetX, step);
        PlayerArt.spring(tiltY, targetY, step);
        if (!scratching) spin *= 1 - SPIN_SPRING;
        if (Math.abs(spin) < 0.05f) spin = 0;
        float cx = art.getWidth() / 2f, cy = art.getHeight() / 2f;
        if (tiltX[0] != 0 || tiltY[0] != 0) {
            camera.save();
            camera.rotateX(tiltX[0]);
            camera.rotateY(tiltY[0]);
            camera.getMatrix(matrix);
            camera.restore();
            matrix.preTranslate(-cx, -cy);
            matrix.postTranslate(cx, cy);
            canvas.concat(matrix);
        }
        if (spin != 0) canvas.rotate(spin, cx, cy);
    }

    /** A soft glare under the finger, inside the art's clip. */
    void sheen(Canvas canvas, float radius) {
        if (!down || scratching || !Settings.get(Settings.ART_TILT)) return;
        paint.setShader(new RadialGradient(fingerX, fingerY, radius, new int[] {0x38ffffff, 0}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(fingerX, fingerY, radius, paint);
        paint.setShader(null);
    }

    /** Where a scratch will land, over the art. */
    void readout(Canvas canvas, float dp) {
        float cx = art.getWidth() / 2f, cy = art.getHeight() / 2f;
        String time = SeekBubble.time(preview) + " / " + SeekBubble.time(duration);
        paint.setTypeface(Fonts.settings(700));
        paint.setTextSize(18 * dp);
        paint.setTextAlign(Paint.Align.CENTER);
        float half = paint.measureText(time) / 2 + 16 * dp;
        paint.setColor(Palette.color(0xe614121c));
        canvas.drawRoundRect(cx - half, cy - 22 * dp, cx + half, cy + 22 * dp, 22 * dp, 22 * dp, paint);
        paint.setColor(Palette.color(0xffd0bcff));
        canvas.drawText(time, cx, cy + 6 * dp, paint);
    }
}
