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
- Эти исправления не означали мерж в main или production deploy; к утру 07.10 стек смержен в `main` (см. §2), production deploy не выполнялся.

Обновлено: 07.10.2026, 09:00 Екб (разделы 2, 4, 5, 6); остальное — 06.10.2026, 23:20 МСК. Для любого агента (Claude, Codex) или человека, который продолжает интеграцию Butler ↔ Saby Presto.
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

## 2. Состояние на 07.10 (09:00 Екб)

Весь стек Saby в `main` (ночь 07.10, мержи 03:31–03:41 UTC, CI `main` зелёный): #36, #37, #40, #47 → #38 (MR3 запись), #41 (MR4 встройка), #48 (MR5 опрос статусов), #49 (MR6 update), #50 (MR8 источник подтверждения), #51 (MR7 блюда ланча), #52 (стаб + записывающий прокси), #53 (документация), #58 (счета, `ASTOR_BILLING_*`), #59 (показ: `payment-link`, оплата, отзыв, чаевые — `SABY_E2E_DEMO.md`), #61 (OpenAI-совместимый провайдер модели, выключен по умолчанию).

Ветка `codex/saby-base` больше не нужна. Производство не трогалось: последний прогон `Deploy to Yandex VM` — preflight на `5a3d3f4` (06.10), кандидат релиза — вершина `main` после #60 (см. issue #42). Всё по-прежнему выключено: `ASTOR_SABY_ENABLED=false`, `SABY_WRITE_ENABLED=false`, `SABY_PAYMENT_ENABLED=false`, `ASTOR_BILLING_ENABLED=false`.

Историческая таблица PR на 06.10 (все смержены):

| # | Ветка | Что | CI (06.10) |
| --- | --- | --- | --- |
| #47 | `codex/saby-write-review-fixes` → `feature/saby-adapter-write` | правки по ревью #38: валидация ID из `create`, телефон цифрами, карта кодов, `adopt`/`forget`, `woTable`/`table` | ✅, смержен |
| #38 | `feature/saby-adapter-write` → `main` (Дима) | MR3 запись: `create`, `state`, `cancel` за `SABY_WRITE_ENABLED` | ✅, смержен |
| #41 | `claude/saby-booking-wiring` → `main` | MR4 встройка в поток брони: занятость столов, бронь в Saby после холда, отмена | ✅, смержен |
| #48 | `codex/saby-status-sync` → `codex/saby-base` | MR5 `ExternalBookingSync`: 20 → CONFIRMED, 220 → отмена, раз в минуту | ✅, смержен |
| #49 | `codex/saby-update` → #48 | MR6 изменение гостем → `PUT order/{id}/update` | ✅, смержен |
| #50 | `codex/saby-confirmation-source` → #49 | MR8 `ASTOR_BOOKING_CONFIRMATION_SOURCE=HOSTESS\|VENUE_SYSTEM` (B12) | ✅, смержен |
| #51 | `codex/saby-lunch-dishes` → #50 | MR7 блюда ланча в ту же бронь (`update` + `nomenclatures`) | ✅, смержен |
| #52 | `codex/saby-stub` → `main` | стаб Presto + записывающий прокси | ✅, смержен |

Весь Java-код #47–#51 **не компилировался локально** (Maven Central закрыт из облачной среды Claude) — только CI.

## 3. Что блокирует живой запуск

Не код — доступы. Поддержка Saby (06.10): лицензия Presto Профи подходит; «Подключения к Saby» (ID подключения, защищённый ключ, сервисный ключ) выдаёт только **руководитель организации** ООО «Счастье» — Тариэль, у Михаила раздела нет. Инструкция для Тариэля — в чате Михаила от 06.10 (Настройки → Система → Безопасность → Подключения к Saby → «+», «С ограничением по правам», «По сервисному ключу»). Ключи — только в менеджер паролей → `.env.production` на ВМ, не в issue/Telegram/git.

Неизвестны до первого живого вызова: форма ответа `order/create` (B4), `GET order/{id}`, `update`, `price-list`, `nomenclature/list`. Парсеры защитные; при другой форме — честный `PROVIDER_RESULT_UNKNOWN` / «внести вручную», не падение.

## 4. Порядок после получения ключей

1. `listPoints()` (или `GET /retail/point/list` через стаб в режиме `record`) → `SABY_POINT_ID`; `hall/list` → `SABY_HALL_ID`, имена столов (должны совпадать с кодами столов Butler: «5», «Стол 5», «05» — `ExternalTableCodes`).
2. `ASTOR_SABY_ENABLED=true`, запись выключена → сутки смотрим, что занятость из Saby не ломает выбор стола.
3. `SABY_SMOKE_WRITE=true` + тестовый телефон на согласованный с Тариэлем стол → в выводе `responseFields` реальная форма `create` → поправить `firstText` в `SabyReservationProvider`, если поле другое; записать в `SABY_PRESTO_BOOKING_API.md` 4.4.
4. `SABY_WRITE_ENABLED=true` на стенде (сначала прогон 9 шагов на стабе — `SABY_E2E_DEMO.md` §2); `ExternalBookingSync` подтверждает заявки по Presto; решить `ASTOR_BOOKING_CONFIRMATION_SOURCE` для показа 15.10 (решение Михаила).
5. Ланч: `SABY_PRICE_LIST_ID` из `price-list`, проверить, что названия блюд в `business-lunch/aeris.json` совпадают с Presto буква в букву (иначе `DISH_NOT_IN_SABY` и заказ остаётся хостес).

Без ключей: на стенде `docker compose … -f docker-compose.saby-stub.yml`, прогнать в Telegram полный цикл; на показе — страховка «эмуляция Presto».

## 5. Что ещё не сделано

- `GET order/{id}` читается перед `update` (#49), но стол, назначенный админом Saby при `woTable`, в карточку хостес не подтягивается (форма ответа неизвестна).
- Вебхуки Saby вместо опроса — требуют публичный HTTPS и неописанную проверку подлинности; для пилота опрос.
- `booking/calendar` как подсказка времени, стоп-лист и меню из Saby для ответов бота (сейчас — парсер сайта, `scripts/aeris_menu`).
- Оплата: `payment-link` реализован (#59) за `SABY_PAYMENT_ENABLED`; включать только после живой ссылки и эквайринга в Presto. Депозит — не раньше подтверждённой интеграции, решение заказчика. Чаевые через Saby — метода в API нет, вопрос в поддержку.
- Durable-идемпотентность только через `sbis_external_id`; кэш ключей в провайдере — на процесс (24 ч).

## 6. Как начать новый чат

Вставить:

> Продолжаем интеграцию Astor Butler ↔ Saby Presto. Репозиторий `astor-hospitality/Astor_Butler_MVP`. Сначала прочитай `docs/integrations/SABY_NEXT_CHAT.md`, затем `SABY_PRESTO_BOOKING_API.md` и `CLAUDE.md`. Проверь `main` и открытые PR — файл мог устареть; стек Saby (#36–#61) уже в `main`. Правила: FSM — источник истины; бронь не считается подтверждённой без ответа ресторана; ключи Saby только в серверном окружении; `src/main/**` — только через ветку и PR, в `main` не пушить; локальные тесты и CI — отдельные проверки. Задача на сегодня: <…>.

Если чат ведёт Claude в облаке: Maven Central оттуда недоступен, компиляция — только CI; GitHub Actions-логи читаются через браузер после входа в GitHub.
