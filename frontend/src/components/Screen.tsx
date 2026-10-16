import type { ReactNode } from "react";
import type { Theme } from "@content/types";

interface ScreenProps {
  theme: Theme;
  id?: string;
  /** Read by screen readers as the name of the region. */
  label?: string;
  as?: "section" | "footer" | "header" | "div";
  className?: string;
  children: ReactNode;
}

/**
 * One screen of a page: a field in one colour. It sets nothing but the colour; the margins and the air are given by
 * what stands on it (`wrap`, `air`). `data-poster` is what the theme watcher follows to change the colour of the page.
 */
export function Screen({ theme, id, label, as: Tag = "section", className = "", children }: ScreenProps) {
  return (
    <Tag id={id} aria-label={label} data-poster={theme} className={`poster theme-${theme} relative ${className}`}>
      {children}
    </Tag>
  );
}
