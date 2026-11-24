package spatch;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w412dp-h820dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AboutDownloadsTest {
    @Test public void aboutShowsBuildCreditsPrivacyAndWorkingSupportActions() throws Exception {
        SpatchUi ui = create();
        settings(ui, 6);
        for (String label : new String[]{"Spatch build", "Host app", "Android", "Copy build details", "Source code", "Releases & updates", "Report a problem", "License", "AutoEq", "Your account & data"}) assertNotNull(label, find(ui, label));
        click(find(ui, "Copy build details"));
        ClipboardManager clipboard = (ClipboardManager) ui.activity.getSystemService(Context.CLIPBOARD_SERVICE);
        String copied = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(copied.contains("Spatch build "));
        assertTrue(copied.contains(ui.activity.getPackageName()));
        assertTrue(copied.contains("API 35"));
        click(find(ui, "Source code"));
        assertEquals("https://github.com/n0thhhing/spatch", shadowOf(ui.activity).getNextStartedActivity().getDataString());
        click(find(ui, "Releases & updates"));
        assertTrue(shadowOf(ui.activity).getNextStartedActivity().getDataString().endsWith("/releases/tag/branch-fresh"));
        screenshot(ui, "about");
        ui.activity.finish();
    }

    @Test public void oneSongDownloadsKeepsItsIdentityAndCanBeRemoved() throws Exception {
        SpatchUi ui = create();
        settle(100);
        int playlistsBefore = ((byte[]) nativeCall("nativePlaylistTargets", new Class<?>[0])).length;
        MediaEntry song = new MediaEntry("Offline song", "Offline artist", "spotify:track:offline-single", "demo:2", -1);
        call(ui, "searchActions", new Class<?>[]{MediaEntry.class}, song);
        click(find(ui, "Download song"));
        waitForState(ui, song.uri, 3);
        assertEquals("Offline song", downloaded(ui, song.uri).title);
        assertEquals("demo:2", downloaded(ui, song.uri).image);
        assertEquals("Downloading must not like the song", 0, nativeCall("nativeSaved", new Class<?>[]{String.class}, song.uri));
        assertEquals("Downloading must not create a playlist", playlistsBefore, ((byte[]) nativeCall("nativePlaylistTargets", new Class<?>[0])).length);
        settings(ui, 4);
        assertNotNull("Downloads screen uses the song title", find(ui, "Offline song"));
        assertNotNull(find(ui, "Downloaded"));
        screenshot(ui, "individual-download");
        // A new screen instance must recover the account-scoped metadata too.
        SpatchUi reopened = new SpatchUi(ui.activity);
        ui.activity.setContentView(reopened);
        settle(120);
        call(reopened, "pullDownloads", new Class<?>[0]);
        assertEquals("Offline artist", downloaded(reopened, song.uri).subtitle);
        call(reopened, "searchActions", new Class<?>[]{MediaEntry.class}, song);
        click(find(reopened, "Remove download"));
        assertNotNull(find(reopened, "Remove download"));
        click(find(reopened, "Remove download"));
        waitForState(reopened, song.uri, 0);
        assertNull(downloaded(reopened, song.uri));
        ui.activity.finish();
    }

    @Test public void cancellationDoesNotReappearAndMetadataIsValidatedBeforeMutation() throws Exception {
        SpatchUi ui = create();
        MediaEntry song = new MediaEntry("Cancel me", "Artist", "spotify:episode:offline-cancel", "demo:1", -1);
        call(ui, "searchActions", new Class<?>[]{MediaEntry.class}, song);
        click(find(ui, "Download episode"));
        for (int i = 0; i < 20 && state(ui, song.uri) == 0; i++) settle(10);
        assertTrue(state(ui, song.uri) > 0);
        call(ui, "searchActions", new Class<?>[]{MediaEntry.class}, song);
        click(find(ui, "Cancel download"));
        click(find(ui, "Cancel download"));
        waitForState(ui, song.uri, 0);
        settle(800);
        assertEquals("A cancelled preview job must stay cancelled", 0, state(ui, song.uri));
        java.lang.reflect.Method download = SpatchUi.class.getDeclaredMethod("nativeDownload", String.class, int.class, byte[].class);
        download.setAccessible(true);
        byte[] invalid = PackedStrings.packStrings(new String[]{"Wrong", "", "spotify:track:other", "", "-1"});
        assertEquals("Invalid download details", download.invoke(null, song.uri, 0, invalid));
        assertEquals(0, state(ui, song.uri));
        // Queue entries and playlist songs expose the same download operation.
        ((List<MediaEntry>) field(ui, "upcoming")).add(song);
        call(ui, "queueActions", new Class<?>[]{int.class, MediaEntry.class}, 0, song);
        assertNotNull(find(ui, "Download episode"));
        ui.back();
        String[] fields = {"Playlist song", "Artist", "spotify:track:offline-playlist", "demo:3", "Album", "", "", "180000", "", "", "", ""};
        call(ui, "songActions", new Class<?>[]{MediaEntry.Song.class}, new MediaEntry.Song(fields, 0, 0));
        assertNotNull(find(ui, "Download song"));
        ui.activity.finish();
    }

    private static SpatchUi create() {
        PreviewActivity activity = Robolectric.buildActivity(PreviewActivity.class).setup().visible().get();
        ShadowLooper.idleMainLooper();
        SpatchUi ui = (SpatchUi) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        ui.prefs.edit().putBoolean("reduce_motion", true).apply();
        return ui;
    }

    private static void settings(SpatchUi ui, int section) throws Exception {
        call(ui, "openSettings", new Class<?>[]{boolean.class}, false);
        ui.settingsSection = section;
        ui.showPage();
    }

    private static int state(SpatchUi ui, String uri) throws Exception {
        call(ui, "pullDownloads", new Class<?>[0]);
        return (Integer) call(ui, "downloadState", new Class<?>[]{String.class}, uri);
    }

    private static MediaEntry downloaded(SpatchUi ui, String uri) throws Exception {
        List<MediaEntry> entries = (List<MediaEntry>) call(ui, "entries", new Class<?>[]{int.class, String.class, boolean.class, int.class}, 4, "Offline", false, 400);
        return entries.stream().filter(e -> e.uri.equals(uri)).findFirst().orElse(null);
    }

    private static void waitForState(SpatchUi ui, String uri, int target) throws Exception {
        for (int i = 0; i < 80 && state(ui, uri) != target; i++) settle(40);
        assertEquals(target, state(ui, uri));
    }

    private static void settle(int millis) throws Exception {
        Thread.sleep(millis);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(millis));
    }

    private static Object nativeCall(String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method method = SpatchUi.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(null, args);
    }

    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return Reflect.call(target, name, types, args);
    }

    private static void click(View view) {
        assertNotNull(view);
        while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
        assertTrue(view.performClick());
    }

    private static View find(View view, String label) {
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()) || view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View found = find(((ViewGroup) view).getChildAt(i), label); if (found != null) return found; }
        return null;
    }

    private static void screenshot(SpatchUi ui, String name) throws Exception {
        View root = ui.activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(820, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 412, 820);
        Bitmap bitmap = Bitmap.createBitmap(412, 820, Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
        File file = new File("build/reports/preview/" + name + ".png"); file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }
}
