# Консьерж ⇄ Батлер: включение, проверка, откат

Спецификация: [2026-10-14-astor-negotiation-design.md](../superpowers/specs/2026-10-14-astor-negotiation-design.md) §7.
Все флаги по умолчанию `false`. Включаем по одному, в обоих сервисах: следующий — только после проверки предыдущего.
Решение о каждом флаге на проде — Михаил. Гостям вне списка тестовых чатов ничего не меняется до шага 6.

## Как переключить флаг

**Батлер.** На ВМ, в `/opt/astor-butler/.env.production`. Перед правкой —
`cp .env.production .env.production.bak-negotiation-$(date +%Y%m%d-%H%M)`. Затем пересоздать только контейнер AERIS
(как в [GUEST_JOURNEY_ROLLOUT.md](GUEST_JOURNEY_ROLLOUT.md)):

```bash
docker compose --env-file ../.env.production --env-file images.env -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudru.yml --profile frontend --profile telegram up -d --no-deps --no-build aeris-astor-butler-bot
```

**Консьерж.** На ВМ, в `/opt/astor-concierge/.env` (копия: `cp .env .env.bak-negotiation-$(date +%Y%m%d-%H%M)`), затем
`cd /opt/astor-concierge && docker compose -p astor-concierge up -d --force-recreate concierge`.
Консьерж на ВМ выложен архивом от 11.10, а не из git: сначала выложить ветку `feat/negotiation-agent`
(`scripts/deploy-vm.sh`, [DEPLOY_VM.md](https://github.com/astor-hospitality/Astor_Concierge/blob/main/docs/operations/DEPLOY_VM.md)),
в его `.env` ещё нет ключей `LEDGER_*` / `AGENT_*`.

SQL ниже — через `docker exec -i astor_postgres_test sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'`.

## Порядок

| # | Батлер (`.env.production`) | Консьерж (`.env`) | Что меняется для гостя | Проверка после |
| --- | --- | --- | --- | --- |
| 0 | `ASTOR_GUEST_JOURNEY_ENABLED=true` — предусловие, должен быть включён до шагов 4 и 6 ([GUEST_JOURNEY_ROLLOUT.md](GUEST_JOURNEY_ROLLOUT.md)) | — | ничего: путь гостя уже ведётся | `grep ASTOR_GUEST_JOURNEY_ENABLED .env.production` — `true`; `SELECT count(*) FROM guest_visits WHERE opened_at > now() - interval '1 day';` — визиты открываются; после шагов 4 и 6 в логе Батлера нет `needs ASTOR_GUEST_JOURNEY_ENABLED=true` (`docker logs aeris-astor-butler-bot 2>&1 \| grep GUEST_JOURNEY`). Без пути гостя бронь агента не привязывается к визиту, `visit_status` ничего не находит, табло пустое |
| 1 | `ASTOR_LEDGER_ENABLED=true` | `LEDGER_ENABLED=true` | ничего: лента визита пишется | пробное сообщение Батлеру и Консьержу: `SELECT side, channel, kind, left(text, 40) FROM visit_turns ORDER BY id DESC LIMIT 10;` — есть `GUEST`/`BUTLER` и `GUEST`/`CONCIERGE`; телефоны в `text`/`payload` вида `+7 912 ***-**-60`; без согласия гостя у строк Консьержа `text` пуст и `payload` содержит `"hidden":true` |
| 2 | `ASTOR_BOARD_ENABLED=true`, `ASTOR_BOARD_TOKEN`, `ASTOR_BOARD_CONCIERGE_HEALTH_URL` | — | ничего: табло astor-ai.ru/board | `curl -sS -H "X-Astor-Board-Token: $T" https://astor-ai.ru/api/board/v1/health-map` — 200 и JSON; без заголовка — 401; лампа Kafka всегда жёлтая (нет health-контрибьютора) — это не ошибка; SSE: `curl -N "https://astor-ai.ru/api/board/v1/stream?t=$T"` — события приходят сразу, а не пачкой (gzip на edge не копит поток) |
| 3 | `ASTOR_VISIT_SUMMARY_ENABLED=true`, `GIGACHAT_MAX_TOKENS` ≥ 700 | — | ничего: саммари визита и уроки агентам ждут менеджера на табло | через ≤10 мин после закрытого визита: `SELECT visit_id, left(outcome, 60), created_at FROM visit_summaries ORDER BY created_at DESC LIMIT 5;`; `SELECT side, status, left(text, 60) FROM agent_lessons ORDER BY id DESC LIMIT 5;` — `PROPOSED`; в логах нет `Visit summary parked` и `not the expected JSON` |
| 4 | `ASTOR_CAPABILITIES_ENABLED=true` | — | ничего: новые эндпоинты | `docker run --rm --network edge curlimages/curl -sS -H "X-Astor-Internal-Token: $I" http://astor-api-gateway:8080/api/capabilities/v1/tools` — список инструментов; без токена — 404 от шлюза; `SELECT count(*) FROM capability_calls;` растёт только при вызовах |
| 5 | `ASTOR_CONCIERGE_WEBHOOK_ENABLED=true`, `ASTOR_WEBHOOK_SECRET` | `ASTOR_WEBHOOK_SECRET` (то же значение) | ничего, пока нет брони из чата Консьержа | тестовое событие (ниже): подписанное — `404` (бронь неизвестна, подпись принята), без подписи — `401`, без секрета у Консьержа — `503` |
| 6 | `ASTOR_AGENT_ENABLED=true`, `ASTOR_AGENT_ALLOWLIST=<id>,<id>` | `AGENT_ENABLED=true`, `AGENT_ALLOWLIST=<id>,<id>`, `LLM_PROVIDER=gigachat` | только чаты из списка: Консьерж договаривается с Батлером, в чате два голоса (`AERIS · Батлер: …`) | тестовый чат пишет Консьержу «где пообедать у Мамина-Сибиряка» → ответ с вариантами; бронь → строка `AERIS · Батлер: …`, карточка у хостес; в ленте `TOOL_CALL`/`TOOL_RESULT` по каждому вызову; чат вне списка — прежний поток |

### Перед шагом 6: модель агента умеет функции на нашем ключе

Проверено 14.10 на ключе скоупа `GIGACHAT_API_PERS`: `GigaChat-3-Pro` — 404 «No such model», `GigaChat-2-Pro` и
`GigaChat-2` — корректный `function_call` (дата в ISO, когда системный промпт требует `YYYY-MM-DD`). Повторить перед
шагом 6 одним запросом — с ВМ, ключ берётся из `.env.production` и в консоль не печатается:

```bash
cd /opt/astor-butler && set -a && . ./.env.production && set +a
TOKEN=$(curl -sS -X POST "${GIGACHAT_OAUTH_URL:-https://ngw.devices.sberbank.ru:9443/api/v2/oauth}" \
  -H "Authorization: Basic $GIGACHAT_AUTH_KEY" -H "RqUID: $(uuidgen)" \
  -H 'Content-Type: application/x-www-form-urlencoded' -d "scope=${GIGACHAT_SCOPE:-GIGACHAT_API_PERS}" | jq -r .access_token)
curl -sS "${GIGACHAT_API_URL:-https://gigachat.devices.sberbank.ru/api/v1}/chat/completions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
    "model": "'"${ASTOR_AGENT_MODEL:-GigaChat-2-Pro}"'",
    "messages": [{"role": "system", "content": "Сегодня 2026-10-14. Даты передавай в формате YYYY-MM-DD."},
                 {"role": "user", "content": "Есть стол на двоих завтра в 19:00?"}],
    "functions": [{"name": "table_availability", "description": "Свободные столы",
      "parameters": {"type": "object", "properties": {"date": {"type": "string"}, "time": {"type": "string"},
        "partySize": {"type": "integer"}}, "required": ["date", "time", "partySize"]}}],
    "function_call": "auto"}' | jq '.choices[0].message.function_call, .error // .message'
```

Если curl не доверяет сертификату Сбера — добавить к обоим вызовам `--cacert <путь к russian_trusted_root_ca.pem>`.
Ожидается `{"name": "table_availability", "arguments": {"date": "2026-10-15", "time": "19:00", "partySize": 2}}`.
Если ответ 404 «No such model», 4xx про `functions` или `function_call` пуст — поставить в `ASTOR_AGENT_MODEL` (и
`AGENT_MODEL` у Консьержа) модель, которая на этом ключе отвечает `function_call` (`GigaChat-2-Pro`, запасной —
`GigaChat-2`), и повторить запрос. С моделью без функций каждый ход агента уходит в запасной путь.

Шаг 6 у Консьержа без `LLM_PROVIDER=gigachat` не включится (в логе `AGENT_ENABLED needs LLM_PROVIDER=gigachat`).
Батлер должен включить шаги 1, 4 и 6 раньше Консьержа: Консьерж ходит в `/api/ledger/v1/turns`, `/api/capabilities/v1/*`
и `/api/negotiation/v1/turn` с `X-Astor-Internal-Token` (`ASTOR_INTERNAL_API_TOKEN` в обоих `.env`, уже заведён).

## Строки для `.env`

Батлер, `/opt/astor-butler/.env.production`:

```bash
ASTOR_LEDGER_ENABLED=true
ASTOR_BOARD_ENABLED=true
ASTOR_BOARD_TOKEN=<openssl rand -hex 24; не короче 16 символов, иначе 401 на всё>
# Имя контейнера Консьержа в сети astor-butler_default; алиас astor-concierge есть только в сети edge.
ASTOR_BOARD_CONCIERGE_HEALTH_URL=http://astor-concierge-concierge-1:8080/readyz
ASTOR_VISIT_SUMMARY_ENABLED=true
# Не меньше 700: 256 по умолчанию обрезает JSON саммари; три неразобранных ответа — визит пропускается до перезапуска.
GIGACHAT_MAX_TOKENS=900
# ASTOR_SUMMARY_MODEL=   пусто = GIGACHAT_MODEL (GigaChat-2-Max)
ASTOR_CAPABILITIES_ENABLED=true
ASTOR_CONCIERGE_WEBHOOK_ENABLED=true
ASTOR_CONCIERGE_WEBHOOK_URL=http://astor-concierge-concierge-1:8096
ASTOR_WEBHOOK_SECRET=<openssl rand -hex 32; одинаковый в обоих сервисах>
ASTOR_AGENT_ENABLED=true
ASTOR_AGENT_ALLOWLIST=<chat id Михаила>,<chat id Тариэля>
# Проверено 14.10 на ключе скоупа GIGACHAT_API_PERS: GigaChat-3-Pro отвечает 404 «No such model»,
# GigaChat-2-Pro возвращает корректный function_call. Перед шагом 6 — проверка ниже.
ASTOR_AGENT_MODEL=GigaChat-2-Pro
# Ход переговоров у Батлера короче ожидания Консьержа (BUTLER_NEGOTIATION_TIMEOUT_MS=30000): Консьерж не бросает ход,
# который Батлер ещё делает. В боте Батлера свой бюджет — ASTOR_AGENT_BOT_TURN_TIMEOUT_MS (12000).
ASTOR_AGENT_TURN_TIMEOUT_MS=25000
```

Консьерж, `/opt/astor-concierge/.env`:

```bash
LEDGER_ENABLED=true
ASTOR_WEBHOOK_SECRET=<то же значение, что у Батлера>
LLM_PROVIDER=gigachat
AGENT_ENABLED=true
AGENT_ALLOWLIST=<chat id Михаила>,<chat id Тариэля>
AGENT_MODEL=GigaChat-2-Pro
AGENT_TIMEOUT_MS=20000
AGENT_TURN_TIMEOUT_MS=60000
# Сколько Консьерж ждёт ход Батлера; больше ASTOR_AGENT_TURN_TIMEOUT_MS Батлера (25000).
BUTLER_NEGOTIATION_TIMEOUT_MS=30000
```

`WEB_ENABLED=true` у Консьержа обязателен для вебхука: маршрут `/api/concierge/v1/events` живёт на `WEB_PORT` 8096.
Наружу он не опубликован (edge отдаёт Консьержу только `/api/concierge/messages`), подпись HMAC — второй рубеж.

## Edge и шлюз: `/api/board/*`

- Шлюз: `location /api/board/` в `docker/nginx/nginx.conf.template` стоит перед общим `location /api/` (тот без
  `X-Astor-Internal-Token` отвечает 404). Табло закрыто только `BoardTokenFilter`; `proxy_buffering off` и
  `proxy_read_timeout 1h` — для SSE. Применяется пересозданием `api-gateway` (`up -d api-gateway`, релиз через workflow
  делает это сам; если шаблон правили на месте — `--force-recreate`).
- Edge: файл `/opt/edge/sites.d/astor.caddy` на ВМ должен совпадать с `infra/astor-site/astor.caddy` этой ветки — в нём
  блок `@board path /api/board/*` с `flush_interval -1` перед общим `@backend`. Скопировать и проверить
  ([infra/astor-site/README.md](../../infra/astor-site/README.md) §3):

```bash
sudo install -m 644 /opt/astor-site/infra/astor-site/astor.caddy /opt/edge/sites.d/astor.caddy
docker exec vedal-proxy caddy validate --config /etc/caddy/Caddyfile
docker exec vedal-proxy caddy reload   --config /etc/caddy/Caddyfile
```

  Без `validate` не перезагружать: общий Caddyfile обслуживает vedal-med.ru и c3ag.ru.
- Страница `/board` — статика `astor-site` (`frontend/astor-butler/board/`): после выкладки ветки пересоздать `astor-site`.
- Токен стрима идёт в `?t=` (EventSource не умеет заголовки). У сайта нет директивы `log`; если её добавить — вырезать
  `t` из query. Журнал доступа Батлера на этот путь проверить: токена в нём быть не должно.

## Тестовое событие вебхука (шаг 5)

С ВМ, секрет читается из `.env` в переменную, на экран не выводится:

```bash
S=$(grep '^ASTOR_WEBHOOK_SECRET=' /opt/astor-butler/.env.production | cut -d= -f2-)
B='{"type":"CONFIRMED","reservationId":0,"visitId":null,"sessionId":"probe","butlerText":"probe","facts":{}}'
SIG=$(printf '%s' "$B" | openssl dgst -sha256 -hmac "$S" -hex | sed 's/^.* //')
docker run --rm --network astor-butler_default curlimages/curl -sS -o /dev/null -w '%{http_code}\n' \
  -H 'Content-Type: application/json' -H "X-Astor-Signature: sha256=$SIG" -d "$B" \
  http://astor-concierge-concierge-1:8096/api/concierge/v1/events
```

Ожидается `404` (подпись верна, брони 0 нет). Hex подписи — строчными буквами: Консьерж сравнивает с учётом регистра.
Живое событие Батлер шлёт только по брони, у визита которой есть связанная сессия Консьержа; остальные брони — прежним путём.

## Тестовые чаты (шаг 6)

1. Chat id личного чата в Telegram = id пользователя. Ids Михаила и Тариэля, скорее всего, уже есть в
   `TEAM_TELEGRAM_IDS` Консьержа; иначе — после их `/start` боту Батлера:
   `SELECT DISTINCT chat_id FROM guest_visits WHERE chat_id IS NOT NULL ORDER BY 1;` и сверить с ними.
2. Вписать оба id через запятую в `ASTOR_AGENT_ALLOWLIST` (Батлер) и `AGENT_ALLOWLIST` (Консьерж), пересоздать оба
   контейнера. Пустой список — агент не отвечает никому, даже с `*_AGENT_ENABLED=true`.
3. Прогон — [scripts/e2e/negotiation-run.txt](../../scripts/e2e/negotiation-run.txt), 40 реплик гостя в чате **Консьержа**
   (не бота Батлера); строки `# хостес: …` — действия в рабочей группе между репликами.
4. Расширять список — только после прогона без `FALLBACK` в ленте:
   `SELECT kind, count(*) FROM visit_turns WHERE at > now() - interval '1 hour' GROUP BY kind;`.

Особенности чатов из списка:
- «не пишите мне» в чате Батлера отвечает агент, а не политика полезности (`ASTOR_USEFULNESS_POLICY_ENABLED`): отписку
  проверять в чате вне списка или с выключенным агентом.
- У Консьержа чат из списка не получает ответов прежнего потока на свободный текст — только агент; при ошибке модели
  или таймауте реплики (60 с, `AGENT_TURN_TIMEOUT_MS` Консьержа) — запасной путь и строка `FALLBACK` в ленте.

## Стоимость

Агенты обоих сервисов — `GigaChat-2-Pro` (`ASTOR_AGENT_MODEL` / `AGENT_MODEL`; 500/500 ₽ за 1 млн токенов, `GigaChat-3-Pro`
на скоупе PERS недоступна — проверено 14.10); `GigaChat-2-Max` (`GIGACHAT_MODEL`) — только
для саммари визита, один вызов на закрытый визит. История в промпте агента ограничена ≈2k токенов, факты агент берёт
инструментами. Оценка на 2-Pro ≈250–300 ₽ на визит (тариф почти как у 2-Max; на 3-Pro было бы ≈10–40 ₽). Сверить с журналом вызовов после первого дня.

## Откат

Выключить флаг (`=false`) в обратном порядке (агент → вебхук → инструменты → саммари → табло → лента) и пересоздать
контейнер той же командой; для мгновенного отката агента достаточно очистить `ASTOR_AGENT_ALLOWLIST` / `AGENT_ALLOWLIST`.
Данные остаются: `visit_turns`, `visit_summaries`, `agent_lessons`, `capability_calls` при выключенных флагах никто не
пишет и не читает, при повторном включении они продолжаются. Брони, сделанные агентом, — обычные брони: хостес видит и
подтверждает их как раньше. Без вебхука Консьерж узнаёт статус брони прежним опросом. Edge-блок `@board` можно
оставить: при `ASTOR_BOARD_ENABLED=false` весь `/api/board/**` отвечает 404.
