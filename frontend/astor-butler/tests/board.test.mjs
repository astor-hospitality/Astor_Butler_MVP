// Табло /board: чистые функции из js/board.js (без DOM) — колонки канбана, дорожки карточки,
// цвета здоровья, время площадки и окна метрик.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  STAGES, groupByStage, laneOf, healthColor, lanesOf, defaultOpenVisit,
  venueTime, venueWindow, metricTiles, streamUrl, nextBackoff,
} from '../js/board.js';

test('groupsVisitsInStageOrder', () => {
  const visits = [
    { id: 1, stage: 'SEATED' }, { id: 2, stage: 'REQUESTED' }, { id: 3, stage: 'REQUESTED' }, { id: 4, stage: 'NO_SHOW' },
  ];
  const grouped = groupByStage(visits);
  assert.deepEqual(Object.keys(grouped),
    ['REQUESTED', 'CONFIRMED', 'VISIT_DAY', 'SEATED', 'BILL', 'AFTER', 'RETURNED', 'CANCELLED', 'NO_SHOW']);
  assert.deepEqual(grouped.REQUESTED.map(v => v.id), [2, 3]);
  assert.deepEqual(grouped.SEATED.map(v => v.id), [1]);
  assert.deepEqual(grouped.BILL, [], 'empty columns stay');
  assert.deepEqual(STAGES.map(s => s.label),
    ['Заявка', 'Подтверждена', 'День визита', 'За столом', 'Счёт', 'После', 'Вернулся', 'Отмена', 'Не пришёл']);
});

test('conciergeAndButlerShareTheAgentsLane', () => {
  assert.equal(laneOf({ side: 'GUEST' }), 'guest');
  assert.equal(laneOf({ side: 'CONCIERGE' }), 'agents');
  assert.equal(laneOf({ side: 'BUTLER' }), 'agents');
  assert.equal(laneOf({ side: 'STAFF' }), 'staff');
  assert.equal(laneOf({ side: 'SYSTEM' }), 'staff');
});

test('unknownHealthIsGrey', () => {
  assert.equal(healthColor('GREEN'), 'var(--good)');
  assert.equal(healthColor('YELLOW'), 'var(--warn)');
  assert.equal(healthColor('RED'), 'var(--bad)');
  assert.equal(healthColor(undefined), 'var(--idle)');
  assert.equal(healthColor('PURPLE'), 'var(--idle)');
});

test('a tool call and its result become one mono line "tool → result" in the agents lane', () => {
  const lanes = lanesOf([
    { at: '2026-10-14T07:41:00Z', side: 'GUEST', kind: 'MESSAGE', text: 'Стол на двоих' },
    { at: '2026-10-14T07:41:01Z', side: 'BUTLER', kind: 'TOOL_CALL', text: 'table_availability',
      payload: { tool: 'table_availability', arguments: { time: '13:00', guests: 2 } } },
    { at: '2026-10-14T07:41:02Z', side: 'BUTLER', kind: 'TOOL_RESULT', text: 'table_availability',
      payload: { tool: 'table_availability', ok: true, error: null, facts: { free: '13:15', table: 4 } } },
    { at: '2026-10-14T07:41:03Z', side: 'BUTLER', kind: 'MESSAGE', text: 'Есть 13:15 у окна' },
    { at: '2026-10-14T07:42:00Z', side: 'BUTLER', kind: 'TOOL_RESULT', text: 'reservation_create',
      payload: { tool: 'reservation_create', ok: false, error: 'UNAVAILABLE', facts: {} } },
    { at: '2026-10-14T07:43:00Z', side: 'STAFF', kind: 'STAFF_ACTION', text: 'Хостес: «Да»' },
  ]);
  assert.equal(lanes.guest.length, 1);
  assert.equal(lanes.staff.length, 1);
  assert.equal(lanes.agents.length, 3);
  assert.equal(lanes.agents[0].tool, true);
  assert.equal(lanes.agents[0].text, 'table_availability time=13:00, guests=2 → free=13:15, table=4');
  assert.equal(lanes.agents[1].side, 'AERIS · Батлер');
  assert.equal(lanes.agents[2].text, 'reservation_create → ошибка UNAVAILABLE');
  assert.equal(lanes.guest[0].time, '12:41', 'venue time, UTC+5');
});

test('the open card is the first seated visit, else the newest', () => {
  assert.equal(defaultOpenVisit([]), null);
  const visits = [
    { id: 1, stage: 'AFTER', lastTurnAt: '2026-10-14T09:00:00Z' },
    { id: 2, stage: 'SEATED', lastTurnAt: '2026-10-14T07:00:00Z' },
    { id: 3, stage: 'SEATED', lastTurnAt: '2026-10-14T08:00:00Z' },
  ];
  assert.equal(defaultOpenVisit(visits).id, 2);
  assert.equal(defaultOpenVisit(visits.filter(v => v.stage !== 'SEATED').concat(
    { id: 4, stage: 'REQUESTED', lastTurnAt: '2026-10-14T10:00:00Z' }, { id: 5, stage: 'CONFIRMED', lastTurnAt: null })).id, 4);
});

