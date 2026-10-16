import { mediaUrl } from "@/lib/media";

/**
 * The site has one face and no other, TT Travels Next: headings, the small capitals, buttons, numbers and the reading
 * text alike. The files are media and live in S3 with the pictures (fonts/… under NEXT_PUBLIC_MEDIA_BASE_URL), so the
 * layout writes the @font-face rules itself instead of next/font: a missing file never stops the build, the page then
 * simply stays in the fallback. The bucket must allow the site's origin (CORS), or the browser refuses the files.
 *
 * In the styles the face is reached only through `--font-display` and `--font-text` (see globals.css); both name it
 * through `--font-travels`, set here. Until the face has arrived the browser shows Arial fitted to its measures
 * (the same overrides next/font measured from the file), so nothing jumps.
 *
 * History: on 2026-10-10 Roma asked for one face only and the text went to TT Travels Next too;
 * on 2026-10-11 he asked for a second face back (Muller Next for reading); on 2026-10-16 he settled on one face,
 * TT Travels Next, in three sizes.
 *
 * TODO (licence): these are trial builds ("for evaluation only"). Buy the licence and put the full files
 * under the same names before the site goes to its domain (and run `npm run media:manifest`).
 */

/** TT Travels Next: Regular for reading, Medium for the small capitals, ExtraBold for headings, Black for the largest words. */
const faces = [
  { file: "fonts/TTTravelsNextTrial-Regular.woff", weight: 400 },
  { file: "fonts/TTTravelsNextTrial-Medium.woff", weight: 500 },
  { file: "fonts/TTTravelsNextTrial-ExtraBold.woff", weight: 800 },
  { file: "fonts/TTTravelsNextTrial-Black.woff", weight: 900 },
];

/** The addresses of the files, preloaded by the layout. */
export const fontFiles = faces.map(({ file }) => mediaUrl(file));

/** The rules the layout puts into <head>. "ss01" gives plain д and г. */
export const fontFaces = [
  ...faces.map(
    ({ weight }, i) =>
      `@font-face{font-family:"TT Travels Next";src:url("${fontFiles[i]}") format("woff");font-weight:${weight};font-style:normal;font-display:swap;font-feature-settings:"ss01"}`,
  ),
  `@font-face{font-family:"TT Travels Next Fallback";src:local("Arial");ascent-override:70.84%;descent-override:23.61%;line-gap-override:0%;size-adjust:148.21%}`,
  `:root{--font-travels:"TT Travels Next","TT Travels Next Fallback"}`,
].join("\n");
