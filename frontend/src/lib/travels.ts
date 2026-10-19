/**
 * The measures of TT Travels Next Black, in thousandths of an em: for each capital, digit and mark the site sets in a
 * massive word, its advance and the empty space on its left and on its right. Made from the font file itself
 * (fonts/TTTravelsNextTrial-Black.woff in the media); when the licensed file replaces the trial one, make it again.
 * With these a word can be drawn in an SVG exactly from edge to edge without waiting for the browser to measure it.
 */
const GLYPHS: Record<string, [advance: number, left: number, right: number]> = {
  "A": [904, 0, 0],
  "B": [901, 65, 20],
  "C": [1006, 40, 30],
  "D": [915, 65, 40],
  "E": [830, 65, 45],
  "F": [800, 65, 35],
  "G": [1005, 40, 35],
  "H": [960, 65, 65],
  "I": [730, 45, 45],
  "J": [805, 20, 55],
  "K": [860, 65, 0],
  "L": [765, 65, 20],
  "M": [1110, 65, 65],
  "N": [970, 65, 65],
  "O": [1030, 40, 40],
  "P": [865, 65, 30],
  "Q": [1030, 40, 40],
  "R": [885, 65, 10],
  "S": [935, 45, 40],
  "T": [770, 0, 0],
  "U": [935, 55, 55],
  "V": [904, 5, 5],
  "W": [1160, 15, 15],
  "X": [910, 5, 5],
  "Y": [842, -5, -5],
  "Z": [845, 45, 40],
  "А": [904, 0, 0],
  "Б": [875, 65, 25],
  "В": [901, 65, 20],
  "Г": [750, 65, 25],
  "Д": [1010, 15, 20],
  "Е": [830, 65, 45],
  "Ё": [830, 65, 45],
  "Ж": [1270, 10, 10],
  "З": [851, 15, 20],
  "И": [970, 65, 65],
  "Й": [970, 65, 65],
  "К": [860, 65, 0],
  "Л": [990, 15, 65],
  "М": [1110, 65, 65],
  "Н": [960, 65, 65],
  "О": [1030, 40, 40],
  "П": [945, 65, 65],
  "Р": [865, 65, 30],
  "С": [1006, 40, 30],
  "Т": [770, 0, 0],
  "У": [827, -5, -10],
  "Ф": [1120, 35, 35],
  "Х": [910, 5, 5],
  "Ц": [995, 65, 20],
  "Ч": [895, 40, 65],
  "Ш": [1220, 65, 65],
  "Щ": [1280, 65, 20],
  "Ъ": [960, 5, 25],
  "Ы": [1170, 65, 65],
  "Ь": [860, 65, 25],
  "Э": [1011, 35, 40],
  "Ю": [1305, 65, 45],
  "Я": [870, 5, 65],
  "0": [910, 45, 45],
  "1": [760, 50, 20],
  "2": [885, 45, 35],
  "3": [865, 35, 25],
  "4": [875, 35, 20],
  "5": [860, 40, 30],
  "6": [891, 40, 35],
  "7": [775, 30, 25],
  "8": [880, 30, 30],
  "9": [891, 35, 40],
  " ": [260, 0, 0],
  "-": [535, 35, 35],
  "–": [730, 35, 35],
  ".": [295, 25, 25],
  ",": [295, 20, 25],
  "?": [770, 25, 25],
  "!": [335, 45, 45],
  "«": [649, 10, 20],
  "»": [649, 20, 10],
  ":": [295, 25, 25],
  "/": [581, 0, 0],
  "&": [850, 35, -20],
};

/** Capitals are this tall. */
export const CAP_HEIGHT = 700;
/** Massive words are set a little tight. */
export const TRACKING = -20;

const glyph = (ch: string) => GLYPHS[ch] ?? GLYPHS["Н"];

/** Where the ink of a line starts, counted from the point the line is set at. */
export const inkLeft = (line: string) => glyph(line[0] ?? "Н")[1];

/** How wide the ink of a line is, from its first stroke to its last. */
export function inkWidth(line: string): number {
  const chars = [...line];
  if (chars.length === 0) return 0;
  const advances = chars.reduce((sum, ch) => sum + glyph(ch)[0], 0) + TRACKING * (chars.length - 1);
  return advances - glyph(chars[0])[1] - glyph(chars[chars.length - 1])[2];
}
