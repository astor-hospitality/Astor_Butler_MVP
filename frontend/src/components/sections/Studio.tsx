import Image from "next/image";
import type { SiteContent } from "@content/site";
import type { Picture } from "@content/types";
import { Reveal } from "../Reveal";
import { RevealImage } from "../RevealImage";
import { Screen } from "../Screen";

interface StudioProps {
  t: SiteContent;
  /** The chrome gamepad of the identity: it stands in front of the massive word. */
  object: Picture;
  /** The chrome mark, for the places of the team that have no portrait yet. */
  mark: Picture;
}

/**
 * The studio, on the light stage. The massive word stands behind the chrome gamepad (the picture in front, the type
 * behind it); under them the studio's own words about its films, and the four places of the team.
 * A portrait is black and white and turns red and black under the pointer; a place without a portrait holds the
 * chrome mark on red, never an empty frame.
 */
export function Studio({ t, object, mark }: StudioProps) {
  return (
    <Screen theme="paper" id="about" label={t.studio.label} className="wrap air">
      <div className="relative grid justify-items-center" style={{ fontSize: "calc(var(--w) * 0.18)" }}>
        <Reveal kind="clip" className="absolute left-1/2 top-0 -translate-x-1/2">
          <p className="mass text-[1em] !leading-[0.78]" aria-hidden="true">
            {t.studio.word}
          </p>
        </Reveal>
        <RevealImage src={object.src} width={object.width} height={object.height} alt={object.alt} sizes="(min-width: 768px) 34vw, 78vw" className="relative z-[1] mt-[0.4em] w-[78vw] md:w-[34vw]" />
      </div>

      <div className="grid-12 mt-[clamp(3rem,9vh,5.5rem)] gap-y-4">
        <Reveal as="p" delay={0} className="caps col-span-12 opacity-60 md:col-span-4">
          {t.studio.label}
        </Reveal>
        <Reveal as="p" className="col-span-12 max-w-[46ch] leading-normal md:col-span-6 md:col-start-7">
          {t.studio.text}
        </Reveal>
      </div>

      <ul className="mt-[clamp(3rem,9vh,5.5rem)] grid grid-cols-2 gap-x-[clamp(0.75rem,1.1vw,1rem)] gap-y-8 md:grid-cols-4">
        {t.studio.members.map((member, i) => (
          <li key={member.num} className="min-w-0">
            <Reveal kind="clip" delay={i * 0.07}>
              <div className={`face-tile ${member.photo ? "" : "is-sign"}`}>
                {member.photo ? (
                  <Image src={member.photo.src} alt={member.photo.alt} fill sizes="(min-width: 768px) 22vw, 45vw" className="face" />
                ) : (
                  <Image src={mark.src} alt="" width={mark.width} height={mark.height} sizes="(min-width: 768px) 12vw, 25vw" className="h-auto w-[56%]" />
                )}
              </div>
            </Reveal>
            <Reveal delay={0.3 + i * 0.07}>
              <p className="caps mt-4 opacity-60 tabular-nums">{member.role ? `${member.num} / ${member.role}` : member.num}</p>
              <h3 className="mt-1 font-display text-[length:var(--fs-s)] font-extrabold uppercase leading-[1.15] lg:text-[length:var(--fs-t)]">{member.name}</h3>
            </Reveal>
          </li>
        ))}
      </ul>
    </Screen>
  );
}
