package spatch;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import java.util.WeakHashMap;

/**
 * Now Playing title/artist: when the text really changes (a new song), it fades up while sliding
 * 6dp into place instead of snapping. Watches Spotify's own TextViews; never sets their text.
 */
final class TextSlide implements TextWatcher {
    private static final WeakHashMap<TextView, TextSlide> attached = new WeakHashMap<TextView, TextSlide>();
    private final TextView view;
    private String last;

    private TextSlide(TextView view) {
        this.view = view;
        CharSequence t = view.getText();
        last = t == null ? null : t.toString();
    }

    static void attach(View v) {
        if (!(v instanceof TextView) || attached.containsKey(v)) return;
        TextView t = (TextView) v;
        TextSlide slide = new TextSlide(t);
        attached.put(t, slide);
        t.addTextChangedListener(slide);
    }

    public void afterTextChanged(Editable s) {
        String now = s == null ? null : s.toString();
        if (now == null || now.equals(last)) return; // repeated sets of the same text don't animate
        boolean first = last == null;
        last = now;
        if (first || !Wave.animationsEnabled()) return;
        view.setAlpha(0f);
        view.setTranslationX(6 * view.getResources().getDisplayMetrics().density);
        // Spotify relayouts and inflates the new track's page in the first ~100 ms (traced: up to a
        // 55 ms frame); starting after that keeps the fade on smooth frames.
        view.animate().alpha(1f).translationX(0f).setStartDelay(120).setDuration(260).start();
    }

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    public void onTextChanged(CharSequence s, int start, int before, int count) {}
}
