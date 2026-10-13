# Путь визита гостя AERIS — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Восемь осей диплома как один путь визита: лента визита, «как в прошлый раз», политика контакта, КБЖУ из Saby, петля возврата, зеркало в Saby CRM — всё за флагами `false`.

**Architecture:** Новый пакет `domain/journey` — лента визита (`guest_visits`, `guest_visit_events`) и `JourneyRecorder` как единственная точка записи; существующие сервисы брони/ланча/счёта вызывают рекордер. `ContactPolicy` решает, может ли бот написать первым. Зеркало в Saby CRM — фоновая задача, читающая неотправленные события ленты.

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate, Liquibase (yaml + sql), JUnit 5 + AssertJ + Mockito, `MockRestServiceServer` для Saby, Node-заглушка `scripts/saby_stub`.

**Spec:** `docs/superpowers/specs/2026-10-13-guest-journey-design.md`

## Global Constraints

- Все новые флаги `false` по умолчанию; при выключенном флаге поведение бота не меняется ни на байт (тесты проверяют).
- Флаги спецификации: `ASTOR_GUEST_JOURNEY_ENABLED`, `ASTOR_CONTACT_POLICY_ENABLED`, `ASTOR_SABY_CRM_MIRROR_ENABLED`, `ASTOR_RETURN_LOOP_ENABLED`. Дополнительно (поведение гостя, поэтому свой флаг): `ASTOR_REPEAT_LUNCH_ENABLED`, `ASTOR_LUNCH_NUTRITION_ENABLED`, `ASTOR_JOURNEY_WEEKLY_REPORT_ENABLED`.
- Лимит: не больше **6** сообщений «бот первым» за путь; ни одного на этапе «За столом»; сбой политики = молчание.
- «Вернулся»: новая бронь с тем же телефоном в течение **30** дней. Приглашение вернуться — через `astor.guest-journey.return.invite-after-days` (по умолчанию **7**).
- «Доброе дело» и «театр выбора» в этом плане — только события в ленте из существующих сценариев (`HiddenHeartScenario`, `SafePlayScenario`); новых сообщений гостю и денег нет.
- Тон сообщений: на «вы», эмодзи допустимы. Тексты — как в примере диалога 13.10 (Мария), меняются только Михаилом.
- Новые переменные окружения пробрасываются в `docker-compose.prod.yml` для `aeris-astor-butler-bot` с умолчанием `false`.
- Каждая миграция — отдельный `- include:` с ровно одним `file:` в `changelog-master.yaml`.
- Коммиты оканчиваются строкой `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Гость без телефона (не поделился контактом) — визит пишется по `chat_id`, «Вернулся» не ставится, «как в прошлый раз» не предлагается, ничего не падает. → тест в Task 1 и Task 3.
2. Меню сменилось, прошлых блюд в прайсе нет — «как в прошлый раз» предлагает только сохранившиеся блюда; если ни одного — кнопку не показывать. → тест в Task 3.
3. Повтор опроса Presto (статус «сел» приходит дважды) — одно событие, этап не скачет. → тест в Task 1.
4. Гость отписался, но сам пишет боту — ответы идут как обычно, молчат только проактивные. → тест в Task 4.
5. Saby CRM отвечает ошибкой/401 на середине очереди — порядок событий сохраняется, сделка не дублируется. → тест в Task 7.

---

### Task 1: Лента визита

**Files:**
- Create: `src/main/resources/db/changelog/2026-10-14-guest-journey.yaml`, `…/2026-10-14-guest-journey.sql`; Modify: `…/changelog-master.yaml`
- Create: `src/main/java/museon_online/astor_butler/domain/journey/{JourneyAxis,JourneyStage,VisitEventKind,GuestVisit,GuestVisitEvent,GuestVisitRepository,JourneyRecorder,GuestJourneyProperties}.java`
- Test: `src/test/java/museon_online/astor_butler/domain/journey/{JourneyRecorderTest,VisitEventKindTest}.java`

**Interfaces:**
- Produces:
  - `enum JourneyAxis { IDENTITY, PERSONALIZATION, GRATITUDE, INFO, SOCIAL, PLAY, TIME, SAFETY, NONE }`
  - `enum JourneyStage { REQUESTED, CONFIRMED, VISIT_DAY, SEATED, BILL, AFTER, RETURNED, CANCELLED, NO_SHOW, OPTED_OUT }` с `boolean isExit()` и `int order()`
  - `enum VisitEventKind` — у каждого `JourneyAxis axis()`, `JourneyStage stage()` (или `null`, если этап не меняет), `boolean oneShot()`. Значения: `REQUESTED(TIME, REQUESTED, true)`, `RECOGNIZED(IDENTITY, null, true)`, `REPEAT_OFFERED(PERSONALIZATION, null, true)`, `REPEAT_ACCEPTED(PERSONALIZATION, null, true)`, `CONFIRMED(TIME, CONFIRMED, true)`, `MORNING_CONFIRMED(TIME, VISIT_DAY, true)`, `MOVED(TIME, null, false)`, `SEATED(IDENTITY, SEATED, true)`, `DISH_QUESTION(INFO, null, false)`, `CHOICE_THEATRE(PLAY, null, false)`, `BILL_REQUESTED(GRATITUDE, BILL, true)`, `REVIEW(GRATITUDE, null, true)`, `TIP(GRATITUDE, null, true)`, `GOOD_DEED(SOCIAL, null, true)`, `AFTER_SENT(GRATITUDE, AFTER, true)`, `INVITED(PERSONALIZATION, null, true)`, `RETURNED(IDENTITY, RETURNED, true)`, `CANCELLED(SAFETY, CANCELLED, true)`, `NO_SHOW(NONE, NO_SHOW, true)`, `OPTED_OUT(SAFETY, OPTED_OUT, true)`.
  - `record GuestVisit(Long id, Long reservationId, Long chatId, String guestPhone, String venueCode, JourneyStage stage, String sabyLeadId, Long returnedVisitId, int botFirstMessages, Instant openedAt, Instant closedAt)`
  - `GuestVisitRepository`: `Optional<GuestVisit> findByReservation(long reservationId)`, `GuestVisit open(long reservationId, Long chatId, String guestPhone, String venueCode)`, `boolean insertEvent(long visitId, VisitEventKind kind, String source, String note)` (false, если oneShot уже есть), `void moveStage(long visitId, JourneyStage stage)`, `List<GuestVisitEvent> unmirrored(int limit)`, `void markMirrored(long eventId)`, `void setSabyLead(long visitId, String leadId)`, `int incrementBotFirst(long visitId)`, `Optional<GuestVisit> lastClosedByPhone(String phone, Instant since)`, `void linkReturn(long fromVisitId, long toVisitId)`
  - `JourneyRecorder.record(long reservationId, Long chatId, String guestPhone, String venueCode, VisitEventKind kind, String source, String note)` → `Optional<GuestVisit>`; при выключенном флаге — `Optional.empty()` без обращений к БД; исключения ловит и логирует.
  - `GuestJourneyProperties` (`@ConfigurationProperties("astor.guest-journey")`): `enabled=false`, `return.inviteAfterDays=7`, `return.windowDays=30`, `maxBotFirstMessages=6`.

- [ ] **Step 1: Write the failing tests** — `JourneyRecorderTest` (H2/`JdbcTemplate`, как в `GuestCardServiceTest`):
  `disabledRecorderTouchesNothing` (мок репозитория без вызовов), `firstEventOpensVisitAndMovesStage` (REQUESTED → stage REQUESTED), `oneShotEventIsWrittenOnce` (SEATED дважды → одно событие, stage SEATED), `stageNeverGoesBack` (CONFIRMED после SEATED → stage SEATED), `exitAlwaysWins` (CANCELLED после SEATED → CANCELLED), `visitWithoutPhoneIsKeptByChat` (guestPhone=null → визит открыт, `lastClosedByPhone` не находит), `recorderSwallowsRepositoryFailure` (репозиторий бросает → `Optional.empty()`, нет исключения). `VisitEventKindTest.everyKindHasAxis`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='JourneyRecorderTest,VisitEventKindTest'` — FAIL (нет классов).
