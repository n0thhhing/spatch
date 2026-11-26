const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
const panels = [...document.querySelectorAll('[data-panel]')];
const tabs = [...document.querySelectorAll('.tabs a')];
const ind = document.querySelector('.tab-ind');
const names = { overview: 'Spatch', features: 'Features · Spatch', install: 'Install · Spatch', docs: 'Docs · Spatch', questions: 'Questions · Spatch', changelog: 'Changelog · Spatch' };
const buzz = ms => { try { navigator.vibrate?.(ms); } catch {} };

// Heading letters stay hidden until their first sweep, so they never flash in the fallback
// font while Google Sans Flex is still loading.
if (!reduce) document.documentElement.classList.add('wait');

// Split expressive headings into letters so each can swell under a pointer or finger;
// screen readers get the plain text from the hidden copy instead.
for (const h of document.querySelectorAll('.flex')) {
  const text = h.textContent.trim();
  const words = text.split(' ').map(w => `<span class="w">${[...w].map(c => `<span class="ch">${c}</span>`).join('')}</span>`);
  h.innerHTML = `<span class="sr">${text}</span><span aria-hidden="true">${words.join(' ')}</span>`;
}

// Freeze which words sit on each line, so letters can grow and push their neighbours
// along the line without words jumping to the next line or the page moving.
function lock(root) {
  // Only headings measured at another width need it, so revisiting a tab costs no layout.
  // Writes, then one round of reads, then writes: a single forced layout for all of them.
  const width = String(innerWidth);
  const heads = [...root.querySelectorAll('.flex')].filter(h => h.dataset.w !== width);
  if (!heads.length) return;
  const boxes = heads.map(h => {
    h.classList.add('measure');
    const box = h.querySelector('[aria-hidden]');
    const words = [...box.querySelectorAll('.w')];
    box.replaceChildren(...words.flatMap((w, i) => i ? [' ', w] : [w]));
    return { h, box, words };
  });
  for (const b of boxes) {
    b.lines = [];
    for (const w of b.words) {
      // Words on one line can sit a pixel or two apart (rounding, taller letters),
      // so only a bigger jump in offsetTop starts a new line.
      const top = w.offsetTop;
      if (!b.lines.length || Math.abs(b.lines.at(-1).top - top) > 2) b.lines.push({ top, words: [] });
      b.lines.at(-1).words.push(w);
    }
  }
  for (const { h, box, lines } of boxes) {
    box.replaceChildren(...lines.map((l, n) => {
      const line = document.createElement('span');
      line.className = 'line';
      line.style.setProperty('--l', n);
      line.append(...l.words.flatMap((w, i) => i ? [' ', w] : [w]));
      return line;
    }));
    h.classList.remove('measure');
    h.dataset.w = width;
  }
}

// Each heading sweeps in when it comes into view, so only headings you can see animate.
// The bottom margin waits until a heading is a little way up the screen, so the sweep is
// seen rather than finishing just below the fold.
const sweepWatch = new IntersectionObserver(entries => {
  for (const e of entries) if (e.isIntersecting) {
    sweepWatch.unobserve(e.target);
    e.target.classList.remove('waiting');
    void e.target.offsetWidth; // restart the animation
    e.target.classList.add('play');
  }
}, { rootMargin: '0px 0px -8% 0px' });

function sweep(root) {
  for (const h of root.querySelectorAll('.flex')) h.classList.remove('play');
  lock(root);
  if (reduce) return;
  document.documentElement.classList.remove('wait');
  for (const h of root.querySelectorAll('.flex')) { h.classList.add('waiting'); sweepWatch.observe(h); }
}

