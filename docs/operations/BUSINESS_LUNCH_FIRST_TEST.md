# Бизнес-ланч: первый живой тест в AERIS (полуавтомат и автомат с Saby)

Дата: 2026-10-10. Ветка `docs/business-lunch-first-test`, только документация: кода, деплоев и секретов здесь нет.
Для Михаила, Димы (`@dmtrshm`, сторона Saby), Ромы и команды ресторана. Всё ниже сверено с `main` на `f5dd496`
(последний деплой на Cloud.ru VM 2026-10-08) — ссылки на файлы даны, чтобы любое утверждение можно было перепроверить.

Что проверяем: двое статистов оформляют бизнес-ланч через AERIS-бота двумя путями — **полуавтомат** (заявку подтверждает
хостес кнопкой в Telegram) и **автомат** (бронь и блюда уходят в Saby Presto, подтверждение, оплата и закрытие приходят из
Presto). Связанные документы: [SABY_E2E_DEMO.md](../integrations/SABY_E2E_DEMO.md) (сценарий показа),
[SABY_BILLING.md](../integrations/SABY_BILLING.md) (счета), [SABY_NEXT_CHAT.md](../integrations/SABY_NEXT_CHAT.md) (порядок
включения Saby), [CONCIERGE_BUTLER_HANDOFF_DRAFT.md](../contracts/CONCIERGE_BUTLER_HANDOFF_DRAFT.md) §6 (вход из Concierge),
[AERIS_MANAGER_INTERVIEW_1.md](AERIS_MANAGER_INTERVIEW_1.md) (Discovery-опросник для менеджера смены: процесс ланча, роли, аккаунты, Saby, условия теста).

## 0. Коротко: что получится, а что нет

| | Полуавтомат (хостес) | Автомат (Saby) |
| --- | --- | --- |
| Готовность кода | Готов, ничего включать не нужно | Код есть, но из Telegram-диалога **недостижим**: см. §1.7, п. 1 |
| Что нужно на VM | `AERIS_ASTOR_BUTLER_STAFF_CHAT_ID`, желательно admin/system чаты | то же + ключи Saby + флаги `ASTOR_SABY_ENABLED`, `SABY_WRITE_ENABLED`, `SABY_PAYMENT_ENABLED`, `ASTOR_BILLING_ENABLED` |
| Кто подтверждает | Хостес кнопкой «Да» в staff-чате | Сотрудник в Presto; Butler читает статус раз в минуту |
| Риск для ресторана | Нулевой: Presto не трогается | Пишем в живой Presto; форма его ответов ни разу не проверена |

Рекомендация: первый прогон — **полуавтомат с двумя статистами**, параллельно Дима снимает телефонный блокер (§1.7 п. 1)
и делает read-only smoke Saby. Автомат — вторым прогоном, когда оба пункта закрыты.

## 1. Что делает код сегодня

### 1.1 Как гость попадает в сценарий

`fsm/scenario/BusinessLunchHandoff.java`, `fsm/scenario/BusinessLunchScenario.java` (`supports`, `rememberHandoff`,
`continueAfterFirstTouch`), `fsm/scenario/ScenarioRouter.java:50-60`.

1. **Ссылка из Concierge** — `https://t.me/<AERIS_ASTOR_BUTLER_BOT_USERNAME>?start=lunch_aeris[_s<комбо>][_p<гостей>][_d<ГГГГММДД>][_t<ЧЧММ>][_r<id запроса>]`.
   Бот получает `/start lunch_aeris_p2_d20261013_t1300_r7f3a`; первым идёт код заведения, остальное по желанию. Что не
   распарсилось или не подходит (суббота, 19:00, 40 гостей) — бот спросит сам. Параметр Telegram: до 64 символов `A-Za-z0-9_-`.
2. **Сообщение в шлюз** `POST /api/messages` с `payload.concierge = {scenario: "BUSINESS_LUNCH", venueCode, setCode, partySize, date, time, requestId}` — для автоматизированного Concierge; в этом тесте не используется.
3. **Прямой вход** — гость в главном меню пишет «бизнес-ланч» / «ланч» / «lunch» (`asksForLunch`). Кнопки «Бизнес-ланч» в
   клавиатуре бота нет (`TableReservationNotificationService.guestMainMenuKeyboard`, `TelegramRouter`), только текст.

Новый гость сначала проходит согласие и контакт (`FirstTouchScenario`: состояние `CONSENT_REQUIRED`, кнопка «Согласиться и
поделиться контактом»). Черновик ланча из ссылки ждёт в Redis (`BusinessLunchDraftStorage`, ключ `astor:lunch:draft:telegram:<chatId>`,
TTL сутки) и продолжается сразу после контакта: «Спасибо, контакт получил.» + первый вопрос ланча. Голый `/start` черновик стирает.

**Concierge сегодня — это документация без приложения** (`~/IdeaProjects/Astor_Concierge/README.md`: «пока не содержит
приложения»). Поэтому «путь из Concierge» в тесте означает: статист открывает deep-link, который мы ему пришлём.

### 1.2 Шаги диалога (состояния FSM)

`fsm/core/BotState.java:24-29`, `BusinessLunchScenario.advance/onSet/onDish/onPartySize/onDate/onTime/onConfirmation`,
меню — `src/main/resources/business-lunch/aeris.json` (загружается `domain/lunch/BusinessLunchCatalog`).

Меню AERIS — **без комбо** (`sets: []`), пять разделов, 17 позиций с ценами; `confirmedByVenue: false`, поэтому бот добавляет
«Состав и стоимость на день подтвердит команда.» Дни — будни, окно 12:00–16:00, посадка 90 минут
(`BusinessLunchOffer.seating()`), время заведения `Asia/Yekaterinburg` (`BookingTimeProvider.VENUE_ZONE`).

| Состояние | Вопрос бота | Кнопки |
| --- | --- | --- |
| `BUSINESS_LUNCH_CHOOSE_SET` | «Какой вариант выбираете?» | только для меню с комбо — для AERIS **пропускается** |
| `BUSINESS_LUNCH_CHOOSE_DISH` | «Салаты: что добавить? Каждое нажатие добавляет одну порцию.» и далее по разделам (Суп, Горячее, Десерты, Напитки); после первого блюда — строка «В заказе: … Итого … ₽.» | по одной на блюдо «Нисуаз · 290 ₽», `➖ Убрать последнее`, `➡️ Дальше`, `↩️ Отменить` |
| `BUSINESS_LUNCH_COLLECT_PARTY_SIZE` | «На сколько гостей накрыть?» | `1` `2` `3` `4` (до 20, `BusinessLunchService.MAX_GUESTS`) |
| `BUSINESS_LUNCH_COLLECT_DATE` | «В какой день вас ждать?» | «Сегодня 13.10», «Завтра 14.10», «Ср 15.10» … только дни ланча |
| `BUSINESS_LUNCH_COLLECT_TIME` | «Во сколько вас ждать?» | получасовые слоты 12:00…15:30; на сегодня — только будущие |
| `BUSINESS_LUNCH_CONFIRMATION` | сводка + «Отправляю заявку команде? Если есть пожелания, напишите их одной строкой, я передам. Лишнее можно убрать: напишите «убрать» и название блюда.» | `✅ Отправить заявку`, `✏️ Изменить`, `↩️ Отменить` |

