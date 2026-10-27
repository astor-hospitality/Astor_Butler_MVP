# Сайт C3 на c3ag.ru: настройки для запуска

`frontend/` – новый сайт студии C3: Next.js 16, сборка `output: "standalone"`, контейнер слушает порт 3001 (как раньше; в `docker-compose.cloudru.yml` переменная `PORT=3000`, сервер её слушается). Как устроен сам сайт – в [README.md](README.md).

Фото, видео, иконки и шрифты в Git не лежат и в образ не попадают: они в S3, сайт берёт их по `NEXT_PUBLIC_MEDIA_BASE_URL`. В репозитории только их опись с размерами – `content/media-manifest.ts`. Домен в Vercel не нужен.

## 1. Переменные

Все `NEXT_PUBLIC_*` вшиваются в сборку (это build args образа), они публичны. Секретов сайт не читает.

| Переменная | Нужна | Что это | Пример |
|---|---|---|---|
| `NEXT_PUBLIC_MEDIA_BASE_URL` | да | Адрес медиа в S3 или CDN, без `/` в конце. Под ним лежат `images/…`, `videos/…`, `fonts/…`. Без неё сборка в Docker останавливается. | `https://storage.yandexcloud.net/<bucket>/<prefix>` |
| `NEXT_PUBLIC_SITE_URL` | да (по умолчанию `https://c3ag.ru`) | Адрес сайта: база ссылок для превью в мессенджерах, `sitemap.xml`, `robots.txt`. | `https://c3ag.ru` |
| `NEXT_PUBLIC_WEB_CHAT_ENDPOINT` | для чата | Куда окно Clio шлёт сообщения: `POST /api/messages` Butler, тот же контракт, что у прошлого сайта (канал `WEB`). Пусто – окно говорит, что чат не подключён. `/api/chat` – локальная заглушка с демо-ответами, только для разработки (на c3ag.ru Caddy её закрывает). | `https://c3ag.ru/api/messages` (тот же домен, CORS не нужен; Caddy уже пропускает) или `https://api.c3ag.ru/api/messages` (origin `https://c3ag.ru` должен быть в `ASTOR_WEB_ALLOWED_ORIGINS`) |
| `NEXT_PUBLIC_VIDEO_BASE_URL` | нет (пока пусто) | Адрес HLS-копий фильмов для собственного плеера сайта, без `/` в конце: под ним `<ключ>/master.m3u8` (1080p/720p/480p, у 4K-фильмов ещё 2160p). Пусто – свой плеер выключен, фильмы играют как сейчас: работы с Google Drive – в плеере Drive (iframe), остальные – файлом с Яндекс Диска через `/api/watch`. Задавать, только когда фильмы залиты (раздел 3). | `https://storage.yandexcloud.net/c3ag-video/hls` |
| `NEXT_PUBLIC_VIDEO_BASE_URL` | нет (пока пусто) | Адрес HLS-копий фильмов для собственного плеера сайта, без `/` в конце: под ним `<ключ>/master.m3u8` (1080p/720p/480p, у 4K-фильмов ещё 2160p). Пусто – свой плеер выключен, фильмы играют как сейчас: работы с Google Drive – в плеере Drive (iframe), остальные – файлом с Яндекс Диска через `/api/watch`. Задавать, только когда фильмы залиты и в `content/work-media.ts` появились ключи `hls` (раздел 3). | `https://storage.yandexcloud.net/c3ag-video/hls` |
| `NEXT_PUBLIC_POLICY_URL` | нет | Политика обработки персональных данных, на которую ссылаются окно чата и подвал. По умолчанию `/docs/policy.html` – файл в репозитории, на него же ссылается `ConsentController`. Пустая строка выключает чат. | `/docs/policy.html` |

Во время работы контейнера: `PORT` (3001 по умолчанию), `HOSTNAME=0.0.0.0`, `NODE_ENV=production` – уже стоят в `Dockerfile`.

Где задать:

