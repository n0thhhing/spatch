package spatch;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.widget.TextView;

/** The player layouts set an Android font family directly, bypassing Spotify's font loaders. */
public final class TrackTitle extends TextView {
    public TrackTitle(Context context, AttributeSet attrs) {
        super(context, attrs);
        Typeface stock = getTypeface();
        Typeface replacement = Fonts.applied(800, stock);
        if (replacement != stock) setTypeface(replacement);
    }
}
