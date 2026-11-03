#!/usr/bin/env python3
"""Helps port Spatch to a new Spotify build.

    python3 port/port.py <old.apkm> <new.apkm> [--work <dir>]

Decodes both builds (cached in the work folder), maps every resource ID by name, and matches each
obfuscated Spotify class Spatch refers to (in src/ and theme/dex/) with its renamed self in the new
build. Writes <work>/classes.tsv and <work>/resources.tsv and prints what needs a human: classes it
couldn't match with confidence. It changes nothing in the repository.

Classes are matched on what survives obfuscation: string constants, framework superclasses and
interfaces, method shapes and the code itself, with obfuscated names blanked out and resource IDs
translated to the new build before comparing.
"""
import argparse
import collections
import glob
import hashlib
import os
import re
import subprocess
import sys
import zipfile
from concurrent.futures import ProcessPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD_TOOLS = sorted(glob.glob('/opt/homebrew/share/android-commandlinetools/build-tools/*'))
OBF = re.compile(r'Lp/[\w$]+;')
MEMBER = re.compile(r'(Lp/_;->)[\w$]+')
LABEL = re.compile(r':(cond|goto|try_start|try_end|catch|catchall|pswitch|sswitch|array|pswitch_data|sswitch_data)_[0-9a-f]+')
RES = re.compile(r'0x7f[0-9a-f]{6}')
FIELD = re.compile(r'^(\.field .*?)[\w$]+(:\S+)')
METHOD = re.compile(r'^(\.method .*?)([\w$]+)(\(.*)$')
STRING = re.compile(r'const-string(?:/jumbo)? \w+, "((?:[^"\\]|\\.)*)"')
KEEP = {'run', 'get', 'set', 'put', 'add', 'end', 'map', 'top'}


def decode(apkm, out):
    """base.apk of the APKM, decoded once into out."""
    if os.path.isdir(os.path.join(out, 'smali')):
        return
    os.makedirs(out, exist_ok=True)
    with zipfile.ZipFile(apkm) as z:
        z.extract('base.apk', out)
    subprocess.run(['apktool', 'd', '-q', '-f', os.path.join(out, 'base.apk'), '-o', os.path.join(out, 'dec')], check=True)
    os.rename(os.path.join(out, 'dec', 'smali'), os.path.join(out, 'smali'))
    for d in glob.glob(os.path.join(out, 'dec', 'smali_classes*')):
        os.rename(d, os.path.join(out, os.path.basename(d)))


def resources(base):
    dump = subprocess.run([os.path.join(BUILD_TOOLS[-1], 'aapt2'), 'dump', 'resources', base], capture_output=True, text=True).stdout
    return {m.group(2): int(m.group(1), 16) for m in re.finditer(r'resource (0x[0-9a-f]{8}) (\S+)', dump)}


def normalize(text, ids=None, raw=False):
    """The class's lines with what obfuscation and resource tables change blanked out or translated;
    with raw, each comes paired with the original line."""
    out = []
    for line in text.splitlines():
        line = original = line.strip()
        if not line or line[0] == '#' or line.startswith(('.line', '.source', '.param', '.end param', '.prologue', '.local ', '.end local', '.restart')):
            continue
        line = OBF.sub('Lp/_;', line)
        line = MEMBER.sub(r'\1_', line)
        line = LABEL.sub(r':\1', line)
        m = FIELD.match(line)
        if m:
            line = m.group(1) + '_' + m.group(2)
        m = METHOD.match(line)
        if m and m.group(2) not in KEEP and len(m.group(2)) <= 3 and not m.group(2).startswith('<'):
            line = m.group(1) + '_' + m.group(3)
        if ids is not None:
            line = RES.sub(lambda r: ids.get(r.group(), '0x7f??'), line)
        out.append((original, line) if raw else line)
    return out


def features(path):
    text = open(path, encoding='utf-8', errors='replace').read()
    lines = normalize(text)
    sup = next((l for l in lines if l.startswith('.super')), '')
    ifaces = tuple(sorted(l for l in lines if l.startswith('.implements')))
    sig = tuple(sorted(l for l in lines if l.startswith(('.method', '.field'))))
    strings = frozenset(STRING.findall(text))
    return path, sup, ifaces, hashlib.sha1(repr(sig).encode()).hexdigest(), sum(1 for l in sig if l.startswith('.method')), sum(1 for l in sig if l.startswith('.field')), strings, len(lines)


def index(tree):
    paths = glob.glob(os.path.join(tree, 'smali*', 'p', '*.smali'))
    with ProcessPoolExecutor() as pool:
        return list(pool.map(features, paths, chunksize=256))


