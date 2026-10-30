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
