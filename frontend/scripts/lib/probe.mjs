/**
 * What the check scripts share (archive-check, referer-check): where the site
 * is, one careful HTTP request, and the Referer a browser would put on it.
 *
 * Plain node:http on purpose, not fetch. These checks are about one header, so
 * every hop of a redirect has to carry exactly the Referer we decide. And no
 * film may be downloaded: the archive is camera masters, hundreds of megabytes
 * each, so a request ends the moment its status line is in.
 */
import http from "node:http";
import https from "node:https";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

export const FRONTEND_DIR = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
/** `npm run dev` and `npm start` both listen here. */
export const DEFAULT_BASE = "http://127.0.0.1:3001";
/** The page a visitor is on. Yandex lets "localhost" through, so it is never the default. */
export const DEFAULT_SITE = "https://c3ag.ru/";
export const YADISK_ROUTE = "/api/yadisk";

const USER_AGENT = "c3ag-checks (+https://c3ag.ru)";
// One socket per request: nothing is left open to keep the script alive.
const agents = {
  "http:": new http.Agent({ keepAlive: false }),
  "https:": new https.Agent({ keepAlive: false }),
};

export const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

/** `--base`, the first bare argument or C3AG_BASE_URL; without a trailing slash. */
export function siteBase(value) {
  const raw = String(value || process.env.C3AG_BASE_URL || DEFAULT_BASE).trim();
  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new Error(`"${raw}" is not an address, expected something like ${DEFAULT_BASE}`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error(`"${raw}" must start with http:// or https://`);
  }
  return url.origin + url.pathname.replace(/\/+$/, "");
}

/** A whole number from the command line, or a clear refusal. */
export function wholeNumber(value, name, min) {
  const n = Number(value);
  if (!Number.isInteger(n) || n < min) {
    throw new Error(`--${name} must be a whole number, ${min} or more (got "${value}")`);
  }
  return n;
}

export function readVideos() {
  return JSON.parse(readFileSync(join(FRONTEND_DIR, "data", "videos.json"), "utf8"));
}

/** "yadisk:/AI/clip.mp4" with prefix "yadisk:" → "/AI/clip.mp4"; anything else → null. */
export function archivePath(ref, prefix) {
  const text = String(ref ?? "");
  return text.startsWith(prefix) ? text.slice(prefix.length) : null;
}

/** The same URLs lib/media-ref.ts builds for the pages. */
export function routeUrl(base, path, { poster = false, resolve = false } = {}) {
  const params = new URLSearchParams({ path });
  if (poster) params.set("kind", "poster");
  if (resolve) params.set("resolve", "1");
  return `${base}${YADISK_ROUTE}?${params}`;
}

/** How much of a refusal is worth reading: a line of text, not a page. */
const ERROR_TEXT_BYTES = 300;
const CONNECT_TIMEOUT_MS = 5_000;

/**
 * One GET. Resolves with the status and headers as soon as they arrive and
 * drops the connection. `body: true` reads the answer (a page, a JSON), at most
 * `maxBytes`. `body: "error"` reads the first line of a 4xx/5xx and nothing of
 * a success, which is how the file hosts are asked.
 */
export function request(url, { headers = {}, timeoutMs = 10_000, body = false, maxBytes = 8_000_000 } = {}) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const lib = target.protocol === "https:" ? https : http;
    let timer;
    let settled = false;
    const finish = (settle, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      settle(value);
    };
    const req = lib.request(
      target,
      {
        method: "GET",
        agent: agents[target.protocol],
        headers: { "user-agent": USER_AGENT, accept: "*/*", ...headers },
      },
      (res) => {
        const answer = { status: res.statusCode ?? 0, headers: res.headers, text: "" };
        const whole = body === true;
        if (!whole && !(body === "error" && answer.status >= 400)) {
          res.destroy();
          finish(resolve, answer);
          return;
        }
        const limit = whole ? maxBytes : ERROR_TEXT_BYTES;
        const chunks = [];
        let size = 0;
        const done = () => {
          answer.text = Buffer.concat(chunks).toString("utf8").slice(0, limit);
          finish(resolve, answer);
        };
        res.on("data", (chunk) => {
          chunks.push(chunk);
          size += chunk.length;
          if (size <= limit) return;
          res.destroy();
          if (whole) finish(reject, new Error(`the answer is larger than ${maxBytes} bytes`));
          else done();
        });
        res.on("end", done);
        res.on("error", (error) => finish(reject, error));
      },
    );
    const giveUp = (what, ms) =>
      setTimeout(() => {
        req.destroy(Object.assign(new Error(`no ${what} in ${ms / 1000} s`), { code: "ETIMEDOUT" }));
      }, ms);
    timer = giveUp("answer", timeoutMs);
    // A host that is there connects in a fraction of a second. One that is not
    // would otherwise cost the whole timeout, on every try, for every file.
    req.on("socket", (socket) => {
      if (!socket.connecting) return;
      const connecting = giveUp("connection", Math.min(timeoutMs, CONNECT_TIMEOUT_MS));
      socket.once("connect", () => clearTimeout(connecting));
      socket.once("close", () => clearTimeout(connecting));
    });
    req.on("error", (error) => finish(reject, error));
    req.end();
  });
}

/** Why a request got no answer at all, in a few words. */
export function describeError(error) {
  const code = error?.code ?? error?.cause?.code;
  const text = error?.message || String(error);
  return code && !text.includes(code) ? `${code} ${text}` : text;
}