- [ ] **Step 3: Migration** — таблицы `guest_visits(id bigserial pk, reservation_id bigint unique not null, chat_id bigint, guest_phone varchar(32), venue_code varchar(32) not null, stage varchar(24) not null, saby_lead_id varchar(64), returned_visit_id bigint, bot_first_messages int not null default 0, opened_at timestamptz not null default now(), closed_at timestamptz)`, индекс по `guest_phone`; `guest_visit_events(id bigserial pk, visit_id bigint not null references guest_visits(id), kind varchar(32) not null, axis varchar(16) not null, source varchar(8) not null, note varchar(500), occurred_at timestamptz not null default now(), mirrored_at timestamptz)`, частичный уникальный индекс `(visit_id, kind)` для oneShot делается в репозитории (`insert … where not exists`), индекс `(mirrored_at) where mirrored_at is null`.
- [ ] **Step 4: Implement** перечисленные классы. Переход этапа: новый этап применяется, если он выход, или если `new.order() > current.order()`; из выхода не выходим.
- [ ] **Step 5: Run** тесты Step 2 — PASS; `mvn -B -o -q test -Dtest='*Liquibase*,*Migration*'` — PASS.
- [ ] **Step 6: Commit** `feat(journey): visit ledger behind ASTOR_GUEST_JOURNEY_ENABLED`.

