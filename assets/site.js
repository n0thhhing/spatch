(function () {
  "use strict";
  var doc = document, root = doc.documentElement;
  var reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  var $ = function (s, c) { return (c || doc).querySelector(s); };
  var $$ = function (s, c) { return Array.prototype.slice.call((c || doc).querySelectorAll(s)); };
  var TAU = Math.PI * 2;

  // The app's play button: soft lobes, five at rest and nine while playing (t blends between them).
  function lobes(t, r0, amp) {
    var d = "", n = 120;
    for (var i = 0; i < n; i++) {
      var a = i / n * TAU, r = r0 + amp * ((1 - t) * Math.cos(5 * a) + t * Math.cos(9 * a));
      d += (i ? "L" : "M") + (50 + 50 * r * Math.cos(a)).toFixed(2) + " " + (50 + 50 * r * Math.sin(a)).toFixed(2);
    }
    return d + "Z";
  }
  $$("[data-brand]").forEach(function (el) { el.insertAdjacentHTML("afterbegin", '<svg viewBox="0 0 100 100" aria-hidden="true"><path d="' + lobes(0, .86, .08) + '"/></svg>'); });
  $$("[data-scallop]").forEach(function (el) { el.insertAdjacentHTML("afterbegin", '<svg viewBox="0 0 100 100" aria-hidden="true"><path d="' + lobes(0, .88, .06) + '"/></svg>'); });

  // Menu
  var nb = $(".nav-btn"), dr = $(".drawer");
  if (nb && dr) {
    nb.addEventListener("click", function () { var o = dr.classList.toggle("open"); nb.setAttribute("aria-expanded", o ? "true" : "false"); });
    dr.addEventListener("click", function (e) { if (e.target.tagName === "A") dr.classList.remove("open"); });
  }

  // Covers: original generated art, in the style of the preview's demo covers.
  function cover(ctx, s, hue) {
    var g = ctx.createLinearGradient(0, 0, s, s);
    g.addColorStop(0, "hsl(" + hue + " 75% 58%)"); g.addColorStop(1, "hsl(" + (hue + 40) + " 60% 22%)");
    ctx.fillStyle = g; ctx.fillRect(0, 0, s, s);
    ctx.strokeStyle = "rgba(255,255,255,.32)"; ctx.lineWidth = Math.max(1, s / 220);
    for (var k = 1; k < 26; k++) { ctx.beginPath(); ctx.arc(s * .38 + k * s * .006, s * .62 - k * s * .004, k * s * .028, 0, TAU); ctx.stroke(); }
  }

  var TRACKS = [
    { t: "Glasshouse", a: "Northbound", h: 190, d: 214, l: ["Every line lights as it is sung", "The column glides to keep it centred", "Tap a line and the song jumps there", "Then it settles back into place"] },
    { t: "Daybreak Radio", a: "Hana Vale", h: 330, d: 188, l: ["The colours follow what is playing", "Press play and the button blooms", "Pause and everything comes to rest", "Skip, and the page changes with it"] },
    { t: "Blue Hour", a: "Low Tide", h: 140, d: 236, l: ["A ring of bars around the cover", "Moving with the music underneath", "A soft blur of the cover behind it", "Quiet when the room is quiet"] },
    { t: "Signal Fires", a: "June Static", h: 262, d: 201, l: ["Every piece of it is a setting", "Keep what you like, hide the rest", "Two lines of lyrics, or none at all", "It is your player after all"] }
  ];
  var cur = 0, locked = false;
  function tint(h) { if (!locked) root.style.setProperty("--h", h); }

  // The live player
  var live = $(".live");
  if (live) {
    var cv = $("canvas", live), ctx = cv.getContext("2d"), art = doc.createElement("canvas"), actx = art.getContext("2d");
    var playBtn = $(".play", live), shapePath = $(".play .shape path", live), glyph = $(".play .glyph", live);
    var wave = $(".wave", live), wDone = $(".done", wave), wRest = $(".rest", wave);
    var title = $(".meta b", live), artist = $(".meta small", live), elapsed = $(".times .at", live), left = $(".times .left", live);
    var lineNow = $(".card .now", live), lineNext = $(".card .later", live);
    var playing = !reduce, morph = playing ? 1 : 0, amp = playing ? 1 : 0, pos = 0, spin = 0, bars = new Float32Array(72), seen = 0, lineAt = -1, visible = true, raf = 0;
    var size = 0, dpr = Math.min(2, window.devicePixelRatio || 1);
    var fmt = function (s) { s = Math.max(0, Math.floor(s)); return Math.floor(s / 60) + ":" + ("0" + s % 60).slice(-2); };
    var resize = function () { size = Math.round(cv.getBoundingClientRect().width * dpr); cv.width = cv.height = size; art.width = art.height = Math.max(1, Math.round(size * .62)); cover(actx, art.width, TRACKS[cur].h); };
    var setLines = function (i, animate) {
      var tr = TRACKS[cur]; lineAt = i;
      var a = tr.l[i % tr.l.length], b = tr.l[(i + 1) % tr.l.length];
      if (!animate) { lineNow.textContent = a; lineNext.textContent = b; return; }
      [lineNow, lineNext].forEach(function (e) { e.style.transform = "translateY(-8px)"; e.style.opacity = "0"; });
      setTimeout(function () { lineNow.textContent = a; lineNext.textContent = b; [lineNow, lineNext].forEach(function (e) { e.style.transform = ""; e.style.opacity = ""; }); }, 220);
    };
    var draw = function (now) {
      var s = size, c = s / 2, R = s * .48, r = s * .31;
      if (!s) return;
      ctx.clearRect(0, 0, s, s);
      var glow = getComputedStyle(root).getPropertyValue("--glow").trim() || "#8ef";
      ctx.strokeStyle = glow; ctx.lineCap = "round"; ctx.lineWidth = s * .014;
      for (var i = 0; i < bars.length; i++) {
        var a = i / bars.length * TAU - Math.PI / 2;
        var target = amp * (.3 + .7 * Math.abs(Math.sin(now / 420 + i * .7) * Math.cos(now / 690 + i * .33)));
        bars[i] += (target - bars[i]) * .18;
        var inner = r + s * .055, len = s * .016 + bars[i] * (R - inner - s * .016);
        ctx.beginPath(); ctx.moveTo(c + Math.cos(a) * inner, c + Math.sin(a) * inner); ctx.lineTo(c + Math.cos(a) * (inner + len), c + Math.sin(a) * (inner + len)); ctx.stroke();
      }
      ctx.save(); ctx.translate(c, c); ctx.rotate(spin); ctx.beginPath(); ctx.arc(0, 0, r, 0, TAU); ctx.clip();
      ctx.drawImage(art, -r, -r, r * 2, r * 2); ctx.restore();
      ctx.lineWidth = s * .008; ctx.beginPath(); ctx.arc(c, c, r + s * .022, 0, TAU); ctx.stroke();
      shapePath.setAttribute("d", lobes(morph, .9, .055));
      var turn = (spin * 40) % 360;
      playBtn.style.transform = "rotate(" + turn.toFixed(1) + "deg)"; glyph.style.transform = "rotate(" + (-turn).toFixed(1) + "deg)";
      var tr = TRACKS[cur], p = pos / tr.d, x = (p * 300).toFixed(1), A = 4 * amp, d = "M0 11";
      for (var k = 4; k <= p * 300; k += 4) d += "L" + k + " " + (11 + Math.sin(k / 9 - now / 260) * A).toFixed(2);
      wDone.setAttribute("d", d); wRest.setAttribute("d", "M" + x + " 11L300 11");
      elapsed.textContent = fmt(pos); left.textContent = "-" + fmt(tr.d - pos);
    };
    var frame = function (now) {
      var dt = seen ? Math.min(.05, (now - seen) / 1000) : 0; seen = now;
      var goal = playing ? 1 : 0;
      morph += (goal - morph) * Math.min(1, dt * 7);
      amp += (goal - amp) * Math.min(1, dt * 4);
      if (playing) { pos += dt; spin += dt * .12; }
      if (pos >= TRACKS[cur].d) load(cur + 1);
      var li = Math.floor(pos / 3.4);
      if (li !== lineAt) setLines(li, lineAt >= 0);
      draw(now);
      var settling = Math.abs(goal - morph) > .002 || Math.abs(goal - amp) > .002;
      raf = (visible && (playing || settling)) ? requestAnimationFrame(frame) : 0;
      if (!raf) seen = 0;
    };
    var load = function (i) {
      cur = (i + TRACKS.length) % TRACKS.length; var tr = TRACKS[cur];
      title.textContent = tr.t; artist.textContent = tr.a; pos = 0; tint(tr.h);
      if (size) cover(actx, art.width, tr.h);
      $$(".wmock").forEach(function (w) { $("b", w).textContent = tr.t; $("small", w).textContent = tr.a; var cw = $("canvas", w); cover(cw.getContext("2d"), cw.width, tr.h); });
      setLines(0, lineAt >= 0);
      if (!raf) frame(performance.now());
    };
    var setPlaying = function (v) {
      playing = v; playBtn.setAttribute("aria-label", v ? "Pause" : "Play"); playBtn.setAttribute("aria-pressed", v ? "true" : "false");
      glyph.innerHTML = v ? '<path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"/>' : '<path d="M8 5l11 7-11 7z"/>';
      if (!raf) frame(performance.now());
    };
    playBtn.addEventListener("click", function () { setPlaying(!playing); });
    $(".prev", live).addEventListener("click", function () { load(pos > 3 ? cur : cur - 1); });
    $(".next", live).addEventListener("click", function () { load(cur + 1); });
    if ("IntersectionObserver" in window) new IntersectionObserver(function (es) { visible = es[0].isIntersecting; if (visible && !raf) frame(performance.now()); }).observe(live);
    addEventListener("resize", function () { resize(); if (!raf) draw(performance.now()); });
    $$(".wmock canvas").forEach(function (cw) { cw.width = cw.height = 152; });
    resize(); load(0); setPlaying(playing);
  }

  // Lyric stage
  var stage = $("[data-lyrics]");
  if (stage) {
    var lis = $$("li", stage), at = 0, timer;
    var show = function (i, user) {
      at = (i + lis.length) % lis.length;
      lis.forEach(function (l, k) { l.classList.toggle("on", k === at); l.classList.toggle("next", k === (at + 1) % lis.length); });
      if (user) { clearInterval(timer); if (!reduce) timer = setInterval(function () { show(at + 1); }, 2800); }
    };
    lis.forEach(function (l, k) { l.tabIndex = 0; l.addEventListener("click", function () { show(k, true); }); l.addEventListener("keydown", function (e) { if (e.key === "Enter") show(k, true); }); });
    show(0); if (!reduce) timer = setInterval(function () { show(at + 1); }, 2800);
  }

  // Widget mock: its skip button skips the live player too
  $$(".wmock").forEach(function (w) {
    var cw = $("canvas", w); if (!cw.width || cw.width === 300) { cw.width = cw.height = 152; cover(cw.getContext("2d"), 152, TRACKS[cur].h); }
    var sp = $(".wbtn .shape path", w); if (sp) sp.setAttribute("d", lobes(0, .88, .07));
    var nx = $(".wnext", w); if (nx) nx.addEventListener("click", function () { var n = $(".live .next"); if (n) n.click(); });
  });

  // Palettes: wear one everywhere (the page stops following the covers)
  var NAMES = { lavender: "Lavender", gruvbox: "Gruvbox", "catppuccin-mocha": "Catppuccin Mocha", "rose-pine": "Rosé Pine", nord: "Nord", "tokyo-night": "Tokyo Night", dracula: "Dracula", "gruvbox-material-dark": "Gruvbox Material", everforest: "Everforest", kanagawa: "Kanagawa", "catppuccin-macchiato": "Catppuccin Macchiato", "catppuccin-frappe": "Catppuccin Frappé", "rose-pine-moon": "Rosé Pine Moon", "tokyo-night-storm": "Tokyo Night Storm", solarized: "Solarized", one: "One", spotify: "Spotify green", "default": "Default" };
  var T = {"lavender":["#14121c","#1d1a2a","#2b2640","#f3eeff","#bcb3dc","#d0bcff"],"gruvbox":["#282828","#3c3836","#504945","#fbf1c7","#a89984","#fe8019"],"catppuccin-mocha":["#1e1e2e","#313244","#45475a","#cdd6f4","#a6adc8","#cba6f7"],"rose-pine":["#191724","#1f1d2e","#26233a","#e0def4","#908caa","#c4a7e7"],"nord":["#2e3440","#3b4252","#434c5e","#eceff4","#d8dee9","#88c0d0"],"tokyo-night":["#1a1b26","#24283b","#292e42","#c0caf5","#9aa5ce","#7aa2f7"],"dracula":["#282a36","#343746","#44475a","#f8f8f2","#adb5cb","#bd93f9"],"gruvbox-material-dark":["#282828","#32302f","#45403d","#ddc7a1","#a89984","#a9b665"],"everforest":["#2d353b","#3d484d","#475258","#d3c6aa","#949f97","#a7c080"],"kanagawa":["#1f1f28","#2a2a37","#363646","#dcd7ba","#898881","#7e9cd8"],"catppuccin-macchiato":["#24273a","#363a4f","#494d64","#cad3f5","#888ea8","#c6a0f6"],"catppuccin-frappe":["#303446","#414559","#51576d","#c6d0f5","#969cb4","#ca9ee6"],"rose-pine-moon":["#2a273f","#393552","#44415a","#e0def4","#918ea3","#c4a7e7"],"tokyo-night-storm":["#24283b","#292e42","#3b4261","#c0caf5","#898fac","#7aa2f7"],"solarized":["#002b36","#073642","#0d4452","#9fabab","#809196","#3794d6"],"one":["#282c34","#2c313a","#3e4451","#abb2bf","#8f939c","#61afef"],"spotify":["#121212","#1f1f1f","#2a2a2a","#ffffff","#7d7d7d","#1ed760"],"default":["#19191b","#232326","#2e2e31","#ececee","#818187","#ececed"]};
  function lum(h) { var n = parseInt(h.slice(1), 16); return ((n >> 16 & 255) * .2126 + (n >> 8 & 255) * .7152 + (n & 255) * .0722) / 255; }
  function wear(name, save) {
    var t = T[name]; if (!t) return; locked = true;
    var s = root.style, set = function (k, v) { s.setProperty(k, v); };
    set("--bg", t[0]); set("--surface", t[1]); set("--sunk", t[2]); set("--ink", t[3]); set("--dim", t[4]); set("--accent", t[5]);
    set("--deep", t[1]); set("--deep-ink", t[3]); set("--glow", t[5]); set("--on-accent", lum(t[5]) > .55 ? "#101014" : "#ffffff");
    s.colorScheme = lum(t[0]) > .5 ? "light" : "dark";
    $$("[data-pal]").forEach(function (b) { b.setAttribute("aria-pressed", b.getAttribute("data-pal") === name ? "true" : "false"); });
    $$("[data-pal-name]").forEach(function (e) { e.textContent = NAMES[name]; });
    if (save) try { localStorage.setItem("spatch-palette-v3", name); } catch (e) { /* private mode */ }
  }
  $$("[data-palettes]").forEach(function (box) {
    Object.keys(T).forEach(function (name) {
      var b = doc.createElement("button"); b.type = "button"; b.className = "chip"; b.setAttribute("data-pal", name); b.setAttribute("aria-pressed", "false"); b.title = NAMES[name]; b.setAttribute("aria-label", NAMES[name]);
      b.style.setProperty("--c1", T[name][0]); b.style.setProperty("--c2", T[name][5]);
      b.addEventListener("click", function () { wear(name, true); }); box.appendChild(b);
    });
  });
  var saved = null; try { saved = localStorage.getItem("spatch-palette-v3"); } catch (e) { /* ignore */ }
  if (saved && T[saved]) wear(saved, false);

  // Switch board
  var mock = $(".mock");
  if (mock) $$("[data-switch]").forEach(function (sw) {
    var apply = function () { mock.classList.toggle("off-" + sw.getAttribute("data-switch"), !sw.checked); };
    sw.addEventListener("change", apply); apply();
  });
})();
