"use client";

import { ASK_EVENT } from "@/lib/clio";

/**
 * The button at the end of a direction's page. The studio's proposals end with a phrase to send ("ПАКЕТ", "Хочу кино"):
 * a click opens Clio's window with that phrase already in the field, the visitor only has to send it.
 */
export function AskClio({ phrase, label }: { phrase: string; label: string }) {
  return (
    <button
      type="button"
      onClick={() => window.dispatchEvent(new CustomEvent<string>(ASK_EVENT, { detail: phrase }))}
      className="caps hairline px-6 py-4 transition-colors duration-500 hover:bg-[color:var(--line)] hover:text-[color:var(--on-line)]"
    >
      {label}
    </button>
  );
}
