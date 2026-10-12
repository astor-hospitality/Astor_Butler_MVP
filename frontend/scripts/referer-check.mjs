/**
 * Does the archive still play for a visitor of the real domain?
 *
 *   npm run check:referer                    # the local site, http://127.0.0.1:3001
 *   npm run check:referer https://c3ag.ru    # the live one, before and after a deploy
 *   npm run check:referer:build              # after `npm run build`: no network, no Disk
 *
 * Yandex's file host answers 403 to a request whose Referer names another site
 * (PR #72). It lets "localhost" and an empty Referer through, so a site that is
 * broken for every visitor looks fine on a developer's machine. Two things keep
 * the Referer away from Yandex, and the next edit of app/layout.tsx or
 * app/api/yadisk/route.ts can lose either:
 *   - the pages declare <meta name="referrer" content="same-origin">, which is
 *     what lets a <video> load an archive clip;
 *   - the poster redirect of /api/yadisk carries Referrer-Policy: no-referrer,
 *     which covers a poster wherever the page policy does not reach.
 *
 * The script takes one poster and one clip, works out from what the site
 * declares the Referer a browser on https://c3ag.ru/ sends for each, and asks
 * Yandex with exactly that. A 403 there is the bug of #72 back: exit 1.
 * Next to it it prints the table of #72, the same two links with no Referer and
 * with the site's. The 403 in that column is Yandex's rule, not a failure: it
 * is the reason the two guards exist.
 *
 * --build checks the build instead of a running site: every prerendered page
 * must carry the meta, the poster redirect must carry the header, and the same
 * table runs against a stand-in for Yandex (scripts/lib/fake-disk.mjs). It
 * needs no network and no secrets, so it can run in CI.
 *
 * Exit codes: 0 fine; 1 a guard is missing or a browser would get 403; 2 the
 * check could not run (no site, no build, Yandex did not answer).
 */
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { parseArgs } from "node:util";
import {
  DEFAULT_POLICY,
  DEFAULT_SITE,
  FRONTEND_DIR,
  YADISK_ROUTE,
  archivePath,
  describeError,
  follow,
  isAlive,
  metaPolicy,
  parsePolicy,
  readVideos,
  refererFor,
  request,
  retrying,
  routeUrl,
  siteBase,
  wholeNumber,
} from "./lib/probe.mjs";

const USAGE = `Usage: node scripts/referer-check.mjs [address of the site] [options]

  --base <url>      the site to ask (default: C3AG_BASE_URL or http://127.0.0.1:3001)
  --build           check the build in .next against a stand-in for Yandex, with no network
  --referer <url>   the page a visitor is on (default ${DEFAULT_SITE})
  --timeout <s>     seconds to wait for one answer (default 10)
  --retries <n>     more tries when a host does not answer (default 2)
  -h, --help        this text`;

/** A sample is looked for among this many records, in case the first ones are off the Disk. */
const CANDIDATES = 8;
const META = '<meta name="referrer" content="same-origin">';

function readOptions() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      base: { type: "string" },
      build: { type: "boolean", default: false },
      referer: { type: "string" },
      timeout: { type: "string", default: "10" },
      retries: { type: "string", default: "2" },
      help: { type: "boolean", short: "h", default: false },
    },
  });
  if (values.help) return null;
  let site;
  try {
    site = new URL(values.referer ?? DEFAULT_SITE).href;
  } catch {
    throw new Error(`--referer "${values.referer}" is not an address, expected something like ${DEFAULT_SITE}`);
  }
  return {
    build: values.build,
    base: values.build ? null : siteBase(values.base ?? positionals[0]),
    site,
    timeoutMs: wholeNumber(values.timeout, "timeout", 1) * 1000,
    retries: wholeNumber(values.retries, "retries", 0),
  };
}

const sent = (referer) => (referer ? `Referer: ${referer}` : "no Referer");

/**
 * The table against the site at `base`. `disk` names who answers for the files
 * in the report. `strict` also demands the exact two declarations of #72.
 * Returns the exit code.
 */
