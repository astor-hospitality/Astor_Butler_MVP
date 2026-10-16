import type { CSSProperties, PointerEvent as ReactPointerEvent } from "react";
import { FadeImage } from "../FadeImage";
import type { RailItem } from "./WorksRail";

interface WorkPostProps {
  item: RailItem;
  /** Whether the work is on the visitor's storyboard. */
  on: boolean;
  labels: { watch: string; add: string; remove: string };
  onOpen: () => void;
  onToggle: () => void;
  onPointerEnter: (e: ReactPointerEvent<HTMLButtonElement>) => void;
  onPointerLeave: (e: ReactPointerEvent<HTMLButtonElement>) => void;
}

/** "BASTA – итоговый ролик": the name the work is known by, and what it is. A title without a dash is all name. */
function splitTitle(title: string): [name: string, what: string] {
  const at = title.indexOf(" – ");
  return at < 0 ? [title, ""] : [title.slice(0, at), title.slice(at + 3)];
}

/**
 * A work as a post of the studio's Instagram carousel (Roma's word, 2026-10-16, after the studio's example YAD.mp4):
 * a red 4:5 field, the name of the work in heavy white capitals where the carousel has the chrome title, the direction and
 * a line of what the work is in small capitals, and the work itself. A wide work plays along the foot of the post in
 * its own shape, as in the example; an upright one stands on the right, whole, so a vertical clip is never cut to a band.
 * Under the pointer the post lights up, an upright work plays its short clip; a click opens the player.
 */
export function WorkPost({ item, on, labels, onOpen, onToggle, onPointerEnter, onPointerLeave }: WorkPostProps) {
  const tall = item.frame.height > item.frame.width;
  const [name, what] = splitTitle(item.title);
  const media = tall ? undefined : ({ aspectRatio: `${item.frame.width} / ${item.frame.height}` } as CSSProperties);

  return (
    <div className={`post ${tall ? "is-tall" : "is-wide"}`}>
      <button type="button" aria-label={`${labels.watch}: ${item.title}`} onClick={onOpen} onPointerEnter={onPointerEnter} onPointerLeave={onPointerLeave} className="post-face sheen focus-visible:-outline-offset-4">
        <span className="post-text">
          <span className="caps post-dir">{item.groupTitle}</span>
          <span className="post-name" aria-hidden="true">
            {name}
          </span>
          {what ? <span className="caps post-what">{what}</span> : null}
        </span>
        <span className="post-media" style={media}>
          <FadeImage src={item.frame.src} alt={item.frame.alt} fill sizes={tall ? "(min-width: 768px) 12vw, 36vw" : "(min-width: 768px) 25vw, 78vw"} draggable={false} />
          {/* the short clips are cut upright: they play only where the post shows the work upright */}
          {item.clip && tall ? <video muted loop playsInline preload="none" tabIndex={-1} aria-hidden="true" data-src={item.clip} /> : null}
        </span>
      </button>
      <button type="button" aria-pressed={on} aria-label={`${on ? labels.remove : labels.add}: ${item.title}`} onClick={onToggle} className="post-plus focus-visible:-outline-offset-4">
        <svg viewBox="0 0 16 16" className="size-[1.125rem]" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
          {on ? <path d="M2.5 8.5l3.5 3.5 7.5-8" /> : <path d="M8 2v12M2 8h12" />}
        </svg>
      </button>
    </div>
  );
}
