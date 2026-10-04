# Astor Butler Architecture

## Назначение

Astor Butler - soft-governance tool для HoReCa. Система объединяет guest-facing каналы, manager/staff/admin web app и публичную promo/lead-gen витрину. Telegram остается первым рабочим транспортом MVP, но целевая архитектура строится как omnichannel agent: Telegram, web-chat, телефония, WhatsApp, MAX, Instagram Direct и REST/API-клиенты нормализуются в единый Message Gateway. Web app - рабочее место команды, promo frontend - витрина для production story, System Design/JavaGuru-материалов и сбора лидов.

Главный архитектурный принцип: FSM является single source of truth для сценариев взаимодействия. UI-слои не содержат бизнес-логики.

## High-Level Diagram

```mermaid
flowchart LR
    Guest["Guest / omnichannel user"]
    Staff["Staff / Manager / Admin"]
    Visitor["Promo visitor / Lead"]

    Channels["Guest Channels<br/>Telegram / Web Chat / Telephony / WhatsApp / MAX / Instagram Direct"]
    Web["Manager Web App<br/>Next.js / React"]
    Promo["Promo Lead-Gen Frontend<br/>Next.js / React / GSAP / Framer Motion / Lenis"]

    Gateway["API Gateway<br/>TLS / routing / rate limiting / payload limits"]
    Keycloak["Keycloak<br/>OAuth2 / OIDC / JWT Stateless"]

    Rest["REST API<br/>Swagger / OpenAPI"]
    KafkaIn["Kafka Listener<br/>Consumer / Producer"]
    Services["Service Layer / gRPC Boundary<br/>module-to-module calls"]

    FSM["FSM Core / Orchestration Layer<br/>single source of truth"]
    AI["AI Adapter<br/>intent parsing / entity extraction"]

    User["User Domain"]
    Booking["Booking Domain"]
    Content["Content Domain"]
    Media["Media Domain"]
    Timeline["Timeline Domain"]
    Manager["Manager Domain"]
    Ops["Smart Solution Ops CRM<br/>projects / tasks / launches"]
    Notify["Notifications Domain"]
    Capability["Capability Extensions<br/>Memory / Preference / Tips / Guide / Play / Safety"]

    Pg["PostgreSQL + pgvector<br/>JDBC / Liquibase / semantic memory"]
    Mongo["MongoDB<br/>internal documents / flexible metadata"]
    Redis["Redis<br/>FSM hot context / idempotency / cache / short queues"]
    Scylla["ScyllaDB / Cassandra API<br/>append-only FSM timeline"]
    Neo4j["Neo4j<br/>scenario graph / preference graph"]
    S3["S3-compatible Object Storage<br/>video / photo / docs / menu / media"]
    Kafka["Kafka / RabbitMQ / Artemis<br/>events / audit / analytics / notifications"]
    Search["Elasticsearch / OpenSearch<br/>future full-text search"]

    Obs["Observability<br/>Prometheus / Grafana / ELK / Jaeger / OpenTelemetry"]
    CICD["CI/CD<br/>GitHub Actions / TeamCity / Jenkins / Nexus / Registry"]

    Guest --> Channels --> Gateway
    Staff --> Web --> Gateway
    Visitor --> Promo --> Gateway

    Gateway --> Keycloak
    Gateway --> Rest
    KafkaIn --> FSM
    Rest --> FSM
    FSM --> AI
    FSM --> Services

    Services --> User
    Services --> Booking
    Services --> Content
    Services --> Media
    Services --> Timeline
    Services --> Manager
    Services --> Ops
    Services --> Notify
    Services --> Capability

    Capability --> User
    Capability --> Booking
    Capability --> Content
    Capability --> Media
    Capability --> Timeline
    Capability --> Notify

    User --> Pg
    Booking --> Pg
    Content --> Pg
    Timeline --> Pg
    Manager --> Pg
    Ops --> Pg
    Media --> S3
    Media --> Mongo
    Content --> Mongo

    FSM --> Redis
    FSM --> Scylla
    FSM --> Neo4j
    User --> Redis
    Content --> Redis
    Booking --> Kafka
    Timeline --> Kafka
    Timeline --> Scylla
    Capability --> Neo4j
    Notify --> Kafka
    Capability --> Redis
    Capability --> Kafka
    Content --> Search

    Rest --> Obs
    Kafka --> Obs
    Pg --> Obs
    Redis --> Obs
    Scylla --> Obs
    Neo4j --> Obs
    S3 --> Obs
    CICD --> Rest
```

## Входные каналы

### Guest Channels / Transport Adapters

Telegram используется как первый guest-facing UI MVP:

- сообщения;
- callbacks;
- контакты;
- safe exit из сценария;
- уведомления и переходы в бронирование.

Telegram adapter только нормализует входящие события в `InboundEvent` и отправляет ответы. Он не принимает бизнес-решений.

Service chats внутри Telegram (`TELEGRAM_ADMIN_CHAT_ID`, `TELEGRAM_ANALYTICS_CHAT_ID`, `TELEGRAM_HOSTESS_CHAT_ID`, `TELEGRAM_SYSTEM_CHAT_ID=-5403153261`) относятся к control plane: сообщения сохраняются, попадают в audit/timeline/event trail и могут запускать служебные команды, но не запускают guest FSM. Runtime-удаление сообщений через Telegram `DeleteMessage` временно отключено; будущая UX-чистка проектируется как session policy поверх Redis/timeline.

Целевая transport-модель не привязана к Telegram. Каждый внешний канал должен иметь adapter, который приводит вход в общий контракт `MessageGatewayService`: canonical text, attachments/media references, channel identity, correlation id, consent metadata, source timestamp and raw payload reference. Бизнес-сценарии не ветвятся под каждый мессенджер.

Целевые guest-facing adapters:

- Telegram Bot API;
- web-chat на сайте заведения;
- телефония через SIP/виртуальную АТС;
- WhatsApp Business API;
- MAX bot/API, когда публичный официальный контур доступен;
- Instagram Direct через Meta Messaging API, когда доступ разрешен и доступен заказчику;
- REST API для будущих мобильных приложений, CRM и внутренних систем.

Каналы с региональными, юридическими или сетевыми ограничениями подключаются только через официальные API/интеграционные контуры, доступные заказчику и инфраструктуре проекта. Если провайдер требует отдельный egress region, это решается на уровне integration gateway, а не внутри FSM.

### Astor Glasses Informational Pilot (2026-10-04)

