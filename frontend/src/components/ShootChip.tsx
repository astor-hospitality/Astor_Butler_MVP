"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { frames, packagesFor, shootNeeds, shootTexts as t } from "@content/shoot";
import { useBoard } from "@/lib/board";
import { useShoot } from "@/lib/shoot";

/**
 * The storyboard so far, on a small plate that follows the visitor: "Раскадровка · 60 000 ₽ · 3 кадра".
 * It appears once something is chosen or marked, leads to the configurator, and steps aside while the configurator
 * itself is on screen or after the storyboard was sent. On wide screens it stands in the bottom row next to Clio's star
 * (her note then rises above it, see the end of globals.css); on a phone it keeps to the left corner.
 */
export function ShootChip() {
  // a new page starts the watch over again
  return <Plate key={usePathname()} />;
}

function Plate() {
  const choice = useShoot();
  const marked = useBoard();
  const [near, setNear] = useState(false);

  useEffect(() => {
    const el = document.getElementById("shoot");
    if (!el) return;
    const watch = new IntersectionObserver(([entry]) => setNear(entry.isIntersecting), { threshold: 0.15 });
    watch.observe(el);
    return () => watch.disconnect();
  }, []);

  const need = shootNeeds.find((item) => item.id === choice.need);
  const pack = need ? packagesFor(need).find((item) => item.name === choice.pack) : undefined;
  if (choice.sent || near || (!need && marked.length === 0)) return null;

  return (
    <Link
      href="/#shoot"
      aria-label={t.chip.open}
      className="shoot-chip caps theme-paper field hairline fixed bottom-[clamp(1rem,3vh,1.5rem)] left-[clamp(1rem,3vw,1.5rem)] z-40 flex max-w-[calc(100vw-6.5rem)] flex-wrap gap-x-2 px-3 py-2 tabular-nums sm:bottom-[calc(clamp(1rem,3vh,1.5rem)+0.875rem)] sm:left-auto sm:right-[calc(clamp(1rem,3vw,1.5rem)+4.75rem)]"
    >
      <span>{t.chip.name}</span>
      {pack ? (
        <>
          <span aria-hidden="true">·</span>
          <span>{pack.price}</span>
        </>
      ) : null}
      <span aria-hidden="true">·</span>
      <span>{frames(marked.length)}</span>
    </Link>
  );
}
