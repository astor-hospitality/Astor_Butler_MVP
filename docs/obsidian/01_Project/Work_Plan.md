# Work Plan

Дата: 2026-06-04

## Active Team Task 2026-07-23

Фокус: Smart Solution Ops CRM как командный Telegram-штаб.

Рабочая задача:

- собрать в боте единый статус по проектам, задачам, коллам, презентациям, ответственным, срокам и пайплайну до готового результата;
- вести вертикали `VIDEO`, `MED`, `IZI`, `RESTO`, `PRINT`, `SITE`, `ADS` структурно, а не только текстом в чате;
- отвечать на вопросы участников группы сначала из Ops CRM + project memory;
- если AI/RAG не знает ответ, бот тегает владельца; ответ владельца на исходный вопрос сохраняется в память проекта;
- Smart Solution бот должен быть отдельным runtime от AERIS, с отдельным BotFather token, Redis prefix и портом.

Стоп-лист на текущий срез:

- не продолжать VM deploy без отдельной команды;
- не коммитить и не синхронизировать `.env`, `.env.*`, `target/**`, `.codex*`;
- не хранить BotFather token в git, docs или Obsidian;
- не смешивать Smart Solution group flow с гостевым AERIS FSM.

Первый командный статус:

- `VIDEO`: видео-продакшен у `@egor`, нужен короткий статус по сценарию, дедлайнам и next deliverable;
- `MED`: презентация по медицине у `@michael`, нужен текущий статус, ссылка/версия и срок готовности;
- `IZI`: статус "ожидает оплату", нужен ответственный и дата следующего касания;
- `RESTO`: запуски ресторанов, нужен список заведений/этапов;
- `PRINT`: типография, нужен список макетов/тиражей/сроков;
- `SITE`: Smart_Soultion.com + внутренняя CRM, нужен pipeline от логотипа/сайта до командного dashboard.
- `ADS`: Яндекс Бизнес/Директ/Карты, нужен официальный внешний growth-контур: карточка, кампании, бюджеты, UTM, KPI и отчеты.

Definition of Done для этого среза:

- команда может написать `/projects`, `/summary CODE`, `/tasks CODE`, `/calls`, `/artifacts CODE` и получить актуальный digest;
- обычный вопрос в группе получает ответ от бота, если ответ есть в CRM/RAG;
- неизвестный вопрос эскалируется владельцу, а ответ владельца становится новой памятью проекта;
- рекламные статусы и решения по Яндекс Бизнес/Директ живут в `ADS`, а не смешиваются с публичной выдачей Алисы/Карт;
- AERIS и Smart Solution не конфликтуют по токенам, Redis state и портам;
- после проверки локально можно отдельно включать VM/Yandex deploy.

## Active Launch Status 2026-07-30

Статус: backend AERIS на Yandex VM готов к демонстрационному REST/Swagger smoke, Telegram пока замокан.

Сделано:

- SSH-доступ к VM восстановлен через порт `2222`;
- исходники синхронизированы в `/opt/astor-butler`;
- `aeris_astor_butler_bot` пересобран через Docker Compose;
- health/readiness/Swagger зелёные;
- YandexGPT Lite runtime probe зелёный;
- `/api/messages` contact + booking smoke создал реальную заявку в PostgreSQL;
- Smart Solution Ops `RESTO` переведен в `READY_TO_LAUNCH`, `85%`.

Следующие шаги:

1. Получить от Егора Germany SOCKS5/HTTP CONNECT proxy, который открывает `https://api.telegram.org`.
2. Подключить proxy env на VM и только после smoke включить `TELEGRAM_BOT_ENABLED=true`.
3. Купить и настроить `c3ag.ru`: `A` record на `51.250.31.97`, затем nginx/Caddy TLS.
4. Поднять C3 Agency frontend контейнер из main/front branch на этой VM.
5. Спроектировать и подключить SABY/SBIS external reservation port для реального ресторана.

Актуализация 2026-07-30:

