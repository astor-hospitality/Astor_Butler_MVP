# Astor Glass: поручение голосом

Дата: 2026-10-10. Решение Михаила 10.10: очки делают первое действие — менеджер нажимает кнопку, говорит поручение, и оно становится задачей сотруднику, которую видит команда. До этого Astor Glass был строго информационным (см. `GLASSES_ASSIST_PILOT.md`); расширение границы на одно действие зафиксировано в `docs/architecture/ADR-glasses-voice-tasks.md`.

Связанные документы: контракт assist — `GLASSES_ASSIST_PILOT.md`; релей в системный чат — `GLASSES_TRANSCRIPT_RELAY.md`; правила поручений и портал — `GLASSES_STAFF_TASKS_P1_DRAFT.md`, `STAFF_PORTAL_RUNBOOK.md`.

## Как это работает

```
менеджер в очках: кнопка → «Задача: Анне принести воду на пятый стол»
   │
   ▼
iPhone → POST /api/glasses/assist {requestId, audioBase64 | text, intent?: "task"}      (runtime очков, bearer)
   │  STT как обычно → текст
   │  это поручение? — intent == "task"  ИЛИ  текст начинается с триггер-слова
   │        нет → обычный информационный ответ, как раньше
   │        да  ↓
   │  GigaChat: строгий JSON {assignee, tableCode, title, instruction, priority}
   │        не разобрал → title = первое предложение (≤ 60), instruction = весь текст, assignee = null
   │
   ├──► POST /api/internal/glasses/staff-tasks  (монолит AERIS, заголовок X-Astor-Relay-Token)
   │        {requestId, tenant, staff, draft:{…}}
   │        монолит: StaffScope(tenant, staff, роль из ASTOR_GLASSES_STAFF_ROLE)
   │                 исполнитель: по имени/роли среди активных сотрудников на смене, иначе — сам менеджер
   │                 StaffPortalService.create(scope, eventId = requestId, draft)  → PostgreSQL + audit
   │                 карточка в системный чат Telegram
   │        ← {taskId, title, assignee, assigneeStaffId, self, instruction, tableCode, priority, status}
   │
   ├──► ответ в наушник: «Записал поручение для Анна: Принести воду. Стол 5.»
   │                     или «Поручение записал на вас: … .»
   │                     при отказе монолита: 503 TASK_UNAVAILABLE «Не смог записать поручение, повторите»
   │
   └──► GlassesTranscriptRelay (как раньше): вопрос + подтверждение → системный чат и лента смены
```

Runtime очков по-прежнему без базы, Telegram и Kafka: он только собирает черновик и просит Butler создать задачу. Кто может поручать, кто на смене, кому достанется и чем это станет — решает монолит теми же правилами, что и стаф-портал (`StaffTaskService`). Очки ничего не подтверждают и статусов не меняют: ACK, этапы, фото остаются за P1-контрактом.

## Контракт для iOS

`POST /api/glasses/assist` — всё как в `GLASSES_ASSIST_PILOT.md`, плюс:

| Поле запроса | Значение |
| --- | --- |
| `intent` (опционально) | `"task"` — запрос из отдельного жеста «поручение»: текст или аудио трактуется как поручение независимо от первых слов; `"assist"` — обычный вопрос, даже если он начинается с триггер-слова. Любое другое значение — `400 MALFORMED_REQUEST`. Без поля — решают первые слова. |
| `text` / `audioBase64` | как раньше; аудио сначала распознаётся, потом проверяется на поручение |
| `imageBase64` + `intent: "task"` | `400 MALFORMED_REQUEST`: фото не превращается в задачу |

Триггер-слова по умолчанию: «задача», «поручение», «поручи», «передай» — в начале фразы, после них конец слова или знак («Задача: …», «Поручи Анне …», «Передай Илье, что …»). «Какие задачи на сегодня?», «Передайте …» — не поручение. Список настраивается (`ASTOR_GLASSES_TASK_TRIGGER_WORDS`).

Ответ `200` — прежний `{requestId, text, capabilities, audioBase64?, …}` и новый объект `task`, который есть только когда поручение записано:

```json
{
  "requestId": "ff5a8c58-bb60-43f4-b542-1e26c8b96581",
  "text": "Записал поручение для Анна: Принести воду. Стол 5.",
  "capabilities": { "...": "..." },
  "task": {
    "taskId": "a2e1…", "title": "Принести воду",
    "assignee": "Анна", "assigneeStaffId": "anna", "self": false,
    "instruction": "Принести воду на пятый стол", "tableCode": "5",
    "priority": "NORMAL", "status": "ASSIGNED"
  }
}
```

