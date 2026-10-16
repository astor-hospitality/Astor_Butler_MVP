import Link from "next/link";
import type { CSSProperties } from "react";
import type { Direction, SiteContent } from "@content/site";
import { inkWidth } from "@/lib/travels";
import { Reveal } from "../Reveal";
import { Screen } from "../Screen";
import { Visual } from "../Visual";

/**
 * Services and prices: the seven directions, one screen after another, from the first to the seventh
 * (as the site had them before, now in the scale of the new one: Roma's word, 2026-10-11).
 * They stand right after the words about the studio, all on the light stage: the home page keeps black for those
 * words alone. The row opens with its plain name, "Услуги и цены": red, small, at the right margin, close over the
 * hairline of the first direction (Roma drew it there over a screenshot, 2026-10-11, after a full-width line and
 * a small heading on the left before it). A hairline parts each direction from the one before.
 * The parrot stands only here, with the first direction.
 */
export function Directions({ t }: { t: SiteContent }) {
  const items = t.directions.items;
  const title = t.services.label;

  return (
    <>
      {/* less air over it than over other screens: the dark screen above already ends with a pause */}
      <Screen theme="paper" id="products" label={title} className="wrap pt-[clamp(2.5rem,10vh,6rem)]">
        <Reveal kind="clip">
          <h2 className="svc-head">{title}</h2>
        </Reveal>
      </Screen>

      {items.map((item, i) => (
        <Poster key={item.key} item={item} first={i === 0} more={t.services.more} missing={t.missing} />
      ))}
    </>
  );
}

/**
 * One direction, with as little on its screen as it can do with (Roma's word, 2026-10-11: the row looked overloaded):
 * its number, its name as the massive word of the screen, its picture, one quiet line about it, and where the prices
 * start, which is also the way to its page: it is underlined in red and has no arrow (Roma drew it so on a screenshot).
 * Who it is for and everything else is on that page.
 * The picture stands in front of the name, over the lower part of its letters on the right (the picture in front,
 * the type behind). The name is as large as the width lets it be, counted from its own longest line, so a short name
 * is huge and a long one still fits.
 * On a wide screen the line stands under the name and the price is set large at the foot of the screen, level with
 * the lower edge of the picture, so the left half is held at both ends and does not look empty; on a phone the price
 * stays a whisper under the line.
 */
function Poster({ item, first, more, missing }: { item: Direction; first: boolean; more: string; missing: string }) {
  const widest = Math.max(...item.lines.map((line) => inkWidth(line.toUpperCase())));
  const share = (1000 / (widest + 30)).toFixed(4);
  const set = { fontSize: `min(calc(var(--w) * ${share}), 16.25rem)`, "--more": item.lines.length - 1 } as CSSProperties;
  // the first direction stands close under the name of the row
  const top = first ? "pt-[clamp(0.75rem,2vh,1.25rem)]" : "pt-[clamp(2.5rem,8vh,5rem)]";

  return (
    // no id here: Clio's notes about a direction look for it and belong to the direction's own page, not to this row
    <Screen theme="paper" label={item.title} className={`wrap pb-[clamp(3.5rem,13vh,7.5rem)] ${top}`}>
      <Reveal as="p" delay={0} className="dir-rule caps pt-4 text-red tabular-nums">
        {item.num}
      </Reveal>

      <div className="dir-set mt-[clamp(1rem,3vh,2rem)]" style={set}>
        <Reveal kind="clip">
          <h3 className="mass text-[1em]">
            {item.lines.map((line) => (
              <span key={line} className="block">
                {line}
              </span>
            ))}
          </h3>
        </Reveal>

        <div className="grid-12 gap-y-5">
          <Link href={`/${item.key}`} data-cursor={more} aria-label={`${item.title}. ${more}`} className="dir-pic col-span-12 md:col-span-5 md:col-start-8 md:row-start-1">
            <Visual visual={item.cover} missingLabel={missing} ratio="4/5" sizes="(min-width: 768px) 38vw, 86vw" />
          </Link>

          <div className="dir-text col-span-12 text-[length:var(--fs-t)] md:col-span-7 md:row-start-1">
            <Reveal as="p" className="dir-line">
              {item.caption}
            </Reveal>
            <Reveal as="p" delay={0.45} className="dir-price">
              <Link href={`/${item.key}`} aria-label={`${item.title}: ${item.price}. ${more}`} className="dir-more">
                {item.price}
              </Link>
            </Reveal>
          </div>
        </div>
      </div>
    </Screen>
  );
}
