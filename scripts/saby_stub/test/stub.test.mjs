import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { createWorld, STATE, PRODUCT_STATE } from "../lib/world.mjs";
import { createStubServer } from "../lib/server.mjs";
import { redact } from "../lib/proxy.mjs";
import { loadDishes } from "../server.mjs";

let server;
let base;
let world;

before(async () => {
  world = createWorld({ dishes: [{ name: "Борщ со сметаной", cost: 270, section: "Суп" }, { name: "Медовик", cost: 220, section: "Десерты" }] });
  server = createStubServer(world);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => new Promise((resolve) => server.close(resolve)));

async function call(method, path, { token, body } = {}) {
  const response = await fetch(base + path, {
    method,
    headers: { ...(token ? { "X-SBISAccessToken": token } : {}), ...(body ? { "content-type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  return { status: response.status, json: text ? JSON.parse(text) : null };
}

async function auth() {
  const { status, json } = await call("POST", "/oauth/service/", { body: { app_client_id: "c", app_secret: "s", secret_key: "k" } });
  assert.equal(status, 200);
  return json.token;
}

const booking = (over = {}) => ({
  product: "restaurant", pointId: 206, datetime: "2026-10-07 19:00:00", comment: "Astor Butler #12",
  customer: { name: "Иван", phone: "79990000000" }, booking: { visitors: 2, woTable: true }, ...over,
});

test("service authorization issues a token and every retail call needs it", async () => {
  const missing = await call("POST", "/oauth/service/", { body: { app_client_id: "c" } });
  assert.equal(missing.status, 401);
  const token = await auth();
  assert.match(token, /^stub-/);
  assert.equal((await call("GET", "/retail/point/list")).status, 401);
  assert.equal((await call("GET", "/retail/point/list", { token: "nope" })).status, 401);
  const points = await call("GET", "/retail/point/list?product=restaurant", { token });
  assert.equal(points.status, 200);
  assert.equal(points.json.salesPoints[0].id, 206);
  assert.equal(points.json.salesPoints[0].product, "restaurant");
});

test("calendar and hall list follow the documented shapes", async () => {
  const token = await auth();
  const calendar = await call("GET", "/retail/booking/calendar?pointId=206&fromDate=07.10.2026&toDate=08.10.2026", { token });
  assert.equal(calendar.status, 200);
  assert.equal(calendar.json.dates.length, 2);
  assert.equal(calendar.json.dates[0].date, "2026-10-07");
  assert.equal(calendar.json.dates[0].halls[0].hallId, 271);
  assert.ok(calendar.json.dates[0].halls[0].intervals.every((i) => i >= 0 && i <= 47));

  const halls = await call("GET", "/retail/hall/list?pointId=206&date=2026-10-07%2019:00:00", { token });
  assert.equal(halls.status, 200);
  const items = halls.json.halls[0].items;
  assert.equal(items.length, 9);
  assert.deepEqual(items.map((i) => i.name).slice(0, 3), ["1", "2", "3"]);
  assert.equal(items.find((i) => i.name === "Бар").isBookingLocked, true);
  assert.ok(items.every((i) => i.busy === false));
  assert.equal((await call("GET", "/retail/hall/list?pointId=206&date=tomorrow", { token })).status, 400);
});

test("create, state, confirm by staff, update, cancel — the whole loop Butler needs", async () => {
  const token = await auth();
  const created = await call("POST", "/retail/order/create", { token, body: booking() });
  assert.equal(created.status, 200);
  const id = created.json.externalId;
  assert.match(id, /^[0-9a-f-]{36}$/);

  let state = await call("GET", `/retail/order/${id}/state`, { token });
  assert.equal(state.json.state, STATE.ONLINE);
  assert.equal(state.json.productState, PRODUCT_STATE.NEW);

  const full = await call("GET", `/retail/order/${id}`, { token });
  assert.equal(full.json.booking.woTable, true);
  assert.equal(full.json.customer.phone, "79990000000");

  const confirmed = await call("POST", `/__admin/confirm/${id}`);
  assert.equal(confirmed.status, 200);
  state = await call("GET", `/retail/order/${id}/state`, { token });
  assert.equal(state.json.state, STATE.CONFIRMED);
  assert.equal(state.json.productState, PRODUCT_STATE.ACCEPTED);

  const seated = await call("POST", `/__admin/seat/${id}`, { body: { table: 3035 } });
  assert.equal(seated.json.booking.table, 3035);

  const updated = await call("PUT", `/retail/order/${id}/update`, { token, body: booking({ datetime: "2026-10-07 20:00:00", booking: { visitors: 3, woTable: false, table: 3035, hall: 271 } }) });
  assert.equal(updated.status, 200);
  assert.equal((await call("GET", `/retail/order/${id}`, { token })).json.booking.visitors, 3);

  const states = await call("GET", `/retail/order/states?externalIds=["${id}","missing"]`, { token });
  assert.equal(states.json[0].state, STATE.CONFIRMED);
  assert.equal(states.json[1].state, null);

  const cancelled = await call("PUT", `/retail/order/${id}/cancel`, { token });
  assert.equal(cancelled.status, 200);
  state = await call("GET", `/retail/order/${id}/state`, { token });
  assert.equal(state.json.state, STATE.CANCELLED);
  assert.equal(state.json.productState, PRODUCT_STATE.CANCELLED);
  assert.equal((await call("PUT", `/retail/order/${id}/update`, { token, body: { comment: "late" } })).status, 409);
  assert.equal((await call("GET", "/retail/order/unknown/state", { token })).status, 404);
});

test("a table booked by Butler is busy for the next guest at the same time, free two hours later", async () => {
  const token = await auth();
  const created = await call("POST", "/retail/order/create", { token, body: booking({ datetime: "2026-10-09 19:00:00", booking: { visitors: 2, woTable: false, hall: 271, table: 3031 } }) });
  assert.equal(created.status, 200, JSON.stringify(created.json));
  const same = await call("GET", "/retail/hall/list?pointId=206&date=2026-10-09%2020:00:00", { token });
  assert.equal(same.json.halls[0].items.find((i) => i.id === 3031).busy, true);
  const later = await call("GET", "/retail/hall/list?pointId=206&date=2026-10-09%2021:30:00", { token });
  assert.equal(later.json.halls[0].items.find((i) => i.id === 3031).busy, false);

  const clash = await call("POST", "/retail/order/create", { token, body: booking({ datetime: "2026-10-09 19:30:00", booking: { visitors: 2, woTable: false, hall: 271, table: 3031 } }) });
  assert.equal(clash.status, 400);
  assert.match(clash.json.message, /busy/);
  const tooSmall = await call("POST", "/retail/order/create", { token, body: booking({ datetime: "2026-10-09 12:00:00", booking: { visitors: 5, woTable: false, hall: 271, table: 3032 } }) });
  assert.match(tooSmall.json.message, /too small/);
  const locked = await call("POST", "/retail/order/create", { token, body: booking({ datetime: "2026-10-09 12:00:00", booking: { visitors: 2, woTable: false, hall: 271, table: 3039 } }) });
  assert.match(locked.json.message, /locked/);
});

test("price list and nomenclature search answer the lunch lookup", async () => {
  const token = await auth();
  const priceList = await call("GET", "/retail/nomenclature/price-list?pointId=206&actualDate=07.10.2026%2012:00:00", { token });
  assert.equal(priceList.json.priceLists[0].id, 4);
  const found = await call("GET", "/retail/v2/nomenclature/list?pointId=206&priceListId=4&searchString=борщ", { token });
  const dish = found.json.nomenclatures.find((n) => !n.isParent);
  assert.equal(dish.name, "Борщ со сметаной");
  assert.equal(dish.cost, 270);
  assert.equal(typeof dish.id, "number");
  const wrongList = await call("GET", "/retail/v2/nomenclature/list?pointId=206&priceListId=9&searchString=борщ", { token });
  assert.equal(wrongList.json.nomenclatures.length, 0);

  const withDishes = await call("POST", "/retail/order/create", { token, body: booking({ nomenclatures: [{ id: dish.id, count: 2, priceListId: 4, name: dish.name }] }) });
  assert.equal(withDishes.status, 200);
  const unknownDish = await call("POST", "/retail/order/create", { token, body: booking({ nomenclatures: [{ id: 999999, count: 1 }] }) });
  assert.equal(unknownDish.status, 400);
});

test("auto-confirm plays the busy staff after the configured delay", async () => {
  let clock = 1_000_000;
  const auto = createWorld({ autoConfirmSeconds: 30, now: () => clock });
  const { externalId } = auto.create(booking());
  assert.equal(auto.state(externalId).state, STATE.ONLINE);
  clock += 29_000;
  assert.equal(auto.state(externalId).state, STATE.ONLINE);
  clock += 2_000;
  assert.equal(auto.state(externalId).state, STATE.CONFIRMED);
});

test("the stub knows the AERIS lunch dishes from the repository file", async () => {
  const dishes = await loadDishes();
  assert.ok(dishes.some((d) => d.name === "Борщ со сметаной" && d.cost === 270), "lunch file is read");
  assert.ok(dishes.length >= 17);
});

test("recorded fixtures carry no guest data, tokens or keys", () => {
  const record = redact({ token: "abc", customer: { name: "Иван", phone: "79990000000" }, booking: { table: 3035 }, nested: [{ app_secret: "x", id: 7 }] });
  assert.deepEqual(record, { token: "<secret>", customer: { name: "<name:4>", phone: "<phone:11>" }, booking: { table: 3035 }, nested: [{ app_secret: "<secret>", id: 7 }] });
});

test("payment: the link leads to a page that pays the order, the state shows it, closing ends the visit", async () => {
  const token = await auth();
  const found = await call("GET", "/retail/v2/nomenclature/list?pointId=206&priceListId=4&searchString=борщ", { token });
  const dish = found.json.nomenclatures.find((n) => !n.isParent);
  const { json: created } = await call("POST", "/retail/order/create", { token, body: booking({ nomenclatures: [{ id: dish.id, count: 2, priceListId: 4, name: dish.name }] }) });
  const id = created.externalId;

  const link = await call("GET", `/retail/order/${id}/payment-link`, { token });
  assert.equal(link.status, 200);
  assert.equal(link.json.link, `${base}/__pay/${id}`);
  assert.equal(link.json.amount, 540);
  assert.equal((await call("GET", `/retail/order/${id}/payment-link`)).status, 401, "the link needs the token like every Saby route");

  const page = await fetch(link.json.link);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /540 ₽/);
  assert.equal((await call("GET", `/retail/order/${id}/state`, { token })).json.payState, 0);

  const paid = await fetch(link.json.link, { method: "POST" });
  assert.equal(paid.status, 200);
  assert.match(await paid.text(), /Оплачено/);
  const state = (await call("GET", `/retail/order/${id}/state`, { token })).json;
  assert.equal(state.payState, 200);
  assert.equal(state.payments.length, 1);
  assert.equal(state.payments[0].sum, 540);
  assert.match(await (await fetch(link.json.link)).text(), /Уже оплачено/);

  const closed = await call("POST", `/__admin/close/${id}`);
  assert.equal(closed.status, 200);
  assert.equal(closed.json.state, STATE.CLOSED);
  assert.equal(closed.json.productState, PRODUCT_STATE.DONE);
  assert.equal((await call("GET", `/retail/order/${id}/payment-link`, { token })).status, 409, "a closed order has no payment page");
  assert.equal((await call("GET", `/retail/order/missing/payment-link`, { token })).status, 404);
});

test("payment: the admin can mark an order paid, and a public URL replaces the request host in the link", async () => {
  const token = await auth();
  const { json: created } = await call("POST", "/retail/order/create", { token, body: booking() });
  const paid = await call("POST", `/__admin/pay/${created.externalId}`);
  assert.equal(paid.status, 200);
  assert.equal(paid.json.payState, 200);
  assert.equal((await call("GET", `/retail/order/${created.externalId}/payment-link`, { token })).json.amount, null, "no dishes, no sum");

  const demo = createStubServer(createWorld(), { publicUrl: "https://demo.example.org/presto/" });
  await new Promise((resolve) => demo.listen(0, "127.0.0.1", resolve));
  try {
    const demoBase = `http://127.0.0.1:${demo.address().port}`;
    const authed = await fetch(demoBase + "/oauth/service/", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ app_client_id: "c", app_secret: "s", secret_key: "k" }) });
    const demoToken = (await authed.json()).token;
    const made = await fetch(demoBase + "/retail/order/create", { method: "POST", headers: { "content-type": "application/json", "X-SBISAccessToken": demoToken }, body: JSON.stringify(booking()) });
    const { externalId } = await made.json();
    const link = await fetch(`${demoBase}/retail/order/${externalId}/payment-link`, { headers: { "X-SBISAccessToken": demoToken } });
    assert.equal((await link.json()).link, `https://demo.example.org/presto/__pay/${externalId}`);
  } finally {
    await new Promise((resolve) => demo.close(resolve));
  }
});
