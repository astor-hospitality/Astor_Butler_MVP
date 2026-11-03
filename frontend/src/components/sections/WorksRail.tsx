"use client";

import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import type { WorksTexts } from "@content/site";
import type { Picture } from "@content/types";
import { toggleOnBoard, useBoard } from "@/lib/board";
import { ASK_EVENT } from "@/lib/clio";
import { motionAllowed } from "@/lib/motion";
import { Reveal } from "../Reveal";
import { VideoLightbox } from "./VideoLightbox";
import { splitTitle, WorkTile } from "./WorkTile";

export interface RailItem {
  /** How the storyboard knows the work: its direction and name. */
  id: string;
  title: string;
  /** The key and the name of the direction it belongs to. */
  group: string;
  groupTitle: string;
  frame: Picture;
  /** A work that has a page of its own. */
  slug?: string;
  /** Its short silent preview: plays in the tile while the tile is on screen. */
  clip?: string;
  /** The name its full film is asked for by, when the film is in the studio's Disk folder (see /api/watch). */
  watch?: string;
  /** The id of its full film in the studio's Google Drive portfolio: it plays in Drive's own player. */
  drive?: string;
  /** The full address of its film as a stream (HLS, three qualities): it plays in the site's own player, before Drive.
   *  Only when the films' storage is set (NEXT_PUBLIC_VIDEO_BASE_URL, see lib/video.ts). */
  hls?: string;
}

interface WorksRailProps {
  items: RailItem[];
  t: WorksTexts;
  /** Read by screen readers as the name of the row. */
  label: string;
  /** The small line under each title: the direction (the home page, where all of them are mixed), or what the work is
   *  (a row of one direction, where the title is then the name alone). */
  note?: "group" | "what";
  /** How many more works the studio's archive holds; the last tile of the row says so and leads to Clio. */
  archive?: number;
  /** "В раскадровке: 3" and what the plus is for, under the row (the home page, where the storyboard is). */
  boardLine?: boolean;
  /** Pixels a second the row drifts by itself. */
  speed?: number;
  className?: string;
}

/** "ещё 16 работ": 1 работа, 2 работы, 5 работ. */
function worksWord(n: number): string {
  const tens = n % 100;
  const ones = n % 10;
  return tens >= 11 && tens <= 14 ? "работ" : ones === 1 ? "работа" : ones >= 2 && ones <= 4 ? "работы" : "работ";
}

/**
 * The works as a row of wide tiles on the field, running off the right edge of the screen (Sergey, 2026-10-26:
 * «ролики… должны были смещаться влево по анимации и сразу проигрываться»). Every tile plays its short silent cut by
 * itself while it is on screen, and the row drifts slowly to the left, without end: when it is longer than the screen
 * it is drawn twice, one copy after the other, and when the first copy has gone by the row is set back by its length,
 * which the eye cannot see. It slows to a stop under the pointer, under a finger, while a tile has the keyboard, while
 * the player is open and while it is off screen, and picks up again softly. It can be pulled with the mouse, swiped,
 * or moved with a sideways wheel; with "reduce motion" it never moves by itself. A click on a tile opens the player.
 */
