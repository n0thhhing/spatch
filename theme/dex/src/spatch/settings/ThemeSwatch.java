package spatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** One theme as a tiny screen in its own colours; a ring and check mark the chosen one. */
final class ThemeSwatch extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int theme;
    private String name, secondLine;

    ThemeSwatch(Context c, int theme, String name) {
        super(c, null);
        this.theme = theme;
        setName(name);
        setClickable(true);
        setFocusable(true);
    }

    void setName(String name) {
        int split=name.lastIndexOf(' ');
        boolean wrap=name.length()>13 && split>0;
        this.name = wrap?name.substring(0,split):name;
        this.secondLine=wrap?name.substring(split+1):null;
        invalidate();
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float dp = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight(), r = 20 * dp, pad = 6 * dp;
        int surface = Palette.tone(theme, 0), card = Palette.tone(theme, 2), text = Palette.tone(theme, 3), accent = Palette.tone(theme, 6);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surface);
        c.drawRoundRect(pad, pad, w - pad, h - pad, r, r, paint);
        float inner = pad + 10 * dp;
        paint.setColor(accent);
        c.drawCircle(inner + 11 * dp, inner + 11 * dp, 11 * dp, paint);
        paint.setColor(card);
        c.drawRoundRect(inner + 28 * dp, inner + 4 * dp, w - inner, inner + 10 * dp, 3 * dp, 3 * dp, paint);
        c.drawRoundRect(inner + 28 * dp, inner + 14 * dp, w - inner - 10 * dp, inner + 20 * dp, 3 * dp, 3 * dp, paint);
        c.drawRoundRect(inner, inner + 30 * dp, w - inner, inner + 42 * dp, 6 * dp, 6 * dp, paint);
        paint.setColor(text);
        paint.setTextSize(11.5f * dp);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(name, w / 2, h - pad - (secondLine==null?11:23) * dp, paint);
        if(secondLine!=null)c.drawText(secondLine,w/2,h-pad-9*dp,paint);
        paint.setTextAlign(Paint.Align.LEFT);
        if (!isSelected()) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f * dp);
        paint.setColor(accent);
        c.drawRoundRect(pad - 3 * dp, pad - 3 * dp, w - pad + 3 * dp, h - pad + 3 * dp, r + 3 * dp, r + 3 * dp, paint);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(surface);
        float x = inner + 11 * dp, y = inner + 11 * dp;
        paint.setStrokeWidth(2.2f * dp);
        c.drawLine(x - 5 * dp, y, x - 1.5f * dp, y + 3.5f * dp, paint);
        c.drawLine(x - 1.5f * dp, y + 3.5f * dp, x + 5 * dp, y - 3.5f * dp, paint);
    }
}
