# Next Chat Handoff

## Update 2026-07-22 - Yandex VM CI/CD Prep + Astor DDD Review Pack

- Созданы рабочие ветки:
  - Astor Butler: `codex/yandex-vm-cicd`;
  - rpm-ddd: `codex/astor-ddd-agent-rules`.
- В `rpm-ddd` добавлен Astor Butler DDD agent playbook:
  - implementer/admin writes;
  - DDD reviewer challenges FSM/domain/AI boundary;
  - RF marketer reviews Russian HoReCa offer;
  - US marketer reviews international SaaS positioning.
- В Astor Butler добавлен первый CI/CD контур:
  - `.github/workflows/ci.yml` runs Maven tests/package + Docker build;
  - `.github/workflows/deploy-yandex-vm.yml` manual SSH/rsync deploy to prepared VM;
  - `docker-compose.prod.yml` production override for VM runtime.
- Добавлен runbook: `docs/operations/YANDEX_VM_DEPLOYMENT.md`.
- Compose validation found and fixed duplicate Yandex env keys in `docker-compose.yml` for `c3flex-astor-butler-bot`.
- Yandex Cloud console/billing/VM creation remains manual until a checked `yc` service account, budget guard and secrets policy are available.
- Local Maven caveat: default shell Java is 21 and fails with `release version 25 not supported`; use:
  - `JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/loom-ea-25-loom+1-11/Contents/Home mvn -B test`.

## Update 2026-07-09 - Java 25 / Spring Boot 4 Migration

- Перед миграцией текущие изменения были закоммичены и запушены в `main`:
  - commit `2a3c467` - `Prepare Astor Assist site and Yandex AI gateway`.
- Для миграции создана ветка `codex/java25-boot4-upgrade`.
- Backend baseline обновлен:
  - Java `21` -> `25`;
  - Spring Boot `3.2.6` -> `4.1.0`;
  - Spring AI `1.0.0-M6` -> `2.0.0`;
  - Docker build/runtime images -> Eclipse Temurin 25.
- Boot 4 API updates:
  - `RestTemplateBuilder` переехал в `org.springframework.boot.restclient`;
  - timeout methods теперь `connectTimeout(...)` и `readTimeout(...)`.
- Spring AI 2 Ollama updates:
  - используется `spring-ai-starter-model-ollama`;
  - `OllamaOptions` заменен на `OllamaChatOptions` и `OllamaEmbeddingOptions`;
  - `OllamaApi` создается через builder.
- Проверка green:
  - `docker run --rm -v "$PWD":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace maven:3.9-eclipse-temurin-25 mvn -q -DskipTests compile`;
  - `docker run --rm -v "$PWD":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace maven:3.9-eclipse-temurin-25 mvn -q test`.
- Known warnings:
  - Lombok вызывает JDK warning про deprecated `sun.misc.Unsafe`;
  - Mockito inline mock-maker self-attach предупреждает, что в будущих JDK лучше подключить Mockito как javaagent;
  - `GenericJackson2JsonRedisSerializer` помечен deprecated/for removal.

## Update 2026-07-08 - Yandex AI Studio Gateway Prep

- Подготовлен cloud-provider для realtime LLM-understanding:
  - `src/main/java/museon_online/astor_butler/model/YandexModelGateway.java`;
  - включается через `ASTOR_MODEL_PROVIDER=yandex`;
  - использует Yandex AI Studio Completion API `/foundationModels/v1/completion`.
- Env для live-запуска:
  - `YANDEX_FOLDER_ID`;
  - `YANDEX_API_KEY` или `YANDEX_IAM_TOKEN`;
  - `YANDEX_MODEL=yandexgpt-5-lite`;
  - `YANDEX_QUALITY_MODEL=yandexgpt-5.1`;
  - `YANDEX_TIMEOUT_MS=8000`.
- `intent-slots-json` requests автоматически отправляются с `jsonObject=true`, чтобы YandexGPT возвращал валидный JSON для `LlmUnderstandingService`.
- Embeddings/vision через Yandex пока не реализованы: provider возвращает safe fallback для этих capability, чтобы приложение не падало при text-only пилоте.
- Добавлен probe для первого живого замера без запуска Spring:
  - `scripts/probe_yandex_understanding.mjs`;
  - пример запуска: `YANDEX_FOLDER_ID=... YANDEX_API_KEY=... node scripts/probe_yandex_understanding.mjs`.
- Focused provider tests green:
  - `mvn -q -Dtest=ModelGatewayProviderTest,YandexModelGatewayTest test`.
- Следующий шаг после звонка/получения ключей: выполнить probe, посмотреть latency/usage/JSON, затем включить `ASTOR_UNDERSTANDING_LLM_ENABLED=true` и `ASTOR_MODEL_PROVIDER=yandex` на AERIS runtime.

## Update 2026-07-08 - Table Booking Understanding Corpus + Runtime Check

- Добавлен target corpus для table booking understanding:
  - `src/test/resources/understanding/table-booking-target-corpus.jsonl`;
  - 30 живых фраз: 14 текущих baseline-pass и 16 `KNOWN_GAP`;
  - `KNOWN_GAP` фиксирует желаемое поведение будущего LLM-understanding слоя, но не ломает текущую сборку.
- `GuestInputUnderstandingServiceTest` теперь читает этот corpus и проверяет:
  - `PASS` cases должны распознаваться текущим rule-based/Natasha/fallback пониманием;
  - `KNOWN_GAP` cases документируются как целевые сценарии для следующего слоя.
- Focused tests green:
  - `mvn -Dtest=GuestInputUnderstandingServiceTest test`;
  - result: `13` tests, `0` failures, `BUILD SUCCESS`;
  - `mvn -Dtest=GuestInputUnderstandingServiceTest,FirstTouchScenarioTest,TableBookingScenarioTest,ChangeCancelScenarioTest,MessageGatewayServiceTest test`;
  - result: `67` tests, `0` failures, `BUILD SUCCESS`.
- AERIS runtime rebuilt and restarted:
  - `docker compose --profile telegram up -d --build aeris-astor-butler-bot`;
  - image built: `astor-butler-mvp:local`;
  - container: `aeris_astor_butler_bot`;
  - health: `http://localhost:8089/actuator/health` returns `UP`.
- Runtime caveat: `LLM Warm-up disabled`; `llm-gateway:11434` is not available in this profile, so semantic embeddings/RAG bootstrap were skipped with warnings. Core FSM/Telegram runtime is up.
- Следующий шаг: подключить LLM-understanding в shadow/enrich mode на локальном стенде, прогнать тот же corpus и сравнить `current rule result` vs `LLM JSON result` before FSM.

## Update 2026-07-08 - LLM Understanding First Slice

- Начат переход от rule-based-only понимания к LLM-assisted understanding без ломки текущего FSM:
  - добавлен `LlmUnderstandingService`;
  - добавлен `LlmUnderstandingResult`;
  - `GuestInputUnderstandingService` теперь может, при включенном `ASTOR_UNDERSTANDING_LLM_ENABLED`/`astor.understanding.llm.enabled`, принять structured JSON от LLM и обогатить intent/slots;
  - по умолчанию слой выключен, текущие regex/rules/Natasha/pgvector остаются рабочим fallback.
- Новый LLM contract:
  - LLM возвращает только JSON;
  - поля: `intent`, `confidence`, `slots`, `missingSlots`, `replyDraft`;
  - FSM/domain layer остаются единственным местом для брони, отмены, hold, платежей и подтверждений.
- Добавлен `/restart` как безопасный выход из сломанного ручного теста:
  - очищает FSM state/pending intents через `fsmStorage.clear`;
  - очищает `TableBookingDraftStorage`;
  - очищает `ChangeCancelDraftStorage`;
  - для гостя с consent возвращает `READY_FOR_DIALOG` и главное меню.
- Focused tests green:
  - `mvn -Dtest=GuestInputUnderstandingServiceTest,FirstTouchScenarioTest,TableBookingScenarioTest,ChangeCancelScenarioTest,MessageGatewayServiceTest test`;
  - result: `66` tests, `0` failures, `BUILD SUCCESS`.
- Следующий шаг: вынести 30-50 живых фраз в отдельный corpus для table booking understanding, включить LLM-understanding в shadow/enrich mode на локальном стенде и сравнить rule-based vs LLM JSON before FSM.

## Update 2026-07-08 - Graduation E2E Demo Prep

- Подготовлен короткий graduation/demo package для сценария table booking voice E2E:
  - `docs/demo/ASTOR_BUTLER_GRADUATION_E2E_RU.md`;
  - `docs/demo/TABLE_BOOKING_VOICE_E2E_MANUAL_TEST_RU.md`.
- Зафиксирована честная демо-граница: STT/LLM помогают понять вход, но order/hold/confirm/change/cancel выполняют FSM и domain services.
- Проверены ключевые классы маршрута:
  - `TelegramRouter`;
  - `TelegramVoiceTranscriptionService`;
  - `MessageGatewayService`;
  - `ScenarioRouter`;
  - `GuestInputUnderstandingService`;
  - `TableBookingScenario`;
  - `TableReservationService`;
  - `TableReservationNotificationService`;
  - `HostessReservationApprovalService`;
  - `ChangeCancelScenario`.
