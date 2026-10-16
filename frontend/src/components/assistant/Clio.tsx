"use client";

import Image from "next/image";
import { useEffect, useId, useRef, useState, type CSSProperties, type FormEvent } from "react";
import type { Hint } from "@content/hints";
import { policyHref, type ClioTexts, type ContactLink } from "@content/site";
import { ASK_EVENT, askClio, clioConnected, SEND_EVENT } from "@/lib/clio";
import { useHints } from "./useHints";

interface Line {
  from: "clio" | "you";
  text: string;
  /** The studio's contacts follow the text: for the lines that send the visitor to write directly. */
  contacts?: boolean;
}

interface ClioProps {
  t: ClioTexts;
  /** Where to write while the chat cannot answer. */
  links: ContactLink[];
  /** Her star as it looks on a red screen and, without red in it, on a white or a black one. */
  avatar: { red: string; plain: string };
}

/** Her face is the star of the mark as a chrome tube: open in the middle, so its heart is always the colour of the page under it. */
function Face({ src, sizes, className = "" }: { src: string; sizes: string; className?: string }) {
  return <Image src={src} alt="" fill sizes={sizes} className={`object-contain ${className}`} />;
}

/** How long a note takes to go away (the same as `clio-note-out` in globals.css). */
const NOTE_OUT_MS = 200;

/** Is motion on? Read at the moment of asking: the layout's inline script decides it before the first paint. */
const moving = () => document.documentElement.classList.contains("motion");

/**
 * Keeps the last note on the page for a moment after it was put away, so it can leave softly instead of vanishing.
 * Without motion it goes at once.
 */
function useLeaving<T>(value: T | null, ms: number): { shown: T | null; leaving: boolean } {
  const [shown, setShown] = useState(value);
  // a new note takes the place at once (state adjusted while rendering, the way React advises for a changed prop)
  if (value !== null && value !== shown) setShown(value);
  const leaving = value === null && shown !== null;
  useEffect(() => {
    if (!leaving) return;
    const timer = window.setTimeout(() => setShown(null), moving() ? ms : 0);
    return () => window.clearTimeout(timer);
  }, [leaving, ms]);
  return { shown, leaving };
}

/**
 * Clio travels with the visitor: a small chrome star in the corner of every screen, standing still, nothing around it.
 * A click opens her window, a light sheet in the thin red frame: no bubbles, no shadows, small capitals for who speaks.
 * On a phone the window is a sheet that rises from the bottom edge and leaves the upper part of the page in sight.
 *
 * Her motion is the quiet kind good product assistants have (Intercom's launcher, Vercel's panels): the star does not
 * move by itself; under the pointer it comes a little closer and light runs over the chrome once; the window grows out
 * of the star and goes back into it faster than it came; new lines rise a few pixels into place; while she writes, a slow
 * band of colour passes over the words. The steps themselves are in globals.css (search for `clio`).
 *
 * She is also a guide: while the visitor reads, she now and then shows a short note next to the star about the part of the
 * page on screen (the notes and their rules are in content/hints.ts, the deciding is in useHints). A click on a note
 * opens the window, and she starts the talk from that subject. The notes can be switched off, in a note or in the window.
 */
