# Cloud.ru Deploy Runbook: c3ag.ru и AERIS на общей VM

Дата: 2026-10-08

Цель: перенести публичный C3AG frontend (сначала) и AERIS/Butler backend (потом) с Yandex VM `51.250.31.97` на Cloud.ru Evolution VM, которую Astor делит с порталом Vedal. Деплой идёт через GitHub Actions: образы собираются в GHCR, VM только `pull && up -d`. Ничего не собирается на сервере, исходники на сервер не копируются.

Параметры стенда (меняются в одном месте: GitHub secrets/vars, DNS и этот файл):

```text
CLOUDRU_IP        176.123.165.162
OS                Ubuntu 24.04, 4 vCPU / 16 GB, 100 GB SSD
SSH               ubuntu@CLOUDRU_IP :22 (ключи, без пароля)
Astor path        /opt/astor-butler
Vedal path        /opt/vedal-portal (репозиторий astor-hospitality/MuseonUrania)
```

Файлы, которые описывает этот runbook:

```text
docker-compose.cloudru.yml                  overlay для Cloud.ru (последний -f)
infra/cloudru/edge/c3ag.caddy               сайт-блок для общего Caddy на стороне Vedal
scripts/deploy/cloudru-deploy.sh            выполняется на VM по SSH из workflow
scripts/deploy/test-cloudru-deploy.sh       mock-тест guard-логики (запускается в CI)
.github/workflows/deploy-cloudru-vm.yml     build → GHCR → SSH deploy → smoke
.github/workflows/deploy-yandex-vm.yml      deprecated, триггеры отключены
```

## 1. Как устроена общая VM

Единственный edge-прокси на машине — Caddy-контейнер `proxy` портала Vedal. Только он публикует `80/443`. Он же создаёт внешнюю docker-сеть `edge`. Astor ничего не публикует на хост: два контейнера входят в сеть `edge`, и Caddy проксирует на них по имени.

| Хост | Что | Где живёт |
| --- | --- | --- |
| `c3ag.ru`, `www.c3ag.ru` | Next.js frontend | контейнер `c3ag-frontend`, порт `3000`, сеть `edge` |
| `api.c3ag.ru` | nginx api-gateway → AERIS | контейнер `astor-api-gateway`, порт `8080`, сеть `edge` |
| нет публичного имени | AERIS bot `aeris_astor_butler_bot:8089`, PostgreSQL, Redis, Mongo, Kafka, MinIO | только приватная сеть `astor-butler_default` |
| портал Vedal | своё | `/opt/vedal-portal`, своя приватная сеть + `edge` |

Раскладка `/opt/astor-butler` (пользователь деплоя должен уметь `docker` без пароля: группа `docker` или `sudo -n docker`; cloud-init из `infra/cloudru/` создаёт такого пользователя `deploy`, на VM Vedal это `ubuntu`):

```text
/opt/astor-butler/
  .env.production           секреты, root:docker 0640, создаётся руками, в git не попадает
  releases/<sha>/           compose-файлы + docker/nginx + images.env + cloudru-deploy.sh
  current  -> releases/<sha>    живой релиз
  previous -> releases/<sha>    предыдущий релиз (цель rollback)
  backups/                  pg_dump перед backend-деплоем (см. §5)
```

Образы в GHCR (приватные, lowercase обязателен):

```text
ghcr.io/astor-hospitality/astor_butler_mvp/c3ag-frontend:sha-<sha> | :main
ghcr.io/astor-hospitality/astor_butler_mvp/astor-butler:sha-<sha>  | :main
```

Память (ориентир на 16 GB, Vedal занимает своё): AERIS `3g`, PostgreSQL `768m`, Mongo `1g`, Redpanda ~`0.7g`, MinIO/Redis/nginx/frontend < `1g` суммарно. Scylla, Neo4j, Prometheus, Grafana, Redpanda Console, Debezium на Cloud.ru по умолчанию не поднимаются (в overlay у них отдельные профили `graph-timeline`, `observability`, `kafka-tools`); в `docker-compose.prod.yml` Scylla/Neo4j и так выключены флагами.

## 2. Подключение к edge-прокси

Со стороны Astor (`docker-compose.cloudru.yml`):

