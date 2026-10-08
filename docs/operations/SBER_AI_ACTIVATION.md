# Sber AI: включение GigaChat и Cloud.ru Foundation Models

Дата: 2026-10-08

Как перевести модели Astor Butler (и Astor Concierge) на инфраструктуру Сбера, не меняя FSM, контракты и поведение по умолчанию. Переключение — одной переменной окружения, откат — той же переменной обратно.

## Что в стеке использует AI сегодня

| Функция | Где | Провайдер сейчас | Вариант Сбера | Переключатель |
| --- | --- | --- | --- | --- |
| Понимание гостя, черновики ответов, Q&A для ops-группы (`ModelGateway.generateText`) | `fsm/understanding`, `fsm/reply`, `service/message` | `ASTOR_MODEL_PROVIDER`: `spring-ai` (Ollama, по умолчанию), `ollama-raw`, `yandex`, `yandex-agent`, `openai-compatible` | `cloudru` (Cloud.ru Foundation Models, OpenAI API) или `gigachat` (GigaChat API напрямую) | `ASTOR_MODEL_PROVIDER` |
| Embeddings для RAG и intent-examples (`generateEmbedding`, pgvector) | `domain/semantic` | `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER`: `none`, `ollama`, `spring-ai`, `model-gateway` (через провайдер выше: Yandex `text-search-doc/latest`) | `model-gateway` + `CLOUDRU_EMBEDDING_MODEL` или `GIGACHAT_EMBEDDING_MODEL` | `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=model-gateway` |
| Vision (`analyzeImage`: фото стола, glasses) | `ModelGateway`, `api/glasses` | Ollama `qwen2.5vl`, `openai-compatible` vision-модель, Yandex AI Studio (`YandexGlassesGateway`) | `CLOUDRU_VISION_MODEL`; GigaChat через `/files` + `attachments`; glasses-pilot: `ASTOR_GLASSES_MODEL_PROVIDER=cloudru` | см. ниже |
| STT голосовых сообщений (Telegram) и записей очков | `speech` (`SpeechToTextService`), `api/glasses` (`GlassesVoice`) | `cloudru`: Cloud.ru `openai/whisper-large-v3` (по умолчанию); `local`: `faster-whisper` subprocess (rollback) | уже Сбер/Cloud.ru, см. «STT через whisper-large-v3» | `ASTOR_STT_PROVIDER`, `ASTOR_GLASSES_STT_PROVIDER` |
| TTS для очков (`GlassesSpeech`) | `api/glasses` | Yandex SpeechKit TTS | SaluteSpeech TTS, не реализовано, см. TODO | — |
| STT/TTS веб-чата CLIO (`frontend/app/api/chat/*`) | frontend | заглушки `yandex-speechkit` (test-double, провайдер не подключён) | SaluteSpeech, не реализовано, см. TODO | — |

