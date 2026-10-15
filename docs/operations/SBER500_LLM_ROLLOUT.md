# Агент Батлера на API Sber500 × Disrupt: выкладка и откат

Дата: 2026-10-15. Применяется после merge PR «OpenAI-compatible tool calling» и зелёного CI. Результаты проверки API, выбор
модели, нагрузка и стоимость — `SBER500_LLM_ACCEPTANCE.md`. Порядок для Консьержа — его `docs/operations/SBER500_LLM_ROLLOUT.md`.

## Что меняется и что нет

| Меняется | Не меняется |
| --- | --- |
| Только модель агента переговоров (`AgentLoop`): `ASTOR_AGENT_PROVIDER=openai-compatible` | `ASTOR_MODEL_PROVIDER` (понимание FSM, ответы, саммари), embeddings и индексы (`ASTOR_SEMANTIC_*`), STT, TTS, очки |
| Только сервис `aeris-astor-butler-bot` | `api-gateway`, PostgreSQL, Redis, Kafka, фронтенд, C3FLEX, Smart Solution |
| Только чаты из `ASTOR_AGENT_ALLOWLIST` (сначала — чат Михаила) | QR-путь AERIS и все остальные гости: прежние сценарии FSM |

Код по умолчанию выключен: пока `ASTOR_AGENT_PROVIDER` пуст, новый образ ведёт себя как прежний (агент на GigaChat).
Миграций БД в этом PR нет, поэтому откат образа не требует восстановления базы.

Ключ API в чат, PR, логи и документы не попадает. Вводит его человек скрытым вводом прямо в `.env.production`.

## 1. Перед началом: состояние ВМ

Другие участники могут выкладывать в это же время. Каждую команду выполнять заново непосредственно перед своим шагом.

```bash
gh run list --repo astor-hospitality/Astor_Butler_MVP --workflow deploy-cloudru-vm.yml --limit 5
```

```bash
ssh ubuntu@176.123.165.162
```

```bash
cd /opt/astor-butler && readlink current previous && ls -1t releases | head -3
```

```bash
pgrep -af 'cloudru-deploy.sh|docker compose' || echo "нет идущей выкладки"
```

```bash
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' | grep -E 'aeris_astor_butler_bot|astor-api-gateway|astor_postgres_test'
```

Текущие настройки моделей без значений секретов:

```bash
sudo grep -E '^(ASTOR_MODEL_PROVIDER|ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER|ASTOR_AGENT_ENABLED|ASTOR_AGENT_ALLOWLIST|ASTOR_AGENT_MODEL|ASTOR_AGENT_PROVIDER|ASTOR_CALL_LOG_ENABLED)=' /opt/astor-butler/.env.production
```

```bash
sudo grep -cE '^OPENAI_COMPATIBLE_API_KEY=.+' /opt/astor-butler/.env.production
```

Ожидается `ASTOR_MODEL_PROVIDER=gigachat`; если `ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER` пуст или `model-gateway`, `ASTOR_MODEL_PROVIDER` в
этой выкладке тем более не трогать: embeddings уехали бы вместе с ним. Если идёт чужая выкладка или `current` не тот,
что ожидался, — остановиться и выяснить.

## 2. Бэкап данных и конфигурации

```bash
cd /opt/astor-butler/current
```

```bash
docker exec astor_postgres_test sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > ../backups/astor-$(date +%F-%H%M).dump
```

```bash
ls -lt ../backups | head -3
```

```bash
sudo cp -p /opt/astor-butler/.env.production /opt/astor-butler/backups/env.production.$(date +%F-%H%M)
```

Дамп должен быть непустым; копия `.env.production` сохраняет права `root:docker 0640` и содержит секреты — не пересылать.

## 3. Выкладка кода (новый образ, поведение прежнее)

Actions → `Deploy to Cloud.ru VM`: `scope=backend`, `mode=preflight`; затем `mode=deploy`, `confirm=DEPLOY_BACKEND`,
`backup_path=/opt/astor-butler/backups/astor-<дата>.dump` из шага 2 (моложе 24 часов). Одобряет владелец в environment
`production`. То же из терминала:

```bash
gh workflow run deploy-cloudru-vm.yml --repo astor-hospitality/Astor_Butler_MVP --ref main -f scope=backend -f mode=preflight
```

