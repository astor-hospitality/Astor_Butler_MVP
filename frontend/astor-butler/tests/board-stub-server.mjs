// Local stub for the board page: serves the site and fixture answers for /api/board/v1/** (no Butler needed).
//   node frontend/astor-butler/tests/board-stub-server.mjs [port] [legacy]   → http://127.0.0.1:8095/board/
// "legacy" answers /today with 404, as a Butler without the manager's day does: the «Сегодня» tab then reads /visits.
// Token: stub-board-token-0001 (fixture only). /__login stores it in sessionStorage and opens the board.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const site = fileURLToPath(new URL('..', import.meta.url));
const port = Number(process.argv[2] || 8095);
const legacy = process.argv[3] === 'legacy';
const TOKEN = 'stub-board-token-0001';
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' };

// The venue's date (Asia/Yekaterinburg, UTC+5), so the fixtures fall on the board's «today» at any hour.
const today = new Date(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Yekaterinburg' }).format(new Date()) + 'T00:00:00Z');
const at = (days, hh, mm) => new Date(today.getTime() + days * 86400000 + ((hh - 5) * 60 + mm) * 60000).toISOString();
const ago = (minutes) => new Date(Date.now() - minutes * 60000).toISOString();

const visits = [
  { id: 40, reservationId: 116, stage: 'REQUESTED', visitDay: at(1, 13, 30), guest: 'Андрей Л.', phone: '+7 912 ***-**-41', table: null, guestMessages: 6, lastTurnAt: at(0, 11, 2), hasSummary: false },
  { id: 41, reservationId: 117, stage: 'REQUESTED', visitDay: at(3, 19, 0), guest: 'Гость', phone: null, table: null, guestMessages: 2, lastTurnAt: at(0, 10, 15), hasSummary: false },
  { id: 39, reservationId: 115, stage: 'CONFIRMED', visitDay: at(0, 14, 0), guest: 'Ольга Н.', phone: '+7 922 ***-**-08', table: '7', guestMessages: 9, lastTurnAt: at(0, 9, 40), hasSummary: false },
  { id: 42, reservationId: 118, stage: 'SEATED', visitDay: at(0, 13, 15), guest: 'Мария К.', phone: '+7 912 ***-**-60', table: '4', guestMessages: 27, lastTurnAt: at(0, 13, 21), hasSummary: true },
  { id: 35, reservationId: 110, stage: 'AFTER', visitDay: at(-1, 19, 0), guest: 'Дмитрий В.', phone: '+7 950 ***-**-12', table: '2', guestMessages: 31, lastTurnAt: at(-1, 21, 30), hasSummary: true },
  { id: 30, reservationId: 101, stage: 'RETURNED', visitDay: at(-3, 13, 0), guest: 'Таня С.', phone: null, table: '5', guestMessages: 14, lastTurnAt: at(-3, 14, 10), hasSummary: true },
  { id: 33, reservationId: 108, stage: 'NO_SHOW', visitDay: at(-2, 20, 0), guest: 'Игорь П.', phone: null, table: '9', guestMessages: 3, lastTurnAt: at(-2, 12, 0), hasSummary: false },
];
const turns = [
  { at: at(0, 12, 41), side: 'GUEST', channel: 'CONCIERGE_TG', kind: 'MESSAGE', text: 'Стол на двоих в 13:00, торопимся', payload: null, goal: null },
  { at: at(0, 12, 41), side: 'BUTLER', channel: 'INTERNAL', kind: 'TOOL_CALL', text: 'table_availability', payload: { tool: 'table_availability', arguments: { time: '13:00', guests: 2 } }, goal: null },
  { at: at(0, 12, 41), side: 'BUTLER', channel: 'INTERNAL', kind: 'TOOL_RESULT', text: 'table_availability', payload: { tool: 'table_availability', ok: true, error: null, facts: { '13:00': 'занято', free: '13:15', table: 4 } }, goal: null },
  { at: at(0, 12, 41), side: 'BUTLER', channel: 'CONCIERGE_TG', kind: 'MESSAGE', text: 'В 13:00 занято, есть 13:15 у окна. Закажете ланч заранее — подадим за 10 минут.', payload: null, goal: null },
  { at: at(0, 12, 42), side: 'GUEST', channel: 'CONCIERGE_TG', kind: 'MESSAGE', text: 'Да, 13:15 подходит. Борщ и пасту, без лука', payload: null, goal: null },
  { at: at(0, 12, 42), side: 'BUTLER', channel: 'INTERNAL', kind: 'TOOL_CALL', text: 'reservation_create', payload: { tool: 'reservation_create', arguments: { time: '13:15', table: 4 } }, goal: null },
  { at: at(0, 12, 42), side: 'BUTLER', channel: 'INTERNAL', kind: 'TOOL_RESULT', text: 'reservation_create', payload: { tool: 'reservation_create', ok: true, error: null, facts: { reservation: 118, status: 'ждёт хостес' } }, goal: null },
  { at: at(0, 12, 42), side: 'CONCIERGE', channel: 'CONCIERGE_TG', kind: 'MESSAGE', text: 'Забронировала 13:15 у окна, ланч без лука заказан.', payload: null, goal: null },
  { at: at(0, 12, 43), side: 'STAFF', channel: 'STAFF_GROUP', kind: 'STAFF_ACTION', text: 'Хостес Дарья: «Да»', payload: null, goal: null },
  { at: at(0, 12, 43), side: 'SYSTEM', channel: 'SABY', kind: 'TOOL_RESULT', text: 'saby_order', payload: { tool: 'order/create', ok: true, error: null, facts: { ms: 2100 } }, goal: null },
  { at: at(0, 13, 4), side: 'STAFF', channel: 'STAFF_GROUP', kind: 'STAFF_ACTION', text: 'Хостес: «🪑 Гость сел»', payload: null, goal: null },
  { at: at(0, 13, 21), side: 'GUEST', channel: 'BUTLER_TG', kind: 'MESSAGE', text: 'Можно ещё хлеба?', payload: null, goal: null },
  { at: at(0, 13, 21), side: 'STAFF', channel: 'GLASSES', kind: 'STAFF_ACTION', text: 'Карточка официанту Кириллу: хлеб, стол 4', payload: null, goal: null },
];
// The manager's day as Butler's rows hold it; telegramUserId and sessionId never leave the server (see /today below).
const dayRows = [
  { visitId: 50, reservationId: 130, venue: 'AERIS', at: at(0, 12, 30), guest: 'Андрей', table: '3', guests: 2, status: 'CONFIRMED', lunchRub: 1160, channel: 'BUTLER', waitingSince: null, stars: null },
  { visitId: 51, reservationId: 131, venue: 'AERIS', at: at(0, 12, 45), guest: 'Сергей', table: '2', guests: 1, status: 'BILL', lunchRub: 580, channel: 'BUTLER', waitingSince: null, stars: 4 },
  { visitId: 52, reservationId: 132, venue: 'AERIS', at: at(0, 13, 0), guest: 'Ольга', table: '7', guests: 4, status: 'WAITING', lunchRub: 2320, channel: 'CONCIERGE', waitingSince: ago(14), stars: null },
  { visitId: 53, reservationId: 133, venue: 'AERIS', at: at(0, 13, 5), guest: 'Тест', table: null, guests: 2, status: 'WAITING', lunchRub: 99999, channel: 'BUTLER', waitingSince: ago(30), stars: null, telegramUserId: 900000123 },
  { visitId: 42, reservationId: 118, venue: 'AERIS', at: at(0, 13, 15), guest: 'Мария', table: '4', guests: 2, status: 'BILL', lunchRub: 980, channel: 'CONCIERGE', waitingSince: null, stars: 5 },
  { visitId: 54, reservationId: 134, venue: 'AERIS', at: at(0, 13, 30), guest: 'Дмитрий', table: null, guests: 3, status: 'WAITING', lunchRub: null, channel: 'BUTLER', waitingSince: ago(4), stars: null },
  { visitId: 55, reservationId: 135, venue: 'AERIS', at: at(0, 13, 40), guest: 'Лиза', table: '6', guests: 2, status: 'CONFIRMED', lunchRub: 1160, channel: 'CONCIERGE', waitingSince: null, stars: 5, sessionId: 'c-uxtest-0007' },
  { visitId: 56, reservationId: 136, venue: 'AERIS', at: at(0, 14, 0), guest: 'Игорь', table: '9', guests: 2, status: 'CANCELLED', lunchRub: 1160, channel: 'BUTLER', waitingSince: null, stars: null },
  { visitId: 57, reservationId: 137, venue: 'AERIS', at: at(0, 19, 0), guest: 'Татьяна', table: '5', guests: 6, status: 'CONFIRMED', lunchRub: null, channel: 'CONCIERGE', waitingSince: null, stars: null },
  { visitId: 60, reservationId: 140, venue: 'LOFT', at: at(0, 14, 30), guest: 'Павел', table: '12', guests: 2, status: 'CONFIRMED', lunchRub: 1300, channel: 'CONCIERGE', waitingSince: null, stars: null },
  { visitId: 61, reservationId: 141, venue: 'LOFT', at: at(0, 15, 0), guest: 'Ксения', table: null, guests: 2, status: 'WAITING', lunchRub: null, channel: 'BUTLER', waitingSince: ago(2), stars: null },
];
// The server's rule (BoardQueries.todayRows): synthetic Telegram ids 900000001–900000999 and Concierge sessions c-uxtest-*.
const testGuest = (row) => (row.telegramUserId >= 900000001 && row.telegramUserId <= 900000999)
  || String(row.sessionId || '').startsWith('c-uxtest-');

function managersDay(url) {
  const venue = (url.searchParams.get('venue') || '').toUpperCase() || null;
  const items = dayRows.filter(row => !testGuest(row) && (!venue || row.venue === venue))
    .sort((a, b) => Date.parse(a.at) - Date.parse(b.at) || a.visitId - b.visitId)
    .map(({ telegramUserId, sessionId, ...item }) => item);
  const live = items.filter(item => item.status !== 'CANCELLED' && item.status !== 'NO_SHOW');
  const stars = items.map(item => item.stars).filter(value => value != null);
  const waiting = items.filter(item => item.status === 'WAITING');
  return {
    from: url.searchParams.get('from'), to: url.searchParams.get('to'), venue,
    venues: [...new Set(dayRows.map(row => row.venue))].sort(), items,
    totals: {
      requests: items.length, confirmed: items.filter(item => item.status === 'CONFIRMED' || item.status === 'BILL').length,
      waiting: waiting.length, waitingOverdue: waiting.filter(item => Date.now() - Date.parse(item.waitingSince) >= 600000).length,
      lunchRub: live.reduce((sum, item) => sum + (item.lunchRub || 0), 0),
      ratingAvg: stars.length ? Math.round(stars.reduce((a, b) => a + b, 0) / stars.length * 10) / 10 : null, ratings: stars.length,
    },
  };
}

const summary = { visitId: 42, guestWanted: 'быстро пообедать вдвоём, без лука', venueOffered: '13:15 у окна, ланч заранее → подача 10 мин',
  outcome: 'бронь #118, ланч 1 160 ₽, подача за 9 минут', stuck: [], nextTimeGuest: ['предложить столик у окна сразу'], nextTimeAgents: [], createdAt: at(0, 13, 30) };
const metrics = { visits: 8, guestMessagesAvg: 27.4, visitsWith40Plus: 1, negotiationsClosed: 6, negotiationsTotal: 8, confirmMedianSeconds: 54,
  lunchConversion: 0.63, reviews: 2, returned: 1, modelCalls: 11, modelErrors: 0, modelP95Ms: 1900, fallbacks: 1, sabyErrors: 0 };
const health = [
  { component: 'butler', status: 'GREEN', detail: 'отвечает' }, { component: 'postgres', status: 'GREEN', detail: 'UP' },
  { component: 'redis', status: 'GREEN', detail: 'UP' }, { component: 'kafka', status: 'YELLOW', detail: 'нет проверки' },
  { component: 'gigachat', status: 'GREEN', detail: 'ошибок 0 из последних 5' }, { component: 'saby', status: 'YELLOW', detail: 'ошибок 1 из 6 за 15 минут' },
  { component: 'concierge', status: 'GREEN', detail: 'готов' },
];
const lessons = [
  { id: 7, visitId: 42, side: 'BUTLER', text: 'Если гость торопится — сразу предлагать ланч заранее и столик у окна.', status: 'PROPOSED', createdAt: at(0, 13, 30), decidedAt: null },
  { id: 8, visitId: 35, side: 'CONCIERGE', text: 'Уточнять повод визита до выбора стола.', status: 'PROPOSED', createdAt: at(-1, 22, 0), decidedAt: null },
];

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body === undefined ? '' : JSON.stringify(body));
}

