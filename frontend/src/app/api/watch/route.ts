import type { NextRequest } from "next/server";
import { workMedia } from "@content/work-media";

/** The studio's public portfolio folder. Only this handler knows its address; the pages never show it. */
const FOLDER = "https://disk.yandex.ru/d/oopTdDN0CTuIig";

/**
 * The full film of a work, for the player: `/api/watch?work=viking-1`.
 * The films live in the studio's Disk folder, and Yandex gives an address to a file only for a short while,
 * so the address is asked for at the moment of playing and the browser is sent on to it. The browser then reads the film
 * straight from Yandex, piece by piece. Only the works of the catalogue that live on the Disk can be asked for
 * (see content/work-media.ts); the ones on Google Drive play in Drive's own player and never come here.
 */
export async function GET(request: NextRequest) {
  const name = request.nextUrl.searchParams.get("work") ?? "";
  const media = Object.hasOwn(workMedia, name) ? workMedia[name] : undefined;
  if (!media?.source) return new Response("Not found", { status: 404 });

  const ask = new URL("https://cloud-api.yandex.net/v1/disk/public/resources/download");
  ask.searchParams.set("public_key", FOLDER);
  ask.searchParams.set("path", media.source);
  try {
    const answer = await fetch(ask, { cache: "no-store" });
    const data: { href?: unknown } = await answer.json();
    if (!answer.ok || typeof data.href !== "string") return new Response("Unavailable", { status: 502 });
    // Yandex refuses a film asked for "from" another site (it looks at the Referer). So the browser is told to follow
    // this redirect without saying where it comes from. The address is good for a short while only: it is not kept.
    return new Response(null, { status: 302, headers: { Location: data.href, "Referrer-Policy": "no-referrer", "Cache-Control": "no-store" } });
  } catch {
    return new Response("Unavailable", { status: 502 });
  }
}
