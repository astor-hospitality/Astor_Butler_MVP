/* HTTP front of the stand-in Presto. Routes are the documented Saby ones; /__admin/* is what the staff would do
   in Presto by hand (confirm, seat, cancel), so a demo or a stand test can drive the whole loop without Saby. */

import { createServer } from "node:http";

const MAX_BODY = 256 * 1024;

/**
 * @param publicUrl where a guest's phone reaches this stub, for the payment page link; defaults to the request's host
 */
export function createStubServer(world, { log = () => {}, publicUrl = "" } = {}) {
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

    // --- the payment page a guest opens from the link; stands in for Saby's own page ---
    const pay = /^\/__pay\/([^/]+)$/.exec(path);
    if (pay) {
      const order = world.get(pay[1]);
      if (!order) return html(res, 404, payPage("Заказ не найден", "Такого заказа в стенде Presto нет.", null));
      if (req.method === "POST") {
        const paid = world.pay(pay[1]);
        if (paid?.error) return html(res, paid.error, payPage("Оплата невозможна", paid.message, null));
        return html(res, 200, payPage("Оплачено", "Спасибо! Ресторан увидит оплату через минуту.", null));
      }
      const amount = world.total(order);
      const paidAlready = order.payState === 200;
      return html(res, 200, payPage(
        paidAlready ? "Уже оплачено" : "Оплата заказа",
        (amount === null ? "Сумму назовёт ресторан." : "К оплате: " + amount + " ₽.") + " Это стенд-двойник Saby: карта не нужна.",
        paidAlready ? null : pay[1],
      ));
    }

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
    const order = /^\/retail\/order\/([^/]+)(?:\/(state|update|cancel|payment-link))?$/.exec(path);
    if (order) {
      const [, id, action] = order;
      if (req.method === "GET" && !action) return answer(res, world.get(id), 404);
      if (req.method === "GET" && action === "state") return answer(res, world.state(id), 404);
      if (req.method === "GET" && action === "payment-link") return answer(res, world.paymentLink(id, publicUrl || "http://" + (req.headers.host || "localhost:8090")), 404);
      if (req.method === "PUT" && action === "update") return answer(res, world.update(id, body), 404);
      if (req.method === "PUT" && action === "cancel") return answer(res, world.cancel(id), 404);
    }
    return send(res, 404, { message: "no such route in the Saby stub: " + req.method + " " + path });
  });
}

function admin(req, res, path, body, world) {
  const m = /^\/__admin\/(orders|reset|confirm|seat|cancel|pay|close)(?:\/([^/]+))?$/.exec(path);
  if (!m) return send(res, 404, { message: "unknown admin route" });
  const [, action, id] = m;
  if (action === "orders" && req.method === "GET") return send(res, 200, world.list());
  if (action === "reset" && req.method === "POST") { world.reset(); return send(res, 200, {}); }
  if (action === "confirm" && req.method === "POST") return answer(res, world.confirm(id), 404);
  if (action === "cancel" && req.method === "POST") return answer(res, world.cancel(id), 404);
  if (action === "seat" && req.method === "POST") return answer(res, world.seat(id, body?.table), 404);
  if (action === "pay" && req.method === "POST") return answer(res, world.pay(id), 404);
  if (action === "close" && req.method === "POST") return answer(res, world.close(id), 404);
  return send(res, 405, { message: "method not allowed" });
}

/** A one-button page in place of Saby's: it says the sum and takes the "payment". */
function payPage(title, text, payableId) {
  const button = payableId
    ? `<form method="post" action="/__pay/${escapeHtml(payableId)}"><button type="submit">Оплатить</button></form>`
    : "";
  return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)} · Presto (стенд)</title>
<style>body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#f6f4ef;color:#222;margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{background:#fff;border-radius:16px;padding:32px;max-width:420px;margin:16px;box-shadow:0 8px 32px rgba(0,0,0,.08)}h1{font-size:22px;margin:0 0 12px}p{line-height:1.5;margin:0 0 20px}
button{font-size:17px;padding:14px 28px;border:0;border-radius:12px;background:#1a1a1a;color:#fff;width:100%}small{color:#777}</style></head>
<body><main><h1>${escapeHtml(title)}</h1><p>${escapeHtml(text)}</p>${button}<p><small>Saby Presto — стенд-двойник для проверки Astor Butler. Настоящие платежи здесь не проходят.</small></p></main></body></html>`;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

function html(res, status, text) {
  res.writeHead(status, { "content-type": "text/html; charset=utf-8", "content-length": Buffer.byteLength(text) });
  res.end(text);
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
