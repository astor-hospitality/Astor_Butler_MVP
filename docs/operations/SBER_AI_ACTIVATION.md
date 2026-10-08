# Sber AI: включение GigaChat и Cloud.ru Foundation Models

Дата: 2026-10-08

Как перевести модели Astor Butler (и Astor Concierge) на инфраструктуру Сбера, не меняя FSM, контракты и поведение по умолчанию. Переключение — одной переменной окружения, откат — той же переменной обратно.

## Что в стеке использует AI сегодня

| Функция | Где | Провайдер сейчас | Вариант Сбера | Переключатель |
| --- | --- | --- | --- | --- |
| Понимание гостя, черновики ответов, Q&A для ops-группы (`ModelGateway.generateText`) | `fsm/understanding`, `fsm/reply`, `service/message` | `ASTOR_MODEL_PROVIDER`: `spring-ai` (Ollama, по умолчанию), `ollama-raw`, `yandex`, `yandex-agent`, `openai-compatible` | `cloudru` (Cloud.ru Foundation Models, OpenAI API) или `gigachat` (GigaChat API напрямую) | `ASTOR_MODEL_PROVIDER` |
| Embeddings для RAG и intent-examples (`generateEmbedding`, pgvector) | `domain/semantic` | `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER`: `none`, `ollama`, `spring-ai`, `model-gateway` (через провайдер выше: Yandex `text-search-doc/latest`) | `model-gateway` + `CLOUDRU_EMBEDDING_MODEL` или `GIGACHAT_EMBEDDING_MODEL` | `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=model-gateway` |
| Vision (`analyzeImage`: фото стола, glasses) | `ModelGateway`, `api/glasses` | Ollama `qwen2.5vl`, `openai-compatible` vision-модель, Yandex AI Studio (`YandexGlassesGateway`) | `CLOUDRU_VISION_MODEL`; GigaChat через `/files` + `attachments`; glasses-pilot: `ASTOR_GLASSES_MODEL_PROVIDER=cloudru` | см. ниже |
| STT голосовых сообщений | `speech-to-text` (`ASTOR_STT_COMMAND`) | локальный `faster-whisper` (без облачного вендора) | не требуется; альтернатива — SaluteSpeech, см. TODO | — |
| TTS для очков (`GlassesSpeech`) | `api/glasses` | Yandex SpeechKit TTS | SaluteSpeech TTS, не реализовано, см. TODO | — |
| STT/TTS веб-чата CLIO (`frontend/app/api/chat/*`) | frontend | заглушки `yandex-speechkit` (test-double, провайдер не подключён) | SaluteSpeech, не реализовано, см. TODO | — |

Исходники провайдеров: `src/main/java/museon_online/astor_butler/model/CloudRuModelGateway.java`, `GigaChatModelGateway.java`, `GigaChatTrust.java`.

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

Голосовые сообщения гостей распознаются локальным `faster-whisper` (без вендора), веб-чат CLIO держит заглушки Yandex SpeechKit, очки используют Yandex SpeechKit TTS (`GlassesSpeech`). Перевод на SaluteSpeech (Сбер) не реализован: нужен отдельный адаптер с тем же OAuth-потоком через `ngw.devices.sberbank.ru` (scope `SALUTE_SPEECH_PERS`/`_B2B`/`_CORP`), эндпоинты `smartspeech.sber.ru/rest/v1/speech:recognize` и `.../text:synthesize`, тот же сертификат НУЦ. Объём работы: `GlassesSpeech` (endpoint + заголовок `Authorization: Bearer` вместо `Api-Key`, формат `application/x-www-form-urlencoded` → `audio/x-pcm`/`audio/ogg`), `frontend/app/api/chat/{transcribe,speak}` (сейчас test-double), `OPENAI`/Yandex там нет.

## Astor Concierge

У Concierge те же переключатели: `LLM_PROVIDER=gigachat` (уже был) и `LLM_PROVIDER=cloudru` (`CLOUDRU_API_KEY`, `CLOUDRU_MODEL`), сертификат через `NODE_EXTRA_CA_CERTS`. Подробности — `docs/operations/SBER_AI_ACTIVATION.md` в репозитории Concierge.

## Что остаётся вручную

- Купить/выпустить ключи: Cloud.ru API key или GigaChat Authorization key с нужным scope; пополнить баланс.
- Скачать и проверить сертификаты НУЦ Минцифры, положить в `certs/`.
- Сверить имена моделей с `GET /models` (Cloud.ru) и с кабинетом GigaChat.
- Решить, переиндексировать ли embeddings (см. выше) или оставить `none`.
- Живой смоук на тестовом ключе до прода: JSON-ответы understanding, vision на фото стола, лимиты RPS.
