# Sber AI: включение GigaChat и Cloud.ru Foundation Models

Дата: 2026-10-08

Как перевести модели Astor Butler (и Astor Concierge) на инфраструктуру Сбера, не меняя FSM, контракты и поведение по умолчанию. Переключение — одной переменной окружения, откат — той же переменной обратно.

## Что в стеке использует AI сегодня

| Функция | Где | Провайдер сейчас | Вариант Сбера | Переключатель |
| --- | --- | --- | --- | --- |
| Понимание гостя, черновики ответов, Q&A для ops-группы (`ModelGateway.generateText`) | `fsm/understanding`, `fsm/reply`, `service/message` | `ASTOR_MODEL_PROVIDER`: `spring-ai` (Ollama, по умолчанию), `ollama-raw`, `yandex`, `yandex-agent`, `openai-compatible` | `cloudru` (Cloud.ru Foundation Models, OpenAI API) или `gigachat` (GigaChat API напрямую) | `ASTOR_MODEL_PROVIDER` |
| Vision (`analyzeImage`: фото стола, glasses) | `ModelGateway`, `api/glasses` | Ollama `qwen2.5vl`, `openai-compatible` vision-модель, Yandex AI Studio (`YandexGlassesGateway`) | `CLOUDRU_VISION_MODEL`; GigaChat через `/files` + `attachments`; glasses-pilot: `ASTOR_GLASSES_AI_PROVIDER=cloudru` или `gigachat` | см. ниже |
| STT голосовых сообщений (Telegram) и записей очков | `speech` (`SpeechToTextService`), `api/glasses` (`GlassesVoice`) | `cloudru`: Cloud.ru `openai/whisper-large-v3` (по умолчанию); `yandex`: SpeechKit v1 (бот — Ogg Opus как есть; очки — ffmpeg в образе перекодирует MP4/AAC в Ogg Opus); `local`: `faster-whisper` subprocess (rollback) | уже Сбер/Cloud.ru, см. «STT через whisper-large-v3»; SaluteSpeech STT закрыт для новых подключений | `ASTOR_STT_PROVIDER`, `ASTOR_GLASSES_STT_PROVIDER` |
| Embeddings для RAG и intent-examples (`generateEmbedding`, pgvector) | `domain/semantic` | `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER`: `none`, `ollama`, `spring-ai`, `model-gateway` (через провайдер выше), `yandex` (Yandex AI Studio напрямую, не зависит от `ASTOR_MODEL_PROVIDER`) | GigaChat `Embeddings` платные (402 на бесплатном пакете) → прод: `yandex`, см. «Эмбеддинги: Yandex text-search» | `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER` |
| TTS для очков (`GlassesSpeech` → порт `TextToSpeech`) | `api/glasses`, `speech/` | Yandex SpeechKit TTS (`yandex`, по умолчанию в glasses-runtime) | SaluteSpeech TTS (`salute`), см. «TTS через SaluteSpeech» | `ASTOR_GLASSES_TTS_PROVIDER` |
| TTS веб-чата CLIO (`POST /api/chat/speak` в бэкенде; `frontend/app/api/chat/speak` остаётся test-double) | `api/speech`, `speech/` | SpeechKit (`yandex`, rollback) | SaluteSpeech (`salute`, по умолчанию) | `ASTOR_TTS_PROVIDER`, `ASTOR_TTS_WEB_ENABLED` |
| STT веб-чата CLIO (`frontend/app/api/chat/transcribe`) | frontend | заглушка `yandex-speechkit` (test-double) | SaluteSpeech, не реализовано | — |

Исходники провайдеров: `src/main/java/museon_online/astor_butler/model/CloudRuModelGateway.java`, `GigaChatModelGateway.java`, `GigaChatTrust.java`; речь — `src/main/java/museon_online/astor_butler/speech/` (`TextToSpeech`, `SaluteSpeechTextToSpeech`, `SpeechKitTextToSpeech`, `TextToSpeechProviders`).

## Вариант 1: Cloud.ru Evolution Foundation Models (`cloudru`)

Хостинг моделей Сбера с OpenAI-совместимым API (`/chat/completions`, `/embeddings`, `/models`). Самый дешёвый путь: тот же код, что и `openai-compatible`, свои переменные `CLOUDRU_*`.

Где взять ключ:

1. Консоль Cloud.ru → Evolution Foundation Models → создать API-ключ (нужен проект и привязанный способ оплаты).
2. Список доступных моделей и точные имена: `GET https://foundation-models.api.cloud.ru/v1/models` с заголовком `Authorization: Bearer <ключ>`.
3. Тарифы — за 1M токенов, свои у каждой модели; смотреть в каталоге Cloud.ru на момент включения (в репозитории цены не фиксируем).

Переменные (`.env.production` → docker-compose passthrough уже есть):

```
ASTOR_MODEL_PROVIDER=cloudru
CLOUDRU_API_KEY=<ключ из консоли Cloud.ru>
CLOUDRU_MODEL=GigaChat/GigaChat-2-Max           # frontline: intent/slot JSON
CLOUDRU_QUALITY_MODEL=Qwen/Qwen3-235B-A22B-Instruct-2507   # опционально, профиль QUALITY
CLOUDRU_VISION_MODEL=                            # опционально; без него vision отвечает fallback
CLOUDRU_EMBEDDING_MODEL=                         # опционально; см. раздел про embeddings
# CLOUDRU_BASE_URL=https://foundation-models.api.cloud.ru/v1
# CLOUDRU_JSON_MODE=true   # false, если модель отвергает response_format
# CLOUDRU_TEMPERATURE=0.1  # отрицательное значение = не отправлять
```

Имена моделей `GigaChat/GigaChat-2-Max`, `Qwen/Qwen3-235B-A22B-Instruct-2507`, `openai/gpt-oss-120b` — из публичного каталога Cloud.ru на дату написания; **перед включением сверить с `GET /models`** (допущение, в коде имена не зашиты, кроме значений по умолчанию в glasses-pilot).

