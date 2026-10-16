import type { ReactNode } from "react";
import type { Theme } from "@content/types";

interface PosterSectionProps {
  theme: Theme;
  id?: string;
  /** Read by screen readers as the name of the region. */
  label?: string;
  as?: "section" | "footer" | "header";
  className?: string;
  children: ReactNode;
}

/**
 * One screen of the site: a field in one colour, a lot of air, something in the middle of it.
 * `data-poster` is what the theme watcher follows to change the colour of the page.
 */
export function PosterSection({ theme, id, label, as: Tag = "section", className = "", children }: PosterSectionProps) {
  return (
    <Tag
      id={id}
      aria-label={label}
      data-poster={theme}
      className={`poster theme-${theme} wrap relative flex min-h-svh flex-col justify-center py-[clamp(6.5rem,13vh,9.5rem)] ${className}`}
    >
      {children}
    </Tag>
  );
}
