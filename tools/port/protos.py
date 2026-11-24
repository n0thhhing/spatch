#!/usr/bin/env python3
"""Rebuilds .proto files from Spotify's protobuf-lite classes in an apktool-decoded APK.

Every generated message passes its exact schema to newMessageInfo: a "message info" string (field
numbers, wire types, oneofs) and an object array (Java field names, element classes). That survives
R8, so it is decoded here instead of guessing from the obfuscated getters. Proto field names come
from the kept *_FIELD_NUMBER constants. Enums become int32 (same wire format).

    tools/port/protos.py <decoded-apk-dir> <out-dir> [java/package/prefix ...]
"""
import os
import re
import sys
from collections import defaultdict

SCALAR = {0: "double", 1: "float", 2: "int64", 3: "uint64", 4: "int32", 5: "fixed64", 6: "fixed32", 7: "bool",
          8: "string", 9: "MESSAGE", 10: "bytes", 11: "uint32", 12: "int32", 13: "sfixed32", 14: "sfixed64",
          15: "sint32", 16: "sint64", 17: "GROUP"}
PACKED = [0, 1, 2, 3, 4, 5, 6, 7, 11, 12, 13, 14, 15, 16]  # scalar kinds of 35..48
ENUMS = {12, 30, 44, 63}
SUPER = re.compile(r"^\.super Lcom/google/protobuf/h;", re.M)


def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\":
            n = s[i + 1]
            if n == "u":
                out.append(chr(int(s[i + 2:i + 6], 16))); i += 6; continue
            out.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "0": "\0"}.get(n, n)); i += 2; continue
        out.append(c); i += 1
    return out


class Reader:
    def __init__(self, chars):
        self.c, self.i = [ord(x) for x in chars], 0

    def next(self):
        v = self.c[self.i]; self.i += 1
        if v < 0xD800:
            return v
        result, shift = v & 0x1FFF, 13
        while True:
            v = self.c[self.i]; self.i += 1
            if v < 0xD800:
                return result | (v << shift)
            result |= (v & 0x1FFF) << shift; shift += 13


def objects(method):
    """The object array passed to newMessageInfo, as ('s', name) / ('c', class) / ('o', other)."""
    regs, arr = {}, None
    lines = method.split("\n")
    for ln in lines:
        ln = ln.strip()
        m = re.match(r'const-string(?:/jumbo)? ([vp]\d+), "(.*)"$', ln)
        if m: regs[m[1]] = ("s", m[2]); continue
        m = re.match(r"const-class ([vp]\d+), L([^;]+);", ln)
        if m: regs[m[1]] = ("c", m[2]); continue
        m = re.match(r"sget-object ([vp]\d+), L([^;]+);->(\w+):", ln)
        if m: regs[m[1]] = ("o", m[2] + "." + m[3]); continue
        m = re.match(r"move-object(?:/from16|/16)? ([vp]\d+), ([vp]\d+)", ln)
        if m: regs[m[1]] = regs.get(m[2]); continue
        m = re.match(r"filled-new-array/range \{([vp])(\d+) \.\. [vp](\d+)\}, \[Ljava/lang/Object;", ln)
        if m: arr = [regs.get(m[1] + str(k)) for k in range(int(m[2]), int(m[3]) + 1)]; continue
        m = re.match(r"filled-new-array \{([^}]*)\}, \[Ljava/lang/Object;", ln)
        if m: arr = [regs.get(r.strip()) for r in m[1].split(",") if r.strip()]; continue
        m = re.match(r"new-array ([vp]\d+), ([vp]\d+), \[Ljava/lang/Object;", ln)
        if m: arr = []; continue
        m = re.match(r"aput-object ([vp]\d+), ([vp]\d+), ([vp]\d+)", ln)
        if m and arr is not None:
            arr.append(regs.get(m[1])); continue
        if "newMessageInfo" in ln:
            break
        m = re.match(r'const-string(?:/jumbo)? [vp]\d+, "(.*)"$', ln)
    return arr or []


