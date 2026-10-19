import { isPicture, type Visual as VisualData } from "@content/types";
import { LoopVideo } from "./LoopVideo";
import { Placeholder } from "./Placeholder";
import { Reveal } from "./Reveal";
import { RevealImage } from "./RevealImage";

interface VisualProps {
  visual: VisualData;
  /** "Здесь будет", for a picture that is not there yet. */
  missingLabel: string;
  sizes: string;
  /** A frame the picture fills. Leave out for an object that floats on the field in its own proportions. */
  ratio?: "4/5" | "1/1";
  eager?: boolean;
  className?: string;
}

/** A picture from the content files, or the frame with a note where the picture is still owed. */
export function Visual({ visual, missingLabel, sizes, ratio, eager, className }: VisualProps) {
  if (!isPicture(visual)) {
    return (
      <Reveal kind="clip" className={className}>
        <Placeholder todo={visual.todo} label={missingLabel} ratio={ratio ?? "4/5"} />
      </Reveal>
    );
  }
  return (
    <RevealImage src={visual.src} width={visual.width} height={visual.height} alt={visual.alt} sizes={sizes} ratio={ratio} eager={eager} className={className}>
      {visual.video ? <LoopVideo clip={visual.video} /> : null}
    </RevealImage>
  );
}
