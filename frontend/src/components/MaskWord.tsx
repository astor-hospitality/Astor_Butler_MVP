import { useId } from "react";
import { CAP_HEIGHT, inkLeft, inkWidth, TRACKING } from "@/lib/travels";

/** The air between two lines of a massive word, in the same units as the letters. */
const GAP = 150;

interface MaskWordProps {
  /** The word, one short line under another. Set in capitals. */
  lines: string[];
  /** A hairline around the letters: for a dark page, where the dark parts of a picture would melt into it. */
  outline?: boolean;
  /**
   * How far the sheet goes on past the word in every direction, in the units of the letters (a capital is 700 high).
   * For a word that stands over a picture as large as the screen: the sheet then covers the whole screen around the word.
   */
  bleed?: number;
  /** The colour of the sheet. The colour of the page, unless the sheet has to keep its own while the page changes. */
  fill?: string;
  /**
   * A picture to fill the letters with, drawn inside the word itself: then there is no sheet at all, and around the
   * letters the page shows as it is, with its grain and its vignette (Roma, 2026-10-16: a sheet of flat black around
   * the name read as a black box on the dark pages).
   */
  picture?: string;
  className?: string;
}

const face = { fontFamily: "var(--font-display)", fontWeight: 900, fontSize: "1000px", letterSpacing: `${TRACKING}px`, fontSynthesis: "none" } as const;

/**
 * A massive word whose letters are windows. Either it is a sheet in the colour of the page with the letters cut out of
 * it, exactly as large as the word itself (or larger, see `bleed`), and a picture or a film laid under it shows through
 * the letters and nowhere else; or (`picture`) the picture is clipped to the letters and nothing else is drawn.
 * The word is drawn from the measures of the face (see lib/travels), so it reaches from edge to edge of its box
 * on any screen and never has to be measured in the browser.
 */
export function MaskWord({ lines, outline = false, bleed = 0, fill = "var(--page-bg)", picture, className = "" }: MaskWordProps) {
  // an id that is safe inside `url(#…)`
  const id = `mask${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
  const set = lines.map((line) => line.toUpperCase());
  const width = Math.max(...set.map(inkWidth));
  const height = set.length * CAP_HEIGHT + (set.length - 1) * GAP;
  // a little over the edge, so no hairline of the picture shows around the sheet
  const sheet = { x: -4, y: -4, width: width + 8, height: height + 8 };
  const letters = (props: React.SVGProps<SVGTextElement>) =>
    set.map((line, i) => (
      <text key={i} x={-inkLeft(line)} y={CAP_HEIGHT + i * (CAP_HEIGHT + GAP)} style={face} {...props}>
        {line}
      </text>
    ));

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className={className} aria-hidden="true" focusable="false">
      {picture ? (
        <>
          <defs>
            <clipPath id={id} clipPathUnits="userSpaceOnUse">
              {letters({})}
            </clipPath>
          </defs>
          <image href={picture} x={0} y={0} width={width} height={height} preserveAspectRatio="xMidYMid slice" clipPath={`url(#${id})`} />
        </>
      ) : (
        <>
          <defs>
            <mask id={id} maskUnits="userSpaceOnUse" {...sheet}>
              <rect {...sheet} fill="#fff" />
              {letters({ fill: "#000" })}
            </mask>
          </defs>
          {/* the sheet around the word: a plain frame with the word's box left open, so the mask stays as small as the word */}
          {bleed ? <path fillRule="evenodd" d={`M${-bleed} ${-bleed}H${width + bleed}V${height + bleed}H${-bleed}ZM0 0H${width}V${height}H0Z`} style={{ fill }} /> : null}
          <rect {...sheet} mask={`url(#${id})`} style={{ fill }} />
        </>
      )}
      {/* strong enough to hold the letters where the picture behind them is as dark as the page (QA, 2026-10-16) */}
      {outline ? letters({ fill: "none", stroke: "currentColor", strokeOpacity: 0.62, strokeWidth: 9 }) : null}
    </svg>
  );
}
