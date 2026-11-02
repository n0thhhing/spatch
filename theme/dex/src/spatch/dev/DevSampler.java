package spatch;

import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** A debug-only sampling profiler: where the main thread is whenever it isn't idle, written to logcat. */
final class DevSampler implements Runnable {
    private static final long EVERY = 6, REPORT = 6000;
    private static volatile boolean on;

    static void set(boolean value) {
        if (value == on) return;
        on = value;
        if (on) {
            Thread thread = new Thread(new DevSampler(), "SpatchSampler");
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }
    }

    public void run() {
        Thread main = Looper.getMainLooper().getThread();
        HashMap<String, Integer> self = new HashMap<String, Integer>(), total = new HashMap<String, Integer>(), owner = new HashMap<String, Integer>(), ours = new HashMap<String, Integer>();
        int busy = 0, all = 0;
        long started = System.currentTimeMillis();
        while (on) {
            try {
                Thread.sleep(EVERY);
            } catch (InterruptedException e) {
                return;
            }
            StackTraceElement[] stack = main.getStackTrace();
            all++;
            if (stack.length > 0 && !stack[0].getMethodName().equals("nativePollOnce")) {
                busy++;
                add(self, name(stack[0]));
                for (StackTraceElement frame : stack) {
                    String n = frame.getClassName();
                    if (n.startsWith("android.") || n.startsWith("java.") || n.startsWith("com.android.") || n.startsWith("dalvik.")) continue;
                    add(owner, name(frame));
                    break;
                }
                for (StackTraceElement frame : stack) {
                    if (frame.getClassName().startsWith("spatch.")) {
                        add(ours, name(frame));
                        break;
                    }
                }
                HashSet<String> seen = new HashSet<String>();
                for (StackTraceElement frame : stack) if (seen.add(name(frame))) add(total, name(frame));
            }
            if (System.currentTimeMillis() - started > REPORT) {
                report(busy, all, self, total);
                for (String line : top(owner, false, 16)) Log.i("SpatchProf", "owner " + line);
                for (String line : top(ours, false, 16)) Log.i("SpatchProf", "ours  " + line);
                owner.clear();
                ours.clear();
                self.clear();
                total.clear();
                busy = all = 0;
                started = System.currentTimeMillis();
            }
        }
    }

    private static String name(StackTraceElement frame) {
        return frame.getClassName() + "." + frame.getMethodName();
    }

    private static void add(Map<String, Integer> counts, String key) {
        Integer now = counts.get(key);
        counts.put(key, Integer.valueOf(now == null ? 1 : now.intValue() + 1));
    }

    private static void report(int busy, int all, Map<String, Integer> self, Map<String, Integer> total) {
        Log.i("SpatchProf", "busy " + busy + " of " + all + " samples");
        for (String line : top(self, false, 14)) Log.i("SpatchProf", "self  " + line);
        for (String line : top(total, true, 34)) Log.i("SpatchProf", "total " + line);
    }

    private static List<String> top(Map<String, Integer> counts, boolean appOnly, int limit) {
        ArrayList<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().compareTo(a.getValue());
            }
        });
        ArrayList<String> out = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : sorted) {
            String key = e.getKey();
            if (appOnly && (key.startsWith("android.os") || key.startsWith("java.") || key.startsWith("com.android.internal") || key.startsWith("dalvik."))) continue;
            out.add(e.getValue() + " " + key);
            if (out.size() == limit) break;
        }
        return out;
    }
}
