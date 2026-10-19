import Link from "next/link";
import { policyHref, type SiteContent } from "@content/site";
import type { Theme } from "@content/types";
import { Reveal } from "../Reveal";
import { Screen } from "../Screen";

/**
 * The footer: the mark, what the studio is, the seven directions and three links.
 * It stands on the colour of the screen above it (red after the last word of the home page): a few last lines
 * are not a screen of their own, and the page should not change colour for them.
 */
export function Footer({ t, theme = "red" }: { t: SiteContent; theme?: Theme }) {
  return (
    <Screen theme={theme} as="footer" className="wrap pb-8 pt-[clamp(1rem,4vh,2.5rem)]">
      <Link href="/#top" aria-label={t.nav.home} className="inline-block py-2">
        <span className="mark w-[4.5rem]" />
      </Link>
      <Reveal as="div" delay={0} className="grid-12 mt-8 gap-y-8">
        <p className="caps col-span-12 opacity-80 md:col-span-4">{t.footer.line}</p>
        <nav aria-label={t.directions.label} className="col-span-6 md:col-span-3 md:col-start-7">
          <ul className="leading-8">
            {t.directions.items.map((item) => (
              <li key={item.key}>
                <Link href={`/${item.key}`} className="underline-offset-4 hover:underline">
                  {item.title}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
        <ul className="col-span-6 leading-8 md:col-span-3">
          {t.footer.links.map((link) => (
            <li key={link.label}>
              <Link href={link.href} className="underline-offset-4 hover:underline" {...(link.href.startsWith("http") ? { target: "_blank", rel: "noreferrer" } : {})}>
                {link.label}
              </Link>
            </li>
          ))}
        </ul>
      </Reveal>
      {/* the right end of this line keeps clear of Clio's star in the corner where the margin is narrower than the star (phones, tablets) */}
      <p
        className="caps mt-12 flex flex-wrap justify-between gap-x-8 gap-y-1 pt-4 opacity-80"
        style={{ borderTop: "1px solid color-mix(in srgb, var(--fg) 34%, transparent)", paddingRight: "max(0px, calc(clamp(1rem, 3vw, 1.5rem) + 4.5rem - var(--m)))" }}
      >
        <span>{t.footer.legal}</span>
        {policyHref ? (
          <a href={policyHref} target="_blank" rel="noreferrer" className="underline-offset-4 hover:underline">
            {t.footer.policy}
          </a>
        ) : null}
      </p>
    </Screen>
  );
}