async function refererTable({ base, site, disk, strict, timeoutMs, retries }) {
  // A host that does not answer, or answers 5xx, gets another try. A 403 does not: it is an answer.
  const patient = (attempt) => retrying(attempt, { retries, again: (r) => r.status >= 500 });
  const ask = async (link, how, range) => {
    try {
      return await patient(() => follow(link, { ...how, page: site, range, timeoutMs }));
    } catch (error) {
      const referer = how.referer ?? refererFor(how.policy, site, link);
      return { status: 0, referer, error: `${error.host ?? "the host"} did not answer: ${describeError(error)}` };
    }
  };

  // ── What the site declares ──
  let page;
  try {
    page = await patient(() => request(`${base}/`, { body: true, timeoutMs }));
  } catch (error) {
    console.error(`The site does not answer at ${base}: ${describeError(error)}`);
    console.error("Start it (npm run dev) or pass its address: npm run check:referer https://c3ag.ru");
    return 2;
  }
  if (page.status !== 200) {
    console.error(`${base}/ answered ${page.status}, expected the home page.`);
    return 2;
  }
  const pageMeta = metaPolicy(page.text);
  const pageHeader = parsePolicy(page.headers["referrer-policy"]);
  const pagePolicy = pageMeta ?? pageHeader ?? DEFAULT_POLICY;

  // ── One poster and one clip that are there at all ──
  const records = readVideos().slice(0, CANDIDATES);
  const tried = [];
  let poster = null;
  for (const record of records) {
    const path = archivePath(record.poster, "yadisk-poster:");
    if (!path) continue;
    try {
      const redirect = await patient(() => request(routeUrl(base, path, { poster: true }), { timeoutMs }));
      const location = redirect.headers.location;
      if (redirect.status !== 302 || !location) {
        tried.push(`poster ${path}: ${YADISK_ROUTE} answered ${redirect.status}, expected a 302 with a link`);
        continue;
      }
      const link = new URL(location, base).href;
      const plain = await ask(link, { referer: "" });
      if (!isAlive(plain.status)) {
        tried.push(`poster ${path}: ${plain.error ?? `${disk} answered ${plain.status} even without a Referer`}`);
        continue;
      }
      poster = { path, link, plain, policy: parsePolicy(redirect.headers["referrer-policy"]) };
      break;
    } catch (error) {
      tried.push(`poster ${path}: the site did not answer: ${describeError(error)}`);
    }
  }
  let clip = null;
  for (const record of records) {
    const path = archivePath(record.src, "yadisk:");
    if (!path) continue;
    try {
      const answer = await patient(() => request(routeUrl(base, path, { resolve: true }), { body: true, timeoutMs }));
      let link = null;
      try {
        link = JSON.parse(answer.text).url ?? null;
      } catch {
        // not JSON
      }
      if (answer.status !== 200 || !link) {
        tried.push(`clip ${path}: ${YADISK_ROUTE} answered ${answer.status}, expected 200 with a link`);
        continue;
      }
      const plain = await ask(link, { referer: "" }, "bytes=0-0");
      if (!isAlive(plain.status)) {
        tried.push(`clip ${path}: ${plain.error ?? `${disk} answered ${plain.status} even without a Referer`}`);
        continue;
      }
      clip = { path, link, plain };
      break;
    } catch (error) {
      tried.push(`clip ${path}: the site did not answer: ${describeError(error)}`);
    }
  }
  if (!poster || !clip) {
    console.error(
      `Could not get a working ${!poster ? "poster" : "clip"} link through ${base}${YADISK_ROUTE}, so there is nothing to compare:`,
    );
    for (const line of tried) console.error(`  ${line}`);
    return 2;
  }

  console.log("What the site declares");
  const declared = [
    pageMeta ? `<meta name="referrer" content="${pageMeta}">` : 'no <meta name="referrer">',
    ...(pageHeader ? [`Referrer-Policy: ${pageHeader}`] : []),
  ];
  console.log(`  page /            ${declared.join(", ")}`);
  console.log(`  poster redirect   ${poster.policy ? `Referrer-Policy: ${poster.policy}` : "no Referrer-Policy"}`);

  // ── The table of #72, and the one request that matters in each row ──
  // A poster is judged by its redirect alone, under the policy a browser has
  // when the page says nothing: that is the case the header is there for.
  const rows = [
    { name: "poster", item: poster, policy: poster.policy ?? DEFAULT_POLICY, range: undefined },
    { name: "clip", item: clip, policy: pagePolicy, range: "bytes=0-0" },
  ];
  for (const row of rows) {
    row.forced = await ask(row.item.link, { referer: site }, row.range);
    // With no Referer to send, a browser's request is the one already made.
    row.browser = refererFor(row.policy, site, row.item.link)
      ? await ask(row.item.link, { policy: row.policy }, row.range)
      : row.item.plain;
  }

  const forcedTitle = sent(site);
  const line = (name, plain, forced, browser) =>
    console.log(`  ${name.padEnd(8)}${plain.padEnd(13)}${forced.padEnd(forcedTitle.length + 3)}${browser}`);
  const status = (answer) => String(answer.status || "none");
  console.log(`\nWhat ${disk} answers`);
  line("", "no Referer", forcedTitle, "as a browser sends it");
  for (const row of rows) {
    line(row.name, status(row.item.plain), status(row.forced), `${status(row.browser)} (${sent(row.browser.referer)})`);
  }
  console.log(`\n  the poster is of ${poster.path}`);
  console.log(`  the clip is ${clip.path}`);

  // ── The verdict: one line per guard ──
  const failures = new Map();
  const unknown = [];
  for (const row of rows) {
    const answer = row.browser;
    if (isAlive(answer.status)) continue;
    if (answer.error) {
      unknown.push(`${row.name}: ${answer.error}`);
    } else if (answer.status >= 500) {
      unknown.push(`${row.name}: ${disk} answered ${answer.status}, which is its own trouble. Run again.`);
    } else if (row.name === "clip") {
      failures.set(
        "meta",
        `A <video> on the page sends "${sent(answer.referer)}" and ${disk} answers ${answer.status}: ` +
          `no archive clip plays for a visitor. The page must declare ${META}, ` +
          'which is `referrer: "same-origin"` in app/layout.tsx.',
      );
    } else {
      failures.set(
        "header",
        `The poster redirect lets "${sent(answer.referer)}" through and ${disk} answers ${answer.status}. ` +
          `The 302 of ${YADISK_ROUTE}?kind=poster must carry Referrer-Policy: no-referrer (app/api/yadisk/route.ts).`,
      );
    }
  }
  if (strict && pageMeta !== "same-origin" && !failures.has("meta")) {
    failures.set("meta", `The home page must carry ${META}; it has ${pageMeta ? `"${pageMeta}"` : "none"}.`);
  }
  if (strict && poster.policy !== "no-referrer" && !failures.has("header")) {
    failures.set(
      "header",
      `The poster redirect must carry Referrer-Policy: no-referrer; it has ${poster.policy ? `"${poster.policy}"` : "none"}.`,
    );
  }

  console.log("");
  if (rows.some((row) => isAlive(row.forced.status))) {
    console.log(
      `Note: ${disk} let "${forcedTitle}" through this time, so the guards are not what makes it work today. Keep them.`,
    );
  }
  if (failures.size) {
    console.log("FAILED, see PR #72:");
    for (const text of failures.values()) console.log(`  ${text}`);
    return 1;
  }
  if (unknown.length) {
    console.log("Not known, so not checked:");
    for (const text of unknown) console.log(`  ${text}`);
    return 2;
  }
  console.log("OK: with the Referer a browser sends, the poster and the clip both come.");
  return 0;
}

