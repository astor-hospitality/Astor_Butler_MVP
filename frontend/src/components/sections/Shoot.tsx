"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { frames, packagesFor, shootNeeds, shootTexts as t } from "@content/shoot";
import { toggleOnBoard, useBoard } from "@/lib/board";
import { SEND_EVENT } from "@/lib/clio";
import { setShoot, useShoot } from "@/lib/shoot";
import { FadeImage } from "../FadeImage";
import { PosterSection } from "../PosterSection";
import { Reveal } from "../Reveal";
import type { RailItem } from "./WorksRail";

/** A row that can be chosen: plain words, and a red plate under them when it is the one. */
const row = (on: boolean) =>
  `flex w-full items-baseline justify-between gap-4 px-3 py-3 text-left transition-colors duration-300 focus-visible:-outline-offset-2 ${
    on ? "bg-[color:var(--line)] text-[color:var(--on-line)]" : "hover:bg-[color:var(--line)] hover:text-[color:var(--on-line)]"
  }`;

/** Not a whole window with the frame in its middle: it follows the lollipop poster after the page's usual air, and
 *  no more (Sergey, 2026-10-26: «огромные пустоты между блоками», the space before this block first of all). */
const tight = "!min-h-0 !pt-0 !pb-[var(--air)]";

/**
 * "Соберите съёмку за три шага": the visitor says what is to be shot, picks a package and gathers references,
 * and the result goes to Clio as one message.
 * It is one thin red frame on the light stage (it stood on black until Roma took black off the home page, 2026-10-11),
 * the frame of the site's posters, ruled into three columns, one for each step,
 * with the total along its lower edge: no cards, no ornaments, only type and lines (the first version, with clapper
 * stripes and a white card, was not liked). On a phone the columns stand one under another inside the same frame.
 * Everything it offers comes from content/shoot.ts; the choice is kept for as long as the tab lives.
 * `works` are all the works of the site: the storyboard keeps only their names, the pictures are found here.
 */
