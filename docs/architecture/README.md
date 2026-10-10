# Architecture Docs

Архитектурный пакет: runtime boundaries, data model, local databases and infrastructure decisions.

Use this folder for:

- backend/system architecture;
- database topology;
- local database access;
- Model Gateway / NLU / RAG / VLM runtime boundaries.

Key files:

- `ARCHITECTURE.md`
- `DATABASE_MODEL.md`
- `LOCAL_DATABASES.md`
- `ADR-salute-speaker.md` — ADR + план спайка: Astor Butler на колонках SberBoom через Chat App / SmartApp API webhook
- `MAX_ADAPTER_PLAN.md` — канал MAX (VK) для Butler и Concierge: проверенный Bot API, сопоставление с Telegram, фазы, фаза 1 за флагом `ASTOR_MAX_ENABLED`
- `WEB_CHANNEL_ADAPTER.md` — веб-канал: один чат-виджет сайта для Butler и Concierge, контракт `/api/astor/messages` и `/api/concierge/messages`, quick replies, безопасность, маршрутизация на VM

Do not put scenario walkthroughs here; FSM flow belongs in `docs/fsm/`.
