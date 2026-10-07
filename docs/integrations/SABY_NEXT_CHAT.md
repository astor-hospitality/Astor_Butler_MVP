# Saby: передача контекста в следующий чат

## Ревью 07.10.2026 — актуализация

Таблица CI ниже — исторический срез 06.10, не текущий release gate. Проверять GitHub checks на точном HEAD каждого PR.

- #48: опрос теперь проходит все активные брони по ID; первые 200 неизменных заявок не блокируют остальные.
- #49: перед обновлением читается полный заказ Presto. Посадка, блюда и дополнительные поля сохраняются; неполный ответ или чужая точка блокируют запись. Посадку хостес сверяет отдельно.
- #50: подтянуты исправления #48/#49; собственная логика выбора источника подтверждения не менялась.
- #51: блюда добавляются к текущему заказу без пересоздания посадки. Если блюда уже есть, требуется ручное согласование — без перезаписи и автоматических дублей.
- #52: записывающий прокси маскирует варианты имён чувствительных полей и не сохраняет сырой не-JSON ответ. Это не гарантия полной анонимизации; реальные fixtures проверять вручную и не публиковать.
- Реальный Saby не проверялся. Форма GET order/{id} должна быть подтверждена тестовым smoke; незнакомая структура блокирует запись. GET + PUT не атомарны: конкурирующее редактирование требует отдельной проверки.
- Код проверялся локально на Java 25; утверждение ниже «не компилировался локально» относится к исходной облачной сессии, а не к текущему состоянию.
- Эти исправления не означают мерж в main или production deploy. #41 остаётся draft.

Обновлено: 06.10.2026, 23:20 МСК. Для любого агента (Claude, Codex) или человека, который продолжает интеграцию Butler ↔ Saby Presto.
Правила работы — `CLAUDE.md`, `AGENTS.md`; карта API и блокеры — `SABY_PRESTO_BOOKING_API.md`; план из четырёх MR — `SABY_INTEGRATION_PLAN.md`.

## 1. Где что лежит

| Слой | Код |
| --- | --- |
| Доменный порт | `domain/booking/external/ExternalReservationProvider` (+ `ExternalBookingSnapshot`, `ExternalBookingState`, `ExternalTableOccupancy`, `ExternalTableCodes`) |
| Адаптер Saby | `integration/saby/SabyReservationProvider` (read/write/update/state/cancel, `adopt`/`forget`), `SabyApiClient` (auth, токен-кэш, ретрай GET), `SabyOrderPayload`, `SabyBookingState`, `SabyOrderResult`, `SabyReservationProperties` |
| Ланч | `domain/lunch/ExternalLunchOrderProvider` → `integration/saby/SabyLunchOrderProvider` + `SabyMenuCatalog` |
| Встройка в бронь | `domain/booking/TableReservationService` (занятость, create → Saby, change → update, cancel), `ExternalBookingSync` (опрос статусов), `HostessReservationApprovalService` (режим подтверждения) |
| Карточка хостес | `TableReservationNotificationService.externalSyncLine` |
| Двойник Presto | `scripts/saby_stub` + `docker-compose.saby-stub.yml` |
| Конфиг | `application.yaml` → `astor.integrations.saby.*`, `astor.booking.*`; проброс в контейнер — `docker-compose.prod.yml` |

## 2. Состояние на 06.10 (23:20 МСК)