«Да»/«✅ Отправить заявку» — `isYes`; «отмена»/«↩️ Отменить» на любом шаге — выход в главное меню без заявки; длинная
фраза на сводке (> 4 слов) — пожелание для команды; короткая («лучше в 14:30», «на троих») — правка сводки.

### 1.3 Что происходит после «✅ Отправить заявку»

`BusinessLunchScenario.place` → `domain/lunch/BusinessLunchService.place`:

1. `TableReservationService.findOverlappingReservation` — если у гостя уже есть заявка на пересекающееся время, второй стол не
   удерживается: «У вас уже есть заявка #N … вторую не создаю».
2. Выбирается внешний провайдер: `ExternalLunchOrderProvider` со `status().enabled() && configured()` — это `SabyLunchOrderProvider`,
   когда `ASTOR_SABY_ENABLED=true` и заданы `SABY_APP_CLIENT_ID`, `SABY_APP_SECRET`, `SABY_SECRET_KEY`, `SABY_POINT_ID`
   (`SabyReservationProperties.missingConfiguration`). Иначе — `MANUAL_ENTRY`.
3. `TableReservationService.createReservation` (`domain/booking/TableReservationService.java:92-116`):
   - `externalProvider.checkAvailability` → при включённом Saby `GET /retail/hall/list` (занятость столов из Presto; при
     ошибке решает локальная база);
   - локальный стол, удержание, статус `AWAITING_MANAGER_CONFIRMATION`, пометка `seatingPreference = «Бизнес-ланч»`,
     комментарий хостес вида «Бизнес-ланч из Concierge: Нисуаз × 1, Борщ со сметаной × 2. Итого 830 ₽. Пожелание: … В Saby внести вручную.»
     (`BusinessLunchService.hostessComment`; хвост «В Saby внести вручную» — только если провайдера нет);
   - `reserveExternally` → `SabyReservationProvider.reserve` → `POST /retail/order/create` (`SabyOrderPayload.create`: `product=restaurant`,
     `pointId`, `datetime`, `customer{name, phone}`, `booking{visitors, hall, woTable=true}`, комментарий с маркером
     `Astor Butler #<id заявки>`). Нужны `SABY_WRITE_ENABLED=true`, имя **и российский телефон гостя**; иначе
     `SABY_WRITE_DISABLED` / `GUEST_DATA_REQUIRED`, в Presto ничего не создаётся;
   - `sbisExternalId` сохраняется в заявку; карточка хостес `notifyHostessApprovalRequest` с кнопками «Да»/«Нет» и строкой
     «Saby: …» (`TableReservationNotificationService.externalSyncLine`).
4. `SabyLunchOrderProvider.submit(order, "astor-lunch-<id>")` (`integration/saby/SabyLunchOrderProvider.java`): fail-closed цепочка
   `NO_SABY_BOOKING` (нет `sbisExternalId`) → `GUEST_DATA_REQUIRED` → `GET /retail/nomenclature/price-list` (или `SABY_PRICE_LIST_ID`) →
   `GET /retail/v2/nomenclature/list?searchString=<название>` по каждому блюду, совпадение названия **буква в букву** без учёта
   регистра (`SabyMenuCatalog.normalize`), иначе `DISH_NOT_IN_SABY` и заказ целиком остаётся хостес → `GET /retail/order/{id}`
   (проверка полноты снимка, своей точки, отсутствия блюд, иначе `EXISTING_DISHES_REQUIRE_REVIEW`) → `PUT /retail/order/{id}/update`
   с `nomenclatures` → `SABY_LUNCH_ATTACHED`.
5. Слушатели (`BusinessLunchOrderListener`): `domain/billing/LunchBillRecorder` при `ASTOR_BILLING_ENABLED=true` открывает счёт
   `ESTIMATED` (сумма по ценам `aeris.json`) и переводит в `ISSUED`, если Saby принял; `saby_call_log` при `ASTOR_SABY_CALL_LOG_ENABLED=true`.
6. Гостю: «Готово. Заявку #N на бизнес-ланч передал команде AERIS. Как только хостес ответит, я вернусь с финальным статусом.» + сводка.
   Если Saby был включён, но заказ не принял — в admin-чат (`TELEGRAM_ADMIN_CHAT_ID`) уходит `AdminAlert` «Astor Butler / business lunch —
   SABY не принял заказ. Внесите его вручную. … Статус: <код>» (`BusinessLunchScenario.externalAlert`).

Butler нигде не называет бронь подтверждённой — это делает следующий шаг.

### 1.4 Полуавтомат: подтверждает хостес

`domain/booking/HostessReservationApprovalService.java`, `TableReservationNotificationService.java`, `telegram/adapter/TelegramRouter.handleCallback`.

- Карточка «Новая заявка на бронь стола» приходит в чат `TELEGRAM_HOSTESS_CHAT_ID` (для заявки берётся `telegram.booking.hostess-chat-id`
  на момент создания, `BusinessLunchService.hostessChatId`). Кнопки `table_booking:confirm:<id>` / `table_booking:reject:<id>`
  работают **только из этого чата** (`isHostessChat`).
- «Да» → `TableReservationService.confirm` → гостю «Бронь подтверждена / Ваш стол ждет вас …», хостес «Принял. Бронь #N подтверждена,
  гостю отправлен красивый ордер.» + карточка «Бронь стола подтверждена командой».
- «Нет» → `reject` → гостю «Пока не получилось подтвердить этот стол …» с альтернативой; если бронь была в Saby — `PUT /retail/order/{id}/cancel`.
- Режим `ASTOR_BOOKING_CONFIRMATION_SOURCE` (`application.yaml:117`, `BookingConfirmationSource`): по умолчанию `HOSTESS`. В `VENUE_SYSTEM`
  кнопка «Да» на заявке, которая уже в Presto, отвечает «подтверждается в Presto: примите её там» — одна бронь не подтверждается дважды.
  **Эта переменная не пробрасывается ни одним `docker-compose*.yml`**, на VM действует только `HOSTESS` (см. §1.7 п. 5).