export function Shoot({ works }: { works: RailItem[] }) {
  const choice = useShoot();
  const marked = useBoard();
  const need = shootNeeds.find((item) => item.id === choice.need) ?? null;
  const packs = need ? packagesFor(need) : [];
  const pack = packs.find((item) => item.name === choice.pack) ?? null;
  const refs = marked.flatMap((id) => works.find((work) => work.id === id) ?? []);

  const send = () => {
    if (!need || !pack) return;
    const said = [
      t.message,
      `${t.total.need}: ${need.label}.`,
      `${t.total.pack}: ${pack.name}, ${pack.price}.`,
      refs.length > 0 ? `${t.total.refs} (${refs.length}): ${refs.map((work) => work.title).join("; ")}.` : `${t.total.refs}: нет.`,
    ];
    window.dispatchEvent(new CustomEvent<string>(SEND_EVENT, { detail: said.join(" ") }));
    setShoot({ sent: true });
  };

  if (choice.sent) {
    return (
      <PosterSection theme="paper" id="shoot" label={t.title} className={`${tight} items-center`}>
        <div className="hairline flex w-full max-w-[52rem] flex-col items-center px-[8%] py-[clamp(3rem,12vh,7rem)] text-center">
          <span className="mark w-14 text-[color:var(--line)]" aria-hidden="true" />
          <h2 className="hero-type mt-6">{t.done.word}</h2>
          <p className="copy mt-5">{t.done.text}</p>
          <button type="button" onClick={() => setShoot({ need: null, pack: null, sent: false })} className="caps mt-8 py-3 underline underline-offset-4">
            {t.done.again}
          </button>
        </div>
      </PosterSection>
    );
  }

  // What is chosen so far, as one line: the parts that are there, with strokes between them, like the links of the header.
  const chosen = [need?.label, pack?.name, pack?.price, refs.length > 0 ? frames(refs.length) : undefined].filter((part): part is string => Boolean(part));

  return (
    <PosterSection theme="paper" id="shoot" label={t.title} className={tight}>
      <Reveal>
        <h2 className="display max-w-[16ch]">{t.title}</h2>
      </Reveal>

      <Reveal kind="clip" className="hairline mt-[clamp(2rem,6vh,3.5rem)]">
        <ol className="grid lg:grid-cols-[1fr_1.2fr_1fr]">
          <Step n={1} name={t.steps.need.name}>
            <ul>
              {shootNeeds.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    aria-pressed={item.id === choice.need}
                    onClick={() => setShoot({ need: item.id, pack: packagesFor(item).length === 1 ? packagesFor(item)[0].name : null })}
                    className={`caps ${row(item.id === choice.need)}`}
                  >
                    {item.label}
                  </button>
                </li>
              ))}
            </ul>
          </Step>

          <Step n={2} name={t.steps.pack.name}>
            {need ? (
              <ul>
                {packs.map((item) => (
                  <li key={item.name}>
                    <button type="button" aria-pressed={item.name === choice.pack} onClick={() => setShoot({ pack: item.name })} className={row(item.name === choice.pack)}>
                      <span>
                        <span className="caps block">{item.name}</span>
                        {item.note ? <span className="caps mt-1 block opacity-80">{item.note}</span> : null}
                      </span>
                      <span className="caps shrink-0 whitespace-nowrap tabular-nums">{item.price}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="copy px-3 py-3 opacity-80">{t.steps.pack.wait}</p>
            )}
          </Step>

          <Step n={3} name={t.steps.refs.name}>
            {refs.length > 0 ? (
              <ul className="grid grid-cols-3 gap-2 px-3 pt-3">
                {refs.map((work) => (
                  <li key={work.id}>
                    <div className="relative aspect-square">
                      <FadeImage src={work.frame.src} alt={work.title} fill sizes="120px" className="object-cover" />
                      <button
                        type="button"
                        aria-label={`${t.steps.refs.remove}: ${work.title}`}
                        onClick={() => toggleOnBoard(work.id)}
                        className="absolute right-0 top-0 grid size-8 place-items-center bg-white text-red focus-visible:-outline-offset-4"
                      >
                        <svg viewBox="0 0 16 16" className="size-3" fill="none" stroke="currentColor" strokeWidth="1.75" aria-hidden="true">
                          <path d="M2 2l12 12M14 2L2 14" />
                        </svg>
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="copy px-3 py-3 opacity-80">{t.steps.refs.empty}</p>
            )}
            <p className="caps px-3 py-3">
              <Link href="/#catalog" className="py-2 underline underline-offset-4">
                {t.steps.refs.toWorks}
              </Link>
            </p>
          </Step>
        </ol>

        <div className="flex flex-col gap-5 border-t-[1.5px] border-[color:var(--line)] p-[clamp(1.25rem,2.4vw,2rem)] lg:flex-row lg:items-center lg:justify-between">
          <div>
            <p className="caps">{t.total.name}</p>
            <p className="display-xs mt-2" aria-live="polite">
              {chosen.length > 0 ? chosen.join(" / ") : t.total.none}
            </p>
          </div>
          <button
            type="button"
            disabled={!need || !pack}
            onClick={send}
            className="caps shrink-0 bg-red px-8 py-5 text-white transition-opacity duration-300 hover:opacity-85 disabled:opacity-40 disabled:hover:opacity-40"
          >
            {t.total.send}
          </button>
        </div>
      </Reveal>

      <Reveal as="p" className="caps mt-4">
        {!need || !pack ? t.total.locked : t.total.note}
      </Reveal>
    </PosterSection>
  );
}

/** One step of the three, a column of the frame: its number in the count of the site ("01 / 03"), its name, the choice. */
function Step({ n, name, children }: { n: number; name: string; children: ReactNode }) {
  return (
    <li className="border-[color:var(--line)] p-[clamp(0.75rem,1.4vw,1.25rem)] max-lg:[&:not(:first-child)]:border-t-[1.5px] lg:[&:not(:first-child)]:border-l-[1.5px]">
      <p className="caps px-3 pt-3 tabular-nums">
        {String(n).padStart(2, "0")} / 03
      </p>
      <h3 className="display-xs mt-1 px-3 pb-4">{name}</h3>
      {children}
    </li>
  );
}
