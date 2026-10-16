"use client";

import Image, { type ImageProps } from "next/image";
import { useState } from "react";

/**
 * A picture in a row of many (the strips of the catalogue): it comes in softly when it has arrived,
 * instead of popping into an already open strip. Without scripts it is simply there (see `[data-fade]` in globals.css).
 */
export function FadeImage({ alt, ...props }: ImageProps) {
  const [loaded, setLoaded] = useState(false);
  return <Image alt={alt} {...props} data-fade="" data-loaded={loaded ? "" : undefined} onLoad={() => setLoaded(true)} onError={() => setLoaded(true)} />;
}