// Touch: the letter under a finger swells and pushes its neighbours, as hover does with a mouse.
{
  let lit = null, fade;
  const light = (x, y) => {
    const el = document.elementFromPoint(x, y);
    const ch = el && el.closest('.flex .ch');
    if (ch === lit) return;
    lit?.classList.remove('on');
    lit = ch;
    ch?.classList.add('on');
  };
  // Only a touch that starts on a heading letter is followed, so ordinary scrolling does no hit-testing.
  let tracking = false;
  addEventListener('touchstart', e => {
    tracking = !!e.target.closest?.('.flex .ch');
    if (!tracking) return;
    clearTimeout(fade);
    const t = e.touches[0]; light(t.clientX, t.clientY);
  }, { passive: true });
  addEventListener('touchmove', e => {
    if (!tracking) return;
    const t = e.touches[0]; if (t) light(t.clientX, t.clientY);
  }, { passive: true });
  // The letter stays swollen a moment after the finger lifts, so the swell is seen and eases back.
  const release = () => { fade = setTimeout(() => { lit?.classList.remove('on'); lit = null; }, 300); };
  addEventListener('touchend', release, { passive: true });
  addEventListener('touchcancel', release, { passive: true });
}

function placeIndicator() {
  const a = tabs.find(t => t.hasAttribute('aria-current'));
  if (!a) return;
  // offsetLeft and the pill's left: 0 both measure from the bar's padding edge.
  ind.style.setProperty('--x', a.offsetLeft + 'px');
  ind.style.setProperty('--w', a.offsetWidth + 'px');
}

// Re-place the pill whenever any tab changes size or position: the open tab widening on phones,
// the web font arriving, the window resizing. A drag owns the pill while it lasts.
const tabBar = document.querySelector('.tabs');
for (const el of [tabBar, ...tabs]) new ResizeObserver(() => { if (!tabBar.classList.contains('dragging')) placeIndicator(); }).observe(el);

// Drag across the tab bar and let go on a tab, like the app's navigator.
{
  let start = null, dragging = false, under = null;
  const tabAt = x => tabs.find(t => { const r = t.getBoundingClientRect(); return x >= r.left && x <= r.right; })
    || (x < tabs[0].getBoundingClientRect().left ? tabs[0] : tabs.at(-1));
  // Works with a finger, pen or mouse; the tabs' own link dragging is switched off below so it can't steal the gesture.
  tabBar.addEventListener('pointerdown', e => {
    if (e.button !== 0) return;
    start = e.clientX; dragging = false; under = null;
  });
  for (const t of tabs) t.draggable = false;
  tabBar.addEventListener('dragstart', e => e.preventDefault());
  tabBar.addEventListener('pointermove', e => {
    if (start === null) return;
    // A tap with a slightly wobbly finger stays a tap; only a real slide becomes a drag.
    if (!dragging && Math.abs(e.clientX - start) < 8) return;
    if (!dragging) { dragging = true; tabBar.classList.add('dragging'); try { tabBar.setPointerCapture(e.pointerId); } catch {} }
    const was = under;
    under = tabAt(e.clientX);
    if (under !== was && e.pointerType === 'touch') buzz(5);
    const box = tabBar.getBoundingClientRect();
    const w = under.offsetWidth;
    // The pill follows the finger, centred on it, kept inside the bar.
    const pad = parseFloat(getComputedStyle(tabBar).paddingLeft);
    const x = Math.max(pad, Math.min(box.width - pad - w, e.clientX - box.left - w / 2));
    ind.style.setProperty('--w', w + 'px');
    ind.style.setProperty('--x', x + 'px');
    for (const t of tabs) t.style.color = t === under ? 'var(--ink)' : 'var(--fog)';
  });
  const end = e => {
    if (start === null) return;
    start = null;
    if (!dragging) return;
    tabBar.classList.remove('dragging');
    for (const t of tabs) t.style.color = '';
    // A cancelled drag (the page took the gesture to scroll) puts the pill back instead of switching.
    if (e.type === 'pointerup' && under && location.hash !== under.hash) location.hash = under.hash;
    else placeIndicator();
    // Swallow only the click that may directly follow this drag, not the next real tap.
    setTimeout(() => { dragging = false; }, 60);
  };
  tabBar.addEventListener('pointerup', end);
  tabBar.addEventListener('pointercancel', end);
  // If the pointer is lost some other way, put the pill back rather than leave it mid-drag.
  tabBar.addEventListener('lostpointercapture', () => { if (start !== null) end({ type: 'cancel' }); });
  addEventListener('blur', () => { if (start !== null) end({ type: 'cancel' }); });
  // A drag that ends on a tab shouldn't also count as a tap.
  tabBar.addEventListener('click', e => { if (dragging) { e.preventDefault(); dragging = false; } }, true);
}

