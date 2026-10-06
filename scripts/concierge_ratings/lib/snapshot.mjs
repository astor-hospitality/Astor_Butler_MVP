/* Rating snapshot rules. Pure: no clock, files or network here.

   One entry per venue and source. A failed check never changes the stored rating:
   the last successful value stays and the failure is recorded next to it. */

export const SCHEMA = 1;
export const SOURCES = ["yandex", "gis"];
export const DEFAULT_STALE_AFTER_HOURS = 168;

/** Both maps rate from 1 to 5. Zero, a string or a missing value is "no rating", not a low one. */
export function validRating(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 1 && value <= 5;
}

export function normalizeRating(value) {
  return Math.round(value * 100) / 100;
}

export function status(entry, now, staleAfterHours) {
  if (!validRating(entry.rating) || !entry.checkedAt) return "missing";
  const ageHours = (now.getTime() - new Date(entry.checkedAt).getTime()) / 3600000;
  return ageHours <= staleAfterHours ? "fresh" : "stale";
}

function emptyEntry(source) {
  return { sourceId: source.id, url: source.url, rating: null, provider: null, checkedAt: null, updatedAt: null,
    status: "missing", lastFailure: null };
}

/**
 * Applies one check result to an entry.
 * result: undefined (not checked this run) | { ok: true, rating, provider } | { ok: false, code }
 */
export function applyResult(entry, result, now) {
  if (!result) return entry;
  const at = now.toISOString();
  if (!result.ok || !validRating(result.rating)) {
    return { ...entry, lastFailure: { at, code: result.ok ? "INVALID_RATING" : result.code } };
  }
  const rating = normalizeRating(result.rating);
  return { ...entry, rating, provider: result.provider, checkedAt: at,
    updatedAt: entry.rating === rating && entry.updatedAt ? entry.updatedAt : at, lastFailure: null };
}

/**
 * Builds the next snapshot from the previous one, the current venue list and this run's results.
 * venues: [{ id, sources: { yandex: { id, url }, gis: { id, url } } }]
 * results: { [venueId]: { [source]: result } }
 */
export function buildSnapshot(previous, venues, results, now, staleAfterHours = DEFAULT_STALE_AFTER_HOURS) {
  const next = { schema: SCHEMA, generatedAt: now.toISOString(), staleAfterHours, venues: {} };
  for (const venue of venues) {
    const before = (previous && previous.venues && previous.venues[venue.id]) || {};
    const entries = {};
    for (const key of SOURCES) {
      const source = venue.sources[key];
      if (!source) continue;
      // A rating belongs to one object on the map. Another id is another object, so nothing carries over.
      const kept = before[key] && before[key].sourceId === source.id ? { ...before[key], url: source.url } : emptyEntry(source);
      const entry = applyResult(kept, results[venue.id] && results[venue.id][key], now);
      entries[key] = { ...entry, status: status(entry, now, staleAfterHours) };
    }
    next.venues[venue.id] = entries;
  }
  return next;
}

/** Throws when a snapshot is not safe to publish or to build upon. */
export function assertSnapshot(snapshot) {
  const fail = (reason) => { throw new Error("Invalid ratings snapshot: " + reason); };
  if (!snapshot || typeof snapshot !== "object" || snapshot.schema !== SCHEMA) fail("unknown schema");
  if (Number.isNaN(Date.parse(snapshot.generatedAt))) fail("generatedAt");
  if (!(snapshot.staleAfterHours > 0)) fail("staleAfterHours");
  if (!snapshot.venues || typeof snapshot.venues !== "object" || Array.isArray(snapshot.venues)) fail("venues");
  for (const [venueId, entries] of Object.entries(snapshot.venues)) {
    for (const [key, entry] of Object.entries(entries)) {
      const where = venueId + "." + key;
      if (!SOURCES.includes(key)) fail("unknown source " + where);
      if (!entry || typeof entry.sourceId !== "string" || !entry.sourceId) fail("sourceId of " + where);
      if (entry.rating !== null && !validRating(entry.rating)) fail("rating of " + where);
      if ((entry.rating === null) !== (entry.checkedAt === null)) fail("rating without check time in " + where);
      if (entry.checkedAt !== null && Number.isNaN(Date.parse(entry.checkedAt))) fail("checkedAt of " + where);
    }
  }
  return snapshot;
}

/** Entries that need attention: never checked, or checked too long ago. */
export function attention(snapshot, now) {
  const rows = [];
  for (const [venueId, entries] of Object.entries(snapshot.venues)) {
    for (const [key, entry] of Object.entries(entries)) {
      const current = status(entry, now, snapshot.staleAfterHours);
      if (current !== "fresh") rows.push({ venueId, source: key, status: current });
    }
  }
  return rows;
}
