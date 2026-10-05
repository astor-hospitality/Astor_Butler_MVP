const ASSET_MANIFEST = {
  "/": "index.html",
  "/index.html": "index.html",
  "/astor_butler": "astor_butler/index.html",
  "/astor_butler.html": "astor_butler/index.html",
  "/astor_butler/index.html": "astor_butler/index.html",
  "/astor_concierge": "astor_concierge/index.html",
  "/astor_concierge.html": "astor_concierge/index.html",
  "/astor_concierge/index.html": "astor_concierge/index.html",
  "/astor_concierge/feed": "astor_concierge/feed/index.html",
  "/astor_concierge/feed.html": "astor_concierge/feed/index.html",
  "/astor_concierge/feed/index.html": "astor_concierge/feed/index.html",
  "/css/feed.css": "css/feed.css",
  "/js/feed.js": "js/feed.js",
  "/data/venues.json": "data/venues.json",
  "/staff": "staff/index.html",
  "/staff.html": "staff/index.html",
  "/staff/index.html": "staff/index.html",
  "/css/staff.css": "css/staff.css",
  "/js/staff.js": "js/staff.js",
  "/js/staff-auth.js": "js/staff-auth.js",
  "/css/style.css": "css/style.css",
  "/js/main.js": "js/main.js",
  "/js/widget.js": "js/widget.js",
  "/assets/astor-butler-mascot.png": "assets/astor-butler-mascot.png",
  "/assets/astor-butler-silhouette.svg": "assets/astor-butler-silhouette.svg",
  "/assets/ab-logo.jpg": "assets/ab-logo.jpg",
  "/assets/favicon.svg": "assets/favicon.svg",
  "/assets/hero-restaurant.svg": "assets/hero-restaurant.svg",
  "/assets/og-astor-assist.png": "assets/og-astor-assist.png",
  "/assets/og-image.png": "assets/og-image.png",
  "/assets/scene-hostess.svg": "assets/scene-hostess.svg",
  "/assets/scene-vision.svg": "assets/scene-vision.svg",
  "/docs/offer.html": "docs/offer.html",
  "/docs/comparison.html": "docs/comparison.html",
  "/docs/brand.html": "docs/brand.html",
  "/docs/docs.css": "docs/docs.css"
};

const MIME_TYPES = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".jpg": "image/jpeg",
  ".json": "application/json; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml; charset=utf-8"
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/staff" || url.pathname === "/staff.html") {
      url.pathname = "/staff/";
      return Response.redirect(url.href, 303);
    }
    const pathname = normalizePath(url.pathname);
    const assetPath = ASSET_MANIFEST[pathname];

    if (!assetPath) {
      return new Response("Not found", { status: 404 });
    }

    const asset = await env.ASSETS.fetch(new URL(assetPath, "https://assets.local/"));
    const headers = new Headers(asset.headers);
    headers.set("Content-Type", contentType(assetPath));
    headers.set("Cache-Control", cacheControl(assetPath));

    return new Response(asset.body, {
      status: asset.status,
      headers
    });
  }
};

function normalizePath(pathname) {
  if (pathname !== "/" && pathname.endsWith("/")) {
    return pathname.slice(0, -1) + ".html";
  }
  return pathname;
}

function contentType(assetPath) {
  const extension = assetPath.slice(assetPath.lastIndexOf("."));
  return MIME_TYPES[extension] || "application/octet-stream";
}

function cacheControl(assetPath) {
  if (assetPath.startsWith("staff/") || assetPath === "js/staff.js" || assetPath === "js/staff-auth.js") return "no-store";
  if (assetPath.endsWith(".html")) {
    return "public, max-age=60";
  }
  return "public, max-age=31536000, immutable";
}
