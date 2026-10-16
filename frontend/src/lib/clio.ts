import { policyHref } from "@content/site";

/**
 * Clio's line to the studio's server.
 *
 * The body follows the server's contract for the web channel (POST /api/messages, the same one the chat
 * on the current c3ag.ru sends), so connecting the window takes two things and no code:
 *   1. NEXT_PUBLIC_WEB_CHAT_ENDPOINT – the address of that endpoint;
 *   2. `policyHref` in content/site.ts – the page with the personal data policy the visitor agrees to by writing.
 * While either is missing nothing is sent anywhere and the window says so.
 *
 * TODO (studio): both of the above. Not tried against the real server yet.
 */
const ENDPOINT = process.env.NEXT_PUBLIC_WEB_CHAT_ENDPOINT ?? "";

/** Fired on the window by a button that hands Clio a phrase (see AskClio): `detail` is the phrase. */
export const ASK_EVENT = "c3:ask";

/** Fired on the window by something that speaks to Clio for the visitor (the shoot configurator): `detail` is sent as their message. */
export const SEND_EVENT = "c3:send";

export const clioConnected = ENDPOINT !== "" && policyHref !== "";

/** The version of the policy the visitor agreed to; goes to the server with every message. */
const POLICY_VERSION = "2026-10";

/** An anonymous visitor, for as long as the page is open. Nothing is stored in the browser. */
let visitor: { id: string; chat: number; since: string } | null = null;

function who() {
  visitor ??= {
    id: crypto.randomUUID(),
    chat: 900_000_000 + Math.floor(Math.random() * 1_000_000),
    since: new Date().toISOString(),
  };
  return visitor;
}

/** Sends one message, returns Clio's answer. Throws when there is none. */
export async function askClio(text: string): Promise<string> {
  const { id, chat, since } = who();
  const correlationId = `web-${new Date().toISOString()}-${id.slice(0, 8)}`;

  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Request-Id": correlationId },
    body: JSON.stringify({
      channel: "WEB",
      externalUserId: `web:anon:${id}`,
      chatId: chat,
      text,
      contactPhone: null,
      firstName: null,
      username: null,
      correlationId,
      payload: {
        site: "c3ag",
        sessionId: id,
        page: window.location.pathname + window.location.hash,
        referrer: document.referrer || null,
        utm: {},
        selectedVideo: null,
        viewport: {
          width: window.innerWidth,
          height: window.innerHeight,
          devicePixelRatio: window.devicePixelRatio || 1,
          locale: navigator.language,
          timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
        },
        // the visitor saw the line about the policy under the field before the first message
        consent: { privacyAccepted: true, policyVersion: POLICY_VERSION, acceptedAt: since },
      },
    }),
  });

  const data: { reply?: unknown; text?: unknown } = await res.json().catch(() => ({}));
  const reply = data.reply ?? data.text;
  if (!res.ok || typeof reply !== "string" || reply === "") throw new Error("Clio did not answer");
  return reply;
}
