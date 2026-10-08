# Переезд Astor с Yandex Cloud на Cloud.ru Evolution

Дата: 2026-10-08. Владелец переезда — Михаил; код — Claude (PR #81, #82, #83, #84); инфраструктура, ключи, `apply` и переключение — Codex/Михаил. Повод — инфраструктурный инцидент у Яндекса и конкурс Sber500 × Disrupt. Компрометация ключей не заявлена: старую ВМ не изолировать и не удалять до приёмки.

## 0. Стоп-факторы — проверить до первого платного шага

1. **Деньги.** Баланс проекта `Astor-Sber500-Contest` — 75,06 ₽, грантов 0. ВМ, бакет и модели платные. Пока в биллинге не видно покрытия хотя бы на месяц, `terraform apply` и inference-запросы не выполняются. Михаил подтвердил, что оплатит при необходимости — это снимает блокер только после фактического пополнения.
2. **Ключи, засвеченные в чате**, перевыпустить до постоянного использования.
3. **Один Telegram-токен — один polling.** Два экземпляра бота с одним токеном отбирают друг у друга апдейты. Новый стенд поднимается с `TELEGRAM_*_ENABLED=false` и включается только в момент переключения (раздел 6).

## 1. Что меняется, а что нет

| Слой | Сейчас | На Cloud.ru | Код |
| --- | --- | --- | --- |
| ВМ, Docker Compose | Yandex VM `astor-butler-aeris-mvp` | ВМ из `infra/cloudru` | #82 |
| Текст гостевого бота | `ASTOR_MODEL_PROVIDER=yandex-agent` (персона в AI Studio, prompt id) | `openai-compatible` → Foundation Models + системный промпт из файла | #84 |
| JSON-понимание (intent/slots) | прямой YandexGPT, `jsonObject` | тот же `OpenAiCompatibleModelGateway`, `json-mode` | есть |
| Очки: текст + vision | `YandexGlassesGateway` | `GlassesCompletionsGateway`, `ASTOR_GLASSES_AI_PROVIDER=cloudru` | #81 |
| Очки: STT | локальный faster-whisper | без изменений | — |
| Очки: TTS | SpeechKit (#78, опционально) | **остаётся SpeechKit**, пока у Cloud.ru нет TTS; выключается одной переменной. Голос не отключаем | решение 08.10 |
| Embeddings / RAG | Yandex `text-search-*`, размерность по текущему `ASTOR_SEMANTIC_EMBEDDING_DIMENSION` | BGE-M3 (1024) или Qwen Embedding — **индекс пересоздать**, `ASTOR_SEMANTIC_EMBEDDING_DIMENSION` сменить | задача |
| Архив очков S3 | `storage.yandexcloud.net` | `https://s3.cloud.ru`, ключ `<tenant_id>:<key_id>` | #81 |
| Медиа S3 монолита | MinIO в Compose | без изменений (MinIO остаётся на ВМ) | — |
| Postgres, Mongo, Redis, Kafka | Compose | Compose на новой ВМ | — |
| FSM, Telegram, стаф-портал | — | без изменений | — |

Неизвестные Cloud.ru, которые надо доказать запросом (по 1–2 запроса, лимит 10 ₽): генерация текста выбранной моделью, vision с JPEG data URL, поддержка `chat_template_kwargs.enable_thinking=false`, `response_format=json_object`, embeddings и их размерность. До этого модели — кандидаты, не решения.

## 2. Карта переменных `.env.production`

Файл создаётся руками на новой ВМ от root, `0600`. **Не копировать с Яндекса целиком** — ниже только то, что отличается; остальное (Postgres, Mongo, Redis, Telegram, Saby, стаф-портал) переносится как есть, кроме токенов, которые перевыпускаются.

```bash
# --- AI монолита ---
ASTOR_MODEL_PROVIDER=openai-compatible
OPENAI_COMPATIBLE_BASE_URL=https://foundation-models.api.cloud.ru/v1
OPENAI_COMPATIBLE_API_KEY=<ключ Foundation Models сервисного аккаунта>
OPENAI_COMPATIBLE_MODEL=ai-sage/GigaChat3-10B-A1.8B          # быстрый текст — кандидат
OPENAI_COMPATIBLE_QUALITY_MODEL=<GigaChat 3.5 / DeepSeek-V4-Pro по каталогу>
OPENAI_COMPATIBLE_VISION_MODEL=Qwen/Qwen3.6-35B-A3B
OPENAI_COMPATIBLE_EMBEDDING_MODEL=<BGE-M3 по каталогу>
OPENAI_COMPATIBLE_SYSTEM_PROMPT_FILE=/opt/astor-butler/config/astor-persona.md   # #84
ASTOR_SEMANTIC_EMBEDDING_DIMENSION=<размерность выбранной модели>

# --- Очки (isolated adapter) ---
ASTOR_GLASSES_AI_PROVIDER=cloudru
ASTOR_GLASSES_CLOUDRU_API_KEY=<тот же или отдельный ключ FM>
ASTOR_GLASSES_TEXT_MODEL=ai-sage/GigaChat3-10B-A1.8B
ASTOR_GLASSES_VISION_MODEL=Qwen/Qwen3.6-35B-A3B
ASTOR_GLASSES_S3_ENDPOINT=https://s3.cloud.ru
ASTOR_GLASSES_S3_BUCKET=<glasses_bucket из Terraform>
ASTOR_GLASSES_S3_ACCESS_KEY=<tenant_id>:<key_id>
ASTOR_GLASSES_S3_SECRET_KEY=<secret>
# TTS: оставить ASTOR_GLASSES_TTS_* как есть (SpeechKit) или ASTOR_GLASSES_TTS_ENABLED=false

# --- Убрать ---
# YANDEX_API_KEY, YANDEX_FOLDER_ID, YANDEX_AGENT_*, ASTOR_GLASSES_YANDEX_* — не переносить.
```

Файл персоны `astor-persona.md` — экспорт инструкций агента из AI Studio (Михаил копирует из консоли), лежит рядом с env, `0640 root:deploy`, в git не попадает.

## 3. Порядок

1. **Код.** Слить #81 (провайдер очков), #82 (Terraform), #83 (этот runbook), #84 (системный промпт). CI зелёный — не доказательство работы с Cloud.ru, только компиляции и контрактов.
2. **Снимок текущего.** На Яндекс-ВМ: `nproc`, `free -g`, `df -h`, `docker compose ps`, версии образов — записать в этот документ (раздел 8). Terraform-переменные `flavor_name`/`disk_size_gb` выбираются не меньше этого.
3. **Бэкап.** `pg_dump` Postgres и `mongodump` Mongo в `/opt/astor-butler/backups/<дата>`, скопировать на машину оператора. Проверить восстановление в пустой контейнер локально — факт восстановления, не наличие файла.
4. **Деньги** (раздел 0) → `terraform apply` по `infra/cloudru/README.md`. Записать `public_ip`.
5. **ВМ.** `ssh deploy@<ip>`; проверить `docker compose version`. Создать `/opt/astor-butler/.env.production` (раздел 2) с `TELEGRAM_*_ENABLED=false`. Положить `astor-persona.md`.
6. **Деплой кода.** Воркфлоу деплоя указать на новую ВМ (секреты `*_VM_HOST/USER/SSH_KEY/DEPLOY_PATH` в GitHub Environment `production`), режим `preflight`, затем `deploy` с путём к бэкапу. Либо вручную: `rsync` репозитория в `/opt/astor-butler/releases/<sha>` и `scripts/deploy/aeris-scoped.sh`.
7. **Восстановление данных** в Postgres/Mongo новой ВМ из бэкапа шага 3. Liquibase-миграции прогонит приложение при старте.
8. **Smoke без Telegram** (раздел 4).
9. **Переключение** (раздел 6).

## 4. Smoke-чеклист на новой ВМ

- [ ] `curl -s http://127.0.0.1:8089/actuator/health` → `UP`.
- [ ] Foundation Models: один текстовый запрос через `/api/...` понимания или `scripts/probe_llm_understanding.mjs` с базовым URL Cloud.ru — ответ не fallback.
- [ ] JSON-понимание: сценарий брони через replay (`scripts/replay_fsm_scenario.sh`) даёт те же переходы FSM, что на Яндексе. FSM — источник истины; если модель отвечает иначе, меняется промпт, не FSM.
- [ ] Очки: `GET /api/glasses/capabilities` → `text/vision/storage` ready после первого запроса; один `assist` с фото.
- [ ] S3 Cloud.ru: `PUT`/`GET` журнала смены через `/api/glasses/sessions/{id}/report`.
- [ ] Embeddings: размерность в ответе = `ASTOR_SEMANTIC_EMBEDDING_DIMENSION`; индекс пересоздан.
- [ ] Логи не содержат ключей и текста гостей (`docker compose logs --tail 200 | grep -ci 'sk-\|Api-Key'` → 0).
- [ ] Стаф-портал открывается, JWT принимает.

## 5. Что НЕ делать

- Не запускать два polling-бота с одним токеном.
- Не удалять и не выключать Яндекс-ВМ до приёмки.
- Не копировать `.env.production` вслепую.
- Не объявлять готовность по зелёному CI.
- Не тратить больше оговорённого лимита на тестовые inference-запросы без подтверждения.

## 6. Переключение

1. Окно: вне смены AERIS (до 10:00 или после 23:30 по Екатеринбургу).
2. На Яндекс-ВМ: `docker compose stop aeris-astor-butler-bot c3flex-astor-butler-bot` (polling освобождён).
3. На новой ВМ: `TELEGRAM_*_ENABLED=true`, `docker compose up -d aeris-astor-butler-bot`; `/start` в боте из личного аккаунта; сообщение в системный чат пришло.
4. DNS `c3ag.ru` → новый IP; стаф-портал и сайт Astor открываются по домену; TLS выпущен.
5. Relay очков, системный/аналитический чаты — те же id, проверить одним сообщением.
6. Наблюдение 24 часа. Утренний брифинг 10:05 пришёл — переезд принят.

## 7. Откат

Обратная последовательность раздела 6: стоп бота на Cloud.ru, старт на Яндексе, DNS назад. Данные, записанные за время работы на Cloud.ru, в Яндекс не возвращаются автоматически — поэтому окно наблюдения короткое, а откат принимается только в первые часы.

## 8. Снимок Яндекс-ВМ

_Заполняется на шаге 2._

| | |
| --- | --- |
| vCPU / RAM / диск | |
| Образы (`docker compose images`) | |
| Размер Postgres / Mongo | |
| Дата бэкапа, восстановление проверено | |
