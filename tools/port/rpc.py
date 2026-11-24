#!/usr/bin/env python3
"""Lists every Esperanto RPC Spotify's Java code makes: service, method, call kind, request and
response messages. Clients are ClientBase / CoroutineClientBase subclasses; R8 keeps the service
and method strings and the protobuf class names, so the catalog survives obfuscation.

    tools/port/rpc.py <decoded-apk-dir> > crates/core/proto/rpc.tsv
"""
import glob
import os
import re
import sys

PROTO = re.compile(r"L((?:com|spotify)/[^;]*?(?:proto|Proto)[^;]*);")


def proto_of(java):
    pkg, _, cls = java.rpartition("/")
    return pkg.replace("/", ".") + "." + "_".join(cls.split("$")[1:] or [cls])


def main():
    root = sys.argv[1]
    files = {}
    for path in glob.glob(os.path.join(root, "smali*", "**", "*.smali"), recursive=True):
        files[path.split("/smali", 1)[1].split("/", 1)[1][:-6]] = path
    text_cache = {}

    def text_of(cls):
        if cls not in text_cache:
            p = files.get(cls)
            text_cache[cls] = open(p, errors="ignore").read() if p else ""
        return text_cache[cls]

    DECODE = r"invoke-static \{[vp]\d+\}, L([^;]+);->\w+\(\[B\)L\1;"

    def switch_case(cls, index):
        """Messages decoded in case `index` of a shared lambda's apply(Object), or all of them if it has no switch."""
        text = text_of(cls)
        m = re.search(r"\.method public (?:final )?apply\(Ljava/lang/Object;\)Ljava/lang/Object;.*?\.end method", text, re.S)
        if not m:
            return []
        body = m[0]
        sw = re.search(r"packed-switch [vp]\d+, :(\w+)", body)
        if not sw or index is None:
            return re.findall(DECODE, body)
        table = re.search(r":" + sw[1] + r"\s*\n\s*\.packed-switch (0x[0-9a-f]+|-?\d+)(.*?)\.end packed-switch", body, re.S)
        if not table:
            return []
        first = int(table[1], 0)
        labels = re.findall(r":(\w+)", table[2])
        k = index - first
        if not 0 <= k < len(labels):
            block = body[body.find("packed-switch"):]  # the default branch, before the first case
            block = block[: block.find(":pswitch_")]
        else:
            start = body.find(":" + labels[k] + "\n")
            nxt = re.search(r"\n\s*:pswitch_", body[start + 1:])
            block = body[start: start + 1 + nxt.start()] if nxt else body[start:]
        return re.findall(DECODE, block)

    def singleton_index(cls, field):
        """The byte a shared lambda instance was built with, from its class initialiser."""
        text = text_of(cls)
        m = re.search(r"constructor <clinit>.*?\.end method", text, re.S)
        if not m:
            return None
        body = re.sub(r"\n\s*(\.line \d+)?\s*\n", "\n", m[0])
        hit = re.search(r"const(?:/4|/16)? [vp]\d+, (-?0x[0-9a-f]+|-?\d+)\s*\n\s*invoke-direct \{[vp]\d+, [vp]\d+\}, L" + re.escape(cls) + r";-><init>\(B\)V\s*\n\s*sput-object [vp]\d+, L" + re.escape(cls) + r";->" + re.escape(field) + ":", body)
        return int(hit[1], 0) if hit else None

    def responses(after):
        nxt = after.find("ClientBase;->call")
        after = after[:nxt] if nxt >= 0 else after
        first_map = after.find("->map(")
        head = after[: first_map] if first_map >= 0 else after[:600]
        found = re.findall(DECODE, head)
        s = re.search(r"sget-object [vp]\d+, L([^;]+);->(\w+):L\1;", head)
        if s:
            found += switch_case(s[1], singleton_index(s[1], s[2]))
        n = re.search(r"new-instance [vp]\d+, L([^;]+);(?:.|\n){0,200}?const(?:/4|/16)? [vp]\d+, (-?0x[0-9a-f]+|-?\d+)", head)
        if n:
            found += switch_case(n[1], int(n[2], 0))
        elif not s:
            for lam in re.findall(r"new-instance [vp]\d+, L([^;]+);", head):
                found += switch_case(lam, None)
        return found

    rows = set()
    for cls, path in files.items():
        text = open(path, errors="ignore").read()
        if "ClientBase;->call" not in text:
            continue
        for method in re.findall(r"\.method.*?\.end method", text, re.S):
            for call in re.finditer(r"ClientBase;->(call\w+)\(", method):
                before, after = method[: call.start()], method[call.end():]
                strings = re.findall(r'const-string(?:/jumbo)? [vp]\d+, "([^"]+)"', before)
                services = [s for s in strings if re.fullmatch(r"[a-z_][\w.]*\.[A-Z]\w+", s)]
                methods = [s for s in strings if re.fullmatch(r"[A-Z]\w+", s)]
                if not services or not methods:
                    continue
                casts = re.findall(r"check-cast [vp]\d+, L([^;]+);", before)
                reqs = [c for c in casts if PROTO.match("L" + c + ";")]
                built = re.findall(r"invoke-static \{\}, L([^;]+);->\w+\(\)L", before)
                req = reqs[-1] if reqs else next((b for b in reversed(built) if PROTO.match("L" + b + ";")), "?")
                resp = [r for r in dict.fromkeys(responses(after)) if PROTO.match("L" + r + ";")]
                if len(resp) > 1:  # a shared decoder whose case wasn't pinned: keep the one from the request's package
                    near = req if req != "?" else services[-1].replace(".", "/")
                    resp = [max(resp, key=lambda r: (len(os.path.commonprefix([r, near])), -resp.index(r)))]
                kind = call.group(1).replace("call", "").lower()
                rows.add((services[-1], methods[-1], kind, proto_of(req) if req != "?" else "?", ",".join(proto_of(r) for r in resp) or "?", cls))
    merged = {}
    for service, method, kind, req, resp, cls in sorted(rows):  # one row per RPC: the call sites that name its messages win
        row = merged.setdefault((service, method, kind), ["?", "?", []])
        if row[0] == "?": row[0] = req
        if row[1] == "?": row[1] = resp
        row[2].append(cls)
    known = {proto_of(c) for c in files if PROTO.match("L" + c + ";")}
    for (service, method, kind), row in merged.items():
        # No call site named the request: the matching *Request (or <Method>Request) beside the response, if one exists.
        if row[0] == "?" and row[1] != "?":
            pkg = row[1].rpartition(".")[0]
            for guess in (row[1][: -len("Response")] + "Request" if row[1].endswith("Response") else None, pkg + "." + method + "Request"):
                if guess and guess in known:
                    row[0] = guess + " (by name)"; break
    print("service\tmethod\tkind\trequest\tresponse\tclients")
    for (service, method, kind), (req, resp, clients) in sorted(merged.items()):
        print("\t".join([service, method, kind, req, resp, ",".join(clients)]))


if __name__ == "__main__":
    main()
