#!/usr/bin/env node
/**
 * How to put the site's media into S3: prints the files with the Content-Type and Cache-Control each one needs,
 * the upload commands and the CORS rule of the bucket. It uploads nothing, and it neither holds nor reads any key:
 * the commands it prints run with the operator's own aws / s3cmd profile.
 *
 *   node scripts/media-upload-plan.mjs [--tool aws|s3cmd] [--dir public/media]
 *
 * The bucket, the folder inside it, the S3 endpoint and the site origin come from the operator's environment
 * (or are left as <placeholders> in the commands):
 *   C3_MEDIA_BUCKET    e.g. c3ag-media
 *   C3_MEDIA_PREFIX    e.g. site       (the folder; NEXT_PUBLIC_MEDIA_BASE_URL then ends with /site); '' = the root
 *   C3_S3_ENDPOINT     e.g. https://s3.cloud.ru               (the S3 API, for the upload)
 *   C3_MEDIA_PUBLIC    e.g. https://c3ag-media.s3.cloud.ru    (how a browser reads the bucket: not always endpoint/bucket)
 *   C3_SITE_ORIGIN     e.g. https://c3ag.ru   (for CORS)
 * c3ag.ru today (2026-10-29): Cloud.ru, bucket c3ag-media, files in its root, see DEPLOY_C3_SITE.md.
 */
import { existsSync, readdirSync, statSync } from "node:fs";
import { dirname, extname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const FRONTEND = resolve(dirname(fileURLToPath(import.meta.url)), "..");

const { values: opts } = parseArgs({
  options: {
    dir: { type: "string", default: join(FRONTEND, "public", "media") },
    tool: { type: "string", default: "aws" },
  },
});

/**
 * Every address the site uses carries `?v=<hash of the file>` (src/lib/media.ts), so a replaced file gets a new address:
 * the files can be cached for a year. A file replaced without a new manifest would stay old in caches – so after a
 * replacement always run `npm run media:manifest`, commit and deploy.
 */
const CACHE = "public, max-age=31536000, immutable";
const TYPES = {
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".webp": "image/webp",
  ".avif": "image/avif",
  ".gif": "image/gif",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".mp4": "video/mp4",
  ".webm": "video/webm",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
};

const bucket = process.env.C3_MEDIA_BUCKET || "<bucket>";
const prefix = (process.env.C3_MEDIA_PREFIX ?? "<prefix>").replace(/^\/+|\/+$/g, "");
const endpoint = process.env.C3_S3_ENDPOINT || "<s3-endpoint>";
const origin = process.env.C3_SITE_ORIGIN || "https://c3ag.ru";
const publicBase = (process.env.C3_MEDIA_PUBLIC || "<public address of the bucket>").replace(/\/+$/, "");
const target = `s3://${bucket}${prefix ? `/${prefix}` : ""}/`;

const root = resolve(opts.dir);
if (!existsSync(root)) {
  console.error(`No media folder at ${root}.`);
  process.exit(1);
}
const walk = (dir) =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(join(dir, e.name)) : e.isFile() && !e.name.startsWith(".") ? [join(dir, e.name)] : []));
const files = ["images", "videos", "fonts"].filter((r) => existsSync(join(root, r))).flatMap((r) => walk(join(root, r))).sort();

const unknown = files.filter((f) => !TYPES[extname(f).toLowerCase()]);
if (unknown.length) {
  console.error(`No Content-Type known for: ${unknown.map((f) => relative(root, f)).join(", ")}. Add it to TYPES.`);
  process.exit(1);
}

console.log(`# ${files.length} files from ${relative(FRONTEND, root).split(sep).join("/") || "."} -> ${target}\n`);
console.log("# key | Content-Type | Cache-Control | bytes");
for (const file of files) {
  const key = relative(root, file).split(sep).join("/");
  console.log(`${key} | ${TYPES[extname(file).toLowerCase()]} | ${CACHE} | ${statSync(file).size}`);
}

const local = relative(process.cwd(), root).split(sep).join("/") || ".";
const exts = [...new Set(files.map((f) => extname(f).toLowerCase()))].sort();

console.log("\n# Upload: one command per kind of file, so each gets its own Content-Type.");
console.log("# Public read: either keep --acl public-read / --acl-public, or drop it and open the prefix by a bucket policy or a CDN.\n");
for (const ext of exts) {
  const type = TYPES[ext];
  if (opts.tool === "s3cmd") {
    console.log(
      `s3cmd sync --acl-public --no-guess-mime-type --mime-type="${type}" --add-header="Cache-Control: ${CACHE}" --exclude "*" --include "*${ext}" ${local}/ ${target}`,
    );
  } else {
    console.log(
      `aws s3 sync ${local}/ ${target} --endpoint-url ${endpoint} --exclude "*" --include "*${ext}" --content-type "${type}" --cache-control "${CACHE}" --acl public-read`,
    );
  }
}

const cors = {
  CORSRules: [{ AllowedOrigins: [origin, origin.replace("://", "://www.")], AllowedMethods: ["GET", "HEAD"], AllowedHeaders: ["*"], MaxAgeSeconds: 86400 }],
};
console.log("\n# CORS of the bucket: the fonts (and the mask of the Clio star) are read by the browser in CORS mode.");
console.log("# put-bucket-cors replaces every rule of the bucket: read the current ones first (get-bucket-cors) and add this one.");
console.log(`# cors.json:\n${JSON.stringify(cors, null, 2)}`);
if (opts.tool === "s3cmd") {
  console.log("# s3cmd takes the same rules as XML: s3cmd setcors cors.xml s3://" + bucket);
} else {
  console.log(`aws s3api put-bucket-cors --bucket ${bucket} --cors-configuration file://cors.json --endpoint-url ${endpoint}`);
}
console.log(`\n# Then build with NEXT_PUBLIC_MEDIA_BASE_URL=${publicBase}${prefix ? `/${prefix}` : ""}`);
