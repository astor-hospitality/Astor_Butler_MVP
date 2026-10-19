import type { Metadata } from "next";
import { getImageProps } from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";
import { catalog } from "@content/catalog";
import { productLabels, products, type Product, type ProductLabels } from "@content/products";
import { site, type Direction } from "@content/site";
import type { Theme } from "@content/types";
import { AskClio } from "@/components/assistant/AskClio";
import { MaskWord } from "@/components/MaskWord";
import { ThemeWatcher } from "@/components/motion/ThemeWatcher";
import { Reveal } from "@/components/Reveal";
import { Screen } from "@/components/Screen";
import { Footer } from "@/components/sections/Footer";
import { toRail } from "@/components/sections/Works";
import { WorksRail } from "@/components/sections/WorksRail";
import { Visual } from "@/components/Visual";
import { inkWidth } from "@/lib/travels";

/** Only the seven directions have a page; any other address is not found. */
export const dynamicParams = false;

export function generateStaticParams() {
  return site.directions.items.map((item) => ({ direction: item.key }));
}

const find = (key: string) => site.directions.items.find((item) => item.key === key);

export async function generateMetadata({ params }: PageProps<"/[direction]">): Promise<Metadata> {
  const { direction } = await params;
  const item = find(direction);
  if (!item) return {};
  return { title: item.title, description: products[item.key].lead };
}

type Block = "works" | "packages" | "details";

/**
 * A direction, in the same system as the home page: the dark first screen with its name as a massive word (its picture
 * is seen through the letters), what it is and where the prices start; then its works as a grid of the same cards,
 * the prices, what is included and how it goes, and the red last screen with the way into a talk with Clio.
 * Dark, light and red screens take turns, so the order of the blocks depends on which of them the direction has:
 * with works the light grid parts the dark first screen from the dark prices; without works the details do.
 */
export default async function DirectionPage({ params }: PageProps<"/[direction]">) {
  const { direction } = await params;
  const item = find(direction);
  if (!item) notFound();
  const t = site;
  const l = productLabels;
  const product = products[item.key];
  const works = catalog[item.key];
  const items = t.directions.items;
  const next = items[(items.indexOf(item) + 1) % items.length];

  const hasWorks = works.length > 0;
  const hasPackages = Boolean(product.packages);
  const order: Block[] = hasWorks ? (hasPackages ? ["works", "packages", "details"] : ["works", "details"]) : hasPackages ? ["details", "packages"] : ["details"];
  // the details stand on the light stage, unless the light grid of works is right above them
  const detailsTheme: Theme = hasWorks && !hasPackages ? "ink" : "paper";

  return (
    <>
      <ThemeWatcher />
      <Cover item={item} product={product} l={l} />

      {order.map((block) => {
        if (block === "works") {
          return (
            <Screen key={block} theme="paper" label={l.works} className="wrap air">
              <Reveal as="h2" delay={0} className="caps mb-[clamp(1.5rem,5vh,3rem)] opacity-60">
                {l.works}
              </Reveal>
              <WorksRail items={works.map((work) => toRail(work, item))} grid t={t.works} />
            </Screen>
          );
        }
        if (block === "packages") return <Packages key={block} product={product} l={l} />;
        return <Details key={block} theme={detailsTheme} product={product} l={l} />;
      })}

      <Screen theme="red" label={l.next} className="wrap air text-center">
        <Reveal as="p" delay={0} className="caps">
          {l.next}
        </Reveal>
        <Reveal>
          <h2 className="display mx-auto mt-6 max-w-[18ch]">{item.title}</h2>
        </Reveal>
        <Reveal className="mt-[clamp(1.5rem,5vh,3rem)]">
          <AskClio phrase={product.ask} label={`${l.write} «${product.ask}»`} />
        </Reveal>
        <Reveal as="p" className="copy mx-auto mt-5">
          {product.packages ? l.afterFixed : l.afterQuote}
        </Reveal>
        <Reveal as="p" className="caps mt-[clamp(3rem,10vh,6rem)] flex flex-wrap justify-center gap-x-10 gap-y-2">
          <Link href={`/${next.key}`} className="py-3 underline-offset-4 hover:underline">
            {l.nextDirection}: {next.title}
          </Link>
          <Link href="/#products" className="py-3 underline-offset-4 hover:underline">
            {l.all}
          </Link>
        </Reveal>
      </Screen>

      <Footer t={t} />
    </>
  );
}

