"use client";

import Link from "next/link";
import { useEffect, useRef, useState, type CSSProperties } from "react";
import { createPortal } from "react-dom";
import type { WorksTexts } from "@content/site";
import { ChromeIcon } from "../ChromeIcon";
import { FilmPlayer } from "./FilmPlayer";
import type { RailItem } from "./WorksRail";

interface VideoLightboxProps {
  items: RailItem[];
  /** Which of them is playing. */
  at: number;
  onMove: (to: number) => void;
  onClose: () => void;
  t: WorksTexts;
}

/** Said under a work whose stream did not come and which has no other film to fall back to. */
const UNSTREAMED = "Фильм не загрузился – проверьте интернет и откройте его ещё раз.";

/**
 * The player: a dark sheet over the whole window with the work's film, with sound.
 * The arrows on screen lead to the neighbouring works, and so do Shift with ← and → on the keyboard; the arrows alone
 * do too, unless the site's own player is showing, where they move the film by five seconds the way every video
 * player does (the least surprise for someone watching). Escape or the cross closes it, on a phone a swipe down too.
 * A film streamed from the studio's storage (`hls`) plays in the site's own player (FilmPlayer) with a choice of
 * quality; if the stream does not come, the work falls back to what it had before it. A film from the studio's
 * Google Drive plays in Drive's own player, framed like the work's frame. Any other film is the work's own file in
 * the studio's Disk folder (see /api/watch). Such a file is a master, and a browser cannot play every master: when
 * it cannot, the short silent cut of the same work plays instead and a line says so.
 * It is drawn straight into the body, outside the page's own boxes (some of them are moved by the reveals,
 * and a moved box would hold a fixed sheet inside itself).
 */