`GlassesController` exposes `/api/glasses/assist` and `/api/glasses/capabilities` with a separate, expiring pilot bearer credential bound server-side to one staff member and venue. Authorization is checked inside both handlers before reading the body; the existing global `permitAll` does not grant glasses access. The credential is limited to these informational routes and cannot authorize guest/staff domain mutations.

The adapter bounds JSON/media sizes, rejects unknown identity/URL fields, limits the pilot to 10 attempts/minute and one provider call in flight, and returns structured errors without provider diagnostics. Text calls only `ModelGateway.generateText`; readiness is true for 60 seconds after a nonempty, nonfallback response. There is no tenant data retrieval yet. Voice and vision are explicitly unavailable (503), with no STT invocation, image gateway fallback, media persistence or temporary files. Audio container signature checking is not codec/duration validation; full validation is required before enabling voice.

No `MessageGatewayService`, FSM, booking, staff-task or notification service is invoked. Staff assignments, evidence and actions/ACK belong to a separate future scoped domain adapter. Pilot configuration and acceptance blockers: `docs/operations/GLASSES_ASSIST_PILOT.md`. This is local preparation, not a deployed endpoint or a completed hardware voice cycle.

### Telephony Intake

Телефония рассматривается как отдельный transport adapter, а не как отдельная бизнес-логика.

Целевой поток:

1. SIP/виртуальная АТС принимает звонок и создает call session.
2. Audio stream или запись попадает в STT adapter.
3. STT возвращает transcript + confidence + timing metadata.
4. `MessageGatewayService` создает normalized inbound event с channel=`telephony`.
5. `GuestInputUnderstandingService` и FSM ведут тот же сценарий, что и для текста/голоса в мессенджере.
6. Ответ может уйти голосом через TTS/call playback, SMS, мессенджер, web-chat или карточкой команде.

MVP-level телефонии может быть read/assist mode: расшифровка звонка, summary, карточка действия для хостес/менеджера. Следующий уровень - guided voice assistant для коротких сценариев. Автоматическое подтверждение по телефону включается только после validation policy, интеграции с booking/availability domain и явных лимитов.

### Network / Integration Gateway

Astor Butler должен уметь работать параллельно с российскими каналами, внешними API и инфраструктурой заказчика.

Сетевой принцип:

- публичные каналы РФ, сайт заведения и web-chat обращаются к обычным HTTPS endpoints без VPN со стороны гостя;
- backend не должен требовать VPN от гостя;
- outbound-доступ к внешним API, которые требуют другого региона или отдельного маршрута, идет через controlled integration gateway;
- integration gateway может жить вне РФ или в нужном регионе провайдера;
- связь между backend, integration gateway и инфраструктурой заказчика закрывается через WireGuard, IPsec или managed private connectivity;
- маршрутизация по провайдерам настраивается как egress policy: Telegram/WhatsApp/Instagram/OpenAI/YandexGPT/SBIS/iiko/r_keeper могут иметь разные outbound routes;
- secrets, API tokens and webhooks хранятся отдельно от application code и ротируются через secret manager/runtime env.

Эта схема не меняет business authority: все каналы и API приходят в Message Gateway, проходят normalization, idempotency, consent checks and FSM/domain validation.

### Manager Web App

Manager/staff/admin web app нужен для операционной работы:

- dashboard менеджера;
- список и карточка заявок;
- пользователи и роли;
- поиск;
- таймлайны;
- posts/afisha management;
- media library;
- staff tasks;
- notifications center;
- admin settings.

Авторизация идет через Keycloak/OAuth2/OIDC. Frontend передает JWT в backend через `Authorization: Bearer`.

### Smart Solution Ops CRM

Smart Solution Ops CRM - внутренний проектный контур для управления командой и запусками через Telegram, REST API и будущий manager dashboard.

Первый backend slice:

- `ops_projects` - карточка проекта или запуска: вертикаль, пайплайн, статус, ответственный, срок, следующий call, прогресс, launch status и definition of done;
- `ops_tasks` - задачи проекта: ответственный, приоритет, статус, pipeline stage, срок и ссылка на deliverable;
- `ops_calls` - расписание проектных созвонов с владельцем и временем;
- `ops_artifacts` - ссылки на презентации, брифы, договоры, видео, дизайн, отчеты и другие deliverables;
- `/api/ops/**` - REST boundary для Telegram-команд, dashboard/frontend и внешней автоматизации;
- `/api/ops/projects/{id}/digest` - HTML/Telegram-ready статус проекта для командного чата.

Целевые Telegram-сценарии:

- показать активные проекты;
- показать статус конкретного проекта;
- собрать daily/weekly status digest;
- добавить задачу с ответственным и сроком;
- поставить или посмотреть ближайшие call'ы;
- сохранить ссылку на презентацию, бриф, дизайн, видео или отчет;
- обновить статус проекта или задачи;
- найти горящие дедлайны и блокеры.

AI в этом контуре помогает суммаризировать, искать и формулировать, но не является источником истины: финальные статусы, сроки, ответственные и переходы пайплайна меняются через доменный сервис/API.

### Promo / Lead-Gen Frontend

Promo frontend - публичная витрина для презентации production story, System Design/JavaGuru-материалов и сбора лидов.

Стек:

- Next.js;
- React;
- GSAP;
- Framer Motion;
- Lenis smooth scroll.

Функции:

- immersive landing page;
- видео `mp4/webm`, muted autoplay, lazy loading, adaptive streaming target;
- CTA в Telegram, CRM, курс или форму заявки;
- сбор UTM/source/campaign;
- отправка lead events в backend.

WordPress/Headless CMS не является целевой backend-архитектурой. CMS-функции реализуются собственным `content/admin` модулем на общем backend stack.

## Backend

### API Gateway / Load Balancer

Локальный MVP уже содержит API Gateway как отдельный Nginx-контейнер:

- внешний порт: `8080`;
- внешний локальный вход: `http://localhost:8080`;
- локальный upstream: `host.docker.internal:8088`, то есть внутренний dev-порт Spring Boot из IDEA;
- Swagger через gateway: `http://localhost:8080/swagger-ui/index.html`;
- health gateway: `http://localhost:8080/gateway/health`;
- мягкий rate limit для `/api/**`, `/auth/**`, `/actuator/**`, Swagger/OpenAPI;
- прокидывание `X-Request-Id`, `X-Forwarded-*`, `X-Real-IP`.

Целевая конфигурация для ресторанного пилота на 100 гостей за вечер:

