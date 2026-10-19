/* Astor board (/board): kanban of visits, the open visit in three lanes, metrics, architecture, lessons.
   Closed by the board token (X-Astor-Board-Token); the token lives in sessionStorage only; a sign-in link /board/#t=<token> sets it.
   ES module: the pure functions below are imported by tests/board.test.mjs; the DOM part runs only in a browser. */

export const API = "/api/board/v1";
export const VENUE_TZ = "Asia/Yekaterinburg";
const TOKEN_KEY = "astor-board-token";
const THEME_KEY = "astor-board-theme";
const HIDDEN_TEXT = "текст скрыт";

export const STAGES = [
  { key: "REQUESTED", label: "Заявка" },
  { key: "CONFIRMED", label: "Подтверждена" },
  { key: "VISIT_DAY", label: "День визита" },
  { key: "SEATED", label: "За столом" },
  { key: "BILL", label: "Счёт" },
  { key: "AFTER", label: "После" },
  { key: "RETURNED", label: "Вернулся" },
  { key: "CANCELLED", label: "Отмена" },
  { key: "NO_SHOW", label: "Не пришёл" },
];

export const LANES = [
  { key: "guest", label: "Гость" },
  { key: "agents", label: "Консьерж ⇄ Батлер" },
  { key: "staff", label: "Персонал и системы" },
];

const SIDE_LANE = { GUEST: "guest", CONCIERGE: "agents", BUTLER: "agents", STAFF: "staff", SYSTEM: "staff" };
const SIDE_LABEL = { CONCIERGE: "Консьерж", BUTLER: "AERIS · Батлер" };
const SIDE_NAME = { CONCIERGE: "Консьерж", BUTLER: "Батлер" };
const HEALTH_VAR = { GREEN: "var(--good)", YELLOW: "var(--warn)", RED: "var(--bad)" };

export const COMPONENTS = {
  concierge: "Консьерж", butler: "Батлер", gigachat: "GigaChat", saby: "Saby",
  postgres: "Postgres", redis: "Redis", kafka: "Kafka",
};

/** Visits by stage, every column present and in board order; an unknown stage gets its own column at the end. */
export function groupByStage(visits) {
  const grouped = {};
  for (const stage of STAGES) grouped[stage.key] = [];
  for (const visit of visits || []) {
    const key = visit && visit.stage ? visit.stage : "REQUESTED";
    (grouped[key] = grouped[key] || []).push(visit);
  }
  return grouped;
}

/** Which lane of the visit card a turn belongs to. */
export function laneOf(turn) {
  return SIDE_LANE[turn && turn.side] || "staff";
}

/** CSS colour of a health light; anything unknown is grey. */
export function healthColor(status) {
  return HEALTH_VAR[status] || "var(--idle)";
}

// ---- venue time -------------------------------------------------------------------------------------------------

