# Болтающий батлер AERIS — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Путь визита на восьми осях: лента визита со счётчиком сообщений гостя, «как в прошлый раз», фильтр полезности, диалог батлера (знания, КБЖУ, просьбы за столом), петля возврата с отчётом, зеркало в Saby CRM — всё за флагами `false`.

**Architecture:** Пакет `domain/journey` — лента (`guest_visits`, `guest_visit_events`) и `JourneyRecorder` как единственная точка записи; существующие сервисы вызывают рекордер. `UsefulnessPolicy` пропускает «бот первым» только из белого списка, один раз за визит. `domain/journey/talk` отвечает из знаний и превращает просьбы в задачи персоналу через `StaffTaskService`. Зеркало Saby CRM — фоновая задача по неотправленным событиям.

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate, Liquibase (yaml + sql), JUnit 5 + AssertJ + Mockito, `MockRestServiceServer`, Node-заглушка `scripts/saby_stub`.

**Spec:** `docs/superpowers/specs/2026-10-13-guest-journey-design.md`

## Global Constraints

- Все новые флаги `false` по умолчанию; при выключенном флаге поведение бота не меняется (тесты проверяют).
- Флаги: `ASTOR_GUEST_JOURNEY_ENABLED`, `ASTOR_REPEAT_LUNCH_ENABLED`, `ASTOR_USEFULNESS_POLICY_ENABLED`, `ASTOR_BUTLER_TALK_ENABLED`, `ASTOR_RETURN_LOOP_ENABLED`, `ASTOR_JOURNEY_WEEKLY_REPORT_ENABLED`, `ASTOR_SABY_CRM_MIRROR_ENABLED`; каждый пробрасывается в `docker-compose.prod.yml` для `aeris-astor-butler-bot` с умолчанием `false`.
- Лимита на количество сообщений нет. «Бот первым» — только виды из белого списка, каждый один раз за визит; ответы гостю не ограничены и не задерживаются; сбой фильтра = молчание.
- Метрика конкурса: сообщения **гостя** за визит (реплики и нажатия), цель ≈ **40**; сообщения бота не считаются.
- Окно визита для счётчика: от заявки до конца следующего дня после визита (время ресторана, `Asia/Yekaterinburg`).
- «Вернулся»: новая бронь с тем же телефоном в **30** дней; приглашение — через `astor.guest-journey.return.invite-after-days` (по умолчанию **7**).
- Время первого блюда в подсказке: «обычно через 7–10 минут» (интервью стажёра 12.10), настройка `astor.guest-journey.talk.first-dish-minutes=7-10`.
- Факты о ресторане — только из `src/main/resources/venue/aeris.json`; нет факта — «уточню у команды» + вопрос в чат персонала. Факты не выдумывать.
- Тон: на «вы», эмодзи допустимы; тексты меняет только Михаил.
- Каждая миграция — отдельный `- include:` с ровно одним `file:` в `changelog-master.yaml`.
- Коммиты оканчиваются строкой `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Гость без телефона — визит по `chat_id`, «Вернулся» и «как в прошлый раз» не срабатывают, ничего не падает. → Task 1, Task 3.
2. Меню сменилось — «как в прошлый раз» только с сохранившимися блюдами; ни одного — не предлагать. → Task 3.
3. Просьба за столом, а официант на стол не назначен — задача в чат персонала без имени, гостю «Передал команде». → Task 5.
4. Отписанный гость пишет сам — ответы идут, счётчик растёт, «бот первым» молчит. → Task 4.
5. Saby CRM падает посреди очереди — порядок сохраняется, сделка не дублируется. → Task 7.

---

### Task 1: Лента визита и счётчик сообщений гостя

**Files:**
- Create: `src/main/resources/db/changelog/2026-10-14-guest-journey.{yaml,sql}`; Modify: `changelog-master.yaml`
- Create: `src/main/java/museon_online/astor_butler/domain/journey/{JourneyAxis,JourneyStage,VisitEventKind,GuestVisit,GuestVisitEvent,GuestVisitRepository,JourneyRecorder,GuestJourneyProperties}.java`
- Test: `src/test/java/museon_online/astor_butler/domain/journey/{JourneyRecorderTest,VisitEventKindTest}.java`

**Interfaces:**
- Produces:
  - `enum JourneyAxis { IDENTITY, PERSONALIZATION, GRATITUDE, INFO, SOCIAL, PLAY, TIME, SAFETY, NONE }`
  - `enum JourneyStage { REQUESTED, CONFIRMED, VISIT_DAY, SEATED, BILL, AFTER, RETURNED, CANCELLED, NO_SHOW, OPTED_OUT }` — `boolean isExit()`, `int order()`
  - `enum VisitEventKind` с `axis()`, `stage()` (или `null`), `oneShot()`: `REQUESTED(TIME, REQUESTED, true)`, `RECOGNIZED(IDENTITY, null, true)`, `REPEAT_OFFERED(PERSONALIZATION, null, true)`, `REPEAT_ACCEPTED(PERSONALIZATION, null, true)`, `CONFIRMED(TIME, CONFIRMED, true)`, `MORNING_CONFIRMED(TIME, VISIT_DAY, true)`, `MOVED(TIME, null, false)`, `SEATED(IDENTITY, SEATED, true)`, `DISH_QUESTION(INFO, null, false)`, `VENUE_QUESTION(INFO, null, false)`, `TABLE_REQUEST(IDENTITY, null, false)`, `CHOICE_THEATRE(PLAY, null, false)`, `BILL_REQUESTED(GRATITUDE, BILL, true)`, `REVIEW(GRATITUDE, null, true)`, `TIP(GRATITUDE, null, true)`, `GOOD_DEED(SOCIAL, null, true)`, `AFTER_SENT(GRATITUDE, AFTER, true)`, `INVITED(PERSONALIZATION, null, true)`, `RETURNED(IDENTITY, RETURNED, true)`, `CANCELLED(SAFETY, CANCELLED, true)`, `NO_SHOW(NONE, NO_SHOW, true)`, `OPTED_OUT(SAFETY, OPTED_OUT, true)`
  - `record GuestVisit(Long id, Long reservationId, Long chatId, String guestPhone, String venueCode, JourneyStage stage, String sabyLeadId, Long returnedVisitId, int guestMessages, Instant visitDay, Instant openedAt, Instant closedAt)`
  - `GuestVisitRepository`: `Optional<GuestVisit> findByReservation(long)`, `Optional<GuestVisit> activeByChat(long chatId, Instant now)`, `GuestVisit open(long reservationId, Long chatId, String guestPhone, String venueCode, Instant visitDay)`, `boolean insertEvent(long visitId, VisitEventKind kind, String source, String note)` (false для повторного oneShot), `void moveStage(long visitId, JourneyStage)`, `int incrementGuestMessages(long visitId)`, `List<GuestVisitEvent> unmirrored(int limit)`, `void markMirrored(long eventId)`, `void setSabyLead(long visitId, String leadId)`, `Optional<GuestVisit> lastClosedByPhone(String phone, Instant since)`, `void linkReturn(long fromVisitId, long toVisitId)`, `boolean wasSent(long visitId, String proactiveKind)`, `void markSent(long visitId, String proactiveKind)`
  - `JourneyRecorder.record(long reservationId, Long chatId, String guestPhone, String venueCode, Instant visitDay, VisitEventKind kind, String source, String note)` → `Optional<GuestVisit>`; `JourneyRecorder.countGuestMessage(long chatId)` → `OptionalInt`. Флаг off — без обращений к БД; исключения ловятся и логируются.
  - `GuestJourneyProperties` (`astor.guest-journey`): `enabled=false`, `return.inviteAfterDays=7`, `return.windowDays=30`, `zoneId=Asia/Yekaterinburg`.

- [ ] **Step 1: Write the failing tests** — `JourneyRecorderTest`: `disabledRecorderTouchesNothing`, `firstEventOpensVisitAndMovesStage`, `oneShotEventIsWrittenOnce`, `stageNeverGoesBack`, `exitAlwaysWins`, `visitWithoutPhoneIsKeptByChat`, `recorderSwallowsRepositoryFailure`, `guestMessageCountsOnlyInsideVisitWindow` (за день до заявки и через 2 дня после визита — не считаются; внутри окна +1), `guestMessageWithoutVisitIsIgnored`. `VisitEventKindTest.everyKindHasAxis`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='JourneyRecorderTest,VisitEventKindTest'` — FAIL.
- [ ] **Step 3: Migration** — `guest_visits(id bigserial pk, reservation_id bigint unique not null, chat_id bigint, guest_phone varchar(32), venue_code varchar(32) not null, stage varchar(24) not null, saby_lead_id varchar(64), returned_visit_id bigint, guest_messages int not null default 0, visit_day timestamptz, opened_at timestamptz not null default now(), closed_at timestamptz)` с индексами по `guest_phone`, `chat_id`; `guest_visit_events(id bigserial pk, visit_id bigint not null references guest_visits(id), kind varchar(32) not null, axis varchar(16) not null, source varchar(8) not null, note varchar(500), occurred_at timestamptz not null default now(), mirrored_at timestamptz)`, индекс `(mirrored_at) where mirrored_at is null`; `guest_visit_sent(visit_id bigint not null, kind varchar(32) not null, sent_at timestamptz not null default now(), primary key(visit_id, kind))`; `guest_contact_prefs(chat_id bigint primary key, opted_out_at timestamptz)`.
- [ ] **Step 4: Implement** классы. Этап меняется, если новый — выход или `order()` больше текущего; из выхода не выходим.
- [ ] **Step 5: Run** тесты Step 2 — PASS.
- [ ] **Step 6: Commit** `feat(journey): visit ledger and guest message counter behind ASTOR_GUEST_JOURNEY_ENABLED`.