def parse_class(path):
    text = open(path, errors="ignore").read()
    if not SUPER.search(text):
        return None
    name = re.search(r"^\.class (?:[a-z]+ )*L([^;]+);", text, re.M)[1]
    numbers = {int(v, 16): k.lower() for k, v in re.findall(r"\.field public static final (\w+)_FIELD_NUMBER:I = (0x[0-9a-f]+)", text)}
    jtypes = dict(re.findall(r"\.field private (\w+_):(\S+)", text))
    dm = re.search(r"\.method public final dynamicMethod\(.*?\.end method", text, re.S)
    if not dm:
        return None
    body = dm[0]
    info = None
    for m in re.finditer(r'const-string(?:/jumbo)? [vp]\d+, "((?:[^"\\]|\\.)*)"\n(?:\s*\n|\s*\.line \d+\n)*\s*sget-object [vp]\d+, L[^;]+;->DEFAULT_INSTANCE', body):
        info = m[1]
    if info is None:
        return {"name": name, "fields": [], "error": "no message info"}
    objs = objects(body[: body.find("newMessageInfo")])
    r = Reader(unescape(info))
    flags = r.next(); count = r.next()
    oneofs = has_bits = 0
    if count:
        oneofs, has_bits = r.next(), r.next()
        for _ in range(6): r.next()  # min, max, entries, maps, repeated, check-initialized
    pos = oneofs * 2 + has_bits
    oneof_names = [objs[k * 2][1] if k * 2 < len(objs) and objs[k * 2] else "oneof%d" % k for k in range(oneofs)]
    fields, error = [], None
    try:
        for _ in range(count):
            num, extra = r.next(), r.next()
            kind = extra & 0xFF
            f = {"num": num, "name": numbers.get(num, "field_%d" % num), "kind": kind, "label": "", "type": None, "oneof": None}
            if kind >= 51:
                f["oneof"] = oneof_names[r.next()]
                base = kind - 51
                f["type"] = SCALAR[base]
                if base in (9, 17):
                    f["type"] = ("msg", objs[pos][1]); pos += 1
                elif base == 12 and (extra & 0x800):
                    pos += 1
            else:
                jname = objs[pos][1] if pos < len(objs) and objs[pos] else None
                pos += 1
                if kind <= 17:
                    f["type"] = SCALAR[kind]
                    if kind in (9, 17):
                        t = jtypes.get(jname, "")
                        f["type"] = ("msg", t[1:-1]) if t.startswith("L") else "bytes"
                    if kind == 12 and (extra & 0x800): pos += 1
                    if extra & 0x1000: r.next()
                elif kind <= 34:
                    f["label"] = "repeated"; base = kind - 18
                    f["type"] = SCALAR[base]
                    if base in (9, 17):
                        f["type"] = ("msg", objs[pos][1]); pos += 1
                    if base == 12 and (extra & 0x800): pos += 1
                elif kind <= 48:
                    f["label"] = "repeated"; f["type"] = SCALAR[PACKED[kind - 35]]
                    if PACKED[kind - 35] == 12 and (extra & 0x800): pos += 1
                elif kind == 49:
                    f["label"] = "repeated"; f["type"] = ("msg", objs[pos][1]); pos += 1
                elif kind == 50:
                    f["type"] = ("map", objs[pos][1] if pos < len(objs) and objs[pos] else "?"); pos += 1
                    if extra & 0x800: pos += 1
            fields.append(f)
    except (IndexError, KeyError, TypeError) as e:
        error = "decode: %r" % e
    if not error and pos != len(objs):
        error = "object array: used %d of %d" % (pos, len(objs))
    return {"name": name, "fields": fields, "error": error}


_files = {}
_field_types = {}
WIRE = {"DOUBLE": "double", "FLOAT": "float", "INT64": "int64", "UINT64": "uint64", "INT32": "int32", "FIXED64": "fixed64",
        "FIXED32": "fixed32", "BOOL": "bool", "STRING": "string", "BYTES": "bytes", "UINT32": "uint32", "ENUM": "int32",
        "SFIXED32": "sfixed32", "SFIXED64": "sfixed64", "SINT32": "sint32", "SINT64": "sint64"}


def smali(root, cls):
    if cls not in _files:
        _files[cls] = None
        for d in os.listdir(root):
            p = os.path.join(root, d, cls + ".smali")
            if d.startswith("smali") and os.path.exists(p):
                _files[cls] = open(p, errors="ignore").read(); break
    return _files[cls]


def field_type_names(root, cls):
    """WireFormat.FieldType's obfuscated static fields by constant name, from its <clinit>."""
    if cls not in _field_types:
        names, reg_name, last = {}, {}, None
        text = smali(root, cls) or ""
        clinit = re.search(r"constructor <clinit>.*?\.end method", text, re.S)
        for ln in (clinit[0] if clinit else "").split("\n"):
            ln = ln.strip()
            m = re.match(r"new-instance ([vp]\d+), L", ln)
            if m: last = m[1]; reg_name[last] = None; continue
            m = re.match(r'const-string [vp]\d+, "([A-Z0-9_]+)"', ln)
            if m and last and reg_name.get(last) is None: reg_name[last] = m[1]; continue
            m = re.match(r"sput-object ([vp]\d+), L[^;]+;->(\w+):", ln)
            if m and reg_name.get(m[1]): names[m[2]] = reg_name[m[1]]
        _field_types[cls] = names
    return _field_types[cls]


