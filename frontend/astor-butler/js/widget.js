/* ============================================================
   Astor — widget transport layer (Butler and Concierge).

   BACKEND HOOKUP (see docs/architecture/WEB_CHANNEL_ADAPTER.md):
   1. Before this script, a page may set window.AstorChatConfig:
        <script>window.AstorChatConfig = { product: "concierge" };</script>
      product "butler"    → POST /api/astor/messages     (Astor Butler, guest FSM)
      product "concierge" → POST /api/concierge/messages (Astor Concierge, booking flow)
      endpoint: null      → no network, friendly local stub replies.
   2. submitMessage({ text }) POSTs the web channel contract:
        { channel: "WEB", text, payload: { sessionId, site, pageContext, sentAt,
                                           action?, contactPhone?, locale } }
      and resolves { text, quickReplies, requestContact, raw }.
   3. Quick replies (web keyboard) are rendered under the last butler bubble
      by this file; pressing one submits #chatForm so main.js keeps owning
      the chat log, typing indicator and error handling.
   ============================================================ */

(function () {
  "use strict";

  const DEFAULTS = {
    product: "butler",
    endpoint: undefined,
    endpoints: {
      butler: "/api/astor/messages",
      concierge: "/api/concierge/messages",
    },
    channel: "WEB",
    site: undefined,
    sites: {
      butler: "astor-butler-commercial",
      concierge: "astor-concierge",
    },
    pageContext: "commercial_landing",
    timeoutMs: 25000,
    maxVisibleQuickReplies: 8,
  };

  const page = window.AstorChatConfig || {};
  const config = Object.assign({}, DEFAULTS, page);
  config.endpoints = Object.assign({}, DEFAULTS.endpoints, page.endpoints || {});
  config.sites = Object.assign({}, DEFAULTS.sites, page.sites || {});
  config.product = config.product === "concierge" ? "concierge" : "butler";
  if (config.endpoint === undefined) config.endpoint = config.endpoints[config.product];
  if (config.site === undefined) config.site = config.sites[config.product];
  window.AstorChatConfig = config;

  /* ---------- session ---------- */

  const SESSION_KEY = "astor.chat.session." + config.product;

  function newSessionId() {
    return "web-" + Math.random().toString(36).slice(2) + "-" + Date.now().toString(36);
  }

  function loadSessionId() {
    try {
      const stored = window.sessionStorage.getItem(SESSION_KEY);
      if (stored && /^[A-Za-z0-9][A-Za-z0-9._:-]{5,79}$/.test(stored)) return stored;
    } catch (_) { /* storage blocked: a per-page-load session is fine */ }
    return newSessionId();
  }

  let sessionId = loadSessionId();

  function rememberSession(id) {
    sessionId = id;
    try { window.sessionStorage.setItem(SESSION_KEY, id); } catch (_) { /* ignore */ }
  }
  rememberSession(sessionId);

  /* ---------- state shared with the quick-reply layer ---------- */

  const chatForm = document.getElementById("chatForm");
  const chatInput = document.getElementById("chatInput");
  const chatLog = document.getElementById("chatLog");
  const defaultPlaceholder = chatInput ? chatInput.getAttribute("placeholder") || "" : "";

  let pendingAction = null; // { label, value } of the quick reply the guest just pressed
  let contactMode = false;  // the backend asked for a phone number
  let lastQuickReplies = [];

  const PHONE = /^\+?[0-9][0-9 ()-]{6,24}$/;

  /* ---------- stub mode ---------- */

  const stubReplies = {
    butler: [
      { text: "Добрый вечер. Я — Astor Butler, цифровой дворецкий. Сейчас я в демонстрационном режиме, но уже скоро смогу держать вашу бронь по-настоящему.", quickReplies: ["Бронь стола", "Меню кухни", "Афиша"] },
      { text: "Записал. Когда меня подключат к ресторану, я передам это хостес вместе с контекстом — а пока просто рад знакомству.", quickReplies: ["Связаться с командой", "Главное меню"] },
      { text: "Прекрасный запрос. В боевом режиме я бы уточнил: вам удобнее у окна или в тихой зоне?", quickReplies: ["У окна", "В тихой зоне"] },
      { text: "Понял вас. Настоящие сценарии — бронь, меню, пожелания — уже работают в Telegram. Здесь я пока показываю манеры.", quickReplies: [] },
    ],
    concierge: [
      { text: "Здравствуйте! Я Astor Concierge — помогу выбрать место и оставить заявку на столик. Сейчас я в демонстрационном режиме.", quickReplies: ["Куда пойти", "Забронировать стол", "Бизнес-ланч"] },
      { text: "Записал. В боевом режиме я бы уточнил день, время и число гостей, а затем передал заявку ресторану.", quickReplies: ["Сегодня", "Завтра"] },
      { text: "Бронь подтверждает сам ресторан, а я сообщу его ответ — здесь пока только манеры.", quickReplies: [] },
    ],
  };
  let stubIndex = 0;

  /* ---------- helpers ---------- */

  function normalizeQuickReplies(data) {
    const list = Array.isArray(data.quickReplies) ? data.quickReplies.slice() : [];
    if (Array.isArray(data.messages)) {
      data.messages.forEach((m) => {
        if (Array.isArray(m.quickReplies)) m.quickReplies.forEach((q) => list.push(q));
      });
    }
    const seen = new Set();
    return list
      .map((q) => (typeof q === "string" ? { kind: "text", text: q, value: q } : q))
      .filter((q) => q && typeof q.text === "string" && q.text.trim())
      .map((q) => ({
        id: q.id || (q.kind || "text") + ":" + (q.value || q.url || q.text),
        kind: ["text", "action", "contact", "url"].indexOf(q.kind) >= 0 ? q.kind : "text",
        text: q.text,
        value: typeof q.value === "string" ? q.value : q.text,
        url: typeof q.url === "string" ? q.url : null,
      }))
      .filter((q) => (seen.has(q.id) ? false : (seen.add(q.id), true)));
  }

  function replyText(data) {
    if (Array.isArray(data.messages) && data.messages.length) {
      const parts = data.messages.map((m) => (m && typeof m.text === "string" ? m.text.trim() : "")).filter(Boolean);
      if (parts.length) return parts.join("\n\n");
    }
    const text = data.text || data.reply || data.message;
    return typeof text === "string" ? text : "";
  }

  // Butler may answer with HTML (links to the policy). The log renders textContent, so keep the link target visible.
  function htmlToText(html) {
    try {
      const doc = new DOMParser().parseFromString(html, "text/html");
      doc.querySelectorAll("a[href]").forEach((a) => {
        const href = a.getAttribute("href");
        if (href && a.textContent.indexOf(href) < 0) a.textContent = a.textContent + " (" + href + ")";
      });
      doc.querySelectorAll("br").forEach((br) => br.replaceWith("\n"));
      return doc.body.textContent || "";
    } catch (_) {
      return html.replace(/<[^>]+>/g, "");
    }
  }

  function enterContactMode() {
    contactMode = true;
    if (!chatInput) return;
    chatInput.setAttribute("placeholder", "+7 900 000-00-00");
    chatInput.setAttribute("inputmode", "tel");
    chatInput.setAttribute("autocomplete", "tel");
    chatInput.focus();
  }

  function leaveContactMode() {
    contactMode = false;
    if (!chatInput) return;
    chatInput.setAttribute("placeholder", defaultPlaceholder);
    chatInput.removeAttribute("inputmode");
    chatInput.removeAttribute("autocomplete");
  }

  /* ---------- transport ---------- */

  /**
   * Send a message to the selected product backend.
   * @param {{text: string}} input
   * @returns {Promise<{text: string, quickReplies: Array, requestContact: boolean, raw: object|null}>}
   */
  async function submitMessage(input) {
    const text = String(input && input.text ? input.text : "").trim();
    const meta = {
      sessionId,
      site: config.site,
      pageContext: config.pageContext,
      sentAt: new Date().toISOString(),
      locale: (navigator.language || "ru").slice(0, 8),
    };
    if (pendingAction && pendingAction.label === text) meta.action = pendingAction.value;
    pendingAction = null;
    if (contactMode && PHONE.test(text)) meta.contactPhone = text;
    leaveContactMode();

    const body = { channel: config.channel, text, payload: meta };

    if (!config.endpoint) return stubReply();

    const res = await fetch(config.endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(config.timeoutMs),
    });

    let data = null;
    try { data = await res.json(); } catch (_) { data = null; }

    if (res.status === 429 && data && typeof data.text === "string" && data.text.trim()) {
      return finish({ text: data.text, quickReplies: [], requestContact: false, raw: data });
    }
    if (!res.ok || !data) throw new Error("Widget backend error: " + res.status);

    if (typeof data.sessionId === "string" && data.sessionId && data.sessionId !== sessionId) rememberSession(data.sessionId);

    let reply = replyText(data);
    if (!reply) throw new Error("Widget received an empty reply");
    if (data.html === true) reply = htmlToText(reply);

    const quickReplies = normalizeQuickReplies(data);
    const requestContact = data.requestContact === true || quickReplies.some((q) => q.kind === "contact");
    return finish({ text: reply, quickReplies, requestContact, raw: data });
  }

  function stubReply() {
    const list = stubReplies[config.product];
    const reply = list[stubIndex % list.length];
    stubIndex += 1;
    return new Promise((resolve) => {
      setTimeout(() => resolve(finish({
        text: reply.text,
        quickReplies: normalizeQuickReplies({ quickReplies: reply.quickReplies }),
        requestContact: false,
        raw: null,
      })), 900 + Math.random() * 700);
    });
  }

  function finish(result) {
    lastQuickReplies = result.quickReplies;
    // main.js appends the butler bubble right after this promise resolves; render the buttons after it.
    setTimeout(() => {
      polishLastBubble();
      renderQuickReplies(result.quickReplies);
      if (result.requestContact && !result.quickReplies.some((q) => q.kind === "contact")) enterContactMode();
    }, 0);
    return result;
  }

  /* ---------- quick replies UI ---------- */

  function clearQuickReplies() {
    if (!chatLog) return;
    chatLog.querySelectorAll(".chat-quick-replies").forEach((el) => el.remove());
  }

  function polishLastBubble() {
    if (!chatLog) return;
    const bubbles = chatLog.querySelectorAll(".bubble.butler-msg");
    const last = bubbles[bubbles.length - 1];
    if (last && !last.classList.contains("chat-typing")) last.style.whiteSpace = "pre-line";
  }

  function chipStyle(el) {
    el.style.font = "inherit";
    el.style.fontSize = "0.85em";
    el.style.color = "inherit";
    el.style.background = "transparent";
    el.style.border = "1px solid currentColor";
    el.style.borderRadius = "999px";
    el.style.padding = "0.35em 0.9em";
    el.style.cursor = "pointer";
    el.style.opacity = "0.85";
    el.style.textDecoration = "none";
    el.style.lineHeight = "1.2";
  }

  function renderQuickReplies(quickReplies) {
    clearQuickReplies();
    if (!chatLog || !chatForm || !chatInput || !quickReplies || !quickReplies.length) return;

    const group = document.createElement("div");
    group.className = "chat-quick-replies";
    group.setAttribute("role", "group");
    group.setAttribute("aria-label", "Быстрые ответы");
    group.style.display = "flex";
    group.style.flexWrap = "wrap";
    group.style.gap = "0.4em";
    group.style.margin = "0.25em 0 0.5em";

    const visible = quickReplies.slice(0, config.maxVisibleQuickReplies);
    const hidden = quickReplies.slice(config.maxVisibleQuickReplies);

    function append(q) {
      let el;
      if (q.kind === "url" && q.url) {
        el = document.createElement("a");
        el.href = q.url;
        el.target = "_blank";
        el.rel = "noopener noreferrer";
        el.textContent = q.text;
      } else {
        el = document.createElement("button");
        el.type = "button";
        el.textContent = q.text;
        el.addEventListener("click", () => press(q));
      }
      el.className = "chat-quick-reply";
      el.dataset.kind = q.kind;
      chipStyle(el);
      group.appendChild(el);
    }

    visible.forEach(append);
    if (hidden.length) {
      const more = document.createElement("button");
      more.type = "button";
      more.className = "chat-quick-reply chat-quick-reply-more";
      more.textContent = "Ещё " + hidden.length;
      chipStyle(more);
      more.addEventListener("click", () => {
        more.remove();
        hidden.forEach(append);
      });
      group.appendChild(more);
    }

    chatLog.appendChild(group);
    chatLog.scrollTop = chatLog.scrollHeight;
  }

  function press(q) {
    if (chatForm.dataset.pending === "true") return;
    if (q.kind === "contact") {
      enterContactMode();
      return;
    }
    pendingAction = q.kind === "action" ? { label: q.text, value: q.value } : null;
    chatInput.value = q.kind === "action" ? q.text : q.value;
    clearQuickReplies();
    if (typeof chatForm.requestSubmit === "function") chatForm.requestSubmit();
    else chatForm.dispatchEvent(new Event("submit", { cancelable: true, bubbles: true }));
  }

  // The guest typed instead of pressing: the stale buttons go away with the new message.
  if (chatForm) {
    chatForm.addEventListener("submit", () => {
      if (chatInput && chatInput.value.trim()) clearQuickReplies();
    }, true);
  }

  function reset() {
    rememberSession(newSessionId());
    pendingAction = null;
    leaveContactMode();
    clearQuickReplies();
  }

  // Expose for main.js, other pages and backend integration tests.
  window.AstorChat = {
    submitMessage,
    renderQuickReplies,
    reset,
    get sessionId() { return sessionId; },
    get product() { return config.product; },
    get endpoint() { return config.endpoint; },
    get quickReplies() { return lastQuickReplies; },
  };
})();