Glasses-pilot (изолированный рантайм `GlassesPilotApplication`):

```
ASTOR_GLASSES_AI_PROVIDER=cloudru                # прежнее имя ASTOR_GLASSES_MODEL_PROVIDER тоже читается
ASTOR_GLASSES_CLOUDRU_API_KEY=<ключ>
ASTOR_GLASSES_TEXT_MODEL=GigaChat/GigaChat-2-Max
ASTOR_GLASSES_VISION_MODEL=Qwen/Qwen2.5-VL-72B-Instruct   # имя сверить с /models
```

## STT через whisper-large-v3 (Cloud.ru)

Правило владельца: на VM нет локального ML, только платные облачные модели. Распознавание речи поэтому идёт в Cloud.ru Evolution Foundation Models: `POST https://foundation-models.api.cloud.ru/v1/audio/transcriptions` (OpenAI-совместимый multipart: `file`, `model=openai/whisper-large-v3`, `language=ru`, `response_format=json` → `{"text": "..."}`; заголовок `Authorization: Bearer <CLOUDRU_API_KEY>`; лимит файла 25 МБ). Ключ тот же, что у текстовых моделей (`CLOUDRU_API_KEY` / `ASTOR_GLASSES_CLOUDRU_API_KEY`), тарифицируется по минутам аудио — смотреть в каталоге Cloud.ru, имя модели сверить с `GET /models`.

Адаптер: `CloudRuWhisperSpeechToText` (JDK `HttpClient`, без Spring, общий для бота и очков). Без ретраев на `4xx`, один повтор на `5xx` и таймаут; ошибка — `CloudRuWhisperException` со статусом и причиной (без аудио, текста и ключа). Локальный `faster-whisper` остаётся выбираемым для отката.

### Бот (Telegram-голосовые, `aeris-*`, `c3flex-*`, `smart-solution-bot`)

`.env.production` (docker-compose passthrough есть в `docker-compose.yml` и `docker-compose.prod.yml`; в prod значение по умолчанию уже `cloudru`):

```
ASTOR_STT_ENABLED=true
ASTOR_STT_PROVIDER=cloudru                       # local = faster-whisper subprocess (rollback)
CLOUDRU_API_KEY=<ключ из консоли Cloud.ru>        # общий с ASTOR_MODEL_PROVIDER=cloudru
# CLOUDRU_BASE_URL=https://foundation-models.api.cloud.ru/v1
# ASTOR_STT_CLOUDRU_MODEL=openai/whisper-large-v3
# ASTOR_STT_LANGUAGE=ru                          # пусто = автоопределение языка
# ASTOR_STT_TIMEOUT_SECONDS=60                   # весь запрос вместе с загрузкой файла
```

Образ бота (`Dockerfile` в корне, собирается в GHCR workflow-ом `deploy-cloudru-vm.yml`) по умолчанию **без** Python/ffmpeg/faster-whisper (`ARG STT_LOCAL_WHISPER=false`), поэтому `ASTOR_STT_PROVIDER=local` в нём работать не будет. Telegram отдаёт голосовые как `audio/ogg` (opus) — Whisper принимает ogg/opus, mp3, wav, m4a без перекодирования; `telegram.voice.max-file-size-bytes` (10 МБ) ниже лимита 25 МБ.

Откат на локальный STT: собрать образ `docker build --build-arg STT_LOCAL_WHISPER=true -t <image> .` (или `STT_LOCAL_WHISPER=true docker compose build`), выставить `ASTOR_STT_PROVIDER=local` и прежний `ASTOR_STT_COMMAND`/`ASTOR_STT_MODEL`, перезапустить бота. Неизвестное значение `ASTOR_STT_PROVIDER` валит старт с сообщением, какие значения допустимы.

### Очки (`GlassesPilotApplication`, контейнер `astor_glasses_api`)

`runtime.env` (`docker/glasses/runtime.env.example`):

```
ASTOR_GLASSES_VOICE_ENABLED=true
ASTOR_GLASSES_STT_PROVIDER=cloudru               # local = glasses_stt.py (rollback)
ASTOR_GLASSES_CLOUDRU_API_KEY=<ключ>             # тот же, что для ASTOR_GLASSES_AI_PROVIDER=cloudru
# CLOUDRU_BASE_URL=https://foundation-models.api.cloud.ru/v1
# ASTOR_GLASSES_STT_CLOUDRU_MODEL=openai/whisper-large-v3
# ASTOR_GLASSES_STT_LANGUAGE=ru
# ASTOR_GLASSES_STT_TIMEOUT_MS=20000             # максимум 30000
```

Какой Dockerfile собирать:

| Образ | Dockerfile | Что внутри | Когда |
| --- | --- | --- | --- |
| production | `docker/glasses/Dockerfile.cloud` | JRE + `ffmpeg` + `app.jar`; провайдер — `ASTOR_GLASSES_STT_PROVIDER` из `runtime.env` (`yandex` — SpeechKit после перекодирования ffmpeg, `cloudru` — whisper; в ENV образа только значение по умолчанию `cloudru`); staging-каталогу нужен один `app.jar` | всегда, пока STT в облаке |
| rollback | `docker/glasses/Dockerfile` | python venv + faster-whisper/ctranslate2/onnxruntime (~1.5 ГБ), `ASTOR_GLASSES_STT_PROVIDER=local`, модели в `/models` | только если облачный STT недоступен |

Compose: `docker compose -f docker/glasses/compose.yaml -f docker/glasses/compose.cloudru.yaml up -d` на Cloud.ru VM (overlay убирает bind `/models`, он не нужен облачному образу). Телефон шлёт `audio/mp4` (AAC mono 16 кГц, до 2 МБ); адаптер отправляет байты как `input.m4a` без временных файлов. Отличия от локального декодера: граница 30 секунд записи больше не проверяется (ограничитель — 2 МБ в контроллере), пустой ответ модели = `NO_SPEECH` (400), отказ сервиса по формату = `MALFORMED_AUDIO` (400), прочее — `VOICE_UNAVAILABLE` (503), текст и диагностика провайдера наружу не уходят.