- `1` локальный backend instance для разработки;
- `2` backend instances для preprod/review;
- `3` backend instances для production MVP.

В production MVP роли инстансов разделяются:

- `1` Telegram ingress/adapter instance (`aeris-astor-butler-bot` / `aeris_astor_butler_bot`) с `TELEGRAM_BOT_ENABLED=true`;
- `1+` application/API instances (`c3flex-astor-butler-bot` / `c3flex_astor_butler_bot` и будущие API replicas) с `TELEGRAM_BOT_ENABLED=false`;
- все REST/Web Chat запросы идут через API Gateway/Load Balancer;
- FSM state хранится в Redis, данные в PostgreSQL, события и аудит в Kafka.

Почему Telegram long polling только на одном instance: несколько long-polling процессов с одним bot token конфликтуют за доставку update. Горизонтальное масштабирование Telegram-входа возможно после перехода на webhook через API Gateway и строгой idempotency по `updateId`.

### Public Boundary

Наружу система предоставляет:

- REST API;
- Swagger/OpenAPI contracts;
- Kafka Listener / Consumer / Producer для event boundary;
- Prometheus metrics endpoint;
- readiness/liveness probes для Kubernetes/OpenShift.

### Service Layer / gRPC Boundary

Внутреннее взаимодействие backend-модулей проектируется через service layer. Для межмодульных и будущих межсервисных вызовов используется gRPC boundary:

- `UserService`;
- `BookingService`;
- `ContentService`;
- `MediaService`;
- `TimelineService`;
- `ManagerService`;
- `NotificationService`.

REST API не должен напрямую размазывать бизнес-логику по контроллерам. Контроллеры принимают запрос, валидируют контракт и передают команду в application/FSM/orchestration layer.

## FSM Core

FSM Core отвечает за:

- текущее состояние пользователя;
- допустимые переходы;
- fallback и safe state;
- late/offline messages;
- idempotency-aware обработку событий;
- orchestration между AI Adapter и доменными модулями.

FSM не живет в Telegram, web app или promo frontend. Все UI-каналы являются транспортами.

## AI Adapter

AI Adapter - заменяемый модуль для первого понимания человеческого сообщения:

- intent parsing;
- entity extraction;
- normalization свободного текста;
- fallback к rule-based логике при timeout/error.

AI Adapter не является источником бизнес-правил. Он помогает понять, что пользователь хочет, после чего сценарий продолжает FSM.

Confirmation policy имеет несколько уровней зрелости:

- `human approval`: важное действие подтверждает хостес/менеджер;
- `assisted approval`: Astor готовит решение и карточку, человек подтверждает одним действием;
- `trusted automation`: после проверок, лимитов и интеграции с учетной системой Astor выполняет действие сам в рамках правил;
- `human oversight`: команда видит поток событий, исключения и спорные случаи, но не подтверждает каждый типовой шаг.

Целевой термин для коммерческого текста: "поточный режим". Это означает, что сценарий становится быстрым, повторяемым и контролируемым, а не "магическим". Переход в поточный режим возможен только для сценариев с понятными правилами, observability, audit trail, rollback/override policy and domain validation.

Local LLM topology is intentionally small:

- `ollama-1` is the frontline inference instance behind `llm-gateway`;
- `ollama-2` is the learner/shadow instance for local evaluation, prompt experiments and future fine-tuning data review;
- default local language model split is `qwen2.5:1.5b` for frontline guest replies and `qwen2.5:3b` for quality/shadow tasks; heavier Qwen/VLM models are optional profiles, not baseline runtime;
- business flows must not depend on a third local model or on an LLM-only decision. FSM state and domain services remain authoritative.

### Model Gateway: органы восприятия и сменные провайдеры

Astor Butler должен расти не как набор прямых вызовов к Ollama/OpenAI/Natasha, а как система со сменными AI-способностями за одним контрактом. В терминах продукта это похоже на "цифрового сотрудника", но центр принятия решений остается не в модели, а в FSM.

```mermaid
flowchart TD
    Guest["Гость<br/>текст / голос / звонок / фото / PDF / web"]
    Transport["Transport adapters<br/>Telegram / Web / Telephony / WhatsApp / MAX / Instagram / REST"]
    Gateway["MessageGatewayService<br/>normalized input"]

    subgraph ModelGateway["Model Gateway<br/>сменный AI provider layer"]
      Text["Language capability<br/>LLM: local Qwen/Ollama or cloud OpenAI"]
      Vision["Vision capability<br/>VLM: Qwen2.5-VL / MiniCPM-V / cloud vision"]
      Hearing["Hearing capability<br/>STT: faster-whisper / cloud STT"]
      Voice["Voice capability<br/>TTS: future"]
      Embeddings["Embedding capability<br/>pgvector corpus / RAG"]
    end

    NLU["NLU / Semantic Router<br/>rules + Natasha + examples + embeddings"]
    FSM["FSM<br/>single source of truth<br/>allowed transitions"]
    Domain["Domain services<br/>booking / media / payments / content"]
    Memory["Memory stack<br/>PostgreSQL / Redis / MinIO / Scylla / Neo4j / Obsidian docs"]
    Reply["Response Composer<br/>state-bound phrase + media + buttons"]

    Guest --> Transport --> Gateway
    Gateway --> Hearing --> NLU
    Gateway --> Vision --> NLU
    Gateway --> Text --> NLU
    Gateway --> Embeddings --> NLU
    NLU --> FSM --> Domain --> Memory
    FSM --> Reply --> Guest
    Text --> Reply
```

Capability mapping:

| Capability | Local MVP | Production fallback | What it may do | What it must not do |
| --- | --- | --- | --- | --- |
| Language / "язык" | Qwen/Ollama text model | OpenAI, YandexGPT or compatible API | phrase generation, summaries, entity extraction, ambiguous intent help | confirm bookings, payments, cancellations, bids |
| Vision / "глаза" | Qwen2.5-VL or smaller local VLM; OCR/OpenCV for deterministic parts | OpenAI vision or managed VLM | read floor plan, extract table map candidates, understand guest image marks | decide table availability or create orders |
| Hearing / "уши" | faster-whisper | managed STT | transcribe Telegram voice/audio into normalized text | route business scenario without NLU/FSM |
| Voice / "голос" | disabled for MVP | future TTS | speak confirmations or accessibility mode | replace written confirmation for side effects |
| Embeddings / "ассоциативная память" | pgvector + approved corpus | cloud embeddings if needed | retrieve similar phrases, docs, menu/context chunks | override FSM transition guards |

