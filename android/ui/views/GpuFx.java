package spatch;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;

/**
 * Optional effects drawn by small GPU programs (AGSL shaders, Android 13+) and GPU blur (Android 12+). Every caller
 * keeps its plain Canvas drawing as the fallback, used on older phones and whenever the "GPU effects" setting is off.
 */
final class GpuFx {
    private GpuFx() {}

    static boolean shaders(SharedPreferences prefs) { return Build.VERSION.SDK_INT >= 33 && prefs.getBoolean("gpu_fx", true); }

    static boolean blur(SharedPreferences prefs) { return Build.VERSION.SDK_INT >= 31 && prefs.getBoolean("gpu_fx", true); }

    /** A shader's AGSL source, by name (field, ring, grain, glow): the .agsl files live with the Rust library, which hands them over. */
    static String source(String name) { return nativeShader(name); }

    private static native String nativeShader(String name);

    /**
     * The cover blurred once on the GPU (Android 12+) at `size` px square, read back as an ordinary small bitmap: the
     * backdrop then draws it exactly as it drew the CPU blur, so nothing extra runs per frame. `sigma` is a fraction of
     * the size. Null if the GPU path isn't available, and the caller blurs on the CPU instead.
     */
    static Bitmap blurOnce(Bitmap cover, int size, float sigma) {
        if (Build.VERSION.SDK_INT < 31) return null;
        android.media.ImageReader reader = android.media.ImageReader.newInstance(size, size, android.graphics.PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        android.graphics.HardwareRenderer renderer = new android.graphics.HardwareRenderer();
        try {
            renderer.setSurface(reader.getSurface());
            android.graphics.RenderNode node = new android.graphics.RenderNode("cover-blur");
            node.setPosition(0, 0, size, size);
            float radius = Math.max(1f, (sigma * size - .5f) / .57735f); // Android's blur radius to Gaussian sigma: s = .57735r + .5
            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP));
            android.graphics.RecordingCanvas c = node.beginRecording();
            c.drawBitmap(cover, null, new android.graphics.Rect(0, 0, size, size), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            node.endRecording();
            renderer.setContentRoot(node);
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            android.media.Image image = reader.acquireNextImage();
            if (image == null) return null;
            try (android.hardware.HardwareBuffer buffer = image.getHardwareBuffer()) {
                Bitmap gpu = Bitmap.wrapHardwareBuffer(buffer, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
                return gpu == null ? null : gpu.copy(Bitmap.Config.ARGB_8888, false); // small, and then usable anywhere
            } finally {
                image.close();
            }
        } catch (RuntimeException failed) {
            android.util.Log.w("SpatchUi", "GPU cover blur failed; using the CPU blur", failed);
            return null;
        } finally {
            renderer.destroy();
            reader.close();
        }
    }

    /** The cover's average colour in each quarter (top left, top right, bottom left, bottom right), from a 12x12 shrink. */
    static int[] quarters(Bitmap cover) {
        Bitmap small = Bitmap.createScaledBitmap(cover, 12, 12, true);
        int[] px = new int[144];
        small.getPixels(px, 0, 12, 0, 0, 12, 12);
        int[] out = new int[4];
        for (int q = 0; q < 4; q++) {
            int r = 0, g = 0, b = 0;
            for (int y = (q / 2) * 6; y < (q / 2) * 6 + 6; y++) for (int x = (q % 2) * 6; x < (q % 2) * 6 + 6; x++) {
                int c = px[y * 12 + x];
                r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
            }
            out[q] = 0xff000000 | (r / 36) << 16 | (g / 36) << 8 | (b / 36);
        }
        return out;
    }
}