### 1.5 Автомат: что делает Saby без человека в Telegram

| Шаг | Код | Вызов Saby | Что видит гость / персонал |
| --- | --- | --- | --- |
| Бронь | `SabyReservationProvider.reserve` | `POST /retail/order/create` | карточка хостес со строкой «Saby: бронь уже создана в Presto без стола — посадите её там на стол из заявки» |
| Блюда | `SabyLunchOrderProvider.submit` | `price-list`, `nomenclature/list`, `GET order/{id}`, `PUT order/{id}/update` | в Presto у брони появляются позиции ланча; гостю ничего |
| Подтверждение | `domain/booking/ExternalBookingSync` (каждые `ASTOR_EXTERNAL_SYNC_DELAY_MS`=60 с, первый через 90 с) | `GET /retail/order/{id}/state` | `state` 20/50 → `confirmFromVenue` → гостю «Бронь подтверждена», хостес — карточка «подтверждена командой»; 220 → отказ/отмена гостю; 180/200 → `COMPLETED` (`SabyBookingState`) |
| Счёт | `LunchBillRecorder`, `GuestBillService` | — | счёт `ISSUED` в `guest_bills` |
| Ссылка на оплату | `domain/billing/GuestBillPaymentPrompter` (каждые 30 с, после подтверждения) | `GET /retail/order/{id}/payment-link` (`SabyPaymentProvider`, нужен `SABY_PAYMENT_ENABLED=true`) | гостю «Счёт по вашему заказу … Предварительно: 1720 ₽» + кнопка «Оплатить» (ссылка ресторана) |
| Оплата | `BillingSync.onVenueSnapshot` | тот же `state`, поле `payState` (`SabyPayState`: 10/200 → PAID) | гостю «Оплата получена» |
| Закрытие заказа | `BillingSync` → `VisitReviewService.prompt` | `state` 200 / `productState` 1999 | гостю «Как прошёл обед?» со звёздами, затем 👍/👎 и «Оставить чаевые» (страховка по таймеру `ASTOR_BILLING_REVIEW_DELAY_MINUTES` после конца ланча) |

Всё это выключено по умолчанию; без ключей Saby и флагов ни один HTTP-вызов не делается (`SABY_BILLING.md` §1).

### 1.6 Переключатели

Имена — как в `application.yaml` и `docker-compose.prod.yml`. Колонка «проброс» = есть ли переменная в `environment:` сервиса
`aeris-astor-butler-bot` (compose не использует `env_file`, поэтому переменная без проброса из `.env.production` в контейнер не попадает).

| Переменная | По умолчанию | Что включает | Проброс |
| --- | --- | --- | --- |
| `ASTOR_SABY_ENABLED` | `false` | адаптер Saby (чтение занятости, опрос статусов) | да |
| `SABY_WRITE_ENABLED` | `false` | `order/create`, `order/update` (блюда), `cancel` | да |
| `SABY_PAYMENT_ENABLED` | `false` | `payment-link` → кнопка «Оплатить» гостю | да |
| `ASTOR_BILLING_ENABLED` | `false` | счета `guest_bills`, журнал `bill_operations`, оценка после визита | да |
| `ASTOR_SABY_CALL_LOG_ENABLED` | `false` | `saby_call_log` (метод, путь, код, длительность; без тел и токенов) | да |
| `ASTOR_BILLING_REVIEW_DELAY_MINUTES` | `60` | через сколько минут после конца ланча спросить оценку, если Presto не закрыл заказ | да |
| `SABY_APP_CLIENT_ID`, `SABY_APP_SECRET`, `SABY_SECRET_KEY`, `SABY_POINT_ID` | пусто | обязательные для `configured()` | да |
| `SABY_HALL_ID`, `SABY_PRICE_LIST_ID` | пусто | зал (нужен, если Butler называет стол) и прайс-лист ланча (иначе берётся первый из `price-list`) | да |
| `ASTOR_BOOKING_CONFIRMATION_SOURCE` | `HOSTESS` | кто даёт финальное «подтверждено» | **нет** |
| `ASTOR_EXTERNAL_SYNC_DELAY_MS` / `_INITIAL_DELAY_MS` | 60000 / 90000 | период опроса Presto | **нет** |
| `ASTOR_BUSINESS_LUNCH_ENABLED`, `ASTOR_BUSINESS_LUNCH_VENUE` | `true` / `AERIS` | сам сценарий | **нет** (значит, выключить его на VM без правки compose нельзя) |
| `SABY_API_BASE_URL`, `SABY_AUTH_URL` | `https://api.sbis.ru`, `https://online.sbis.ru/oauth/service/` | адреса живого Saby | **нет** (стаб подключается только оверлеем `docker-compose.saby-stub.yml`) |
| `TELEGRAM_HOSTESS_CHAT_ID` ← `AERIS_ASTOR_BUTLER_STAFF_CHAT_ID` | пусто | staff-чат: карточки хостес, кнопки Да/Нет | да (`docker-compose.yml:290`) |
| `TELEGRAM_ADMIN_CHAT_ID` ← `AERIS_ASTOR_BUTLER_ADMIN_CHAT_ID` | пусто | admin-чат: алерты «Saby не принял», manager help, safe-play | да (`:288`) |
| `TELEGRAM_SYSTEM_CHAT_ID` ← `AERIS_ASTOR_BUTLER_SYSTEM_CHAT_ID` | пусто | system-чат: трассировка каждого диалога (`TelegramSystemNotifier`) | да (`:291`) |
| `TELEGRAM_ANALYTICS_CHAT_ID` ← `AERIS_ASTOR_BUTLER_ANALYTICS_CHAT_ID` | = admin | карточки событий из Kafka, сводки смены | да (`:289`) |
| `TELEGRAM_OPS_CHAT_ID` | = hostess-чат | ops-команды (`/ops`, `/projects`) у AERIS-бота | только у профиля `smart-solution` (`SMART_SOLUTION_OPS_CHAT_ID`, `docker-compose.prod.yml:275`) |
| `TELEGRAM_BOOKING_MANAGER_CHAT_ID` | `876857557` | записывается в заявку как `managerTelegramId`; уведомлений на него код не шлёт | **нет** |

### 1.7 Чего нет, что заглушка, что допущение