- **GitHub Actions** (`deploy-cloudru-vm.yml`, scope `frontend`): переменные репозитория `C3AG_MEDIA_BASE_URL`, `C3AG_PUBLIC_URL`, `C3AG_WEB_CHAT_ENDPOINT`. Если `C3AG_MEDIA_BASE_URL` не задана, workflow подставит `https://storage.yandexcloud.net/c3ag-media` – это сработает, только если файлы лежат в корне бакета `c3ag-media` (папки `images/`, `videos/`, `fonts/`; со старыми ключами `content/c3ag/…` и `brand/…` они не пересекаются). Если медиа в другом бакете или в папке – задать переменную.
- **docker compose** (`docker-compose.yml`, `docker-compose.prod.yml`): `C3_FRONTEND_MEDIA_BASE_URL`, `C3_FRONTEND_PUBLIC_URL`, `C3_FRONTEND_WEB_CHAT_ENDPOINT`. В локальном `docker-compose.yml` `C3_FRONTEND_MEDIA_BASE_URL` по умолчанию пустая – сборку образа без неё Dockerfile остановит, это нарочно.
- `NEXT_PUBLIC_POLICY_URL` ни workflow, ни compose не передают: действует значение по умолчанию.
- `NEXT_PUBLIC_VIDEO_BASE_URL` workflow и compose пока не передают: в `Dockerfile` она пустая, свой плеер выключен, для этого выпуска ничего добавлять не нужно. Когда HLS-копии фильмов будут в S3, добавить (это вне `frontend/`, правит владелец):
  - `.github/workflows/deploy-cloudru-vm.yml`, job `build-frontend`, шаг «Build and push c3ag-frontend», в `build-args` после строки `NEXT_PUBLIC_MEDIA_BASE_URL=…`:

    ```yaml
                NEXT_PUBLIC_VIDEO_BASE_URL=${{ vars.C3AG_VIDEO_BASE_URL || '' }}
    ```

    и переменную репозитория `C3AG_VIDEO_BASE_URL` – адрес из таблицы выше;
  - `docker-compose.yml` и `docker-compose.prod.yml` (сервис `c3-agency-frontend`, `build.args`), а если образ собирается на самой VM – и `docker-compose.cloudru.yml`:

    ```yaml
            NEXT_PUBLIC_VIDEO_BASE_URL: ${C3_FRONTEND_VIDEO_BASE_URL:-}
    ```
- `NEXT_PUBLIC_VIDEO_BASE_URL` workflow и compose пока не передают: в `Dockerfile` она пустая, свой плеер выключен, и для этого выпуска ничего добавлять не нужно. Когда HLS-копии фильмов будут в S3, добавить (это вне `frontend/`, правит владелец):
  - `.github/workflows/deploy-cloudru-vm.yml`, job `build-frontend`, шаг «Build and push c3ag-frontend», в `build-args` после `NEXT_PUBLIC_MEDIA_BASE_URL`:
    ```yaml
                NEXT_PUBLIC_VIDEO_BASE_URL=${{ vars.C3AG_VIDEO_BASE_URL || '' }}
    ```
    и переменную репозитория `C3AG_VIDEO_BASE_URL` = адрес из таблицы выше;
  - `docker-compose.yml` и `docker-compose.prod.yml` (`c3-agency-frontend` → `build.args`), а если образ собирается на VM – и `docker-compose.cloudru.yml`:
    ```yaml
            NEXT_PUBLIC_VIDEO_BASE_URL: ${C3_FRONTEND_VIDEO_BASE_URL:-}
    ```
- Старые аргументы (`NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_BRAND_LOGO_URL`, `NEXT_PUBLIC_CLIO_*`, `NEXT_PUBLIC_PRODUCT_CARD_SLIDES_AUTOPLAY`) новый сайт не читает; compose и workflow их ещё передают – Docker напишет, что они не использованы, это безвредно. Убрать их можно отдельной правкой compose и workflow.

## 2. S3

**Где.** Любой бакет и папка, доступные браузеру по HTTPS. Под `NEXT_PUBLIC_MEDIA_BASE_URL` должны лежать ровно те пути, что в таблице ниже (те же, что в папке `public/media` у разработчика):

```text
<bucket>/<prefix>/
  fonts/              4 файла   TT Travels Next (пробные сборки, см. «Открытые вопросы»)
  images/icons/      21         хромированные иконки (PNG)
  images/meta/        3         картинка ссылки 1200 × 630, favicon, иконка для iPhone
  images/refs/       18         фирменные картинки, знак на красном, Clio, команда (четыре человека)
  images/work/       92         кадры работ (74 работы каталога и кадры кейсов)
  videos/             9         шоурил первого экрана, анимация знака, знак на красном, живые фирменные картинки
  videos/work/       61         короткие ролики карточек (превью работ в ленте)
```

Итого 208 файлов, 45,7 МБ. Файлы передаются отдельно (архив той же папки); после распаковки в `frontend/public/media` команда `npm run media:check` скажет, совпадают ли они с описью.

