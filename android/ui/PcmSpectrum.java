package spatch;

/** Turns a window of mono PCM into the byte layout of Android's Visualizer FFT, so the existing analyser reads it unchanged. */
final class PcmSpectrum {
    static final int N = 1024;
    private static final float[] HANN = new float[N], COS = new float[N / 2], SIN = new float[N / 2];
    static {
        for (int i = 0; i < N; i++) HANN[i] = (float) (.5 - .5 * Math.cos(2 * Math.PI * i / N));
        for (int i = 0; i < N / 2; i++) { COS[i] = (float) Math.cos(2 * Math.PI * i / N); SIN[i] = (float) -Math.sin(2 * Math.PI * i / N); }
    }

    private PcmSpectrum() {}

    /**
     * `samples` (N of them, -1..1) in, `out` (N bytes) filled: [0] the DC term, [1] the Nyquist term, then a (re, im) pair
     * per bin 1..N/2-1. A full-scale sine at a bin's centre reads about 127; the analyser adapts its own reference.
     */
    static void fftBytes(float[] samples, byte[] out) {
        float[] re = new float[N], im = new float[N];
        for (int i = 0; i < N; i++) re[i] = samples[i] * HANN[i];
        fft(re, im);
        float k = 127f * 4f / N; // a Hann window passes half the amplitude, and a bin holds amplitude * N / 2
        out[0] = clamp(re[0] * k);
        out[1] = clamp(re[N / 2] * k);
        for (int b = 1; b < N / 2; b++) {
            out[2 * b] = clamp(re[b] * k);
            out[2 * b + 1] = clamp(im[b] * k);
        }
    }

    private static byte clamp(float v) { return (byte) Math.max(-127, Math.min(127, Math.round(v))); }

    /** In-place radix-2 FFT. */
    private static void fft(float[] re, float[] im) {
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= N; len <<= 1) {
            int step = N / len;
            for (int start = 0; start < N; start += len) {
                for (int k = 0; k < len / 2; k++) {
                    float wr = COS[k * step], wi = SIN[k * step];
                    int a = start + k, b = a + len / 2;
                    float tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                }
            }
        }
    }
}
