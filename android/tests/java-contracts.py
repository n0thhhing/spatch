#!/usr/bin/env python3
"""Exercise production catalog, creation-route and stream lifecycle contracts.

Requires JAVA_HOME and ANDROID_HOME, as used by the Android build. Fixtures replace
Spotify/RxJava at the boundary; they do not contact Spotify or test authentication.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[2]


def _strip_comments_and_strings(source: str) -> str:
    """Remove Java comments and string/char literals while preserving structure."""
    pattern = re.compile(
        r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
    )
    return pattern.sub(lambda m: "\n" if m.group(0).startswith("//") else " ", source)


def _non_private_static_methods(path: Path) -> set[str]:
    """Return public and package-private static method names declared at top-level of a class."""
    cleaned = _strip_comments_and_strings(path.read_text(encoding="utf-8"))
    top_level: list[str] = []
    depth = 0
    for ch in cleaned:
        if ch == "{":
            depth += 1
            if depth == 2:
                top_level.append(";")
        elif ch == "}":
            depth = max(0, depth - 1)
        elif depth == 1:
            top_level.append(ch)
    methods: set[str] = set()
    for decl in "".join(top_level).split(";"):
        paren = decl.find("(")
        if paren == -1:
            continue
        eq = decl.find("=")
        if eq != -1 and eq < paren:
            continue
        head = decl[:paren].strip()
        tokens = head.split()
        if len(tokens) < 2:
            continue
        modifiers = set(tokens[:-1])
        if "static" not in modifiers or "private" in modifiers:
            continue
        if modifiers & {"class", "interface", "enum"}:
            continue
        name = tokens[-1]
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            methods.add(name)
    return methods


def check_core_bridge_preview_contract() -> None:
    """Ensure every non-private CoreBridge static method used by UI/preview tests exists in preview."""
    prod_path = REPO / "android/ui/bridge/CoreBridge.java"
    preview_path = REPO / "preview/src/spatch/CoreBridge.java"
    prod_methods = _non_private_static_methods(prod_path)
    preview_methods = _non_private_static_methods(preview_path)

    caller_files = [
        p for p in (REPO / "android/ui").rglob("*.java") if p != prod_path
    ]
    caller_files.extend(
        p for p in (REPO / "preview/src").rglob("*.java") if p != preview_path
    )
    caller_files.extend((REPO / "preview/desktop/src/test").rglob("*.java"))

    used_methods: set[str] = set()
    call_re = re.compile(r"\bCoreBridge\.([A-Za-z_][A-Za-z0-9_]*)\s*\(")
    for caller in caller_files:
        cleaned = _strip_comments_and_strings(caller.read_text(encoding="utf-8"))
        for match in call_re.finditer(cleaned):
            name = match.group(1)
            if name in prod_methods:
                used_methods.add(name)

    missing = sorted(used_methods - preview_methods)
    assert not missing, (
        f"preview/src/spatch/CoreBridge.java is missing non-private static method(s) "
        f"used by UI/tests: {', '.join(missing)}"
    )
    extra = sorted(preview_methods - prod_methods)
    assert not extra, (
        f"preview/src/spatch/CoreBridge.java declares static method(s) absent from "
        f"android/ui/CoreBridge.java: {', '.join(extra)}"
    )
    print(
        f"CoreBridge preview contract passed: {len(used_methods)} UI/test-used static methods "
        f"present in preview ({len(preview_methods)} total preview methods)"
    )


check_core_bridge_preview_contract()

JAVA = Path(os.environ["JAVA_HOME"]) / "bin"
ANDROID = sorted(
    (Path(os.environ["ANDROID_HOME"]) / "platforms").glob("android-*/android.jar"),
    key=lambda p: tuple(int(part) for part in p.parent.name.removeprefix("android-").split(".") if part.isdigit()),
)[-1]

FIXTURES = {
    'android/util/Log.java': """package android.util;
public final class Log {
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable t) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable t) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable t) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
}""",
    'io/reactivex/rxjava3/core/Single.java': """package io.reactivex.rxjava3.core;
