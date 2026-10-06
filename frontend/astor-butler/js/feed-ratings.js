/* ============================================================
   Astor Concierge — ratings for the venue feed.

   Reads the snapshot published by scripts/concierge_ratings (data/ratings/snapshot.json).
   Pure functions: no DOM and no network, so the same file is checked in Node.
   ============================================================ */

(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.AstorFeedRatings = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const SOURCES = ["yandex", "gis"];

  // Both maps rate from 1 to 5. Anything else is "no rating", never a low rating.
  function validRating(value) {
    return typeof value === "number" && Number.isFinite(value) && value >= 1 && value <= 5;
  }

  function usable(snapshot) {
    return Boolean(snapshot && snapshot.schema === 1 && snapshot.venues && snapshot.staleAfterHours > 0);
  }

  /* Ratings the page may show for one venue. A stored rating is used only while the venue still
     links to the same object on the map; staleness is judged at view time, not at publish time. */
  function venueRatings(venue, snapshot, nowMs) {
    if (!usable(snapshot)) return [];
    const entries = snapshot.venues[venue.id] || {};
    const links = venue.mapUrls || {};
    return SOURCES.map((key) => ({ key, entry: entries[key] }))
      .filter((item) => item.entry && validRating(item.entry.rating) && item.entry.url === links[item.key])
      .map((item) => {
        const checked = Date.parse(item.entry.checkedAt);
        return {
          key: item.key,
          rating: item.entry.rating,
          checkedAt: Number.isNaN(checked) ? null : checked,
          stale: Number.isNaN(checked) || nowMs - checked > snapshot.staleAfterHours * 3600000,
        };
      });
  }

  function average(ratings) {
    if (!ratings.length) return null;
    // Rounded so that float noise (4.6999… vs 4.7) never decides the order.
    return Math.round((ratings.reduce((sum, item) => sum + item.rating, 0) / ratings.length) * 100) / 100;
  }

  /* Pinned venues are an editorial choice and keep their place whatever their rating is.
     The rest go by the average; venues without any rating go last; ties are ordered by name. */
  function rank(venues, snapshot, nowMs) {
    const rated = venues.map((venue) => {
      const ratings = venueRatings(venue, snapshot, nowMs);
      return { venue, ratings, average: average(ratings) };
    });
    const rest = rated.filter((entry) => !entry.venue.pinned);
    rest.sort((a, b) => {
      const byRating = (b.average === null ? -1 : b.average) - (a.average === null ? -1 : a.average);
      return byRating || String(a.venue.name).localeCompare(String(b.venue.name), "ru");
    });
    return { pinned: rated.filter((entry) => entry.venue.pinned), rest };
  }

  /* For the footer: per source, the oldest check among the ratings on the page, and whether any is stale. */
  function checks(entries) {
    const oldest = {};
    let stale = false;
    entries.forEach((entry) => entry.ratings.forEach((item) => {
      if (item.stale) stale = true;
      if (item.checkedAt !== null && (!(item.key in oldest) || item.checkedAt < oldest[item.key])) oldest[item.key] = item.checkedAt;
    }));
    return { oldest, stale };
  }

  return { validRating, usable, venueRatings, average, rank, checks };
});
