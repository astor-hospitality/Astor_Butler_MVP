/* Where a rating comes from.

   manual   — a person opened the venue's page on the map and typed what it shows. Works today.
   licensed — a JSON API the source's owner has allowed us to read and to store. Neither Yandex Maps
              nor 2GIS allows that under their public terms (see docs/operations/CONCIERGE_RATINGS.md),
              so this provider refuses to start until the operator states that a permission exists. */

import { SOURCES, validRating } from "./snapshot.mjs";
import { SourceFailure } from "./http.mjs";

export const LICENSE_ACK = "storage-permitted";

/** "aeris:yandex=4.8,gis=4.8" → results for one venue. Every value must be typed; nothing is assumed. */
export function manualResults(args, venues) {
  const known = new Map(venues.map((venue) => [venue.id, venue]));
  const results = {};
  if (!args.length) throw new Error("Nothing to record. Example: record aeris:yandex=4.8,gis=4.8");
  for (const arg of args) {
    const [venueId, list, ...extra] = arg.split(":");
    const venue = known.get(venueId);
    if (!venue || !list || extra.length) throw new Error("Unknown venue or wrong form: " + arg);
    if (results[venueId]) throw new Error("Venue given twice: " + venueId);
    results[venueId] = {};
    for (const pair of list.split(",")) {
      const [key, raw, ...rest] = pair.split("=");
      if (!SOURCES.includes(key) || !venue.sources[key] || rest.length) throw new Error("Unknown source in " + arg);
      if (key in results[venueId]) throw new Error("Source given twice in " + arg);
      // "4,8" is how both maps print it; a comma here already separates sources, so only a dot is accepted.
      const rating = /^\d(\.\d{1,2})?$/.test(raw || "") ? Number(raw) : NaN;
      if (!validRating(rating)) throw new Error("Rating must be a number from 1 to 5, like 4.8: " + arg);
      results[venueId][key] = { ok: true, rating, provider: "manual" };
    }
  }
  return results;
}

/** Settings of licensed sources from the server environment. Sources without settings are simply absent. */
export function licensedSources(env) {
  const configured = {};
  for (const key of SOURCES) {
    const prefix = "ASTOR_RATINGS_" + key.toUpperCase() + "_";
    const url = env[prefix + "URL"];
    if (!url) continue;
    if (env[prefix + "LICENSE"] !== LICENSE_ACK) {
      throw new Error(prefix + "LICENSE must be \"" + LICENSE_ACK + "\": automatic reading needs the source owner's "
        + "permission to store its data");
    }
    const path = env[prefix + "RATING_PATH"];
    if (!path) throw new Error(prefix + "RATING_PATH is required");
    configured[key] = { url, key: env[prefix + "KEY"] || "", path: path.split(".") };
  }
  return configured;
}

function dig(value, path) {
  for (const step of path) {
    if (value === null || typeof value !== "object" || !(step in value)) return undefined;
    value = value[step];
  }
  return value;
}

/** Reads every configured source for every venue, one request at a time. Never throws for a single source. */
export async function licensedResults(venues, sources, client) {
  const results = {};
  for (const venue of venues) {
    for (const [key, source] of Object.entries(sources)) {
      if (!venue.sources[key]) continue;
      const url = source.url.replaceAll("{id}", encodeURIComponent(venue.sources[key].id))
        .replaceAll("{key}", encodeURIComponent(source.key));
      let result;
      try {
        const value = dig(await client.getJson(url), source.path);
        // An absent field means the answer changed shape. A present but empty one means "not rated yet".
        if (value === undefined) result = { ok: false, code: "FORMAT" };
        else if (!validRating(value)) result = { ok: false, code: "NO_RATING" };
        else result = { ok: true, rating: value, provider: "licensed" };
      } catch (failure) {
        result = { ok: false, code: failure instanceof SourceFailure ? failure.code : "INTERNAL" };
      }
      (results[venue.id] ||= {})[key] = result;
    }
  }
  return results;
}