public class Single<T> {
    private final Object value;
    private final RuntimeException error;
    public long lastTimeout;
    public java.util.concurrent.TimeUnit lastUnit;
    public Single(Object value) { this.value = value; this.error = null; }
    public static <T> Single<T> error(RuntimeException error) { return new Single<>(error, true); }
    private Single(RuntimeException error, boolean ignored) { this.value = null; this.error = error; }
    public final Single timeout(long amount, java.util.concurrent.TimeUnit unit) {
        assert (amount == 12 && unit == java.util.concurrent.TimeUnit.SECONDS)
            || (amount == 15 && unit == java.util.concurrent.TimeUnit.SECONDS)
            || (amount >= 1 && amount <= 8000 && unit == java.util.concurrent.TimeUnit.MILLISECONDS);
        this.lastTimeout = amount;
        this.lastUnit = unit;
        return this;
    }
    public final Object blockingGet() {
        if (error != null) throw error;
        return value;
    }
    public final io.reactivex.rxjava3.disposables.Disposable subscribe(io.reactivex.rxjava3.functions.Consumer success, io.reactivex.rxjava3.functions.Consumer error) {
        try { if (this.error != null) error.accept(this.error); else success.accept(value); }
        catch (Throwable t) { throw new RuntimeException(t); }
        return () -> {};
    }
}""",
    'p/yt20.java': """package p; public class yt20 {}
""",
    'p/v4p1.java': """package p;
public class v4p1 { public static String s() { return "zh_TW"; } }""",
    'spatch/NativeTransport.java': """package spatch;
import com.spotify.esperanto.esperanto.Transport;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
public class NativeTransport implements Transport {
    public int calls;
    public Single lastSingle;
    public RuntimeException failWith;
    public byte[] reply = new byte[0];
    public Single callSingle(String service, String method, byte[] request) {
        calls++;
        lastSingle = failWith != null ? Single.error(failWith) : new Single(reply);
        return lastSingle;
    }
    public Observable callStream(String service, String method, byte[] request) { throw new UnsupportedOperationException(); }
    public byte[] callSync(String service, String method, byte[] request) { throw new UnsupportedOperationException(); }
}""",
    'spatch/OtherTransport.java': """package spatch;
import com.spotify.esperanto.esperanto.Transport;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
public class OtherTransport implements Transport {
    public int calls;
    public byte[] reply = new byte[]{7, 8};
    public Single callSingle(String service, String method, byte[] request) {
        calls++;
        return new Single(reply);
    }
    public Observable callStream(String service, String method, byte[] request) { throw new UnsupportedOperationException(); }
    public byte[] callSync(String service, String method, byte[] request) { throw new UnsupportedOperationException(); }
}""",
    'spatch/CallFallbackCheck.java': """package spatch;
import java.util.Arrays;
public class CallFallbackCheck {
    public static void main(String[] args) {
        NativeTransport first = new NativeTransport();
        first.failWith = new RuntimeException("timed out");
        OtherTransport second = new OtherTransport();
        CoreBridge.wrap(first);
        CoreBridge.wrap(second);

        try {
            CoreBridge.callTimed("spotify.playlist_esperanto.proto.PlaylistService", "Modify", new byte[0], 5000);
            throw new AssertionError("Write failure must propagate");
        } catch (RuntimeException expected) {
            assert expected == first.failWith;
        }
        assert first.calls == 1 && second.calls == 0 : "Write must be sent exactly once without fallback";
        assert first.lastSingle.lastTimeout == 5000 : "Write must wait the full timeout on the first transport";

        try {
            CoreBridge.callTimed("spotify.volume_esperanto.proto.VolumeService", "VolumeSet", new byte[0], 5000);
            throw new AssertionError("VolumeSet failure must propagate");
        } catch (RuntimeException expected) {
            assert expected == first.failWith;
        }
        assert first.calls == 2 && second.calls == 0 : "VolumeSet is a write and must not fall back";

        byte[] readReply = CoreBridge.callTimed("spotify.playlist_esperanto.proto.PlaylistService", "Get", new byte[0], 5000);
        assert Arrays.equals(readReply, new byte[]{7, 8});
        assert first.calls == 3 && second.calls == 1 : "A read on an unknown service is asked of every transport at once";
        byte[] again = CoreBridge.callTimed("spotify.playlist_esperanto.proto.PlaylistService", "Get", new byte[0], 5000);
        assert Arrays.equals(again, new byte[]{7, 8});
        assert first.calls == 3 && second.calls == 2 : "Once learned, a read goes only to the transport that answered";
        System.out.println("Call fallback checks passed: writes sent once with full wait, reads race until learned");
    }
}""",
    'spatch/CatalogRequestCheck.java': """package spatch;