- Focused Maven tests green:
  - `mvn -Dtest=TableBookingScenarioTest,ChangeCancelScenarioTest,ExternalCommandSpeechToTextServiceTest test`;
  - result: `30` tests, `0` failures, `BUILD SUCCESS`.
- Для живого Telegram-прогона все еще нужно проверить runtime окружение: bot token, `TELEGRAM_HOSTESS_CHAT_ID`, права бота в staff chat, `ASTOR_STT_ENABLED=true`, доступность faster-whisper/STT command и health на AERIS profile.
- Добавлен черновик сообщения организаторам Zodiaque для пилота Astor Concierge: без implementation fee, с покрытием прямых расходов и правом оформить кейс.

## Update 2026-06-27 - Spring AI ModelGateway + Embeddings/RAG Boundary

- Spring AI переведен в основной text provider за `ModelGateway`:
  - default `ASTOR_MODEL_PROVIDER=spring-ai`;
  - `SpringAiOllamaModelGateway` использует Spring AI `OllamaChatModel`;
  - raw `OllamaModelGateway` остается fallback/diagnostic режимом через `ASTOR_MODEL_PROVIDER=ollama-raw`.
- Embeddings переведены на такой же gateway-подход:
  - `ModelGateway` получил `generateEmbedding(ModelEmbeddingRequest)`;
  - добавлены `ModelEmbeddingRequest` / `ModelEmbeddingResponse`;
  - `SpringAiOllamaModelGateway` теперь держит text + embedding capability;
  - `ModelGatewayEmbeddingProvider` стал целевым provider для semantic memory.
- AERIS runtime configuration:
  - `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=model-gateway`;
  - `ASTOR_SEMANTIC_EMBEDDING_MODEL=nomic-embed-text`;
  - `ASTOR_INTENT_EXAMPLES_INGEST_ON_STARTUP=true`.
- Локальная Ollama проверка:
  - `astor_ollama_1` содержит `nomic-embed-text`, `qwen2.5:1.5b`, `qwen2.5:3b`;
  - `/api/embed` успешно отдает embedding для русской фразы "забронировать стол завтра на двоих".
- AERIS container проверен после rebuild:
  - `aeris_astor_butler_bot` healthy на `localhost:8089`;
  - startup ingest записал unique runtime examples в `intent_examples`;
  - `intent_example_embeddings` содержит `27` rows, model `nomic-embed-text`, dimension `768`;
  - live pgvector cosine search по "хочу столик завтра на двоих" возвращает top booking intents.
- VLM capability boundary начат:
  - `ModelGateway` получил `analyzeImage(ModelVisionRequest)`;
  - добавлены `ModelVisionRequest` / `ModelVisionResponse`;
  - первый local adapter идет через Ollama `/api/chat` with images;
  - default model: `LLM_OLLAMA_VISION_MODEL=qwen2.5vl:3b`;
  - это supporting input layer, не источник booking decisions.
- VLM runtime check:
  - `qwen2.5vl:3b` успешно скачан в `astor_ollama_1`;
  - cold smoke на локальной картинке пока не прошел из-за Docker memory limit: Ollama требует `10.1 GiB`, доступно `9.5 GiB`;
  - для запуска Qwen2.5-VL на ноутбуке нужно поднять Docker Desktop memory примерно до 12-14 GB или временно выбрать более легкую vision fallback-модель для dev режима.
- Focused tests green:
  - `OllamaClientTest`;
  - `ModelGatewayEmbeddingProviderTest`;
  - `ModelGatewayProviderTest`;
  - `GuestInputUnderstandingServiceTest`;
  - 10/10 focused tests passed on Java 21.
- Документация обновлена:
  - `docs/architecture/ARCHITECTURE.md`;
  - `docs/fsm/FSM_SCENARIOS.md`;
  - `docs/FSM_SCENARIOS_VIEWER.html`;
  - `04_Tech/Tech_Decisions.md`.
- Graphify updated after code changes.
- Следующий шаг: поднять Docker memory limit или выбрать dev fallback VLM, повторить smoke prompt на тестовой картинке/плане и затем проектировать `FloorPlanVisionService`: MinIO asset -> image/base64 -> `ModelGateway.analyzeImage(...)` -> table candidates -> FSM validation.

## Update 2026-06-26 - Table Booking FSM Refactor

- Начат рефактор `TableBookingScenario` по архитектурному принципу: NLU извлекает intent/slots, FSM выбирает следующий допустимый шаг, LLM/phrase layer отвечает только за формулировку текста.
- `FsmScenario` получил overload `supports/handle(..., UnderstoodInput)`, а `ScenarioRouter` теперь передает результат `GuestInputUnderstandingService` в runtime-сценарии.
- `TableBookingScenario` стал orchestration layer:
  - `TableBookingDraftMerger` собирает/мерджит draft из stored state + normalized text + NLU slots;
  - `TableBookingStepRegistry` задает порядок missing slots: table/zone -> date -> time -> party size -> seating preference -> order;
  - `BookingPhraseService` вынесен как будущая точка подключения LLM phrasing под guardrails FSM.
- Убрана основная масса regex/if parsing из `TableBookingScenario`; парсинг временно живет в merger как compatibility layer до полноценного state-aware rules + Natasha + pgvector NLU.
- Добавлен тест, что `UnderstoodInput.slots.partySize=3` не вызывает повторный вопрос "сколько гостей", а переводит сценарий к следующему FSM-шагу.
- Проверка: `TableBookingScenarioTest` green, 15/15 через Maven из IntelliJ с `JAVA_HOME=temurin-21.0.11`.
- Следующий шаг: расширить тот же state-driven refactor на phrasing/LLM contract и затем пройти ручной тест Натальей по полному сценарию table booking.

## Update 2026-06-17 - Role Chat Previews And Notion Instructions

- Обновлен контур preview/instructions для четырех рабочих Telegram-контекстов:
  - Guest chat: закрепленный AERIS preview с GitHub guest guide и Notion Knowledge Base;
  - Astor Butler Staff Chat: операционные карточки команды, брони, safe-play, service requests;
  - Astor Butler Admin Chat: fallback/recovery, manager help, feedback и ручной контроль;
  - Astor Butler System Chat: startup/FSM transitions/action tags/correlation telemetry.
- В Notion Knowledge Base обновлены/созданы role pages:
  - Guest: `https://app.notion.com/p/381a7c019f1981a3b8c2f1fc9159b469`;
  - Staff Chat: `https://app.notion.com/p/381a7c019f1981b08ca4ed4146e630e4`;
  - Admin Chat: `https://app.notion.com/p/381a7c019f1981988530d1464d567af4`;
  - System Chat: `https://app.notion.com/p/382a7c019f198148b78aef491ceee4f6`.
- Корневая Notion KB получила блок быстрых ссылок на role instructions и public guest guide.
- В код добавлен `OperationalChatPreviewNotifier`: при старте приложения публикует и pin-ит Staff/Admin/System previews, если `ASTOR_OPERATIONAL_PREVIEW_ENABLED=true`.
- Guest preview version поднят до `2026-06-17-role-previews`, чтобы `/start` заново обновил закрепленное сообщение.
- README синхронизирован с текущей реальностью role chats, pgvector/Scylla/Neo4j и Telegram UX contract.

## Update 2026-06-17 - Change/Cancel Active Orders

- После ручного прогона продолжено доведение сценариев к production-like поведению без пересборки контейнера на каждом шаге.
- `ChangeCancelScenario` больше не только эскалирует отмену/изменение в админку:
  - если гость явно пишет `отмени бронь #N`, сценарий ищет активную table reservation этого chatId, вызывает `TableReservationService.cancelByGuest`, освобождает hold и возвращает гостя в `READY_FOR_DIALOG`;
  - если гость пишет `отмени заявку #N`, сценарий ищет активную event booking заявку этого chatId, вызывает `EventBookingService.cancelByGuest`, возвращает гостя в `READY_FOR_DIALOG` и отправляет admin alert;
  - без явного номера заявки сценарий по-прежнему показывает активные брони/ивенты и просит выбрать конкретную заявку, чтобы случайно не снять не то.
- `TableReservationService.cancelByGuest` теперь уведомляет хостес, но не дублирует guest reply: guest-facing ответ идет из FSM-сценария.
- Добавлены focused tests на отмену table reservation и event booking.
- Проверки:
  - `ChangeCancelScenarioTest`, `TableReservationServiceTest` green;
  - `mvn -DskipTests compile` green on explicit Java 21;
  - `git diff --check` clean;
  - `graphify update .` выполнен.
- Контейнер не пересобирался: пользователь попросил собирать контейнер в конце сессии/среза.

## Update 2026-06-17 - Manager Help Operator Context

