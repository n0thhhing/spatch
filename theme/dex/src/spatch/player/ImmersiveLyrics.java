package spatch;

import android.graphics.Canvas;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import p.S_LyricLoaded;
import p.S_LyricContent;
import p.S_PlayerClock;
import com.spotify.player.model.PlayerState;
import com.spotify.player.model.ContextTrack;

/** Current and nearby lines from Spotify's NPV result, timed to playback. */
public final class ImmersiveLyrics {
    public static volatile Object model;
    public static volatile Object playback;
    public static volatile Object clock;
    private final TextPaint paint = new TextPaint(3);
    private final StaticLayout[] lines = new StaticLayout[3];
    private Object shown;
    private int selected = -2, width;
    private float textSize;
    private String text = "";

    static S_LyricContent content(Object state, String uri) {
        if (!(state instanceof S_LyricLoaded) || uri == null) return null;
        S_LyricLoaded loaded = (S_LyricLoaded) state;
        return uri.equals(loaded.b) && loaded.a != null ? loaded.a.a : null;
    }

    static int index(S_LyricContent lyrics, long position) {
        if (lyrics == null || lyrics.a == null || lyrics.a.isEmpty()) return -1;
        if (lyrics.b == 1) return 0; // Unsynced lyrics show their opening line.
        int current = -1;
        for (int i = 0; i < lyrics.a.size(); i++) {
            if (lyrics.a.get(i).a > position) break;
            current = i;
        }
        return current;
    }

    String update(float availableWidth, float size) {
        Object state=playback, time=clock;
        if (!(state instanceof PlayerState) || !(time instanceof S_PlayerClock))
            return update(null,0,availableWidth,size);
        PlayerState current=(PlayerState)state;
        Object track=current.track().h();
        String uri=track instanceof ContextTrack ? ((ContextTrack)track).uri() : null;
        Object position=current.position(((S_PlayerClock)time).b()).h();
        return update(uri,position instanceof Number ? Math.max(0,((Number)position).longValue()) : 0,availableWidth,size);
    }

    String update(String uri, long position, float availableWidth, float size) {
        Object state = model;
        S_LyricContent lyrics = content(state, uri);
        int next = index(lyrics, position), pixels = Math.max(1, (int) availableWidth);
        if (shown == lyrics && selected == next && width == pixels && textSize == size) return text;
        shown = lyrics; selected = next; width = pixels; textSize = size;
        paint.setTextSize(size);
        android.graphics.Typeface tf = Fonts.settings(700);
        paint.setTypeface(tf != null ? tf : android.graphics.Typeface.DEFAULT_BOLD);
        for (int i = 0; i < lines.length; i++) {
            int row = next + i - 1;
            String value = lyrics != null && lyrics.a != null && row >= 0 && row < lyrics.a.size() ? lyrics.a.get(row).b : "";
            if (value == null) value = "";
            if (i == 1 && (lyrics == null || lyrics.a == null || lyrics.a.isEmpty())) value = "Lyrics unavailable";
            if (i == 1) text = value;
            lines[i] = StaticLayout.Builder.obtain(value, 0, value.length(), paint, pixels)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build();
        }
        return text;
    }

    void draw(Canvas canvas, float cx, float top, float height, boolean expanded) {
        if (height <= 0 || lines[1] == null) return;
        int start = expanded ? 0 : 1, end = expanded ? 3 : 2;
        float rowHeight = height / (end - start);
        int save = canvas.save();
        canvas.clipRect(cx-width/2f, top, cx+width/2f, top+height);
        boolean focus = Settings.get(Settings.LYRICS_FOCUS);
        int accent = Tint.album();
        if (accent == 0 || (accent & 0xffffff) == 0) accent = Palette.color(0xffd0bcff);
        for (int i = start; i < end; i++) {
            if (i == 1) {
                paint.setColor(focus ? accent : Palette.color(0xfff3eeff));
                if (focus) paint.setShadowLayer(8f, 0, 0, (accent & 0x66ffffff) | 0x55000000);
                else paint.clearShadowLayer();
            } else {
                paint.clearShadowLayer();
                paint.setColor(focus ? Palette.color(0x66bcb3dc) : Palette.color(0x99f3eeff));
            }
            int row = canvas.save();
            canvas.translate(cx-width/2f, top+(i-start)*rowHeight+Math.max(0,(rowHeight-lines[i].getHeight())/2));
            lines[i].draw(canvas);
            canvas.restoreToCount(row);
        }
        paint.clearShadowLayer();
        canvas.restoreToCount(save);
    }
}
