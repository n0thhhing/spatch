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

REPO = Path(__file__).resolve().parent.parent


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
    prod_path = REPO / "java/dex/src/spatch/CoreBridge.java"
    preview_path = REPO / "app/preview/src/spatch/CoreBridge.java"
    prod_methods = _non_private_static_methods(prod_path)
    preview_methods = _non_private_static_methods(preview_path)

    caller_files = [
        p for p in (REPO / "java/dex/src/spatch").glob("*.java") if p != prod_path
    ]
    caller_files.extend(
        p for p in (REPO / "app/preview/src").rglob("*.java") if p != preview_path
    )
    caller_files.extend((REPO / "app/preview/desktop/src/test").rglob("*.java"))

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
        f"app/preview/src/spatch/CoreBridge.java is missing non-private static method(s) "
        f"used by UI/tests: {', '.join(missing)}"
    )
    extra = sorted(preview_methods - prod_methods)
    assert not extra, (
        f"app/preview/src/spatch/CoreBridge.java declares static method(s) absent from "
        f"java/dex/src/spatch/CoreBridge.java: {', '.join(extra)}"
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
    'io/reactivex/rxjava3/core/Single.java': """package io.reactivex.rxjava3.core;
public class Single<T> {
    private final Object value;
    public Single(Object value) { this.value = value; }
    public final Single timeout(long seconds, java.util.concurrent.TimeUnit unit) {
        assert (seconds == 12 && unit == java.util.concurrent.TimeUnit.SECONDS)
            || (seconds >= 1 && seconds <= 8000 && unit == java.util.concurrent.TimeUnit.MILLISECONDS);
        return this;
    }
    public final Object blockingGet() { return value; }
    public final io.reactivex.rxjava3.disposables.Disposable subscribe(io.reactivex.rxjava3.functions.Consumer success, io.reactivex.rxjava3.functions.Consumer error) { return null; }
}""",
    'p/yt20.java': """package p; public class yt20 {}
""",
    'p/jx51.java': """package p;
public interface jx51 {
    io.reactivex.rxjava3.core.Single a(java.util.Map<String,String> params, java.util.Map<String,String> headers);
}""",
    'p/v4p1.java': """package p;
public class v4p1 { public static String s() { return "zh_TW"; } }""",
    'spatch/CatalogRequestCheck.java': """package spatch;
import java.util.*;
import io.reactivex.rxjava3.core.Single;
public class CatalogRequestCheck {
    public static class Reply { public byte[] toByteArray() { return new byte[]{10,0}; } }
    public static class Api implements p.jx51 {
        Map<String,String> last;
        public Single a(Map<String,String> params, Map<String,String> headers) {
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
            assert type == p.jx51.class && feature.equals("android-search-onlineproto");
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
        System.out.println("Catalog request checks passed: native API/locale, metadata pair, Unicode, paging, reply bytes, factory selection, missing connection");
    }
}""",
}

with tempfile.TemporaryDirectory(prefix="spatch-catalog-check-") as temporary:
    work = Path(temporary)
    sources = sorted((REPO / "java/dex/src/spatch").glob("*.java"))
    sources += [REPO / f"java/dex/test/spatch/{name}.java"
                for name in ("BlurTest", "CreationRouteTest", "PcmSpectrumTest", "SubscriptionsTest")]
    sources += [p for p in (REPO / "java/dex/stubs").rglob("*.java") if p.name != "Single.java"]
    for name, source in FIXTURES.items():
        file = work / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(source)
        sources.append(file)
    classes = work / "classes"
    subprocess.run([str(JAVA / "javac"), "--release", "11", "-cp", str(ANDROID),
                    "-d", str(classes), *map(str, sources)], check=True)
    for check in ("BlurTest", "CatalogRequestCheck", "CreationRouteTest", "PcmSpectrumTest", "SubscriptionsTest"):
        subprocess.run([str(JAVA / "java"), "-ea", "-cp", os.pathsep.join([str(classes), str(ANDROID)]),
                        f"spatch.{check}"], check=True)
