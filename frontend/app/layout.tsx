import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
// Loaded second on purpose: the product sheet overrides a handful of globals
// (mobile menu layout, footer) and relies on source order to win.
import "./products.css";

// One family for the whole site — headings differ by weight and tracking,
// not by typeface. See the "Minimal skin" block in globals.css.
const inter = Inter({
  subsets: ["cyrillic", "latin"],
  weight: ["300", "400", "500", "600"],
  variable: "--font-body",
  display: "swap",
});

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? "https://c3ag.ru";
const SITE_TITLE = "C3AG.ru — видеопродакшн полного цикла";
const SITE_DESCRIPTION =
  "Восемь направлений C3AG.ru: Reels, репортаж, Wedding, Podcast, реклама, Film, AI и Smart Solutions. Фиксированные продукты и сметы под проект.";

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: SITE_TITLE,
  description: SITE_DESCRIPTION,
  // The portfolio is served straight off Yandex.Disk, and its download host
  // answers 403 to any request that names another site in Referer. From a
  // visitor's browser that is every archive clip and every poster: they play
  // on localhost, which Yandex lets through, and on no real domain.
  // "same-origin" keeps the referrer for our own pages and API and sends none
  // to other hosts. Embeds are not affected: they set their own policy on the
  // <iframe> (see ProductPage).
  referrer: "same-origin",
  keywords: [
    "видеопродакшн",
    "видеосъёмка",
    "съёмка reels",
    "репортаж с мероприятия",
    "видеоподкаст",
    "свадебный фильм",
    "рекламный ролик",
    "AI видео",
    "C3AG.ru",
    "C3 Agency",
  ],
  openGraph: {
    type: "website",
    locale: "ru_RU",
    url: SITE_URL,
    siteName: "C3AG.ru",
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
    images: [
      {
        url: "/og-image.png",
        width: 1200,
        height: 630,
        alt: "C3AG.ru — видео-продакшн с характером",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
    images: ["/og-image.png"],
  },
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ru" className={inter.variable} suppressHydrationWarning>
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: `try{var t=localStorage.getItem('c3ag-theme');if(t!=='light'&&t!=='dark')t=matchMedia('(prefers-color-scheme: light)').matches?'light':'dark';document.documentElement.dataset.theme=t;}catch(e){document.documentElement.dataset.theme='dark';}`,
          }}
        />
      </head>
      <body>{children}</body>
    </html>
  );
}
