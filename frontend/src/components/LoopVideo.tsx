"use client";

import { useEffect, useRef } from "react";
import type { Clip } from "@content/types";
import { motionAllowed } from "@/lib/motion";

/**
 * A silent looping clip laid exactly over its still picture. It loads and plays only while the visitor stays on it.
 * The clip is taller than the picture in it (empty bands above and below), so it is sized by the picture, not by its own frame.
 * With "reduce motion" nothing is loaded and the still stays.
 */
export function LoopVideo({ clip }: { clip: Clip }) {
  const ref = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    const video = ref.current;
    if (!video || !motionAllowed()) return;
    // A clip weighs megabytes and would take the line from the pictures further down the page. So it is fetched only
    // for a visitor who stays on it for a moment, and a clip that has not arrived in full is let go when it leaves the screen.
    let wait: number | undefined;
    const whole = () => video.buffered.length > 0 && Number.isFinite(video.duration) && video.buffered.end(video.buffered.length - 1) >= video.duration - 0.3;
    const watch = new IntersectionObserver(
      ([entry]) => {
        window.clearTimeout(wait);
        if (entry.isIntersecting) {
          wait = window.setTimeout(() => {
            if (!video.getAttribute("src")) video.src = clip.src;
            video.play().catch(() => {});
          }, 1200);
        } else {
          video.pause();
          if (video.getAttribute("src") && !whole()) {
            video.removeAttribute("src");
            video.load();
          }
        }
      },
      { threshold: 0.2 },
    );
    watch.observe(video);
    return () => {
      window.clearTimeout(wait);
      watch.disconnect();
    };
  }, [clip.src]);

  // A clip cut to the frame's own shape simply covers the frame, a hair larger than it: sized from its height,
  // it came out a pixel narrower and the still showed through as a thin line at the right edge.
  if (clip.pictureShare === 1) {
    return <video ref={ref} muted loop playsInline preload="none" aria-hidden="true" tabIndex={-1} className="absolute inset-0 h-full w-full scale-[1.01] object-cover" />;
  }
  return (
    <video
      ref={ref}
      muted
      loop
      playsInline
      preload="none"
      aria-hidden="true"
      tabIndex={-1}
      className="absolute left-1/2 top-1/2 max-w-none -translate-x-1/2 -translate-y-1/2"
      style={{ height: `${100 / clip.pictureShare}%`, aspectRatio: `${clip.width} / ${clip.height}` }}
    />
  );
}
