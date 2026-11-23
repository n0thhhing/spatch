(function () {
  "use strict";
  var doc = document, body = doc.body, root = doc.documentElement;
  var reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  var $ = function (s, c) { return (c || doc).querySelector(s); }, $$ = function (s, c) { return Array.prototype.slice.call((c || doc).querySelectorAll(s)); };

  // The app's play button: five soft lobes (the same maths as the app's shape).
  function scallopPath(n, r0, amp) {
    var pts = [], steps = 120;
    for (var i = 0; i < steps; i++) { var a = i / steps * Math.PI * 2, r = r0 + amp * Math.cos(a * n); pts.push((50 + 50 * r * Math.cos(a)).toFixed(2) + " " + (50 + 50 * r * Math.sin(a)).toFixed(2)); }
    return "M" + pts.join("L") + "Z";
  }
  $$("[data-scallop]").forEach(function (el) {
    el.insertAdjacentHTML("afterbegin", '<svg viewBox="0 0 100 100" aria-hidden="true"><path d="' + scallopPath(5, .9, .06) + '"/></svg>');
  });
  $$("[data-brand]").forEach(function (el) { el.insertAdjacentHTML("afterbegin", '<svg viewBox="0 0 100 100" aria-hidden="true"><path d="' + scallopPath(5, .88, .08) + '"/></svg>'); });

  // Menu
  var nb = $(".nav-btn"), dr = $(".drawer");
  if (nb && dr) {
    nb.addEventListener("click", function () { var o = dr.classList.toggle("open"); nb.setAttribute("aria-expanded", o ? "true" : "false"); });
    dr.addEventListener("click", function (e) { if (e.target.tagName === "A") dr.classList.remove("open"); });
  }

  // Palettes: choosing one wears it everywhere and stops the tones from changing.
  var NAMES = { lavender: "Lavender", gruvbox: "Gruvbox", "catppuccin-mocha": "Catppuccin Mocha", "rose-pine": "Rosé Pine", nord: "Nord", "tokyo-night": "Tokyo Night", dracula: "Dracula", "gruvbox-material-dark": "Gruvbox Material", everforest: "Everforest", kanagawa: "Kanagawa", "catppuccin-macchiato": "Catppuccin Macchiato", "catppuccin-frappe": "Catppuccin Frappé", "rose-pine-moon": "Rosé Pine Moon", "tokyo-night-storm": "Tokyo Night Storm", solarized: "Solarized", one: "One", spotify: "Spotify green", "default": "Default" };
  var T = {"lavender":["#14121c","#1d1a2a","#2b2640","#f3eeff","#bcb3dc","#d0bcff"],"gruvbox":["#282828","#3c3836","#504945","#fbf1c7","#a89984","#fe8019"],"catppuccin-mocha":["#1e1e2e","#313244","#45475a","#cdd6f4","#a6adc8","#cba6f7"],"rose-pine":["#191724","#1f1d2e","#26233a","#e0def4","#908caa","#c4a7e7"],"nord":["#2e3440","#3b4252","#434c5e","#eceff4","#d8dee9","#88c0d0"],"tokyo-night":["#1a1b26","#24283b","#292e42","#c0caf5","#9aa5ce","#7aa2f7"],"dracula":["#282a36","#343746","#44475a","#f8f8f2","#adb5cb","#bd93f9"],"gruvbox-material-dark":["#282828","#32302f","#45403d","#ddc7a1","#a89984","#a9b665"],"everforest":["#2d353b","#3d484d","#475258","#d3c6aa","#949f97","#a7c080"],"kanagawa":["#1f1f28","#2a2a37","#363646","#dcd7ba","#898881","#7e9cd8"],"catppuccin-macchiato":["#24273a","#363a4f","#494d64","#cad3f5","#888ea8","#c6a0f6"],"catppuccin-frappe":["#303446","#414559","#51576d","#c6d0f5","#969cb4","#ca9ee6"],"rose-pine-moon":["#2a273f","#393552","#44415a","#e0def4","#918ea3","#c4a7e7"],"tokyo-night-storm":["#24283b","#292e42","#3b4261","#c0caf5","#898fac","#7aa2f7"],"solarized":["#002b36","#073642","#0d4452","#9fabab","#809196","#3794d6"],"one":["#282c34","#2c313a","#3e4451","#abb2bf","#8f939c","#61afef"],"spotify":["#121212","#1f1f1f","#2a2a2a","#ffffff","#7d7d7d","#1ed760"],"default":["#19191b","#232326","#2e2e31","#ececee","#818187","#ececed"]};
  var locked = false;
  function lum(h) { var n = parseInt(h.slice(1), 16); return ((n >> 16 & 255) * .2126 + (n >> 8 & 255) * .7152 + (n & 255) * .0722) / 255; }
  function wear(name, save) {
    var t = T[name]; if (!t) return; locked = true;
    var s = root.style; s.setProperty("--bg", t[0]); s.setProperty("--surface", t[1]); s.setProperty("--ink", t[3]); s.setProperty("--dim", t[4]); s.setProperty("--accent", t[5]);
    s.setProperty("--on-accent", lum(t[5]) > .55 ? "#101014" : "#ffffff"); s.colorScheme = lum(t[0]) > .5 ? "light" : "dark";
    body.removeAttribute("data-tone");
    $$("[data-pal]").forEach(function (b) { b.setAttribute("aria-pressed", b.getAttribute("data-pal") === name ? "true" : "false"); });
    $$("[data-pal-name]").forEach(function (e) { e.textContent = NAMES[name]; });
    if (save) try { localStorage.setItem("spatch-palette", name); } catch (e) { /* private mode */ }
  }
  $$("[data-palettes]").forEach(function (box) {
    Object.keys(T).forEach(function (name) {
      var b = doc.createElement("button"); b.type = "button"; b.className = "chip"; b.setAttribute("data-pal", name); b.setAttribute("aria-pressed", "false"); b.title = NAMES[name]; b.setAttribute("aria-label", NAMES[name]);
      b.style.setProperty("--c1", T[name][0]); b.style.setProperty("--c2", T[name][5]); b.addEventListener("click", function () { wear(name, true); }); box.appendChild(b);
    });
  });
  var saved = null; try { saved = localStorage.getItem("spatch-palette"); } catch (e) { /* ignore */ }
  if (saved && T[saved]) wear(saved, false);

  // Tracks: every band is a track; the one in view sets the tone and the player's title.
  var tracks = $$("[data-track]"), player = $(".player");
  var cur = 0;
  function setTrack(i) {
    cur = Math.max(0, Math.min(tracks.length - 1, i)); var t = tracks[cur];
    if (!locked && t.getAttribute("data-tone")) body.setAttribute("data-tone", t.getAttribute("data-tone"));
    if (player) { $(".meta b", player).textContent = t.getAttribute("data-track"); $(".meta small", player).textContent = t.getAttribute("data-by") || "Spatch"; }
  }
  if (tracks.length && "IntersectionObserver" in window) {
    var io = new IntersectionObserver(function (es) { es.forEach(function (e) { if (e.isIntersecting) setTrack(tracks.indexOf(e.target)); }); }, { rootMargin: "-45% 0px -50% 0px" });
    tracks.forEach(function (t) { io.observe(t); });
    setTrack(0);
  }

  // The mini-player: wavy progress is the scroll; prev/next jump between tracks; play stops the drift.
  if (player) {
    var done = $(".done", player), wavePath = "M0 7";
    // Two identical halves (a whole number of waves each), so shifting by half loops without a seam.
    for (var x = 5; x <= 400; x += 5) wavePath += " L" + x + " " + (7 + Math.sin(x / 200 * Math.PI * 16) * 4).toFixed(2);
    $$(".wave svg", player).forEach(function (s) { s.setAttribute("viewBox", "0 0 400 14"); s.setAttribute("preserveAspectRatio", "none"); s.innerHTML = '<path d="' + wavePath + '"/>'; });
    var upd = function () { var h = doc.documentElement.scrollHeight - innerHeight, p = h > 0 ? Math.min(1, Math.max(0, scrollY / h)) : 0; done.style.clipPath = "inset(0 " + ((1 - p) * 100).toFixed(2) + "% 0 0)"; };
    addEventListener("scroll", upd, { passive: true }); addEventListener("resize", upd); upd();
    var go = function (d) { var n = tracks[Math.max(0, Math.min(tracks.length - 1, cur + d))]; if (n) n.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" }); };
    $(".prev", player).addEventListener("click", function () { go(-1); });
    $(".next", player).addEventListener("click", function () { go(1); });
    var play = $(".play", player), playing = !reduce;
    var sync = function () { player.classList.toggle("paused", !playing); play.setAttribute("aria-label", playing ? "Pause the motion" : "Play the motion"); play.innerHTML = playing ? '<svg viewBox="0 0 24 24"><path d="M6 5h4v14H6zM14 5h4v14h-4z"/></svg>' : '<svg viewBox="0 0 24 24"><path d="M7 4l13 8-13 8z"/></svg>'; };
    play.addEventListener("click", function () { playing = !playing; sync(); }); sync();
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
    lis.forEach(function (l, k) { l.addEventListener("click", function () { show(k, true); }); l.tabIndex = 0; l.addEventListener("keydown", function (e) { if (e.key === "Enter") show(k, true); }); });
    show(0); if (!reduce) timer = setInterval(function () { show(at + 1); }, 2800);
  }

  // Switch board
  var mock = $(".mock");
  $$("[data-switch]").forEach(function (sw) {
    var key = sw.getAttribute("data-switch");
    var apply = function () { mock.classList.toggle("off-" + key, !sw.checked); };
    sw.addEventListener("change", apply); apply();
  });
})();
