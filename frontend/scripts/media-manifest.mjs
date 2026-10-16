#!/usr/bin/env node
/**
 * The list of the site's media, made from the local media folder.
 *
 * Photos, video and fonts are never committed (they live in S3, see DEPLOY_C3_SITE.md), yet the pages need the size
 * of every picture at build time (next/image reserves the room before the file arrives). So this script reads the
 * local copy of the media, `public/media/` (git-ignored), and writes `content/media-manifest.ts`, which IS committed:
 * for every file its path relative to the media root, its size in bytes, a short hash of its content (`v`, added to the
 * address as `?v=…`, so a replaced file gets a new address and nobody is shown the old one from a cache) and, for a
 * picture, its width and height.
 *
 *   node scripts/media-manifest.mjs              write content/media-manifest.ts from public/media
 *   node scripts/media-manifest.mjs --check      fail if the committed manifest differs from public/media
 *   node scripts/media-manifest.mjs --markdown   print the list as a Markdown table (for DEPLOY_C3_SITE.md)
 *   node scripts/media-manifest.mjs --dir <path> read the media from another folder
 *
 * No dependencies: the sizes are read from the file headers (PNG, JPEG, WebP, GIF).
 */
import { createHash } from "node:crypto";
import { readFileSync, readdirSync, statSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const FRONTEND = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(FRONTEND, "content", "media-manifest.ts");
/** Only these folders of the media root belong to the site. */
const ROOTS = ["images", "videos", "fonts"];

const { values: opts } = parseArgs({
  options: {
    dir: { type: "string", default: join(FRONTEND, "public", "media") },
    check: { type: "boolean", default: false },
    markdown: { type: "boolean", default: false },
  },
});

/** Width and height from the header of a picture, or null for a file that is not one. */
export function imageSize(buf) {
  // PNG: the IHDR chunk comes first
  if (buf.length > 24 && buf.readUInt32BE(0) === 0x89504e47) {
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
  }
  // GIF
  if (buf.length > 10 && buf.toString("ascii", 0, 3) === "GIF") {
    return { width: buf.readUInt16LE(6), height: buf.readUInt16LE(8) };
  }
  // WebP: RIFF....WEBP and one of three first chunks
  if (buf.length > 30 && buf.toString("ascii", 0, 4) === "RIFF" && buf.toString("ascii", 8, 12) === "WEBP") {
    const chunk = buf.toString("ascii", 12, 16);
    if (chunk === "VP8X") return { width: 1 + buf.readUIntLE(24, 3), height: 1 + buf.readUIntLE(27, 3) };
    if (chunk === "VP8 ") return { width: buf.readUInt16LE(26) & 0x3fff, height: buf.readUInt16LE(28) & 0x3fff };
    if (chunk === "VP8L") {
      const b = buf.subarray(21, 25);
      return { width: 1 + (b[0] | ((b[1] & 0x3f) << 8)), height: 1 + ((b[1] >> 6) | (b[2] << 2) | ((b[3] & 0x0f) << 10)) };
    }
    return null;
  }
  // JPEG: walk the markers up to the first frame header (SOF0–SOF15 but DHT, JPG and DAC)
  if (buf.length > 4 && buf[0] === 0xff && buf[1] === 0xd8) {
    let at = 2;
    while (at + 9 < buf.length) {
      if (buf[at] !== 0xff) return null;
      const marker = buf[at + 1];
      if (marker === 0xff) { at += 1; continue; }
      const length = buf.readUInt16BE(at + 2);
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { width: buf.readUInt16BE(at + 7), height: buf.readUInt16BE(at + 5) };
      }
      at += 2 + length;
    }
  }
  return null;
}

function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return walk(path);
    return entry.isFile() && !entry.name.startsWith(".") ? [path] : [];
  });
}

function collect(root) {
  if (!existsSync(root)) {
    console.error(`No media folder at ${root}. Put the media there first (see DEPLOY_C3_SITE.md).`);
    process.exit(1);
  }
  const files = ROOTS.filter((r) => existsSync(join(root, r))).flatMap((r) => walk(join(root, r)));
  const entries = {};
  for (const file of files.sort()) {
    const key = relative(root, file).split(sep).join("/");
    const buf = readFileSync(file);
    const entry = { bytes: statSync(file).size, v: createHash("sha256").update(buf).digest("hex").slice(0, 10) };
    if (key.startsWith("images/")) {
      const size = imageSize(buf);
      if (size) Object.assign(entry, size);
      else if (!key.endsWith(".ico")) console.warn(`warning: no picture size in ${key}`);
    }
    entries[key] = entry;
  }
  return entries;
}

function render(entries) {
  const lines = Object.entries(entries).map(([key, e]) => {
    const size = e.width ? `, width: ${e.width}, height: ${e.height}` : "";
    return `  ${JSON.stringify(key)}: { bytes: ${e.bytes}, v: ${JSON.stringify(e.v)}${size} },`;
  });
  return `/**
 * Made by scripts/media-manifest.mjs from the local media folder (public/media): do not edit by hand,
 * run \`npm run media:manifest\` again after a file is added or replaced, and upload the same files to S3.
 * Every path is relative to the media root (NEXT_PUBLIC_MEDIA_BASE_URL); \`v\` is a short hash of the file,
 * \`width\`/\`height\` are the size of a picture in pixels.
 */
export interface MediaEntry {
  bytes: number;
  v: string;
  width?: number;
  height?: number;
}

export const mediaManifest = {
${lines.join("\n")}
} as const satisfies Record<string, MediaEntry>;

export type MediaPath = keyof typeof mediaManifest;
`;
}

/** A size for people: "2,9 МБ" in the Russian table of DEPLOY_C3_SITE.md, "2.9 MB" in the console. */
function human(bytes, ru = false) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1).replace(".", ru ? "," : ".")} ${ru ? "МБ" : "MB"}`;
  return `${Math.max(1, Math.round(bytes / 1024))} ${ru ? "КБ" : "KB"}`;
}

const entries = collect(resolve(opts.dir));
const total = Object.values(entries).reduce((sum, e) => sum + e.bytes, 0);

if (opts.markdown) {
  console.log("| Ключ | Размер | Пиксели |");
  console.log("|---|---:|---|");
  for (const [key, e] of Object.entries(entries)) {
    console.log(`| \`${key}\` | ${human(e.bytes, true)} | ${e.width ? `${e.width} × ${e.height}` : ""} |`);
  }
  console.log(`| **Всего: ${Object.keys(entries).length} файлов** | **${human(total, true)}** | |`);
} else if (opts.check) {
  const now = existsSync(OUT) ? readFileSync(OUT, "utf8") : "";
  if (now !== render(entries)) {
    console.error("content/media-manifest.ts does not match public/media: run `npm run media:manifest`.");
    process.exit(1);
  }
  console.log(`media-manifest is up to date: ${Object.keys(entries).length} files, ${human(total)}.`);
} else {
  writeFileSync(OUT, render(entries));
  console.log(`Wrote ${relative(FRONTEND, OUT)}: ${Object.keys(entries).length} files, ${human(total)}.`);
}
