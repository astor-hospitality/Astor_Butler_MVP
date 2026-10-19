import type { MetadataRoute } from "next";
import { cases } from "@content/cases";
import { site, siteUrl } from "@content/site";

/** The home page, the seven directions and the work pages. */
export default function sitemap(): MetadataRoute.Sitemap {
  return [
    { url: siteUrl, priority: 1 },
    ...site.directions.items.map((item) => ({ url: `${siteUrl}/${item.key}`, priority: 0.8 })),
    ...cases.map((item) => ({ url: `${siteUrl}/work/${item.slug}`, priority: 0.6 })),
  ];
}