const partsFormat = new Intl.DateTimeFormat("en-US", {
  timeZone: VENUE_TZ, year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
});
const timeFormat = new Intl.DateTimeFormat("ru-RU", { timeZone: VENUE_TZ, hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
const dayFormat = new Intl.DateTimeFormat("ru-RU", { timeZone: VENUE_TZ, weekday: "short", day: "2-digit", month: "2-digit" });

function toDate(value) {
  if (value == null || value === "") return null;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function venueParts(date) {
  const parts = {};
  for (const part of partsFormat.formatToParts(date)) parts[part.type] = Number(part.value);
  return parts;
}

/** Venue midnight (as a UTC Date) of the venue day that contains this instant. */
function venueMidnight(date) {
  const p = venueParts(date);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  const offset = Math.round((asUtc - Math.floor(date.getTime() / 1000) * 1000) / 60000) * 60000;
  return new Date(Date.UTC(p.year, p.month - 1, p.day) - offset);
}

/** "13:04" in venue time; "" for nothing. */
export function venueTime(value) {
  const date = toDate(value);
  return date ? timeFormat.format(date) : "";
}

/** "ср, 14.10" in venue time; "" for nothing. */
export function venueDay(value) {
  const date = toDate(value);
  return date ? dayFormat.format(date).replace(",", "") : "";
}

const DAY = 86400000;
const WINDOW_DAYS = { today: 1, "7d": 7, "30d": 30 };

/** Metric window ending at the next venue midnight: today, the last 7 or the last 30 venue days. */
export function venueWindow(kind, now = new Date()) {
  const days = WINDOW_DAYS[kind] || 1;
  const midnight = venueMidnight(now).getTime();
  return { from: new Date(midnight - (days - 1) * DAY).toISOString(), to: new Date(midnight + DAY).toISOString() };
}

/** The kanban's window: the last week and the next two weeks of visit days. */
export function visitsWindow(now = new Date()) {
  const midnight = venueMidnight(now).getTime();
  return { from: new Date(midnight - 7 * DAY).toISOString(), to: new Date(midnight + 15 * DAY).toISOString() };
}

// ---- visit card -------------------------------------------------------------------------------------------------

function clip(text, max) {
  return text.length <= max ? text : text.slice(0, max - 1) + "…";
}

function flat(value) {
  if (value == null) return "—";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

function pairs(map) {
  if (!map || typeof map !== "object") return "";
  return Object.entries(map).map(([key, value]) => key + "=" + flat(value)).join(", ");
}

function toolResult(payload) {
  if (!payload || payload.ok === false) return "ошибка " + ((payload && payload.error) || "?");
  return clip(pairs(payload.facts), 160) || "ok";
}

/** The visit's turns split into the three lanes; a tool call and its result make one mono line "tool args → result". */
export function lanesOf(turns) {
  const lanes = { guest: [], agents: [], staff: [] };
  const pending = new Map();
  for (const turn of turns || []) {
    const lane = laneOf(turn);
    const payload = turn.payload || {};
    const item = { at: turn.at, time: venueTime(turn.at), lane, side: SIDE_LABEL[turn.side] || "", text: turn.text || "",
      tool: false, hidden: turn.text === HIDDEN_TEXT || payload.hidden === true };
    if (turn.kind === "TOOL_CALL") {
      const name = payload.tool || turn.text || "tool";
      const args = clip(pairs(payload.arguments), 120);
      Object.assign(item, { tool: true, side: "", text: args ? name + " " + args : name });
      pending.set(turn.side + "/" + name, item);
    } else if (turn.kind === "TOOL_RESULT") {
      const name = payload.tool || turn.text || "tool";
      const call = pending.get(turn.side + "/" + name);
      if (call) {
        call.text += " → " + toolResult(payload);
        pending.delete(turn.side + "/" + name);
        continue;
      }
      Object.assign(item, { tool: true, side: "", text: name + " → " + toolResult(payload) });
    } else if (turn.kind === "FALLBACK") {
      Object.assign(item, { tool: true, text: "уход на FSM · " + item.text });
    }
    lanes[lane].push(item);
  }
  return lanes;
}

/** The card opened by default: the first seated visit, else the one with the newest turn. */
export function defaultOpenVisit(visits) {
  if (!visits || visits.length === 0) return null;
  const seated = visits.find((visit) => visit.stage === "SEATED");
  if (seated) return seated;
  const time = (visit) => (toDate(visit.lastTurnAt) || new Date(0)).getTime();
  return visits.reduce((best, visit) => (time(visit) > time(best) || (time(visit) === time(best) && visit.id > best.id) ? visit : best));
}

// ---- metrics ----------------------------------------------------------------------------------------------------

const number = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 1 });
const num = (value) => (value == null ? "—" : number.format(value).replace(/ | /g, " "));

function duration(seconds) {
  if (seconds == null) return "—";
  if (seconds < 60) return seconds + " с";
  const minutes = Math.floor(seconds / 60);
  return minutes < 60 ? minutes + " мин " + (seconds % 60) + " с" : num(minutes / 60) + " ч";
}

/** Tiles for the /metrics record: label, value and a small note. */
export function metricTiles(m) {
  if (!m) return [];
  return [
    { label: "Визитов", value: num(m.visits) },
    { label: "Сообщений гостя", value: num(m.guestMessagesAvg), note: "в среднем · цель 40" },
    { label: "Визитов 40+", value: num(m.visitsWith40Plus) },
    { label: "Переговоры → бронь", value: num(m.negotiationsClosed) + " / " + num(m.negotiationsTotal) },
    { label: "До подтверждения", value: duration(m.confirmMedianSeconds), note: m.confirmMedianSeconds == null ? "" : "медиана" },
    { label: "Ланч", value: Math.round((m.lunchConversion || 0) * 100) + " %" },
    { label: "GigaChat p95", value: m.modelP95Ms == null ? "—" : num(m.modelP95Ms / 1000) + " с",
      note: num(m.modelErrors) + " ошиб. из " + num(m.modelCalls) },
    { label: "Уходы на FSM", value: num(m.fallbacks) },
    { label: "Ошибки Saby", value: num(m.sabyErrors) },
    { label: "Отзывы", value: num(m.reviews) },
    { label: "Вернулись", value: num(m.returned) },
  ];
}

// ---- live stream ------------------------------------------------------------------------------------------------

/** The stream URL: EventSource cannot send headers, so the token goes as ?t= (accepted on this route only). */
export function streamUrl(token) {
  return API + "/stream?t=" + encodeURIComponent(token);
}

/** Sign-in link /board/#t=<token>: the fragment never reaches the server or its logs. */
export function tokenFromHash(hash) {
  const match = /^#t=(.+)$/.exec(hash || "");
  if (!match) return null;
  try { return decodeURIComponent(match[1]) || null; } catch (e) { return null; }
}

/** Reconnect delay: 2 s, doubling, at most 30 s. */
export function nextBackoff(previous) {
  return previous ? Math.min(previous * 2, 30000) : 2000;
}

// ---- browser ----------------------------------------------------------------------------------------------------

const store = {
  get(key) { try { return window.sessionStorage.getItem(key); } catch (e) { return null; } },
  set(key, value) { try { window.sessionStorage.setItem(key, value); } catch (e) { /* private mode: memory only */ } },
  remove(key) { try { window.sessionStorage.removeItem(key); } catch (e) { /* nothing stored */ } },
};

function el(tag, props, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value == null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key in node && key !== "list") node[key] = value;
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : String(child));
  }
  return node;
}