### Проверка

1. Лог старта бота без `ASTOR_STT_PROVIDER must be local or cloudru`; в payload входящего голосового `transcriptionMetadata.provider=cloudru`.
2. Смоук: голосовое в Telegram → текст в FSM; `/api/glasses/transcribe` с короткой записью → `text`.
3. Если ключа нет, STT не роняет сервис: бот отвечает `transcriptionStatus=FAILED` с причиной `CLOUDRU_API_KEY is not set`, очки — `503 VOICE_UNAVAILABLE`.

Риски: задержка сети (загрузка файла + инференс, обычно 2–6 с на голосовое до минуты — ставьте `ASTOR_STT_TIMEOUT_SECONDS` с запасом); лимит 25 МБ (у Telegram 10 МБ, у очков 2 МБ — не достигается); тарификация за минуты; при 5xx Cloud.ru один повтор, затем `FAILED` без локального fallback.

## Вариант 2: GigaChat API напрямую (`gigachat`)

Для контракта с GigaChat (личный, B2B или корпоративный). OAuth2-токен живёт ~30 минут, обновляется автоматически за 60 секунд до истечения и при `401`.

Где взять ключ:

1. developers.sber.ru → GigaChat API → проект → «Получить Authorization key» (Base64 от `client_id:client_secret`; это и есть `GIGACHAT_AUTH_KEY`).
2. Scope: `GIGACHAT_API_PERS` (физлицо, freemium + оплата по токенам), `GIGACHAT_API_B2B` (юрлицо, pay-as-you-go), `GIGACHAT_API_CORP` (юрлицо, пакеты). Scope должен совпадать с договором, иначе OAuth отвечает `401`.
3. Тарифы — по токенам, отдельно для `GigaChat` (Lite), `GigaChat-Pro`, `GigaChat-Max` и `Embeddings`; актуальные в кабинете.

Сертификаты. Эндпоинты Сбера подписаны НУЦ Минцифры (Russian Trusted Root CA), которого нет в стандартном trust store JVM. Решение в коде — `GigaChatTrust`: PEM добавляется **к** системным корневым, проверка TLS не отключается, hostname проверяется JDK-клиентом.

1. Скачать цепочку с gosuslugi.ru (страница «Сертификаты НУЦ Минцифры»): корневой `russian_trusted_root_ca_pem.crt` и промежуточный `russian_trusted_sub_ca_pem.crt`; сверить отпечатки с опубликованными.
2. Склеить в один файл `certs/russian_trusted_root_ca.pem` (каталог `certs/` в `.gitignore`).
3. В `docker-compose.yml` раскомментировать том `./certs/...` у сервиса бота, в `.env.production` указать `GIGACHAT_CA_CERT_PATH=/app/certs/russian_trusted_root_ca.pem`.
4. Нечитаемый путь роняет первый вызов модели с `GIGACHAT_CA_CERT_PATH ...` — это намеренно, вместо тихого отключения TLS.

Переменные:

```
ASTOR_MODEL_PROVIDER=gigachat
GIGACHAT_AUTH_KEY=<Authorization key из кабинета>
GIGACHAT_SCOPE=GIGACHAT_API_PERS            # или GIGACHAT_API_B2B / GIGACHAT_API_CORP
GIGACHAT_CA_CERT_PATH=/app/certs/russian_trusted_root_ca.pem
GIGACHAT_MODEL=GigaChat                      # frontline
GIGACHAT_QUALITY_MODEL=GigaChat-Max          # опционально
GIGACHAT_VISION_MODEL=GigaChat-Max           # опционально; фото уходит в /files и прикрепляется по id
GIGACHAT_EMBEDDING_MODEL=Embeddings          # по умолчанию; EmbeddingsGigaR — крупнее
# GIGACHAT_OAUTH_URL=https://ngw.devices.sberbank.ru:9443/api/v2/oauth
# GIGACHAT_API_URL=https://gigachat.devices.sberbank.ru/api/v1
```

Ограничения GigaChat, учтённые в адаптере: нет `response_format` (JSON-промпты полагаются на инструкции в тексте; `GuestInputUnderstandingService` уже валидирует ответ); картинки только через загрузку файла (`purpose=general`), поддерживают её модели Pro/Max — проверить на пилоте, т.к. до живого ключа это допущение.

## Прямые ключи Сбера, пока Cloud.ru FM недоступен

Состояние на 2026-10-08: Cloud.ru Foundation Models отвечает `402`, пока поддержка не активирует биллинг. SaluteSpeech закрыт для новых подключений (ни STT, ни новый ключ TTS), поэтому голос и распознавание временно идут через Yandex SpeechKit, а текст и vision — через GigaChat API напрямую. Код Cloud.ru и SaluteSpeech остаётся, возврат — теми же переменными.

Проверено живыми ключами (с VM): GigaChat API со scope `GIGACHAT_API_PERS` отвечает на `/chat/completions` для `GigaChat-2-Max`, `GigaChat-2`, `GigaChat`; `/embeddings` на бесплатном пакете отвечает `402`. Ключ Yandex с scope `foundationModels` + `speechkitStt` + `speechkitTts` работает: TTS `oggopus` — 200, STT туда-обратно — OK, embeddings `text-search` — размерность 256.

### Бот (`.env.production`, три бота)

