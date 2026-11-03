"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import type { SiteContent } from "@content/site";
import type { Picture } from "@content/types";
import { gsap, motionAllowed, onReady, useGSAP } from "@/lib/motion";

interface MaskHeroProps {
  t: SiteContent["hero"];
  /** The first frame of the film: painted at once, and instead of the film when motion is off. */
  still: Picture;
  film: { wide: string; tall: string; from: number };
}

/**
 * The first screen: the showreel alone on the whole screen, straight after the turning mark of the preloader.
 * There used to be the word «C3» in red over it, its letters windows onto the film, which then grew and melted away;
 * Roma took it off (2026-10-16: «убрать эти буквы, чтобы был чистый видеоряд»), so the page opens on the film alone.
 * Under the film the page is red, not black (Sergey, 2026-10-26: «черные фоны табу»): the preloader's red lifts straight
 * onto the film, and the page scrolls on into the red screen with the words about the studio. The small line
 * «Студия C3 / видеопродакшн» under the mark is gone too (Sergey the same day: «функционально бесполезна»).
 * The screen is one window tall; the film drifts a little slower than the page as it goes.
 * The film is attached only after the page has come alive, plays without sound and stops while it is off screen.
 */
export function MaskHero({ t, still, film }: MaskHeroProps) {
  const root = useRef<HTMLElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const [playing, setPlaying] = useState(false);

  useEffect(() => {
    const el = video.current;
    if (!el || !motionAllowed()) return;
    let unwatch = () => {};
    const start = () => {
      // the film starts from the frame that is already painted
      el.addEventListener("loadedmetadata", () => void (el.currentTime = film.from), { once: true });
      el.src = window.matchMedia("(orientation: portrait)").matches ? film.tall : film.wide;
      const watch = new IntersectionObserver(
        ([entry]) => {
          if (entry.isIntersecting) el.play().catch(() => {});
          else el.pause();
        },
        { threshold: 0.05 },
      );
      watch.observe(el);
      unwatch = () => watch.disconnect();
    };
    const off = onReady(start);
    return () => {
      off();
      unwatch();
    };
  }, [film]);

  // As the screen scrolls away the film lags a little behind it.
  useGSAP(
    () => {
      const el = root.current;
      if (!el || !motionAllowed()) return;
      gsap.fromTo(el.querySelector(".hero-film-in"), { yPercent: 0 }, { yPercent: 10, ease: "none", scrollTrigger: { trigger: el, start: "top top", end: "bottom top", scrub: true } });
    },
    { scope: root },
  );

  return (
    <section ref={root} id="top" aria-label={t.label} data-poster="red" className="hero poster theme-red relative">
      <div className="hero-stage">
        <div className="hero-film" aria-hidden="true">
          <div className="hero-film-in">
            <Image src={still.src} alt="" fill preload sizes="100vw" />
            <video ref={video} muted loop playsInline preload="none" tabIndex={-1} data-on={playing} onPlaying={() => setPlaying(true)} />
          </div>
        </div>

        <h1 className="sr-only">{t.word}</h1>
        {/* always white: it lies on the film, also while the page under it has already turned light */}
        <p className="caps absolute inset-x-[var(--m)] bottom-8 flex justify-between gap-6 text-white">
          <span>{t.scroll}</span>
          <span className="max-md:hidden">{t.reel}</span>
        </p>
      </div>
    </section>
  );
}
