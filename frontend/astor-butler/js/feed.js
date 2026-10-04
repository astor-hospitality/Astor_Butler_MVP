/* ============================================================
   Astor Concierge — venue feed.

   Order: pinned venues first (editorial choice), the rest by the
   average of Yandex Maps and 2GIS ratings. Data lives in
   data/venues.json; ratings are entered by hand, never scraped.
   Works as a Telegram Mini App and as a plain web page.
   ============================================================ */

(function () {
  "use strict";

  const DATA_URL = new URL("../../data/venues.json", window.location.href);
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

  /* ---------- Ranking ---------- */
  function validRating(value) {
    return typeof value === "number" && value > 0 && value <= 5;
  }

  function averageRating(ratings) {
    const values = [ratings && ratings.yandex, ratings && ratings.gis].filter(validRating);
    if (!values.length) return null;
    // Rounded so that float noise (4.6999… vs 4.7) never decides the order.
    return Math.round((values.reduce((sum, value) => sum + value, 0) / values.length) * 100) / 100;
  }

  function rank(venues) {
    const pinned = venues.filter((venue) => venue.pinned);
    const rest = venues
      .filter((venue) => !venue.pinned)
      .map((venue) => ({ venue, average: averageRating(venue.ratings) }));
    // Venues without any rating go last; ties are ordered by name.
    rest.sort((a, b) => {
      const byRating = (b.average === null ? -1 : b.average) - (a.average === null ? -1 : a.average);
      return byRating || String(a.venue.name).localeCompare(String(b.venue.name), "ru");
    });
    return { pinned, rest };
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

  function sourceLabels(ratings) {
    const parts = [];
    if (ratings && validRating(ratings.yandex)) parts.push("Яндекс " + formatRating(ratings.yandex));
    if (ratings && validRating(ratings.gis)) parts.push("2ГИС " + formatRating(ratings.gis));
    return parts;
  }

  // stacked: one source per line, for the narrow score column of the ranked list.
  function ratingBlock(ratings, className, stacked) {
    const block = el("div", className);
    const average = averageRating(ratings);
    if (average === null) {
      block.append(el("span", "rating-sources", "Рейтинг не внесён"));
      return block;
    }
    block.append(el("span", "rating-value", formatRating(average)));
    const labels = sourceLabels(ratings);
    (stacked ? labels : [labels.join(" · ")]).forEach((label) => block.append(el("span", "rating-sources", label)));
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

  function pinnedCard(venue) {
    const card = el("article", "venue-card");
    const body = el("div", "venue-body");
    body.append(el("h2", "venue-name", venue.name), el("p", "venue-meta", metaText(venue)));
    body.append(ratingBlock(venue.ratings, "venue-rating"));

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

    item.append(el("span", "rank-pos", String(position)), info, ratingBlock(venue.ratings, "rank-score", true));
    return item;
  }

  function render(data) {
    const venues = Array.isArray(data && data.venues) ? data.venues.filter((venue) => venue && venue.name) : [];
    const ranked = rank(venues);
    const status = document.getElementById("feedStatus");
    const pinnedBox = document.getElementById("feedPinned");
    const rankedBox = document.getElementById("feedRanked");
    const list = document.getElementById("feedRankList");

    document.getElementById("feedCity").textContent = (data && data.city) || "";
    pinnedBox.replaceChildren(...ranked.pinned.map(pinnedCard));
    pinnedBox.hidden = !ranked.pinned.length;
    list.replaceChildren(...ranked.rest.map((entry, index) => rankItem(entry, index + 1)));
    rankedBox.hidden = !ranked.rest.length;

    status.hidden = venues.length > 0;
    if (!venues.length) status.textContent = "Заведения пока не добавлены.";

    const date = document.getElementById("feedRatingsDate");
    const updated = data && data.ratingsUpdatedAt ? new Date(data.ratingsUpdatedAt) : null;
    date.hidden = !updated || Number.isNaN(updated.getTime());
    if (!date.hidden) date.textContent = "Рейтинги внесены вручную " + updated.toLocaleDateString("ru-RU") + ".";
  }

  async function load() {
    try {
      const response = await fetch(DATA_URL, { cache: "no-cache" });
      if (!response.ok) throw new Error("Feed data error: " + response.status);
      render(await response.json());
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
  window.AstorFeed = { averageRating, rank, render };
})();
