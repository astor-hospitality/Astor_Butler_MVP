/* The stand-in Presto: one point of sale, one hall with named tables, a price list with the dishes Butler knows,
   and bookings that live in memory. Shapes follow saby.ru/help/integration/api/app_presto (read 06.10.2026);
   where the documentation shows no response (order/create, order/{id}) the shape is our assumption and is
   marked as such in README.md. Record mode (proxy.mjs) is how the assumption gets checked against the real thing. */

import { randomUUID } from "node:crypto";

export const STATE = { DRAFT: 5, ONLINE: 10, CONFIRMED: 20, IN_WORK: 50, BLOCKED: 150, SHIPPED: 180, CLOSED: 200, CANCELLED: 220 };
export const PRODUCT_STATE = { NEW: 1000, ACCEPTED: 1001, IN_PROGRESS: 1002, CANCELLED: 1998, DONE: 1999 };

const SEATING_MINUTES = 120;

export function createWorld({ pointId = 206, hallId = 271, priceListId = 4, tables = defaultTables(), dishes = [], now = () => Date.now(), autoConfirmSeconds = 0 } = {}) {
  const orders = new Map();
  const tokens = new Set();
  const catalog = dishes.map((dish, index) => ({
    id: 1000 + index,
    externalId: randomUUID(),
    name: dish.name,
    cost: dish.cost ?? null,
    hierarchicalId: dish.section ? sectionId(dish.section) : 1,
    hierarchicalParent: 1,
    isParent: false,
    isKit: false,
    description: dish.description || "",
  }));
  const sections = [...new Set(dishes.map((d) => d.section).filter(Boolean))].map((name) => ({
    id: sectionId(name), name, hierarchicalId: sectionId(name), hierarchicalParent: 1, isParent: true,
  }));

  const point = {
    id: pointId, name: "AERIS (stub)", product: "restaurant", address: "ул. Мамина-Сибиряка, 58", locality: "Екатеринбург",
    phone: "+79965922116", phones: ["+79965922116"], prices: [priceListId], defaultPriceLists: [priceListId], defaultPriceList: priceListId,
    worktime: [{ start: "12:00", stop: "02:00", workdays: [1, 2, 3, 4, 5, 6, 7] }], latitude: "56.8389", longitude: "60.6057", image: "",
    paymentKinds: [],
  };

  function issueToken() {
    const token = "stub-" + randomUUID();
    tokens.add(token);
    return token;
  }

  function validToken(token) {
    return !!token && tokens.has(token);
  }

  function parseLocal(text) {
    // "ГГГГ-ММ-ДД ЧЧ:ММ:СС" in the venue's own time; the stub keeps everything in that frame.
    const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?$/.exec(String(text || "").trim());
    if (!m) return null;
    return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  }

  function overlaps(order, startMs, minutes = SEATING_MINUTES) {
    const a0 = order.startMs;
    const a1 = a0 + SEATING_MINUTES * 60_000;
    const b1 = startMs + minutes * 60_000;
    return a0 < b1 && startMs < a1;
  }

  function active(order) {
    return order.state !== STATE.CANCELLED && order.state !== STATE.CLOSED;
  }

  function tick() {
    if (!autoConfirmSeconds) return;
    const t = now();
    for (const order of orders.values()) {
      if (order.state === STATE.ONLINE && t - order.createdMs >= autoConfirmSeconds * 1000) {
        confirm(order.externalId);
      }
    }
  }

  function hallList({ date, hallId: wantedHall }) {
    tick();
    const startMs = parseLocal(date);
    if (startMs === null) return { error: 400, message: "date must be ГГГГ-ММ-ДД ЧЧ:ММ:СС" };
    if (wantedHall && String(wantedHall) !== String(hallId)) return { halls: [], outcome: { hasmore: false } };
    const items = tables.map((table) => {
      const busy = [...orders.values()].some((o) => active(o) && o.booking.table === table.id && overlaps(o, startMs));
      return { id: table.id, kind: "table", name: table.name, position: { x: table.x, y: table.y, z: 0 }, isBookingLocked: !!table.locked,
        busy, capacity: table.capacity, endTime: null, type: 1, visible: true, disposition: 0 };
    });
    return { halls: [{ id: hallId, name: "Основной зал", active: true, background: { position: "", repeat: "", size: "", url: "" },
      relation: { left: 0, top: 0, right: 0, bottom: 0 }, items }], outcome: { hasmore: false } };
  }

  function calendar({ fromDate, toDate }) {
    const from = parseRu(fromDate);
    const to = parseRu(toDate);
    if (from === null || to === null || to < from) return { error: 400, message: "fromDate/toDate must be дд.мм.гггг" };
    const dates = [];
    for (let d = from; d <= to && dates.length < 31; d += 86_400_000) {
      const day = new Date(d);
      const iso = `${day.getUTCFullYear()}-${pad(day.getUTCMonth() + 1)}-${pad(day.getUTCDate())}`;
      // Open 12:00–02:00: half-hour intervals 24..47 (12:30 … 24:00) plus 0..3 of the next day are offered as 24..47 here.
      dates.push({ date: iso, halls: [{ hallId, intervals: Array.from({ length: 24 }, (_, i) => 24 + i) }] });
    }
    return { dates, outcome: { hasMore: false } };
  }

  function create(body) {
    tick();
    const problems = [];
    if (body?.product !== "restaurant") problems.push("product must be restaurant");
    if (String(body?.pointId) !== String(pointId)) problems.push("unknown pointId");
    const startMs = parseLocal(body?.datetime);
    if (startMs === null) problems.push("datetime must be ГГГГ-ММ-ДД ЧЧ:ММ:СС");
    if (!body?.customer?.name) problems.push("customer.name is required");
    if (!body?.customer?.phone) problems.push("customer.phone is required");
    const booking = body?.booking || {};
    const visitors = Number(booking.visitors);
    if (!(visitors >= 1)) problems.push("booking.visitors is required");
    let table = null;
    if (!booking.woTable) {
      if (String(booking.hall) !== String(hallId)) problems.push("booking.hall is required and must exist");
      table = tables.find((t) => String(t.id) === String(booking.table));
      if (!table) problems.push("booking.table must exist");
      else if (table.locked) problems.push("table is locked for booking");
      else if (table.capacity < visitors) problems.push("table is too small");
      else if ([...orders.values()].some((o) => active(o) && o.booking.table === table.id && overlaps(o, startMs))) problems.push("table is busy");
    }
    const nomenclatures = Array.isArray(body?.nomenclatures) ? body.nomenclatures : [];
    for (const item of nomenclatures) {
      if (!catalog.some((c) => c.id === item.id || c.externalId === item.externalId)) problems.push("unknown nomenclature " + (item.id ?? item.externalId));
    }
    if (problems.length) return { error: 400, message: problems.join("; ") };

    const externalId = randomUUID();
    const order = {
      externalId,
      createdMs: now(),
      startMs,
      state: STATE.ONLINE,
      productState: PRODUCT_STATE.NEW,
      payState: 0,
      product: "restaurant",
      pointId,
      datetime: body.datetime,
      comment: body.comment || "",
      customer: { ...body.customer },
      booking: { hall: hallId, table: table ? table.id : null, visitors, woTable: !table },
      nomenclatures: nomenclatures.map((n) => ({ ...n })),
    };
    orders.set(externalId, order);
    return { externalId };
  }

  function get(externalId) {
    tick();
    const order = orders.get(externalId);
    return order ? publicOrder(order) : null;
  }

  function state(externalId) {
    tick();
    const order = orders.get(externalId);
    return order ? { state: order.state, payState: order.payState, payments: [], productState: order.productState } : null;
  }

  function update(externalId, body) {
    tick();
    const order = orders.get(externalId);
    if (!order) return null;
    if (!active(order)) return { error: 409, message: "order is cancelled or closed" };
    if (body?.datetime !== undefined) {
      const startMs = parseLocal(body.datetime);
      if (startMs === null) return { error: 400, message: "datetime must be ГГГГ-ММ-ДД ЧЧ:ММ:СС" };
      order.datetime = body.datetime;
      order.startMs = startMs;
    }
    if (body?.comment !== undefined) order.comment = body.comment;
    if (body?.customer) order.customer = { ...order.customer, ...body.customer };
    if (body?.booking) {
      if (body.booking.visitors !== undefined) order.booking.visitors = Number(body.booking.visitors);
      if (body.booking.woTable === false && body.booking.table !== undefined) {
        const table = tables.find((t) => String(t.id) === String(body.booking.table));
        if (!table) return { error: 400, message: "booking.table must exist" };
        order.booking.table = table.id;
        order.booking.woTable = false;
      }
    }
    if (Array.isArray(body?.nomenclatures)) {
      for (const item of body.nomenclatures) {
        if (!catalog.some((c) => c.id === item.id || c.externalId === item.externalId)) return { error: 400, message: "unknown nomenclature " + (item.id ?? item.externalId) };
      }
      order.nomenclatures = body.nomenclatures.map((n) => ({ ...n }));
    }
    return {};
  }

  function cancel(externalId) {
    tick();
    const order = orders.get(externalId);
    if (!order) return null;
    order.state = STATE.CANCELLED;
    order.productState = PRODUCT_STATE.CANCELLED;
    return {};
  }

  /** What the staff do in Presto: accept the booking. */
  function confirm(externalId) {
    const order = orders.get(externalId);
    if (!order) return null;
    if (!active(order)) return { error: 409, message: "order is cancelled or closed" };
    order.state = STATE.CONFIRMED;
    order.productState = PRODUCT_STATE.ACCEPTED;
    return publicOrder(order);
  }

  /** What the staff do in Presto: seat the booking at a table (for a woTable booking). */
  function seat(externalId, tableId) {
    const order = orders.get(externalId);
    const table = tables.find((t) => String(t.id) === String(tableId));
    if (!order || !table) return null;
    order.booking.table = table.id;
    order.booking.woTable = false;
    return publicOrder(order);
  }

  function priceList() {
    return { priceLists: [{ id: priceListId, name: "Основное меню" }], outcome: { hasMore: false } };
  }

  function nomenclatureList({ searchString, priceListId: wanted, pageSize }) {
    if (wanted && String(wanted) !== String(priceListId)) return { nomenclatures: [], outcome: { hasMore: false } };
    const needle = normalize(searchString);
    const rows = [...sections, ...catalog].filter((row) => !needle || normalize(row.name).includes(needle));
    return { nomenclatures: rows.slice(0, Math.min(Number(pageSize) || 50, 1000)), outcome: { hasMore: false } };
  }

  function list() {
    tick();
    return [...orders.values()].map(publicOrder);
  }

  function reset() {
    orders.clear();
  }

  function publicOrder(order) {
    return { externalId: order.externalId, state: order.state, productState: order.productState, payState: order.payState,
      product: order.product, pointId: order.pointId, datetime: order.datetime, comment: order.comment,
      customer: { ...order.customer }, booking: { ...order.booking }, nomenclatures: order.nomenclatures.map((n) => ({ ...n })) };
  }

  return { point, hallId, priceListId, tables, catalog, issueToken, validToken, hallList, calendar, create, get, state, update, cancel, confirm, seat, priceList, nomenclatureList, list, reset };
}

