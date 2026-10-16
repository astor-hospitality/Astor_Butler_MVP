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
| `NEXT_PUBLIC_POLICY_URL` | нет | Политика обработки персональных данных, на которую ссылаются окно чата и подвал. По умолчанию `/docs/policy.html` – файл в репозитории, на него же ссылается `ConsentController`. Пустая строка выключает чат. | `/docs/policy.html` |

Во время работы контейнера: `PORT` (3001 по умолчанию), `HOSTNAME=0.0.0.0`, `NODE_ENV=production` – уже стоят в `Dockerfile`.

Где задать:

- **GitHub Actions** (`deploy-cloudru-vm.yml`, scope `frontend`): переменные репозитория `C3AG_MEDIA_BASE_URL`, `C3AG_PUBLIC_URL`, `C3AG_WEB_CHAT_ENDPOINT`. Если `C3AG_MEDIA_BASE_URL` не задана, workflow подставит `https://storage.yandexcloud.net/c3ag-media` – это сработает, только если файлы лежат в корне бакета `c3ag-media` (папки `images/`, `videos/`, `fonts/`; со старыми ключами `content/c3ag/…` и `brand/…` они не пересекаются). Если медиа в другом бакете или в папке – задать переменную.
- **docker compose** (`docker-compose.yml`, `docker-compose.prod.yml`): `C3_FRONTEND_MEDIA_BASE_URL`, `C3_FRONTEND_PUBLIC_URL`, `C3_FRONTEND_WEB_CHAT_ENDPOINT`. В локальном `docker-compose.yml` `C3_FRONTEND_MEDIA_BASE_URL` по умолчанию пустая – сборку образа без неё Dockerfile остановит, это нарочно.
- `NEXT_PUBLIC_POLICY_URL` ни workflow, ни compose не передают: действует значение по умолчанию.
- Старые аргументы (`NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_BRAND_LOGO_URL`, `NEXT_PUBLIC_CLIO_*`, `NEXT_PUBLIC_PRODUCT_CARD_SLIDES_AUTOPLAY`) новый сайт не читает; compose и workflow их ещё передают – Docker напишет, что они не использованы, это безвредно. Убрать их можно отдельной правкой compose и workflow.

## 2. S3

**Где.** Любой бакет и папка, доступные браузеру по HTTPS. Под `NEXT_PUBLIC_MEDIA_BASE_URL` должны лежать ровно те пути, что в таблице ниже (те же, что в папке `public/media` у разработчика):

```text
<bucket>/<prefix>/
  fonts/              4 файла   TT Travels Next (пробные сборки, см. «Открытые вопросы»)
  images/icons/      21         хромированные иконки (PNG)
  images/meta/        3         картинка ссылки 1200 × 630, favicon, иконка для iPhone
  images/refs/       15         фирменные картинки, Clio, команда
  images/work/       49         кадры работ
  videos/             8         шоурил первого экрана, анимация знака, живые фирменные картинки
  videos/work/       10         короткие ролики карточек
```

Итого 110 файлов, 29,4 МБ. Файлы передаются отдельно (архив той же папки); после распаковки в `frontend/public/media` команда `npm run media:check` скажет, совпадают ли они с описью.

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

## 3. Caddy: `/api/watch`

Плеер просит полный фильм у `/api/watch?work=<имя>`: сервер сайта спрашивает у Яндекса свежую ссылку на файл в публичной папке студии и отправляет браузер по ней (302). Сейчас `infra/cloudru/edge/c3ag.caddy` отвечает 404 на любой `/api/*` вне списка, поэтому без правки плеер покажет вместо фильма короткий ролик или кадр. Строки для `c3ag.caddy`, перед `@closed_api` (это не в `frontend/`, правит владелец edge):

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
- клик по работе открывает плеер, фильм идёт с `downloader.disk.yandex.ru` (после правки Caddy);
- одно помеченное сообщение в окне Clio доходит до операторского чата (как в `docs/operations/C3AG_DOMAIN_RUNBOOK.md`);
- нет горизонтальной прокрутки на 375 px.