### Task 2: Запись событий из существующих сервисов

**Files:**
- Modify: `domain/booking/TableReservationService.java` (создание брони → `REQUESTED`), `domain/booking/TableReservationNotificationService.java` (`notifyGuestConfirmed` → `CONFIRMED`, `notifyGuestCancelled` → `CANCELLED`, `notifyGuestMovedByStaff` → `MOVED`), `domain/booking/BookingMorningCheck*.java` (ответ «буду» → `MORNING_CONFIRMED`), `TableReservationService.seatFromVenue` и кнопка хостес «Гость сел» → `SEATED`, `domain/billing/GuestBillRequestService.java` → `BILL_REQUESTED`, отзыв (`VisitReviewRepository` вставка) → `REVIEW`, `domain/tips` (личные чаевые) → `TIP`, `fsm/scenario/BusinessLunchScenario.java` вопрос о блюде → `DISH_QUESTION`; `fsm/scenario/HiddenHeartScenario.java` подтверждённый черновик → `GOOD_DEED`, `fsm/scenario/SafePlayScenario.java` старт сценария → `CHOICE_THEATRE`, `domain/booking/ExternalBookingSync.java` отмена из Presto → `CANCELLED` (source `PRESTO`); `docker-compose.prod.yml` (`ASTOR_GUEST_JOURNEY_ENABLED: ${ASTOR_GUEST_JOURNEY_ENABLED:-false}`)
- Test: `src/test/java/museon_online/astor_butler/domain/journey/JourneyHooksTest.java` + правки существующих тестов только в конструкторах (мок рекордера)

**Interfaces:**
- Consumes: `JourneyRecorder.record(...)` из Task 1.
- Produces: событие на каждый переход §4 спецификации; источник `BOT` / `PRESTO` / `STAFF` / `GUEST`.