`assignee` — отображаемое имя из справочника; `null` и `self: true`, когда поручение осталось на том, кто его дал (имя не распознано, человек не на смене или двое подходят одинаково). `tableCode` — `null`, если стол не назван. Голос Астора (`audioBase64`) озвучивает `text`, как и прежде.

Ошибки — в прежнем формате `{requestId, error:{code, message}}`:

| Код | Когда | Что сказать |
| --- | --- | --- |
| `503 TASK_UNAVAILABLE`, message «Не смог записать поручение, повторите» | монолит недоступен, отказал (401/403/409/503) или ответил не тем | озвучить `error.message`; повтор с тем же `requestId` безопасен |
| `503 TASK_UNAVAILABLE`, message «Поручения через очки не включены» | `intent: "task"` при выключенной функции | функция не включена на сервере |
| `400 MALFORMED_REQUEST` | неизвестный `intent`, фото с `intent: "task"` | ошибка клиента |

Повтор того же `requestId` с тем же телом возвращает то же подтверждение и тот же `task` из кеша ответов (120 с); монолит со своей стороны дедуплицирует по `eventId = requestId`, поэтому задача не создаётся дважды и после рестарта runtime. Отказ Butler не сбрасывает готовность текста (`capabilities.text`).

## Внутренний контракт монолита

`POST /api/internal/glasses/staff-tasks` — только между контейнерами, наружу не публикуется (как `/api/internal/glasses/transcript`). Заголовок `X-Astor-Relay-Token` — тот же общий токен `ASTOR_GLASSES_RELAY_TOKEN` (≥ 16 символов, постоянное сравнение). Тело до 16 KiB:

```json
{
  "requestId": "<UUID>", "tenant": "AERIS", "staff": "<staff_id менеджера из scope очков>",
  "draft": { "assignee": "Анне", "tableCode": "5", "title": "Принести воду",
             "instruction": "Принести воду на пятый стол", "priority": "NORMAL" }
}
```

Все поля `draft`, кроме `title`, необязательны; незнакомые поля отвергаются (`role` в теле — не способ поднять права: роль берётся из конфигурации). Ответ `200` — объект `task` из раздела выше. Коды отказа: `401 UNAUTHORIZED` (нет/неверный токен), `503 RELAY_UNAVAILABLE` (токен не настроен на монолите), `503 TASKS_DISABLED` (персистентность поручений выключена), `400 MALFORMED_REQUEST`, `413 PAYLOAD_TOO_LARGE`; из правил поручений — `403 STAFF_INACTIVE` (менеджер не зарегистрирован под этой ролью или не активен), `403 FORBIDDEN`, `409 ASSIGNEE_OFF_SHIFT` (исполнитель без открытой смены — например, сам менеджер, когда задача легла на него), `409 EVENT_CONFLICT`.

Что делает монолит:

1. Проверяет токен, затем наличие `StaffPortalService` (иначе 503 `TASKS_DISABLED`).
2. Собирает `StaffScope(tenant, staff, role)`, где `role` — `ASTOR_GLASSES_STAFF_ROLE` (по умолчанию `MANAGER`; допустим `HOSTESS`; `WAITER` не стартует). Менеджер должен быть активным членом `astor_staff_members` под этой ролью — это проверка `StaffPortalService.active`.
3. Если `requestId` уже обработан — возвращает прежнюю задачу, карточку не повторяет.
4. Ищет исполнителя (`StaffAssignees`): среди активных сотрудников **с открытой сменой** — точное совпадение имени или `staff_id`, совпадение по основе слова («Анне» → «Анна», «Петровой» → «Петрова», но «Марии» ≠ «Марина»), слово роли («официанту» → единственный официант на смене). Если подходят двое или никто — исполнитель сам менеджер.
5. `StaffPortalService.create(scope, requestId, Draft("Astor Glass", tableCode | "-", title, instruction, [этап "done" без фото], priority, deadline = null, assigneeStaffId))` — одна транзакция под блокировкой tenant: задача, processed event, audit `ASSIGNED` (актор — менеджер).
6. Карточка в системный чат: «Поручение от <менеджер>: <title> — <instruction>. Стол <tableCode>. Для: <исполнитель>» (имена — из справочника). Неудача отправки не влияет на ответ.