Routing policy:

- `cheap/local`: default for simple FSM-bound tasks, local manual tests and privacy-sensitive work.
- `quality/cloud`: allowed for complex vision, hard Russian dialog, summaries and customer-facing wording when local quality is not enough.
- `privacy/local`: forces local models and deterministic tools.
- `timeout/fallback`: if a provider times out, the scenario receives a typed failure and uses state-specific deterministic fallback or recovery.
- `shadow/learner`: a second local model may evaluate prompts or collect comparison data, but never blocks the guest flow. In local MVP terms this is the `QUALITY` profile.

The Java code depends on `ModelGateway`/capability contracts, not on concrete providers from FSM or message services. Production AERIS uses `YandexModelGateway` for text generation and embeddings through Yandex AI Studio APIs. `ModelGateway.generateEmbedding(...)` writes vectors into PostgreSQL `pgvector`: `text-search-doc/latest` indexes chunks and `text-search-query/latest` embeds retrieval queries. Local Ollama/Spring AI adapters remain dev/diagnostic options only; they are not the production RAG path. Vision keeps the same boundary through `ModelGateway.analyzeImage(...)` and should use a managed provider when production credentials and safety checks are approved. STT command service and Natasha adapter are implementation details behind the same boundary.

Future provider adapters must keep the same boundary:

- API text/vision/embedding providers can replace each other behind `ModelGateway`, but RAG vectors still persist and search in PostgreSQL `pgvector`.
- YandexGPT/Alisa integration is a provider adapter, not a direct dependency of FSM scenarios.
- MAX, Meta Instagram and WhatsApp are transport adapters. They normalize inbound events into the same `MessageGatewayService` contract and must not fork business logic away from Telegram/Web.
- Telephony/SIP providers are transport adapters. STT/TTS live behind Model Gateway capability contracts; call control lives behind telephony adapter/domain ports.
- Provider choice is configuration/runtime policy; scenarios still receive typed text, slots, RAG context and model failure, not vendor SDK objects.

### Semantic Response Cache and Learning Loop

LLM latency is allowed to be high only on the first useful generation. Repeated guest questions must be answered through a semantic response cache before the system spends another long local inference call.

```mermaid
flowchart LR
    GuestQ["Guest question<br/>text / voice transcript"]
    Normalize["Normalize + state context<br/>scenario, slots, venue"]
    ExactRedis["Redis exact cache<br/>normalized key"]
    Vector["pgvector semantic cache<br/>similar question/context"]
    Answer["Best known answer<br/>rated response + source"]
    LLM["Frontline LLM<br/>generate if cache miss"]
    Audit["PostgreSQL + Kafka<br/>prompt, answer, outcome"]
    Learner["Shadow learner<br/>local Qwen quality profile / OpenAI teacher"]
    Review["Human/test review<br/>rating + approval"]

    GuestQ --> Normalize --> ExactRedis
    ExactRedis -- hit --> Answer
    ExactRedis -- miss --> Vector
    Vector -- confident hit --> Answer
    Vector -- miss/low confidence --> LLM --> Answer
    Answer --> Audit --> Learner --> Review --> ExactRedis
    Review --> Vector
```

Cache policy:

- `Redis` stores hot exact/semantic response candidates: normalized question, scenario/state, answer text, source chunks, rating, TTL and version.
- `pgvector` retrieves similar questions, menu chunks and approved response examples when exact Redis cache misses.
- `PostgreSQL` and `Kafka` remain durable truth for prompts, LLM responses, guest outcome, fallback flags and later analytics.
- The `learner/shadow` model may compare answers, suggest better wording and prepare fine-tune/eval datasets. It must not block guest flow.
- Cloud APIs such as OpenAI can be used as a teacher/evaluator for hard Russian dialog, VLM reasoning or quality scoring. Production policy decides whether to pay tokens for quality or stay local for privacy/cost.
- No cache entry may bypass FSM/domain validation. Cached answers can phrase information faster, but bookings, payments, cancellations and bids still go through domain services and explicit state transitions.

This creates a narrow ML case for the diploma/story: Astor Butler learns not by silently changing business rules, but by turning real audited dialogs into rated examples, RAG chunks, cache entries and future fine-tune/evaluation corpora.

### Vision pipeline for table booking

Vision is a supporting input layer for `TableBookingScenario`, not a replacement for the table booking domain model.

```mermaid
flowchart LR
    Pdf["AERIS PLAN.pdf<br/>source asset in MinIO"]
    Offline["Offline plan annotation<br/>VLM/OCR/OpenCV + manual review"]
    TableMap["Structured table map<br/>table, zone, capacity, polygon, tags"]
    DB["PostgreSQL<br/>venue_tables + plan regions"]
    GuestImage["Guest photo / marked plan"]
    RuntimeVision["Runtime image understanding<br/>detect mark / table candidate"]
    Slots["Machine slots<br/>tableCode / preferredZone / confidence"]
    FSM["TableBookingScenario FSM"]

    Pdf --> Offline --> TableMap --> DB
    GuestImage --> RuntimeVision --> Slots --> FSM
    DB --> RuntimeVision
    DB --> FSM
```

Production rule: plan recognition is reviewed once and stored as structured data. At runtime the VLM may suggest `tableCode`, `preferredZone` or `seatingPreference`, but FSM and booking services validate capacity, date/time availability and hostess confirmation.

### Russian NLU Toolchain

Чтобы не наращивать бесконечный набор `if` для русских фраз, слой понимания ввода делится на несколько маленьких adapter-ов. Каждый adapter возвращает только candidates/slots/confidence и не имеет права создавать бронь, платеж, ставку, отмену или менять FSM state напрямую.

Целевой порядок:

1. `Transport normalization` очищает Telegram/web input и STT transcript: убирает diagnostics, нормализует регистр, `ё/е`, кнопки меню и raw payload.
2. `Fast rules` обрабатывают очевидные команды: `/start`, контакт, callbacks, кнопки главного меню, явные слова `меню`, `бронь`, `отмена`.
3. `NatashaRussianNluAdapter` извлекает русские леммы, морфологию, имена, места, staff references и помогает со noisy STT через optional local Natasha HTTP service.
4. Даты, время, номера столов и количество гостей извлекаются state-aware rules внутри общего understanding layer и считаются через `BookingTimeProvider`, чтобы сохранить правило "текущей недели".
5. `DucklingRussianNluAdapter` остается archival/experimental кодом для spike-сравнения, но удален из Docker Compose runtime и не поднимается штатным стартовым скриптом.
6. `intent_examples` и `pgvector` ищут approved похожие фразы и context pack.
7. `GuestInputUnderstandingService` объединяет candidates в `UnderstoodInput`.
8. `ScenarioRouter` и FSM guard проверяют, допустим ли переход в текущем состоянии.

