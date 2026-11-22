package spatch;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile for play/pause, reusing the home-screen widget's already-live player state. */
public final class PlaybackTile extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        SpatchWidget.attach(this);
        refresh();
    }

    @Override public void onClick() {
        super.onClick();
        SpatchWidget.attach(this);
        // SpatchUi's own PAUSE/RESUME action codes, the same ones the widget's toggle button sends.
        SpatchUi.widgetCommand(SpatchWidget.playing() ? 0 : 1);
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean playing = SpatchWidget.playing();
        tile.setState(playing ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(playing ? "Pause" : "Play");
        if (Build.VERSION.SDK_INT >= 29) {
            String title = SpatchWidget.title();
            tile.setSubtitle(title.isEmpty() ? "Nothing playing" : title);
        }
        tile.setIcon(Icon.createWithBitmap(SpatchWidget.tileIcon(48)));
        tile.updateTile();
    }
}
