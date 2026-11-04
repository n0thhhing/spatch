package spatch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.media.session.MediaController;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

/** Previous or Next in the mini-player (android:tag). Sends the session command Spotify's own buttons reach. */
public final class MiniSkip extends ImageView implements View.OnClickListener {
    private static final long PREVIOUS = 16, NEXT = 32; // PlaybackState.ACTION_SKIP_TO_*
    private boolean next;

    public MiniSkip(Context c, AttributeSet a) {
        super(c, a);
        setOnClickListener(this);
    }

    protected void onFinishInflate() {
        super.onFinishInflate();
        next = "next".equals(getTag());
        int icon = getResources().getIdentifier(next ? "encore_icon_skip_forward_24" : "encore_icon_skip_back_24", "drawable", getContext().getPackageName());
        if (icon != 0) setImageResource(icon);
        setImageTintList(ColorStateList.valueOf(Palette.color(0xfff3eeff)));
        setBackground(new RippleDrawable(ColorStateList.valueOf(Palette.color(0x33d0bcff)), null, null));
        setScaleType(ImageView.ScaleType.CENTER);
    }

    void allow(long actions) {
        boolean on = (actions & (next ? NEXT : PREVIOUS)) != 0;
        setEnabled(on);
        setAlpha(on ? 1f : 0.38f);
    }

    public void onClick(View v) {
        MediaController c = SpatchWidget.controller();
        if (c != null) {
            if (next) c.getTransportControls().skipToNext();
            else c.getTransportControls().skipToPrevious();
            Haptics.playback(this);
        } else {
            PlayerSwipe.skip(this, next);
        }
        if (Wave.animationsEnabled() && Settings.buttons()) {
            float nudge = (next ? 3 : -3) * getResources().getDisplayMetrics().density;
            setTranslationX(nudge);
            animate().translationX(0).setDuration(200).start();
        }
    }
}
