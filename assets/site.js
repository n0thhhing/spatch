(function () {
  "use strict";
  var doc = document, root = doc.documentElement;
  var reduce = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;

  // Palettes: bg, surface, surface2, ink, ink2, accent (the same sets the app ships).
  var NAMES = { lavender: "Lavender", gruvbox: "Gruvbox", "catppuccin-mocha": "Catppuccin Mocha", "rose-pine": "Rosé Pine", nord: "Nord", "tokyo-night": "Tokyo Night", dracula: "Dracula", "gruvbox-material-dark": "Gruvbox Material", everforest: "Everforest", kanagawa: "Kanagawa", "catppuccin-macchiato": "Catppuccin Macchiato", "catppuccin-frappe": "Catppuccin Frappé", "rose-pine-moon": "Rosé Pine Moon", "tokyo-night-storm": "Tokyo Night Storm", solarized: "Solarized", one: "One", spotify: "Spotify green", "default": "Default" };
  var THEMES = {"lavender":["#14121c","#1d1a2a","#2b2640","#f3eeff","#bcb3dc","#d0bcff"],"gruvbox":["#282828","#3c3836","#504945","#fbf1c7","#a89984","#fe8019"],"catppuccin-mocha":["#1e1e2e","#313244","#45475a","#cdd6f4","#a6adc8","#cba6f7"],"rose-pine":["#191724","#1f1d2e","#26233a","#e0def4","#908caa","#c4a7e7"],"nord":["#2e3440","#3b4252","#434c5e","#eceff4","#d8dee9","#88c0d0"],"tokyo-night":["#1a1b26","#24283b","#292e42","#c0caf5","#9aa5ce","#7aa2f7"],"dracula":["#282a36","#343746","#44475a","#f8f8f2","#adb5cb","#bd93f9"],"gruvbox-material-dark":["#282828","#32302f","#45403d","#ddc7a1","#a89984","#a9b665"],"everforest":["#2d353b","#3d484d","#475258","#d3c6aa","#949f97","#a7c080"],"kanagawa":["#1f1f28","#2a2a37","#363646","#dcd7ba","#898881","#7e9cd8"],"catppuccin-macchiato":["#24273a","#363a4f","#494d64","#cad3f5","#888ea8","#c6a0f6"],"catppuccin-frappe":["#303446","#414559","#51576d","#c6d0f5","#969cb4","#ca9ee6"],"rose-pine-moon":["#2a273f","#393552","#44415a","#e0def4","#918ea3","#c4a7e7"],"tokyo-night-storm":["#24283b","#292e42","#3b4261","#c0caf5","#898fac","#7aa2f7"],"solarized":["#002b36","#073642","#0d4452","#9fabab","#809196","#3794d6"],"one":["#282c34","#2c313a","#3e4451","#abb2bf","#8f939c","#61afef"],"spotify":["#121212","#1f1f1f","#2a2a2a","#ffffff","#7d7d7d","#1ed760"],"default":["#19191b","#232326","#2e2e31","#ececee","#818187","#ececed"]};
  var current = "lavender";

  function lum(hex) {
    var n = parseInt(hex.slice(1), 16), r = n >> 16 & 255, g = n >> 8 & 255, b = n & 255;
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  }
  function apply(name, save) {
    var t = THEMES[name]; if (!t) return;
    current = name;
    var s = root.style;
    s.setProperty("--bg", t[0]); s.setProperty("--surface", t[1]); s.setProperty("--surface2", t[2]);
    s.setProperty("--ink", t[3]); s.setProperty("--ink2", t[4]); s.setProperty("--accent", t[5]);
    s.setProperty("--on-accent", lum(t[5]) > 0.55 ? "#101014" : "#ffffff");
    s.colorScheme = lum(t[0]) > 0.5 ? "light" : "dark";
    root.setAttribute("data-palette", name);
    doc.querySelectorAll("[data-theme-name]").forEach(function (el) { el.textContent = NAMES[name]; });
    doc.querySelectorAll("[data-pal]").forEach(function (b) { b.setAttribute("aria-pressed", b.getAttribute("data-pal") === name ? "true" : "false"); });
    var meta = doc.querySelector('meta[name="theme-color"]'); if (meta) meta.setAttribute("content", t[0]);
    if (save) try { localStorage.setItem("spatch-palette", name); } catch (e) { /* private mode: fine */ }
  }
  var HERO = ["lavender", "catppuccin-mocha", "tokyo-night", "rose-pine", "nord", "dracula", "gruvbox", "everforest", "spotify"];
  doc.querySelectorAll("[data-swatches]").forEach(function (box) {
    HERO.forEach(function (name) {
      var b = doc.createElement("button"); b.type = "button"; b.className = "swatch"; b.setAttribute("data-pal", name);
      b.setAttribute("aria-label", NAMES[name] + " palette"); b.setAttribute("aria-pressed", "false"); b.title = NAMES[name];
      b.style.setProperty("--c1", THEMES[name][0]); b.style.setProperty("--c2", THEMES[name][5]);
      b.addEventListener("click", function () { apply(name, true); });
      box.appendChild(b);
    });
  });
  doc.querySelectorAll("[data-palette-grid]").forEach(function (grid) {
    Object.keys(THEMES).forEach(function (name) {
      var t = THEMES[name], b = doc.createElement("button"); b.type = "button"; b.className = "palette"; b.setAttribute("data-pal", name); b.setAttribute("aria-pressed", "false");
      b.style.cssText = "--p-bg:" + t[0] + ";--p-surface:" + t[1] + ";--p-ink:" + t[3] + ";--p-dim:" + t[4] + ";--p-accent:" + t[5];
      b.innerHTML = '<span class="bar"><i></i><i></i><i></i><i></i></span><strong>' + NAMES[name] + "</strong><small>" + t[5] + "</small>";
      b.addEventListener("click", function () { apply(name, true); });
      grid.appendChild(b);
    });
  });
  var saved = null; try { saved = localStorage.getItem("spatch-palette"); } catch (e) { /* ignore */ }
  if (saved && THEMES[saved]) apply(saved, false); else apply("lavender", false), root.removeAttribute("data-palette");

  // Mobile menu
  var toggle = doc.querySelector(".nav-toggle"), drawer = doc.querySelector(".mobile-drawer");
  if (toggle && drawer) {
    toggle.addEventListener("click", function () {
      var open = drawer.classList.toggle("open"); toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
    drawer.addEventListener("click", function (e) { if (e.target.tagName === "A") { drawer.classList.remove("open"); toggle.setAttribute("aria-expanded", "false"); } });
  }

  // Scroll reveal
  var items = doc.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && !reduce) {
    var io = new IntersectionObserver(function (es) { es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }); }, { threshold: .12 });
    items.forEach(function (el) { io.observe(el); });
  } else items.forEach(function (el) { el.classList.add("in"); });

  // Live lyrics demo: the sung line lights and the column glides to keep it centred, like the app.
  var card = doc.querySelector("[data-lyrics]");
  if (card) {
    var LINES = ["Every line lights as it is sung", "The column glides to keep it centred", "Tap a line to jump straight there", "♪", "Glow follows the cover's colours", "Dim lines melt into the edges", "Pick it up in the player or full screen", "Or leave it off, it is optional"];
    var col = doc.createElement("div"); col.className = "col";
    LINES.forEach(function (t, i) {
      var r = doc.createElement("div"); r.className = "lrow"; r.textContent = t; r.setAttribute("data-i", i);
      r.addEventListener("click", function () { go(i, true); }); col.appendChild(r);
    });
    card.appendChild(col);
    var rows = col.children, at = 0, timer = null;
    var go = function (i, user) {
      at = (i + LINES.length) % LINES.length;
      for (var k = 0; k < rows.length; k++) { rows[k].classList.toggle("on", k === at); rows[k].classList.toggle("near", Math.abs(k - at) === 1); }
      var r = rows[at]; col.style.transform = "translateY(" + (-(r.offsetTop + r.offsetHeight / 2)) + "px)";
      if (user) { clearInterval(timer); if (!reduce) timer = setInterval(function () { go(at + 1); }, 2400); }
    };
    go(0);
    if (!reduce) timer = setInterval(function () { go(at + 1); }, 2400);
    window.addEventListener("resize", function () { go(at); });
  }

  // Parallax on the hero devices
  var stage = doc.querySelector(".hero-stage");
  if (stage && !reduce && matchMedia("(pointer: fine)").matches) {
    var layers = stage.querySelectorAll("[data-depth]");
    stage.addEventListener("pointermove", function (e) {
      var b = stage.getBoundingClientRect(), x = (e.clientX - b.left) / b.width - .5, y = (e.clientY - b.top) / b.height - .5;
      layers.forEach(function (l) { var d = parseFloat(l.getAttribute("data-depth")); l.style.transform = "translate3d(" + (x * d * 30) + "px," + (y * d * 22) + "px,0) rotateY(" + (x * d * 7) + "deg) rotateX(" + (-y * d * 5) + "deg)"; });
    });
    stage.addEventListener("pointerleave", function () { layers.forEach(function (l) { l.style.transform = ""; }); });
  }

  // Zoomable screenshots
  doc.addEventListener("click", function (e) {
    var img = e.target.closest && e.target.closest("[data-zoom]"); if (!img) return;
    var box = doc.createElement("div"); box.className = "lightbox"; box.setAttribute("role", "dialog"); box.setAttribute("aria-label", "Screenshot");
    var big = doc.createElement("img"); big.src = img.getAttribute("src"); big.alt = img.getAttribute("alt") || ""; box.appendChild(big);
    var close = function () { box.remove(); doc.removeEventListener("keydown", key); }, key = function (k) { if (k.key === "Escape") close(); };
    box.addEventListener("click", close); doc.addEventListener("keydown", key); doc.body.appendChild(box);
  });
})();