// Reading progress as the player's seek wave. Browsers with scroll timelines move it themselves;
// others get three transforms per frame. Scrolling only toggles a class twice: straight, then wavy.
const seek = document.querySelector('.seek');
const seekByCss = CSS.supports('animation-timeline: scroll()');
const seekClip = seek.querySelector('.seek-clip'), seekInner = seek.querySelectorAll('.seek-wave, .seek-flat'), seekDot = seek.querySelector('.seek-dot');
let maxScroll = 1, seekIdle;
const measureScroll = () => { maxScroll = document.documentElement.scrollHeight - innerHeight; };
new ResizeObserver(() => { measureScroll(); updateSeek(); }).observe(document.querySelector('main'));
function updateSeek() {
  if (seekByCss) return;
  const p = maxScroll > 0 ? Math.min(1, scrollY / maxScroll) : 0;
  // The clip slides in from the left while the wave inside slides the same distance back,
  // so the wave itself stays put and only the visible part of it grows.
  seekClip.style.transform = `translateX(${(p - 1) * 100}%)`;
  for (const el of seekInner) el.style.transform = `translateX(${(1 - p) * 100}%)`;
  seekDot.style.transform = `translateX(${p * innerWidth}px)`;
}

function show(first) {
  const id = location.hash.slice(1);
  const target = id ? document.getElementById(id) : null;
  const host = target && target.closest('[data-panel]');
  const name = panels.some(p => p.dataset.panel === id) ? id : host ? host.dataset.panel : 'overview';
  const anchor = host && target !== host ? target : null;
  if (!first && document.body.dataset.tab === name) {
    if (anchor) anchor.scrollIntoView({ behavior: reduce ? 'instant' : 'smooth' });
    else scrollTo({ top: 0, behavior: 'instant' });
    return;
  }
  // Scroll before swapping tabs, while the current layout is still valid.
  if (!first && !anchor) scrollTo({ top: 0, behavior: 'instant' });
  for (const p of panels) p.hidden = p.dataset.panel !== name;
  for (const t of tabs) t.hash === '#' + name ? t.setAttribute('aria-current', 'page') : t.removeAttribute('aria-current');
  document.body.dataset.tab = name;
  document.title = names[name];
  // Next frame, so reading the tab's position shares that frame's layout instead of forcing an extra one.
  requestAnimationFrame(() => {
    placeIndicator();
    if (anchor) anchor.scrollIntoView({ behavior: 'instant' });
  });
  const panel = panels.find(p => p.dataset.panel === name);
  // The first sweep waits for the web font, so letters are measured in the right face.
  first ? document.fonts.ready.then(() => sweep(panel)) : sweep(panel);
  if (name === 'changelog' && logPage === 0) loadLog();
  updateSeek();
}

// GitHub replies are kept for 10 minutes (and reused if a later request fails), and a request
// already on its way is shared, so the changelog and the last-updated line ask only once.
const inflight = new Map();
function gh(url) {
  if (inflight.has(url)) return inflight.get(url);
  const key = 'gh:' + url;
  let cached = null;
  try { cached = JSON.parse(localStorage.getItem(key)); } catch {}
  if (cached && Date.now() - cached.t < 600000) return Promise.resolve(cached.data);
  const p = fetch(url, { headers: { Accept: 'application/vnd.github+json' } })
    .then(r => { if (!r.ok) throw r.status; return r.json(); })
    .then(data => { try { localStorage.setItem(key, JSON.stringify({ t: Date.now(), data })); } catch {} return data; })
    .catch(status => { if (cached) return cached.data; throw status; })
    .finally(() => inflight.delete(url));
  inflight.set(url, p);
  return p;
}

// Changelog: commits on the branch the nightlies build from, fetched live from GitHub.
const REPO = 'n0thhhing/spatch', BRANCH = 'fresh', PER_PAGE = 40;
const log = document.querySelector('.log');
const more = document.querySelector('.more');
const areaBox = document.querySelector('.areas');
let logPage = 0, logBusy = false, lastDay = '', dayList = null, dayCount = null, areaPick = 'All';

