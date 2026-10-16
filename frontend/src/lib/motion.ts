"use client";

import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";

gsap.registerPlugin(ScrollTrigger, useGSAP);

export { gsap, ScrollTrigger, useGSAP };

/** Fired once, when the preloader lets go of the page. Reveals wait for it. */
const READY_EVENT = "c3:ready";

/** Motion is on when the inline script in the layout found no "reduce motion" preference. */
export const motionAllowed = () => document.documentElement.classList.contains("motion");

const isReady = () => document.documentElement.classList.contains("ready");

export function markReady() {
  if (isReady()) return;
  document.documentElement.classList.add("ready");
  window.dispatchEvent(new Event(READY_EVENT));
}

/** Runs now if the page is ready, otherwise when it becomes ready. Returns a cleanup. */
export function onReady(run: () => void): () => void {
  if (isReady()) {
    run();
    return () => {};
  }
  window.addEventListener(READY_EVENT, run, { once: true });
  return () => window.removeEventListener(READY_EVENT, run);
}