### Task 2: События из существующих сервисов

**Files:**
- Modify: `domain/booking/TableReservationService.java` (создание → `REQUESTED`; `seatFromVenue` → `SEATED`/`PRESTO`), `domain/booking/TableReservationNotificationService.java` (`notifyGuestConfirmed` → `CONFIRMED`, `notifyGuestCancelled` → `CANCELLED`, `notifyGuestMovedByStaff` → `MOVED`), кнопка хостес «Гость сел» → `SEATED`/`STAFF`, `domain/booking/BookingMorningCheck*.java` («буду» → `MORNING_CONFIRMED`), `domain/booking/ExternalBookingSync.java` (отмена из Presto → `CANCELLED`/`PRESTO`), `domain/billing/GuestBillRequestService.java` → `BILL_REQUESTED`, вставка отзыва (`VisitReviewRepository`) → `REVIEW`, личные чаевые (`domain/tips`) → `TIP`, `fsm/scenario/HiddenHeartScenario.java` подтверждение → `GOOD_DEED`, `fsm/scenario/SafePlayScenario.java` старт → `CHOICE_THEATRE`, `service/message/MessageGatewayService.java` (каждое входящее от гостя в личном чате, включая нажатие кнопки → `countGuestMessage(chatId)`); `docker-compose.prod.yml` (`ASTOR_GUEST_JOURNEY_ENABLED`)
- Test: `src/test/java/museon_online/astor_butler/domain/journey/JourneyHooksTest.java`

