# Техаудит: чек-лист артефактов

Статус ставится после аудита: **есть / частично / нет**. «Предварительно» — что видно в репозитории на 14.10, не вывод аудита.

| # | Требование | Артефакт (путь) | Предварительно | Статус |
| --- | --- | --- | --- | --- |
| 1 | README с инструкцией запуска | `README.md` → «Запуск локально» (`scripts/start_local_infra.sh`, `scripts/run_local_app.sh`, `mvn -B test`), «Выкладка» | раздел есть; проверить запуск с нуля по шагам | [заполнить] |
| 2 | Схема архитектуры | `README.md` → «Архитектура в 10 строках»; `docs/architecture/ARCHITECTURE.md` (диаграммы mermaid); Консьерж и агенты — `docs/superpowers/specs/2026-10-14-astor-negotiation-design.md` (ветка `docs/negotiation-spec`) | схемы Butler есть; общей схемы Консьерж + Батлер + GigaChat + Saby одной картинкой нет | [заполнить] |
| 3 | Код инструментирования | `src/main/resources/db/changelog/2026-06-29-model-interaction-audit.sql`, `src/main/java/museon_online/astor_butler/model/ModelInteractionAuditRepository.java`; `saby_call_log` в `2026-10-07-guest-billing.sql`; `telegram_messages` (`TelegramIntakeService`); Prometheus `/actuator/prometheus`; Консьерж — `analytics_events` (SQLite) | таблицы есть; нет токенов в аудите, 1 запись аудита при 140 сообщениях — проверить запись | [заполнить] |
| 4 | Нагрузочный тест ≥ 10 RPS | отчёт `docs/sber500/LOAD_TEST.md`; скрипты `k6/read-api-load.js`, `k6/smoke.js`, `k6/weekend-guest-scenarios.js` | `read-api-load.js` — 10 VU со `sleep(1)`, это ≈ 10 RPS на пике, без запаса; нужен прогон с постоянной скоростью ≥ 10 RPS (`constant-arrival-rate`) и отчёт | [заполнить] |
| 5 | LLM-cost на DAU и на целевое действие | `docs/sber500/LLM_COST.md` | модель-оценка есть; замер — после записи `usage` GigaChat в `metadata` аудита | [заполнить] |
| 6 | История коммитов | `git log --oneline --since=2026-06-01 \| wc -l`; `git log --format='%h %ad %an %s' --date=short` (выгрузка в форму); публичный репозиторий `astor-hospitality/Astor_Butler_MVP` и `Astor_Concierge` | в `docs/sber500-stage1` 224 коммита; Консьерж — [заполнить] | [заполнить] |

## Что сделать до аудита

1. Прогнать k6 на ВМ или стенде с `constant-arrival-rate` ≥ 10 RPS по публичным эндпоинтам (`/api/system/ping`,
   `POST /api/astor/messages`) и записать `docs/sber500/LOAD_TEST.md`: сценарий, RPS, p50/p95/p99, доля ошибок,
   дата, коммит. Секреты и адреса ВМ в отчёт не писать.
2. Добавить в аудит модели токены (`usage`) и проверить, что каждый вызов пишет строку.
3. Одна схема «Консьерж ⇄ Батлер ⇄ GigaChat ⇄ Saby ⇄ персонал» в `docs/architecture/` и ссылка на неё из README.
