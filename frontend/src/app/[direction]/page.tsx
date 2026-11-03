import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import type { CSSProperties, ReactNode } from "react";
import { catalog } from "@content/catalog";
import { productLabels, products, type Product, type ProductLabels } from "@content/products";
import { site, type Direction } from "@content/site";
import type { Theme } from "@content/types";
import { AskClio } from "@/components/assistant/AskClio";
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

type Block = "packages" | "details" | "next";

/**
 * A direction, in the same system as the home page and with no black on it (Sergey, 2026-10-26: «черные фоны табу»).
 * The red first screen, as the studio liked it on the old site: the name of the direction in white, the row of its
 * works playing under it, then its picture, what it is and where the prices start. After it the light and the red
 * stage take turns: the prices, what is included and how it goes, and the last screen with the way into a talk with Clio.
 */
export default async function DirectionPage({ params }: PageProps<"/[direction]">) {
  const { direction } = await params;
  const item = find(direction);
  if (!item) notFound();
  const t = site;
  const l = productLabels;
  const product = products[item.key];
  const items = t.directions.items;
  const next = items[(items.indexOf(item) + 1) % items.length];

  const order: Block[] = product.packages ? ["packages", "details", "next"] : ["details", "next"];
  // the first screen is red, so the light stage comes next and the two take turns from there
  const themeOf = (i: number): Theme => (i % 2 === 0 ? "paper" : "red");

  return (
    <>
      <ThemeWatcher />
      <Cover item={item} product={product} l={l} />

      {order.map((block, i) => {
        const theme = themeOf(i);
        if (block === "packages") return <Packages key={block} theme={theme} product={product} l={l} />;
        if (block === "details") return <Details key={block} theme={theme} product={product} l={l} />;
        return (
          <Screen key={block} theme={theme} label={l.next} className="wrap air text-center">
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
        );
      })}

      {/* the footer stands on the colour of the last screen */}
      <Footer t={t} theme={themeOf(order.length - 1)} />
    </>
  );
}

/**
 * The first screen, red (the header of the old site the studio liked, «C3 FILM» with a row of films on red): the name
 * of the direction in white letters as wide as the page, the row of its works under it, playing by themselves, and then,
 * next to the direction's picture, what it is, who it is for and where the prices start.
 */
function Cover({ item, product, l }: { item: Direction; product: Product; l: ProductLabels }) {
  // the share of the width one em may take, so the longest line of the name reaches from margin to margin
  const share = (1000 / (Math.max(...item.lines.map((line) => inkWidth(line.toUpperCase()))) + 30)).toFixed(4);
  // the page may have a picture of its own (the AI page shows the droid)
  const cover = item.pageCover ?? item.cover;
  const works = catalog[item.key].map((work) => toRail(work, item));
  return (
    <Screen theme="red" id={item.key} label={item.title} className="wrap overflow-x-clip pb-[var(--air)] pt-[clamp(6.5rem,17vh,10rem)]">
      <Reveal as="p" delay={0} className="caps flex justify-between gap-6">
        <span className="tabular-nums">
          {item.num} / {site.directions.label}
        </span>
        <Link href="/#products" className="underline-offset-4 hover:underline">
          {l.all}
        </Link>
      </Reveal>

      <Reveal kind="clip" className="mt-[clamp(1.25rem,4vh,2.25rem)]">
        <h1 className="mass page-name" style={{ "--share": share } as CSSProperties}>
          {item.lines.map((line) => (
            <span key={line} className="block">
              {line}
            </span>
          ))}
        </h1>
      </Reveal>

      <WorksRail items={works} label={l.works} note="what" t={site.works} className="mt-[clamp(1.75rem,5vh,3rem)]" />

      <div className="grid-12 mt-[clamp(3rem,10vh,6rem)] items-end gap-y-8">
        <div className="col-span-12 md:col-span-4">
          <Visual visual={cover} missingLabel={site.missing} ratio="4/5" sizes="(min-width: 768px) 26vw, 80vw" className="page-pic" />
        </div>
        <Reveal as="div" className="col-span-12 md:col-span-7 md:col-start-6">
          <p className="dir-caption">{item.caption}</p>
          <p className="caps mt-5 opacity-80">{item.audience}</p>
          <p className="copy mt-3">{product.lead}</p>
          <p className="caps mt-6 flex flex-wrap items-center gap-x-4 gap-y-2">
            <span className="hairline px-3 py-2">{item.price}</span>
            <span className="opacity-80">{product.priceNote}</span>
          </p>
        </Reveal>
      </div>
    </Screen>
  );
}

/** The packages side by side, each in a thin frame in the line colour: name, price, what the price covers. */
function Packages({ theme, product, l }: { theme: Theme; product: Product; l: ProductLabels }) {
  const packages = product.packages ?? [];
  // Two cards in a row at most: the price is set in the heading size and stays on one line, and a third or a fourth
  // column would be too narrow for it ("7 000 ₽ / час").
  const columns = packages.length >= 2 ? "sm:grid-cols-2" : "";
  const priceSize = "text-[length:var(--fs-m)]";
  return (
    <Screen theme={theme} id="price" label={l.price} className="wrap air">
      <Reveal className="mb-[clamp(1.5rem,5vh,3rem)]">
        <h2 className="display">{l.price}</h2>
      </Reveal>
      <ul className={`grid gap-[clamp(0.75rem,1.1vw,1rem)] ${columns} ${packages.length === 1 ? "max-w-[34rem]" : ""}`}>
        {packages.map((pack, i) => (
          <li key={pack.name}>
            <Reveal kind="clip" delay={i * 0.08} className="hairline flex h-full flex-col p-[clamp(1.25rem,2.4vw,2rem)]">
              {/* «Чаще выбирают» only where there is a choice */}
              {packages.length > 1 ? (
                <p className="caps mb-4 min-h-[1.6em] whitespace-nowrap">{pack.featured ? <span className="bg-[color:var(--line)] px-2 py-1 text-[color:var(--on-line)]">{l.featured}</span> : null}</p>
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
                  <h3 className="caps text-[color:var(--line)]">{group.group}</h3>
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
                <span className="caps text-[color:var(--line)] tabular-nums">{String(i + 1).padStart(2, "0")}</span>
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
    <section aria-label={title} className="grid-12 gap-y-4 border-t-[1.5px] border-[color:var(--line)] py-[clamp(1.5rem,5vh,3rem)] first:border-t-0">
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