## Где поручение потом живёт

- PostgreSQL монолита: `astor_staff_tasks` (payload задачи, `source_ref = "Astor Glass"`), `astor_staff_task_events` (идемпотентность по `requestId`), `astor_staff_task_audit` (кто и когда). Миграция `db/changelog/2026-10-05-staff-portal.yaml` уже включена в `changelog-master.yaml` и применяется Liquibase при старте бота — отдельных действий не нужно; таблицы создаются и при выключенном портале.
- Системный чат Telegram (`TELEGRAM_SYSTEM_CHAT_ID`) — карточка поручения и, следом, обычная расшифровка «вопрос + подтверждение» от transcript-релея. Пока стаф-портал не включён, это единственное место, где команда видит поручение.
- Стаф-портал, когда `astor.staff.enabled=true`: менеджер — в `GET /api/admin/staff-tasks/dashboard`, исполнитель — в `GET /api/staff/tasks` (полный snapshot назначенных ему задач на открытой смене) с командами ACCEPT/STAGE_DONE/COMPLETE. Включение портала — отдельный rollout (`STAFF_PORTAL_RUNBOOK.md`), для голосовых поручений он не нужен.

Сообщение в очки исполнителю (`POST /api/glasses/messages`) не ставится: у runtime один серверный scope — сам менеджер, который и так слышит подтверждение. Когда появятся scope для нескольких сотрудников, очередь сообщений — готовое место для «тебе поручение».

## Настройки

| Переменная | По умолчанию | Где | Назначение |
| --- | --- | --- | --- |
| `ASTOR_GLASSES_TASKS_ENABLED` | `false` | runtime очков | включает распознавание поручений; без неё всё как раньше, а `intent: "task"` получает 503 |
| `ASTOR_GLASSES_RELAY_TOKEN` | пусто | **оба** сервиса, одинаковый, ≥ 16 символов | общий токен transcript-релея и поручений |
| `ASTOR_GLASSES_STAFF_TASKS_URL` | `http://aeris-astor-butler-bot:8089/api/internal/glasses/staff-tasks` | runtime очков | адрес монолита внутри docker-сети |
| `ASTOR_GLASSES_TASK_TRIGGER_WORDS` | `задача,поручение,поручи,передай` | runtime очков | триггер-слова через запятую, регистр не важен |
| `ASTOR_GLASSES_TIMEOUT_MS` | `10000` | runtime очков | в поручение входят вызов модели и вызов Butler (до 8 с); рекомендуется `20000` |
| `ASTOR_STAFF_TASKS_ENABLED` | `false` | монолит | персистентность поручений без JWT-портала (`astor.staff.tasks-enabled`); при `false` endpoint отвечает 503 `TASKS_DISABLED` |
| `ASTOR_GLASSES_STAFF_ROLE` | `MANAGER` | монолит | роль, с которой носитель очков создаёт поручения: `MANAGER` или `HOSTESS` |
| `ASTOR_STAFF_ENABLED` | `false` | монолит | JWT-портал; для голосовых поручений не нужен и не включается |
| `TELEGRAM_SYSTEM_CHAT_ID`, `TELEGRAM_SYSTEM_NOTIFICATIONS_ENABLED` | пусто / `false` | монолит | куда уходит карточка |

Токен — только в серверных env (root 0600), не в git, не в чатах, не в мобильном клиенте. В `docker-compose.yml` переменные бота уже проброшены (`ASTOR_GLASSES_RELAY_TOKEN`, `ASTOR_STAFF_TASKS_ENABLED`, `ASTOR_GLASSES_STAFF_ROLE`); у очков — через `runtime.env` (см. `docker/glasses/runtime.env.example`).

## Справочник сотрудников: что должно быть в базе

Правила поручений требуют, чтобы и автор, и исполнитель были в `astor_staff_members`, а исполнитель — на открытой смене (внешний ключ `astor_staff_tasks → astor_staff_members`). Пока портал выключен, формы `PUT /api/admin/staff/members/{staff}` нет, поэтому пилотный справочник заводится оператором SQL в БД монолита (параметры подставлять руками, реальные идентификаторы в git не класть):