/** The first line of a refusal, short enough for a report. */
export function oneLine(text) {
  return String(text ?? "").replace(/\s+/g, " ").trim().slice(0, 120);
}

/**
 * Try again after a pause, a little longer each time. `again(result)` says
 * whether an answer is worth another try; a thrown error always is.
 */
export async function retrying(attempt, { retries = 2, pauseMs = 1500, again = () => false } = {}) {
  for (let n = 0; ; n++) {
    let result;
    let error;
    try {
      result = await attempt();
    } catch (caught) {
      error = caught;
    }
    const retry = error ? true : again(result);
    if (!retry || n >= retries) {
      if (error) throw error;
      return result;
    }
    await sleep(pauseMs * (n + 1));
  }
}

/** Run `worker` over `items`, at most `limit` at a time, results in order. */
export async function pool(items, limit, worker) {
  const results = new Array(items.length);
  let next = 0;
  const lane = async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await worker(items[index], index);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, lane));
  return results;
}

// ── The Referer a browser sends ────────────────────────────────────────────

/** What a browser uses when nothing is declared. */
export const DEFAULT_POLICY = "strict-origin-when-cross-origin";
const POLICIES = new Set([
  "no-referrer",
  "no-referrer-when-downgrade",
  "same-origin",
  "origin",
  "strict-origin",
  "origin-when-cross-origin",
  "strict-origin-when-cross-origin",
  "unsafe-url",
]);
/** Spellings only <meta name="referrer"> still accepts. */
const LEGACY_META = {
  never: "no-referrer",
  default: DEFAULT_POLICY,
  always: "unsafe-url",
  "origin-when-crossorigin": "origin-when-cross-origin",
};

/** A Referrer-Policy header value → the policy it sets (the last known token), or null. */
export function parsePolicy(value) {
  const tokens = String(Array.isArray(value) ? value.join(",") : value ?? "")
    .split(",")
    .map((token) => token.trim().toLowerCase());
  let policy = null;
  for (const token of tokens) if (POLICIES.has(token)) policy = token;
  return policy;
}

/** The policy <meta name="referrer"> declares in a page, or null. The last one wins. */
export function metaPolicy(html) {
  let policy = null;
  for (const [tag] of String(html).matchAll(/<meta\b[^>]*>/gi)) {
    if (!/\bname\s*=\s*["']?referrer["'\s/>]/i.test(tag)) continue;
    const content = /\bcontent\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/i.exec(tag);
    const value = (content?.[1] ?? content?.[2] ?? content?.[3] ?? "").trim().toLowerCase();
    const known = LEGACY_META[value] ?? (POLICIES.has(value) ? value : null);
    if (known) policy = known;
  }
  return policy;
}

/**
 * The Referer header a browser sends from `page` to `target` under `policy`
 * ("" for none). This is the table from the Referrer Policy spec.
 */
export function refererFor(policy, page, target) {
  const from = new URL(page);
  const to = new URL(target);
  const full = from.origin + from.pathname + from.search;
  const origin = from.origin + "/";
  const sameOrigin = from.origin === to.origin;
  const downgrade = from.protocol === "https:" && to.protocol !== "https:";
  switch (policy) {
    case "no-referrer":
      return "";
    case "same-origin":
      return sameOrigin ? full : "";
    case "origin":
      return origin;
    case "strict-origin":
      return downgrade ? "" : origin;
    case "origin-when-cross-origin":
      return sameOrigin ? full : origin;
    case "no-referrer-when-downgrade":
      return downgrade ? "" : full;
    case "unsafe-url":
      return full;
    default:
      if (sameOrigin) return full;
      return downgrade ? "" : origin;
  }
}

/**
 * Request a URL and follow its redirects by hand, deciding the Referer of every
 * hop. With `referer` set ("" for none) that exact value goes on every hop.
 * Without it, each hop carries what a browser on `page` would send under
 * `policy`, and a redirect that carries Referrer-Policy changes the policy for
 * the hops after it, as it does in a browser.
 *
 * Resolves with the last status, the host that gave it, its words if it is a
 * refusal, and the Referer of the first hop. An error carries `host` too.
 * `redirects: false` stops at the first answer, redirect or not.
 */
export async function follow(
  url,
  { page, policy = DEFAULT_POLICY, referer, range, timeoutMs, redirects = true, maxHops = 6 } = {},
) {
  let current = url;
  let inForce = policy;
  let firstReferer;
  for (let hop = 0; hop < maxHops; hop++) {
    const host = new URL(current).host;
    const sent = referer !== undefined ? referer : refererFor(inForce, page, current);
    firstReferer ??= sent;
    const headers = {};
    if (sent) headers.referer = sent;
    if (range) headers.range = range;
    let res;
    try {
      res = await request(current, { headers, timeoutMs, body: "error" });
    } catch (error) {
      throw Object.assign(error, { host });
    }
    const location = res.headers.location;
    if (redirects && res.status >= 300 && res.status < 400 && location) {
      inForce = parsePolicy(res.headers["referrer-policy"]) ?? inForce;
      current = new URL(location, current).href;
      continue;
    }
    return { status: res.status, host, text: oneLine(res.text), referer: firstReferer };
  }
  throw new Error(`more than ${maxHops} redirects`);
}

/** 200 for a whole answer, 206 for a slice of one. */
export const isAlive = (status) => status === 200 || status === 206;
