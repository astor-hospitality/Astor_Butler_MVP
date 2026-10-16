import type { Metadata } from "next";
import Link from "next/link";
import { site } from "@content/site";
import { Frame } from "@/components/Frame";

export const metadata: Metadata = { title: "Страница не найдена" };

/** A page that is not there: the frame poster again, with the way home. */
export default function NotFound() {
  const t = site.notFound;
  return (
    <section data-poster="paper" className="theme-paper field wrap flex min-h-svh flex-col items-center justify-center py-28">
      <Frame className="w-[min(100%,420px)]">
        <span className="mark w-14 text-red" aria-hidden="true" />
        <h1 className="caps">
          404
          <br />
          {t.text}
        </h1>
        <Link href="/" className="caps py-3 underline underline-offset-4">
          {t.home}
        </Link>
      </Frame>
    </section>
  );
}
