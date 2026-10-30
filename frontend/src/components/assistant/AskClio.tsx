"use client";

import type { ReactNode } from "react";
import { ASK_EVENT } from "@/lib/clio";

/** The classes of the plain button at the end of a direction's page: a thin frame that fills with the line colour under the pointer. */
const framed = "caps hairline px-6 py-4 transition-colors duration-500 hover:bg-[color:var(--line)] hover:text-[color:var(--on-line)]";

interface AskClioProps {
  /** Put into Clio's field when her window opens; empty opens the window on a blank field. */
  phrase?: string;
  label: ReactNode;
  /** Replaces the plain framed look (the header's call is a filled pill, see Nav). */
  className?: string;
}

/**
 * A button that opens Clio's window. The studio's proposals end with a phrase to send ("ПАКЕТ", "Хочу кино"):
 * a click opens the window with that phrase already in the field, the visitor only has to send it.
 * Without a phrase it simply opens the talk: the header's «Обсудить проект» does that (Sergey, 2026-10-26:
 * «обсудить проект должна запускать чат ИИ-помощника»).
 */
export function AskClio({ phrase = "", label, className = framed }: AskClioProps) {
  return (
    <button type="button" onClick={() => window.dispatchEvent(new CustomEvent<string>(ASK_EVENT, { detail: phrase }))} className={className}>
      {label}
    </button>
  );
}