```
# Текст: GigaChat API напрямую
ASTOR_MODEL_PROVIDER=gigachat
GIGACHAT_AUTH_KEY=<Authorization key GigaChat>
GIGACHAT_SCOPE=GIGACHAT_API_PERS
GIGACHAT_CA_CERT_PATH=/app/certs/russian_trusted_root_ca.pem   # том из docker-compose.cloudru.yml
GIGACHAT_MODEL=GigaChat-2-Max
GIGACHAT_QUALITY_MODEL=GigaChat-2-Max
GIGACHAT_VISION_MODEL=GigaChat-2-Max                           # фото через /files + attachments
# Embeddings: на бесплатном пакете GigaChat /embeddings = 402, а model-gateway пошёл бы именно туда
ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=none

# Голос и распознавание: Yandex SpeechKit (ключ сервисного аккаунта)
ASTOR_TTS_PROVIDER=yandex
YANDEX_SPEECHKIT_API_KEY=<Api-Key сервисного аккаунта>
YANDEX_SPEECHKIT_TTS_VOICE=filipp                               # folderId не нужен: ключ сервисного аккаунта
YANDEX_TTS_FORMAT=oggopus                                       # Telegram voice; mp3 — для веба
ASTOR_STT_ENABLED=true
ASTOR_STT_PROVIDER=yandex
ASTOR_STT_LANGUAGE=ru                                           # уходит как lang=ru-RU
# ASTOR_STT_TIMEOUT_SECONDS=60
# YANDEX_SPEECHKIT_STT_ENDPOINT=https://stt.api.cloud.yandex.net/speech/v1/stt:recognize
```

`ASTOR_TELEGRAM_VOICE_REPLIES=on|auto` работает с `YANDEX_TTS_FORMAT=oggopus` (Ogg Opus → `audio/ogg`, отправка через `sendVoice`); при `mp3` бот предупредит в логе и ответит текстом. Passthrough `YANDEX_TTS_FORMAT` добавлен в `docker-compose.yml` / `docker-compose.prod.yml`; остальные переменные там уже были. Сертификат НУЦ на VM уже лежит в `/opt/astor-butler/certs/russian_trusted_root_ca.pem`, `docker-compose.cloudru.yml` монтирует его во все три бота (`/app/certs/...:ro`). Если файла на хосте нет, Docker создаст на его месте каталог и первый вызов GigaChat упадёт с `GIGACHAT_CA_CERT_PATH ...` — TLS не ослабляется.

STT-адаптер `yandex` (`YandexSpeechKitSpeechToText`): `POST https://stt.api.cloud.yandex.net/speech/v1/stt:recognize?lang=ru-RU&format=oggopus`, `Authorization: Api-Key`, тело — байты голосового, ответ `{"result": "..."}`. Лимиты синхронного v1: 1 МБ, 30 секунд, один канал. Размер проверяется до отправки; голосовое длиннее 30 секунд SpeechKit отклоняет (`400`), гость получает обычный `FAILED`-путь с причиной, нарезки нет. Кодировка определяется по байтам: уходит только Ogg Opus (Telegram voice), остальное (MP3, WAV, MP4) отклоняется локально без вызова. Ретраи: без повторов на `4xx`, один повтор на `5xx` и таймаут.

### Очки (`/opt/astor-glasses/private/runtime.env`)

```
ASTOR_GLASSES_AI_PROVIDER=gigachat
ASTOR_GLASSES_GIGACHAT_AUTH_KEY=<Authorization key GigaChat>   # если пусто — берётся GIGACHAT_AUTH_KEY
GIGACHAT_SCOPE=GIGACHAT_API_PERS
GIGACHAT_CA_CERT_PATH=/certs/russian_trusted_root_ca.pem      # том из docker/glasses/compose.cloudru.yaml
ASTOR_GLASSES_TEXT_MODEL=GigaChat-2-Max                        # по умолчанию; префикс "GigaChat/" отбрасывается
ASTOR_GLASSES_VISION_MODEL=GigaChat-2-Max                      # по умолчанию; Qwen-имя от Cloud.ru здесь не подойдёт
# GIGACHAT_TIMEOUT_MS=20000

ASTOR_GLASSES_TTS_ENABLED=true
ASTOR_GLASSES_TTS_PROVIDER=yandex
ASTOR_GLASSES_TTS_API_KEY=<Api-Key SpeechKit>
ASTOR_GLASSES_TTS_VOICE=filipp                                 # очки остаются на MP3

ASTOR_GLASSES_STT_PROVIDER=yandex                              # SpeechKit v1; MP4/AAC → Ogg Opus через ffmpeg в образе
YANDEX_SPEECHKIT_API_KEY=<Api-Key SpeechKit>                   # или отдельный ASTOR_GLASSES_STT_API_KEY
# ASTOR_GLASSES_STT_LANGUAGE=ru                                # уходит как lang=ru-RU
# ASTOR_GLASSES_FFMPEG=ffmpeg                                  # бинарник в образе Dockerfile.cloud
```

Провайдер очков `gigachat` (`GigaChatGlassesGateway`) переиспользует `GigaChatModelGateway` бота: OAuth через NGW с кэшем токена, `GigaChatTrust` для НУЦ, фото — загрузка в `/files` и `attachments`. Поверх — контракт очков, как у `GlassesCompletionsGateway`: ответ только при `finish_reason=stop` и непустом тексте (`length` и `blacklist` = «Provider unavailable»), `max_tokens` 256 для текста и 1024 для фото, `temperature` 0.1, никакой диагностики провайдера наружу. Выбор — та же настройка `ASTOR_GLASSES_AI_PROVIDER` (`yandex` | `cloudru` | `gigachat`), неизвестное значение роняет старт. Ключ свой (`ASTOR_GLASSES_GIGACHAT_AUTH_KEY`), ключи Cloud.ru/Yandex из того же файла GigaChat не получает. `docker/glasses/compose.cloudru.yaml` монтирует `/opt/astor-glasses/certs/russian_trusted_root_ca.pem` в `/certs/...:ro`.