- `ManagerHelpScenario` усилен как явный operator handoff:
  - admin card теперь содержит последние сохраненные сообщения гостя из `telegram_messages`;
  - действие для команды сформулировано как операторский workflow: открыть диалог, проверить историю, подключиться вручную и затем внести результат в систему отдельным действием;
  - кликабельный `tg://user?id=...` link остался в карточке гостя.
- Общий доступ к последним сообщениям вынесен из booking-only класса в `domain.telegram.TelegramGuestContextRepository`.
- `TableReservationNotificationService` теперь тоже использует общий `TelegramGuestContextRepository`.
- Проверки:
  - `ManagerHelpScenarioTest`, `ChangeCancelScenarioTest`, `TableReservationServiceTest` green;
  - `mvn -DskipTests compile` green on explicit Java 21;
  - `git diff --check` clean;
  - `graphify update .` выполнен.

## Update 2026-06-17 - Feedback Admin Card Linked To DB Record

- `FeedbackScenario` уже имел persistence/API через `guest_feedback`; дополнительно усилена admin card:
  - показывает `Feedback: #id`;
  - показывает `Type`, `Sentiment`, `Priority`, `Status`;
  - действие для команды теперь просит проверить конкретную карточку feedback и закрыть запись после follow-up.
- Focused tests:
  - `FeedbackScenarioTest`, `ManagerHelpScenarioTest`, `ChangeCancelScenarioTest` green;
  - `mvn -DskipTests compile` green on explicit Java 21;
  - `git diff --check` clean;
  - `graphify update .` выполнен.

## Update 2026-06-17 - Smart Tip Draft Lifecycle

- `SmartTipScenario` доведен от draft к явному status transition:
  - создание суммы по-прежнему создает `tip_orders` в `AWAITING_GUEST_CONFIRMATION`;
  - ответ гостя `да` вызывает `TipService.confirmLatestDraft(chatId)` и переводит последний draft в `AWAITING_PAYMENT`;
  - ответ гостя `нет` вызывает `TipService.cancelLatestDraft(chatId)` и переводит draft в `CANCELLED`;
  - guest reply теперь содержит id благодарности, получателя, сумму и честную boundary: следующий слой подключит СБП-ссылку или Telegram Stars invoice.
- `TipService` получил explicit operations:
  - `confirmDraft(id)`;
  - `cancelDraft(id)`;
  - `confirmLatestDraft(chatId)`;
  - `cancelLatestDraft(chatId)`.
- `TipController` получил Swagger/API endpoints:
  - `POST /api/tips/orders/{id}/confirm`;
  - `POST /api/tips/orders/{id}/cancel`.
- Добавлен `TipControllerTest`.
- Focused checks:
  - `SmartTipScenarioTest`, `TipControllerTest` green;
  - `mvn -DskipTests compile` green on explicit Java 21;
  - `git diff --check` clean;
  - `graphify update .` выполнен.

## Update 2026-06-17 - Hidden Heart Draft Lifecycle

- `HiddenHeartScenario` доведен по тому же шаблону, что Smart Tip:
  - создание суммы создает `donation_orders` в `AWAITING_GUEST_CONFIRMATION`;
  - ответ гостя `да` вызывает `DonationService.confirmLatestDraft(chatId)` и переводит последний donation draft в `AWAITING_PAYMENT`;
  - ответ гостя `нет` вызывает `DonationService.cancelLatestDraft(chatId)` и переводит draft в `CANCELLED`;
  - guest reply сохраняет privacy boundary: в impact идет агрегированный вклад без приватных платежных данных.
- `DonationService` получил explicit operations:
  - `confirmDraft(id)`;
  - `cancelDraft(id)`;
  - `confirmLatestDraft(chatId)`;
  - `cancelLatestDraft(chatId)`.
- `DonationController` получил Swagger/API endpoints:
  - `POST /api/donations/orders/{id}/confirm`;
  - `POST /api/donations/orders/{id}/cancel`.
- Добавлен `DonationControllerTest`.
- Focused checks:
  - `HiddenHeartScenarioTest`, `DonationControllerTest`, `SmartTipScenarioTest`, `TipControllerTest` green;
  - `mvn -DskipTests compile` green on explicit Java 21;
  - `git diff --check` clean;
  - `graphify update .` выполнен.

## Update 2026-06-17 - Art Auction Bid Lifecycle

- `ArtAuctionScenario` исправлен по lifecycle:
  - новая ставка теперь создается в `AWAITING_GUEST_CONFIRMATION`, а не сразу в `AWAITING_MANAGER_VALIDATION`;
  - ответ гостя `да/ok` вызывает `ArtAuctionService.confirmLatestBidDraft(chatId)` и переводит ставку в `AWAITING_MANAGER_VALIDATION`;
  - ответ гостя `нет` вызывает `ArtAuctionService.cancelLatestBidDraft(chatId)` и переводит ставку в `CANCELLED`;
  - guest reply подчеркивает, что финальный прием ставки требует проверки active lot, min step, top-5 и event owner.
- `ArtAuctionService` получил explicit operations:
  - `confirmBidDraft(id)`;
  - `cancelBidDraft(id)`;
  - `confirmLatestBidDraft(chatId)`;
  - `cancelLatestBidDraft(chatId)`.
- `ArtAuctionController` получил Swagger/API endpoints:
  - `POST /api/auctions/bids/{id}/confirm`;
  - `POST /api/auctions/bids/{id}/cancel`.
- Добавлен `ArtAuctionControllerTest`.
- Focused checks:
  - `ArtAuctionScenarioTest`, `ArtAuctionControllerTest`, `HiddenHeartScenarioTest`, `DonationControllerTest`, `SmartTipScenarioTest`, `TipControllerTest` green;
  - `mvn -DskipTests compile` green on explicit Java 21;
  - `git diff --check` clean;
  - `graphify update .` выполнен.

## Update 2026-06-16 - Manual Test Fixes After Natalia Booking

- Ручной тест Натальей прошел до table booking confirmation, но выявил UX/ops баги.
- Исправлено в текущем рабочем дереве:
  - карточка хостес для table booking стала богаче: chat/user id, телефон из сохраненного профиля/контакта, зона/пожелание, исходный запрос и последние сохраненные сообщения гостя из `telegram_messages`;
  - телефон брони теперь автоматически подтягивается из `users.phone` / `telegram_profiles.phone_number`, даже если текущее сообщение гостя уже не содержит contact payload;
  - `SafePlayScenario` отправляет admin card с контекстом активной брони/стола/времени и inline-кнопками `Да, можно` / `Нет`;
  - Telegram callback для safe play отвечает гостю подтверждением/отказом, вместо абстрактного alert без действия;
  - добавлен `TelegramSystemNotifier`: system chat получает короткие FSM transition cards при включенном `TELEGRAM_SYSTEM_NOTIFICATIONS_ENABLED`;
  - `.env` локально получил `TELEGRAM_SYSTEM_NOTIFICATIONS_ENABLED=true`, но `.env` не должен попадать в git.
- Проверки:
  - focused tests: `SafePlayScenarioTest`, `MessageGatewayServiceTest`, `TableReservationServiceTest` green;
  - `mvn -DskipTests compile` green on explicit Java 21;
  - `git diff --check` clean;
  - `graphify update .` выполнен.
- Контейнер не пересобирался после этих правок по решению пользователя: rebuild только в конце крупного среза / перед ручным тестом.

## Update 2026-06-16 - Preference/Concierge Viewer Sync

- Завершены и запушены vertical slices:
  - `PreferenceScenario`: durable guest preference, states `PREFERENCE_COLLECT_TEXT`, `PREFERENCE_SAVED`, API `/api/preferences`, table `guest_preferences`;
  - `ConciergeScenario`: сервисная просьба команде, states `CONCIERGE_COLLECT_REQUEST`, `CONCIERGE_SENT`, API `/api/concierge/requests`, table `concierge_requests`.
- Контейнерный стенд был пересобран после новых доменов:
  - `astor_app` healthy;
  - gateway `/gateway/health` ok;
  - `/actuator/health` UP;
  - Liquibase применил `guest-preferences` и `concierge-requests`.
- Viewer и companion spec синхронизированы:
  - `docs/FSM_SCENARIOS_VIEWER.html` получил разделы `8.1 Preference` и `16.1 Concierge`;
  - `docs/fsm/FSM_SCENARIOS.md` получил полные Mermaid-описания этих сценариев;
  - registry, scenario intake matrix, main menu routes и предкодовый test matrix обновлены.
- Production readiness status:
  - стенд готов для ручного Telegram smoke;
  - код остается MVP/prod-like, но не production-ready для реального заведения до полного E2E, payment runtime, operator workflow, semantic retrieval hardening и security/privacy pass.

## Update 2026-06-16 - Table Booking Seating Preference Slice

- `TableBookingScenario` получил структурное сохранение пожеланий по посадке:
  - `preferredZone` для зон `VIP_ZONE`, `WINE_ROOM`, `BAR`;
  - `seatingPreference` для живой фразы гостя: "тихий стол", "у окна", "винная комната" и т.д.