export function VideoLightbox({ items, at, onMove, onClose, t }: VideoLightboxProps) {
  const item = items[at];
  const shut = useRef<HTMLButtonElement>(null);
  const touchY = useRef<number | null>(null);
  // the works whose full film did not play
  const [broken, setBroken] = useState<string[]>([]);
  // the works whose stream did not play
  const [unstreamed, setUnstreamed] = useState<string[]>([]);
  const own = Boolean(item?.hls && !unstreamed.includes(item.id));

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    const root = document.documentElement;
    shut.current?.focus({ preventScroll: true });
    root.classList.add("lenis-stopped");
    return () => {
      root.classList.remove("lenis-stopped");
      before?.focus?.({ preventScroll: true });
    };
  }, []);

  // A click into Drive's player takes the keyboard into its frame, where the page hears no keys: it is given back
  // at once (the click itself still lands in the player), so Escape and the arrows keep working.
  useEffect(() => {
    let timer = 0;
    const onBlur = () => {
      timer = window.setTimeout(() => {
        if (document.activeElement instanceof HTMLIFrameElement) shut.current?.focus({ preventScroll: true });
      }, 0);
    };
    window.addEventListener("blur", onBlur);
    return () => {
      window.removeEventListener("blur", onBlur);
      window.clearTimeout(timer);
    };
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      // Escape in full screen only leaves the full screen (the browser does it), the sheet stays
      if (e.key === "Escape" && !document.fullscreenElement) onClose();
      // the site's own player takes the bare arrows for the film, and a slider keeps its own
      if ((own && !e.shiftKey) || (e.target instanceof Element && e.target.closest("input"))) return;
      if (e.key === "ArrowRight") onMove((at + 1) % items.length);
      if (e.key === "ArrowLeft") onMove((at - 1 + items.length) % items.length);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [at, items.length, onMove, onClose, own]);

  if (!item) return null;
  const full = item.watch && !broken.includes(item.id) ? `/api/watch?work=${encodeURIComponent(item.watch)}` : undefined;
  // the shape of a Drive film, read off its frame: the box of the player keeps it, whatever the window
  const shape = { "--shape": item.frame.width / item.frame.height } as CSSProperties;
  const src = full ?? item.clip;
  const two = (n: number) => String(n).padStart(2, "0");
  // the chrome arrows stand bare over the film, no frame around them, and large enough to be seen on any picture (Roma, 2026-10-16)
  const arrow = "absolute top-1/2 grid size-20 -translate-y-1/2 place-items-center max-md:hidden [&>img]:transition-transform [&>img]:duration-300";

  return createPortal(
    <div
      className="lightbox"
      role="dialog"
      aria-modal="true"
      aria-label={item.title}
      data-lenis-prevent
      onTouchStart={(e) => (touchY.current = e.touches[0]?.clientY ?? null)}
      onTouchEnd={(e) => {
        const from = touchY.current;
        touchY.current = null;
        if (from !== null && (e.changedTouches[0]?.clientY ?? from) - from > 90) onClose();
      }}
    >
      <header className="wrap flex items-center justify-between py-4">
        <p className="caps tabular-nums">
          {two(at + 1)} / {two(items.length)}
        </p>
        <button ref={shut} type="button" aria-label={t.close} onClick={onClose} className="caps flex items-center gap-3 py-3">
          <span>{t.close}</span>
          <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
            <path d="M2 2l12 12M14 2L2 14" />
          </svg>
        </button>
      </header>

      {/* a flex box of the row's own height: in a grid cell sized to its content a tall film grew past the window */}
      <div className="relative flex min-h-0 items-center justify-center px-[var(--m)]">
        {own && item.hls ? (
          <FilmPlayer
            key={item.hls}
            src={item.hls}
            poster={item.frame.src}
            shape={item.frame.width / item.frame.height}
            title={item.title}
            onFail={() => setUnstreamed((list) => [...list, item.id])}
          />
        ) : item.drive ? (
          <div className="lightbox-drive">
            <iframe key={item.drive} src={`https://drive.google.com/file/d/${item.drive}/preview`} title={item.title} allow="autoplay; fullscreen" allowFullScreen style={shape} />
          </div>
        ) : src ? (
          <video
            key={src}
            src={src}
            poster={item.frame.src}
            controls
            autoPlay
            playsInline
            loop={!full}
            muted={!full}
            onError={() => {
              if (full) setBroken((list) => [...list, item.id]);
            }}
          />
        ) : (
          // eslint-disable-next-line @next/next/no-img-element -- the still of a work with no film at hand, shown as it is
          <img src={item.frame.src} alt={item.frame.alt} />
        )}
        <button type="button" aria-label={t.prev} onClick={() => onMove((at - 1 + items.length) % items.length)} className={`${arrow} left-6 hover:[&>img]:-translate-x-1.5`}>
          <ChromeIcon name="arrow" width={64} mirror />
        </button>
        <button type="button" aria-label={t.next} onClick={() => onMove((at + 1) % items.length)} className={`${arrow} right-6 hover:[&>img]:translate-x-1.5`}>
          <ChromeIcon name="arrow" width={64} />
        </button>
      </div>

      <footer className="wrap flex flex-wrap items-end justify-between gap-x-8 gap-y-3 py-5">
        <div>
          <h2 className="display-xs">{item.title}</h2>
          <p className="caps opacity-60">{item.groupTitle}</p>
        </div>
        <p className="caps flex flex-wrap items-center gap-x-6 gap-y-1">
          {item.watch && !full && !own ? <span className="opacity-60">{src ? t.fallback : t.noFilm}</span> : null}
          {/* a stream that did not come, for a work with nothing else to play: what happened and what to do */}
          {item.hls && !own && !item.drive && !item.watch && !src ? <span className="opacity-60">{UNSTREAMED}</span> : null}
          {item.slug ? (
            <Link href={`/work/${item.slug}`} onClick={onClose} className="py-2 underline underline-offset-4">
              {t.about}
            </Link>
          ) : null}
        </p>
        {/* on a phone there are no arrows on the film: the neighbours are reached from a row of their own at the very
            bottom, back at the left edge and on at the right, so they stand in the same place whatever the work's
            caption is (Roma, 2026-10-16: «стрелочки вечно куда-то перемещаются») */}
        <div className="-mx-3 flex w-full justify-between md:hidden">
          <button type="button" aria-label={t.prev} onClick={() => onMove((at - 1 + items.length) % items.length)} className="grid h-11 w-14 place-items-center">
            <ChromeIcon name="arrow" width={44} mirror />
          </button>
          <button type="button" aria-label={t.next} onClick={() => onMove((at + 1) % items.length)} className="grid h-11 w-14 place-items-center">
            <ChromeIcon name="arrow" width={44} />
          </button>
        </div>
      </footer>
    </div>,
    document.body,
  );
}
