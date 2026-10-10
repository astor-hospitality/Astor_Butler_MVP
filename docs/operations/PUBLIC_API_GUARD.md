# Охрана непубличного API Butler: внутренний токен и allow-list на edge

Дата: 2026-10-10. Ветка `fix/public-api-guard`. Статус: код и конфиги в репозитории, **на VM не выкачено** — порядок выката ниже.

Связанные документы: `CLOUDRU_DEPLOY_RUNBOOK.md` (раскладка VM и Caddy), `GLASSES_TRANSCRIPT_RELAY.md` (собственный токен релея очков), `STAFF_PORTAL_RUNBOOK.md` (собственная JWT-цепочка стаф-портала), `docs/architecture/WEB_CHANNEL_ADAPTER.md` (веб-канал, PR #118).

## 1. Что было открыто

Маршрут из Интернета до бота был сквозным и без аутентификации:

```
Caddy (vedal-proxy, vhost c3ag.ru)      @backend path /api/* /auth/* …  → astor-api-gateway:8080
nginx api-gateway                        location /api/  → aeris-astor-butler-bot:8089
Butler  config/SecurityConfig.java       anyRequest().permitAll(), CSRF выключен, фильтра токена нет
```

Поэтому любой клиент снаружи мог:

| Запрос | Что делал |
| --- | --- |
| `POST /api/messages` с `{"channel":"TELEGRAM","chatId":<чужой chatId>,…}` | Прогонял FSM от имени этого гостя: давал согласие, стирал черновики, через «Изменить / отменить» отменял реальные брони, создавал брони под сохранёнными именем и телефоном |
| `GET /api/bookings/table-reservations/telegram/{chatId}` | Отдавал брони любого гостя вместе с телефоном |
| `PUT /api/fsm/telegram/{chatId}/state` | Переставлял состояние FSM гостя |
| `POST /api/bookings/table-reservations/{id}/confirm` и `/reject` | Подтверждал и отклонял брони от имени хостес |
| `POST /api/bookings/table-reservations` | Создавал холды столов |
| `GET /api/concierge/requests/telegram/{chatId}` | Отдавал запросы консьержа гостя |

Легитимные публичные вызовы: виджет c3ag.ru (`POST /api/messages`, channel `WEB`), виджет сайта Astor (`/api/astor/messages`), речь веб-чата (`/api/chat/*`), очки (`/api/glasses/*`, bearer внутри), стаф-портал (`/api/staff/**`, `/api/admin/staff/**`, `/api/admin/staff-tasks/**`, своя JWT-цепочка, `denyAll` пока выключен), сессия браузера (`/api/auth/me`, `/api/auth/logout`, `/auth/*`).

## 2. Что делает фикс — три слоя

### 2.1. Butler: `config/InternalApiGuardFilter` + `config/SecurityConfig`

Фильтр стоит в основной цепочке Spring Security сразу после CORS-фильтра (после HTTP-firewall, который уже отбросил `..;/`, `%2e` и двойные слеши). Списки путей — явные константы в классе.

| Группа | Пути | Правило |
| --- | --- | --- |
| Внутренние (`INTERNAL_PATHS`) | `/api/bookings/**`, `/api/fsm/**`, `/api/admin/**`, `/api/internal/**`, `/api/concierge/**`, `/actuator/**` | Нужен заголовок `X-Astor-Internal-Token`, равный `ASTOR_INTERNAL_API_TOKEN`. Нет или неверный → **401** `UNAUTHORIZED`, `details.reason=INTERNAL_TOKEN_REQUIRED`. До контроллера запрос не доходит |
| Исключения внутри них (`PUBLIC_EXCEPTIONS`) | `/api/admin/staff/**`, `/api/admin/staff-tasks/**` (JWT стаф-портала), `/api/internal/glasses/**` — релей очков `transcript` и `staff-tasks` (PR #121), оба проверяют свой `X-Astor-Relay-Token`, runtime очков второй токен слать не умеет, `/actuator/health`, `/actuator/health/**`, `/actuator/prometheus` (Prometheus 2.x не умеет свои заголовки; снаружи всё равно 404) | Фильтр не трогает |
| Шлюз сообщений | `POST /api/messages` | Без заголовка проходит только `channel: WEB` (или без канала — контроллер считает его WEB). `TELEGRAM`, `INTERNAL` и любой другой без заголовка → **403** `FORBIDDEN`, `details.reason=INTERNAL_CHANNEL_REQUIRES_TOKEN`, `details.channel`. С верным заголовком проходит любой канал. Заголовок есть, но неверный → 401 независимо от канала. Анонимное тело больше 256 KiB → 413. Тело после просмотра отдаётся контроллеру без изменений |
| Публичные, не тронуты | `/api/glasses/**`, `/api/chat/**`, `/api/staff/**`, `/api/astor/**`, `/api/web/**`, `/api/auth/**` и остальные `/api/*` | Как раньше |

Токен: переменная `ASTOR_INTERNAL_API_TOKEN` → свойство `astor.security.internal-api-token`, минимум 16 символов. **Пустой или короткий токен = fail closed**: внутренние пути отвечают 401 всем, при старте один `WARN` с именем переменной. Сравнение постоянное по времени. Значение заголовка в логи не пишется; отказ логируется одной строкой `WARN` (метод, путь, адрес, причина).

Тесты: `src/test/java/museon_online/astor_butler/config/InternalApiGuardFilterTest.java` — MockMvc через настоящую цепочку `SecurityConfig`.

### 2.2. Gateway nginx: `docker/nginx/nginx.conf.template`

Тот же allow-list явными `location`: `/api/messages`, `/api/astor/`, `/api/chat/`, `/api/glasses/`, `/api/staff/`, `/api/admin/staff`, `/api/auth/`, `/actuator/health`, `/actuator/prometheus`. Остальное под `/api/` и `/actuator/` проксируется только при наличии заголовка `X-Astor-Internal-Token` (значение проверяет бот), иначе `404`. Так gateway не становится второй публичной дверью там, где он опубликован на host-порт (`docker-compose.prod.yml`).

### 2.3. Edge Caddy: `infra/cloudru/edge/c3ag.caddy`

Вместо `@backend path /api/* …` — явный allow-list:

```
/api/messages  /api/astor/*  /api/chat/*  /api/staff/*  /api/admin/staff*  /api/auth/*  /auth/*  /actuator/health  /gateway/health
```

`/api/glasses/*` по-прежнему уходит в `astor_glasses_api:8091`. Всё остальное под `/api/*` и `/actuator/*` — `respond 404` до любого upstream. Тот же список стоит на техническом имени `api.c3ag.176-123-165-162.nip.io` (swagger на нём больше не виден). Публичного `/api/leads*` в контроллерах нет — в список не добавлен.

`/api/concierge/*` **не** проксируется на Butler: у Butler под этим префиксом только `ConciergeRequestController` с выдачей по chatId. Веб-канал Concierge (Astor_Concierge PR #28, `POST /api/concierge/messages`, порт 8096) живёт в другом контейнере — для него в сниппете оставлен закомментированный блок, который включается, когда контейнер войдёт в сеть `edge`.

## 3. Кто должен слать токен

| Вызывающий | Путь | Нужен ли `X-Astor-Internal-Token` |
| --- | --- | --- |
| Concierge → Butler по docker-сети (`http://aeris-astor-butler-bot:8089`) | `POST /api/bookings/table-reservations`, чтение статусов `/api/bookings/**`, `/api/messages` с `channel` ≠ `WEB` | **Да** |
| Ops / curl изнутри docker-сети | `/api/bookings/**`, `/api/fsm/**`, `/api/admin/**`, `/actuator/**` | **Да** |
| Виджет c3ag.ru, `AstorWebRelay` (`/api/astor/messages` → `/api/messages` channel WEB) | `/api/messages` | Нет |
| Runtime очков → релей | `/api/internal/glasses/transcript`, `/api/internal/glasses/staff-tasks` | Нет — свой `X-Astor-Relay-Token` |
| Prometheus (`docker/prometheus/prometheus.yml`, через `api-gateway:8080`) | `/actuator/prometheus` | Нет — исключение; снаружи 404 |
| Стаф-портал | `/api/staff/**`, `/api/admin/staff*` | Нет — своя JWT-цепочка |

**Concierge.** Бот Concierge (`Astor_Concierge`, TypeScript, `src/butler/client.ts`) ходит в Butler по docker-сети `BUTLER_API_BASE_URL=http://aeris_astor_butler_bot:8089`: `POST /api/bookings/table-reservations`, `GET /api/bookings/table-reservations/{id}`, `GET /api/bookings/tables/availability`, `POST /api/concierge/requests`. Все четыре пути после фикса внутренние, поэтому в Concierge есть парный PR `fix(butler): send the internal API token to Butler`: переменная `ASTOR_INTERNAL_API_TOKEN` в `src/config.ts` и `.env.example`, заголовок `X-Astor-Internal-Token` на каждый запрос к Butler, когда переменная задана. Значение — то же, что в `/opt/astor-butler/.env.production`. Пока Butler без фильтра, лишний заголовок он игнорирует — поэтому Concierge выкатывается **первым**. Без токена Butler ответит 401, и бронь из Concierge не создастся: это ожидаемое fail closed.

## 4. Порядок выката на VM (176.123.165.162)

Порядок важен: **Concierge → Butler + gateway → Caddy**. Concierge с заголовком работает и против старого Butler (заголовок игнорируется); Butler с фильтром против старого Concierge ломает брони (401). Каждый шаг откатывается отдельно (§5).

Предусловие: оба PR смержены в `main`; образ `astor-butler:main` собран workflow `Deploy to Cloud.ru VM` или `git pull` в `/opt/astor-butler/current`; Concierge выкатывается своим `scripts/deploy-vm.sh` из его репозитория. Все команды — от пользователя из группы `docker`.

```bash
# 1. Один токен на оба окружения. Значение в чат и в git не попадает.
umask 077
TOKEN=$(openssl rand -hex 32)
printf 'ASTOR_INTERNAL_API_TOKEN=%s\n' "$TOKEN" | sudo tee -a /opt/astor-butler/.env.production >/dev/null
printf 'ASTOR_INTERNAL_API_TOKEN=%s\n' "$TOKEN" | sudo tee -a /opt/astor-concierge/.env >/dev/null   # путь .env Concierge — по его DEPLOY_VM.md
unset TOKEN
sudo chown root:docker /opt/astor-butler/.env.production && sudo chmod 0640 /opt/astor-butler/.env.production

# 1a. Сначала Concierge: новый образ с заголовком (из репозитория Astor_Concierge, его runbook docs/operations/DEPLOY_VM.md)
cd /opt/astor-concierge && bash scripts/deploy-vm.sh main
docker compose logs --tail=50 concierge | grep -ciE 'ConfigError|ASTOR_INTERNAL_API_TOKEN'   # ожидается 0

# 2. Butler: проверить compose без вывода значений
cd /opt/astor-butler/current
docker compose --env-file ../.env.production --env-file images.env \
  -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudru.yml \
  --profile frontend --profile telegram config --quiet

# 3. Перезапустить gateway (новый nginx-шаблон) и ботов (новый образ + токен)
docker compose --env-file ../.env.production --env-file images.env \
  -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudru.yml \
  --profile frontend --profile telegram up -d --force-recreate api-gateway aeris-astor-butler-bot
# если подняты c3flex-astor-butler-bot / smart-solution-bot — добавить их в ту же команду
docker logs aeris_astor_butler_bot 2>&1 | grep -c 'ASTOR_INTERNAL_API_TOKEN'   # ожидается 0: WARN «is not set» нет
docker compose --env-file ../.env.production --env-file images.env \
  -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudru.yml ps

# 4. Caddy: сохранить старый сниппет, установить новый, validate ДО reload
sudo cp /opt/edge/sites.d/c3ag.caddy /opt/edge/sites.d/c3ag.caddy.bak-$(date +%F)
sudo install -m 0644 infra/cloudru/edge/c3ag.caddy /opt/edge/sites.d/c3ag.caddy
docker exec vedal-proxy caddy validate --config /etc/caddy/Caddyfile
docker exec vedal-proxy caddy reload --config /etc/caddy/Caddyfile
```

Если путь include другой — посмотреть `docker exec vedal-proxy cat /etc/caddy/Caddyfile` и `docker inspect vedal-proxy --format '{{json .Mounts}}'`.

### Smoke после выката

```bash
# edge: внутренние пути закрыты до upstream
curl -s -o /dev/null -w '%{http_code}\n' https://c3ag.ru/api/bookings/table-reservations/telegram/1   # 404
curl -s -o /dev/null -w '%{http_code}\n' https://c3ag.ru/api/fsm/telegram/1/state                     # 404
curl -s -o /dev/null -w '%{http_code}\n' https://c3ag.ru/api/concierge/requests/telegram/1            # 404
curl -s -o /dev/null -w '%{http_code}\n' https://c3ag.ru/actuator/metrics                             # 404
curl -s -o /dev/null -w '%{http_code}\n' https://c3ag.ru/actuator/health                              # 200

# Butler: /api/messages открыт для WEB, закрыт для TELEGRAM без токена (403 = запрос дошёл до фильтра)
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://c3ag.ru/api/messages \
  -H 'Content-Type: application/json' -d '{"channel":"TELEGRAM","chatId":1,"text":"x"}'           # 403
curl -s -X POST https://c3ag.ru/api/messages -H 'Content-Type: application/json' \
  -d '{"channel":"WEB","text":"smoke: проверка после выката, не отвечать","payload":{"sessionId":"web-smoke-1","site":"c3ag"}}'
#   → 200 и JSON с nextState WEB_LEAD_RECEIVED. Внимание: это настоящий веб-лид, он уйдёт в админ-чат.

# сайты живы
curl -s -o /dev/null -w '%{http_code}\n' https://c3ag.ru/          # 200
curl -s -o /dev/null -w '%{http_code}\n' https://vedal-med.ru/     # 200

# изнутри docker-сети с токеном внутренний путь проходит до контроллера (200 или 404 от контроллера, не 401)
TOKEN=$(sudo grep '^ASTOR_INTERNAL_API_TOKEN=' /opt/astor-butler/.env.production | cut -d= -f2-)
docker run --rm --network astor-butler_default curlimages/curl -s -o /dev/null -w '%{http_code}\n' \
  -H "X-Astor-Internal-Token: $TOKEN" http://aeris-astor-butler-bot:8089/api/bookings/table-reservations/telegram/1
docker run --rm --network astor-butler_default curlimages/curl -s -o /dev/null -w '%{http_code}\n' \
  http://aeris-astor-butler-bot:8089/api/bookings/table-reservations/telegram/1                     # 401
unset TOKEN

# Concierge → Butler. Вариант А (без создания брони): availability из контейнера Concierge его же окружением —
# 200 означает, что токен в .env Concierge совпадает с Butler.
docker compose -f /opt/astor-concierge/docker-compose.yml exec concierge node -e '
  fetch(process.env.BUTLER_API_BASE_URL + "/api/bookings/tables/availability?venueCode=AERIS&from=2026-12-01T10:00:00Z&to=2026-12-01T12:00:00Z&partySize=2",
    { headers: { "x-astor-internal-token": process.env.ASTOR_INTERNAL_API_TOKEN ?? "" } }).then(r => console.log(r.status))'   # 200, не 401
# Вариант Б (боевой путь): тестовая бронь из бота Concierge в Telegram → в Butler появляется заявка со статусом
# AWAITING_MANAGER_CONFIRMATION и хостес видит её в своём чате. Делать только по согласованию: это настоящая заявка.
```

## 5. Откат

Слои независимы, откатывать можно по одному.

- **Caddy**: `sudo cp /opt/edge/sites.d/c3ag.caddy.bak-<дата> /opt/edge/sites.d/c3ag.caddy && docker exec vedal-proxy caddy validate --config /etc/caddy/Caddyfile && docker exec vedal-proxy caddy reload --config /etc/caddy/Caddyfile`.
- **Gateway и боты**: `bash /opt/astor-butler/current/scripts/deploy/cloudru-deploy.sh /opt/astor-butler rollback <sha> backend` на предыдущий образ (см. `CLOUDRU_DEPLOY_RUNBOOK.md`). Строку `ASTOR_INTERNAL_API_TOKEN` в `.env.production` можно оставить — старый образ её не читает.
- **Concierge**: откат образа его `scripts/deploy-vm.sh <предыдущий ref>`; заголовок без фильтра в Butler безвреден, строку в `.env` можно оставить.
- **Только Butler-фильтр не отключается флагом** намеренно: пустой токен закрывает внутренние пути, а не открывает. Если нужно временно открыть внутренний путь для отладки — делать это изнутри docker-сети с токеном, не через edge.

Данные и миграции фикс не трогает.

## 6. Что остаётся открытым

- Остальные `/api/*` контроллеры (users, consents, preferences, timelines, ops, manager, integrations, payments, content, media, merch, donations, tips, feedback, notifications, auctions, posts, system) по-прежнему `permitAll` внутри docker-сети; снаружи они закрыты edge (404). Добавить их в `INTERNAL_PATHS` — одна строка в константе, когда будет понятно, кто из них нужен Concierge.
- `/internal/*` (`ObservabilityController`) доступен через `location /` в nginx, но edge его не проксирует.
- Prometheus на Cloud.ru VM не поднят (профиль `observability`); исключение `/actuator/prometheus` сделано ради локального стенда и будущего включения.
- Фактический путь include сниппета в общем Caddyfile Vedal, путь `.env` Concierge на VM и имя контейнера Concierge для `/api/concierge/messages` — проверить при выкате.
- Prometheus 3.x умеет `http_headers`; при обновлении можно убрать исключение и слать токен из scrape-config.