test('instants render in Asia/Yekaterinburg', () => {
  assert.equal(venueTime('2026-10-14T08:04:00Z'), '13:04');
  assert.equal(venueTime(null), '');
});

test('metric windows start at venue midnight', () => {
  const now = new Date('2026-10-14T20:30:00Z'); // 01:30 on 15.10 in Yekaterinburg
  assert.deepEqual(venueWindow('today', now), { from: '2026-10-14T19:00:00.000Z', to: '2026-10-15T19:00:00.000Z' });
  assert.deepEqual(venueWindow('7d', now), { from: '2026-10-08T19:00:00.000Z', to: '2026-10-15T19:00:00.000Z' });
  assert.deepEqual(venueWindow('30d', now), { from: '2026-09-15T19:00:00.000Z', to: '2026-10-15T19:00:00.000Z' });
});

test('metric tiles read the /metrics record and say when a figure is missing', () => {
  const tiles = metricTiles({ visits: 8, guestMessagesAvg: 27.5, visitsWith40Plus: 1, negotiationsClosed: 6,
    negotiationsTotal: 8, confirmMedianSeconds: null, lunchConversion: 0.63, reviews: 2, returned: 1,
    modelCalls: 11, modelErrors: 0, modelP95Ms: 1900, fallbacks: 1, sabyErrors: 0 });
  const byLabel = Object.fromEntries(tiles.map(t => [t.label, t]));
  assert.equal(byLabel['Переговоры → бронь'].value, '6 / 8');
  assert.equal(byLabel['До подтверждения'].value, '—');
  assert.equal(byLabel['Ланч'].value, '63 %');
  assert.equal(byLabel['GigaChat p95'].value, '1,9 с');
  assert.equal(byLabel['Сообщений гостя'].value, '27,5');
});

test('the stream carries the token only as an encoded query parameter; backoff 2 s → 30 s', () => {
  assert.equal(streamUrl('a b&c'), '/api/board/v1/stream?t=a%20b%26c');
  assert.equal(nextBackoff(0), 2000);
  assert.equal(nextBackoff(2000), 4000);
  assert.equal(nextBackoff(16000), 30000);
  assert.equal(nextBackoff(30000), 30000);
});

test('the page loads board.js as a module and keeps the token out of localStorage', () => {
  const html = readFileSync(new URL('../board/index.html', import.meta.url), 'utf8');
  assert.match(html, /<script type="module" src="\.\.\/js\/board\.js"><\/script>/);
  const js = readFileSync(new URL('../js/board.js', import.meta.url), 'utf8');
  assert.doesNotMatch(js, /localStorage\.setItem\([^)]*token/i);
  assert.match(js, /sessionStorage/);
});

test('signInLinkCarriesTheTokenInTheFragment', async () => {
  const { tokenFromHash } = await import('../js/board.js');
  assert.equal(tokenFromHash('#t=abc123'), 'abc123');
  assert.equal(tokenFromHash('#t=a%2Bb'), 'a+b');
  assert.equal(tokenFromHash('#visits'), null);
  assert.equal(tokenFromHash(''), null);
  assert.equal(tokenFromHash('#t='), null);
});

test('«Сегодня» without /today reads /visits: first names, status by stage, the venue day only, time order', async () => {
  const { dayFromVisits } = await import('../js/board.js');
  const window = { from: '2026-10-14T19:00:00.000Z', to: '2026-10-15T19:00:00.000Z' };
  const items = dayFromVisits([
    { id: 2, reservationId: 12, stage: 'SEATED', visitDay: '2026-10-15T08:15:00Z', guest: 'Мария К.', table: '4' },
    { id: 1, reservationId: 11, stage: 'REQUESTED', visitDay: '2026-10-15T08:00:00Z', guest: 'Гость', table: null },
    { id: 3, reservationId: 13, stage: 'AFTER', visitDay: '2026-10-15T09:00:00Z', guest: 'Пётр', table: '2' },
    { id: 4, reservationId: 14, stage: 'NO_SHOW', visitDay: '2026-10-15T10:00:00Z', guest: 'Игорь П.', table: '9' },
    { id: 5, reservationId: 15, stage: 'CANCELLED', visitDay: '2026-10-15T11:00:00Z', guest: 'Анна', table: null },
    { id: 6, reservationId: 16, stage: 'CONFIRMED', visitDay: '2026-10-15T19:00:00Z', guest: 'Завтра', table: null },
    { id: 7, reservationId: 17, stage: 'CONFIRMED', visitDay: '2026-10-14T18:59:00Z', guest: 'Вчера', table: null },
  ], window);
  assert.deepEqual(items.map(i => [i.guest, i.status]), [
    ['Гость', 'WAITING'], ['Мария', 'CONFIRMED'], ['Пётр', 'BILL'], ['Игорь', 'NO_SHOW'], ['Анна', 'CANCELLED']]);
  assert.equal(items[1].table, '4');
  assert.equal(items[1].lunchRub, null, 'what /visits does not hold stays empty, not zero');
  assert.equal(items[1].channel, null);
  assert.equal(items[1].guests, null);
});

