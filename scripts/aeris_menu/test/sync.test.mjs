import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileName, menuImages } from "../lib/pages.mjs";
import { sync } from "../sync.mjs";

const fixture = (name) => readFile(new URL("./fixtures/" + name, import.meta.url), "utf8");

test("lists the pictures of a page once each, without logos", async () => {
  const images = menuImages(await fixture("bl_page.html"));
  assert.deepEqual(images.map((i) => i.url), [
    "https://static.tildacdn.com/tild3430-3461-4631-b461-316563643434/1111.jpg",
    "https://static.tildacdn.com/tild3564-3035-4938-a233-633261646337/Aeris____140297__4_4.png",
  ]);
  assert.equal(images[0].name, "63643434_1111.jpg");
  assert.equal(fileName("https://static.tildacdn.com/tild1-2/BAR%20MENU.jpg"), "tild1-2_BAR_MENU.jpg");
});

function fakeSite() {
  const png = (seed) => Buffer.from("PNG" + seed);
  const bodies = {
    "https://aeris.bar/kitchen": () => fixture("kitchen_page.html"),
    "https://aeris.bar/bl": () => fixture("bl_page.html"),
    "https://aeris.bar/barmenu": () => Promise.resolve("<html>no pictures</html>"),
    "https://aeris.bar/wineroom": () => Promise.reject(new Error("boom")),
    "https://static.tildacdn.com/tild3430-3461-4631-b461-316563643434/1111.jpg": () => png("a"),
    "https://static.tildacdn.com/tild3564-3035-4938-a233-633261646337/Aeris____140297__4_4.png": () => png("b"),
  };
  const calls = [];
  const doFetch = async (url) => {
    calls.push(url);
    if (url.startsWith("https://store.tildaapi.com/")) return { ok: true, json: async () => JSON.parse(await fixture("tilda_products_sample.json")) };
    const body = bodies[url];
    if (!body) return { ok: false, status: 404, headers: new Headers() };
    const value = await body();
    return { ok: true, status: 200, headers: new Headers({ "content-type": "image/png" }),
      text: async () => value, arrayBuffer: async () => (Buffer.isBuffer(value) ? value : Buffer.from(value)) };
  };
  return { doFetch, calls, bodies };
}

test("writes the kitchen files, the pictures and a manifest; a failed page does not stop the others", async () => {
  const out = await mkdtemp(join(tmpdir(), "aeris-menu-"));
  const site = fakeSite();
  const log = [];
  const { manifest, failed } = await sync({ doFetch: site.doFetch, now: () => new Date("2026-10-06T16:00:00Z"), log: (l) => log.push(l),
    options: { out, ocr: false, pages: ["kitchen", "barmenu", "wineroom", "bl"] } });

  assert.equal(failed, true, "wineroom failed");
  assert.equal(manifest.pages.wineroom.status, "failed");
  assert.equal(manifest.pages.kitchen.status, "ok");
  assert.equal(manifest.pages.kitchen.dishes, 6);
  assert.equal(manifest.pages.barmenu.images.length, 0);
  assert.deepEqual(manifest.pages.bl.images.map((i) => i.changed), [true, true]);

  const kitchen = JSON.parse(await readFile(join(out, "kitchen.json"), "utf8"));
  assert.equal(kitchen.fetchedAt, "2026-10-06T16:00:00.000Z");
  assert.equal((await readdir(join(out, "images"))).length, 2);
  assert.ok(manifest.files["images/63643434_1111.jpg"].sha256);
  assert.match(await readFile(join(out, "kitchen.md"), "utf8"), /Закуски/);

  // Second run: nothing changed on the site, so no picture is rewritten and the first fetch date is kept.
  const again = await sync({ doFetch: site.doFetch, now: () => new Date("2026-10-07T16:00:00Z"), log: () => {},
    options: { out, ocr: false, pages: ["bl"] } });
  assert.deepEqual(again.manifest.pages.bl.images.map((i) => i.changed), [false, false]);
  assert.equal(again.manifest.files["images/63643434_1111.jpg"].fetchedAt, "2026-10-06T16:00:00.000Z");
  assert.equal(again.manifest.files["kitchen.json"].fetchedAt, "2026-10-06T16:00:00.000Z", "files of pages not synced this run are kept");

  // A changed picture is downloaded again.
  site.bodies["https://static.tildacdn.com/tild3430-3461-4631-b461-316563643434/1111.jpg"] = () => Buffer.from("PNG new");
  const third = await sync({ doFetch: site.doFetch, now: () => new Date("2026-10-08T16:00:00Z"), log: () => {}, options: { out, ocr: false, pages: ["bl"] } });
  assert.deepEqual(third.manifest.pages.bl.images.map((i) => i.changed), [true, false]);
});

test("ocr is skipped with a reason when tesseract or its Russian data is missing", async () => {
  const out = await mkdtemp(join(tmpdir(), "aeris-menu-"));
  const site = fakeSite();
  const { manifest } = await sync({ doFetch: site.doFetch, log: () => {}, options: { out, ocr: true, pages: ["bl"] } });
  assert.ok(["ok", "skipped"].includes(manifest.ocr.status));
  if (manifest.ocr.status === "skipped") assert.match(manifest.ocr.reason, /tesseract/);
});

test("a page without a catalog block is reported, not guessed", async () => {
  const out = await mkdtemp(join(tmpdir(), "aeris-menu-"));
  await writeFile(join(out, "manifest.json"), "{not json");
  const doFetch = async () => ({ ok: true, status: 200, headers: new Headers(), text: async () => "<html>pictures now</html>" });
  const { manifest, failed } = await sync({ doFetch, log: () => {}, options: { out, ocr: false, pages: ["kitchen"] } });
  assert.equal(failed, true);
  assert.match(manifest.pages.kitchen.error, /no Tilda catalog block/);
});
