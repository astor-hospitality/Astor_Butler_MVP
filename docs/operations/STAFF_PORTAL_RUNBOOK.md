# Staff portal — PostgreSQL / отдельный вход Astor

Дата: 2026-10-05. Это инструкция включения **после** ревью/CI и согласования стенда; не подтверждение production deployment. VEDAL, C3AG identities/credentials и изолированный glasses pilot не изменяются.

## Границы

- `/staff/` — кабинет менеджера/хостес. Нет demo JSON, локальных бизнес-переходов, «сыграть за официанта» и фиктивного фото ACK.
- Данные: `astor_staff_members`, `astor_staff_tasks`, `astor_staff_task_events`, `astor_staff_task_audit`, tenant lock в PostgreSQL **монолита Astor**.
- Менеджер/хостес создают, отменяют, переназначают поручения, разрешают HELP. Только MANAGER ведёт справочник/смены. Сотрудник принимает, закрывает этапы и завершает **свою** задачу с открытой сменой.
- Пилотный bearer `/api/glasses/assist` не является staff JWT и не разрешает изменения задач. Staff API не вызывает guest FSM/booking/notifications. Реальная интеграция Concierge→staff остаётся отдельной работой; `sourceRef` сейчас — только метка, не ссылка с авторизацией.
- Фото `/api/staff/tasks/{task}/evidence` пока **503 EVIDENCE_UNAVAILABLE**. Новые задачи UI создаются без обязательного фото; API может задать `evidenceRequired=true`, но такой этап нельзя закрыть до появления реального storage. Изолированный pilot S3/step ACK не подключён автоматически к этому домену.

## 1. Отдельный Keycloak Astor

### Изолированная инфраструктура — 2026-10-06

Исходники deployment: `docker/keycloak/`. Выбран существующий Astor origin
`https://c3ag.ru` (витрина `/astor/` уже имеет TLS), отдельный prefix
`/astor-auth`. Новые DNS/покупки/сертификаты не требуются. Образец VEDAL прочитан
read-only: используем модель issuer/audience/PKCE, **не** его `start-dev`,
открытый Docker port, БД, realm, пользователей, пароли или theme.

- Production optimized Keycloak **26.8.0**; `KC_HTTP_RELATIVE_PATH` и management
  path заданы на build stage (в 26.8 это build-time settings).
- Standalone Compose project `astor-identity`, отдельный PostgreSQL16, volume
  `astor-identity_identity-postgres`. Это **не** live `astor_postgres_test`.
- Только identity Keycloak/PG в приватной database network. Отдельная private
  proxy network `astor-identity_proxy` связывает Keycloak со шлюзом; PG к ней
  не подключён. VEDAL и монолит к новым сетям не подключаются.
- Public paths: `/astor-auth/realms/astor/` и `/astor-auth/resources/`. Все прочие
  `/astor-auth/` (включая admin/master/health/metrics) — 404. Служебный port 18880
  доступен только на `127.0.0.1` для операторского CLI через существующий SSH.
  Публичную admin UI не открывать ради onboarding.
  Keycloak имеет отдельную operator network для Docker loopback publishing;
  без неё Docker не публикует port при all-internal stack. Другие сервисы
  к operator network не подключаются; PG остаётся только на internal database.
- Realm `astor`, public client `astor-staff-ui` с exact callback/logout
  `https://c3ag.ru/astor/staff/`, origin `https://c3ag.ru`, S256; API audience
  `astor-api`; token 300s; tenant admin-only. Мобильный client пока не импортируем:
  физический custom-scheme callback ещё не принят.
- Никаких реальных staff accounts импортом. Bootstrap master operator
  `astor-bootstrap` имеет случайный 256-bit пароль только в root-private runtime.
  После создания постоянного именного оператора с MFA временный bootstrap
  должен быть удалён оператором; runtime file не отправлять в чат/Telegram.

Issuer: `https://c3ag.ru/astor-auth/realms/astor`.
JWKS: `https://c3ag.ru/astor-auth/realms/astor/protocol/openid-connect/certs`.
Эти адреса можно использовать для PR25 **после** отдельного DB/rollout approval;
само наличие Keycloak не включает staff API и не применяет миграцию монолита.

#### Воспроизводимость и секреты

