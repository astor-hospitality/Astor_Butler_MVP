"use client";

import { useEffect, useRef, type CSSProperties } from "react";
import { motionAllowed } from "@/lib/motion";
import { FadeImage } from "../FadeImage";
import type { RailItem } from "./WorksRail";

interface WorkTileProps {
  item: RailItem;
  /** Opens the player with the work's full film. */
  onOpen: () => void;
  /** The small plus that puts the work on the storyboard; left out, the tile has none. */
  board?: { on: boolean; onToggle: () => void; add: string; remove: string };
  /** The word in the pointer over the picture, and the start of the button's name for a screen reader. */
  watch: string;
  /** The caption, when it is not the work's whole title (a row of one direction shows the name the work is known by). */
  title?: string;
  /** The small line under the title: the direction on the home page, what the work is in a row of one direction. */
  note?: string;
  /** A copy of the tile in the second half of a looping row: seen, but not reached by the keyboard or read twice. */
  copy?: boolean;
}

/** Wider than this, a frame is shown as a wide tile (16:9); narrower, as an upright one (see `.tile` in globals.css). */
const WIDE = 1.2;

/**
 * All the tiles of the site share one watcher: a tile's short clip is fetched and played while the tile is on screen,
 * and paused as soon as it leaves (a row of thirty films playing off screen would eat the phone). The watcher sees
 * through the rows' own clipping, so a tile that has drifted out of its row counts as off screen.
 */
let watcher: IntersectionObserver | null = null;
function watch(video: HTMLVideoElement): () => void {
  watcher ??= new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const clip = entry.target as HTMLVideoElement;
        if (entry.isIntersecting) {
          if (!clip.getAttribute("src") && clip.dataset.src) clip.src = clip.dataset.src;
          clip.play().catch(() => {});
        } else {
          clip.pause();
        }
      }
    },
    { threshold: 0.2 },
  );
  watcher.observe(video);
  return () => watcher?.unobserve(video);
}

/** "BASTA – итоговый ролик": the name the work is known by, and what it is. */
export function splitTitle(title: string): [name: string, what: string] {
  const at = title.indexOf(" – ");
  return at < 0 ? [title, ""] : [title.slice(0, at), title.slice(at + 3)];
}

/**
 * One work, the same everywhere on the site (the works on the home page, the rows of the directions, a direction's
 * own page): a frame of the film in a wide tile, playing its short silent cut by itself while it is on screen,
 * and the caption under it, the title and one small line. A work with no cut yet shows its still.
 * A click opens the player with the full film and its sound.
 */
export function WorkTile({ item, onOpen, board, watch: watchLabel, title = item.title, note, copy = false }: WorkTileProps) {
  const video = useRef<HTMLVideoElement>(null);
  const wide = item.frame.width / item.frame.height >= WIDE;

  useEffect(() => {
    const el = video.current;
    if (!el || !motionAllowed()) return;
    // a visitor who asked the browser to save data gets the stills
    if ((navigator as Navigator & { connection?: { saveData?: boolean } }).connection?.saveData) return;
    const shown = () => (el.dataset.on = "true");
    el.addEventListener("playing", shown);
    const stop = watch(el);
    return () => {
      stop();
      el.removeEventListener("playing", shown);
    };
  }, [item.clip]);

  return (
    <li className={`tile ${wide ? "is-wide" : "is-tall"}`} style={{ "--frame": `${item.frame.width} / ${item.frame.height}` } as CSSProperties} aria-hidden={copy || undefined}>
      <div className="tile-media">
        <button type="button" tabIndex={copy ? -1 : undefined} aria-label={`${watchLabel}: ${item.title}`} data-cursor={watchLabel} onClick={onOpen} className="tile-open focus-visible:-outline-offset-4">
          <FadeImage src={item.frame.src} alt={copy ? "" : item.frame.alt} fill sizes={wide ? "(min-width: 768px) 34vw, 82vw" : "(min-width: 768px) 18vw, 40vw"} draggable={false} />
          {item.clip ? <video ref={video} muted loop playsInline preload="none" tabIndex={-1} aria-hidden="true" data-src={item.clip} /> : null}
        </button>
        {board ? (
          <button
            type="button"
            tabIndex={copy ? -1 : undefined}
            aria-pressed={board.on}
            aria-label={`${board.on ? board.remove : board.add}: ${item.title}`}
            title={board.on ? board.remove : board.add}
            data-cursor=""
            onClick={board.onToggle}
            className="tile-plus focus-visible:-outline-offset-4"
          >
            <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.75" aria-hidden="true">
              {board.on ? <path d="M2.5 8.5l3.5 3.5 7.5-8" /> : <path d="M8 2v12M2 8h12" />}
            </svg>
          </button>
        ) : null}
      </div>
      <p className="tile-title">{title}</p>
      {note ? <p className="caps tile-note">{note}</p> : null}
    </li>
  );
}