def map_types(root, holder):
    """Key and value types of a map field, from the MapEntryLite its default-entry holder builds."""
    cls, _, field = holder.partition(".")
    text = smali(root, cls) or ""
    clinit = re.search(r"constructor <clinit>.*?\.end method", text, re.S)
    body = clinit[0] if clinit else ""
    regs, kinds = {}, []
    for ln in body.split("\n"):
        ln = ln.strip()
        m = re.match(r"sget-object ([vp]\d+), L([^;]+);->(\w+):L([^;]+);", ln)
        if m: regs[m[1]] = ("sget", m[2], m[3]); continue
        m = re.match(r"invoke-static \{\}, L([^;]+);->\w+\(\)L", ln)
        if m: regs["result"] = ("msg", m[1]); continue
        m = re.match(r"move-result-object ([vp]\d+)", ln)
        if m and "result" in regs: regs[m[1]] = regs.pop("result"); continue
        m = re.match(r"invoke-direct \{[vp]\d+, ([vp]\d+), ([vp]\d+), ([vp]\d+), ([vp]\d+)\}, L[^;]+;-><init>", ln)
        if m: kinds = [regs.get(m[1]), regs.get(m[2]), regs.get(m[3]), regs.get(m[4])]
    def kind(r):
        if r and r[0] == "sget":
            return WIRE.get(field_type_names(root, r[1]).get(r[2]), "MESSAGE" if field_type_names(root, r[1]).get(r[2]) == "MESSAGE" else None)
        return None
    if len(kinds) != 4:
        return "string", "bytes", "unresolved map entry"
    key = kind(kinds[0]) or "string"
    value = kind(kinds[2])
    vdef = kinds[3]
    if value in (None, "MESSAGE"):
        if vdef and vdef[0] == "sget" and vdef[2] == "DEFAULT_INSTANCE": value = ("msg", vdef[1])
        elif vdef and vdef[0] == "msg": value = ("msg", vdef[1])
        else: value = "bytes"
    return key, value, None


def proto_name(java):
    pkg, _, cls = java.rpartition("/")
    parts = cls.split("$")
    return pkg.replace("/", "."), "_".join(parts[1:]) if len(parts) > 1 else parts[0]


def main():
    root, out = sys.argv[1], sys.argv[2]
    prefixes = sys.argv[3:] or ["com/spotify/"]
    msgs = []
    for d in sorted(os.listdir(root)):
        if not d.startswith("smali"): continue
        for pre in prefixes:
            base = os.path.join(root, d, pre)
            if not os.path.isdir(base): continue
            for dp, _, fs in os.walk(base):
                for f in fs:
                    if f.endswith(".smali"):
                        m = parse_class(os.path.join(dp, f))
                        if m: msgs.append(m)
    by_pkg = defaultdict(list)
    names = {}
    taken = defaultdict(list)
    for m in msgs:
        taken[proto_name(m["name"])].append(m["name"])
    for m in msgs:
        pkg, n = proto_name(m["name"])
        if len(taken[(pkg, n)]) > 1:  # two outer files in one package reuse an inner name: keep the outer
            n = "_".join(m["name"].rpartition("/")[2].split("$"))
        names[m["name"]] = (pkg, n)
        by_pkg[pkg].append((n, m))
    os.makedirs(out, exist_ok=True)
    bad = 0
    for pkg, items in sorted(by_pkg.items()):
        lines = ['syntax = "proto3";', "", "// Rebuilt from Spotify's generated classes by tools/port/protos.py; enums are int32.", "package %s;" % pkg, ""]
        imports = set()
        body = []
        for n, m in sorted(items, key=lambda x: x[0]):
            if m["error"]: bad += 1; body.append("// %s: %s" % (n, m["error"]))
            body.append("message %s {" % n)
            groups = defaultdict(list)
            plain = []
            for f in m["fields"]:
                (groups[f["oneof"]] if f["oneof"] else plain).append(f)

            def render(f):
                t = f["type"]
                if isinstance(t, tuple) and t[0] == "msg":
                    p, mn = names.get(t[1], proto_name(t[1]))
                    if p != pkg: imports.add(p)
                    t = mn if p == pkg else "." + p + "." + mn
                elif isinstance(t, tuple) and t[0] == "map":
                    k, v, _ = map_types(root, t[1])
                    if isinstance(v, tuple):
                        p, mn = names.get(v[1], proto_name(v[1]))
                        if p != pkg: imports.add(p)
                        v = mn if p == pkg else "." + p + "." + mn
                    t = "map<%s, %s>" % (k, v)
                elif t in ("MESSAGE", "GROUP", None):
                    t = "bytes"
                label = f["label"] + " " if f["label"] else ""
                return "%s%s %s = %d;" % (label, t, f["name"], f["num"])
            for f in plain:
                body.append("  " + render(f))
            for g, fs in groups.items():
                body.append("  oneof %s {" % g.rstrip("_"))
                for f in fs: body.append("    " + render(f))
                body.append("  }")
            body.append("}")
            body.append("")
        for p in sorted(imports):
            lines.insert(4, 'import "%s.proto";' % p)
        with open(os.path.join(out, pkg + ".proto"), "w") as fh:
            fh.write("\n".join(lines + body))
    print("%d messages in %d packages, %d with decode warnings" % (len(msgs), len(by_pkg), bad))


if __name__ == "__main__":
    main()
