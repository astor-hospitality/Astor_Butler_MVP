"use client";

import { useRef } from "react";
import type { SiteContent } from "@content/site";
import { gsap, motionAllowed, onReady, ScrollTrigger, useGSAP } from "@/lib/motion";

/** Letters a second: unhurried, so the sentences can be read as they come. */
const SPEED = 21;
/** The pause after a sentence, in seconds. */
const BREATH = 0.45;

/**
 * Who we are and what we do, under the first screen: the light stage, and on it nothing but words. It stood on black,
 * in white words, until Sergey took black off the site (2026-10-26: «черные фоны табу»). Light, because the screens take
 * turns: the film lies on red, the works under these words are on red again. The sentences type themselves out, letter by letter behind a thin caret, once the screen comes into
 * view (the way the phrase on bbx.media's first screen does; Roma's reference). Nothing depends on the scroll:
 * the visitor may read or move on.
 * Every letter is in the page from the start, hidden, so the lines keep their places and a word never breaks in the
 * middle. Without motion the sentences are simply there. The sentences are in content/site.ts.
 */
export function About({ t }: { t: SiteContent["about"] }) {
  const root = useRef<HTMLElement>(null);

  useGSAP(
    (_, contextSafe) => {
      const el = root.current;
      if (!el || !contextSafe || !motionAllowed()) return;
      const sentences = Array.from(el.querySelectorAll<HTMLElement>(".sentence")).map((sentence) => Array.from(sentence.querySelectorAll<HTMLElement>("[data-char]")));

      const type = contextSafe(() => {
        el.dataset.typing = "on";
        let caret: HTMLElement | undefined;
        const put = (letter: HTMLElement) => {
          letter.classList.add("is-on");
          caret?.classList.remove("is-last");
          letter.classList.add("is-last");
          caret = letter;
        };
        const line = gsap.timeline({ onComplete: () => void (el.dataset.typing = "done") });
        sentences.forEach((letters) => {
          const at = { n: 0 };
          let shown = 0;
          line.to(at, {
            n: letters.length,
            duration: letters.length / SPEED,
            ease: "none",
            onUpdate: () => {
              const upto = Math.min(letters.length, Math.floor(at.n));
              while (shown < upto) put(letters[shown++]);
            },
            onComplete: () => {
              while (shown < letters.length) put(letters[shown++]);
            },
          });
          line.to({}, { duration: BREATH });
        });
      });
      const run = contextSafe(() => {
        ScrollTrigger.create({ trigger: el, start: "top 55%", once: true, onEnter: type });
      });
      return onReady(run);
    },
    { scope: root },
  );

  // what a screen reader says: the same sentences, whole
  const said = t.sentences.join(" ");

  return (
    <section ref={root} id="manifesto" aria-label={t.label} data-poster="paper" className="about poster theme-paper relative wrap">
      <div className="about-stage">
        <div>
          <p className="caps opacity-60">{t.label}</p>
          <p className="about-text mt-6" aria-label={said}>
            {t.sentences.map((sentence, i) => (
              <span key={i} className="sentence" aria-hidden="true">
                {sentence.split(" ").map((word, j, words) => (
                  <span key={j}>
                    {/* a word never breaks in the middle; on a phone a word with a hyphen may break after the hyphen */}
                    <span className="type-whole">
                      {word.split(/(?<=-)/).map((part, p) => (
                        <span key={p} className="type-word">
                          {[...part].map((letter, k) => (
                            <span key={k} data-char>
                              {letter}
                            </span>
                          ))}
                        </span>
                      ))}
                    </span>
                    {j < words.length - 1 ? " " : null}
                  </span>
                ))}
              </span>
            ))}
          </p>
        </div>
      </div>
    </section>
  );
}
