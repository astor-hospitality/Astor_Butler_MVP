/* ============================================================
   Astor Butler — widget transport layer.

   BACKEND HOOKUP:
   1. AstorChatConfig.endpoint points to the backend message gateway,
      e.g. "https://api.astorbutler.ru/api/messages".
   2. submitMessage(payload) POSTs JSON and returns the reply.
      Payload contract matches MessageController:
        { channel: "WEB", text, payload: { sessionId, site, sentAt } }
   3. While endpoint is null the widget answers with a friendly
      local stub — no network calls are made.
   ============================================================ */

window.AstorChatConfig = window.AstorChatConfig || {
  endpoint: "/api/astor/messages",
  channel: "WEB",
  site: "astor-butler-commercial",
};

(function () {
  "use strict";

  const sessionId =
    "web-" + Math.random().toString(36).slice(2) + "-" + Date.now().toString(36);

  const stubReplies = [
    "Добрый вечер. Я — Astor Butler, цифровой дворецкий. Сейчас я в демонстрационном режиме, но уже скоро смогу держать вашу бронь по-настоящему.",
    "Записал. Когда меня подключат к ресторану, я передам это хостес вместе с контекстом — а пока просто рад знакомству.",
    "Прекрасный запрос. В боевом режиме я бы уточнил: вам удобнее у окна или в тихой зоне?",
    "Понял вас. Настоящие сценарии — бронь, меню, пожелания — уже работают в Telegram. Здесь я пока показываю манеры.",
  ];
  let stubIndex = 0;

  /**
   * Send a message to the butler backend.
   * @param {{text: string}} input
   * @returns {Promise<{text: string}>} butler reply
   */
  async function submitMessage(input) {
    const payload = {
      channel: window.AstorChatConfig.channel,
      text: input.text,
      payload: {
        sessionId,
        site: window.AstorChatConfig.site,
        pageContext: "commercial_landing",
        sentAt: new Date().toISOString(),
      },
    };

    const endpoint = window.AstorChatConfig.endpoint;
    if (endpoint) {
      const res = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(25000),
      });
      if (!res.ok) throw new Error("Widget backend error: " + res.status);
      const data = await res.json();
      const text = data.text || data.reply || data.message;
      if (typeof text !== "string" || !text.trim()) throw new Error("Widget received an empty reply");
      return { text };
    }

    // Mock mode: friendly stub with a small "thinking" delay.
    return new Promise((resolve) => {
      const reply = stubReplies[stubIndex % stubReplies.length];
      stubIndex += 1;
      setTimeout(() => resolve({ text: reply }), 900 + Math.random() * 700);
    });
  }

  // Expose for main.js and for future backend integration tests.
  window.AstorChat = { submitMessage, sessionId };

})();
