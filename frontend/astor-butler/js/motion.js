// Анимации главной в духе js/main.js старых страниц: подъём первого экрана,
// заголовки по словам, въезд блоков при прокрутке. Разметку не меняет –
// классы ставятся здесь, поэтому без скрипта всё видно сразу.
//
// Плавность при медленной прокрутке (особенно iPhone / Safari):
// – анимируются только opacity и transform, без filter: blur();
// – при прокрутке ничего не пересчитывается через CSS-переменные: полоса прогресса
//   идёт от scroll-timeline (или пишет transform напрямую), параллакс и «пыль» –
//   только на десктопе с мышью;
// – will-change ставится лишь на время въезда блока и снимается после;
// – бесконечные анимации (телефон, шарик Astor) стоят, пока их не видно.
(function () {
  const mq = (q) => window.matchMedia(q);
  if (mq("(prefers-reduced-motion: reduce)").matches) return;
  if (!("IntersectionObserver" in window)) return;
  const root = document.documentElement;
  root.classList.add("motion");

  // «Богатый» режим: широкий экран и точный указатель. На телефонах прокрутка
  // идёт рывками инерции, и всё, что привязано к scroll, там только мешает.
  const fineMq = mq("(hover: hover) and (pointer: fine)");
  const wideMq = mq("(min-width: 960px)");
  const isRich = () => fineMq.matches && wideMq.matches;
  const syncRich = () => root.classList.toggle("m-rich", isRich());
  syncRich();

  // 1. Первый экран поднимается по очереди. После въезда класс снимается,
  //    чтобы заполнение анимации не держало transform и слой.
  const hero = document.querySelectorAll(".hero-copy > *, .hero-shot");
  hero.forEach((el, i) => {
    el.classList.add("m-rise");
    el.style.setProperty("--d", String(i));
    el.addEventListener("animationend", function done(e) {
      if (e.target !== el || e.animationName !== "m-rise") return;
      el.classList.remove("m-rise");
      el.removeEventListener("animationend", done);
    });
  });

  // 1b. Кино-оболочка старых страниц: шторки кадра, полоса прогресса, пыль, параллакс, подсказка.
  const heroEl = document.querySelector(".hero");
  document.body.insertAdjacentHTML("afterbegin",
    '<div class="m-letterbox top" aria-hidden="true"></div><div class="m-letterbox bottom" aria-hidden="true"></div>' +
    '<div class="m-progress" aria-hidden="true"><span></span></div>');
  const open = () => root.classList.add("film-open");
  window.addEventListener("load", () => requestAnimationFrame(open));
  setTimeout(open, 2200);

  // Полоса прогресса: где есть scroll-timeline (Chrome, Safari 26+), её двигает
  // сам браузер без скрипта; иначе пишем transform напрямую и только при изменении.
  const bar = document.querySelector(".m-progress span");
  const cssTimeline = !!(window.CSS && CSS.supports && CSS.supports("animation-timeline: scroll()"));
  if (cssTimeline) root.classList.add("m-timeline");

  const copy = document.querySelector(".hero-copy");
  const shot = document.querySelector(".hero-shot");
  let maxScroll = 1, vh = window.innerHeight, lastP = -1, lastY = -1, heroOn = true;
  const measure = () => {
    vh = window.innerHeight;
    maxScroll = Math.max(1, root.scrollHeight - vh);
  };
  const clearParallax = () => {
    if (copy) { copy.style.transform = ""; copy.style.opacity = ""; }
    if (shot) shot.style.transform = "";
    lastY = -1;
  };
  let ticking = false;
  const update = () => {
    ticking = false;
    const y = window.scrollY;
    if (!cssTimeline && bar) {
      const p = Math.min(1, Math.max(0, y / maxScroll));
      if (Math.abs(p - lastP) > 0.0005) { bar.style.transform = "scaleX(" + p.toFixed(4) + ")"; lastP = p; }
    }
    if (isRich() && heroOn && y !== lastY && y < vh * 1.2) {
      lastY = y;
      if (copy) {
        copy.style.transform = "translate3d(0," + (y * 0.18).toFixed(1) + "px,0)";
        copy.style.opacity = Math.max(0, 1 - y / (vh * 0.9)).toFixed(3);
      }
      if (shot) shot.style.transform = "translate3d(0," + (y * -0.08).toFixed(1) + "px,0)";
    }
  };
  const onScroll = () => {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(update);
  };
  const needScroll = () => !cssTimeline || isRich();
  let listening = false;
  const syncScroll = () => {
    const want = needScroll();
    if (want && !listening) { window.addEventListener("scroll", onScroll, { passive: true }); listening = true; onScroll(); }
    if (!want && listening) { window.removeEventListener("scroll", onScroll); listening = false; }
    if (!isRich()) clearParallax();
  };
  measure();
  syncScroll();
  let resizeTimer = 0;
  window.addEventListener("resize", () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { measure(); onScroll(); }, 120);
  }, { passive: true });
  window.addEventListener("load", () => { measure(); onScroll(); });
  const onMode = () => { syncRich(); syncScroll(); dust.sync(); };
  [fineMq, wideMq].forEach((m) => (m.addEventListener ? m.addEventListener("change", onMode) : m.addListener(onMode)));

  // Пыль в свете первого экрана – как на старых страницах, только в тонах приложения.
  // Только на десктопе с мышью; стоит, когда первый экран не виден или вкладка скрыта.
  const dust = (() => {
    const api = { sync() {}, visible: false };
    if (!heroEl) return api;
    let canvas = null, ctx = null, motes = [], w = 0, h = 0, running = false;
    const resize = () => {
      if (!canvas) return;
      const dpr = Math.min(window.devicePixelRatio || 1, 1.5);
      w = heroEl.clientWidth; h = heroEl.clientHeight;
      canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
      canvas.style.width = w + "px"; canvas.style.height = h + "px";
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.fillStyle = "rgb(201, 211, 245)";
    };
    const spawn = () => {
      motes = Array.from({ length: 36 }, () => ({
        x: Math.random() * w, y: Math.random() * h, r: 0.6 + Math.random() * 1.8,
        vx: -0.08 + Math.random() * 0.16, vy: -0.12 - Math.random() * 0.18,
        a: 0.08 + Math.random() * 0.3, tw: Math.random() * Math.PI * 2,
      }));
    };
    const frame = () => {
      if (!running) return;
      ctx.clearRect(0, 0, w, h);
      for (const m of motes) {
        m.x += m.vx; m.y += m.vy; m.tw += 0.02;
        if (m.y < -4 || m.x < -4 || m.x > w + 4) { m.x = Math.random() * w; m.y = h + 4; }
        ctx.globalAlpha = m.a * (0.6 + 0.4 * Math.sin(m.tw));
        ctx.beginPath();
        ctx.arc(m.x, m.y, m.r, 0, Math.PI * 2);
        ctx.fill();
      }
      requestAnimationFrame(frame);
    };
    api.sync = () => {
      const want = isRich() && api.visible && !document.hidden;
      if (want && !canvas) {
        canvas = document.createElement("canvas");
        canvas.className = "m-dust";
        canvas.setAttribute("aria-hidden", "true");
        ctx = canvas.getContext && canvas.getContext("2d");
        if (!ctx) { canvas = null; return; }
        heroEl.prepend(canvas);
        resize(); spawn();
        window.addEventListener("resize", () => { if (canvas) resize(); }, { passive: true });
      }
      if (canvas) canvas.hidden = !isRich();
      if (want && !running && canvas) { running = true; requestAnimationFrame(frame); }
      if (!want) running = false;
    };
    document.addEventListener("visibilitychange", () => api.sync());
    return api;
  })();

  if (heroEl) {
    const next = document.querySelector("#app");
    if (next) {
      heroEl.insertAdjacentHTML("beforeend",
        '<button type="button" class="m-hint" aria-label="Прокрутить к экранам приложения"><span>Листайте</span>' +
        '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg></button>');
      heroEl.querySelector(".m-hint").addEventListener("click", () => next.scrollIntoView({ behavior: "smooth" }));
    }
    // Первый экран ушёл из вида – стоят пыль, парение телефона и параллакс.
    new IntersectionObserver((entries) => entries.forEach((e) => {
      heroOn = e.isIntersecting;
      heroEl.classList.toggle("m-off", !heroOn);
      dust.visible = heroOn;
      dust.sync();
    }), { threshold: 0 }).observe(heroEl);
  }

  // Шарик Astor крутится бесконечно – пока его не видно, анимации на паузе.
  const orbIO = new IntersectionObserver((entries) => entries.forEach((e) => {
    e.target.classList.toggle("m-off", !e.isIntersecting);
  }), { rootMargin: "120px 0px" });
  document.querySelectorAll(".orb").forEach((orb) => { orb.classList.add("m-off"); orbIO.observe(orb); });

  // 2. Заголовки разделов – по словам. Делим только по обычным пробелам:
  //    неразрывные (&nbsp;) остаются внутри слова, переносы как в разметке.
  const titles = document.querySelectorAll(".section-title, .ask h2");
  titles.forEach((title) => {
    const text = title.textContent.trim();
    const words = text.split(/[ \t\r\n]+/);
    title.setAttribute("aria-label", text);
    title.textContent = "";
    words.forEach((word, i) => {
      const span = document.createElement("span");
      span.className = "title-word";
      span.setAttribute("aria-hidden", "true");
      span.style.setProperty("--w", String(Math.min(i, 8)));
      span.textContent = word;
      title.appendChild(span);
      if (i < words.length - 1) title.appendChild(document.createTextNode(" "));
    });
    title.classList.add("m-words");
  });

  // 3. Блоки въезжают при прокрутке. Кто показался в одном кадре – идут с небольшим шагом,
  //    одиночный блок – сразу. Срабатывает чуть раньше, чем блок доходит до низа экрана.
  const STEP_MS = 70, MAX_STEPS = 4, RUN_MS = 800;
  const reveal = (el, k) => {
    if (el.classList.contains("in")) return;
    const delay = Math.min(k, MAX_STEPS) * STEP_MS;
    el.style.transitionDelay = delay ? delay + "ms" : "";
    el.classList.add("in");
    // will-change живёт только на время въезда.
    setTimeout(() => {
      el.classList.add("m-done");
      el.style.transitionDelay = "";
    }, RUN_MS + delay + 80);
  };
  const isRail = (ul) => ul.scrollWidth > ul.clientWidth + 4;
  const io = new IntersectionObserver((entries) => {
    let k = 0;
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      const el = entry.target;
      if (el.classList.contains("m-words")) {
        el.classList.add("words-in");
        io.unobserve(el);
      } else if (el.classList.contains("m-rail")) {
        // Лента экранов, листается вбок: карточки за краем сами не пересекут экран,
        // поэтому показываем всю ленту разом, как только она появилась.
        if (!isRail(el)) return;
        [...el.children].forEach((li) => { if (li.classList.contains("m-reveal")) { reveal(li, k++); io.unobserve(li); } });
        io.unobserve(el);
      } else {
        reveal(el, k++);
        io.unobserve(el);
      }
    });
  }, { threshold: 0, rootMargin: "0px 0px -6% 0px" });

  titles.forEach((t) => io.observe(t));
  const groups = [
    ".section .kicker", ".section .lead", ".section > .wrap > p",
    ".screens > li", ".journey > li", ".gets > li", ".connect",
    ".rules", ".ask .orb", ".ask p", ".ask .chips", ".ask .btn",
  ];
  groups.forEach((sel) => {
    document.querySelectorAll(sel).forEach((el) => {
      if (el.closest(".hero") || el.classList.contains("m-reveal")) return;
      el.classList.add("m-reveal");
      io.observe(el);
    });
  });
  document.querySelectorAll(".screens").forEach((ul) => { ul.classList.add("m-rail"); io.observe(ul); });
})();
