// Анимации главной в духе js/main.js старых страниц: подъём первого экрана,
// заголовки по словам, въезд блоков при прокрутке. Разметку не меняет –
// классы ставятся здесь, поэтому без скрипта всё видно сразу.
(function () {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  if (!("IntersectionObserver" in window)) return;
  document.documentElement.classList.add("motion");

  // 1. Первый экран поднимается по очереди.
  const hero = document.querySelectorAll(".hero-copy > *, .hero-shot");
  hero.forEach((el, i) => {
    el.classList.add("m-rise");
    el.style.setProperty("--d", String(i));
  });

  // 1b. Кино-оболочка старых страниц: шторки кадра, полоса прогресса, пыль, параллакс, подсказка.
  const heroEl = document.querySelector(".hero");
  document.body.insertAdjacentHTML("afterbegin",
    '<div class="m-letterbox top" aria-hidden="true"></div><div class="m-letterbox bottom" aria-hidden="true"></div>' +
    '<div class="m-progress" aria-hidden="true"><span></span></div>');
  const open = () => document.documentElement.classList.add("film-open");
  window.addEventListener("load", () => requestAnimationFrame(open));
  setTimeout(open, 2200);

  const bar = document.querySelector(".m-progress span");
  const copy = document.querySelector(".hero-copy");
  const shot = document.querySelector(".hero-shot");
  let ticking = false;
  const onScroll = () => {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(() => {
      const y = window.scrollY;
      const max = document.documentElement.scrollHeight - window.innerHeight;
      if (max > 0) bar.style.setProperty("--p", String(y / max));
      if (y < window.innerHeight * 1.2) {
        if (copy) {
          copy.style.setProperty("--copy-shift", (y * 0.18).toFixed(1) + "px");
          copy.style.setProperty("--copy-fade", Math.max(0, 1 - y / (window.innerHeight * 0.9)).toFixed(3));
        }
        if (shot) shot.style.setProperty("--shot-shift", (y * -0.08).toFixed(1) + "px");
      }
      ticking = false;
    });
  };
  window.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  if (heroEl) {
    const next = document.querySelector("#app");
    if (next) {
      heroEl.insertAdjacentHTML("beforeend",
        '<button type="button" class="m-hint" aria-label="Прокрутить к экранам приложения"><span>Листайте</span>' +
        '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg></button>');
      heroEl.querySelector(".m-hint").addEventListener("click", () => next.scrollIntoView({ behavior: "smooth" }));
    }

    // Пыль в свете первого экрана – как на старых страницах, только в тонах приложения.
    const canvas = document.createElement("canvas");
    canvas.className = "m-dust";
    canvas.setAttribute("aria-hidden", "true");
    heroEl.prepend(canvas);
    const ctx = canvas.getContext && canvas.getContext("2d");
    if (ctx) {
      const count = window.matchMedia("(max-width: 600px)").matches ? 16 : 42;
      let motes = [], w = 0, h = 0, running = false;
      const resize = () => {
        const dpr = Math.min(window.devicePixelRatio || 1, 2);
        w = heroEl.clientWidth; h = heroEl.clientHeight;
        canvas.width = w * dpr; canvas.height = h * dpr;
        canvas.style.width = w + "px"; canvas.style.height = h + "px";
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      };
      const spawn = () => {
        motes = Array.from({ length: count }, () => ({
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
          ctx.beginPath();
          ctx.arc(m.x, m.y, m.r, 0, Math.PI * 2);
          ctx.fillStyle = "rgba(201, 211, 245, " + (m.a * (0.6 + 0.4 * Math.sin(m.tw))).toFixed(3) + ")";
          ctx.fill();
        }
        requestAnimationFrame(frame);
      };
      resize(); spawn();
      window.addEventListener("resize", resize, { passive: true });
      new IntersectionObserver((entries) => entries.forEach((e) => {
        if (e.isIntersecting && !running) { running = true; frame(); }
        if (!e.isIntersecting) running = false;
      }), { threshold: 0.05 }).observe(heroEl);
    }
  }

  // 2. Заголовки разделов – по словам, когда раздел виден.
  const titles = document.querySelectorAll(".section-title, .ask h2");
  titles.forEach((title) => {
    const words = title.textContent.trim().split(/\s+/);
    title.setAttribute("aria-label", title.textContent.trim());
    title.textContent = "";
    words.forEach((word, i) => {
      const span = document.createElement("span");
      span.className = "title-word";
      span.setAttribute("aria-hidden", "true");
      span.style.setProperty("--w", String(i));
      span.textContent = word;
      title.appendChild(span);
      if (i < words.length - 1) title.appendChild(document.createTextNode(" "));
    });
  });
  const titleIO = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("words-in");
      titleIO.unobserve(entry.target);
    });
  }, { threshold: 0.3 });
  titles.forEach((t) => titleIO.observe(t));

  // 3. Блоки въезжают при прокрутке; соседи в одном списке – с шагом.
  const groups = [
    ".section .kicker", ".section .lead", ".section > .wrap > p",
    ".screens > li", ".journey > li", ".gets > li", ".connect",
    ".rules", ".ask .orb", ".ask p", ".ask .chips", ".ask .btn",
  ];
  const revealIO = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("in");
      revealIO.unobserve(entry.target);
    });
  }, { threshold: 0.15, rootMargin: "0px 0px -8% 0px" });
  groups.forEach((sel) => {
    document.querySelectorAll(sel).forEach((el) => {
      if (el.closest(".hero")) return;
      const siblings = el.parentElement ? [...el.parentElement.children] : [el];
      el.classList.add("m-reveal");
      el.style.setProperty("--i", String(Math.min(siblings.indexOf(el), 6)));
      revealIO.observe(el);
    });
  });
})();