- PostgreSQL `table_reservation_orders` расширен колонками `preferred_zone` и `seating_preference`.
- API response/request для table reservations теперь показывает эти поля.
- Карточка хостес и guest confirmation/rejection показывают пожелание по посадке.
- Focused tests по table booking / runtime / booking API / change-cancel зеленые.

## Update 2026-06-16 - Notion Knowledge Base Refresh Package Prepared

- Подготовлен полный пакет для обновления Notion Knowledge Base Astor Butler:
  - `/Users/michaelwelly/IdeaProjects/Astor_Butler_MVP/docs/public/notion-knowledge-base-refresh-2026-06-15.md`.
- Внутри файла есть:
  - Knowledge Base Audit Report;
  - готовые страницы EN/RU для разделов 01-05;
  - audit/plan для разделов 06-17;
  - список расхождений между кодом, Viewer и документацией.
- Источники сверки:
  - текущий код и Liquibase migrations;
  - `docs/FSM_SCENARIOS_VIEWER.html`;
  - `docs/architecture/ARCHITECTURE.md`;
  - `Current_Status.md`, `Backlog.md` и остальные docs/notes.
- Ключевые выводы:
  - Astor Butler нужно описывать как soft-governance system, не как обычный Telegram bot;
  - Telegram является transport/UI, FSM является single source of truth;
  - `ScenarioRouter` и `FsmScenario` уже являются текущей runtime-архитектурой;
  - AI/LLM находится вне бизнес-полномочий;
  - реальный Kafka outbox сейчас публикует `USER_MESSAGE_RECEIVED` и `LLM_RESPONSE_GENERATED`, а полный event catalog является целевой taxonomy;
  - `ImpactMeterScenario` и `ArtAuctionScenario` уже реализованы как MVP Java scenarios, хотя в запросе пользователя были перечислены как future;
  - `ConciergeScenario` пока не реализован;
  - после первичного refresh в рабочем дереве появился `PreferenceScenario` vertical slice, его нужно считать WIP до focused verification.
- После переподключения Notion app/plugin 2026-06-16 запись в Notion выполнена:
  - создан `Knowledge Base Audit Report - 2026-06-16`;
  - обновлены страницы 01-05 EN/RU;
  - дополнительно обновлен дубликат русской страницы `01 Обзор продукта`, чтобы рядом не осталось старого product overview.
- Notion page links:
  - Audit Report: `https://app.notion.com/p/380a7c019f1981b28e89f5264543f2ff`;
  - 01 Product Overview EN: `https://app.notion.com/p/380a7c019f198014974ec612d676de6e`;
  - 01 Обзор продукта RU: `https://app.notion.com/p/380a7c019f1980a3a892d6cc43a0ace5`;
  - 02 System Architecture EN: `https://app.notion.com/p/380a7c019f198091beb2d368c6bdd16d`;
  - 02 Архитектура системы RU: `https://app.notion.com/p/380a7c019f198049b73bf3f67a364ee1`;
  - 03 Domain Model EN: `https://app.notion.com/p/380a7c019f1980dd8997f29b80028b69`;
  - 03 Модель домена RU: `https://app.notion.com/p/380a7c019f1980fba0bafb3f5a0b3fc5`;
  - 04 FSM Catalog EN: `https://app.notion.com/p/380a7c019f1980c3a92df09a426f6ef0`;
  - 04 Каталог FSM RU: `https://app.notion.com/p/380a7c019f1980ffa455d5bfc101cbf4`;
  - 05 Event Catalog EN: `https://app.notion.com/p/380a7c019f19802cb119f816c2cc9bff`;
  - 05 Каталог событий RU: `https://app.notion.com/p/380a7c019f1980ae8954ea80f96c048d`.
- Продолжение 2026-06-16:
  - обновлен `06 API Catalog`;
  - созданы страницы `07 AI Governance` - `17 Glossary`;
  - корневая страница Notion получила актуальный `Knowledge Base Refresh Index - 2026-06-16`;
  - `04 FSM Catalog`, `04 Каталог FSM` и Audit Report получили addendum по Preference WIP.
- Additional Notion links:
  - root index: `https://app.notion.com/p/380a7c019f1980d78b68d8bc659c609b`;
  - 06 API Catalog: `https://app.notion.com/p/380a7c019f1980929afff596c4bdd97f`;
  - 07 AI Governance: `https://app.notion.com/p/380a7c019f19811fa299d80b403efc86`;
  - 08 Media Pipeline: `https://app.notion.com/p/380a7c019f1981c79c83d0e5e7723ce6`;
  - 09 Runtime Components: `https://app.notion.com/p/380a7c019f1981c78f8efbee7ebf8fe7`;
  - 10 Development Guide: `https://app.notion.com/p/380a7c019f1981c49de5df064b507fc6`;
  - 11 Infrastructure Guide: `https://app.notion.com/p/380a7c019f1981958609d6fc401728c0`;
  - 12 Operations Runbook: `https://app.notion.com/p/380a7c019f1981d18413cf8c959b61c0`;
  - 13 Technical Decisions (ADR): `https://app.notion.com/p/380a7c019f1981e09322c4a822db4322`;
  - 14 Current Status: `https://app.notion.com/p/380a7c019f198117ab23e889c8ce72ac`;
  - 15 Backlog: `https://app.notion.com/p/380a7c019f1981f19707e4e7bb5dcf54`;
  - 16 New Developer Guide: `https://app.notion.com/p/380a7c019f1981f1aa00c69d09cc40a1`;
  - 17 Glossary: `https://app.notion.com/p/380a7c019f198128801ef5d859af36f3`.

## Update 2026-06-15 - FSM Scenario Router Started

- Начато кодовое внедрение предкодового FSM-плана.
- Добавлен `FsmScenario` contract в `src/main/java/museon_online/astor_butler/fsm/scenario/FsmScenario.java`.
- Добавлен `ScenarioRouter` в `src/main/java/museon_online/astor_butler/fsm/scenario/ScenarioRouter.java`.
- `FirstTouchScenario`, `TableBookingScenario`, `MenuAssetsScenario`, `QuietGuideScenario`, `MainMenuScenario` теперь реализуют общий контракт.
- `MessageGatewayService` больше не выбирает конкретные доменные сценарии напрямую: он вызывает `ScenarioRouter`, а сам остается gateway для capture/logging/service-chat guard/STT retry/fallback.
- Текущая composite-intent логика перенесена в `ScenarioRouter`: safe content может выполняться вместе, table booking как side-effect scenario откладывает вторичные intents.
- Добавлен первый новый explicit-сценарий `ManagerHelpScenario`: short manager call -> ask reason; meaningful request -> admin handoff card + return to `READY_FOR_DIALOG`.
- Новые states: `MANAGER_HELP_COLLECT_REASON`, `MANAGER_HELP_SENT`.
- Добавлен `RecoveryScenario`: первый непонятный текст уточняет intent, повторный непонятный текст отправляет admin alert и возвращает гостя в `READY_FOR_DIALOG`.
- Добавлен `RecoveryRetryService` на Redis TTL counter для recovery attempts.
- Добавлен `ChangeCancelScenario`: intent изменения/отмены брони собирает дату/время/номер заявки и отправляет admin handoff без автоматического изменения holds/orders.
- Добавлен первый слой `EventBookingScenario`: банкет/корпоратив/свадьба/выкуп зала отделены от обычной посадки; короткий intent просит детали, структурированный запрос отправляет admin handoff.
- Добавлен `SmartTipScenario`: чаевые стали отдельной веткой до будущей СБП-интеграции; сейчас создается и подтверждается draft без обещания платежа.
- Добавлен `HiddenHeartScenario`: благотворительный вклад стал отдельной веткой; сейчас создается анонимный donation draft и impact event draft без раскрытия приватных платежных данных.
- Добавлен `ArtAuctionScenario`: ставка стала отдельной веткой; LLM не принимает ставку, финальный прием требует active lot/min step/event owner validation.
- Добавлен `ImpactMeterScenario`: агрегированные итоги культурного вклада стали отдельной read-only веткой без приватных платежных данных.
- Добавлен `FeedbackScenario`: отзыв/обратная связь стали отдельной веткой с admin handoff card.
- Добавлен `MerchScenario`: запросы по мерчу/сабражной цепи стали отдельной веткой с manual confirmation boundary.
- Добавлен `SafePlayScenario`: сабражный ритуал стал отдельной веткой с team confirmation и запретом dangerous how-to.
- Focused check: `JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/jbrsdk_jcef-21.0.10/Contents/Home mvn -q -Dtest=MessageGatewayServiceTest,SafePlayScenarioTest,MerchScenarioTest,FeedbackScenarioTest,ImpactMeterScenarioTest,SmartTipScenarioTest,HiddenHeartScenarioTest,ArtAuctionScenarioTest,ManagerHelpScenarioTest,RecoveryScenarioTest,ChangeCancelScenarioTest,EventBookingScenarioTest test` зеленый.
- `git diff --check` зеленый.
- `graphify update .` выполнен после изменений.
- Следующий coding step: добавлять первый новый домен как отдельный `FsmScenario` component, начиная с самого полезного для презентации сценария, не расширяя `MessageGatewayService`.