async function readBody(req) {
  let raw = '';
  for await (const chunk of req) raw += chunk;
  try { return JSON.parse(raw || '{}'); } catch { return {}; }
}

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://stub');
  const path = url.pathname;
  if (path === '/__login') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(`<script>sessionStorage.setItem('astor-board-token','${TOKEN}');location.replace('/board/'+location.hash)</script>`);
  }
  if (path.startsWith('/api/board/v1/')) {
    const token = path === '/api/board/v1/stream' ? url.searchParams.get('t') : req.headers['x-astor-board-token'];
    if (token !== TOKEN) return send(res, 401);
    const route = path.slice('/api/board/v1'.length);
    if (route === '/stream') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      res.write(': ping\n\n');
      const timer = setInterval(() => res.write('event: turn\ndata: {"visitId":42}\n\n'), 15000);
      return req.on('close', () => clearInterval(timer));
    }
    if (route === '/visits') return send(res, 200, visits);
    if (route === '/today') return legacy ? send(res, 404) : send(res, 200, managersDay(url));
    if (route === '/metrics') return send(res, 200, metrics);
    if (route === '/health-map') return send(res, 200, health);
    if (route === '/lessons') return send(res, 200, lessons.filter(l => l.status === 'PROPOSED'));
    let m;
    if ((m = /^\/visits\/(\d+)\/turns$/.exec(route))) return send(res, 200, m[1] === '42' ? turns : turns.slice(0, 1));
    if ((m = /^\/visits\/(\d+)\/summary$/.exec(route))) return m[1] === '42' ? send(res, 200, summary) : send(res, 404);
    if ((m = /^\/visits\/(\d+)\/next-time$/.exec(route)) && req.method === 'POST') {
      const { text } = await readBody(req);
      if (!text) return send(res, 400);
      if (m[1] !== '42') return send(res, 404);
      summary.nextTimeAgents.push('BUTLER: ' + text);
      return send(res, 200, summary);
    }
    if ((m = /^\/lessons\/(\d+)\/(accept|reject)$/.exec(route)) && req.method === 'POST') {
      const lesson = lessons.find(l => String(l.id) === m[1]);
      if (!lesson) return send(res, 404);
      const { text } = await readBody(req);
      lesson.status = m[2] === 'accept' ? 'ACCEPTED' : 'REJECTED';
      if (text) lesson.text = text;
      return send(res, 200, lesson);
    }
    return send(res, 404);
  }
  const file = normalize(join(site, path.endsWith('/') ? path + 'index.html' : path));
  if (!file.startsWith(site)) return send(res, 404);
  try {
    const data = await readFile(file);
    res.writeHead(200, { 'Content-Type': types[extname(file)] || 'application/octet-stream' });
    res.end(data);
  } catch {
    send(res, 404);
  }
}).listen(port, '127.0.0.1', () => console.log(`board stub: http://127.0.0.1:${port}/__login`));
