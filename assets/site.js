// Shared page chrome: app bar, drawer, light/dark toggle, scroll reveals, lightbox.
(function () {
  var PAGES = [
    ["index.html", "Home", "home"],
    ["features.html", "Features", "auto_awesome"],
    ["themes.html", "Themes", "palette"],
    ["install.html", "Install", "download"],
    ["manager.html", "Manager", "system_update"],
    ["gallery.html", "Gallery", "photo_library"],
    ["faq.html", "FAQ", "help"],
    ["changelog.html", "Changelog", "history"],
  ];
  var LOGO = '<svg viewBox="0 0 108 108" aria-hidden="true"><path fill="var(--primary)" d="' + scallop() + '"/><path fill="var(--on-primary)" d="M49 44 L64 54 L49 64 Z"/></svg>';
  var here = location.pathname.split("/").pop() || "index.html";

  function scallop() {
    var d = "";
    for (var i = 0; i <= 240; i++) {
      var a = 2 * Math.PI * i / 240, r = 30 + 2.6 * Math.cos(9 * a);
      d += (i ? " L" : "M") + (54 + r * Math.sin(a)).toFixed(2) + "," + (54 - r * Math.cos(a)).toFixed(2);
    }
    return d + " Z";
  }

  function chrome() {
    var links = PAGES.map(function (p) {
      return '<a href="' + p[0] + '"' + (p[0] === here ? ' aria-current="page"' : "") + ">" + p[1] + "</a>";
    }).join("");
    var items = PAGES.map(function (p) {
      return '<a class="item" href="' + p[0] + '"' + (p[0] === here ? ' aria-current="page"' : "") + '><span class="ms">' + p[2] + "</span>" + p[1] + "</a>";
    }).join("");
    var bar = document.createElement("header");
    bar.className = "appbar";
    bar.innerHTML = '<button class="icon-btn menu-btn" aria-label="Open navigation"><span class="ms">menu</span></button>' +
      '<a class="brand" href="index.html">' + LOGO + "Spatch</a>" +
      '<nav class="nav" aria-label="Pages">' + links + "</nav>" +
      '<button class="icon-btn palette-btn" aria-label="Try a Spatch theme" aria-haspopup="true" aria-expanded="false"><span class="ms">palette</span></button>' +
      '<button class="icon-btn scheme-btn" aria-label="Switch light or dark"><span class="ms">dark_mode</span></button>' +
      '<a class="icon-btn" href="https://github.com/n0thhhing/spatch" aria-label="Source on GitHub"><span class="ms">code</span></a>';
    document.body.prepend(bar);
    var drawer = document.createElement("div");
    drawer.innerHTML = '<div class="drawer-scrim"></div><nav class="drawer" aria-label="Pages"><a class="brand" href="index.html">' + LOGO + "Spatch</a>" + items + "</nav>";
    document.body.append(drawer);
    bar.querySelector(".menu-btn").onclick = function () { document.body.classList.add("drawer-open"); };
    drawer.querySelector(".drawer-scrim").onclick = function () { document.body.classList.remove("drawer-open"); };
    addEventListener("keydown", function (e) { if (e.key === "Escape") { document.body.classList.remove("drawer-open"); closeLightbox(); } });
    addEventListener("scroll", function () { bar.classList.toggle("scrolled", scrollY > 8); }, { passive: true });

    var scheme = bar.querySelector(".scheme-btn");
    function apply(mode) {
      document.documentElement.dataset.scheme = mode;
      scheme.firstChild.textContent = mode === "light" ? "dark_mode" : "light_mode";
    }
    var saved = null;
    try { saved = localStorage.getItem("scheme"); } catch (e) {}
    apply(saved || (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark"));
    scheme.onclick = function () {
      var next = document.documentElement.dataset.scheme === "light" ? "dark" : "light";
      apply(next);
      try { localStorage.setItem("scheme", next); } catch (e) {}
    };
  }

  function footer() {
    var f = document.createElement("footer");
    f.innerHTML = '<div class="wrap cols"><div><a class="brand" href="index.html" style="margin-bottom:14px">' + LOGO + 'Spatch</a>' +
      "<p>An unofficial, cosmetic redesign for Spotify on Android. Not affiliated with or endorsed by Spotify AB. Spotify is a trademark of Spotify AB.</p></div>" +
      '<div><h4>Spatch</h4><ul><li><a href="features.html">Features</a></li><li><a href="themes.html">Themes</a></li><li><a href="install.html">Install</a></li><li><a href="changelog.html">Changelog</a></li></ul></div>' +
      '<div><h4>Project</h4><ul><li><a href="https://github.com/n0thhhing/spatch">Source</a></li><li><a href="https://github.com/n0thhhing/spatch/releases">Releases</a></li><li><a href="faq.html">FAQ</a></li><li><a href="https://github.com/n0thhhing/spatch/blob/main/LICENSE">MIT licence</a></li></ul></div></div>';
    document.body.append(f);
  }

  var box;
  function lightbox() {
    box = document.createElement("div");
    box.className = "lightbox";
    box.innerHTML = '<button class="icon-btn close" aria-label="Close"><span class="ms">close</span></button><img alt="">';
    document.body.append(box);
    box.onclick = closeLightbox;
    document.querySelectorAll("[data-zoom]").forEach(function (el) {
      el.addEventListener("click", function () {
        var img = el.querySelector("img");
        box.querySelector("img").src = img.src;
        box.querySelector("img").alt = img.alt;
        box.classList.add("open");
      });
    });
  }
  function closeLightbox() { if (box) box.classList.remove("open"); }

  function reveal() {
    var seen = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add("in"); seen.unobserve(e.target); } });
    }, { threshold: .12 });
    document.querySelectorAll(".reveal").forEach(function (el) { seen.observe(el); });
  }

  // Spatch's own palettes (surface, raised, highest, text, muted, accent), straight from src/theme.rs.
  var THEMES = {"lavender": ["#14121c", "#1d1a2a", "#2b2640", "#f3eeff", "#bcb3dc", "#d0bcff"], "gruvbox": ["#282828", "#3c3836", "#504945", "#fbf1c7", "#a89984", "#fe8019"], "catppuccin-mocha": ["#1e1e2e", "#313244", "#45475a", "#cdd6f4", "#a6adc8", "#cba6f7"], "rose-pine": ["#191724", "#1f1d2e", "#26233a", "#e0def4", "#908caa", "#c4a7e7"], "nord": ["#2e3440", "#3b4252", "#434c5e", "#eceff4", "#d8dee9", "#88c0d0"], "tokyo-night": ["#1a1b26", "#24283b", "#292e42", "#c0caf5", "#9aa5ce", "#7aa2f7"], "dracula": ["#282a36", "#343746", "#44475a", "#f8f8f2", "#adb5cb", "#bd93f9"], "gruvbox-material-dark": ["#282828", "#32302f", "#45403d", "#ddc7a1", "#a89984", "#a9b665"], "everforest": ["#2d353b", "#3d484d", "#475258", "#d3c6aa", "#949f97", "#a7c080"], "kanagawa": ["#1f1f28", "#2a2a37", "#363646", "#dcd7ba", "#898881", "#7e9cd8"], "catppuccin-macchiato": ["#24273a", "#363a4f", "#494d64", "#cad3f5", "#888ea8", "#c6a0f6"], "catppuccin-frappe": ["#303446", "#414559", "#51576d", "#c6d0f5", "#969cb4", "#ca9ee6"], "rose-pine-moon": ["#2a273f", "#393552", "#44415a", "#e0def4", "#918ea3", "#c4a7e7"], "tokyo-night-storm": ["#24283b", "#292e42", "#3b4261", "#c0caf5", "#898fac", "#7aa2f7"], "solarized": ["#002b36", "#073642", "#0d4452", "#9fabab", "#809196", "#3794d6"], "one": ["#282c34", "#2c313a", "#3e4451", "#abb2bf", "#8f939c", "#61afef"], "spotify": ["#121212", "#1f1f1f", "#2a2a2a", "#ffffff", "#7d7d7d", "#1ed760"], "default": ["#19191b", "#232326", "#2e2e31", "#ececee", "#818187", "#ececed"]};
  function title(name) { return name.split("-").map(function (w) { return w[0].toUpperCase() + w.slice(1); }).join(" ").replace("Rose Pine", "Rosé Pine"); }
  var paletteStyle = document.createElement("style");
  document.head.append(paletteStyle);
  function palette(name) {
    var t = THEMES[name];
    document.documentElement.dataset.palette = t ? name : "";
    if (!t || name === "lavender") { paletteStyle.textContent = ""; return; }
    var mix = function (a, b, p) { return "color-mix(in srgb, " + a + " " + p + "%, " + b + ")"; };
    var s = t[0], raised = t[1], high = t[2], text = t[3], muted = t[4], accent = t[5];
    paletteStyle.textContent = ':root[data-scheme="dark"]{' +
      "--surface:" + s + ";--surface-dim:" + s + ";--surface-low:" + mix(raised, s, 50) + ";--surface-container:" + mix(raised, s, 80) +
      ";--surface-high:" + raised + ";--surface-highest:" + high + ";--on-surface:" + text + ";--on-surface-variant:" + muted +
      ";--outline:" + mix(muted, s, 75) + ";--outline-variant:" + high + ";--primary:" + accent + ";--on-primary:" + s +
      ";--primary-container:" + mix(accent, s, 32) + ";--on-primary-container:" + mix(accent, text, 45) +
      ";--secondary:" + mix(accent, muted, 40) + ";--secondary-container:" + mix(accent, high, 20) + ";--on-secondary-container:" + text +
      ";--tertiary:" + mix(accent, text, 55) + ";--tertiary-container:" + mix(accent, raised, 26) + ";--on-tertiary-container:" + text + "}" +
      ':root[data-scheme="light"]{--primary:' + mix(accent, "#000", 62) + ";--primary-container:" + mix(accent, "#fff", 26) +
      ";--secondary-container:" + mix(accent, "#fff", 16) + ";--tertiary-container:" + mix(accent, "#fff", 20) + "}";
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = s;
  }
  function picker() {
    var btn = document.querySelector(".palette-btn");
    var menu = document.createElement("div");
    menu.className = "palette-menu";
    menu.setAttribute("role", "menu");
    menu.innerHTML = '<p class="palette-title">Try a Spatch theme</p><div class="swatches">' + Object.keys(THEMES).map(function (n) {
      var t = THEMES[n];
      return '<button role="menuitemradio" data-name="' + n + '" title="' + title(n) + '" style="--s:' + t[0] + ";--a:" + t[5] + ";--h:" + t[2] + '">' +
        '<span class="dot"></span><span class="name">' + title(n) + "</span></button>";
    }).join("") + "</div>";
    document.body.append(menu);
    var current = "lavender";
    try { current = localStorage.getItem("palette") || current; } catch (e) {}
    function choose(name, keep) {
      palette(name);
      menu.querySelectorAll("button").forEach(function (b) { b.setAttribute("aria-checked", b.dataset.name === name); });
      if (keep) try { localStorage.setItem("palette", name); } catch (e) {}
    }
    choose(current, false);
    function close() { menu.classList.remove("open"); btn.setAttribute("aria-expanded", "false"); }
    btn.onclick = function (e) {
      e.stopPropagation();
      var r = btn.getBoundingClientRect();
      menu.style.top = r.bottom + 8 + "px";
      menu.style.right = Math.max(8, innerWidth - r.right) + "px";
      var open = menu.classList.toggle("open");
      btn.setAttribute("aria-expanded", open);
      if (open) menu.querySelector('[aria-checked="true"]').focus();
    };
    menu.onclick = function (e) {
      var b = e.target.closest("button");
      if (!b) return;
      e.stopPropagation();
      choose(b.dataset.name, true);
      b.classList.remove("pop"); void b.offsetWidth; b.classList.add("pop");
    };
    addEventListener("click", close);
    addEventListener("keydown", function (e) { if (e.key === "Escape") close(); });
  }

  chrome();
  picker();
  footer();
  lightbox();
  reveal();
})();