## Update 2026-06-08 - Preview, STT Diagnostics, Quiet Guide Video

- Пользователь провел ручные Telegram-тесты MenuAssets/QuietGuide и нашел UX/architecture issues:
  - persistent preview не всегда виден гостю;
  - голосовые идут долго, а технический `onnxruntime cpuid_info warning` попал в текст гостя/admin fallback;
  - видео-тур отправлялся как Telegram video и Telegram менял отображение/соотношение сторон;
  - нужен semantic layer: LLM должна помогать понимать intent/entities/stage, а FSM остается single source of truth;
  - нужна публичная инструкция гостя и инструкция команды.
- Исправлено в коде:
  - `ExternalCommandSpeechToTextService` больше не делает `redirectErrorStream(true)`: transcript берется только из stdout, stderr сохраняется как diagnostics metadata и не попадает в текст гостя;
  - `TelegramVoiceTranscriptionService` помечает доступный, но неуспешный STT как `FAILED`, а не `PENDING`;
  - `QuietGuideScenario` помечает `INTERIOR.mp4` как `videoSendMode=DOCUMENT`;
  - `TelegramRouter` при таком режиме отправляет MinIO media object через `SendDocument`, чтобы Telegram не переупаковывал видео как inline video;
  - preview version обновлен до `2026-06-08-aeris-guide`, preview text теперь ссылается на guest guide;
  - startup admin card теперь содержит ссылки на guest/staff guide.
- Добавлены публичные docs:
  - `/Users/michaelwelly/IdeaProjects/Astor_Butler_MVP/docs/guest-guide.html`;
  - `/Users/michaelwelly/IdeaProjects/Astor_Butler_MVP/docs/staff-guide.html`.
- Архитектура:
  - `docs/architecture/ARCHITECTURE.md` получил схему `SemanticRouter and Scenario Graph Memory`;
  - `docs/fsm/FSM_SCENARIOS.md` получил `Semantic FSM Loop` и минимальную модель памяти рядом с FSM.
- Graphify:
  - осмотрен `safishamsi/graphify`; релевантен для Astor как graph-memory слой по коду/docs/media;
  - полезные режимы: `graphify .`, `graphify export callflow-html`, `graphify . --obsidian`, `graphify . --wiki`, MCP;
  - пока не устанавливать в проектный git вслепую: сначала прогнать в отдельный output/tmp и сравнить с Obsidian.

## Update 2026-06-08 - Runtime Media Catalog And Start Preview

- Пользователь согласовал отказ от хранения тяжелых PDF/video в git/resources.
- Реализация:
  - добавлен PostgreSQL runtime catalog `media_assets`;
  - добавлен `AerisMediaCatalog` + `MediaAssetRepository`;
  - `MenuAssetsScenario` отправляет меню через MinIO object keys из `media_assets`;
  - `TableBookingScenario` отправляет план зала через asset `AERIS_FLOOR_PLAN`, не `classpath:booking/aeris-plan.pdf`;
  - `QuietGuideScenario` берет `AERIS_INTERIOR_TOUR` из catalog;
  - `TelegramRouter` умеет отправлять document metadata с `objectKey`;
  - PDF меню и `aeris-plan.pdf` удалены из `src/main/resources`, добавлен `.gitignore` guard;
  - `scripts/ingest_aeris_runtime_assets.sh` загружает runtime PDF/video в MinIO и upsert-ит `media_assets`;
  - `/start` для известного гостя больше не отправляет второе plain text сообщение под pinned preview: response text пустой, preview остается единственным UI-сообщением.
- Проверка:
  - `mvn test` в Docker Maven/JDK 21: 53/53 green;
  - ingest AERIS runtime assets: 6 objects uploaded to MinIO, 6 rows in `media_assets`;
  - `docker compose --profile app up -d --build app`: `astor_app` healthy;
  - REST smoke: menu request returns 4 document object keys;
  - REST smoke `/start` for known guest returns blank text + `READY_FOR_DIALOG`, so Telegram sends only preview.

## Update 2026-06-08 - Container STT Enabled

- Пользователь попросил включить STT так, чтобы голос работал всегда в контейнерном инстансе.
- Реализация:
  - `Dockerfile` runtime теперь ставит `ffmpeg`, `python3`, `python3-pip`, `faster-whisper==1.1.1`, `requests`;
  - `scripts/stt_faster_whisper.py` копируется в `/app/stt_faster_whisper.py`;
  - `docker-compose.yml` для `app` включает `ASTOR_STT_ENABLED=true`, команду `python3 /app/stt_faster_whisper.py {file}`, model `base`, language `ru`, timeout `90s`;
  - HuggingFace cache вынесен в Docker volume `huggingface-cache`, чтобы модель не скачивалась заново после пересоздания `astor_app`;
  - добавлен `VoiceTranscriptionRetryService`: Redis-счетчик неудачных voice/STT попыток на chatId, TTL 1800 секунд;
  - `MessageGatewayService` теперь: первая неудачная расшифровка просит перезаписать голосовое, вторая подряд просит написать текстом; успешный текст/не-voice сбрасывает счетчик.
- Проверка:
  - `mvn test` в Docker Maven/JDK 21: 52/52 green;
  - `docker compose --profile app up -d --build app`: `astor_app` пересобран и поднят;
  - `docker exec astor_app python3 -c "import faster_whisper"`: ok;
  - `http://localhost:8080/actuator/health`: UP;
  - STT smoke на TTS-фразе "покажи пожалуйста меню ресторана": `base` распознал как "покажи, пожалуйста, меню ресторана.";
  - REST smoke: contact -> first failed voice -> `TRANSCRIPTION_RETRY_REQUESTED`; second failed voice -> `TRANSCRIPTION_FAILED_TWICE`, `ASK_TEXT_INPUT`.
- STT model prewarm:
  - запускали silent wav внутри контейнера, чтобы `faster-whisper` скачал/инициализировал модель;
  - silent audio ожидаемо вернул blank/exit 1, это не ошибка сценария.

## Update 2026-06-08 - Status, FSM reread, Natalia reset helper

- Пользователь попросил свежий статус проекта/бэклог и повторно внимательно зайти в FSM.
- Обновлены Obsidian notes:
  - `01_Project/Current_Status.md`;
  - `01_Project/Backlog.md`.
- Добавлен локальный helper для ручных тестов Натальи:
  - `/Users/michaelwelly/IdeaProjects/Astor_Butler_MVP/scripts/reset_natalia_test_user.sh`.
- Скрипт по умолчанию целится в `telegram_id/chat_id=1773317437`, защищается от reset admin `421441838`, чистит PostgreSQL user/profile/messages/consents/table orders/holds и Redis keys:
  - `astor:fsm:telegram:1773317437:state`;
  - `astor:booking:table:draft:telegram:1773317437`.
- Проверка скрипта: `scripts/reset_natalia_test_user.sh --dry-run` показал Наталью, 26 Telegram messages, 1 consent, 1 profile/user; транзакция rollback, Redis untouched.
- Preview issue: Telegram preview считается отправленным по `telegram_profiles.preview_message_id` + `preview_version`, поэтому при повторных тестах/удаленном сообщении Наталья может не видеть preview. Reset helper сбрасывает это через удаление профиля; продуктово нужно решить, нужен ли resend preview на `/start`.
- Maven test в JDK 21 контейнере сначала упал из-за устаревшего тестового ожидания `06.06`: на дату 2026-06-08 это уже прошлое, код переносит дату на следующий год. Тест поправлен на динамическую будущую дату.
- Проверка после правки: `docker run --rm -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-21 mvn test` -> 44/44 green.
- FSM spec/viewer обновлены перед кодом:
  - `/start` = safe restart, persistent preview, route by consent;
  - voice/audio нормализуется в transport/intake до FSM;
  - `MenuAssetsScenario` = 4 PDF меню + RAG guardrails;
  - `QuietGuideScenario` = афиша/справки/видео-тур/концепция;
  - `INTERIOR.mp4` должен жить в MinIO `content/aeris/interior/INTERIOR.mp4`, не в git/jar;
  - RAG menu index должен быть shared retrieval слоем для всех локальных LLM инстансов.
- В рабочем дереве остаются незакоммиченные изменения, не смешивать без решения пользователя:
  - `.idea/*`;
  - `README.md`;
  - `docs/FSM_SCENARIOS_VIEWER.html`;
  - `TableBookingDraftStorage.java`;
  - `TableBookingScenario.java`;
  - `TableBookingScenarioTest.java`;
  - `scripts/reset_natalia_test_user.sh`.

## Update 2026-06-06 - Table Booking Date Flow Fix