def jaccard(a, b):
    a, b = collections.Counter(a), collections.Counter(b)
    inter = sum((a & b).values())
    return inter / max(1, sum((a | b).values()))


TOKEN = re.compile(r'Lp/([\w$]+);(?:->([\w$]+))?')


def votes(pairs, ids, class_votes, member_votes):
    """Lines that match once names are blanked out must name corresponding things: each such line in
    a matched pair of classes votes for the old-to-new name of every class and member it mentions."""
    import difflib
    for (old_name, old_path), (new_name, new_path) in pairs:
        a = normalize(open(old_path, encoding='utf-8', errors='replace').read(), ids, raw=True)
        b = normalize(open(new_path, encoding='utf-8', errors='replace').read(), raw=True)
        m = difflib.SequenceMatcher(None, [n for _, n in a], [n for _, n in b], autojunk=False)
        for block in m.get_matching_blocks():
            for k in range(block.size):
                ra, rb = a[block.a + k][0], b[block.b + k][0]
                ta, tb = TOKEN.findall(ra), TOKEN.findall(rb)
                if len(ta) == len(tb):
                    for (oc, om), (nc, nm) in zip(ta, tb):
                        class_votes[oc][nc] += 1
                        if om and nm:
                            member_votes[(oc, om)][(nc, nm)] += 1
                for pattern in (FIELD_NAME, METHOD_NAME):
                    fa, fb = pattern.match(ra), pattern.match(rb)
                    if fa and fb:
                        member_votes[(old_name, fa.group(1))][(new_name, fb.group(1))] += 1


FIELD_NAME = re.compile(r'^\.field .*?([\w$]+):\S+')
METHOD_NAME = re.compile(r'^\.method .*?([\w$]+)\(')


