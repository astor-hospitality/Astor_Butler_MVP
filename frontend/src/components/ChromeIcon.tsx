import Image from "next/image";
import { mediaUrl } from "@/lib/media";

/**
 * The studio's own icons in chrome, the way the client sent them ("иконки на сайт"): cut from the sheet by
 * `tools/chrome/cut_icons.py` into `images/icons` of the media (S3), each on a transparent ground with its own proportions.
 * Chrome reads on the light, the red and the black, so one set serves every screen.
 * They stand only where an icon already stood (the arrows of the works rail and of the player): the owner took
 * scattered chrome ornaments off the site as clutter, so they are not decoration.
 */
const ICONS = {
  sound: [256, 202],
  star: [256, 224],
  lightning: [246, 256],
  flame: [139, 256],
  drop: [160, 256],
  infinity: [256, 117],
  wave: [256, 105],
  arrow: [256, 165],
  heartbeat: [256, 216],
  crown: [256, 193],
  heart: [256, 199],
  pin: [160, 256],
  link: [256, 217],
  share: [224, 256],
  power: [220, 256],
  play: [246, 256],
  pause: [155, 256],
  forward: [256, 223],
  rewind: [256, 232],
  trash: [224, 256],
  fullscreen: [256, 230],
} as const;

export type ChromeIconName = keyof typeof ICONS;

interface ChromeIconProps {
  name: ChromeIconName;
  /** Its width in px; the height follows the icon's own proportions. */
  width: number;
  /** Turned to face the other way: the arrow for "back". */
  mirror?: boolean;
  className?: string;
}

/** One chrome icon. It only shows what its button says: the button carries the name, the icon is hidden from readers. */
export function ChromeIcon({ name, width, mirror = false, className = "" }: ChromeIconProps) {
  const [w, h] = ICONS[name];
  return (
    <Image
      src={mediaUrl(`images/icons/${name}.png`)}
      alt=""
      aria-hidden="true"
      width={width}
      height={Math.round((width * h) / w)}
      draggable={false}
      // served as cut: the browser's own scaling of the 256 px file keeps the thin chrome lines sharp at 28 px,
      // the optimizer's 32 px copy blurred them (one small file, cached, for every button)
      unoptimized
      // the width is set here as well: the player sizes every picture inside it to its own (`.lightbox img`)
      style={{ width }}
      className={["pointer-events-none h-auto max-w-none select-none", mirror && "-scale-x-100", className].filter(Boolean).join(" ")}
    />
  );
}