## 6. Открытые вопросы

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
| `images/refs/hero-mask.jpg` | 183 КБ | 1280 × 720 |
| `images/refs/hero-still.jpg` | 41 КБ | 1920 × 1080 |
| `images/refs/hero.png` | 559 КБ | 1600 × 849 |
| `images/refs/icecream.jpg` | 217 КБ | 1080 × 1350 |
| `images/refs/lollipop.webp` | 357 КБ | 1099 × 1497 |
| `images/refs/parrot.jpg` | 503 КБ | 1080 × 1350 |
| `images/refs/sheep.jpg` | 413 КБ | 1080 × 1350 |
| `images/refs/team-michael.jpg` | 75 КБ | 640 × 640 |
| `images/refs/team-sergey.jpg` | 36 КБ | 640 × 640 |
| `images/work/aeris-1.jpg` | 66 КБ | 720 × 1280 |
| `images/work/aeris-2.jpg` | 59 КБ | 720 × 1280 |
| `images/work/aeris-3.jpg` | 75 КБ | 720 × 1280 |
| `images/work/aeris-4.jpg` | 122 КБ | 720 × 1280 |
| `images/work/aeris-5.jpg` | 94 КБ | 720 × 1280 |
| `images/work/bloggers-1.jpg` | 99 КБ | 720 × 1280 |
| `images/work/bloggers-2.jpg` | 130 КБ | 1280 × 720 |
| `images/work/dir-ai.jpg` | 116 КБ | 1280 × 720 |
| `images/work/dir-events.jpg` | 52 КБ | 1280 × 536 |
| `images/work/dir-film.jpg` | 50 КБ | 1280 × 536 |
| `images/work/dir-podcast.jpg` | 71 КБ | 1280 × 536 |
| `images/work/dir-reclama.jpg` | 55 КБ | 1280 × 516 |
| `images/work/dir-reels.jpg` | 123 КБ | 720 × 1280 |
| `images/work/dir-wedding.jpg` | 207 КБ | 1672 × 941 |
| `images/work/ess11-1.jpg` | 152 КБ | 720 × 1280 |
| `images/work/ess11-2.jpg` | 230 КБ | 720 × 1280 |
| `images/work/ess11-3.jpg` | 153 КБ | 720 × 1280 |
| `images/work/ess11-4.jpg` | 156 КБ | 720 × 1280 |
| `images/work/paralleli-1.jpg` | 66 КБ | 720 × 1280 |
| `images/work/paralleli-2.jpg` | 91 КБ | 720 × 1280 |
| `images/work/paralleli-3.jpg` | 55 КБ | 720 × 1280 |
| `images/work/paralleli-4.jpg` | 50 КБ | 720 × 1280 |
| `images/work/viking-1.jpg` | 89 КБ | 720 × 1280 |
| `images/work/viking-2.jpg` | 73 КБ | 720 × 1280 |
| `images/work/viking-3.jpg` | 74 КБ | 1280 × 720 |
| `images/work/viking-4.jpg` | 77 КБ | 720 × 1280 |
| `images/work/viking-5.jpg` | 51 КБ | 714 × 1280 |
| `images/work/w-013.jpg` | 139 КБ | 720 × 1280 |
| `images/work/w-015.jpg` | 94 КБ | 714 × 1280 |
| `images/work/w-019.jpg` | 129 КБ | 841 × 1280 |
| `images/work/w-028.jpg` | 86 КБ | 720 × 1280 |
| `images/work/w-044.jpg` | 91 КБ | 1280 × 536 |
| `images/work/w-045.jpg` | 54 КБ | 1280 × 541 |
| `images/work/w-046.jpg` | 41 КБ | 1280 × 672 |
| `images/work/w-069.jpg` | 66 КБ | 1280 × 675 |
| `images/work/w-071.jpg` | 77 КБ | 1280 × 588 |
| `images/work/w-073.jpg` | 61 КБ | 1280 × 501 |
| `images/work/w-076.jpg` | 79 КБ | 1280 × 542 |
| `images/work/w-078.jpg` | 66 КБ | 1280 × 536 |
| `images/work/w-081.jpg` | 156 КБ | 1280 × 720 |
| `images/work/w-083.jpg` | 41 КБ | 1280 × 536 |
| `images/work/w-087.jpg` | 74 КБ | 1280 × 536 |
| `images/work/w-091.jpg` | 110 КБ | 720 × 1280 |
| `images/work/w-112.jpg` | 68 КБ | 720 × 1280 |
| `images/work/w-119.jpg` | 56 КБ | 720 × 1280 |
| `images/work/w-145.jpg` | 50 КБ | 720 × 1280 |
| `images/work/w-155.jpg` | 69 КБ | 1280 × 536 |
| `images/work/w-157.jpg` | 112 КБ | 1280 × 536 |
| `images/work/w-158.jpg` | 49 КБ | 1280 × 536 |
| `videos/burger.mp4` | 2,7 МБ |  |
| `videos/droid.mp4` | 2,6 МБ |  |
| `videos/hero-m.mp4` | 1,6 МБ |  |
| `videos/hero.mp4` | 2,9 МБ |  |
| `videos/icecream.mp4` | 1,2 МБ |  |
| `videos/logo-spin.webm` | 1,4 МБ |  |
| `videos/parrot.mp4` | 2,3 МБ |  |
| `videos/sheep.mp4` | 2,6 МБ |  |
| `videos/work/bloggers-2.mp4` | 437 КБ |  |
| `videos/work/dir-ai.mp4` | 262 КБ |  |
| `videos/work/dir-reclama.mp4` | 120 КБ |  |
| `videos/work/dir-reels.mp4` | 330 КБ |  |
| `videos/work/w-013.mp4` | 225 КБ |  |
| `videos/work/w-015.mp4` | 313 КБ |  |
| `videos/work/w-087.mp4` | 379 КБ |  |
| `videos/work/w-112.mp4` | 99 КБ |  |
| `videos/work/w-119.mp4` | 235 КБ |  |
| `videos/work/w-145.mp4` | 394 КБ |  |
| **Всего: 110 файлов** | **29,4 МБ** | |
