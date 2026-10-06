/* Which map objects the ratings belong to. Identity is the object's id in the map URL, never the name:
   two venues may share a name, and a venue may be renamed. */

import { readFile } from "node:fs/promises";

const PATTERNS = {
  yandex: /^https:\/\/yandex\.ru\/maps\/org\/[^/?#]+\/(\d+)\/?$/,
  gis: /^https:\/\/2gis\.ru\/(?:[a-z_-]+\/)?firm\/(\d+)\/?$/,
};

export function sourceId(key, url) {
  const match = typeof url === "string" ? PATTERNS[key].exec(url) : null;
  return match ? match[1] : null;
}

/** [{ id, name, sources: { yandex?: { id, url }, gis?: { id, url } } }] from the feed's venue list. */
export function venueSources(data) {
  const venues = Array.isArray(data && data.venues) ? data.venues : null;
  if (!venues) throw new Error("Venue list has no venues array");
  const seen = new Set();
  return venues.map((venue) => {
    if (!venue || typeof venue.id !== "string" || !/^[a-z0-9][a-z0-9-]*$/.test(venue.id)) {
      throw new Error("Venue without a usable id: " + JSON.stringify(venue && venue.name));
    }
    if (seen.has(venue.id)) throw new Error("Duplicate venue id: " + venue.id);
    seen.add(venue.id);
    const sources = {};
    for (const key of Object.keys(PATTERNS)) {
      const url = venue.mapUrls && venue.mapUrls[key];
      if (url === undefined || url === null) continue;
      const id = sourceId(key, url);
      if (!id) throw new Error("Map link of " + venue.id + "." + key + " has no object id: " + url);
      sources[key] = { id, url };
    }
    return { id: venue.id, name: venue.name, sources };
  });
}

export async function readVenues(path) {
  return venueSources(JSON.parse(await readFile(path, "utf8")));
}
