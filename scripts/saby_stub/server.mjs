#!/usr/bin/env node
/* Stand-in Saby Presto for stand tests and the demo, or a recording proxy to the real one.

   node scripts/saby_stub/server.mjs                  stub on :8090 with the AERIS menu and 9 tables
   SABY_STUB_MODE=record node scripts/saby_stub/server.mjs   proxy to api.sbis.ru, fixtures into scripts/saby_stub/fixtures

   Environment (stub):   SABY_STUB_PORT=8090  SABY_STUB_POINT_ID=206  SABY_STUB_HALL_ID=271  SABY_STUB_PRICE_LIST_ID=4
                         SABY_STUB_AUTO_CONFIRM_SECONDS=0   (>0: bookings confirm themselves after N seconds, like busy staff)
   Environment (stub):   SABY_STUB_PUBLIC_URL=https://demo.example.org/presto   where a guest's phone reaches the payment page (default: the request host)
   Environment (record): SABY_UPSTREAM=https://api.sbis.ru  SABY_AUTH_UPSTREAM=https://online.sbis.ru  SABY_STUB_FIXTURES=<dir>

   Point Butler at it:   SABY_API_BASE_URL=http://localhost:8090  SABY_AUTH_URL=http://localhost:8090/oauth/service/
                         SABY_APP_CLIENT_ID=stub SABY_APP_SECRET=stub SABY_SECRET_KEY=stub SABY_POINT_ID=206 SABY_HALL_ID=271 */

import { fileURLToPath } from "node:url";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { createWorld } from "./lib/world.mjs";
import { createStubServer } from "./lib/server.mjs";
import { createRecordingProxy } from "./lib/proxy.mjs";

const HERE = fileURLToPath(new URL(".", import.meta.url));
const env = process.env;
const port = Number(env.SABY_STUB_PORT || 8090);
const log = (...parts) => console.log(new Date().toISOString(), ...parts);

/** The dishes Presto would know: the AERIS business lunch file plus the kitchen snapshot from aeris.bar, when present. */
export async function loadDishes() {
  const dishes = [];
  const seen = new Set();
  const add = (name, cost, section, description) => {
    const key = String(name || "").trim().toLowerCase();
    if (!key || seen.has(key)) return;
    seen.add(key);
    dishes.push({ name: String(name).trim(), cost: cost ?? null, section: section || "", description: description || "" });
  };
  try {
    const lunch = JSON.parse(await readFile(resolve(HERE, "../../src/main/resources/business-lunch/aeris.json"), "utf8"));
    for (const course of lunch.courses || []) for (const dish of course.dishes || []) add(dish.title, dish.priceRub, "Бизнес-ланч · " + course.title);
  } catch { /* no lunch file in this checkout */ }
  try {
    const kitchen = JSON.parse(await readFile(resolve(HERE, "../../src/main/resources/menu/aeris/site/kitchen.json"), "utf8"));
    for (const section of kitchen.sections || []) for (const dish of section.dishes || []) add(dish.title, dish.priceRub, section.title, dish.description);
  } catch { /* no kitchen snapshot in this checkout */ }
  return dishes;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if ((env.SABY_STUB_MODE || "stub") === "record") {
    const server = createRecordingProxy({
      apiUpstream: env.SABY_UPSTREAM || "https://api.sbis.ru",
      authUpstream: env.SABY_AUTH_UPSTREAM || "https://online.sbis.ru",
      fixturesDir: env.SABY_STUB_FIXTURES || resolve(HERE, "fixtures"),
      log,
    });
    server.listen(port, () => log(`Saby recording proxy on :${port} → ${env.SABY_UPSTREAM || "https://api.sbis.ru"}; fixtures are redacted`));
  } else {
    const dishes = await loadDishes();
    const world = createWorld({
      pointId: Number(env.SABY_STUB_POINT_ID || 206),
      hallId: Number(env.SABY_STUB_HALL_ID || 271),
      priceListId: Number(env.SABY_STUB_PRICE_LIST_ID || 4),
      autoConfirmSeconds: Number(env.SABY_STUB_AUTO_CONFIRM_SECONDS || 0),
      dishes,
    });
    const server = createStubServer(world, { log, publicUrl: env.SABY_STUB_PUBLIC_URL || "" });
    server.listen(port, () => log(`Saby stub on :${port}: point ${world.point.id}, hall ${world.hallId}, ${world.tables.length} tables, ${dishes.length} dishes, auto-confirm ${env.SABY_STUB_AUTO_CONFIRM_SECONDS || 0}s`));
  }
}