// Rough area for each change, from its wording; first match wins.
const AREAS = [
  ['Landscape', /landscape/i],
  ['Widget', /widget/i],
  ['Lyrics', /lyric/i],
  ['Sound', /\beq\b|equali[sz]er|sound|bass boost|loudness|reverb|limiter|autoeq/i],
  ['Manager', /manager|clone|nightly|install|signing|patch/i],
  ['Player', /player|cover|artwork|seek|visuali[sz]er|transport|wave|backdrop|blur|glow|disc|pulse|audio|heart|like|sleep|timer|previous|shuffle|repeat|track/i],
  ['Library', /library|playlist|album|liked|search|queue|home|download/i],
  ['Settings', /setting|appearance|palette|theme|toggle|option/i],
];
const areaOf = msg => (AREAS.find(([, re]) => re.test(msg)) || ['Other'])[0];

function dayLabel(d) {
  const opts = { weekday: 'long', day: 'numeric', month: 'long' };
  if (d.getFullYear() !== new Date().getFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString(undefined, opts);
}

// GitHub lists commits newest first, so a change from a new day always starts a new group,
// and a page that ends mid-day carries on in the same group when older changes load.
function addCommit(c) {
  const d = new Date(c.commit.committer.date);
  if (d.toDateString() !== lastDay) {
    lastDay = d.toDateString();
    const day = document.createElement('section');
    day.className = 'day';
    const head = document.createElement('div');
    head.className = 'day-head';
    const h = document.createElement('h2');
    h.textContent = dayLabel(d);
    dayCount = document.createElement('p');
    dayCount.dataset.n = 0;
    head.append(h, dayCount);
    dayList = document.createElement('ul');
    day.append(head, dayList);
    log.append(day);
  }
  const n = ++dayCount.dataset.n;
  dayCount.textContent = n === 1 ? '1 change' : `${n} changes`;
  const li = document.createElement('li');
  const msg = c.commit.message.split('\n')[0].replace(/\.$/, '');
  li.dataset.area = areaOf(msg);
  const tag = document.createElement('span');
  tag.className = 'tag';
  tag.textContent = li.dataset.area;
  const text = document.createElement('span');
  text.textContent = msg;
  const a = document.createElement('a');
  a.href = c.html_url;
  a.textContent = c.sha.slice(0, 7);
  a.setAttribute('aria-label', `See change ${c.sha.slice(0, 7)} on GitHub`);
  li.append(tag, text, ' ', a);
  dayList.append(li);
}

function filterLog() {
  for (const day of log.querySelectorAll('.day')) {
    let n = 0;
    for (const li of day.querySelectorAll('li')) {
      const show = areaPick === 'All' || li.dataset.area === areaPick;
      li.hidden = !show; n += show;
    }
    day.hidden = n === 0;
    day.querySelector('.day-head p').textContent = n === 1 ? '1 change' : `${n} changes`;
  }
  for (const b of areaBox.children) b.setAttribute('aria-pressed', String(b.textContent === areaPick));
  updateSeek();
}

function buildAreas() {
  const present = new Set([...log.querySelectorAll('li')].map(li => li.dataset.area));
  const names = ['All', ...AREAS.map(a => a[0]), 'Other'].filter(n => n === 'All' || present.has(n));
  areaBox.replaceChildren(...names.map(n => {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'area'; b.textContent = n;
    b.addEventListener('click', () => { areaPick = n; filterLog(); });
    return b;
  }));
  areaBox.hidden = false;
}

function logError(status) {
  const p = document.createElement('p');
  p.className = 'log-status';
  const why = status === 403 || status === 429
    ? 'GitHub limits how often a browser can ask for changes. Try again in a few minutes, or see them '
    : 'The changes couldn’t be loaded from GitHub. Check your connection and try again, or see them ';
  const a = document.createElement('a');
  a.href = `https://github.com/${REPO}/commits/${BRANCH}`;
  a.textContent = 'on GitHub';
  p.append(why, a, '.');
  logPage === 0 ? log.replaceChildren(p) : log.append(p);
}

async function loadLog() {
  if (logBusy) return;
  logBusy = true;
  more.disabled = true;
  try {
    const commits = await gh(`https://api.github.com/repos/${REPO}/commits?sha=${BRANCH}&per_page=${PER_PAGE}&page=${logPage + 1}`);
    if (logPage === 0) log.replaceChildren();
    logPage++;
    commits.forEach(addCommit);
    more.hidden = commits.length < PER_PAGE;
    buildAreas();
    filterLog();
  } catch (status) {
    logError(status);
  } finally {
    logBusy = false;
    more.disabled = false;
    updateSeek();
  }
}
more.addEventListener('click', loadLog);

// Latest build: when the nightly branch last changed, and the newest Manager release.
(async () => {
  try {
    // Same first page the changelog uses, so this costs no extra request.
    const [list, r] = await Promise.all([
      gh(`https://api.github.com/repos/${REPO}/commits?sha=${BRANCH}&per_page=${PER_PAGE}&page=1`),
      gh(`https://api.github.com/repos/n0thhhing/spatch-manager/releases/latest`).catch(() => null),
    ]);
    const mins = (new Date(list[0].commit.committer.date) - Date.now()) / 60000;
    const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
    const ago = Math.abs(mins) < 60 ? rtf.format(Math.round(mins), 'minute')
      : Math.abs(mins) < 1440 ? rtf.format(Math.round(mins / 60), 'hour') : rtf.format(Math.round(mins / 1440), 'day');
    const t = document.querySelector('.build-text');
    const a = document.createElement('a');
    a.href = '#changelog'; a.textContent = `Last updated ${ago}`;
    t.append(a, '.');
    if (r && r.name) t.append(` ${r.name} is the latest release.`);
    document.querySelector('.build').hidden = false;
  } catch {}
})();

// Palettes from the app: name, bg, surfHi, ink, accent2, inkDim, accent.
const PALETTES = [
  ['Lavender', 0x14121c, 0x2b2640, 0xf3eeff, 0xc6b8ff, 0xbcb3dc, 0xd0bcff],
  ['Gruvbox', 0x282828, 0x504945, 0xfbf1c7, 0xd5c4a1, 0xa89984, 0xfe8019],
  ['Catppuccin Mocha', 0x1e1e2e, 0x45475a, 0xcdd6f4, 0xf5c2e7, 0xa6adc8, 0xcba6f7],
  ['Catppuccin Macchiato', 0x24273a, 0x494d64, 0xcad3f5, 0xa5adcb, 0x888ea8, 0xc6a0f6],
  ['Catppuccin Frappé', 0x303446, 0x51576d, 0xc6d0f5, 0xa5adce, 0x969cb4, 0xca9ee6],
  ['Rosé Pine', 0x191724, 0x26233a, 0xe0def4, 0xebbcba, 0x908caa, 0xc4a7e7],
  ['Rosé Pine Moon', 0x2a273f, 0x44415a, 0xe0def4, 0x918dab, 0x918ea3, 0xc4a7e7],
  ['Nord', 0x2e3440, 0x434c5e, 0xeceff4, 0x8fbcbb, 0xd8dee9, 0x88c0d0],
  ['Tokyo Night', 0x1a1b26, 0x292e42, 0xc0caf5, 0xbb9af7, 0x9aa5ce, 0x7aa2f7],
  ['Tokyo Night Storm', 0x24283b, 0x3b4261, 0xc0caf5, 0xa9b1d6, 0x898fac, 0x7aa2f7],
  ['Dracula', 0x282a36, 0x44475a, 0xf8f8f2, 0xff79c6, 0xadb5cb, 0xbd93f9],
  ['Gruvbox Material', 0x282828, 0x45403d, 0xddc7a1, 0xd4be98, 0xa89984, 0xa9b665],
  ['Everforest', 0x2d353b, 0x475258, 0xd3c6aa, 0x9da9a0, 0x949f97, 0xa7c080],
  ['Kanagawa', 0x1f1f28, 0x363646, 0xdcd7ba, 0xc8c093, 0x898881, 0x7e9cd8],
  ['Solarized', 0x002b36, 0x0d4452, 0x9fabab, 0x839496, 0x809196, 0x3794d6],
  ['One', 0x282c34, 0x3e4451, 0xabb2bf, 0x9da5b4, 0x8f939c, 0x61afef],
  ['Spotify', 0x121212, 0x2a2a2a, 0xffffff, 0xb3b3b3, 0x7d7d7d, 0x1ed760],
  ['Graphite', 0x19191b, 0x2e2e31, 0xececee, 0xa9a9ae, 0x818187, 0xececed],
];
const PALETTE_VARS = ['--bg', '--fog', '--dim', '--acc', '--ink', '--t1', '--t2', '--t3'];
const hex = n => '#' + n.toString(16).padStart(6, '0');
const lum = n => (.2126 * (n >> 16 & 255) + .7152 * (n >> 8 & 255) + .0722 * (n & 255)) / 255;

function applyPalette(i) {
  const s = document.body.style;
  const p = PALETTES[i];
  if (!p) PALETTE_VARS.forEach(v => s.removeProperty(v));
  else {
    const [, bg, hi, ink, acc2, dim, acc] = p;
    s.setProperty('--bg', hex(bg)); s.setProperty('--fog', hex(ink)); s.setProperty('--dim', hex(dim));
    // Text on the accent is near-black on light accents and white on dark ones, the same rule the app uses.
    s.setProperty('--acc', hex(acc)); s.setProperty('--ink', lum(acc) > .55 ? '#101014' : '#ffffff');
    // The tints that colour the background are pulled toward the palette's background,
    // so a bright accent like Spotify green still gives a dark page.
    s.setProperty('--t1', `color-mix(in srgb, ${hex(acc)} 50%, ${hex(bg)})`);
    s.setProperty('--t2', `color-mix(in srgb, ${hex(acc2)} 40%, ${hex(bg)})`);
    s.setProperty('--t3', hex(hi));
  }
  for (const b of document.querySelectorAll('.swatch')) b.setAttribute('aria-pressed', String(+b.dataset.i === i));
  document.querySelector('.pal-name').textContent = p ? `Showing ${p[0]}.` : '';
  try { p ? localStorage.setItem('spatch-palette', p[0]) : localStorage.removeItem('spatch-palette'); } catch {}
}

{
  const box = document.querySelector('.swatches');
  const make = (i, name, cls, bg, acc) => {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'swatch' + cls; b.dataset.i = i; b.title = name;
    b.setAttribute('aria-label', name); b.setAttribute('aria-pressed', 'false');
    if (bg) { b.style.setProperty('--sw-bg', bg); b.style.setProperty('--sw-acc', acc); }
    b.addEventListener('click', () => applyPalette(i));
    box.append(b);
  };
  make(-1, 'Dynamic: colours from each tab', ' dynamic');
  PALETTES.forEach((p, i) => make(i, p[0], '', hex(p[1]), hex(p[6])));
  document.querySelector('.palettes').hidden = false;
  let saved = -1;
  try { saved = PALETTES.findIndex(p => p[0] === localStorage.getItem('spatch-palette')); } catch {}
  applyPalette(saved);
}

// The hero halo morphs between shapes, like the app's loader: cookie, flower, soft square, circle.
{
  const path = document.querySelector('.halo path');
  // Each shape is [lobes, lobe depth, turn]: the radius swings by the depth around the edge
  // that many times. Blending two radii point by point morphs one shape into the next.
  const SHAPES = [[9, .08, 0], [6, .15, 0], [4, .06, Math.PI / 4], [0, 0, 0]];
  const radius = (s, t) => 1 - s[1] + s[1] * Math.cos(s[0] * (t - s[2]));
  const draw = (a, b, k) => {
    let d = '';
    for (let i = 0; i < 144; i++) {
      const t = i / 144 * Math.PI * 2, r = 48 * (radius(a, t) * (1 - k) + radius(b, t) * k);
      d += (i ? 'L' : 'M') + (50 + r * Math.cos(t)).toFixed(2) + ' ' + (50 + r * Math.sin(t)).toFixed(2);
    }
    path.setAttribute('d', d + 'Z');
  };
  draw(SHAPES[0], SHAPES[0], 0);
  if (!reduce) {
    let i = 0, seen = false;
    // Morph only while the hero is on screen and the page is visible; nothing redraws in between.
    new IntersectionObserver(([e]) => { seen = e.isIntersecting; }).observe(path.closest('.hero-shot'));
    const back = k => 1 + 2.2 * (k - 1) ** 3 + 1.2 * (k - 1) ** 2; // ease out with a small overshoot
    setInterval(() => {
      if (!seen || document.hidden) return;
      const a = SHAPES[i], b = SHAPES[(i + 1) % SHAPES.length], t0 = performance.now();
      i = (i + 1) % SHAPES.length;
      const step = now => { const k = Math.min(1, (now - t0) / 1400); draw(a, b, back(k)); if (k < 1) requestAnimationFrame(step); };
      requestAnimationFrame(step);
    }, 6000);
  }
}

// Screenshot viewer: the picture flies from its place to full size and back.
{
  const viewer = document.querySelector('.viewer'), big = viewer.querySelector('.big'), close = viewer.querySelector('.viewer-close');
  let source = null;
  const ease = 'cubic-bezier(.2, 1.15, .35, 1)';
  // From one box to another as a single transform; .big has transform-origin 0 0 so the
  // translate lines up the top-left corners and the scale fits the sizes.
  const flight = (from, to) => [
    { transform: `translate(${from.left - to.left}px, ${from.top - to.top}px) scale(${from.width / to.width}, ${from.height / to.height})` },
    { transform: 'none' },
  ];
  function open(img) {
    source = img;
    big.src = img.currentSrc || img.src; big.alt = img.alt;
    viewer.hidden = false;
    document.documentElement.style.overflow = 'hidden';
    const go = () => {
      const from = img.getBoundingClientRect(), to = big.getBoundingClientRect();
      // The original hides while its copy is out, so it reads as one picture leaving its spot.
      img.style.visibility = 'hidden';
      if (!reduce) {
        big.animate(flight(from, to), { duration: 520, easing: ease });
        viewer.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 260, easing: 'ease-out' });
      }
      close.focus();
    };
    big.complete ? go() : big.addEventListener('load', go, { once: true });
  }
  function shut() {
    if (!source || viewer.hidden) return;
    const img = source; source = null;
    const done = () => { viewer.hidden = true; img.style.visibility = ''; document.documentElement.style.overflow = ''; img.focus({ preventScroll: true }); };
    if (reduce) return done();
    const to = img.getBoundingClientRect(), from = big.getBoundingClientRect();
    // fill: 'forwards' holds the last frame until the viewer is hidden; without it the big
    // picture would snap back to full size for one frame before disappearing.
    big.animate(flight(to, from).reverse(), { duration: 420, easing: 'cubic-bezier(.4, 0, .2, 1)', fill: 'forwards' }).onfinish = () => { big.getAnimations().forEach(a => a.cancel()); done(); };
    viewer.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 420, easing: 'ease-in', fill: 'forwards' }).onfinish = e => e.target.cancel();
  }
  for (const img of document.querySelectorAll('.hero-shot img, .shots img, .row-shots img, .land-hero, .land-row img')) {
    img.classList.add('zoomable');
    img.tabIndex = 0;
    img.setAttribute('role', 'button');
    img.setAttribute('aria-label', 'Enlarge: ' + img.alt);
    img.addEventListener('click', () => open(img));
    img.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(img); } });
  }
  viewer.addEventListener('click', shut);
  addEventListener('keydown', e => { if (e.key === 'Escape') shut(); });
}

