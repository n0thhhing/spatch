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
  const text = document.createElement('span');
  text.textContent = c.commit.message.split('\n')[0].replace(/\.$/, '');
  const a = document.createElement('a');
  a.href = c.html_url;
  a.textContent = c.sha.slice(0, 7);
  a.setAttribute('aria-label', `See change ${c.sha.slice(0, 7)} on GitHub`);
  li.append(text, ' ', a);
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
