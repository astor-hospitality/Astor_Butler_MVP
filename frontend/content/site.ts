import { media, work } from "./media";
import type { DirectionKey, Picture } from "./types";

/**
 * Everything the home page says. Edit here, not in the markup.
 * The page has what the current site (c3ag.ru) has: the first screen, who we are and what we do, the seven directions
 * with prices one screen after another, the works, the shoot configurator, the studio, the contacts.
 * The site is in Russian only. Latin letters stay in three places: the mark C3, addresses, and proper names
 * (Clio, Instagram, Telegram, clients and their events, the two founders as they write themselves).
 * The words are the studio's own wherever it has them (the current site, its posters, its proposals).
 */
export interface SiteContent {
  meta: { title: string; description: string };
  nav: { home: string; services: string; work: string; about: string; cta: string; skip: string };
  hero: { label: string; word: string; scroll: string; reel: string };
  about: { label: string; sentences: string[] };
  works: WorksTexts;
  poster: { wide: Part[][]; narrow: Part[][] };
  services: { label: string; more: string };
  studio: { word: string; label: string; text: string; members: Member[] };
  fails: { label: string; line: string; stories: Story[] };
  final: { label: string; word: string; line: string; cta: string; links: ContactLink[] };
  footer: { line: string; legal: string; policy: string; links: { label: string; href: string }[] };
  directions: { label: string; items: Direction[] };
  caseLabels: { client: string; year: string; role: string; next: string; back: string };
  notFound: { text: string; home: string };
  missing: string;
  clio: ClioTexts;
}

/** A piece of a massive line: plain, or one of the red words. */
export interface Part {
  text: string;
  red?: boolean;
}

/** The words of the works rail and of the player. */
export interface WorksTexts {
  title: string;
  all: string;
  /** Read by screen readers as the name of the row of directions. */
  filter: string;
  prev: string;
  next: string;
  watch: string;
  /** The word in the pointer over the rail. */
  drag: string;
  /** The plus on a card, before the name of the work: it puts the work on the storyboard. */
  add: string;
  remove: string;
  /** "В раскадровке: 3" under the rail, and the sentence that says what the plus is for. */
  board: string;
  hint: string;
  /** The last card of the rail. */
  archive: string;
  archiveMore: string;
  archiveAsk: string;
  /** Shown instead of the rail for a direction that has no works yet. */
  empty: string;
  emptyCta: string;
  emptyAsk: string;
  /** The player. */
  close: string;
  about: string;
  sound: string;
  /** Said in the player when a film cannot be played here and its short cut is shown instead. */
  fallback: string;
  /** Said when a film cannot be played here and there is no short cut either: its still stays. */
  noFilm: string;
}

export interface Member {
  /** "01" to "04". */
  num: string;
  /** A person by name, or a part of the studio when no name is given yet. */
  name: string;
  role?: string;
  /** Without a portrait the tile holds the chrome mark. */
  photo?: Picture;
}

/** The words of the assistant's window. */
export interface ClioTexts {
  name: string;
  role: string;
  open: string;
  close: string;
  greeting: string;
  placeholder: string;
  send: string;
  you: string;
  typing: string;
  /** Said instead of an answer while the chat is not connected to the server. The contacts follow it. */
  offline: string;
  failed: string;
  /** Shown under the field once the chat is connected: sending a message means agreeing to the policy. */
  consent: { text: string; link: string };
  /** Around her notes (the notes themselves are in hints.ts): the link under a note, the cross, and the switch. */
  hints: { answer: string; close: string; off: string; turnOff: string; turnOn: string };
}

export interface Direction {
  key: DirectionKey;
  /** Its place in the row, 01 to 07. */
  num: string;
  title: string;
  /** The title as it is set in the massive word of its page: whole words, one line under another. */
  lines: string[];
  /** One line about it. */
  caption: string;
  /** Who it is for. */
  audience: string;
  /** Where the prices start, or that the work is estimated. */
  price: string;
  /** Its picture: seen through the letters on its page, and the small frame in the row of services. */
  cover: Picture;
  /** Another picture for its own page, when the studio gave the direction a second one. */
  pageCover?: Picture;
}

/** One mishap on set, told by the studio: what happened, and how the shoot came out of it. */
export interface Story {
  title: string;
  text: string;
}

export interface ContactLink {
  label: string;
  value: string;
  href: string;
}

// TODO: real addresses. The Instagram name is read off the posters ("@ C3.AGNCY"), the rest are placeholders.
const links: ContactLink[] = [
  { label: "Почта", value: "hello@c3.agncy", href: "mailto:hello@c3.agncy" },
  { label: "Instagram", value: "@c3.agncy", href: "https://www.instagram.com/c3.agncy" },
  { label: "Telegram", value: "@c3agncy", href: "https://t.me/c3agncy" },
];