**Если в бакете уже лежит прошлая версия (110 файлов от PR #221).** Залить новую папку теми же командами поверх: `aws s3 sync` пошлёт только новые и изменённые файлы. Изменённые файлы (шоурил, его кадр, обложки подкастов и свадеб) получат в описи новый хеш, то есть новый адрес `?v=…`, поэтому годовой кэш не мешает. Файлы, которых в описи больше нет (кадры и ролики старого каталога), сайт не запрашивает; удалять их не обязательно.

**Доступ.** Публичное чтение этих ключей (ACL `public-read` или политика бакета на папку) или CDN перед бакетом.

**CORS.** Шрифты (и маска звезды Clio) браузер читает в режиме CORS: без правила останется Arial и пропадёт блик на звезде. Правило бакета:

```json
{
  "CORSRules": [
    {
      "AllowedOrigins": ["https://c3ag.ru", "https://www.c3ag.ru"],
      "AllowedMethods": ["GET", "HEAD"],
      "AllowedHeaders": ["*"],
      "MaxAgeSeconds": 86400
    }
  ]
}
```

**Заголовки.** `Content-Type` по расширению, `Cache-Control: public, max-age=31536000, immutable` для всех. Год кэша безопасен: каждый адрес сайта несёт хеш файла (`…/hero.png?v=bb222f0733`), заменённый файл получает новый адрес. Поэтому после замены файла: `npm run media:manifest`, закоммитить `content/media-manifest.ts`, залить файл, пересобрать образ.

| Расширение | Content-Type |
|---|---|
| `.jpg` | `image/jpeg` |
| `.png` | `image/png` |
| `.webp` | `image/webp` |
| `.ico` | `image/x-icon` |
| `.mp4` | `video/mp4` |
| `.webm` | `video/webm` |
| `.woff` | `font/woff` |

Запросы с `Range` (видео) S3 поддерживает сам.

**Как залить.** `npm run media:upload-plan` (или с `-- --tool s3cmd`) печатает список файлов с заголовками, команды `aws s3 sync` по одной на каждый тип файла и команду для CORS. Ключей скрипт не содержит и не читает: команды запускаются с профилем оператора. Бакет, папку, endpoint и origin он берёт из `C3_MEDIA_BUCKET`, `C3_MEDIA_PREFIX`, `C3_S3_ENDPOINT`, `C3_SITE_ORIGIN`, иначе оставляет `<заглушки>`. Пример строки:

```bash
aws s3 sync public/media/ s3://<bucket>/<prefix>/ --endpoint-url https://storage.yandexcloud.net \
  --exclude "*" --include "*.jpg" --content-type "image/jpeg" \
  --cache-control "public, max-age=31536000, immutable" --acl public-read
```

**Картинки через next/image.** Сервер сайта сам скачивает оригиналы из S3 и отдаёт уменьшенные AVIF/WebP по `/_next/image` (кэш – внутри контейнера, в `.next/cache/images`). Контейнеру нужен исходящий HTTPS до хоста S3; другие хосты next/image не принимает (`images.remotePatterns` строится из `NEXT_PUBLIC_MEDIA_BASE_URL`). Видео, шрифты и иконки браузер берёт из S3 напрямую. Для сборки доступ к S3 не нужен: размеры берутся из описи.

## 3. Как играют фильмы, Caddy: `/api/watch`

В каталоге 74 работы. Клик по карточке открывает плеер, и фильм берётся из одного из трёх мест, первое подходящее:

1. **Свой плеер сайта (HLS)** – если задана `NEXT_PUBLIC_VIDEO_BASE_URL` и у работы в `content/work-media.ts` есть ключ `hls`. Сейчас ключей нет и переменная пустая: этот путь выключен (`hls.js` даже не загружается). Когда копии будут готовы: бакет (например `c3ag-video`) с публичным чтением и CORS `GET, HEAD` для `https://c3ag.ru`, `https://www.c3ag.ru` с `ExposeHeaders: Content-Length, Content-Range, ETag` (hls.js читает плейлисты и сегменты через fetch), затем переменная из раздела 1 и пересборка.
2. **Google Drive** – 58 работ (у них в `work-media.ts` ключ `drive`) играют в плеере самого Drive: iframe `https://drive.google.com/file/d/<id>/preview`. Серверу сайта для этого ничего не нужно; если когда-нибудь появится Content-Security-Policy, в `frame-src` нужен `https://drive.google.com`.
3. **Яндекс Диск через `/api/watch`** – 16 работ (ключ `source`).

Для третьего пути плеер просит полный фильм у `/api/watch?work=<имя>`: сервер сайта спрашивает у Яндекса свежую ссылку на файл в публичной папке студии и отправляет браузер по ней (302). Сейчас `infra/cloudru/edge/c3ag.caddy` отвечает 404 на любой `/api/*` вне списка, поэтому без правки плеер покажет вместо фильма короткий ролик или кадр. Строки для `c3ag.caddy`, перед `@closed_api` (это не в `frontend/`, правит владелец edge):

```caddy
	@site_api path /api/watch
	handle @site_api {
		reverse_proxy c3ag-frontend:3000 {
			header_up X-Forwarded-For {remote_host}
			header_up X-Forwarded-Proto {scheme}
			header_up X-Forwarded-Host {host}
		}
	}
```

`/api/chat` (заглушка) наружу открывать не нужно.

## 4. Сборка и запуск

Docker (нужен Node 22 в образе, он уже в `Dockerfile`):

```bash
docker build -t c3ag-frontend:local \
  --build-arg NEXT_PUBLIC_MEDIA_BASE_URL=https://storage.yandexcloud.net/<bucket>/<prefix> \
  --build-arg NEXT_PUBLIC_SITE_URL=https://c3ag.ru \
  --build-arg NEXT_PUBLIC_WEB_CHAT_ENDPOINT=https://c3ag.ru/api/messages \
  frontend
docker run --rm -p 3001:3001 c3ag-frontend:local
```

Без Docker (то же, что делает образ):

```bash
cd frontend
npm ci
NEXT_PUBLIC_MEDIA_BASE_URL=https://storage.yandexcloud.net/<bucket>/<prefix> \
NEXT_PUBLIC_SITE_URL=https://c3ag.ru \
NEXT_PUBLIC_WEB_CHAT_ENDPOINT=https://c3ag.ru/api/messages npm run build
mkdir -p .next/standalone/public && cp -r public/docs public/*.svg .next/standalone/public/
cp -r .next/static .next/standalone/.next/
PORT=3001 HOSTNAME=0.0.0.0 node .next/standalone/server.js
```

(`public/media` в `standalone` не копировать: на сервере медиа берётся из S3.)

Прод через GitHub Actions: после мержа – «Deploy to Cloud.ru VM», scope `frontend`; workflow собирает образ `c3ag-frontend` из `frontend/` и кладёт в GHCR, VM пересоздаёт только `c3ag-frontend`. Перед этим – переменные из раздела 1, файлы и CORS из раздела 2, строки Caddy из раздела 3.

## 5. Что проверить после выкладки

Страницы, ответ 200:

```text
/  /ai  /events  /reels  /podcast  /reclama  /film  /wedding
/work/paralleli  /work/viking  /work/bloggers  /work/ess-11  /work/aeris
/docs/policy.html  /robots.txt  /sitemap.xml  /c3ag-logo.svg
```

Любой другой адрес (например `/nope`) – страница 404 «Здесь ничего не снимали.».

Перенаправления:

| Старый адрес | Куда | Код |
|---|---|---|
| `/ru`, `/en` | `/` | 307 |
| `/ru/<путь>`, `/en/<путь>` | `/<путь>` | 307 |
| `/studio`, `/studio/…`, `/auth/callback` | `/` | 307 |
| `/smart-solutions` | `/` | 308 |
| `/films`, `/podcasts`, `/advertising`, `/reportage` | `/film`, `/podcast`, `/reclama`, `/events` | 308 |
| `/favicon.ico`, `/og-image.png`, `/clio-avatar.jpg`, `/team/*-portrait.jpeg`, `/product-covers/<направление>.jpg` | тот же или ближайший файл в S3 | 307 |

`/product-covers/film.jpg` и `/clio-avatar.jpg` проверяет `scripts/k6_c3ag_prod_smoke.js`: k6 идёт по перенаправлению и получает файл из S3.

```bash
for p in / /ai /events /reels /podcast /reclama /film /wedding /work/viking /docs/policy.html /nope /ru /studio /smart-solutions; do
  printf '%-20s %s\n' "$p" "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "https://c3ag.ru$p")"
done
```

В браузере (DevTools → Network, телефон и широкий экран):

- картинки идут через `/_next/image?url=<адрес S3>`, видео, шрифты и иконки – прямо с хоста S3, всё 200/206;
- в консоли нет ошибок CORS; текст набран TT Travels Next, а не Arial;
- на первом экране играет шоурил; на красном экране заставки крутится знак (Chrome, Edge, Firefox);
- в ленте работ на красном экране карточки играют свои короткие ролики (`videos/work/*.mp4` с хоста S3);
- клик по работе с Drive открывает плеер Drive (iframe `drive.google.com`); клик по работе с Диска (например VIKING или «Электросетьстрой 11») – фильм идёт с `downloader.disk.yandex.ru` (после правки Caddy);
- одно помеченное сообщение в окне Clio доходит до операторского чата (как в `docs/operations/C3AG_DOMAIN_RUNBOOK.md`);
- нет горизонтальной прокрутки на 375 px.

## 6. Открытые вопросы

- **Команда.** Имя линейного продюсера Сергей не прислал (на сайте «Линейный продюсер», роль «Оператор»), `content/site.ts`, TODO.
- **Контакты студии.** Почта и Telegram – заглушки (`hello@c3.agncy`, `@c3agncy`), Instagram прочитан с постеров. Настоящие присылает Миша; они в `content/site.ts` (`links`).
- **Шрифты.** TT Travels Next – пробные сборки («for evaluation only»). До публичного запуска нужны купленные файлы: положить в S3 под теми же именами (`fonts/TTTravelsNextTrial-*.woff`) или под новыми с правкой `src/app/fonts.ts`, затем `npm run media:manifest`. Если меняется сам шрифт, заново снять метрики в `src/lib/travels.ts`.
- **`/api/watch`** берёт фильмы из публичной папки студии на Яндекс Диске. Адрес папки есть только в серверном коде (`src/app/api/watch/route.ts`), на страницы он не попадает. Яндекс может ограничивать скачивание; исходники тяжёлые (до десятков ГБ, часть в HEVC). Надёжнее положить сжатые копии фильмов в S3.
- **Caddy** – строки из раздела 3.
- **Чат** с настоящим сервером не проверялся.
- **«Приёмка»** (кабинет клиента прошлого сайта) – ссылки в подвале нет, `/studio` ведёт на главную.

## 7. Опись медиа

Сделано `node scripts/media-manifest.mjs --markdown` из `content/media-manifest.ts`.

| Ключ | Размер | Пиксели |
|---|---:|---|
| `fonts/TTTravelsNextTrial-Black.woff` | 51 КБ |  |
| `fonts/TTTravelsNextTrial-ExtraBold.woff` | 52 КБ |  |
| `fonts/TTTravelsNextTrial-Medium.woff` | 52 КБ |  |
| `fonts/TTTravelsNextTrial-Regular.woff` | 53 КБ |  |
| `images/icons/arrow.png` | 30 КБ | 256 × 165 |
| `images/icons/crown.png` | 43 КБ | 256 × 193 |
| `images/icons/drop.png` | 52 КБ | 160 × 256 |
| `images/icons/flame.png` | 40 КБ | 139 × 256 |
| `images/icons/forward.png` | 66 КБ | 256 × 223 |
| `images/icons/fullscreen.png` | 62 КБ | 256 × 230 |
| `images/icons/heart.png` | 52 КБ | 256 × 199 |
| `images/icons/heartbeat.png` | 32 КБ | 256 × 216 |
| `images/icons/infinity.png` | 33 КБ | 256 × 117 |
| `images/icons/lightning.png` | 40 КБ | 246 × 256 |
| `images/icons/link.png` | 66 КБ | 256 × 217 |
| `images/icons/pause.png` | 33 КБ | 155 × 256 |
| `images/icons/pin.png` | 50 КБ | 160 × 256 |
| `images/icons/play.png` | 63 КБ | 246 × 256 |
| `images/icons/power.png` | 50 КБ | 220 × 256 |
| `images/icons/rewind.png` | 68 КБ | 256 × 232 |
| `images/icons/share.png` | 59 КБ | 224 × 256 |
| `images/icons/sound.png` | 26 КБ | 256 × 202 |
| `images/icons/star.png` | 21 КБ | 256 × 224 |
| `images/icons/trash.png` | 65 КБ | 224 × 256 |
| `images/icons/wave.png` | 28 КБ | 256 × 105 |
| `images/meta/apple-icon.png` | 3 КБ | 180 × 180 |
| `images/meta/favicon.ico` | 3 КБ |  |
| `images/meta/og.jpg` | 47 КБ | 1200 × 630 |
| `images/refs/burger.jpg` | 338 КБ | 1080 × 1350 |
| `images/refs/clio-dark.png` | 63 КБ | 512 × 512 |
| `images/refs/clio.png` | 71 КБ | 512 × 512 |
| `images/refs/droid.jpg` | 363 КБ | 1080 × 1350 |
| `images/refs/gamepad.webp` | 125 КБ | 1525 × 1023 |
| `images/refs/glasses.jpg` | 559 КБ | 1400 × 1606 |
| `images/refs/hero-mask.jpg` | 76 КБ | 1280 × 720 |
| `images/refs/hero-still.jpg` | 41 КБ | 1920 × 1080 |
| `images/refs/hero.png` | 559 КБ | 1600 × 849 |
| `images/refs/icecream.jpg` | 217 КБ | 1080 × 1350 |
| `images/refs/lollipop.webp` | 357 КБ | 1099 × 1497 |
| `images/refs/mark-red.jpg` | 64 КБ | 1400 × 1750 |
| `images/refs/parrot.jpg` | 503 КБ | 1080 × 1350 |
| `images/refs/sheep.jpg` | 413 КБ | 1080 × 1350 |
| `images/refs/team-michael.jpg` | 75 КБ | 640 × 640 |
| `images/refs/team-producer.jpg` | 36 КБ | 960 × 1200 |
| `images/refs/team-sergey.jpg` | 36 КБ | 640 × 640 |
| `images/refs/team-vyacheslav.jpg` | 94 КБ | 960 × 1200 |
| `images/work/aeris-1.jpg` | 66 КБ | 720 × 1280 |
| `images/work/aeris-2.jpg` | 59 КБ | 720 × 1280 |
| `images/work/aeris-3.jpg` | 75 КБ | 720 × 1280 |
| `images/work/aeris-4.jpg` | 122 КБ | 720 × 1280 |
| `images/work/aeris-5.jpg` | 94 КБ | 720 × 1280 |
| `images/work/ai-1.jpg` | 120 КБ | 1280 × 675 |
| `images/work/ai-2.jpg` | 126 КБ | 1280 × 675 |
| `images/work/ai-3.jpg` | 68 КБ | 1280 × 720 |
| `images/work/ai-4.jpg` | 65 КБ | 608 × 1080 |
| `images/work/bloggers-2.jpg` | 130 КБ | 1280 × 720 |
| `images/work/clip-1.jpg` | 69 КБ | 1280 × 536 |
| `images/work/clip-2.jpg` | 59 КБ | 1280 × 536 |
| `images/work/clip-3.jpg` | 68 КБ | 1280 × 536 |
| `images/work/clip-4.jpg` | 86 КБ | 1280 × 536 |
| `images/work/clip-5.jpg` | 13 КБ | 1280 × 720 |
| `images/work/clip-6.jpg` | 35 КБ | 1280 × 536 |
| `images/work/clip-7.jpg` | 31 КБ | 1280 × 536 |
| `images/work/com-0.jpg` | 21 КБ | 1280 × 675 |
| `images/work/com-1.jpg` | 36 КБ | 1280 × 536 |
| `images/work/com-2.jpg` | 35 КБ | 1280 × 675 |
| `images/work/com-3.jpg` | 77 КБ | 1280 × 720 |
| `images/work/com-4-1.jpg` | 75 КБ | 1280 × 536 |
| `images/work/com-4.jpg` | 75 КБ | 1280 × 720 |
| `images/work/dir-events.jpg` | 52 КБ | 1280 × 536 |
| `images/work/dir-film.jpg` | 50 КБ | 1280 × 536 |
| `images/work/dir-podcast.jpg` | 73 КБ | 1200 × 1500 |
| `images/work/dir-reels.jpg` | 123 КБ | 720 × 1280 |
| `images/work/dir-wedding.jpg` | 75 КБ | 1200 × 1500 |
| `images/work/ess11-1.jpg` | 152 КБ | 720 × 1280 |
| `images/work/ess11-2.jpg` | 230 КБ | 720 × 1280 |
| `images/work/ess11-3.jpg` | 153 КБ | 720 × 1280 |
| `images/work/ess11-4.jpg` | 156 КБ | 720 × 1280 |
| `images/work/ev-1.jpg` | 80 КБ | 1280 × 720 |
| `images/work/ev-10.jpg` | 55 КБ | 1120 × 452 |
| `images/work/ev-11.jpg` | 11 КБ | 640 × 268 |
| `images/work/ev-12.jpg` | 17 КБ | 640 × 268 |
| `images/work/ev-13.jpg` | 24 КБ | 640 × 268 |
| `images/work/ev-14.jpg` | 14 КБ | 640 × 268 |
| `images/work/ev-15.jpg` | 14 КБ | 640 × 268 |
| `images/work/ev-16.jpg` | 11 КБ | 640 × 360 |
| `images/work/ev-17.jpg` | 24 КБ | 640 × 268 |
| `images/work/ev-18.jpg` | 18 КБ | 640 × 268 |
| `images/work/ev-2.jpg` | 51 КБ | 1280 × 720 |
| `images/work/ev-3a.jpg` | 31 КБ | 1280 × 536 |
| `images/work/ev-3b.jpg` | 53 КБ | 1280 × 536 |
| `images/work/ev-3c.jpg` | 78 КБ | 1280 × 536 |
| `images/work/ev-4.jpg` | 51 КБ | 1280 × 536 |
| `images/work/ev-5.jpg` | 23 КБ | 1280 × 536 |
| `images/work/ev-6.jpg` | 10 КБ | 640 × 268 |
| `images/work/ev-7.jpg` | 10 КБ | 640 × 268 |
| `images/work/ev-8.jpg` | 19 КБ | 640 × 268 |
| `images/work/ev-9.jpg` | 25 КБ | 640 × 268 |
| `images/work/paralleli-1.jpg` | 66 КБ | 720 × 1280 |
| `images/work/paralleli-2.jpg` | 91 КБ | 720 × 1280 |
| `images/work/paralleli-3.jpg` | 55 КБ | 720 × 1280 |
| `images/work/paralleli-4.jpg` | 50 КБ | 720 × 1280 |
| `images/work/rec-1.jpg` | 34 КБ | 1280 × 536 |
| `images/work/rec-2.jpg` | 37 КБ | 1280 × 536 |
| `images/work/rec-3.jpg` | 37 КБ | 1280 × 675 |
| `images/work/rec-4-1.jpg` | 90 КБ | 1280 × 536 |
| `images/work/rec-4.jpg` | 124 КБ | 1280 × 536 |
| `images/work/rec-6.jpg` | 52 КБ | 1280 × 536 |
| `images/work/rec-7.jpg` | 60 КБ | 1280 × 720 |
| `images/work/rec-8.jpg` | 42 КБ | 1280 × 720 |
| `images/work/rec-9.jpg` | 45 КБ | 1280 × 720 |
| `images/work/viking-1.jpg` | 89 КБ | 720 × 1280 |
| `images/work/viking-2.jpg` | 73 КБ | 720 × 1280 |
| `images/work/viking-3.jpg` | 74 КБ | 1280 × 720 |
| `images/work/viking-4.jpg` | 77 КБ | 720 × 1280 |
| `images/work/viking-5.jpg` | 51 КБ | 714 × 1280 |
| `images/work/w-044.jpg` | 91 КБ | 1280 × 536 |
| `images/work/w-045.jpg` | 54 КБ | 1280 × 541 |
| `images/work/w-046.jpg` | 41 КБ | 1280 × 672 |
| `images/work/w-091.jpg` | 110 КБ | 720 × 1280 |
| `images/work/w-112.jpg` | 68 КБ | 720 × 1280 |
| `images/work/w-119.jpg` | 56 КБ | 720 × 1280 |
| `images/work/w-145.jpg` | 50 КБ | 720 × 1280 |
| `images/work/w-155.jpg` | 69 КБ | 1280 × 536 |
| `images/work/w-157.jpg` | 112 КБ | 1280 × 536 |
| `images/work/w-158.jpg` | 49 КБ | 1280 × 536 |
| `images/work/wed-1.jpg` | 53 КБ | 1280 × 536 |
| `images/work/wed-10.jpg` | 51 КБ | 1280 × 720 |
| `images/work/wed-11.jpg` | 52 КБ | 1280 × 536 |
| `images/work/wed-12.jpg` | 97 КБ | 1280 × 720 |
| `images/work/wed-2.jpg` | 70 КБ | 1280 × 720 |
| `images/work/wed-3.jpg` | 69 КБ | 1280 × 536 |
| `images/work/wed-4.jpg` | 49 КБ | 1280 × 720 |
| `images/work/wed-5.jpg` | 70 КБ | 1280 × 720 |
| `images/work/wed-6.jpg` | 134 КБ | 1280 × 720 |
| `images/work/wed-7.jpg` | 54 КБ | 1280 × 536 |
| `images/work/wed-8.jpg` | 42 КБ | 1280 × 720 |
| `images/work/wed-9.jpg` | 39 КБ | 1280 × 536 |
| `videos/burger.mp4` | 2,7 МБ |  |
| `videos/droid.mp4` | 2,6 МБ |  |
| `videos/hero-m.mp4` | 1,8 МБ |  |
| `videos/hero.mp4` | 3,2 МБ |  |
| `videos/icecream.mp4` | 1,2 МБ |  |
| `videos/logo-spin.webm` | 1,4 МБ |  |
| `videos/mark-red.mp4` | 376 КБ |  |
| `videos/parrot.mp4` | 2,3 МБ |  |
| `videos/sheep.mp4` | 2,6 МБ |  |
| `videos/work/ai-1.mp4` | 469 КБ |  |
| `videos/work/ai-2.mp4` | 439 КБ |  |
| `videos/work/ai-3.mp4` | 282 КБ |  |
| `videos/work/ai-4.mp4` | 445 КБ |  |
| `videos/work/clip-1.mp4` | 106 КБ |  |
| `videos/work/clip-2.mp4` | 375 КБ |  |
| `videos/work/clip-3.mp4` | 333 КБ |  |
| `videos/work/clip-4.mp4` | 251 КБ |  |
| `videos/work/clip-5.mp4` | 175 КБ |  |
| `videos/work/clip-7.mp4` | 209 КБ |  |
| `videos/work/com-0.mp4` | 115 КБ |  |
| `videos/work/com-1.mp4` | 115 КБ |  |
| `videos/work/com-2.mp4` | 226 КБ |  |
| `videos/work/com-3.mp4` | 269 КБ |  |
| `videos/work/com-4-1.mp4` | 223 КБ |  |
| `videos/work/com-4.mp4` | 215 КБ |  |
| `videos/work/dir-film.mp4` | 75 КБ |  |
| `videos/work/dir-podcast.mp4` | 123 КБ |  |
| `videos/work/dir-reels.mp4` | 330 КБ |  |
| `videos/work/ev-1.mp4` | 497 КБ |  |
| `videos/work/ev-10.mp4` | 306 КБ |  |
| `videos/work/ev-11.mp4` | 304 КБ |  |
| `videos/work/ev-12.mp4` | 404 КБ |  |
| `videos/work/ev-13.mp4` | 420 КБ |  |
| `videos/work/ev-14.mp4` | 395 КБ |  |
| `videos/work/ev-15.mp4` | 361 КБ |  |
| `videos/work/ev-16.mp4` | 424 КБ |  |
| `videos/work/ev-18.mp4` | 223 КБ |  |
| `videos/work/ev-2.mp4` | 435 КБ |  |
| `videos/work/ev-3a.mp4` | 428 КБ |  |
| `videos/work/ev-3b.mp4` | 356 КБ |  |
| `videos/work/ev-3c.mp4` | 437 КБ |  |
| `videos/work/ev-4.mp4` | 340 КБ |  |
| `videos/work/ev-5.mp4` | 396 КБ |  |
| `videos/work/ev-6.mp4` | 464 КБ |  |
| `videos/work/ev-7.mp4` | 455 КБ |  |
| `videos/work/ev-8.mp4` | 464 КБ |  |
| `videos/work/ev-9.mp4` | 460 КБ |  |
| `videos/work/paralleli-1.mp4` | 222 КБ |  |
| `videos/work/rec-1.mp4` | 159 КБ |  |
| `videos/work/rec-2.mp4` | 88 КБ |  |
| `videos/work/rec-3.mp4` | 151 КБ |  |
| `videos/work/rec-4-1.mp4` | 292 КБ |  |
| `videos/work/rec-4.mp4` | 292 КБ |  |
| `videos/work/rec-6.mp4` | 145 КБ |  |
| `videos/work/rec-7.mp4` | 133 КБ |  |
| `videos/work/rec-8.mp4` | 171 КБ |  |
| `videos/work/rec-9.mp4` | 166 КБ |  |
| `videos/work/viking-1.mp4` | 212 КБ |  |
| `videos/work/w-044.mp4` | 269 КБ |  |
| `videos/work/w-045.mp4` | 97 КБ |  |
| `videos/work/w-046.mp4` | 82 КБ |  |
| `videos/work/w-091.mp4` | 352 КБ |  |
| `videos/work/w-112.mp4` | 99 КБ |  |
| `videos/work/w-119.mp4` | 235 КБ |  |
| `videos/work/w-145.mp4` | 394 КБ |  |
| `videos/work/w-155.mp4` | 257 КБ |  |
| `videos/work/w-157.mp4` | 282 КБ |  |
| `videos/work/w-158.mp4` | 131 КБ |  |
| `videos/work/wed-1.mp4` | 156 КБ |  |
| `videos/work/wed-2.mp4` | 256 КБ |  |
| **Всего: 208 файлов** | **45,7 МБ** | |