def referenced():
    """Obfuscated class names Spatch mentions anywhere it reaches into Spotify."""
    names = set()
    for f in ['src/edits.rs', 'src/dex.rs', 'src/helpers.rs', 'src/patch.rs']:
        t = open(os.path.join(ROOT, f)).read()
        names |= set(re.findall(r'Lp/([\w$]+);', t)) | set(re.findall(r'smali\w*/p/([\w$]+)\.smali', t))
    names |= {os.path.basename(p)[:-5] for p in glob.glob(os.path.join(ROOT, 'theme/dex/stubs/p/*.java'))}
    for p in glob.glob(os.path.join(ROOT, 'theme/dex/src/**/*.java'), recursive=True):
        names |= set(re.findall(r'\bp\.([a-z][\w]*)\b', open(p).read()))
    return sorted(names)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('old')
    ap.add_argument('new')
    ap.add_argument('--work', default=os.path.join(ROOT, 'port', '.work'))
    args = ap.parse_args()
    old_dir, new_dir = os.path.join(args.work, 'old'), os.path.join(args.work, 'new')
    for apkm, d in ((args.old, old_dir), (args.new, new_dir)):
        print('decoding', os.path.basename(apkm), flush=True)
        decode(apkm, d)

    old_res, new_res = resources(os.path.join(old_dir, 'base.apk')), resources(os.path.join(new_dir, 'base.apk'))
    ids = {'0x%08x' % v: '0x%08x' % new_res[n] for n, v in old_res.items() if n in new_res}
    with open(os.path.join(args.work, 'resources.tsv'), 'w') as f:
        for n, v in sorted(old_res.items()):
            f.write('0x%08x\t%s\t%s\n' % (v, '0x%08x' % new_res[n] if n in new_res else '-', n))

    print('indexing the new build', flush=True)
    new = index(new_dir)
    by_string, by_sig, by_shape = collections.defaultdict(list), collections.defaultdict(list), collections.defaultdict(list)
    for i, (path, sup, ifaces, sig, nm, nf, strings, size) in enumerate(new):
        for s in strings:
            by_string[s].append(i)
        by_sig[sig].append(i)
        by_shape[(sup, ifaces, nm, nf)].append(i)

    def fingerprint(old_path):
        """New classes scored against an old one, best first."""
        _, sup, ifaces, sig, nm, nf, strings, size = features(old_path)
        cands = set()
        for s in sorted(strings, key=lambda s: len(by_string.get(s, ())) or 1 << 30)[:4]:
            if 0 < len(by_string.get(s, ())) < 50:
                cands.update(by_string[s])
        cands.update(by_sig.get(sig, ()))
        if len(cands) < 3:
            cands.update(by_shape.get((sup, ifaces, nm, nf), ())[:3000])
        old_lines = normalize(open(old_path, encoding='utf-8', errors='replace').read(), ids)
        scored = []
        for i in cands:
            path, _, _, _, _, _, _, nsize = new[i]
            if nsize and not 0.6 < size / nsize < 1.6:
                continue
            scored.append((jaccard(old_lines, normalize(open(path, encoding='utf-8', errors='replace').read())), path))
        return sorted(scored, reverse=True)

    rows, unsure = [], 0
    names = referenced()
    for name in names:
        old_path = next(iter(glob.glob(os.path.join(old_dir, 'smali*', 'p', name + '.smali'))), None)
        if old_path is None:
            rows.append((name, '-', '-', 0, 'not in the old build'))
            continue
        scored = fingerprint(old_path)
        if not scored:
            rows.append((name, '-', '-', 0, 'no candidate'))
            unsure += 1
            continue
        best, path = scored[0]
        second = scored[1][0] if len(scored) > 1 else 0
        ok = best >= 0.7 and best - second >= 0.08
        unsure += not ok
        rel = os.path.relpath(path, new_dir)
        rows.append((name, os.path.basename(path)[:-6], rel.split(os.sep)[0], round(best, 3), 'ok' if ok else 'check (next best %.3f)' % second))

    # Votes from the confident matches settle ties and low scores, and give the member names.
    where = {os.path.basename(p)[:-6]: p for p, *_ in new}
    old_where = lambda n: next(iter(glob.glob(os.path.join(old_dir, 'smali*', 'p', n + '.smali'))), None)
    mapping = {r[0]: r[1] for r in rows if r[4] == 'ok'}
    member_votes = collections.defaultdict(collections.Counter)
    helpers = {}  # old classes that use an unresolved one, matched by fingerprint, for their votes alone
    for _ in range(3):
        for r in rows:
            if r[4].startswith('ok') or r[4] == 'not in the old build':
                continue
            users = subprocess.run(['grep', '-rlF', '--include=*.smali', 'Lp/%s;' % r[0]] + glob.glob(os.path.join(old_dir, 'smali*', 'p')), capture_output=True, text=True).stdout.split()
            for user in users[:25]:
                user_name = os.path.basename(user)[:-6]
                if user_name in mapping or user_name in helpers:
                    continue
                scored = fingerprint(user)
                if scored and scored[0][0] >= 0.85 and (len(scored) == 1 or scored[0][0] - scored[1][0] >= 0.08):
                    helpers[user_name] = os.path.basename(scored[0][1])[:-6]
        class_votes = collections.defaultdict(collections.Counter)
        member_votes = collections.defaultdict(collections.Counter)
        pairs = {**helpers, **mapping}
        votes([((o, old_where(o)), (n, where[n])) for o, n in pairs.items() if old_where(o) and n in where], ids, class_votes, member_votes)
        grew = False
        for i, r in enumerate(rows):
            name = r[0]
            top = class_votes.get(name, collections.Counter()).most_common(2)
            if not top:
                continue
            (winner, count), runner = top[0], (top[1][1] if len(top) > 1 else 0)
            if count >= 2 and count >= 3 * runner and winner in where:
                agrees = r[1] == winner
                status = 'ok' if r[4] == 'ok' and agrees else 'ok (votes %d:%d%s)' % (count, runner, '' if agrees else ', fingerprint said ' + r[1])
                if mapping.get(name) != winner:
                    grew = True
                mapping[name] = winner
                rows[i] = (name, winner, os.path.relpath(where[winner], new_dir).split(os.sep)[0], r[3], status)
        if not grew:
            break

    with open(os.path.join(args.work, 'classes.tsv'), 'w') as f:
        f.write('old\tnew\tfolder\tscore\tstatus\n')
        for r in rows:
            f.write('\t'.join(map(str, r)) + '\n')
    with open(os.path.join(args.work, 'members.tsv'), 'w') as f:
        for (oc, om), counter in sorted(member_votes.items()):
            if oc in mapping:
                (nc, nm), count = counter.most_common(1)[0]
                f.write('%s\t%s\t%s\t%s\t%d\n' % (oc, om, nc, nm, count))
    unsure = sum(1 for r in rows if not r[4].startswith('ok') and r[4] != 'not in the old build')
    print('%d classes: %d matched, %d to check; resource ids: %d of %d carried over' % (len(names), len(names) - unsure, unsure, len(ids), len(old_res)))
    for r in rows:
        if not r[4].startswith('ok') and r[4] != 'not in the old build':
            print('  %-8s -> %-8s %-16s %s %s' % r)
    for r in rows:
        if 'fingerprint said' in r[4]:
            print('  votes overrode:', r)


if __name__ == '__main__':
    sys.exit(main())