- Исправлен ручной пошаговый flow бронирования стола: теперь дата, время и количество гостей накапливаются в Redis draft между сообщениями.
- Конкретный баг: `06.06` мог распознаваться как время `06:00`, а дата терялась на следующем шаге. Добавлен отдельный parser даты `dd.mm[/yyyy]`, `сегодня`, `завтра`, `послезавтра`, и более строгий parser времени.
- Добавлен unit-тест: `хочу забронировать столик` -> `06.06` -> `20:00` -> `на двоих` -> `TABLE_BOOKING_WAIT_TABLE_SELECTION`.
- Проверка: `mvn test` green, 44/44 tests.
- Контейнер `astor_app` пересобран и запущен в Docker Compose.
- REST manual smoke через `/api/messages` green: first touch/contact/date/time/party -> hall plan.
- k6 deterministic FSM baseline green: 9 scenarios, 27 HTTP requests, 81/81 checks, p95 ~384 ms.
- Старый `k6/weekend-guest-scenarios.js` не использовать как строгий регресс без доработки: он берет фиксированные chatId и follow-up ветки уходят в LLM/Ollama, где возможны локальные CPU timeouts.

## Update 2026-06-06 - Weekend Container Stand

- Рабочий контейнерный backend поднят через Docker Compose: `app` + `api-gateway`; IDEA для weekend E2E не нужна.
- LLM теперь запущен как pool: `ollama-1`, `ollama-2`, `ollama-3` за `llm-gateway`, host URL `http://localhost:11434`, app URL `http://llm-gateway:11434`.
- Исправлен REST Telegram simulation: numeric `externalUserId` мапится в `telegramUserId`.
- Исправлен first-touch: новый Telegram-гость в `UNKNOWN` получает consent/contact даже без `/start`.
- `mvn test`: 43/43 green.
- k6 weekend matrix: first-touch/contact 9/9 green, Kafka admin consumer lag 0, события доставлялись в admin chat.
- Открытый риск: свободные LLM paths под параллельной нагрузкой все еще timeout на локальной CPU Ollama; следующий продуктовый шаг - переводить menu/poster/art/donation/auction/manager follow-ups в явные FSM сценарии.
- Подробный отчет: `/Users/michaelwelly/IdeaProjects/Astor_Butler_MVP/docs/analytics/2026-06-06-weekend-container-stand.md`.

## Update 2026-06-06 - Load Test Handoff

- Добавлен deterministic k6 baseline: `/Users/michaelwelly/IdeaProjects/Astor_Butler_MVP/k6/weekend-fsm-baseline.js`.
- Fast smoke после пересборки контейнера: 9 fresh guests, 27 HTTP requests, 81/81 checks green, p95 около 288 ms.
- Paced baseline: 45 fresh guests, 135 HTTP requests, 405/405 checks green, p95 около 100 ms.
- Bursty 45 guests / 9 VUs упирается в `api-gateway limit_req` и дает 503; это ожидаемая защита gateway, не сбой FSM.
- Kafka consumer group `astor-admin-events` после финального smoke: `Stable`, `TOTAL-LAG 0`.
- Обнаружен Telegram API limit для admin chat (`429 Too Many Requests`) при массовой доставке аналитики.
- Исправлено: `TelegramAdminNotifier` теперь сериализует admin analytics delivery, держит паузу `TELEGRAM_ANALYTICS_MIN_SEND_INTERVAL_MS` по умолчанию 3200 ms и повторяет отправку по `retry_after`; `AnalyticsKafkaConsumer` помечает событие processed только после успешной доставки.
- Контейнер `astor_app` пересобран и запущен из Docker Compose; IDEA можно закрывать для ручного weekend E2E.

Дата: 2026-06-04

## Где работаем

Репозиторий:

`/Users/michaelwelly/IdeaProjects/Astor_Butler_MVP`

Ветка:

`main`

GitHub:

`https://github.com/michaelwelly/Astor_Butler_MVP`

Текущий baseline перед cleanup:

`268662c Add consent vault events and local gateway stack`

## Что важно помнить

- Astor Butler MVP - Java 21 + Spring Boot монолит.
- Telegram - первый UI/transport.
- FSM - single source of truth.
- Backend разворачивается локально через Docker infrastructure + Spring Boot из IDE.
- Frontend C3FLEX живет в отдельной папке `frontend/` и работает как отдельное направление.
- `.env` и реальные креды не коммитятся.
- Локальные `.idea/dataSources.xml` и `.idea/misc.xml` не коммитить без явного решения пользователя.

## Что актуально реализовано к текущему baseline

- Local API Gateway на `localhost:8080` перед Spring Boot dev-портом.
- Swagger/OpenAPI на `http://localhost:8080/swagger-ui/index.html`.
- Telegram bot flow до контакта/Consent Vault.
- Сохранение Telegram profile/messages/consents в PostgreSQL.
- Kafka topic для пользовательских событий.
- Analytics/admin delivery в Telegram admin chat с feature flags.
- Redis используется для FSM hot state.
- MongoDB используется как документная/metadata база `aether`.
- MinIO используется как локальный S3-compatible тестовый слой.
- Redpanda Console доступна для просмотра Kafka.

## Текущая задача cleanup

Пользователь признал неактуальными и разрешил удалить:

- `.codex/`
- `.codex_audio_chunks/`
- `.codex_frames/`
- `.codex_tmp_hr_screening.wav`
- `.codex_transcripts/`
- `.codex_whisper_chunks/`
- `.codex_whisper_out/`
- sandbox classes в `src/main/java/museon_online/astor_butler/sandbox/`

После cleanup:

1. Проверить `git status --short`.
2. Убедиться, что `.env`, `target/**`, `.codex*` не tracked.
3. Запустить `mvn test` или минимум `mvn -q -DskipTests package` на JDK 21.
4. Сделать отдельный cleanup commit.
5. Push делать только после проверки, что секреты и build artifacts не попали в diff.

## Следующий продуктовый фокус

1. Consent Vault:
   - сохранять первое касание Telegram;
   - сохранять профиль гостя;
   - сохранять каждое входящее сообщение;
   - фиксировать согласие с политикой обработки данных;
   - поддержать отзыв/экспорт согласия позже.
2. Kafka:
   - публиковать user events;
   - держать idempotency key;
   - писать consumer для analytics/admin наблюдения;
   - не спамить админ-чат, управлять этим через feature flags.
3. FSM:
   - первый сценарий после `/start`: контакт + consent + безопасный fallback;
   - дальше `EVENT_BOOKING` и `Slot Keeper`;
   - LLM не должен владеть бизнес-логикой, только помогать с текстом/intent/entity extraction.

## Проверочные адреса

- Swagger: `http://localhost:8080/swagger-ui/index.html`
- Gateway health: `http://localhost:8080/gateway/health`
- Backend health: `http://localhost:8080/actuator/health`
- Redpanda Console: `http://localhost:8081`

## Что читать следующему чату

1. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/01_Project/Project_Context.md`
2. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/01_Project/Current_Status.md`
3. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/01_Project/Backlog.md`
4. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/01_Project/NEXT_CHAT_HANDOFF.md`
5. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/01_Project/Cleanup_And_Architecture_Plan.md`
6. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/04_Tech/Code_Map.md`
7. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/04_Tech/Tech_Decisions.md`
8. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/03_FSM/FSM_Index.md`
9. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/03_FSM/FIRST_TOUCH_FSM.md`
10. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/03_FSM/FSM_Master_Plan.md`
11. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/04_Tech/Capability_Map.md`
12. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/04_Tech/API_Reality_Check.md`
13. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/02_Product/Consent_Vault.md`
14. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/04_Tech/Event_Taxonomy.md`
15. `/Users/michaelwelly/Obsidian/Astor_Butler_Knowledge/04_Tech/Local_Runbook.md`

## Update 2026-06-15 - FSM Scenario Router Cleanup

- `MainMenuScenario` больше не содержит product branches.
- Теперь `MainMenuScenario` делает только две вещи: явный показ главного меню (`меню`, `главное меню`, `/menu`) и safe exit из активного runtime-сценария (`стоп`, `назад`, `отмена`).
- Чаевые, донаты, аукцион, impact, мероприятие, изменение/отмена, менеджер, отзывы, мерч, Safe Play, меню и Quiet Guide должны жить только в отдельных `FsmScenario`.
- Sequential composite-intent сохраняет deferred safe-intents в Redis через `FSMStorage.setPendingIntents`.
- Redis key: `astor:fsm:telegram:{chatId}:pending-intents`, TTL совпадает с FSM state TTL.
- Pending safe-content после брони теперь доставляется не только внутри `ScenarioRouter`, но и после внешнего hostess callback confirmation через `TableReservationPendingIntentService`.
- Реализован MVP `AERIS Channel Ingest`: manual endpoint `/api/content/ingest/aeris-channel`, PostgreSQL `venue_content_posts/assets`, MinIO media mirroring, parser public `https://t.me/s/aeris_gastrobar`, rules classifier, QuietGuide read path для афиши/промо.
- После финальной сверки 10.1 и FSM-каркаса полный тестовый прогон зеленый:

```bash
JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/jbrsdk_jcef-21.0.10/Contents/Home mvn -q test
```

- Следующая рабочая сессия: писать глубокую бизнес-логику внутрь сценариев, начиная с ручного тестового прохода FirstTouch -> MainMenu -> TableBooking/MenuAssets/QuietGuide/AERIS Channel.
- Focused regression green:

```bash
JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/jbrsdk_jcef-21.0.10/Contents/Home mvn -q -Dtest=MainMenuScenarioTest,MessageGatewayServiceTest,SafePlayScenarioTest,MerchScenarioTest,FeedbackScenarioTest,ImpactMeterScenarioTest,SmartTipScenarioTest,HiddenHeartScenarioTest,ArtAuctionScenarioTest,ManagerHelpScenarioTest,RecoveryScenarioTest,ChangeCancelScenarioTest,EventBookingScenarioTest test
```

## Update 2026-06-15 - DB-First FSM Vertical Slice

- Пользователь выбрал DB-first подход к наполнению сценариев: сначала storage contracts, затем domain/service, затем API/Swagger, tests, container rebuild, ручной тест Натальей.
- Порядок сценариев остается: FirstTouch/MainMenu -> MenuAssets/QuietGuide -> TableBooking -> ChangeCancel -> EventBooking -> ManagerHelp/Recovery -> Feedback -> SmartTip -> HiddenHeart -> ArtAuction -> ImpactMeter -> Merch/SafePlay.
- Первый live slice сделан для `FirstTouch + MainMenu` runtime visibility.
- Добавлен `FsmRuntimeStateService`, который собирает Telegram runtime view из:
  - PostgreSQL `telegram_profiles`, `users`, `user_consents`, `telegram_messages`;
  - Redis FSM state и pending intents;
  - Redis table booking draft.
- Добавлены живые API endpoints:
  - `GET /api/fsm/telegram/{chatId}/state`;
  - `POST /api/fsm/telegram/{chatId}/reset`;
  - `PUT /api/fsm/telegram/{chatId}/state`;
  - `DELETE /api/fsm/telegram/{chatId}/state`.
- Старые `/api/fsm/users/{uuid}/...` пока оставлены как compatibility stubs до финализации внешнего user id.
- Full regression green:

```bash
JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/jbrsdk_jcef-21.0.10/Contents/Home mvn -q test
```

## Update 2026-06-15 - MenuAssets + QuietGuide Read Slice

- Добавлен `AerisContentReadService`.
- Добавлен `AerisContentController`.
- Добавлены runtime read endpoints:
  - `GET /api/content/aeris/menu-assets`;
  - `GET /api/content/aeris/quiet-guide?prompt=...`.
- `menu-assets` показывает 4 PDF: kitchen/bar/elements/wine, bucket/objectKey/publicUrl и `ragSource=media_assets:AERIS_MENU_*`.
- `quiet-guide` показывает interior video asset, approved concept copy и active AERIS channel posts с mirrored assets.
- `VenueContentRepository` получил `findAssetsByPostId`.
- Full regression green:

```bash
JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/jbrsdk_jcef-21.0.10/Contents/Home mvn -q test
```

- Docker image rebuilt and `astor_app` recreated/healthy.
- Gateway smoke:
  - `/api/content/aeris/menu-assets` -> `menuCount=4`;
  - `/api/content/aeris/quiet-guide?prompt=афиша` -> `interior=AERIS_INTERIOR_TOUR`, concept exists, `activePosts=0` in current local DB.

## Update 2026-06-15 - TableBooking Runtime Read Slice

- Добавлен DB-first read layer для обычной брони столов.
- Добавлен `TableBookingRuntimeService`, который собирает Telegram runtime view из:
  - PostgreSQL `venue_tables`, `table_reservation_orders`, `table_reservation_holds`;
  - Redis `TableBookingDraftStorage`;
  - MinIO/media catalog через `AerisMediaCatalog.floorPlan()`.
- `TableReservationRepository` получил read methods:
  - `findOrdersByChatId`;
  - `findActiveOrdersByChatId`.
- `TableReservationService` получил read methods:
  - `getReservation`;
  - `listReservationsByChatId`;
  - `listActiveReservationsByChatId`.
- Добавлены API endpoints:
  - `GET /api/bookings/table-reservations/{id}`;
  - `GET /api/bookings/table-reservations/telegram/{chatId}?limit=...`;
  - `GET /api/bookings/table-reservations/telegram/{chatId}/runtime?venueCode=AERIS`.
- Full regression green:

```bash
JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/jbrsdk_jcef-21.0.10/Contents/Home mvn -q test
```

- `graphify update .` выполнен.
- Docker image rebuilt and `astor_app` recreated/healthy.
- Gateway smoke:
  - `/actuator/health` -> `UP`;
  - `/api/bookings/table-reservations/telegram/1773317437/runtime?venueCode=AERIS` -> floor plan + seeded tables + current draft/orders view;
  - `/api/bookings/table-reservations/telegram/1773317437?limit=5` -> `[]` in current local DB.
- Commit pushed: `4137348 Add table booking runtime read API`.

## Update 2026-06-17 - Merch Draft + SafePlay Split

- Merch flow приведен к явному draft lifecycle:
  - guest request -> `merch_orders.status=AWAITING_GUEST_CONFIRMATION`;
  - guest `да` -> `MerchService.confirmLatestDraft(chatId)` -> `PENDING_TEAM` + admin alert;
  - guest `нет` -> `MerchService.cancelLatestDraft(chatId)` -> `CANCELLED`;
  - неясный ответ в `MERCH_SENT` просит выбрать `да/нет`.
- Добавлены merch API endpoints:
  - `POST /api/merch/orders/{id}/confirm`;
  - `POST /api/merch/orders/{id}/cancel`.
- SafePlay больше не перехватывает merch-покупку вида `купить/заказать сабражную цепь`; такой запрос должен идти в `MerchScenario`.
- SafePlay сохраняет ритуал/опасные how-to boundaries: `сабражный ритуал` остается в `SafePlayScenario`.
- Focused regression green:

```bash
JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home mvn -Dtest=MerchScenarioTest,SafePlayScenarioTest,MerchControllerTest test
```

- `git diff --check` clean.
- `graphify update .` выполнен.

## Update 2026-06-17 - Weekend RC / Understanding Layer

- Собран и поднят контейнерный `astor_app` без IDEA:
  - `docker compose --profile app build app`;
  - `docker compose --profile app up -d app api-gateway`;
  - `astor_app` и `astor_api_gateway` healthy.
- Smoke:
  - `http://localhost:8080/actuator/health` -> `UP`;
  - внутренний `http://127.0.0.1:8088/actuator/health` внутри `app` -> `UP`.
- Liquibase применил pgvector changeset:
  - `db/changelog/2026-06-17-intent-understanding-pgvector.yaml`;
  - `intent_examples`, `intent_example_embeddings`, `intent_understanding_misses`.
- Understanding corpus:
  - `IntentExampleBootstrap` прочитал `classpath:understanding/guest-input-golden-corpus.jsonl`;
  - в текущей БД 25 уникальных `intent_examples`;
  - embeddings provider выключен (`none`) для устойчивого ручного теста.
- Гостевое preview обновлено до weekend RC:
  - кнопки теперь основной путь для людей;
  - свободный текст/голос остается через NLU/understanding layer.
- Focused regression green:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=GuestInputUnderstandingServiceTest,IntentExampleCorpusLoaderTest,ScenarioRouterTest,TableBookingScenarioTest,MessageGatewayServiceTest test
```

- Package green:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -DskipTests package
```

- В system chat preview отправился, но pin не удался из-за прав бота:
  - нужно дать боту право закреплять сообщения в system chat, если нужен закреп.
- Next manual test:
  - reset Natalia/Sergey через `scripts/reset_manual_test_users.sh`;
  - `/start`;
  - кнопка `📅 Забронировать стол`;
  - ответы: завтра / 20:00 / 2 / выбрать стол;
  - проверка Staff Chat confirmation buttons и гостевого order message.

## Update 2026-06-17 - Concierge Managed Lifecycle

- `ConciergeScenario` оставлен как сервисная просьба команде: гость получает спокойное "передал команде", без обещания исполнения.
- Добавлен управляемый lifecycle для `concierge_requests`:
  - `PENDING_TEAM`;
  - `IN_PROGRESS`;
  - `COMPLETED`;
  - `CANCELLED`.
- `ConciergeRequestService` получил методы:
  - `markInProgress(id)`;
  - `complete(id)`;
  - `cancel(id)`.
- Финальные заявки `COMPLETED/CANCELLED` не переоткрываются автоматически; сервис вернет `409 CONFLICT`.
- Добавлены Concierge API endpoints:
  - `POST /api/concierge/requests/{id}/in-progress`;
  - `POST /api/concierge/requests/{id}/complete`;
  - `POST /api/concierge/requests/{id}/cancel`.
- Viewer/Markdown обновлены: Concierge теперь показан как объект с team/API lifecycle, а не только как admin-chat card.
- Focused regression green:

```bash
JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home mvn -Dtest=ConciergeScenarioTest,ConciergeRequestControllerTest test
```

- `git diff --check` clean.
- `graphify update .` выполнен.

## Update 2026-06-17 - Preference Soft Delete/API