export function defaultTables() {
  return [
    { id: 3031, name: "1", capacity: 2, x: 1, y: 1 },
    { id: 3032, name: "2", capacity: 2, x: 2, y: 1 },
    { id: 3033, name: "3", capacity: 4, x: 3, y: 1 },
    { id: 3034, name: "4", capacity: 4, x: 1, y: 2 },
    { id: 3035, name: "5", capacity: 4, x: 2, y: 2 },
    { id: 3036, name: "6", capacity: 6, x: 3, y: 2 },
    { id: 3037, name: "7", capacity: 6, x: 1, y: 3 },
    { id: 3038, name: "8", capacity: 8, x: 2, y: 3 },
    { id: 3039, name: "Бар", capacity: 10, x: 3, y: 3, locked: true },
  ];
}

function parseRu(text) {
  const m = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(String(text || "").trim());
  return m ? Date.UTC(+m[3], +m[2] - 1, +m[1]) : null;
}

const pad = (n) => String(n).padStart(2, "0");
const normalize = (s) => String(s || "").trim().toLowerCase().replace(/ё/g, "е").replace(/\s+/g, " ");
let nextSection = 100;
const sectionIds = new Map();
function sectionId(name) {
  if (!sectionIds.has(name)) sectionIds.set(name, nextSection++);
  return sectionIds.get(name);
}
