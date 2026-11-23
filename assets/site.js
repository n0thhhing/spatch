const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
const panels = [...document.querySelectorAll('[data-panel]')];
const tabs = [...document.querySelectorAll('.tabs a')];
const ind = document.querySelector('.tab-ind');
const fill = document.querySelector('.seek-fill');
const names = { overview: 'Spatch', features: 'Features · Spatch', install: 'Install · Spatch', questions: 'Questions · Spatch', changelog: 'Changelog · Spatch' };

if (!reduce) document.documentElement.classList.add('wait');
// Split expressive headings into letters so each can sweep and swell; screen readers get the plain text.
for (const h of document.querySelectorAll('.flex')) {
  const text = h.textContent.trim();
  let i = 0;
  const words = text.split(' ').map(w =>
    `<span class="w">${[...w].map(c => `<span class="ch" style="--i:${i++}">${c}</span>`).join('')}</span>`);
  h.innerHTML = `<span class="sr">${text}</span><span aria-hidden="true">${words.join(' ')}</span>`;
}

// Freeze which words sit on each line, so letters can grow and push their neighbours
// along the line without words jumping to the next line or the page moving.
function lock(root) {
  for (const h of root.querySelectorAll('.flex')) {
    h.classList.add('measure'); h.classList.remove('play');
    const box = h.querySelector('[aria-hidden]');
    const words = [...box.querySelectorAll('.w')];
    box.replaceChildren(...words.flatMap((w, i) => i ? [' ', w] : [w]));
    const lines = [];
    for (const w of words) {
      const top = w.offsetTop;
      if (!lines.length || Math.abs(lines.at(-1).top - top) > 2) lines.push({ top, words: [] });
      lines.at(-1).words.push(w);
    }
    box.replaceChildren(...lines.map(l => {
      const line = document.createElement('span');
      line.className = 'line';
      line.append(...l.words.flatMap((w, i) => i ? [' ', w] : [w]));
      return line;
    }));
    h.classList.remove('measure');
  }
}

function sweep(root) {
  lock(root);
  if (reduce) return;
  document.documentElement.classList.remove('wait');
  for (const h of root.querySelectorAll('.flex')) {
    void h.offsetWidth; // restart the animation
    h.classList.add('play');
  }
}

function placeIndicator() {
  const a = tabs.find(t => t.hasAttribute('aria-current'));
  if (!a) return;
  ind.style.setProperty('--x', a.offsetLeft - 5 + 'px');
  ind.style.setProperty('--w', a.offsetWidth + 'px');
}

function updateSeek() {
  const max = document.documentElement.scrollHeight - innerHeight;
  fill.style.setProperty('--p', max > 0 ? Math.min(1, scrollY / max) : 0);
}

function show(first) {
  const id = location.hash.slice(1);
  const name = panels.some(p => p.dataset.panel === id) ? id : 'overview';
  for (const p of panels) p.hidden = p.dataset.panel !== name;
  for (const t of tabs) t.hash === '#' + name ? t.setAttribute('aria-current', 'page') : t.removeAttribute('aria-current');
  document.body.dataset.tab = name;
  document.title = names[name];
  placeIndicator();
  if (!first) scrollTo({ top: 0, behavior: 'instant' });
  const panel = panels.find(p => p.dataset.panel === name);
  // The first sweep waits for the web font, so letters are measured in the right face.
  first ? document.fonts.ready.then(() => sweep(panel)) : sweep(panel);
  if (name === 'changelog' && logPage === 0) loadLog();
  updateSeek();
}

// Changelog: commits on the branch the nightlies build from, fetched live from GitHub.
const REPO = 'n0thhhing/spatch', BRANCH = 'fresh', PER_PAGE = 40;
const log = document.querySelector('.log');
const more = document.querySelector('.more');
let logPage = 0, logBusy = false, lastDay = '', dayList = null, dayCount = null;

