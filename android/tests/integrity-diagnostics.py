#!/usr/bin/env python3
"""Run the real helper against small Android/Spotify fakes; requires JAVA_HOME, no APK or device."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[2]
JDK = Path(os.environ["JAVA_HOME"])

SOURCES = {
    'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
    boolean getBoolean(String key, boolean fallback);
    int getInt(String key, int fallback);
}''',
    'android/content/Context.java': '''package android.content;
public class Context {
    public static final int MODE_PRIVATE = 0;
    public SharedPreferences prefs;
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!name.equals("spatch_ui") || mode != MODE_PRIVATE) throw new AssertionError();
        return prefs;
    }
}''',
    'android/util/Log.java': '''package android.util;
public class Log {
    public static final StringBuilder messages = new StringBuilder();
    public static int i(String tag, String message) { messages.append(tag).append(':').append(message).append('\\n'); return 0; }
}''',
    'com/spotify/integrity/integrityimpl/proto/VerifyRequest.java': '''package com.spotify.integrity.integrityimpl.proto;
public final class VerifyRequest {
    public final String token, correlation, pkg, unknown;
    public int sizeReads;
    public VerifyRequest(String token, String correlation, String pkg, String unknown) {
        this.token = token; this.correlation = correlation; this.pkg = pkg; this.unknown = unknown;
    }
    public int getSerializedSize() { sizeReads++; return token.length() + correlation.length() + pkg.length() + unknown.length(); }
    public Builder toBuilder() { return new Builder(this); }
    public static final class Builder {
        private final VerifyRequest original;
        private String token;
        public Builder(VerifyRequest original) { this.original = original; token = original.token; }
        public void r(String token) { this.token = token; }
        public VerifyRequest build() { return new VerifyRequest(token, original.correlation, original.pkg, original.unknown); }
    }
}''',
    'com/spotify/integrity/integrityimpl/proto/VerifyResponse.java': '''package com.spotify.integrity.integrityimpl.proto;
public final class VerifyResponse {
    public static final VerifyResponse DEFAULT = new VerifyResponse();
    public static VerifyResponse n() { return DEFAULT; }
}''',
    'spatch/DevIntegrityTest.java': '''package spatch;
import android.content.*;
import android.util.Log;
import com.spotify.integrity.integrityimpl.proto.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class DevIntegrityTest {
    private static final Map<String, Object> values = new HashMap<>();
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    private static final class Service implements p.dmt0 {
        int calls;
        VerifyRequest last;
        p.osm continuation;
        Object result = new Object();
        RuntimeException error;
        public Object a(VerifyRequest request, p.osm continuation) {
            calls++; last = request; this.continuation = continuation;
            if (error != null) throw error;
            return result;
        }
    }
    public static void main(String[] args) throws Exception {
        Service service = new Service();
        VerifyRequest request = new VerifyRequest("SECRET_TOKEN", "correlation", "com.spotify.music", "unknown-field");
        Object[] resumed = new Object[1];
        p.osm continuation = new p.osm() {
            public Object getContext() { return null; }
            public void resumeWith(Object result) { resumed[0] = result; }
        };
        check(DevIntegrity.verify(service, request, continuation) == service.result);
        check(service.last == request && service.continuation == continuation && request.sizeReads == 0);
        Context context = new Context();
        context.prefs = new SharedPreferences() {
            public boolean getBoolean(String key, boolean fallback) { return (boolean) values.getOrDefault(key, fallback); }
            public int getInt(String key, int fallback) { return (int) values.getOrDefault(key, fallback); }
        };
        DevIntegrity.init(context);
        check(DevIntegrity.verify(service, request, continuation) == service.result);
        check(request.sizeReads == 0 && Log.messages.length() == 0);

        DevIntegrity.pluginConstructed();
        DevIntegrity.pluginStarted();
        DevIntegrity.validation(false);
        DevIntegrity.employeeValidation(false);
        DevIntegrity.scheduled(-1);
        DevIntegrity.tokenStarted();
        DevIntegrity.tokenOutcome(null);
        check(Log.messages.length() == 0);
        values.put("integrity_trace", true);
        DevIntegrity.pluginConstructed();
        DevIntegrity.pluginStarted();
        DevIntegrity.validation(false);
        DevIntegrity.employeeValidation(true);
        DevIntegrity.scheduled(-1);
        DevIntegrity.tokenStarted();
        DevIntegrity.tokenOutcome(new Object() {
            public String toString() { throw new AssertionError("Do not inspect token contents"); }
        });
        DevIntegrity.tokenOutcome(null);
        check(Log.messages.toString().contains("validation_enabled=false"));
        check(Log.messages.toString().contains("employee_validation_enabled=true"));
        check(Log.messages.toString().contains("saved_attempt_day=-1 device_day="));
        check(Log.messages.toString().contains("token_acquisition_entered"));
        check(Log.messages.toString().contains("token_result_class=null"));
        check(DevIntegrity.verify(service, request, continuation) == service.result);
        check(service.last == request && service.continuation == continuation);
        RuntimeException failure = new IllegalStateException("SECRET_ERROR");
        service.error = failure;
        try { DevIntegrity.verify(service, request, continuation); throw new AssertionError(); }
        catch (RuntimeException actual) { check(actual == failure); }
        service.error = null;
        Object suspended = new Object();
        service.result = suspended;
        check(DevIntegrity.verify(service, request, continuation) == suspended);
        Object asyncFailure = new Object();
        service.continuation.resumeWith(asyncFailure);
        check(resumed[0] == asyncFailure && service.continuation == continuation);

        values.put("integrity_skip", true);
        int before = service.calls;
        check(DevIntegrity.verify(service, request, continuation) == VerifyResponse.DEFAULT);
        check(service.calls == before);
        DevIntegrity.accepted();
        DevIntegrity.failure(failure);
        check(Log.messages.toString().contains("caller_result=accepted"));
        check(Log.messages.toString().contains("caller_error=java.lang.IllegalStateException"));
        check(!Log.messages.toString().contains("SECRET_ERROR"));
        values.put("integrity_variant", 1);
        check(DevIntegrity.verify(service, request, continuation) == suspended);
        check(service.calls == before + 1 && service.last != request && service.last.token.equals(""));
        check(request.token.equals("SECRET_TOKEN"));
        check(service.last.correlation.equals(request.correlation) && service.last.pkg.equals(request.pkg));
        check(service.last.unknown.equals(request.unknown) && service.continuation == continuation);
        values.put("integrity_skip", false);
        check(DevIntegrity.verify(service, request, continuation) == suspended && service.last == request);

        String digest = "0123456789abcdef0123456789abcdef01234567";
        byte[] lower = digest.getBytes(StandardCharsets.US_ASCII);
        byte[] upper = digest.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        byte[][] patterns = DevIntegrity.patterns(digest);
        check(patterns.length == 3 && patterns[2].length == 20);
        check(DevIntegrity.contains(lower, lower.length, patterns));
        check(DevIntegrity.contains(upper, upper.length, patterns));
        check(DevIntegrity.contains(patterns[2], 20, patterns));
        byte[] suffix = ("prefix" + digest).getBytes(StandardCharsets.US_ASCII);
        check(DevIntegrity.contains(suffix, suffix.length, patterns));
        check(!DevIntegrity.contains(suffix, suffix.length - 1, patterns));
        check(!DevIntegrity.contains(null, 0, patterns));
        check(!DevIntegrity.contains(lower, lower.length + 1, patterns));
        check(!DevIntegrity.contains(lower, -1, patterns));
        check(!DevIntegrity.contains(lower, 0, patterns));
        check(!DevIntegrity.contains("mismatch".getBytes(StandardCharsets.US_ASCII), 8, patterns));
        check(DevIntegrity.patterns(null).length == 0);
        check(!DevIntegrity.contains(lower, lower.length, null));
        check(!DevIntegrity.contains(lower, lower.length, new byte[][] {null, new byte[0]}));
        check(DevIntegrity.patterns("invalid:").length == 0 && DevIntegrity.patterns("").length == 0);
        check(DevIntegrity.patterns(digest + ":" + digest).length == 6);
        Log.messages.setLength(0);
        DevIntegrity.body(lower, lower.length);
        check(Log.messages.toString().contains("unknown-no-read"));
        values.put("integrity_trace", false);
        Log.messages.setLength(0);
        DevIntegrity.signature(digest);
        DevIntegrity.body(lower, lower.length);
        check(Log.messages.length() == 0);
        values.put("integrity_trace", true);
        DevIntegrity.event("TestReport", lower);
        check(Log.messages.toString().contains("event/TestReport") && Log.messages.toString().contains("digest_match=true"));
        Log.messages.setLength(0);
        DevIntegrity.signature(digest);
        check(Log.messages.toString().contains("self_signature_sha1=" + digest));
        check(Log.messages.toString().contains("DevIntegrityTest.main"));
        Log.messages.setLength(0);
        DevIntegrity.body(suffix, suffix.length - 1);
        check(Log.messages.toString().contains("digest_match=false"));
        check(!Log.messages.toString().contains("prefix"));
        check(!Log.messages.toString().contains("SECRET_TOKEN"));

        values.clear();
        int sizeReads = request.sizeReads;
        Log.messages.setLength(0);
        check(DevIntegrity.verify(service, request, continuation) == suspended);
        check(service.last == request && request.sizeReads == sizeReads && Log.messages.length() == 0);
        System.out.println("integrity diagnostics: passthrough, skip, empty-token, errors, continuations, tracing and reset passed");
    }
}''',
}


def main():
    with tempfile.TemporaryDirectory(prefix='spatch-integrity-test-') as work:
        root = Path(work)
        for name, source in SOURCES.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
        for name, original in {
            'spatch/DevIntegrity.java': 'android/ui/DevIntegrity.java',
            'p/dmt0.java': 'android/stubs/p/dmt0.java',
            'p/osm.java': 'android/stubs/p/osm.java',
        }.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPO / original, path)
        classes = root / 'classes'
        classes.mkdir()
        subprocess.run([str(JDK / 'bin/javac'), '--release', '11', '-d', str(classes),
                        *map(str, root.rglob('*.java'))], check=True)
        subprocess.run([str(JDK / 'bin/java'), '-cp', str(classes), 'spatch.DevIntegrityTest'], check=True)


if __name__ == '__main__':
    main()
