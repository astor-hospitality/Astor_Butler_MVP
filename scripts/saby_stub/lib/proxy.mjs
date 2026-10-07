/* Record mode: forwards every call to the real Saby and writes the exchange, with guest data and tokens blanked,
   into fixtures/. One live smoke through this proxy turns the stub's assumptions about undocumented answers
   (order/create, order/{id}) into recorded facts. */

import { createServer } from "node:http";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

const AUTH_PREFIX = "/oauth/service";

export function createRecordingProxy({ apiUpstream, authUpstream, fixturesDir, log = () => {} }) {
  let counter = 0;
  return createServer(async (req, res) => {
    const url = new URL(req.url, "http://proxy");
    const isAuth = url.pathname.startsWith(AUTH_PREFIX);
    const target = new URL(url.pathname + url.search, isAuth ? authUpstream : apiUpstream);
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const requestBody = Buffer.concat(chunks);

    const headers = {};
    for (const name of ["content-type", "accept", "x-sbisaccesstoken"]) {
      if (req.headers[name]) headers[name] = req.headers[name];
    }
    let upstream;
    try {
      upstream = await fetch(target, { method: req.method, headers, body: requestBody.length ? requestBody : undefined, redirect: "manual", signal: AbortSignal.timeout(20000) });
    } catch (error) {
      log("upstream failed", req.method, url.pathname, error.message);
      res.writeHead(502, { "content-type": "application/json" });
      return res.end(JSON.stringify({ message: "upstream failed: " + error.message }));
    }
    const responseBody = Buffer.from(await upstream.arrayBuffer());
    res.writeHead(upstream.status, { "content-type": upstream.headers.get("content-type") || "application/json" });
    res.end(responseBody);

    const name = `${new Date().toISOString().replace(/[:.]/g, "-")}-${String(++counter).padStart(3, "0")}-${req.method}-${url.pathname.replace(/[^a-z0-9]+/gi, "_").replace(/^_|_$/g, "")}.json`;
    const record = {
      request: { method: req.method, path: url.pathname, query: redactQuery(url.searchParams), body: redact(tryJson(requestBody)) },
      response: { status: upstream.status, body: redact(tryJson(responseBody)) },
      recordedAt: new Date().toISOString(),
    };
    try {
      await mkdir(fixturesDir, { recursive: true });
      await writeFile(join(fixturesDir, name), JSON.stringify(record, null, 2) + "\n");
      log("recorded", name, upstream.status);
    } catch (error) {
      log("could not write fixture", error.message);
    }
  });
}

const SECRET_KEYS = new Set(["app_client_id", "app_secret", "secret_key", "token", "access_token", "sid"]);
const PERSONAL_KEYS = new Set(["name", "lastname", "patronymic", "email", "phone", "comment"]);

/** Keys that identify a person or a credential are replaced by their type and length; structure and ids stay. */
export function redact(value) {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === "object") {
    const out = {};
    for (const [key, inner] of Object.entries(value)) {
      const normalized = key.toLowerCase().replace(/[-_]/g, "");
      if (SECRET_KEYS.has(key.toLowerCase()) || /token|secret|password|authorization|cookie|apikey|sessionkey/.test(normalized)) out[key] = "<secret>";
      else if (PERSONAL_KEYS.has(key.toLowerCase())) out[key] = typeof inner === "string" ? `<${key.toLowerCase()}:${inner.length}>` : "<personal>";
      else out[key] = redact(inner);
    }
    return out;
  }
  return value;
}

function redactQuery(params) {
  const out = {};
  for (const [key, inner] of params) Object.assign(out, redact({ [key]: inner }));
  return out;
}

export function tryJson(buffer) {
  const text = buffer.toString("utf8");
  if (!text.trim()) return null;
  // Error pages and plaintext can contain credentials or guest details. Never persist them.
  try {
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === "object" ? parsed : { "<body>": "<omitted>" };
  } catch { return { "<body>": "<non-json omitted>" }; }
}
