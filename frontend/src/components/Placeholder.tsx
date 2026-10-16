import { Frame } from "./Frame";

interface PlaceholderProps {
  /** What picture is owed, e.g. "chrome object on red, 4:5". */
  todo: string;
  /** "Здесь будет". */
  label: string;
  ratio?: "4/5" | "1/1";
  className?: string;
}

/** Stands where a picture is still missing: the frame poster with a note about what has to be made. */
export function Placeholder({ todo, label, ratio = "4/5", className = "" }: PlaceholderProps) {
  return (
    <Frame ratio={ratio} className={className}>
      <span className="mark w-12 text-[color:var(--line)]" aria-hidden="true" />
      <p className="caps">
        {label}
        <br />
        {todo}
      </p>
    </Frame>
  );
}