```sql
-- носитель очков: staff_id = ASTOR_GLASSES_STAFF из runtime.env очков, роль = ASTOR_GLASSES_STAFF_ROLE
INSERT INTO astor_staff_members (tenant, staff_id, display_name, role, active, shift_open)
VALUES ('<ASTOR_GLASSES_TENANT>', '<ASTOR_GLASSES_STAFF>', '<Имя менеджера>', 'MANAGER', TRUE, TRUE)
ON CONFLICT (tenant, staff_id) DO UPDATE SET role = EXCLUDED.role, active = TRUE, shift_open = TRUE;

-- сотрудники, которым можно поручать голосом (display_name — как их называют вслух)
INSERT INTO astor_staff_members (tenant, staff_id, display_name, role, active, shift_open)
VALUES ('<tenant>', 'anna', 'Анна', 'WAITER', TRUE, TRUE),
       ('<tenant>', 'ilya', 'Илья', 'WAITER', TRUE, TRUE)
ON CONFLICT (tenant, staff_id) DO NOTHING;

-- конец смены: UPDATE astor_staff_members SET shift_open = FALSE WHERE tenant = '<tenant>' AND staff_id = '<id>';
```

`tenant` должен совпадать с `ASTOR_GLASSES_TENANT` очков, `staff_id` менеджера — с `ASTOR_GLASSES_STAFF`. Смена менеджера должна быть открыта, иначе поручение «на себя» получит 409 `ASSIGNEE_OFF_SHIFT` и в наушнике будет «повторите». Когда включат портал с Keycloak, `staff_id` станет `sub` токена — записи пилота тогда нужно согласовать с реальными subject ID.

## Выкатка на VM (оба образа)

Код не деплоится этим PR; порядок для оператора после merge в `main`:

1. **Монолит (бот AERIS).** Образ `ghcr.io/astor-hospitality/astor_butler_mvp/astor-butler:main` собирается CI. На VM в `/opt/astor-butler/.env.production` добавить `ASTOR_STAFF_TASKS_ENABLED=true`, `ASTOR_GLASSES_STAFF_ROLE=MANAGER`, `ASTOR_GLASSES_RELAY_TOKEN=<новый секрет ≥ 16 символов>` (если transcript-релей ещё не включался — токен новый, сгенерировать `openssl rand -hex 24`); убедиться, что `TELEGRAM_SYSTEM_CHAT_ID` задан и `TELEGRAM_SYSTEM_NOTIFICATIONS_ENABLED=true`. Обновить checkout `/opt/astor-butler/current` (`git pull` main, в нём новый `docker-compose.yml` с пробросом) и перезапустить бота обычным путём (`deploy-final.sh` либо `docker compose … pull && up -d aeris-astor-butler-bot`). В логе старта Liquibase не должен применять новых changeSet (таблицы стафа уже есть с 2026-10-05); если БД была пустой — применит `2026-10-05-staff-portal`.
2. **Справочник.** Выполнить SQL из раздела выше в БД монолита (`docker compose exec postgres psql …`).
3. **Очки.** Собрать jar из `main`, положить в staging рядом с `docker/glasses/Dockerfile.cloud`, собрать `astor-glasses:v7` (сверить SHA-256 jar, как в `GLASSES_ASSIST_PILOT.md`). В `/opt/astor-glasses/private/runtime.env` добавить `ASTOR_GLASSES_TASKS_ENABLED=true`, тот же `ASTOR_GLASSES_RELAY_TOKEN`, при желании `ASTOR_GLASSES_TRANSCRIPT_RELAY_ENABLED=true` и `ASTOR_GLASSES_TIMEOUT_MS=20000`. `ASTOR_GLASS_VERSION=v7 docker compose -f docker/glasses/compose.yaml -f docker/glasses/compose.cloudru.yaml up -d glasses`. Оба контейнера в сети `astor-butler_default`, адрес по умолчанию `aeris-astor-butler-bot:8089` резолвится без правок.
4. **Проверка.** Из контейнера очков или с VM: `POST /api/glasses/assist` с bearer, `{"requestId":"<uuid>","text":"Задача: Анне принести воду на пятый стол"}` → `200`, `text` начинается с «Записал поручение», в ответе `task.taskId`; в системном чате — карточка; `SELECT task_id, assignee_staff_id FROM astor_staff_tasks ORDER BY changed_at DESC LIMIT 1`. Повтор с тем же `requestId` → тот же `taskId`, карточки нет. Обычный вопрос («Что по бизнес-ланчу?») отвечает как раньше. Затем — физическая проверка с телефона в чате «Проверить интеграцию ИИ с очками»: кнопка → голос → подтверждение в наушнике.
5. **Откат.** `ASTOR_GLASSES_TASKS_ENABLED=false` в runtime.env и перезапуск очков возвращает прежнее поведение без смены образа; `ASTOR_STAFF_TASKS_ENABLED=false` на боте закрывает endpoint (503). Созданные задачи остаются в БД.

