"use client";

import Lenis from "lenis";
import { usePathname } from "next/navigation";
import { useEffect, useRef } from "react";
import { gsap, motionAllowed, onReady, ScrollTrigger } from "@/lib/motion";
import { posterAt, THEME_LINE, themeOf } from "./ThemeWatcher";

/**
 * Smooth scrolling (Lenis) driven by GSAP's clock, so scroll-linked animation and the scroll itself never drift apart.
 * Not started at all when the visitor asked for reduced motion.
 *
 * Two more things live here because they belong to the scroll:
 * the thin line on the right edge that stands for the scrollbar, and the way a link to another part of the page is followed.
 * Such a link does not race through every screen in between (red and white flashing past); a sheet in the colour of the
 * place it leads to closes over the page, the page is moved under it, and the sheet lifts.
 */
export function SmoothScroll() {
  const pathname = usePathname();
  const line = useRef<HTMLDivElement>(null);
  const sheet = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // Tells the inline script in the layout that scripts did arrive; otherwise it would switch motion off as a safety net.
    const root = document.documentElement;
    root.classList.add("hydrated");

    // The line follows the scroll with or without motion.
    const measure = () => {
      const room = root.scrollHeight - window.innerHeight;
      line.current?.style.setProperty("--at", room > 0 ? String(Math.min(1, window.scrollY / room)) : "0");
    };
    measure();
    window.addEventListener("scroll", measure, { passive: true });
    window.addEventListener("resize", measure);
    const stopMeasuring = () => {
      window.removeEventListener("scroll", measure);
      window.removeEventListener("resize", measure);
    };
    if (!motionAllowed()) return stopMeasuring;

    const lenis = new Lenis({ duration: 1.15 });
    lenis.on("scroll", ScrollTrigger.update);
    // Going down, the navigation leaves the pictures alone; going up, or at the very top, it is back.
    lenis.on("scroll", ({ direction, scroll }) => {
      if (direction !== 0) root.classList.toggle("nav-away", direction > 0 && scroll > 160);
    });
    const tick = (time: number) => lenis.raf(time * 1000);
    gsap.ticker.add(tick);
    gsap.ticker.lagSmoothing(0);

    // The page does not scroll under the preloader.
    lenis.stop();
    const off = onReady(() => lenis.start());

    const halt = () => lenis.scrollTo(window.scrollY, { immediate: true, force: true });
    // the same for the browser's back and forward
    window.addEventListener("popstate", halt);

    // A link to another part of this page: the sheet closes, the page moves under it, the sheet lifts.
    let busy = false;
    const follow = (e: MouseEvent) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const link = (e.target as Element | null)?.closest<HTMLAnchorElement>("a[href]");
      if (!link || link.target === "_blank") return;
      const to = new URL(link.href);
      if (to.origin !== window.location.origin) return;
      if (to.pathname !== window.location.pathname) {
        // To another page: a glide still running from the wheel would carry on over the new page and leave it at the
        // old height (or past the place its link points to). It ends here, where the page stands; the router takes over.
        halt();
        return;
      }
      if (!to.hash) return;
      const target = document.getElementById(decodeURIComponent(to.hash.slice(1)));
      const cover = sheet.current;
      if (!target || !cover) return;
      // taken before the router sees it: the router would move the page at once, with a hard cut
      e.preventDefault();
      e.stopPropagation();
      if (busy) return;
      busy = true;
      // The sheet has the colour the page will have there, by the theme watcher's own rule: the colour the poster under
      // the line gives once the target is at the top of the window (or as near it as the page lets it come). The first
      // screen, once its film has the window, is dark (`data-poster-now`), not the red it is named with.
      const lands = Math.min(Math.max(target.getBoundingClientRect().top, -window.scrollY), lenis.limit - window.scrollY);
      const theme = themeOf(posterAt(lands + window.innerHeight * THEME_LINE));
      const colour = theme === "red" ? "var(--color-red)" : theme === "ink" ? "var(--color-ink)" : "var(--color-paper)";
      gsap
        .timeline({ onComplete: () => void (busy = false) })
        .set(cover, { backgroundColor: colour, clipPath: "inset(100% 0% 0% 0%)", visibility: "visible" })
        .to(cover, { clipPath: "inset(0% 0% 0% 0%)", duration: 0.5, ease: "power3.inOut" })
        .call(() => {
          // under the sheet the page takes its new colour at once, not in a slow fade
          root.classList.add("jumping");
          lenis.scrollTo(target, { immediate: true, force: true });
          ScrollTrigger.update();
          window.history.replaceState(null, "", to.hash);
          root.classList.remove("nav-away");
        })
        .to(cover, { clipPath: "inset(0% 0% 100% 0%)", duration: 0.6, ease: "power3.inOut" }, "+=0.12")
        .set(cover, { visibility: "hidden" })
        .call(() => root.classList.remove("jumping"));
    };
    document.addEventListener("click", follow, true);

    return () => {
      stopMeasuring();
      document.removeEventListener("click", follow, true);
      window.removeEventListener("popstate", halt);
      off();
      gsap.ticker.remove(tick);
      lenis.destroy();
      root.classList.remove("nav-away", "jumping");
    };
  }, []);

  // A new page has new heights: measure the triggers again once it is laid out.
  useEffect(() => {
    const id = requestAnimationFrame(() => ScrollTrigger.refresh());
    return () => cancelAnimationFrame(id);
  }, [pathname]);

  return (
    <>
      <div ref={line} aria-hidden="true" className="scroll-line" />
      <div ref={sheet} aria-hidden="true" className="jump-sheet" />
    </>
  );
}
