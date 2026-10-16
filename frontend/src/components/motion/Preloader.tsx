"use client";

import Image from "next/image";
import { useEffect, useRef } from "react";
import type { Picture } from "@content/types";
import { gsap, markReady, motionAllowed, useGSAP } from "@/lib/motion";

/** How long the mark stays before it starts to dissolve, in seconds the visitor actually sees. */
const HOLD = 1.5;
/** However late the scripts arrive, the mark is not taken away sooner than this after them. */
const AT_LEAST = 0.9;
/** Where the mark turns: once it has started turning, it turns at least this long before the screen lets go. */
const TURN = 1.1;
/** ...but the screen does not wait for a slow film longer than this after the scripts, in seconds. */
const WAIT_TURN = 3.2;

/**
 * The first second and a half of the site: a red screen with the chrome mark turning, and a counter that runs from
 * 0 to 100 in small capitals. Then the mark dissolves and what is under it comes through; at that moment
 * the page is told it may start. The mark comes in by a CSS animation, so it moves from the first paint and does not
 * wait for the scripts.
 *
 * The time is counted from the moment the screen was really painted in a tab the visitor is looking at:
 * a page that took long to arrive, or was opened in a background tab, still shows the mark for its full time.
 * The screen is rendered only when motion is on (CSS); on a click to another page of the site it is not played again.
 *
 * Where the browser can show the alpha of a WebM (the inline script in the layout marks the page `spin` before the
 * first paint), only the studio's own animation of the mark turning is shown: no still mark first and no flash of the
 * star (Roma, 2026-10-16: «оставь только прокручивание»). The screen waits for it to start turning and lets it turn a
 * moment. Safari cannot show that alpha, so there the still mark stays.
 */
export function Preloader({ mark, spin }: { mark: Picture; spin?: string }) {
  const root = useRef<HTMLDivElement>(null);
  const video = useRef<HTMLSpanElement>(null);
  const logo = useRef<HTMLDivElement>(null);
  const count = useRef<HTMLSpanElement>(null);

  useGSAP(
    (_, contextSafe) => {
      const el = root.current;
      if (!el || !contextSafe || !motionAllowed()) {
        markReady();
        return;
      }
      const leave = contextSafe(() => {
        gsap
          .timeline()
          .call(markReady)
          .to(logo.current, { scale: 1.05, autoAlpha: 0, duration: 0.8, ease: "power2.in" }, 0)
          .to(el, { autoAlpha: 0, duration: 1, ease: "power2.inOut" }, 0.1)
          .set(el, { display: "none" })
          .call(() => video.current?.querySelector("video")?.pause());
      });
      let timer: number | undefined;
      const began = performance.now();
      const turns = () => document.documentElement.classList.contains("spin");
      // the counter is done; where the mark turns, let it have turned a moment first
      const done = () => {
        if (!turns() || root.current?.dataset.spin === "on") {
          const since = Number(root.current?.dataset.spinAt ?? 0);
          const more = turns() ? Math.max(0, TURN * 1000 - (performance.now() - since)) : 0;
          timer = window.setTimeout(leave, more);
          return;
        }
        if ((performance.now() - began) / 1000 > WAIT_TURN) return leave();
        timer = window.setTimeout(done, 100);
      };
      const start = () => {
        if (document.hidden) return;
        document.removeEventListener("visibilitychange", start);
        const painted = performance.getEntriesByName("first-contentful-paint")[0]?.startTime;
        const seen = painted === undefined ? 0 : (performance.now() - painted) / 1000;
        const left = Math.max(AT_LEAST, HOLD - seen);
        // the counter reaches 100 just as the screen lets go
        const at = { n: 0 };
        gsap.to(at, { n: 100, duration: left, ease: "power1.inOut", onUpdate: () => void (count.current && (count.current.textContent = String(Math.round(at.n)).padStart(3, "0"))) });
        timer = window.setTimeout(done, left * 1000);
      };
      document.addEventListener("visibilitychange", start);
      start();
      return () => {
        window.clearTimeout(timer);
        document.removeEventListener("visibilitychange", start);
      };
    },
    { scope: root },
  );

  // The turning mark: only where the alpha of a WebM is shown (not Safari, not any browser on an iPhone or iPad).
  useEffect(() => {
    const slot = video.current;
    // the film the inline script in the layout began to load before the first paint (only where it can play)
    const el = (window as Window & { __c3spin?: HTMLVideoElement }).__c3spin;
    const page = document.documentElement;
    if (!slot || !el || !spin || !motionAllowed() || !page.classList.contains("spin")) return;
    slot.append(el);
    const on = () => {
      if (!root.current) return;
      root.current.dataset.spinAt = String(performance.now());
      root.current.dataset.spin = "on";
    };
    // a film that cannot play gives the still mark back
    const fail = () => page.classList.remove("spin");
    el.addEventListener("playing", on, { once: true });
    el.addEventListener("error", fail, { once: true });
    el.play().catch(() => {});
    return () => {
      el.removeEventListener("playing", on);
      el.removeEventListener("error", fail);
    };
  }, [spin]);

  return (
    <div ref={root} aria-hidden="true" className="preloader theme-red field fixed inset-0 z-[60] place-items-center">
      <div ref={logo} className="relative w-[min(76vw,720px)]">
        <Image src={mark.src} alt="" width={mark.width} height={mark.height} preload sizes="(min-width: 960px) 720px, 76vw" className="preloader-mark h-auto w-full" />
        {/* the turning mark is put in here (see the effect above); the box itself takes no room */}
        {spin ? <span ref={video} className="contents" /> : null}
      </div>
      <p className="caps absolute bottom-8 left-[var(--m)] tabular-nums">
        <span ref={count}>000</span>
      </p>
    </div>
  );
}