export function WorksRail({ items, t, label, note = "group", archive = 0, boardLine = false, speed = 34, className = "" }: WorksRailProps) {
  const [open, setOpen] = useState<number | null>(null);
  const [loop, setLoop] = useState(false);
  const marked = useBoard();
  const view = useRef<HTMLDivElement>(null);
  const track = useRef<HTMLUListElement>(null);
  // where the row stands and how it moves; kept out of React, it changes every frame
  const at = useRef({ x: 0, v: 0, set: 0, pace: 0, quietUntil: 0, hover: false, touch: false, focus: false, drag: false, seen: false });
  const dragged = useRef(false);
  const opened = useRef(false);
  useEffect(() => {
    opened.current = open !== null;
  }, [open]);

  const count = items.length + (archive > 0 ? 1 : 0);
  // a row of upright works only (the vertical videos) is set taller, so its tiles are not slivers
  const tallRow = items.length > 0 && items.every((item) => item.frame.width / item.frame.height < 1.2);

  /** Puts the row at `x`, within one length of it when it loops, or within its own ends when it does not. */
  const place = (x: number) => {
    const el = track.current;
    const box = view.current;
    if (!el || !box) return;
    const s = at.current;
    if (loop && s.set > 0) {
      while (x > 0) x -= s.set;
      while (x <= -s.set) x += s.set;
    } else {
      x = Math.min(0, Math.max(x, Math.min(0, box.clientWidth - el.scrollWidth)));
    }
    s.x = x;
    el.style.transform = `translate3d(${x.toFixed(2)}px, 0, 0)`;
  };

  // How long one copy of the row is, and whether the row is longer than the screen at all.
  useLayoutEffect(() => {
    const el = track.current;
    const box = view.current;
    if (!el || !box) return;
    const measure = () => {
      const tiles = el.children;
      if (tiles.length < count || count === 0) return;
      const first = tiles[0] as HTMLElement;
      const last = tiles[count - 1] as HTMLElement;
      const gap = parseFloat(getComputedStyle(el).columnGap) || 0;
      const set = last.offsetLeft + last.offsetWidth - first.offsetLeft + gap;
      at.current.set = set;
      setLoop(count > 1 && first.offsetLeft + set - gap > box.clientWidth + 1);
    };
    measure();
    const sizes = new ResizeObserver(measure);
    sizes.observe(box);
    return () => sizes.disconnect();
  }, [count]);

  // The drift, the glide after a swipe and the soft stops, one frame at a time.
  useEffect(() => {
    const box = view.current;
    if (!box) return;
    const s = at.current;
    const drifts = loop && motionAllowed();
    let frame = 0;
    let last = 0;

    const tick = (now: number) => {
      frame = requestAnimationFrame(tick);
      const dt = Math.min(0.05, (now - (last || now)) / 1000);
      last = now;
      if (s.drag) return;
      let move = 0;
      // after a swipe the row glides on and slows down by itself
      if (Math.abs(s.v) > 4) {
        move += s.v * dt;
        s.v *= Math.pow(0.03, dt);
      } else s.v = 0;
      const busy = !s.seen || s.hover || s.touch || s.focus || opened.current || now < s.quietUntil;
      // the drift starts and stops softly: a quarter of a second to a stop, nearly a second back to its pace
      s.pace = busy || !drifts ? Math.max(0, s.pace - dt / 0.25) : Math.min(1, s.pace + dt / 0.9);
      move -= speed * s.pace * dt;
      if (move !== 0) place(s.x + move);
    };
    const run = () => {
      if (!frame) {
        last = 0;
        frame = requestAnimationFrame(tick);
      }
    };
    const halt = () => {
      cancelAnimationFrame(frame);
      frame = 0;
    };
    // it starts once most of it is on screen, so it is not found already moved on
    const seen = new IntersectionObserver(
      ([entry]) => {
        s.seen = entry.intersectionRatio >= 0.5;
        if (s.seen) run();
        else if (!entry.isIntersecting) halt();
      },
      { threshold: [0, 0.5] },
    );
    seen.observe(box);

    const onEnter = (e: PointerEvent) => void (e.pointerType === "mouse" && (s.hover = true));
    const onLeave = (e: PointerEvent) => void (e.pointerType === "mouse" && (s.hover = false));
    const onWheel = (e: WheelEvent) => {
      // only a sideways wheel (a trackpad, or Shift with a mouse) moves the row; an upright one scrolls the page
      const dx = e.shiftKey && !e.deltaX ? e.deltaY : e.deltaX;
      if (Math.abs(dx) <= Math.abs(e.shiftKey ? 0 : e.deltaY)) return;
      e.preventDefault();
      s.v = 0;
      s.quietUntil = performance.now() + 2500;
      place(s.x - dx);
    };
    // a tile reached with the keyboard is brought into sight
    const onFocusIn = (e: FocusEvent) => {
      s.focus = true;
      const tile = (e.target as HTMLElement).closest("li");
      if (!tile) return;
      const r = tile.getBoundingClientRect();
      const b = box.getBoundingClientRect();
      const margin = Math.min(64, b.width * 0.06);
      if (r.left < b.left + margin) place(s.x + (b.left + margin - r.left));
      else if (r.right > b.right - margin) place(s.x - (r.right - (b.right - margin)));
    };
    const onFocusOut = (e: FocusEvent) => {
      if (box.contains(e.relatedTarget as Node | null)) return;
      s.focus = false;
      s.quietUntil = performance.now() + 1200;
    };
    box.addEventListener("pointerenter", onEnter);
    box.addEventListener("pointerleave", onLeave);
    box.addEventListener("wheel", onWheel, { passive: false });
    box.addEventListener("focusin", onFocusIn);
    box.addEventListener("focusout", onFocusOut);
    return () => {
      halt();
      seen.disconnect();
      box.removeEventListener("pointerenter", onEnter);
      box.removeEventListener("pointerleave", onLeave);
      box.removeEventListener("wheel", onWheel);
      box.removeEventListener("focusin", onFocusIn);
      box.removeEventListener("focusout", onFocusOut);
    };
    // `place` reads `loop` from this render, and the effect starts again whenever it changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loop, speed]);

  // A row as long as the screen or longer is taken in hand: pulled with the mouse, swiped with a finger.
  // An upright swipe is left to the page (touch-action: pan-y), so the page still scrolls over the row.
  const pull = (e: ReactPointerEvent<HTMLDivElement>) => {
    const box = view.current;
    if (!box || (e.pointerType === "mouse" && e.button !== 0)) return;
    const s = at.current;
    const fromX = e.clientX;
    const fromY = e.clientY;
    const x0 = s.x;
    let lastX = e.clientX;
    let lastT = performance.now();
    let v = 0;
    dragged.current = false;
    if (e.pointerType !== "mouse") s.touch = true;
    s.v = 0;
    const move = (ev: PointerEvent) => {
      const dx = ev.clientX - fromX;
      if (!s.drag) {
        if (Math.abs(dx) < 6 || Math.abs(dx) < Math.abs(ev.clientY - fromY)) return;
        s.drag = true;
        dragged.current = true;
      }
      const now = performance.now();
      const dt = Math.max(1, now - lastT) / 1000;
      v = v * 0.6 + ((ev.clientX - lastX) / dt) * 0.4;
      lastX = ev.clientX;
      lastT = now;
      place(x0 + dx);
    };
    const drop = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", drop);
      window.removeEventListener("pointercancel", drop);
      s.touch = false;
      if (s.drag) {
        s.drag = false;
        // a pull that stopped before it was let go does not glide on
        s.v = performance.now() - lastT > 80 ? 0 : Math.max(-2600, Math.min(2600, v));
        s.quietUntil = performance.now() + 2500;
      } else if (e.pointerType !== "mouse") s.quietUntil = performance.now() + 1500;
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", drop);
    window.addEventListener("pointercancel", drop);
  };

  if (items.length === 0) return null;

  const ask = (phrase: string) => window.dispatchEvent(new CustomEvent<string>(ASK_EVENT, { detail: phrase }));
  const tiles = (copy: boolean) =>
    items.map((item, i) => {
      const [name, what] = splitTitle(item.title);
      const on = marked.includes(item.id);
      return (
        <WorkTile
          key={`${copy ? "copy" : "own"}:${item.id}`}
          item={item}
          copy={copy}
          watch={t.watch}
          title={note === "what" ? name : item.title}
          note={note === "what" ? what : item.groupTitle}
          onOpen={() => setOpen(i)}
          board={{ on, onToggle: () => toggleOnBoard(item.id), add: t.add, remove: t.remove }}
        />
      );
    });
  const archiveTile = (copy: boolean) =>
    archive > 0 ? (
      <li key={copy ? "copy:archive" : "archive"} className="tile is-wide" aria-hidden={copy || undefined}>
        {/* the last tile: how much more the archive holds; the way into it is a talk with Clio */}
        <button type="button" tabIndex={copy ? -1 : undefined} onClick={() => ask(t.archiveAsk)} className="tile-media tile-archive focus-visible:-outline-offset-4">
          <span className="caps">{t.archive}</span>
          <span className="tile-archive-count">
            {t.archiveMore} {archive} {worksWord(archive)}
          </span>
        </button>
      </li>
    ) : null;

  return (
    <>
      <Reveal kind="clip" className={`rail ${className}`}>
        <div
          ref={view}
          role="region"
          aria-label={label}
          data-cursor={loop ? t.drag : undefined}
          data-tall={tallRow || undefined}
          data-loop={loop || undefined}
          className="rail-view"
          onPointerDown={pull}
          onDragStart={(e) => e.preventDefault()}
          onClickCapture={(e) => {
            // a pull that ends over a tile is not a click on it
            if (!dragged.current) return;
            dragged.current = false;
            e.preventDefault();
            e.stopPropagation();
          }}
        >
          <ul ref={track} className="rail-track">
            {tiles(false)}
            {archiveTile(false)}
            {loop ? tiles(true) : null}
            {loop ? archiveTile(true) : null}
          </ul>
        </div>
      </Reveal>

      {boardLine ? (
        <Reveal as="p" className="caps mt-6 opacity-80 tabular-nums">
          {t.board}: {marked.length}. {t.hint}
        </Reveal>
      ) : null}

      {open !== null ? <VideoLightbox items={items} at={open} onMove={setOpen} onClose={() => setOpen(null)} t={t} /> : null}
    </>
  );
}