- `ports: !reset []` у всех сервисов — ни одного host-порта;
- `networks: [default, edge]` только у `c3-agency-frontend` и `api-gateway`; `edge` объявлена как `external: true`;
- `container_name: c3ag-frontend` (`PORT=3000`) и `container_name: astor-api-gateway` — по этим именам Caddy находит upstream;
- `ASTOR_BACKEND_UPSTREAM=aeris-astor-butler-bot:8089`; gateway теперь доверяет `X-Forwarded-For`/`X-Forwarded-Proto` только из приватных docker-диапазонов (`docker/nginx/nginx.conf.template`), иначе rate-limit nginx считал бы всех посетителей одним IP Caddy;
- образы и ресурсы из `images.env` релиза, секреты из `/opt/astor-butler/.env.production`.

Со стороны Vedal нужно подключить `infra/cloudru/edge/c3ag.caddy` в общий Caddyfile (например, `/opt/vedal-portal/caddy/sites/c3ag.caddy` + `import sites/*.caddy`) и перезагрузить `proxy`. Содержимое сниппета:

```caddy
www.c3ag.ru {
	redir https://c3ag.ru{uri} permanent
}

c3ag.ru {
	encode zstd gzip
	header {
		Strict-Transport-Security "max-age=31536000"
		X-Content-Type-Options nosniff
		Referrer-Policy strict-origin-when-cross-origin
		-Server
	}
	reverse_proxy c3ag-frontend:3000 {
		health_uri /
		health_interval 30s
	}
}

api.c3ag.ru {
	encode zstd gzip
	header {
		Strict-Transport-Security "max-age=31536000"
		X-Content-Type-Options nosniff
		-Server
	}
	request_body {
		max_size 20MB
	}
	@docs path /swagger-ui/* /v3/api-docs*
	respond @docs 404
	reverse_proxy astor-api-gateway:8080 {
		health_uri /gateway/health
		health_interval 30s
	}
}
```

Сертификаты Caddy выпустит сам, когда DNS укажет на VM (нужен открытый `:80` для HTTP-01). До этого `https://c3ag.ru` с новой VM не ответит — это ожидаемо, см. §6.

Проверка с VM без публикации портов:

```bash
docker network inspect edge --format '{{range .Containers}}{{.Name}} {{end}}'
docker run --rm --network edge curlimages/curl -sS -o /dev/null -w '%{http_code}\n' http://c3ag-frontend:3000/
docker run --rm --network edge curlimages/curl -sS http://astor-api-gateway:8080/gateway/health
```

## 3. GitHub: environments, secrets, vars

Два GitHub Environments с required reviewers:

| Environment | Кто одобряет | Что деплоит |
| --- | --- | --- |
| `production-frontend` | владелец проекта и/или Егор | scope `frontend`: только `c3ag-frontend`, без backup, без backend |
| `production` | только владелец проекта | scope `backend`/`full`: AERIS + api-gateway; требует `confirm=DEPLOY_BACKEND`, `backup_path`, зелёный CI на этот же коммит |

Это замена модели «ограниченный frontend-deploy для Егора» (`C3AG_EGOR_RESTRICTED_FRONTEND_DEPLOY.md`): вместо forced-command SSH-пользователя `egor-c3deploy` на VM Егор запускает/одобряет workflow со scope `frontend`. Ключ VM он не видит (секрет environment), на сервер не заходит, `.env.production`, БД, Telegram и docker socket ему недоступны по построению. Деплоится только `main` (проверка `GITHUB_REF` в workflow).

Secrets (в обоих environments, значения одинаковые):

```text
CLOUDRU_SSH_HOST          176.123.165.162
CLOUDRU_SSH_PORT          22
CLOUDRU_SSH_USER          ubuntu   (или deploy, если VM поднята cloud-init из infra/cloudru)
CLOUDRU_SSH_KEY           приватный ключ деплоя (ed25519, отдельный, только для Actions)
CLOUDRU_SSH_KNOWN_HOSTS   строка `ssh-keyscan -p 22 176.123.165.162` (пиннинг host key; без неё workflow предупредит и доверится keyscan)
```

Vars (repository или environment, все публичные, все с дефолтами в workflow):

```text
CLOUDRU_DEPLOY_PATH       /opt/astor-butler
C3AG_PUBLIC_URL           https://c3ag.ru
C3AG_API_BASE_URL         https://api.c3ag.ru
C3AG_WEB_CHAT_ENDPOINT    https://api.c3ag.ru/api/messages
C3AG_MEDIA_BASE_URL       https://storage.yandexcloud.net/c3ag-media
C3AG_BRAND_LOGO_URL       https://storage.yandexcloud.net/c3ag-media/brand/c3ag-logo.svg
```

