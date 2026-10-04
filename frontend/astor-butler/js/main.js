/* ============================================================
   Astor Butler — presentation interactions.
   Vanilla JS, no build step. Graceful degradation everywhere:
   - no fine pointer  -> native cursor, no ripples;
   - reduced motion   -> everything visible, no animation;
   - no JS            -> content still readable (CSS keeps
                         .reveal visible via media query fallback
                         and hero opens on scroll).
   ============================================================ */
(function () {
  "use strict";

  const prefersReduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const finePointer = window.matchMedia("(pointer: fine)").matches;

  /* ---------- 1. Theme switch ---------- */
  const themeToggle = document.getElementById("themeToggle");
  const themeText = themeToggle ? themeToggle.querySelector(".theme-toggle-text") : null;
  const storedTheme = window.localStorage ? window.localStorage.getItem("astor-theme") : null;
  const systemLight = window.matchMedia("(prefers-color-scheme: light)").matches;
  const initialTheme = storedTheme || (systemLight ? "light" : "dark");

  function applyTheme(theme) {
    const isLight = theme === "light";
    document.body.classList.toggle("light-theme", isLight);
    if (themeToggle) {
      themeToggle.setAttribute("aria-pressed", String(isLight));
    }
    if (themeText) {
      themeText.textContent = isLight ? "Темная тема" : "Светлая тема";
    }
  }

  applyTheme(initialTheme);

  if (themeToggle) {
    themeToggle.addEventListener("click", () => {
      const nextTheme = document.body.classList.contains("light-theme") ? "dark" : "light";
      applyTheme(nextTheme);
      try {
        window.localStorage.setItem("astor-theme", nextTheme);
      } catch (_) {
        // Storage can be unavailable in private browser modes.
      }
    });
  }

  /* ---------- 2. Key cursor + ripple trail ---------- */
  if (finePointer && !prefersReduced) {
    document.body.classList.add("key-cursor");
    const key = document.getElementById("cursorKey");
    const rippleLayer = document.getElementById("rippleLayer");
    let lastRipple = 0;

    document.addEventListener("mousemove", (e) => {
      key.style.transform =
        "translate(" + (e.clientX - 6) + "px," + (e.clientY - 6) + "px) rotate(-35deg)";
      const now = performance.now();
      if (now - lastRipple > 90) {
        lastRipple = now;
        const r = document.createElement("span");
        r.className = "ripple";
        const size = 40 + Math.random() * 50;
        r.style.width = r.style.height = size + "px";
        r.style.left = e.clientX + "px";
        r.style.top = e.clientY + "px";
        rippleLayer.appendChild(r);
        setTimeout(() => r.remove(), 1500);
      }
    }, { passive: true });

    document.addEventListener("mouseleave", () => { key.style.opacity = "0"; });
    document.addEventListener("mouseenter", () => { key.style.opacity = "1"; });
  }

  /* ---------- 3. Cinema chrome ---------- */
  const hero = document.getElementById("hero");

  // 3a. Letterbox opens once the first frame is ready.
  window.addEventListener("load", () => {
    requestAnimationFrame(() => document.body.classList.add("film-open"));
  });
  // Fallback if load hangs on slow fonts/images.
  setTimeout(() => document.body.classList.add("film-open"), 2200);

  // 3b. Film progress bar + hero parallax, one rAF loop.
  const progressBar = document.getElementById("filmProgressBar");
  const heroBg = document.querySelector(".hero-bg");
  if (!prefersReduced) {
    let ticking = false;
    const onScroll = () => {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => {
        const doc = document.documentElement;
        const max = doc.scrollHeight - window.innerHeight;
        if (progressBar && max > 0) {
          progressBar.style.setProperty("--film-progress", String(window.scrollY / max));
          progressBar.style.transform = "scaleX(" + (window.scrollY / max) + ")";
        }
        if (heroBg && window.scrollY < window.innerHeight * 1.2) {
          heroBg.style.setProperty("--hero-shift", (window.scrollY * 0.18).toFixed(1) + "px");
        }
        ticking = false;
      });
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
  }

  // 3c. Metallic dust drifting in the hero light. Canvas, cheap, pausable.
  const dustCanvas = document.getElementById("heroDust");
  if (dustCanvas && !prefersReduced && dustCanvas.getContext) {
    const ctx = dustCanvas.getContext("2d");
    const isMobile = window.matchMedia("(max-width: 600px)").matches;
    const COUNT = isMobile ? 16 : 42;
    let motes = [];
    let running = false;
    let w = 0, h = 0;

    function dustColor() {
      return getComputedStyle(document.body).getPropertyValue("--dust-rgb").trim() || "214, 222, 230";
    }

    function resize() {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      w = hero.clientWidth; h = hero.clientHeight;
      dustCanvas.width = w * dpr; dustCanvas.height = h * dpr;
      dustCanvas.style.width = w + "px"; dustCanvas.style.height = h + "px";
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    function spawn() {
      motes = Array.from({ length: COUNT }, () => ({
        x: Math.random() * w,
        y: Math.random() * h,
        r: 0.6 + Math.random() * 1.8,
        vx: -0.08 + Math.random() * 0.16,
        vy: -0.12 - Math.random() * 0.18,
        a: 0.08 + Math.random() * 0.3,
        tw: Math.random() * Math.PI * 2,
      }));
    }

    function frame() {
      if (!running) return;
      ctx.clearRect(0, 0, w, h);
      for (const m of motes) {
        m.x += m.vx; m.y += m.vy; m.tw += 0.02;
        if (m.y < -4 || m.x < -4 || m.x > w + 4) {
          m.x = Math.random() * w; m.y = h + 4;
        }
        const alpha = m.a * (0.6 + 0.4 * Math.sin(m.tw));
        ctx.beginPath();
        ctx.arc(m.x, m.y, m.r, 0, Math.PI * 2);
        ctx.fillStyle = "rgba(" + dustColor() + ", " + alpha.toFixed(3) + ")";
        ctx.fill();
      }
      requestAnimationFrame(frame);
    }

    resize(); spawn();
    window.addEventListener("resize", () => { resize(); }, { passive: true });

    if ("IntersectionObserver" in window) {
      new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
          const shouldRun = entry.isIntersecting;
          if (shouldRun && !running) { running = true; frame(); }
          if (!shouldRun) running = false;
        });
      }, { threshold: 0.05 }).observe(hero);
    } else {
      running = true; frame();
    }
  }

  // 3d. Section titles flow in word by word.
  if (!prefersReduced) {
    document.querySelectorAll(".section-title").forEach((title) => {
      const words = title.textContent.trim().split(/\s+/);
      title.textContent = "";
      words.forEach((word, i) => {
        const span = document.createElement("span");
        span.className = "title-word";
        span.style.setProperty("--w", String(i));
        span.textContent = word;
        title.appendChild(span);
        if (i < words.length - 1) title.appendChild(document.createTextNode(" "));
      });
    });
  }

  /* ---------- 3e. Hero scroll hint ---------- */
  const scrollHint = document.getElementById("scrollHint");
  if (scrollHint) {
    scrollHint.addEventListener("click", () => {
      document.getElementById("what").scrollIntoView({ behavior: prefersReduced ? "auto" : "smooth" });
    });
  }

  /* ---------- 4. Scroll reveal ---------- */
  const revealEls = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && !prefersReduced) {
    const io = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("in");
          io.unobserve(entry.target);
        }
      });
    }, { threshold: 0.15, rootMargin: "0px 0px -8% 0px" });
    revealEls.forEach((el) => io.observe(el));
  } else {
    revealEls.forEach((el) => el.classList.add("in"));
  }

  /* ---------- 5. Active section (cinematic titles) ---------- */
  const sections = document.querySelectorAll("main .section");

  if ("IntersectionObserver" in window && !prefersReduced) {
    const activeIO = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        sections.forEach((section) => section.classList.remove("active"));
        entry.target.classList.add("active");
      });
    }, { threshold: 0.05, rootMargin: "-24% 0px -34% 0px" });
    sections.forEach((section) => activeIO.observe(section));
  } else {
    sections.forEach((section) => section.classList.add("active"));
  }

  /* ---------- 6. Chat widget reveal + logic ---------- */
  const chatWidget = document.getElementById("chatWidget");
  const chatFab = document.getElementById("chatFab");
  const chatCloseBtn = document.getElementById("chatCloseBtn");
  const chatForm = document.getElementById("chatForm");
  const chatInput = document.getElementById("chatInput");
  const chatLog = document.getElementById("chatLog");
  const openChatBtn = document.getElementById("openChatBtn");
  let greeted = false;

  if (!chatWidget || !chatFab || !chatCloseBtn || !chatForm || !chatInput || !chatLog) {
    return;
  }

  function showWidget() {
    chatWidget.setAttribute("aria-hidden", "false");
  }

  // Reveal FAB when the final section approaches.
  if ("IntersectionObserver" in window) {
    const finalIO = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) showWidget();
      });
    }, { threshold: 0.2 });
    const finalSection = document.getElementById("final");
    if (finalSection) finalIO.observe(finalSection);
  } else {
    showWidget();
  }

  function addBubble(text, who) {
    const b = document.createElement("div");
    b.className = "bubble " + (who === "guest" ? "guest" : "butler-msg");
    b.textContent = text;
    chatLog.appendChild(b);
    chatLog.scrollTop = chatLog.scrollHeight;
    return b;
  }

  function addTyping() {
    const t = document.createElement("div");
    t.className = "bubble butler-msg chat-typing";
    t.innerHTML = "<span></span><span></span><span></span>";
    chatLog.appendChild(t);
    chatLog.scrollTop = chatLog.scrollHeight;
    return t;
  }

  function openChat() {
    showWidget();
    chatWidget.classList.add("open");
    if (!greeted) {
      greeted = true;
      addBubble("Здравствуйте. Расскажите о заведении или задайте вопрос о внедрении Astor.", "butler");
    }
    chatInput.focus();
  }

  function closeChat() {
    chatWidget.classList.remove("open");
    chatFab.focus();
  }

  chatFab.addEventListener("click", openChat);
  chatCloseBtn.addEventListener("click", closeChat);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && chatWidget.classList.contains("open")) closeChat();
  });

  if (openChatBtn) openChatBtn.addEventListener("click", openChat);

  chatForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const text = chatInput.value.trim();
    if (!text || chatForm.dataset.pending === "true") return;
    chatForm.dataset.pending = "true";
    const sendButton = chatForm.querySelector("button[type=submit]");
    if (sendButton) sendButton.disabled = true;
    chatInput.value = "";
    addBubble(text, "guest");
    const typing = addTyping();
    try {
      const reply = await window.AstorChat.submitMessage({ text });
      typing.remove();
      addBubble(reply.text, "butler");
    } catch (err) {
      typing.remove();
      addBubble("Сейчас не удалось получить ответ. Повторите сообщение чуть позже или откройте Telegram-демо.", "butler");
    } finally {
      chatForm.dataset.pending = "false";
      if (sendButton) sendButton.disabled = false;
    }
  });
})();
