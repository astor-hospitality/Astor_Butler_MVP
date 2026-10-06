import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createClient, SourceFailure } from "../lib/http.mjs";
import { licensedResults, licensedSources, LICENSE_ACK, manualResults } from "../lib/providers.mjs";

const fixture = async (name) => readFile(new URL("./fixtures/" + name, import.meta.url), "utf8");
const reply = (status, body, headers = {}) => ({ ok: status >= 200 && status < 300, status,
  headers: new Headers(headers), text: async () => body });
const timeout = () => { throw Object.assign(new Error("timed out"), { name: "TimeoutError" }); };

/** A client whose network and clock are scripted: no real request, no real waiting. */
function scripted(steps, options = {}) {
  const calls = [], sleeps = [];
  let clock = 0;
  const client = createClient({ ...options, random: () => 0, now: () => clock,
    sleep: async (ms) => { sleeps.push(ms); clock += ms; },
    fetch: async (url, init) => { calls.push({ url, init }); const step = steps.shift(); return typeof step === "function" ? step() : step; } });
  return { client, calls, sleeps };
}
const code = (expected) => (error) => error instanceof SourceFailure && error.code === expected;

test("network errors, timeouts, 429 and 5xx are retried with growing pauses, then reported", async () => {
  const body = await fixture("source-ok.json");
  const recovered = scripted([timeout, reply(503, ""), reply(200, body)], { retries: 2, backoffMs: 1000, minIntervalMs: 0 });
  assert.equal((await recovered.client.getJson("https://source.example/x")).result.items[0].reviews.general_rating, 4.8);
  assert.deepEqual(recovered.sleeps, [1000, 2000]);

  const gaveUp = scripted([timeout, timeout, timeout], { retries: 2, minIntervalMs: 0 });
  await assert.rejects(gaveUp.client.getJson("https://source.example/x"), code("TIMEOUT"));
  assert.equal(gaveUp.calls.length, 3);

  const limited = scripted([reply(429, "", { "retry-after": "7" }), reply(200, body)], { minIntervalMs: 0 });
  await limited.client.getJson("https://source.example/x");
  assert.deepEqual(limited.sleeps, [7000], "the pause the source asked for is respected");
});

test("a refusal or a broken answer is not retried", async () => {
  for (const [step, expected] of [[reply(403, ""), "HTTP_403"], [reply(404, ""), "HTTP_404"],
    [reply(200, "<html>captcha</html>"), "FORMAT"], [reply(200, "x".repeat(300000)), "TOO_LARGE"]]) {
    const { client, calls } = scripted([step], { minIntervalMs: 0 });
    await assert.rejects(client.getJson("https://source.example/x"), code(expected));
    assert.equal(calls.length, 1);
  }
  const plain = scripted([], {});
  await assert.rejects(plain.client.getJson("http://source.example/x"), code("INSECURE_URL"));
  assert.equal(plain.calls.length, 0);
});

test("requests are spaced out, bounded by a timeout and never follow redirects", async () => {
  const body = await fixture("source-ok.json");
  const { client, calls, sleeps } = scripted([reply(200, body), reply(200, body)], { minIntervalMs: 1500, timeoutMs: 4000 });
  await client.getJson("https://source.example/1");
  await client.getJson("https://source.example/2");
  assert.deepEqual(sleeps, [1500]);
  assert.equal(calls[0].init.redirect, "error");
  assert.ok(calls[0].init.signal instanceof AbortSignal);
});

const venues = [
  { id: "aeris", sources: { gis: { id: "700", url: "https://2gis.ru/ekaterinburg/firm/700" }, yandex: { id: "103", url: "y" } } },
  { id: "momo", sources: { gis: { id: "701", url: "https://2gis.ru/ekaterinburg/firm/701" } } },
];
const env = { ASTOR_RATINGS_GIS_URL: "https://source.example/items?id={id}&key={key}", ASTOR_RATINGS_GIS_KEY: "secret-key",
  ASTOR_RATINGS_GIS_RATING_PATH: "result.items.0.reviews.general_rating", ASTOR_RATINGS_GIS_LICENSE: LICENSE_ACK };

test("a licensed source is read by object id; a changed format or a missing rating is a failure, not a value", async () => {
  const { client, calls } = scripted([reply(200, await fixture("source-ok.json")), reply(200, await fixture("source-changed-format.json"))],
    { minIntervalMs: 0 });
  const results = await licensedResults(venues, licensedSources(env), client);
  assert.deepEqual(results, { aeris: { gis: { ok: true, rating: 4.8, provider: "licensed" } },
    momo: { gis: { ok: false, code: "FORMAT" } } });
  assert.equal(calls[0].url, "https://source.example/items?id=700&key=secret-key");
  assert.equal(results.aeris.yandex, undefined, "a source without settings is not touched");

  const unrated = scripted([reply(200, await fixture("source-no-rating.json")), reply(500, ""), reply(500, ""), reply(500, "")],
    { minIntervalMs: 0, backoffMs: 1 });
  assert.deepEqual(await licensedResults(venues, licensedSources(env), unrated.client),
    { aeris: { gis: { ok: false, code: "NO_RATING" } }, momo: { gis: { ok: false, code: "HTTP_500" } } });
});

test("automatic reading does not start without a stated permission, and the key never reaches a message", async () => {
  assert.deepEqual(licensedSources({}), {});
  assert.throws(() => licensedSources({ ...env, ASTOR_RATINGS_GIS_LICENSE: "" }), /permission to store/);
  assert.throws(() => licensedSources({ ...env, ASTOR_RATINGS_GIS_RATING_PATH: "" }), /RATING_PATH/);
  const failing = scripted([() => { throw new Error("connect failed https://source.example/items?key=secret-key"); },
    reply(403, "{\"message\":\"bad key secret-key\"}")], { retries: 0, minIntervalMs: 0 });
  const results = await licensedResults(venues, licensedSources(env), failing.client);
  assert.deepEqual(results, { aeris: { gis: { ok: false, code: "NETWORK" } }, momo: { gis: { ok: false, code: "HTTP_403" } } });
  assert.doesNotMatch(JSON.stringify(results), /secret-key/);
});

test("typed ratings are accepted only for known venues, known sources and real values", () => {
  assert.deepEqual(manualResults(["aeris:yandex=4.8,gis=5", "momo:gis=4.75"], venues), {
    aeris: { yandex: { ok: true, rating: 4.8, provider: "manual" }, gis: { ok: true, rating: 5, provider: "manual" } },
    momo: { gis: { ok: true, rating: 4.75, provider: "manual" } } });
  for (const bad of [[], ["nobody:yandex=4.8"], ["aeris"], ["aeris:tripadvisor=4.8"], ["momo:yandex=4.8"], ["aeris:yandex=0"],
    ["aeris:yandex=5.1"], ["aeris:yandex=4,8"], ["aeris:yandex="], ["aeris:yandex=4.8,yandex=4.9"], ["aeris:gis=4.8", "aeris:gis=4.9"]]) {
    assert.throws(() => manualResults(bad, venues), Error, JSON.stringify(bad));
  }
});
