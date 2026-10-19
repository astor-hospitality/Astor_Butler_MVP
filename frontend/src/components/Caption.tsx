import type { ReactNode } from "react";
import { Reveal } from "./Reveal";

interface CaptionProps {
  as?: "p" | "figcaption" | "span";
  className?: string;
  children: ReactNode;
}

/** The small capitals under or beside a picture. It signs the picture and arrives after it. */
export function Caption({ as = "p", className = "", children }: CaptionProps) {
  return (
    <Reveal as={as} className={`caps ${className}`}>
      {children}
    </Reveal>
  );
}