test('day counters: confirmed counts the bill, waiting over 10 minutes is overdue, cancelled lunches earn nothing', async () => {
  const { dayTotals, dayCounters, waitingMinutes, isOverdue } = await import('../js/board.js');
  const now = new Date('2026-10-15T09:00:00Z');
  const items = [
    { status: 'WAITING', waitingSince: '2026-10-15T08:50:00Z', lunchRub: 1160, stars: null, channel: 'BUTLER' },
    { status: 'WAITING', waitingSince: '2026-10-15T08:51:00Z', lunchRub: null, stars: null, channel: 'CONCIERGE' },
    { status: 'CONFIRMED', waitingSince: null, lunchRub: 980, stars: 5, channel: 'CONCIERGE' },
    { status: 'BILL', waitingSince: null, lunchRub: 580, stars: 4, channel: 'BUTLER' },
    { status: 'CANCELLED', waitingSince: null, lunchRub: 1160, stars: null, channel: 'BUTLER' },
  ];
  assert.equal(waitingMinutes(items[0], now), 10);
  assert.equal(isOverdue(items[0], now), true);
  assert.equal(isOverdue(items[1], now), false);
  assert.equal(waitingMinutes(items[2], now), null);
  const totals = dayTotals(items, now);
  assert.deepEqual(totals, { requests: 5, confirmed: 2, waiting: 2, overdue: 1, lunchRub: 2720, ratingAvg: 4.5, ratings: 2 });
  const tiles = dayCounters(totals);
  assert.deepEqual(tiles.map(t => t.label),
    ['Заявок', 'Подтверждено', 'Ждут ответа хостес', 'Выручка ланча', 'Оценка гостей']);
  const byKey = Object.fromEntries(tiles.map(t => [t.key, t]));
  assert.equal(byKey.waiting.alert, true);
  assert.equal(byKey.waiting.note, 'дольше 10 минут: 1');
  assert.equal(byKey.lunch.value, '2 720 ₽');
  assert.equal(byKey.rating.value, '4,5');
  assert.equal(byKey.rating.note, 'средняя из 2 оценок');
  assert.equal(dayCounters({ ...totals, ratings: 1 }).at(-1).note, 'средняя из 1 оценки');
  const limited = dayCounters(dayTotals(items.map(i => ({ ...i, waitingSince: null })), now), { full: false });
  assert.deepEqual(limited.map(t => t.key), ['requests', 'confirmed', 'waiting'], 'a server without /today: no lunch, no rating');
  assert.equal(limited[2].alert, false);
});

test('the day asks /today for one venue or for all; first names and plurals for the hall', async () => {
  const { dayPath, firstName, plural, DAY_STATUS, DAY_CHANNEL } = await import('../js/board.js');
  const window = { from: '2026-10-14T19:00:00.000Z', to: '2026-10-15T19:00:00.000Z' };
  assert.equal(dayPath(window, 'AERIS'),
    '/today?from=2026-10-14T19%3A00%3A00.000Z&to=2026-10-15T19%3A00%3A00.000Z&venue=AERIS');
  assert.equal(dayPath(window, null), '/today?from=2026-10-14T19%3A00%3A00.000Z&to=2026-10-15T19%3A00%3A00.000Z');
  assert.equal(firstName('Мария К.'), 'Мария');
  assert.equal(firstName(''), 'Гость');
  assert.deepEqual([1, 2, 5, 11, 22].map(n => plural(n, ['гость', 'гостя', 'гостей'])), ['гость', 'гостя', 'гостей', 'гостей', 'гостя']);
  assert.deepEqual(Object.values(DAY_STATUS).map(s => s.label), ['ждёт хостес', 'подтверждена', 'счёт', 'отменена', 'не пришёл']);
  assert.deepEqual(DAY_CHANNEL, { CONCIERGE: 'Консьерж', BUTLER: 'бот ресторана' });
});

test('«Сегодня» only reads: the tab never posts and keeps the token out of localStorage', () => {
  const js = readFileSync(new URL('../js/board.js', import.meta.url), 'utf8');
  const day = js.slice(js.indexOf('// ---- today: the shift manager'), js.indexOf('// ---- health ----'));
  assert.ok(day.length > 0);
  assert.doesNotMatch(day, /method:\s*"POST"/);
  assert.doesNotMatch(js, /localStorage\.setItem\([^)]*TOKEN/);
  const html = readFileSync(new URL('../board/index.html', import.meta.url), 'utf8');
  assert.match(html, /data-tab="today"[^>]*>Сегодня</);
  for (const tab of ['today', 'visits', 'metrics', 'arch', 'lessons']) assert.match(html, new RegExp('id="tab-' + tab + '"'));
});