class Unauthorized extends Error {}

function startBoard() {
  const $ = (id) => document.getElementById(id);
  const state = { token: store.get(TOKEN_KEY) || "", visits: [], openId: null, userPicked: false, tab: "visits",
    window: "today", source: null, backoff: 0, retry: null, refresh: null, health: null };

  // ---- API ----
  async function api(path, options = {}) {
    const response = await fetch(API + path, {
      method: options.method || "GET", cache: "no-store", credentials: "omit",
      headers: { "X-Astor-Board-Token": state.token, ...(options.body ? { "Content-Type": "application/json" } : {}) },
      body: options.body ? JSON.stringify(options.body) : undefined,
    });
    if (response.status === 401) {
      signOut("Неверный токен");
      throw new Unauthorized();
    }
    return response;
  }
  async function json(path) {
    const response = await api(path);
    if (!response.ok) throw new Error("HTTP " + response.status);
    return response.json();
  }
  const query = (w) => "?from=" + encodeURIComponent(w.from) + "&to=" + encodeURIComponent(w.to);
  function failed(error, where) {
    if (error instanceof Unauthorized) return;
    setStatus("Ошибка загрузки: " + where);
  }

  // ---- login ----
  function signOut(message) {
    state.token = "";
    store.remove(TOKEN_KEY);
    closeStream();
    clearInterval(state.health);
    document.body.classList.add("locked");
    $("loginError").textContent = message || "";
    $("token").value = "";
    $("login").hidden = false;
    $("token").focus();
  }
  function signIn(token) {
    state.token = token;
    store.set(TOKEN_KEY, token);
    $("login").hidden = true;
    document.body.classList.remove("locked");
    setStatus("подключаюсь…");
    start();
  }
  // A sign-in link opens the board straight away and the token leaves the address bar.
  const linked = tokenFromHash(window.location.hash);
  if (linked) {
    window.history.replaceState(null, "", window.location.pathname + window.location.search);
    state.token = linked;
    store.set(TOKEN_KEY, linked);
  }
  $("loginForm").addEventListener("submit", (event) => {
    event.preventDefault();
    const token = $("token").value.trim();
    if (token) signIn(token);
  });
  $("logout").addEventListener("click", () => signOut(""));

  // ---- theme ----
  function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    $("theme").textContent = theme === "dark" ? "Светлая" : "Тёмная";
    try { window.sessionStorage.setItem(THEME_KEY, theme); } catch (e) { /* not remembered */ }
  }
  let savedTheme = null;
  try { savedTheme = window.sessionStorage.getItem(THEME_KEY); } catch (e) { /* default */ }
  applyTheme(savedTheme === "light" ? "light" : "dark");
  $("theme").addEventListener("click", () => applyTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark"));

  // ---- tabs ----
  const TABS = ["visits", "metrics", "arch", "lessons"];
  function showTab(tab) {
    state.tab = TABS.includes(tab) ? tab : "visits";
    for (const name of TABS) {
      $("tab-" + name).hidden = name !== state.tab;
      const button = document.querySelector('[data-tab="' + name + '"]');
      button.setAttribute("aria-selected", String(name === state.tab));
    }
    if (!state.token) return;
    if (state.tab === "metrics") loadMetrics();
    if (state.tab === "lessons") loadLessons();
    if (state.tab === "arch") loadHealth();
  }
  for (const button of document.querySelectorAll("[data-tab]")) {
    button.addEventListener("click", () => { history.replaceState(null, "", "#" + button.dataset.tab); showTab(button.dataset.tab); });
  }

  function setStatus(text) {
    $("status").textContent = text;
  }

  // ---- visits ----
  async function loadVisits() {
    try {
      state.visits = await json("/visits" + query(visitsWindow()));
      if (!state.visits.some((visit) => visit.id === state.openId)) {
        state.userPicked = false;
        state.openId = null;
      }
      if (!state.userPicked) {
        const open = defaultOpenVisit(state.visits);
        const changed = (open && open.id) !== state.openId;
        state.openId = open ? open.id : null;
        if (changed) loadVisit();
      }
      renderKanban();
      renderVisitHeader();
    } catch (error) {
      failed(error, "визиты");
    }
  }

  function renderKanban() {
    const grouped = groupByStage(state.visits);
    const columns = STAGES.concat(Object.keys(grouped).filter((key) => !STAGES.some((s) => s.key === key))
      .map((key) => ({ key, label: key })));
    $("kanban").replaceChildren(...columns.map((stage) => el("section", { class: "col", "aria-label": stage.label },
      el("h4", {}, stage.label, " ", el("b", {}, grouped[stage.key].length)),
      grouped[stage.key].map((visit) => el("button", {
        type: "button", class: "card" + (visit.id === state.openId ? " sel" : ""),
        "aria-pressed": String(visit.id === state.openId),
        onclick: () => { state.openId = visit.id; state.userPicked = true; renderKanban(); renderVisitHeader(); loadVisit(); },
      },
      el("span", { class: "who" }, visit.guest || "Гость"),
      el("span", { class: "meta" }, cardMeta(visit)),
      visit.hasSummary ? el("span", { class: "pill" }, "саммари ✓") : null)))));
  }

  function cardMeta(visit) {
    const bits = [venueDay(visit.visitDay)];
    const time = venueTime(visit.visitDay);
    if (time && time !== "00:00") bits.push(time);
    if (visit.table) bits.push("стол " + visit.table);
    bits.push(visit.guestMessages + " сообщ.");
    return bits.filter(Boolean).join(" · ");
  }

  function openVisit() {
    return state.visits.find((visit) => visit.id === state.openId) || null;
  }

  function renderVisitHeader() {
    const visit = openVisit();
    $("visit").hidden = !visit;
    $("noVisit").hidden = !!visit;
    if (!visit) return;
    const title = [visit.guest || "Гость", visit.table ? "стол " + visit.table : null,
      venueTime(visit.visitDay) !== "00:00" ? venueTime(visit.visitDay) : null].filter(Boolean).join(" · ");
    $("visitTitle").textContent = title;
    const stage = STAGES.find((s) => s.key === visit.stage);
    $("visitMeta").textContent = ["визит #" + visit.id, "бронь #" + visit.reservationId, stage ? stage.label : visit.stage,
      visit.phone, visit.lastTurnAt ? "последняя реплика " + venueTime(visit.lastTurnAt) : null].filter(Boolean).join(" · ");
  }

  function loadVisit() {
    loadTurns();
    loadSummary();
  }

  async function loadTurns() {
    const id = state.openId;
    if (id == null) return;
    try {
      const turns = await json("/visits/" + id + "/turns");
      if (id !== state.openId) return;
      const lanes = lanesOf(turns);
      $("lanes").replaceChildren(...LANES.map((lane) => el("section", { class: "lane " + lane.key },
        el("h5", {}, lane.label, " ", el("b", {}, lanes[lane.key].length)),
        lanes[lane.key].length === 0 ? el("p", { class: "empty" }, "пока тихо") : null,
        lanes[lane.key].map((item) => el("div", { class: "turn" + (item.tool ? " tool" : "") + (item.hidden ? " hidden" : "") },
          el("span", { class: "t" }, item.time),
          item.side ? el("span", { class: "side" }, item.side) : null,
          el("span", { class: "x" }, item.text))))));
    } catch (error) {
      failed(error, "реплики");
    }
  }

  // ---- summary ----
  function summaryBlock(label, value) {
    return el("div", {}, el("b", {}, label), value || "—");
  }
  function renderSummary(summary, note) {
    const next = summary ? [...(summary.nextTimeGuest || []), ...(summary.nextTimeAgents || [])] : [];
    $("summary").replaceChildren(
      summaryBlock("Гость хотел", summary && summary.guestWanted),
      summaryBlock("Ресторан предложил", summary && summary.venueOffered),
      summaryBlock("Чем кончилось", summary && summary.outcome),
      el("div", {}, el("b", {}, "В следующий раз"),
        next.length ? el("ul", {}, next.map((line) => el("li", {}, line))) : "—"));
    $("summaryNote").textContent = note || "";
  }
  async function loadSummary() {
    const id = state.openId;
    if (id == null) return;
    try {
      const response = await api("/visits/" + id + "/summary");
      if (id !== state.openId) return;
      if (response.ok) renderSummary(await response.json(), "");
      else if (response.status === 404) renderSummary(null, "Саммари ещё нет.");
      else if (response.status === 503) renderSummary(null, "Саммари выключены.");
      else renderSummary(null, "Саммари не загрузилось.");
    } catch (error) {
      failed(error, "саммари");
    }
  }
  $("rebuild").addEventListener("click", async () => {
    const id = state.openId;
    if (id == null) return;
    $("rebuild").disabled = true;
    $("summaryNote").textContent = "Пересобираю…";
    try {
      const response = await api("/visits/" + id + "/summary", { method: "POST" });
      if (response.ok) { renderSummary(await response.json(), "Пересобрано."); loadVisits(); }
      else if (response.status === 503) $("summaryNote").textContent = "Саммари выключены.";
      else if (response.status === 422) $("summaryNote").textContent = "Не удалось собрать саммари.";
      else $("summaryNote").textContent = "Ошибка: HTTP " + response.status;
    } catch (error) {
      failed(error, "саммари");
    } finally {
      $("rebuild").disabled = false;
    }
  });
  $("nextTimeForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const id = state.openId;
    const text = $("nextTime").value.trim();
    if (id == null || !text) return;
    try {
      const response = await api("/visits/" + id + "/next-time", { method: "POST", body: { text } });
      if (response.ok) { $("nextTime").value = ""; renderSummary(await response.json(), "Записано: агент учтёт в следующий раз."); }
      else if (response.status === 404) $("summaryNote").textContent = "У визита ещё нет саммари.";
      else if (response.status === 503) $("summaryNote").textContent = "Саммари выключены.";
      else $("summaryNote").textContent = "Ошибка: HTTP " + response.status;
    } catch (error) {
      failed(error, "заметка");
    }
  });

  // ---- metrics ----
  function tiles(list) {
    return list.map((tile) => el("div", { class: "tile" }, el("b", {}, tile.label),
      el("div", { class: "v" }, tile.value, tile.note ? el("small", {}, tile.note) : null)));
  }
  async function loadToday() {
    try {
      $("todayTiles").replaceChildren(...tiles(metricTiles(await json("/metrics" + query(venueWindow("today")))).slice(1, 9)));
    } catch (error) {
      failed(error, "метрики");
    }
  }
  async function loadMetrics() {
    try {
      $("metricTiles").replaceChildren(...tiles(metricTiles(await json("/metrics" + query(venueWindow(state.window))))));
    } catch (error) {
      failed(error, "метрики");
    }
  }
  for (const button of document.querySelectorAll("[data-window]")) {
    button.addEventListener("click", () => {
      state.window = button.dataset.window;
      for (const other of document.querySelectorAll("[data-window]")) other.setAttribute("aria-pressed", String(other === button));
      loadMetrics();
    });
  }

  // ---- health ----
  async function loadHealth() {
    try {
      const map = await json("/health-map");
      const byName = Object.fromEntries(map.map((item) => [item.component, item]));
      $("health").replaceChildren(...map.map((item) => el("span", { class: "node", title: item.detail || "" },
        el("i", { class: "dot", style: "background:" + healthColor(item.status) }), COMPONENTS[item.component] || item.component)));
      for (const node of document.querySelectorAll("[data-health]")) {
        const item = byName[node.dataset.health];
        node.style.setProperty("--node", healthColor(item && item.status));
        const detail = node.querySelector(".detail");
        if (detail) detail.textContent = item ? clip(item.detail || item.status, 20) : "нет данных";
      }
      $("archUpdated").textContent = "обновлено " + venueTime(new Date());
    } catch (error) {
      failed(error, "здоровье");
    }
  }

  // ---- lessons ----
  async function loadLessons() {
    try {
      const lessons = await json("/lessons?status=PROPOSED");
      $("lessonsEmpty").hidden = lessons.length > 0;
      $("lessons").replaceChildren(...lessons.map(lessonCard));
    } catch (error) {
      failed(error, "уроки");
    }
  }
  function lessonCard(lesson) {
    const text = el("textarea", { rows: 3, value: lesson.text || "", "aria-label": "Текст урока" });
    const note = el("span", { class: "note" });
    const card = el("article", { class: "lesson" },
      el("header", {}, el("span", { class: "pill" }, SIDE_NAME[lesson.side] || lesson.side || "—"),
        el("span", { class: "meta" }, [lesson.visitId ? "визит #" + lesson.visitId : null,
          lesson.createdAt ? venueDay(lesson.createdAt) + " " + venueTime(lesson.createdAt) : null].filter(Boolean).join(" · "))),
      text,
      el("div", { class: "actions" },
        el("button", { type: "button", class: "btn primary", onclick: () => decide("accept", { text: text.value.trim() }) }, "Принять"),
        el("button", { type: "button", class: "btn", onclick: () => decide("reject") }, "Отклонить"),
        note));
    async function decide(action, body) {
      for (const button of card.querySelectorAll("button")) button.disabled = true;
      try {
        const response = await api("/lessons/" + lesson.id + "/" + action, { method: "POST", body });
        if (response.ok) {
          card.classList.add("done");
          note.textContent = action === "accept" ? "Принят" : "Отклонён";
          setTimeout(() => { card.remove(); $("lessonsEmpty").hidden = $("lessons").children.length > 0; }, 900);
          return;
        }
        note.textContent = "Ошибка: HTTP " + response.status;
      } catch (error) {
        failed(error, "урок");
      }
      for (const button of card.querySelectorAll("button")) button.disabled = false;
    }
    return card;
  }

  // ---- live stream ----
  function closeStream() {
    if (state.source) state.source.close();
    state.source = null;
    clearTimeout(state.retry);
  }
  function openStream() {
    closeStream();
    if (!state.token || typeof EventSource === "undefined") return;
    const source = new EventSource(streamUrl(state.token));
    state.source = source;
    source.addEventListener("open", () => { state.backoff = 0; setStatus("живое обновление"); });
    source.addEventListener("turn", (event) => {
      let visitId = null;
      try { visitId = JSON.parse(event.data).visitId; } catch (e) { /* ignore a malformed event */ }
      clearTimeout(state.refresh);
      state.refresh = setTimeout(() => {
        loadVisits();
        if (visitId != null && visitId === state.openId) loadTurns();
      }, 300);
    });
    source.addEventListener("error", () => {
      source.close();
      if (state.source !== source) return;
      state.backoff = nextBackoff(state.backoff);
      setStatus("нет связи, повтор через " + state.backoff / 1000 + " с");
      state.retry = setTimeout(async () => {
        // A refused token never opens the stream: one plain request tells a bad token from a network fault.
        try { await api("/health-map"); openStream(); } catch (error) { if (!(error instanceof Unauthorized)) openStream(); }
      }, state.backoff);
    });
  }

  function start() {
    $("today").textContent = venueDay(new Date());
    loadVisits();
    loadToday();
    loadHealth();
    clearInterval(state.health);
    state.health = setInterval(loadHealth, 30000);
    openStream();
    showTab(state.tab);
  }

  showTab((location.hash || "").slice(1));
  if (state.token) signIn(state.token);
  else signOut("");
}

if (typeof window !== "undefined" && typeof document !== "undefined") {
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", startBoard);
  else startBoard();
}
