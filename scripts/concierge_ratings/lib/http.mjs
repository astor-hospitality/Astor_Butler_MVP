/* Bounded JSON requests to a rating source: timeout, a few retries with backoff, a pause between calls.

   Failures are reported as short codes. The request URL can carry a key, so neither the URL nor
   the source's error text is ever put into a message. */

const MAX_BODY_BYTES = 256 * 1024;
const MAX_RETRY_AFTER_MS = 30000;

export class SourceFailure extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function createClient({ fetch: doFetch = globalThis.fetch, sleep = pause, random = Math.random,
                               now = () => Date.now(), timeoutMs = 8000, retries = 2, backoffMs = 1000,
                               minIntervalMs = 1000 } = {}) {
  let lastCallAt = null;

  async function once(url) {
    // One host is never called more often than once per interval, retries included.
    if (lastCallAt !== null) {
      const wait = lastCallAt + minIntervalMs - now();
      if (wait > 0) await sleep(wait);
    }
    lastCallAt = now();
    let response;
    try {
      response = await doFetch(url, { redirect: "error", headers: { Accept: "application/json" },
        signal: AbortSignal.timeout(timeoutMs) });
    } catch (error) {
      throw new SourceFailure(error && error.name === "TimeoutError" ? "TIMEOUT" : "NETWORK");
    }
    if (!response.ok) {
      const failure = new SourceFailure("HTTP_" + response.status);
      failure.retryAfter = Number(response.headers.get("retry-after"));
      throw failure;
    }
    let text;
    try {
      text = await response.text();
    } catch (error) {
      throw new SourceFailure(error && error.name === "TimeoutError" ? "TIMEOUT" : "NETWORK");
    }
    if (text.length > MAX_BODY_BYTES) throw new SourceFailure("TOO_LARGE");
    try {
      return JSON.parse(text);
    } catch (error) {
      throw new SourceFailure("FORMAT");
    }
  }

  function retryable(failure) {
    return ["TIMEOUT", "NETWORK", "HTTP_429"].includes(failure.code) || /^HTTP_5\d\d$/.test(failure.code);
  }

  return {
    async getJson(url) {
      if (!/^https:\/\//.test(url)) throw new SourceFailure("INSECURE_URL");
      for (let attempt = 0; ; attempt++) {
        try {
          return await once(url);
        } catch (failure) {
          if (!(failure instanceof SourceFailure) || !retryable(failure) || attempt >= retries) throw failure;
          const asked = failure.retryAfter > 0 ? failure.retryAfter * 1000 : 0;
          const backoff = backoffMs * 2 ** attempt * (1 + random() / 2);
          await sleep(Math.min(Math.max(asked, backoff), MAX_RETRY_AFTER_MS));
        }
      }
    },
  };
}