Исходники провайдеров: `src/main/java/museon_online/astor_butler/model/CloudRuModelGateway.java`, `GigaChatModelGateway.java`, `GigaChatTrust.java`; STT — `src/main/java/museon_online/astor_butler/speech/CloudRuWhisperSpeechToText.java` (общий HTTP-адаптер), `CloudRuWhisperSpeechToTextService.java` (бот), `ExternalCommandSpeechToTextService.java` (локальный rollback), `api/glasses/GlassesVoice.java` (очки).

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
ASTOR_GLASSES_MODEL_PROVIDER=cloudru
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
ASTOR_GLASSES_CLOUDRU_API_KEY=<ключ>             # тот же, что для ASTOR_GLASSES_MODEL_PROVIDER=cloudru
# CLOUDRU_BASE_URL=https://foundation-models.api.cloud.ru/v1
# ASTOR_GLASSES_STT_CLOUDRU_MODEL=openai/whisper-large-v3
# ASTOR_GLASSES_STT_LANGUAGE=ru
# ASTOR_GLASSES_STT_TIMEOUT_MS=20000             # максимум 30000
```

Какой Dockerfile собирать:

| Образ | Dockerfile | Что внутри | Когда |
| --- | --- | --- | --- |
| production | `docker/glasses/Dockerfile.cloud` | только JRE + `app.jar`, `ASTOR_GLASSES_STT_PROVIDER=cloudru` зашит в ENV; staging-каталогу нужен один `app.jar` | всегда, пока STT в Cloud.ru |
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

## Embeddings и переиндексация

`semantic_embeddings.embedding` — `vector(1536)` (миграция `2026-06-11-semantic-memory-pgvector.sql`), `ASTOR_SEMANTIC_EMBEDDING_DIMENSION=1536`. Yandex `text-search-doc` даёт 256, GigaChat `Embeddings` — 1024, Cloud.ru-модели — свои размерности. Смена модели embeddings требует:

1. выставить `ASTOR_SEMANTIC_EMBEDDING_DIMENSION` и `ASTOR_SEMANTIC_EMBEDDING_MODEL`/`_QUERY_EMBEDDING_MODEL` под новую модель (для `cloudru`/`gigachat` имя берётся из `CLOUDRU_EMBEDDING_MODEL`/`GIGACHAT_EMBEDDING_MODEL`, значение `ASTOR_SEMANTIC_EMBEDDING_MODEL` остаётся меткой в БД);
2. переиндексировать корпус (`ASTOR_SEMANTIC_CHUNKS_INGEST_ON_STARTUP=true`, `ASTOR_INTENT_EXAMPLES_INGEST_ON_STARTUP=true`) — старые векторы другой размерности нельзя сравнивать с новыми; при смене размерности колонки нужна отдельная миграция `vector(N)`.

Пока это не сделано, безопасный вариант — `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=none` (поиск по примерам интентов и RAG отключаются, FSM работает на правилах).

## Переключение и откат

1. Заполнить переменные нужного варианта в `.env.production` (секреты только на сервере, не в репозитории и не в чате).
2. `ASTOR_MODEL_PROVIDER=cloudru` или `gigachat`, перезапустить бота: `docker compose up -d aeris-astor-butler-bot` (и остальные боты по необходимости).
3. Проверить лог старта: не должно быть `Model provider ... is selected but not configured` и `GIGACHAT_CA_CERT_PATH`-ошибок; `ModelGateway text generation provider=cloudru|gigachat` в debug-логе.
4. Смоук как в `AERIS_YANDEX_RAG_AND_LOGGING_SMOKE.md`: голосовое/текстовое бронирование, ответ на свободный вопрос.
5. Откат: вернуть `ASTOR_MODEL_PROVIDER` к прежнему значению (`yandex` в prod, `spring-ai` локально) и перезапустить. Переменные `CLOUDRU_*`/`GIGACHAT_*` можно оставить — без выбранного провайдера они не читаются.

## Речь: TODO для SaluteSpeech

Голосовые сообщения гостей и записи очков распознаются Cloud.ru whisper-large-v3 (см. выше; локальный `faster-whisper` остался только для отката), веб-чат CLIO держит заглушки Yandex SpeechKit, очки используют Yandex SpeechKit TTS (`GlassesSpeech`). Перевод на SaluteSpeech (Сбер) не реализован: нужен отдельный адаптер с тем же OAuth-потоком через `ngw.devices.sberbank.ru` (scope `SALUTE_SPEECH_PERS`/`_B2B`/`_CORP`), эндпоинты `smartspeech.sber.ru/rest/v1/speech:recognize` и `.../text:synthesize`, тот же сертификат НУЦ. Объём работы (только TTS и веб-чат, STT уже на Cloud.ru): `GlassesSpeech` (endpoint + заголовок `Authorization: Bearer` вместо `Api-Key`, формат `application/x-www-form-urlencoded` → `audio/x-pcm`/`audio/ogg`), `frontend/app/api/chat/{transcribe,speak}` (сейчас test-double), `OPENAI`/Yandex там нет.

## Astor Concierge

У Concierge те же переключатели: `LLM_PROVIDER=gigachat` (уже был) и `LLM_PROVIDER=cloudru` (`CLOUDRU_API_KEY`, `CLOUDRU_MODEL`), сертификат через `NODE_EXTRA_CA_CERTS`. Подробности — `docs/operations/SBER_AI_ACTIVATION.md` в репозитории Concierge.

## Что остаётся вручную

- Купить/выпустить ключи: Cloud.ru API key или GigaChat Authorization key с нужным scope; пополнить баланс.
- Скачать и проверить сертификаты НУЦ Минцифры, положить в `certs/`.
- Сверить имена моделей с `GET /models` (Cloud.ru) и с кабинетом GigaChat.
- Решить, переиндексировать ли embeddings (см. выше) или оставить `none`.
- Живой смоук на тестовом ключе до прода: JSON-ответы understanding, vision на фото стола, лимиты RPS.