1. **Телефон гостя не доходит до Saby из диалога — блокер автомата.** `TelegramRouter.toIncomingMessage` (`:245-271`) кладёт
   `contactPhone` только из `message.getContact()`, т.е. из того единственного сообщения, которым гость делится контактом.
   На шаге «✅ Отправить заявку» это обычный текст, `incoming.contactPhone()` = `null`, и `BusinessLunchScenario.place` (`:408`)
   передаёт `guestPhone = null` (так же `TableBookingScenario:435`). Дальше по цепочке: `SabyReservationProvider.reserve` →
   `GUEST_DATA_REQUIRED` → нет `sbisExternalId` → `SabyLunchOrderProvider.submit` → `NO_SABY_BOOKING` → карточка хостес
   «Saby: нет имени или телефона гостя. Внесите бронь в Presto вручную.» + алерт в admin-чат. Телефон при этом сохранён в
   `user_contacts` (`domain/identity/IdentityService.upsertPhoneContact`), но никто его оттуда не читает. Нужна правка кода
   (подставлять основной телефон из `user_contacts` при сборке `TableReservationCommand`/`BusinessLunchService.Request`) —
   отдельный PR с тестом, не часть этого документа. **Побочный эффект уже сейчас:** на карточке хостес «Телефон: не указан».
2. **Живой Saby ни разу не вызывался** (`SABY_BILLING.md` §10, `SABY_NEXT_CHAT.md`: «Реальный Saby не проверялся»). Форма ответов
   `order/create`, `GET order/{id}`, `price-list`, `nomenclature/list`, `payment-link` — допущения; `scripts/saby_stub` отвечает «как мы
   думаем» (`scripts/saby_stub/README.md`, «Честно»). Незнакомая форма снимка брони **блокирует** запись блюд (`RESULT_UNKNOWN`), а не ломает заявку.
3. **Стаб vs живой.** На VM стаб не поднят и в прод-профиль не входит; `SABY_API_BASE_URL` не пробрасывается, так что при `ASTOR_SABY_ENABLED=true`
   бот пойдёт только в `api.sbis.ru`. Прогон со стабом — только на стенде/локально (`docker-compose.saby-stub.yml`).
4. **Concierge — без кода**; `lunchUrl` в ленте, `requestId`, обратный вызов Concierge о статусе — не существуют. Источник
   `CONCIERGE` в заявке определяется только по deep-link.
5. **Не пробрасываемые переменные** (§1.6): `ASTOR_BOOKING_CONFIRMATION_SOURCE`, `ASTOR_EXTERNAL_SYNC_*`, `ASTOR_BUSINESS_LUNCH_*`,
   `SABY_API_BASE_URL/AUTH_URL`, `TELEGRAM_BOOKING_MANAGER_CHAT_ID`. Для теста это терпимо (режим `HOSTESS` + опрос Presto работают
   параллельно: кто первый подтвердил, тот и подтвердил; второе действие — no-op). Для показа 15.10 решение по `VENUE_SYSTEM` требует
   строки в `docker-compose.prod.yml` (зона Егора/Димы).
6. **Меню не сверено**: `confirmedByVenue: false`, цены и окно 12:00–16:00 — с фото печатного меню 06.10 (`CONCIERGE_BUTLER_HANDOFF_DRAFT.md` §6).
   Названия блюд в Presto должны совпадать с `aeris.json` буква в букву, иначе `DISH_NOT_IN_SABY` на весь заказ.
7. **Нет**: чаевых через Saby (кнопка ведёт в сценарий СБП сотрудника), суммы ресторана до оплаты (показывается предварительная Butler),
   «Мои счета», повторной отправки ссылки, повторной отправки заказа в Saby после сбоя, разных комбо на гостей (`SABY_E2E_DEMO.md` §4, `SABY_BILLING.md` §9).
8. **Staff portal / роли `StaffScope` (WAITER, HOSTESS, MANAGER)** — Keycloak-контур поднят, но staff API не задеплоен (`STAFF_PORTAL_RUNBOOK.md`);
   в этом тесте роли сотрудников = членство в Telegram-чатах, не JWT.
9. **Миграции billing** (`2026-10-07-guest-billing.sql`, `2026-10-08-guest-billing-payment-review.sql`) «на настоящем PostgreSQL не запускались»
   (`SABY_BILLING.md` §10). База на VM создана пустой 2026-10-08, значит они уже применились при старте — проверить по логам `Liquibase` перед тестом.
10. **Analytics-чат из Kafka** (`analytics/AnalyticsKafkaConsumer`, `KAFKA_TOPICS.md`) работает, только если Debezium/outbox на VM живы — это не
    условие теста, а приятный бонус.

## 2. Кого собрать и куда добавить

### 2.1 Роли → чаты → переменные

Состав и анкету команды снимает менеджер смены на Discovery-встрече по [Опроснику № 1](AERIS_MANAGER_INTERVIEW_1.md)
(раздел 4 «Аккаунты команды для Astor Butler», вопросы 13–19 про роли и связь, 27–32 про сам тест). Роли и колонки ниже — те же,
что в опроснике; **заполненную** таблицу (имена, телефоны) храним вне git — в закрытой заметке Notion/Obsidian, в репозитории только шаблон.

| Роль | Имя | Telegram @username | Телефон для связи | На смене в день теста (да/нет) |
| --- | --- | --- | --- | --- |
| Управляющий | | | | |
| Менеджер смены | | | | |
| Хостес | | | | |
| Официант 1 | | | | |
| Официант 2 | | | | |
| Бармен | | | | |
| Повар / кухня | | | | |

Что каждая роль делает в тесте и куда её добавить. Все Telegram-чаты бота — групповые; бот должен быть **администратором** группы,
иначе не видит сообщений и не может писать (`SHIFT_BRIEFING.md`, «Как подключить аналитический чат»). Для первого теста достаточно
двух групп (staff + admin); system-чат — для тех, кто разбирает трассы; analytics можно не создавать (по умолчанию = admin).

| Роль | Чаты, где должен быть | Что делает в тесте | Переменная / шаг |
| --- | --- | --- | --- |
| Управляющий | admin, staff, (analytics) | смотрит алерты, принимает решение «автомат/нет», отвечает на вопросы 21 и 39–41 опросника | id групп → `AERIS_ASTOR_BUTLER_ADMIN_CHAT_ID`, `AERIS_ASTOR_BUTLER_STAFF_CHAT_ID` |
| Менеджер смены | admin, staff | ведёт смену, дублирует хостес, отвечает на «позвать менеджера»; в автомате — у кассы Saby (вопрос 30) | те же группы |
| Хостес | **staff (обязательно)** | жмёт «Да»/«Нет» на карточке заявки; в автомате — принимает бронь в Presto (вопрос 29) | `AERIS_ASTOR_BUTLER_STAFF_CHAT_ID` |
| Официант 1 | staff | обслуживает стол статиста A, видит состав заказа и время; в автомате — закрывает заказ в Presto | — |
| Официант 2 | staff | обслуживает стол статиста B; подмена по вопросу 16 | — |
| Бармен | staff | видит раздел «Напитки» в заказе ланча | — |
| Повар / кухня | staff | видит блюда и время подачи; сверяет 17 названий блюд с Presto (§1.2) | — |

