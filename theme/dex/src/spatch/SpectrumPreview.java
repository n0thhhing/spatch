package spatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Fixed sample of the selected spectrum look in settings, labelled so it reads as a demo. */
final class SpectrumPreview extends View {
    private static final float[] SAMPLE = {
        .95f, .7f, .85f, .55f, .75f, .4f, .65f, .5f, .35f, .6f, .3f, .45f,
        .55f, .25f, .4f, .3f, .35f, .2f, .3f, .25f, .15f, .2f, .12f, .1f,
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    SpectrumPreview(Context c) {
        super(c, null);
        setContentDescription("Sample of the selected visualizer look, not live audio");
    }

    protected void onDraw(Canvas c) {
        float dp = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Palette.color(0xffd0bcff));
        Spectrum.draw(c, paint, path, SAMPLE, Settings.choice(Settings.VIZ_SHAPE), 16 * dp, w - 16 * dp, h, h - 8 * dp,
            Settings.vizMirrored(), Settings.vizThin(), false, "subtle".equals(Settings.choice(Settings.VIZ_TAPER)), dp);
        paint.setColor(Palette.color(0xffbcb3dc));
        paint.setTextSize(11 * dp);
        c.drawText("Sample", 18 * dp, 12 * dp, paint);
    }
}
