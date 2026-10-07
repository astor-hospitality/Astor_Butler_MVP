/* HTTP front of the stand-in Presto. Routes are the documented Saby ones; /__admin/* is what the staff would do
   in Presto by hand (confirm, seat, cancel), so a demo or a stand test can drive the whole loop without Saby. */

import { createServer } from "node:http";

const MAX_BODY = 256 * 1024;

export function createStubServer(world, { log = () => {} } = {}) {
  return createServer(async (req, res) => {
    const url = new URL(req.url, "http://stub");
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const q = Object.fromEntries(url.searchParams);
    let body = null;
    try {
      body = await readJson(req);
    } catch (e) {
      return send(res, 400, { message: e.message });
    }
    log(req.method, path, q);

    // --- service authorization: POST https://online.sbis.ru/oauth/service/ ---
    if (req.method === "POST" && path === "/oauth/service") {
      if (!body?.app_client_id || !body?.app_secret || !body?.secret_key) return send(res, 401, { message: "app_client_id, app_secret, secret_key are required" });
      return send(res, 200, { token: world.issueToken() });
    }
    if (path === "/__health") return send(res, 200, { ok: true, orders: world.list().length });

    // --- admin: what the staff do in Presto ---
    if (path.startsWith("/__admin/")) return admin(req, res, path, body, world);

    // --- everything else needs the token header ---
    if (!world.validToken(req.headers["x-sbisaccesstoken"])) return send(res, 401, { message: "X-SBISAccessToken is missing or unknown" });

    if (req.method === "GET" && path === "/retail/point/list") {
      const points = q.product && q.product !== "restaurant" ? [] : [world.point];
      return send(res, 200, { salesPoints: points, outcome: { hasMore: false } });
    }
    if (req.method === "GET" && path === "/retail/booking/calendar") return answer(res, world.calendar(q));
    if (req.method === "GET" && path === "/retail/hall/list") return answer(res, world.hallList(q));
    if (req.method === "GET" && path === "/retail/nomenclature/price-list") return answer(res, world.priceList(q));
    if (req.method === "GET" && path === "/retail/v2/nomenclature/list") return answer(res, world.nomenclatureList(q));
    if (req.method === "POST" && path === "/retail/order/create") return answer(res, world.create(body));
    if (req.method === "GET" && path === "/retail/order/states") {
      const ids = parseIds(q.externalIds);
      return send(res, 200, ids.map((id) => ({ externalId: id, ...(world.state(id) || { state: null }) })));
    }
    const order = /^\/retail\/order\/([^/]+)(?:\/(state|update|cancel))?$/.exec(path);
    if (order) {
      const [, id, action] = order;
      if (req.method === "GET" && !action) return answer(res, world.get(id), 404);
      if (req.method === "GET" && action === "state") return answer(res, world.state(id), 404);
      if (req.method === "PUT" && action === "update") return answer(res, world.update(id, body), 404);
      if (req.method === "PUT" && action === "cancel") return answer(res, world.cancel(id), 404);
    }
    return send(res, 404, { message: "no such route in the Saby stub: " + req.method + " " + path });
  });
}

function admin(req, res, path, body, world) {
  const m = /^\/__admin\/(orders|reset|confirm|seat|cancel)(?:\/([^/]+))?$/.exec(path);
  if (!m) return send(res, 404, { message: "unknown admin route" });
  const [, action, id] = m;
  if (action === "orders" && req.method === "GET") return send(res, 200, world.list());
  if (action === "reset" && req.method === "POST") { world.reset(); return send(res, 200, {}); }
  if (action === "confirm" && req.method === "POST") return answer(res, world.confirm(id), 404);
  if (action === "cancel" && req.method === "POST") return answer(res, world.cancel(id), 404);
  if (action === "seat" && req.method === "POST") return answer(res, world.seat(id, body?.table), 404);
  return send(res, 405, { message: "method not allowed" });
}

function answer(res, result, missing = 400) {
  if (result === null || result === undefined) return send(res, missing, { message: "not found" });
  if (result.error) return send(res, result.error, { message: result.message });
  return send(res, 200, result);
}

function send(res, status, json) {
  const text = JSON.stringify(json);
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "content-length": Buffer.byteLength(text) });
  res.end(text);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) reject(new Error("body too large"));
      else chunks.push(chunk);
    });
    req.on("end", () => {
      const text = Buffer.concat(chunks).toString("utf8").trim();
      if (!text) return resolve(null);
      try { resolve(JSON.parse(text)); } catch { reject(new Error("body is not JSON")); }
    });
    req.on("error", reject);
  });
}

function parseIds(raw) {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.map(String);
  } catch { /* fall through */ }
  return raw.replace(/^\[|\]$/g, "").split(",").map((s) => s.trim()).filter(Boolean);
}