В `main`: MR1 карта API (#36), MR2 чтение (#37), проброс переменных (#40). Всё выключено: `ASTOR_SABY_ENABLED=false`.

Открытые PR, **мержить строго по порядку** (каждый стоит на предыдущем, после мержа — «Update branch» следующему):

| # | Ветка | Что | CI |
| --- | --- | --- | --- |
| #47 | `codex/saby-write-review-fixes` → `feature/saby-adapter-write` | правки по ревью #38: валидация ID из `create`, телефон цифрами, карта кодов, `adopt`/`forget`, `woTable`/`table` | ✅ |
| #38 | `feature/saby-adapter-write` → `main` (Дима) | MR3 запись: `create`, `state`, `cancel` за `SABY_WRITE_ENABLED` | ✅, ждёт мержа #47 |
| #41 | `claude/saby-booking-wiring` → `main` | MR4 встройка в поток брони: занятость столов, бронь в Saby после холда, отмена | ✅ |
| #48 | `codex/saby-status-sync` → `codex/saby-base` | MR5 `ExternalBookingSync`: 20 → CONFIRMED, 220 → отмена, раз в минуту | ✅ |
| #49 | `codex/saby-update` → #48 | MR6 изменение гостем → `PUT order/{id}/update` | ✅ (один из прогонов флакнул) |
| #50 | `codex/saby-confirmation-source` → #49 | MR8 `ASTOR_BOOKING_CONFIRMATION_SOURCE=HOSTESS\|VENUE_SYSTEM` (B12) | ✅ |
| #51 | `codex/saby-lunch-dishes` → #50 | MR7 блюда ланча в ту же бронь (`update` + `nomenclatures`) | ✅ |
| #52 | `codex/saby-stub` → `main` | стаб Presto + записывающий прокси | ✅, независим |

`codex/saby-base` = #41 + #47, служебная база стека, не PR. Весь Java-код #47–#51 **не компилировался локально** (Maven Central закрыт из облачной среды Claude) — только CI.

## 3. Что блокирует живой запуск

Не код — доступы. Поддержка Saby (06.10): лицензия Presto Профи подходит; «Подключения к Saby» (ID подключения, защищённый ключ, сервисный ключ) выдаёт только **руководитель организации** ООО «Счастье» — Тариэль, у Михаила раздела нет. Инструкция для Тариэля — в чате Михаила от 06.10 (Настройки → Система → Безопасность → Подключения к Saby → «+», «С ограничением по правам», «По сервисному ключу»). Ключи — только в менеджер паролей → `.env.production` на ВМ, не в issue/Telegram/git.

Неизвестны до первого живого вызова: форма ответа `order/create` (B4), `GET order/{id}`, `update`, `price-list`, `nomenclature/list`. Парсеры защитные; при другой форме — честный `PROVIDER_RESULT_UNKNOWN` / «внести вручную», не падение.

## 4. Порядок после получения ключей

1. `listPoints()` (или `GET /retail/point/list` через стаб в режиме `record`) → `SABY_POINT_ID`; `hall/list` → `SABY_HALL_ID`, имена столов (должны совпадать с кодами столов Butler: «5», «Стол 5», «05» — `ExternalTableCodes`).
2. `ASTOR_SABY_ENABLED=true`, запись выключена → сутки смотрим, что занятость из Saby не ломает выбор стола.
3. `SABY_SMOKE_WRITE=true` + тестовый телефон на согласованный с Тариэлем стол → в выводе `responseFields` реальная форма `create` → поправить `firstText` в `SabyReservationProvider`, если поле другое; записать в `SABY_PRESTO_BOOKING_API.md` 4.4.
4. `SABY_WRITE_ENABLED=true` на стенде; `ExternalBookingSync` подтверждает заявки по Presto; решить `ASTOR_BOOKING_CONFIRMATION_SOURCE` для показа 15.10 (решение Михаила).
5. Ланч: `SABY_PRICE_LIST_ID` из `price-list`, проверить, что названия блюд в `business-lunch/aeris.json` совпадают с Presto буква в букву (иначе `DISH_NOT_IN_SABY` и заказ остаётся хостес).

Без ключей: смержить #52, на стенде `docker compose … -f docker-compose.saby-stub.yml`, прогнать в Telegram полный цикл; на показе — страховка «эмуляция Presto».

## 5. Что ещё не сделано

- `GET order/{id}` в адаптере — чтобы подтянуть стол, назначенный админом Saby при `woTable`, обратно в карточку (форма ответа неизвестна).
- Вебхуки Saby вместо опроса — требуют публичный HTTPS и неописанную проверку подлинности; для пилота опрос.
- `booking/calendar` как подсказка времени, стоп-лист и меню из Saby для ответов бота (сейчас — парсер сайта, `scripts/aeris_menu`).
- Оплата/депозит (`payment-link`) — не раньше подтверждённой интеграции, решение заказчика.
- Durable-идемпотентность только через `sbis_external_id`; кэш ключей в провайдере — на процесс (24 ч).

## 6. Как начать новый чат

Вставить:

> Продолжаем интеграцию Astor Butler ↔ Saby Presto. Репозиторий `astor-hospitality/Astor_Butler_MVP`. Сначала прочитай `docs/integrations/SABY_NEXT_CHAT.md`, затем `SABY_PRESTO_BOOKING_API.md` и `CLAUDE.md`. Проверь текущее состояние PR #38, #41, #47–#52 и `main` — файл мог устареть. Правила: FSM — источник истины; бронь не считается подтверждённой без ответа ресторана; ключи Saby только в серверном окружении; `src/main/**` — только через ветку и PR, в `main` не пушить; локальные тесты и CI — отдельные проверки. Задача на сегодня: <…>.

Если чат ведёт Claude в облаке: Maven Central оттуда недоступен, компиляция — только CI; GitHub Actions-логи читаются через браузер после входа в GitHub.
