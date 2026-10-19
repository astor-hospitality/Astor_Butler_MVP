import type { NextConfig } from "next";
import { PHASE_PRODUCTION_BUILD } from "next/constants";
import { mediaManifest } from "./content/media-manifest";

/**
 * The media (photos, video, fonts) is not in the repository: it lives in S3 under NEXT_PUBLIC_MEDIA_BASE_URL
 * (see src/lib/media.ts and DEPLOY_C3_SITE.md). Like every NEXT_PUBLIC_* value it is fixed at build time.
 */
const mediaBase = (process.env.NEXT_PUBLIC_MEDIA_BASE_URL ?? "").trim().replace(/\/+$/, "");
const mediaOrigin = mediaBase ? new URL(mediaBase) : null;

/** next/image may fetch and resize pictures from the media host only, under the media path. */
type RemotePatterns = NonNullable<NonNullable<NextConfig["images"]>["remotePatterns"]>;
const remotePatterns: RemotePatterns = mediaOrigin
  ? [
      {
        protocol: mediaOrigin.protocol.replace(":", "") as "http" | "https",
        hostname: mediaOrigin.hostname,
        port: mediaOrigin.port,
        pathname: `${mediaOrigin.pathname.replace(/\/+$/, "")}/**`,
      },
    ]
  : [];

/** Only for a check on one machine, where a local server stands in for S3: Next refuses local addresses otherwise. */
const mediaIsLocal = mediaOrigin !== null && ["localhost", "127.0.0.1", "[::1]"].includes(mediaOrigin.hostname);

/** An old public file of the previous site, now in the media: its address leads to the new file. */
const toMedia = (path: keyof typeof mediaManifest) => (mediaBase ? `${mediaBase}/${path}?v=${mediaManifest[path].v}` : `/media/${path}`);

const nextConfig: NextConfig = {
  // the Docker image runs .next/standalone/server.js (see Dockerfile)
  output: "standalone",
  images: {
    formats: ["image/avif", "image/webp"],
    remotePatterns,
    ...(mediaIsLocal ? { dangerouslyAllowLocalIP: true } : {}),
  },
  devIndicators: false,
  async redirects() {
    return [
      // The site is in Russian only. Addresses from the time it had two languages still lead somewhere.
      ...["ru", "en"].flatMap((lang) => [
        { source: `/${lang}`, destination: "/", permanent: false },
        { source: `/${lang}/:path*`, destination: "/:path*", permanent: false },
      ]),
      // The old site had an eighth direction; this one does not. Its address leads home for good.
      { source: "/smart-solutions", destination: "/", permanent: true },
      // Old names of the directions on c3ag.ru.
      { source: "/films", destination: "/film", permanent: true },
      { source: "/podcasts", destination: "/podcast", permanent: true },
      { source: "/advertising", destination: "/reclama", permanent: true },
      { source: "/reportage", destination: "/events", permanent: true },
      // The clients' room («Приёмка») and the sign-in page of the old site have no page here yet.
      { source: "/studio", destination: "/", permanent: false },
      { source: "/studio/:path*", destination: "/", permanent: false },
      { source: "/auth/callback", destination: "/", permanent: false },
      // Public files of the old site that other places link to (link previews, the k6 smoke check): the nearest new file.
      { source: "/favicon.ico", destination: toMedia("images/meta/favicon.ico"), permanent: false },
      { source: "/og-image.png", destination: toMedia("images/meta/og.jpg"), permanent: false },
      { source: "/clio-avatar.jpg", destination: toMedia("images/refs/clio.png"), permanent: false },
      { source: "/team/sergey-izyrow-portrait.jpeg", destination: toMedia("images/refs/team-sergey.jpg"), permanent: false },
      { source: "/team/michael-welly-portrait.jpeg", destination: toMedia("images/refs/team-michael.jpg"), permanent: false },
      ...Object.entries<keyof typeof mediaManifest>({
        ai: "images/refs/droid.jpg",
        events: "images/refs/parrot.jpg",
        reels: "images/refs/burger.jpg",
        podcast: "images/work/dir-podcast.jpg",
        reclama: "images/refs/glasses.jpg",
        film: "images/refs/sheep.jpg",
        wedding: "images/work/dir-wedding.jpg",
      }).map(([direction, path]) => ({ source: `/product-covers/${direction}.jpg`, destination: toMedia(path), permanent: false })),
    ];
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default function config(phase: string): NextConfig {
  if (phase === PHASE_PRODUCTION_BUILD && !mediaOrigin) {
    console.warn(
      "\n⚠ NEXT_PUBLIC_MEDIA_BASE_URL is empty: the build expects the media in public/media (local work only). " +
        "For a server build set it to the S3 address, see DEPLOY_C3_SITE.md.\n",
    );
  }
  return nextConfig;
}
