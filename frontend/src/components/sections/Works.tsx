import { ARCHIVE_TOTAL, catalog, type Work } from "@content/catalog";
import type { Direction, SiteContent } from "@content/site";
import { workMedia } from "@content/work-media";
import { mediaUrl } from "@/lib/media";
import { Reveal } from "../Reveal";
import { Screen } from "../Screen";
import { WorksRail, type RailItem } from "./WorksRail";

/** The short silent preview of a work, from the media root (S3), if one was cut. */
const clipOf = (work: Work) => {
  const clip = workMedia[work.file]?.clip;
  return clip ? mediaUrl(clip) : undefined;
};

/** A work as a card shows it. The storyboard knows it by its direction and name. */
export const toRail = (work: Work, of: Direction): RailItem => ({
  id: `${of.key}: ${work.title}`,
  title: work.title,
  group: of.key,
  groupTitle: of.title,
  frame: work.frame,
  slug: work.slug,
  clip: clipOf(work),
  watch: workMedia[work.file] ? work.file : undefined,
});

/**
 * Every work of the site. The directions take turns (one work from each, then the second from each, and so on),
 * so the rail shows all the studio does and not six films of one kind in a row. A direction with no works yet is left out.
 */
export function allWorks(t: SiteContent): RailItem[] {
  const groups = t.directions.items.filter((item) => catalog[item.key].length > 0);
  const longest = Math.max(...groups.map((item) => catalog[item.key].length));
  return Array.from({ length: longest }).flatMap((_, i) => groups.flatMap((item) => (catalog[item.key][i] ? [toRail(catalog[item.key][i], item)] : [])));
}

/**
 * The works, on the light stage: the massive word on the left margin, the directions as chips, the rail of cards.
 * All seven directions are among the chips; one that has no works yet answers with a red plate (see WorksRail).
 */
export function Works({ t }: { t: SiteContent }) {
  const items = allWorks(t);

  return (
    <Screen theme="paper" id="catalog" label={t.works.title} className="wrap overflow-x-clip pb-[var(--air)] pt-[clamp(2rem,8vh,5rem)]">
      <Reveal kind="clip">
        <h2 className="mass -ml-[0.03em]" style={{ fontSize: "calc(var(--w) * 0.178)" }}>
          {t.works.title}
        </h2>
      </Reveal>
      <WorksRail items={items} groups={t.directions.items.map((item) => ({ key: item.key, title: item.title }))} archive={Math.max(0, ARCHIVE_TOTAL - items.length)} t={t.works} />
    </Screen>
  );
}
