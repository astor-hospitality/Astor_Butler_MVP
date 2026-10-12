-- Synthetic rows for the local "before" base, so that a backup has something to lose.
-- No real guest, employee or chat is described here: every identifier is made up.
BEGIN;

INSERT INTO users (telegram_id, first_name, last_name, username, role, display_name)
SELECT 9000000000 + n, 'Rehearsal', 'Guest ' || n, 'rehearsal_guest_' || n, 'GUEST', 'Rehearsal Guest ' || n
FROM generate_series(1, 25) AS n;

INSERT INTO concierge_requests (chat_id, telegram_user_id, user_id, venue_code, request_type, guest_name, request_text, metadata_json)
SELECT 9000000000 + (n % 25) + 1, 9000000000 + (n % 25) + 1, u.id, 'AERIS', 'GENERAL',
       'Rehearsal Guest ' || ((n % 25) + 1), 'Rehearsal request ' || n, jsonb_build_object('rehearsal', true, 'n', n)
FROM generate_series(1, 40) AS n
JOIN users u ON u.telegram_id = 9000000000 + (n % 25) + 1;

INSERT INTO guest_preferences (chat_id, telegram_user_id, user_id, venue_code, category, preference_text)
SELECT 9000000000 + (n % 25) + 1, 9000000000 + (n % 25) + 1, u.id, 'AERIS', 'SEATING', 'Rehearsal preference ' || n
FROM generate_series(1, 30) AS n
JOIN users u ON u.telegram_id = 9000000000 + (n % 25) + 1;

COMMIT;