- proxy переносится на созвон с Егором;
- `c3ag.ru` куплен/активируется, DNS настраиваем после активации домена;
- C3 Agency frontend поднят на той же VM отдельным контейнером;
- временный preview до домена: `http://51.250.31.97:3001`;
- доменный runbook добавлен в `docs/operations/C3AG_DOMAIN_RUNBOOK.md`;
- после DNS/TLS нужно закрыть прямой публичный `3001` и вести трафик через `80/443`.

## Цель ближайшего этапа

Собрать Astor Butler MVP как управляемый Telegram/FSM backend для ресторана/event-площадки: первый контакт гостя, Consent Vault, сохранение профиля и сообщений, Kafka event trail, админ-уведомления, далее - сценарии бронирования и Slot Keeper.

## Принцип работы

FSM остается источником истины:

- AI помогает понять текст клиента и извлечь сущности;
- бизнес-логика живет в состояниях, переходах, доменных сервисах и валидаторах;
- Telegram остается транспортом;
- legacy-репозиторий используется как источник голоса, тона и поведенческого стиля, но не как источник бизнес-правил.

## Фаза 0. Гигиена репозитория

Статус: продолжается.

Сделано:

- вычищены `.env` и `target/**` из локального коммита перед push;
- добавлен `.gitignore`;
- `main` синхронизирован с `origin/main` на baseline `268662c Add consent vault events and local gateway stack`;
- локальная Maven-проверка на JDK 21 проходила успешно на предыдущих этапах;
- удаляются неактуальные sandbox-упражнения и локальные `.codex*` артефакты.

Осталось:

- не трогать локальную `.idea/dataSources.xml`, пока не ясно, нужна ли она в проекте;
- в будущем добавить GitHub Actions для Maven build/test.
- после удаления sandbox проверить сборку и сделать отдельный cleanup commit.

## Фаза 1. Документы от Яны

Цель: получить реальные материалы, чтобы FSM не был фантазией.

Нужно получить:

- банкетные меню, фуршеты, пакеты, доп. услуги;
- прайсы, депозиты, предоплаты, отмены, переносы;
- коммерческие предложения;
- договоры, приложения, счета, акты, чек-листы;
- текущий путь клиента от первого сообщения до подтверждения;
- типовые вопросы и ответы менеджеров;
- обезличенные переписки;
- обязательные поля для бронирования;
- документы и требования по подрядчикам;
- ограничения площадки;
- критерии "готовой брони".

Результат фазы:

- `02_Product/Event_Booking_Process.md`
- `02_Product/Required_Documents.md`
- `02_Product/Booking_Data_Model.md`

## Фаза 2. Product/FSM spec

Цель: превратить процесс бронирования в явный FSM-сценарий.

Сценарий `EVENT_BOOKING`:

- приветствие и определение намерения;
- тип мероприятия;
- дата и время;
- количество гостей;
- формат;
- бюджет;
- меню и напитки;
- технические требования;
- подрядчики;
- контактные данные;
- подтверждение заявки;
- передача менеджеру;
- fallback и эскалация на живого менеджера.

Результат фазы:

- список состояний;
- список событий;
- обязательные поля на каждом шаге;
- валидаторы;
- правила возврата к предыдущему шагу;
- правила эскалации;
- структура итоговой заявки.

## Фаза 3. Доменные модели MVP

Цель: добавить минимальный домен event booking без смешивания с Telegram.

Предварительные сущности:

- `GuestProfile`
- `Venue`
- `EventBooking`
- `EventDetails`
- `BookingContact`
- `BookingDocument`
- `BookingStatus`

PostgreSQL:

- пользователи;
- бронирования;
- статусы;
- структурированные поля заявки;
- связи между гостем, менеджером и заявкой.

MongoDB:

- файлы;
- документы;
- обезличенные примеры;
- метаданные загруженных материалов.

Redis:

- FSM hot context;
- idempotency;
- быстрый кеш документов/шаблонов;
- временные черновики заявки.

Дополнение 2026-06-05: для обычной посадки столов вводится отдельный `Table Booking` домен, чтобы не смешивать банкетные/event-заявки и конкретную посадку гостя в зале. Первый слой уже описан через `venue_tables`, `table_reservation_orders`, `table_reservation_holds` и документацию `docs/fsm/TABLE_BOOKING.md`.