// Heart: bounce and burst into little hearts and cookies, like liking a song.
{
  const heart = document.querySelector('.heart');
  heart.addEventListener('click', () => {
    const on = heart.getAttribute('aria-pressed') !== 'true';
    heart.setAttribute('aria-pressed', String(on));
    heart.setAttribute('aria-label', on ? 'Unlike' : 'Like');
    buzz(on ? [10, 40, 14] : 8);
    if (reduce || !on) return;
    heart.classList.remove('pop'); void heart.offsetWidth; heart.classList.add('pop'); // restart the bounce
    const r = heart.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    for (let n = 0; n < 18; n++) {
      const bit = document.createElement('i');
      bit.className = 'bit ' + ['h', 'c', 'd'][n % 3];
      bit.style.left = cx - 7 + 'px'; bit.style.top = cy - 7 + 'px';
      document.body.append(bit);
      // Pieces fly out evenly around the heart, then drop a little as they fade, like falling confetti.
      const ang = n / 18 * Math.PI * 2 + Math.random() * .3, dist = 60 + Math.random() * 70;
      bit.animate([
        { transform: 'translate(0, 0) scale(.4) rotate(0deg)', opacity: 1 },
        { transform: `translate(${Math.cos(ang) * dist}px, ${Math.sin(ang) * dist - 20}px) scale(${.8 + Math.random() * .7}) rotate(${(Math.random() - .5) * 300}deg)`, opacity: 1, offset: .7 },
        { transform: `translate(${Math.cos(ang) * dist * 1.1}px, ${Math.sin(ang) * dist + 30}px) scale(.6)`, opacity: 0 },
      ], { duration: 900 + Math.random() * 300, easing: 'cubic-bezier(.15, .9, .3, 1)' }).onfinish = () => bit.remove();
    }
  });
}

