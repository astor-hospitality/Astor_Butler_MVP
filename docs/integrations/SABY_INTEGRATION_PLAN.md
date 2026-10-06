# План интеграции Saby Presto

Дата: 2026-10-05. Автор: Дима (`@dmtrshm`). Для issue [#24](https://github.com/astor-hospitality/Astor_Butler_MVP/issues/24) и для согласования с Михаилом.

Работа разбита на четыре MR. Каждый следующий опирается на предыдущий. Методы Saby, решения для adapter и полный список блокеров B1–B12 — в [SABY_PRESTO_BOOKING_API.md](SABY_PRESTO_BOOKING_API.md).

## Порядок

| Шаг | Что | Нужно до начала |
| --- | --- | --- |
| 1 | MR1 — документация, статус в #24, запросы по B1, B2, B3, B5, B9 | Ничего |
| 2 | MR2 — adapter только на чтение, на моках | B10 |
| 3 | Smoke-тест чтения на тестовом аккаунте | B1, B2 |
| 4 | MR3 — запись за флагом | MR2, B10; smoke-тест — B3, B4, B5 |
| 5 | MR4 — подключение к Butler | MR3, B11, B12 |

## MR1. Документация: карта API Saby и блокеры

Ветка `docs/saby-presto-api-map`. Риск: нет.

| Коммит | Изменения |
| --- | --- |
| `docs: add Saby Presto booking API map and blockers` | `docs/integrations/SABY_PRESTO_BOOKING_API.md`: методы, параметры, авторизация, ссылки на документацию Saby, решения для adapter, блокеры |
| `docs: add Saby integration plan` | Этот файл |
| `docs: link Saby API map from Concierge handoff` | Ссылка в [CONCIERGE_BUTLER_HANDOFF_DRAFT.md](../contracts/CONCIERGE_BUTLER_HANDOFF_DRAFT.md) §3, текст Ромы не меняется |

После MR1 — статус-комментарий в #24 со ссылкой на MR.

## MR2. Saby adapter: только чтение

Ветка `feature/saby-adapter-read`. Риск: низкий, по умолчанию выключено. Начало — после B10, smoke-тест — после B1 и B2.

| Коммит | Изменения |
| --- | --- |
| `saby: replace placeholder properties with Presto service auth config` | [SabyReservationProperties.java](../../src/main/java/museon_online/astor_butler/integration/saby/SabyReservationProperties.java): убрать `authMethod`, `apiToken`, `clientId`, `clientSecret`, `refreshToken`, `organizationId`, `restaurantId`, `availabilityPath`, `reservationPath`. Добавить `baseUrl`, `authUrl`, `appClientId`, `appSecret`, `secretKey`, `pointId`, `hallId`, `venueCode`, `zoneId`, `timeoutMs`, `maxRetries`, `writeEnabled=false`. [application.yaml](../../src/main/resources/application.yaml): переменные из раздела 8 карты API, без значений |
| `saby: add SabyApiClient with service auth and token cache` | Новый `SabyApiClient`: токен через `oauth/service`, заголовок `X-SBISAccessToken`, кеш токена, повторная авторизация при `401`, таймауты через `RestTemplateBuilder`, повторы только GET и авторизации при `5xx`, `429` и сетевых ошибках. Ключи не пишутся в логи |
| `saby: implement read-only availability via hall/list` | `checkAvailability`: `hall/list` на время начала, фильтр свободных столов, кандидаты в `metadata`, перевод времени в `zoneId`, неизвестный `venueCode` → `UNSUPPORTED_VENUE`. `reserve` → `SABY_WRITE_DISABLED` |
| `saby: add point/list diagnostic for setup` | `listPoints()` с `product=restaurant` — узнать `pointId` и `hallId` при первом запуске. Используется только smoke-тестом |
| `test: cover Saby auth, availability and failure modes` | `MockRestServiceServer`: авторизация и заголовок, фильтр столов, повторная авторизация после `401`, таймаут → `PROVIDER_TIMEOUT`, `5xx` → `PROVIDER_ERROR`, выключенный провайдер не ходит в сеть |
| `test: add opt-in Saby read-only smoke` | `SabyReadOnlySmokeIT`, только при `SABY_SMOKE=true` и заданных ключах, в CI пропускается |

Готово, когда: тесты и CI зелёные, при выключенном Saby поведение Butler не изменилось.

## MR3. Saby adapter: запись за флагом

Ветка `feature/saby-adapter-write`. Риск: средний, `SABY_WRITE_ENABLED=false` по умолчанию. Начало — после MR2 и B10, smoke-тест — после B3, B4, B5.

| Коммит | Изменения |
| --- | --- |
| `saby: map TableReservationCommand to order/create payload` | `product=restaurant`, `pointId`, `datetime`, `customer{name, phone}`, `comment` + `Astor Butler #<ключ>`, `booking{visitors, hall?, table? \| woTable=true}`. Без имени или телефона → `GUEST_DATA_REQUIRED`, в сеть не ходим |
| `saby: implement reserve without POST retries` | POST не повторяется. Нет ответа после отправки → `PROVIDER_RESULT_UNKNOWN`. Кеш `ключ → результат` в памяти. Успех → `created=true`, `SABY_ORDER_CREATED_UNCONFIRMED`, UUID брони |
| `saby: add order state and cancel calls` | `state(externalId)` и `cancel(externalId)` только в `SabyReservationProvider`, доменный интерфейс не меняется. `state` → `SABY_STATE_<n>` до закрытия B5 |
| `test: cover Saby create, state, cancel and duplicate protection` | Тело запроса; нет повтора POST при `5xx` и таймауте; один вызов на два `reserve` с одним ключом; при выключенной записи нет вызовов |
| `test: extend opt-in smoke with create, state, cancel` | Только при `SABY_SMOKE_WRITE=true`, на тестовой точке, бронь сразу отменяется |

Готово, когда: тесты зелёные; smoke-тест на тестовой точке создал, прочитал и отменил бронь; B4 и B5 закрыты и внесены в карту API.

## MR4. Подключение к Butler

Ветка `feature/saby-booking-wiring`. Риск: высокий, меняет основной поток брони. Зона Михаила (PR #8), рекомендуется Codex. Начало — после MR3, B11, B12.

| Коммит | Изменения |
| --- | --- |
| `booking: add source and idempotency key to reservation creation` | Пункт 2 из §4 handoff-документа, миграция Liquibase |
| `booking: call ExternalReservationProvider behind feature flag` | После локальной заявки и карточки хостес — `reserve`. Сбой Saby не ломает текущий поток |
| `booking: persist sbisExternalId before any retry` | Надёжная защита от дублей (B6) |
| `booking: never report CONFIRMED from Saby create alone` | Итог гостю ставит хостес, пока не согласовано иное (B12) |
| `test: integration tests for Saby-enabled and Saby-down paths` | |

## Позже, отдельными задачами

- Синхронизация статусов: опрос `order/states` или webhook, если Saby поддерживает его для броней.
- Маппинг столов Butler на `tableId` Saby вместо `woTable`.
- Изменение брони через `order/{id}/update`.
- Пробелы из handoff-документа: истечение удержания (`EXPIRED`), авторизация booking API, обратный канал для Concierge.

## Что не трогаем

- FSM, согласие гостя и `TableReservationService` — до MR4 и согласования с Михаилом.
- Боевые данные, общий VEDAL/C3AG, `DATABASECHANGELOG` — без согласованного окна.
- Реальные гостевые брони в тестах.