/**
 * The first screen: the name as a massive word with the direction's picture behind its letters,
 * under it one line about it, the plain facts and the price.
 */
function Cover({ item, product, l }: { item: Direction; product: Product; l: ProductLabels }) {
  // the share of the width one em may take, so the longest line of the name reaches from margin to margin
  const share = (1000 / (Math.max(...item.lines.map((line) => inkWidth(line.toUpperCase()))) + 30)).toFixed(4);
  // the page may have a picture of its own (the AI page shows the droid, the row of services the parrot)
  const cover = item.pageCover ?? item.cover;
  // the picture inside the letters comes through the image optimiser like any other (1920 px wide is plenty for a strip of letters)
  const wordPicture = getImageProps({ src: cover.src, alt: "", width: 960, height: Math.round((960 * cover.height) / cover.width), quality: 80 }).props.src;
  return (
    <Screen theme="ink" id={item.key} label={item.title} className="wrap pb-[var(--air)] pt-[clamp(6.5rem,17vh,10rem)]">
      <Reveal as="p" delay={0} className="caps flex justify-between gap-6 opacity-60">
        <span className="tabular-nums">
          {item.num} / {site.directions.label}
        </span>
        <Link href="/#products" className="underline-offset-4 hover:underline">
          {l.all}
        </Link>
      </Reveal>

      <Reveal kind="clip" className="mt-[clamp(1.5rem,4vh,2.5rem)]">
        <h1>
          <span className="sr-only">{item.title}</span>
          {/* a wide screen: the picture fills the letters, and around them the page shows as it is (no box of black) */}
          <span className="hero-word block max-md:hidden" aria-hidden="true">
            <MaskWord lines={item.lines} picture={wordPicture} outline />
          </span>
          {/* a phone: there the letters of a long name are too small to be windows, so the name is plain and the picture stands under it */}
          <span className="mass block md:hidden" aria-hidden="true" style={{ fontSize: `min(calc(var(--w) * ${share}), 16.25rem)` }}>
            {item.lines.map((line) => (
              <span key={line} className="block">
                {line}
              </span>
            ))}
          </span>
        </h1>
      </Reveal>
      <div className="mt-6 md:hidden">
        <Visual visual={cover} missingLabel={site.missing} ratio="4/5" sizes="92vw" eager />
      </div>

      <div className="grid-12 mt-[clamp(2rem,6vh,3.5rem)] gap-y-8">
        <Reveal as="p" className="dir-caption col-span-12 md:col-span-6">
          {item.caption}
        </Reveal>
        <Reveal as="div" delay={0.45} className="col-span-12 md:col-span-5 md:col-start-8">
          <p className="caps opacity-60">{item.audience}</p>
          <p className="copy mt-3 opacity-80">{product.lead}</p>
          <p className="caps mt-6 flex flex-wrap items-center gap-x-4 gap-y-2">
            <span className="border border-[color:var(--color-red)] px-3 py-2">{item.price}</span>
            <span className="opacity-60">{product.priceNote}</span>
          </p>
        </Reveal>
      </div>
    </Screen>
  );
}

