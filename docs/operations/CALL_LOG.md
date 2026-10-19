# Единый журнал вызовов (call log) для жюри акселератора

Дата: 2026-10-15. Ветка `feat/call-log`. Таблица `call_log` (миграция `2026-10-15-call-log`), код `domain/calllog/`.

Одна таблица, в которой лежит каждый вызов LLM, каждый вызов Saby, каждое сообщение гостя и каждое целевое действие —
с токенами и стоимостью LLM в рублях. Выгружается одним CSV.

## 1. Поля

| колонка          | что это                                                                                  |
|------------------|------------------------------------------------------------------------------------------|
| `ts`             | дата и время события (UTC, в CSV — ISO-8601)                                             |
| `source`         | источник трафика: `telegram`, `web`, `max`, источник брони/счёта (`telegram`, `direct`…); `internal` — вызов модели вне сообщения гостя |
| `pseudo_user_id` | псевдонимный id гостя: первые 24 hex от `sha256(salt + ":" + id)`                         |
| `correlation_id` | id обработки сообщения (связывает сообщение гостя и вызовы LLM по нему)                   |
| `call_type`      | `llm` · `llm:vision` · `llm:embedding` · `tool` · `guest_message` · `target_action`      |
| `name`           | модель (`GigaChat-3-Pro`), `saby:<операция>`, `reservation:create` и т.п.                 |
| `result`         | `ok`/`fallback`/`error` (LLM: исключение → `error`, ответ с флагом fallback → `fallback`), `OK`/`HTTP_ERROR`/`IO_ERROR` (Saby), `received`, статус брони/счёта |
| `error_code`     | класс корневой причины ошибки LLM (`SocketTimeoutException`…) или HTTP-статус Saby ≥ 400   |
| `tokens_in/out`  | токены запроса/ответа из `usage` ответа модели                                           |
| `cost_rub`       | стоимость вызова LLM в рублях (4 знака)                                                  |
| `latency_ms`     | длительность вызова LLM или Saby                                                         |

## 2. Что пишется

| call_type       | где точка записи                                                   | name                                   |
|-----------------|--------------------------------------------------------------------|----------------------------------------|
| `llm`, `llm:vision`, `llm:embedding` | `CallLogModelGateway` — обёртка над выбранным бином `ModelGateway`: каждый `generateText` / `analyzeImage` / `generateEmbedding` любого вызывающего (сценарии, приветствие, саммари голосовых, ops-вопросы, очки, прогрев, эмбеддинги), ровно один раз | имя модели из ответа |
| `tool`          | `SabyCallLogRepository.record` (каждый HTTP-вызов Saby)             | `saby:/путь/{id}`                       |
| `guest_message` | `TelegramIntakeService.capture` (Telegram), `WebChatController.process` (WEB-виджет) | —                    |
| `target_action` | `TableReservationService`: создание, подтверждение (руками, авто, после истечения, из Saby), «гость сел» (хостес и Saby); `GuestBillService.open` | `reservation:create`, `reservation:confirm`, `reservation:seated`, `bill:open` |

Как подключена обёртка: `CallLogModelGatewayConfiguration` (только при `ASTOR_CALL_LOG_ENABLED=true`) регистрирует
`BeanPostProcessor`, который оборачивает тот единственный `ModelGateway`-бин, что выбран `astor.model.provider`.
С выключенным флагом обёртки нет вовсе. Канал, гость и correlation id берутся из `CallLogContext`, который
`MessageGatewayService.handle` открывает на время обработки сообщения (и переносит через `supplyAsync` в
понимании и композиторе ответа); вызов вне сообщения пишется с `source = internal` без id. Отдельное приложение
glasses-pilot и вручную созданные шлюзы (не бины) не оборачиваются.

Вызовы Saby попадают в журнал, только когда включён и журнал Saby (`ASTOR_SABY_CALL_LOG_ENABLED=true`):
перехватчик HTTP подключается этим флагом. Сообщения канала MAX как `guest_message` не пишутся (своей единой точки
входа у канала нет), но их вызовы LLM пишутся с `source = max`.