import java.util.*;
import com.spotify.extendedmetadata.proto.BatchedExtensionResponse;
import com.spotify.playlist4.proto.Playlist4ApiProto;
import com.spotify.searchview.proto.MainViewResponse;
import io.reactivex.rxjava3.core.Single;
public class CatalogRequestCheck {
    public static class Reply { public byte[] toByteArray() { return new byte[]{10,0}; } }
    public static class Api implements SpotifyWeb {
        Map<String,String> last;
        public Single<Playlist4ApiProto.SelectedListContent> playlist(String playlistId) { throw new UnsupportedOperationException(); }
        public Single<BatchedExtensionResponse> extendedMetadata(com.google.protobuf.Empty request) { throw new UnsupportedOperationException(); }
        public Single<p.ii31> lyrics(String trackId) { throw new UnsupportedOperationException(); }
        public Single<p.ii31> storageResolve(String path, Map<String,String> params) { throw new UnsupportedOperationException(); }
        public Single<BatchedExtensionResponse> extendedMetadataAsWeb(com.google.protobuf.Empty request, Map<String,String> headers) { throw new UnsupportedOperationException(); }
        @SuppressWarnings("unchecked")
        public Single<MainViewResponse> search(Map<String,String> params, Map<String,String> headers) {
            assert headers.isEmpty() : "Query metadata cannot be mixed with partial header metadata";
            assert params.containsKey("request_id") && params.containsKey("timestamp");
            assert !params.containsKey("_t") && !params.containsKey("on_demand_sets_enabled");
            UUID.fromString(params.get("request_id"));
            assert Math.abs(Long.parseLong(params.get("timestamp")) - System.currentTimeMillis()) < 10000;
            assert params.get("query_complete").equals("submit");
            assert params.get("locale").equals("zh_TW") : "Use Spotify's normalized locale";
            assert params.get("limit").equals("20");
            assert new HashSet<>(Arrays.asList(params.get("entity_types").split(","))).containsAll(Arrays.asList("track","album","artist","playlist","show","audio_episode"));
            last = new HashMap<>(params);
            return new Single(new Reply());
        }
    }
    public static class Maker {
        public final Api api = new Api();
        public Object createWebgateService(Class<?> type, String feature) {
            assert type == SpotifyWeb.class && feature.equals("android-search-onlineproto");
            return api;
        }
    }
    public static void main(String[] args) throws Exception {
        try { CoreBridge.search("test", ""); throw new AssertionError("Missing connection must fail"); }
        catch (IllegalStateException expected) { assert expected.getMessage().contains("starting"); }
        Maker maker = new Maker();
        CoreBridge.webgate(maker, new p.yt20());
        CoreBridge.webgate(new Object(), new Object());
        CoreBridge.webgate(new Object(), null);
        String query = "Björk & 東京 + Radiohead";
        assert Arrays.equals(CoreBridge.search(query, ""), new byte[]{10,0});
        assert maker.api.last.get("query").equals(query);
        assert maker.api.last.get("page_token").isEmpty();
        String token = "opaque+/page=2&next";
        CoreBridge.search(query, token);
        assert maker.api.last.get("page_token").equals(token) : "Do not encode Retrofit query values twice";
        System.out.println("Catalog request checks passed: SpotifyWeb search/locale, metadata pair, Unicode, paging, reply bytes, factory selection, missing connection");
    }
}""",
}

with tempfile.TemporaryDirectory(prefix="spatch-catalog-check-") as temporary:
    work = Path(temporary)
    sources = sorted((REPO / "android/ui").rglob("*.java"))
    sources += [REPO / f"android/tests/spatch/{name}.java"
                for name in ("BlurTest", "CreationRouteTest", "SubscriptionsTest")]
    sources += [p for p in (REPO / "android/stubs").rglob("*.java") if p.name != "Single.java"]
    for name, source in FIXTURES.items():
        file = work / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(source)
        sources.append(file)
    classes = work / "classes"
    subprocess.run([str(JAVA / "javac"), "--release", "11", "-cp", str(ANDROID),
                    "-d", str(classes), *map(str, sources)], check=True)
    for check in ("BlurTest", "CallFallbackCheck", "CatalogRequestCheck", "CreationRouteTest", "SubscriptionsTest"):
        subprocess.run([str(JAVA / "java"), "-ea", "-cp", os.pathsep.join([str(classes), str(ANDROID)]),
                        f"spatch.{check}"], check=True)
