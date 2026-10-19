"use client";

import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type PointerEvent as ReactPointerEvent } from "react";
import type { WorksTexts } from "@content/site";
import type { Picture } from "@content/types";
import { toggleOnBoard, useBoard } from "@/lib/board";
import { ASK_EVENT } from "@/lib/clio";
import { gsap, motionAllowed, useGSAP } from "@/lib/motion";
import { Reveal } from "../Reveal";
import { VideoLightbox } from "./VideoLightbox";
import { WorkPost } from "./WorkPost";

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
  /** Its short silent preview: plays in the card under the pointer. */
  clip?: string;
  /** The name its full film is asked for by (see /api/watch). */
  watch?: string;
}

interface WorksRailProps {
  items: RailItem[];
  /** The directions to choose from, in the order of the site: all seven, with or without works. */
  groups?: { key: string; title: string }[];
  /** How many more works the studio's archive holds; the last card of the rail says so. */
  archive?: number;
  /** On a direction's own page the same cards stand in a grid, with no filters. */
  grid?: boolean;
  t: WorksTexts;
}

/** The chosen direction lives in the address (`?dir=ai`), so a link to the page can carry it. */
const DIR_EVENT = "c3:dir";
const subscribeDir = (tell: () => void) => {
  window.addEventListener("popstate", tell);
  window.addEventListener(DIR_EVENT, tell);
  return () => {
    window.removeEventListener("popstate", tell);
    window.removeEventListener(DIR_EVENT, tell);
  };
};
const readDir = () => new URLSearchParams(window.location.search).get("dir");
function writeDir(key: string | null) {
  const url = new URL(window.location.href);
  if (key) url.searchParams.set("dir", key);
  else url.searchParams.delete("dir");
  window.history.replaceState(window.history.state, "", url);
  window.dispatchEvent(new Event(DIR_EVENT));
}

/** "ещё 34 работы": 1 работа, 2 работы, 5 работ. */
function worksWord(n: number): string {
  const tens = n % 100;
  const ones = n % 10;
  return tens >= 11 && tens <= 14 ? "работ" : ones === 1 ? "работа" : ones >= 2 && ones <= 4 ? "работы" : "работ";
}

/**
 * The works as a rail of upright cards. It starts on the left margin and runs off the right edge of the screen:
 * the card cut by the edge says there is more. It drifts on slowly by itself, and stops for the visitor: it is pulled
 * with the mouse, swiped on a phone or moved with the keyboard, and a pull stops on a card; a thin red line under it shows how far along it is.
 * The chips above narrow it to one direction: the cards leave, the new ones come, the rail starts again.
 * A card plays its short film, without sound, under the pointer (on a phone, the card that stands whole on screen);
 * a click opens the player with the film in full. The plus puts the work on the storyboard for the visitor's own shoot.
 */