`GITHUB_TOKEN` с `packages: write` пушит образы и на время деплоя логинит VM в GHCR (токен передаётся по stdin, `docker logout` выполняется в `always()`-шаге). Отдельный PAT на VM не нужен.

Старые `YANDEX_VM_*` секреты не удалять, пока Yandex VM жива.

## 4. Подготовка VM (один раз, руками, от root)

Docker и сеть `edge` ставит bootstrap Vedal. Для Astor дополнительно:

```bash
id ubuntu | grep -q docker || usermod -aG docker ubuntu
docker network inspect edge >/dev/null            # должна существовать до первого деплоя
mkdir -p /opt/astor-butler/releases /opt/astor-butler/backups
chown -R ubuntu:ubuntu /opt/astor-butler && chmod 0750 /opt/astor-butler
install -o root -g docker -m 0640 /dev/null /opt/astor-butler/.env.production
```

`.env.production` заполняется руками по карте переменных Yandex-стенда (`YANDEX_VM_DEPLOYMENT.md`, §Server Environment), но не копируется вслепую: обязательные `YANDEX_FOLDER_ID`, `YANDEX_API_KEY`/`YANDEX_IAM_TOKEN`, `JWT_SECRET`, пароли PostgreSQL/Mongo/MinIO/Redis, Telegram-токены AERIS, `ASTOR_WEB_ALLOWED_ORIGINS=https://c3ag.ru,https://www.c3ag.ru`. Переменные `SMART_SOLUTION_*` нужны только если поднимать профиль `smart-solution`. Проверка валидности без вывода значений: `cd /opt/astor-butler/current && docker compose --env-file ../.env.production --env-file images.env -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudru.yml --profile frontend --profile telegram config --quiet`.

ufw: `22` (см. §8), `80`, `443`. Ничего больше — Astor host-портов не имеет.

## 5. Первый деплой

Этап 1 — frontend (DNS ещё на Yandex, Caddy сертификат ещё не выпустил):

1. Vedal: `proxy` запущен, сеть `edge` есть, `c3ag.caddy` подключён.
2. Actions → `Deploy to Cloud.ru VM` → `scope=frontend`, `mode=preflight`. Ожидаемо: `No current release yet: first deploy.`
3. Тот же workflow: `scope=frontend`, `mode=deploy`, `public_smoke=false`. Approve в `production-frontend`. Workflow соберёт образ, зальёт `releases/<sha>/`, VM сделает `pull` и `up -d --wait` только для `c3-agency-frontend`, проверит `http://127.0.0.1:3000/` внутри контейнера.
4. На VM: `docker ps --filter name=c3ag-frontend` → `healthy`; проверка через `edge` из §2.
5. Переключить DNS (§6). После того как `dig` отдаёт новый IP и Caddy выпустил сертификат: `curl -I https://c3ag.ru/` → `200`, `curl -I https://www.c3ag.ru/` → `308` на apex.
6. k6 read-only smoke с новыми адресами:

```bash
C3AG_BASE_URL=https://c3ag.ru C3AG_BACKEND_URL=https://api.c3ag.ru \
C3AG_K6_VUS=2 C3AG_K6_DURATION=5m k6 run scripts/k6_c3ag_prod_smoke.js
```

(до этапа 2 backend-проверка в k6 будет красной — это ожидаемо, ограничиться frontend-страницами.)

Этап 2 — backend (AERIS + api-gateway):

1. Перенос данных с Yandex (отдельное окно, Telegram polling AERIS на Yandex выключить перед дампом, чтобы не разъехались состояния):

```bash
# на Yandex VM
docker exec astor_postgres_test sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > /tmp/astor-$(date +%F).dump
docker exec astor_mongo_test sh -c 'mongodump --quiet --archive --authenticationDatabase admin -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD"' > /tmp/astor-mongo-$(date +%F).archive
# MinIO: mc mirror astor/astor-media и astor/astor-documents в /tmp/minio-<date>/
# затем scp в /opt/astor-butler/backups/ на Cloud.ru VM
```

2. На Cloud.ru VM поднять только хранилища и восстановить данные, пока AERIS не запущен:

