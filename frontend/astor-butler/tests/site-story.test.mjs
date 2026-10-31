// Титульная страница рассказывает одну историю продукта: кнопки ведут в нужных ботов, якоря существуют,
// тексты на «вы», с короткими тире, экраны приложения – лёгкие WebP.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const html = readFileSync(fileURLToPath(new URL('../index.html', import.meta.url)), 'utf8');
const text = s => s.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
const links = [...html.matchAll(/<a\b[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g)].map(m => ({ href: m[1], label: text(m[2]) }));
const body = text(html.replace(/<head>[\s\S]*?<\/head>/, '').replace(/<(script|style)[\s\S]*?<\/\1>/g, ''));
const asset = src => fileURLToPath(new URL('../' + src, import.meta.url));

test('the first screen tells what the Astor app does and opens it in Telegram', () => {
  const hero = /<section class="hero"[\s\S]*?<\/section>/.exec(html)?.[0] ?? '';
  assert.match(text(hero), /Идеи и желания превращаются в планы/);
  assert.match(text(hero), /места и события, собирать друзей и организовывать встречи/);
  const cta = /<div class="cta">[\s\S]*?<\/div>/.exec(hero)?.[0] ?? '';
  const ctaLinks = [...cta.matchAll(/<a\b[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g)].map(m => [m[1], text(m[2])]);
  assert.deepEqual(ctaLinks, [
    ['https://t.me/Astorapp', 'Открыть Astor в Telegram'],
    ['#venues', 'Для заведений'],
  ]);
  // Чипы похожи на кнопки приложения – значит, это ссылки в приложение, а не мёртвые плашки.
  for (const chip of hero.matchAll(/<ul class="chips"[\s\S]*?<\/ul>/g)) {
    assert.doesNotMatch(chip[0], /<li class=/, 'chips must be links');
    assert.match(chip[0], /<a class="glass" href="https:\/\/t\.me\/Astorapp"/);
  }
});

test('the app section shows the real app screens, each with a title', () => {
  const app = /<section class="section" id="app"[\s\S]*?<\/section>/.exec(html)?.[0] ?? '';
  const titles = [...app.matchAll(/<h3>([\s\S]*?)<\/h3>/g)].map(m => text(m[1]));
  assert.deepEqual(titles, ['Карта', 'Бизнес-ланч', 'События', 'Бронь стола', 'Мои планы', 'Друзья', 'ИИ-помощник Astor']);
});

test('the guest path is four steps from a wish to a meetup, each with a real screen', () => {
  const how = /<section class="section section-alt" id="how"[\s\S]*?<\/section>/.exec(html)?.[0] ?? '';
  const legs = [...how.matchAll(/<li class="leg">([\s\S]*?)<\/li>/g)].map(m => m[1]);
  assert.deepEqual(legs.map(l => text(/<h3>([\s\S]*?)<\/h3>/.exec(l)[1])),
    ['Скажите, чего хочется', 'Выберите место или событие', 'Забронируйте стол', 'Позовите друзей']);
  for (const leg of legs) assert.match(leg, /<img class="shot" src="assets\/app\//);
  assert.ok(how.includes('href="https://t.me/Astorapp"'), 'the path ends with a way to try it');
});

test('venues see the Astor Business cabinet, the price and how to connect', () => {
  const venues = /<section class="section" id="venues"[\s\S]*?<\/section>/.exec(html)?.[0] ?? '';
  assert.match(venues, /src="assets\/app\/biz-today\.webp"/);
  assert.match(text(venues), /Astor Бизнес/);
  assert.match(text(venues), /30 000 ₽ в месяц/);
  assert.ok(venues.includes('id="connect"') && venues.includes('href="https://t.me/michael_welly"'));
});

test('every image on the page exists', () => {
  for (const [, src] of html.matchAll(/<img[^>]+src="([^"]+)"/g)) assert.ok(existsSync(asset(src)), `missing ${src}`);
});

test('every in-page anchor has its target', () => {
  for (const { href } of links.filter(l => l.href.startsWith('#'))) {
    assert.match(html, new RegExp(`id="${href.slice(1)}"`), `${href} has no target`);
  }
});

test('the footer links the app, the cabinet, the earlier products and the policy', () => {
  const footer = /<footer[\s\S]*?<\/footer>/.exec(html)?.[0] ?? '';
  for (const href of ['https://t.me/Astorapp', '#venues', 'astor_butler/', 'astor_glass/', 'astor_concierge/', 'policy.html']) {
    assert.ok(footer.includes(`href="${href}"`), `footer: no link to ${href}`);
  }
});

test('Telegram links open only the Astor app and Mikhail for venues', () => {
  const tg = links.filter(l => l.href.includes('t.me/')).map(l => new URL(l.href).pathname);
  assert.ok(tg.length >= 3);
  for (const path of tg) assert.ok(['/Astorapp', '/michael_welly'].includes(path), `unknown Telegram link ${path}`);
});

test('copy speaks to the reader as «вы», with en dashes and no promise that the bot confirms', () => {
  assert.doesNotMatch(body, /—/, 'en dash «–» instead of the em dash');
  assert.doesNotMatch(body, /(^|[^А-Яа-яЁё])(ты|тебя|тебе|твой|твоя|твои|закажи|напиши)(?=[^А-Яа-яЁё]|$)/i, 'informal «ты»');
  assert.doesNotMatch(body, /бот подтвер(дит|ждает)/i, 'the restaurant confirms, not the bot');
  assert.match(body, /Бронь подтверждает ресторан/);
});

test('the page stays light: no video, one small local animation script, only small WebP app screens', () => {
  assert.doesNotMatch(html, /<video|<iframe|\.mp4|\.webm/i);
  const scripts = [...html.matchAll(/<script\b[^>]*>/gi)].map(m => m[0]);
  assert.deepEqual(scripts, ['<script src="js/motion.js" defer>'], 'only the local animation script');
  const imgs = [...html.matchAll(/<img\b[^>]*>/g)].map(m => m[0]);
  assert.ok(imgs.length > 0);
  const files = new Set();
  for (const img of imgs) {
    const src = /src="([^"]+)"/.exec(img)[1];
    assert.match(src, /^assets\/app\/[a-z-]+\.webp$/, `${src}: only app screens in WebP`);
    assert.match(img, /\salt="[^"]+"/, `${src}: needs alt`);
    assert.match(img, /\swidth="\d+" height="\d+"/, `${src}: needs size`);
    files.add(src);
  }
  const total = [...files].reduce((sum, src) => sum + statSync(asset(src)).size, 0);
  assert.ok(total < 1_000_000, `app screens weigh ${total} bytes`);
});