// Stickers on the screenshots, like the playlist header stickers; tap one to spin it.
{
  // Where each sticker sits on its screenshot: [left, right, top, bottom], alternating corners.
  const spots = [['-14px', null, '-16px', null], [null, '-16px', '22%', null], ['-12px', null, null, '30%'], [null, '-14px', null, '12%']];
  document.querySelectorAll('.shots figure').forEach((fig, n) => {
    const s = document.createElement('span');
    s.className = n % 2 ? 'sticker flower' : 'sticker';
    s.setAttribute('aria-hidden', 'true');
    const [l, r, t, b] = spots[n % 4];
    Object.assign(s.style, { left: l || '', right: r || '', top: t || '', bottom: b || '' });
    s.style.setProperty('--tilt', (n % 2 ? 14 : -12) + 'deg');
    // Each tap adds one more full turn, so quick taps keep spinning it further instead of restarting.
    let turns = 0;
    s.addEventListener('click', () => {
      buzz(6);
      if (reduce) return;
      turns++;
      s.animate([{ transform: `rotate(${(turns - 1) * 360}deg)` }, { transform: `rotate(${turns * 360}deg)` }],
        { duration: 900, easing: 'cubic-bezier(.2, 1.3, .4, 1)', fill: 'forwards' });
    });
    fig.prepend(s);
  });
}

