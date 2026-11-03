-- Sber500 x Disrupt, этап 1: псевдонимные пользователи по дням и источникам и сколько из них дошли до целевого действия.
-- Только чтение, без изменения схемы. Те же таблицы и та же псевдонимизация, что в export_calls.sql.
--
--   psql -v salt="$SBER500_SALT" -v since='2026-10-01' -At -f export_users.sql   (без строки заголовка)
--
-- Колонки: day, source, users, users_with_guest_message, users_with_llm_call, users_reached_reservation,
--          users_reached_bill, users_reached_any_target_action
-- day — календарный день по Екатеринбургу (Asia/Yekaterinburg, время AERIS).
-- day = 'total' — уникальные пользователи за весь период; source = 'all' — по всем источникам вместе (без двойного счёта).

\set ON_ERROR_STOP on

\if :{?salt}
\else
  \echo 'export_users.sql: задайте -v salt=... (SBER500_SALT)'
  \quit
\endif
\if :{?since}
\else
  \set since '1970-01-01'
\endif

WITH events AS (
    SELECT m.received_at AS ts, 'telegram'::text AS source,
           md5(:'salt' || coalesce(m.telegram_user_id::text, m.chat_id::text)) AS pseudo_user_id,
           'guest_message'::text AS kind
    FROM telegram_messages m
    WHERE m.received_at >= :'since'::timestamptz
    UNION ALL
    SELECT a.created_at, lower(coalesce(nullif(a.channel, ''), 'unknown')),
           md5(:'salt' || coalesce(a.telegram_user_id::text, a.chat_id::text)),
           'llm'
    FROM model_interaction_audit a
    WHERE a.created_at >= :'since'::timestamptz
      AND coalesce(a.telegram_user_id, a.chat_id) IS NOT NULL
    UNION ALL
    SELECT r.created_at, lower(r.source),
           md5(:'salt' || coalesce(r.telegram_user_id::text, r.chat_id::text)),
           'reservation'
    FROM table_reservation_orders r
    WHERE r.created_at >= :'since'::timestamptz
    UNION ALL
    SELECT b.created_at, lower(b.source),
           md5(:'salt' || coalesce(b.telegram_user_id::text, b.chat_id::text)),
           'bill'
    FROM guest_bills b
    WHERE b.created_at >= :'since'::timestamptz
),
by_day AS (
    SELECT to_char(ts AT TIME ZONE 'Asia/Yekaterinburg', 'YYYY-MM-DD') AS day, source, pseudo_user_id, kind
    FROM events
),
grouped AS (
    SELECT coalesce(day, 'total') AS day,
           coalesce(source, 'all') AS source,
           count(DISTINCT pseudo_user_id) AS users,
           count(DISTINCT pseudo_user_id) FILTER (WHERE kind = 'guest_message') AS users_with_guest_message,
           count(DISTINCT pseudo_user_id) FILTER (WHERE kind = 'llm') AS users_with_llm_call,
           count(DISTINCT pseudo_user_id) FILTER (WHERE kind = 'reservation') AS users_reached_reservation,
           count(DISTINCT pseudo_user_id) FILTER (WHERE kind = 'bill') AS users_reached_bill,
           count(DISTINCT pseudo_user_id) FILTER (WHERE kind IN ('reservation', 'bill')) AS users_reached_any_target_action,
           GROUPING(source) AS is_all_sources,
           GROUPING(day) AS is_total
    FROM by_day
    GROUP BY GROUPING SETS ((day, source), (source), (day), ())
)
SELECT concat_ws(',', day, source, users, users_with_guest_message, users_with_llm_call,
                 users_reached_reservation, users_reached_bill, users_reached_any_target_action)
FROM grouped
ORDER BY is_all_sources, source, is_total, day;
