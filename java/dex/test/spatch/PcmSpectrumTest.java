package spatch;

/** Checks of the PCM-to-FFT bytes that feed the visualiser without a microphone. */
public final class PcmSpectrumTest {
    public static void main(String[] args) {
        int n = PcmSpectrum.N;
        byte[] out = new byte[n];
        PcmSpectrum.fftBytes(new float[n], out);
        for (byte b : out) assert b == 0 : "Silence reads zero";

        int bin = 50;
        float[] sine = new float[n];
        for (int i = 0; i < n; i++) sine[i] = (float) (.5 * Math.sin(2 * Math.PI * bin * i / n));
        PcmSpectrum.fftBytes(sine, out);
        int best = 0;
        double strongest = 0;
        for (int b = 1; b < n / 2; b++) {
            double magnitude = Math.hypot(out[2 * b], out[2 * b + 1]);
            if (magnitude > strongest) { strongest = magnitude; best = b; }
        }
        assert best == bin : "The loudest bin is the sine's: " + best;
        assert strongest > 50 && strongest < 80 : "Half scale reads about 63: " + strongest;
        assert Math.hypot(out[2 * 200], out[2 * 200 + 1]) < 4 : "Far bins stay quiet";
        System.out.println("PcmSpectrumTest: all checks passed");
    }
}
