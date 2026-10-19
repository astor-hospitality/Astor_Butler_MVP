import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { preload } from "react-dom";
import { logoSpin, media } from "@content/media";
import { site, siteUrl } from "@content/site";
import { Clio } from "@/components/assistant/Clio";
import { Nav } from "@/components/Nav";
import { ShootChip } from "@/components/ShootChip";
import { Cursor } from "@/components/motion/Cursor";
import { Preloader } from "@/components/motion/Preloader";
import { SmoothScroll } from "@/components/motion/SmoothScroll";
import { mediaImage, mediaUrl } from "@/lib/media";
import { fontFaces, fontFiles } from "./fonts";
import "./globals.css";

/** The picture of a shared link: the mark on red (from the media, like every picture). */
const og = mediaImage("images/meta/og.jpg");
const shareImage = { url: og.src, width: og.width, height: og.height, alt: site.meta.title };

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: { default: site.meta.title, template: `%s · C3` },
  description: site.meta.description,
  // a link sent in a messenger comes with the mark on red
  openGraph: { type: "website", locale: "ru_RU", siteName: "C3", title: site.meta.title, description: site.meta.description, images: [shareImage] },
  twitter: { card: "summary_large_image", images: [shareImage] },
  icons: {
    icon: [
      { url: mediaUrl("images/meta/favicon.ico"), sizes: "32x32" },
      { url: "/icon.svg", type: "image/svg+xml" },
    ],
    apple: mediaUrl("images/meta/apple-icon.png"),
  },
};

export const viewport: Viewport = {
  themeColor: "#b80314",
};

/**
 * Runs before the first paint. If the visitor has not asked for reduced motion it switches motion on
 * (and, where the alpha of a WebM can be shown, marks the page `spin` and starts loading the turning mark at once, in a
 * video element the preloader takes over: it would otherwise start only after the scripts, and the red screen stood empty):
 * from then on the reveals start hidden and the red screen with the mark shows. If scripts never arrive, it switches motion back off
 * after a few seconds, so nothing stays hidden. With reduced motion the page is simply ready at once.
 */
const beforePaint = `(function(){var d=document.documentElement;if(window.matchMedia("(prefers-reduced-motion: reduce)").matches){d.classList.add("ready");return}d.classList.add("motion");try{var u=navigator.userAgent,a=/iPhone|iPad|iPod/.test(u)||(u.indexOf("Safari/")>-1&&!/Chrome|Chromium|CriOS|Edg|OPR|Firefox|FxiOS/.test(u));var v=document.createElement("video");if(!a&&v.canPlayType('video/webm; codecs="vp9"')){d.classList.add("spin");v.muted=true;v.loop=true;v.playsInline=true;v.preload="auto";v.className="preloader-spin";v.setAttribute("aria-hidden","true");v.src="${logoSpin}";window.__c3spin=v}}catch(e){}setTimeout(function(){if(!d.classList.contains("hydrated")){d.classList.remove("motion");d.classList.add("ready")}},6000)})()`;

const noScript = `.swap{display:block!important}.swap>.face{visibility:visible!important}.swap>.face+.face{margin-top:5rem}.face .piece{clip-path:none!important}.face .note{opacity:1!important;transform:none!important}.clio-dock{display:none!important}`;

/** The site is in Russian only. */
export default function RootLayout({ children }: { children: ReactNode }) {
  const t = site;
  // the face is asked for at once, as next/font did; the files come from the media host, hence crossOrigin
  for (const href of fontFiles) preload(href, { as: "font", type: "font/woff", crossOrigin: "anonymous" });

  return (
    <html lang="ru" suppressHydrationWarning>
      <head>
        <style dangerouslySetInnerHTML={{ __html: fontFaces }} />
      </head>
      <body>
        <script dangerouslySetInnerHTML={{ __html: beforePaint }} />
        {/* Without scripts nothing can be clicked open, so what hides behind a click is simply laid out. */}
        <noscript>
          <style>{noScript}</style>
        </noscript>
        <a href="#content" className="caps sr-only z-[80] bg-paper p-4 text-ink focus:not-sr-only focus:fixed focus:left-4 focus:top-4">
          {t.nav.skip}
        </a>
        {/* the vignette and the grain of the dark stage, behind everything (see `.ink-fx`) */}
        <div className="ink-fx" aria-hidden="true" />
        <Preloader mark={media.hero} spin={logoSpin} />
        <Cursor />
        <Nav t={t.nav} />
        <main id="content">{children}</main>
        <ShootChip />
        <Clio t={t.clio} links={t.final.links} avatar={{ red: media.clio.src, plain: media.clioPlain.src }} />
        <SmoothScroll />
      </body>
    </html>
  );
}