**Interfaces:**
- Consumes: `JourneyRecorder.record(...)`, `countGuestMessage(long)` из Task 1 (через `ObjectProvider<JourneyRecorder>`, чтобы старые конструкторы тестов не ломались).

- [ ] **Step 1: Write the failing test** `JourneyHooksTest`: по тесту на хук — `verify(recorder).record(eq(reservationId), …, eq(VisitEventKind.X), eq(source), any())`; `staffChatMessagesAreNotCounted`, `buttonPressCountsAsGuestMessage`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest=JourneyHooksTest` — FAIL.
- [ ] **Step 3: Implement** — по одной строке вызова в каждом месте.
- [ ] **Step 4: Run** полный `mvn -B -o -q test` — PASS.
- [ ] **Step 5: Commit** `feat(journey): record visit events and guest messages from existing flows`.

### Task 3: «Как в прошлый раз»

**Files:**
- Create: `domain/journey/{LastLunch,LastLunchService,RepeatLunchProperties}.java` (`astor.guest-journey.repeat-lunch.enabled=false`)
- Modify: `fsm/scenario/FirstTouchScenario.java` (приветствие знакомого гостя), `fsm/scenario/BusinessLunchScenario.java` (фраза «как в прошлый раз» на любом шаге, в т.ч. с правкой: «как в прошлый раз, но без сыра»), `fsm/scenario/BusinessLunchHandoff.java` (токен `again` в `/start lunch_aeris_again`), `docker-compose.prod.yml` (`ASTOR_REPEAT_LUNCH_ENABLED`)
- Test: `domain/journey/LastLunchServiceTest.java`, `fsm/scenario/RepeatLunchScenarioTest.java`

**Interfaces:**
- Consumes: `GuestBillRepository.findByChatId(Long, int)`, `findLines(Long)` (`GuestBillLine.itemCode`); `BusinessLunchOffer.dish(String)`; `JourneyRecorder` (`RECOGNIZED`, `REPEAT_OFFERED`, `REPEAT_ACCEPTED`).
- Produces: `record LastLunch(List<String> dishCodes, List<String> titles)`; `LastLunchService.find(Long chatId, BusinessLunchOffer offer)` → `Optional<LastLunch>`.

- [ ] **Step 1: Write the failing tests** — `LastLunchServiceTest`: `takesDishesOfTheLatestLunchBill`, `dropsDishesNoLongerOnTheMenu`, `emptyWhenNothingFromLastTimeIsOnTheMenu`, `emptyWithoutChat`. `RepeatLunchScenarioTest`: `knownGuestGreetingNamesLastDishesWhenFlagOn` (текст «С возвращением, {имя}! В прошлый раз у вас был {блюда}. Повторить или собрать заново?»; кнопок, кроме главного меню, нет), `greetingUnchangedWhenFlagOff`, `repeatPhrasePrefillsDraftAndShowsConfirmation` («давай как в прошлый раз» → `BUSINESS_LUNCH_CONFIRMATION`, сумма по текущим ценам), `repeatWithChangeKeepsTheWish` («как в прошлый раз, но без сыра» → пожелание «без сыра»), `deepLinkAgainActsLikeThePhrase`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='LastLunchServiceTest,RepeatLunchScenarioTest'` — FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** тесты Step 2 и `FirstTouchScenarioTest,BusinessLunch*Test` — PASS.
- [ ] **Step 5: Commit** `feat(journey): «как в прошлый раз» for a known lunch guest`.

