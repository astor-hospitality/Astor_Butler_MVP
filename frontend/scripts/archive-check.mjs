/**
 * Is every work of the archive still on the Disk?
 *
 *   npm run check:archive                    # the local site, http://127.0.0.1:3001
 *   npm run check:archive https://c3ag.ru    # the live one, before a deploy
 *   node scripts/archive-check.mjs --base https://c3ag.ru --concurrency 2
 *
 * Every record of data/videos.json that plays from the Disk goes the way the
 * player takes it: /api/yadisk?path=…&resolve=1 for a signed link, then the
 * first byte of the file behind that link. Anything but 200 or 206 is a dead
 * player on the site; the script lists those and exits with 1.
 *
 * A file that leaves the Disk takes its work off the site without a word, and
 * nobody opens all of them by hand. Run this before a deploy.
 *
 * The Disk answers a signed link with a redirect to the machine that holds the
 * file, a different one each time. Where the network does not reach those
 * machines, --no-bytes stops at that redirect: it still finds a file the Disk
 * has lost, and it does not wait for hosts that will never answer.
 *
 * It is polite to Yandex: four requests at a time, one byte per film, a pause
 * before each of the two retries. The address of the site also comes from
 * C3AG_BASE_URL.
 *
 * Exit codes: 0 every path answered 200/206; 1 at least one did not; 2 none is
 * known to be dead, but the check could not finish (the site or a file host
 * did not answer from this machine, a wrong argument).
 */
import { parseArgs } from "node:util";
import {
  YADISK_ROUTE,
  archivePath,
  describeError,
  follow,
  isAlive,
  pool,
  readVideos,
  request,
  retrying,
  routeUrl,
  siteBase,
  wholeNumber,
} from "./lib/probe.mjs";

const USAGE = `Usage: node scripts/archive-check.mjs [address of the site] [options]

  --base <url>        the site to ask (default: C3AG_BASE_URL or http://127.0.0.1:3001)
  --concurrency <n>   requests at a time (default 4)
  --timeout <s>       seconds to wait for one answer (default 10)
  --retries <n>       more tries for a path that did not answer 200/206 (default 2)
  --no-bytes          stop at the Disk's redirect to the machine that holds the file
  -h, --help          this text`;

function readOptions() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      base: { type: "string" },
      concurrency: { type: "string", default: "4" },
      timeout: { type: "string", default: "10" },
      retries: { type: "string", default: "2" },
      "no-bytes": { type: "boolean", default: false },
      help: { type: "boolean", short: "h", default: false },
    },
  });
  if (values.help) return null;
  return {
    base: siteBase(values.base ?? positionals[0]),
    concurrency: wholeNumber(values.concurrency, "concurrency", 1),
    timeoutMs: wholeNumber(values.timeout, "timeout", 1) * 1000,
    retries: wholeNumber(values.retries, "retries", 0),
    bytes: !values["no-bytes"],
  };
}

const isRedirect = (status) => status >= 300 && status < 400;

/**
 * One path, one try. `state` is one of:
 *   "alive"    the file came (or, with --no-bytes, the Disk pointed at it);
 *   "missing"  the route says the Disk has no such file;
 *   "refused"  the Disk gave a link and then answered with an error for it;
 *   "silent"   somebody did not answer, so nothing is known about the file.
 */
async function check(path, options) {
  let link;
  try {
    const answer = await request(routeUrl(options.base, path, { resolve: true }), {
      body: true,
      timeoutMs: options.timeoutMs,
    });
    let json = null;
    try {
      json = JSON.parse(answer.text);
    } catch {
      // not JSON: a proxy's error page, most likely
    }
    if (answer.status !== 200 || !json?.url) {
      const said = json?.error ? `: ${json.error}` : "";
      return {
        // 404 is the route's word for "not in the archive". Anything else is the site's own trouble.
        state: answer.status === 404 ? "missing" : "silent",
        status: answer.status,
        note: `${YADISK_ROUTE} answered ${answer.status}${said}`,
      };
    }
    link = json.url;
  } catch (error) {
    return { state: "silent", status: "no answer", note: `the site did not answer: ${describeError(error)}` };
  }

  try {
    // No Referer here: this check is about the file, the Referer has its own (check:referer).
    const file = await follow(link, {
      referer: "",
      range: "bytes=0-0",
      timeoutMs: options.timeoutMs,
      redirects: options.bytes,
    });
    if (isAlive(file.status) || (!options.bytes && isRedirect(file.status))) {
      return { state: "alive", status: file.status, note: "" };
    }
    const said = file.text ? `: ${file.text}` : "";
    return { state: "refused", status: file.status, note: `${file.host} answered ${file.status}${said}` };
  } catch (error) {
    return {
      state: "silent",
      status: "no answer",
      note: `${error.host ?? "the file host"} did not answer: ${describeError(error)}`,
    };
  }
}