Организаторы теста (Михаил, Дима, Рома) — в admin, staff и system (`AERIS_ASTOR_BUTLER_SYSTEM_CHAT_ID`). Два статиста — гости:
ни в одном служебном чате не состоят (§3.1); статист A открывает deep-link «из Concierge», статист B пишет `/start` и «бизнес-ланч».

Персональные id сотрудников для бота **не нужны**: в коде нет списков разрешённых пользователей, права даёт членство в группе,
чей id записан в переменной. Единственный персональный id в конфиге — `TELEGRAM_OPS_OWNER_USER_ID` у ops-профиля (не для этого теста).

### 2.2 Как безопасно получить числовые id

Id не пишем в чаты и документы. Они попадают только в `/opt/astor-butler/.env.production` на VM (root:docker 0640).

- **Группы (staff/admin/system):** создать группу, добавить бота администратором, написать в ней любое сообщение. На VM:
  `docker compose … logs --since 10m aeris-astor-butler-bot | grep 'InboundEvent accepted'` — строка `[PIPELINE] … chatId=-100…`
  (`TelegramRouter.java:230-235`, уровень INFO). У групп id отрицательный, у супергрупп начинается с `-100`. Если Telegram
  превратил группу в супергруппу, при первой отправке аналитики бот напишет в лог новый id (`TelegramAdminNotifier`: «chat migrated to supergroup. Set …»).
- **Альтернатива** из `SHIFT_BRIEFING.md` — `getUpdates` через `curl` с токеном. Делать это только при **остановленном** боте: он
  работает long-polling, и параллельный `getUpdates` даёт 409 и может «съесть» обновления.
- **Люди (если когда-нибудь понадобятся):** человек пишет боту `/start`; его `chat`/`user` id видны в карточке system-чата
  («Telegram: chat … / user … / @username», `TelegramSystemNotifier.java:133-137`) и в карточке хостес. В группу не копировать.
- **Проверка, что id верный:** после перезапуска бот шлёт стартовое сообщение в admin-чат (`StartupAdminNotifier`) и закрепляет
  карточки-превью в admin/staff/system (`OperationalChatPreviewNotifier`, флаг `ASTOR_OPERATIONAL_PREVIEW_ENABLED`). Нет закреплённой
  карточки — id не тот или бот не админ.

## 3. Статисты и сценарии

### 3.1 Что нужно от двух статистов

- Два **разных** Telegram-аккаунта с российским номером (для автомата Saby принимает только `7XXXXXXXXXX`, `SabyOrderPayload.phone`),
  не сотрудники ресторана и не участники staff/admin чатов (иначе их сообщения пойдут по ветке служебного чата).
- Никогда не писали AERIS-боту — тогда они пройдут согласие и контакт как настоящие новые гости. Если писали — `/start` сбросит диалог,
  но согласие уже выдано и контакт запрашиваться не будет (`FirstTouchScenario.handleStart`).
- Телефон с Telegram на руках в момент теста; статист A должен уметь открыть ссылку из нашего сообщения.
- Дата теста — будний день, время внутри 12:00–16:00 Екатеринбурга; ссылку и сообщения готовим на этот день.

### 3.2 Полуавтомат: скрипт диалога (Saby выключен или только чтение)

Пред-условие: `AERIS_ASTOR_BUTLER_STAFF_CHAT_ID` задан, хостес в staff-чате. `ASTOR_SABY_ENABLED=false` (как сейчас) **или** `true`
с `SABY_WRITE_ENABLED=false` — тогда ко всем карточкам добавится «Saby: запись из Butler выключена. Внесите бронь в Presto вручную.»,
а в admin-чат на каждый ланч придёт алерт `SABY_WRITE_DISABLED` — это ожидаемо.

**Статист A — «из Concierge».** Ему отправляем ссылку вида
`https://t.me/<бот>?start=lunch_aeris_p2_d<ГГГГММДД>_t1300_rtest-a` (день — дата теста).

| # | Гость | Ожидаемый ответ бота | Состояние |
| --- | --- | --- | --- |
| 1 | открывает ссылку, нажимает «Start» | «Нажимая кнопку "Согласиться и поделиться контактом", вы соглашаетесь с политикой…» + кнопка | `CONSENT_REQUIRED` |
| 2 | нажимает «Согласиться и поделиться контактом» | «Спасибо, контакт получил.» + «Бизнес-ланч в AERIS: по будням с 12:00 до 16:00. Состав и стоимость на день подтвердит команда.» + полное меню + «Салаты: что добавить? …» | `BUSINESS_LUNCH_CHOOSE_DISH` |
| 3 | «Нисуаз · 290 ₽» | «Добавил: Нисуаз.» + «Салаты: …» + «В заказе: Нисуаз × 1. Итого 290 ₽.» | то же |
| 4 | «➡️ Дальше» | «Суп: что добавить? …» | то же |
| 5 | «Борщ со сметаной · 270 ₽» ×2 | «Добавил: Борщ со сметаной.» … «В заказе: Нисуаз × 1, Борщ со сметаной × 2. Итого 830 ₽.» | то же |
| 6 | «➡️ Дальше» → «Треска с соусом тартар · 520 ₽» → «➡️ Дальше» → «➡️ Дальше» (десерты) → «Домашний клюквенный морс · 140 ₽» → «➡️ Дальше» | после последнего раздела — сразу сводка (гости, день, время уже из ссылки) | `BUSINESS_LUNCH_CONFIRMATION` |
| 7 | читает сводку: «Бизнес-ланч в AERIS / <День>, <дд.мм> в 13:00 / Гостей: 2 / Заказ: … / Итого: 1490 ₽ / Состав и стоимость на день подтвердит команда.» | «Отправляю заявку команде? …» | то же |
| 8 | «один гость не ест лук» (пожелание) | «Пожелание записал.» + сводка с строкой «Пожелание: …» | то же |
| 9 | «✅ Отправить заявку» | «Готово. Заявку #N на бизнес-ланч передал команде AERIS. Как только хостес ответит, я вернусь с финальным статусом.» + сводка | `READY_FOR_DIALOG` |

**Статист B — «напрямую».**

