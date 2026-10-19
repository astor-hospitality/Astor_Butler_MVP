import type { SiteContent } from "@content/site";
import type { Picture } from "@content/types";
import { Reveal } from "../Reveal";
import { Screen } from "../Screen";
import { Visual } from "../Visual";

interface FailsProps {
  t: SiteContent["fails"];
  /** The studio's poster for it: the dropped ice cream whose splash is the mark. */
  picture: Picture;
  missing: string;
}

/**
 * Mishaps on set, after the studio and before the last word (Sergey, 2026-10-14: «прикольно рассказать про факапы
 * на съёмках»). It is set the way the studio's own poster is: the picture in the middle of the light stage, and under it
 * two lines of small capitals, one at each edge of the picture: what the block is, and the poster's line
 * «Ни один дубль не застрахован». The stories, when the studio writes them, stand under the poster, numbered;
 * until then the poster is the whole block.
 */
export function Fails({ t, picture, missing }: FailsProps) {
  return (
    <Screen theme="paper" id="fails" label={t.label} className="wrap air">
      <figure className="mx-auto w-full md:w-[46%]">
        <Visual visual={picture} missingLabel={missing} ratio="4/5" sizes="(min-width: 768px) 40vw, 86vw" />
        <Reveal as="div" className="mt-5 flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
          <h2 className="caps">{t.label}</h2>
          {/* stays at the right edge also when the two lines no longer fit side by side and it goes under the first */}
          <figcaption className="caps ml-auto text-right">{t.line}</figcaption>
        </Reveal>
      </figure>

      {t.stories.length ? (
        <ol className="grid-12 mt-[clamp(3rem,9vh,5.5rem)] gap-y-10">
          {t.stories.map((story, i) => (
            <li key={story.title} className="col-span-12 md:col-span-6 md:odd:col-start-1 md:even:col-start-7">
              <Reveal>
                <p className="caps text-red tabular-nums">{String(i + 1).padStart(2, "0")}</p>
                <h3 className="display-xs mt-2">{story.title}</h3>
                <p className="copy mt-3 opacity-80">{story.text}</p>
              </Reveal>
            </li>
          ))}
        </ol>
      ) : null}
    </Screen>
  );
}
