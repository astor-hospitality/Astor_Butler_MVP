# Хост ВМ Cloud.ru: что живёт вне docker compose

Всё, что ниже, стоит на `astor-aeris-vm` (176.123.165.162) с 8 октября 2026 и раньше жило только на машине.
Секретов в этом каталоге нет: ключи лежат в env-файлах на ВМ (права 600) и в git не попадают.

## Telegram egress — AmneziaWG + tinyproxy

Из Cloud.ru `api.telegram.org` напрямую не открывается. Обычный WireGuard провайдер режет (рукопожатия нет),
проходит только AmneziaWG.

| Что | Где на ВМ |
| --- | --- |
| Контейнер | `astor-tg-awg`, образ `amneziavpn/amneziawg-go:latest`, `--restart unless-stopped` |
| Сеть | `astor-butler_default` (в ней же боты и Консьерж) |
| Конфиг клиента | `/etc/astor-telegram-wg/awg/awg0.conf` (0600, вне git) |
| Точка входа | `/etc/astor-telegram-wg/awg/entrypoint.sh` = `telegram-egress-entrypoint.sh` из этого каталога |
| Прокси для приложений | `http://astor-tg-awg:8888` |

Как подготовлен `awg0.conf` из клиентского конфига AmneziaVPN:

- убрать IPv6 из `Address` и `AllowedIPs`, а также строку `DNS` (в контейнере IPv6 выключен, resolvconf нет);
- добавить в `[Interface]` строки `Table = off` и **`MTU = 1280`**. С MTU 1420 рукопожатие и DNS проходят, а TLS молча
  теряется: оболочка сети Cloud.ru добавляет свои заголовки.

Запуск:

```bash
docker run -d --name astor-tg-awg --restart unless-stopped --network astor-butler_default \
  --cap-add NET_ADMIN --device /dev/net/tun --sysctl net.ipv6.conf.all.disable_ipv6=1 \
  -v /etc/astor-telegram-wg/awg/awg0.conf:/etc/amnezia/amneziawg/awg0.conf:ro \
  -v /etc/astor-telegram-wg/awg/entrypoint.sh:/entrypoint.sh:ro \
  amneziavpn/amneziawg-go:latest /entrypoint.sh
```

Приложения: боты — `TELEGRAM_PROXY_TYPE=HTTP`, `TELEGRAM_PROXY_HOST=astor-tg-awg`, `TELEGRAM_PROXY_PORT=8888`;
Консьерж — `TELEGRAM_PROXY_URL=http://astor-tg-awg:8888`.

**Один клиентский конфиг — одно устройство.** Если тем же конфигом пользуется ноутбук, сервер VPN перебрасывает
пир между ними и оба теряют пакеты. Для ВМ нужен отдельный пользователь на VPN-сервере.

Известное ограничение: `t.me` (сбор постов публичного канала) через текущий VPN-сервер не открывается, хотя
`api.telegram.org` открывается. На работу ботов это не влияет.

Systemd-юнит `astor-telegram-wg-proxy.service` (обычный WireGuard в netns + tinyproxy на `10.233.200.2:8888`)
на ВМ установлен, но выключен — из Cloud.ru он не устанавливает соединение.

## Ночной бэкап Astor

`astor-backup.sh` + `astor-backup.service` + `astor-backup.timer` (каждый день в 03:30 МСК).

- Postgres: `pg_dump -Fc`, проверка `pg_restore --list` (дамп без секций с данными — ошибка);
- Mongo: `mongodump --archive --gzip`;
- копия на ВМ в `/var/backups/astor/daily` (7 дней) и в Cloud.ru Object Storage `s3://astor-backups/db/`.
- Каталог бэкапов — `0700`, новые дампы — `0600` (`umask 077`).
- Если хотя бы одна загрузка в S3 не прошла, служба завершается с ошибкой и не удаляет старые локальные копии.
  Проверить `systemctl status astor-backup.service` и повторить запуск после восстановления S3;
  при длительном отказе контролировать свободное место на ВМ.
- Локальная проверка без Docker, БД и S3: `python3 -m unittest discover -s infra/cloudru/host/tests -v`.

Ключ S3 — `/home/ubuntu/.s3-cloudru.env` (0600), формат ключа Cloud.ru: `AWS_ACCESS_KEY_ID=<tenant_id>:<key_id>`.

Установка:

```bash
sudo install -m 750 astor-backup.sh /usr/local/sbin/
sudo install -m 644 astor-backup.service astor-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now astor-backup.timer
```

## Корневой сертификат НУЦ Минцифры

Нужен для API Сбера (`ngw.devices.sberbank.ru`, `gigachat.devices.sberbank.ru`). Бандл root + sub CA лежит
на ВМ как `russian_trusted_root_ca.pem` в `/opt/astor-butler/certs/`, `/opt/astor-glasses/certs/`,
`/opt/astor-concierge/certs/`, `/opt/vedal-portal/backend/certs/`. При склейке сертификатов обязателен перевод
строки между ними — без него файл не читается.

## Ключи ИИ (`astor-enable-sber.sh`)

Скрипт читает `~ubuntu/.sber.env` и прописывает ключи в env бота, очков и портала Ведала, после чего удаляет
входной файл. Ветка SaluteSpeech в нём больше не используется: с 15.07.2026 SaluteSpeech закрыт для новых
подключений. Голос и эмбеддинги работают через Yandex SpeechKit / AI Studio.

Действующая схема на 8 октября 2026:

| Задача | Провайдер |
| --- | --- |
| Ответы бота, Консьержа, Ведалины | GigaChat API, scope `GIGACHAT_API_PERS`, модель `GigaChat-2-Max` |
| Фото с очков | GigaChat (`/files` + `attachments`) |
| Озвучка | Yandex SpeechKit: Астор — `filipp`, Ведалина — `alena` |
| Распознавание речи | Yandex SpeechKit |
| Поиск по смыслу (Ведал) | Yandex `text-search-doc` / `text-search-query`, 256 измерений |
| Cloud.ru Foundation Models | заблокирован (402), обращение в поддержку № 426273 |
