import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readdir, readFile, rm, utimes, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { main } from "../update.mjs";
import { Busy, withLock } from "../lib/store.mjs";
import { LICENSE_ACK } from "../lib/providers.mjs";

const VENUES = { venues: [
  { id: "aeris", name: "AERIS", pinned: true, mapUrls: { yandex: "https://yandex.ru/maps/org/aeris/103/", gis: "https://2gis.ru/ekaterinburg/firm/700" } },
  { id: "momo", name: "Момо", mapUrls: { yandex: "https://yandex.ru/maps/org/momo/119/", gis: "https://2gis.ru/ekaterinburg/firm/701" } },
] };

async function workspace(t) {
  const dir = await mkdtemp(join(tmpdir(), "astor-ratings-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const venues = join(dir, "venues.json");
  const out = join(dir, "ratings");
  await writeFile(venues, JSON.stringify(VENUES));
  let clock = new Date("2026-10-05T09:00:00.000Z");
  const lines = [];
  const run = (args, extra = {}) => main([...args, "--venues", venues, "--out", out],
    { env: {}, now: () => clock, print: (line) => lines.push(line), ...extra });
  const published = async () => JSON.parse(await readFile(join(out, "snapshot.json"), "utf8"));
  return { out, run, published, lines, at: (iso) => { clock = new Date(iso); } };
}

test("recording twice updates the snapshot in place: new dates, no duplicates, previous copy kept", async (t) => {
  const w = await workspace(t);
  assert.equal(await w.run(["record", "aeris:yandex=4.8,gis=4.8", "momo:yandex=4.9,gis=4.9"]), 0);
  const first = await w.published();
  assert.equal(first.venues.momo.gis.checkedAt, "2026-10-05T09:00:00.000Z");

  w.at("2026-10-06T09:00:00.000Z");
  assert.equal(await w.run(["record", "momo:gis=4.7"]), 0);
  const second = await w.published();
  assert.deepEqual(Object.keys(second.venues), ["aeris", "momo"]);
  assert.equal(second.generatedAt, "2026-10-06T09:00:00.000Z");
  assert.equal(second.venues.momo.gis.rating, 4.7);
  assert.equal(second.venues.momo.gis.updatedAt, "2026-10-06T09:00:00.000Z");
  assert.deepEqual(second.venues.aeris, first.venues.aeris, "what was not checked today keeps its own date");
  assert.deepEqual((await readdir(w.out)).sort(), ["snapshot.json", "snapshot.prev.json"], "no lock or temporary file is left");

  assert.equal(await w.run(["rollback"]), 0);
  assert.deepEqual(await w.published(), first);
});

test("a wrong command changes nothing", async (t) => {
  const w = await workspace(t);
  await w.run(["record", "aeris:yandex=4.8,gis=4.8", "momo:yandex=4.9,gis=4.9"]);
  const before = await w.published();
  assert.equal(await w.run(["record", "aeris:yandex=7"]), 1);
  assert.equal(await w.run(["record", "ghost:yandex=4.8"]), 1);
  assert.equal(await w.run(["publish-everything"]), 1);
  assert.equal(await w.run(["record", "aeris:yandex=4.1", "--dry-run"]), 0);
  assert.deepEqual(await w.published(), before);
});

test("status tells what needs a check and a damaged file is not overwritten", async (t) => {
  const w = await workspace(t);
  assert.equal(await w.run(["status"]), 2, "nothing published yet");
  assert.equal(await w.run(["record", "aeris:yandex=4.8,gis=4.8"]), 2, "momo was never checked");
  assert.match(w.lines.join("\n"), /2 rating\(s\) need a check: momo\.yandex, momo\.gis/);
  await w.run(["record", "momo:yandex=4.9,gis=4.9"]);
  assert.equal(await w.run(["status"]), 0);
  w.at("2026-10-20T09:00:00.000Z");
  assert.equal(await w.run(["status"]), 2, "two weeks later everything is stale");

  await writeFile(join(w.out, "snapshot.json"), "{ \"schema\": 1, \"venues\": ");
  assert.equal(await w.run(["record", "aeris:yandex=4.8"]), 1);
  assert.match(w.lines.at(-1), /not valid JSON/);
  assert.equal(await readFile(join(w.out, "snapshot.json"), "utf8"), "{ \"schema\": 1, \"venues\": ");
});

test("only one update runs at a time; a lock abandoned long ago is taken over", async (t) => {
  const w = await workspace(t);
  let inner;
  await withLock(w.out, async () => { inner = await w.run(["record", "aeris:yandex=4.8"]); });
  assert.equal(inner, 3);
  assert.match(w.lines.at(-1), /Another ratings update is running/);
  await assert.rejects(withLock(w.out, () => withLock(w.out, () => {})), Busy);

  const lock = join(w.out, ".update.lock");
  await writeFile(lock, "{}");
  const old = new Date(Date.now() - 60 * 60 * 1000);
  await utimes(lock, old, old);
  assert.equal(await w.run(["record", "aeris:yandex=4.8,gis=4.8", "momo:yandex=4.9,gis=4.9"]), 0);
});

test("a scheduled run with no licensed source only refreshes statuses", async (t) => {
  const w = await workspace(t);
  await w.run(["record", "aeris:yandex=4.8,gis=4.8", "momo:yandex=4.9,gis=4.9"]);
  w.at("2026-10-20T09:00:00.000Z");
  assert.equal(await w.run(["run"]), 2);
  const snapshot = await w.published();
  assert.equal(snapshot.venues.aeris.yandex.status, "stale");
  assert.equal(snapshot.venues.aeris.yandex.rating, 4.8);
  assert.equal(snapshot.venues.aeris.yandex.checkedAt, "2026-10-05T09:00:00.000Z");
});

test("a scheduled run with a licensed source keeps the old value when the source fails", async (t) => {
  const w = await workspace(t);
  await w.run(["record", "aeris:yandex=4.8,gis=4.8", "momo:yandex=4.9,gis=4.9"]);
  const env = { ASTOR_RATINGS_GIS_URL: "https://source.example/items?id={id}", ASTOR_RATINGS_GIS_LICENSE: LICENSE_ACK,
    ASTOR_RATINGS_GIS_RATING_PATH: "rating" };
  const client = { getJson: async (url) => {
    if (url.endsWith("id=700")) return { rating: 4.6 };
    throw new Error("unexpected failure");
  } };
  w.at("2026-10-06T09:00:00.000Z");
  assert.equal(await w.run(["run"], { env, client }), 2);
  const snapshot = await w.published();
  assert.equal(snapshot.venues.aeris.gis.rating, 4.6);
  assert.equal(snapshot.venues.aeris.gis.provider, "licensed");
  assert.equal(snapshot.venues.momo.gis.rating, 4.9);
  assert.deepEqual(snapshot.venues.momo.gis.lastFailure, { at: "2026-10-06T09:00:00.000Z", code: "INTERNAL" });
  assert.equal(snapshot.venues.aeris.yandex.provider, "manual", "the other source is not touched");

  assert.equal(await w.run(["run"], { env: { ...env, ASTOR_RATINGS_GIS_LICENSE: "" }, client }), 1);
  assert.deepEqual(await w.published(), snapshot);
});
