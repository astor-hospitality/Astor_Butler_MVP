"use client";

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { hintRules, hints, type Hint } from "@content/hints";

/** What is remembered for as long as the tab lives: the notes already shown, and that she was told to be quiet. */
const SEEN = "c3:hints-seen";
const QUIET = "c3:hints-quiet";
/** Remembered for good: the visitor switched the notes off. */
const OFF = "c3:hints-off";

const get = (store: "session" | "local", key: string): string | null => {
  try {
    return (store === "session" ? window.sessionStorage : window.localStorage).getItem(key);
  } catch {
    return null; // storage is closed (private mode): nothing is remembered, the notes still work
  }
};
const set = (store: "session" | "local", key: string, value: string | null) => {
  try {
    const box = store === "session" ? window.sessionStorage : window.localStorage;
    if (value === null) box.removeItem(key);
    else box.setItem(key, value);
  } catch {
    // see above
  }
};

// The switch is read from the browser's memory once and then kept here, so every reader sees the same answer.
let offNow: boolean | null = null;
const offListeners = new Set<() => void>();
const readOff = () => (offNow ??= get("local", OFF) === "1");
const subscribeOff = (tell: () => void) => {
  offListeners.add(tell);
  return () => {
    offListeners.delete(tell);
  };
};
const writeOff = (next: boolean) => {
  offNow = next;
  set("local", OFF, next ? "1" : null);
  offListeners.forEach((tell) => tell());
};

/** Does the part fill enough of the screen, or is enough of it on screen? */
function inView(el: Element): boolean {
  const box = el.getBoundingClientRect();
  if (box.height === 0) return false;
  const shown = Math.min(box.bottom, window.innerHeight) - Math.max(box.top, 0);
  return shown / window.innerHeight >= hintRules.inView || shown / box.height >= hintRules.inView;
}

interface HintsState {
  /** The note on show, if any. */
  hint: Hint | null;
  /** The visitor switched the notes off. */
  off: boolean;
  setOff: (off: boolean) => void;
  /** The visitor closed the note (the cross, or a tap elsewhere). Two in a row and she stays silent. */
  close: () => void;
  /** The visitor took the note up: it goes away and the count of closed notes starts again. */
  take: () => void;
  /** Holds the note while the pointer or the keyboard is on it. */
  hold: (held: boolean) => void;
}

/**
 * Decides which of Clio's notes is on show (see content/hints.ts for the rules).
 * `busy` is true while the chat is open: no note then.
 */
export function useHints(busy: boolean): HintsState {
  const [hint, setHint] = useState<Hint | null>(null);
  const off = useSyncExternalStore(subscribeOff, readOff, () => false);
  const state = useRef({ seen: new Set<string>(), quiet: false, closed: 0, last: 0, candidate: "", since: 0, held: false, hideAt: 0, ready: 0, path: "" });
  const busyNow = useRef(busy);
  useEffect(() => {
    busyNow.current = busy;
  }, [busy]);

  // What the browser remembers.
  useEffect(() => {
    const s = state.current;
    try {
      const stored: unknown = JSON.parse(get("session", SEEN) ?? "[]");
      if (Array.isArray(stored)) s.seen = new Set(stored.filter((id): id is string => typeof id === "string"));
    } catch {
      // a broken record is simply ignored
    }
    s.quiet = get("session", QUIET) === "1";
  }, []);

  const hide = useCallback(() => setHint(null), []);

  useEffect(() => {
    if (off) return;
    const s = state.current;
    const timer = window.setInterval(() => {
      const now = Date.now();
      const root = document.documentElement;
      // nothing before the red screen has let go, and a few seconds after it
      if (!root.classList.contains("ready")) return;
      s.ready ||= now;
      // a new page: the note of the old one goes away
      const path = window.location.pathname;
      const moved = s.path !== "" && s.path !== path;
      s.path = path;
      if (moved && hint) return hide();

      if (hint) {
        if (busyNow.current) return hide();
        if (!s.held && now >= s.hideAt) hide();
        return;
      }
      if (s.quiet || busyNow.current || document.hidden) return;
      if (now - s.ready < hintRules.afterIntro * 1000 || now - s.last < hintRules.gap * 1000) return;

      const next = hints.find((item) => {
        if (s.seen.has(item.id)) return false;
        const el = document.querySelector(item.target);
        return el ? inView(el) : false;
      });
      if (!next) {
        s.candidate = "";
        return;
      }
      if (s.candidate !== next.id) {
        s.candidate = next.id;
        s.since = now;
        return;
      }
      if (now - s.since < hintRules.dwell * 1000) return;

      s.seen.add(next.id);
      set("session", SEEN, JSON.stringify([...s.seen]));
      s.last = now;
      s.hideAt = now + hintRules.stay * 1000;
      s.candidate = "";
      setHint(next);
    }, 400);
    return () => window.clearInterval(timer);
  }, [hint, off, hide]);

  const close = useCallback(() => {
    const s = state.current;
    s.closed += 1;
    if (s.closed >= hintRules.quietAfter) {
      s.quiet = true;
      set("session", QUIET, "1");
    }
    hide();
  }, [hide]);

  const take = useCallback(() => {
    state.current.closed = 0;
    hide();
  }, [hide]);

  const hold = useCallback((held: boolean) => {
    const s = state.current;
    s.held = held;
    // let go: the note stays a little longer, not the whole time again
    if (!held) s.hideAt = Math.max(s.hideAt, Date.now() + 1500);
  }, []);

  const setOff = useCallback(
    (next: boolean) => {
      writeOff(next);
      if (next) hide();
      else {
        // switched back on: she may speak again
        state.current.quiet = false;
        state.current.closed = 0;
        set("session", QUIET, null);
      }
    },
    [hide],
  );

  return { hint, off, setOff, close, take, hold };
}