## Фаза 4. Кодовая реализация

Статус: начато 2026-05-05.

Порядок реализации:

1. Создать пакет `domain/booking`. - done
2. Добавить черновик заявки и Redis-хранилище draft. - done
3. Добавить Liquibase changelog для PostgreSQL. - done
4. Добавить MongoDB dependency/config для файлового слоя.
5. Описать порт хранения документов без жесткой привязки к Telegram.
6. Расширить `BotState` состояниями `EVENT_BOOKING`. - done
7. Добавить handlers для шагов бронирования. - first version done
8. Добавить response builder/templates.
9. Покрыть FSM-переходы unit-тестами. - first tests done
10. Добавить smoke-тест сборки в GitHub Actions.

Что реализовано:

- `EventBookingDraft`
- `EventBookingDraftStorage`
- `RedisEventBookingDraftStorage`
- `EventBooking`
- `BookingStatus`
- `EventBookingRepository`
- `EventBookingService`
- `EventBookingSummaryFormatter`
- `EventBookingManagerNotifier`
- Liquibase changelog `2026-05-05-create-event-bookings.yaml`
- `EventBookingHandler`
- callback-aware `CommandContext`
- callback/contact-aware `InboundEvent`
- `FSMHandler#getStates()` для одного handler на несколько состояний
- вход в сценарий через `/event_booking`, старый `/table_booking` и intent-фразы
- сохранение подтвержденной заявки в PostgreSQL со статусом `READY_FOR_MANAGER`
- сохранение эскалации в PostgreSQL со статусом `MANAGER_REVIEW`
- опциональное уведомление менеджера через `ASTOR_MANAGER_TELEGRAM_CHAT_ID`
- если manager chat id не задан, summary логируется без падения приложения
- unit-тесты `EventBookingHandlerTest`, `EventBookingServiceTest`, `EventBookingSummaryFormatterTest`, `EventBookingManagerNotifierTest`

Проверка:

- `JAVA_HOME=/Users/michaelwelly/Library/Java/JavaVirtualMachines/jbrsdk_jcef-21.0.10/Contents/Home mvn test`
- результат: `BUILD SUCCESS`, 8 tests passed
- локальный старт с Postgres/Redis прошел
- Liquibase создал таблицу `event_bookings`
- `/actuator/health` вернул `{"status":"UP"}`

## Фаза 5. Legacy voice

Цель: взять из `Astor_Butler_Legacy` стиль общения, но не тащить legacy-архитектуру.

Нужно сделать:

- получить/клонировать `Astor_Butler_Legacy`;
- выделить tone-of-voice;
- описать правила общения Astor;
- создать `06_References/Legacy_Voice.md`;
- использовать tone layer в шаблонах ответов.

Правило:

Тон может быть живым, теплым и узнаваемым, но FSM-ответы должны оставаться ясными, проверяемыми и безопасными для клиента ресторана.

## Фаза 6. Notion и рабочая база знаний

Цель: перенести лучшие практики из Notion в локальную проектную память и затем в docs/specs.

Источники:

- `Surviving Java Interviews / Как выжить на Java-собеседовании`
- `New data source`
- `План Защиты Симл(ви)и CV`

Нужно извлечь:

- стек;
- инженерные правила;
- стиль ведения документации;
- практики тестирования;
- практики описания задач.

## Ближайшие 3 шага

1. Закончить cleanup: удалить sandbox/Codex-мусор, проверить git status, сборку и отсутствие `.env`/`target` в tracked files.
2. Довести Table Booking слой: реализовать `TableBookingScenario` поверх описанной FSM-спеки, связать slot extraction с `TableReservationService`, отправлять AERIS plan и создавать hold только после сбора date/time/party/table.
3. После стабилизации первого контакта и посадки перейти к `EVENT_BOOKING`/Slot Keeper и подготовить System Design ДЗ на базе реального API/FSM flow.

## Дополнение 2026-07-23. Smart Solution Ops CRM через Telegram

Продуктовый фокус расширяется: Telegram-бот становится внутренним операционным центром Smart Solution для управления проектами и командой.