- [ ] **Step 1: Write the failing test** `JourneyHooksTest`: для каждого хука — вызов сервиса с моком рекордера и `verify(recorder).record(eq(reservationId), …, eq(VisitEventKind.X), eq(source), any())`. Отдельно: `seatedFromPrestoTwiceRecordsOneCall` не нужен (идемпотентность в Task 1) — проверяем только `source = PRESTO` vs `STAFF`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest=JourneyHooksTest` — FAIL.
- [ ] **Step 3: Implement** — по одной строке `recorder.record(...)` в каждом месте; рекордер внедряется через `ObjectProvider<JourneyRecorder>`, чтобы старые конструкторы тестов не ломались.
- [ ] **Step 4: Run** `mvn -B -o -q test` (полный, ~50 с) — PASS, 0 падений.
- [ ] **Step 5: Commit** `feat(journey): record visit events from booking, lunch, bill and tips`.

### Task 3: «Как в прошлый раз»

**Files:**
- Create: `domain/journey/LastLunch.java`, `domain/journey/LastLunchService.java`, `domain/journey/RepeatLunchProperties.java` (`astor.guest-journey.repeat-lunch.enabled=false`)
- Modify: `fsm/scenario/FirstTouchScenario.java` (приветствие знакомого гостя), `fsm/scenario/BusinessLunchScenario.java` (кнопка/фраза «как в прошлый раз» и deep-link `/start lunch_aeris_again`), `fsm/scenario/BusinessLunchHandoff.java` (токен `again`), `docker-compose.prod.yml` (`ASTOR_REPEAT_LUNCH_ENABLED`)
- Test: `domain/journey/LastLunchServiceTest.java`, `fsm/scenario/RepeatLunchScenarioTest.java`

**Interfaces:**
- Consumes: `GuestBillRepository.findByChatId(Long, int)`, `findLines(Long)`; `BusinessLunchOffer.dish(String code)`; `JourneyRecorder` (`RECOGNIZED`, `REPEAT_OFFERED`, `REPEAT_ACCEPTED`).
- Produces: `record LastLunch(List<String> dishCodes, List<String> titles)`; `LastLunchService.find(Long chatId, BusinessLunchOffer offer)` → `Optional<LastLunch>` — последний счёт ланча гостя, строки `itemCode`, которые есть в текущем меню; пусто, если таких нет.

- [ ] **Step 1: Write the failing tests** — `LastLunchServiceTest`: `takesDishesOfTheLatestLunchBill` (два счёта → блюда последнего), `dropsDishesNoLongerOnTheMenu` (из 3 блюд в меню осталось 2 → 2), `emptyWhenNothingFromLastTimeIsOnTheMenu`, `emptyWithoutChat` (chatId null). `RepeatLunchScenarioTest`: `knownGuestGreetingOffersRepeatWhenFlagOn` — текст начинается «С возвращением» и содержит названия прошлых блюд, кнопки «🔁 Как в прошлый раз», «🍽 Выбрать заново», «📅 Стол»; `greetingUnchangedWhenFlagOff` (текст из #199); `repeatButtonPrefillsDraftAndShowsConfirmation` (state `BUSINESS_LUNCH_CONFIRMATION`, итог = сумма прошлых блюд по текущим ценам); `deepLinkAgainActsLikeTheButton`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='LastLunchServiceTest,RepeatLunchScenarioTest'` — FAIL.
- [ ] **Step 3: Implement**. Приветствие (флаг on, есть `LastLunch`): «С возвращением, {имя}! В прошлый раз у вас был {блюда через запятую}.» Нет имени — без обращения. Кнопка заполняет черновик ланча блюдами и переводит в подтверждение с прежним выбором даты/времени по умолчанию сценария.
- [ ] **Step 4: Run** тесты Step 2 и `FirstTouchScenarioTest,BusinessLunch*Test` — PASS.
- [ ] **Step 5: Commit** `feat(journey): «как в прошлый раз» for a known lunch guest`.

### Task 4: Политика контакта

