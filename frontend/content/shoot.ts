import { products } from "./products";
import type { DirectionKey } from "./types";

/**
 * The shoot configurator, "Соберите съёмку за три шага": everything it says and offers is here.
 *
 * Step one is what is to be shot (`needs`). Each need leads to a direction, and step two shows that direction's packages.
 * The packages and their prices are NOT written here a second time: they are read from products.ts, the same record the
 * direction's own page shows, so the configurator can never name a price the page does not. To change a price, change it there.
 * A need without a direction ("Другое") is estimated, not priced.
 * Step three is the storyboard: the works the visitor marked with a plus in the works reel.
 */
export interface ShootNeed {
  id: string;
  label: string;
  /** The direction whose packages are offered. Left out: the work is estimated for the task. */
  direction?: DirectionKey;
}

export interface ShootPackage {
  name: string;
  price: string;
  note?: string;
}

export const shootNeeds: ShootNeed[] = [
  { id: "restaurant", label: "Гости в ресторан", direction: "reels" },
  { id: "event", label: "Снять событие", direction: "events" },
  { id: "wedding", label: "Свадебный фильм", direction: "wedding" },
  { id: "podcast", label: "Записать подкаст", direction: "podcast" },
  { id: "other", label: "Другое" },
];

/** What is offered when the need has no priced packages: reclama, films, AI and anything else. */
const estimate: ShootPackage = { name: "Смета под проект", price: "по смете", note: "Реклама, кино, ИИ-ролики и всё, чего нет в списке: считаем под задачу" };

export function packagesFor(need: ShootNeed): ShootPackage[] {
  const packs = need.direction ? products[need.direction].packages : undefined;
  return packs?.length ? packs.map(({ name, price, note }) => ({ name, price, note })) : [estimate];
}

/** 1 кадр, 2 кадра, 5 кадров. */
export function frames(n: number): string {
  const tens = n % 100;
  const ones = n % 10;
  const word = tens >= 11 && tens <= 14 ? "кадров" : ones === 1 ? "кадр" : ones >= 2 && ones <= 4 ? "кадра" : "кадров";
  return `${n} ${word}`;
}

export const shootTexts = {
  title: "Соберите съёмку за три шага",
  steps: {
    need: { name: "Что снимаем" },
    pack: { name: "Пакет", wait: "Сначала выберите, что снимаем." },
    refs: {
      name: "Референсы",
      empty: "Пока ничего не отмечено. Плюс на кадре в «Работах» добавляет работу сюда.",
      toWorks: "К работам",
      remove: "Убрать из раскадровки",
    },
  },
  total: {
    name: "Итог",
    need: "Что снимаем",
    pack: "Пакет",
    price: "Цена",
    refs: "Референсы",
    /** In the line of the total while nothing is chosen. */
    none: "Пока ничего не выбрано",
    send: "Отправить Clio",
    /** Under the button while it cannot be pressed. */
    locked: "Выберите, что снимаем, и пакет.",
    note: "Точную смету подтвердит менеджер.",
  },
  done: {
    word: "Снято.",
    text: "Раскадровка у Clio. Она передаст её команде, точную смету подтвердит менеджер.",
    again: "Собрать заново",
  },
  /** The small plate that follows the visitor down the page once something is chosen. */
  chip: { name: "Раскадровка", open: "Открыть раскадровку" },
  /** How the message to Clio begins. */
  message: "Раскадровка с сайта.",
};
