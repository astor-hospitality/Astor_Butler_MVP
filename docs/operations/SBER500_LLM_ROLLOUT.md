# Агент Батлера на API Sber500 × Disrupt: включение, проверка, откат

Дата: 2026-10-15, обновлено после ревью Codex. Выкладку кода (образ) делает Codex своей процедурой; этот документ — только
конфигурация агента на уже выложенном образе, проверки и откат. Результаты проверки API, модель, нагрузка и бюджет —
`SBER500_LLM_ACCEPTANCE.md`. Порядок для Консьержа — его `docs/operations/SBER500_LLM_ROLLOUT.md`.

## Что есть на ВМ (проверено Codex 15.10)

- Прод Butler — `8a2f349` (main с PR #211), агент на прямом GigaChat-2-Pro; ключа нового API в runtime нет.
- `/opt/astor-butler/current` — обычная директория, не симлинк релиза; `releases/` и `cloudru-deploy.sh rollback` здесь
  не применяются. Локальный `docker-compose.cloudru-host.yml` в ней — часть рабочей конфигурации: не перезаписывать и не удалять.
- `ASTOR_AGENT_ALLOWLIST` пуст (собственный бот AERIS без агента), у Консьержа `AGENT_ALLOWLIST=*`.

## Что меняется и что нет

| Меняется | Не меняется |
| --- | --- |
| Только модель агента переговоров (`AgentLoop`): `ASTOR_AGENT_PROVIDER=openai-compatible` | `ASTOR_MODEL_PROVIDER` (понимание FSM, ответы, саммари), embeddings и индексы, STT, TTS, очки |
| Только сервис `aeris-astor-butler-bot` | `api-gateway`, PostgreSQL, Redis, Kafka, фронтенд, C3FLEX, Smart Solution |
| Дневной лимит расхода агента (`agent_budget_days`, миграция Liquibase при старте) | Данные гостей и броней |

Пока `ASTOR_AGENT_PROVIDER` пуст, новый образ ведёт агента через GigaChat, как сейчас. Ключ API вводится человеком скрытым
вводом прямо в env-файл; в чат, логи и документы не попадает.

## 1. Перед началом: состояние ВМ

Другие участники могут выкладывать в это же время — каждую команду выполнять непосредственно перед своим шагом.

```bash
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}' | grep -E 'aeris_astor_butler_bot|astor-api-gateway|astor_postgres_test'
```

Как compose запустил бот — каталог, файлы compose и env-файл (метки контейнера, без значений переменных):

```bash
docker inspect --format '{{json .Config.Labels}}' aeris_astor_butler_bot | tr ',' '\n' | grep -E 'com.docker.compose.(project|service|project.working_dir|project.config_files|project.environment_file)"'
```

Дальше `<DIR>`, `<FILES>` (каждый через `-f`), `<ENV>` и `<PROJECT>` — значения из этих меток. Настройки моделей без секретов:

```bash
sudo grep -E '^(ASTOR_MODEL_PROVIDER|ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER|ASTOR_AGENT_ENABLED|ASTOR_AGENT_ALLOWLIST|ASTOR_AGENT_MODEL|ASTOR_AGENT_PROVIDER|ASTOR_AGENT_DAILY_BUDGET_RUB)=' <ENV>
```

Если идёт чужая выкладка или образ не тот, что выложил Codex, — остановиться.

## 2. Бэкап

```bash
docker exec astor_postgres_test sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > /opt/astor-butler/backups/astor-$(date +%F-%H%M).dump
```

```bash
sudo cp -p <ENV> /opt/astor-butler/backups/env.before-agent-$(date +%F-%H%M)
```

```bash
cp -p <DIR>/docker-compose.cloudru-host.yml /opt/astor-butler/backups/docker-compose.cloudru-host.$(date +%F-%H%M).yml
```

## 3. Образ с этим PR

Выкладывает Codex. После выкладки — здоровье и отсутствие ошибок миграции (Liquibase создаёт `agent_budget_days` при старте):

```bash
docker exec aeris_astor_butler_bot curl -fsS --max-time 10 http://127.0.0.1:8089/actuator/health
```

```bash
docker logs --since 10m aeris_astor_butler_bot 2>&1 | grep -E 'agent-budget|agent_budget_days|ERROR' | tail -20
```

## 4. Включение агента на новом API (только `aeris-astor-butler-bot`)

Убрать прежние значения этих ключей, не трогая остальные строки и права файла:

```bash
sudo sh -c 'f=<ENV>; grep -vE "^(ASTOR_AGENT_PROVIDER|ASTOR_AGENT_MODEL|ASTOR_AGENT_DAILY_BUDGET_RUB|ASTOR_AGENT_CALL_RESERVE_RUB|OPENAI_COMPATIBLE_BASE_URL|OPENAI_COMPATIBLE_API_KEY|OPENAI_COMPATIBLE_PARALLEL_TOOL_CALLS)=" "$f" > "$f.new" && cat "$f.new" > "$f" && rm "$f.new"'
```

Добавить новые (ключ — скрытым вводом, в историю shell не попадает):

```bash
read -r -s -p 'OPENAI_COMPATIBLE_API_KEY: ' k && echo && printf '%s\n' 'ASTOR_AGENT_PROVIDER=openai-compatible' 'ASTOR_AGENT_MODEL=gigachat-3-pro' 'ASTOR_AGENT_DAILY_BUDGET_RUB=1000' 'ASTOR_AGENT_CALL_RESERVE_RUB=1' 'OPENAI_COMPATIBLE_BASE_URL=https://shared1.multitool.works:4000/v1' "OPENAI_COMPATIBLE_API_KEY=$k" 'OPENAI_COMPATIBLE_PARALLEL_TOOL_CALLS=false' | sudo tee -a <ENV> > /dev/null; unset k
```

Пересоздать только бот той же командой compose, что из меток (все файлы, включая `docker-compose.cloudru-host.yml`):

```bash
cd <DIR> && docker compose -p <PROJECT> --env-file <ENV> <FILES> up -d --no-deps --no-build aeris-astor-butler-bot
```

## 5. Проверка

```bash
docker exec aeris_astor_butler_bot sh -c 'echo "agent_provider=$ASTOR_AGENT_PROVIDER agent_model=$ASTOR_AGENT_MODEL budget=$ASTOR_AGENT_DAILY_BUDGET_RUB model_provider=$ASTOR_MODEL_PROVIDER"; [ -n "$OPENAI_COMPATIBLE_API_KEY" ] && echo key=set || echo key=MISSING'
```

```bash
docker logs --since 15m aeris_astor_butler_bot 2>&1 | grep -E 'Agent daily model budget|Agent turn falls back|not configured|openai-compatible chat failed' | tail -20
```

```bash
docker exec aeris_astor_butler_bot curl -fsS http://127.0.0.1:8089/actuator/prometheus | grep -E '^astor_agent_(model_calls|turns|tool_calls|model_tokens|budget)'
```

Ожидается: `agent_provider=openai-compatible`, `model_provider=gigachat`, `key=set`; при старте
`Agent daily model budget: 1000 RUB per venue day, 1 RUB reserved per call`; нет `not configured`.

## 6. Проверка переговоров в чате Михаила

1. Через Консьержа в чате Михаила: «Хочу пообедать в AERIS в 13:00 вдвоём» → строка `AERIS · Батлер: …` с предложением;
   «Да, оформляйте» → заявка у хостес (не подтверждённая бронь). Тестовую заявку отменить через хостес.
2. `astor_agent_turns_total{outcome="answered"}` и `astor_agent_budget_spent_rub` растут, в call log — строки `llm` с
   моделью `gigachat-3-pro`.
3. Перезапустить контейнер бота и убедиться, что `astor_agent_budget_spent_rub` не обнулился.

Это проверка технической части. Новая интеграция открывается остальным гостям только после шагов 5–6. QR-путь AERIS
(`ASTOR_AGENT_ALLOWLIST`) не переключается без отдельного согласования.

## 7. Откат конфигурации (минуты)

```bash
sudo cp -p "$(ls -1t /opt/astor-butler/backups/env.before-agent-* | head -1)" <ENV>
```

Затем команда пересоздания бота из шага 4 и проверка шага 5: ожидается `agent_provider=` (пусто), `agent_model=GigaChat-2-Pro`.

## 8. Откат образа

Делает Codex своей процедурой выкладки: предыдущий образ — `8a2f349`. Таблица `agent_budget_days` старому образу не мешает;
восстанавливать БД не нужно.

## 9. Когда откатывать

- `astor_agent_turns_total{outcome="fallback"}` больше 10% ходов за 30 минут;
- `openai-compatible chat failed: HTTP 401/402/403` (ключ, бюджет) или серия `HTTP 429`;
- ответ обещает подтверждённую бронь или заявка создаётся без «да» гостя — сразу шаг 7;
- `Agent daily model budget is spent` при малом числе гостей — признак зацикливания.

## 10. Остаток бюджета ключа

Ключ общий у Батлера и Консьержа; лимитов на самом ключе нет. Ключ передаётся curl через stdin, наружу — только два числа:

```bash
docker exec aeris_astor_butler_bot sh -c 'printf "Authorization: Bearer %s\n" "$OPENAI_COMPATIBLE_API_KEY" | curl -fsS -H @- "${OPENAI_COMPATIBLE_BASE_URL%/v1}/key/info"' | grep -oE '"(spend|max_budget)":[0-9.]+'
```

`max_budget` — номинальный бюджет ключа, не остаток; остаток = `max_budget − spend` на момент запроса, если ключом не
пользуется никто другой. Единица в ответе не указана (см. `SBER500_LLM_ACCEPTANCE.md`, §1).