```mermaid
flowchart LR
    Raw["Raw input<br/>Telegram / Web / STT"]
    Clean["Transport cleaner<br/>canonical text + metadata"]
    Rules["Fast rules<br/>buttons, /start, explicit words"]
    Rules["State-aware rules<br/>date / time / number"]
    Natasha["NatashaAdapter<br/>lemmas / entities / noisy RU"]
    Examples["intent_examples<br/>approved phrases"]
    Vector["pgvector<br/>similar examples + context"]
    Merge["Understanding merge<br/>slots + candidates + confidence"]
    Router["ScenarioRouter<br/>FSM guard"]
    Scenario["FsmScenario<br/>domain service"]

    Raw --> Clean --> Rules --> Merge
    Clean --> Rules --> Merge
    Clean --> Natasha --> Merge
    Clean --> Examples --> Merge
    Clean --> Vector --> Merge
    Merge --> Router --> Scenario
```

Conflict policy:

- active FSM state wins over generic extraction: `TABLE_BOOKING_COLLECT_TIME + "8"` means time, while `TABLE_BOOKING_WAIT_TABLE_SELECTION + "7"` means table selection;
- side-effect scenarios win over content guesses: "тихий стол в винной комнате" is a seating preference, not a wine-menu request;
- golden corpus and approved examples win over a single external tool;
- low confidence leads to one useful clarification, then recovery/admin handoff;
- all misses go to `intent_understanding_misses` for human review before becoming new knowledge.

Runtime подключение:

- `docker-compose.yml` содержит profile `nlu` только с сервисом `natasha-nlu`.
- Duckling удален из Docker Compose runtime. Кодовый adapter остается выключенным для отдельного spike/feature flag, если мы позже докажем пользу на golden corpus.
- Если рабочую машину нужно разгрузить, можно запустить со старым rules-only поведением: `ASTOR_NLU_STACK_ENABLED=false scripts/start_container_stack.sh`.
- Для локального Spring Boot из IDEA можно поднять только profile `nlu`, а в `.env` указать `ASTOR_NLU_NATASHA_URL=http://localhost:8011/analyze`.

Table booking time policy:

- все вычисления даты/времени брони идут через `BookingTimeProvider`, а не через прямые `LocalDate.now()` / `LocalTime.now()` внутри сценария;
- canonical venue timezone: `Asia/Yekaterinburg`;
- `TABLE_BOOKING_COLLECT_DATE` отдает reply-кнопки на 21 день от сегодняшнего дня и одновременно принимает свободный текст;
- русские weekdays интерпретируются относительно текущей недели заведения: "на пятницу" означает ближайшую пятницу, включая сегодня;
- `TABLE_BOOKING_COLLECT_TIME` отдает reply-кнопки времени с шагом 1 час: для сегодняшней даты от ближайшего целого часа, для будущей даты от 12:00;
- order window по умолчанию 2 часа, а в staff/admin projection дата и время показываются отдельно (`Дата`, `Время`), чтобы не маскировать midnight fallback.

### Semantic Router and Scenario Graph Memory

Целевой слой между `MessageGatewayService` и конкретными сценариями - `SemanticRouter`.
Он нужен не для того, чтобы LLM "говорила вместо бота", а чтобы единообразно понимать текст, голос, будущий REST/web input и позицию гостя в графе сценариев.

```mermaid
flowchart TD
    TG["Telegram voice/text"]
    REST["REST / future web chat"]
    Normalize["Transport normalization<br/>voice/audio -> transcript<br/>strip diagnostics<br/>canonical IncomingMessage"]
    Gateway["MessageGatewayService"]
    Context["Guest Context Snapshot<br/>identity + consent + active scenario<br/>drafts + recent intents + preferences"]
    Graph["Scenario Graph Registry<br/>states, allowed transitions,<br/>capabilities, exits"]
    NLU["GuestInputUnderstanding<br/>rules + Natasha<br/>examples + pgvector"]
    Semantic["SemanticRouter<br/>FSM-aware intent plan"]
    Decision["SemanticDecision<br/>intent, confidence, entities,<br/>target scenario, clarification"]
    FSM["FSM Scenario Router<br/>single source of truth"]
    Domain["Domain Services<br/>booking, media, content, timeline"]
    Outbox["Outbox + Kafka + admin projection"]
    Reply["OutgoingMessage<br/>guest reply + media + admin alert"]

    TG --> Normalize
    REST --> Normalize
    Normalize --> Gateway
    Gateway --> Context
    Gateway --> Graph
    Gateway --> NLU
    Context --> NLU
    NLU --> Semantic
    Graph --> Semantic
    Semantic --> Decision
    Decision --> FSM
    FSM --> Domain
    Domain --> Outbox
    FSM --> Reply
    Reply --> Outbox
```

Контракт:

- fast path сначала rule-based: `/start`, контакт, кнопки, явные слова "меню", "бронь", "отмена";
- LLM подключается для неоднозначного текста, голосовых транскриптов и извлечения сущностей;
- `SemanticDecision` не меняет БД и не подтверждает бизнес-действия;
- FSM проверяет допустимость перехода по текущему состоянию и графу возможностей;
- активный runtime-state остается один, но рядом хранится `Guest Context Snapshot`: последние intent, открытые drafts, preference facts, recent failures;
- если confidence низкий, сценарий задает один человеческий уточняющий вопрос, а не сразу падает в admin fallback.

### Semantic Data Stack

Astor Butler uses three different memory layers because they answer different questions:

| Layer | Technology | Question | Source of truth |
| --- | --- | --- | --- |
| Semantic memory | PostgreSQL + `pgvector` | "What does this guest probably mean, and which content should the LLM read?" | durable RAG sources/chunks/embeddings |
| Hot runtime state | Redis | "Where is the guest right now?" | short-lived operational state, recoverable |
| FSM timeline | ScyllaDB/Cassandra API | "How did the guest get here, what happened before, what may happen next?" | append-only transition history |
| Scenario graph | Neo4j | "Which states, scenarios, capabilities and transitions are connected?" | graph projection of FSM/spec, not OLTP facts |