/**
 * The seven directions, exactly these and in this order (the brief of 2026-10-11): names, lines and prices as given there.
 * There is no eighth: "Smart Solutions" of the old site is not a direction of this one.
 * Five are led by the brand's art: the chrome droid (AI, «c3 pio», «точно для AI раздела»), the parrot (reportage),
 * the burger (vertical clips for restaurants and bars), the glasses (advertising), the popcorn sheep (film: you need
 * something to munch on to watch one); the others by a frame from a work. The art and the reasons are Sergey's (2026-10-14),
 * the places are Roma's (2026-10-16).
 */
const items: Direction[] = [
  {
    key: "ai",
    num: "01",
    title: "ИИ-ролики",
    lines: ["ИИ-ролики"],
    caption: "Рекламный контент без съёмочной площадки",
    audience: "Брендам, агентствам, продакшнам",
    price: "Смета под проект",
    // the droid is «точно для AI раздела» (Sergey); the parrot went to the reportage on Roma's word (2026-10-16)
    cover: media.droid,
  },
  {
    key: "events",
    num: "02",
    title: "Репортаж",
    lines: ["Репортаж"],
    caption: "Атмосфера и масштаб события в одном ролике",
    audience: "Клубам, площадкам, организаторам",
    price: "от 22 000 ₽",
    cover: media.parrot,
  },
  {
    key: "reels",
    num: "03",
    title: "Вертикальные ролики",
    lines: ["Вертикальные", "ролики"],
    caption: "Контент на 2–4 недели за одну смену",
    audience: "Ресторанам и барам",
    price: "85 000 ₽",
    cover: media.burger,
  },
  {
    key: "podcast",
    num: "04",
    title: "Подкасты",
    lines: ["Подкасты"],
    caption: "Запись видеоподкаста под ключ",
    audience: "Экспертам, продюсерам, предпринимателям",
    price: "45 000 ₽",
    cover: work.podcast,
  },
  {
    key: "reclama",
    num: "05",
    title: "Реклама",
    lines: ["Реклама"],
    caption: "Рекламные ролики полного цикла",
    audience: "Компаниям, брендам, агентствам",
    price: "Смета под проект",
    cover: media.glasses,
  },
  {
    key: "film",
    num: "06",
    title: "Кино",
    lines: ["Кино"],
    caption: "Документальные и корпоративные фильмы",
    audience: "Компаниям, заводам, девелоперам",
    price: "Смета под проект",
    cover: media.sheep,
  },
  {
    key: "wedding",
    num: "07",
    title: "Свадьбы",
    lines: ["Свадьбы"],
    caption: "Кинематографичная история вашего дня",
    audience: "Молодожёнам и организаторам свадеб",
    price: "от 60 000 ₽",
    cover: work.wedding,
  },
];

/**
 * Four places. Two have a name and a portrait; the other two are parts of the studio until it names the people.
 * TODO (studio): the people behind "Финансы" and "Производство", and their portraits.
 */
const members: Member[] = [
  { num: "01", name: "Sergey Izyrow", role: "Основатель", photo: media.teamSergey },
  { num: "02", name: "Michael Welly", role: "Технический директор", photo: media.teamMichael },
  { num: "03", name: "Финансы" },
  { num: "04", name: "Производство" },
];

/**
 * The page with the personal data policy the visitor agrees to by writing to Clio. By default it is the policy of C3AG
 * that ships with this site (public/docs/policy.html, the same one the server's consent points to);
 * NEXT_PUBLIC_POLICY_URL gives another address. The chat talks to the server only when both this and
 * NEXT_PUBLIC_WEB_CHAT_ENDPOINT are set (see src/lib/clio.ts).
 */
export const policyHref: string = process.env.NEXT_PUBLIC_POLICY_URL ?? "/docs/policy.html";

/** Where the site lives: the base of the share links, the sitemap and robots.txt (NEXT_PUBLIC_SITE_URL, fixed at build time). */
export const siteUrl = (process.env.NEXT_PUBLIC_SITE_URL || "https://c3ag.ru").replace(/\/+$/, "");

