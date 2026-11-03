package spatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import p.S_EncoreIconDrawable;

/** Canvas control sharing native Transport/Scallop motion and the real Spotify action's state. */
final class ImmersiveButton {
    View source;
    private final boolean close;
    private final View state;
    private final Transport surface;
    private final Paint paint = new Paint(3);
    private final float[] scale = {1,0};
    private long frame;
    private float alpha = 1, playMix, hover;
    private boolean playKnown;

    ImmersiveButton(Context context, int shape, boolean close) {
        this.close = close;
        state = new View(context, null);
        surface = new Transport(state, shape, null);
        surface.setCallback(state);
    }

    boolean enabled() {
        if (close) return true;
        if (source == null || !source.isShown()) return false;
        View v = source;
        while (true) {
            if (!v.isEnabled()) return false;
            ViewParent parent = v.getParent();
            if (!(parent instanceof View)) return true;
            v = (View)parent;
        }
    }

    void interaction(boolean pressed, boolean hovered, boolean clicked) {
        pressed &= enabled(); hovered &= enabled();
        // Skip buttons never scale (they nudge sideways, as in the player); the rest bounce on click.
        if (clicked && Settings.buttons() && !surface.isSkip()) scale[0] = Math.min(scale[0], 0.92f);
        state.setEnabled(enabled()); state.setPressed(pressed); state.setHovered(hovered);
        surface.setState(pressed ? new int[]{0x0101009e,0x010100a7} : hovered ? new int[]{0x0101009e,0x01010367} : new int[0]);
    }

    boolean click() { return enabled() && source != null && ClickHaptic.forward(source); }

    void draw(Canvas c, Drawable icon, Drawable alternate, float x, float y, float radius, boolean playing) {
        long now = SystemClock.uptimeMillis();
        long dt = frame == 0 ? 16 : Math.min(64, now-frame); frame = now;
        boolean motion = Settings.buttons();
        boolean enabled = enabled();
        if (!enabled) interaction(false, false, false);
        float desired = enabled ? 1 : 0.36f;
        float blend = motion ? 1-(float)Math.exp(-dt/110f) : 1;
        alpha += (desired-alpha)*blend;
        hover += ((enabled && state.isHovered()?1:0)-hover)*blend;
        if (motion && !surface.isSkip()) PlayerArt.spring(scale, state.isPressed() ? 0.92f : 1, dt);
        else { scale[0]=1;scale[1]=0; }
        int save = c.save();
        // Transport computes the skip nudge onto the state view (as it moves the real player button);
        // apply it here so icon and highlight slide together. It is one frame behind, unnoticeable.
        c.translate(x+state.getTranslationX(),y); c.scale(scale[0],scale[0],0,0);
        boolean selected = source != null && (source.isSelected() || source.isActivated());
        if (alternate != null) {
            paint.setColor(0xffffff);paint.setAlpha(Math.round(28*hover*alpha));
            c.drawCircle(0,0,radius*1.18f,paint);
            paint.setColor(0xffd0bcff); paint.setAlpha(Math.round(255*alpha));
            Scallop.draw(c,radius,paint,playing ? 9 : 5,surface);
            if (!playKnown || !motion) { playMix=playing?1:0;playKnown=true; }
            else playMix += ((playing?1:0)-playMix)*(1-(float)Math.exp(-dt/85f));
            drawIcon(c,icon,radius*.9f,Math.round(255*alpha*(1-playMix)),0xff14121c);
            drawIcon(c,alternate,radius*.9f,Math.round(255*alpha*playMix),0xff14121c);
        } else {
            surface.setBounds(Math.round(-radius),Math.round(-radius),Math.round(radius),Math.round(radius));
            surface.draw(c);
            drawIcon(c,icon,radius,Math.round(255*alpha),selected?0xffd0bcff:0xfff3eeff);
            if (selected && enabled) {paint.setColor(0xffd0bcff);c.drawCircle(0,radius*.8f,radius*.06f,paint);}
        }
        c.restoreToCount(save);
    }

    private static void drawIcon(Canvas c, Drawable icon, float size, int alpha, int color) {
        if (icon instanceof S_EncoreIconDrawable) ((S_EncoreIconDrawable)icon).b(color);
        int half=Math.round(size/2);icon.setBounds(-half,-half,half,half);
        icon.setAlpha(alpha);icon.draw(c);
    }
}
