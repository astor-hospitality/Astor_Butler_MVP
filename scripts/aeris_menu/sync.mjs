#!/usr/bin/env node
/* Pulls the current AERIS menus from aeris.bar into files.

   node scripts/aeris_menu/sync.mjs [--out DIR] [--ocr] [--pages kitchen,barmenu,wineroom,bl]

   kitchen   Tilda catalog → kitchen.json + kitchen.md (dishes, sections, prices, descriptions)
   barmenu, wineroom, bl   pictures of the card → images/ (+ ocr/*.txt with --ocr and tesseract)
   manifest.json           when, from where, what changed (sha256 per file)

   Needs network access to aeris.bar and store.tildaapi.com. Exit 0 all pages read, 2 some page failed. */

import { fileURLToPath } from "node:url";
import { resolve, join } from "node:path";
import { mkdir, readFile, writeFile, access } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { discoverCatalog, fetchCatalog, normalizeCatalog, renderMarkdown } from "./lib/tilda.mjs";
import { downloadImage, menuImages, sha256 } from "./lib/pages.mjs";

const HERE = fileURLToPath(new URL(".", import.meta.url));
const SITE = "https://aeris.bar";
const PAGES = {
  kitchen: { kind: "catalog", path: "/kitchen", title: "Кухня" },
  barmenu: { kind: "images", path: "/barmenu", title: "Бар" },
  wineroom: { kind: "images", path: "/wineroom", title: "Винная карта" },
  bl: { kind: "images", path: "/bl", title: "Бизнес-ланч" },
};

function parse(argv) {
  const options = { out: resolve(HERE, "../../src/main/resources/menu/aeris/site"), ocr: false, pages: Object.keys(PAGES) };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--out") options.out = resolve(argv[++i] || "");
    else if (arg === "--ocr") options.ocr = true;
    else if (arg === "--pages") options.pages = String(argv[++i] || "").split(",").map((p) => p.trim()).filter(Boolean);
    else throw new Error("Unknown option " + arg);
  }
  for (const page of options.pages) if (!PAGES[page]) throw new Error("Unknown page " + page + "; known: " + Object.keys(PAGES).join(", "));
  return options;
}

async function fetchPage(doFetch, path) {
  const response = await doFetch(SITE + path, { headers: { "User-Agent": "AstorButler menu sync" }, signal: AbortSignal.timeout(20000) });
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${SITE + path}`);
  return response.text();
}

async function readManifest(out) {
  try {
    return JSON.parse(await readFile(join(out, "manifest.json"), "utf8"));
  } catch {
    return { files: {} };
  }
}

const exists = (path) => access(path).then(() => true, () => false);

function tesseractAvailable() {
  const probe = spawnSync("tesseract", ["--list-langs"], { encoding: "utf8" });
  if (probe.error || probe.status !== 0) return { ok: false, reason: "tesseract not installed" };
  const langs = (probe.stdout + probe.stderr).split("\n").map((l) => l.trim());
  if (!langs.includes("rus")) return { ok: false, reason: "tesseract has no 'rus' language (apt install tesseract-ocr-rus / brew install tesseract-lang)" };
  return { ok: true };
}

function ocr(imagePath) {
  const run = spawnSync("tesseract", [imagePath, "stdout", "-l", "rus+eng", "--psm", "4"], { encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
  if (run.error || run.status !== 0) throw new Error("tesseract failed: " + (run.error?.message || run.stderr.trim()));
  return run.stdout;
}

export async function sync({ doFetch = globalThis.fetch, now = () => new Date(), log = console.log, options }) {
  const fetchedAt = now().toISOString();
  const previous = await readManifest(options.out);
  const manifest = { venue: "AERIS", site: SITE, fetchedAt, pages: {}, files: { ...previous.files } };
  let failed = false;
  await mkdir(join(options.out, "images"), { recursive: true });

  for (const key of options.pages) {
    const page = PAGES[key];
    const url = SITE + page.path;
    try {
      const html = await fetchPage(doFetch, page.path);
      if (page.kind === "catalog") {
        const ids = discoverCatalog(html);
        if (!ids) throw new Error("no Tilda catalog block on the page; the menu may have moved to pictures");
        const raw = await fetchCatalog(doFetch, ids);
        const menu = normalizeCatalog(raw, { source: url, fetchedAt });
        const json = JSON.stringify(menu, null, 2) + "\n";
        await writeFile(join(options.out, "kitchen.json"), json);
        await writeFile(join(options.out, "kitchen.md"), renderMarkdown(menu));
        manifest.files["kitchen.json"] = { sha256: sha256(json), fetchedAt, source: url };
        manifest.pages[key] = { title: page.title, kind: page.kind, status: "ok", dishes: menu.dishCount, sections: menu.sections.length, catalog: ids };
        log(`${key}: ${menu.dishCount} dishes in ${menu.sections.length} sections`);
      } else {
        const images = menuImages(html);
        const files = [];
        for (const image of images) {
          const relative = "images/" + image.name;
          const target = join(options.out, relative);
          const known = manifest.files[relative];
          const downloaded = await downloadImage(doFetch, image.url);
          const changed = !known || known.sha256 !== downloaded.sha256 || !(await exists(target));
          if (changed) await writeFile(target, downloaded.bytes);
          manifest.files[relative] = { sha256: downloaded.sha256, fetchedAt: changed ? fetchedAt : known.fetchedAt, source: image.url, page: key, bytes: downloaded.bytes.length };
          files.push({ file: relative, changed });
        }
        manifest.pages[key] = { title: page.title, kind: page.kind, status: "ok", images: files };
        log(`${key}: ${files.length} pictures, ${files.filter((f) => f.changed).length} new or changed`);
      }
    } catch (error) {
      failed = true;
      manifest.pages[key] = { title: page.title, kind: page.kind, status: "failed", error: String(error.message || error) };
      log(`${key}: FAILED — ${error.message || error}`);
    }
  }

  if (options.ocr) {
    const available = tesseractAvailable();
    if (!available.ok) {
      log("ocr: skipped — " + available.reason);
      manifest.ocr = { status: "skipped", reason: available.reason };
    } else {
      await mkdir(join(options.out, "ocr"), { recursive: true });
      let done = 0;
      for (const [relative, info] of Object.entries(manifest.files)) {
        if (!relative.startsWith("images/") || !options.pages.includes(info.page)) continue;
        const textFile = "ocr/" + relative.slice("images/".length).replace(/\.[^.]+$/, "") + ".txt";
        const knownOcr = manifest.files[textFile];
        if (knownOcr && knownOcr.imageSha256 === info.sha256 && (await exists(join(options.out, textFile)))) continue;
        try {
          const text = ocr(join(options.out, relative));
          const body = `# НЕПРОВЕРЕННЫЙ OCR. Источник: ${info.source}\n# Сверять с картинкой перед использованием.\n\n${text}`;
          await writeFile(join(options.out, textFile), body);
          manifest.files[textFile] = { sha256: sha256(body), imageSha256: info.sha256, fetchedAt, page: info.page, verified: false };
          done++;
        } catch (error) {
          log(`ocr ${relative}: FAILED — ${error.message}`);
        }
      }
      manifest.ocr = { status: "ok", produced: done };
      log(`ocr: ${done} new text files (unverified)`);
    }
  }

  await writeFile(join(options.out, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
  return { manifest, failed };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const options = parse(process.argv.slice(2));
    const { failed } = await sync({ options });
    console.log("written to " + options.out);
    process.exit(failed ? 2 : 0);
  } catch (error) {
    console.error(error.message || error);
    process.exit(1);
  }
}
