import Link from "next/link";
import type { SiteContent } from "@content/site";
import { AskClio } from "./assistant/AskClio";

/**
 * The header, small and fixed: the mark on the left, three links on the centre line, the call on the right.
 * It takes the colour of the page, so it reads on red and on the light stage alike. The call, «Обсудить проект», is a
 * filled pill that opens Clio's window (Sergey, 2026-10-26: a click on it «ничего не происходит», it has to stand out
 * and start the assistant's chat): always red with white words and a thin white ring, so it stands out on every
 * stage and on a phone (Sergey, 2026-10-27; see `.nav-cta`). On the way down the page it hides, on the way up
 * it returns (see SmoothScroll). On a phone and a narrow tablet only the mark and the call stay: the page is one road, and the footer has the links.
 */
export function Nav({ t }: { t: SiteContent["nav"] }) {
  const links = [
    { href: "/#products", label: t.services },
    { href: "/#catalog", label: t.work },
    { href: "/#about", label: t.about },
  ];

  return (
    <header className="nav wrap inset-x-0 top-0 z-40 flex h-[4.5rem] items-center justify-between">
      <Link href="/#top" aria-label={t.home} className="py-3">
        <span className="mark w-[52px] sm:w-[60px]" />
      </Link>
      {/* from 864 px: on a narrower tablet the three links ran into the call on the right */}
      <nav aria-label="C3" className="caps absolute left-1/2 -translate-x-1/2 whitespace-nowrap max-[54rem]:hidden">
        <ul className="flex items-center gap-[clamp(1.25rem,3vw,2.75rem)]">
          {links.map((link) => (
            <li key={link.href}>
              <Link href={link.href} className="py-3 underline-offset-[6px] hover:underline">
                {link.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      <AskClio label={t.cta} className="nav-cta caps whitespace-nowrap" />
    </header>
  );
}