**Files:**
- Create: `domain/journey/ContactPolicy.java`, `domain/journey/ContactMoment.java`, `domain/journey/ContactDecision.java`; migration `2026-10-14-guest-contact-prefs.{yaml,sql}` (`guest_contact_prefs(chat_id bigint pk, opted_out_at timestamptz)`)
- Modify: `TableReservationNotificationService.notifyGuestConfirmed`, `BookingMorningCheckNotifier`, `GuestBillNotifier` (просьба об отзыве), кнопка «Больше не писать» (обработчик в `TelegramRouter` callback → `OPTED_OUT`); `docker-compose.prod.yml` (`ASTOR_CONTACT_POLICY_ENABLED`)
- Test: `domain/journey/ContactPolicyTest.java`

**Interfaces:**
- Consumes: `GuestVisitRepository.findByReservation`, `incrementBotFirst`; `JourneyRecorder` (`OPTED_OUT`).
- Produces: `enum ContactMoment { REQUEST_RECEIVED, CONFIRMED, MORNING, BILL, AFTER, INVITE }`; `sealed interface ContactDecision` → `Allow`, `Silent(String reason)`; `ContactPolicy.allow(long reservationId, Long chatId, ContactMoment moment)` → `ContactDecision`. При выключенном флаге — всегда `Allow` (поведение как сейчас).

- [ ] **Step 1: Write the failing test** `ContactPolicyTest`: `allowsKeyMomentWithinLimit`, `silentAfterSixBotFirstMessages` (7-е → `Silent("limit")`), `silentWhileSeated` (stage SEATED, moment AFTER → `Silent("at table")`), `silentWhenOptedOut`, `silentWhenPolicyFails` (репозиторий бросает → `Silent("error")`), `flagOffAlwaysAllows`, `optOutDoesNotBlockReplies` (ответ на сообщение гостя не идёт через политику — проверка на уровне `TelegramRouter.send`: нет вызова `allow`).
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest=ContactPolicyTest` — FAIL.
- [ ] **Step 3: Implement**; каждое проактивное место: `if (policy.allow(...) instanceof Silent) return;` до отправки; `Allow` увеличивает `bot_first_messages`.
- [ ] **Step 4: Run** полный `mvn -B -o -q test` — PASS.
- [ ] **Step 5: Commit** `feat(journey): contact policy — key moments only, 6 per journey, silent at the table`.

### Task 5: КБЖУ и вес из Saby

**Files:**
- Modify: `integration/saby/SabyMenuCatalog.java` (`Dish` + `Nutrition`), `fsm/scenario/BusinessLunchScenario.java` (вопросы «калории», «кбжу», «сколько грамм», «вес»), `docker-compose.prod.yml` (`ASTOR_LUNCH_NUTRITION_ENABLED`)
- Test: `integration/saby/SabyMenuCatalogNutritionTest.java`, `fsm/scenario/LunchNutritionAnswerTest.java`

**Interfaces:**
- Produces: `record Nutrition(Integer grams, Integer kcal, BigDecimal protein, BigDecimal fat, BigDecimal carbs)`; `SabyMenuCatalog.nutrition(String sabyName, long priceListId)` → `Optional<Nutrition>`; значения из `attributes`: `outQuantity` (граммы), `calories`/`protein`/`fat`/`carbohydrate` на 100 г × `outQuantity`/100, округление до целых ккал и 0 знаков для БЖУ. Кэш на 1 час, как у прайс-листа.

- [ ] **Step 1: Write the failing tests** — каталог: ответ с `attributes {"calories":212.69,"outQuantity":"280","protein":11.62,"fat":5.41,"carbohydrate":29.38}` → `Nutrition(280, 596, 33, 15, 82)`; нет `attributes` → empty. Сценарий: «сколько калорий в пасте» → «Паста болоньезе: 280 г, около 596 ккал (Б 33 · Ж 15 · У 82).»; Saby недоступен → прежний ответ о составе; флаг off → вопрос о калориях обрабатывается как сейчас; событие `DISH_QUESTION` пишется.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='SabyMenuCatalogNutritionTest,LunchNutritionAnswerTest'` — FAIL.
- [ ] **Step 3: Implement** (поиск блюда по `Dish.sabyLookupName()`, прайс из `SABY_PRICE_LIST_ID`).
- [ ] **Step 4: Run** тесты Step 2 и `BusinessLunch*Test,SabyMenuCatalog*Test` — PASS.
- [ ] **Step 5: Commit** `feat(lunch): weight and nutrition from Saby tech cards`.

