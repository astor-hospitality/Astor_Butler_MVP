import type { CSSProperties } from "react";
import { isPicture, type Visual } from "@content/types";

/**
 * The width at which a picture, keeping its own proportions, is no taller than the given share of the screen's height
 * (and no wider than its place). The whole picture then fits one screen and nothing of it is cut.
 * A picture that is still owed gets the proportions of a 4:5 poster.
 */
export function fitHeight(visual: Visual, share: number): CSSProperties {
  const ratio = isPicture(visual) ? visual.width / visual.height : 4 / 5;
  return { width: `min(100%, calc(${share}svh * ${ratio.toFixed(4)}))` };
}
