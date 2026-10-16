import { work } from "./media";
import type { Theme, Visual } from "./types";

/**
 * Works that have a page of their own: a cover, a few words, more frames. They are opened from the catalogue (catalog.ts).
 * Names come from the names of the folders and files in the studio's portfolio folder.
 * TODO (studio): check the names and the clients, write two or three sentences for each, give the years.
 */
export interface Case {
  slug: string;
  title: string;
  /** The title as it is set in the large heading: with soft hyphens (\u00AD) where a long word may break on a narrow screen. */
  heading?: string;
  /** One line under the title in the grid and on the cover: the direction the work belongs to. */
  line: string;
  client: string;
  year: string;
  role: string;
  text: string;
  /** The colour of the case's poster in the grid and of its cover screen. */
  theme: Theme;
  cover: Visual;
  feed: Visual[];
}

/** TODO: the year of each work. While it is empty the line is not shown. */
const YEAR = "";

export const cases: Case[] = [
  {
    slug: "paralleli",
    title: "Paralleli",
    line: "Вертикальные ролики",
    client: "Paralleli",
    year: YEAR,
    role: "Съёмка и монтаж вертикальных роликов",
    text: "Вертикальные ролики для Paralleli: кухня, бар, живой вокал.",
    theme: "red",
    cover: work.paralleli[0],
    feed: work.paralleli.slice(1),
  },
  {
    slug: "viking",
    title: "VIKING",
    line: "ИИ-ролики",
    client: "VIKING",
    year: YEAR,
    role: "ИИ-афиши",
    text: "Пять ИИ-афиш для VIKING. Съёмочной площадки не было.",
    theme: "paper",
    cover: work.viking[0],
    feed: work.viking.slice(1),
  },
  {
    slug: "bloggers",
    title: "Блогеры России",
    line: "Репортаж",
    client: "VK",
    year: YEAR,
    role: "Репортаж с события",
    text: "Репортаж с события «Блогеры России» для VK.",
    theme: "red",
    cover: work.bloggers[0],
    feed: work.bloggers.slice(1),
  },
  {
    slug: "aeris",
    title: "AERIS",
    line: "Реклама",
    client: "AERIS",
    year: YEAR,
    role: "Анимированные афиши",
    text: "Анимированные афиши для гастробара AERIS.",
    theme: "paper",
    cover: work.aeris[0],
    feed: work.aeris.slice(1),
  },
  {
    slug: "ess-11",
    title: "«Электросетьстрой 11»",
    heading: "«Электросеть\u00ADстрой 11»",
    line: "Кино",
    client: "Электросетьстрой",
    year: YEAR,
    role: "Документальный фильм и короткие ролики к нему",
    text: "Документальный фильм о компании и шесть коротких роликов из его материала.",
    theme: "red",
    cover: work.ess11[0],
    feed: [work.film, ...work.ess11.slice(1)],
  },
];

export const caseBySlug = (slug: string) => cases.find((c) => c.slug === slug);

export const nextCase = (slug: string): Case => {
  const i = cases.findIndex((c) => c.slug === slug);
  return cases[(i + 1) % cases.length];
};