STT очков (с 2026-10-10): телефон пишет MP4/AAC, а синхронный SpeechKit v1 принимает только Ogg Opus и LPCM без заголовка, поэтому при `ASTOR_GLASSES_STT_PROVIDER=yandex` `GlassesVoice` перекодирует запись `ffmpeg`-ом внутри контейнера (`-map 0:a:0 -t 30 -ac 1 -ar 16000 -c:a libopus -b:a 32k -application voip -f ogg`, приватный временный каталог, удаляется после запроса, ничего не логируется) и отдаёт Opus тому же адаптеру `YandexSpeechKitSpeechToText`, что и у бота. Ключ — `ASTOR_GLASSES_STT_API_KEY`, при пустом `YANDEX_SPEECHKIT_API_KEY`. Образ `docker/glasses/Dockerfile.cloud` ставит `ffmpeg` (Ubuntu-сборка с libopus), пользователь остаётся `10001`. Ответы телефону как у `cloudru`: пустой `result` — `400 NO_SPEECH`, отказ ffmpeg или `400/415/422` SpeechKit — `400 MALFORMED_AUDIO`, больше 1 МБ Opus — `413 AUDIO_TOO_LONG`, прочее — `503 VOICE_UNAVAILABLE`. Включение: `ASTOR_GLASSES_STT_PROVIDER=yandex` в `runtime.env`, пересборка образа из `Dockerfile.cloud`, `docker compose ... up -d --force-recreate glasses` — пошагово в `GLASSES_ASSIST_PILOT.md`, раздел «STT очков через Yandex SpeechKit». Локального ML на VM по-прежнему нет: ffmpeg только перекодирует.

### Что не проверено живым вызовом (допущения)