### Task 6: Петля возврата и отчёт по вторникам

**Files:**
- Create: `domain/journey/ReturnLoopScheduler.java` (`@ConditionalOnProperty("astor.guest-journey.return.enabled")`), `domain/journey/ReturnMatcher.java`, `domain/journey/JourneyWeeklyReport.java` (`astor.guest-journey.weekly-report.enabled=false`, отправка в `TELEGRAM_ADMIN_CHAT_ID` по вторникам 10:00 Asia/Yekaterinburg, Redis-маркер «отправлено за неделю» как у табло)
- Modify: `TableReservationService` (новая бронь → `ReturnMatcher.onNewReservation`), `docker-compose.prod.yml` (`ASTOR_RETURN_LOOP_ENABLED`, `ASTOR_JOURNEY_WEEKLY_REPORT_ENABLED`)
- Test: `domain/journey/ReturnLoopSchedulerTest.java`, `ReturnMatcherTest.java`, `JourneyWeeklyReportTest.java`

**Interfaces:**
- Consumes: `ContactPolicy.allow(..., AFTER | INVITE)`, `GuestVisitRepository.lastClosedByPhone`, `linkReturn`, `JourneyRecorder` (`AFTER_SENT`, `INVITED`, `RETURNED`), `LastLunchService` (для приглашения).
- Produces: `ReturnMatcher.onNewReservation(long reservationId, String guestPhone)`; тексты: следующий день — «{Официант} получил ваш отзыв и чаевые — спасибо, ему очень приятно. Хорошего дня!» (без официанта: «Спасибо за отзыв — передали команде AERIS. Хорошего дня!»), кнопка «Больше не писать»; приглашение — «{Имя}, на этой неделе в ланче {новое блюдо, если есть}. Собрать «как в прошлый раз» на {ближайший будний день}?» с кнопками «🔁 Да, на {день}» (deep-link `lunch_aeris_again`) и «Не сейчас».

- [ ] **Step 1: Write the failing tests** — `ReturnLoopSchedulerTest`: `afterMessageNextDayOnlyOnce`, `inviteAfterSevenDaysOnlyOnce`, `noMessagesWhenOptedOut`, `noInviteIfGuestAlreadyBookedAgain`, `openVisitPastItsDayWithoutSeatedBecomesNoShow` (визит прошлого дня без `SEATED` и без выхода → `NO_SHOW`, сообщений нет). `ReturnMatcherTest`: `samePhoneWithin30DaysMarksReturned` (stage RETURNED, `returnedVisitId` = новый визит), `after30DaysNotReturned`, `noPhoneNoMatch`. `JourneyWeeklyReportTest`: строка «Неделя: 34 визита · вернулись 9 (26%) · чаевые адресно 12 · отписок 1» из фикстуры; один раз за неделю.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='ReturnLoopSchedulerTest,ReturnMatcherTest,JourneyWeeklyReportTest'` — FAIL.
- [ ] **Step 3: Implement**; планировщик раз в 15 минут, только 10:00–21:00 по времени ресторана.
- [ ] **Step 4: Run** полный `mvn -B -o -q test` — PASS.
- [ ] **Step 5: Commit** `feat(journey): return loop and Tuesday report`.

### Task 7: Зеркало в Saby CRM

**Files:**
- Modify: `integration/saby/SabyApiClient.java` — `JsonNode rpc(String method, Map<String, ?> params)`: POST JSON-RPC 2.0 на `https://online.sbis.ru/service/` (хост из `SABY_AUTH_URL`), тот же токен и повтор при 401.
- Create: `integration/saby/crm/{SabyCrmProperties,SabyCrmClient,SabyCrmMirror}.java`; `SabyCrmProperties` (`astor.integrations.saby.crm`): `enabled=false`, `regulationId`, `phaseDirections` (`Map<JourneyStage, Long>`), `responsibleId` (опц.)
- Modify: `scripts/saby_stub/lib/world.mjs`, `server.mjs` — методы `CRMLead.insertRecord` (возвращает `@Документ`) и `CRMEvent.AddEvent`; `scripts/saby_stub/test/stub.test.mjs`; `docker-compose.prod.yml` (`ASTOR_SABY_CRM_MIRROR_ENABLED`, `SABY_CRM_JOURNEY_REGULATION_ID`, `SABY_CRM_PHASE_DIRECTIONS` в формате `REQUESTED=11,CONFIRMED=12,…`)
- Test: `integration/saby/crm/SabyCrmMirrorTest.java`

