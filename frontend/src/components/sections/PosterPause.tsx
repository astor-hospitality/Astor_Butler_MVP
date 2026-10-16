import type { CSSProperties } from "react";
import type { Part, SiteContent } from "@content/site";
import type { Picture } from "@content/types";
import { inkWidth } from "@/lib/travels";
import { Line, Lines, Words } from "../Lines";
import { RevealImage } from "../RevealImage";
import { Screen } from "../Screen";

/** The size at which the longest of the lines reaches from margin to margin, as a share of the grid's width. */
const fit = (lines: Part[][]) => {
  const widest = Math.max(...lines.map((parts) => inkWidth(parts.map((part) => part.text).join("").toUpperCase())));
  return `calc(var(--w) * ${(1000 / (widest + 30)).toFixed(4)})`;
};

/**
 * The studio's poster with the lollipop, between the directions and the works, on the light stage.
 * Its line is set as massive words, and the lollipop stands in front of them on the right, next to the last word,
 * "это": the picture in front, the type behind. (The first version, the lollipop alone inside a thin frame on red,
 * looked strange: red on red, and a lot of nothing around it.) On a phone the words are broken into shorter lines
 * and the lollipop stands under them.
 */
export function PosterPause({ picture, t }: { picture: Picture; t: SiteContent["poster"] }) {
  const said = t.wide.map((parts) => parts.map((part) => part.text).join("")).join(" ");
  const sizes = { "--fs-wide": fit(t.wide), "--fs-narrow": fit(t.narrow) } as CSSProperties;

  return (
    <Screen theme="paper" label={said} className="wrap pb-[var(--air)] pt-[clamp(2rem,8vh,5rem)]">
      <div className="lolly-set" style={sizes}>
        {/* the same line twice, broken for a wide screen and for a phone; only one of the two is ever shown */}
        <div className="lolly-words">
          <Lines as="h2" className="mass text-[1em] !leading-[0.95] max-md:hidden" stagger={0.09}>
            {t.wide.map((parts, i) => (
              <Line key={i}>
                <Words parts={parts} />
              </Line>
            ))}
          </Lines>
          <Lines as="h2" className="mass text-[1em] !leading-[0.95] md:hidden" stagger={0.09}>
            {t.narrow.map((parts, i) => (
              <Line key={i}>
                <Words parts={parts} />
              </Line>
            ))}
          </Lines>
        </div>
        <RevealImage src={picture.src} width={picture.width} height={picture.height} alt={picture.alt} sizes="(min-width: 768px) 30vw, 62vw" className="lolly-pic" />
      </div>
    </Screen>
  );
}
