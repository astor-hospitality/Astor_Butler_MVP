# Astor Butler

Telegram-дворецкий для HoReCa: гость ↔ персонал ↔ менеджер. Сценарии живут в конечном автомате (FSM) — он единственный
источник истины, а Telegram, веб-виджет, MAX и очки Astor Glass — только транспорт и UI. Модели (Yandex, Sber, локальные)
понимают речь и помогают с текстом, но не подтверждают брони, счета и поручения — это делают FSM, доменные сервисы и человек.

Первое заведение — гастробар AERIS (Екатеринбург). На той же инфраструктуре живут боты c3flex и Smart Solution
(отдельные профили compose, свои сценарии).

## Что это

| Канал | Состояние | Где описано |
| --- | --- | --- |
| Telegram (long polling) | основной рабочий транспорт: гость, staff / admin / system / analytics-чаты, карточки хостес с «Да / Нет» | [FSM_SCENARIOS.md](docs/fsm/FSM_SCENARIOS.md), [FSM viewer](docs/FSM_SCENARIOS_VIEWER.html) |
| WEB (виджет сайта) | `POST /api/astor/messages` — тот же FSM, quick replies, лимиты и retention (#118) | [WEB_CHANNEL_ADAPTER.md](docs/architecture/WEB_CHANNEL_ADAPTER.md) |
| MAX (VK) | фаза 1 за флагом `ASTOR_MAX_ENABLED`, по умолчанию выключен; живой бот не подключался (#116) | [MAX_ADAPTER_PLAN.md](docs/architecture/MAX_ADAPTER_PLAN.md) |
| Astor Glass (очки + iPhone) | информационные вопросы (`POST /api/glasses/assist`) и первое действие — поручение голосом «Задача: …» → `astor_staff_tasks` (#121) | [GLASSES_ASSIST_PILOT.md](docs/operations/GLASSES_ASSIST_PILOT.md), [GLASSES_VOICE_TASKS.md](docs/operations/GLASSES_VOICE_TASKS.md), [ADR](docs/architecture/ADR-glasses-voice-tasks.md) |
| Astor Concierge | отдельный репозиторий [Astor_Concierge](https://github.com/astor-hospitality/Astor_Concierge): гостевой ИИ-консьерж. В Butler гость приходит по deep-link `?start=lunch_aeris…` или через веб-канал, бронь — по API Butler | [CONCIERGE_BUTLER_HANDOFF_DRAFT.md](docs/contracts/CONCIERGE_BUTLER_HANDOFF_DRAFT.md), [BUSINESS_LUNCH_FIRST_TEST.md §1.1](docs/operations/BUSINESS_LUNCH_FIRST_TEST.md) |

Сценарии гостя: согласие и контакт, бронь стола с подтверждением хостес, бизнес-ланч (блюда, пожелания, пометка кухне),
счёт и оценка, чаевые, предпочтения, запросы консьержа. Интеграция с кассой Saby Presto (СБИС) — за флагами, см. ниже.

## Статус на 12.10.2026

`main` = `ed7c037` (merge #191). Всё ниже взято из документов репозитория и влитых PR; что именно выложено на ВМ,
сверяется по тегу образа и логам ([HANDOVER_2026-10-10.md §1](docs/operations/HANDOVER_2026-10-10.md)).

### Что живёт на ВМ Cloud.ru

Одна ВМ, общая с порталом Vedal; наружу смотрит только общий edge-Caddy. Адреса и секреты — в runbook и в
`.env.production` на ВМ, не здесь.

- Боты Telegram: AERIS (Butler), c3flex, Smart Solution, Консьерж — через прокси-контейнер для Telegram.
- Шлюз `astor-api-gateway` (nginx) с allow-list публичных путей; остальной `/api/*` — только с внутренним токеном
  ([PUBLIC_API_GUARD.md](docs/operations/PUBLIC_API_GUARD.md)).
- Runtime очков Astor Glass — отдельный контейнер без базы и Telegram.
- Astor Concierge — свой compose-проект.
- Сайт Astor — контейнер `astor-site`, домен astor-ai.ru ([infra/astor-site/](infra/astor-site/README.md), #112, #124, #139).
- PostgreSQL, Redis, MongoDB, Kafka (Redpanda), MinIO — только в приватной сети; ночной бэкап базы в S3
  ([infra/cloudru/host/](infra/cloudru/host/README.md)).

Выложено 10.10 (HANDOVER §2): STT очков через Yandex SpeechKit (#119), поручения голосом (#121), телефон гостя для Saby (#117),
защита API (#123), веб-канал (#118), MAX фаза 1 (#116), фундамент мультиязычности (#114), сайт (#112, #124).
Волна 12.10 по интервью команды AERIS (#155–#191) влита в `main` и выложена на ВМ вечером 12.10 (образ бота, шлюз,
очки v10); все новые функции выехали выключенными и включаются флагами в `.env.production`.

### Первый живой тест: бизнес-ланч в AERIS, 15.10.2026

План — [BUSINESS_LUNCH_FIRST_TEST.md](docs/operations/BUSINESS_LUNCH_FIRST_TEST.md): два статиста оформляют ланч через бота.
Первый прогон — **полуавтомат**: заявка → карточка хостес «Да / Нет» → подтверждение гостю; нужен только id staff-чата.
Второй — **автомат с Saby** после ключей и read-only smoke. Критерий: гость сел, заявка дошла до хостес, подтверждение
вернулось, бронь видна в Saby (HANDOVER §5). Откат — флагами на ВМ и `/stop` менеджера смены.

Подготовка: [SABY_STAFF_MEMO_DASHA.md](docs/operations/SABY_STAFF_MEMO_DASHA.md) (памятка менеджеру зала: что должно быть
в Presto, люди для справочника), [SHIFT_BRIEFING.md](docs/operations/SHIFT_BRIEFING.md) (сводки смены, `/status_butler`),
[MENU_COMPOSITION_FOR_BOT.md](docs/operations/MENU_COMPOSITION_FOR_BOT.md), интервью AERIS № 1–№ 6 (см. «Документы»).

### Фичи за флагами (по умолчанию выключены)

Имена — как в `application.yaml`; в `docker-compose*.yml` им соответствуют алиасы `AERIS_*`. Полная таблица с дефолтами
и пробросом в контейнер — [BUSINESS_LUNCH_FIRST_TEST.md §1.6](docs/operations/BUSINESS_LUNCH_FIRST_TEST.md).

| Флаг | Что включает | PR |
| --- | --- | --- |
| `ASTOR_MAX_ENABLED` | канал MAX: текст, кнопки, согласие и контакт (фаза 1, long polling) | #116 |
| `ASTOR_I18N_ENABLED`, `ASTOR_I18N_MT_ENABLED` | определение языка гостя, каталог ru/en, машинный перевод | #114 |
| `ASTOR_TELEGRAM_VOICE_REPLIES` (`off`) | голосовые ответы бота + короткая текстовая сводка, `/voice on\|off` | #105 |
| `ASTOR_STAFF_TASKS_ENABLED` | поручения из очков в `astor_staff_tasks` без стаф-портала | #121 |
| `ASTOR_GLASSES_TRANSCRIPT_RELAY_ENABLED` | релей вопросов и ответов очков в системный чат | #70 |
| `ASTOR_SHIFT_BRIEFING_ENABLED` | утренний план и вечерняя сводка смены в аналитическом чате | #67 |
| `ASTOR_LUNCH_SCOREBOARD_ENABLED` | табло ланча: оценки 5★, хит-парад блюд, `/табло` | #189 |
| `ASTOR_KILL_SWITCH_ENABLED` | стоп-кнопка менеджера смены: `/stop` с саммари, `/start_butler`, `/status_butler` | #172 |
| `ASTOR_BOOKING_AUTO_CONFIRM_ENABLED` | таймер карточки хостес: автоподтверждение, `/manual on`, передача менеджеру | #159 |
| `ASTOR_BOOKING_MORNING_CHECK_ENABLED` | утреннее переподтверждение гостю: «в силе / перенести / отменить» | #158 |
| `ASTOR_BOOKING_HOLD_EXPIRY_ENABLED` | истечение удержания стола по TTL, стол освобождается | #169 |
| `ASTOR_BOOKING_STAFF_EDIT_ENABLED` | кнопка «Изменить» на карточке хостес: стол, время, гости | #171 |
| `ASTOR_BOOKING_SEATED_ENABLED` | кнопка «Гость сел», статус `SEATED`, маппинг состояний Saby | #182, #183 |
| `ASTOR_WAITER_GUEST_CARD_ENABLED` | карточка гостя официанту после «гость сел» | #190 |
| `ASTOR_REVIEW_MOMENT` (`after-close`) | `on-bill`: оценка до оплаты, кнопка «Счёт» у хостес | #157 |
| `ASTOR_GUEST_BILL_REQUEST_ENABLED` | гость просит счёт в боте: карточка команде и отзыв | #178 |
| `ASTOR_TIPS_PERSONAL_ENABLED` | чаевые лично официанту стола: `/официант`, `/чаевые` | #179 |
| `ASTOR_LUNCH_WISHES_ENABLED` | пожелания к блюдам и напиткам, состав блюд из меню | #180 |
| `ASTOR_LUNCH_PREORDER_MARK_ENABLED` | пометка кухне «ПРЕДЗАКАЗ: готовить ко времени» | #191 |
| `ASTOR_STAFF_HANDOFF_ENABLED` | персональная ссылка / QR сотрудника, лог входа гостя, `/ссылка` | #181 |
| `ASTOR_GUIDING_QUESTION_ENABLED` | направляющий вопрос в конце ответа бота | #177 |
| `ASTOR_STAFF_EVIDENCE_ENABLED` | фото-подтверждение этапа поручения в приватном S3 | #141 |
| `ASTOR_SABY_ENABLED`, `SABY_WRITE_ENABLED`, `SABY_PAYMENT_ENABLED`, `ASTOR_BILLING_ENABLED` | Saby Presto: чтение занятости, запись заказа, ссылка оплаты, счета гостя | [SABY_PRESTO_BOOKING_API.md](docs/integrations/SABY_PRESTO_BOOKING_API.md), #117, #140 |

Включено по умолчанию: сценарий бизнес-ланча (`ASTOR_BUSINESS_LUNCH_ENABLED`), Kafka и outbox (`KAFKA_ENABLED`,
`KAFKA_OUTBOX_ENABLED`). Стаф-портал за JWT (`astor.staff.enabled`) выключен — нет OIDC-издателя
([STAFF_PORTAL_RUNBOOK.md](docs/operations/STAFF_PORTAL_RUNBOOK.md), репетиция миграций —
[STAFF_PORTAL_REHEARSAL_2026-10-11.md](docs/operations/STAFF_PORTAL_REHEARSAL_2026-10-11.md)).

### Известные ограничения

- Живой Saby ни разу не вызывался: форма ответов Presto — допущение, проверено только на стабе (BUSINESS_LUNCH §1.7).
- Меню ланча не сверено с рестораном (`confirmedByVenue: false`, #91).
- Поручения из очков видны в базе и системном чате; кабинет персонала выключен.
- MAX-боты только разговаривают, брони ждут channel-aware API (#137); веб-чат Консьержа снаружи — после маршрута Caddy.

### Бэклог (открытые issues)

- Тест и ресторан: #127 бот в рабочую группу AERIS; #131 критерий успеха; #129, #130 оплата, лояльность, депозит, no-show;
  #132, #133 отзывы за столом и промо.
- Гостевой путь: #147 единый входящий канал броней; #149 утреннее подтверждение; #152 Яндекс Смарт Резерв; #153 «театр выбора»;
  #163, #164 карточка гостя и предзаказ; #173, #174 предзаказ на депозите и меню из Saby.
- Персонал и очки: #154 роли и поручения; #160 поручение в наушник; #162 чек-лист уборки; #184–#187 голосовой заказ,
  жест подтверждения, приоритет озвучки, база гостей.
- Платформа: #115 мультиязычность, фазы 1–8; #120, #137 MAX фаза 2; #93 кабинет персонала; #98 эксплуатация и бэкапы;
  #100 техдолг; #88–#92 карточки, FSM viewer, документы.
- Открытый PR: #146 кнопки процента чаевых.

## Как устроен Astor: Консьерж ⇄ Батлер

Спецификация — [2026-10-14-astor-negotiation-design.md](docs/superpowers/specs/2026-10-14-astor-negotiation-design.md),
включение и откат — [NEGOTIATION_ROLLOUT.md](docs/operations/NEGOTIATION_ROLLOUT.md).

- **Два голоса.** Консьерж ([Astor_Concierge](https://github.com/astor-hospitality/Astor_Concierge)) — на стороне гостя, Батлер — на стороне ресторана. Агент Консьержа договаривается с агентом Батлера через `/api/negotiation/v1/turn` и инструменты Capability API v1 (`/api/capabilities/v1/*`); ответ ресторана приходит в чат Консьержа с подписью `AERIS · Батлер:`.
- **Факт, а не текст.** Инструмент возвращает факт; бронь подтверждает только хостес или Presto. События брони Батлер отправляет Консьержу подписанным вебхуком (HMAC, `ASTOR_WEBHOOK_SECRET`).
- **Лента визита** `visit_turns` — все реплики гостя, агентов, персонала и Saby под одним id визита; телефоны маскируются, текст Консьержа — только с согласия гостя. По закрытому визиту — саммари и уроки агентам.
- **Табло** astor-ai.ru/board — визиты, переговоры и лампы здоровья; вход по `ASTOR_BOARD_TOKEN`.
- **Флаги** (все `false`): `ASTOR_LEDGER_ENABLED`, `ASTOR_BOARD_ENABLED`, `ASTOR_VISIT_SUMMARY_ENABLED`, `ASTOR_CAPABILITIES_ENABLED`, `ASTOR_CONCIERGE_WEBHOOK_ENABLED`, `ASTOR_AGENT_ENABLED` + `ASTOR_AGENT_ALLOWLIST`; у Консьержа `LEDGER_ENABLED`, `AGENT_ENABLED` + `AGENT_ALLOWLIST`. Агенты — GigaChat-2-Pro (GigaChat-3-Pro недоступна на скоупе PERS, проверено 14.10), саммари — GigaChat-2-Max. С 15.10 агентов можно отдельно от остального перевести на OpenAI-совместимый API программы Sber500 × Disrupt: `ASTOR_AGENT_PROVIDER=openai-compatible` (у Консьержа `AGENT_LLM_PROVIDER`), модель из `GET /models`; проверка API, нагрузка и стоимость — [`SBER500_LLM_ACCEPTANCE.md`](docs/operations/SBER500_LLM_ACCEPTANCE.md), выкладка и откат — [`SBER500_LLM_ROLLOUT.md`](docs/operations/SBER500_LLM_ROLLOUT.md).

## Архитектура в 10 строках

```text
Telegram · WEB-виджет · MAX · Astor Glass (iPhone → runtime очков) · REST
          │ transport adapters (TelegramRouter, WebChatController, MAX long polling, /api/glasses/*)
          ▼
Message Gateway ──► Scenario Router ──► FSM Runtime  (единственный источник истины)
          │
Domain services: booking · business lunch · billing · tips · staff tasks · preference · concierge
          │
PostgreSQL + pgvector (факты, outbox, семантическая память) · Redis (горячее состояние FSM, черновики)
Kafka / Redpanda (события, аналитика) · MinIO / S3 (медиа, фото-подтверждения) · MongoDB (документы)
          │
Проекции: staff / admin / system / analytics чаты Telegram · Saby Presto (адаптер) · модели через Model Gateway
```

Инвариант: бизнес-решения = FSM + доменные сервисы; модель (Yandex AI Studio, Sber GigaChat / Cloud.ru FM, OpenAI-совместимые,
Spring AI / Ollama) — только понимание, извлечение слотов и черновики текста. Подробно: [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md),
[DATABASE_MODEL.md](docs/architecture/DATABASE_MODEL.md); ADR: [очки → поручение](docs/architecture/ADR-glasses-voice-tasks.md),
[колонки Салют](docs/architecture/ADR-salute-speaker.md); планы: [MAX](docs/architecture/MAX_ADAPTER_PLAN.md),
[мультиязычность](docs/architecture/I18N_MULTILANG_PLAN.md), [веб-канал](docs/architecture/WEB_CHANNEL_ADAPTER.md).

## Запуск локально

Стек: Java 25, Spring Boot 4.1, Spring AI 2.0, Maven 3.9, Liquibase, Docker Compose (`pom.xml`, `Dockerfile`).

```bash
scripts/start_local_infra.sh      # api-gateway, postgres, redis, mongo, kafka, redpanda-console, minio, prometheus, grafana
scripts/run_local_app.sh [.env]   # Spring Boot из Maven с переменными из .env
mvn -B test                       # тот же прогон, что в CI (.github/workflows/ci.yml)
```

Без локального JDK 25: `docker run --rm -v "$PWD":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace maven:3.9-eclipse-temurin-25 mvn -B test`.

Профили `docker-compose.yml`: `telegram` (бот AERIS), `app` (бот c3flex), `smart-solution`, `frontend` (C3AG), `nlu` (Natasha),
`ai` (llm-gateway, Ollama). Оверлеи: `docker-compose.prod.yml`; `docker-compose.cloudru.yml` (дополнительно `graph-timeline`,
`kafka-tools`, `observability`); `docker-compose.saby-stub.yml` (стаб Saby для локального прогона автомата).
Локально: Swagger `http://localhost:8080/swagger-ui/index.html`, health `http://localhost:8080/actuator/health`,
Redpanda Console `http://localhost:8081`, MinIO Console `http://localhost:9001`.

Переменные окружения — только имена; значения в локальном `.env`, который не коммитится:

| Группа | Переменные | Назначение |
| --- | --- | --- |
| Хранилища | `POSTGRES_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `MONGODB_URI`, `KAFKA_BOOTSTRAP_SERVERS`, `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET_MEDIA`, `S3_BUCKET_DOCUMENTS` | базы, брокер, объектное хранилище |
| Telegram | `TELEGRAM_BOT_ENABLED`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_HOSTESS_CHAT_ID`, `TELEGRAM_ADMIN_CHAT_ID`, `TELEGRAM_SYSTEM_CHAT_ID`, `TELEGRAM_ANALYTICS_CHAT_ID`, `TELEGRAM_BOOKING_MANAGER_CHAT_ID`, `TELEGRAM_PROXY_HOST`, `TELEGRAM_PROXY_PORT`, `TELEGRAM_PROXY_TYPE` | бот и служебные чаты; в compose для AERIS — `AERIS_ASTOR_BUTLER_*_CHAT_ID` |
| Модели и речь | `ASTOR_MODEL_PROVIDER` (`spring-ai` · `yandex` · `gigachat` · `cloudru` · `openai-compatible`), `YANDEX_*`, `GIGACHAT_*`, `CLOUDRU_*`, `ASTOR_STT_PROVIDER` (`cloudru` · `yandex` · `local`), `ASTOR_TTS_PROVIDER`, `YANDEX_SPEECHKIT_*`, `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER` | выбор провайдера — конфигурация, не логика сценариев ([SBER_AI_ACTIVATION.md](docs/operations/SBER_AI_ACTIVATION.md)) |
| Безопасность | `ASTOR_INTERNAL_API_TOKEN`, `ASTOR_GLASSES_RELAY_TOKEN` | внутренний токен непубличного API; токен релея очков |
| Интеграции | `SABY_APP_CLIENT_ID`, `SABY_APP_SECRET`, `SABY_SECRET_KEY`, `SABY_POINT_ID`, `SABY_HALL_ID`, `SABY_PRICE_LIST_ID`, `MAX_BOT_TOKEN`, `MAX_API_BASE_URL` | Saby Presto и MAX |
| Очки | `ASTOR_GLASSES_TENANT`, `ASTOR_GLASSES_STAFF_ROLE`, `ASTOR_GLASSES_TRANSCRIPT_RELAY_URL` | tenant справочника сотрудников, роль автора поручений, адрес релея |

## Выкладка

Runbook — [CLOUDRU_DEPLOY_RUNBOOK.md](docs/operations/CLOUDRU_DEPLOY_RUNBOOK.md); состояние стенда и ручной путь —
[HANDOVER_2026-10-10.md](docs/operations/HANDOVER_2026-10-10.md); охрана API — [PUBLIC_API_GUARD.md](docs/operations/PUBLIC_API_GUARD.md).

- Образ собирается из `main` (GitHub Actions `deploy-cloudru-vm.yml` → GHCR `astor-butler:sha-<sha>`), на ВМ ничего не
  собирается. Деплой произвольного sha запрещён: откат на старый коммит — revert в `main`.
- Флаги и секреты — только в `.env.production` на ВМ (в git не попадает). Новая функция выезжает выключенной и включается флагом.
- Liquibase применяет миграции при старте приложения; откат образа миграции не отменяет — перед backend-деплоем нужен
  свежий бэкап базы (runbook §5, §7).
- Шлюз nginx сам переразрешает имя бота после пересоздания контейнера (#168); перезапуск шлюза больше не обязателен.
- Путь через Yandex VM (`deploy-yandex-vm.yml`, [AERIS_SCOPED_DEPLOY.md](docs/operations/AERIS_SCOPED_DEPLOY.md)) устарел,
  триггеры отключены.

Правила: ветка → PR в `main` → ревью **до** мержа → зелёный CI (`ci.yml`: тесты, проверка guard-скриптов, поиск секретов
в репозитории). Общий edge-Caddy меняет только Михаил. Production-операции требуют отдельной авторизации (`CLAUDE.md`).

## Документы

Карта `docs/` — [docs/README.md](docs/README.md). Приоритет источников: [FSM viewer](docs/FSM_SCENARIOS_VIEWER.html) →
[ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) → [FSM_SCENARIOS.md](docs/fsm/FSM_SCENARIOS.md) → `docs/obsidian/`.

| Пакет | Что внутри |
| --- | --- |
| [docs/operations/](docs/operations/README.md) | runbooks (Cloud.ru, API guard, стаф-портал, Telegram-прокси), [передача руля 10.10](docs/operations/HANDOVER_2026-10-10.md), [план первого теста](docs/operations/BUSINESS_LUNCH_FIRST_TEST.md), [сводки смены](docs/operations/SHIFT_BRIEFING.md), очки (`GLASSES_*.md`) |
| Интервью AERIS | [№ 1 менеджер смены](docs/operations/AERIS_MANAGER_INTERVIEW_1.md) ([ответы](docs/operations/AERIS_MANAGER_INTERVIEW_1_ANSWERS.md)) · [№ 2 хостес](docs/operations/AERIS_HOSTESS_INTERVIEW_2.md) ([ответы](docs/operations/AERIS_HOSTESS_INTERVIEW_2_ANSWERS.md)) · [№ 3 менеджер зала, второй круг](docs/operations/AERIS_MANAGER_INTERVIEW_3.md) · [№ 4 официант](docs/operations/AERIS_WAITER_INTERVIEW_4.md) ([ответы](docs/operations/AERIS_WAITER_INTERVIEW_4_ANSWERS.md)) · [№ 5 шеф-повар](docs/operations/AERIS_CHEF_INTERVIEW_5.md) ([ответы](docs/operations/AERIS_CHEF_INTERVIEW_5_ANSWERS.md)) · [№ 6 стажёр, носитель очков](docs/operations/AERIS_TRAINEE_INTERVIEW_6.md) ([ответы](docs/operations/AERIS_TRAINEE_INTERVIEW_6_ANSWERS.md)) |
| `docs/product/` | планы фич по интервью: [гостевой путь](docs/product/GUEST_JOURNEY_FEATURES_FROM_INTERVIEWS.md), [официант](docs/product/WAITER_FEATURES_PLAN.md), [кухня](docs/product/KITCHEN_FEATURES_PLAN.md), [носитель очков](docs/product/GLASSES_WEARER_FEATURES_PLAN.md), [карточка гостя](docs/product/GUEST_CARD.md), [направляющий вопрос](docs/product/GUIDING_QUESTION.md) |
| [docs/architecture/](docs/architecture/README.md) | архитектура, модель данных, локальные БД, ADR, планы MAX / i18n / веб-канала |
| [docs/fsm/](docs/fsm/README.md) | текстовые companion-документы FSM, бронь стола, план реализации |
| [docs/contracts/](docs/contracts/README.md) | API, Kafka / outbox, Telegram Stars, [Concierge → Butler → Saby](docs/contracts/CONCIERGE_BUTLER_HANDOFF_DRAFT.md) |
| `docs/integrations/` | Saby Presto: [карта API](docs/integrations/SABY_PRESTO_BOOKING_API.md), [счета](docs/integrations/SABY_BILLING.md), [сценарий показа](docs/integrations/SABY_E2E_DEMO.md); [карточка гостя и склейка каналов](docs/integrations/GUEST_IDENTITY_OPEN_QUESTIONS.md) |
| `docs/content/`, [docs/commercial/](docs/commercial/README.md), [docs/frontend/](docs/frontend/README.md), [docs/research/](docs/research/README.md) | медиа и RAG-источники; бренд-бук, КП, договор AERIS; фронтенд-handoff; исследования |
| [docs/obsidian/](docs/obsidian/README.md), `docs/analytics/`, `docs/archive/` | проектная память агентов; отчёты прогонов; история |
| `infra/` | [Cloud.ru: Terraform, cloud-init, бэкап, Telegram-egress](infra/cloudru/README.md); [сайт Astor](infra/astor-site/README.md) |
| `clients/` | пилотные клиенты очков: `ios-glasses`, `android-glasses` |

## Команда и роли

Как в [HANDOVER_2026-10-10.md](docs/operations/HANDOVER_2026-10-10.md); контакты — не здесь.

| Кто | Роль |
| --- | --- |
| Михаил | владелец продукта и стенда: ВМ, общий Caddy, ключи и DNS, решения по продукту, ревью |
| Дима | принял руль 10.10: бэкенд и эксплуатация (#122, #126, #128–#135, #137), сторона Saby, Консьерж |
| Егор | бэкенд и фронтенд C3AG, мультиязычность (#115) |
| Рома | Astor Glass (iOS и runtime), кабинет персонала (#93), контракт Concierge → Butler → Saby |
| Codex, Claude | агенты-исполнители: ветки и PR, обязательное ревью человека |
| AERIS | Дарья Анатольевна — менеджер зала, подтверждает заявки на тесте; Дарья Вячеславовна — хостес; Тариэль — доступы (группа, ключи Saby); официанты и бармен — в справочнике `astor_staff_members` |

## Лицензия и статус репозитория

Лицензия — Apache 2.0 ([docs/LICENSE](docs/LICENSE)). Репозиторий публичный; статус — MVP в пилоте с одним заведением (AERIS),
не публичный релиз и не магазинное приложение. Связанные репозитории команды: [Astor_Concierge](https://github.com/astor-hospitality/Astor_Concierge),
[MuseonUrania (Vedal)](https://github.com/astor-hospitality/MuseonUrania), [smart-solution-website](https://github.com/astor-hospitality/smart-solution-website),
[Astor_Butler_Legacy](https://github.com/michaelwelly/Astor_Butler_Legacy); секреты и базы гостей между ними не делятся.

Не коммитить: `.env`, `.env.*`, `target/`, `build/`, `.idea/*`, `.codex*`, локальные медиа-оригиналы. Перед push проверять
diff на секреты и build-артефакты (`CLAUDE.md`, `AGENTS.md`).
