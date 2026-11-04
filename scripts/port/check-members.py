#!/usr/bin/env python3
"""Checks each stub and smali-edit member against the real classes of a decoded Spotify build.

    python3 scripts/port/check-members.py <decoded build> [column in scripts/port/aliases.tsv]

A stub or edit that names a renamed or reshaped field, constructor or method compiles fine and
only fails on the phone (or halfway through a DEX rebuild).
"""
import functools
import glob
import os
import re
import sys
from pathlib import Path

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PRIMS = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S', 'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D'}
LANG = {'Object': 'Ljava/lang/Object;', 'String': 'Ljava/lang/String;', 'Integer': 'Ljava/lang/Integer;'}
DRAWABLE = {'draw', 'setAlpha', 'setColorFilter', 'getOpacity'}

if len(sys.argv) < 2 or sys.argv[1] in ('-h', '--help'):
    sys.exit((__doc__ or '').strip())

DEC = sys.argv[1]
aliases_text = Path(ROOT, 'scripts/port/aliases.tsv').read_text(encoding='utf-8')
rows = [l.split('\t') for l in aliases_text.splitlines() if l and not l.startswith('#')]
BUILD = sys.argv[2] if len(sys.argv) > 2 else rows[0][-1]
col = rows[0].index(BUILD)
old_col = max(1, col - 1)
new = {r[0]: r[col].rstrip('?').split('/')[1] for r in rows[1:]}
old_by_new = {r[col].rstrip('?').split('/')[1]: r[old_col].rstrip('?').split('/')[1] for r in rows[1:] if len(r) > old_col}
alias_by_new = {v: k for k, v in new.items()}

votes = {}
members_tsv = Path(ROOT, 'scripts/port/.work/members.tsv')
if members_tsv.is_file():
    for l in members_tsv.read_text(encoding='utf-8').splitlines():
        oc, om, nc, nm, count = l.split('\t')
        votes[(nc, om)] = f'{nm} ({count} votes)'


@functools.cache
def smali_text(cls):
    path = os.path.join(DEC, cls) if cls.endswith('.smali') else next(iter(glob.glob(f'{DEC}/smali*/p/{cls}.smali')), None)
    return Path(path).read_text(encoding='utf-8', errors='replace') if path and os.path.isfile(path) else ''


@functools.cache
def declared(cls):
    t = smali_text(cls)
    fields = set(re.findall(r'^\.field [^\n]*?\s([\w$]+:[^\s=]+)', t, re.MULTILINE))
    methods = set(re.findall(r'^\.method [^\n]*?\s([\w$><]+\(\S+)', t, re.MULTILINE))
    supers = re.findall(r'^\.(?:super|implements) Lp/([\w$]+);', t, re.MULTILINE)
    return fields, methods, supers


@functools.cache
def hierarchy(cls):
    fields, methods, supers = declared(cls)
    f_all, m_all = set(fields), set(methods)
    for sup in supers:
        pf, pm = hierarchy(sup)
        f_all |= pf
        m_all |= {m for m in pm if not m.startswith('<')}
    return f_all, m_all


def desc(jtype):
    jtype = re.sub(r'<.*>', '', jtype).replace('...', '[]').strip()
    dims = jtype.count('[]')
    base = jtype.replace('[]', '').strip().removeprefix('p.')
    if base in PRIMS:
        d = PRIMS[base]
    elif base in LANG:
        d = LANG[base]
    elif base.startswith('S_'):
        d = f'Lp/{new[base[2:]]};'
    else:
        d = f'L{base.replace(".", "/")};'
    return '[' * dims + d


def readable(text):
    return re.sub(r'Lp/([\w$]+);', lambda m: f'Lp/S_{alias_by_new[m.group(1)]};' if m.group(1) in alias_by_new else m.group(0), text)


def hint(cls, name, shape, is_field):
    fields, methods, _ = declared(cls)
    pool = fields if is_field else methods
    sep = ':' if is_field else '('
    same = sorted(m.split(sep, 1)[0] for m in pool if m.endswith(shape) and m.split(sep, 1)[0] != '<clinit>')
    by_name = sorted(readable(m) for m in pool if m.startswith(name + sep))
    parts = []
    if (cls, name) in votes:
        parts.append(f'vote: {votes[(cls, name)]}')
    if same:
        parts.append(f'same shape: {", ".join(same[:6])}')
    if by_name:
        parts.append(f'named {name}: {", ".join(by_name[:4])}')
    return f' ({"; ".join(parts)})' if parts else ''