/** Every prerendered page of the build must carry the meta. Returns the exit code. */
function builtPages() {
  const dir = join(FRONTEND_DIR, ".next", "server", "app");
  if (!existsSync(join(FRONTEND_DIR, ".next", "BUILD_ID")) || !existsSync(dir)) {
    console.error("There is no build in .next. Run `npm run build` first.");
    return 2;
  }
  const pages = readdirSync(dir, { recursive: true })
    .map(String)
    .filter((name) => name.endsWith(".html"))
    .sort();
  if (pages.length === 0) {
    console.error("Found no prerendered pages in .next/server/app, so the check sees nothing. Has the build changed shape?");
    return 2;
  }
  const without = pages.filter((name) => metaPolicy(readFileSync(join(dir, name), "utf8")) !== "same-origin");
  console.log("Prerendered pages in .next/server/app");
  console.log(`  ${pages.length - without.length} of ${pages.length} carry ${META}`);
  if (without.length === 0) return 0;
  for (const name of without) console.log(`  without it: ${name.replaceAll("\\", "/")}`);
  console.log(`\nFAILED, see PR #72:\n  Every page must carry ${META}, which is \`referrer: "same-origin"\` in app/layout.tsx.`);
  return 1;
}

async function main() {
  let options;
  try {
    options = readOptions();
  } catch (error) {
    console.error(`${error.message}\n\n${USAGE}`);
    return 2;
  }
  if (!options) {
    console.log(USAGE);
    return 0;
  }

  if (!options.build) {
    console.log(`Referer check of ${options.base}, as a visitor of ${options.site} sees it\n`);
    return refererTable({ ...options, disk: "Yandex", strict: false });
  }

  console.log("Referer check of the build in .next, against a stand-in for Yandex (no network)\n");
  const pagesCode = builtPages();
  if (pagesCode === 2) return 2;
  const { startBuiltSite, startFakeDisk } = await import("./lib/fake-disk.mjs");
  const fakeDisk = await startFakeDisk();
  const builtSite = await startBuiltSite(fakeDisk.url);
  console.log("");
  // The stand-in speaks plain http, and by default a browser sends no Referer
  // from an https page to an http address. So the page is taken as http here:
  // otherwise a missing guard would go unnoticed.
  const tableCode = await refererTable({
    base: builtSite.url,
    site: "http://c3ag.ru/",
    disk: "the stand-in",
    strict: true,
    timeoutMs: options.timeoutMs,
    retries: 0,
  });
  builtSite.close();
  fakeDisk.close();
  return pagesCode === 1 || tableCode === 1 ? 1 : Math.max(pagesCode, tableCode);
}

const code = await main();
process.exitCode = code;
// The build check serves the site from this process, and Next keeps it alive.
if (process.argv.includes("--build")) process.stdout.write("", () => process.exit(code));
