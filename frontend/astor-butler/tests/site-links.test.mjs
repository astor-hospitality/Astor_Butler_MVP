// Все относительные ссылки и ресурсы страниц сайта должны существовать в каталоге сайта.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const site = fileURLToPath(new URL('..', import.meta.url));
const skip = new Set(['node_modules', 'server', 'tests', '.openai', '.vercel']);
const products = ['astor_butler/', 'astor_concierge/', 'astor_glass/'];

function htmlPages(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    if (skip.has(entry.name)) return [];
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return htmlPages(path);
    return entry.name.endsWith('.html') ? [path] : [];
  });
}

function targets(html) {
  return [...html.matchAll(/\b(?:href|src)="([^"]+)"/g)].map(m => m[1])
    .filter(url => !/^(?:https?:|mailto:|tel:|data:|#|javascript:)/.test(url))
    .map(url => url.replace(/[?#].*$/, ''))
    .filter(Boolean);
}

const pages = htmlPages(site);

test('every relative link and asset on every page resolves to a file', () => {
  const missing = [];
  for (const page of pages) {
    const html = readFileSync(page, 'utf8');
    for (const url of targets(html)) {
      let path = resolve(dirname(page), url);
      if (existsSync(path) && statSync(path).isDirectory()) path = join(path, 'index.html');
      if (!existsSync(path)) missing.push(`${relative(site, page)} -> ${url}`);
      if (!resolve(path).startsWith(site)) missing.push(`${relative(site, page)} -> ${url} (outside the site)`);
    }
  }
  assert.deepEqual(missing, []);
});

test('the title page and every product page link to all three products and the policy', () => {
  for (const name of ['index.html', ...products.map(p => `${p}index.html`)]) {
    const page = join(site, name);
    const html = readFileSync(page, 'utf8');
    const hrefs = targets(html).map(url => relative(site, resolve(dirname(page), url)) + (url.endsWith('/') ? '/' : ''));
    for (const product of products) {
      if (name.startsWith(product)) continue;
      assert.ok(hrefs.includes(product), `${name}: no link to ${product}`);
    }
    assert.ok(hrefs.includes('policy.html'), `${name}: no link to policy.html`);
  }
});
