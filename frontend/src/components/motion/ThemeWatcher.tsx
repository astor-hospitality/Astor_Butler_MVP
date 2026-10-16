"use client";

import { useLayoutEffect } from "react";
import { ScrollTrigger } from "@/lib/motion";

/** The page takes the colour of the poster under this line: a share of the window's height, from its top. */
export const THEME_LINE = 0.55;

/**
 * The poster under a line `y` pixels from the top of the window, read from the layout as it is right now.
 * A poster inside another one wins over it; a hidden poster (no box) is passed over. Where no poster covers the line
 * (a gap between screens, the foot of a short page) the nearest one is taken.
 */
export function posterAt(y: number): HTMLElement | undefined {
  let under: HTMLElement | undefined;
  let near: HTMLElement | undefined;
  let gap = Infinity;
  for (const poster of document.querySelectorAll<HTMLElement>("[data-poster]")) {
    const box = poster.getBoundingClientRect();
    if (box.height === 0) continue;
    // later in the document is deeper, or painted over: it wins
    if (box.top <= y && box.bottom > y) under = poster;
    const off = Math.max(box.top - y, y - box.bottom, 0);
    if (off < gap) {
      gap = off;
      near = poster;
    }
  }
  return under ?? near;
}

/** The colour a poster gives the page: a poster whose colour changes while it is on screen says which it is now. */
export const themeOf = (poster?: HTMLElement) => poster?.dataset.posterNow ?? poster?.dataset.poster;

/**
 * Keeps the colour of the page in step with the poster that is on screen.
 * Each poster names its colour in `data-poster`; the page takes the colour of the poster under the line at 55% of the
 * window, and the registered colour properties (see globals.css) carry it over softly. The first screen turns dark
 * once the film has it, and says so in `data-poster-now`.
 *
 * The poster is looked up in the live layout every time, never from positions measured earlier: screens change
 * height after they were measured (the configurator, the works filter, a resized window), and a fast fling or a jump
 * must not leave a colour behind. It is looked up once a frame while the page scrolls (Lenis moves the window, so its
 * scroll comes here too), when the window or the page changes size, when the fonts are in, after ScrollTrigger measures
 * again, when a poster changes its colour, and when anything else writes the colour of the page.
 * Followed with or without motion: what stays on screen all the way down (Clio's star) has to know the colour under it.
 * One per page.
 */
export function ThemeWatcher() {
  useLayoutEffect(() => {
    const root = document.documentElement;
    let frame = 0;
    let gone = false;

    const sync = () => {
      const theme = themeOf(posterAt(window.innerHeight * THEME_LINE));
      if (theme && root.dataset.theme !== theme) root.dataset.theme = theme;
    };
    const soon = () => {
      if (!frame && !gone)
        frame = requestAnimationFrame(() => {
          frame = 0;
          sync();
        });
    };

    // before the first paint, from the screen under the line now
    sync();

    // In the very frame Lenis moves the page (ScrollTrigger updates right after it), so the colour is not a frame late;
    // the window's own scroll event below still catches every other way the page moves, and what lies past this range.
    const follow = ScrollTrigger.create({ start: 0, end: "max", onUpdate: sync });
    window.addEventListener("scroll", soon, { passive: true });
    window.addEventListener("resize", soon);
    window.addEventListener("load", soon);
    ScrollTrigger.addEventListener("refresh", soon);
    ScrollTrigger.addEventListener("scrollEnd", soon);
    void document.fonts?.ready.then(soon);
    // anything that changes the height of the page or of a screen moves the posters under the line
    const sizes = new ResizeObserver(soon);
    sizes.observe(document.body);
    document.querySelectorAll("[data-poster]").forEach((poster) => sizes.observe(poster));
    // a poster tells its colour now; and whoever else writes the colour of the page is checked against the layout
    const marks = new MutationObserver(soon);
    marks.observe(document.body, { subtree: true, attributes: true, attributeFilter: ["data-poster", "data-poster-now"] });
    marks.observe(root, { attributes: true, attributeFilter: ["data-theme"] });

    return () => {
      gone = true;
      cancelAnimationFrame(frame);
      follow.kill();
      window.removeEventListener("scroll", soon);
      window.removeEventListener("resize", soon);
      window.removeEventListener("load", soon);
      ScrollTrigger.removeEventListener("refresh", soon);
      ScrollTrigger.removeEventListener("scrollEnd", soon);
      sizes.disconnect();
      marks.disconnect();
      // the next page starts from the colour of its own first poster (see globals.css); a page without a watcher keeps that
      delete root.dataset.theme;
    };
  }, []);
  return null;
}
