"use client";

import { useEffect, useRef } from "react";
import { gsap, motionAllowed } from "@/lib/motion";

/** The circle open, in px (the `[&.is-open]:size-[7.5rem]` below). */
const OPEN = 120;
/** How much of the circle's width the word may take: a word wider than that is set smaller until it fits. */
const ROOM = 0.78;

/**
 * A small dot instead of the pointer; over a case it opens into a circle with a word in it.
 * The word always stays inside the circle (Sergey, 2026-10-26: «текст кнопки тоже вылазит за кружок»): a long one,
 * «Подробнее» or «Смотреть», is set smaller, measured each time the word changes.
 * Only for a mouse, and only when motion is on: on touch screens and with "reduce motion" the system pointer stays.
 * What it reacts to is marked in the markup: `data-cursor="View"`.
 */
export function Cursor() {
  const dot = useRef<HTMLDivElement>(null);
  const label = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    const el = dot.current;
    if (!el || !motionAllowed() || !window.matchMedia("(pointer: fine)").matches) return;

    const root = document.documentElement;
    root.classList.add("has-cursor");
    gsap.set(el, { xPercent: -50, yPercent: -50, autoAlpha: 0 });
    const x = gsap.quickTo(el, "x", { duration: 0.3, ease: "power3.out" });
    const y = gsap.quickTo(el, "y", { duration: 0.3, ease: "power3.out" });

    // the word in its own size first; if it is wider than the room in the circle, smaller in proportion
    const fit = (word: HTMLSpanElement, text: string) => {
      if (word.textContent === text) return;
      word.textContent = text;
      word.style.fontSize = "";
      const wide = word.offsetWidth;
      const size = parseFloat(getComputedStyle(word).fontSize);
      if (wide > OPEN * ROOM) word.style.fontSize = `${((size * OPEN * ROOM) / wide).toFixed(2)}px`;
    };

    // what the pointer is over decides the word; the page can also move under a pointer that stands still
    const at = { x: -1, y: -1 };
    const read = (under: Element | null) => {
      const target = under?.closest<HTMLElement>("[data-cursor]");
      el.classList.toggle("is-open", Boolean(target));
      if (target && label.current) fit(label.current, target.dataset.cursor ?? "");
    };
    const move = (e: PointerEvent) => {
      at.x = e.clientX;
      at.y = e.clientY;
      x(e.clientX);
      y(e.clientY);
      gsap.to(el, { autoAlpha: 1, duration: 0.2, overwrite: "auto" });
      read(e.target as Element | null);
    };
    const scrolled = () => {
      if (at.x >= 0) read(document.elementFromPoint(at.x, at.y));
    };
    const leave = () => gsap.to(el, { autoAlpha: 0, duration: 0.2, overwrite: "auto" });
    // a click can put something new under a pointer that stands still (the player over the rail): read again once it is drawn
    let wait = 0;
    const clicked = () => {
      window.clearTimeout(wait);
      wait = window.setTimeout(scrolled, 80);
    };

    window.addEventListener("pointermove", move, { passive: true });
    window.addEventListener("scroll", scrolled, { passive: true });
    window.addEventListener("click", clicked);
    document.addEventListener("pointerleave", leave);
    return () => {
      window.clearTimeout(wait);
      window.removeEventListener("click", clicked);
      window.removeEventListener("pointermove", move);
      window.removeEventListener("scroll", scrolled);
      document.removeEventListener("pointerleave", leave);
      root.classList.remove("has-cursor");
    };
  }, []);

  return (
    <div
      ref={dot}
      aria-hidden="true"
      className="cursor group pointer-events-none fixed left-0 top-0 z-[70] size-2 place-items-center rounded-full bg-[color:var(--page-fg)] text-[color:var(--page-bg)] transition-[width,height] duration-500 ease-expo [&.is-open]:size-[7.5rem]"
    >
      <span ref={label} className="caps whitespace-nowrap !tracking-[0.06em] opacity-0 transition-opacity duration-300 group-[.is-open]:opacity-100" />
    </div>
  );
}