// Motes drifting up, like the player's. They start low on the screen and drift up through it,
// and the negative delays put each one somewhere mid-flight on load instead of all starting together.
if (!reduce) {
  const aura = document.querySelector('.aura');
  const count = innerWidth < 720 ? 5 : 10;
  for (let n = 0; n < count; n++) {
    const m = document.createElement('i');
    const s = 2 + Math.random() * 5;
    m.className = 'mote';
    m.style.cssText = `left:${Math.random() * 100}%;top:${30 + Math.random() * 75}%;width:${s}px;height:${s}px;` +
      `animation-name:float-${'abc'[n % 3]};animation-duration:${14 + Math.random() * 18}s;animation-delay:${-Math.random() * 30}s`;
    aura.append(m);
  }
}

// Panel ids are p-*, so hash links never make the browser jump.
addEventListener('hashchange', () => show(false));
// The pill appears in place on load instead of sliding in from the left; two frames lets it
// settle at its first position before its transition is switched on.
document.documentElement.classList.add('no-anim');
show(true);
requestAnimationFrame(() => requestAnimationFrame(() => document.documentElement.classList.remove('no-anim')));

let resizeTimer;
addEventListener('resize', () => {
  placeIndicator(); measureScroll(); updateSeek();
  // Re-freeze heading lines once resizing settles; doing it on every resize event would force
  // a layout per event while a window is being dragged.
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => lock(panels.find(p => !p.hidden)), 150);
});

let scrollQueued = false;
addEventListener('scroll', () => {
  if (!seekByCss && !scrollQueued) { scrollQueued = true; requestAnimationFrame(() => { scrollQueued = false; updateSeek(); }); }
  if (reduce) return;
  if (!seek.classList.contains('seeking')) seek.classList.add('seeking');
  clearTimeout(seekIdle);
  seekIdle = setTimeout(() => seek.classList.remove('seeking'), 180);
}, { passive: true });
