"""Checks each stub's extends/implements against the real class hierarchy of a decoded Spotify build.

    python3 scripts/port/hierarchy.py <decoded build> <column in scripts/port/aliases.tsv>

A stub that claims the wrong parent compiles fine and only fails when the phone's verifier rejects
the class Spatch built against it, which is how a wrong match for S_ComposeOutline first showed up.
"""
import re, sys, glob, os
DEC, BUILD = sys.argv[1], sys.argv[2]
lines = [l.split('\t') for l in open('scripts/port/aliases.tsv').read().splitlines() if l and not l.startswith('#')]
col = lines[0].index(BUILD)
new = {r[0]: r[col].rstrip('?') for r in lines[1:]}
def parents(cls):
    hits = glob.glob(f'{DEC}/smali*/p/{cls}.smali')
    if not hits: return set()
    t = open(hits[0]).read()
    out = set(re.findall(r'^\.(?:super|implements) Lp/(\w+);', t, re.M))
    for p in list(out): out |= parents(p)
    return out
bad = 0
sources = glob.glob('theme/dex/stubs/p/S_*.java') + glob.glob('theme/dex/src/**/*.java', recursive=True)
for path in sources:
    code = re.sub(r'//.*', '', open(path).read())
    for m in re.finditer(r'class\s+(\w+)\s*(?:extends\s+(?:p\.)?(S_\w+))?\s*(?:implements\s+([^{]+))?\{', code):
        name, ext, impl = m.group(1), m.group(2), m.group(3) or ''
        supers = ([ext] if ext else []) + re.findall(r'(?:p\.)?(S_\w+)', impl)
        if not supers or not name.startswith('S_'):
            continue
        mine = new[name[2:]].split('/')[1]
        got = parents(mine)
        for sup in supers:
            want = new[sup[2:]].split('/')[1]
            if want not in got:
                bad += 1; print(f'{name} ({mine}) should extend {sup} ({want}); real parents: {sorted(got)[:6]}')
print(bad, 'hierarchy problems')
sys.exit(1 if bad else 0)
