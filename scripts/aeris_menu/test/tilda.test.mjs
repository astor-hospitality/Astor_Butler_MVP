import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { catalogUrl, discoverCatalog, fetchCatalog, normalizeCatalog, renderMarkdown } from "../lib/tilda.mjs";

const fixture = (name) => readFile(new URL("./fixtures/" + name, import.meta.url), "utf8");

test("finds the catalog block ids in the page script", async () => {
  assert.deepEqual(discoverCatalog(await fixture("kitchen_page.html")), { recid: "2272368201", storepart: "801053506212" });
  assert.equal(discoverCatalog("<html>no catalog</html>"), null);
  assert.equal(discoverCatalog(null), null);
});

test("asks the store for all parts and a page of 100", () => {
  const url = new URL(catalogUrl({ recid: "1", storepart: "2" }, 3));
  assert.equal(url.origin + url.pathname, "https://store.tildaapi.com/api/getproductslist/");
  assert.equal(url.searchParams.get("storepartuid"), "2");
  assert.equal(url.searchParams.get("recid"), "1");
  assert.equal(url.searchParams.get("slice"), "3");
  assert.equal(url.searchParams.get("getallparts"), "true");
});

test("normalizes a real-shaped answer into sections in the venue's order", async () => {
  const raw = JSON.parse(await fixture("tilda_products_sample.json"));
  const menu = normalizeCatalog(raw, { source: "https://aeris.bar/kitchen", fetchedAt: "2026-10-06T16:00:00.000Z" });

  assert.equal(menu.venue, "AERIS");
  assert.equal(menu.dishCount, 6);
  assert.deepEqual(menu.sections.map((s) => s.title), ["Закуски", "Горячие закуски", "Салаты", "Супы", "Пасты и ризотто", "Мясо", "Море", "Гарниры", "Десерты"]);
  assert.deepEqual(menu.sections.map((s) => s.dishes.length), [2, 2, 2, 0, 0, 0, 0, 0, 0]);

  const first = menu.sections[0].dishes[0];
  assert.equal(first.priceRub, 1590);
  assert.equal(first.oldPriceRub, null);
  assert.match(first.description, /пармезан/);
  assert.equal(first.url, "https://aeris.bar/kitchen/tproduct/441829530012-antipastisiri-italyanskie-sirovyali-vyal");
  assert.ok(!("gallery" in first), "no picture urls in the menu file");
});

test("keeps a dish with an unknown section and no price instead of dropping it", () => {
  const raw = { total: 1, parts: [{ uid: 1, title: "All", root: true, sort: 1 }],
    products: [{ uid: 9, title: "<b>Блюдо дня</b>", price: "", descr: "", partuids: "[777]", sort: 5 }] };
  const menu = normalizeCatalog(raw, { source: "s", fetchedAt: "t" });
  assert.deepEqual(menu.sections.map((s) => s.title), ["Без раздела"]);
  assert.equal(menu.sections[0].dishes[0].title, "Блюдо дня");
  assert.equal(menu.sections[0].dishes[0].priceRub, null);
  assert.equal(menu.sections[0].dishes[0].description, null);
});

test("reads every page of a long catalog", async () => {
  const page = (products, total) => ({ ok: true, json: async () => ({ total, parts: [], products }) });
  const first = Array.from({ length: 100 }, (_, i) => ({ uid: i }));
  const calls = [];
  const doFetch = async (url) => { calls.push(new URL(url).searchParams.get("slice")); return calls.length === 1 ? page(first, 103) : page([{ uid: 100 }, { uid: 101 }, { uid: 102 }], 103); };
  const raw = await fetchCatalog(doFetch, { recid: "1", storepart: "2" });
  assert.deepEqual(calls, ["1", "2"]);
  assert.equal(raw.products.length, 103);
});

test("fails loudly on a store error or a strange answer", async () => {
  await assert.rejects(fetchCatalog(async () => ({ ok: false, status: 503 }), { recid: "1", storepart: "2" }), /HTTP 503/);
  await assert.rejects(fetchCatalog(async () => ({ ok: true, json: async () => ({ oops: 1 }) }), { recid: "1", storepart: "2" }), /no products/);
});

test("renders a readable markdown menu", async () => {
  const raw = JSON.parse(await fixture("tilda_products_sample.json"));
  const md = renderMarkdown(normalizeCatalog(raw, { source: "https://aeris.bar/kitchen", fetchedAt: "2026-10-06" }));
  assert.match(md, /^# AERIS — кухня/);
  assert.match(md, /## Закуски\n\n- \*\*АНТИПАСТИ/);
  assert.match(md, /— 1590 ₽/);
});