Цель: через бота выгружать команде статусы по проектам, расписание коллов, задачи, презентации, ответственных, сроки, этапы пайплайна и статусы запусков в разных вертикалях.

Вертикали:

- рестораны и HoReCa;
- типографии;
- video production;
- медицина;
- сайты и AI-проекты Smart Solution;
- Яндекс Бизнес/Директ/Карты как внешний growth/adtech контур.

Первый backend slice:

- `ops_projects` - карточка проекта/запуска;
- `ops_tasks` - задачи, ответственные, сроки, deliverables;
- `ops_calls` - расписание проектных созвонов;
- `ops_artifacts` - презентации, брифы, договоры, видео, дизайн и отчеты;
- `/api/ops/**` - REST-контур для dashboard/Telegram-команд;
- `/api/ops/projects/{id}/digest` - Telegram-ready статус проекта.
- Telegram-команды для service chat: `/ops`, `/newproject`, `/projects`, `/project CODE`, `/tasks CODE`, `/summary CODE`, `/status CODE STATUS 90% text`, `/task CODE "title" @owner 25.07`, `/call CODE "title" 24.07 15:00 @owner`, `/calls CODE`, `/artifact CODE "title" https://... PRESENTATION @owner`, `/artifacts CODE`.
- Seeded стартовые карточки для команды: `VIDEO` (видео-продакшен у `@egor`), `MED` (медицинская презентация у `@michael`), `IZI` (ожидает оплату), `RESTO` (запуски ресторанов), `PRINT` (типография), `SITE` (Smart_Soultion.com и внутренняя CRM), `ADS` (Яндекс Бизнес/Директ/Карты).
- Separate `smart-solution-bot` runtime: отдельный Telegram token, compose profile `smart-solution`, port `8090`, Redis prefix `smart-solution`, group Q&A через Ops CRM + RAG memory; если бот не знает ответ, тегает owner, а reply owner сохраняется в `SMART_SOLUTION_GROUP_MEMORY`.
- Reverse RAG/adtech boundary: внутренний AI может релевантно и прозрачно рекомендовать партнерский ресторан внутри наших каналов; публичная выдача Яндекса/Алисы/Карт продвигается через официальные продукты Яндекс Бизнес/Директ/Карты и ведется в проекте `ADS`.

Ключевой принцип остается прежним:

- Telegram - транспорт и быстрый UI;
- FSM/status lifecycle - источник истины по процессам;
- AI помогает с формулировками, суммаризацией, поиском и подготовкой материалов;
- финальные бизнес-статусы, сроки, ответственные и переходы по пайплайну должны храниться структурно.

## Дополнение 2026-07-23. Calliope -> AERIS Yandex AI rollout

Цель: перевести реализацию Calliope в AERIS как основного ассистента для ресторанного production-smoke, не ломая FSM-архитектуру.

Решение:

- Yandex AI Studio Agent пока не создаем; используем прямые `gpt://...` model URI через существующий `YandexModelGateway`.
- `yandexgpt-5-lite` работает как дешевый слой понимания intent/slots.
- `yandexgpt-5.1` работает как quality слой для сложных non-FSM запросов, recovery и manager summaries.
- Speech realtime выделен в отдельный adapter позже; текущий Java gateway покрывает text completion.
- Telegram polling включаем только после German SOCKS5/HTTP proxy, потому что Yandex Cloud IP pool сейчас таймаутит `api.telegram.org`.
- SABY/SBIS интеграция идет через отдельный external reservation port и не должна попадать в FSM как прямой API-вызов.

Ближайшие шаги:

1. Включить Yandex runtime env на VM без коммита секретов.
2. Сделать completion probe и REST smoke через `/api/messages`.
3. Получить German proxy от Егора и подключить Telegram Bot API.
4. Прогнать реальную бронь стола: guest phrase -> FSM -> order/hold -> hostess confirmation -> guest final status.
5. Спроектировать `SabyReservationProvider` после получения credentials/API contract.

Runbook: `docs/operations/CALLIOPE_TO_AERIS_YANDEX_AI_ROLLOUT.md`.

## Дополнение 2026-07-23. ООО «Счастье» / AERIS commercial production budget

Для следующего оплачиваемого production-этапа зафиксирована рабочая смета:

- запуск Astor Butler / AERIS: 60 000 рублей с учетом налогов;
- сопровождение после тестового месяца: 20 000 рублей в месяц с учетом налогов;
- первый тестовый месяц сопровождения показывается в договоре/счете как 20 000 рублей со скидкой 100%, то есть для Заказчика бесплатно;
- текущая Yandex Cloud VM себестоимость: около 10 860 RUB/month без сверхлимитного трафика;
- если инфраструктура перевыставляется клиенту с НДС 22%: около 13 249 RUB/month;
- AI token reserve: 5 000 RUB/month, stress reserve: 15 000 RUB/month;
- per guest AI economics: примерно 1.63 RUB в Lean и 3.68 RUB в Standard mode без клиентского НДС.

Печатный пакет:

- `docs/commercial/AERIS_SERVICE_AGREEMENT_DRAFT_RU.md`;
- `docs/commercial/OOO_SCHASTYE_AERIS_PROD_BUDGET_RU.md`;
- `docs/commercial/OOO_SCHASTYE_AERIS_PRINT_PACKAGE_RU.md`.

## Дополнение 2026-08-02. Astor Butler / Saby contract package

Пользователь подтвердил vendor spelling: `Saby` (`https://saby.ru/`), исторически `СБИС/sbis` только как прежнее название. Для ресторанной интеграции используем официальный reference `Saby Presto`; публичные страницы подтверждают продукт для общепита и общий API-подход Saby, но не дают конкретный API method для бронирования столов. Поэтому в договорном пакете capabilities разделены на verified facts и requested integration capabilities.

Подготовлен reviewable Markdown-пакет по модели договор + приложения:

- `docs/commercial/ASTOR_BUTLER_SABY_AGREEMENT_PACKAGE_RU.md`;
- `docs/commercial/ASTOR_BUTLER_SABY_SERVICE_AGREEMENT_DRAFT_RU.md`;
- `docs/commercial/ASTOR_BUTLER_SABY_ANNEX_1_TZ_RU.md`;
- `docs/commercial/ASTOR_BUTLER_SABY_ANNEX_2_API_WORKFLOW_RU.md`;
- `docs/commercial/ASTOR_BUTLER_SABY_ANNEX_3_DATA_CONSENT_RU.md`;
- `docs/commercial/ASTOR_BUTLER_SABY_ANNEX_4_MILESTONES_ACCEPTANCE_RU.md`.

Не отправлять внешним получателям без явного адресата и финального approval. Перед implementation нужны Saby API contract, auth model, sandbox/prod credentials, organization/restaurant ids, limits/tariffs and representative guidance.

## Дополнение 2026-08-02. C3AG post-narrative video feed follow-up

После текущего production deploy зафиксировано следующее frontend-требование для отдельного concrete batch: post-narrative video feed должен показывать product videos как четыре space-filling панели на desktop, а на mobile иметь комфортный horizontal scroll/swipe. Использовать только confirmed archive media, controls должны быть доступными, без autoplay sound, Wedding empty-state не менять и не заполнять fallback-контентом.

## Дополнение 2026-08-02. Telegram webhook routing plan

Подготовлен non-deployed implementation plan для Telegram webhook на `c3ag.online` или подтвержденном поддомене:

- `docs/operations/TELEGRAM_WEBHOOK_ROUTING_PLAN.md`.

Факты текущего состояния:

- `c3ag.online` уже указывает A-record на VM `51.250.31.97`, DNS authority - REG.RU;
- `api.c3ag.online` и `telegram.c3ag.online` сейчас не резолвятся;
- в активном Yandex folder нет ALB и Certificate Manager certificates;
- публичная Yandex DNS zone для `c3ag.online` отсутствует;
- SG разрешает inbound `443`, но на VM порт `443` не слушается;
- backend сейчас работает через long polling (`TelegramLongPollingBot`), endpoint `/telegram/webhook` не реализован.

Вывод: DNS alone is not enough. Для webhook нужен HTTPS routing layer + backend webhook controller + секретный header validation. Webhook снижает polling, но не отменяет исходящие вызовы к `api.telegram.org:443` для отправки сообщений/management; текущий timeout Telegram egress остается отдельным blocker.

