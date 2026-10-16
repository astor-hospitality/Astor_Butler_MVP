"use client";

import Image from "next/image";
import { useRef, type ReactNode } from "react";
import { gsap, motionAllowed, onReady, ScrollTrigger, useGSAP } from "@/lib/motion";

interface RevealImageProps {
  src: string;
  width: number;
  height: number;
  alt: string;
  sizes: string;
  /**
   * The picture of the first screen: preloaded, and painted at once instead of opening later.
   * The preloader's sheet lifting off it is its reveal; the browser also counts it as shown from the first paint.
   * Everything else is lazy.
   */
  eager?: boolean;
  /** A fixed frame, the picture fills it. Without it the picture keeps its own proportions (objects with no background). */
  ratio?: "4/5" | "1/1";
  className?: string;
  /** Laid over the picture inside the same frame, e.g. a looping clip of it. */
  children?: ReactNode;
}

/**
 * A picture that opens from the bottom up when it comes on screen and drifts a little inside its frame while the page scrolls.
 * Without scripts, or with "reduce motion", it is simply there.
 */
export function RevealImage({ src, width, height, alt, sizes, eager = false, ratio, className = "", children }: RevealImageProps) {
  const box = useRef<HTMLDivElement>(null);
  const inner = useRef<HTMLDivElement>(null);

  useGSAP(
    (_, contextSafe) => {
      const el = box.current;
      const img = inner.current;
      if (!el || !img || !contextSafe || !motionAllowed()) return;
      const run = contextSafe(() => {
        const enter = { trigger: el, start: "top 90%", once: true };
        if (eager) {
          gsap.fromTo(el, { scale: 1.06 }, { scale: 1, duration: 1.6, ease: "expo.out" });
        } else {
          // The frame opens when it is on screen AND its picture has arrived: over a slow line an empty frame would open
          // first and the picture would just pop in later. A picture that fails to load does not hold the frame shut.
          const open = gsap.timeline({ paused: true }).fromTo(el, { clipPath: "inset(100% 0% 0% 0%)" }, { clipPath: "inset(0% 0% 0% 0%)", duration: 1.3, ease: "power3.out" }, 0);
          if (ratio) open.fromTo(img, { scale: 1.1 }, { scale: 1, duration: 1.6, ease: "power3.out" }, 0);
          const play = contextSafe(() => open.play());
          const picture = el.querySelector("img");
          // A lazy picture inside a frame that is clipped shut is, for the browser, not on screen at all: it would never
          // start loading. So the loading is started by hand, a screen and a half before the frame is reached.
          const fetchNow = () => picture?.setAttribute("loading", "eager");
          ScrollTrigger.create({ trigger: el, start: "top 250%", once: true, onEnter: fetchNow });
          ScrollTrigger.create({
            ...enter,
            onEnter: () => {
              fetchNow();
              if (!picture || picture.complete) return play();
              picture.addEventListener("load", play, { once: true });
              picture.addEventListener("error", play, { once: true });
            },
          });
        }
        const drift = ratio ? 5 : 3;
        gsap.fromTo(img, { yPercent: -drift }, { yPercent: drift, ease: "none", scrollTrigger: { trigger: el, start: "top bottom", end: "bottom top", scrub: true } });
      });
      return onReady(run);
    },
    { scope: box },
  );

  if (ratio) {
    return (
      <div ref={box} data-reveal={eager ? undefined : "clip"} className={`relative overflow-hidden ${className}`} style={{ aspectRatio: ratio }}>
        {/* taller than the frame by the amount it drifts, so no edge ever shows */}
        <div ref={inner} className="absolute inset-x-0 -inset-y-[6%]">
          <Image src={src} alt={alt} fill sizes={sizes} preload={eager} className="object-cover" />
          {children}
        </div>
      </div>
    );
  }

  return (
    <div ref={box} data-reveal={eager ? undefined : "clip"} className={className}>
      {/* a clip laid over the picture is taller than it: what sticks out is cut here */}
      <div ref={inner} className="relative overflow-hidden">
        <Image src={src} alt={alt} width={width} height={height} sizes={sizes} preload={eager} className="h-auto w-full" />
        {children}
      </div>
    </div>
  );
}