Пока Cloud.ru Foundation Models отвечает 402 (тикет 426273), черновик собирается fallback-ом из самих слов (title = первое предложение) — поручение всё равно записывается, но без разбора имени и стола. С `ASTOR_GLASSES_AI_PROVIDER=gigachat` и ключом Сбера разбор работает полностью.

## Границы и ограничения

- **Первое действие очков.** До 10.10 Astor Glass только отвечал; теперь создаёт поручение — и только его. Очки не принимают, не закрывают и не переназначают задачи, не меняют брони и FSM; статус меняется только через портал/P1-команды. Границы `GLASSES_ASSIST_PILOT.md` в остальном не меняются.
- **Исполнитель — осторожно.** Угадывания между двумя людьми нет: неоднозначность и незнакомое имя оставляют поручение на менеджере, что слышно в подтверждении («записал на вас»). Переназначить можно в портале, когда он включён.
- **Стол** — как сказано; если не назван, в задаче хранится `-`, в подтверждении и ответе стола нет.
- **Модель может ошибиться** в разборе (имя, стол, заголовок); при любом сбое разбора берутся сами слова, поручение не теряется. В логах нет текста поручений, имён и токенов — только коды и HTTP-статусы.
- **Идемпотентность** — по `requestId` на обеих сторонах; повтор после обрыва связи безопасен.
- **Лимиты** прежние: 10 запросов в минуту на credential, один запрос за раз, таймаут `ASTOR_GLASSES_TIMEOUT_MS`; аудио ≤ 30 с.
- **Один scope.** Поручение всегда от того единственного сотрудника, к которому привязан серверный bearer; роль ему назначает конфигурация монолита, а не очки и не тело запроса.

## Проверки

Полный offline-suite Maven зелёный (команда `mvn -o -B test`, отчёты в `target/surefire-reports`). Новые и дополненные тесты:

- Runtime очков: `GlassesVoiceTasksTest` (триггер-слова и отрицательные случаи, явный `intent`, настройка списка, разбор JSON модели, обёртки/fences, обрезка заголовка, fallback при любом сбое, подтверждения, `eventId = requestId`, отказ Butler), `GlassesStaffTaskRelayTest` (заголовок токена, тело, разбор receipt, отказы 401/503/мусор/сеть → `TASK_UNAVAILABLE`), `GlassesControllerTest` (`intent: "task"`, триггер без intent, неизвестный intent и фото → 400, выключенная функция, отказ Butler с сохранением готовности текста), `GlassesAssistServiceTest` (аудио → поручение, transcript-релей получает слова и подтверждение, повтор из кеша без второй задачи, `intent: "assist"`).
- Монолит: `GlassesStaffTaskControllerTest` (401 без/с неверным токеном, 503 `RELAY_UNAVAILABLE`/`TASKS_DISABLED`, happy path с Draft и карточкой, исполнитель по имени/роли/на себя, повтор `requestId` без второй карточки, отказ правил с кодом, роль из конфигурации, malformed), `StaffAssigneesTest` (падежи, фамилии, роли, смена/активность, неоднозначность), `StaffTasksConfigurationTest` (сервис есть при `tasks-enabled` без портала; портал — отдельно), `TelegramGlassesTaskTest` (текст карточки, выключенные уведомления, сбой бота).

## Открытые вопросы

1. Реальный `staff_id` и имена сотрудников для `astor_staff_members` в AERIS (сейчас — SQL оператором; с Keycloak — `sub`).
2. Чат для карточек: сейчас системный (`TELEGRAM_SYSTEM_CHAT_ID`), как у расшифровок. Нужен ли отдельный staff/ops-чат (`TELEGRAM_OPS_CHAT_ID` / `TELEGRAM_HOSTESS_CHAT_ID`) — решение Михаила.
3. Как исполнитель без портала узнаёт о поручении — пока только из карточки в чате; очередь сообщений в очки заработает, когда появятся scope для нескольких сотрудников.
4. Жест для `intent: "task"` в iOS-приложении (`clients/ios-glasses`) — этим PR не делается; до него работают триггер-слова.
5. Включать ли transcript-релей вместе с поручениями (тогда в чате и слова, и подтверждение) — рекомендуется, но это отдельный флаг.