function dayLabel(d) {
  const opts = { weekday: 'long', day: 'numeric', month: 'long' };
  if (d.getFullYear() !== new Date().getFullYear()) opts.year = 'numeric';
  return d.toLocaleDateString(undefined, opts);
}

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
const areaBox = document.querySelector('.areas');
let areaPick = 'All';

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
    const r = await fetch(`https://api.github.com/repos/${REPO}/commits?sha=${BRANCH}&per_page=${PER_PAGE}&page=${logPage + 1}`,
      { headers: { Accept: 'application/vnd.github+json' } });
    if (!r.ok) throw r.status;
    const commits = await r.json();
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

// Panel ids are p-*, so hash links never make the browser jump.
addEventListener('hashchange', () => show(false));
document.documentElement.classList.add('no-anim');
show(true);
requestAnimationFrame(() => requestAnimationFrame(() => document.documentElement.classList.remove('no-anim')));
document.fonts.ready.then(placeIndicator);
let resizeTimer;
addEventListener('resize', () => {
  placeIndicator(); updateSeek();
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => lock(panels.find(p => !p.hidden)), 150);
});
// shortcut: unthrottled scroll handler; it is one style write per frame.
addEventListener('scroll', () => requestAnimationFrame(updateSeek), { passive: true });

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
const hex = n => '#' + n.toString(16).padStart(6, '0');
const lum = n => (.2126 * (n >> 16 & 255) + .7152 * (n >> 8 & 255) + .0722 * (n & 255)) / 255;
const PALETTE_VARS = ['--bg', '--fog', '--dim', '--acc', '--ink', '--t1', '--t2', '--t3'];

function applyPalette(i) {
  const s = document.body.style;
  const p = PALETTES[i];
  if (!p) PALETTE_VARS.forEach(v => s.removeProperty(v));
  else {
    const [, bg, hi, ink, acc2, dim, acc] = p;
    s.setProperty('--bg', hex(bg)); s.setProperty('--fog', hex(ink)); s.setProperty('--dim', hex(dim));
    s.setProperty('--acc', hex(acc)); s.setProperty('--ink', lum(acc) > .55 ? '#101014' : '#ffffff');
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

// Ring of bars behind the hero phone.
{
  const n = 72, ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('class', 'bars'); svg.setAttribute('viewBox', '0 0 100 100'); svg.setAttribute('aria-hidden', 'true');
  for (let k = 0; k < n; k++) {
    const r = document.createElementNS(ns, 'rect');
    r.setAttribute('x', 49.3); r.setAttribute('y', 1); r.setAttribute('width', 1.4); r.setAttribute('height', 10); r.setAttribute('rx', .7);
    r.style.cssText = `--d:${.9 + (k * 7 % 5) * .18}s;--dl:${-k * .07}s`;
    // Rotate a wrapper, not the bar: CSS scale on the bar would otherwise apply after the rotation.
    const g = document.createElementNS(ns, 'g');
    g.setAttribute('transform', `rotate(${k * 360 / n} 50 50)`);
    g.append(r);
    svg.append(g);
  }
  const shot = document.querySelector('.hero-shot');
  shot.insertBefore(svg, shot.querySelector('img'));
}

// Latest build: when the nightly branch last changed, and the newest Manager release.
(async () => {
  try {
    const [c, r] = await Promise.all([
      fetch(`https://api.github.com/repos/${REPO}/commits/${BRANCH}`).then(x => x.ok ? x.json() : Promise.reject()),
      fetch(`https://api.github.com/repos/${REPO}/releases/latest`).then(x => x.ok ? x.json() : null).catch(() => null),
    ]);
    const mins = (new Date(c.commit.committer.date) - Date.now()) / 60000;
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

// Motes drifting up, like the player's.
if (!reduce) {
  const aura = document.querySelector('.aura');
  const count = innerWidth < 720 ? 14 : 26;
  for (let n = 0; n < count; n++) {
    const m = document.createElement('i');
    const s = 2 + Math.random() * 5;
    m.className = 'mote';
    m.style.cssText = `left:${Math.random() * 100}%;top:${30 + Math.random() * 75}%;width:${s}px;height:${s}px;` +
      `--d:${14 + Math.random() * 18}s;--dl:${-Math.random() * 30}s;--o:${.25 + Math.random() * .5};--x:${(Math.random() - .5) * 80}px`;
    aura.append(m);
  }
}