/** The packages side by side on the dark stage, each in a thin red frame: name, price, what the price covers. */
function Packages({ product, l }: { product: Product; l: ProductLabels }) {
  const packages = product.packages ?? [];
  // Two cards in a row at most: the price is set in the heading size and stays on one line, and a third or a fourth
  // column would be too narrow for it ("7 000 ₽ / час").
  const columns = packages.length >= 2 ? "sm:grid-cols-2" : "";
  const priceSize = "text-[length:var(--fs-m)]";
  return (
    <Screen theme="ink" id="price" label={l.price} className="wrap air">
      <Reveal className="mb-[clamp(1.5rem,5vh,3rem)]">
        <h2 className="display">{l.price}</h2>
      </Reveal>
      <ul className={`grid gap-[clamp(0.75rem,1.1vw,1rem)] ${columns} ${packages.length === 1 ? "max-w-[34rem]" : ""}`}>
        {packages.map((pack, i) => (
          <li key={pack.name}>
            <Reveal kind="clip" delay={i * 0.08} className="hairline flex h-full flex-col p-[clamp(1.25rem,2.4vw,2rem)]">
              {/* «Чаще выбирают» only where there is a choice */}
              {packages.length > 1 ? (
                <p className="caps mb-4 min-h-[1.6em] whitespace-nowrap">{pack.featured ? <span className="bg-red px-2 py-1 text-white">{l.featured}</span> : null}</p>
              ) : null}
              <h3 className="caps">{pack.name}</h3>
              <p className={`mt-3 font-display leading-none font-medium tracking-[0.04em] whitespace-nowrap tabular-nums ${priceSize}`}>{pack.price}</p>
              {pack.note ? <p className="caps mt-3 opacity-70">{pack.note}</p> : null}
              <ul className="copy mt-5 flex flex-col gap-1.5 border-t-[1.5px] border-[color:var(--line)] pt-5 opacity-80">
                {pack.includes.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            </Reveal>
          </li>
        ))}
      </ul>
    </Screen>
  );
}

/**
 * Everything else: "what is included", "how it works", "who it is for" and the rest, like a call sheet.
 * Each block is a row: the name on the left in large type, the facts on the right, a thin red line between the rows.
 */
function Details({ theme, product, l }: { theme: Theme; product: Product; l: ProductLabels }) {
  return (
    <Screen theme={theme} id="details" label={l.includes} className="wrap air">
      <Reveal>
        {product.includes ? (
          <Row title={l.includes}>
            <div className={`grid gap-x-8 gap-y-8 ${product.includes.length > 1 ? "sm:grid-cols-2" : ""}`}>
              {product.includes.map((group) => (
                <div key={group.group}>
                  <h3 className="caps text-red">{group.group}</h3>
                  <List items={group.items} className="mt-2" />
                </div>
              ))}
            </div>
          </Row>
        ) : null}
        {product.deliverables ? (
          <Row title={l.deliverables}>
            <List items={product.deliverables} />
          </Row>
        ) : null}
        <Row title={l.steps}>
          <ol className="flex flex-col gap-5">
            {product.steps.map((step, i) => (
              <li key={step.title} className="grid grid-cols-[2.25rem_1fr] gap-x-2">
                <span className="caps text-red tabular-nums">{String(i + 1).padStart(2, "0")}</span>
                <div>
                  <h3 className="caps">{step.title}</h3>
                  <p className="copy mt-1 opacity-80">{step.text}</p>
                </div>
              </li>
            ))}
          </ol>
        </Row>
        {product.timeline ? (
          <Row title={l.timeline}>
            <List items={product.timeline} />
          </Row>
        ) : null}
        {product.terms ? (
          <Row title={l.terms}>
            <List items={product.terms} />
          </Row>
        ) : null}
        {product.fitFor ? (
          <Row title={l.fitFor}>
            <List items={product.fitFor} className={product.fitFor.length > 5 ? "max-w-none gap-x-8 sm:columns-2" : ""} />
          </Row>
        ) : null}
        {product.geo ? (
          <Row title={l.geo}>
            <p className="copy opacity-80">{product.geo}</p>
          </Row>
        ) : null}
      </Reveal>
    </Screen>
  );
}

/** One row of the sheet. The name takes five columns of twelve: "что снимаем", the longest, fits them in one line. */
function Row({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="grid-12 gap-y-4 border-t-[1.5px] border-[color:var(--color-red)] py-[clamp(1.5rem,5vh,3rem)] first:border-t-0">
      <h2 className="display-sm col-span-12 lg:col-span-5">{title}</h2>
      <div className="col-span-12 lg:col-span-7">{children}</div>
    </section>
  );
}

function List({ items, className = "" }: { items: string[]; className?: string }) {
  return (
    <ul className={`copy opacity-80 ${className}`}>
      {items.map((line) => (
        <li key={line} className="mb-1.5 break-inside-avoid">
          {line}
        </li>
      ))}
    </ul>
  );
}