```bash
gh workflow run deploy-cloudru-vm.yml --repo astor-hospitality/Astor_Butler_MVP --ref main -f scope=backend -f mode=deploy -f confirm=DEPLOY_BACKEND -f backup_path=/opt/astor-butler/backups/astor-<дата>.dump
```

Проверка версии и здоровья:

```bash
readlink /opt/astor-butler/current
```

```bash
docker ps --filter name=aeris_astor_butler_bot --format '{{.Image}} {{.Status}}'
```

```bash
docker exec aeris_astor_butler_bot curl -fsS --max-time 10 http://127.0.0.1:8089/actuator/health
```

Ожидается: `releases/<sha merge-коммита>`, образ `sha-<тот же sha>`, `healthy`, `{"status":"UP"...}`. Агент ещё на GigaChat.

## 4. Включение агента на новом API (только `aeris-astor-butler-bot`)

Бэкап конфигурации перед правкой:

```bash
sudo cp -p /opt/astor-butler/.env.production /opt/astor-butler/backups/env.production.before-agent-$(date +%F-%H%M)
```

Убрать прежние значения этих ключей (если были), не трогая остальные строки и права файла:

```bash
sudo sh -c 'f=/opt/astor-butler/.env.production; grep -vE "^(ASTOR_AGENT_PROVIDER|ASTOR_AGENT_MODEL|ASTOR_AGENT_DAILY_BUDGET_RUB|OPENAI_COMPATIBLE_BASE_URL|OPENAI_COMPATIBLE_API_KEY|OPENAI_COMPATIBLE_PARALLEL_TOOL_CALLS)=" "$f" > "$f.new" && cat "$f.new" > "$f" && rm "$f.new"'
```

Добавить новые (ключ — скрытым вводом, в историю shell не попадает):

```bash
read -r -s -p 'OPENAI_COMPATIBLE_API_KEY: ' k && echo && printf '%s\n' 'ASTOR_AGENT_PROVIDER=openai-compatible' 'ASTOR_AGENT_MODEL=gigachat-3-pro' 'ASTOR_AGENT_DAILY_BUDGET_RUB=1000' 'OPENAI_COMPATIBLE_BASE_URL=https://shared1.multitool.works:4000/v1' "OPENAI_COMPATIBLE_API_KEY=$k" 'OPENAI_COMPATIBLE_PARALLEL_TOOL_CALLS=false' | sudo tee -a /opt/astor-butler/.env.production > /dev/null; unset k
```

`ASTOR_AGENT_MODEL=gigachat-3-pro` — выбор Михаила 15.10 (`SBER500_LLM_ACCEPTANCE.md`, §2). `ASTOR_AGENT_DAILY_BUDGET_RUB=1000` —
лимит (решение Михаила): при 1000 ₽ за день заведения агент перестаёт вызывать модель до следующего дня, гости получают сценарии.
`ASTOR_AGENT_ENABLED=true` и `ASTOR_AGENT_ALLOWLIST=<chat id Михаила>` должны уже стоять; других чатов в списке на этом шаге нет.

Пересоздать только бот (та же команда compose, что у `cloudru-deploy.sh`):

```bash
cd /opt/astor-butler/current && docker compose --project-directory /opt/astor-butler/current --env-file /opt/astor-butler/.env.production --env-file /opt/astor-butler/current/images.env -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudru.yml --profile telegram up -d --no-deps --no-build --pull never --wait --wait-timeout 240 aeris-astor-butler-bot
```

## 5. Проверка: версия, здоровье, runtime-настройки, логи, метрики

```bash
docker ps --filter name=aeris_astor_butler_bot --format '{{.Image}} {{.Status}}'
```

```bash
docker exec aeris_astor_butler_bot curl -fsS --max-time 10 http://127.0.0.1:8089/actuator/health
```

Настройки агента внутри контейнера — ключ только как «задан / нет»:

```bash
docker exec aeris_astor_butler_bot sh -c 'echo "agent_provider=$ASTOR_AGENT_PROVIDER agent_model=$ASTOR_AGENT_MODEL enabled=$ASTOR_AGENT_ENABLED model_provider=$ASTOR_MODEL_PROVIDER"; [ -n "$OPENAI_COMPATIBLE_API_KEY" ] && echo key=set || echo key=MISSING'
```

```bash
docker logs --since 15m aeris_astor_butler_bot 2>&1 | grep -E 'Agent turn falls back|not configured|openai-compatible chat failed' | tail -20
```

