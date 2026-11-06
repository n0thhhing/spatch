// The jelly navigation pill from Spatch: two edges on springs, the leading one quicker, so the pill
// stretches towards the chosen tab and settles with a wobble. Same constants as the manager's JellyBar.
(function () {
  var LEAD = 700, TRAIL = 240, ZETA = 0.55;
  var calm = matchMedia("(prefers-reduced-motion: reduce)");
  document.querySelectorAll(".jelly-bar").forEach(function (bar) {
    var pill = bar.querySelector(".jelly-pill"), tabs = [].slice.call(bar.querySelectorAll("button"));
    var l = 0, r = 0, lv = 0, rv = 0, lt = 0, rt = 0, last = 0, running = false, dragging = false, downX = 0, selected = 0;
    function slot(i) {
      var b = tabs[i].getBoundingClientRect(), o = bar.getBoundingClientRect(), w = Math.min(64, b.width - 8);
      var c = b.left - o.left + b.width / 2;
      return [c - w / 2, c + w / 2];
    }
    function step(x, v, to, k, dt) { var c = 2 * ZETA * Math.sqrt(k); v += (k * (to - x) - c * v) * dt; return [x + v * dt, v]; }
    function draw() {
      var rest = rt - lt, width = Math.max(r - l, 1), squash = Math.min(1, Math.sqrt(rest / width));
      pill.style.transform = "translateX(" + l + "px) scaleY(" + (0.75 + 0.25 * squash) + ")";
      pill.style.width = width + "px";
    }
    function frame(now) {
      var dt = last ? Math.min(0.032, (now - last) / 1000) : 0.016;
      last = now;
      var right = rt + lt > r + l, a = step(l, lv, lt, right ? TRAIL : LEAD, dt), b = step(r, rv, rt, right ? LEAD : TRAIL, dt);
      l = a[0]; lv = a[1]; r = b[0]; rv = b[1];
      draw();
      if (Math.abs(lv) + Math.abs(rv) > 1 || Math.abs(l - lt) + Math.abs(r - rt) > 0.5) requestAnimationFrame(frame);
      else { l = lt; r = rt; draw(); running = false; last = 0; }
    }
    function go(to, animate) {
      lt = to[0]; rt = to[1];
      if (!animate || calm.matches) { l = lt; r = rt; lv = rv = 0; draw(); return; }
      if (!running) { running = true; requestAnimationFrame(frame); }
    }
    function select(i, animate) {
      selected = i;
      tabs.forEach(function (t, j) {
        t.setAttribute("aria-selected", j === i);
        t.querySelector(".ms").classList.toggle("fill", j === i);
        if (j === i && animate && !calm.matches) { t.classList.remove("pop"); void t.offsetWidth; t.classList.add("pop"); }
      });
      go(slot(i), animate);
    }
    tabs.forEach(function (t, i) { t.addEventListener("click", function () { if (!dragging) select(i, true); }); });
    bar.addEventListener("pointerdown", function (e) { downX = e.clientX; dragging = false; bar.setPointerCapture(e.pointerId); });
    bar.addEventListener("pointermove", function (e) {
      if (!bar.hasPointerCapture(e.pointerId)) return;
      if (!dragging && Math.abs(e.clientX - downX) < 8) return;
      dragging = true;
      var o = bar.getBoundingClientRect(), s = slot(selected), half = (s[1] - s[0]) / 2;
      var x = Math.max(half, Math.min(o.width - half, e.clientX - o.left));
      go([x - half, x + half], true);
    });
    bar.addEventListener("pointerup", function (e) {
      if (!dragging) return;
      var o = bar.getBoundingClientRect();
      select(Math.max(0, Math.min(tabs.length - 1, Math.floor((e.clientX - o.left) / o.width * tabs.length))), true);
      setTimeout(function () { dragging = false; }, 0);
    });
    bar.addEventListener("keydown", function (e) {
      var d = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
      if (!d) return;
      var i = (selected + d + tabs.length) % tabs.length;
      select(i, true);
      tabs[i].focus();
    });
    addEventListener("resize", function () { go(slot(selected), false); });
    select(0, false);
  });
})();