### Task 4: Фильтр полезности

**Files:**
- Create: `domain/journey/{UsefulnessPolicy,ProactiveMessage,ProactiveDecision}.java`
- Modify: проактивные отправки — `TableReservationNotificationService.notifyGuestConfirmed`, `BookingMorningCheckNotifier`, `GuestBillNotifier`; фраза гостя «не пишите мне» / «больше не писать» (распознаётся в `MessageGatewayService` до сценариев) → `guest_contact_prefs.opted_out_at`, событие `OPTED_OUT`, ответ «Хорошо, сам писать не буду. Если что-то понадобится — просто напишите»; `docker-compose.prod.yml` (`ASTOR_USEFULNESS_POLICY_ENABLED`)
- Test: `domain/journey/UsefulnessPolicyTest.java`

**Interfaces:**
- Consumes: `GuestVisitRepository.findByReservation`, `wasSent`, `markSent`; `guest_contact_prefs`.
- Produces: `enum ProactiveMessage { REQUEST_RECEIVED, CONFIRMED, MORNING, SEATED_HINT, STAFF_COMING, BILL_READY, AFTER, INVITE }`; `sealed interface ProactiveDecision` → `Allow`, `Silent(String reason)`; `UsefulnessPolicy.allow(long reservationId, Long chatId, ProactiveMessage kind)`. Флаг off → всегда `Allow`. `STAFF_COMING` — по одному на просьбу, остальные — один раз за визит.