## Дополнение 2026-08-02. Telegram egress via scoped WireGuard proxy

Пользователь предоставил WireGuard config для VPN exit и разрешил использовать его для восстановления Telegram connectivity. Конфиг full-tunnel, поэтому он не применялся на host route.

Сделано на production VM:

- установлен `wireguard-tools` и `tinyproxy`;
- дефолтный host `tinyproxy.service` отключен;
- создан systemd service `astor-telegram-wg-proxy.service`;
- WireGuard поднят только внутри network namespace `astor-tg-wg`;
- proxy слушает `10.233.200.2:8888`;
- host default route остался через `10.129.0.1 dev eth0`;
- Telegram TLS через proxy работает;
- direct host Telegram 443 still timeout, то есть workaround scoped;
- token-safe `getMe` через proxy вернул `ok=true` для `astor_butler_bot`;
- после отдельного smoke пользователь подтвердил живой ответ бота на стартовый сценарий;
- Telegram polling включен для AERIS runtime через isolated HTTP proxy `10.233.200.2:8888`;
- `TELEGRAM_GET_UPDATES_TIMEOUT_SECONDS=20`, чтобы long polling не конфликтовал с proxy idle behavior;
- runtime audit 2026-08-02: Yandex text understanding включен, scenario reply LLM выключен, STT/voice выключен, Saby runtime credentials/env не настроены.

## Дополнение 2026-08-02. AERIS Telegram buttons / Voice / Saby boundary

Подтверждено production smoke: Yandex provider отвечает на контролируемый read-only prompt, но генерация сценарных ответов остается выключенной (`ASTOR_SCENARIO_REPLY_LLM_ENABLED=false`), чтобы меню, бронь и manager handoff оставались структурными FSM-сценариями, а не LLM actions.

В Telegram guest keyboard добавлены понятные кнопки: `Меню кухни`, `Бар`, `Коктейли`, `Винная карта`, `Видео-тур`, `Бронь стола`, `Связаться с командой`, `Главное меню`. Тексты маршрутизируются через существующие FSM scenarios.

Voice pipeline: Telegram voice media boundary есть, STT выключен. Добавлены pre-STT лимиты по длительности/размеру и честный текстовый fallback. Реальный SpeechKit можно включать только через server-side credentials/secret storage и отдельный smoke.

Saby: локальный repo/docs/Obsidian и non-secret runtime config не содержат реальный API contract/base URL/auth/credentials/org id/restaurant id. Добавлена compile-ready Saby provider boundary, disabled by default, fail-closed without network calls. Не создавать реальные брони Saby до получения официального API-контракта и sandbox/prod credentials.

Дополнительный production media smoke 2026-08-02:

- найден подтвержденный локальный архив `/Users/michaelwelly/Desktop/AERIS FILES`;
- через существующий ingest path загружены в MinIO `astor-media` кухня, бар, Elements/коктейли, винная карта, план зала и `INTERIOR.mp4`;
- `media_assets` содержит активные asset codes `AERIS_MENU_KITCHEN`, `AERIS_MENU_BAR`, `AERIS_MENU_ELEMENTS`, `AERIS_MENU_WINE`, `AERIS_FLOOR_PLAN`, `AERIS_INTERIOR_TOUR`;
- scoped API smoke `покажи винную карту` вернул deterministic `MENU_ASSETS_DELIVERED` с `AERIS_MENU_WINE`, без LLM fan-out.

Runbook: `docs/operations/TELEGRAM_WIREGUARD_EGRESS_RUNBOOK.md`.

## Дополнение 2026-08-02. API-first RAG / Astor entity / source registry

- Production RAG переводится с локального Ollama embeddings path на API-first provider через `YandexModelGateway`.
- Embeddings генерируются Yandex AI Studio `textEmbedding`: `text-search-doc/latest` для документов и `text-search-query/latest` для retrieval-запросов.
- Vector store остается PostgreSQL `pgvector` на VM; внешняя vector DB не используется.
- В БД добавляются:
  - `venue_profiles` для публичного профиля AERIS;
  - `astor_assistant_profiles` для сущности Astor как цифрового дворецкого AERIS;
  - `venue_monitored_sources` для официального сайта, Yandex Maps, 2GIS, Instagram reference и текущего Telegram public channel ingest;
  - disabled boundaries для Review Distribution и consented guest media insights.
