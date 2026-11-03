-- Michael Welly's Doha painting as an art auction lot.
-- Both the event and the lot are seeded as DRAFT: they are not listed to guests
-- and do not accept bids until a manager sets event + lot status to ACTIVE.
-- Starting price, step, final photo and title are intentionally left open
-- (metadata_json.pending) until the owner confirms them.

INSERT INTO art_auction_events (
    venue_code,
    event_code,
    title,
    description,
    status,
    metadata_json
) VALUES (
    'AERIS',
    'WELLY_DOHA_PAINTING',
    'Astor Art Auction: картина Михаила Уэлли из Дохи',
    'Аукцион одной работы, связанной с историей Astor в Катаре. Черновик: не опубликован.',
    'DRAFT',
    '{"source":"astor-series","public":false}'::jsonb
)
ON CONFLICT (venue_code, event_code) DO NOTHING;

INSERT INTO art_auction_lots (
    auction_event_id,
    lot_code,
    title,
    artist_name,
    description,
    status,
    starting_price_minor,
    min_step_minor,
    currency,
    metadata_json
)
SELECT
    e.id,
    'WELLY_DOHA_PAINTING_01',
    'Доха (рабочее название)',
    'Michael Welly',
    'Холст, начатый в Дохе: эскиз на мольберте снят 02.01.2023. Отсылает к ар-деко полотну, которое Михаил сфотографировал 24.08.2022, работая head bartender в Дохе в сезон Чемпионата мира 2022.',
    'DRAFT',
    NULL,
    NULL,
    'RUB',
    '{
      "public": false,
      "pending": ["final_title", "final_photo", "starting_price", "min_step", "currency", "owner_approval"],
      "evidence": {
        "sketch_photo": "astor-series/proofs/easel_doha_2023-01-02.jpg",
        "reference_photo": "astor-series/proofs/painting_doha_2022-08-24.jpg"
      },
      "note": "reference_photo shows a third-party artwork on a venue wall; it is evidence/inspiration only and is not the item for sale"
    }'::jsonb
FROM art_auction_events e
WHERE e.venue_code = 'AERIS'
  AND e.event_code = 'WELLY_DOHA_PAINTING'
ON CONFLICT (auction_event_id, lot_code) DO NOTHING;
