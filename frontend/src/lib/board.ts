"use client";

import { useSyncExternalStore } from "react";

/**
 * The storyboard ("раскадровка"): the works a visitor marked with a plus as references for their own shoot.
 * It lives for as long as the tab does (sessionStorage); where storage is closed it lives until the page is left.
 * The works carousel writes to it, the shoot configurator reads it.
 * A work is known by its direction and name, e.g. "events: BASTA – итоговый ролик".
 */
const KEY = "c3:board";
const NONE: string[] = [];

let marked: string[] | null = null;
const listeners = new Set<() => void>();

function read(): string[] {
  if (marked) return marked;
  try {
    const stored: unknown = JSON.parse(window.sessionStorage.getItem(KEY) ?? "[]");
    marked = Array.isArray(stored) ? stored.filter((id): id is string => typeof id === "string") : [];
  } catch {
    marked = [];
  }
  return marked;
}

function write(next: string[]) {
  marked = next;
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    // storage is closed (private mode): the marks stay in memory
  }
  listeners.forEach((tell) => tell());
}

function subscribe(tell: () => void) {
  listeners.add(tell);
  return () => {
    listeners.delete(tell);
  };
}

/** Adds the work, or takes it away if it is already there. */
export function toggleOnBoard(id: string) {
  const now = read();
  write(now.includes(id) ? now.filter((other) => other !== id) : [...now, id]);
}

export function clearBoard() {
  write([]);
}

/** The marked works, in the order they were marked. Empty on the server and until the page has come alive. */
export function useBoard(): string[] {
  return useSyncExternalStore(subscribe, read, () => NONE);
}
