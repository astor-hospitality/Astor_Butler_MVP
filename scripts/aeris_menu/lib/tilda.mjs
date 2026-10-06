/* The AERIS kitchen menu lives in a Tilda catalog block. The page itself holds only the block's ids;
   the dishes come from store.tildaapi.com as JSON. Nothing here guesses: a page without a catalog
   block or an answer in an unexpected shape is reported, not patched over. */

const STORE_API = "https://store.tildaapi.com/api/getproductslist/";
const PAGE_SIZE = 100;
const MAX_PAGES = 10;

/** Finds the catalog block ids in a Tilda page: `recid:'…',storepart:'…'` inside its inline script. */
export function discoverCatalog(html) {
  const match = /recid:\s*'(\d+)'\s*,\s*storepart:\s*'(\d+)'/.exec(html || "");
  if (!match) return null;
  return { recid: match[1], storepart: match[2] };
}

export function catalogUrl({ recid, storepart }, slice = 1) {
  const params = new URLSearchParams({
    storepartuid: storepart, recid, getallparts: "true", getoptions: "true",
    slice: String(slice), size: String(PAGE_SIZE), getpartinfo: "true",
  });
  return STORE_API + "?" + params.toString();
}

/** All products of the block, page by page, with the section list from the first answer. */
export async function fetchCatalog(doFetch, ids) {
  const products = [];
  let parts = null;
  let total = null;
  for (let slice = 1; slice <= MAX_PAGES; slice++) {
    const response = await doFetch(catalogUrl(ids, slice), { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw new Error("Tilda store answered HTTP " + response.status);
    const page = await response.json();
    if (!Array.isArray(page.products)) throw new Error("Tilda store answer has no products array");
    if (parts === null) {
      parts = Array.isArray(page.parts) ? page.parts : [];
      total = Number(page.total);
    }
    products.push(...page.products);
    if (page.products.length < PAGE_SIZE || products.length >= total) break;
  }
  return { total, parts, products };
}

const toRub = (value) => {
  const number = Number(String(value ?? "").replace(",", "."));
  return Number.isFinite(number) && number > 0 ? Math.round(number) : null;
};

const clean = (text) => String(text ?? "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();

/** Sections in the venue's order, each with its dishes in the venue's order. Sections are the
    catalog's own non-root parts; a dish without a known section goes to "Без раздела". */
export function normalizeCatalog(raw, { source, fetchedAt }) {
  const sections = (raw.parts || [])
    .filter((part) => !part.root)
    .sort((a, b) => Number(a.sort) - Number(b.sort))
    .map((part) => ({ uid: String(part.uid), title: clean(part.title), dishes: [] }));
  const byUid = new Map(sections.map((section) => [section.uid, section]));
  const orphan = { uid: null, title: "Без раздела", dishes: [] };

  for (const product of [...(raw.products || [])].sort((a, b) => Number(a.sort) - Number(b.sort))) {
    const dish = {
      uid: String(product.uid),
      title: clean(product.title),
      description: clean(product.descr || product.text) || null,
      priceRub: toRub(product.price),
      oldPriceRub: toRub(product.priceold),
      sku: clean(product.sku) || null,
      mark: clean(product.mark) || null,
      url: product.url || null,
    };
    const partUids = parsePartUids(product.partuids);
    const section = partUids.map((uid) => byUid.get(uid)).find(Boolean);
    (section || orphan).dishes.push(dish);
  }
  if (orphan.dishes.length) sections.push(orphan);

  const dishCount = sections.reduce((n, section) => n + section.dishes.length, 0);
  return {
    venue: "AERIS",
    menu: "kitchen",
    source,
    fetchedAt,
    catalogTotal: Number(raw.total) || null,
    dishCount,
    sections,
  };
}

function parsePartUids(value) {
  if (Array.isArray(value)) return value.map(String);
  try {
    const parsed = JSON.parse(value || "[]");
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

/** The same menu as a page a person reads: section headings and "dish — price" lines. */
export function renderMarkdown(menu) {
  const lines = [`# AERIS — кухня (с сайта ${menu.source})`, "", `Снято: ${menu.fetchedAt}. Блюд: ${menu.dishCount}.`, "",
    "Источник правды — сайт и ресторан; цены и состав могут измениться без предупреждения.", ""];
  for (const section of menu.sections) {
    lines.push(`## ${section.title}`, "");
    for (const dish of section.dishes) {
      const price = dish.priceRub === null ? "цена не указана" : `${dish.priceRub} ₽`;
      lines.push(`- **${dish.title}** — ${price}`);
      if (dish.description) lines.push(`  ${dish.description}`);
    }
    lines.push("");
  }
  return lines.join("\n");
}
