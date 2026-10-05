# Claude Project Instructions

Claude works in this repository as a focused frontend/UX assistant.
Codex remains the primary agent for backend, FSM, infrastructure, Telegram logic, database work, and production readiness.

## Default Scope

Claude may work without extra approval in:

- `frontend/**`
- `design-system/**`
- frontend assets and UI copy
- frontend-oriented documentation explicitly assigned by the user

Claude must treat these files as context/source of truth, but must not edit them unless the user explicitly asks:

- `docs/README.md`
- `docs/fsm/FSM_SCENARIOS.md`
- `docs/FSM_SCENARIOS_VIEWER.html`
- `docs/architecture/ARCHITECTURE.md`
- `docs/contracts/KAFKA_TOPICS.md`
- `docs/fsm/TABLE_BOOKING.md`
- `docs/obsidian/**`
- `docker-compose.yml`
- `src/main/**`
- `.env`, `.env.*`
- Liquibase migrations
- backend tests

## Hard Rules

1. Do not delete project documentation.
2. Do not rewrite backend/FSM/infrastructure files without explicit permission.
3. Do not commit or expose secrets, `.env`, build artifacts, IDE files, or local caches.
4. If a task touches backend, databases, Telegram bot behavior, Kafka, Docker, or production readiness, stop and recommend handing it to Codex.
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

## Explicit Astor Glass scope for Roma — 2026-10-04

Mikhail authorized Roma to test and extend the Astor Glass v1 product through feature branches and PRs, and to operate only the approved Astor services through a root-owned restricted wrapper. This explicit assignment extends the default frontend scope for glasses API/tests/scripts, the Astor presentation frontend and associated documentation. It does not authorize direct edits to main, guest FSM changes, destructive database work, arbitrary deployment code or access to VEDAL/C3AG.

- Read `docs/operations/GLASSES_ASSIST_PILOT.md` and the release handoff before changes. Informational assist cannot ACK or mutate staff tasks/guest bookings.
- Secrets stay server-side. No `.env*`, bearer/cloud keys, raw audio/photos/transcripts or production logs in commits or reports.
- No shared-host Docker/root, Docker socket/group, general sudo, arbitrary commands or arbitrary image/ref deployment. Use only explicitly allowed `astor-glasses-admin` operations.
- Staff identity/feed/evidence/ACK/concurrency/idempotency are a separate P1 contract. A single-photo vision answer is not a continuous video stream or a committed task state.
- Every PR must describe the change and tests. Required GitHub reviews/CI remain mandatory; local tests and a pilot deployment do not replace them.
