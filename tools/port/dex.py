"""Read the DEX metadata and normal control flow needed by binding discovery."""
import hashlib
import re
import struct
import zlib
from dataclasses import dataclass


class Unsupported(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Unsupported(message)


def one(values, role):
    values = list(values)
    require(len(values) == 1, f'{role}: expected one match, found {len(values)}')
    return values[0]


def parameters(proto):
    params, result = proto[1:].split(')', 1)
    values = re.findall(r'\[*(?:L[^;]+;|[ZBCSIFJD])', params)
    require(''.join(values) == params and bool(result), f'invalid prototype: {proto}')
    return values, result


WIDTHS = {}
for width, groups in (
    (1, '00 01 04 07 0a-12 1d 1e 21 27 28 7b-8f b0-cf'),
    (2, '02 05 08 13 15 16 19 1a 1c 1f 20 22 23 29 2d-3d 44-6d 90-af d0-e2 fe ff'),
    (3, '03 06 09 14 17 1b 24-26 2a-2c 6e-72 74-78 fc fd'),
    (4, 'fa fb'), (5, '18'),
):
    for group in groups.split():
        ends = group.split('-')
        for op in range(int(ends[0], 16), int(ends[-1], 16) + 1):
            WIDTHS[op] = width


@dataclass
class Instruction:
    offset: int
    op: int
    words: tuple
    ref: object = None

    @property
    def next(self):
        return self.offset + len(self.words)

    @property
    def registers(self):
        high = self.words[0] >> 8
        if 0x6e <= self.op <= 0x72:
            packed = self.words[2]
            regs = [(packed >> (4 * i)) & 15 for i in range(4)] + [high & 15]
            require(high >> 4 <= 5, 'invalid invoke register count')
            return tuple(regs[:high >> 4])
        if 0x74 <= self.op <= 0x78:
            return tuple(range(self.words[2], self.words[2] + high))
        if self.op in (0x01, 0x04, 0x07, 0x20, 0x21, 0x23) or 0x32 <= self.op <= 0x37 or 0x52 <= self.op <= 0x5f:
            return high & 15, high >> 4
        if self.op in (0x02, 0x05, 0x08):
            return high, self.words[1]
        if self.op in (0x03, 0x06, 0x09):
            return self.words[1], self.words[2]
        return (high,)


class Method:
    def __init__(self, dex, ref, flags, code):
        self.dex, self.ref, self.flags, self.code = dex, ref, flags, code

    def body(self):
        require(self.code != 0, f'no code: {self.ref}')
        regs, ins, outs, tries, debug, count = self.dex.unpack('<HHHHII', self.code)
        require(count <= (len(self.dex.data) - self.code - 16) // 2, 'code item exceeds DEX file')
        words = self.dex.unpack('<' + 'H' * count, self.code + 16)
        instructions, offset = {}, 0
        while offset < count:
            word = words[offset]
            op, size, ref = word & 255, WIDTHS.get(word & 255), None
            require(size is not None, f'unsupported opcode {op:#x} in {self.ref}')
            if op == 0 and word:
                require(word in (0x100, 0x200, 0x300), 'unknown instruction payload')
                n = words[offset + 1]
                size = (4 + n * 2) if word == 0x100 else (2 + n * 4)
                if word == 0x300:
                    size = 4 + (n * (words[offset + 2] | words[offset + 3] << 16) + 1) // 2
            require(offset + size <= count, f'truncated instruction: {self.ref}')
            part = words[offset:offset + size]
            if op in (0x1a, 0x1b):
                ref = self.dex.strings[part[1] if op == 0x1a else part[1] | part[2] << 16]
            elif op in (0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25):
                ref = self.dex.types[part[1]]
            elif 0x52 <= op <= 0x6d:
                ref = self.dex.fields[part[1]]
            elif 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78:
                ref = self.dex.methods[part[1]]
            elif op >= 0xfa:
                raise Unsupported(f'unsupported modern invoke/reference: {self.ref}')
            instructions[offset] = Instruction(offset, op, part, ref)
            offset += size
        return instructions

    def literals(self):
        return {i.ref for i in self.body().values() if i.op in (0x1a, 0x1b)} if self.code else set()


class Dex:
    def __init__(self, data, name):
        self.data, self.name = data, name
        require(data[:8] in (b'dex\n035\0', b'dex\n037\0', b'dex\n038\0', b'dex\n039\0', b'dex\n040\0'), 'unsupported DEX version')
        require(self.u32(32) == len(data) and self.u32(36) == 112 and self.u32(40) == 0x12345678, 'invalid DEX header')
        require(self.u32(8) == zlib.adler32(data[12:]) & 0xffffffff, 'DEX checksum mismatch')
        require(data[12:32] == hashlib.sha1(data[32:]).digest(), 'DEX signature mismatch')
        self.strings = []
        for i in range(self.u32(56)):
            start = self.u32(self.u32(60) + i * 4)
            _, start = self.uleb(start)
            raw = data[start:data.index(0, start)].replace(b'\xc0\x80', b'\0')
            value = raw.decode('utf-8', 'surrogatepass')
            self.strings.append(value.encode('utf-16-le', 'surrogatepass').decode('utf-16-le', 'surrogatepass'))
        self.types = [self.strings[self.u32(self.u32(68) + i * 4)] for i in range(self.u32(64))]
        protos = []
        for i in range(self.u32(72)):
            _, ret, params = self.unpack('<III', self.u32(76) + i * 12)
            protos.append('(' + ''.join(self.type_list(params)) + ')' + self.types[ret])
        self.fields, self.methods, self.classes = [], [], {}
        for i in range(self.u32(80)):
            owner, typ, name = self.unpack('<HHI', self.u32(84) + i * 8)
            self.fields.append((self.types[owner], self.strings[name], self.types[typ]))
        for i in range(self.u32(88)):
            owner, proto, name = self.unpack('<HHI', self.u32(92) + i * 8)
            self.methods.append((self.types[owner], self.strings[name], protos[proto]))
        for i in range(self.u32(96)):
            row = self.unpack('<IIIIIIII', self.u32(100) + i * 32)
            require(self.types[row[0]] not in self.classes, 'duplicate DEX class')
            self.classes[self.types[row[0]]] = row

    def unpack(self, fmt, off):
        require(0 <= off <= len(self.data) - struct.calcsize(fmt), 'DEX offset outside file')
        return struct.unpack_from(fmt, self.data, off)

    def u32(self, off):
        return self.unpack('<I', off)[0]

    def uleb(self, off):
        value = 0
        for shift in range(0, 35, 7):
            b = self.data[off]
            off += 1
            value |= (b & 127) << shift
            if b < 128:
                require(value <= 0xffffffff, 'ULEB128 overflow')
                return value, off
        raise Unsupported('invalid ULEB128')

    def type_list(self, off):
        if not off:
            return ()
        count = self.u32(off)
        require(count <= (len(self.data) - off - 4) // 2, 'type list exceeds DEX file')
        return tuple(self.types[i] for i in self.unpack('<' + 'H' * count, off + 4))

    def members(self, name):
        off = self.classes[name][6]
        fields, methods, counts = [], [], []
        if not off:
            return fields, methods
        for _ in range(4):
            count, off = self.uleb(off)
            counts.append(count)
        for group, count in enumerate(counts):
            idx = 0
            for _ in range(count):
                diff, off = self.uleb(off)
                flags, off = self.uleb(off)
                idx += diff
                if group < 2:
                    fields.append((self.fields[idx], flags))
                else:
                    code, off = self.uleb(off)
                    methods.append(Method(self, self.methods[idx], flags, code))
        require(all(f[0][0] == name for f in fields) and all(m.ref[0] == name for m in methods), 'class member owner mismatch')
        return fields, methods

    def selected(self, markers):
        ids = {i for i, s in enumerate(self.strings) if s in markers}
        if not ids:
            return
        patterns = [b'\x1b.' + re.escape(struct.pack('<I', i)) for i in ids]
        patterns += [b'\x1a.' + re.escape(struct.pack('<H', i)) for i in ids if i < 65536]
        pattern = re.compile(b'|'.join(patterns), re.DOTALL)
        for name in self.classes:
            for method in self.members(name)[1]:
                if method.code:
                    count = self.u32(method.code + 12)
                    buf = self.data[method.code + 16:method.code + 16 + count * 2]
                    if pattern.search(buf) and method.literals() & markers:
                        yield method

    def annotation(self, off):
        typ, off = self.uleb(off)
        count, off = self.uleb(off)
        values = {}
        for _ in range(count):
            name, off = self.uleb(off)
            value, off = self.encoded(off)
            values[self.strings[name]] = value
        return (self.types[typ], values), off

    def encoded(self, off):
        head = self.data[off]
        kind, size, off = head & 31, (head >> 5) + 1, off + 1
        if kind == 0x1c:
            count, off = self.uleb(off)
            values = []
            for _ in range(count):
                value, off = self.encoded(off)
                values.append(value)
            return values, off
        if kind == 0x1d:
            return self.annotation(off)
        if kind in (0x1e, 0x1f):
            return (None if kind == 0x1e else bool(head >> 5)), off
        require(off + size <= len(self.data), 'truncated encoded value')
        value = int.from_bytes(self.data[off:off + size], 'little')
        pools = {0x17: self.strings, 0x18: self.types, 0x19: self.fields, 0x1a: self.methods, 0x1b: self.fields}
        require(kind in pools or kind in (0, 2, 3, 4, 6, 0x10, 0x11), 'unsupported encoded value')
        return (pools[kind][value] if kind in pools else value), off + size

    def annotations(self, name):
        directory = self.classes[name][5]
        off = self.u32(directory) if directory else 0
        result = {}
        if off:
            for i in range(self.u32(off)):
                item = self.u32(off + 4 + i * 4)
                (typ, values), _ = self.annotation(item + 1)
                require(typ not in result, 'duplicate annotation')
                result[typ] = values
        return result


def successors(body, ins):
    op, words = ins.op, ins.words
    signed16 = lambda n: n - 65536 if n & 0x8000 else n
    signed32 = lambda a, b: struct.unpack('<i', struct.pack('<HH', a, b))[0]
    if op in (0x0e, 0x0f, 0x10, 0x11, 0x27):
        return ()
    if op in (0x28, 0x29, 0x2a):
        delta = ((words[0] >> 8) - 256 if words[0] & 0x8000 else words[0] >> 8) if op == 0x28 else signed16(words[1]) if op == 0x29 else signed32(words[1], words[2])
        targets = (ins.offset + delta,)
    elif 0x32 <= op <= 0x3d:
        targets = (ins.next, ins.offset + signed16(words[1]))
    elif op in (0x2b, 0x2c):
        payload = body[ins.offset + signed32(words[1], words[2])].words
        require(payload[0] == (0x100 if op == 0x2b else 0x200), 'invalid switch payload')
        start = 4 if op == 0x2b else 2 + payload[1] * 2
        targets = (ins.next,) + tuple(ins.offset + signed32(payload[i], payload[i + 1]) for i in range(start, len(payload), 2))
    else:
        targets = (ins.next,)
    require(all(t in body and not (body[t].op == 0 and body[t].words[0]) for t in targets), 'invalid control-flow target')
    return targets


def reachable(body, start, blocked=()):
    found, todo = set(), [start]
    while todo:
        pos = todo.pop()
        if pos in found or pos in blocked:
            continue
        require(pos in body, 'control flow outside method')
        found.add(pos)
        todo.extend(successors(body, body[pos]))
    return found