- `GigaChatGlassesGateway` и vision `GigaChat-2-Max` через `/files` на ключе `GIGACHAT_API_PERS` — только stub-тесты; чат GigaChat с VM проверен, загрузка фото — нет.
- SpeechKit STT из кода: `Content-Type: application/octet-stream`, отсутствие `folderId` при ключе сервисного аккаунта, текст ошибки `400` при голосовом длиннее 30 секунд — по документации v1 и stub-тестам; живой round-trip делался вне этого кода.
- SpeechKit STT на Opus, полученном из записи очков через ffmpeg: команда ffmpeg проверена локально на синтетическом AAC (валидный → Ogg Opus mono ≤ 30 с, обрезанный/пустой → ненулевой exit), сам round-trip очки → SpeechKit — только stub-тесты; установка `ffmpeg` в `eclipse-temurin:25-jre` (Ubuntu) с libopus — по пакетной базе, образ до слияния не собирался.
- SpeechKit TTS `format=oggopus` в форме `tts:synthesize` вместе с `voice`/`emotion`/`speed` (без `folderId`, как после #78) — живой запрос 200 был, но не через этот адаптер.

### Откат

Вернуть `ASTOR_MODEL_PROVIDER=cloudru`, `ASTOR_STT_PROVIDER=cloudru`, `ASTOR_TTS_PROVIDER=salute`, `ASTOR_GLASSES_AI_PROVIDER=cloudru`, `ASTOR_GLASSES_STT_PROVIDER=cloudru` — после активации биллинга Cloud.ru (образ с ffmpeg остаётся, ffmpeg при `cloudru` не вызывается). Переменные `GIGACHAT_*`/`YANDEX_*` можно оставить: без выбранного провайдера они не читаются (кроме TTS-настроек SpeechKit, которые читаются, но не используются).

## Embeddings и переиндексация

Колонки `semantic_embeddings.embedding` и `intent_example_embeddings.embedding` — `vector` без фиксированной размерности: исходный `vector(1536)` из `2026-06-11-semantic-memory-pgvector.sql` снят changeset-ом `2026-06-29-semantic-rag-runtime`. Каждая строка хранит `embedding_model` и `embedding_dimension`, поиск сравнивает только строки текущей модели и размерности. Поэтому смена модели embeddings (Yandex `text-search-*` — 256, GigaChat `Embeddings` — 1024, Cloud.ru — свои) не требует миграции, только:

1. выставить `ASTOR_SEMANTIC_EMBEDDING_DIMENSION` под модель и `ASTOR_SEMANTIC_EMBEDDING_MODEL`/`_QUERY_EMBEDDING_MODEL` (для `model-gateway` + `cloudru`/`gigachat` имя берётся из `CLOUDRU_EMBEDDING_MODEL`/`GIGACHAT_EMBEDDING_MODEL`, `ASTOR_SEMANTIC_EMBEDDING_MODEL` остаётся меткой в БД);
2. один раз перезапустить с переиндексацией (`AERIS_SEMANTIC_CHUNKS_INGEST_ON_STARTUP=true` — по умолчанию уже `true`, `AERIS_INTENT_EXAMPLES_INGEST_ON_STARTUP=true`): bootstrap-ы перезаписывают векторы по ключу строки новой моделью. Старые векторы другой модели/размерности поиск не видит; `SemanticEmbeddingStoreCheck` пишет в лог их число и флаг, который их перезапишет.

Если колонку когда-то вручную закрепили как `vector(N)` с другим N, бот не стартует с сообщением `... is vector(N) but ASTOR_SEMANTIC_EMBEDDING_DIMENSION=...` и готовым SQL — разовая правка (векторы всё равно пересчитываются при старте):

```sql
DELETE FROM semantic_embeddings;        ALTER TABLE semantic_embeddings        ALTER COLUMN embedding TYPE vector;
DELETE FROM intent_example_embeddings;  ALTER TABLE intent_example_embeddings  ALTER COLUMN embedding TYPE vector;
```

Пока embeddings не настроены, безопасный вариант — `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=none` (поиск по примерам интентов и RAG отключаются, FSM работает на правилах, RAG — на текстовом поиске).

## Эмбеддинги: Yandex text-search (GigaChat Embeddings платные)

GigaChat `/embeddings` на бесплатном пакете владельца отвечает `402`, поэтому чат остаётся на `ASTOR_MODEL_PROVIDER=gigachat`, а векторы считает Yandex AI Studio через отдельный адаптер `YandexTextEmbeddingProvider` (`ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=yandex`), который от `ASTOR_MODEL_PROVIDER` не зависит:

- `POST https://llm.api.cloud.yandex.net/foundationModels/v1/textEmbedding`, `Authorization: Api-Key <ключ>`, тело `{"modelUri":"emb://<folder>/text-search-doc/latest","text":"..."}`, ответ `{"embedding":[256 чисел]}` (проверено вживую);
- документы (RAG-чанки, intent-examples, их поиск) — `text-search-doc/latest`, запрос гостя к RAG — `text-search-query/latest`; полный `emb://...` в `ASTOR_SEMANTIC_*EMBEDDING_MODEL` используется как есть;
- ключ: `ASTOR_EMBEDDINGS_YANDEX_API_KEY`, если пусто — `YANDEX_SPEECHKIT_API_KEY`, затем `YANDEX_API_KEY`; каталог: `ASTOR_EMBEDDINGS_YANDEX_FOLDER_ID`, затем `YANDEX_FOLDER_ID`, затем `YANDEX_SPEECHKIT_FOLDER_ID`. Сервисному аккаунту ключа нужна роль `ai.languageModels.user` в этом каталоге; у ключа с ограниченной областью действия должна быть `yc.ai.foundationModels.execute` (ключ только для SpeechKit не подойдёт);
- ретраи `429`/`5xx`/сетевых ошибок — `YANDEX_EMBEDDING_MAX_ATTEMPTS` (6) с паузой `YANDEX_EMBEDDING_RETRY_DELAY_MS` (5000, далее x2 до x16 или `Retry-After`); `ASTOR_SEMANTIC_EMBEDDINGS_THROTTLE_MS` (1200 в compose) — минимальный интервал между запросами, простаивающий адаптер не ждёт;
- ответ другой размерности, чем `ASTOR_SEMANTIC_EMBEDDING_DIMENSION`, отклоняется с подсказкой нужного значения; ни ключ, ни тело ответа в лог не пишутся.

Прод (`/opt/astor-butler/.env.production`, passthrough в `docker-compose.yml`/`docker-compose.prod.yml` для `aeris-*` и `c3flex-*`):

```
ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=yandex
ASTOR_SEMANTIC_EMBEDDING_DIMENSION=256
ASTOR_SEMANTIC_EMBEDDING_MODEL=text-search-doc/latest
ASTOR_SEMANTIC_QUERY_EMBEDDING_MODEL=text-search-query/latest
ASTOR_EMBEDDINGS_YANDEX_API_KEY=<API-ключ Yandex Cloud>     # пусто = YANDEX_SPEECHKIT_API_KEY, затем YANDEX_API_KEY
YANDEX_FOLDER_ID=<id каталога>                              # уже обязателен в docker-compose.prod.yml
# ASTOR_EMBEDDINGS_YANDEX_FOLDER_ID=<id каталога>           # только если ключ из другого каталога
AERIS_INTENT_EXAMPLES_INGEST_ON_STARTUP=true                # переиндексация intent-examples (golden corpus) на старте
AERIS_SEMANTIC_CHUNKS_INGEST_ON_STARTUP=true                # по умолчанию true: RAG-чанки из classpath:semantic
# ASTOR_SEMANTIC_EMBEDDINGS_THROTTLE_MS=1200                # можно снизить, если в логе нет 429
```

Переиндексация на старте: оба bootstrap-а (`IntentExampleBootstrap`, `SemanticMemoryBootstrap`) — идемпотентные upsert-ы, при каждом старте с флагами пересчитывают все векторы текущим провайдером (около 60 запросов, при 1200 мс — порядка 80 с после подъёма HTTP; healthcheck бота это выдерживает). Ошибка embeddings выключает их до конца старта, чанки и примеры всё равно пишутся. Флаги можно оставить включёнными. Индекса по векторам нет: десятки строк, последовательный просмотр быстрее.

Проверка после `docker compose ... up -d aeris-astor-butler-bot`:

1. Лог старта: `Semantic embeddings provider=yandex ... dimension=256 apiKey=set folderId=set`, `Semantic embedding store ok` (или предупреждение о строках к переиндексации), `Intent examples bootstrapped: examples=N, embeddings=N, model=text-search-doc/latest`, `Semantic RAG chunks bootstrapped: chunks=N, embeddings=N`.
2. В БД: `SELECT embedding_model, embedding_dimension, count(*) FROM semantic_embeddings GROUP BY 1, 2;` и то же для `intent_example_embeddings` — одна строка `text-search-doc/latest | 256`.
3. Смоук: свободный вопрос по меню в боте получает ответ из RAG (`Semantic retrieval skipped` в логе быть не должно).

Откат: `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=none` и перезапуск; векторы в БД можно не трогать. Неизвестное значение провайдера останавливает старт с сообщением, называющим `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER`.

## Переключение и откат

1. Заполнить переменные нужного варианта в `.env.production` (секреты только на сервере, не в репозитории и не в чате).
2. `ASTOR_MODEL_PROVIDER=cloudru` или `gigachat`, перезапустить бота: `docker compose up -d aeris-astor-butler-bot` (и остальные боты по необходимости).
3. Проверить лог старта: не должно быть `Model provider ... is selected but not configured` и `GIGACHAT_CA_CERT_PATH`-ошибок; `ModelGateway text generation provider=cloudru|gigachat` в debug-логе.
4. Смоук как в `AERIS_YANDEX_RAG_AND_LOGGING_SMOKE.md`: голосовое/текстовое бронирование, ответ на свободный вопрос.
5. Откат: вернуть `ASTOR_MODEL_PROVIDER` к прежнему значению (`yandex` в prod, `spring-ai` локально) и перезапустить. Переменные `CLOUDRU_*`/`GIGACHAT_*` можно оставить — без выбранного провайдера они не читаются.

## TTS через SaluteSpeech

Голосовые сообщения гостей и записи очков распознаются Cloud.ru whisper-large-v3 (см. выше; локальный `faster-whisper` остался только для отката), веб-чат CLIO держит заглушки Yandex SpeechKit, очки используют Yandex SpeechKit TTS (`GlassesSpeech`). Перевод на SaluteSpeech (Сбер) не реализован: нужен отдельный адаптер с тем же OAuth-потоком через `ngw.devices.sberbank.ru` (scope `SALUTE_SPEECH_PERS`/`_B2B`/`_CORP`), эндпоинты `smartspeech.sber.ru/rest/v1/speech:recognize` и `.../text:synthesize`, тот же сертификат НУЦ. Объём работы (только TTS и веб-чат, STT уже на Cloud.ru): `GlassesSpeech` (endpoint + заголовок `Authorization: Bearer` вместо `Api-Key`, формат `application/x-www-form-urlencoded` → `audio/x-pcm`/`audio/ogg`), `frontend/app/api/chat/{transcribe,speak}` (сейчас test-double), `OPENAI`/Yandex там нет.

Голос Астора (очки) и Clio (веб-чат) синтезируется в облаке Сбера: SaluteSpeech, REST-синтез. Локальных моделей нет и не планируется. Прежний провайдер — Yandex SpeechKit — остаётся в коде как `yandex` для отката той же переменной.

Распознавание голосовых (STT) описано выше: whisper-large-v3 в Cloud.ru, локальных моделей на ВМ нет.

### Как это устроено

Порт `TextToSpeech` (`speech/TextToSpeech.java`) и два адаптера:

- `SaluteSpeechTextToSpeech` — OAuth-токен с того же шлюза, что у GigaChat (`POST https://ngw.devices.sberbank.ru:9443/api/v2/oauth`, `Authorization: Basic <SALUTE_AUTH_KEY>`, `RqUID`, `scope=SALUTE_SPEECH_*`), токен живёт 30 минут, кэшируется и обновляется за 60 секунд до истечения и один раз при `401` от синтеза. Синтез — `POST https://smartspeech.sber.ru/rest/v1/text:synthesize?format=<формат>&voice=<голос>`, тело — текст (`Content-Type: application/text`) или SSML (`application/ssml`, если строка начинается с `<speak`), `Authorization: Bearer <токен>`, ответ — байты аудио. Лимит тела — 4 000 символов (наши вызовы ограничены 600).
- `SpeechKitTextToSpeech` — ровно тот запрос, который очки отправляли с пилота (form-encoded, `Api-Key`, MP3). Поведение не менялось.

Сертификаты: оба хоста Сбера подписаны НУЦ Минцифры; тот же PEM-бандл, что для GigaChat (см. «Вариант 2»), подключается через `SALUTE_CA_CERT_PATH` (если не задан — берётся `GIGACHAT_CA_CERT_PATH`). Проверка TLS не отключается: нечитаемый путь роняет старт бина, а не включает «доверять всем».

Кто использует порт:

| Потребитель | Переключатель | Как отдаёт аудио |
| --- | --- | --- |
| Очки, `GlassesSpeech` (`/api/glasses/speech`, поле `audioBase64` в `assist`) | `ASTOR_GLASSES_TTS_PROVIDER=yandex` (по умолчанию) или `salute` в `runtime.env` glasses-контейнера | `audioMimeType` теперь берётся из адаптера: `audio/mpeg` (SpeechKit) или `audio/wav` / `audio/ogg` (SaluteSpeech по `SALUTE_TTS_FORMAT`); `audioVoiceGender` — по голосу. Лимит аудио поднят до 4 MiB (600 символов WAV 24 kHz ≈ 2 MiB). |
| Веб-чат CLIO, `POST /api/chat/speak` (бэкенд, `ChatSpeechController`) | `ASTOR_TTS_PROVIDER=salute` (по умолчанию) или `yandex`; `ASTOR_TTS_WEB_ENABLED=true` включает эндпоинт | JSON той же формы, что ждёт виджет: `audioUrl` (data-URL), плюс `audioBase64`, `audioMimeType`, `voice`, `status` (`READY`/`UNAVAILABLE`/`FAILED`). 503 без аудио, если голос выключен или провайдер упал; 429 при превышении `ASTOR_TTS_WEB_CONCURRENCY`/`ASTOR_TTS_WEB_RATE_PER_MINUTE`. Фронтенд указывает на него `NEXT_PUBLIC_CLIO_TTS_ENDPOINT=https://api.c3ag.ru/api/chat/speak` (CORS для `/api/**` уже настроен через `ASTOR_WEB_ALLOWED_ORIGINS`); локальная заглушка `frontend/app/api/chat/speak` не изменилась. |
| Telegram-бот, `TelegramVoiceReplyService` | `ASTOR_TELEGRAM_VOICE_REPLIES=off` (по умолчанию) / `on` / `auto`, плюс `SALUTE_TTS_FORMAT=opus` | Голосовое через `sendVoice` (Ogg Opus) и короткая текстовая сводка с кнопками-ссылками и «Подробнее»; гость переключает hands-free командой `/voice on|off`. Описание, таймаут, fallback и стоимость — `TELEGRAM_VOICE_REPLIES.md`. |

### Где взять ключ

1. developers.sber.ru → Личный кабинет → проект → добавить сервис **SaluteSpeech** → «Получить Authorization key». Это Base64 от `client_id:client_secret` — и есть `SALUTE_AUTH_KEY`. Ключ для GigaChat не подходит: у SaluteSpeech свой проект и свой ключ.
2. Scope должен совпадать с договором, иначе OAuth отвечает `401`: `SALUTE_SPEECH_PERS` — физлицо (freemium + оплата по факту), `SALUTE_SPEECH_B2B` — юрлицо, предоплата, `SALUTE_SPEECH_CORP` — юрлицо, постоплата. (`SBER_SPEECH` в документации помечен устаревшим.)
3. Тарифы: синтез тарифицируется по символам, у физлиц есть бесплатная квота, у юрлиц — пакеты; актуальные цифры — в разделе «Тарифы» документации SaluteSpeech на момент включения (в репозитории цены не фиксируем). Параллельных потоков: до 5 у физлиц, до 10 у юрлиц — отсюда `ASTOR_TTS_WEB_CONCURRENCY=2` и один поток в очках.
4. Сертификат НУЦ Минцифры — тот же файл `certs/russian_trusted_root_ca.pem`, что для GigaChat.

Голоса (24 kHz / 8 kHz): `Nec_24000` Наталья (по умолчанию, женский — Clio), `Bys_24000` Борис, `May_24000` Марфа, `Tur_24000` Тарас, `Ost_24000` Александра, `Pon_24000` Сергей, `Kin_24000` Kira (английский). Для очков, где голос Астора мужской, ставить `SALUTE_TTS_VOICE=Bys_24000` (или `Tur_24000`, `Pon_24000`) в `runtime.env` glasses-контейнера. Форматы: `wav16` (веб и iOS — играет везде), `opus` (Ogg Opus — Telegram voice, Android), `pcm16`, `alaw`.

### Переменные

Бэкенд (`.env.production`, passthrough в `docker-compose.yml` / `docker-compose.prod.yml` уже есть для трёх ботов):

```
ASTOR_TTS_PROVIDER=salute                     # yandex — откат
ASTOR_TTS_WEB_ENABLED=true                    # иначе /api/chat/speak отвечает 503
SALUTE_AUTH_KEY=<Authorization key проекта SaluteSpeech>
SALUTE_SCOPE=SALUTE_SPEECH_PERS               # или SALUTE_SPEECH_B2B / SALUTE_SPEECH_CORP
SALUTE_TTS_VOICE=Nec_24000
SALUTE_TTS_FORMAT=wav16                       # opus для Telegram voice (обязателен при ASTOR_TELEGRAM_VOICE_REPLIES=on|auto)
SALUTE_CA_CERT_PATH=/app/certs/russian_trusted_root_ca.pem   # по умолчанию = GIGACHAT_CA_CERT_PATH
# SALUTE_OAUTH_URL=https://ngw.devices.sberbank.ru:9443/api/v2/oauth
# SALUTE_TTS_URL=https://smartspeech.sber.ru/rest/v1/text:synthesize
# SALUTE_TTS_TIMEOUT_MS=10000
# ASTOR_TTS_WEB_CONCURRENCY=2
# ASTOR_TTS_WEB_RATE_PER_MINUTE=60
# Откат на SpeechKit:
# YANDEX_SPEECHKIT_API_KEY=   YANDEX_SPEECHKIT_FOLDER_ID=   YANDEX_SPEECHKIT_TTS_VOICE=alena
```

Очки (`/opt/astor-glasses/private/runtime.env`, root 0600; в `docker/glasses/compose.yaml` раскомментировать том с PEM):

```
ASTOR_GLASSES_TTS_ENABLED=true
ASTOR_GLASSES_TTS_PROVIDER=salute             # yandex — прежние ASTOR_GLASSES_TTS_API_KEY/FOLDER/VOICE
SALUTE_AUTH_KEY=<тот же или отдельный ключ SaluteSpeech>
SALUTE_SCOPE=SALUTE_SPEECH_PERS
SALUTE_TTS_VOICE=Bys_24000
SALUTE_TTS_FORMAT=wav16
SALUTE_CA_CERT_PATH=/certs/russian_trusted_root_ca.pem
```

Фронтенд (build arg образа `c3ag-frontend`): `C3_FRONTEND_CLIO_TTS_ENABLED=true`, `C3_FRONTEND_CLIO_TTS_ENDPOINT=https://api.c3ag.ru/api/chat/speak`.

### Включение, проверка, откат

1. Положить ключ и сертификат на сервер, выставить переменные выше, перезапустить бота (`docker compose up -d aeris-astor-butler-bot`) и/или glasses-контейнер.
2. В логе старта: `TTS provider=salute voice=... format=audio/wav`; предупреждение `TTS provider salute is selected but not configured` означает пустой `SALUTE_AUTH_KEY`, `SALUTE_CA_CERT_PATH ...` — проблему с PEM.
3. Смоук: `curl -s -X POST https://api.c3ag.ru/api/chat/speak -H 'Content-Type: application/json' -d '{"text":"Здравствуйте, это Clio."}' | jq -r .status` → `READY`; для очков — `POST /api/glasses/speech` с мобильным bearer, в ответе `audioMimeType: audio/wav`. Ошибка `401` от OAuth в логе — ключ или scope; `401` от синтеза один раз — норма (токен обновится), подряд — ключ отозван.
4. Откат: `ASTOR_TTS_PROVIDER=yandex` (и `ASTOR_GLASSES_TTS_PROVIDER=yandex`) плюс прежние переменные SpeechKit, перезапуск. `SALUTE_*` можно оставить — без выбранного провайдера они не читаются. Неизвестное имя провайдера роняет старт, а не молча выбирает другой голос.

Что не проверялось на живом ключе (допущения до первого смоука): имена query-параметров `format`/`voice` и значения `Content-Type: application/text` / `application/ssml` взяты из справочника REST API (страница параметров рендерится динамически, в тексте документации не читается); `opus` считаем Ogg Opus; `expires_at` в ответе OAuth считаем миллисекундами epoch (13-значный пример в документации), при отсутствии поля токен живёт 25 минут.

## Astor Concierge

У Concierge те же переключатели: `LLM_PROVIDER=gigachat` (уже был) и `LLM_PROVIDER=cloudru` (`CLOUDRU_API_KEY`, `CLOUDRU_MODEL`), сертификат через `NODE_EXTRA_CA_CERTS`. Подробности — `docs/operations/SBER_AI_ACTIVATION.md` в репозитории Concierge.

## Что остаётся вручную

- Купить/выпустить ключи: Cloud.ru API key или GigaChat Authorization key с нужным scope; для речи — Authorization key проекта SaluteSpeech; пополнить баланс.
- Скачать и проверить сертификаты НУЦ Минцифры, положить в `certs/`.
- Сверить имена моделей с `GET /models` (Cloud.ru) и с кабинетом GigaChat.
- Решить, переиндексировать ли embeddings (см. выше) или оставить `none`.
- Живой смоук на тестовом ключе до прода: JSON-ответы understanding, vision на фото стола, лимиты RPS; для SaluteSpeech — одна фраза через `/api/chat/speak` и `/api/glasses/speech`.
