import test from "node:test";
import assert from "node:assert/strict";
import { assertSnapshot, attention, buildSnapshot, validRating } from "../lib/snapshot.mjs";
import { sourceId, venueSources } from "../lib/venues.mjs";

const venues = [
  { id: "aeris", sources: { yandex: { id: "103", url: "https://yandex.ru/maps/org/aeris/103/" },
    gis: { id: "700", url: "https://2gis.ru/ekaterinburg/firm/700" } } },
  { id: "momo", sources: { yandex: { id: "119", url: "https://yandex.ru/maps/org/momo/119/" } } },
];
const day = (n) => new Date(Date.UTC(2026, 9, n, 12));
const ok = (rating, provider = "manual") => ({ ok: true, rating, provider });

test("a successful check stores the rating with its source, check time and change time", () => {
  const first = buildSnapshot(null, venues, { aeris: { yandex: ok(4.8), gis: ok(4.7) } }, day(1));
  assert.deepEqual(first.venues.aeris.yandex, { sourceId: "103", url: "https://yandex.ru/maps/org/aeris/103/",
    rating: 4.8, provider: "manual", checkedAt: day(1).toISOString(), updatedAt: day(1).toISOString(),
    status: "fresh", lastFailure: null });
  // Same value confirmed later: checked again, but it did not change.
  const second = buildSnapshot(first, venues, { aeris: { yandex: ok(4.8), gis: ok(4.9) } }, day(3));
  assert.equal(second.venues.aeris.yandex.checkedAt, day(3).toISOString());
  assert.equal(second.venues.aeris.yandex.updatedAt, day(1).toISOString());
  assert.equal(second.venues.aeris.gis.updatedAt, day(3).toISOString());
  assertSnapshot(second);
});

test("a failed check keeps the last good rating and never turns into zero", () => {
  const first = buildSnapshot(null, venues, { aeris: { yandex: ok(4.8) } }, day(1));
  for (const result of [{ ok: false, code: "TIMEOUT" }, { ok: false, code: "FORMAT" }, ok(0), ok(null), ok(5.4), ok("4.8")]) {
    const next = buildSnapshot(first, venues, { aeris: { yandex: result } }, day(2));
    assert.equal(next.venues.aeris.yandex.rating, 4.8);
    assert.equal(next.venues.aeris.yandex.checkedAt, day(1).toISOString());
    assert.equal(next.venues.aeris.yandex.lastFailure.at, day(2).toISOString());
    assert.equal(next.venues.aeris.yandex.lastFailure.code, result.ok ? "INVALID_RATING" : result.code);
  }
  // A source that never answered stays without a rating; the next success clears the failure mark.
  const never = buildSnapshot(null, venues, { momo: { yandex: { ok: false, code: "NETWORK" } } }, day(1));
  assert.equal(never.venues.momo.yandex.rating, null);
  assert.equal(never.venues.momo.yandex.status, "missing");
  assert.equal(buildSnapshot(never, venues, { momo: { yandex: ok(4.9) } }, day(2)).venues.momo.yandex.lastFailure, null);
});

test("a rating grows stale with time and an unchecked source is left untouched", () => {
  const first = buildSnapshot(null, venues, { aeris: { yandex: ok(4.8), gis: ok(4.7) } }, day(1), 48);
  const later = buildSnapshot(first, venues, { aeris: { gis: ok(4.7) } }, day(4), 48);
  assert.equal(later.venues.aeris.yandex.status, "stale");
  assert.equal(later.venues.aeris.yandex.rating, 4.8);
  assert.equal(later.venues.aeris.gis.status, "fresh");
  assert.deepEqual(attention(later, day(4)), [{ venueId: "aeris", source: "yandex", status: "stale" },
    { venueId: "momo", source: "yandex", status: "missing" }]);
});

test("two runs in a row neither duplicate venues nor damage what is stored", () => {
  const results = { aeris: { yandex: ok(4.8), gis: ok(4.7) }, momo: { yandex: ok(4.9) } };
  const first = buildSnapshot(null, venues, results, day(1));
  const second = buildSnapshot(first, venues, results, day(1));
  assert.deepEqual(second, first);
  assert.deepEqual(Object.keys(second.venues), ["aeris", "momo"]);
});

test("a rating follows the map object, not the venue name", () => {
  const first = buildSnapshot(null, venues, { aeris: { yandex: ok(4.8) }, momo: { yandex: ok(4.9) } }, day(1));
  const moved = [{ id: "aeris", sources: { yandex: { id: "999", url: "https://yandex.ru/maps/org/aeris/999/" } } }];
  const next = buildSnapshot(first, moved, {}, day(2));
  assert.equal(next.venues.aeris.yandex.rating, null);
  assert.equal(next.venues.aeris.yandex.sourceId, "999");
  assert.equal(next.venues.momo, undefined, "a venue removed from the list leaves the snapshot");
});

test("a snapshot that is not safe to publish is refused", () => {
  const good = buildSnapshot(null, venues, { aeris: { yandex: ok(4.8) } }, day(1));
  const broken = (change) => { const copy = structuredClone(good); change(copy); return () => assertSnapshot(copy); };
  assert.throws(broken((s) => { s.schema = 2; }), /unknown schema/);
  assert.throws(broken((s) => { s.venues.aeris.yandex.rating = 0; }), /rating of aeris\.yandex/);
  assert.throws(broken((s) => { s.venues.aeris.yandex.checkedAt = null; }), /without check time/);
  assert.throws(broken((s) => { s.venues.aeris.tripadvisor = s.venues.aeris.yandex; }), /unknown source/);
  assert.equal(validRating(1), true);
  assert.equal(validRating(0.9), false);
});

test("the map object id is taken from the link and a link without one is rejected", () => {
  assert.equal(sourceId("yandex", "https://yandex.ru/maps/org/aeris/103837967593/"), "103837967593");
  assert.equal(sourceId("gis", "https://2gis.ru/ekaterinburg/firm/70000001085768632"), "70000001085768632");
  assert.equal(sourceId("gis", "https://2gis.ru/firm/1267165676499509"), "1267165676499509");
  assert.equal(sourceId("yandex", "https://yandex.ru/maps/?text=AERIS"), null);
  assert.equal(sourceId("gis", "https://2gis.ru/ekaterinburg/search/AERIS"), null);
  const list = (venue) => () => venueSources({ venues: [venue] });
  assert.throws(list({ id: "aeris", mapUrls: { yandex: "https://yandex.ru/maps/?text=AERIS" } }), /no object id/);
  assert.throws(list({ name: "No id" }), /usable id/);
  assert.throws(() => venueSources({ venues: [{ id: "a" }, { id: "a" }] }), /Duplicate/);
  assert.deepEqual(venueSources({ venues: [{ id: "a", name: "A" }] }), [{ id: "a", name: "A", sources: {} }]);
});
