/**
 * Where the films are streamed from: the base address of the storage that holds the HLS folders made by tools/hls/
 * (`<key>/master.m3u8` with its 1080p, 720p and 480p renditions). It is read at build time. While it is empty no work
 * has a stream, and every film plays the way it did before (Drive's player, or the Disk file through /api/watch).
 */
const BASE = (process.env.NEXT_PUBLIC_VIDEO_BASE_URL ?? "").trim().replace(/\/+$/, "");

/** The full address of a work's stream, from its path in work-media.ts (`/<key>/master.m3u8`); none without a base. */
export function streamUrl(path: string | undefined): string | undefined {
  if (!BASE || !path) return undefined;
  return `${BASE}/${path.replace(/^\/+/, "")}`;
}
