#!/usr/bin/env python3
"""Diffs a target class between old and new decoded builds in Spatch's S_<alias> / R_<name> terms.

    python3 scripts/port/diff-target.py [S_<alias> | <alias> | <smali-path>] [new-class] [--work <dir>] [--old <col>] [--new <col>] [--raw]

With a target, checks any src/edits.rs anchors for it against the new build and prints a unified
diff of old vs new smali with obfuscated classes rewritten to S_<alias>, resource IDs to R_<type>_<name>
and .line directives stripped (--raw keeps original lines). Without a target, checks every edit anchor
and lists any unconfirmed (?) aliases.
"""
import argparse
import difflib
import glob
import os
import re
import sys
from pathlib import Path

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def load_aliases(old_col_name, new_col_name, work):
    aliases_text = Path(ROOT, 'scripts/port/aliases.tsv').read_text(encoding='utf-8')
    rows = [l.split('\t') for l in aliases_text.splitlines() if l and not l.startswith('#')]
    hdr = rows[0]
    oc = hdr.index(old_col_name) if old_col_name in hdr else len(hdr) - 2
    nc = hdr.index(new_col_name) if new_col_name in hdr else len(hdr) - 1
    old_map, new_map, unsure = {}, {}, []
    for r in rows[1:]:
        if len(r) > oc and '/' in r[oc]:
            old_map[r[0]] = r[oc].rstrip('?')
        if len(r) > nc and '/' in r[nc]:
            if r[nc].endswith('?'):
                unsure.append((r[0], r[nc]))
            new_map[r[0]] = r[nc].rstrip('?')
    classes_tsv = Path(work, 'classes.tsv')
    if classes_tsv.is_file():
        by_old = {v.split('/')[1]: k for k, v in old_map.items()}
        for l in classes_tsv.read_text(encoding='utf-8').splitlines()[1:]:
            o, n, folder, _score, status = l.split('\t')
            alias = by_old.get(o)
            if alias and alias not in new_map and n != '-':
                new_map[alias] = f'{folder}/{n}'
                if not status.startswith('ok'):
                    unsure.append((alias, f'{folder}/{n}? ({status})'))
    return hdr[oc], hdr[nc], old_map, new_map, unsure


def load_resources(work):
    old_id_to_r, new_id_to_r, r_to_new_id = {}, {}, {}
    res_tsv = Path(work, 'resources.tsv')
    if res_tsv.is_file():
        for l in res_tsv.read_text(encoding='utf-8').splitlines():
            oid, nid, name = l.split('\t')
            tok = 'R_' + name.replace('/', '_')
            old_id_to_r[oid] = tok
            if nid != '-':
                new_id_to_r[nid] = tok
                r_to_new_id[tok] = nid
    return old_id_to_r, new_id_to_r, r_to_new_id


def locate(tree, cell_or_path):
    if cell_or_path.endswith('.smali'):
        p = os.path.join(tree, cell_or_path)
        return p if os.path.isfile(p) else None
    cls = cell_or_path.split('/')[-1]
    if '/' in cell_or_path:
        p = os.path.join(tree, cell_or_path.split('/')[0], 'p', cls + '.smali')
        if os.path.isfile(p):
            return p
    return next(iter(glob.glob(os.path.join(tree, 'smali*', 'p', cls + '.smali'))), None)


def edits_for(new_map, r_to_new_id):
    src = Path(ROOT, 'src/edits.rs').read_text(encoding='utf-8')
    out = []
    for m in re.finditer(r'Edit\s*\{\s*file:\s*"([^"]+)",\s*anchor:\s*"((?:[^"\\]|\\.)*)"', src, re.DOTALL):
        ftok, anchor = m.group(1), m.group(2).encode().decode('unicode_escape')
        rewritten = re.sub(r'\bS_([A-Z]\w*)', lambda a: new_map.get(a.group(1), a.group(0)).split('/')[-1], anchor)
        if r_to_new_id:
            rewritten = re.sub(r'\bR_[a-z]+_\w+', lambda r: r_to_new_id.get(r.group(0), r.group(0)), rewritten)
        out.append((ftok, anchor, rewritten))
    return out


