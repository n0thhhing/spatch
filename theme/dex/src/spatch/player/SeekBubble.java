package spatch;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

/** A time label that floats above a finger dragging the progress bar. */
final class SeekBubble extends Drawable {
    private static SeekBubble shown;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private float x, y;
    private String text = "";

    private SeekBubble(float dp) {
        this.dp = dp;
    }

    static String time(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    /** Called with every touch on the bar. */
    static void touch(View bar, MotionEvent e) {
        View root = bar.getRootView();
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (shown != null) root.getOverlay().remove(shown);
            shown = null;
            return;
        }
        if (!Settings.get(Settings.SEEK_BUBBLE) || SpatchWidget.controller() == null) return;
        android.media.MediaMetadata metadata = SpatchWidget.controller().getMetadata();
        long duration = metadata == null ? 0 : metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        if (duration <= 0) return;
        if (shown == null) {
            shown = new SeekBubble(bar.getResources().getDisplayMetrics().density);
            shown.setBounds(0, 0, root.getWidth(), root.getHeight());
            root.getOverlay().add(shown);
        }
        int[] at = new int[2];
        bar.getLocationInWindow(at);
        float width = Math.max(1, bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight());
        float fraction = Math.max(0, Math.min(1, (e.getX() - bar.getPaddingLeft()) / width));
        shown.x = at[0] + bar.getPaddingLeft() + fraction * width;
        shown.y = at[1] - 30 * shown.dp;
        shown.text = time((long) (fraction * duration));
        shown.invalidateSelf();
    }

    public void draw(Canvas c) {
        paint.setTypeface(Fonts.applied(700, android.graphics.Typeface.DEFAULT_BOLD));
        paint.setTextSize(15 * dp);
        paint.setTextAlign(Paint.Align.CENTER);
        float half = paint.measureText(text) / 2 + 12 * dp;
        float cx = Math.max(half + 8 * dp, Math.min(getBounds().width() - half - 8 * dp, x));
        // Ambient soft drop shadow
        paint.setColor(0x38000000);
        c.drawRoundRect(cx - half, y - 15 * dp, cx + half, y + 19 * dp, 17 * dp, 17 * dp, paint);
        // Pill body
        paint.setColor(Palette.color(0xffd0bcff));
        c.drawRoundRect(cx - half, y - 17 * dp, cx + half, y + 17 * dp, 17 * dp, 17 * dp, paint);
        paint.setColor(Palette.color(0xff14121c));
        c.drawText(text, cx, y + 5 * dp, paint);
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