export function WorksRail({ items, groups, archive = 0, grid = false, t }: WorksRailProps) {
  const dir = useSyncExternalStore(subscribeDir, readDir, () => null);
  const group = groups?.some((item) => item.key === dir) ? dir : null;
  const shown = useMemo(() => (group ? items.filter((item) => item.group === group) : items), [items, group]);
  const [open, setOpen] = useState<number | null>(null);
  const marked = useBoard();
  const track = useRef<HTMLUListElement>(null);
  const bar = useRef<HTMLElement>(null);
  const dragged = useRef(false);
  const leaving = useRef(false);
  // the chip pressed last, shown pressed at once while the cards are still leaving; a second press in that moment wins
  const [picked, setPicked] = useState<{ key: string | null } | null>(null);
  const chosen = picked ? picked.key : group;

  /** Where each card starts, in the track's own scroll units. */
  const starts = useCallback(() => {
    const el = track.current;
    if (!el) return [];
    const origin = (el.firstElementChild as HTMLElement | null)?.offsetLeft ?? 0;
    return Array.from(el.children, (card) => (card as HTMLElement).offsetLeft - origin);
  }, []);

  // A glide in progress, if any. While it runs the track does not snap to the cards: the two would fight.
  const glide = useRef<gsap.core.Tween | null>(null);
  const settle = useCallback(() => {
    glide.current?.kill();
    glide.current = null;
    if (track.current) track.current.style.scrollSnapType = "";
  }, []);
  const glideTo = useCallback(
    (left: number, seconds: number, ease: string) => {
      const el = track.current;
      if (!el) return;
      const to = Math.max(0, Math.min(left, el.scrollWidth - el.clientWidth));
      settle();
      if (!motionAllowed()) return void el.scrollTo({ left: to, behavior: "auto" });
      const at = { x: el.scrollLeft };
      el.style.scrollSnapType = "none";
      glide.current = gsap.to(at, { x: to, duration: seconds, ease, onUpdate: () => (el.scrollLeft = at.x), onComplete: settle });
    },
    [settle],
  );
  useEffect(() => settle, [settle]);

  // The rail moves on by itself, slowly (Roma, 2026-10-16: «чтобы карточки сами прокручивались, не спеша»): a steady
  // drift while it is on screen and nobody is busy with it. A pointer over it, a finger on it, a pull, the wheel, the
  // keyboard or the open player stop it; it goes on a few seconds after the visitor lets go. At the end it waits a
  // moment, fades out and comes back at the first card (a glide back across every card was a blur). While it drifts it does not snap to the cards (the two would fight);
  // a pull still ends on a card. With "reduce motion" nothing moves by itself.
  useEffect(() => {
    const el = track.current;
    if (!el || grid || open !== null || !motionAllowed()) return;
    const speed = window.matchMedia("(hover: none)").matches ? 22 : 30; // px a second
    let frame = 0;
    let last = 0;
    let pos = el.scrollLeft; // kept as a fraction: the browser rounds scrollLeft, and a slow drift is less than a pixel a frame
    let seen = false;
    let hover = false;
    let touch = false;
    let focus = false;
    let quietUntil = 0;
    let endAt = 0;
    let rewinding = false;
    const rest = (ms: number) => (quietUntil = performance.now() + ms);

    const tick = (now: number) => {
      frame = requestAnimationFrame(tick);
      const dt = Math.min(0.05, (now - (last || now)) / 1000);
      last = now;
      if (!seen || hover || touch || focus || rewinding || leaving.current || glide.current || now < quietUntil) {
        pos = el.scrollLeft;
        return;
      }
      const max = el.scrollWidth - el.clientWidth;
      if (max <= 0) return;
      // moved by the visitor (wheel, trackpad, keys, a swipe still gliding): let it rest, then go on from there
      if (Math.abs(el.scrollLeft - pos) > 2) {
        pos = el.scrollLeft;
        rest(3500);
        return;
      }
      if (pos >= max - 1) {
        endAt ||= now;
        if (now - endAt > 2500) {
          endAt = 0;
          rewinding = true;
          gsap.to(el, {
            opacity: 0,
            duration: 0.45,
            ease: "power2.in",
            onComplete: () => {
              el.scrollLeft = pos = 0;
              gsap.to(el, { opacity: 1, duration: 0.7, ease: "power2.out", clearProps: "opacity", onComplete: () => void (rewinding = false) });
              rest(2000);
            },
          });
        }
        return;
      }
      el.style.scrollSnapType = "none";
      pos = Math.min(max, pos + speed * dt);
      el.scrollLeft = pos;
    };

    const onEnter = (e: PointerEvent) => {
      if (e.pointerType === "mouse") hover = true;
    };
    const onLeave = (e: PointerEvent) => {
      if (e.pointerType !== "mouse") return;
      hover = false;
      rest(1200);
    };
    const onDown = (e: PointerEvent) => {
      if (e.pointerType !== "mouse") touch = true;
    };
    const onUp = (e: PointerEvent) => {
      if (e.pointerType === "mouse" || !touch) return;
      touch = false;
      rest(3500);
    };
    const onFocusIn = () => (focus = true);
    const onFocusOut = (e: FocusEvent) => {
      if (el.contains(e.relatedTarget as Node | null)) return;
      focus = false;
      rest(1200);
    };
    const watch = new IntersectionObserver(
      ([entry]) => {
        seen = entry.isIntersecting;
        if (seen) rest(1200);
      },
      { threshold: 0.3 },
    );
    watch.observe(el);
    el.addEventListener("pointerenter", onEnter);
    el.addEventListener("pointerleave", onLeave);
    el.addEventListener("pointerdown", onDown);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
    el.addEventListener("focusin", onFocusIn);
    el.addEventListener("focusout", onFocusOut);
    frame = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(frame);
      gsap.killTweensOf(el);
      el.style.opacity = "";
      watch.disconnect();
      el.removeEventListener("pointerenter", onEnter);
      el.removeEventListener("pointerleave", onLeave);
      el.removeEventListener("pointerdown", onDown);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
      el.removeEventListener("focusin", onFocusIn);
      el.removeEventListener("focusout", onFocusOut);
      if (!glide.current) el.style.scrollSnapType = "";
    };
  }, [shown, grid, open]);

  // The red line follows the track, however it was moved.
  useEffect(() => {
    const el = track.current;
    if (!el) return;
    let frame = 0;
    const measure = () => {
      frame = 0;
      bar.current?.style.setProperty("--at", String(Math.min(1, (el.scrollLeft + el.clientWidth) / Math.max(1, el.scrollWidth))));
    };
    const onScroll = () => {
      frame ||= requestAnimationFrame(measure);
    };
    measure();
    el.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll);
    return () => {
      el.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
      cancelAnimationFrame(frame);
    };
  }, [shown, grid]);

  // Another direction: the new cards come in one after another, and the rail starts from its first card.
  const first = useRef(true);
  useGSAP(
    () => {
      const el = track.current;
      if (first.current) {
        first.current = false;
        return;
      }
      leaving.current = false;
      if (!el) return;
      settle();
      el.scrollTo({ left: 0, behavior: "auto" });
      if (!motionAllowed()) return;
      gsap.fromTo(el.children, { autoAlpha: 0, y: 12 }, { autoAlpha: 1, y: 0, duration: 0.5, ease: "power3.out", stagger: 0.03, clearProps: "opacity,visibility,transform" });
    },
    { dependencies: [group] },
  );

  /** The cards on screen leave, then the direction changes. Only the cards in sight take part: all thirty-odd one
   *  after another kept the rail empty for over a second, and a second press in that time was lost. */
  const next = useRef<string | null>(null);
  const choose = (key: string | null) => {
    if (key === chosen) return;
    next.current = key;
    setPicked({ key });
    if (leaving.current) return;
    const el = track.current;
    const done = () => {
      setPicked(null);
      if (next.current !== group) return writeDir(next.current);
      // pressed away and straight back: the direction stays, the cards come back
      leaving.current = false;
      if (el) gsap.to(el.children, { autoAlpha: 1, y: 0, duration: 0.3, ease: "power3.out", clearProps: "opacity,visibility,transform" });
    };
    if (!el || !motionAllowed() || el.children.length === 0) return done();
    leaving.current = true;
    const box = el.getBoundingClientRect();
    const inSight = Array.from(el.children).filter((card) => {
      const r = card.getBoundingClientRect();
      return r.right > box.left && r.left < box.right;
    });
    gsap.to(inSight, { autoAlpha: 0, y: 12, duration: 0.22, ease: "power2.in", stagger: 0.03, onComplete: done });
  };

  // A mouse cannot swipe, so the rail can be taken and pulled; let go, it glides on to the next card that way.
  const pull = (e: ReactPointerEvent<HTMLUListElement>) => {
    const el = track.current;
    if (!el || grid || e.pointerType !== "mouse" || e.button !== 0) return;
    const fromX = e.clientX;
    const fromLeft = el.scrollLeft;
    dragged.current = false;
    const move = (ev: PointerEvent) => {
      const dx = ev.clientX - fromX;
      if (!dragged.current && Math.abs(dx) < 5) return;
      dragged.current = true;
      settle();
      el.style.scrollSnapType = "none";
      el.scrollLeft = fromLeft - dx;
    };
    const drop = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", drop);
      window.removeEventListener("pointercancel", drop);
      if (!dragged.current) return;
      // the snapping stays off until the glide has landed: switched on now, it would pull the track back first
      const list = starts();
      const forward = el.scrollLeft > fromLeft;
      const ahead = list.find((left) => left >= el.scrollLeft - 1);
      const behind = [...list].reverse().find((left) => left <= el.scrollLeft + 1);
      glideTo((forward ? ahead : behind) ?? (forward ? el.scrollWidth : 0), 0.9, "power4.out");
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", drop);
    window.addEventListener("pointercancel", drop);
  };

  // Under the pointer a card plays its short film; the film is fetched only then.
  const play = (card: HTMLElement) => {
    const video = card.querySelector("video");
    if (!video || !motionAllowed()) return;
    if (!video.getAttribute("src") && video.dataset.src) video.src = video.dataset.src;
    video.currentTime = 0;
    video
      .play()
      .then(() => (video.dataset.on = "true"))
      .catch(() => {});
  };
  const stop = (card: HTMLElement) => {
    const video = card.querySelector("video");
    if (!video) return;
    video.pause();
    video.dataset.on = "false";
  };

  // A phone has no pointer to hover with: there the card that stands whole on screen plays, one at a time.
  useEffect(() => {
    const el = track.current;
    if (!el || !motionAllowed() || !window.matchMedia("(hover: none)").matches) return;
    const watch = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => (entry.isIntersecting ? play(entry.target as HTMLElement) : stop(entry.target as HTMLElement)));
      },
      { threshold: 0.9 },
    );
    Array.from(el.children).forEach((card) => watch.observe(card));
    return () => watch.disconnect();
  }, [shown]);

  const ask = (phrase: string) => window.dispatchEvent(new CustomEvent<string>(ASK_EVENT, { detail: phrase }));
  const empty = groups && group && shown.length === 0;

  // every work as a post of the studio's carousel (see WorkPost)
  const cards = shown.map((item, i) => (
    <li key={item.id} className="card">
      <WorkPost
        item={item}
        on={marked.includes(item.id)}
        labels={{ watch: t.watch, add: t.add, remove: t.remove }}
        onOpen={() => setOpen(i)}
        onToggle={() => toggleOnBoard(item.id)}
        onPointerEnter={(e) => e.pointerType === "mouse" && play(e.currentTarget)}
        onPointerLeave={(e) => e.pointerType === "mouse" && stop(e.currentTarget)}
      />
    </li>
  ));

  return (
    <>
      {groups ? (
        // no arrows beside the filters (Roma, 2026-10-16): the rail is pulled with the mouse or swiped, and the chips have the whole row
        <Reveal className="mt-[clamp(2rem,6vh,3.5rem)]">
          <ul aria-label={t.filter} className="chips caps">
            <li>
              <button type="button" aria-pressed={chosen === null} onClick={() => choose(null)} className="chip">
                {t.all}
              </button>
            </li>
            {groups.map((item) => (
              <li key={item.key}>
                <button type="button" aria-pressed={chosen === item.key} onClick={() => choose(item.key)} className="chip">
                  {item.title}
                </button>
              </li>
            ))}
          </ul>
        </Reveal>
      ) : null}

      {empty ? (
        // a direction that has no works to show yet: a red plate with the way to ask for them
        <div className="theme-red field mt-7 flex min-h-[18rem] flex-col items-start justify-between gap-10 p-[clamp(1.5rem,3vw,2.75rem)]">
          <p className="display-sm max-w-[20ch]">{t.empty}</p>
          <button type="button" onClick={() => ask(t.emptyAsk)} className="caps pill bg-white text-red">
            {t.emptyCta}
          </button>
        </div>
      ) : grid ? (
        <Reveal kind="clip">
          <ul ref={track} className="card-grid">
            {cards}
          </ul>
        </Reveal>
      ) : (
        <>
          <Reveal kind="clip" className="rail mt-7">
            <ul
              ref={track}
              tabIndex={0}
              aria-label={t.title}
              data-cursor={t.drag}
              className="rail-track focus-visible:-outline-offset-2"
              onPointerDown={pull}
              onDragStart={(e) => e.preventDefault()}
              onClickCapture={(e) => {
                // a pull that ends over a card is not a click on it
                if (!dragged.current) return;
                dragged.current = false;
                e.preventDefault();
                e.stopPropagation();
              }}
            >
              {cards}
              {archive > 0 && !group ? (
                <li className="card">
                  {/* the last post: the archive, the way into it is a talk with Clio */}
                  <div className="post">
                    <button type="button" onClick={() => ask(t.archiveAsk)} className="post-face post-archive focus-visible:-outline-offset-4">
                      <span className="caps">{t.archive}</span>
                      <span className="post-archive-count">
                        {t.archiveMore} {archive} {worksWord(archive)}
                      </span>
                    </button>
                  </div>
                </li>
              ) : null}
            </ul>
          </Reveal>
          <Reveal>
            <i ref={bar} aria-hidden="true" className="rail-bar mt-8 block">
              <i />
            </i>
          </Reveal>
        </>
      )}

      <Reveal as="p" className="caps mt-5 opacity-60 tabular-nums">
        {t.board}: {marked.length}. {t.hint}
      </Reveal>

      {open !== null ? <VideoLightbox items={shown} at={open} onMove={setOpen} onClose={() => setOpen(null)} t={t} /> : null}
    </>
  );
}
