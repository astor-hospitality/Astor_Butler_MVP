// Знак Astor – засечная «A» со звездой вместо перекладины – один и тот же на всех страницах:
// inline-SVG с путями из assets/astor-mark.svg, старых кружков с буквами нет, favicon у всех страниц один.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = rel => readFileSync(fileURLToPath(new URL(`../${rel}`, import.meta.url)), 'utf8');
const pathsOf = svg => [...svg.matchAll(/<path d="([^"]+)"\/>/g)].map(m => m[1]);
const marksOf = html => [...html.matchAll(/<svg class="astor-mark"[^>]*>([\s\S]*?)<\/svg>/g)].map(m => m[0]);

const master = pathsOf(read('assets/astor-mark.svg'));
const withMark = {
  'index.html': 5, // шапка, подвал и три аватара Astor в макетах чатов ботов
  'astor_butler/index.html': 3, // шапка, подвал, кнопка чата
  'astor_concierge/index.html': 2,
  'astor_glass/index.html': 2,
  'board/index.html': 2, // вход и шапка табло
};
const withFavicon = [...Object.keys(withMark), 'staff/index.html', 'astor_concierge/feed/index.html',
  'docs/brand.html', 'docs/comparison.html', 'docs/offer.html', 'policy.html'];

test('assets/astor-mark.svg holds the five shapes of the mark', () => {
  assert.equal(master.length, 5);
});

test('every page draws the same mark inline, hidden from screen readers', () => {
  for (const [page, count] of Object.entries(withMark)) {
    const marks = marksOf(read(page));
    assert.equal(marks.length, count, `${page}: expected ${count} marks`);
    for (const svg of marks) {
      assert.deepEqual(pathsOf(svg), master, `${page}: mark differs from assets/astor-mark.svg`);
      assert.match(svg, /viewBox="116 113 400 400"/, `${page}: viewBox`);
      assert.match(svg, /fill="currentColor"/, `${page}: the mark takes the text colour`);
      assert.match(svg, /aria-hidden="true"/, `${page}: the mark is decorative next to the word Astor`);
    }
  }
});

test('the old letter marks are gone', () => {
  for (const page of Object.keys(withMark)) {
    const html = read(page);
    assert.doesNotMatch(html, /mark-ring|brand-monogram/, `${page}: old mark class`);
    assert.doesNotMatch(html, /<span class="avatar">A<\/span>|id="chatFab"[^>]*>A</, `${page}: old letter «A»`);
  }
});

test('every page links the same favicon version, and the favicon draws the mark, not letters', () => {
  const hrefs = withFavicon.map(page => /<link rel="icon"[^>]*href="([^"]+)"/.exec(read(page))?.[1] ?? '');
  const versions = new Set(hrefs.map(href => href.replace(/^(\.\.\/)+/, '')));
  assert.deepEqual([...versions], ['assets/favicon.svg?v=20261023-star']);
  assert.doesNotMatch(read('assets/favicon.svg'), /<text/);
});