- Автоматически включен только уже реализованный `AERIS_TELEGRAM_PUBLIC_CHANNEL` ingest с часовым interval. Остальные источники ждут adapter/legal review.
- Instagram/media анализ гостя допустим только opt-in и как practical service preference insights, не как скрытый психологический портрет или sensitive inference.
- Добавлены smoke scripts:
  - `scripts/probe_yandex_reply.mjs`;
  - `scripts/smoke_system_logging.mjs`.


## 2026-10-04: S3 documents and glasses backend hardening

Михаил подтвердил оба S3 назначения: материалы очков и документы для ответа. Отдельная ветка `codex/glasses-backend-hardening` от PR #11; фронт Ромы/ограниченный SSH остаются в PR #12. S3 bucket `astor-glasses-pilot-b1gug0tmrgmsq5pfsvhs`: private, max 1 GiB, отдельный SA, только конкретный document GET и scoped materials PUT, lifecycle materials 1 day (daily processing), без folder roles/ключей в git. Bucket ACL задаёт базовые права; policy ограничивает действия/пути. Live static-key GET/PUT PASS; material GET, doc PUT, чужой scope PUT, list, DELETE и anonymous GET 403. Учебный документ business lunch, реальное меню/назначения отсутствуют.

API: memory reply retry 32 entries/120s, payload conflict 409, cached answer survives a storage failure to retry archive, late model result after timeout not reused; silence 400 NO_SPEECH, successful STT readiness preserved. All work remains informational, outside FSM/task ACK. Full Maven package: 309 tests, 0 failures/errors/skipped; real decoder 6 PASS + cached Whisper silence PASS. Graph updated. Runtime rollout/HTTPS smoke and PR CI are separate completion signals; physical acceptance stays with chat «Проверить интеграцию ИИ с очками». Operational details: `docs/operations/GLASSES_S3_STORAGE.md`.


## 2026-10-05: фото по учебным шагам, приветствие и локальная команда

Main a4023a7 включён в codex/glasses-backend-hardening без конфликтов. Ядро поручений остаётся отдельно от informational pilot. В clients/ios-glasses перенесён reviewable iPhone companion без SDK/Pods/build/ключей. Фото: четыре шага, обязательные PLACE_SETTINGS/FINAL_CHECK, session+stage+revision, S3 receipt, ручной переход и bounded retry. Runtime v3-step-photo promoted; полный Maven 336/336; публичный synthetic AAC/STT/photo/NO_SPEECH/409/401 PASS, контекст сверён в S3. iPhone build/sign/install PASS; wear greeting/local-only wake добавлены, аппаратная поддержка ещё не подтверждена. Документ: docs/operations/GLASSES_STEP_PHOTOS.md. Telegram Тариэлю отправлен 4 октября 19:02, меню к 5 октября ожидает подтверждения; Notion пакет создан.


## 2026-10-05 — private media archive v4

- /api/glasses/media: authenticated raw JPEG/AAC/MP4 up to64 MiB, canonical file/session UUID, standard SHA256, one upload, independent10/min limiter. Server commits media then receipt; receipt GET only is allowed, raw GET/list/delete/other scope PUT remain denied.
- v4-media-archive is live; v3 retained stopped for rollback. Candidate restart retry and public HTTPS receipts/conflict/hash/auth and6MiB MP4 passed. Full Maven package347 tests green; CI for775a7a5 green, next archive head pending.
- iPhone snapshot includes Dock modules and call policy fix. SDK Ready/HFP/wear capability2 enabled verified by physical chat; false SDK InCall during own SCO is fixed there. Greeting/wake/charge-upload are separate acceptance checks still open. Standby is local opt-in, no speaker verification; Personal Team Wi-Fi video import stays off.
- Portable companion sources in clients/ios-glasses; licensed SDK, build outputs and access credentials excluded. API archive does not complete StaffTaskService commands or FSM steps.
