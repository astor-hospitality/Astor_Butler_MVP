# Astor Glass: информационный пилот

Дата: 2026-10-04. Ветка `codex/glasses-voice-adapter`, [issue #9](https://github.com/astor-hospitality/Astor_Butler_MVP/issues/9). Михаил разрешил запуск Astor, облачные вызовы и координацию физического теста с чатом «Проверить интеграцию ИИ с очками». Изолированный runtime не запускает Telegram, DB, Kafka, FSM или startup notifications основного монолита.

## Контракт

- `POST /api/glasses/assist`, JSON, `Authorization: Bearer …`. Canonical UUID `requestId`, `text` до 4000 Unicode code points; опционально одна пара `audioBase64`/`audioMimeType:audio/mp4` или `imageBase64`/`imageMimeType:image/jpeg`. Для аудио текст может быть пустым. URL, client tenant/staff/chatId и неизвестные поля отвергаются.
- Body до 5 MiB, decoded media до 2 MiB. JPEG signature/reader/header dimensions до 1280 px без записи на диск. MP4 header проверяется до запуска helper; PyAV полностью декодирует единственный AAC stream 16kHz/mono, проверяет metadata и реальное число samples до 30 секунд. Допускается один AAC frame padding, который отрезается перед STT. MPEG/PCM/stereo/44.1kHz/лишние streams не принимаются.
- Voice: cached local faster-whisper base, CPU/int8, Russian; затем реальный YandexGPT. Text: YandexGPT. Vision: Qwen3.6-35b-a3b с JPEG data URL; без подмены анализа изображения текстовым ответом. Cloud request отключает data logging и не содержит tools.
- `200`: `{requestId,text,capabilities}`, только непустой полный ответ провайдера. Informational assist не подтверждает и не выполняет задания, брони или FSM-переходы. Контекст ресторана/RAG и staff task feed пока отсутствуют.
- `GET /api/glasses/capabilities`: тот же bearer; text/voice/vision, maxAudioSeconds=30, maxAudioBytes=maxImageBytes=2097152, maxImageDimension=1280, maxTextChars=4000, maxBodyBytes=5242880. Readiness означает успешный вызов за последние 300 секунд; до первого запроса и после ошибки false. Разрешённый POST может прогреть провайдер при false.
- Ошибки `{requestId:UUID|null,error:{code,message}}`: 400 malformed; 401 missing/invalid bearer; 403 expired/missing scope; 413 limits; 429 busy/rate; 503 unavailable/provider timeout. RequestId сохраняется после валидации; отказ до чтения body имеет null. Cache-Control:no-store, WWW-Authenticate для 401, Retry-After для 429.
- Один server-bound tenant/staff credential, 10 авторизованных попыток в минуту, один pipeline без очереди. Runtime timeout 45 секунд, STT 20 секунд; лимиты локальны одной JVM. Busy provider остаётся занятым до фактического окончания, новые потоки не накапливаются.

## Процесс и файлы

Helper вызывается argv без shell. Environment очищается от cloud/Telegram/DB credentials; HF offline, модель заранее загружена. stdout/stderr одновременно дренируются с лимитом 64 KiB, diagnostic stderr не публикуется. Temp request directory 0700, файл 0600, Docker /tmp — ограниченный tmpfs. Success/error/timeout удаляют файл только после завершения процесса и захваченных потомков. Если завершение подтвердить нельзя, ответ 503, private файл остаётся в карантине до остановки контейнера; не удалять файл под работающим процессом. Raw media/transcripts/keys не логируются.

## Runtime

`docker/glasses/Dockerfile`, reviewed runtime shape `docker/glasses/compose.yaml`, pinned `requirements.txt`, standalone `GlassesPilotApplication`, boot jar через PropertiesLauncher. Контейнер `astor_glasses_api`: nonroot 10001, read-only rootfs, cap-drop ALL, no-new-privileges, bounded CPU/RAM/PIDs, модели read-only, без публичного host port. API публикуется узким HTTPS route `/api/glasses/` через существующий gateway с валидным сертификатом c3ag.ru. Старый frontend C3AG и сервисы VEDAL не заменяются.

Server-only settings: ASTOR_GLASSES_TOKEN_SHA256, TENANT, STAFF, EXPIRES_AT, TEXT_ENABLED, VOICE_ENABLED, TIMEOUT_MS, STT_TIMEOUT_MS, PYTHON, STT_SCRIPT, STT_MODEL_DIR, WORK_DIR; ASTOR_GLASSES_YANDEX_API_KEY/FOLDER, TEXT_MODEL, VISION_MODEL. Secret env лежит вне git, root 0600. SHA-256 hash не является мобильным токеном. Отзыв/ротация — замена server config и restart только isolated adapter; expiry проверяется на каждом запросе.

Отдельный service account `astor-glasses-runtime`: только `ai.languageModels.user`, API key scope `yc.ai.foundationModels.execute`, срок ключа до 2026-10-11 18:00 UTC. Мобильный доступ до 2026-10-04 22:20 UTC; bearer передаётся только приватно и сохраняется в Keychain. Мобильный клиент не получает cloud key. Этот однотокенный пилот не заменяет P1 identity/session revocation.

## Фронт и Telegram

Презентационный фронт `frontend/astor-butler` публикуется отдельно по `/astor/`. Widget обращается к `/api/astor/messages`, который через bounded anonymous-WEB-only relay обращается к основному `/api/messages`; это гостевой WEB lead transport, а не staff/admin portal. Устранены ошибка cold load из-за недоступного isLocalhost и пустой fallback reply. Действующий бот — `@astor_butler_bot`; polling transport использует отдельный WireGuard proxy. 2026-10-04 перезапущен только proxy service, подтверждены getMe и отсутствие tunnel/polling ошибок в свежем окне; бот/DB не перезапускались. Полный Telegram диалог требует отдельного smoke.

## Проверки и приёмка

Локальный Maven suite: 297 tests, 0 failures/errors/skipped. `scripts/test_glasses_audio.py`: 6 настоящих decoder fixtures, включая 30/31 секунды, stereo, 44.1kHz и испорченный контейнер. Синтетическая русская речь прошла реальный Whisper и YandexGPT; синтетический JPEG прошёл реальный Qwen. Это backend smoke, не физический HFP acceptance.

Физический тест делает отдельный чат: очки microphone → iPhone AAC m4a → API → STT → ответ → HFP TTS. Проверить cancel/timeout/offline/reconnect/lock/call interruptions, requestId и отсутствие task ACK. Не объявлять Astor Glass полностью готовым до этого теста и required GitHub CI/review.

P1: реальные staff/tenant/shift, назначенные задачи и авторизованный context, task/order/table/stage/version, evidence retention, отдельные commands с permissions/idempotency/version, ACK/replay/offline. P2: event-bound photo coaching, APNs/signing и delivery. Video/live-stream не реализован; текущий vision — один JPEG на запрос.

### Воспроизводимая сборка

Backend release staging должен содержать `app.jar` (готовый Maven boot jar), `glasses_stt.py`, `requirements.txt`, `Dockerfile`. Перед docker build дождаться завершения копирования JAR и сверить SHA-256 source/staging/image; частичный JAR не запускать. Frontend staging: `site/` из `frontend/astor-butler`, `Dockerfile` из frontend.Dockerfile, `frontend.nginx.conf`. Собрать `astor-glasses:v1` и `astor-presentation:v1`; root оператор запускает `docker compose -f docker/glasses/compose.yaml up -d`. Credentials/model cache готовятся отдельно; `docker compose config` не публиковать, он может раскрыть env. После замены контейнеров проверить DNS upstream, `nginx -t` и graceful reload gateway. Для rollback использовать сохранённые immutable image IDs и прежнюю config из backup, не перестраивать старый образ из плавающего tag.
