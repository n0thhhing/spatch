package spatch;

/** Checks of the cover blur's pixel maths (no Android needed). */
public final class BlurTest {
    public static void main(String[] args) {
        int[] flat = new int[8 * 8];
        java.util.Arrays.fill(flat, 0xFF336699);
        Blur.blur(flat, 8, 8, 3);
        for (int c : flat) assert c == 0xFF336699 : "A flat colour must stay exactly itself";

        int w = 16, h = 4;
        int[] edge = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) edge[y * w + x] = x < w / 2 ? 0xFFFF0000 : 0xFF0000FF;
        Blur.blur(edge, w, h, 2);
        int previous = 255;
        for (int x = 0; x < w; x++) {
            int c = edge[x], red = (c >> 16) & 255, blue = c & 255;
            assert (c >>> 24) == 255 : "Opaque stays opaque";
            assert red <= previous : "Red fades monotonically across the edge";
            assert red + blue > 200 && red + blue < 310 : "Colour is conserved while it mixes";
            previous = red;
        }
        assert ((edge[0] >> 16) & 255) > 200 && (edge[w - 1] & 255) > 200 : "Far sides keep their colour";
        int mid = edge[w / 2];
        assert ((mid >> 16) & 255) > 40 && (mid & 255) > 40 : "The seam is a mix of both";
        System.out.println("BlurTest: all checks passed");
    }
}
