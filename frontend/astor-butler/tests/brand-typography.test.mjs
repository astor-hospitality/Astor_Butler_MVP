// Бренд-типографика: статическая копия токенов должна совпадать с источником в design-system,
// каждая страница сайта подключать ее первой, а шрифт грузиться только из нее.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const site = fileURLToPath(new URL('..', import.meta.url));
const repo = join(site, '..', '..');
const source = join(repo, 'design-system', 'brand', 'typography.css');
const copy = join(site, 'css', 'brand-typography.css');
const skip = new Set(['node_modules', 'server', 'tests', '.openai', '.vercel']);

function htmlPages(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    if (skip.has(entry.name)) return [];
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return htmlPages(path);
    return entry.name.endsWith('.html') ? [path] : [];
  });
}

const pages = htmlPages(site);
const stylesheets = ['css/style.css', 'css/feed.css', 'css/staff.css', 'docs/docs.css'].map(p => join(site, p));

test('static copy matches design-system/brand/typography.css byte for byte', () => {
  assert.equal(readFileSync(copy, 'utf8'), readFileSync(source, 'utf8'),
    'copy is stale: cp design-system/brand/typography.css frontend/astor-butler/css/brand-typography.css');
});

test('every page links brand-typography.css first and loads no other font service', () => {
  assert.ok(pages.length >= 10, `expected the whole site, found ${pages.length} pages`);
  for (const page of pages) {
    const html = readFileSync(page, 'utf8');
    const name = relative(site, page);
    const links = [...html.matchAll(/<link[^>]*rel="stylesheet"[^>]*>/g)].map(m => m[0]);
    assert.ok(links.length > 0, `${name}: no stylesheet`);
    const href = /href="([^"]+)"/.exec(links[0])?.[1];
    assert.ok(href && href.endsWith('css/brand-typography.css'), `${name}: first stylesheet must be brand-typography.css, got ${href}`);
    assert.doesNotMatch(html, /fonts\.googleapis\.com\/css/, `${name}: font loading belongs in brand-typography.css only`);
    assert.doesNotMatch(html, /Playfair/, `${name}: serif display font is gone`);
  }
});

test('site stylesheets take the family and weights from the brand tokens', () => {
  for (const file of stylesheets) {
    const css = readFileSync(file, 'utf8');
    const name = relative(site, file);
    assert.match(css, /var\(--brand-font-family\)/, `${name}: --font-body must come from --brand-font-family`);
    assert.doesNotMatch(css, /font-family:[^;]*"(Inter|Playfair Display)"/, `${name}: hard-coded font family`);
    assert.doesNotMatch(css, /font-style:\s*italic/, `${name}: one family means no italics`);
    assert.doesNotMatch(css, /font(?:-weight)?:\s*[5-9]\d\d\b/, `${name}: numeric weights; use --brand-weight-*`);
  }
});

test('c3ag Next.js layout loads the same family as the brand tokens', () => {
  const tokens = readFileSync(source, 'utf8');
  const family = /--brand-font-family:\s*"([^"]+)"/.exec(tokens)?.[1];
  assert.ok(family, 'first family in --brand-font-family must be quoted');
  const layout = readFileSync(join(repo, 'frontend', 'app', 'layout.tsx'), 'utf8');
  assert.ok(layout.includes(family), `frontend/app/layout.tsx must load "${family}" too (see design-system/brand/README.md)`);
});
