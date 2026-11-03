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

  chrome();
  footer();
  lightbox();
  reveal();
})();