```bash
cd /opt/astor-butler/current
docker compose --env-file ../.env.production --env-file images.env \
  -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudru.yml \
  --profile telegram up -d postgres mongo minio minio-init redis kafka
docker exec -i astor_postgres_test sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner' < ../backups/astor-<date>.dump
docker exec -i astor_mongo_test sh -c 'mongorestore --archive --authenticationDatabase admin -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD"' < ../backups/astor-mongo-<date>.archive
```

3. `Deploy to Cloud.ru VM` → `scope=backend`, `mode=preflight`, затем `mode=deploy`, `confirm=DEPLOY_BACKEND`, `backup_path=/opt/astor-butler/backups/astor-<date>.dump` (файл должен быть непустым и моложе 24 часов — тот самый дамп). Approve в `production`. Liquibase применит миграции при старте AERIS.
4. Проверки: `https://api.c3ag.ru/actuator/health` → `UP`; `https://api.c3ag.ru/swagger-ui/` → `404` (закрыт на edge); с сайта отправить одно помеченное WEB-сообщение и убедиться, что оно дошло до операторского чата (как в `C3AG_DOMAIN_RUNBOOK.md`, §2026-08-01).
5. Только после приёмки остановить AERIS на Yandex (иначе два polling-клиента одного бота). Удаление Yandex VM — отдельное решение.

STT без локального ML: образ бота из GHCR собран из корневого `Dockerfile` с `STT_LOCAL_WHISPER=false` (нет Python/ffmpeg/faster-whisper), распознавание идёт в Cloud.ru whisper-large-v3 — в `.env.production` нужны `CLOUDRU_API_KEY` и (по желанию) `ASTOR_STT_PROVIDER=cloudru`, который и так значение по умолчанию в `docker-compose.prod.yml`. Очки на этой VM собирать из `docker/glasses/Dockerfile.cloud` (JRE + ffmpeg; `ASTOR_GLASSES_STT_PROVIDER=yandex` — SpeechKit после перекодирования, `cloudru` — whisper) и поднимать `-f docker/glasses/compose.yaml -f docker/glasses/compose.cloudru.yaml`; `docker/glasses/Dockerfile` с venv и `/models` — только для отката на `ASTOR_GLASSES_STT_PROVIDER=local`. Переменные и проверка — `SBER_AI_ACTIVATION.md`, раздел «STT через whisper-large-v3».

Telegram egress: на Yandex `api.telegram.org:443` был недоступен напрямую (WireGuard-обход в `TELEGRAM_WIREGUARD_EGRESS_RUNBOOK.md`). На Cloud.ru сначала проверить `curl -4 -sS -o /dev/null -w '%{http_code}\n' --max-time 10 https://api.telegram.org/` с VM и из контейнера; если `200`/`302` — прокси-переменные `TELEGRAM_PROXY_*` в `.env.production` оставить `NO_PROXY`.

## 6. DNS для c3ag.ru на reg.ru

Текущее состояние (REG.RU DNS, `ns1.reg.ru`/`ns2.reg.ru`): `@ A 51.250.31.97`, `www A 51.250.31.97`, `api` без записи (см. `C3AG_DOMAIN_RUNBOOK.md`).

Шаг 0, за сутки до переключения — снизить TTL существующих записей до `300`, не меняя значения. Иначе старый TTL (часто 3600+) продержит часть пользователей на Yandex после переключения.

Шаг 1, переключение (после этапа 1 из §5, когда `c3ag-frontend` на новой VM `healthy` и Caddy-блок подключён):

| Host | Type | Value | TTL |
| --- | --- | ---: | ---: |
| `@` | `A` | `176.123.165.162` | `300` |
| `api` | `A` | `176.123.165.162` | `300` |
| `www` | `CNAME` | `c3ag.ru.` | `300` |

`www` меняется с `A` на `CNAME`: в панели reg.ru удалить A-запись `www`, затем добавить CNAME (две записи с одним именем разных типов панель не примет). `api` создаётся до этапа 2: до запуска backend Caddy ответит на `api.c3ag.ru` ошибкой upstream — это нормально, но сертификат выпустится заранее.

Проверка:

```bash
dig +short c3ag.ru A @ns1.reg.ru
dig +short www.c3ag.ru CNAME @ns1.reg.ru
dig +short api.c3ag.ru A @ns1.reg.ru
dig +short c3ag.ru A                      # публичные резолверы, после TTL
curl -sS -o /dev/null -w '%{http_code} %{remote_ip}\n' https://c3ag.ru/
```

