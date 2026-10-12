/**
 * A stand-in for Yandex.Disk, so the build can be checked with no network and
 * no real archive (npm run check:referer:build).
 *
 * It plays both sides the site talks to:
 *   /v1/disk/public/resources…   the public API: hands out a "signed" link to itself
 *   /file/…, /preview/…          the file host: 403 to a request whose Referer
 *                                names another site; the file to a request with
 *                                no Referer, with localhost or with one of
 *                                Yandex's own pages. That is what the real host
 *                                does, measured in PR #72.
 */
import http from "node:http";
import { FRONTEND_DIR } from "./probe.mjs";

/** Where app/api/yadisk/route.ts looks for the Disk. */
const DISK_API_ORIGIN = "https://cloud-api.yandex.net";

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve(`http://127.0.0.1:${server.address().port}`));
  });
}

/** Does the file host turn this Referer away? */
function refuses(referer) {
  if (!referer) return false;
  let host;
  try {
    host = new URL(referer).hostname;
  } catch {
    return true;
  }
  return !(host === "localhost" || host === "yandex.ru" || host.endsWith(".yandex.ru"));
}

export async function startFakeDisk() {
  let self = "";
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, self);
    const send = (status, headers, body) => {
      res.writeHead(status, headers);
      res.end(body);
    };
    const json = (body) => send(200, { "content-type": "application/json" }, JSON.stringify(body));
    const path = url.searchParams.get("path") ?? "/";

    if (url.pathname === "/v1/disk/public/resources/download") {
      return json({ href: `${self}/file${encodeURI(path)}`, method: "GET", templated: false });
    }
    if (url.pathname === "/v1/disk/public/resources") {
      return json({ path, type: "file", preview: `${self}/preview${encodeURI(path)}` });
    }
    if (url.pathname.startsWith("/file/") || url.pathname.startsWith("/preview/")) {
      if (refuses(req.headers.referer)) return send(403, { "content-type": "text/plain" }, "Forbidden");
      if (url.pathname.startsWith("/preview/")) return send(200, { "content-type": "image/jpeg" }, "jpeg");
      return req.headers.range
        ? send(206, { "content-type": "video/mp4", "content-range": "bytes 0-0/1000" }, "f")
        : send(200, { "content-type": "video/mp4" }, "film");
    }
    return send(404, { "content-type": "text/plain" }, "the stand-in has no such address");
  });
  self = await listen(server);
  return { url: self, close: () => server.close() };
}

/**
 * Serve the production build (.next) from this process, with the Disk API
 * swapped for the stand-in at `diskUrl`. Nothing in the site's code changes:
 * the route still calls the address it always calls, and that one address is
 * answered locally.
 */
export async function startBuiltSite(diskUrl) {
  const realFetch = globalThis.fetch;
  globalThis.fetch = (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (!url.startsWith(`${DISK_API_ORIGIN}/`)) return realFetch(input, init);
    return realFetch(diskUrl + url.slice(DISK_API_ORIGIN.length), init);
  };

  process.env.NODE_ENV = "production";
  const { default: next } = await import("next");
  const server = http.createServer();
  const url = await listen(server);
  const app = next({
    dev: false,
    dir: FRONTEND_DIR,
    hostname: "127.0.0.1",
    port: server.address().port,
    quiet: true,
  });
  // Next warns here that `next start` is not how a standalone build is served.
  // True for production and beside the point for a check, so that one line is dropped.
  const warn = console.warn;
  console.warn = (...args) => {
    if (!args.join(" ").includes("output: standalone")) warn(...args);
  };
  try {
    await app.prepare();
  } finally {
    console.warn = warn;
  }
  const handle = app.getRequestHandler();
  server.on("request", (req, res) => handle(req, res));
  return { url, close: () => server.close() };
}
