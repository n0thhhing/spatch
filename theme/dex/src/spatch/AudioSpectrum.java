package spatch;

import android.content.Context;
import android.content.ContextWrapper;
import android.app.Activity;
import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import java.util.LinkedHashSet;

/** FFT of Spotify's own AudioTrack session; no microphone, output-mix capture or fabricated bars. */
public final class AudioSpectrum {
    static final String PERMISSION = "android.permission.RECORD_AUDIO";
    private static final LinkedHashSet<Integer> sessions = new LinkedHashSet<Integer>();
    private static volatile int current;
    public static synchronized void created(int id) { if (id > 0) { sessions.add(id); current = id; } }
    public static synchronized void destroyed(int id) {
        sessions.remove(id);
        if (current == id) { current = 0; for (int remaining : sessions) current = remaining; }
    }
    public static synchronized void paused(boolean paused, int id) {
        if (!paused && sessions.contains(id)) current = id;
    }

    /** Renderers of the one shared capture: it runs while any of them is visible and playing. */
    static final int BOTTOM = 1, RADIAL = 2, MINI = 4, WAVE = 8;
    static final AudioSpectrum SHARED = new AudioSpectrum();
    private static volatile int consumers;

    static void request(Context context, int consumer, boolean active) {
        consumers = active ? consumers | consumer : consumers & ~consumer;
        if (queued) return;
        queued = true;
        app = context.getApplicationContext();
        worker().post(CAPTURE);
    }

    /** For a drawable, which isn't told when it leaves the screen: stops drawing, and capture lapses. */
    static void lease(Context context, int consumer) {
        request(context, consumer, true);
        worker().removeCallbacks(LAPSE);
        worker().postDelayed(LAPSE, 1000);
    }

    private static final Runnable LAPSE = new Runnable() {
        public void run() {
            drop(WAVE);
        }
    };

    /** Setting up and reading a Visualizer are binder calls into the audio server: measured stalling skips on the main thread. */
    private static android.os.Handler worker() {
        if (worker == null) {
            android.os.HandlerThread thread = new android.os.HandlerThread("SpatchAudio");
            thread.start();
            worker = new android.os.Handler(thread.getLooper());
        }
        return worker;
    }

    /** A renderer went away (hidden, closed, backgrounded); no context is needed to stop capture. */
    static void drop(int consumer) {
        consumers &= ~consumer;
        if (consumers == 0) worker().post(new Runnable() {
            public void run() {
                if (consumers == 0) SHARED.release();
            }
        });
    }

    static int session() {
        return current;
    }

    private static android.os.Handler worker;
    private static volatile boolean queued;
    private static volatile Context app;
    private static final Runnable CAPTURE = new Runnable() {
        public void run() {
            queued = false;
            SHARED.update(app, consumers != 0);
        }
    };
    private Visualizer visualizer;
    private int attempted;
    private byte[] fft;
    private long sampledAt, retryAt;
    final float[] levels = new float[24];
    String status;

    void update(Context context, boolean active) {
        long now = SystemClock.uptimeMillis();
        if (context.checkSelfPermission(PERMISSION) != 0) {
            release(); status = "Tap to enable audio visualizer"; return;
        }
        if (!active) { release(); status = null; return; }
        int session = current;
        if (session <= 0) { release(); status = "Visualizer needs playback on this phone"; return; }
        if (attempted != session) { release(); attempted = session; retryAt = 0; }
        if (visualizer == null && now < retryAt) { status = "Audio visualization unavailable"; return; }
        try {
            if (visualizer == null) {
                visualizer = new Visualizer(session);
                int[] range = Visualizer.getCaptureSizeRange();
                int size = Math.max(range[0], Math.min(1024, range[1]));
                if (visualizer.setCaptureSize(size) != 0 || visualizer.setEnabled(true) != 0)
                    throw new IllegalStateException("Visualizer setup failed");
                fft = new byte[size];
            }
            if (now - sampledAt < 16) return; // about one capture per display frame
            float dt = sampledAt == 0 ? 16 : Math.min(100, now - sampledAt);
            sampledAt = now;
            if (visualizer.getFft(fft) != 0) throw new IllegalStateException("Audio session unavailable");
            int rate = visualizer.getSamplingRate() / 1000;
            float[] target = bands(fft, rate, levels.length);
            for (int i = 0; i < levels.length; i++) {
                float a = 1 - (float)Math.exp(-dt / (target[i] > levels[i] ? 20 : 110)); // snappy attack, short release
                levels[i] += (target[i] - levels[i]) * a;
            }
            status = null;
        } catch (RuntimeException e) {
            release(); status = "Audio visualization unavailable"; retryAt = now + 2000;
        }
    }

    /** Energy in log-frequency bands. Fractional-bin integration avoids duplicated bass bins. */
    static float[] bands(byte[] fft, int rate, int count) {
        float[] out = new float[count];
        if (rate <= 0 || fft.length < 4) return out;
        int max = fft.length/2-1;
        double low = Math.max(60, 1.5*rate/fft.length), high = Math.min(16000, rate/2.0);
        for(int i=0;i<count;i++) {
            double start=low*Math.pow(high/low,i/(double)count)*fft.length/rate;
            double end=low*Math.pow(high/low,(i+1.0)/count)*fft.length/rate;
            double power=0;
            for(int k=Math.max(1,(int)Math.floor(start-.5));k<=Math.min(max,(int)Math.ceil(end+.5));k++) {
                double weight=Math.max(0,Math.min(end,k+.5)-Math.max(start,k-.5));
                double re=fft[k*2], im=fft[k*2+1];
                power+=weight*(re*re+im*im)/(128.0*128.0);
            }
            // Display dB of band energy rather than a peak biased by bin count. Silence stays zero.
            out[i]=power==0 ? 0 : Math.max(0,Math.min(1,(float)((10*Math.log10(power)+50)/50)));
        }
        return out;
    }

    void release() {
        if (visualizer != null) {
            try { visualizer.release(); } catch (RuntimeException ignored) { /* already released by audio server */ }
        }
        visualizer = null; sampledAt = 0;
        java.util.Arrays.fill(levels, 0);
    }

    static void requestPermission(Context c) {
        while (c instanceof ContextWrapper && !(c instanceof Activity)) {
            Context base = ((ContextWrapper)c).getBaseContext();
            if (base == c) return;
            c = base;
        }
        if (c instanceof Activity) ((Activity)c).requestPermissions(new String[]{PERMISSION}, 7341);
    }
}