- `PreferenceScenario` остается guest-provided-only memory: сохраняем только то, что гость явно попросил запомнить.
- Добавлен soft-delete lifecycle для `guest_preferences`:
  - `ACTIVE` используется сценариями как мягкая подсказка;
  - `DELETED` сохраняет аудит, но не попадает в active-list.
- `GuestPreferenceService` получил `deletePreference(id)`.
- `GuestPreferenceRepository` получил `updateStatus(id, status)`.
- Добавлен API endpoint:
  - `DELETE /api/preferences/{id}`.
- Viewer/Markdown обновлены: Preference теперь показывает active-list для сценариев и soft-delete как безопасное выключение предпочтения.
- Focused regression green:

```bash
JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home mvn -Dtest=PreferenceScenarioTest,GuestPreferenceControllerTest test
```

- `git diff --check` clean.
- `graphify update .` выполнен.

## Update 2026-07-01 - Safe Play Wine RAG + Server Handoff

- Local AI stack проверен: `ollama-1`, `ollama-2`, `llm-gateway`, embeddings через `nomic-embed-text`, размерность 768.
- `ScenarioReplyComposer` имеет short timeout guard: медленная локальная LLM больше не держит гостя минуту; fallback и model audit сохраняются.
- Booking replay прошел 7/7, заявка создается и гость возвращается в `READY_FOR_DIALOG`.
- RAG/pgvector работает: `semantic_embeddings` и `intent_embeddings` загружены, retrieval отвечает, качество ranking улучшается seed-текстами.
- Добавлен Safe Play wine advice:
  - "какое игристое/шампанское взять под сабраж" -> `SAFE_PLAY_WINE_ADVICE`;
  - поиск по `AERIS_MENU_WINE_SOURCE` + `AERIS_SAFE_PLAY_SOURCE`;
  - ответ с позициями/ценами из винной карты;
  - safety boundary: не давать dangerous how-to, ритуал делает команда AERIS.
- Операционный запрос "хочу сабраж к столу" остается staff/admin confirmation flow.
- `ScenarioRouter` теперь уважает confident `primaryIntent`: SAFE_PLAY может обогнать общий table-booking loop, если NLU уже понял намерение.
- Добавлен `src/main/resources/semantic/aeris/safe-play-sabrage-rag-seed.md`.
- Обновлены:
  - `docs/FSM_SCENARIOS_VIEWER.html`;
  - `docs/fsm/FSM_SCENARIOS.md`;
  - `docs/architecture/ARCHITECTURE.md`;
  - `docs/operations/PRODUCTION_DEPLOYMENT_PLAN.md`;
  - `docs/obsidian/04_Tech/Tech_Decisions.md`.
- Next smoke after container rebuild:

```bash
curl -sG http://localhost:8089/internal/semantic/search \
  --data-urlencode 'q=какое игристое под сабраж цена' \
  --data-urlencode 'limit=5' | jq
```

```bash
curl -s http://localhost:8089/api/messages \
  -H 'Content-Type: application/json' \
  -d '{"channel":"TELEGRAM","chatId":990846408,"telegramUserId":990846408,"text":"какое игристое взять под сабраж","firstName":"Smoke","lastName":"Guest","username":"smoke_guest","correlationId":"safe-play-rag-smoke"}' | jq
```

- Tomorrow server move:
  - use `docs/operations/PRODUCTION_DEPLOYMENT_PLAN.md` section `Windows Server Bootstrap Checklist`;
  - start without VLM if RAM is tight;
  - pull `qwen2.5vl:3b` only after baseline infra/bot are stable.

## Update 2026-07-01 - System Trace + Bot Profile Copy

- System Chat теперь должен показывать full dialog trace для каждого обработанного сообщения:
  - `#dialog_*`;
  - guest input;
  - app reply;
  - FSM state transition;
  - actions;
  - Kafka outbox status;
  - correlation id.
- Kafka/PostgreSQL outbox payload `USER_MESSAGE_RECEIVED` дополнен `outgoingText` и `dialogKey`.
- После контейнерной сборки проверить:

```bash
docker compose -p astor_butler_mvp --profile telegram up -d --build aeris-astor-butler-bot
```

1. Написать Натальей `/start` или короткий запрос.
2. Убедиться, что System Chat получил карточку `Astor Butler / system trace`.
3. Найти тот же диалог по `#dialog_telegram_1773317437`.
4. Проверить outbox/Kafka через Redpanda Console или SQL по `outbox_events`.

- Guest preview text обновлен в `TelegramRouter.previewText()`, версия поднята до `2026-07-01-system-trace-preview`.
- BotFather profile copy лежит в `docs/operations/TELEGRAM_BOT_PROFILE.md`; его нужно применить вручную через `/setabouttext` и `/setdescription`.
- Redis semantic response cache принят как следующий слой: быстрые ответы на повторяющиеся вопросы + накопление quality dataset для learner/OpenAI teacher/eval.

## Astor Glasses isolated backend preparation — 2026-10-04

Worktree `~/.codex/worktrees/fb4b/Astor_Butler_MVP`, branch `codex/glasses-voice-adapter`. Подготовлен informational adapter с pilot bearer access, strict payload/limits, bounded text ModelGateway и явным 503 для voice/vision. Не путать локальные contract tests с подтверждённым STT/e2e. Нет staff feed/evidence/actions/ACK.

Перед продолжением прочитать `docs/operations/GLASSES_ASSIST_PILOT.md`, сверить #9/работу BryxOG. Следующий P0 — STT AAC validation/readiness, безопасный process/temp lifecycle, HTTPS/scoped access и physical voice smoke после отдельного решения. Production, hardware и основной checkout не изменялись; credentials не создавались.

Локальная проверка: JDK 25 compile PASS, 25 новых glasses tests PASS, всего с выбранными gateway/STT compatibility tests 33/33 PASS. Full suite/GitHub CI не запускались. Graphify code graph обновлён.


## 2026-10-04: Astor Glass runtime и фронт для передачи Роме

Михаил разрешил Astor production/pлатные Yandex calls и координацию iPhone теста с отдельным чатом. Real cached Whisper AAC validation + YandexGPT text/voice + Qwen JPEG работают в standalone runtime без component scan/FSM/DB/Telegram startup. Public HTTPS smoke 200 для text/voice/vision, scoped bearer 12h; front `/astor/`, Butler page и WEB-only relay. Telegram proxy scoped restart восстановил getMe, основной бот не рестартовали. Maven 297 tests PASS, decoder 6 PASS. См. `docs/operations/ASTOR_GLASS_ROMA_HANDOFF.md` и runbook; физический HFP acceptance и GitHub CI/review отдельно. Приглашение 0xLaki write отправлено (пока pending); root-owned limited wrapper установлен, SSH key/отчёт destination ещё нужны. Не выдавать shared-host Docker/root или cloud keys Claude/клиенту.


## 2026-10-04: S3 documents and glasses backend hardening

Михаил подтвердил оба S3 назначения: материалы очков и документы для ответа. Отдельная ветка `codex/glasses-backend-hardening` от PR #11; фронт Ромы/ограниченный SSH остаются в PR #12. S3 bucket `astor-glasses-pilot-b1gug0tmrgmsq5pfsvhs`: private, max 1 GiB, отдельный SA, только конкретный document GET и scoped materials PUT, lifecycle materials 1 day (daily processing), без folder roles/ключей в git. Bucket ACL задаёт базовые права; policy ограничивает действия/пути. Live static-key GET/PUT PASS; material GET, doc PUT, чужой scope PUT, list, DELETE и anonymous GET 403. Учебный документ business lunch, реальное меню/назначения отсутствуют.

API: memory reply retry 32 entries/120s, payload conflict 409, cached answer survives a storage failure to retry archive, late model result after timeout not reused; silence 400 NO_SPEECH, successful STT readiness preserved. All work remains informational, outside FSM/task ACK. Full Maven package: 309 tests, 0 failures/errors/skipped; real decoder 6 PASS + cached Whisper silence PASS. Graph updated. Runtime rollout/HTTPS smoke and PR CI are separate completion signals; physical acceptance stays with chat «Проверить интеграцию ИИ с очками». Operational details: `docs/operations/GLASSES_S3_STORAGE.md`.


## 2026-10-05: фото по учебным шагам, приветствие и локальная команда

Main a4023a7 включён в codex/glasses-backend-hardening без конфликтов. Ядро поручений остаётся отдельно от informational pilot. В clients/ios-glasses перенесён reviewable iPhone companion без SDK/Pods/build/ключей. Фото: четыре шага, обязательные PLACE_SETTINGS/FINAL_CHECK, session+stage+revision, S3 receipt, ручной переход и bounded retry. Runtime v3-step-photo promoted; полный Maven 336/336; публичный synthetic AAC/STT/photo/NO_SPEECH/409/401 PASS, контекст сверён в S3. iPhone build/sign/install PASS; wear greeting/local-only wake добавлены, аппаратная поддержка ещё не подтверждена. Документ: docs/operations/GLASSES_STEP_PHOTOS.md. Telegram Тариэлю отправлен 4 октября 19:02, меню к 5 октября ожидает подтверждения; Notion пакет создан.
