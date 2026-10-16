import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { caseBySlug, cases, nextCase } from "@content/cases";
import { site } from "@content/site";
import { flip, isPicture } from "@content/types";
import { Caption } from "@/components/Caption";
import { ThemeWatcher } from "@/components/motion/ThemeWatcher";
import { PosterSection } from "@/components/PosterSection";
import { Reveal } from "@/components/Reveal";
import { Footer } from "@/components/sections/Footer";
import { Visual } from "@/components/Visual";
import { fitHeight } from "@/lib/fit";

export function generateStaticParams() {
  return cases.map((item) => ({ slug: item.slug }));
}

/** Only the works listed in cases.ts have a page. */
export const dynamicParams = false;

export async function generateMetadata({ params }: PageProps<"/work/[slug]">): Promise<Metadata> {
  const { slug } = await params;
  const item = caseBySlug(slug);
  if (!item) return {};
  return { title: item.title, description: item.text };
}

/**
 * A case: the cover, a few words, the frames one by one with wide margins, the way to the next case.
 * Every frame keeps its own proportions. The picture screens share the case's colour (a set of red frames never
 * lands on a red screen); the words and the way out take the other one.
 */
export default async function CasePage({ params }: PageProps<"/work/[slug]">) {
  const { slug } = await params;
  const item = caseBySlug(slug);
  if (!item) notFound();
  const t = site;
  const next = nextCase(item.slug);
  const facts = [
    [t.caseLabels.client, item.client],
    [t.caseLabels.year, item.year],
    [t.caseLabels.role, item.role],
  ].filter(([, value]) => value !== "");

  return (
    <>
      <ThemeWatcher />

      <PosterSection theme={item.theme} label={item.title} className="items-center">
        <figure style={fitHeight(item.cover, 66)}>
          <Visual visual={item.cover} missingLabel={t.missing} sizes="(min-width: 768px) 60vh, 90vw" eager={isPicture(item.cover)} />
          <Caption as="figcaption" className="mt-5 flex justify-between gap-6">
            <span>{item.title}</span>
            <span className="text-right">{item.line}</span>
          </Caption>
        </figure>
      </PosterSection>

      <PosterSection theme={flip(item.theme)} label={item.title}>
        <div className="grid-12 gap-y-12">
          <Reveal className="col-span-12 lg:col-span-7">
            <h1 className="display-sm">{item.heading ?? item.title}</h1>
            <p className="copy mt-6">{item.text}</p>
          </Reveal>
          {/* beside the text only from 1024 px: in a third of a tablet the client's name in capitals ran past the edge */}
          <Reveal className="col-span-12 lg:col-span-4 lg:col-start-9">
            <dl className="caps grid grid-cols-[auto_minmax(0,1fr)] gap-x-6 gap-y-3">
              {facts.map(([name, value]) => (
                <div key={name} className="contents">
                  <dt className="whitespace-nowrap">{name} /</dt>
                  <dd className="[overflow-wrap:anywhere]">{value}</dd>
                </div>
              ))}
            </dl>
          </Reveal>
        </div>
      </PosterSection>

      {item.feed.map((visual, i) => (
        <PosterSection key={i} theme={item.theme} label={`${item.title}, ${i + 1}`} className="items-center">
          <div style={fitHeight(visual, 74)}>
            <Visual visual={visual} missingLabel={t.missing} sizes="(min-width: 768px) 80vw, 92vw" />
          </div>
        </PosterSection>
      ))}

      <PosterSection theme={flip(item.theme)} as="footer" label={t.caseLabels.next} className="items-center text-center">
        <Reveal kind="clip" className="frame-45">
          <Link href={`/work/${next.slug}`} data-cursor={t.works.watch} className="hairline flex aspect-[4/5] flex-col items-center justify-center gap-6 p-[8%] transition-colors duration-500 hover:bg-[color:var(--line)] hover:text-[color:var(--on-line)]">
            <span className="caps">{t.caseLabels.next}</span>
            <span className="display-xs">{next.heading ?? next.title}</span>
          </Link>
        </Reveal>
        <Reveal as="p" className="caps mt-10">
          <Link href="/#catalog" className="py-3 underline-offset-4 hover:underline">
            {t.caseLabels.back}
          </Link>
        </Reveal>
      </PosterSection>

      <Footer t={t} theme={flip(item.theme)} />
    </>
  );
}
