// Ленты экранов (ul.screens) на телефоне и планшете: под каждой – стрелки и точки.
// Точки показывают, сколько экранов и какой сейчас по центру; стрелки и точки листают ленту.
// Разметку страницы не меняет: без скрипта лента так же листается пальцем, просто без точек.
(function () {
  const rails = document.querySelectorAll(".screens");
  if (!rails.length) return;
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  const arrow = d => '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="' + d + '"/></svg>';

  rails.forEach((rail) => {
    const items = [...rail.children];
    if (items.length < 2) return;
    const name = rail.getAttribute("aria-label") || "Экраны";
    const titles = items.map((li, i) => (li.querySelector("h3")?.textContent || String(i + 1)).trim());

    const pager = document.createElement("div");
    pager.className = "rail-pager";
    pager.setAttribute("role", "group");
    pager.setAttribute("aria-label", name + ": листать");
    pager.innerHTML =
      '<button type="button" class="rail-btn glass" data-step="-1" aria-label="Предыдущий экран">' + arrow("m14.5 6-6 6 6 6") + "</button>" +
      '<span class="rail-dots">' +
      titles.map((t, i) => '<button type="button" data-to="' + i + '" aria-label="Экран ' + (i + 1) + " из " + items.length + ": " + t + '"></button>').join("") +
      "</span>" +
      '<button type="button" class="rail-btn glass" data-step="1" aria-label="Следующий экран">' + arrow("m9.5 6 6 6-6 6") + "</button>" +
      '<span class="rail-live" aria-live="polite"></span>';
    rail.after(pager);
    rail.classList.add("has-pager");

    const dots = [...pager.querySelectorAll("[data-to]")];
    const prev = pager.querySelector('[data-step="-1"]');
    const next = pager.querySelector('[data-step="1"]');
    const live = pager.querySelector(".rail-live");
    let current = -1;

    // Какой экран ближе всех к центру ленты. Считаем по рамкам на экране, а не по offsetLeft:
    // у ul нет position, и offsetLeft отсчитывался бы от страницы.
    const activeIndex = () => {
      const box = rail.getBoundingClientRect();
      const mid = box.left + box.width / 2;
      let best = 0, bestDist = Infinity;
      items.forEach((li, i) => {
        const r = li.getBoundingClientRect();
        const d = Math.abs(r.left + r.width / 2 - mid);
        if (d < bestDist) { best = i; bestDist = d; }
      });
      return best;
    };

    const update = () => {
      const i = activeIndex();
      if (i === current) return;
      const first = current === -1;
      current = i;
      dots.forEach((dot, k) => dot.setAttribute("aria-current", k === i ? "true" : "false"));
      items.forEach((li, k) => li.classList.toggle("is-active", k === i));
      prev.disabled = i === 0;
      next.disabled = i === items.length - 1;
      if (!first) live.textContent = "Экран " + (i + 1) + " из " + items.length + ": " + titles[i];
    };

    const go = (i) => {
      const k = Math.max(0, Math.min(items.length - 1, i));
      const box = rail.getBoundingClientRect();
      const r = items[k].getBoundingClientRect();
      const left = rail.scrollLeft + (r.left + r.width / 2) - (box.left + box.width / 2);
      rail.scrollTo({ left, behavior: reduce.matches ? "auto" : "smooth" });
    };

    pager.addEventListener("click", (e) => {
      const btn = e.target.closest("button");
      if (!btn || btn.disabled) return;
      if (btn.dataset.to !== undefined) go(Number(btn.dataset.to));
      else go(activeIndex() + Number(btn.dataset.step));
    });

    let ticking = false;
    rail.addEventListener("scroll", () => {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => { ticking = false; update(); });
    }, { passive: true });

    // Пейджер нужен, только когда ленте есть куда листать (на десктопе его прячет CSS).
    const fit = () => {
      pager.hidden = rail.scrollWidth <= rail.clientWidth + 1;
      current = -1;
      update();
    };
    if ("ResizeObserver" in window) new ResizeObserver(fit).observe(rail);
    else window.addEventListener("resize", fit, { passive: true });
    fit();
  });
})();
