#!/usr/bin/env node
/* Astor Concierge ratings: keeps frontend/astor-butler/data/ratings/snapshot.json for the venue feed.

   record   aeris:yandex=4.8,gis=4.8 …   a person checked the map pages and types what they show
   run                                    reads sources that are licensed for it, refreshes statuses
   status                                 what is stored and what needs a check
   rollback                               puts the previous snapshot back

   Exit code: 0 everything is fresh, 2 something is stale, missing or failed, 3 another run is active,
   1 the command itself failed. Details: docs/operations/CONCIERGE_RATINGS.md */

import { fileURLToPath, pathToFileURL } from "node:url";
import { resolve } from "node:path";
import { attention, buildSnapshot, DEFAULT_STALE_AFTER_HOURS } from "./lib/snapshot.mjs";
import { readVenues } from "./lib/venues.mjs";
import { Busy, publish, readSnapshot, rollback, withLock } from "./lib/store.mjs";
import { createClient } from "./lib/http.mjs";
import { licensedResults, licensedSources, manualResults } from "./lib/providers.mjs";

const HERE = fileURLToPath(new URL(".", import.meta.url));

function parse(argv) {
  const options = { venues: resolve(HERE, "../../frontend/astor-butler/data/venues.json"), out: resolve(HERE, "../../frontend/astor-butler/data/ratings"),
    staleAfterHours: null, dryRun: false };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--dry-run") options.dryRun = true;
    else if (arg === "--venues") options.venues = resolve(argv[++i] || "");
    else if (arg === "--out") options.out = resolve(argv[++i] || "");
    else if (arg === "--stale-after-hours") options.staleAfterHours = Number(argv[++i]);
    else if (arg.startsWith("--")) throw new Error("Unknown option " + arg);
    else rest.push(arg);
  }
  if (options.staleAfterHours !== null && !(options.staleAfterHours > 0)) throw new Error("--stale-after-hours needs a positive number");
  return { command: rest[0], args: rest.slice(1), options };
}

function report(snapshot, now, print) {
  for (const [venueId, entries] of Object.entries(snapshot.venues)) {
    for (const [key, entry] of Object.entries(entries)) {
      const failure = entry.lastFailure ? "  last attempt failed: " + entry.lastFailure.code : "";
      print([venueId.padEnd(14), key.padEnd(7), String(entry.rating ?? "-").padEnd(5), entry.status.padEnd(8),
        entry.checkedAt || "never checked"].join(" ") + failure);
    }
  }
  const rows = attention(snapshot, now);
  if (rows.length) print(rows.length + " rating(s) need a check: " + rows.map((row) => row.venueId + "." + row.source).join(", "));
  return rows.length;
}

export async function main(argv, { env = process.env, now = () => new Date(), client, print = console.log } = {}) {
  let parsed;
  try {
    parsed = parse(argv);
    if (!["record", "run", "status", "rollback"].includes(parsed.command)) throw new Error("Use: record | run | status | rollback");
  } catch (error) {
    print(error.message);
    return 1;
  }
  const { command, args, options } = parsed;
  try {
    if (command === "status") {
      const snapshot = await readSnapshot(options.out);
      if (!snapshot) { print("Nothing is published yet"); return 2; }
      return report(snapshot, now(), print) ? 2 : 0;
    }
    return await withLock(options.out, async () => {
      if (command === "rollback") {
        const restored = await rollback(options.out);
        print("Returned to the snapshot generated at " + restored.generatedAt);
        return report(restored, now(), print) ? 2 : 0;
      }
      const venues = await readVenues(options.venues);
      const previous = await readSnapshot(options.out);
      let results;
      if (command === "record") {
        results = manualResults(args, venues);
      } else {
        const sources = licensedSources(env);
        if (!Object.keys(sources).length) print("No source is licensed for automatic reading; statuses are refreshed only");
        results = await licensedResults(venues, sources, client || createClient());
      }
      const at = now();
      const snapshot = buildSnapshot(previous, venues, results, at,
        options.staleAfterHours ?? (previous ? previous.staleAfterHours : DEFAULT_STALE_AFTER_HOURS));
      if (options.dryRun) print("Dry run: nothing is published");
      else await publish(options.out, snapshot);
      const failed = Object.values(results).flatMap((bySource) => Object.values(bySource)).filter((result) => !result.ok).length;
      if (failed) print(failed + " check(s) failed; the previous values are kept");
      return report(snapshot, at, print) || failed ? 2 : 0;
    });
  } catch (error) {
    if (error instanceof Busy) { print(error.message); return 3; }
    print(error.message);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main(process.argv.slice(2));
}
