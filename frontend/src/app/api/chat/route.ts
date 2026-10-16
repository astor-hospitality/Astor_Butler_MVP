import { NextRequest, NextResponse } from "next/server";
import { clioDemoReply } from "@/lib/clio-persona";

/**
 * Local mock for the C3AG.ru web chat.
 *
 * It accepts the production-shaped Web Chat body (FRONTEND_BACKEND_CONTRACTS.md
 * §4, `POST /api/messages`) and returns a contract-shaped response. It also
 * still accepts the legacy `{ messages }` shape for backward compatibility.
 *
 * NOTE: This is a frontend mock only. No real FSM/lead logic lives here — that
 * belongs to the backend (Codex). Do not turn this into a backend endpoint.
 */

type LegacyMessage = { from: "bot" | "user"; text: string };

type WebChatBody = {
  channel?: "WEB";
  text?: string;
  correlationId?: string;
  turn?: number; // dev-only hint from the widget to drive the guided script
  payload?: {
    selectedVideo?: { slug?: string } | null;
    consent?: { privacyAccepted?: boolean } | null;
  };
  messages?: LegacyMessage[]; // legacy shape
};

export async function POST(req: NextRequest) {
  const body = (await req.json()) as WebChatBody;

  // Legacy shape: derive step from user message count.
  if (Array.isArray(body.messages)) {
    const step = body.messages.filter((m) => m.from === "user").length;
    const last = [...body.messages].reverse().find((m) => m.from === "user")?.text ?? "";
    return NextResponse.json({ reply: clioDemoReply(last, step) });
  }

  // Contract shape: use the dev-only `turn` hint for the guided script.
  const step = typeof body.turn === "number" ? body.turn : 1;
  const reply = clioDemoReply(body.text ?? "", step);

  // Mirror the production response contract (subset) alongside `reply`.
  return NextResponse.json({
    channel: "WEB",
    text: reply,
    reply, // convenience for the current widget
    nextState: step > 6 ? "READY_FOR_DIALOG" : "COLLECTING",
    fallback: false,
    actions: step > 6 ? ["WEB_LEAD_CAPTURED"] : [],
    createdAt: new Date().toISOString(),
  });
}