Тариф (₽ за 1 млн токенов). Имя модели сравнивается по последнему сегменту пути (`GigaChat/GigaChat-3-Pro` →
`GigaChat-3-Pro`), без учёта регистра: точное имя, либо имя с тегом версии (`GigaChat:1.0.26`) или `-preview`.
Голое `GigaChat` не поглощает незнакомые варианты: для неизвестной модели `cost_rub` пустой и одно WARN с её именем.

| модель                          | вход | выход |
|---------------------------------|-----:|------:|
| `GigaChat-2-Max`, `GigaChat-Max` |  569 |   569 |
| `GigaChat-3-Pro`                |   73 |   176 |
| `GigaChat-2-Pro`, `GigaChat-Pro` |  500 |   500 |
| `GigaChat` (Lite)               |   65 |    65 |

Переопределить или добавить — одной строкой в `.env`:

```
ASTOR_CALL_LOG_TARIFFS="GigaChat-3-Pro=73,176;GigaChat/GigaChat-2-Max=569,569;yandexgpt-5-lite=40,40"
```

## 3. Включение

```
ASTOR_CALL_LOG_ENABLED=true
ASTOR_CALL_LOG_SALT=<случайная строка ≥ 16 символов, хранится только в .env VM>
# ASTOR_CALL_LOG_TARIFFS — необязательно, формат выше
```

По умолчанию выключено: ни одна строка не пишется. Соль короче 16 символов — одно WARN при старте, строки пишутся
без `pseudo_user_id`. Смена соли меняет все псевдонимы — не менять в течение отчётного периода.

Запись в журнал никогда не меняет поведения бота: каждая строка пишется в своей транзакции (`REQUIRES_NEW`),
любая ошибка проглатывается с WARN без данных гостя.

## 4. Выгрузка

```
GET /api/internal/call-log/export?from=2026-10-15&to=2026-10-16   → text/csv
GET /api/internal/call-log/summary?from=2026-10-15&to=2026-10-16  → JSON
```

`from` включительно, `to` не включительно; дата (`2026-10-15`, полночь UTC) или момент (`2026-10-15T00:00:00Z`).
Без параметров — последние 30 дней. Путь под `/api/internal/**`, его охраняет `InternalApiGuardFilter`: без
заголовка `X-Astor-Internal-Token` ответ 401.

Summary (`llmCalls` — все `llm*`): `llmCalls`, `toolCalls`, `guestMessages`, `targetActions`, `distinctUsers`, `tokensIn`, `tokensOut`, `costRub`.

На VM:

```
ASTOR_INTERNAL_API_TOKEN=... scripts/sber500/export_call_log.sh 2026-10-15 2026-10-22 /tmp/sber500
```

Скрипт ходит в `http://aeris_astor_butler_bot:8089` из одноразового контейнера curl в docker-сети бота, пишет
`call_log_<from>_<to>.csv` и печатает summary. `CALL_LOG_DIRECT=1` — curl прямо из текущей оболочки.

## 5. Приватность

- Ни текста сообщений, ни промптов, ни ответов модели, ни телефонов, ни имён — только счётчики и коды.
- Идентификатор гостя — только псевдоним (соль + sha256, 24 hex); сырой Telegram id / chat id / web session id не
  сохраняется. Без соли — без идентификаторов вообще.
- Ячейки CSV, начинающиеся с `= + - @`, экранируются, чтобы таблица не выполнила формулу.

## 6. Как поля закрывают требования жюри

| требование жюри              | колонка                                   |
|------------------------------|-------------------------------------------|
| источник трафика             | `source`                                  |
| псевдонимный id пользователя | `pseudo_user_id`                          |
| дата/время                   | `ts`                                      |
| тип вызова                   | `call_type` (+ `name`)                    |
| результат                    | `result`                                  |
| код ошибки                   | `error_code`                              |
| стоимость LLM                | `cost_rub` (+ `tokens_in`, `tokens_out`)  |
