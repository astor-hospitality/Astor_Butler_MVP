import type { MetadataRoute } from "next";
import { siteUrl } from "@content/site";

/** Everything may be indexed but the film proxy. */
export default function robots(): MetadataRoute.Robots {
  return {
    rules: { userAgent: "*", allow: "/", disallow: "/api/" },
    sitemap: `${siteUrl}/sitemap.xml`,
  };
}
