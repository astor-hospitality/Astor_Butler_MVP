/**
 * The colour of a screen. Red, a dark stage and a light one take turns down a page, never two of a kind in a row
 * where that can be helped: on the dark stage chrome and red look their best.
 */
export type Theme = "red" | "paper" | "ink";

/** The other of the two bright colours. */
export const flip = (theme: Theme): Theme => (theme === "red" ? "paper" : "red");

/** The studio's seven directions, by the addresses they have on the current site. */
export type DirectionKey = "reels" | "events" | "wedding" | "podcast" | "reclama" | "film" | "ai";

/** A picture that is already in /public. */
export interface Picture {
  src: string;
  width: number;
  height: number;
  alt: string;
  /** A looping clip of the same picture, shown over the still when motion is allowed. */
  video?: Clip;
}

/**
 * The studio's clips are made in poster format: the picture stands in the middle of a taller frame,
 * with empty bands above and below. `pictureShare` is the part of the clip's height the picture takes.
 */
export interface Clip {
  src: string;
  width: number;
  height: number;
  pictureShare: number;
}

/** A picture that is still owed: the note says what has to be shot or rendered. */
export interface Missing {
  todo: string;
}

export type Visual = Picture | Missing;

export const isPicture = (visual: Visual): visual is Picture => "src" in visual;
