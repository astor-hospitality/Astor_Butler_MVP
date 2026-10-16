"use client";

import { useRef, type ReactNode } from "react";
import type { Part } from "@content/site";
import { gsap, motionAllowed, onReady, useGSAP } from "@/lib/motion";

type Tag = "p" | "h1" | "h2" | "div";

interface LinesProps {
  as?: Tag;
  className?: string;
  /** Seconds between two lines. */
  stagger?: number;
  delay?: number;
  /** The lines themselves: each one is marked `data-line` (see `Line`). */
  children: ReactNode;
}

/**
 * Massive words come on screen line by line: each line opens from its lower edge and rises a little.
 * Without scripts, or with "reduce motion", the lines are simply there.
 */
export function Lines({ as = "p", className, stagger = 0.1, delay = 0, children }: LinesProps) {
  const ref = useRef<HTMLElement>(null);

  useGSAP(
    (_, contextSafe) => {
      const el = ref.current;
      if (!el || !contextSafe || !motionAllowed()) return;
      const run = contextSafe(() => {
        // the open shape reaches past the line's own box: the breve of «Й» and the tails of «Д» and «Щ» stand outside it
        gsap.fromTo(
          el.querySelectorAll("[data-line]"),
          { clipPath: "inset(0% 0% 100% 0%)", y: 28 },
          { clipPath: "inset(-14% 0% -14% 0%)", y: 0, duration: 1.2, ease: "expo.out", stagger, delay, scrollTrigger: { trigger: el, start: "top 88%", once: true } },
        );
      });
      return onReady(run);
    },
    { scope: ref },
  );

  const Component = as as "p";
  return (
    <Component ref={ref as React.RefObject<HTMLParagraphElement>} className={className}>
      {children}
    </Component>
  );
}

/** One line of a massive block. */
export function Line({ className, children }: { className?: string; children: ReactNode }) {
  return (
    <span data-line className={className}>
      {children}
    </span>
  );
}

/** The pieces of a line: plain words and the red ones. */
export function Words({ parts }: { parts: Part[] }) {
  return (
    <>
      {parts.map((part, i) =>
        part.red ? (
          <span key={i} className="text-red">
            {part.text}
          </span>
        ) : (
          <span key={i}>{part.text}</span>
        ),
      )}
    </>
  );
}
