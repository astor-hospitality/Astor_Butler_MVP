import { film, media } from "@content/media";
import { site } from "@content/site";
import { ThemeWatcher } from "@/components/motion/ThemeWatcher";
import { About } from "@/components/sections/About";
import { Directions } from "@/components/sections/Directions";
import { Fails } from "@/components/sections/Fails";
import { Final } from "@/components/sections/Final";
import { Footer } from "@/components/sections/Footer";
import { MaskHero } from "@/components/sections/MaskHero";
import { PosterPause } from "@/components/sections/PosterPause";
import { Shoot } from "@/components/sections/Shoot";
import { Studio } from "@/components/sections/Studio";
import { allWorks, Works } from "@/components/sections/Works";

/**
 * The home page: red at both ends, light in between, and black in one place only (Roma's word, 2026-10-11).
 * The first screen (red); who we are and what we do, typed out in white on black; then, all on the light stage,
 * the seven directions with prices, one screen after another, the lollipop poster, the works, the three steps of
 * the shoot, the studio and the mishaps on set (the dropped ice cream); the last word, the contacts and the footer (red).
 */
export default function Home() {
  const t = site;

  return (
    <>
      <ThemeWatcher />
      <MaskHero t={t.hero} still={media.heroMask} film={film} />
      <About t={t.about} />
      <Directions t={t} />
      <PosterPause picture={media.lollipop} t={t.poster} />
      <Works t={t} />
      <Shoot works={allWorks(t)} />
      <Studio t={t} object={media.gamepad} mark={media.hero} />
      <Fails t={t.fails} picture={media.icecream} missing={t.missing} />
      <Final t={t} />
      <Footer t={t} />
    </>
  );
}