На VM source живёт в `/opt/astor-identity/source`, runtime —
`/opt/astor-identity/runtime` (root, 0700). `prepare-runtime.sh` создаёт новые
пароли через openssl, не печатает их и не перезаписывает существующие. Password
files имеют uid1000/mode0400 для readonly mount только в новые containers.
PostgreSQL использует `POSTGRES_PASSWORD_FILE`, Keycloak entrypoint читает
файлы внутри контейнера; Compose/images/git не содержат пароль.

`images.env` (0600, только digests, вне git) фиксирует base images:

- Keycloak: `quay.io/keycloak/keycloak@sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc`;
- PostgreSQL: `postgres@sha256:42df6755a4110ea9e324bfaefd02eb2f6afc0f0b9bc063c55c9f73dc29d25770`.

Compose command для **этого** стека: из `/opt/astor-identity/source`, operator
`docker compose --env-file /opt/astor-identity/runtime/images.env -f compose.yaml`.
Не запускать общий `docker compose up` проекта ради identity.

#### Gateway lifecycle и откат

В live template добавляются **только** self-contained locations из
`docker/keycloak/nginx-locations.conf` внутри существующего HTTPS c3ag.ru server.
До записи: checksum/CAS сверка текущего template, backup template и effective
config в root-private runtime, `nginx -t` candidate. Затем `nginx -t` и scoped
reload, **без** restart/recreate шлюза. Media64m/buffering-off, glasses5m,
`/api/astor/messages`, C3AG/VEDAL и conf.d routes сохранить без изменений.

При следующем **recreate** шлюза включить
`docker/keycloak/gateway-network.override.yaml` вместе с его existing Compose
files: ручное `docker network connect` переживает restart/host reboot, но не
создание нового контейнера. Не заменять production nginx полным старым repo
template: там отсутствуют другие live domains. Применять scoped fragment к
текущему operator-managed template и проверять semantic diff.

Rollback identity: удалить только добавленный `/astor-auth` fragment (или вернуть
backup, **только если** после него не было чужих gateway changes), nginx-t/reload;
остановить только `astor-identity` Compose **без `-v`**, disconnect его proxy
network от gateway после удаления routes. БД/volume/secrets сохраняются;
`down -v`, realm overwrite/import override, truncate запрещены без отдельного
решения. Откат identity не требует restart монолита/VEDAL/glasses.

#### Проверки

`node --test docker/keycloak/tests/*.test.mjs` — manifest/boundaries, не live
подтверждение. `smoke-identity.py` по умолчанию делает read-only HTTPS проверки:
discovery/JWKS, spoof headers, запрет admin/master, exact callback/S256/assets.
Только operator с явным `--allow-ephemeral-user` создаёт один synthetic Astor
waiter, проходит настоящий code exchange, проверяет RS256 подпись по JWKS,
issuer/aud/tenant/role/exp, CORS, replay/неверный verifier/logout и удаляет
synthetic account в finally. Не печатает токены/пароли и не обращается к staff
API/БД монолита. Реальный менеджер/directory/bootstrap и вход самого кабинета
проверяются отдельно после согласованной migration.

Live acceptance 2026-10-06: own PostgreSQL/Keycloak healthy; публичный TLS
discovery/JWKS и assets 200, admin/master/health/metrics 404. Полный synthetic
PKCE smoke прошёл, включая реальный code exchange, RS256/JWKS signature,
issuer/aud/tenant/role/300s expiry, CORS, replay/wrong verifier и logout;
synthetic account удалён (204). В 26.8 missing PKCE корректно возвращает
302 с OAuth `invalid_request` на уже проверенный callback, без code/form;
это отказ, не успешный вход. Credential/token values не печатались.
Изоляция проверена по container metadata: live Astor DB/монолит/VEDAL/glasses
не рестартовали и не подключали к identity networks; gateway только reload,
его StartedAt не изменился. C3AG/Astor/VEDAL frontend и monolith health 200;
media64m/unbuffered и glasses5m locations сохранены. Staff API всё ещё не deployed.

