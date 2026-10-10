# Astor site на ВМ Cloud.ru: превью за общим Caddy

Презентационный сайт Astor (`frontend/astor-butler`, чистая статика) публикуется отдельным
контейнером `astor-site` (nginx:1.27-alpine) в сети `edge`, а наружу его отдаёт общий edge-Caddy
`vedal-proxy`, который уже обслуживает vedal-med.ru и c3ag.ru. Собственного домена у Astor пока
нет, поэтому сайт доступен по техническому имени **https://astor.176-123-165-162.nip.io**.

Файлы в этом каталоге:

| Файл | Что |
| --- | --- |
| `compose.yaml` | контейнер `astor-site`: read-only раздача `/opt/astor-site/frontend/astor-butler`, сеть `edge`, `restart: unless-stopped`, healthcheck `/healthz` |
| `nginx.conf` | раздача статики: `index.html` в подкаталогах, 404 для `server/`, `tests/` и dot-файлов, кэш только для css/js/картинок |
| `astor.caddy` | vhost для `/opt/edge/sites.d/`: TLS и заголовки, `/api/astor/messages` в `astor-api-gateway` (Butler, веб-чат), `/api/concierge/messages` в контейнер Concierge `:8096`, остальной `/api/*` в `astor-api-gateway`, всё прочее в `astor-site` |

Сборки нет: контейнер читает файлы прямо из checkout'а, поэтому `git pull` обновляет сайт
без перезапуска.

## 1. Checkout на ВМ

```bash
sudo mkdir -p /opt/astor-site && sudo chown "$USER" /opt/astor-site
git clone https://github.com/astor-hospitality/Astor_Butler_MVP.git /opt/astor-site
cd /opt/astor-site && git checkout main      # после merge PR сайт живёт в main
```

Только для превью до merge: `git checkout feat/astor-site-brand` вместо `main`, а после
merge - §6.

Отдельный checkout, а не `/opt/astor-butler/current`: у backend-деплоя свой скрипт и
свой порядок обновления, сайт должен обновляться независимо от него.

## 2. Контейнер

```bash
docker network inspect edge --format '{{.Name}}'     # сеть общего Caddy должна существовать
cd /opt/astor-site/infra/astor-site
docker compose up -d
docker compose ps                                     # astor-site ... (healthy)
docker run --rm --network edge curlimages/curl -sS -o /dev/null -w '%{http_code}\n' http://astor-site/healthz
docker run --rm --network edge curlimages/curl -sS -o /dev/null -w '%{http_code}\n' http://astor-site/astor_glass/
```

Оба запроса должны вернуть `200`. Если checkout лежит не в `/opt/astor-site`, задать
`ASTOR_SITE_ROOT=/path/to/checkout` перед `docker compose up -d`.

## 3. Caddy: validate ДО reload

```bash
sudo install -m 644 /opt/astor-site/infra/astor-site/astor.caddy /opt/edge/sites.d/astor.caddy
docker exec vedal-proxy caddy validate --config /etc/caddy/Caddyfile
docker exec vedal-proxy caddy reload   --config /etc/caddy/Caddyfile
```

`validate` обязателен: общий Caddyfile обслуживает vedal-med.ru и c3ag.ru, и ошибка в сниппете
Astor уронила бы их вместе. Если `validate` ругается - убрать `/opt/edge/sites.d/astor.caddy`
и не делать reload. Сертификат для nip.io-имени Caddy выпустит сам при первом запросе
(нужен открытый `:80` для HTTP-01, он уже открыт для c3ag).

## 4. Проверка: Astor отвечает, соседи не пострадали

```bash
for u in https://astor-ai.ru/ \
         https://astor-ai.ru/astor_butler/ \
         https://astor-ai.ru/astor_concierge/feed/ \
         https://astor-ai.ru/policy.html \
         https://vedal-med.ru/ \
         https://c3ag.ru/ ; do
  curl -sS -o /dev/null -w "%{http_code} $u\n" "$u"
done
```

Ожидание: шесть строк `200`. Первый запрос к nip.io-имени может занять несколько секунд
(выпуск сертификата). Если vedal-med.ru или c3ag.ru перестали отвечать `200` - откат, §7.

## 5. Обновление сайта

```bash
cd /opt/astor-site && git pull --ff-only
```

Перезапуск контейнера не нужен. Изменился только `infra/astor-site/nginx.conf` -
`docker compose -f /opt/astor-site/infra/astor-site/compose.yaml restart`; изменился
`astor.caddy` - повторить §3.

## 6. После merge PR в main

```bash
cd /opt/astor-site
git fetch origin && git checkout main && git pull --ff-only
git branch -d feat/astor-site-brand
```

Контейнер перезапускать не нужно: он читает файлы из того же каталога.

## 7. Откат

```bash
sudo rm /opt/edge/sites.d/astor.caddy
docker exec vedal-proxy caddy validate --config /etc/caddy/Caddyfile
docker exec vedal-proxy caddy reload   --config /etc/caddy/Caddyfile
docker compose -f /opt/astor-site/infra/astor-site/compose.yaml down
```

vedal-med.ru и c3ag.ru этим не затрагиваются: их vhost'ы лежат в других файлах `sites.d`.

## 8. Свой домен позже: astor.c3ag.ru или купленный

1. Создать A-запись домена на `176.123.165.162` (для `astor.c3ag.ru` - в панели reg.ru
   зоны c3ag.ru) и дождаться, пока `dig +short astor.c3ag.ru` вернёт этот адрес.
2. Только после этого добавить имя в строку адреса сниппета через запятую:

   ```caddy
   astor.c3ag.ru, astor.176-123-165-162.nip.io {
   ```

   Имя без A-записи добавлять нельзя: Caddy будет бесконечно повторять ACME для него.
3. Повторить §3 (`validate`, затем `reload`) и §4 с новым именем. Сертификат выпустится
   автоматически.
4. Когда домен окончательный, обновить `og:image` и упоминания адреса в
   `frontend/astor-butler` (`policy.html` ссылается на `c3ag.ru/astor/policy.html`) отдельным PR.
   Техническое имя можно оставить в сниппете как запасной вход или убрать той же правкой.

Купленный домен подключается так же; если нужен и `www`, добавить блок редиректа по образцу
`www.c3ag.ru` в `infra/cloudru/edge/c3ag.caddy`.

## Что здесь не делается

- Нет сборки, Node и npm на ВМ не нужны.
- Backend Astor не трогается: `/api/astor/messages` проксируется в `astor-api-gateway`
  (его отдаёт сам Butler, `docs/architecture/WEB_CHANNEL_ADAPTER.md`), `/api/concierge/messages` -
  в контейнер Concierge (`astor-concierge-concierge-1:8096`, сеть `edge`), остальной `/api/*` -
  в `astor-api-gateway`, как и на c3ag.ru. Если контейнеры backend остановлены, сайт продолжает
  открываться, не работает только веб-чат виджета.
- Страница `staff/` требует отдельного включения staff-маршрутов и Keycloak
  (`docs/operations/STAFF_PORTAL_RUNBOOK.md`); на превью она открывается, но к серверу не подключится.
