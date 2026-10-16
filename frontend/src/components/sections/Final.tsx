"use client";

import type { SiteContent } from "@content/site";
import { ASK_EVENT } from "@/lib/clio";
import { inkWidth } from "@/lib/travels";
import { Reveal } from "../Reveal";
import { Screen } from "../Screen";

/**
 * The last screen, red, inside the thin white frame of the studio's posters. In it one massive word, plain white
 * (it was set in a silver gradient at first; Roma asked for pure white, 2026-10-11). Under the word the line of the
 * gamepad poster; then the button that opens Clio and the contacts in a column.
 * The word fills the frame whatever the word is: its size is counted from its own width.
 */
export function Final({ t }: { t: SiteContent }) {
  // the share of the frame's inner width one em may take, so the word reaches from edge to edge
  const share = (1000 / (inkWidth(t.final.word.toUpperCase()) + 40)).toFixed(4);

  return (
    <Screen theme="red" id="contact" label={t.final.label} className="wrap py-[clamp(2.5rem,8vh,4.5rem)]">
      <div className="inframe flex min-h-[min(84svh,54rem)] flex-col justify-between gap-12 p-[clamp(1.25rem,3vw,2.75rem)]">
        <Reveal as="p" delay={0} className="caps">
          {t.final.label}
        </Reveal>

        <div>
          <Reveal kind="clip">
            <h2 className="mass -ml-[0.03em] text-white" style={{ fontSize: `calc((var(--w) - 2 * clamp(1.25rem, 3vw, 2.75rem)) * ${share})` }}>
              {t.final.word}
            </h2>
          </Reveal>
          <Reveal as="p" className="caps mt-5">
            {t.final.line}
          </Reveal>
        </div>

        <div className="flex flex-col gap-8 md:flex-row md:items-end md:justify-between">
          <Reveal as="p" className="caps">
            <button type="button" onClick={() => window.dispatchEvent(new CustomEvent<string>(ASK_EVENT, { detail: "" }))} className="caps pill bg-white text-red max-md:w-full">
              {t.final.cta}
            </button>
          </Reveal>
          <Reveal as="div" className="md:min-w-[21rem]">
            <ul className="caps">
              {t.final.links.map((link) => (
                <li key={link.label} className="grid grid-cols-[6.5rem_minmax(0,1fr)] gap-4 border-t border-[color:rgb(255_255_255/0.34)] py-3 last:border-b">
                  <span className="opacity-80">{link.label}</span>
                  <a href={link.href} className="underline-offset-4 [overflow-wrap:anywhere] hover:underline" {...(link.href.startsWith("http") ? { target: "_blank", rel: "noreferrer" } : {})}>
                    {link.value}
                  </a>
                </li>
              ))}
            </ul>
          </Reveal>
        </div>
      </div>
    </Screen>
  );
}