| # | Гость | Ожидаемый ответ бота |
| --- | --- | --- |
| 1 | `/start` → «Согласиться и поделиться контактом» | «Спасибо, контакт получил. Я на связи: …» + главное меню |
| 2 | «бизнес-ланч завтра в 13:00 на двоих» (или просто «ланч») | интро + меню + «Салаты: что добавить?» — день/время/гости уже услышаны (`heardAtOnce`); если написал просто «ланч» — спросит их после блюд |
| 3 | набирает 2–3 блюда, «➡️ Дальше» до конца | сводка → «Отправляю заявку команде?» |
| 4 | «✏️ Изменить» | «Хорошо, соберем заново.» + «Салаты: что добавить?» — проверяем сброс |
| 5 | набирает снова, доходит до сводки, «↩️ Отменить» | «Хорошо, бизнес-ланч не оформляю. Главное меню оставил под рукой.» — проверяем выход |
| 6 | «ланч» → заново до сводки → «✅ Отправить заявку» | «Готово. Заявку #M …» |
| 7 | через минуту: «Изменить / отменить» → «❌ Отменить стол» | «Готово. Я отменил бронь стола #M и освободил слот.» — хостес получает «Гость отменил бронь стола» |

**Что должно прийти персоналу (полуавтомат):**

| Куда | Что | Файл |
| --- | --- | --- |
| staff-чат | «Новая заявка на бронь стола» #N: Гость, «Telegram: chat … / user …», Стол, Дата, Время 13:00 - 14:30, Гостей: 2, **«Телефон: не указан»** (см. §1.7 п. 1), «Зона/пожелание: Бизнес-ланч», блок «Исходный запрос гостя» с составом «Бизнес-ланч из Concierge: … Итого 1490 ₽. Пожелание: … В Saby внести вручную.», «Последние сообщения гостя», «Статус: ожидает решения хостес», кнопки **Да / Нет** | `TableReservationNotificationService.hostessApprovalRequestText` |
| staff-чат, после «Да» | «Принял. Бронь #N подтверждена, гостю отправлен красивый ордер.» + «Бронь стола подтверждена командой» | `notifyHostessAcknowledged`, `notifyHostessConfirmed` |
| гость, после «Да» | «Бронь подтверждена / Ваш стол ждет вас. / Заказ: #N / Стол … / Пожелание: Бизнес-ланч» | `guestConfirmedText` |
| гость, после «Нет» | «Пока не получилось подтвердить этот стол …» + предложение другого стола/времени | `guestRejectedText` |
| staff-чат, отмена гостем | «Гость отменил бронь стола … статус CANCELLED» | `notifyHostessGuestCancelled` |
| admin-чат | только при Saby `enabled` и неудачной отправке: «Astor Butler / business lunch — SABY не принял заказ. Внесите его вручную. … Статус: …» | `BusinessLunchScenario.externalAlert` |
| system-чат | карточка на каждое сообщение: `#dialog_telegram_<chat>`, previous → next state, action tags (`BUSINESS_LUNCH`, `LUNCH_ORDER_PLACED`, `EXTERNAL_ORDER_MANUAL`, `WAIT_HOSTESS_CONFIRMATION`) | `TelegramSystemNotifier` |

Побочных эффектов в Saby — нет (или только чтение `hall/list`, если `ASTOR_SABY_ENABLED=true`).

### 3.3 Автомат: скрипт и ожидания (Saby запись + оплата + счета)

Пред-условия: §4 «Автомат» выполнен целиком; **блокер §1.7 п. 1 закрыт отдельным PR и задеплоен** — иначе этот прогон
детерминированно заканчивается на `GUEST_DATA_REQUIRED`/`NO_SABY_BOOKING`, и его бессмысленно начинать. Порядок включения — как в
`SABY_NEXT_CHAT.md` §4: read-only smoke → сутки наблюдения занятости → запись на тестовой брони → оплата.

Диалог статистов — тот же, что в §3.2 (А по ссылке, B напрямую). Отличается то, что происходит после «✅ Отправить заявку»:

| # | Событие | Кто | Ожидание в Butler | Ожидание в Presto / Saby |
| --- | --- | --- | --- | --- |
| 1 | заявка отправлена | гость | «Готово. Заявку #N …»; в staff-чате карточка со строкой «Saby: бронь уже создана в Presto без стола — посадите её там на стол из заявки. Если нажмёте «Нет», она снимется в Saby автоматически.»; action `EXTERNAL_ORDER_SENT` в system-чате; в admin-чат — **ничего** | бронь с комментарием «… · Astor Butler #N», `woTable=true`, у брони позиции ланча (`PUT update` + `nomenclatures`); `saby_call_log`: auth, hall/list, order/create, price-list, nomenclature/list ×k, order/{id}, order/{id}/update |
| 2 | счёт открыт | — | `guest_bills`: `ISSUED`, `estimate` = Итого из сводки; `bill_operations`: `ESTIMATE_CREATED`, `ISSUED_TO_VENUE` | — |
| 3 | сотрудник **принимает бронь в Presto** (хостес «Да» в Telegram не нажимает) | хостес | в течение ≈1–2 мин (`ExternalBookingSync`): гостю «Бронь подтверждена», в staff-чат «Бронь стола подтверждена командой»; лог `External booking sync: checked=…, confirmed=1` | `state` 20 |
| 4 | ссылка на оплату | — | в течение ≈30 с после подтверждения: гостю «Счёт по вашему заказу / Заказ: #N / Предварительно: 1490 ₽ / Итоговую сумму покажет страница оплаты ресторана» + кнопка «Оплатить»; `bill_operations`: `PAYMENT_LINK_ISSUED` | `GET payment-link` вернул https-ссылку (если эквайринг не настроен — `NO_LINK`, повтор через 10 мин, кнопки не будет; остальное работает) |
| 5 | гость платит (тестовая оплата, сумма по договорённости с рестораном) | статист | при следующем опросе: «Оплата получена. Ресторан подтвердил оплату заказа #N.»; счёт `PAID`; `PAY_STATE_CHANGED` | `payState` 10 или 200 |
| 6 | визит/имитация визита; сотрудник закрывает заказ («Завершить») | официант/менеджер | «Как прошёл обед? …» ★…★★★★★ → после звезды «Спасибо! Одним словом: понравилось?» 👍/👎 → «Оставить чаевые» → сценарий чаевых (СБП сотрудника) | `state` 200 / `productState` 1999 |
| 7 | (ветка) хостес нажимает «Нет» в Telegram на шаге 1 | хостес | гостю отказ; `cancelExternally` → «Saby: бронь #N не снялась в Presto автоматически» только при ошибке | `PUT order/{id}/cancel` → `state` 220 |
| 8 | (ветка) бронь отменяют в Presto после подтверждения | сотрудник | опрос → `cancelFromVenue` → гостю «Пока не получилось подтвердить этот стол…» с альтернативой | — |