export function Clio({ t, links, avatar }: ClioProps) {
  const [open, setOpen] = useState(false);
  const [lines, setLines] = useState<Line[]>([{ from: "clio", text: t.greeting }]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const panelId = useId();
  const fieldId = useId();
  const dock = useRef<HTMLButtonElement>(null);
  const field = useRef<HTMLInputElement>(null);
  const thread = useRef<HTMLOListElement>(null);
  const guide = useHints(open);
  const { hint, close: closeHint, take: takeHint } = guide;
  const note = useLeaving(hint, NOTE_OUT_MS);
  const wasOpen = useRef(false);

  // The newest line is always in view: a new one glides in, and an opened window simply shows where the talk ended.
  useEffect(() => {
    const el = thread.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: wasOpen.current && open && moving() ? "smooth" : "instant" });
    wasOpen.current = open;
  }, [lines, busy, open]);

  // With a mouse the field is ready to type into at once; on a phone the keyboard waits for a tap.
  useEffect(() => {
    if (open && window.matchMedia("(pointer: fine)").matches) field.current?.focus({ preventScroll: true });
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setOpen(false);
      dock.current?.focus({ preventScroll: true });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  // A button elsewhere on the page can hand her a phrase: the window opens with it in the field.
  useEffect(() => {
    const onAsk = (e: Event) => {
      setDraft((e as CustomEvent<string>).detail ?? "");
      setOpen(true);
    };
    window.addEventListener(ASK_EVENT, onAsk);
    return () => window.removeEventListener(ASK_EVENT, onAsk);
  }, []);

  // A tap anywhere else puts the note away (it is not counted as closing it: only the cross is).
  useEffect(() => {
    if (!hint) return;
    const away = (e: MouseEvent) => {
      if (!(e.target as Element | null)?.closest(".clio-note, .clio-dock")) takeHint();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") closeHint();
    };
    document.addEventListener("click", away);
    window.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("click", away);
      window.removeEventListener("keydown", onKey);
    };
  }, [hint, closeHint, takeHint]);

  const say = (line: Line) => setLines((all) => [...all, line]);

  /** One message from the visitor, and her answer to it. */
  const deliver = async (text: string) => {
    say({ from: "you", text });
    setBusy(true);
    try {
      if (!clioConnected) {
        // Without the server her answer is ready at once, but it still comes after a short "Clio пишет":
        // a reply that lands in the same instant as the question reads as a machine, not as someone answering.
        await new Promise((done) => window.setTimeout(done, 700));
        say({ from: "clio", text: t.offline, contacts: true });
        return;
      }
      say({ from: "clio", text: await askClio(text) });
    } catch {
      say({ from: "clio", text: t.failed, contacts: true });
    } finally {
      setBusy(false);
    }
  };
  const deliverNow = useRef(deliver);
  useEffect(() => {
    deliverNow.current = deliver;
  });

  // The shoot configurator speaks for the visitor: its message is sent as theirs and the window opens on it.
  useEffect(() => {
    const onSend = (e: Event) => {
      const text = ((e as CustomEvent<string>).detail ?? "").trim();
      if (!text) return;
      setOpen(true);
      void deliverNow.current(text);
    };
    window.addEventListener(SEND_EVENT, onSend);
    return () => window.removeEventListener(SEND_EVENT, onSend);
  }, []);

  /** The note was taken up: the window opens and she goes on from the note's subject. */
  const answer = (from: Hint) => {
    takeHint();
    setLines((all) => (all.at(-1)?.text === from.opener ? all : [...all, { from: "clio", text: from.opener }]));
    setOpen(true);
  };

  const send = async (e: FormEvent) => {
    e.preventDefault();
    const text = draft.trim();
    if (!text || busy) return;
    setDraft("");
    await deliver(text);
  };

  const noteHint = note.shown;

  const shut = () => {
    setOpen(false);
    dock.current?.focus({ preventScroll: true });
  };

  return (
    <>
      <div
        id={panelId}
        role="dialog"
        aria-label={`${t.name}. ${t.role}`}
        data-on={open}
        inert={!open}
        className="clio-panel theme-paper field hairline fixed bottom-[calc(clamp(1rem,3vh,1.5rem)+4.75rem)] right-[clamp(1rem,3vw,1.5rem)] z-50 flex max-h-[min(34rem,calc(100svh-7rem))] w-[min(24rem,calc(100vw-2rem))] flex-col max-sm:inset-x-0 max-sm:bottom-0 max-sm:max-h-[min(72svh,36rem)] max-sm:w-full max-sm:border-x-0 max-sm:border-b-0 max-sm:pb-[env(safe-area-inset-bottom)]"
      >
        <header className="flex items-center gap-4 p-4">
          <span className="relative block size-12 shrink-0">
            <Face src={avatar.plain} sizes="48px" />
          </span>
          <p className="caps min-w-0 flex-1">
            <span className="block">{t.name}</span>
            <span className="block">{t.role}</span>
          </p>
          <button type="button" aria-label={t.close} onClick={shut} className="grid size-11 shrink-0 place-items-center">
            <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
              <path d="M2 2l12 12M14 2L2 14" />
            </svg>
          </button>
        </header>

        <ol ref={thread} data-lenis-prevent aria-live="polite" className="flex min-h-24 flex-1 flex-col gap-5 overflow-y-auto overscroll-contain border-t-[1.5px] border-[color:var(--line)] p-4">
          {lines.map((line, i) => (
            <li key={i} className={`clio-line ${line.from === "you" ? "pl-10 text-right" : "pr-10"}`}>
              <p className="caps">{line.from === "you" ? t.you : t.name}</p>
              <p className="copy mt-1">{line.text}</p>
              {line.contacts ? (
                <ul className="caps mt-2 flex flex-wrap gap-x-5">
                  {links.map((link) => (
                    <li key={link.label}>
                      <a href={link.href} className="inline-block py-2 underline underline-offset-4" {...(link.href.startsWith("http") ? { target: "_blank", rel: "noreferrer" } : {})}>
                        {link.label}
                      </a>
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          ))}
          {/* where her answer will stand, so nothing shifts when it comes; with motion a slow band of colour passes over the words */}
          {busy ? (
            <li className="clio-line pr-10">
              <p className="caps clio-typing w-fit">{t.typing}…</p>
            </li>
          ) : null}
        </ol>

        <form onSubmit={send} className="flex border-t-[1.5px] border-[color:var(--line)]">
          <label htmlFor={fieldId} className="sr-only">
            {t.placeholder}
          </label>
          {/* 16 px: a phone does not zoom into the page when the field takes focus */}
          <input
            ref={field}
            id={fieldId}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder={t.placeholder}
            autoComplete="off"
            enterKeyHint="send"
            maxLength={2000}
            className="min-w-0 flex-1 bg-transparent px-4 py-4 font-text text-[length:var(--fs-t)] text-ink placeholder:text-ink placeholder:opacity-60 focus-visible:-outline-offset-[6px]"
          />
          <button
            type="submit"
            aria-label={t.send}
            disabled={!draft.trim() || busy}
            className="grid w-14 shrink-0 place-items-center border-l-[1.5px] border-[color:var(--line)] bg-red text-white focus-visible:-outline-offset-[6px] disabled:bg-paper disabled:text-red"
          >
            <svg viewBox="0 0 16 16" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
              <path d="M8 14V2M3 7l5-5 5 5" />
            </svg>
          </button>
        </form>

        <p className="caps border-t-[1.5px] border-[color:var(--line)] px-4 py-2">
          {clioConnected ? (
            <span className="block py-1">
              {t.consent.text}{" "}
              <a href={policyHref} target="_blank" rel="noreferrer" className="underline underline-offset-4">
                {t.consent.link}
              </a>
            </span>
          ) : null}
          <button type="button" onClick={() => guide.setOff(!guide.off)} className="caps py-1 text-left underline underline-offset-4">
            {guide.off ? t.hints.turnOn : t.hints.turnOff}
          </button>
        </p>
      </div>

      {noteHint ? (
        <div
          key={noteHint.id}
          role="status"
          // put away, it stays a moment to leave softly and can no longer be clicked
          data-leaving={note.leaving || undefined}
          inert={note.leaving}
          // On a phone the note stands above the star; on wider screens to the left of it, low along the bottom edge,
          // in the empty band every screen keeps there, so it does not lie over the words it speaks about.
          className="clio-note theme-paper field hairline fixed bottom-[calc(clamp(1rem,3vh,1.5rem)+4.5rem)] right-[clamp(1rem,3vw,1.5rem)] z-50 w-[min(19rem,calc(100vw-2rem))] sm:bottom-[clamp(1rem,3vh,1.5rem)] sm:right-[calc(clamp(1rem,3vw,1.5rem)+4.75rem)] sm:w-[min(27rem,calc(100vw-9rem))]"
          onPointerEnter={() => guide.hold(true)}
          onPointerLeave={() => guide.hold(false)}
          onFocus={() => guide.hold(true)}
          onBlur={() => guide.hold(false)}
        >
          {/* the words themselves are the way into the talk; under them, the same way spelled out and the switch */}
          <button type="button" onClick={() => answer(noteHint)} className="block w-full px-4 pb-2 pr-12 pt-3 text-left focus-visible:-outline-offset-[6px]">
            <span className="sr-only">{t.name}: </span>
            <span className="copy block max-w-none">{noteHint.text}</span>
          </button>
          <button type="button" aria-label={t.hints.close} onClick={closeHint} className="absolute right-0 top-0 grid size-11 place-items-center focus-visible:-outline-offset-[6px]">
            <svg viewBox="0 0 16 16" className="size-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
              <path d="M2 2l12 12M14 2L2 14" />
            </svg>
          </button>
          <p className="flex flex-wrap items-center justify-between gap-x-4 px-4 pb-1">
            <button type="button" onClick={() => answer(noteHint)} className="caps py-2 text-red underline underline-offset-4 focus-visible:-outline-offset-2">
              {t.hints.answer}
            </button>
            <button type="button" onClick={() => guide.setOff(true)} className="caps py-2 focus-visible:-outline-offset-2">
              {t.hints.off}
            </button>
          </p>
        </div>
      ) : null}

      <button
        ref={dock}
        type="button"
        aria-label={open ? t.close : t.open}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => {
          if (hint && !open) return answer(hint);
          setOpen((was) => !was);
        }}
        className={`clio-dock fixed bottom-[clamp(1rem,3vh,1.5rem)] right-[clamp(1rem,3vw,1.5rem)] z-50 size-14 sm:size-16 ${open ? "max-sm:invisible" : ""}`}
      >
        {/* By itself the star stands still. Under the pointer it comes a little closer and light runs over it, a press pushes
            it in, and while the window is open it stands turned by an eighth, as the cross that closes it. When it has a note
            to show it leans once and settles. */}
        <span className="clio-lift relative block size-full">
          <span className={`clio-star absolute inset-0 ${hint ? "is-telling" : ""}`}>
            {/* both are there; the page's colour decides which one shows (see globals.css) */}
            <Face src={avatar.red} sizes="64px" className="on-red" />
            <Face src={avatar.plain} sizes="64px" className="on-plain" />
            <span className="clio-glint" style={{ "--star": `url(${avatar.plain})` } as CSSProperties} />
          </span>
        </span>
      </button>
    </>
  );
}
