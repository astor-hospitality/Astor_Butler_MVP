# Operations Docs

Operations package for deployment, load testing, delivery workflow and next-session handoff.

Use this folder for:

- production deployment plans;
- local/remote operations;
- load testing instructions;
- team delivery workflow;
- next-session backlog.

Key files:

- `PRODUCTION_DEPLOYMENT_PLAN.md`
- `CLOUDRU_DEPLOY_RUNBOOK.md` (current production path: shared Cloud.ru VM, GHCR images)
- `PUBLIC_API_GUARD.md` - internal token `ASTOR_INTERNAL_API_TOKEN` for the non-public Butler API, edge/gateway allow-list, rollout and smoke on the VM.
- `SBER_AI_ACTIVATION.md` - switching models to Sber (Cloud.ru Foundation Models, GigaChat API), keys, certificates, rollback.
- `TELEGRAM_VOICE_REPLIES.md` - Telegram bot answers with a voice note plus a short text summary: flag `ASTOR_TELEGRAM_VOICE_REPLIES`, `/voice on|off`, timeout and fallback, costs.
- `BUSINESS_LUNCH_FIRST_TEST.md` - первый живой тест бизнес-ланча в AERIS: полуавтомат (хостес) и автомат (Saby), состав команды и статистов, pre-flight, таблица результатов, откат флагами.
- `SABY_STAFF_MEMO_DASHA.md` - Памятка для Дарьи (менеджер зала AERIS): что должно существовать в Saby (СБИС Presto) для автомата ланча, как персонал работает в Presto во время теста, таблица людей для справочника Astor. Бот сотрудников Saby не использует.
- `AERIS_MANAGER_INTERVIEW_1.md` - Опросник № 1 для менеджера смены AERIS (Discovery перед первым тестом ланча: процесс, роли, аккаунты команды, Saby, риски).
- `AERIS_MANAGER_INTERVIEW_1_ANSWERS.md` - ответы менеджера смены AERIS 10.10.2026, что меняет план теста, issues #127–#135.
- `AERIS_HOSTESS_INTERVIEW_2_ANSWERS.md` - ответы хостес AERIS 12.10.2026 (опросник № 2: рабочий день, брони, карточка, Saby, зал, вход гостя, тест, отзывы); разбор по этапам гостевого пути — `docs/product/GUEST_JOURNEY_FEATURES_FROM_INTERVIEWS.md`, issues #147–#154.
- `AERIS_SYSTEM_ANALYSIS_TESTS.md`
- `LOAD_TESTING.md`
- `TEAM_DELIVERY_WORKFLOW.md`
- `NEXT_SESSION_BACKLOG.md`

Analytics results themselves go to `docs/analytics/`.