- [ ] **Step 1: Write the failing test** `UsefulnessPolicyTest`: `allowsWhitelistedKindOnce` (второй `MORNING` → `Silent("already sent")`), `staffComingAllowedPerRequest`, `silentWhenOptedOut`, `silentWhenPolicyFails` (→ `Silent("error")`), `flagOffAlwaysAllows`, `optedOutGuestStillGetsReplies`, `optOutPhraseStopsBotFirstMessages`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest=UsefulnessPolicyTest` — FAIL.
- [ ] **Step 3: Implement**; в каждой проактивной отправке `if (policy.allow(...) instanceof Silent) return;`, `Allow` → `markSent`.
- [ ] **Step 4: Run** полный `mvn -B -o -q test` — PASS.
- [ ] **Step 5: Commit** `feat(journey): usefulness policy for bot-first messages`.

### Task 5: Диалог батлера — знания, КБЖУ, просьбы, подсказки

**Files:**
- Create: `src/main/resources/venue/aeris.json` (поля `parking`, `kids`, `wifi`, `winePairing`, `events`, `dishStories` со значениями `null`; факты вносит Михаил), `domain/journey/talk/{VenueFacts,VenueFactsCatalog,ButlerTalk,TableRequest,TableRequestRouter,ButlerTalkProperties}.java` (`astor.guest-journey.talk.enabled=false`)
- Modify: `integration/saby/SabyMenuCatalog.java` (`Nutrition`), `fsm/scenario/BusinessLunchScenario.java` и `fsm/scenario/ScenarioRouter.java` (вызов `ButlerTalk.answer(...)` перед fallback), `TableReservationService.seatFromVenue` / «Гость сел» → подсказка `SEATED_HINT`; `docker-compose.prod.yml` (`ASTOR_BUTLER_TALK_ENABLED`)
- Test: `integration/saby/SabyMenuCatalogNutritionTest.java`, `domain/journey/talk/{ButlerTalkTest,TableRequestRouterTest}.java`

**Interfaces:**
- Consumes: `UsefulnessPolicy.allow(..., SEATED_HINT | STAFF_COMING)`; `StaffTaskService.create(StaffScope, String eventId, StaffTaskService.Draft)` (`api/glasses/tasks`); `JourneyRecorder` (`DISH_QUESTION`, `VENUE_QUESTION`, `TABLE_REQUEST`); `Dish.sabyLookupName()`; назначения официантов на столы смены.
- Produces:
  - `record Nutrition(Integer grams, Integer kcal, Integer protein, Integer fat, Integer carbs)`; `SabyMenuCatalog.nutrition(String sabyName, long priceListId)` → `Optional<Nutrition>` из `attributes` (`outQuantity`; `calories`/`protein`/`fat`/`carbohydrate` на 100 г × граммы/100, округление до целых), кэш 1 час.
  - `enum TableRequest { BREAD, WATER, WAITER, SPEED_UP, CUTLERY, BILL }` с распознаванием фраз («ещё хлеба», «можно воды», «позовите официанта», «ускорьте горячее», «приборы», «счёт»); `TableRequestRouter.route(long reservationId, TableRequest, String guestText)` → `String` («Передал {официант}» / «Передал команде»).
  - `ButlerTalk.answer(IncomingMessage, Optional<GuestVisit>)` → `Optional<String>`: просьба → роутер; вопрос о блюде со словами «калории/кбжу/грамм/вес» → «{Блюдо}: {г} г, около {ккал} ккал (Б {б} · Ж {ж} · У {у}).»; вопрос о ресторане → факт или «Уточню у команды и вернусь с ответом» + сообщение в чат персонала; иначе `empty`.
  - Подсказка `SEATED_HINT`: «Приятного обеда! Первое блюдо обычно подают через 7–10 минут. Ваш официант — {имя}, если что-то понадобится, просто напишите мне.» (без имени — без фразы про официанта).

- [ ] **Step 1: Write the failing tests** — `SabyMenuCatalogNutritionTest`: `attributes {"calories":212.69,"outQuantity":"280","protein":11.62,"fat":5.41,"carbohydrate":29.38}` → `Nutrition(280, 596, 33, 15, 82)`; без `attributes` → empty. `ButlerTalkTest`: `caloriesQuestionAnsweredFromSaby`, `sabyDownFallsBackToComposition`, `unknownVenueFactAsksTeam`, `knownVenueFactAnswered`, `flagOffAnswersNothing`, `eachAnswerRecordsEvent`, `seatedHintSentOnceWithWaiterName`. `TableRequestRouterTest`: `breadBecomesStaffTaskForAssignedWaiter` (ответ «Передал Кириллу»), `noWaiterAssignedGoesToStaffChat` (ответ «Передал команде»), `staffComingHintOncePerRequest`, `unrecognisedPhraseIsNotARequest`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='SabyMenuCatalogNutritionTest,ButlerTalkTest,TableRequestRouterTest'` — FAIL.
- [ ] **Step 3: Implement**; `ButlerTalk` не перехватывает шаги выбора блюд и подтверждения, кроме просьб и вопросов о блюдах.
- [ ] **Step 4: Run** тесты Step 2 и полный `mvn -B -o -q test` — PASS.
- [ ] **Step 5: Commit** `feat(journey): butler talk — venue facts, nutrition from Saby, table requests`.

