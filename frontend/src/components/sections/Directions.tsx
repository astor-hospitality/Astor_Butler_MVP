import Link from "next/link";
import type { CSSProperties } from "react";
import { catalog } from "@content/catalog";
import type { Direction, SiteContent, WorksTexts } from "@content/site";
import { inkWidth } from "@/lib/travels";
import { Reveal } from "../Reveal";
import { Screen } from "../Screen";
import { Visual } from "../Visual";
import { toRail } from "./Works";
import { WorksRail } from "./WorksRail";

/**
 * Services and prices: the seven directions, one screen after another, from the first to the seventh, all on the
 * light stage. The row opens with its plain name, "Услуги и цены": red, small, at the right margin, close over the
 * hairline of the first direction (Roma's drawing, 2026-10-11). A hairline parts each direction from the one before.
 */
export function Directions({ t }: { t: SiteContent }) {
  const items = t.directions.items;
  const title = t.services.label;

  return (
    <>
      <Screen theme="paper" id="products" label={title} className="wrap pt-[clamp(2.5rem,10vh,6rem)]">
        <Reveal kind="clip">
          <h2 className="svc-head">{title}</h2>
        </Reveal>
      </Screen>

      {items.map((item, i) => (
        <Poster key={item.key} item={item} first={i === 0} more={t.services.more} missing={t.missing} works={t.works} />
      ))}
    </>
  );
}

/**
 * One direction as one composition the eye reads in order (Sergey, 2026-10-26: the text «разбежался по углам…
 * глаза разбегаются»): its cover; next to it, gathered in one block, the name, one line about it, who it is for and
 * where the prices start (the way to its page, underlined in red); then, under both, the row of its works, the same
 * tiles as on the works screen, playing by themselves («чтобы была обложка, а потом появлялись работы»).
 * The name is as large as its column lets it be, counted from its own longest line.
 */
function Poster({ item, first, more, missing, works }: { item: Direction; first: boolean; more: string; missing: string; works: WorksTexts }) {
  const widest = Math.max(...item.lines.map((line) => inkWidth(line.toUpperCase())));
  // the name fills the width next to the cover (see `.dir-name`), so it is sized from this share of its longest line
  const share = (1000 / (widest + 30)).toFixed(4);
  const set = { "--share": share } as CSSProperties;
  const rail = catalog[item.key].map((work) => toRail(work, item));
  const top = first ? "pt-[clamp(0.75rem,2vh,1.25rem)]" : "pt-[clamp(2.5rem,8vh,5rem)]";

  return (
    // no id here: Clio's notes about a direction look for it and belong to the direction's own page, not to this row
    <Screen theme="paper" label={item.title} className={`wrap overflow-x-clip pb-[clamp(3.5rem,12vh,7rem)] ${top}`}>
      <Reveal as="p" delay={0} className="dir-rule caps pt-4 text-red tabular-nums">
        {item.num}
      </Reveal>

      <div className="dir-set mt-[clamp(1.25rem,4vh,2.5rem)]" style={set}>
        <Link href={`/${item.key}`} tabIndex={-1} aria-hidden="true" data-cursor={more} className="dir-pic">
          <Visual visual={item.cover} missingLabel={missing} ratio="4/5" sizes="(min-width: 768px) 30vw, 80vw" />
        </Link>

        <div className="dir-text">
          <Reveal kind="clip">
            <h3 className="mass dir-name">
              {item.lines.map((line) => (
                <span key={line} className="block">
                  {line}
                </span>
              ))}
            </h3>
          </Reveal>
          <Reveal as="p" className="dir-line">
            {item.caption}
          </Reveal>
          <Reveal as="p" className="caps dir-who">
            {item.audience}
          </Reveal>
          <Reveal as="p" delay={0.45} className="dir-price">
            <Link href={`/${item.key}`} aria-label={`${item.title}: ${item.price}. ${more}`} className="dir-more">
              {item.price}
            </Link>
          </Reveal>
        </div>
      </div>

      <WorksRail items={rail} label={item.title} note="what" speed={26} t={works} className="mt-[clamp(2rem,6vh,3.5rem)]" />
    </Screen>
  );
}
