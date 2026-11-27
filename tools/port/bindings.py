"""Discover Spatch's required Spotify bindings from APK/APKM behavior, without old names."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
import zipfile

from dex import Dex, Unsupported, one, parameters, reachable, require, successors


MAKER = 'Lcom/spotify/connectivity/httpretrofit/RetrofitMaker;'
HOST = 'spclient.wg.spotify.com'
ASSERTION = 'Service %s has a configurator but no HttpCallFactoryBuilder is wired into this RetrofitMaker'
AUTH = 'Could not retrieve access token for a webgate request: '
BUFFER = 'Cannot buffer entire body for content length: '
RAW = 'Cannot read raw response body of a converted body.'
MARKERS = {
    'GET': 'GET', 'POST': 'POST', 'Headers': '@Headers annotation is empty.',
    'Path': '@Path parameter name must match %s. Found: %s',
    'Body': 'Multiple @Body method annotations found.',
    'QueryMap': '@QueryMap parameter type must be Map.',
    'HeaderMap': '@HeaderMap parameter type must be Map or Headers.',
}
SCHEMAS = {
    'GET': {'value': '()Ljava/lang/String;'}, 'POST': {'value': '()Ljava/lang/String;'},
    'Headers': {'value': '()[Ljava/lang/String;', 'allowUnsafeNonAsciiValues': '()Z'},
    'Path': {'value': '()Ljava/lang/String;', 'encoded': '()Z'}, 'Body': {},
    'QueryMap': {'encoded': '()Z'}, 'HeaderMap': {'allowUnsafeNonAsciiValues': '()Z'},
}


def method_binding(method, **evidence):
    return dict(owner=method.ref[0], name=method.ref[1], descriptor=method.ref[2],
                access=method.flags, dex=method.dex.name, evidence=evidence)


class Input:
    def __init__(self, path):
        raw = Path(path).read_bytes()
        self.sha256 = hashlib.sha256(raw).hexdigest()
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), 'duplicate input archive entries')
            base = archive.read('base.apk') if 'base.apk' in names else raw
        self.base_sha256 = hashlib.sha256(base).hexdigest()
        self.dexes, self.classes = [], {}
        with zipfile.ZipFile(io.BytesIO(base)) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), 'duplicate base APK entries')
            for name in sorted(n for n in names if re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', n)):
                dex = Dex(archive.read(name), name)
                require(not self.classes.keys() & dex.classes.keys(), 'class defined in multiple DEX files')
                self.classes.update((typ, dex) for typ in dex.classes)
                self.dexes.append(dex)
        require(self.dexes, 'input contains no DEX files')
        self.selected = []
        markers = set(MARKERS.values()) | {HOST, ASSERTION, AUTH, '_', 'Authorization', 'Bearer ', BUFFER, RAW}
        for dex in self.dexes:
            self.selected.extend(dex.selected(markers))

    def members(self, typ):
        require(typ in self.classes, f'missing class: {typ}')
        return self.classes[typ].members(typ)

    def methods(self, typ):
        return self.members(typ)[1]

    def method(self, typ, name, descriptor):
        return one((m for m in self.methods(typ) if m.ref[1:] == (name, descriptor)), f'{typ}->{name}{descriptor}')


def annotation_roles(methods, annotation_types=None):
    found = {role: [] for role in MARKERS}
    for method in methods:
        if not method.code:
            continue
        body = method.body()
        live = reachable(body, 0)
        for test in body.values():
            if test.op != 0x20 or test.offset not in live or (annotation_types is not None and test.ref not in annotation_types):
                continue
            branch = test_condition(body, test)
            if branch is None:
                continue
            fallthrough, target = successors(body, branch)
            yes, no = (fallthrough, target) if branch.op == 0x38 else (target, fallthrough)
            blocked = {test.offset, branch.offset}
            exclusive = reachable(body, yes, blocked) - reachable(body, no, blocked)
            literals = {body[p].ref for p in exclusive if body[p].op in (0x1a, 0x1b)}
            for role, marker in MARKERS.items():
                if marker in literals:
                    found[role].append(dict(type=test.ref, consumer=method_binding(method),
                                            type_test=test.offset, branch=branch.offset, marker=marker))
    return {role: one(rows, f'Retrofit {role} branch') for role, rows in found.items()}


def test_condition(body, test):
    aliases, pos = {test.registers[0]}, test.next
    while pos in body and aliases:
        ins = body[pos]
        if ins.op in (0x38, 0x39):
            return ins if ins.registers[0] in aliases else None
        if ins.op in range(0x27, 0x3e) or ins.op in (0x0e, 0x0f, 0x10, 0x11):
            return None
        copied = ins.op in (0x01, 0x02, 0x03) and ins.registers[1] in aliases
        aliases = {r for r in aliases if not writes_register(ins, r)}
        if copied:
            aliases.add(ins.registers[0])
        pos = ins.next
    return None


def verify_annotation(source, role, typ):
    dex = source.classes[typ]
    require(dex.classes[typ][1] & 0x2601 == 0x2601, f'{role}: not a public annotation interface')
    methods = source.methods(typ)
    require(len(methods) == len(SCHEMAS[role]) and {m.ref[1]: m.ref[2] for m in methods} == SCHEMAS[role], f'{role}: annotation members changed')
    require(all(m.flags & 0x401 == 0x401 and not m.code for m in methods), f'{role}: annotation methods changed')
    annotations = dex.annotations(typ)
    retention = annotations.get('Ljava/lang/annotation/Retention;', {}).get('value')
    require(retention == ('Ljava/lang/annotation/RetentionPolicy;', 'RUNTIME', 'Ljava/lang/annotation/RetentionPolicy;'), f'{role}: annotation is not runtime retained')
    targets = annotations.get('Ljava/lang/annotation/Target;', {}).get('value')
    expected = 'METHOD' if role in ('GET', 'POST', 'Headers') else 'PARAMETER'
    require(targets is None or ('Ljava/lang/annotation/ElementType;', expected, 'Ljava/lang/annotation/ElementType;') in targets, f'{role}: wrong annotation target')
    defaults = annotations.get('Ldalvik/annotation/AnnotationDefault;', {}).get('value', (typ, {}))
    require(defaults[0] == typ, f'{role}: default annotation owner changed')
    return dict(elements=SCHEMAS[role], defaults=defaults[1], runtime_retained=True)


def factory_binding(source, assertion_type):
    candidates = []
    for method in source.selected:
        if not method.flags & 8 or parameters(method.ref[2])[1] != MAKER or HOST not in method.literals():
            continue
        body = method.body()
        # A straight-line factory avoids merging different reaching definitions at a branch.
        if any(i.op in range(0x28, 0x3e) for i in body.values()):
            continue
        live = reachable(body, 0)
        constructors = [i for i in body.values() if i.op in (0x70, 0x76) and i.ref[:2] == (MAKER, '<init>')
                        and len(parameters(i.ref[2])[0]) == 5 and parameters(i.ref[2])[0][-1] == assertion_type]
        for call in constructors:
            if call.offset not in live:
                continue
            regs = call.registers
            require(len(regs) == 6, 'factory constructor register layout changed')
            definition = reaching_definition(body, call.offset, regs[-1])
            allocated = reaching_definition(body, call.offset, regs[0])
            if definition is None or definition.op != 0x62 or definition.ref[2] != definition.ref[0]:
                continue
            if allocated is None or allocated.op != 0x22 or allocated.ref != MAKER:
                continue
            returned = one((i for i in body.values() if i.op == 0x11 and i.offset in live), 'factory return')
            if returned.registers[0] != regs[0] or any(writes_register(i, regs[0]) for i in body.values() if call.offset < i.offset < returned.offset):
                continue
            owner = definition.ref[0]
            dex = source.classes.get(owner)
            if dex is None or assertion_type not in dex.type_list(dex.classes[owner][3]):
                continue
            if not any(ASSERTION in m.literals() for m in source.methods(owner)):
                continue
            candidates.append(dict(type=owner, singleton=list(definition.ref), factory=method_binding(method),
                                   constructor=list(call.ref), call_offset=call.offset, argument_register=regs[-1]))
    return one(candidates, 'app Retrofit factory provenance')


def reaching_definition(body, before, register):
    for ins in reversed([i for i in body.values() if i.offset < before]):
        if ins.op in (0x07, 0x08, 0x09) and ins.registers[0] == register:
            register = ins.registers[1]
        elif writes_register(ins, register):
            return ins
    return None


def writes_register(ins, register):
    # Unknown writes invalidate provenance instead of leaving a stale definition live.
    if ins.op in (0x00, 0x0e, 0x0f, 0x10, 0x11, 0x1d, 0x1e, 0x27) or 0x28 <= ins.op <= 0x3d or 0x4b <= ins.op <= 0x51 or 0x59 <= ins.op <= 0x5f or 0x67 <= ins.op <= 0x78:
        return False
    if ins.op == 0x12 or 0x7b <= ins.op <= 0x8f or 0xb0 <= ins.op <= 0xcf:
        dest = ins.words[0] >> 8 & 15
    else:
        dest = ins.registers[0]
    wide = {0x04, 0x05, 0x06, 0x0b, 0x16, 0x17, 0x18, 0x19, 0x45, 0x53, 0x61,
            0x7d, 0x7e, 0x80, 0x81, 0x83, 0x86, 0x88, 0x89, 0x8b}
    wide.update(range(0x9b, 0xa6))
    wide.update(range(0xab, 0xb0))
    wide.update(range(0xbb, 0xc6))
    wide.update(range(0xcb, 0xd0))
    return dest == register or (dest + 1 == register and ins.op in wide)


def locale_binding(method):
    if not method.flags & 8 or method.ref[2] != '()Ljava/lang/String;' or not method.code:
        return None
    seq = [i for i in method.body().values() if i.op != 0]
    if len(seq) != 6:
        return None
    registers, pending, normalizer = {}, None, None
    for ins in seq:
        if pending is not None and ins.op != 0x0c:
            return None
        if ins.op in (0x1a, 0x1b) and ins.ref == '_':
            registers[ins.registers[0]] = 'separator'
        elif ins.op in (0x71, 0x77):
            if ins.ref == ('Ljava/util/Locale;', 'getDefault', '()Ljava/util/Locale;') and not ins.registers:
                pending = 'locale'
            elif ins.ref[2] == '(Ljava/lang/String;Ljava/util/Locale;)Ljava/lang/String;' and tuple(registers.get(r) for r in ins.registers) == ('separator', 'locale'):
                pending, normalizer = 'normalized', ins.ref
            else:
                return None
        elif ins.op == 0x0c and pending is not None:
            registers[ins.registers[0]], pending = pending, None
        elif ins.op == 0x11 and ins is seq[-1] and registers.get(ins.registers[0]) == 'normalized':
            return method_binding(method, normalizer=list(normalizer))
        else:
            return None
    return None


def discover(source):
    maker_fields = source.members(MAKER)[0]
    assertion_field = one((f for f, flags in maker_fields if f[1] == 'mAssertion' and not flags & 8), 'RetrofitMaker.mAssertion')
    assertion_type = assertion_field[2]
    roles = {}
    for role in ('com/spotify/esperanto/esperanto/ClientBase', 'com/spotify/music/SpotifyMainActivity', 'com/spotify/playbacknative/AudioEffectsListener'):
        require('L' + role + ';' in source.classes, f'missing named hook root: {role}')
    roots = [(MAKER, 'createWebgateService', '(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;'),
             ('Lcom/spotify/music/SpotifyMainActivity;', 'onResume', '()V'),
             ('Lcom/spotify/playbacknative/AudioEffectsListener;', 'onPaused', '(ZI)V'),
             ('Lcom/spotify/playbacknative/AudioEffectsListener;', 'onAudioTrackCreated', '(Lcom/spotify/playbacknative/AudioTrackAdapter;)V'),
             ('Lcom/spotify/playbacknative/AudioEffectsListener;', 'onAudioTrackDestroyed', '(Lcom/spotify/playbacknative/AudioTrackAdapter;)V')]
    for typ, name, descriptor in roots:
        root = source.method(typ, name, descriptor)
        require(root.flags & 1 and not root.flags & 8, f'{name}: expected public instance method')
        roles[name] = method_binding(root)
    factory = factory_binding(source, assertion_type)
    roles['app_factory'] = factory
    roles['assertion_interface'] = dict(type=assertion_type, field=list(assertion_field), dex=source.classes[assertion_type].name)
    client = 'Lcom/spotify/esperanto/esperanto/ClientBase;'
    transport = one((f for f, flags in source.members(client)[0] if f[1:] == ('transport', 'Lcom/spotify/esperanto/esperanto/Transport;') and not flags & 8), 'ClientBase transport field')
    roles['transport'] = dict(field=list(transport), dex=source.classes[client].name)

    parser_owners = {}
    for method in source.selected:
        parser_owners.setdefault(method.ref[0], set()).update(method.literals())
    parser = one((owner for owner, literals in parser_owners.items() if set(MARKERS.values()) <= literals), 'Retrofit annotation consumer')
    annotation_types = {typ for typ, dex in source.classes.items() if dex.classes[typ][1] & 0x2000}
    annotations = annotation_roles(source.methods(parser), annotation_types)
    require(len({a['type'] for a in annotations.values()}) == len(MARKERS), 'Retrofit annotation roles overlap')
    for role, annotation in annotations.items():
        annotation.update(verify_annotation(source, role, annotation['type']))
    roles['annotations'] = annotations

    locales = []
    for method in source.selected:
        binding = locale_binding(method)
        if binding is not None:
            locales.append(binding)
    roles['locale'] = one(locales, 'locale method')

    auth_owner = one((owner for owner, literals in parser_owners.items() if {AUTH, 'Authorization', 'Bearer '} <= literals), 'Webgate authorization owner')
    auth = one((m for m in source.methods(auth_owner) if m.flags & 8 and {'Authorization', 'Bearer '} <= m.literals()), 'Webgate authorization method')
    args, ret = parameters(auth.ref[2])
    require(len(args) == 4 and args[2] == 'Ljava/lang/String;' and args[3] == ret, 'authorization descriptor changed')
    require(all(t in source.classes for t in (args[0], args[1], ret)), 'authorization parameter definitions missing')
    roles['authorization'] = method_binding(auth, chain=args[0], request=args[1], response=ret)

    raw_owner = one((owner for owner, literals in parser_owners.items() if {BUFFER, RAW} <= literals), 'raw HTTP response body')
    raw_methods = source.methods(raw_owner)
    raw_dex = source.classes[raw_owner]
    require('Ljava/io/Closeable;' in raw_dex.type_list(raw_dex.classes[raw_owner][3]), 'raw body no longer implements Closeable')
    byte_method = one((m for m in raw_methods if not m.flags & 8 and m.flags & 1 and m.ref[2] == '()[B' and BUFFER in m.literals()), 'raw body bytes method')
    text_method = one((m for m in raw_methods if not m.flags & 8 and m.flags & 1 and m.ref[2] == '()Ljava/lang/String;'), 'raw body text method')
    roles['raw_body'] = dict(type=raw_owner, bytes=method_binding(byte_method), text=method_binding(text_method))

    urls = []
    for field, flags in source.members(args[1])[0]:
        if flags & 1 and not flags & 8 and field[2] in source.classes:
            for method in source.methods(field[2]):
                if method.flags & 1 and not method.flags & 8 and method.ref[2] == '()Ljava/net/URI;':
                    urls.append(dict(field=list(field), uri=method_binding(method)))
    roles['request_url'] = one(urls, 'request URL field and URI accessor')
    return dict(schema=1, status='bindings-discovered', input_sha256=source.sha256,
                base_sha256=source.base_sha256,
                dex_sha256={d.name: hashlib.sha256(d.data).hexdigest() for d in source.dexes},
                bindings=roles,
                remaining_checks=['patch-site dataflow', 'generated helper linkage', 'rebuilt DEX verification', 'device acceptance'])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', help='Original Spotify APK or APKM')
    parser.add_argument('--out', type=Path, help='Write an atomic binding manifest (stdout by default)')
    args = parser.parse_args(argv)
    try:
        result = discover(Input(args.input))
        data = json.dumps(result, indent=2, sort_keys=True) + '\n'
        if args.out:
            require(args.out.resolve() != Path(args.input).resolve(), 'output would overwrite the input')
            args.out.parent.mkdir(parents=True, exist_ok=True)
            fd, temporary = tempfile.mkstemp(prefix='.' + args.out.name, dir=args.out.parent)
            try:
                with os.fdopen(fd, 'w') as output:
                    output.write(data)
                os.replace(temporary, args.out)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        else:
            print(data, end='')
        return 0
    except (Unsupported, OSError, ValueError, IndexError, KeyError, struct.error, zipfile.BadZipFile) as error:
        print(f'Unsupported input: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