### Task 6: Петля возврата и отчёт по вторникам

**Files:**
- Create: `domain/journey/{ReturnLoopScheduler,ReturnMatcher,JourneyWeeklyReport}.java` (`astor.guest-journey.return.enabled=false`, `astor.guest-journey.weekly-report.enabled=false`; отчёт в `TELEGRAM_ADMIN_CHAT_ID` по вторникам 10:00 времени ресторана, Redis-маркер недели как у табло)
- Modify: `TableReservationService` (новая бронь → `ReturnMatcher.onNewReservation`), `docker-compose.prod.yml` (`ASTOR_RETURN_LOOP_ENABLED`, `ASTOR_JOURNEY_WEEKLY_REPORT_ENABLED`)
- Test: `domain/journey/{ReturnLoopSchedulerTest,ReturnMatcherTest,JourneyWeeklyReportTest}.java`

**Interfaces:**
- Consumes: `UsefulnessPolicy.allow(..., AFTER | INVITE)`, `GuestVisitRepository.lastClosedByPhone`, `linkReturn`, `JourneyRecorder` (`AFTER_SENT`, `INVITED`, `RETURNED`, `NO_SHOW`), `LastLunchService`.
- Produces: `ReturnMatcher.onNewReservation(long reservationId, String guestPhone)`; тексты: следующий день — «{Официант} получил ваш отзыв и чаевые — спасибо, ему очень приятно. Хорошего дня!» (без официанта: «Спасибо за отзыв — передали команде AERIS. Хорошего дня!»); приглашение — «{Имя}, на этой неделе в ланче {новое блюдо, если есть}. Собрать «как в прошлый раз» на {ближайший будний день}?»; отчёт — «Неделя: {N} визитов · вернулись {R} ({R%}) · сообщений гостя в среднем {avg} · визитов ≥ 40: {k} · частые вопросы: {top3} · просьб за столом: {q} · отписок: {o}».

