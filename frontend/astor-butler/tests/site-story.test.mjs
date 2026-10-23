// Титульная страница рассказывает одну историю продукта: кнопки ведут в нужных ботов, якоря существуют,
// тексты на «вы», с короткими тире и без тяжёлых медиа.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const html = readFileSync(fileURLToPath(new URL('../index.html', import.meta.url)), 'utf8');
const text = s => s.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
const links = [...html.matchAll(/<a\b[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g)].map(m => ({ href: m[1], label: text(m[2]) }));
const body = text(html.replace(/<head>[\s\S]*?<\/head>/, '').replace(/<(script|style)[\s\S]*?<\/\1>/g, ''));

test('the first screen sells to the restaurant and lets you try Astor as a guest', () => {
  const hero = /<section class="hero"[\s\S]*?<\/section>/.exec(html)?.[0] ?? '';
  assert.match(text(hero), /Гости бронируют в Telegram, хостес только подтверждает/);
  const heroLinks = [...hero.matchAll(/<a\b[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g)].map(m => [m[1], text(m[2])]);
  assert.deepEqual(heroLinks, [
    ['#connect', 'Подключить ресторан'],
    ['https://t.me/astor_concierge_bot?start=lunch_aeris', 'Попробовать как гость'],
  ]);
});

test('the guest path starts at step 0, the Astor Mini App opened from the Concierge', () => {
  const how = /<section class="section" id="how"[\s\S]*?<\/section>/.exec(html)?.[0] ?? '';
  const first = /<li class="step[^"]*">[\s\S]*?<\/li>\s*<li class="step"/.exec(how)?.[0] ?? '';
  assert.match(text(first), /^0\s*вход гостя\s*Astor Mini App/);
  assert.ok(first.includes('href="https://t.me/astor_concierge_bot"'), 'step 0 opens the Concierge');
  assert.match(text(first), /Позвать друзей/);
});

test('every in-page anchor has its target', () => {
  for (const { href } of links.filter(l => l.href.startsWith('#'))) {
    assert.match(html, new RegExp(`id="${href.slice(1)}"`), `${href} has no target`);
  }
});

test('the footer links both bots and the policy', () => {
  const footer = /<footer[\s\S]*?<\/footer>/.exec(html)?.[0] ?? '';
  for (const href of ['https://t.me/astor_concierge_bot', 'https://t.me/aeris_butler_bot', 'policy.html']) {
    assert.ok(footer.includes(`href="${href}"`), `footer: no link to ${href}`);
  }
});

test('Telegram links open only the two known bots and Mikhail for venues', () => {
  const tg = links.filter(l => l.href.includes('t.me/')).map(l => new URL(l.href).pathname);
  assert.ok(tg.length >= 3);
  for (const path of tg) assert.ok(['/astor_concierge_bot', '/aeris_butler_bot', '/michael_welly'].includes(path), `unknown Telegram link ${path}`);
});

test('copy speaks to the reader as «вы», with en dashes and no promise that the bot confirms', () => {
  assert.doesNotMatch(body, /—/, 'en dash «–» instead of the em dash');
  assert.doesNotMatch(body, /(^|[^А-Яа-яЁё])(ты|тебя|тебе|твой|твоя|твои|закажи|напиши)(?=[^А-Яа-яЁё]|$)/i, 'informal «ты»');
  assert.doesNotMatch(body, /бот подтвер(дит|ждает)/i, 'the restaurant confirms, not the bot');
  assert.match(body, /Бронь подтверждает ресторан/);
});

test('the page stays light: no video, no raster images, no scripts', () => {
  assert.doesNotMatch(html, /<video|<iframe|\.mp4|\.webm/i);
  assert.doesNotMatch(html, /<img[^>]+\.(png|jpe?g|webp|gif)/i);
  assert.doesNotMatch(html, /<script/i);
});
