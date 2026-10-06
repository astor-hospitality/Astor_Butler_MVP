/* ============================================================
   Astor Concierge — venue feed.

   Order: pinned venues first (editorial choice), the rest by the
   average of Yandex Maps and 2GIS ratings. Venues live in
   data/venues.json; ratings come from data/ratings/snapshot.json,
   published by scripts/concierge_ratings, and ranked by js/feed-ratings.js.
   Works as a Telegram Mini App and as a plain web page.
   ============================================================ */

(function () {
  "use strict";

  const DATA_URL = new URL("../../data/venues.json", window.location.href);
  const RATINGS_URL = new URL("../../data/ratings/snapshot.json", window.location.href);
  const Ratings = window.AstorFeedRatings;
  // False while the snapshot cannot be read: then nothing on the page may look like a ranking.
  let ratingsAvailable = false;
  const telegram = window.Telegram && window.Telegram.WebApp ? window.Telegram.WebApp : null;
  const insideTelegram = Boolean(telegram && telegram.initData);

  /* ---------- Theme: Telegram scheme, then the site's stored choice, then the system ---------- */
  function applyTheme() {
    let stored = null;
    try { stored = window.localStorage.getItem("astor-theme"); } catch (e) { /* storage may be blocked */ }
    const systemLight = window.matchMedia("(prefers-color-scheme: light)").matches;
    const scheme = insideTelegram ? telegram.colorScheme : stored || (systemLight ? "light" : "dark");
    document.body.classList.toggle("light-theme", scheme === "light");
  }

  /* ---------- Rendering helpers ---------- */
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function formatRating(value) {
    return value.toLocaleString("ru-RU", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  }

  function httpsUrl(value) {
    if (typeof value !== "string" || !value) return null;
    try {
      const url = new URL(value, DATA_URL);
      return url.protocol === "https:" || url.origin === window.location.origin ? url.href : null;
    } catch (e) {
      return null;
    }
  }

  function link(label, href, className) {
    const anchor = el("a", className, label);
    anchor.href = href;
    anchor.rel = "noopener";
    const isTelegramLink = /^https:\/\/t\.me\//.test(href);
    if (!isTelegramLink) anchor.target = "_blank";
    // Inside Telegram a t.me link should switch to the chat instead of opening a browser tab.
    if (isTelegramLink && insideTelegram) {
      anchor.addEventListener("click", (event) => {
        event.preventDefault();
        telegram.openTelegramLink(href);
      });
    }
    return anchor;
  }

  const SOURCE_NAMES = { yandex: "Яндекс", gis: "2ГИС" };
  const MAP_NAMES = { yandex: "Яндекс Карты", gis: "2ГИС" };

  function formatDate(ms) {
    return new Date(ms).toLocaleDateString("ru-RU");
  }

  // A stale rating is still the last value we know, so it is shown, marked and dated.
  // compact: the narrow score column has no room for the word, so the mark there is the italic style.
  function sourceLabel(item, compact) {
    const text = SOURCE_NAMES[item.key] + " " + formatRating(item.rating);
    const label = el("span", item.stale ? "rating-stale" : null, item.stale && !compact ? text + " · устарел" : text);
    if (item.stale) label.setAttribute("aria-label", text + ", давно не проверялся");
    if (item.checkedAt !== null) label.title = "Проверено " + formatDate(item.checkedAt);
    return label;
  }

  // stacked: one source per line, for the narrow score column of the ranked list.
  function ratingBlock(entry, className, stacked) {
    const block = el("div", className);
    if (entry.average === null) {
      block.append(el("span", "rating-sources", ratingsAvailable ? "Рейтинг пока не проверен" : "Рейтинг недоступен"));
      return block;
    }
    block.append(el("span", "rating-value", formatRating(entry.average)));
    let line = null;
    entry.ratings.forEach((item) => {
      if (stacked || !line) {
        line = el("span", "rating-sources");
        block.append(line);
      } else {
        line.append(" · ");
      }
      line.append(sourceLabel(item, stacked));
    });
    return block;
  }

  function metaText(venue) {
    return [venue.category, venue.cuisine, venue.address].filter(Boolean).join(" · ");
  }

  function media(venue) {
    const box = el("div", "venue-media");
    const photos = (Array.isArray(venue.photos) ? venue.photos : [])
      .map((photo) => ({ src: httpsUrl(photo && photo.src), alt: (photo && photo.alt) || venue.name }))
      .filter((photo) => photo.src);
    if (photos.length) {
      const gallery = el("div", "venue-gallery");
      photos.forEach((photo, index) => {
        const img = el("img");
        img.src = photo.src;
        img.alt = photo.alt;
        img.loading = index === 0 ? "eager" : "lazy";
        img.decoding = "async";
        gallery.append(img);
      });
      box.append(gallery);
      if (photos.length > 1) {
        const count = el("span", "venue-count", "1 / " + photos.length);
        gallery.addEventListener("scroll", () => {
          const current = Math.round(gallery.scrollLeft / gallery.clientWidth) + 1;
          count.textContent = current + " / " + photos.length;
        }, { passive: true });
        box.append(count);
      }
    } else {
      const empty = el("div", "venue-media-empty", venue.name);
      empty.setAttribute("aria-hidden", "true");
      box.append(empty);
    }
    if (venue.badge) box.append(el("span", "venue-badge", venue.badge));
    return box;
  }

  function pinnedCard(entry) {
    const venue = entry.venue;
    const card = el("article", "venue-card");
    const body = el("div", "venue-body");
    body.append(el("h2", "venue-name", venue.name), el("p", "venue-meta", metaText(venue)));
    body.append(ratingBlock(entry, "venue-rating"));

    const actions = el("div", "venue-actions");
    const booking = venue.connected ? httpsUrl(venue.bookingUrl) : null;
    if (booking) actions.append(link("Забронировать", booking, "btn btn-primary"));
    const site = httpsUrl(venue.siteUrl);
    if (site) actions.append(link("Сайт", site, "btn btn-quiet"));
    if (actions.childElementCount) body.append(actions);

    if (booking) body.append(el("p", "venue-note", "Заявка уйдёт в Telegram-бот заведения. Бронь подтверждает команда ресторана."));
    card.append(media(venue), body);
    return card;
  }

  function rankItem(entry, position) {
    const venue = entry.venue;
    const item = el("li", "rank-item");
    const info = el("div");
    info.append(el("p", "rank-name", venue.name), el("p", "rank-meta", metaText(venue)));

    const links = el("div", "rank-links");
    const maps = venue.mapUrls || {};
    const yandex = httpsUrl(maps.yandex);
    const gis = httpsUrl(maps.gis);
    if (yandex) links.append(link("Яндекс Карты", yandex));
    if (gis) links.append(link("2ГИС", gis));
    const booking = venue.connected ? httpsUrl(venue.bookingUrl) : null;
    if (booking) links.append(link("Забронировать", booking));
    else links.append(el("span", "rank-state", "Бронь через Astor пока недоступна"));
    info.append(links);

    item.append(el("span", "rank-pos", String(position)), info, ratingBlock(entry, "rank-score", true));
    return item;
  }

  // What the footer may honestly say about the ratings on the page.
  function ratingsNote(entries) {
    if (!ratingsAvailable) return "Рейтинги сейчас недоступны, поэтому список идёт по названию.";
    const summary = Ratings.checks(entries);
    const parts = Object.keys(MAP_NAMES).filter((key) => key in summary.oldest)
      .map((key) => MAP_NAMES[key] + " — " + formatDate(summary.oldest[key]));
    if (!parts.length) return "Рейтинги пока не проверены.";
    return "Рейтинги проверены: " + parts.join(", ") + "." + (summary.stale ? " Курсивом — те, что давно не проверялись." : "");
  }

  function render(data, snapshot) {
    const venues = Array.isArray(data && data.venues) ? data.venues.filter((venue) => venue && venue.name) : [];
    ratingsAvailable = Ratings.usable(snapshot);
    const ranked = Ratings.rank(venues, snapshot, Date.now());
    const status = document.getElementById("feedStatus");
    const pinnedBox = document.getElementById("feedPinned");
    const rankedBox = document.getElementById("feedRanked");
    const list = document.getElementById("feedRankList");

    document.getElementById("feedCity").textContent = (data && data.city) || "";
    pinnedBox.replaceChildren(...ranked.pinned.map((entry) => pinnedCard(entry)));
    pinnedBox.hidden = !ranked.pinned.length;
    list.replaceChildren(...ranked.rest.map((entry, index) => rankItem(entry, index + 1)));
    rankedBox.hidden = !ranked.rest.length;
    document.getElementById("feedRankedTitle").textContent = ratingsAvailable ? "По рейтингу" : "Заведения";
    rankedBox.querySelector(".feed-section-note").textContent = ratingsAvailable
      ? "Среднее между Яндекс Картами и 2ГИС."
      : "По названию: рейтинги сейчас недоступны.";

    status.hidden = venues.length > 0;
    if (!venues.length) status.textContent = "Заведения пока не добавлены.";

    const note = document.getElementById("feedRatingsDate");
    note.hidden = !venues.length;
    note.textContent = ratingsNote(ranked.pinned.concat(ranked.rest));
  }

  // Venues are required. Ratings are not: without them the feed still opens, just unranked.
  async function loadRatings() {
    try {
      const response = await fetch(RATINGS_URL, { cache: "no-cache" });
      return response.ok ? await response.json() : null;
    } catch (error) {
      return null;
    }
  }

  async function load() {
    try {
      const [response, snapshot] = await Promise.all([fetch(DATA_URL, { cache: "no-cache" }), loadRatings()]);
      if (!response.ok) throw new Error("Feed data error: " + response.status);
      render(await response.json(), snapshot);
    } catch (error) {
      const status = document.getElementById("feedStatus");
      status.hidden = false;
      status.textContent = "Не удалось загрузить список. Обновите страницу.";
    }
  }

  applyTheme();
  if (insideTelegram) {
    telegram.ready();
    telegram.expand();
    telegram.onEvent("themeChanged", applyTheme);
  }
  load();

  // Expose for checks and future backend integration.
  window.AstorFeed = { render };
})();
