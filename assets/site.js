// Shared site interactions: mobile navigation drawer, header scroll state,
// lightbox for [data-zoom], and fallback chrome injection for secondary pages.
(function () {
  var PAGES = [
    ["index.html", "Home"],
    ["features.html", "Features"],
    ["themes.html", "Themes"],
    ["install.html", "Install"],
    ["manager.html", "Manager"],
    ["gallery.html", "Gallery"],
    ["faq.html", "FAQ"],
    ["changelog.html", "Changelog"]
  ];

  var here = location.pathname.split("/").pop() || "index.html";

  function ensureChrome() {
    if (!document.querySelector(".site-header")) {
      var links = PAGES.map(function (p) {
        return '<a href="' + p[0] + '"' + (p[0] === here ? ' aria-current="page"' : "") + ">" + p[1] + "</a>";
      }).join("");

      var header = document.createElement("header");
      header.className = "site-header";
      header.innerHTML =
        '<div class="wrap nav-wrap">' +
          '<a class="brand" href="index.html"><img src="assets/icon.svg" alt="" width="34" height="34"><span>Spatch</span></a>' +
          '<nav class="site-nav" aria-label="Primary">' + links + "</nav>" +
          '<button class="nav-toggle" type="button" aria-label="Open navigation" aria-expanded="false" aria-controls="mobile-drawer">' +
            '<svg width="20" height="20" viewBox="0 0 20 20" aria-hidden="true"><path fill="currentColor" d="M3 5h14v1.8H3V5zm0 4.1h14v1.8H3V9.1zm0 4.1h14V15H3v-1.8z"/></svg>' +
          "</button>" +
        "</div>";
      document.body.prepend(header);

      var backdrop = document.createElement("div");
      backdrop.className = "drawer-backdrop";
      var drawer = document.createElement("nav");
      drawer.id = "mobile-drawer";
      drawer.className = "mobile-drawer";
      drawer.setAttribute("aria-label", "Mobile navigation");
      drawer.innerHTML =
        '<div class="drawer-head">' +
          '<a class="brand" href="index.html"><img src="assets/icon.svg" alt="" width="34" height="34"><span>Spatch</span></a>' +
          '<button class="nav-toggle drawer-close" type="button" aria-label="Close navigation">' +
            '<svg width="20" height="20" viewBox="0 0 20 20" aria-hidden="true"><path fill="currentColor" d="M5.3 4.3 10 9l4.7-4.7 1 1L11 10l4.7 4.7-1 1L10 11l-4.7 4.7-1-1L9 10 4.3 5.3l1-1z"/></svg>' +
          "</button>" +
        "</div>" +
        links;
      document.body.append(backdrop, drawer);
    }

    if (!document.querySelector(".site-footer")) {
      var footer = document.createElement("footer");
      footer.className = "site-footer";
      footer.innerHTML =
        '<div class="wrap footer-grid">' +
          '<div>' +
            '<a class="brand" href="index.html"><img src="assets/icon.svg" alt="" width="34" height="34"><span>Spatch</span></a>' +
            '<p class="muted" style="margin-top:14px">An unofficial, cosmetic redesign for Spotify on Android. Not affiliated with or endorsed by Spotify AB. Spotify is a trademark of Spotify AB.</p>' +
          "</div>" +
          "<div><h4>Spatch</h4><ul>" +
            '<li><a href="features.html">Features</a></li>' +
            '<li><a href="themes.html">Themes</a></li>' +
            '<li><a href="install.html">Install</a></li>' +
            '<li><a href="manager.html">Manager</a></li>' +
          "</ul></div>" +
          "<div><h4>Project</h4><ul>" +
            '<li><a href="gallery.html">Gallery</a></li>' +
            '<li><a href="faq.html">FAQ</a></li>' +
            '<li><a href="changelog.html">Changelog</a></li>' +
            '<li><a href="https://github.com/n0thhhing/spatch">GitHub</a></li>' +
          "</ul></div>" +
        "</div>";
      document.body.append(footer);
    }
  }

  function setupNavigation() {
    var header = document.querySelector(".site-header");
    var toggle = document.querySelector(".nav-toggle");
    var closeBtn = document.querySelector(".drawer-close");
    var backdrop = document.querySelector(".drawer-backdrop");
    var drawer = document.querySelector(".mobile-drawer");

    function setOpen(open) {
      document.body.classList.toggle("drawer-open", open);
      if (toggle) toggle.setAttribute("aria-expanded", open ? "true" : "false");
      if (open && closeBtn) closeBtn.focus();
    }

    if (toggle) toggle.addEventListener("click", function () { setOpen(true); });
    if (closeBtn) closeBtn.addEventListener("click", function () { setOpen(false); });
    if (backdrop) backdrop.addEventListener("click", function () { setOpen(false); });
    if (drawer) {
      drawer.querySelectorAll("a").forEach(function (a) {
        a.addEventListener("click", function () { setOpen(false); });
      });
    }

    if (header) {
      var onScroll = function () {
        header.classList.toggle("is-scrolled", window.scrollY > 8);
      };
      onScroll();
      window.addEventListener("scroll", onScroll, { passive: true });
    }

    window.addEventListener("keydown", function (e) {
      if (e.key === "Escape") {
        setOpen(false);
        closeLightbox();
      }
    });
  }

  var lightboxEl;
  function setupLightbox() {
    var zoomables = document.querySelectorAll("[data-zoom]");
    if (!zoomables.length) return;
    lightboxEl = document.createElement("div");
    lightboxEl.className = "lightbox";
    lightboxEl.innerHTML = '<button class="close" type="button" aria-label="Close">&times;</button><img alt="">';
    document.body.append(lightboxEl);
    lightboxEl.addEventListener("click", closeLightbox);
    zoomables.forEach(function (el) {
      el.addEventListener("click", function () {
        var img = el.querySelector("img");
        if (!img) return;
        var target = lightboxEl.querySelector("img");
        target.src = img.src;
        target.alt = img.alt || "";
        lightboxEl.classList.add("open");
      });
    });
  }

  function closeLightbox() {
    if (lightboxEl) lightboxEl.classList.remove("open");
  }

  ensureChrome();
  setupNavigation();
  setupLightbox();
})();
