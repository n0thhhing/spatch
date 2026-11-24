"""Finds the serialized FileDescriptorProtos in Spotify's core (liborbit-jni-spotify.so) and prints every service, method,
message and enum as .proto text: python3 -I tools/port/core_protos.py <lib.so> [package filter]."""
import re
import sys

TYPES = {1: "double", 2: "float", 3: "int64", 4: "uint64", 5: "int32", 6: "fixed64", 7: "fixed32", 8: "bool",
         9: "string", 12: "bytes", 13: "uint32", 15: "sfixed32", 16: "sfixed64", 17: "sint32", 18: "sint64"}


def varint(b, i):
    v = s = 0
    while True:
        if i >= len(b) or s > 63:
            raise ValueError
        c = b[i]; i += 1
        v |= (c & 0x7f) << s; s += 7
        if c < 0x80:
            return v, i


def fields(b, allowed=None):
    """(number, wire, value) until the bytes stop being a valid message; returns (list, end)."""
    out, i = [], 0
    while i < len(b):
        start = i
        try:
            tag, i = varint(b, i)
        except ValueError:
            return out, start
        num, wire = tag >> 3, tag & 7
        if num == 0 or (allowed and num not in allowed):
            return out, start
        try:
            if wire == 0:
                v, i = varint(b, i)
            elif wire == 2:
                n, i = varint(b, i)
                if i + n > len(b):
                    return out, start
                v = b[i:i + n]; i += n
            elif wire == 5:
                v = b[i:i + 4]; i += 4
            elif wire == 1:
                v = b[i:i + 8]; i += 8
            else:
                return out, start
        except ValueError:
            return out, start
        out.append((num, wire, v))
    return out, i


def text(v):
    return v.decode("utf-8", "replace")


def message(b, indent="  "):
    fs, _ = fields(b)
    name = next((text(v) for n, w, v in fs if n == 1 and w == 2), "?")
    lines = [f"{indent[:-2]}message {name} {{"]
    for n, w, v in fs:
        if n == 2 and w == 2:  # field
            ff, _ = fields(v)
            d = {k: val for k, _, val in ff}
            t = TYPES.get(d.get(5), text(d.get(6, b"")).lstrip(".") or "?")
            rep = "repeated " if d.get(4) == 3 else ""
            lines.append(f"{indent}{rep}{t} {text(d.get(1, b'?'))} = {d.get(3, '?')};")
        elif n == 3 and w == 2:
            lines.extend(message(v, indent + "  "))
        elif n == 4 and w == 2:
            lines.extend(enum(v, indent + "  "))
    lines.append(f"{indent[:-2]}}}")
    return lines


def enum(b, indent="  "):
    fs, _ = fields(b)
    name = next((text(v) for n, w, v in fs if n == 1), "?")
    values = []
    for n, w, v in fs:
        if n == 2 and w == 2:
            d = {k: val for k, _, val in fields(v)[0]}
            values.append(f"{text(d.get(1, b'?'))}={d.get(2, 0)}")
    return [f"{indent[:-2]}enum {name} {{ {', '.join(values)} }}"]


def main():
    data = open(sys.argv[1], "rb").read()
    want = sys.argv[2] if len(sys.argv) > 2 else ""
    seen = set()
    for m in re.finditer(rb"\x0a([\x05-\x7f])([a-z0-9_/.]+\.proto)", data):
        if m.group(1)[0] != len(m.group(2)):
            continue
        fs, end = fields(data[m.start():m.start() + 200_000], allowed=set(range(1, 15)))
        if len(fs) < 2 or not any(n == 2 for n, _, _ in fs):
            continue
        name = text(m.group(2))
        pkg = next((text(v) for n, w, v in fs if n == 2 and w == 2), "")
        if (name, pkg) in seen or (want and want not in pkg and want not in name):
            continue
        seen.add((name, pkg))
        print(f"// {name}\npackage {pkg};")
        for n, w, v in fs:
            if n == 4 and w == 2:
                print("\n".join(message(v)))
            elif n == 5 and w == 2:
                print("\n".join(enum(v)))
            elif n == 6 and w == 2:
                sf, _ = fields(v)
                print(f"service {next((text(x) for k, _, x in sf if k == 1), '?')} {{")
                for k, _, x in sf:
                    if k == 2:
                        d = {a: c for a, _, c in fields(x)[0]}
                        stream = " stream" if d.get(6) else ""
                        print(f"  rpc {text(d.get(1, b'?'))}({text(d.get(2, b'')).lstrip('.')}) returns ({stream.strip()} {text(d.get(3, b'')).lstrip('.')});")
                print("}")
        print()


main()