Если на шаге 1 вместо `EXTERNAL_ORDER_SENT` пришёл алерт в admin-чат — читать его «Статус:» по таблице:
`GUEST_DATA_REQUIRED` (блокер п. 1), `DISH_NOT_IN_SABY` (названия в Presto), `NO_PRICE_LIST` (задать `SABY_PRICE_LIST_ID`),
`PROVIDER_AUTH_FAILED` (ключи), `PROVIDER_RESULT_UNKNOWN` (форма ответа Saby не та, что ожидает `SabyLunchOrderProvider`;
записать ответ через `scripts/saby_stub` в режиме `record`), `EXISTING_DISHES_REQUIRE_REVIEW` (у брони в Presto уже есть позиции).
Заявка и карточка хостес при любом из них **остаются**; ланч вносится в Presto руками.

## 4. Pre-flight на VM (без значений)

VM `astor-aeris-vm` (Cloud.ru), стек из `/opt/astor-butler/current` (`main`), секреты в `/opt/astor-butler/.env.production`.
Команды — из `CLOUDRU_DEPLOY_RUNBOOK.md` §5; `docker compose --env-file ../.env.production --env-file images.env -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudru.yml --profile frontend --profile telegram …`.

**Общее (оба сценария)**

- [ ] `config --quiet` проходит (валидность env без вывода значений).
- [ ] Заполнены: `AERIS_ASTOR_BUTLER_BOT_TOKEN`, `AERIS_ASTOR_BUTLER_BOT_USERNAME` (бот отвечает на `/start`).
- [ ] `AERIS_ASTOR_BUTLER_STAFF_CHAT_ID` — staff-чат, бот админ, превью-карточка «Astor Butler Staff Chat» закреплена.
- [ ] `AERIS_ASTOR_BUTLER_ADMIN_CHAT_ID` — admin-чат, стартовое сообщение бота пришло, превью закреплено.
- [ ] `AERIS_ASTOR_BUTLER_SYSTEM_CHAT_ID` (рекомендуется) + `AERIS_ASTOR_BUTLER_SYSTEM_NOTIFICATIONS_ENABLED=true` — трассы диалогов.
- [ ] (опционально) `AERIS_ASTOR_BUTLER_ANALYTICS_CHAT_ID`; иначе аналитика идёт в admin.
- [ ] `TELEGRAM_PROXY_TYPE/HOST/PORT` — egress в Telegram через `astor-tg-awg` жив (`curl` на `api.telegram.org` из контейнера бота).
- [ ] `actuator/health` → `UP`; `logs --since 1h | grep -c ' ERROR '` = 0; в логе есть `Liquibase` без ошибок и строки про обе billing-миграции.
- [ ] Redis жив (черновик ланча живёт в Redis; без него сценарий не продолжится после контакта).
- [ ] Известная дыра: `SMART_SOLUTION_OPS_CHAT_ID` не задан (`=0`). На AERIS-бота **не влияет** — переменная читается только профилем
      `smart-solution` (`docker-compose.prod.yml:275`); если этот профиль не поднят, оставить как есть до отдельного решения.

**Автомат (дополнительно)**

- [ ] Ключи от Тариэля в `.env.production`: `SABY_APP_CLIENT_ID`, `SABY_APP_SECRET`, `SABY_SECRET_KEY` (только там; в чаты и git не попадают).
- [ ] `SABY_POINT_ID`, `SABY_HALL_ID`, `SABY_PRICE_LIST_ID` — сняты через `listPoints`/`hall/list`/`price-list` (стаб в режиме `record` или `SabyReadOnlySmokeTest` с `SABY_SMOKE=true`).
- [ ] Read-only smoke прошёл; сутки с `ASTOR_SABY_ENABLED=true`, `SABY_WRITE_ENABLED=false` — занятость из Presto не ломает выбор стола (лог `External table occupancy is not used` должен отсутствовать или быть объяснён).
- [ ] `SabyWriteSmokeTest` (`SABY_SMOKE_WRITE=true`, тестовый телефон `SABY_SMOKE_GUEST_PHONE`, согласованный с Тариэлем стол) — реальная форма `order/create` записана в `SABY_PRESTO_BOOKING_API.md` 4.4; тестовая бронь снята.
- [ ] Названия 17 блюд ланча в прайс-листе Presto совпадают с `aeris.json` буква в букву (повар/менеджер сверяют по списку в §1.2).
- [ ] Эквайринг и онлайн-оплата включены в Presto (иначе шаг 4 §3.3 даст `NO_LINK` — не ошибка, но кнопки «Оплатить» не будет).
- [ ] Флаги: `ASTOR_SABY_ENABLED=true`, `SABY_WRITE_ENABLED=true`, `SABY_PAYMENT_ENABLED=true`, `ASTOR_BILLING_ENABLED=true`, `ASTOR_SABY_CALL_LOG_ENABLED=true`, `ASTOR_BILLING_REVIEW_DELAY_MINUTES=5` (на время теста) — затем `up -d aeris-astor-butler-bot`.
- [ ] **PR с подстановкой телефона гостя (§1.7 п. 1) смержен и задеплоен** — без него автомат не стартует.
- [ ] Договорённость с рестораном: тестовые брони помечены «Astor Butler #…», кто их снимет в Presto после теста, тестовая оплата возвращается.

## 5. Таблица результатов

Заполняется во время теста; «кто наблюдал» — роль, не id.