def prettify(text, cls_to_alias, id_to_r, raw=False):
    lines = []
    for line in text.splitlines(keepends=True):
        if not raw and line.lstrip().startswith('.line '):
            continue
        if not raw:
            line = re.sub(r'Lp/([\w$]+);', lambda m: f'Lp/S_{cls_to_alias[m.group(1)]};' if m.group(1) in cls_to_alias else m.group(0), line)
            line = re.sub(r'0x7f[0-9a-f]{6}', lambda m: id_to_r.get(m.group(0), m.group(0)), line)
        lines.append(line)
    return lines


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or '').split('\n\n')[0])
    ap.add_argument('target', nargs='?')
    ap.add_argument('new_class', nargs='?')
    ap.add_argument('--work', default=os.path.join(ROOT, 'scripts', 'port', '.work'))
    ap.add_argument('--old', default=None)
    ap.add_argument('--new', default=None)
    ap.add_argument('--raw', action='store_true')
    args = ap.parse_args()

    old_col, new_col, old_map, new_map, unsure = load_aliases(args.old, args.new, args.work)
    old_dir = args.old if args.old and os.path.isdir(args.old) else os.path.join(args.work, 'old')
    new_dir = args.new if args.new and os.path.isdir(args.new) else os.path.join(args.work, 'new')
    old_id_to_r, new_id_to_r, r_to_new_id = load_resources(args.work)
    all_edits = edits_for(new_map, r_to_new_id)

    if not args.target:
        bad = 0
        for alias, cell in unsure:
            bad += 1
            print(f'unsure alias: S_{alias} -> {cell}')
        for ftok, orig, anchor in all_edits:
            cell = new_map.get(ftok[2:], ftok) if ftok.startswith('S_') else ftok
            path = locate(new_dir, cell)
            if not path:
                bad += 1
                print(f'missing target in {new_dir}: {ftok} ({cell})')
                continue
            text = Path(path).read_text(encoding='utf-8', errors='replace')
            if not r_to_new_id and 'R_' in orig:
                continue
            n = text.count(anchor)
            if n != 1:
                bad += 1
                first = orig.strip().splitlines()[0]
                print(f'broken anchor ({n} matches) in {ftok} ({cell}): {first}')
        print(f'{bad} problems')
        return 1 if bad else 0

    alias = args.target.removeprefix('S_')
    if alias in old_map:
        ftok = f'S_{alias}'
        old_cell = old_map[alias]
        new_cell = args.new_class or new_map.get(alias, '')
    else:
        ftok = args.target
        old_cell = args.target
        new_cell = args.new_class or args.target

    old_path, new_path = locate(old_dir, old_cell), locate(new_dir, new_cell)
    if not old_path or not new_path:
        sys.exit(f'could not find smali: old={old_path} ({old_cell} in {old_dir}), new={new_path} ({new_cell} in {new_dir})')

    new_text = Path(new_path).read_text(encoding='utf-8', errors='replace')
    for ef, orig, anchor in all_edits:
        if ef == ftok:
            first = orig.strip().splitlines()[0]
            if not r_to_new_id and 'R_' in orig:
                print(f'# edit {ftok}: skipped resource anchor check (no resources.tsv): {first}')
            else:
                n = new_text.count(anchor)
                status = 'ok (1 match)' if n == 1 else f'BROKEN ({n} matches)'
                print(f'# edit {ftok}: {status}: {first}')

    old_cls_map = {v.split('/')[1]: k for k, v in old_map.items()}
    new_cls_map = {v.split('/')[1]: k for k, v in new_map.items()}
    if alias in old_map and args.new_class:
        new_cls_map[args.new_class.split('/')[-1]] = alias
    old_text = Path(old_path).read_text(encoding='utf-8', errors='replace')
    a = prettify(old_text, old_cls_map, old_id_to_r, args.raw)
    b = prettify(new_text, new_cls_map, new_id_to_r, args.raw)
    sys.stdout.writelines(difflib.unified_diff(a, b, fromfile=f'{old_col}:{old_cell}', tofile=f'{new_col}:{new_cell}'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