- [ ] **Step 1: Write the failing tests** — `ReturnLoopSchedulerTest`: `afterMessageNextDayOnlyOnce`, `inviteAfterSevenDaysOnlyOnce`, `noMessagesWhenOptedOut`, `noInviteIfGuestAlreadyBookedAgain`, `openVisitPastItsDayWithoutSeatedBecomesNoShow`. `ReturnMatcherTest`: `samePhoneWithin30DaysMarksReturned`, `after30DaysNotReturned`, `noPhoneNoMatch`. `JourneyWeeklyReportTest`: строка из фикстуры (34 визита, 9 вернулись → «26%», в среднем 41 сообщение, 18 визитов ≥ 40) и отправка один раз за неделю.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='ReturnLoopSchedulerTest,ReturnMatcherTest,JourneyWeeklyReportTest'` — FAIL.
- [ ] **Step 3: Implement**; планировщик раз в 15 минут, сообщения гостю только 10:00–21:00 времени ресторана.
- [ ] **Step 4: Run** полный `mvn -B -o -q test` — PASS.
- [ ] **Step 5: Commit** `feat(journey): return loop and Tuesday report with guest message metrics`.

### Task 7: Зеркало в Saby CRM

**Files:**
- Modify: `integration/saby/SabyApiClient.java` — `JsonNode rpc(String method, Map<String, ?> params)`: POST JSON-RPC 2.0 на хост из `SABY_AUTH_URL` + `/service/`, тот же токен, повтор при 401.
- Create: `integration/saby/crm/{SabyCrmProperties,SabyCrmClient,SabyCrmMirror}.java`; `SabyCrmProperties` (`astor.integrations.saby.crm`): `enabled=false`, `regulationId`, `phaseDirections` (`Map<JourneyStage, Long>`, env `SABY_CRM_PHASE_DIRECTIONS="REQUESTED=11,CONFIRMED=12,…"`), `responsibleId`
- Modify: `scripts/saby_stub/lib/world.mjs`, `server.mjs`, `test/stub.test.mjs` — `CRMLead.insertRecord`, `CRMEvent.AddEvent`; `docker-compose.prod.yml` (`ASTOR_SABY_CRM_MIRROR_ENABLED`, `SABY_CRM_JOURNEY_REGULATION_ID`, `SABY_CRM_PHASE_DIRECTIONS`)
- Test: `integration/saby/crm/SabyCrmMirrorTest.java`

**Interfaces:**
- Consumes: `GuestVisitRepository.unmirrored`, `markMirrored`, `setSabyLead`.
- Produces: `SabyCrmClient.insertLead(GuestVisit, List<String> dishTitles)` → `String leadId` (`Регламент`, `КонтактноеЛицо{ФИО, Телефон}`, `Примечание` «Astor Butler · бронь #{reservationId}»); `SabyCrmClient.addEvent(String leadId, Long phaseDirectionId, String comment)` (`EventType=3`, `KindOfContact=0`, `ContactType=2`); `SabyCrmMirror.flush()` — `@Scheduled(fixedDelay = 60_000)`, батч 50 по `occurred_at`.

- [ ] **Step 1: Write the failing test** `SabyCrmMirrorTest`: `firstEventCreatesLeadOnce`, `stageEventMovesByConfiguredPhase`, `eventsKeepOrder`, `failureMidQueueKeepsRemainingUnmirrored`, `disabledMirrorSendsNothing`, `guestWithoutPhoneHasNoFakePhone`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest=SabyCrmMirrorTest` и `node --test scripts/saby_stub/test/stub.test.mjs` — FAIL.
- [ ] **Step 3: Implement**.
- [ ] **Step 4: Run** тесты Step 2 и полный `mvn -B -o -q test` — PASS.
- [ ] **Step 5: Commit** `feat(saby): mirror the visit journey into a Saby CRM funnel`.

### Task 8: Включение, откат и прогон на 40 сообщений

**Files:**
- Create: `docs/operations/GUEST_JOURNEY_ROLLOUT.md`, `scripts/e2e/butler-40.txt`; Modify: `docs/operations/test-memos/README.md` (новые флаги в откате), `docs/integrations/SABY_PRESTO_BOOKING_API.md` (раздел «CRM: воронка визита»)

- [ ] **Step 1:** Документ включения по §8 спецификации: команда на каждый флаг, проверка после (`guest_visit_events`, `guest_visits.guest_messages`, `saby_call_log`), откат; предусловия для CRM (воронка в Saby, тестовая сделка с «да» Михаила) и для диалога (факты в `venue/aeris.json` от Тариэля).
- [ ] **Step 2:** `scripts/e2e/butler-40.txt` — 40 реплик тестового гостя для `/opt/astor-butler/e2e-say.py`: заявка словами, вопросы о блюдах и вине, просьбы за столом, счёт, отзыв словами.
- [ ] **Step 3: Run** `mvn -B -o -q test` — PASS.
- [ ] **Step 4: Commit** `docs(journey): rollout, rollback and the 40-message guest run`.