| # | Сценарий | Шаг | Ожидание | Результат (pass/fail) | Кто наблюдал | Заметка / номер заявки |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Полуавтомат A | deep-link → согласие → контакт → продолжение ланча | §3.2 A шаги 1–2 | | статист A + Михаил | |
| 2 | Полуавтомат A | набор блюд, «Дальше», «Убрать последнее» | §3.2 A шаги 3–6 | | | |
| 3 | Полуавтомат A | сводка, пожелание, отправка | §3.2 A шаги 7–9 | | | #___ |
| 4 | Полуавтомат A | карточка в staff-чате с составом, «Бизнес-ланч из Concierge», кнопки | §3.2 «staff-чат» | | хостес | |
| 5 | Полуавтомат A | «Да» → гостю «Бронь подтверждена», хостес «Принял» | | | хостес, статист A | |
| 6 | Полуавтомат B | `/start` → «ланч …» → заявка | §3.2 B 1–3, 6 | | статист B | #___ |
| 7 | Полуавтомат B | «✏️ Изменить», «↩️ Отменить» | §3.2 B 4–5 | | | |
| 8 | Полуавтомат B | «Нет» → отказ гостю с альтернативой | | | хостес, статист B | |
| 9 | Полуавтомат B | отмена гостем → «Гость отменил бронь стола» | §3.2 B 7 | | хостес | |
| 10 | Оба | system-чат: трассы с `LUNCH_ORDER_PLACED`, `WAIT_HOSTESS_CONFIRMATION` | | | Дима/Рома | |
| 11 | Оба | admin-чат: алертов нет (Saby выкл.) или `SABY_WRITE_DISABLED` (Saby read-only) | | | управляющий | |
| 12 | Автомат | бронь + блюда в Presto, `EXTERNAL_ORDER_SENT` | §3.3 шаг 1 | | Дима, хостес | externalId ___ |
| 13 | Автомат | принятие в Presto → «Бронь подтверждена» ≤ 2 мин | §3.3 шаг 3 | | | |
| 14 | Автомат | ссылка «Оплатить» ≤ 1 мин после подтверждения | §3.3 шаг 4 | | | |
| 15 | Автомат | оплата → «Оплата получена» | §3.3 шаг 5 | | | |
| 16 | Автомат | закрытие заказа → звёзды → 👍/👎 → чаевые | §3.3 шаг 6 | | | |
| 17 | Автомат | «Нет» в Telegram снимает бронь в Presto | §3.3 шаг 7 | | | |
| 18 | Автомат | `saby_call_log` без `HTTP_ERROR`/`IO_ERROR`; `bill_operations` полные | | | Дима | |

## 6. Риски и откат

| Риск | Признак | Что делать |
| --- | --- | --- |
| Карточка не пришла в staff-чат | гостю «Готово…», в staff тихо | неверный `AERIS_ASTOR_BUTLER_STAFF_CHAT_ID` или бот не админ; проверить закреплённое превью; лог `Table reservation Telegram notification skipped: … chatConfigured=false` |
| Кнопка «Да» не работает | всплывашка без текста / ничего | нажимают не из hostess-чата (`isHostessChat`) — заявка создана с другим `hostessChatId`; подтвердить через `POST /api/bookings/table-reservations/{id}/confirm` изнутри VM |
| Гость не прошёл контакт | бот повторяет «нужен маленький формальный шаг» | у статиста скрыт номер/запрещён шаринг; поделиться контактом кнопкой, не текстом |
| Deep-link не продолжил ланч после контакта | после контакта — главное меню | Redis недоступен или прошли сутки (TTL черновика); повторить ссылку |
| Время «уже прошло» | бот отказывает во времени | часовой пояс заведения Екатеринбург; выбрать слот позже текущего времени Екб |
| Saby включён, заказ не принят | алерт в admin-чат | заявку **не трогать**; ланч вносится в Presto руками по карточке; код статуса — §3.3 |
| Saby «не ответил, создана ли бронь» | строка «Saby не ответил…» на карточке | найти в Presto бронь с «Astor Butler #N»; есть — `adopt`, нет — внести руками (`SabyReservationProvider.adopt/forget`, `SABY_PRESTO_BOOKING_API.md`) |
| Двойное подтверждение | хостес нажала «Да», Presto тоже принял | штатно: второе — no-op (статус уже `CONFIRMED`); в `VENUE_SYSTEM` «Да» отвечает «подтверждается в Presto» |
| Ссылка на оплату не пришла | нет кнопки «Оплатить» через 2 мин | `payment-link` пустой/не https (`NO_LINK`): эквайринг в Presto или незнакомая форма ответа (`SabyPaymentProvider.link`); повтор через 10 мин |
| Мусор в Presto после теста | тестовые брони/оплаты | снимаются в Presto руками; Butler сам ничего не удаляет |

**Откат — только флагами, без деплоя** (`.env.production` → `up -d aeris-astor-butler-bot`):

1. Оплата/счета мешают: `SABY_PAYMENT_ENABLED=false`, `ASTOR_BILLING_ENABLED=false` — опрос статусов и запись продолжают работать.
2. Запись в Presto мешает: `SABY_WRITE_ENABLED=false` — Butler только читает занятость и статусы; все заявки — хостес; на каждый ланч придёт алерт `SABY_WRITE_DISABLED` (ожидаемо).
3. Saby мешает целиком: `ASTOR_SABY_ENABLED=false` — ровно текущее состояние production; карточки получают хвост «В Saby внести вручную».
4. Сценарий ланча выключить нельзя без правки compose (`ASTOR_BUSINESS_LUNCH_ENABLED` не пробрасывается); при необходимости — попросить гостей
   не писать «ланч» или вернуть предыдущий образ (`CLOUDRU_DEPLOY_RUNBOOK.md` §7).
5. Заявки и счета в базе остаются при любом откате; `bill_operations`/`saby_call_log` только пополняются.

## 7. Открытые вопросы к Михаилу

1. Дата и время первого прогона (будний день, 12:00–16:00 Екб) и кто из команды ресторана «хостес» на этот день.
2. Делаем ли PR с подстановкой телефона гостя из `user_contacts` до 15.10 (без него автомат невозможен) и кто его делает — Дима или Рома.
3. Нужен ли `ASTOR_BOOKING_CONFIRMATION_SOURCE=VENUE_SYSTEM` на показе (требует строки в `docker-compose.prod.yml`), или оставляем `HOSTESS` + опрос.
4. Поднят ли на VM профиль `smart-solution` (тогда `SMART_SOLUTION_OPS_CHAT_ID` нужно заполнить) или нет (тогда переменная не нужна).
5. Ключи Saby от Тариэля: получены ли, и на какой точке (`SABY_POINT_ID`) можно создавать тестовые брони.
6. Сверка меню `aeris.json` с рестораном (цены, окно 12:00–16:00) → `confirmedByVenue: true`.

## 8. Сообщение в чат команды (черновик)

```text
Коллеги, готовим первый живой тест бизнес-ланча через AERIS-бота — <ДД.ММ>, <ЧЧ:ММ>–<ЧЧ:ММ>.
Нужно от каждого: управляющий, менеджер смены, хостес, два официанта, бармен, повар/кухня — ответьте здесь своим @username.
Создадим два чата с ботом: «AERIS Staff» (все) и «AERIS Admin» (управляющий + менеджер); бот будет админом групп.
Хостес в тесте жмёт «Да/Нет» на карточках заявок; повар и менеджер — сверят названия 17 блюд ланча с Presto.
Ещё нужны два статиста-«гостя» — не из команды, с Telegram на телефоне и российским номером; кто может — напишите.
Числовые id, номера телефонов и ключи в этот чат не пишем — только @username.
Сценарий и чеклист: docs/operations/BUSINESS_LUNCH_FIRST_TEST.md; с менеджером смены пройдём Опросник № 1. Вопросы — Михаилу.
```
