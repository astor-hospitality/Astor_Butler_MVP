import { NextResponse } from "next/server";

/**
 * Streams the portfolio straight off the public Yandex.Disk archive, so no
 * object storage, database or media service has to exist yet.
 *
 * How it works: Yandex hands out short-lived signed URLs for public files, so
 * they can't be baked into data/videos.json. This route resolves a path in the
 * archive to a fresh URL. The bytes always travel from Yandex straight to the
 * visitor and never through us — that is the difference between resolving a
 * link and paying a bandwidth bill.
 *
 * Two answer shapes, because images and media disagree:
 *   ?kind=poster   → 302. <img> follows a cross-origin redirect happily.
 *                    The redirect carries Referrer-Policy: no-referrer, because
 *                    the download host answers 403 to a request that names
 *                    another site in Referer. A browser applies the policy of a
 *                    redirect to the request it causes, so this one header
 *                    covers <img>, a CSS background and <video poster> alike.
 *   ?resolve=1     → {"url": …}. A <video> element does NOT survive that
 *                    redirect — measured: the same 5 MB clip that plays in
 *                    4.5 s from the direct link hangs at readyState 0 behind a
 *                    302. So the client asks for the URL first, then assigns
 *                    it to src, which is the path that actually works.
 *
 * Verified against the real archive: the storage host answers 206 Partial
 * Content with Accept-Ranges, so seeking works, and sends
 * Access-Control-Allow-Origin: *, so <video> is happy once pointed straight
 * at it.
 *
 * ponytail: the href cache is a plain in-process Map — correct for one node,
 * and it simply misses more often behind several instances. Swap for Redis or
 * the Next data cache only if the Yandex API starts rate-limiting.
 */

const PUBLIC_KEY =
  process.env.NEXT_PUBLIC_YADISK_PUBLIC_KEY ?? "https://disk.yandex.ru/d/oopTdDN0CTuIig";

const API = "https://cloud-api.yandex.net/v1/disk/public";
/** Well inside the signed URL's own lifetime, so we never serve a dead link. */
const CACHE_TTL_MS = 15 * 60 * 1000;

type Entry = { url: string; expires: number };
const cache = new Map<string, Entry>();

async function resolveVideo(path: string): Promise<string | null> {
  const url = `${API}/resources/download?${new URLSearchParams({ public_key: PUBLIC_KEY, path })}`;
  const res = await fetch(url, { cache: "no-store" });
  if (!res.ok) return null;
  const json = (await res.json()) as { href?: string };
  return json.href ?? null;
}

async function resolvePoster(path: string): Promise<string | null> {
  const url = `${API}/resources?${new URLSearchParams({
    public_key: PUBLIC_KEY,
    path,
    preview_size: "XL",
  })}`;
  const res = await fetch(url, { cache: "no-store" });
  if (!res.ok) return null;
  const json = (await res.json()) as { preview?: string };
  return json.preview ?? null;
}

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const path = searchParams.get("path");
  const kind = searchParams.get("kind") === "poster" ? "poster" : "video";

  if (!path || !path.startsWith("/")) {
    return NextResponse.json(
      { error: "path is required and must start with /" },
      { status: 400 },
    );
  }

  const wantsJson = searchParams.get("resolve") === "1";
  const key = `${kind}:${path}`;
  const answer = (url: string) =>
    wantsJson
      ? NextResponse.json({ url }, { headers: { "cache-control": "no-store" } })
      : NextResponse.redirect(url, { status: 302, headers: { "referrer-policy": "no-referrer" } });

  const hit = cache.get(key);
  if (hit && hit.expires > Date.now()) return answer(hit.url);

  try {
    const url = kind === "poster" ? await resolvePoster(path) : await resolveVideo(path);
    if (!url) {
      return NextResponse.json({ error: "not found in the archive" }, { status: 404 });
    }
    cache.set(key, { url, expires: Date.now() + CACHE_TTL_MS });
    return answer(url);
  } catch {
    return NextResponse.json({ error: "archive unreachable" }, { status: 502 });
  }
}