function list(title, items, advice, limit = Infinity) {
  if (items.length === 0) return;
  console.log(`\n${title} (${items.length}):`);
  for (const item of items.slice(0, limit)) {
    console.log(`  ${item.status}  ${item.path}`);
    console.log(`       ${item.id}. ${item.note}`);
  }
  if (items.length > limit) console.log(`  … and ${items.length - limit} more`);
  console.log(`  ${advice}`);
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

  const records = readVideos();
  // One check per path: a record's master link (originalUrl) repeats its src.
  const byPath = new Map();
  let onDisk = 0;
  for (const record of records) {
    const path = archivePath(record.src, "yadisk:");
    if (!path) continue;
    onDisk++;
    if (!byPath.has(path)) byPath.set(path, record.id);
  }
  const paths = [...byPath.keys()];
  const elsewhere = records.length - onDisk;

  console.log(`Archive check through ${options.base}${YADISK_ROUTE}`);
  console.log(
    `${records.length} records in data/videos.json, ${paths.length} paths on the Disk` +
      (elsewhere ? `, ${elsewhere} played from elsewhere and skipped` : "") +
      `; ${options.concurrency} at a time` +
      (options.bytes ? "" : "; up to the Disk's redirect, the first byte is not asked for"),
  );

  // Is the site there at all? Without a path the route answers 400, and nothing else does.
  try {
    const hello = await request(`${options.base}${YADISK_ROUTE}`, { timeoutMs: options.timeoutMs });
    if (hello.status !== 400) {
      console.error(
        `\n${YADISK_ROUTE} answered ${hello.status} to a request without a path, expected 400. ` +
          `Is ${options.base} the C3AG site?`,
      );
      return 2;
    }
  } catch (error) {
    console.error(`\nThe site does not answer at ${options.base}: ${describeError(error)}`);
    console.error("Start it (npm run dev) or pass its address: npm run check:archive https://c3ag.ru");
    return 2;
  }

  let done = 0;
  // A counter in a terminal, a line every fifty paths in a log.
  const live = process.stderr.isTTY;
  const results = await pool(paths, options.concurrency, async (path) => {
    // The route says 404 for a missing file and for a Disk that is refusing the
    // server alike, and the Disk picks another machine on every try, so
    // anything short of the file gets its retries.
    const result = await retrying(() => check(path, options), {
      retries: options.retries,
      again: (r) => r.state !== "alive",
    });
    done++;
    if (live) process.stderr.write(`\r  ${done}/${paths.length}`);
    else if (done % 50 === 0 && done < paths.length) console.error(`  ${done}/${paths.length}`);
    return { path, id: byPath.get(path), ...result };
  });
  if (live) process.stderr.write("\r" + " ".repeat(24) + "\r");

  const tally = new Map();
  for (const result of results) tally.set(result.status, (tally.get(result.status) ?? 0) + 1);
  console.log("");
  for (const [status, count] of [...tally].sort((a, b) => b[1] - a[1])) {
    console.log(`  ${String(count).padStart(4)}  ${status === "no answer" ? "got no answer" : `answered ${status}`}`);
  }

  const of = (state) => results.filter((result) => result.state === state);
  const missing = of("missing");
  const refused = of("refused");
  const silent = of("silent");
  if (missing.length + refused.length + silent.length === 0) {
    console.log(
      options.bytes
        ? `\nAll ${paths.length} paths are alive.`
        : `\nThe Disk hands over all ${paths.length} files. The first byte was not asked for (--no-bytes).`,
    );
    return 0;
  }

  list(
    "Missing from the Disk",
    missing,
    "Bring the file back to the Disk under the same path, or take the record out of data/videos.json.",
  );
  list(
    "On the Disk, but the Disk did not give the file",
    refused,
    "Open these works on the site. If they do not play there either, it is the same choice as for a missing file.",
  );
  list(
    "No answer, so not known",
    silent,
    "That says nothing about the files: run again, run from another network, " +
      "or pass --no-bytes to stop at the Disk's redirect.",
    // A network that does not reach the file hosts fails the same way for half the archive.
    10,
  );

  const dead = missing.length + refused.length;
  console.log(
    `\n${dead} of ${paths.length} did not come` + (silent.length ? `, ${silent.length} not known.` : "."),
  );
  if (missing.length >= Math.max(5, paths.length / 5)) {
    console.log(
      "So many missing at once is rarely missing files: the Disk may be refusing the server, " +
        `and ${YADISK_ROUTE} answers 404 for that too. Run again later with --concurrency 1.`,
    );
  }
  return dead ? 1 : 2;
}

process.exitCode = await main();
