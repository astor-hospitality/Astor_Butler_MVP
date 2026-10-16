import { mediaManifest, type MediaEntry } from "@content/media-manifest";

/**
 * Where the site's media lives (photos, video, fonts). It is never in git: on the server it is an S3 bucket or a CDN in
 * front of it, named by NEXT_PUBLIC_MEDIA_BASE_URL (no slash at the end, e.g. https://storage.yandexcloud.net/c3ag-media/site).
 * Under it the paths are the same as in the local folder: images/…, videos/…, fonts/….
 * When the variable is empty (local work) the same files are served by Next itself from public/media (git-ignored).
 *
 * The value is read at build time and baked into the pages, like every NEXT_PUBLIC_* variable.
 */
const BASE = (process.env.NEXT_PUBLIC_MEDIA_BASE_URL ?? "").trim().replace(/\/+$/, "");

/** The root the media paths hang from: the S3 address, or /media for local work. */
export const mediaBase = BASE || "/media";

const manifest: Record<string, MediaEntry> = mediaManifest;

function entry(path: string): [string, MediaEntry] {
  const key = path.replace(/^\/+/, "");
  const found = manifest[key];
  if (!found) {
    throw new Error(`content/media-manifest.ts has no "${key}": put the file into public/media and run \`npm run media:manifest\`.`);
  }
  return [key, found];
}

/**
 * The address of one media file, by its path under the media root ("images/refs/hero.png", "videos/hero.mp4").
 * On S3 the address carries a short hash of the file (`?v=…`), so a replaced file gets a new address and the caches can
 * keep every file for a year. A path that is not in the manifest stops the build: a missing picture is found then,
 * not by a visitor.
 */
export function mediaUrl(path: string): string {
  const [key, { v }] = entry(path);
  return BASE ? `${BASE}/${key}?v=${v}` : `/media/${key}`;
}

/** A picture: its address and its size in pixels (from the manifest), for next/image. */
export function mediaImage(path: string): { src: string; width: number; height: number } {
  const [key, { width, height }] = entry(path);
  if (!width || !height) throw new Error(`content/media-manifest.ts has no picture size for "${key}".`);
  return { src: mediaUrl(key), width, height };
}
