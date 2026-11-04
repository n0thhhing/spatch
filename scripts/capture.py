#!/usr/bin/env python3
"""Captures a screenshot (or screen recording) from a connected Android phone via adb.

    python3 scripts/capture.py [-o <path>] [-d | --display] [--rows <n>] [--record <seconds>]

Saves to dist/captures/capture-<timestamp>.png (or .mp4 with --record) unless -o / --out is
given. With -d / --display, renders the screenshot inline in the terminal using the Kitty
graphics protocol (supported by Ghostty and Kitty).
"""
import argparse
import base64
import datetime
import os
import shutil
import struct
import subprocess
import sys
from pathlib import Path

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PNG_MAGIC = b'\x89PNG\r\n\x1a\n'


def display_kitty(png: bytes, rows: int) -> None:
    b64 = base64.standard_b64encode(png).decode('ascii')
    chunks = [b64[i:i + 4096] for i in range(0, len(b64), 4096)] or ['']
    size = f',r={rows}' if rows > 0 else ''
    out = sys.stdout.buffer
    for i, chunk in enumerate(chunks):
        more = 1 if i < len(chunks) - 1 else 0
        head = f'a=T,f=100,q=2{size},m={more}' if i == 0 else f'm={more}'
        out.write(f'\x1b_G{head};{chunk}\x1b\\'.encode('ascii'))
    out.write(b'\n')
    out.flush()


def default_path(ext: str) -> str:
    stamp = datetime.datetime.now(datetime.UTC).astimezone().strftime('%Y%m%d-%H%M%S')
    return os.path.join(ROOT, 'dist', 'captures', f'capture-{stamp}.{ext}')


def main() -> int:
    default_rows = min(36, max(12, shutil.get_terminal_size((80, 40)).lines - 4))
    ap = argparse.ArgumentParser(description=(__doc__ or '').split('\n\n')[0])
    ap.add_argument('path', nargs='?', help='output file path (same as --out)')
    ap.add_argument('-o', '--out', help='output file path (default: dist/captures/capture-<timestamp>.png)')
    ap.add_argument('-d', '--display', action='store_true', help='display inline via Kitty graphics protocol')
    ap.add_argument('--rows', type=int, default=default_rows, help='terminal rows for --display (0 = 1:1 pixels)')
    ap.add_argument('--record', type=int, metavar='SEC', help='record video for SEC seconds instead of a screenshot')
    args = ap.parse_args()

    out = args.out or args.path or default_path('mp4' if args.record else 'png')

    state = subprocess.run(['adb', 'get-state'], capture_output=True, text=True, check=False)
    if state.returncode != 0:
        sys.exit(f"adb: {state.stderr.strip() or 'no device connected'}")

    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)

    if args.record:
        remote = '/sdcard/spatch-capture.mp4'
        try:
            subprocess.run(['adb', 'shell', 'screenrecord', '--time-limit', str(args.record), remote], check=True)
            subprocess.run(['adb', 'pull', '-q', remote, out], check=True)
        finally:
            subprocess.run(['adb', 'shell', 'rm', '-f', remote], capture_output=True, check=False)
        print(os.path.relpath(out, ROOT) if out.startswith(ROOT) else out)
        return 0

    res = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, check=False)
    if res.returncode != 0 or not res.stdout.startswith(PNG_MAGIC):
        sys.exit(f"screencap failed: {res.stderr.decode('utf-8', 'replace').strip() or 'invalid PNG output'}")

    png = res.stdout
    Path(out).write_bytes(png)
    w, h = struct.unpack('>II', png[16:24])
    if args.display:
        display_kitty(png, args.rows)
    shown = os.path.relpath(out, ROOT) if out.startswith(ROOT) else out
    print(f'{shown} ({w}x{h})')
    return 0


if __name__ == '__main__':
    sys.exit(main())