**Interfaces:**
- Consumes: `GuestVisitRepository.unmirrored(int)`, `markMirrored`, `setSabyLead`.
- Produces: `SabyCrmClient.insertLead(GuestVisit visit, List<String> dishTitles)` → `String leadId` (`CRMLead.insertRecord`: `Регламент`, `КонтактноеЛицо{ФИО, Телефон}`, `Примечание` = «Astor Butler · бронь #{reservationId}»); `SabyCrmClient.addEvent(String leadId, Long phaseDirectionId, String comment)` (`CRMEvent.AddEvent`, `EventType=3`, `KindOfContact=0`, `ContactType=2`); `SabyCrmMirror.flush()` — `@Scheduled(fixedDelay = 60_000)`, батч 50, по порядку `occurred_at`; первая отправка по визиту создаёт сделку; событие без перехода — `addEvent(lead, null, «{ось}: {kind}»)`.

- [ ] **Step 1: Write the failing test** `SabyCrmMirrorTest` (`MockRestServiceServer`): `firstEventCreatesLeadOnce`, `stageEventMovesByConfiguredPhase`, `eventsKeepOrder`, `failureMidQueueKeepsRemainingUnmirrored` (2-й вызов 500 → 1-е помечено, 2-е и 3-е нет; следующий `flush` продолжает со 2-го без нового `insertRecord`), `disabledMirrorSendsNothing`, `guestWithoutPhoneUsesChatPlaceholderNotFakePhone` (контакт без `Телефон`, только ФИО «Гость Telegram»).
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest=SabyCrmMirrorTest` и `node --test scripts/saby_stub/test/stub.test.mjs` — FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** тесты Step 2 и полный `mvn -B -o -q test` — PASS.
- [ ] **Step 5: Commit** `feat(saby): mirror the visit journey into a Saby CRM funnel`.

### Task 8: Документация включения

**Files:**
- Modify: `docs/operations/test-memos/README.md` (новые флаги в списке отката), `docs/integrations/SABY_PRESTO_BOOKING_API.md` (раздел «CRM: воронка визита», методы и где взять `PhaseDirectionId`)
- Create: `docs/operations/GUEST_JOURNEY_ROLLOUT.md` — порядок из §8 спецификации, команды включения по одному флагу, проверка после каждого (`/opt/astor-butler/e2e-say.py`, `saby_call_log`, строки `guest_visit_events`), откат.

- [ ] **Step 1:** Написать документы; в `GUEST_JOURNEY_ROLLOUT.md` для шага CRM — предусловие: воронка «Визит гостя AERIS» создана в Saby, одна тестовая сделка с «да» Михаила, идентификаторы переходов записаны в `.env.production`.
- [ ] **Step 2: Run** `mvn -B -o -q test` — PASS (документы не ломают сборку).
- [ ] **Step 3: Commit** `docs(journey): rollout and rollback`.
