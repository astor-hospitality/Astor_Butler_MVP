-- Sber500 x Disrupt, этап 1: единый журнал вызовов из СУЩЕСТВУЮЩИХ таблиц (без изменения схемы). Только чтение.
--
-- Запуск (обычно через export_calls.sh):
--   psql -v salt="$SBER500_SALT" -v since='2026-10-01' -At -f export_calls.sql   (без строки заголовка)
--
-- Колонки: ts, source, pseudo_user_id, call_type, provider_or_system, result, error_code,
--          tokens_in, tokens_out, cost_rub, latency_ms, correlation_id
--
-- Приватность: в выгрузку не попадают telegram_user_id / chat_id, телефоны, имена, текст промптов и сообщений.
-- pseudo_user_id = md5(salt || coalesce(telegram_user_id, chat_id)); соль живёт только в окружении оператора.
--
-- Источники (сверено с миграциями src/main/resources/db/changelog):
--   model_interaction_audit   2026-06-29-model-interaction-audit.sql  -> call_type 'llm'
--   saby_call_log             2026-10-07-guest-billing.sql            -> call_type 'tool:saby'
--   telegram_messages         2026-06-03-consent-telegram-events.yaml (+ user_id из 2026-06-04-normalize-identity.sql)
--                                                                     -> call_type 'guest_message'
--   table_reservation_orders  2026-06-05-table-booking-foundation.sql -> call_type 'target_action:reservation'
--   guest_bills               2026-10-07-guest-billing.sql            -> call_type 'target_action:bill'
--
-- Токены: шлюзы кладут ответ провайдера в metadata->'usage' (GigaChat / OpenAI-совместимые: prompt_tokens,
-- completion_tokens; Yandex Foundation Models: inputTextTokens, completionTokens; Yandex AI Studio agent:
-- input_tokens, output_tokens). ScenarioReplyComposer вкладывает это в metadata->'modelResponse'->'usage'.

\set ON_ERROR_STOP on

\if :{?salt}
\else
  \echo 'export_calls.sql: задайте -v salt=... (SBER500_SALT)'
  \quit
\endif
\if :{?since}
\else
  \set since '1970-01-01'
\endif

