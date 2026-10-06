/* The bar card, the wine list and the business lunch on aeris.bar are pictures, not text.
   This finds the pictures a page shows and downloads the ones that changed. */

import { createHash } from "node:crypto";

const IMAGE_URL = /https:\/\/static\.tildacdn\.com\/[A-Za-z0-9-]+\/[^"'\s)]+?\.(?:jpe?g|png|webp)/gi;
const DECOR = /logo|favicon|icon|arrow|bg_|pattern/i;
const MAX_IMAGE_BYTES = 25 * 1024 * 1024;

/** Picture URLs on a Tilda page in page order, without logos and duplicates. */
export function menuImages(html) {
  const seen = new Set();
  const result = [];
  for (const match of String(html || "").matchAll(IMAGE_URL)) {
    // Tilda serves resized previews as .../<folder>/-/resizeb/x20/<file>; the original is the file without the transform.
    const url = match[0].replace(/\/-\/[^]*?\/([^/]+)$/, "/$1");
    if (seen.has(url) || DECOR.test(url.split("/").pop())) continue;
    seen.add(url);
    result.push({ url, name: fileName(url) });
  }
  return result;
}

/** A stable file name: Tilda's folder id keeps two "1.jpg" apart. */
export function fileName(url) {
  const parts = new URL(url).pathname.split("/").filter(Boolean);
  const folder = parts[0] || "img";
  const base = decodeURIComponent(parts[parts.length - 1] || "image").replace(/[^\w.\-]+/g, "_");
  return `${folder.slice(-8)}_${base}`;
}

export const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

export async function downloadImage(doFetch, url) {
  const response = await doFetch(url, { signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
  const length = Number(response.headers.get("content-length"));
  if (length > MAX_IMAGE_BYTES) throw new Error(`too large: ${url}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length > MAX_IMAGE_BYTES) throw new Error(`too large: ${url}`);
  return { bytes, contentType: response.headers.get("content-type") || null, sha256: sha256(bytes) };
}