Rules:

- LLM never writes business state directly.
- `SemanticRouter` may read pgvector/Neo4j/timeline context and produce `SemanticDecision`.
- FSM validates transitions and writes the current state to Redis.
- Every handled guest message is appended to Kafka and, when enabled, Scylla timeline.
- Neo4j starts with the static scenario graph; guest-specific preference graph is a later extension.
- PostgreSQL stays the transactional source for users, bookings, media catalog and guides.

## Domain Modules

- `Auth` - authorization language, permissions, JWT claims mapping, access decisions. Auth is separate from User.
- `User` - профиль, роли, идентичность, связки Telegram/web.
- `Booking` - заявки, мероприятия, статусы, менеджерская обработка.
- `Content` - посты, афиши, promo blocks, SEO metadata, draft/published/archived state.
- `Media` - metadata и связи с S3 objects.
- `Timeline` - действия пользователя, менеджера, FSM transitions, domain events.
- `Manager` - dashboard, tasks, escalation workflow.
- `Notifications` - Telegram/CRM/email/event notifications.

Доменные модули не содержат Telegram-логики и не должны напрямую зависеть от UI.

## Capability Extensions

Capability modules are product-level extension points above the core domains. MVP creates package boundaries and integration contracts, but does not implement full business logic for every capability in the first iteration.

| Pain axis | Capability module | MVP responsibility | Depends on |
| --- | --- | --- | --- |
| Identity | `Memory Engine` | recognize returning guests by phone, profile and preference history | User, Booking, Timeline |
| Personalization | `Preference Map` | prepare "as last time" suggestions | User, Booking, Content, Timeline |
| Gratitude | `Smart Tip` | digital tips and gratitude scenario tracking | User, Booking, Timeline, Notifications |
| Information support | `Quiet Guide` | menus, posters and useful info without spam | Content, Media, Redis |
| Social impact | `Hidden Heart` | anonymous donation extension point | User, Timeline, Notifications |
| Game experience | `Safe Play` | mini-scenarios with immediate exit | FSM, Timeline, Panic Exit |
| Time management | `Slot Keeper` | slot reminders and time-window coordination | Booking, Timeline, Notifications |
| Safety | `Panic Exit` | immediate scenario termination and safe state recovery | FSM, Redis, Timeline |

Reserved external blocks:

- `Direct Channel Hub` - direct API boundary for guest-to-PMS interaction.
- `Arena Reboot Engine` - hotel-to-stadium scenarios.
- `Consent Vault` - consent storage/export for GDPR, PDPA, PIPL and 152-FZ. It is reserved immediately in MVP because `/start`, contact capture and user profiling require policy acceptance and future export/revoke flows.
- `Impact Meter` - cultural KPI and reporting boundary.

Capability modules must communicate through application services/FSM and domain events. They do not bypass auth, idempotency guard or persistence constraints.

## Package Map

High-level diagram выше является context map. Для разработки используется package map:

### API boundary packages

- `api.auth` - OAuth2/OIDC, JWT, login/logout/current principal endpoints.
- `api.consent` - policy version, consent grant/revoke/export contracts.
- `api.user` - user profiles, roles, lookup.
- `api.fsm` - FSM events, normalized message gateway, state read model and safe reset.
- `api.booking` - booking requests, statuses, manager notes.
- `api.timeline` - user, booking, manager and system timelines.
- `api.content` - posts, afisha, promo blocks, SEO content.
- `api.media` - upload, media metadata, file links.
- `api.manager` - dashboard, tasks, escalation workflow.
- `api.notification` - notification read model and test commands.
- `api.integration` - Gmail, CRM, analytics and external integrations.
- `api.observability` - internal health/readiness/liveness/metrics boundary.

### Domain packages

- `domain.auth` - permissions, role mapping and access decisions.
- `domain.user` - user profile and user business data.
- `domain.booking` - first production domain after user/auth skeleton.
- `domain.content` - posts, afisha and promo content.
- `domain.media` - S3 metadata and file-to-entity links.
- `domain.timeline` - immutable events and user/manager history.
- `domain.manager` - dashboard and operational workflows.
- `domain.notification` - notification commands and delivery state.
- `domain.document` - internal documents, MongoDB metadata and parsing state.

### Service packages

- `service` - application service layer. Controllers stay thin and call services.
- `service.grpc` - future gRPC boundary implementations.

### Capability packages

- `capability.memory` - Memory Engine.
- `capability.preference` - Preference Map.
- `capability.smarttip` - Smart Tip.
- `capability.quietguide` - Quiet Guide.
- `capability.hiddenheart` - Hidden Heart.
- `capability.safeplay` - Safe Play.
- `capability.slotkeeper` - Slot Keeper.
- `capability.panicexit` - Panic Exit.
- `capability.directchannel` - Direct Channel Hub extension point.
- `capability.arenareboot` - Arena Reboot Engine extension point.
- `capability.consent` - Consent Vault extension point.
- `capability.impact` - Impact Meter extension point.

These packages are intentionally thin at MVP start. They give the team stable places for future scenario logic, contracts, metrics and domain-event handlers without polluting CRUD/API packages.

## Data Layer

### PostgreSQL

PostgreSQL - основная СУБД для:

- пользователей;
- ролей;
- заявок;
- статусов;
- таймлайнов;
- постов;
- лидов;
- SEO metadata;
- связей между сущностями.
- semantic/RAG source registry and chunks through `pgvector`.

Persistence strategy: JDBC without JPA/Hibernate. Причина: явный контроль SQL, транзакций, индексов и performance-critical запросов. Миграции схемы - Liquibase.

Identity model описан отдельно в [DATABASE_MODEL.md](DATABASE_MODEL.md). Базовое правило: `users` - внутренняя личность Astor Butler, а `telegram_profiles` - внешний Telegram-аккаунт. Новые домены (`Booking`, `Timeline`, `Notifications`, `Consent Vault`) должны ссылаться на `users.id`, а не на `telegram_user_id`.

Transition note: в коде еще есть legacy JPA-слой `UserRepository/UserProfileService` и совместимые колонки `users.telegram_id`, `users.first_name`, `users.phone`. Новая логика первого касания работает через identity boundary и нормализованные связи `user_id`.

PostgreSQL design rules:

- primary/master принимает write traffic;
- read replicas/slaves используются для read-heavy аналитики и dashboard-запросов;
- таблицы получают понятные имена в предметной области;
- связи `one-to-many`, `many-to-one` и `many-to-many` выражаются явными foreign keys и join tables;
- constraints защищают базу от невалидного состояния;
- индексы создаются по селективным полям и реальным query patterns;
- аналитические запросы не должны ломать OLTP-контур;
- схема мигрируется через Liquibase changesets.

#### pgvector Semantic Memory

`pgvector` is the first production-ready semantic layer for Astor Butler. It keeps retrieval close to existing PostgreSQL data and avoids adding a separate vector database before the RAG workflow is proven.

Tables:

- `semantic_sources` - durable source registry: menu PDFs, guides, FSM docs, future knowledge files.
- `semantic_chunks` - normalized chunks ready for retrieval.
- `semantic_embeddings` - vector embeddings for chunks.

First use cases:

- Quiet Guide menu search;
- Safe Play wine advice: "какое игристое под сабраж" retrieves `AERIS_MENU_WINE_SOURCE` and `AERIS_SAFE_PLAY_SOURCE`, returns positions/prices, and keeps the dangerous-how-to boundary.
- guest/staff instruction retrieval;
- FSM/spec-aware semantic routing;
- future "why did the bot route this request here?" diagnostics.

External vector databases such as Qdrant, Weaviate, Milvus or Pinecone are not part of the AERIS production plan. Scenario code depends on `SemanticMemoryRepository`, but the approved production store is the current PostgreSQL with `pgvector`, not a separate vector service.

### MongoDB

MongoDB выделяется отдельно для внутренних документов и гибких document-like данных:

- загруженные внутренние документы;
- metadata документов;
- промежуточные распознавания/парсинг файлов;
- обезличенные примеры материалов;
- гибкие структуры, которые не должны раздувать PostgreSQL schema.

MongoDB не заменяет PostgreSQL как основную СУБД. Связь с бизнес-сущностями хранится через stable IDs и metadata references.

### Redis

Redis используется для:

- FSM hot context;
- idempotency guard;
- processed event IDs;
- краткоживущих booking drafts;
- краткосрочных очередей;
- semantic response cache для популярных вопросов и проверенных LLM-ответов;
- кеша меню, справочников и публичного контента;
- feature flags/cache для landing blocks.

Текущая TTL-стратегия MVP:

- `astor:fsm:telegram:{chatId}:state` - FSM state, TTL `3` дня (`FSM_REDIS_TTL_SECONDS=259200`);
- `astor:idem:telegram:{eventId}` - idempotency guard, TTL `24` часа (`IDEMPOTENCY_REDIS_TTL_SECONDS=86400`);
- будущие booking drafts - `3` дня;
- будущие menu/reference caches - `6-12` часов;
- future rate limit keys - секунды/минуты.
- future semantic response cache keys - от минут до дней, в зависимости от freshness контента и human rating.

Redis не является единственным источником правды. Важные факты пишутся в PostgreSQL и Kafka: user/profile/consent/messages, timeline/domain events and LLM responses. Если Redis потерян или ключ истек, backend должен восстановить safe state из PostgreSQL/timeline и продолжить сценарий без привязки к конкретному Java instance.

### ScyllaDB / Cassandra-Compatible Timeline

ScyllaDB is used locally as a Cassandra-compatible wide-column store for append-only FSM/user timelines.

Purpose:

- store every guest transition by guest/time;
- keep high-write history separate from Redis hot keys;
- support weekend analytics and future prediction features;
- make "where was this guest before fallback?" answerable without scanning Kafka manually.

Current table:

- keyspace: `astor_timeline`;
- table: `guest_fsm_timeline_by_guest`;
- partition: `guest_id`;
- clustering: `occurred_at DESC`, `event_id`.

Important boundary: Scylla/Cassandra does not replace PostgreSQL for relational facts, bookings, users or media. It is optimized for one primary query pattern: "show me this guest's timeline".

### Neo4j Scenario Graph

Neo4j stores the graph projection of Astor Butler's scenario model.

First graph:

- `(:FsmState)` nodes for every `BotState`;
- `(:Scenario)` nodes such as `FIRST_TOUCH`, `MAIN_MENU`, `MENU_ASSETS`, `QUIET_GUIDE`, `TABLE_BOOKING`;
- `(:Capability)` nodes such as `Quiet Guide`, `Slot Keeper`, `Hidden Heart`;
- `(:Scenario)-[:OWNS_STATE]->(:FsmState)`;
- `(:FsmState)-[:CAN_TRANSITION_TO]->(:FsmState)`.

Why this is useful:

- visual thinking for FSM design;
- scenario impact analysis before code changes;
- later preference graph: guest -> interests -> content -> reservation -> feedback;
- recommendations and explanations ("I suggested wine card because guest asked for Mediterranean dinner and wine").

Neo4j is a graph read/projection layer. It must not become the only source of operational truth.

### S3-Compatible Object Storage

Object Storage используется для:

- фото;
- видео;
- меню;
- документов;
- приложений;
- медиа постов;
- презентаций и PDF;
- derivative assets для promo frontend.

### Kafka / RabbitMQ / Artemis

Event bus используется для:

- audit events;
- notification commands/events;
- analytics events;
- timeline enrichment;
- lead events;
- achievement events;
- интеграций с CRM и внешними системами.

Draft topics:

- `astor.booking.events`;
- `astor.user.events`;
- `astor.timeline.events`;
- `astor.media.events`;
- `astor.lead.events`;
- `astor.notification.commands`;
- `astor.notification.events`;
- `astor.audit.events`;
- `astor.analytics.events`;
- `astor.achievement.events`.

Финальный набор топиков и партиционирование уточняются после нагрузочного тестирования.

## Security

- API Gateway перед backend: TLS termination, routing, rate limiting, payload limits.
- Keycloak как OAuth2/OIDC provider.
- JWT Stateless authentication.
- Spring Security как OAuth2 Resource Server.
- Роли и permissions через JWT claims/scopes.
- Backend не хранит пользовательские web-сессии.
- UI скрывает недоступные действия, backend остается финальной точкой проверки прав.
- Admin actions пишутся в audit/timeline.
- Секреты не хранятся в git. `.env` остается только локально.

## Observability

Observability stack:

- Prometheus для метрик;
- Grafana для dashboard views;
- ELK для централизованных логов;
- Jaeger/OpenTelemetry для tracing.

Минимум 6 Grafana dashboard views:

- API;
- JVM;
- PostgreSQL;
- Redis;
- Kafka;
- business/FSM.

Отслеживаемые SLI:

