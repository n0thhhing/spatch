package spatch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import androidx.compose.ui.graphics.Brush;
import java.io.File;
import p.e6x0;
import p.l6x0;
import p.tu61;

/**
 * Local header for the signed-in user's own profile: bn31.h asks here for the brush over its colour.
 * Other people's profiles keep Spotify's. Nothing here reaches Spotify: the image stays in app-private
 * storage and only this device shows it.
 */
public final class ProfileBanner extends tu61 {
    static final String FILE = "spatch-profile-banner.jpg";
    private static final int DIM = 0x59;
    private static Bitmap image;
    private static boolean read;
    private static ProfileBanner cached;
    private final Bitmap bitmap;
    private final float position;

    private ProfileBanner(Bitmap bitmap, float position) {
        this.bitmap = bitmap;
        this.position = position;
    }

    public static Brush brush(Brush stock, l6x0 profile) {
        if (!own(profile) || !Settings.profileBanner()) return stock;
        Bitmap banner = banner();
        if (banner == null && !Settings.profileThemeHeader()) return stock;
        float position = Settings.profilePosition();
        if (cached == null || cached.bitmap != banner || cached.position != position) cached = new ProfileBanner(banner, position);
        return cached;
    }

    public static long bar(long stock, l6x0 profile) {
        if (!own(profile) || !Settings.profileBanner()) return stock;
        Bitmap banner = banner();
        if (banner == null && !Settings.profileThemeHeader()) return stock;
        int surface = Palette.color(0xff14121c);
        return (long) (banner == null ? Pill.blend(surface, Palette.color(0xffd0bcff), 0.35f) : surface) << 32;
    }

    /** e6x0 is the Edit action, only offered on the signed-in user's profile. */
    private static boolean own(l6x0 profile) {
        for (Object action : profile.f) if (action instanceof e6x0) return true;
        return false;
    }

    static File file() {
        return new File(Settings.app.getFilesDir(), FILE);
    }

    static synchronized Bitmap banner() {
        if (!read && Settings.app != null) {
            read = true;
            File file = file();
            image = file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null; // null: unreadable, so the theme header shows
        }
        return image;
    }

    static synchronized void changed() {
        read = false;
        image = null;
    }

    public Shader c(long size) {
        float w = Float.intBitsToFloat((int) (size >> 32)), h = Float.intBitsToFloat((int) size);
        int surface = Palette.color(0xff14121c);
        if (bitmap == null) {
            int tint = Pill.blend(surface, Palette.color(0xffd0bcff), 0.35f);
            return new LinearGradient(0, 0, 0, h, new int[] {tint, surface}, null, Shader.TileMode.CLAMP);
        }
        float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
        Matrix crop = new Matrix();
        crop.setScale(scale, scale);
        crop.postTranslate((w - bitmap.getWidth() * scale) / 2, (h - bitmap.getHeight() * scale) * position);
        BitmapShader picture = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        picture.setLocalMatrix(crop);
        Shader fade = new LinearGradient(0, 0, 0, h, new int[] {surface & 0xffffff | DIM << 24, surface & 0xffffff | DIM << 24, surface}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        return new ComposeShader(picture, fade, PorterDuff.Mode.SRC_OVER);
    }
}
