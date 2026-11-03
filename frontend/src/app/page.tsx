import { film, media } from "@content/media";
import { site } from "@content/site";
import { ThemeWatcher } from "@/components/motion/ThemeWatcher";
import { About } from "@/components/sections/About";
import { Directions } from "@/components/sections/Directions";
import { Final } from "@/components/sections/Final";
import { Footer } from "@/components/sections/Footer";
import { MaskHero } from "@/components/sections/MaskHero";
import { PosterPause } from "@/components/sections/PosterPause";
import { Shoot } from "@/components/sections/Shoot";
import { Studio } from "@/components/sections/Studio";
import { allWorks, Works } from "@/components/sections/Works";

/**
 * The home page: red at both ends, light in between, and no black anywhere (Sergey, 2026-10-26: «черные фоны табу»).
 * The first screen, the showreel on red; who we are, typed out on the light stage; the works, on red, straight after
 * them (Sergey: «до блоков работ долго добираться»); then, on the light stage, the seven directions with prices,
 * the lollipop poster, the three steps of the shoot and the studio; the last word, the contacts and the footer (red).
 * The mishaps on set (the dropped ice cream, Fails) are off the home page on Sergey's word the same day.
 */
export default function Home() {
  const t = site;

  return (
    <>
      <ThemeWatcher />
      <MaskHero t={t.hero} still={media.heroMask} film={film} />
      <About t={t.about} />
      <Works t={t} />
      <Directions t={t} />
      <PosterPause picture={media.lollipop} t={t.poster} />
      <Shoot works={allWorks(t)} />
      <Studio t={t} object={media.gamepad} mark={media.hero} />
      <Final t={t} />
      <Footer t={t} />
    </>
  );
}