- availability;
- error rate;
- P50/P95/P99 latency по REST;
- P50/P95/P99 latency по gRPC;
- Kafka consumer lag;
- Redis hit ratio;
- PostgreSQL query latency;
- S3 upload/download errors;
- lead conversion events;
- FSM transition failures.

Целевой availability SLO после production-выхода - 99.9% uptime в год. Конкретные latency thresholds фиксируются после нагрузочного тестирования.

## CI/CD And Quality Gates

CI/CD:

- GitHub Actions;
- TeamCity/Jenkins as optional enterprise CI;
- Nexus/Container Registry для артефактов и Docker images;
- Docker для контейнеризации;
- Docker Compose для локального запуска;
- Kubernetes/OpenShift для production-ready deployment.

Quality gates:

- JUnit;
- Mockito;
- Testcontainers;
- JaCoCo;
- Checkstyle;
- PMD or SpotBugs;
- OpenAPI contract checks;
- integration tests for PostgreSQL, Redis, API contracts and Kafka flows.

Цель - максимальное покрытие business-critical кода. Формальный процент coverage утверждается после выделения слоев, где покрытие действительно отражает качество, а не декоративную метрику.

## Runtime Configuration And Local Environment

Runtime configuration:

- one `application.yaml` is the only Spring Boot YAML config for local development;
- `.env` is the only local source for credentials, ports and service endpoints;
- `.env` is ignored by git and stays only on the developer machine.

Config files:

- `application.yaml` - reads local values from environment variables;
- `.env` - local developer credentials and endpoints, not committed.

Local Docker Compose поднимает:

- PostgreSQL;
- Redis;
- MongoDB;
- Kafka-compatible broker for local tests;
- Redpanda Console for Kafka topics, partitions, messages and consumer groups;
- MinIO S3-compatible object storage;
- Prometheus;
- Grafana;
- опционально Ollama/local LLM через `--profile ai`.

The Spring Boot application is intentionally started locally from IDE or Maven, not inside Docker Compose. This keeps Swagger/API development fast and lets the backend connect to Dockerized dependencies through `localhost`.

Local dependency stack:

```bash
docker compose up -d
```

Local backend startup:

```bash
scripts/run_local_app.sh
```

After local backend startup:

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`;
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`;
- metrics: `http://localhost:8080/actuator/prometheus`.

Ollama is intentionally excluded from the default local infrastructure profile. Heavy local models can take tens of gigabytes and should not block PostgreSQL, Redis, MongoDB, Kafka, MinIO, Prometheus or Grafana startup.

When the `ai` profile is enabled, Docker Compose starts two Ollama containers and one `llm-gateway`:

- `astor_ollama_1` and `astor_ollama_2` are pooled behind `llm-gateway`; guest-facing code uses the `FRONTLINE` profile (`qwen2.5:1.5b`) unless a request explicitly asks for `QUALITY`;
- `QUALITY` (`qwen2.5:3b`) is reserved for learner/shadow experiments, prompt review and heavier analysis, and must not block FSM transitions;
- `VISION` defaults to `qwen2.5vl:3b` and is loaded only when the local machine is expected to run image understanding; it is a supporting input layer for candidates, not a source of booking decisions;
- `astor_llm_gateway` routes app traffic only to the two configured instances.

Реальный `.env` не коммитится и остается единственным местом для локальных кредов.

## Sequence Diagram

```mermaid
sequenceDiagram
    actor Guest as Guest / Telegram
    actor Manager as Manager Web UI
    participant Gateway as API Gateway
    participant Keycloak as Keycloak
    participant Tg as Telegram Adapter
    participant Rest as REST API
    participant Idem as Idempotency Guard
    participant FSM as FSM Core
    participant AI as AI Adapter
    participant Services as Service Layer / gRPC Boundary
    participant Booking as Booking Domain
    participant DB as PostgreSQL
    participant Redis as Redis
    participant S3 as S3 Storage
    participant Bus as Kafka / MQ

    Guest->>Tg: message / callback
    Tg->>Gateway: normalized transport event
    Manager->>Gateway: REST request with JWT
    Gateway->>Keycloak: validate token and roles
    Keycloak-->>Gateway: claims / scopes
    Gateway->>Rest: authorized request
    Rest->>Idem: normalize command/event
    Idem->>Redis: check duplicate eventId
    Redis-->>Idem: not processed
    Idem->>FSM: route event
    FSM->>AI: intent parsing / entity extraction
    AI-->>FSM: intent and entities
    FSM->>Redis: load current state and draft
    FSM->>Services: call BookingService
    Services->>Booking: update booking draft/request
    Booking->>DB: persist booking and status
    Booking->>S3: attach media if exists
    Booking->>Bus: publish BookingReadyForManager
    FSM->>Redis: save next state
    FSM-->>Rest: response DTO
    Rest-->>Gateway: HTTP response
    Gateway-->>Manager: dashboard update
    Tg-->>Guest: Telegram response
```

## Current Implementation Status

Текущий код в `main` частично отстает от целевой архитектуры, но первый Telegram/FSM vertical slice уже выделен:

- Telegram `Update -> TelegramRouter -> MessageGatewayService` является первым видимым guest-facing путем.
- `MessageGatewayService` нормализует Telegram/web сообщения, управляет первичным `/start`, contact capture, AI response и fallback-to-admin.
- `POST /api/messages` exposes the same message gateway for future web chat and messenger adapters.
- `InboundEvent` и `IdempotencyGuard` уже есть.
- Duplicate text/contact/command events останавливаются в `TelegramRouter` до gateway route.
- `IdempotencyService` пока in-memory; целевое состояние - Redis-backed guard.
- Legacy `FSMRouter` и отдельные handlers остаются в коде как старый слой, который нужно постепенно слить с message gateway/state-machine contracts.
- AI Adapter как контракт пока не реализован полноценно: есть `AIInterpreter`, `OllamaClient` и старые Alisa-классы, но нет единого production adapter для intent/entity extraction.
- `Consent Vault API` добавлен как Swagger-visible stub for policy, grant, revoke and export flows.
- `pom.xml` пока содержит JPA/Hibernate/Actuator dependencies; целевое состояние - JDBC without JPA/Hibernate и Prometheus/observability stack.
- Keycloak/JWT Stateless пока не подключен в коде: `SecurityConfig` временно разрешает большую часть запросов.
- Booking domain в `main` еще не вынесен как первый production-домен; его нужно строить после User/service/data layer.
