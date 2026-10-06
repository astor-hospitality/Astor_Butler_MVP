import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { assertSnapshot } from "../lib/snapshot.mjs";
import { venueSources } from "../lib/venues.mjs";

const site = (path) => readFileSync(new URL("../../../frontend/astor-butler/" + path, import.meta.url), "utf8");
// The page script is loaded the way a browser would load it: as a plain script that fills a global.
const browser = { self: {} };
vm.runInNewContext(site("js/feed-ratings.js"), browser);
const Ratings = browser.self.AstorFeedRatings;

const HOUR = 3600000;
const NOW = Date.parse("2026-10-05T12:00:00.000Z");
const venue = (id, name, extra = {}) => ({ id, name, mapUrls: { yandex: "https://y/" + id, gis: "https://g/" + id }, ...extra });
const entry = (id, key, rating, hoursAgo = 1) => ({ sourceId: id, url: (key === "yandex" ? "https://y/" : "https://g/") + id,
  rating, checkedAt: rating === null ? null : new Date(NOW - hoursAgo * HOUR).toISOString() });
const snapshotOf = (venues) => ({ schema: 1, staleAfterHours: 168, venues });
const names = (entries) => Array.from(entries, (item) => item.venue.name);

test("the pinned venue keeps its place whatever its rating; the rest go by the average of valid ratings", () => {
  const venues = [venue("b", "Бета"), venue("aeris", "AERIS", { pinned: true }), venue("a", "Альфа"), venue("c", "Гамма"), venue("d", "Дельта")];
  const snapshot = snapshotOf({
    aeris: { yandex: entry("aeris", "yandex", 4.1), gis: entry("aeris", "gis", 4.0) },
    a: { yandex: entry("a", "yandex", 4.6), gis: entry("a", "gis", 4.9) },
    b: { yandex: entry("b", "yandex", 5), gis: entry("b", "gis", null) },
    c: { yandex: entry("c", "yandex", 4.7), gis: entry("c", "gis", 4.8) },
  });
  const ranked = Ratings.rank(venues, snapshot, NOW);
  assert.deepEqual(names(ranked.pinned), ["AERIS"]);
  assert.equal(ranked.pinned[0].average, 4.05);
  // Бета has one valid source (5.0); Альфа and Гамма tie at 4.75 and go by name; Дельта has no rating at all.
  assert.deepEqual(names(ranked.rest), ["Бета", "Альфа", "Гамма", "Дельта"]);
  assert.deepEqual(Array.from(ranked.rest, (item) => item.average), [5, 4.75, 4.75, null]);

  const worse = snapshotOf({ ...snapshot.venues, aeris: { yandex: entry("aeris", "yandex", 1), gis: entry("aeris", "gis", 1) } });
  assert.deepEqual(names(Ratings.rank(venues, worse, NOW).pinned), ["AERIS"]);
  assert.deepEqual(names(Ratings.rank(venues, worse, NOW).rest), ["Бета", "Альфа", "Гамма", "Дельта"]);
});

test("a rating that failed to load is absent, never zero, and a stale one is shown as stale", () => {
  const venues = [venue("a", "Альфа"), venue("b", "Бета")];
  const snapshot = snapshotOf({
    a: { yandex: entry("a", "yandex", 4.4, 24 * 30), gis: { ...entry("a", "gis", null), lastFailure: { at: "2026-10-05T11:00:00.000Z", code: "TIMEOUT" } } },
    b: { yandex: { ...entry("b", "yandex", 0), rating: 0 }, gis: entry("b", "gis", 4.2) },
  });
  const [alpha, beta] = Ratings.rank(venues, snapshot, NOW).rest;
  assert.equal(alpha.venue.name, "Альфа");
  assert.equal(alpha.average, 4.4, "the old value still counts until a new one arrives");
  assert.deepEqual(Array.from(alpha.ratings, (item) => [item.key, item.stale]), [["yandex", true]]);
  assert.equal(beta.average, 4.2, "a zero is not a rating");
  const summary = Ratings.checks([alpha, beta]);
  assert.equal(summary.stale, true);
  assert.equal(summary.oldest.yandex, NOW - 24 * 30 * HOUR);
  assert.equal(summary.oldest.gis, NOW - HOUR);
});

test("a stored rating is not shown for a venue that now links to another map object", () => {
  const moved = venue("a", "Альфа");
  moved.mapUrls.yandex = "https://y/another-object";
  const snapshot = snapshotOf({ a: { yandex: entry("a", "yandex", 4.9), gis: entry("a", "gis", 4.5) } });
  const [only] = Ratings.rank([moved], snapshot, NOW).rest;
  assert.deepEqual(Array.from(only.ratings, (item) => item.key), ["gis"]);
  assert.equal(only.average, 4.5);
});

test("without a usable snapshot the feed still lists every venue, by name and without numbers", () => {
  const venues = [venue("b", "Бета"), venue("aeris", "AERIS", { pinned: true }), venue("a", "Альфа")];
  for (const snapshot of [null, undefined, {}, { schema: 2, staleAfterHours: 168, venues: {} }, { schema: 1, venues: {} }]) {
    const ranked = Ratings.rank(venues, snapshot, NOW);
    assert.equal(Ratings.usable(snapshot), false);
    assert.deepEqual(names(ranked.pinned), ["AERIS"]);
    assert.deepEqual(names(ranked.rest), ["Альфа", "Бета"]);
    assert.ok(ranked.rest.every((item) => item.average === null && item.ratings.length === 0));
  }
});

test("the published files of the site agree with each other", () => {
  const venues = JSON.parse(site("data/venues.json"));
  const snapshot = assertSnapshot(JSON.parse(site("data/ratings/snapshot.json")));
  assert.equal("ratingsUpdatedAt" in venues, false, "the hand-typed date is gone from the venue list");
  assert.ok(venues.venues.every((item) => !("ratings" in item)), "ratings live only in the snapshot");
  assert.deepEqual(venues.venues.filter((item) => item.pinned).map((item) => item.id), ["aeris"]);
  for (const item of venueSources(venues)) {
    for (const [key, source] of Object.entries(item.sources)) {
      const stored = snapshot.venues[item.id] && snapshot.venues[item.id][key];
      assert.ok(stored, "snapshot has " + item.id + "." + key);
      assert.equal(stored.sourceId, source.id);
      assert.equal(stored.url, source.url);
    }
  }
  const ranked = Ratings.rank(venues.venues, snapshot, Date.parse(snapshot.generatedAt));
  assert.deepEqual(names(ranked.pinned), ["AERIS"]);
  assert.ok(ranked.rest.every((item) => item.average !== null));
  const server = site("server/index.js");
  for (const path of ["/js/feed-ratings.js", "/data/ratings/snapshot.json"]) assert.ok(server.includes("\"" + path + "\""), path);
  assert.match(site("astor_concierge/feed/index.html"), /feed-ratings\.js[\s\S]*feed\.js/, "ranking code loads before the page script");
});
