# Astor Negotiation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Консьерж (агент гостя) и Батлер (агент ресторана) ведут переговоры в одном чате гостя через каталог инструментов на GigaChat; каждый шаг трёх сторон пишется в ленту визита, визиты получают саммари, закрытое табло на astor-ai.ru показывает канбан, переговоры, метрики и архитектуру.

**Architecture:** Батлер (Java) получает ленту `visit_turns`, API табло, саммари, function calling в шлюзе моделей, Capability API v1, агента ресторана и API переговоров, вебхук в Консьерж. Консьерж (Node) получает исправленную ссылку на бота, запись в ленту, function calling, агента гостя и приём вебхука. Агентный путь — только для списка тестовых чатов; остальные гости идут прежним путём.

**Tech Stack:** Java 21, Spring Boot 4, JdbcTemplate, Liquibase (yaml + sql), H2 (MODE=PostgreSQL) в тестах, Mockito, MockRestServiceServer; Node 22 + TypeScript (`node --test`), grammY; GigaChat REST (`/chat/completions` с `functions`); статический сайт `frontend/astor-butler`.

**Spec:** `docs/superpowers/specs/2026-10-14-astor-negotiation-design.md` (PR #203).

**Repos and branches:**
- Butler: `astor-hospitality/Astor_Butler_MVP`, ветка `feat/negotiation` **от `feat/guest-journey` (PR #202)** — лента визита опирается на `guest_visits`, `JourneyRecorder`, `TableRequestRouter`, `VenueFactsCatalog`, `SabyNutritionService`, `GuestVisitRepository` (всё из #202).
- Concierge: `astor-hospitality/Astor_Concierge`, ветка `feat/negotiation-agent` от `main`.

## Global Constraints

- Все новые флаги по умолчанию `false`: `astor.ledger.enabled` (`ASTOR_LEDGER_ENABLED`), `astor.board.enabled` (`ASTOR_BOARD_ENABLED`), `astor.summary.enabled` (`ASTOR_VISIT_SUMMARY_ENABLED`), `astor.capabilities.enabled` (`ASTOR_CAPABILITIES_ENABLED`), `astor.agent.enabled` (`ASTOR_AGENT_ENABLED`), `astor.concierge-webhook.enabled` (`ASTOR_CONCIERGE_WEBHOOK_ENABLED`); у Консьержа `AGENT_ENABLED`, `LEDGER_ENABLED`.
- Агентный путь только для chat id из списка: Батлер `ASTOR_AGENT_ALLOWLIST`, Консьерж `AGENT_ALLOWLIST` (через запятую). Пустой список — агент не отвечает никому.
- Инструмент возвращает факт, а не текст для гостя. Каждый вызов инструмента пишется в ленту (TOOL_CALL + TOOL_RESULT). Обязательства идемпотентны по заголовку `Idempotency-Key`.
- Переход «ждёт хостес → подтверждена» делает только хостес или Presto; ни один инструмент не подтверждает бронь.
- Лента пишется в своей транзакции (`REQUIRES_NEW`) и никогда не ломает бронь, ответ гостю или сценарий.
- Телефоны в ленте и на табло маскируются: `+7 912 ***-**-60` (видны код страны, первые 3 и последние 2 цифры).
- Реплики Консьержа и гостя Консьержа пишутся в ленту с текстом только при согласии гостя; без согласия — `text = null`, `payload.hidden = true`.
- Цикл агента: не больше 5 вызовов инструментов за реплику гостя; таймаут модели 20 с; при ошибке модели — запасной путь (сценарии FSM у Батлера, прежний поток у Консьержа).
- Голос Батлера в чате Консьержа подписывается префиксом `AERIS · Батлер: `.
- Табло закрыто: каждый запрос `/api/board/**` требует `X-Astor-Board-Token` = `ASTOR_BOARD_TOKEN` (не короче 16 символов; иначе 401 на всё).
- Внутренние API (`/api/ledger/**`, `/api/capabilities/**`, `/api/negotiation/**`) защищены `X-Astor-Internal-Token` (добавить в `InternalApiGuardFilter.INTERNAL_PATHS`).
- Вебхук Батлер → Консьерж подписывается HMAC-SHA256 тела, заголовок `X-Astor-Signature: sha256=<hex>`, ключ `ASTOR_WEBHOOK_SECRET` (одинаковый в обоих сервисах).
- Модель агентов — `GIGACHAT_MODEL` из окружения (сейчас GigaChat-2-Max); новые модели не вводить.

## Review Focus

1. GigaChat недоступен (402, таймаут, нет ключа) посреди переговоров — гость получает понятный ответ, разговор уходит на прежний путь, полусозданных броней нет. → Task 9, Task 12.
2. Модель зовёт несуществующий инструмент или передаёт неверные аргументы (дата в прошлом, 0 гостей) — инструмент отвечает фактом-ошибкой, исключения нет, агент переспрашивает гостя. → Task 7, Task 9.
3. Повтор обязательства с тем же `Idempotency-Key` после таймаута — тот же результат, второй брони или заказа нет. → Task 8.
4. Бронь из канала CONCIERGE подтверждена хостес — гость получает сообщение в чате Консьержа через вебхук и **не** получает сообщения от бота Батлера на чужой chat id. → Task 10, Task 13.
5. Гость без согласия — текст его реплик и реплик Консьержа не хранится, табло показывает «текст скрыт»; телефоны на табло замаскированы всегда. → Task 1, Task 11.

---

## Butler (Astor_Butler_MVP, ветка `feat/negotiation`)

### Task 1: Лента визита `visit_turns`

**Files:**
- Create: `src/main/resources/db/changelog/2026-10-15-visit-turns.{yaml,sql}` (включить в `changelog-master.yaml`)
- Create: `src/main/java/museon_online/astor_butler/domain/ledger/{TurnSide,TurnChannel,TurnKind,VisitTurn,VisitTurnDraft,VisitTurnRepository,LedgerWriter,LedgerProperties,PhoneMask}.java`
- Create: `src/main/java/museon_online/astor_butler/api/ledger/LedgerController.java`
- Modify: `config/InternalApiGuardFilter.java` (`/api/ledger/**` в `INTERNAL_PATHS`), `service/message/MessageGatewayService.java` (вход/ответ), `domain/journey/JourneyRecorder.java` (действия персонала и Presto), `application.yaml`, `docker-compose.prod.yml`
- Test: `domain/ledger/{VisitTurnRepositoryTest,LedgerWriterTest,PhoneMaskTest}.java`, `api/ledger/LedgerControllerTest.java`, дополнение `MessageGatewayServiceTest`

**Interfaces:**
- Consumes: `GuestVisitRepository.activeByChat(long, Instant)`, `GuestVisitRepository.findByReservation(long)` (#202).
- Produces:
  - `enum TurnSide { GUEST, CONCIERGE, BUTLER, STAFF, SYSTEM }`, `enum TurnChannel { CONCIERGE_TG, BUTLER_TG, STAFF_GROUP, GLASSES, SABY, INTERNAL }`, `enum TurnKind { MESSAGE, TOOL_CALL, TOOL_RESULT, OFFER, STAFF_ACTION, FALLBACK }`
  - `record VisitTurnDraft(Long visitId, String sessionId, TurnSide side, TurnChannel channel, TurnKind kind, String text, Map<String,Object> payload, String goal)`
  - `record VisitTurn(long id, Long visitId, String sessionId, Instant at, TurnSide side, TurnChannel channel, TurnKind kind, String text, Map<String,Object> payload, String goal)`
  - `VisitTurnRepository`: `long append(VisitTurnDraft)`, `int linkSession(String sessionId, long visitId)`, `List<VisitTurn> ofVisit(long visitId)`, `List<VisitTurn> ofSession(String sessionId)`, `int countGuestMessages(long visitId)`; класс аннотирован `@Transactional(propagation = REQUIRES_NEW)`
  - `LedgerWriter`: `void write(VisitTurnDraft)` (маскирует телефоны в `text`, глотает ошибки, ничего не делает при выключенном флаге), `void message(Long chatId, TurnSide side, TurnChannel channel, String text)` (визит по `activeByChat`)
  - `PhoneMask.mask(String text) -> String`
  - `POST /api/ledger/v1/turns` тело `VisitTurnDraft` → `{ "id": long }`; `POST /api/ledger/v1/sessions/{sessionId}/link` тело `{ "visitId": long }`

SQL: `visit_turns(id BIGSERIAL PK, visit_id BIGINT NULL, session_id VARCHAR(64) NULL, at TIMESTAMPTZ NOT NULL DEFAULT now, side VARCHAR(12) NOT NULL, channel VARCHAR(16) NOT NULL, kind VARCHAR(16) NOT NULL, text VARCHAR(4000), payload TEXT, goal VARCHAR(8))`, индексы по `visit_id` и `session_id`. `payload` — JSON строкой (H2 без jsonb).

- [ ] **Step 1: Write failing tests**
  - `PhoneMaskTest.masksRussianPhonesInFreeText`: `mask("звоните +79120000060 или 8 912 000-00-60")` → `"звоните +7 912 ***-**-60 или +7 912 ***-**-60"`; `mask(null)` → `null`.
  - `VisitTurnRepositoryTest.appendsAndReadsInOrder`, `linkSessionMovesConciergeTurnsToTheVisit` (две реплики с `sessionId="c-1"`, `linkSession("c-1", 7)` → 2, `ofVisit(7)` возвращает обе по времени), `countsOnlyGuestMessages`.
  - `LedgerWriterTest.disabledWritesNothing`, `repositoryFailureIsSwallowed`, `phonesAreMaskedBeforeWriting`.
  - `LedgerControllerTest.conciergeTurnWithoutConsentKeepsNoText`: тело с `payload.hidden=true` и непустым `text` → в базе `text = null`.
  - `MessageGatewayServiceTest.ledgerHearsTheGuestAndTheReply`: при включённом флаге `LedgerWriter.message` вызывается дважды — `GUEST/BUTLER_TG` с текстом гостя и `BUTLER/BUTLER_TG` с текстом ответа.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='PhoneMaskTest,VisitTurnRepositoryTest,LedgerWriterTest,LedgerControllerTest,MessageGatewayServiceTest'` — Expected: FAIL (классов нет).
- [ ] **Step 3: Implement.** Хуки — необязательные поля с сеттером (паттерн #202), конструкторы не трогать. В `JourneyRecorder.record` после записи события с источником `STAFF` или `PRESTO` — `LedgerWriter.write(STAFF или SYSTEM, STAFF_GROUP или SABY, STAFF_ACTION, text = kind.name())`.
- [ ] **Step 4: Run** те же тесты и `mvn -B -o -q test` — Expected: PASS, весь набор зелёный.
- [ ] **Step 5: Commit** `feat(ledger): one visit ledger for guest, agents and staff`.

### Task 2: API табло и токен табло

**Files:**
- Create: `api/board/{BoardTokenFilter,BoardController,BoardQueries,BoardMetrics,HealthMap,BoardProperties}.java`
- Modify: `application.yaml` (`astor.board.enabled`, `astor.board.token`, `astor.board.concierge-health-url`), `docker-compose.prod.yml` (`ASTOR_BOARD_ENABLED`, `ASTOR_BOARD_TOKEN`, `ASTOR_BOARD_CONCIERGE_HEALTH_URL`)
- Test: `api/board/{BoardTokenFilterTest,BoardControllerTest,BoardMetricsTest,HealthMapTest}.java`

**Interfaces:**
- Consumes: `VisitTurnRepository` (Task 1), `GuestVisitRepository` (#202), `saby_call_log`, `model_interaction_audit`.
- Produces (JSON):
  - `GET /api/board/v1/visits?from=ISO&to=ISO` → `[{id, reservationId, stage, visitDay, guest (маска имени: «Мария К.»), phone (маска), table, guestMessages, lastTurnAt, hasSummary}]`
  - `GET /api/board/v1/visits/{id}/turns` → `[{at, side, channel, kind, text, payload, goal}]` (текст `null` → `"текст скрыт"`)
  - `GET /api/board/v1/metrics?from&to` → `{visits, guestMessagesAvg, visitsWith40Plus, negotiationsClosed, negotiationsTotal, confirmMedianSeconds, lunchConversion, reviews, returned, modelCalls, modelErrors, modelP95Ms, fallbacks, sabyErrors}`
  - `GET /api/board/v1/health-map` → `[{component, status: GREEN|YELLOW|RED, detail}]` для `butler, postgres, redis, kafka, gigachat, saby, concierge`
  - `GET /api/board/v1/stream` → SSE, событие `turn` с `{visitId}` при каждой новой строке ленты (публикует `LedgerWriter` через `ApplicationEventPublisher`, событие `VisitTurnAppended(Long visitId)`)

- [ ] **Step 1: Write failing tests**
  - `BoardTokenFilterTest.rejectsWithoutOrWithWrongToken` (401), `rejectsEverythingWhenTokenTooShort` (токен из 8 символов → 401 даже с верным заголовком), `passesWithToken`.
  - `BoardControllerTest.visitsAreListedWithMaskedPhonesAndNames`, `hiddenTextIsShownAsHidden`.
  - `BoardMetricsTest.countsGuestMessagesAndForty` (визиты с 12 и 41 сообщениями → `guestMessagesAvg=26.5`, `visitsWith40Plus=1`), `negotiationClosedWhenAReservationOrOrderToolSucceeded`.
  - `HealthMapTest.sabyIsYellowAboveTenPercentErrorsAndRedAboveHalf` (за 15 минут), `gigachatRedWhenLastFiveCallsFailed`, `conciergeRedWhenHealthUrlFails`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='Board*Test,HealthMapTest'` — Expected: FAIL.
- [ ] **Step 3: Implement.** `/api/board/**` не входит в `INTERNAL_PATHS` (браузер не шлёт внутренний токен); защищает `BoardTokenFilter`. Здоровье `postgres/redis/kafka` — из `HealthEndpoint` компонентов `db/redis/kafka`.
- [ ] **Step 4: Run** те же тесты + полный набор — PASS.
- [ ] **Step 5: Commit** `feat(board): closed board API — visits, turns, metrics, health map, live stream`.

### Task 3: Саммари визита, память гостя и уроки агентов

**Files:**
- Create: migration `2026-10-15-visit-summaries.{yaml,sql}` (`visit_summaries`, `agent_lessons`)
- Create: `domain/summary/{VisitSummary,VisitSummaryRepository,VisitSummaryService,AgentLesson,AgentLessonRepository,GuestMemory,SummaryProperties}.java`
- Modify: `api/board/BoardController.java` (саммари и уроки), `application.yaml`, `docker-compose.prod.yml` (`ASTOR_VISIT_SUMMARY_ENABLED`)
- Test: `domain/summary/{VisitSummaryServiceTest,GuestMemoryTest,AgentLessonRepositoryTest}.java`

**Interfaces:**
- Consumes: `VisitTurnRepository.ofVisit` (Task 1), `ModelGateway.generateText(ModelTextRequest)`, `GuestVisitRepository` (#202).
- Produces:
  - `record VisitSummary(long visitId, String guestWanted, String venueOffered, String outcome, List<String> stuck, List<String> nextTimeGuest, List<String> nextTimeAgents, Instant createdAt)`
  - `VisitSummaryService.summarize(long visitId) -> Optional<VisitSummary>` (модель отвечает JSON с ключами `guestWanted, venueOffered, outcome, stuck, nextTimeGuest, nextTimeAgents`; невалидный JSON → `Optional.empty()`, ничего не пишется); `@Scheduled` каждые 10 минут: визиты на этапе AFTER без саммари, не больше 20 за проход
  - `GuestMemory.of(String phone, Long chatId) -> List<String>` — `nextTimeGuest` последних 3 саммари гостя (по телефону, иначе по чату)
  - `AgentLessonRepository`: `propose(long visitId, String side /*CONCIERGE|BUTLER*/, String text)`, `accept(long id, String editedText)`, `reject(long id)`, `List<String> accepted(String side)`
  - Табло: `GET /api/board/v1/visits/{id}/summary`, `POST /api/board/v1/visits/{id}/summary` (пересобрать), `GET /api/board/v1/lessons?status=PROPOSED`, `POST /api/board/v1/lessons/{id}/accept` (тело `{text}`), `POST /api/board/v1/lessons/{id}/reject`, `POST /api/board/v1/visits/{id}/next-time` (ручная правка менеджера → урок со статусом ACCEPTED)

- [ ] **Step 1: Write failing tests**
  - `VisitSummaryServiceTest.summaryFromTheLedgerIsStoredAndLessonsProposed` (модель-заглушка отдаёт JSON; сохраняется саммари; `nextTimeAgents` из 2 строк → 2 урока PROPOSED с `side`, указанным моделью в префиксе `CONCIERGE:`/`BUTLER:`).
  - `VisitSummaryServiceTest.brokenModelJsonStoresNothing`, `disabledDoesNothing`, `promptHidesPhones` (в тексте запроса нет 10+ цифр подряд).
  - `GuestMemoryTest.lastThreeVisitsByPhoneThenByChat`.
  - `AgentLessonRepositoryTest.onlyAcceptedLessonsReachTheAgents`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='VisitSummaryServiceTest,GuestMemoryTest,AgentLessonRepositoryTest'` — FAIL.
- [ ] **Step 3: Implement.** Промпт на русском: «Ты аналитик ресторана. По ленте визита ответь строго JSON …»; лента подаётся строками `[время] СТОРОНА/канал: текст`.
- [ ] **Step 4: Run** + полный набор — PASS.
- [ ] **Step 5: Commit** `feat(summary): visit summaries, guest memory and agent lessons`.

### Task 4: Страница табло на astor-ai.ru/board

**Files:**
- Create: `frontend/astor-butler/board/index.html`, `frontend/astor-butler/js/board.js`, `frontend/astor-butler/css/board.css`
- Modify: файл edge-сайта, который обслуживает `astor-ai.ru` (`grep -rl astor-ai.ru infra/cloudru/edge`): проксировать `/api/board/*` на `aeris_astor_butler_bot:8089` (SSE — без буферизации)
- Test: `frontend/astor-butler/tests/board.test.mjs` (`node --test`, чистые функции из `board.js`)

**Interfaces:**
- Consumes: API Task 2 и Task 3.
- Produces: страница с вводом пароля (токен в `sessionStorage`, обёрнут в try/catch), вкладки **Канбан**, **Метрики**, **Архитектура**, **Уроки**; экспорт чистых функций для тестов: `groupByStage(visits) -> {STAGE: visits[]}`, `laneOf(turn) -> 'guest'|'agents'|'staff'`, `healthColor(status) -> css var`.

Колонки канбана по порядку: REQUESTED «Заявка», CONFIRMED «Подтверждена», VISIT_DAY «День визита», SEATED «За столом», BILL «Счёт», AFTER «После», RETURNED «Вернулся», CANCELLED «Отмена», NO_SHOW «Не пришёл». Дорожки карточки: GUEST → «Гость»; CONCIERGE и BUTLER → «Консьерж ⇄ Батлер»; STAFF и SYSTEM → «Персонал и системы». Архитектура — встроенный SVG: Гость → Консьерж ⇄ Батлер → (Saby, Персонал/очки), Батлер → Postgres/Redis/Kafka, оба агента → GigaChat; цвет узла из `/health-map`.

- [ ] **Step 1: Write failing tests** `groupsVisitsInStageOrder`, `conciergeAndButlerShareTheAgentsLane`, `unknownHealthIsGrey`.
- [ ] **Step 2: Run** `node --test frontend/astor-butler/tests/board.test.mjs` — FAIL.
- [ ] **Step 3: Implement** без внешних библиотек (как остальной `frontend/astor-butler`), тёмная и светлая тема через CSS-переменные, мобильная ширина без горизонтальной прокрутки; живое обновление через `EventSource` (токен в query `?t=` — `BoardTokenFilter` принимает и заголовок, и параметр `t` только для `/stream`).
- [ ] **Step 4: Run** тест — PASS; открыть страницу локально против заглушки и проверить скриншотом.
- [ ] **Step 5: Commit** `feat(board): astor-ai.ru/board — kanban, three lanes, metrics, architecture, lessons`.

### Task 5: Function calling в шлюзе моделей

**Files:**
- Create: `model/{ModelTool,ModelToolCall,ModelChatMessage,ModelChatRequest,ModelChatResponse,ToolCallingModel}.java`
- Modify: `model/ModelCapability.java` (+`TOOL_CALLING`), `model/GigaChatModelGateway.java` (реализует `ToolCallingModel`)
- Test: `model/GigaChatToolCallingTest.java` (MockRestServiceServer)

**Interfaces:**
- Produces:
  - `record ModelTool(String name, String description, Map<String,Object> parameters /* JSON Schema */)`
  - `record ModelToolCall(String name, Map<String,Object> arguments)`
  - `record ModelChatMessage(String role /* system|user|assistant|function */, String content, String name, ModelToolCall functionCall)`
  - `record ModelChatRequest(List<ModelChatMessage> messages, List<ModelTool> tools, ModelProfile profile, String purpose, Map<String,Object> metadata)`
  - `record ModelChatResponse(String text, Optional<ModelToolCall> toolCall, String provider, String model, Duration latency)`
  - `interface ToolCallingModel { ModelChatResponse chat(ModelChatRequest request); }`

Формат GigaChat: в теле `functions: [{name, description, parameters}]`, `function_call: "auto"`; ответ `choices[0].message.function_call = {name, arguments}` (`arguments` — объект или JSON-строка, принять оба); результат инструмента возвращается сообщением `{role: "function", name, content: <JSON-строка>}`. Аудит — в `model_interaction_audit`, как у `generateText`.

- [ ] **Step 1: Write failing tests** `sendsFunctionsAndParsesAFunctionCall`, `argumentsAsAJsonStringAreParsedToo`, `plainAnswerHasNoToolCall`, `functionResultIsSentWithFunctionRole`, `http402IsAModelUnavailableError` (бросает `ModelUnavailableException` — создать, наследник `RuntimeException`, в `model/`).
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest=GigaChatToolCallingTest` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** + полный набор — PASS.
- [ ] **Step 5: Commit** `feat(model): GigaChat function calling`.

### Task 6: Каталог инструментов — каркас и инструменты чтения

**Files:**
- Create: `capability/{CapabilityTool,CapabilityContext,CapabilityResult,CapabilityRegistry,CapabilityProperties}.java`, `capability/tools/{VenueInfoTool,LunchMenuTool,TableAvailabilityTool,VisitStatusTool}.java`, `api/capability/CapabilityController.java`
- Modify: `config/InternalApiGuardFilter.java` (`/api/capabilities/**`), `application.yaml`, compose (`ASTOR_CAPABILITIES_ENABLED`)
- Test: `capability/CapabilityRegistryTest.java`, `capability/tools/*Test.java`, `api/capability/CapabilityControllerTest.java`

**Interfaces:**
- Consumes: `LedgerWriter.write` (Task 1), `ModelTool` (Task 5), `VenueFactsCatalog`, `BusinessLunchCatalog`, `SabyNutritionService`, `TableReservationService.availability`, `GuestVisitRepository` (#202).
- Produces:
  - `interface CapabilityTool { String name(); String description(); Map<String,Object> parameters(); boolean commitment(); CapabilityResult invoke(CapabilityContext ctx, Map<String,Object> args); }`
  - `record CapabilityContext(String caller /* CONCIERGE|BUTLER_AGENT */, Long chatId, String sessionId, Long visitId, String idempotencyKey, String guestName, String guestPhone)`
  - `record CapabilityResult(boolean ok, Map<String,Object> facts, String error /* код: BAD_ARGUMENT, NOT_FOUND, NOT_ALLOWED, UNAVAILABLE */)`
  - `CapabilityRegistry`: `List<ModelTool> asModelTools()`, `CapabilityResult invoke(String name, CapabilityContext, Map<String,Object>)` — неизвестное имя → `error=NOT_FOUND`; исключение инструмента → `error=UNAVAILABLE`; оба вызова пишутся в ленту (`TOOL_CALL`, `TOOL_RESULT`, сторона по `caller`: CONCIERGE → CONCIERGE, BUTLER_AGENT → BUTLER).
  - `GET /api/capabilities/v1/tools` → `[{name, description, parameters, commitment}]`; `POST /api/capabilities/v1/{tool}` тело `{context:{...}, arguments:{...}}` → `CapabilityResult`.
  - Инструменты: `venue_info{}` → `{name, address, facts:{parking,kids,wifi,winePairing}}` (null — «нет данных»); `lunch_menu{date?}` → `{courses:[{title, dishes:[{code,title,priceRub,grams?,kcal?,protein?,fat?,carbs?}]}], from, to, days}`; `table_availability{date, time, partySize}` → `{free:[{tableCode, name, capacity}], alternatives:[{time, tableCode}]}`; `visit_status{}` → `{stage, reservationId, visitDay, table}` по `visitId` или `chatId`.

- [ ] **Step 1: Write failing tests**
  - `CapabilityRegistryTest.unknownToolIsNotFoundNotAnException`, `toolExceptionIsUnavailable`, `everyCallIsWrittenToTheLedger`, `disabledRegistryAnswersUnavailable`.
  - `TableAvailabilityToolTest.pastDateIsABadArgument`, `zeroGuestsIsABadArgument`, `freeTablesAndAlternatives`.
  - `LunchMenuToolTest.nutritionIsAddedWhenSabyKnowsIt`, `VenueInfoToolTest.nullFactsAreReportedAsNoData`.
  - `CapabilityControllerTest.listsToolsAsJsonSchemas`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='Capability*Test,*ToolTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** + полный набор — PASS.
- [ ] **Step 5: Commit** `feat(capability): Capability API v1 — registry and read tools`.

### Task 7: Инструменты-обязательства и идемпотентность

**Files:**
- Create: migration `2026-10-15-capability-calls.{yaml,sql}` (`capability_calls(idempotency_key VARCHAR(80) PK, tool VARCHAR(40), result TEXT, created_at)`), `capability/IdempotencyStore.java`, `capability/tools/{ReservationCreateTool,ReservationChangeTool,ReservationCancelTool,LunchOrderPlaceTool,TableRequestTool,BillRequestTool,ReviewSubmitTool,TipCreateTool}.java`
- Modify: `domain/billing/GuestBillRequestService.java` (+`requestByReservation(long reservationId, String source) -> BillRequestOutcome`), `domain/billing/VisitReviewService.java` (+`submit(long reservationId, int stars, String text) -> boolean`), `domain/booking/TableReservationCommand` вызовы с каналом `CONCIERGE` для caller CONCIERGE
- Test: `capability/IdempotencyStoreTest.java`, `capability/tools/*Test.java`

**Interfaces:**
- Consumes: Task 6 каркас; `TableReservationService.createReservation/changeByGuest/cancelByGuest`, `BusinessLunchService.place`, `TableRequestRouter.route`, `TipService.createDraft`.
- Produces:
  - `IdempotencyStore`: `Optional<CapabilityResult> find(String key)`, `void save(String key, String tool, CapabilityResult result)`; реестр для `commitment()==true` без ключа → `error=BAD_ARGUMENT`; с ключом, который уже есть → сохранённый результат без вызова сервиса.
  - `reservation_create{date, time, partySize, guestName, guestPhone?, wish?}` → `{reservationId, status:"AWAITING_HOSTESS", table, time}`; канал брони = `CONCIERGE` для caller CONCIERGE, `TELEGRAM` для BUTLER_AGENT.
  - `reservation_change{reservationId, date?, time?, partySize?}`, `reservation_cancel{reservationId}` → `{reservationId, status}`; чужая бронь (не тот chatId/sessionId) → `NOT_ALLOWED`.
  - `lunch_order_place{reservationId, dishCodes:[...], confirmedByGuest:true}` → `{orderId, totalRub, sabyStatus}`; `confirmedByGuest != true` → `NOT_ALLOWED`.
  - `table_request{reservationId, request: BREAD|WATER|WAITER|SPEED_UP|CUTLERY}` → `{routed:true, waiter?}`; этап не SEATED → `NOT_ALLOWED`.
  - `bill_request{reservationId}` → `{billId, totalRub, paymentUrl?}`; `review_submit{reservationId, stars 1..5, text?}` → `{accepted}`; повторный отзыв → `NOT_ALLOWED`; `tip_create{reservationId, amountRub}` → `{draftId, waiter}`.

- [ ] **Step 1: Write failing tests**
  - `IdempotencyStoreTest.sameKeyReturnsTheSavedResultWithoutCallingTheService` (Review Focus 3: `reservation_create` дважды с ключом `k1` → `createReservation` вызван 1 раз, оба результата равны).
  - `ReservationCreateToolTest.conciergeBookingGetsTheConciergeChannel`, `neverConfirms` (статус всегда `AWAITING_HOSTESS`), `commitmentWithoutKeyIsABadArgument`.
  - `LunchOrderPlaceToolTest.withoutGuestConfirmationNothingIsPlaced`.
  - `TableRequestToolTest.onlyWhenSeated`, `ReviewSubmitToolTest.secondReviewIsNotAllowed`, `ReservationCancelToolTest.someoneElsesReservationIsNotAllowed`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='IdempotencyStoreTest,*ToolTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** + полный набор — PASS.
- [ ] **Step 5: Commit** `feat(capability): commitment tools with idempotency keys`.

### Task 8: Агент Батлера и API переговоров

**Files:**
- Create: `agent/{ButlerAgent,AgentLoop,AgentProperties,NegotiationTurn,NegotiationReply,ButlerPrompt}.java`, `api/negotiation/NegotiationController.java`
- Modify: `config/InternalApiGuardFilter.java` (`/api/negotiation/**`), `service/message/MessageGatewayService.java` (агент для чатов из `ASTOR_AGENT_ALLOWLIST` до сценариев, при `fallback` — сценарии), `application.yaml`, compose (`ASTOR_AGENT_ENABLED`, `ASTOR_AGENT_ALLOWLIST`)
- Test: `agent/{AgentLoopTest,ButlerAgentTest}.java`, `api/negotiation/NegotiationControllerTest.java`, дополнение `MessageGatewayServiceTest`

**Interfaces:**
- Consumes: `ToolCallingModel.chat` (Task 5), `CapabilityRegistry` (Tasks 6–7), `GuestMemory.of`, `AgentLessonRepository.accepted("BUTLER")` (Task 3), `LedgerWriter` (Task 1).
- Produces:
  - `AgentLoop.run(List<ModelChatMessage> history, List<ModelTool> tools, CapabilityContext ctx) -> AgentOutcome` где `record AgentOutcome(String text, List<CapabilityResult> results, boolean fallback, int toolCalls)`; не больше 5 вызовов, после 5-го — ответ модели без инструментов; `ModelUnavailableException` или таймаут 20 с → `fallback=true, text=null`.
  - `record NegotiationTurn(String sessionId, Long visitId, String guestText, Map<String,Object> conciergePosition /* цели гостя: time, partySize, budget, constraints */, String guestName, String guestPhone)`
  - `record NegotiationReply(String butlerText /* голос Батлера, без префикса */, Map<String,Object> offer, List<Map<String,Object>> facts, Long visitId, boolean fallback)`
  - `ButlerAgent.negotiate(NegotiationTurn) -> NegotiationReply`; `ButlerAgent.reply(IncomingMessage, Long visitId) -> Optional<String>` (прямой бот; пусто при fallback)
  - `POST /api/negotiation/v1/turn` тело `NegotiationTurn` → `NegotiationReply`; когда агент создал бронь, визит открывается (#202) и `linkSession(sessionId, visitId)`.
  - `ButlerPrompt.system(List<String> lessons, List<String> guestMemory, String venueFacts) -> String` — цели ресторана: заполнить зал, продать бизнес-ланч, держать оборот стола 90 минут, получить отзыв; правила: факты только из инструментов, не обещать подтверждение, сумма только из `lunch_menu`/`bill_request`, перед `lunch_order_place` показать итог и получить «да».

- [ ] **Step 1: Write failing tests**
  - `AgentLoopTest.toolCallThenAnswer` (модель-заглушка: 1-й ответ — вызов `table_availability`, 2-й — текст), `stopsAfterFiveToolCalls`, `unknownToolNameGetsAnErrorFactAndTheLoopGoesOn` (Review Focus 2), `modelUnavailableIsAFallback` (Review Focus 1).
  - `ButlerAgentTest.acceptedLessonsAndGuestMemoryAreInThePrompt`, `aReservationLinksTheConciergeSession`.
  - `NegotiationControllerTest.requiresTheInternalToken` (через `InternalApiGuardFilter`), `returnsTheOfferAndFacts`.
  - `MessageGatewayServiceTest.allowlistedChatIsAnsweredByTheAgent`, `otherChatsKeepTheScenarios`, `agentFallbackGoesToTheScenarios`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='AgentLoopTest,ButlerAgentTest,NegotiationControllerTest,MessageGatewayServiceTest'` — FAIL.
- [ ] **Step 3: Implement.** Каждая реплика агента пишется в ленту (`BUTLER`, `MESSAGE` или `OFFER`, `goal="VENUE"`); fallback — строка `FALLBACK`.
- [ ] **Step 4: Run** + полный набор — PASS.
- [ ] **Step 5: Commit** `feat(agent): butler agent with venue goals and the negotiation API`.

### Task 9: Вебхук Батлер → Консьерж

**Files:**
- Create: `integration/concierge/{ConciergeWebhook,ConciergeWebhookProperties,ConciergeEvent}.java`
- Modify: `domain/booking/TableReservationNotificationService.java` — гостевые уведомления (`notifyGuestConfirmed`, `notifyGuestRejected`, `notifyGuestHoldExpired`, `notifyGuestMovedByStaff`, утренний вопрос, «гость сел», `notifyGuestChat`) для брони с каналом `CONCIERGE` идут в вебхук, **не** в Telegram; `domain/billing/GuestBillNotifier.java` (ссылка на оплату), `domain/journey/talk/TableRequestRouter.java` («официант идёт»); `application.yaml`, compose (`ASTOR_CONCIERGE_WEBHOOK_ENABLED`, `ASTOR_CONCIERGE_WEBHOOK_URL`, `ASTOR_WEBHOOK_SECRET`)
- Test: `integration/concierge/ConciergeWebhookTest.java`, дополнения `TableReservationNotificationServiceTest`

**Interfaces:**
- Produces:
  - `record ConciergeEvent(String type /* CONFIRMED|REJECTED|EXPIRED|MOVED|MORNING|SEATED|BILL_READY|STAFF_COMING|CANCELLED */, long reservationId, Long visitId, String sessionId, String butlerText, Map<String,Object> facts)`
  - `ConciergeWebhook.send(ConciergeEvent) -> boolean` — POST `{url}/api/concierge/v1/events`, `X-Astor-Signature: sha256=<hex(HMAC-SHA256(secret, body))>`; 3 попытки с паузой 1 с и 4 с; результат — строка ленты (`BUTLER/INTERNAL/MESSAGE`).
- [ ] **Step 1: Write failing tests** `signsTheBodyWithHmac` (известный секрет и тело → известная подпись), `retriesThreeTimesThenGivesUp`, `TableReservationNotificationServiceTest.conciergeBookingIsNotSentToTelegram` (Review Focus 4: при подтверждении брони с каналом CONCIERGE Telegram-клиент не вызывается, вебхук — да), `telegramBookingIsUnchanged`.
- [ ] **Step 2: Run** `mvn -B -o -q test -Dtest='ConciergeWebhookTest,TableReservationNotificationServiceTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** + полный набор — PASS.
- [ ] **Step 5: Commit** `feat(concierge): signed webhook instead of Telegram for concierge bookings`.

---

## Concierge (Astor_Concierge, ветка `feat/negotiation-agent`)

### Task 10: Ссылка на живой бот и запись в ленту Батлера

**Files:**
- Modify: `data/venues.json` (`venueBotUrl` → `https://t.me/aeris_butler_bot`), `src/butler/client.ts` (+`appendTurn`, `linkSession`), `src/telegram/bot.ts` (`run` и `send` пишут реплики), `src/config.ts` (`LEDGER_ENABLED`)
- Create: `src/ledger/ledger.ts`
- Test: `test/ledger.test.ts`, `test/catalog.test.ts` (или существующий тест каталога)

**Interfaces:**
- Produces:
  - `ButlerClient.appendTurn(turn: { sessionId: string; side: 'GUEST'|'CONCIERGE'|'BUTLER'; channel: 'CONCIERGE_TG'; kind: 'MESSAGE'|'OFFER'|'TOOL_CALL'|'TOOL_RESULT'|'FALLBACK'; text: string | null; payload?: Record<string, unknown>; goal?: 'GUEST'|'VENUE' }): Promise<void>`
  - `Ledger.record(chatId: number, side, kind, text: string | null, payload?)` — `sessionId = "c-" + псевдонимный id гостя из analytics` (не chat id); без согласия гостя (`consent_given` нет или `consent_withdrawn` позже) — `text: null`, `payload.hidden = true`; ошибки глотаются, ответ гостю не задерживается (fire-and-forget).
- [ ] **Step 1: Write failing tests** `venueBotIsTheLiveAerisBot` (`venues.json` AERIS → `https://t.me/aeris_butler_bot`), `ledgerSendsTextOnlyWithConsent` (Review Focus 5), `ledgerFailureNeverBlocksTheReply`, `sessionIdIsPseudonymousNotTheChatId`.
- [ ] **Step 2: Run** `npm test` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run check` — PASS.
- [ ] **Step 5: Commit** `feat(ledger): live AERIS bot link and concierge turns in the visit ledger`.

### Task 11: Function calling в провайдере GigaChat Консьержа

**Files:**
- Modify: `src/understanding/llm.ts`
- Test: `test/llm-tools.test.ts`

**Interfaces:**
- Produces:
  - `interface ToolSpec { name: string; description: string; parameters: Record<string, unknown> }`
  - `type AgentMessage = ChatMessage | { role: 'function'; name: string; content: string } | { role: 'assistant'; content: string; functionCall?: { name: string; arguments: Record<string, unknown> } }`
  - `interface ToolChatResult { text?: string; functionCall?: { name: string; arguments: Record<string, unknown> } }`
  - `GigaChatProvider.chatWithTools(messages: AgentMessage[], tools: ToolSpec[], options: CompletionOptions): Promise<ToolChatResult>` — тот же формат, что в Task 5 (`functions`, `function_call: "auto"`, `arguments` объект или строка); HTTP 402 → `ModelError` с `code = 'UNAVAILABLE'`.
- [ ] **Step 1: Write failing tests** `sendsFunctionsAndReadsAFunctionCall`, `stringArgumentsAreParsed`, `paymentRequiredIsUnavailable`.
- [ ] **Step 2: Run** `npm test` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run check` — PASS.
- [ ] **Step 5: Commit** `feat(llm): GigaChat function calling`.

### Task 12: Агент Консьержа — два голоса в одном чате

**Files:**
- Create: `src/agent/{agent.ts,prompt.ts,tools.ts}`
- Modify: `src/butler/client.ts` (+`negotiate(turn): Promise<NegotiationReply>` → `POST /api/negotiation/v1/turn`), `src/telegram/bot.ts` (для `AGENT_ALLOWLIST` и `AGENT_ENABLED` реплики идут в агента; иначе — `routeGuest` как сейчас), `src/config.ts`
- Test: `test/agent.test.ts`

**Interfaces:**
- Consumes: `chatWithTools` (Task 11), `Ledger.record` (Task 10), `ButlerClient.negotiate`, каталог мест `src/catalog/catalog.ts`.
- Produces:
  - Инструменты Консьержа: `find_venues{query, date?, time?, partySize?}` → карточки мест из каталога; `talk_to_venue{venueCode, message, position:{time?, partySize?, budget?, constraints?}}` → `NegotiationReply` Батлера; `ask_guest{question}` — завершает ход вопросом гостю.
  - `ConciergeAgent.handle(guest: { chatId: number; telegramUserId: number; firstName?: string }, text: string): Promise<string[] | null>` — массив сообщений по порядку: реплики Батлера с префиксом `AERIS · Батлер: ` (каждый `butlerText` из `talk_to_venue`), затем реплика Консьержа; `null` — запасной путь (модель недоступна) → бот уходит в `routeGuest`.
  - Промпт Консьержа: цели гостя (быстро, вкусно, в бюджет, без лишних сообщений), правило «всё о ресторане — только через `talk_to_venue`, ничего не выдумывать», не больше 5 вызовов за ход.
- [ ] **Step 1: Write failing tests** `butlerSpeaksInItsOwnVoiceThenConciergeSums` (заглушка модели: `talk_to_venue` → ответ Батлера «В 13:00 занято, есть 13:15 у окна» → итоговые сообщения `["AERIS · Батлер: В 13:00 занято, есть 13:15 у окна", "<текст Консьержа>"]`), `notAllowlistedGuestGetsTheOldFlow`, `modelDownFallsBackToTheOldFlow` (Review Focus 1), `everyStepIsInTheLedger`.
- [ ] **Step 2: Run** `npm test` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run check` — PASS.
- [ ] **Step 5: Commit** `feat(agent): concierge agent negotiates with the butler in one chat`.

### Task 13: Приём вебхука Батлера

**Files:**
- Create: `src/butler/webhook.ts`
- Modify: `src/http/web.ts` (маршрут `POST /api/concierge/v1/events` на порту web-канала), `src/booking/store.ts` (связь `reservationId → chatId` при брони агентом), `src/booking/poller.ts` (брони агента не опрашивать), `src/config.ts` (`ASTOR_WEBHOOK_SECRET`)
- Test: `test/webhook.test.ts`

**Interfaces:**
- Consumes: формат `ConciergeEvent` (Task 9), `Ledger.record`.
- Produces: `handleButlerEvent(rawBody: string, signature: string | undefined): Promise<{ status: 200 | 401 | 404 }>` — неверная подпись → 401 и ничего не отправлено; неизвестная бронь → 404; иначе гость получает `AERIS · Батлер: <butlerText>`, повтор того же события (тот же `type`+`reservationId`) не дублирует сообщение.
- [ ] **Step 1: Write failing tests** `badSignatureIsRejected`, `confirmedReachesTheGuestInButlerVoice` (Review Focus 4), `sameEventTwiceIsDeliveredOnce`, `agentBookingsAreNotPolled`.
- [ ] **Step 2: Run** `npm test` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run check` — PASS.
- [ ] **Step 5: Commit** `feat(butler): signed butler events delivered in the butler's voice`.

---

## Task 14: Включение, прогон и документы (оба репозитория)

**Files:**
- Create: `docs/operations/NEGOTIATION_ROLLOUT.md` (Батлер): флаги по порядку (ledger → board → summary → capabilities → webhook → agent), env обоих сервисов, маршрут edge `/api/board/*`, проверка после каждого шага (`visit_turns`, `/api/board/v1/health-map`), откат; `scripts/e2e/negotiation-run.txt` — 40 реплик гостя Консьержа от «где пообедать у Мамина-Сибиряка» до отзыва.
- Modify: `README.md` обоих репозиториев — раздел «Как устроен Astor: Консьерж ⇄ Батлер» со ссылкой на спецификацию.

- [ ] **Step 1:** Написать документы.
- [ ] **Step 2: Run** `mvn -B -o -q test` (Батлер) и `npm run check` (Консьерж) — PASS.
- [ ] **Step 3: Commit** `docs(negotiation): rollout, rollback and the 40-message concierge run`.

## Порядок и параллельность

- Батлер: Task 1 → (Task 2 ∥ Task 5) → Task 3 → Task 4; Task 5 → Task 6 → Task 7 → Task 8 → Task 9.
- Консьерж: Task 10 ∥ Task 11 → Task 12 → Task 13 (Task 12 ждёт API Task 8, Task 13 — формат Task 9; тесты Консьержа идут на заглушках, поэтому писать можно параллельно с Батлером).
- Task 14 — последним.

## Task 15: Короткие приветствия двух голосов (оба репозитория; одобрено Михаилом 14.10)

**Files:**
- Butler: `fsm/scenario/FirstTouchScenario.java` (+ тест) — `/start` без deep link и без истории: текст «AERIS · Батлер. Я на связи за столом: меню, вино, счёт, позвать официанта. Что подать?», кнопки «Меню ланча», «Позвать официанта», «Счёт»; визитка-картинка из `/start` убирается (остаётся в закреплённом сообщении); приветствие с историей (`RepeatLunchGreeting`, #202) и deep-link-входы не меняются.
- Concierge: `src/agent/agent.ts` / `src/telegram/bot.ts` (+ тест) — для чатов агента `/start`: «Я Консьерж Astor — подберу место и договорюсь с рестораном за вас. Куда и когда хотите пойти?», кнопки «Обед сегодня», «Ужин», «Мини-апп»; имя/телефон/согласие не спрашиваются до первого предложения ресторана, телефон — один раз, когда ресторан готов подтвердить; старый поток для чатов вне списка не меняется.

- [ ] **Step 1:** Failing tests: `FirstTouchScenarioTest.startWithoutContextIsShortWithThreeButtons`, Concierge `test/agent.test.ts` «start greeting for an allowlisted chat is one question with three buttons».
- [ ] **Step 2: Run** — FAIL. **Step 3:** Implement. **Step 4: Run** — PASS (full suites). **Step 5: Commit** `feat(greeting): one short first screen per voice` in each repo.
