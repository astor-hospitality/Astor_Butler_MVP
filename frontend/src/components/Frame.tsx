import type { ReactNode } from "react";

interface FrameProps {
  ratio?: "4/5" | "1/1";
  className?: string;
  children?: ReactNode;
}

/** The thin frame of the typographic posters: a line, a small mark, a couple of lines of small text inside. */
export function Frame({ ratio = "4/5", className = "", children }: FrameProps) {
  return (
    <div className={`hairline flex flex-col items-center justify-center gap-7 p-[8%] text-center ${className}`} style={{ aspectRatio: ratio }}>
      {children}
    </div>
  );
}
