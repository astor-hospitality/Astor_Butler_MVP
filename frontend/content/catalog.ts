import { mediaImage } from "@/lib/media";
import type { DirectionKey, Picture } from "./types";

/**
 * The works, direction by direction, as on the current site: a row of frames for each.
 * The frames are the previews of the videos in the studio's portfolio folder; the names are read off the file names there.
 * TODO (studio): check the names, choose the frames, and give the clips themselves, so that a frame can play.
 */
export interface Work {
  title: string;
  frame: Picture;
  /** The name of the frame's file: the work's short preview and its full film are found by it (see work-media.ts). */
  file: string;
  /** A work that has its own page (see cases.ts). */
  slug?: string;
}

const w = (file: string, name: string, title: string, slug?: string): Work => ({
  title,
  file: name,
  frame: { ...mediaImage(file), alt: `Кадр из работы «${title}»` },
  ...(slug ? { slug } : {}),
});

/**
 * How many films the studio's portfolio folder held when the catalogue was gathered (2026-10-10).
 * The last card of the works rail says how many more there are than the site shows.
 */
export const ARCHIVE_TOTAL = 212;

export const catalog: Record<DirectionKey, Work[]> = {
  reels: [
    w("images/work/dir-reels.jpg", "dir-reels", "Paralleli – стейк рибай"),
    w("images/work/paralleli-1.jpg", "paralleli-1", "Paralleli – коктейль", "paralleli"),
    w("images/work/w-119.jpg", "w-119", "Сойка – сибас"),
    w("images/work/w-091.jpg", "w-091", "AERIS Gastrobar"),
    w("images/work/w-145.jpg", "w-145", "Alfea – Mercedes"),
    w("images/work/w-112.jpg", "w-112", "Маяковка"),
  ],
  events: [
    w("images/work/w-078.jpg", "w-078", "BASTA – итоговый ролик"),
    w("images/work/dir-events.jpg", "dir-events", "WeAre – Moscow Travel"),
    w("images/work/bloggers-2.jpg", "bloggers-2", "VK – Блогеры России", "bloggers"),
    w("images/work/w-081.jpg", "w-081", "Extreme Fest"),
    w("images/work/w-087.jpg", "w-087", "Groovemode Session"),
    w("images/work/w-083.jpg", "w-083", "WeAre – Imen, итоговый ролик"),
  ],
  // No wedding films in the portfolio folder yet: the direction shows only its cover, as on the current site.
  wedding: [],
  podcast: [
    w("images/work/dir-podcast.jpg", "dir-podcast", "Подкаст «Бесполезные»"),
    w("images/work/w-158.jpg", "w-158", "Подкаст УГМК – трейлер"),
    w("images/work/w-157.jpg", "w-157", "Подкаст «Гринвич» – трейлер"),
    w("images/work/w-155.jpg", "w-155", "WeAre – Moscow Travel, интервью"),
  ],
  reclama: [
    w("images/work/dir-reclama.jpg", "dir-reclama", "Museum – Pink Bloody Paloma"),
    w("images/work/w-069.jpg", "w-069", "Eyebrows – салон красоты"),
    w("images/work/w-073.jpg", "w-073", "Overlust – Vox School"),
    w("images/work/w-076.jpg", "w-076", "Отель More, Крым"),
    w("images/work/w-071.jpg", "w-071", "Zombollector – ролик для ТВ"),
    w("images/work/aeris-1.jpg", "aeris-1", "AERIS – анимированные афиши", "aeris"),
  ],
  film: [
    w("images/work/dir-film.jpg", "dir-film", "«Электросетьстрой 11»", "ess-11"),
    w("images/work/w-044.jpg", "w-044", "WA – 2 Years, промо"),
    w("images/work/w-045.jpg", "w-045", "We Are Wear – промо"),
    w("images/work/w-046.jpg", "w-046", "Pink Panther – видеокурс"),
  ],
  ai: [
    w("images/work/viking-1.jpg", "viking-1", "VIKING", "viking"),
    w("images/work/w-013.jpg", "w-013", "KIIDA"),
    w("images/work/w-015.jpg", "w-015", "PHILCHANSKY"),
    w("images/work/w-019.jpg", "w-019", "SENLIMA"),
    w("images/work/dir-ai.jpg", "dir-ai", "Tangiers"),
    w("images/work/w-028.jpg", "w-028", "MONTA"),
  ],
};