WITH tariff AS (
    -- Тарифы, ₽ за 1 млн токенов (меняйте здесь).
    SELECT 569::numeric AS gigachat_max_rub_per_1m,
           73::numeric  AS gigachat_pro_in,
           176::numeric AS gigachat_pro_out,
           65::numeric  AS gigachat_lite_rub_per_1m
),
llm AS (
    SELECT a.created_at                                    AS ts,
           lower(coalesce(nullif(a.channel, ''), 'unknown')) AS source,
           CASE WHEN coalesce(a.telegram_user_id, a.chat_id) IS NULL THEN NULL
                ELSE md5(:'salt' || coalesce(a.telegram_user_id::text, a.chat_id::text)) END AS pseudo_user_id,
           'llm'::text                                     AS call_type,
           coalesce(nullif(a.provider, ''), 'unknown')
               || CASE WHEN coalesce(a.model, '') <> '' THEN '/' || a.model ELSE '' END AS provider_or_system,
           CASE WHEN NOT a.success THEN 'error'
                WHEN a.fallback_used THEN 'fallback'
                ELSE 'ok' END                              AS result,
           nullif(a.error_type, '')                        AS error_code,
           nullif(coalesce(a.metadata -> 'usage' ->> 'prompt_tokens',
                           a.metadata -> 'usage' ->> 'inputTextTokens',
                           a.metadata -> 'usage' ->> 'input_tokens',
                           a.metadata -> 'modelResponse' -> 'usage' ->> 'prompt_tokens',
                           a.metadata -> 'modelResponse' -> 'usage' ->> 'inputTextTokens',
                           a.metadata -> 'modelResponse' -> 'usage' ->> 'input_tokens'), '')::numeric AS tokens_in,
           nullif(coalesce(a.metadata -> 'usage' ->> 'completion_tokens',
                           a.metadata -> 'usage' ->> 'completionTokens',
                           a.metadata -> 'usage' ->> 'output_tokens',
                           a.metadata -> 'modelResponse' -> 'usage' ->> 'completion_tokens',
                           a.metadata -> 'modelResponse' -> 'usage' ->> 'completionTokens',
                           a.metadata -> 'modelResponse' -> 'usage' ->> 'output_tokens'), '')::numeric AS tokens_out,
           lower(coalesce(a.model, ''))                    AS model_lc,
           a.latency_ms                                    AS latency_ms,
           a.correlation_id                                AS correlation_id
    FROM model_interaction_audit a
    WHERE a.created_at >= :'since'::timestamptz
),
llm_priced AS (
    SELECT l.ts, l.source, l.pseudo_user_id, l.call_type, l.provider_or_system, l.result, l.error_code,
           l.tokens_in, l.tokens_out,
           round(CASE
               WHEN l.model_lc NOT LIKE '%gigachat%' THEN NULL           -- неизвестный тариф (Yandex и пр.)
               WHEN l.model_lc LIKE '%max%'
                   THEN (coalesce(l.tokens_in, 0) + coalesce(l.tokens_out, 0)) * t.gigachat_max_rub_per_1m / 1000000
               WHEN l.model_lc LIKE '%pro%'
                   THEN (coalesce(l.tokens_in, 0) * t.gigachat_pro_in + coalesce(l.tokens_out, 0) * t.gigachat_pro_out) / 1000000
               ELSE (coalesce(l.tokens_in, 0) + coalesce(l.tokens_out, 0)) * t.gigachat_lite_rub_per_1m / 1000000  -- GigaChat / GigaChat-2 / Lite
           END, 6)                                         AS cost_rub,
           l.latency_ms, l.correlation_id
    FROM llm l CROSS JOIN tariff t
),
saby AS (
    SELECT s.occurred_at                                   AS ts,
           'butler'::text                                  AS source,
           NULL::text                                      AS pseudo_user_id,   -- журнал Saby не хранит гостя
           'tool:saby'::text                               AS call_type,
           'saby:' || s.http_method || ' ' || s.operation  AS provider_or_system,
           lower(s.outcome)                                AS result,
           CASE WHEN s.http_status >= 400 THEN s.http_status::text END AS error_code,
           NULL::numeric AS tokens_in, NULL::numeric AS tokens_out, NULL::numeric AS cost_rub,
           s.duration_ms                                   AS latency_ms,
           NULL::text                                      AS correlation_id
    FROM saby_call_log s
    WHERE s.occurred_at >= :'since'::timestamptz
),
inbound AS (
    SELECT m.received_at                                   AS ts,
           'telegram'::text                                AS source,
           md5(:'salt' || coalesce(m.telegram_user_id::text, m.chat_id::text)) AS pseudo_user_id,
           'guest_message'::text                           AS call_type,
           'telegram:' || lower(m.message_kind)            AS provider_or_system,
           'received'::text                                AS result,
           NULL::text AS error_code,
           NULL::numeric AS tokens_in, NULL::numeric AS tokens_out, NULL::numeric AS cost_rub,
           NULL::bigint                                    AS latency_ms,
           m.event_id                                      AS correlation_id
    FROM telegram_messages m
    WHERE m.received_at >= :'since'::timestamptz
),
reservations AS (
    SELECT r.created_at                                    AS ts,
           lower(r.source)                                 AS source,
           md5(:'salt' || coalesce(r.telegram_user_id::text, r.chat_id::text)) AS pseudo_user_id,
           'target_action:reservation'::text               AS call_type,
           CASE WHEN r.sbis_external_id IS NOT NULL THEN 'butler+saby' ELSE 'butler' END AS provider_or_system,
           lower(r.status)                                 AS result,
           NULL::text AS error_code,
           NULL::numeric AS tokens_in, NULL::numeric AS tokens_out, NULL::numeric AS cost_rub,
           NULL::bigint                                    AS latency_ms,
           'reservation:' || r.id                          AS correlation_id
    FROM table_reservation_orders r
    WHERE r.created_at >= :'since'::timestamptz
),
bills AS (
    SELECT b.created_at                                    AS ts,
           lower(b.source)                                 AS source,
           md5(:'salt' || coalesce(b.telegram_user_id::text, b.chat_id::text)) AS pseudo_user_id,
           'target_action:bill'::text                      AS call_type,
           'butler:' || lower(b.kind)
               || CASE WHEN b.external_provider IS NOT NULL THEN '+' || lower(b.external_provider) ELSE '' END
                                                           AS provider_or_system,
           lower(b.status) || '/' || lower(b.pay_state)    AS result,
           NULL::text AS error_code,
           NULL::numeric AS tokens_in, NULL::numeric AS tokens_out, NULL::numeric AS cost_rub,
           NULL::bigint                                    AS latency_ms,
           'bill:' || b.id                                 AS correlation_id
    FROM guest_bills b
    WHERE b.created_at >= :'since'::timestamptz
),
unified AS (
    SELECT ts, source, pseudo_user_id, call_type, provider_or_system, result, error_code,
           tokens_in, tokens_out, cost_rub, latency_ms, correlation_id FROM llm_priced
    UNION ALL SELECT * FROM saby
    UNION ALL SELECT * FROM inbound
    UNION ALL SELECT * FROM reservations
    UNION ALL SELECT * FROM bills
)
-- Строка заголовка CSV печатает export_calls.sh; здесь только данные, по времени.
SELECT concat_ws(',',
           to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
           coalesce(source, ''),
           coalesce(pseudo_user_id, ''),
           call_type,
           '"' || replace(coalesce(provider_or_system, ''), '"', '""') || '"',
           coalesce(result, ''),
           '"' || replace(coalesce(error_code, ''), '"', '""') || '"',
           coalesce(tokens_in::text, ''),
           coalesce(tokens_out::text, ''),
           coalesce(cost_rub::text, ''),
           coalesce(latency_ms::text, ''),
           '"' || replace(coalesce(correlation_id, ''), '"', '""') || '"'
       )
FROM unified
ORDER BY ts;
