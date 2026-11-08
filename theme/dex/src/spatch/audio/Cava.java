package spatch;

/**
 * Backbeat's response, from CAVA's cavacore: a falling bar drops with gravity, an integral keeps it
 * full, auto sensitivity backs off on overshoot and creeps up otherwise, and monstercat lets a peak
 * spill into its neighbours. CAVA counts in frames at 66 fps; here each frame is scaled by its real
 * length, so it behaves the same at 60 or 120 Hz.
 */
final class Cava {
    private static final int N = Spectrum.BANDS;
    private static final double[] out = new double[N], peak = new double[N], fall = new double[N], mem = new double[N], prev = new double[N];
    private static double sens = 1;
    private static boolean sensInit = true;

    private Cava() {}

    static void reset() {
        for (double[] a : new double[][] {out, peak, fall, mem, prev}) java.util.Arrays.fill(a, 0);
        sens = 1;
        sensInit = true;
    }

    /** linear: band magnitudes; dt in ms; noise 0..0.99; spread as Backbeat's monstercat; levels gets 0..1. */
    static void step(float[] linear, float dt, double noise, double spread, double sensitivity, float[] levels) {
        double frames = dt * 66 / 1000.0, gravity = 2 / Math.max(noise, 0.01), keep = Math.pow(noise, frames);
        boolean silent = true, overshoot = false;
        for (float v : linear) if (v > 0) silent = false;
        for (int i = 0; i < N; i++) {
            double x = silent ? 0 : linear[i] * sens;
            if (x < prev[i] && noise > 0.1) {
                x = Math.max(0, peak[i] * (1 - fall[i] * fall[i] * gravity));
                fall[i] += 0.028 * frames;
            } else {
                peak[i] = x;
                fall[i] = 0;
            }
            prev[i] = x;
            x = mem[i] * keep + x;
            if (x < 1e-3) x = 0; // settles to exact zero
            mem[i] = x;
            if (x > 1) {
                overshoot = true;
                x = 1;
            }
            out[i] = x;
        }
        if (overshoot) {
            sens *= Math.pow(0.98, frames);
            sensInit = false;
        } else if (!silent) {
            sens *= Math.pow(sensInit ? 1.1 : 1.001, frames);
        }
        for (int i = 0; i < N; i++) out[i] = Math.min(out[i] * sensitivity, 1);
        monstercat(out, spread * 1.5);
        for (int i = 0; i < N; i++) levels[i] = (float) out[i];
    }

    /** Bar m = max over z of bar[z] / base^|m - z|, in two passes; off at base 1 or less. */
    static void monstercat(double[] v, double base) {
        if (base <= 1) return;
        for (int m = 1; m < v.length; m++) v[m] = Math.max(v[m], v[m - 1] / base);
        for (int m = v.length - 2; m >= 0; m--) v[m] = Math.max(v[m], v[m + 1] / base);
    }
}
