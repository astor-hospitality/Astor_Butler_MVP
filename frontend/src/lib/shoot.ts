"use client";

import { useSyncExternalStore } from "react";

/**
 * What the visitor chose in the shoot configurator: the need, the package, and whether it was already sent to Clio.
 * It lives for as long as the tab does (sessionStorage), like the storyboard next to it (see board.ts).
 */
export interface ShootChoice {
  need: string | null;
  pack: string | null;
  sent: boolean;
}

const KEY = "c3:shoot";
const NONE: ShootChoice = { need: null, pack: null, sent: false };

let now: ShootChoice | null = null;
const listeners = new Set<() => void>();

function read(): ShootChoice {
  if (now) return now;
  try {
    const stored = JSON.parse(window.sessionStorage.getItem(KEY) ?? "null") as Partial<ShootChoice> | null;
    now = { need: typeof stored?.need === "string" ? stored.need : null, pack: typeof stored?.pack === "string" ? stored.pack : null, sent: stored?.sent === true };
  } catch {
    now = NONE;
  }
  return now;
}

export function setShoot(change: Partial<ShootChoice>) {
  now = { ...read(), ...change };
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(now));
  } catch {
    // storage is closed (private mode): the choice stays in memory
  }
  listeners.forEach((tell) => tell());
}

function subscribe(tell: () => void) {
  listeners.add(tell);
  return () => {
    listeners.delete(tell);
  };
}

/** The choice. Empty on the server and until the page has come alive. */
export function useShoot(): ShootChoice {
  return useSyncExternalStore(subscribe, read, () => NONE);
}
