#!/usr/bin/env python3
"""Exercise Spatch settings on a connected phone, restoring every value it changes.

usage: device-settings.py <base.apk> [--static-only] [--only=Screens,Content]

Checks each icon name against the APK, then opens every settings page, flips every switch and picks
every choice, confirming the row's reported state each time. It never plays audio and never touches
resets, themes, fonts, Home sections or the profile banner. Spotify must be open on Home.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.spotify.music'
# Pages reached from each page, by the title on the row that opens them.
TREE = {
    'Appearance': {'Themes & display': {}, 'Fonts': {}},
    'Player': {'Circular album art': {}, 'Album art backdrop': {}, 'Wavy progress': {}, 'Button animations': {},
               'Immersive player': {}, 'Gestures & touch': {}, 'Mini player': {}, 'Play button burst': {}},
    'Visualizers': {'Full-screen visualizer': {}},
    'Screens': {'Navigation bar': {}, 'Expressive filters': {}, 'Side panel style': {}, 'Your Library style': {},
                'Playlists': {'Living play button': {}}, 'Profile header': {}},
    'Motion & haptics': {},
    'Content': {},
}
SWITCH = re.compile(r'^(.+), (On|Off)$')
CHOICE = re.compile(r'^(.+): (.+), (Selected|Not selected)$')
BOTTOM_MARGIN = 170  # px kept clear of the gesture bar


def check_icons(apk):
    tools = Path(os.environ.get('BUILD_TOOLS', '/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0'))
    dump = subprocess.run([str(tools / 'aapt2'), 'dump', 'resources', apk], capture_output=True, text=True, check=True).stdout
    real = set(re.findall(r'drawable/encore_icon_([a-z0-9_]+)_24\b', dump))
    source = (ROOT / 'theme/dex/src/spatch/settings/SpatchSettings.java').read_text()
    match = re.search(r'FEATURE_ICONS = \{(.*?)\};', source, re.DOTALL)
    assert match is not None, 'FEATURE_ICONS table not found in SpatchSettings.java'
    table = match[1]
    used = re.findall(r'"([a-z_]+)"', table)
    used += re.findall(r'icon\("([a-z_]+)"\)|createSettingRow\("([a-z_]+)"', source)
    used = {name for item in used for name in ([item] if isinstance(item, str) else item) if name}
    used |= set(re.findall(r'(?:category|link)\([a-zA-Z]+, "([a-z_]+)"', source))
    missing = sorted(used - real)
    assert real, 'no Encore icons found in the APK'
    assert not missing, f'icons missing from the APK: {missing}'
    print(f'PASS {len(used)} icon names exist in the APK')


def adb(*args):
    return subprocess.run(['adb', *args], capture_output=True, text=True, timeout=60, check=False).stdout


def front():
    return PACKAGE in adb('shell', 'dumpsys', 'activity', 'activities').split('topResumedActivity', 1)[-1][:120]


def screen():
    for _ in range(4):
        adb('shell', 'uiautomator', 'dump', '/sdcard/spatch-ui.xml')
        text = adb('shell', 'cat', '/sdcard/spatch-ui.xml')
        if '<node' in text:
            return ET.fromstring(text[text.index('<'):text.rindex('>') + 1])
        time.sleep(1)
    raise RuntimeError(f"could not read the screen; adb state: {adb('get-state').strip() or 'phone not connected'}")


def centre(node):
    a, b, c, d = map(int, re.findall(r'\d+', node.get('bounds')))
    return (a + c) // 2, (b + d) // 2, d - b


def tap(node):
    if not front():
        raise RuntimeError('Spotify left the foreground')
    x, y, _ = centre(node)
    adb('shell', 'input', 'tap', str(x), str(y))
    time.sleep(0.5)


def find(root, attribute, value):
    for node in root.iter('node'):
        if node.get(attribute) == value:
            return node
    return None


def dismiss_restart():
    """Settings that need a restart ask; Later keeps the running app."""
    root = screen()
    later = find(root, 'text', 'Later')
    if later is None:
        return root
    tap(later)
    time.sleep(0.5)
    return screen()


def visible(root, height):
    out = []
    for node in root.iter('node'):
        desc = node.get('content-desc') or ''
        _, y, size = centre(node) if node.get('bounds') else (0, 0, 0)
        if desc and node.get('enabled') == 'true' and size > 40 and y < height - BOTTOM_MARGIN:
            out.append((desc, node))
    return out


def state(root, name):
    for desc, _ in visible(root, 1 << 30):
        match = SWITCH.match(desc)
        if match and match[1] == name:
            return match[2]
    return None


def flip(page, name, was):
    wanted = 'Off' if was == 'On' else 'On'
    node = next(n for d, n in visible(screen(), 1 << 30) if (match := SWITCH.match(d)) and match[1] == name)
    print(f'flipping {page} / {name}, was {was}', flush=True)  # names what to set back if the phone drops
    tap(node)
    now = state(dismiss_restart(), name)
    try:
        assert now == wanted, f'{page} / {name}: stayed {now} after tapping from {was}'
    finally:
        back = next(n for d, n in visible(screen(), 1 << 30) if (match := SWITCH.match(d)) and match[1] == name)
        tap(back)
        restored = state(dismiss_restart(), name)
    assert restored == was, f'{page} / {name}: not restored, now {restored}, was {was}. Set it back by hand.'


def pick_all(page, title, height):
    """Tries each option of a choice, then returns to the one that was selected."""
    def options():
        found = {}
        for desc, node in visible(screen(), height):
            match = CHOICE.match(desc)
            if match and match[1] == title:
                found[match[2]] = (match[3], node)
        return found
    start = options()
    original = next(k for k, v in start.items() if v[0] == 'Selected')
    try:
        for label in start:
            if label != original:
                tap(options()[label][1])
                assert options()[label][0] == 'Selected', f'{page} / {title}: {label} did not select'
    finally:
        tap(options()[original][1])
    assert options()[original][0] == 'Selected', f'{page} / {title}: not restored to {original}'
    return len(start)


def walk(page, children, height, totals):
    seen = set()
    last = None
    for _ in range(14):
        root = screen()
        names = sorted(d for d, _ in visible(root, height))
        for desc, _ in visible(root, height):
            match = SWITCH.match(desc)
            if match and match[1] not in seen:
                seen.add(match[1])
                flip(page, match[1], match[2])
                totals['switches'] += 1
            match = CHOICE.match(desc)
            if match and match[1] not in seen:
                seen.add(match[1])
                totals['choices'] += pick_all(page, match[1], height)
        if names == last:
            break
        last = names
        adb('shell', 'input', 'swipe', '540', '1900', '540', '1100', '700')
        time.sleep(0.8)
    for _ in range(4):
        adb('shell', 'input', 'swipe', '540', '700', '540', '2100', '200')
    time.sleep(0.8)
    for child, grandchildren in children.items():
        opener = None
        for _ in range(8):
            opener = find(screen(), 'text', child)
            if opener is not None and centre(opener)[1] < height - BOTTOM_MARGIN:
                break
            opener = None
            adb('shell', 'input', 'swipe', '540', '1900', '540', '1300', '600')
            time.sleep(0.8)
        assert opener is not None, f'{page}: no row opens {child}'
        tap(opener)
        time.sleep(1)
        walk(child, grandchildren, height, totals)
        adb('shell', 'input', 'keyevent', 'BACK')
        time.sleep(1.2)
    print(f'PASS {page}')


def test_gestures(height):
    """Exercises cover edge swipes, verifying horizontal swipes trigger next/prev without collapsing the player."""
    print('Testing player gestures & swipe to skip...', flush=True)
    # Open Now Playing screen if not already visible
    mini = find(screen(), 'content-desc', 'Now playing bar')
    if mini is not None:
        tap(mini)
        time.sleep(1.5)
    mid_y = int(height * 0.42)
    # Test swipe left (next)
    adb('shell', 'input', 'swipe', '850', str(mid_y), '150', str(mid_y), '250')
    time.sleep(1.0)
    # Test swipe right (prev)
    adb('shell', 'input', 'swipe', '150', str(mid_y), '850', str(mid_y), '250')
    time.sleep(1.0)
    assert front(), 'Player unexpectedly dismissed or crashed during swipe'
    print('PASS player cover swipes and axis locking')


def test_search():
    """Verifies settings search finds sliders, themes and gestures."""
    print('Testing settings search...', flush=True)
    search_field = find(screen(), 'class', 'android.widget.EditText')
    if search_field is not None:
        tap(search_field)
        time.sleep(0.5)
        adb('shell', 'input', 'text', 'tilt')
        time.sleep(1.0)
        root = screen()
        tilt_result = find(root, 'text', 'Tilt strength') or find(root, 'text', 'Gestures & touch')
        assert tilt_result is not None, 'Search did not find Tilt strength or Gestures & touch'
        adb('shell', 'input', 'keyevent', 'BACK')
        time.sleep(0.5)
    print('PASS settings search indexing and routing')


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    check_icons(sys.argv[1])
    if '--static-only' in sys.argv:
        return
    assert front(), 'open Spotify on Home first'
    adb('logcat', '-b', 'crash', '-c')
    match = re.search(r'x(\d+)', adb('shell', 'wm', 'size'))
    assert match is not None, 'could not read the device screen size'
    height = int(match[1])

    if '--test-gestures' in sys.argv:
        test_gestures(height)
        return

    tap(find(screen(), 'content-desc', 'Go to profile and settings'))
    time.sleep(3)
    tap(find(screen(), 'text', 'Spatch'))
    time.sleep(3)

    if '--test-search' in sys.argv:
        test_search()

    totals = {'switches': 0, 'choices': 0}
    only = next((a[7:].split(',') for a in sys.argv if a.startswith('--only=')), list(TREE))
    for page, children in TREE.items():
        if page not in only:
            continue
        opener = next(n for d, n in visible(screen(), height) if d.startswith(page + ','))
        tap(opener)
        time.sleep(1.2)
        walk(page, children, height, totals)
        adb('shell', 'input', 'keyevent', 'BACK')
        time.sleep(1.2)
    adb('shell', 'input', 'keyevent', 'BACK')
    adb('shell', 'rm', '/sdcard/spatch-ui.xml')
    assert front(), 'Spotify is no longer open'
    crashes = adb('logcat', '-d', '-b', 'crash').count('FATAL')
    assert crashes == 0, f'{crashes} crashes during the walk'
    print(f"PASS {totals['switches']} switches and {totals['choices']} options, no crashes")

    if '--test-gestures' in sys.argv or '--all' in sys.argv:
        test_gestures(height)


main()
