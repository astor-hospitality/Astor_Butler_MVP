import { mediaImage, mediaUrl } from "@/lib/media";
import type { Clip, Picture } from "./types";

/**
 * The files are named by their path under the media root (S3, see src/lib/media.ts); the size of each picture is read
 * from content/media-manifest.ts, made from the files themselves by scripts/media-manifest.mjs. The address carries
 * a short hash of the file, so a replaced picture gets a new address and nobody is shown the old one from a cache.
 */
const picture = (file: string, alt: string, video?: Clip): Picture => ({
  ...mediaImage(file),
  alt,
  ...(video ? { video } : {}),
});

/** A run of frames from one work: the same words, numbered. */
const frames = (files: string[], what: string): Picture[] => files.map((file, i) => picture(file, `${what}, кадр ${i + 1}`));

/**
 * The pictures of the identity (images/refs under the media root): the final shots replace them file for file, under the same names.
 * The brand art is the studio's own (Sergey's posters and their animations, sent 2026-10-14). The food in it is on purpose,
 * in his words: a joke on the culture of consumption, the popcorn you need to watch a film, and «вкусный и сочный контент».
 * Every clip is made from the studio's file by tools/film/brand_media.py: cut to 4:5, 720 x 900, played there and back.
 */
export const media = {
  /** The studio's own chrome mark on a clear background (its file 4.png). It stands on the red screen that opens the site. */
  hero: picture("images/refs/hero.png", "Знак C3 в хроме"),
  /** The first frame of the film of the first screen: painted before the film arrives, and instead of it when motion is off. */
  heroStill: picture("images/refs/hero-still.jpg", "Кадр из шоурила C3: зал в красном свете"),
  /**
   * The frame seen through the letters of the first screen before the film arrives (and instead of it when motion is off):
   * a black and white one, so the letters read at once against the red. The film starts from this very frame (`film.from`).
   */
  heroMask: picture("images/refs/hero-mask.jpg", "Кадр из шоурила C3: съёмка сверху, чёрно-белая"),
  lollipop: picture("images/refs/lollipop.webp", "Знак C3, запаянный в прозрачную упаковку, как леденец на палочке"),
  /** The parrot (Sergey's art, «папуг в качестве»): it leads the reportage, alive. */
  parrot: picture("images/refs/parrot.jpg", "Красно-белый попугай держит в клюве хромированную подвеску со знаком C3", { src: mediaUrl("videos/parrot.mp4"), width: 720, height: 900, pictureShare: 1 }),
  /** The droid with the mark on its forehead («c3 pio»): it leads the AI direction («точно для AI раздела»). */
  droid: picture("images/refs/droid.jpg", "Хромированный дроид со знаком C3 на лбу на красном фоне", { src: mediaUrl("videos/droid.mp4"), width: 720, height: 900, pictureShare: 1 }),
  /** A sheep made of popcorn, eating it with a chrome hoof: to watch a film you need something to munch on. Leads «Кино». */
  sheep: picture("images/refs/sheep.jpg", "Овца в шерсти из попкорна ест попкорн хромированным копытом", { src: mediaUrl("videos/sheep.mp4"), width: 720, height: 900, pictureShare: 1 }),
  /** The mark falls through a burger and stays in it as chrome: juicy content. Leads «Вертикальные ролики», made for restaurants and bars. */
  burger: picture("images/refs/burger.jpg", "Красный бургер со знаком C3, по котлетам стекает хром", { src: mediaUrl("videos/burger.mp4"), width: 720, height: 900, pictureShare: 1 }),
  /** The dropped ice cream whose splash is the mark: the block about mishaps on set («ни один дубль не застрахован»). */
  icecream: picture("images/refs/icecream.jpg", "Упавшее мороженое, лужица растеклась знаком C3", { src: mediaUrl("videos/icecream.mp4"), width: 720, height: 900, pictureShare: 1 }),
  glasses: picture("images/refs/glasses.jpg", "Лицо в хромированных очках, в линзе отражается съёмочная площадка и знак C3"),
  /** Cut off its light background, so it stands on the colour of the screen. */
  gamepad: picture("images/refs/gamepad.webp", "Хромированный геймпад с выдавленными буквами C3AG"),
  /** The portraits come from the current site. They are shown in black and white, like everything that is not red. */
  teamSergey: picture("images/refs/team-sergey.jpg", "Sergey Izyrow"),
  teamMichael: picture("images/refs/team-michael.jpg", "Michael Welly"),
  /**
   * Clio, the studio's assistant: the four-point star of the mark as a chrome tube, open in the middle (see tools/chrome).
   * Chrome shows what stands around it, so there are two of her: on a red screen the metal carries red,
   * on a white or a black one it does not (that one has dark reflections and reads on white as well).
   */
  clio: picture("images/refs/clio.png", "Clio"),
  clioPlain: picture("images/refs/clio-dark.png", "Clio"),
} satisfies Record<string, Picture>;

/**
 * The film of the first screen: a short silent cut of the studio's showreel, compressed for the web (see tools/film).
 * `wide` is for landscape screens, `tall` is the middle of the same frames cut upright for phones;
 * `from` is the second the first screen starts it from (the frame of `media.heroMask`); it then runs in a loop.
 */
export const film = { wide: mediaUrl("videos/hero.mp4"), tall: mediaUrl("videos/hero-m.mp4"), from: 10.2 };

/**
 * The studio's animation of the mark turning in chrome, with its alpha kept (VP9 WebM, from logo.mov): it plays on the red
 * screen that opens the site. Safari cannot show the alpha of a WebM, so there the still mark stays.
 */
export const logoSpin = mediaUrl("videos/logo-spin.webm");

/**
 * Frames from the studio's own films (images/work under the media root). They are the preview frames of the videos in the studio's
 * portfolio folder, 1280 px on the long side. TODO: frames chosen by the studio, in full size, and the clips themselves.
 */
export const work = {
  // covers of the directions that have no brand art of their own
  events: picture("images/work/dir-events.jpg", "Диджей за пультом в красном свете"),
  reels: picture("images/work/dir-reels.jpg", "Стейк на гриле, его поднимают щипцами"),
  podcast: picture("images/work/dir-podcast.jpg", "Трое ведущих за столом с микрофонами"),
  film: picture("images/work/dir-film.jpg", "Герой фильма даёт интервью в полутёмном кабинете"),
  wedding: picture("images/work/dir-wedding.jpg", "Кинокамера перед свадебным залом, на столике букет и кольца"),

  // works shown as cases: the cover first, then the feed
  viking: frames(["images/work/viking-1.jpg", "images/work/viking-2.jpg", "images/work/viking-3.jpg", "images/work/viking-4.jpg", "images/work/viking-5.jpg"], "ИИ-афиша для VIKING"),
  paralleli: frames(["images/work/paralleli-1.jpg", "images/work/paralleli-2.jpg", "images/work/paralleli-3.jpg", "images/work/paralleli-4.jpg"], "Вертикальный ролик для Paralleli"),
  ess11: frames(["images/work/ess11-1.jpg", "images/work/ess11-2.jpg", "images/work/ess11-3.jpg", "images/work/ess11-4.jpg"], "Короткий ролик к фильму «Электросетьстрой 11»"),
  bloggers: frames(["images/work/bloggers-1.jpg", "images/work/bloggers-2.jpg"], "Репортаж «Блогеры России»"),
  aeris: frames(["images/work/aeris-1.jpg", "images/work/aeris-2.jpg", "images/work/aeris-3.jpg", "images/work/aeris-4.jpg", "images/work/aeris-5.jpg"], "Афиша для AERIS"),
};