export const site: SiteContent = {
  meta: {
    title: "C3 – видеопродакшн-студия",
    description: "Независимая продакшн-студия C3. ИИ-ролики, репортажи, вертикальные ролики, подкасты, реклама, кино и свадебные фильмы.",
  },
  nav: { home: "C3, на главную", services: "Услуги", work: "Работы", about: "О студии", cta: "Обсудить проект", skip: "К содержанию" },
  hero: {
    label: "Студия C3 / видеопродакшн",
    word: "C3",
    scroll: "Листайте вниз",
    reel: "Шоурил",
  },
  // Who we are and what we do, typed out letter by letter under the first screen, in white on black
  // (Roma's words, 2026-10-11: "пусть здесь проявляются слова и рассказывают, что мы делаем", "только белый текст",
  // "пусть он медленно сам выходит, как на сайте, который я скидывал", that is bbx.media).
  // Two short sentences and no more ("поменьше текста", the same day): what C3 is, a plain fact, and the studio's own
  // manifesto line. The directions are not named here: the seven screens right under these words name them.
  // TODO (studio): the final wording.
  about: {
    label: "О нас",
    // "до того" is held together, so a line never ends with "до"
    sentences: ["C3 – независимая продакшн-студия.", "Мы ловим энергию до того, как она исчезает."],
  },
  works: {
    title: "Работы",
    all: "Все",
    filter: "Направление",
    prev: "Назад по ленте",
    next: "Вперёд по ленте",
    watch: "Смотреть",
    drag: "Тяни",
    add: "Добавить в раскадровку",
    remove: "Убрать из раскадровки",
    board: "В раскадровке",
    hint: "Плюс на карточке добавляет работу в референсы для вашей съёмки.",
    archive: "Архив",
    archiveMore: "ещё",
    archiveAsk: "Хочу посмотреть архив работ.",
    empty: "Подборку отправим в чат после короткого брифа",
    emptyCta: "Хочу свадебный фильм",
    emptyAsk: "Хочу свадебный фильм.",
    close: "Закрыть",
    about: "О проекте",
    sound: "Со звуком",
    fallback: "Полная версия здесь не открылась, показываем фрагмент.",
    noFilm: "Фильм здесь не открылся, показываем кадр.",
  },
  // The line of the lollipop poster ("Learn to shoot or step aside and hold this."), in Russian, set as massive words:
  // `wide` is how it breaks into lines on a wide screen, `narrow` on a phone. The lollipop stands next to the last word.
  poster: {
    wide: [[{ text: "Научись снимать" }], [{ text: "или отойди" }], [{ text: "и подержи" }], [{ text: "это.", red: true }]],
    narrow: [[{ text: "Научись" }], [{ text: "снимать" }], [{ text: "или отойди" }], [{ text: "и подержи" }], [{ text: "это.", red: true }]],
  },
  // The row of directions opens with its plain name and nothing else (Roma's word, 2026-10-11: the line
  // "Производство без лишних слов." over it looked overloaded).
  services: {
    label: "Услуги и цены",
    more: "Подробнее",
  },
  studio: {
    word: "Студия",
    label: "О студии",
    // The studio's own words, from the manifesto of the current site.
    text: "Наши фильмы ощущаются как редакционный материал, движутся с намерением и оставляют зрителю пространство, чтобы войти внутрь.",
    members,
  },
  // The studio wants to tell about mishaps on set (Sergey, 2026-10-14: «прикольно рассказать про факапы на съёмках»),
  // under its poster with the dropped ice cream and the line «Ни один дубль не застрахован».
  // TODO (studio): the stories themselves, a few lines each; until they come the block is the poster alone.
  fails: { label: "Факапы на съёмках", line: "Ни один дубль не застрахован", stories: [] },
  // "Играем?" stands where "Let's play" stood; the line under it is the one of the gamepad poster ("Content is a game…").
  final: { label: "Контакт", word: "Играем?", line: "Контент – это игра. Правила задаёте вы.", cta: "Обсудить проект", links },
  footer: {
    line: "Независимая продакшн-студия / 2026",
    legal: "© 2026 C3",
    policy: "Политика данных",
    // "Приёмка", the clients' room of the old site, is not on the domain any more (/studio leads home, see next.config.ts).
    // TODO (studio): put the link back when the room has an address.
    links: [
      { label: "Работы", href: "/#catalog" },
      { label: "Контакт", href: "/#contact" },
    ],
  },
  directions: { label: "Направления", items },
  caseLabels: { client: "Клиент", year: "Год", role: "Роль", next: "Следующая работа", back: "Все работы" },
  notFound: { text: "Здесь ничего не снимали.", home: "На главную" },
  missing: "Здесь будет",
  clio: {
    name: "Clio",
    role: "ИИ-ассистентка C3",
    open: "Открыть чат с Clio",
    close: "Закрыть чат",
    greeting: "Я Clio, ИИ-ассистентка C3. Расскажите, что нужно снять.",
    placeholder: "Напишите задачу…",
    send: "Отправить",
    you: "Вы",
    typing: "Clio пишет",
    offline: "Чат ещё не подключён к серверу студии. Напишите нам напрямую:",
    failed: "Не получилось отправить. Попробуйте ещё раз или напишите нам напрямую:",
    consent: { text: "Отправляя сообщение, вы соглашаетесь с", link: "политикой обработки данных" },
    hints: {
      answer: "Ответить",
      close: "Закрыть подсказку",
      off: "Больше не подсказывать",
      turnOff: "Подсказки на страницах включены. Выключить",
      turnOn: "Подсказки на страницах выключены. Включить",
    },
  },
};
