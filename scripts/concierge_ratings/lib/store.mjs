/* Snapshot files: one run at a time, and readers only ever see a complete file. */

import { copyFile, mkdir, open, readFile, rename, rm, stat } from "node:fs/promises";
import { join } from "node:path";
import { assertSnapshot } from "./snapshot.mjs";

export const SNAPSHOT = "snapshot.json";
export const PREVIOUS = "snapshot.prev.json";
const LOCK = ".update.lock";
const LOCK_MAX_AGE_MS = 15 * 60 * 1000;

export class Busy extends Error {
  constructor() { super("Another ratings update is running"); }
}

/** Exclusive run. A lock older than 15 minutes is taken over: its owner is assumed dead. */
export async function withLock(dir, action, now = () => Date.now()) {
  await mkdir(dir, { recursive: true });
  const path = join(dir, LOCK);
  let handle;
  for (let attempt = 0; !handle; attempt++) {
    try {
      handle = await open(path, "wx");
    } catch (error) {
      if (error.code !== "EEXIST" || attempt > 0) throw error.code === "EEXIST" ? new Busy() : error;
      const age = now() - (await stat(path).then((info) => info.mtimeMs, () => now()));
      if (age <= LOCK_MAX_AGE_MS) throw new Busy();
      await rm(path, { force: true });
    }
  }
  try {
    await handle.writeFile(JSON.stringify({ pid: process.pid, startedAt: new Date(now()).toISOString() }));
    await handle.close();
    return await action();
  } finally {
    await rm(path, { force: true });
  }
}

/** null when nothing was published yet. A damaged file is an error: it must not be silently replaced. */
export async function readSnapshot(dir, name = SNAPSHOT) {
  let text;
  try {
    text = await readFile(join(dir, name), "utf8");
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error("Published snapshot " + name + " is not valid JSON; restore it with the rollback command");
  }
  return assertSnapshot(parsed);
}

async function writeAtomically(dir, name, text) {
  const temporary = join(dir, name + "." + process.pid + ".tmp");
  const handle = await open(temporary, "w");
  try {
    await handle.writeFile(text);
    await handle.sync();
  } finally {
    await handle.close();
  }
  // rename replaces the file in one step: a reader gets the old file or the new one, never a part.
  await rename(temporary, join(dir, name));
}

/** Validates, keeps the current file as the rollback copy, then replaces it. */
export async function publish(dir, snapshot) {
  assertSnapshot(snapshot);
  await mkdir(dir, { recursive: true });
  const current = join(dir, SNAPSHOT);
  if (await stat(current).then(() => true, () => false)) {
    const copy = join(dir, PREVIOUS + "." + process.pid + ".tmp");
    await copyFile(current, copy);
    await rename(copy, join(dir, PREVIOUS));
  }
  await writeAtomically(dir, SNAPSHOT, JSON.stringify(snapshot, null, 2) + "\n");
}

/** Puts the previous snapshot back. The replaced one becomes the new rollback copy. */
export async function rollback(dir) {
  const previous = await readSnapshot(dir, PREVIOUS);
  if (!previous) throw new Error("There is no previous snapshot to return to");
  await publish(dir, previous);
  return previous;
}