```bash
docker exec aeris_astor_butler_bot curl -fsS http://127.0.0.1:8089/actuator/prometheus | grep -E '^astor_agent_(model_calls|turns|tool_calls|model_tokens|budget)'
```

Ожидается: `agent_provider=openai-compatible`, `model_provider=gigachat`, `key=set`; в логах нет `not configured`, при старте —
`Agent daily model budget: 1000.0 RUB per venue day`; `astor_agent_budget_limit_rub 1000`.

## 6. Проверка переговоров в чате Михаила

1. Михаил пишет в бот AERIS из allowlist-чата: «Столик на двоих сегодня в 19:00». Ожидается ответ агента, на табло —
   строки `TOOL_CALL`/`TOOL_RESULT`, у хостес — карточка заявки (не бронь; подтверждает хостес).
2. Через Консьержа (после его шага 4): «Хочу пообедать в AERIS в 13:00 вдвоём» → в чате `AERIS · Батлер: …` и ответ Консьержа.
3. Счётчики: `astor_agent_turns_total{outcome="answered"}` растёт, `astor_agent_model_calls_seconds_count{model="gigachat-3-pro"}` растёт;
   в call log (`CALL_LOG.md`) строки `llm` с моделью `gigachat-3-pro` и токенами.
4. Тестовые заявки отменить через хостес.

Зелёный CI и эти шаги проверяют техническую часть, а не готовность гостевого сценария. После проверки Михаил открывает агента
гостям Консьержа (у Консьержа `AGENT_ALLOWLIST=*`, его runbook §6а): Батлер ведёт переговоры с любым гостем Консьержа при
`ASTOR_AGENT_ENABLED=true`, расход ограничен лимитом. `ASTOR_AGENT_ALLOWLIST` — только собственный бот AERIS, там остаётся чат Михаила. QR-путь AERIS на агента не переключать без отдельного согласования.

## 7. Откат конфигурации (минуты, без смены образа)

```bash
ls -1t /opt/astor-butler/backups/env.production.before-agent-* | head -1
```

```bash
sudo cp -p "$(ls -1t /opt/astor-butler/backups/env.production.before-agent-* | head -1)" /opt/astor-butler/.env.production
```

Затем та же команда пересоздания бота из шага 4 и проверки шага 5: ожидается `agent_provider=` (пусто),
`agent_model=GigaChat-2-Pro`.

## 8. Откат образа

```bash
bash /opt/astor-butler/current/scripts/deploy/cloudru-deploy.sh /opt/astor-butler rollback <sha предыдущего релиза> backend
```

Скрипт меняет `current` и `previous` местами и поднимает образы предыдущего релиза для `aeris-astor-butler-bot` и
`api-gateway`. Старый образ не знает `ASTOR_AGENT_PROVIDER` и игнорирует его; агент вернётся на GigaChat, если
`ASTOR_AGENT_MODEL` тоже возвращён (шаг 7). Восстановление БД из дампа — только если откатывают и другие изменения с миграциями.

## 9. Когда откатывать

- `astor_agent_turns_total{outcome="fallback"}` больше 10% ходов за 30 минут или больше 3 подряд в чате Михаила;
- в логах `openai-compatible chat failed: HTTP 401/402/403` (ключ, бюджет) или серия `HTTP 429`;
- ответ агента обещает бронь без подтверждения хостес или нарушает сценарий — сразу шаг 7 и разбор по `model_interaction_audit`;
- `Agent daily model budget is spent` при малом числе гостей — признак зацикливания, не нагрузки: шаг 7 и разбор.

## 10. Остаток бюджета ключа (раз в день)

Ключ общий у Батлера и Консьержа; лимитов на самом ключе нет. Ключ передаётся curl через stdin, наружу выходят только два числа:

```bash
docker exec aeris_astor_butler_bot sh -c 'printf "Authorization: Bearer %s\n" "$OPENAI_COMPATIBLE_API_KEY" | curl -fsS -H @- "${OPENAI_COMPATIBLE_BASE_URL%/v1}/key/info"' | grep -oE '"(spend|max_budget)":[0-9.]+'
```

Потолок: прирост `spend` за сутки не больше 2000 ₽ (два дневных лимита по 1000 ₽); при нём ключа хватит не меньше чем на 37 дней. Больше — смотреть
`astor_agent_budget_spent_rub` и логи `Agent daily model budget`.
