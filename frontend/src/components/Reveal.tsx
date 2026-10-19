"use client";

import { useRef, type ReactNode } from "react";
import { gsap, motionAllowed, onReady, useGSAP } from "@/lib/motion";

type Tag = "div" | "p" | "span" | "h1" | "h2" | "h3" | "li" | "figcaption";

interface RevealProps {
  /** "fade": text, comes after the picture with a small rise. "clip": a block that opens from the bottom up. */
  kind?: "fade" | "clip";
  /** Seconds to wait after the element comes on screen. Text waits longer than pictures: picture first. */
  delay?: number;
  as?: Tag;
  className?: string;
  children: ReactNode;
}

export function Reveal({ kind = "fade", delay = kind === "fade" ? 0.35 : 0, as = "div", className, children }: RevealProps) {
  const ref = useRef<HTMLElement>(null);

  useGSAP(
    (_, contextSafe) => {
      const el = ref.current;
      if (!el || !contextSafe || !motionAllowed()) return;
      // Hidden by opacity (not visibility) while it waits, so the keyboard can still reach what is inside;
      // a focus inside brings it in at once.
      let tween: ReturnType<typeof gsap.fromTo> | undefined;
      const run = contextSafe(() => {
        const scrollTrigger = { trigger: el, start: "top 92%", once: true };
        tween =
          kind === "clip"
            ? gsap.fromTo(el, { clipPath: "inset(100% 0% 0% 0%)" }, { clipPath: "inset(0% 0% 0% 0%)", duration: 1.2, delay, ease: "power3.out", scrollTrigger })
            : gsap.fromTo(el, { opacity: 0, y: 10 }, { opacity: 1, y: 0, duration: 0.9, delay, ease: "power3.out", scrollTrigger });
      });
      const show = () => tween?.progress(1);
      el.addEventListener("focusin", show);
      const off = onReady(run);
      return () => {
        off();
        el.removeEventListener("focusin", show);
      };
    },
    { scope: ref },
  );

  const Component = as as "div";
  return (
    <Component ref={ref as React.RefObject<HTMLDivElement>} data-reveal={kind} className={className}>
      {children}
    </Component>
  );
}
