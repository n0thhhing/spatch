const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
const panels = [...document.querySelectorAll('[data-panel]')];
const tabs = [...document.querySelectorAll('.tabs a')];
const ind = document.querySelector('.tab-ind');
const fill = document.querySelector('.seek-fill');
const names = { overview: 'Spatch', features: 'Features · Spatch', install: 'Install · Spatch', questions: 'Questions · Spatch' };

// Split expressive headings into letters so each can sweep and swell; screen readers get the plain text.
for (const h of document.querySelectorAll('.flex')) {
  const text = h.textContent.trim();
  let i = 0;
  const words = text.split(' ').map(w =>
    `<span class="w">${[...w].map(c => `<span class="ch" style="--i:${i++}">${c}</span>`).join('')}</span>`);
  h.innerHTML = `<span class="sr">${text}</span><span aria-hidden="true">${words.join(' ')}</span>`;
}

function sweep(root) {
  if (reduce) return;
  for (const h of root.querySelectorAll('.flex')) {
    h.classList.remove('play');
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
  sweep(panels.find(p => p.dataset.panel === name));
  updateSeek();
}

// Panel ids are p-*, so hash links never make the browser jump.
addEventListener('hashchange', () => show(false));
document.documentElement.classList.add('no-anim');
show(true);
requestAnimationFrame(() => requestAnimationFrame(() => document.documentElement.classList.remove('no-anim')));
document.fonts.ready.then(placeIndicator);
addEventListener('resize', () => { placeIndicator(); updateSeek(); });
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
