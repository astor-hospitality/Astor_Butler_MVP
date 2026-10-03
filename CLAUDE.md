# Claude Project Instructions

Claude may assist Egor (`BryxOG`) with backend implementation as well as frontend/UX work. This grants repository editing scope, not production-server or secret access.

## Default Scope

Claude may work without extra approval in:

- `frontend/**`
- `design-system/**`
- frontend assets and UI copy
- frontend-oriented documentation explicitly assigned by the user

When working with Egor on an assigned backend task, Claude may also edit:

- `src/main/**`
- `src/test/**`
- Liquibase migrations
- `docker-compose*.yml`

Backend changes must be made on a separate branch and submitted through a pull request. Do not push directly to `main` or deploy production as a side effect of editing code.

Claude must treat these files as context/source of truth, but must not edit them unless the user explicitly asks:

- `docs/README.md`
- `docs/fsm/FSM_SCENARIOS.md`
- `docs/FSM_SCENARIOS_VIEWER.html`
- `docs/architecture/ARCHITECTURE.md`
- `docs/contracts/KAFKA_TOPICS.md`
- `docs/fsm/TABLE_BOOKING.md`
- `docs/obsidian/**`
- `.env`, `.env.*`
- backend, database, Telegram and Docker files outside the explicit Egor-assisted scope above

## Hard Rules

1. Do not delete project documentation.
2. Do not rewrite backend/FSM/infrastructure files unless the work is explicitly assigned with Egor and falls within the scope above, or the user separately authorizes it.
3. Do not commit or expose secrets, `.env`, build artifacts, IDE files, or local caches.
4. For backend, database, Telegram, Kafka and Docker tasks outside the explicit Egor-assisted scope, stop and recommend handing the work to Codex. Production operations always require separate authorization and access.
5. Before editing, state which files will be changed and why.
6. After work, list changed files and call out any forbidden-scope file that was touched.

## Project Snapshot

Astor Butler MVP is a Java 21 + Spring Boot monolith for Telegram/FSM hospitality scenarios.
FSM is the source of truth. Telegram, website, and future web chat are transport/UI layers.

Documentation priority:

1. `docs/FSM_SCENARIOS_VIEWER.html` - visual source of truth.
2. `docs/fsm/FSM_SCENARIOS.md` - text companion.
3. `docs/architecture/ARCHITECTURE.md` - runtime architecture.
4. `docs/obsidian/**` - repo-owned project memory.
5. `docs/archive/**` - historical context only.

Current priority:

- backend/FSM stability comes first;
- AERIS Telegram bot and site/C3FLEX bot must run on one shared infrastructure, but through different scenarios;
- Claude should focus on frontend and wait for frontend tasks from the user/Izi.

## Frontend/Design Guidance

Use `design-system/c3flex/MASTER.md` as the C3FLEX visual source of truth.
Use premium hospitality language for Astor Butler:

- calm;
- elegant;
- helpful;
- no pressure;
- clear action;
- no noisy marketing tone.

For C3FLEX:

- strong portfolio presentation;
- clear conversion path;
- luxury/premium visual identity;
- fast, responsive UI.
