# Astor Glasses: информационный backend adapter

Дата: 2026-10-04. Ветка `codex/glasses-voice-adapter`, отдельный worktree. Это локальная подготовка P0 по [issue #9](https://github.com/astor-hospitality/Astor_Butler_MVP/issues/9), назначенной BryxOG. На момент проверки открытого PR с glasses API нет. Перед интеграцией сверить работу BryxOG и выбрать один adapter; не внедрять параллельный маршрут. Production, VM, основной checkout и Astor_Glasses_Spike не изменялись; телефон не использовался.

## Реализованный контракт

- `POST /api/glasses/assist`, `Content-Type: application/json`, `Authorization: Bearer …`.
- JSON: canonical UUID `requestId`, `text` (до 4000 Unicode code points); опционально одна пара `audioBase64` + `audioMimeType: audio/mp4` либо `imageBase64` + `imageMimeType: image/jpeg`. Для аудио `text` может быть пустым. Не принимать client staff/tenant/chatId, URL или неизвестные поля. JSON null для опциональных полей трактуется как отсутствие.
- Body до 5 MiB, media до 2 MiB decoded. Base64 проверяется до обращения к провайдеру. JPEG: сигнатура, reader, размеры до 1280 px без декодирования растра и записи на диск. Аудио: лишь MP4 `ftyp` header/box sanity; codec/channels/rate/duration ещё не проверяются, так как весь голосовой путь выключен. Заголовок не является доказательством валидного AAC.
- Успех `200`: `{ "requestId": "тот же UUID", "text": "непустой ответ", "capabilities": { ... } }`. Text включается отдельно и вызывает только существующий ModelGateway; blank/fallback/exception/timeout дают 503. Не передаются гостевые идентификаторы, RAG/tenant context пока отсутствует. Ответ информационный, не ACK действия.
- Голос всегда `503 VOICE_UNAVAILABLE`; изображение всегда `503 VISION_UNAVAILABLE`, даже если текст есть. Не вызываются STT или text-only analyzeImage fallback. Нет временных файлов и медиа в persistent storage, нечего удалять при ошибке/таймауте.
- `GET /api/glasses/capabilities` с тем же доступом: `text/voice/vision`, `maxAudioSeconds:30`, `maxAudioBytes:2097152`, `maxImageBytes:2097152`, `maxImageDimension:1280`, `maxTextChars:4000`, `maxBodyBytes:5242880`.
- Text readiness — успешный реальный gateway response за последние 60 секунд. До первого text smoke — false, даже при text-enabled. Text POST всё равно можно выполнить, если включены попытки. Ошибка провайдера сбрасывает readiness. Это краткоживущее наблюдение, не гарантия следующего запроса. Voice/vision остаются false.
- Ошибка: `{ "requestId": "UUID или null", "error": { "code": "…", "message": "…" } }`, без `text`. Если отказ до чтения/валидации requestId (доступ, body limit, rate), ID null. Известный валидный ID сохраняется для ошибок media/provider.
- 400 malformed/MIME/signature/unknown fields; 401 missing/invalid bearer; 403 expired/missing staff scope; 413 limits; 429 busy/rate; 503 unconfigured access/text/voice/vision. `Cache-Control: no-store`, `WWW-Authenticate: Bearer` для 401, `Retry-After:60` для 429.
- Пилот: один server-bound staff/tenant credential, до 10 авторизованных POST-попыток в фиксированную минуту, один gateway call одновременно, без очереди, timeout по умолчанию 10 секунд (максимум 30). Если gateway игнорирует interrupt, слот остаётся занятым до реального завершения; новые запросы получают 429, не запускают новые потоки. Эти лимиты локальны одному JVM, не являются распределённым rate limiter.

## Настройки после отдельного решения об интеграции

Новые настройки можно передавать через стандартный Spring environment binding; `.env` не изменён и credentials не созданы:

| Environment | Значение/назначение |
| --- | --- |
| `ASTOR_GLASSES_TOKEN_SHA256` | Hex SHA-256 высокоэнтропийного bearer, отдельно выданного через безопасный канал. Сам токен не хранить в git/docs/логах |
| `ASTOR_GLASSES_TENANT` | Server-side venue scope пилота |
| `ASTOR_GLASSES_STAFF` | Server-side staff identity пилота |
| `ASTOR_GLASSES_EXPIRES_AT` | Обязательный ISO-8601 Instant, например дата окончания смены |
| `ASTOR_GLASSES_TEXT_ENABLED` | false по умолчанию; true разрешает text attempts |
| `ASTOR_GLASSES_TIMEOUT_MS` | 10000 по умолчанию, bounded 1..30000 |

SHA-256 token configuration immutable in running bean: отзыв/ротация — замена server config и перезагрузка adapter при отдельно разрешённом deployment. Expiry проверяется на каждый запрос без рестарта. Для P1 нужен штатный identity/session revocation, а не этот однотокенный пилот. Bearer не добавляет прав в существующие API; глобальный permitAll вне glasses не исправлялся и требует отдельной работы.

Не запускать весь Spring application для этого локального теста: это может включить Telegram/startup notifications. Использовать isolated MockMvc/unit suite. Доступный HTTPS URL и тестовый доступ в этом проходе не выдавались.

## Точные блокеры голосового запуска P0

1. Supported STT runtime: существующий ExternalCommandSpeechToTextService + scripts/stt_faster_whisper.py — потенциальный порт, но readiness AAC не доказана. Нужны явно выбранные Python/ffmpeg/PyAV/faster-whisper версии, cached local model, RU transcription smoke на настоящем HFP m4a. Для облачного STT отдельно нужны server-side credentials, quota и разрешение paid calls.
2. До включения voice реализовать проверку контейнера/codec AAC/16kHz/mono/длительности ≤30s по actual media metadata, bounded decoder, secure temp file и удаление success/error/timeout. Не доверять client duration. Настроить таймауты и ограничение output/stdout/stderr процесса; прекратить и дождаться process tree до удаления файла.
3. У существующего ExternalCommand STT есть raw stdout/stderr logging, diagnostic metadata и ожидание process до drain pipe; риск раскрытия transcript и deadlock на заполненном pipe. Для glasses не вызывать этот сервис, пока не устранены утечки и не проверены timeout/cleanup. В этой ветке Telegram STT поведение не менялось.
4. Согласовать с BryxOG интеграцию маршрута #9; затем отдельно разрешённые code review/deploy и HTTPS с валидным сертификатом, scoped credential и его expiry. Нужна проверка text runtime readiness, не только health200.
5. Физический acceptance: microphone glasses → iPhone m4a → real STT → informational text → HFP TTS. Отдельно cancellation/timeout/offline/reconnect/lock/call interruptions. Здесь hardware/e2e/production readiness не подтверждались.

## Backlog Михаилу

- P0: real STT, media validation/temp lifecycle, HTTPS/access/text/voice smoke и короткий ответ для TTS. Vision необязателен для короткого голосового цикла и остаётся честно unavailable.
- P1: настоящий staff identity/tenant/shift, feed только назначенных задач, server task/order/table/stage/version, инструкции из разрешённого контекста, evidence upload/storage/retention, отдельные commands с permissions/assignment/version/idempotency, ACK/replay/offline. Две задачи разных столов, foreign tenant/assignment, stale version и duplicate event — обязательная приёмка. Informational assist никогда не завершает задачу.
- P2: import, event-bound photo coaching, настройки, delivery/lock-screen, APNs/signing. Video/live-stream не нужен для P0; firmware capabilities не обещают его поддержку.

## Проверки

`JAVA_HOME=<локальный JDK 25> mvn -Dtest=GlassesControllerTest,GlassesAssistServiceTest test`

Isolated tests используют mocked ModelGateway и синтетические container/image fixtures: это проверки контракта, доступа, границ, honest unavailable, readiness, provider errors, timeout/busy и rate limiting, не реальные STT или cloud calls. Дополнительный полный Maven suite отдельно отражается в handoff; ничего не считать deployed по локальным тестам.

Проверено локально на JDK 25: compile PASS; 25 новых glasses tests PASS; вместе с ModelGatewayProviderTest, YandexAiStudioAgentModelGatewayTest и ExternalCommandSpeechToTextServiceTest — 33 tests, 0 failures/errors/skipped. Полный repository suite и GitHub CI в этом проходе не запускались. `graphify update .` завершён: 6839 nodes/15051 edges; code graph актуализирован, HTML graph visualization пропущена CLI из-за лимита 5000 nodes.