Rollback DNS: вернуть `@` и `api` на `51.250.31.97` (и `www` обратно на `A 51.250.31.97`, т.к. на Yandex Caddy/www-редиректа нет); с TTL 300 возврат занимает минуты. Контейнеры на Yandex для этого не останавливать до приёмки.

Через неделю после переключения TTL можно поднять до `3600`.

## 7. Rollback приложения

Автоматически: если `up -d --wait` или health внутри контейнера не прошли, `cloudru-deploy.sh` сам возвращает образы релиза `current` для тех же сервисов и завершается с ошибкой; `current`/`previous` не меняются. Миграции БД не откатываются — для backend это значит восстановление из `backup_path`, если новая схема несовместима со старым образом.

Вручную, на VM (после удачного деплоя, который оказался плохим):

```bash
bash /opt/astor-butler/current/scripts/deploy/cloudru-deploy.sh /opt/astor-butler rollback <sha> frontend   # или backend / full
```

Скрипт меняет `current` и `previous` местами и поднимает образы предыдущего релиза. Второй вариант без SSH: запустить workflow на том же `main` с нужным scope — соберётся тот же sha, что и был; для отката на более старый коммит нужен revert в `main` (деплой произвольного sha запрещён, как и в Yandex-модели).

## 8. Доступ команды на VM

Сейчас: правило security group «SSH только с одного IP оператора» (`admin_cidr` в `infra/cloudru`). С двумя проектами на машине (Astor, Vedal) и несколькими людьми это не масштабируется: IP оператора меняется, а второй человек получает доступ только через того же оператора.

Сравнение (≤10 строк):

| | Tailscale (tailnet SSH, ключ на человека) | SSH jump/bastion, ключ на человека |
| --- | --- | --- |
| Порт 22 в интернете | закрыт полностью (`ufw allow in on tailscale0 to any port 22`) | открыт на jump-хосте; на VM 22 только с IP jump |
| Выдача/отзыв доступа | в админке tailnet, минуты; MFA через IdP | добавить/убрать ключ на jump и на VM (два места) |
| Что ставить | `tailscaled` на VM и у каждого человека | ещё одна VM/хост за деньги и на поддержке |
| Зависимость | внешний сервис (control plane Tailscale; доступность из РФ проверить) | только свой хост |
| Аудит | кто/когда подключался — в tailnet + `auth.log` | только `auth.log` на двух хостах |
| Аварийный доступ | консоль Cloud.ru | консоль Cloud.ru |

Рекомендация: Tailscale с Tailscale SSH и ACL «группа `astor-ops` → VM:22», port 22 на публичном IP закрыть; оставить в security group временное правило на один IP оператора только как аварийный вход и снять его после проверки tailnet. Jump-хост — запасной вариант, если control plane Tailscale окажется недоступен из нужных сетей. GitHub Actions к этому не относится: runner заходит с публичного IP, поэтому для него `22` должен оставаться открытым для диапазонов GitHub (`https://api.github.com/meta`, ключ `actions`) либо runner подключается через tailnet как ephemeral-узел (`tailscale/github-action`) — второе предпочтительнее, тогда `22` в интернет не торчит вовсе.

## 9. Открытые вопросы

1. Имена/пути на стороне Vedal: точный путь include для `c3ag.caddy` и способ reload `proxy` (`docker exec proxy caddy reload`?). Сниппет написан так, чтобы работать при любом пути.
2. Медиа C3AG по-прежнему в Yandex Object Storage (`storage.yandexcloud.net/c3ag-media`). Переезд бакета в Cloud.ru S3 — отдельная задача; пока оставить, менять через `C3AG_MEDIA_BASE_URL`/`C3AG_BRAND_LOGO_URL`.
3. Пользователь SSH: `ubuntu` (bootstrap Vedal) или `deploy` (cloud-init Astor). Workflow параметризован, но `.env.production` должен быть читаем именно этим пользователем через группу `docker`.
4. Профиль `nlu` (`natasha-nlu`) и `smart-solution` на Cloud.ru не входят в scope workflow; если они нужны, добавить в `profiles`/`services` в `cloudru-deploy.sh`.
5. Пиннинг host key (`CLOUDRU_SSH_KNOWN_HOSTS`) — снять с VM после bootstrap и положить в secrets до первого деплоя.