Основа: [Keycloak container build](https://www.keycloak.org/server/containers),
[hostname](https://www.keycloak.org/server/hostname),
[reverse proxy](https://www.keycloak.org/server/reverseproxy).

Оператор создаёт отдельный Astor identity-контур; не подключает realm/пользователей VEDAL. Использовать HTTPS с проверяемым сертификатом. Не публиковать административную консоль/права или создавать общие логины для команды.

- Realm/issuer Astor, стабильный HTTPS JWKS; RS256.
- API audience: `astor-api`.
- Public client: `astor-staff-ui`, **Standard Flow + PKCE S256**; без client secret, implicit flow и password grant. Exact redirect URI кабинета, например `https://<ASTOR_HOST>/astor/staff/`; Web Origins — только его HTTPS origin, не `*`.
- Access token: `sub` существующего пользователя, одна строка `tenant`, `aud` содержит `astor-api`, обязательный `exp`, разрешённые роли `astor-manager`, `astor-hostess`, `astor-waiter` в `realm_access.roles` либо `resource_access.astor-api.roles`. Роль/tenant берутся из проверенного токена, не query/body/frontend.
- Рекомендуемый access-token lifespan для pilot — 5 минут. Браузер держит access token только в памяти; verifier/state на 5 минут — в sessionStorage, удаляются при callback/logout. Refresh token не сохраняется, после expiry нужен повторный вход. Сам refresh страницы тоже требует нового auth redirect.
- Subject/tenant/роль в PostgreSQL должны совпадать с токеном. Изменение directory role или `active=false` блокирует старый JWT при следующем запросе; изменение только роли в Keycloak может действовать до expiry, поэтому отзыв нужно отражать и в directory. UI регистрация **не** создаёт identity и не выдаёт JWT-роли.

## 2. Миграция и первый менеджер

Добавлена только новая migration `2026-10-05-staff-portal`; ранее применённые changeset не редактировать. До любого запуска на существующей БД после Boot 4 проверить backup/restore, историю `DATABASECHANGELOG`, пути/checksum, Liquibase plan. Не применять автоматически `clearCheckSums`/`changelogSync` и не считать тест новой пустой schema проверкой боевых миграций.

Новая migration создаёт таблицы, **не** seed-ит пользователей/пароли/поручения. Первый менеджер — зарегистрированный subject из Astor Keycloak, привязанный оператором к нужному tenant:

```sql
-- Подготовленный запрос: параметры отдельно, не вставлять реальные identities/credentials в git.
INSERT INTO astor_staff_members (tenant, staff_id, display_name, role, active)
VALUES (?, ?, ?, 'MANAGER', TRUE);
```

Не выполнять этот пример над production автоматически. Менеджер после входа регистрирует существующие subject IDs сотрудников, а затем открывает их смены. Directory роль должна совпадать с widest Astor-ролью токена. Права аккаунта выдаются отдельно оператором Keycloak, не формой.

## 3. Server settings и gateway

Spring Boot свойства (показаны **без реальных значений/секретов**):

```properties
astor.staff.enabled=true
astor.staff.issuer-uri=https://<ASTOR_KEYCLOAK_HOST>/realms/astor
astor.staff.jwk-set-uri=https://<ASTOR_KEYCLOAK_HOST>/realms/astor/protocol/openid-connect/certs
```

Disabled default — staff routes deny-all, даже при существующем общем `permitAll`. Bad/non-HTTPS issuer/JWKS при включении не позволяют стартовать staff security. Используются datasource/transaction manager монолита; не добавлять shared VEDAL DB credentials.

В HTTPS gateway на **том же origin, что кабинет**, направить только:

- `/api/staff/` → Astor monolith;
- `/api/admin/staff/` → Astor monolith;
- `/api/admin/staff-tasks/` и exact `/api/admin/staff-tasks` → Astor monolith.

Сохранить Authorization; не маршрутизировать весь `/api/` или legacy booking API наружу. JSON limit 64 KiB, короткие timeout, без логирования body/Bearer. Пример location-фрагментов: `docker/staff/gateway-locations.conf.example`. Upstream/сеть выбираются оператором и не угаданы из старого адреса VM.

Static worker публикует только HTML/JS/CSS и **не** proxy-ит staff API. Python server на 8765 тоже только static preview: 404 login-config — ожидаемое отсутствие backend, а не live-данные. При публикации под `/astor/` gateway снимает этот prefix только со static files, API остаётся абсолютным `/api/...`; `/staff` должен редиректить в `/staff/`, чтобы относительные assets и exact OIDC callback работали одинаково.

Для staff HTML/JS: `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, запрет indexing. Не кэшировать OAuth callback/данные кабинета в CDN/service worker. Для API Spring Security выставляет no-store; не включать proxy cache. Frontend fetch `credentials: omit`, Bearer, no-store; CORS Keycloak только exact origin. Requests/token exchange имеют 15s deadline.

## 4. API и согласованность

| Route | Назначение |
| --- | --- |
| `GET /api/staff/login-config` | Public enabled/issuer/clientId, без секретов |
| `GET /api/admin/staff-tasks/dashboard` | Tenant staff/tasks + `manageStaff` |
| `GET /api/admin/staff-tasks/{task}/history` | Tenant task audit |
| `POST /api/admin/staff-tasks` | `{eventId, task}`; Draft из task-domain |
| `POST .../{task}/reassign`, `cancel`, `resolve-help` | `{eventId, expectedVersion, staffId?}` |
| `PUT /api/admin/staff/members/{subject}` | `{displayName,role,active}` — MANAGER |
| `POST .../members/{subject}/shift` | `{open,deviceId}` — MANAGER |
| `GET /api/staff/tasks` | `{items,fullSnapshot:true}` — только caller assignments/открытая смена |
| `POST /api/staff/tasks/{task}/commands` | `{eventId,type,expectedVersion,stageCode?}` |
| `POST .../{task}/delivery` | `{eventId,kind}`; delivery/voiced не accept |
| `POST .../{task}/evidence` | Пока 503, без фальшивой записи |

Все task mutations в одной PostgreSQL transaction: tenant-row lock → актуальные права → task CAS → processed-event result → audit. Одновременно работающие экземпляры не теряют same-version delivery marks. Same event/request возвращает предыдущий result; same event/другой request — 409; устаревшая version — 409. При повторе снова проверяются текущие role/activity/shift/ownership. Directory/shift edits также берут tenant lock; обновление metadata и task mutations не являются guest FSM.

Каждые 15s видимый авторизованный кабинет получает полный snapshot; старый исполнитель теряет переназначение. Client сохраняет последний snapshot при сетевом сбое, явно отмечает его устаревшим и запрещает действия; 401/403 очищают данные. Изменение считается успешным только по server acknowledgement, UUID повторяется при сетевом retry. ACK с последующим неудачным refresh оставляет явно устаревший экран, а не объявляет новые данные актуальными.

Pilot limits: 500 задач в manager snapshot / own staff snapshot, 200 directory members, 100 записей task history. History ограничена последними 100, это не полный экспорт audit. При превышении snapshot/directory лимита — 503 `SNAPSHOT_LIMIT`, **не** молчаливое удаление элементов из «полного» snapshot. Для большего tenant нужен отдельный pagination/archive slice; durable rows/events сами не удаляются. Incremental cursor/tombstones и offline client очередь пока не обещаются.

## 5. Проверки до включения

- [ ] Локальные + GitHub CI: security/JWT/access/body limit, реальный PostgreSQL persistence/restart, cross-instance duplicate, rollback, stale version, reassignment и cached replay после закрытия смены/отзыва роли.
- [ ] На согласованной непроизводственной БД применён проверенный Liquibase plan, два Astor staff identities + manager созданы вручную, directory соответствует JWT.
- [ ] HTTPS static + API same-origin, login/code exchange/expiry/logout проверены с реальным **отдельным** Keycloak; VEDAL не затронут.
- [ ] Manager создаёт задачу → только назначенный staff на смене принимает → stages/complete → manager видит audit. Чужой tenant/assignee и закрытая смена запрещены; manager не нажимает ACCEPT за официанта.
- [ ] Нет real photo success в staff API без storage. Подключение iPhone/pilot S3 к staff context/ACK/evidence — отдельный PR и аппаратная приёмка.
- [ ] Production window/rollback/DB backup подтверждены отдельно. Никакой deploy/restart только по факту merged-кода.

Local reproducible tests use a disposable PostgreSQL16 fixture on loopback with database name **staff_portal_test** and public fixture-only password. `ASTOR_STAFF_TEST_JDBC` нельзя направлять в другую БД: тесты отказывают non-loopback/non-fixture URL и используют отдельную случайную schema на каждый test. Без env PG tests skipped; CI всегда задаёт env и PostgreSQL service. Java25 matches current main (старое описание Java21 не соответствует текущему pom).
