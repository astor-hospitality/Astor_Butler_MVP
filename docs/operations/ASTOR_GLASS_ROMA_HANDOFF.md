# Astor Glass / Astor Butler: передача Роме

Дата: 2026-10-04. Ветка `codex/glasses-voice-adapter`. Это рабочий пилот для теста и доработки; merge/release требуют review и GitHub CI, физический HFP acceptance проводит отдельный чат с iPhone.

## Что открыть

- [Astor Assist](https://c3ag.ru/astor/), [Astor Butler](https://c3ag.ru/astor/astor_butler/): отдельный frontend, чат вопросов о внедрении и Telegram-демо.
- [Telegram @astor_butler_bot](https://t.me/astor_butler_bot): действующий AERIS/Butler бот, один транспорт для настроенных гостевых/служебных сценариев. Новые отдельные боты не создавались.
- Glass API: `https://c3ag.ru/api/glasses/assist`, capabilities: `/api/glasses/capabilities`. Scoped bearer передаётся приватно; в отчёте/PR его нет. Mobile session expiry: 2026-10-04 22:20 UTC, затем требуется ротация Михаилом.
- Контракт и ограничения: [GLASSES_ASSIST_PILOT.md](GLASSES_ASSIST_PILOT.md).

## Фактически проверено

Backend Maven: 297 tests, 0 failures/errors/skipped; отдельные decoder fixtures: 6 PASS. Синтетическая русская речь прошла настоящий AAC decoder/Whisper/YandexGPT. Public HTTPS text/voice/vision вернули 200 с совпадающим requestId, ориентировочно 1.9/3.0/0.6 секунды в первом smoke. Vision описал красную геометрическую фигуру на белом фоне. Это не запись с физического микрофона очков.

Без bearer — 401; staff/tenant/expiry привязаны на сервере. Photo анализирует один JPEG, video/live-stream отсутствует. Assist даёт информационный ответ без ACK/изменения FSM. Readiness истекает через 300 секунд после успеха: false после простоя означает необходимость нового smoke, а не автоматическую остановку сервиса.

Telegram: после scoped restart только WireGuard proxy service подтверждены getMe и отсутствие polling/tunnel ошибок в свежем окне; бот/DB не перезапускались. Публичные C3AG/VEDAL/Altai страницы сохранили 200. Полный диалог бронирования/команд ещё не равен getMe и должен проверяться отдельно с явно тестовым сценарием.

Frontend — презентационный сайт плюс WEB lead чат. Публичный relay пропускает только anonymous WEB, пересобирает payload с фиксированным site; client chatId/tenant/staff/channel INTERNAL и произвольные metadata отвергаются. Не выдавать этот фронт за готовый staff/admin кабинет.

## Работа через GitHub / Claude

Роме `0xLaki` отправлено приглашение с write permission; на момент отправки текущий permission оставался read, приглашение необходимо принять. Изменения — в отдельной ветке через PR и review. Read `AGENTS.md`, `CLAUDE.md`, `docs/obsidian/**`, архитектуру и FSM viewer перед доработками. В Claude открыть этот checkout и использовать те же ограничения. Отдельного Claude account/token/agent с production secrets не подключали.

Не коммитить secrets, `.env*`, модели, аудио/фото, `target/**` или локальные `.codex*`. При работе с issue #9 сверить adapter с BryxOG, не добавлять параллельные маршруты. Информационный assist не завершает task и не имитирует ACK; task commands оформлять отдельно с assignment/version/idempotency/permissions.

## Ограниченное администрирование

На VM установлен root-owned `/usr/local/sbin/astor-glasses-admin`: help/version/status/health/restart-glasses/restart-frontend, только фиксированные контейнеры Astor. Исходник: `scripts/astor-glasses-admin`. Нет arbitrary shell/deploy/log/secret read или Docker group. VM shared с VEDAL/C3AG: Docker/root-доступ не выдавать.

SSH login Ромы пока не создан: требуется подтверждённый публичный SSH key. После его получения создать отдельного пользователя без Docker group, ограничить sudo исключительно wrapper и проверить отказ любых других команд. Доступ к private runtime.env/cloud key не нужен. Отчёт подготовлен здесь; внешняя отправка требует адреса/канала Ромы.

На VM: `/opt/astor-glasses/releases/v1`, model cache `/opt/astor-glasses/models` read-only, secret env `/opt/astor-glasses/private/runtime.env` root 0600. Контейнеры `astor_glasses_api`, `astor_presentation`, restart unless-stopped. Gateway изменения узкие; backups `/opt/astor-glasses/backups/<UTC timestamp>` содержат прежние template/active configs. Не заменять фронт C3AG или другие проекты.

## Следующие шаги

1. Физический mic → iPhone AAC → STT → ответ → HFP TTS; cancel/offline/reconnect/lock/call interruption. Записать реальные latency/status/requestId и результат, без raw credentials/media в отчёте.
2. Принять GitHub invitation, провести review и дождаться required CI. SSH подключать только после получения ключа; Claude работает через PR и wrapper.
3. P1: staff identity/shift/tenant, assigned task feed, разрешённый restaurant context, task/order/table/stage/version, evidence retention, отдельные commands/ACK/replay/offline. Пока нет реальных поручений — учебный сценарий явно маркируется как учебный.
4. Ротация mobile access после expiry и cloud API key до 2026-10-11 18:00 UTC. Keys только server-side, logging provider отключён.
