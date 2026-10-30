import { ARCHIVE_TOTAL, catalog, clips, type Work } from "@content/catalog";
import type { SiteContent } from "@content/site";
import { workMedia } from "@content/work-media";
import { mediaUrl } from "@/lib/media";
import { streamUrl } from "@/lib/video";
import { Reveal } from "../Reveal";
import { Screen } from "../Screen";
import { WorksRail, type RailItem } from "./WorksRail";

/** A group of the rail: a direction, or the music videos, which are not one. */
interface Group {
  key: string;
  title: string;
}

/** The address of a work's short preview, which lives with the rest of the media (S3, see src/lib/media.ts). */
const clipUrl = (clip: string | undefined) => (clip ? mediaUrl(clip) : undefined);

/** A work as a card shows it. The storyboard knows it by its direction and name. */
export const toRail = (work: Work, of: Group): RailItem => ({
  id: `${of.key}: ${work.title}`,
  title: work.title,
  group: of.key,
  groupTitle: of.title,
  frame: work.frame,
  slug: work.slug,
  clip: clipUrl(workMedia[work.file]?.clip),
  watch: workMedia[work.file]?.source ? work.file : undefined,
  drive: workMedia[work.file]?.drive,
  hls: streamUrl(workMedia[work.file]?.hls),
});

/** The groups of the rail: the seven directions, then the music videos. */
const railGroups = (t: SiteContent): (Group & { works: Work[] })[] => [
  ...t.directions.items.map((item) => ({ key: item.key, title: item.title, works: catalog[item.key] })),
  { key: "clips", title: t.works.clips, works: clips },
];

/**
 * Every work of the site. The groups take turns (one work from each, then the second from each, and so on),
 * so the rail shows all the studio does and not six films of one kind in a row. A group with no works yet is left out.
 */
export function allWorks(t: SiteContent): RailItem[] {
  const groups = railGroups(t).filter((group) => group.works.length > 0);
  const longest = Math.max(...groups.map((group) => group.works.length));
  return Array.from({ length: longest }).flatMap((_, i) => groups.flatMap((group) => (group.works[i] ? [toRail(group.works[i], group)] : [])));
}

/**
 * The works, on the red screen (Sergey, 2026-10-26: «работы ролики на красном фоне», as the site had them before):
 * the word on the left margin and the row of wide tiles under it, every one playing its short cut, the row drifting
 * to the left by itself. All the directions are mixed in it (see `allWorks`), each tile says which one it is from.
 * No filters: «переключение между блоками тоже не нравится… мелко, не информативно».
 */
export function Works({ t }: { t: SiteContent }) {
  const items = allWorks(t);

  return (
    <Screen theme="red" id="catalog" label={t.works.title} className="wrap overflow-x-clip py-[clamp(4rem,14vh,8rem)]">
      <Reveal kind="clip">
        <h2 className="mass works-head">{t.works.title}</h2>
      </Reveal>
      <WorksRail items={items} label={t.works.title} archive={Math.max(0, ARCHIVE_TOTAL - items.length)} boardLine t={t.works} className="mt-[clamp(1.75rem,5vh,3rem)]" />
    </Screen>
  );
}
