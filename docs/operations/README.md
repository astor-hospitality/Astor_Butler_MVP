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
- [AERIS_HOSTESS_INTERVIEW_2.md](AERIS_HOSTESS_INTERVIEW_2.md) — опросник № 2 для хостес (вопросы).
- [AERIS_MANAGER_INTERVIEW_3.md](AERIS_MANAGER_INTERVIEW_3.md) — опросник № 3, второй круг с менеджером зала по фичам гостевого пути.
- [AERIS_WAITER_INTERVIEW_4.md](AERIS_WAITER_INTERVIEW_4.md) — опросник № 4 для официанта (вопросы).
- `AERIS_WAITER_INTERVIEW_4_ANSWERS.md` - ответы официанта AERIS 12.10.2026 (опросник № 4: смена, заказ и кухня, поручения и наушник, счёт и чаевые, вход гостя, тест); план фич — `docs/product/WAITER_FEATURES_PLAN.md`, issues #160–#165.
- [AERIS_CHEF_INTERVIEW_5.md](AERIS_CHEF_INTERVIEW_5.md) — опросник № 5 для шеф-повара (вопросы).
- `AERIS_CHEF_INTERVIEW_5_ANSWERS.md` - ответы шеф-повара AERIS 12.10.2026 (опросник № 5: кухня в ланч, предзаказ от бота, меню и аллергены, поручения и кухня, гость и кухня, тест); план фич — `docs/product/KITCHEN_FEATURES_PLAN.md`, issues #173–#175.
- [AERIS_TRAINEE_INTERVIEW_6.md](AERIS_TRAINEE_INTERVIEW_6.md) — опросник № 6 для стажёра-официанта, носителя очков на тесте (вопросы).
- `AERIS_TRAINEE_INTERVIEW_6_ANSWERS.md` - ответы стажёра-официанта AERIS 12.10.2026 (опросник № 6: опыт и роль, очки и наушник, сервис уровня Michelin в ланч, заказ и Presto, гость, отзыв и чаевые, тест); план фич — `docs/product/GLASSES_WEARER_FEATURES_PLAN.md`, issues #184–#187.
- `AERIS_SYSTEM_ANALYSIS_TESTS.md`
- `LOAD_TESTING.md`
- `TEAM_DELIVERY_WORKFLOW.md`
- `NEXT_SESSION_BACKLOG.md`

Analytics results themselves go to `docs/analytics/`.