helpers = '\n'.join(Path(p).read_text(encoding='utf-8') for p in glob.glob(os.path.join(ROOT, 'theme/dex/src/**/*.java'), recursive=True))
stubs_all = '\n'.join(Path(p).read_text(encoding='utf-8') for p in glob.glob(os.path.join(ROOT, 'theme/dex/stubs/p/S_*.java')))
edits_src = Path(ROOT, 'src/edits.rs').read_text(encoding='utf-8')
swap = lambda text: re.sub(r'\bS_([A-Z]\w*)', lambda a: new[a.group(1)], text)
edits, added = [], set()
for m in re.finditer(r'Edit\s*\{\s*file:\s*"([^"]+)",\s*anchor:\s*"((?:[^"\\]|\\.)*)",\s*replacement:\s*"((?:[^"\\]|\\.)*)"', edits_src, re.DOTALL):
    ftok, anchor_s, repl_s = m.group(1), swap(m.group(2).encode().decode('unicode_escape')), swap(m.group(3).encode().decode('unicode_escape'))
    target = new[ftok[2:]] if ftok.startswith('S_') else ftok
    edits.append((ftok, target, anchor_s, repl_s))
    for pat in (r'^\.field [^\n]*?\s([\w$]+:[^\s=]+)', r'^\.method [^\n]*?\s([\w$><]+\(\S+)'):
        added |= {(target, sig) for sig in set(re.findall(pat, repl_s, re.MULTILINE)) - set(re.findall(pat, anchor_s, re.MULTILINE))}
bad, seen = 0, set()


def check(where, cls, sig, is_field, direct=False):
    global bad
    if (cls, sig) in added or (where, cls, sig) in seen:
        return
    seen.add((where, cls, sig))
    if not smali_text(cls):
        bad += 1
        print(f'{where}: missing smali for {cls}')
        return
    f_set, m_set = declared(cls)[:2] if direct or sig.startswith('<init>(') else hierarchy(cls)
    pool = f_set if is_field else m_set
    if sig not in pool:
        bad += 1
        sep = ':' if is_field else '('
        name, rest = sig.split(sep, 1)
        label = f'S_{alias_by_new[cls]} ({cls})' if cls in alias_by_new else cls
        print(f'{where}: {label} missing {readable(sig)}{hint(cls, name, sep + rest, is_field)}')


for path in sorted(glob.glob(os.path.join(ROOT, 'theme/dex/stubs/p/S_*.java'))):
    cls_name = os.path.basename(path)[:-5]
    target = new[cls_name[2:]]
    raw = Path(path).read_text(encoding='utf-8')
    if '// Not called by helper code.' in raw or '// Role not confirmed:' in raw:
        used_in = helpers
    else:
        used_in = helpers + '\n' + stubs_all
    code = re.sub(r'/\*.*?\*/|//[^\n]*', '', raw, flags=re.DOTALL)
    if re.search(r'\benum\s+' + cls_name, code):
        body = code.split('{', 1)[1].rsplit('}', 1)[0]
        for const in [c.strip() for c in body.split(',') if c.strip()]:
            if f'.{const}' in used_in:
                check(f'stub {cls_name}', target, f'{const}:Lp/{target};', True)
        continue
    depth, stmt, stmts = 0, [], []
    for ch in code:
        if ch in '{;' and depth == 1:
            stmts.append(''.join(stmt).strip())
            stmt = []
        elif depth == 1:
            stmt.append(ch)
        depth += (ch == '{') - (ch == '}')
    for s in stmts:
        if not s or 'private ' in s or any(w in s.split() for w in ('class', 'interface', 'enum')):
            continue
        if '(' in s and '=' not in s.split('(', 1)[0]:
            head, rest = s.split('(', 1)
            params = rest.split(')', 1)[0]
            words = [w for w in head.split() if w not in ('public', 'protected', 'static', 'final', 'abstract', 'synchronized')]
            p_desc = ''.join(desc(p.rsplit(None, 1)[0]) for p in params.split(',') if p.strip())
            if len(words) == 1 and words[0] == cls_name:
                if f'new {cls_name}(' in used_in:
                    check(f'stub {cls_name}', target, f'<init>({p_desc})V', False, direct=True)
            elif len(words) >= 2:
                ret, name = ' '.join(words[:-1]), words[-1]
                if cls_name == 'S_EncoreIconDrawable' and name in DRAWABLE:
                    continue
                if used_in is helpers and f'.{name}(' not in helpers:
                    continue
                check(f'stub {cls_name}', target, f'{name}({p_desc}){desc(ret)}', False)
        else:
            parts = [p.split('=')[0].strip() for p in s.split(',') if p.strip()]
            words = [w for w in parts[0].split() if w not in ('public', 'protected', 'static', 'final', 'volatile', 'transient')]
            if len(words) < 2:
                continue
            f_desc = desc(' '.join(words[:-1]))
            for f_name in [words[-1]] + [p.split()[-1] for p in parts[1:]]:
                if f'.{f_name}' in helpers:
                    check(f'stub {cls_name}', target, f'{f_name}:{f_desc}', True)

for file_tok, target, anchor_s, repl_s in edits:
    for fm in re.finditer(r'^\.field [^\n]*?\s([\w$]+:[^\s=]+)', anchor_s, re.MULTILINE):
        check(f'edit {file_tok}', target, fm.group(1), True, direct=True)
    for mm in re.finditer(r'^\.method [^\n]*?\s([\w$><]+\(\S+)', anchor_s, re.MULTILINE):
        check(f'edit {file_tok}', target, mm.group(1), False, direct=True)
    for text in (anchor_s, repl_s):
        for cm in re.finditer(r'Lp/([\w$]+);->([\w$><]+)(:\S+|\(\S+)', text):
            cls, name, shape = cm.group(1), cm.group(2), cm.group(3)
            check(f'edit {file_tok}', cls, name + shape, shape.startswith(':'))

print(bad, 'member problems')
sys.exit(1 if bad else 0)
