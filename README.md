# Astor Butler

FSM-first hospitality governance runtime for restaurants, hotels and event venues.

## Release preparation — 6 October 2026

Reviewed baseline: `5a3d3f4`. Business lunch (#33), scoped AERIS deploy (#39)
and Saby environment wiring (#40) are merged. Saby writes (#38) are excluded.
Source-code readiness is not production acceptance.

- Lunch creates a hostess request; AERIS menu, prices and hours need venue approval.
- Saby reading needs live test-point smoke; keep `ASTOR_SABY_ENABLED=false` until verified. No live write or deposit is included.
- Staff/Keycloak is a separate opt-in rollout, not a configured production service.
- Glasses clients are pilot groundwork, not store releases or complete device acceptance.

Release gate: exact-commit green CI, verified DB backup/restore, migration and
rollback compatibility, and recorded deployed revision with smoke results.
Use the [Cloud.ru deploy runbook](docs/operations/CLOUDRU_DEPLOY_RUNBOOK.md):
read-only preflight first, frontend and backend as separate scopes. Image
rollback does not reverse DB migrations. Never restart the whole shared stack
for this release. The Yandex VM path ([scoped deploy](docs/operations/AERIS_SCOPED_DEPLOY.md))
is deprecated and its workflow triggers are disabled.
See [staff rollout](docs/operations/STAFF_PORTAL_RUNBOOK.md) and
[Saby contract](docs/integrations/SABY_PRESTO_BOOKING_API.md).

## Team repositories

Each repository releases independently; secrets and guest databases are not shared.

| Repository | Responsibility |
| --- | --- |
| [Butler MVP](https://github.com/astor-hospitality/Astor_Butler_MVP) | Venue FSM and operations |
| [Concierge](https://github.com/astor-hospitality/Astor_Concierge) | Guest-side HTTP handoff |
| [Butler Legacy](https://github.com/michaelwelly/Astor_Butler_Legacy) | Historical reference |
| [VEDAL](https://github.com/astor-hospitality/MuseonUrania) | Independent customer portal |
| [Altai](https://github.com/astor-hospitality/mts-altai-leaders-trip) | Trip site and registration |
| [Polymnia](https://github.com/astor-hospitality/Museon-Polymnia) | MRI-console research, no clinical deployment |
| [Smart Solution](https://github.com/astor-hospitality/smart-solution-website) | Website brief and delivery |

Astor Butler is not a Telegram bot. Telegram is the first transport adapter. The
product core is a controlled runtime where a finite-state machine, domain
services and auditable storage decide what can happen next.

The initial venue is AERIS gastro bar. The target buyer is a restaurateur or
hospitality operator. The end user is the guest. The operating users are hostess,
staff, manager and administrator roles.

## Executive Summary

Restaurants do not lose guests only because of food or price. They lose guests
when the service context disappears:

- "Can we sit by the window?"
- "We will be late."
- "Please prepare a candle for dessert."
- "I do not eat spicy food."
- "Cancel reservation #12."

A simple chatbot can answer a FAQ. Astor Butler keeps the interaction governed:
state, consent, booking, preference, staff handoff, media delivery, event trail
and human confirmation remain visible and auditable.

## Killer Features

1. **FSM as business authority**
   Every guest interaction is routed through explicit scenarios and states.

2. **AI outside business authority**
   LLMs may classify, summarize or draft text, but they do not confirm bookings,
   payments, bids or staff actions.

3. **Hospitality memory without hidden profiling**
   Preference Map stores only explicit guest-provided preferences after consent.

4. **Draft confirmation boundary**
   Merch, tips, donations and auction bids are drafts until the guest explicitly
   confirms them.

5. **Hostess context pack**
   Booking cards include seating preference, Telegram identifiers, the original
   request and recent guest messages.

6. **Staff/admin/system projections**
   Operational chats receive human-readable cards without becoming guest FSM
   inputs.

7. **Media fidelity**
   Menus, photos, videos and documents are served through a managed media
   catalog and object storage.

8. **Commercially readable documentation**
   The repository contains both engineering contracts and restaurant-facing
   brand/commercial materials.

## Current MVP Scope

- Telegram long-polling adapter.
- Persistent AERIS preview card.
- Consent and contact flow.
- Message gateway and scenario router.
- Redis-backed FSM hot state.
- PostgreSQL durable facts and outbox.
- Kafka/Redpanda event trail.
- MinIO/S3 media storage.
- Local STT boundary for voice/audio.
- LLM-understanding boundary for intent and slot extraction.
- Yandex AI Studio text gateway for cloud Russian-language understanding.
- Semantic RAG runtime and response cache.
- Table booking with hostess confirmation.
- Seating preferences and guest cancellation.
- Menu assets and Quiet Guide.
- Preference Map active-list and soft delete.
- Concierge request lifecycle.
- Merch, Tip, Donation and Art Auction draft flows.
- Safe Play safety boundary.
- Admin/staff/system notification projections.
- Swagger/OpenAPI groups for backend contracts.

## Architecture

```text
Telegram / REST / future web chat
        |
Transport adapters
        |
Message Gateway
        |
Scenario Router
        |
FSM Runtime
        |
Domain Services
        |
PostgreSQL + Redis + Kafka/Redpanda + MinIO/S3
        |
Admin / Staff / System projections
```

Core invariant:

```text
business authority = FSM + domain services
AI adapter          = interpretation and drafting only
```

See:

- [Architecture](docs/architecture/ARCHITECTURE.md)
- [Database model](docs/architecture/DATABASE_MODEL.md)
- [FSM scenarios](docs/fsm/FSM_SCENARIOS.md)
- [FSM viewer](docs/FSM_SCENARIOS_VIEWER.html)
- [API contract](docs/contracts/API_CONTRACT.md)
- [Kafka outbox](docs/contracts/KAFKA_OUTBOX.md)
- [Media pipeline](docs/content/MEDIA_PIPELINE.md)

## Runtime Stack

- Java 25
- Spring Boot 4
- JDBC and Liquibase
- PostgreSQL and pgvector
- Redis
- Kafka / Redpanda
- MinIO / S3-compatible object storage
- MongoDB for document/media metadata
- ScyllaDB/Cassandra-compatible future timeline layer
- Neo4j graph workbench
- Telegram Bot API
- Swagger / OpenAPI
- Docker Compose
- Nginx local API gateway
- Prometheus and Grafana
- Replaceable local/remote LLM gateway: Spring AI/Ollama, raw Ollama fallback,
  Yandex AI Studio provider

## AI Boundary

Astor Butler can use local or cloud models for understanding guest messages, but
business state stays outside the model.

The model layer may:

- classify intent;
- extract slots such as date, time, party size and seating preference;
- summarize context for staff;
- draft a reply inside scenario rules.

The model layer must not:

- create or cancel a booking by itself;
- approve a payment, tip, donation or bid;
- change staff tasks without FSM/domain validation;
- hide uncertainty from the operator.

Runtime provider choice is configuration, not scenario logic:

```text
ASTOR_MODEL_PROVIDER=spring-ai     # default local Spring AI / Ollama path
ASTOR_MODEL_PROVIDER=ollama-raw    # direct Ollama fallback/diagnostics
ASTOR_MODEL_PROVIDER=yandex        # Yandex AI Studio Completion API
```

For Yandex AI Studio:

```text
YANDEX_FOLDER_ID=<folder-id>
YANDEX_API_KEY=<api-key>
YANDEX_MODEL=yandexgpt-5-lite
YANDEX_QUALITY_MODEL=yandexgpt-5.1
```

## API Surface

Implemented or active runtime API groups include:

- Auth and Telegram login verification
- Consent Vault
- User/Profile
- FSM runtime
- Message Gateway
- Booking
- Content / AERIS / Quiet Guide
- Media
- Timeline
- Manager / Notification
- Telegram Stars foundation
- Preference
- Concierge
- Merch
- Tips
- Donations
- Art Auction

The endpoint-level implementation matrix is tracked in
[API_CONTRACT.md](docs/contracts/API_CONTRACT.md).

## App Store / Google Play Readiness Frame

Astor Butler is currently a backend and Telegram-first MVP, not a published
mobile app. The repository is structured so a future mobile or web shell can
meet store review expectations:

- explicit privacy and consent boundary;
- policy page in [docs/policy.html](docs/policy.html);
- no hidden profiling;
- controlled user-generated input handling;
- no autonomous financial commitment by AI;
- human confirmation for bookings, service actions and disputed flows;
- documented data storage and deletion semantics;
- service role separation for guest, staff, admin and system channels.

Future mobile submission work should add:

- production privacy policy URL;
- terms of service;
- support URL and contact;
- account/data deletion workflow;
- screenshots and store copy;
- age/content rating review;
- payment-provider compliance review.

## Local Run

Infrastructure runs through Docker Compose. The Spring Boot application can be
started from IDEA or Maven.

```bash
scripts/start_local_infra.sh
scripts/run_local_app.sh
```

Important local URLs:

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- API Gateway health: `http://localhost:8080/gateway/health`
- Backend health: `http://localhost:8080/actuator/health`
- Redpanda Console: `http://localhost:8081`
- Grafana: `http://localhost:3000`
- Neo4j Browser: `http://localhost:7474`

The `.env` file is local-only and must never be committed.

## Testing

```bash
mvn test
```

The current baseline is Java 25. If local JDK 25 is not installed yet, use the
same Docker image as the migration check:

```bash
docker run --rm \
  -v "$PWD":/workspace \
  -v "$HOME/.m2":/root/.m2 \
  -w /workspace \
  maven:3.9-eclipse-temurin-25 \
  mvn -q test
```

Smoke/load helpers:

```bash
scripts/run_k6_smoke.sh
scripts/run_k6_read_load.sh
```

## Commercial Materials

Restaurant-facing documents are kept separate from technical contracts:

- [Brand guide, RU](docs/commercial/BRAND_GUIDE_RU.md)
- [Commercial offer, RU](docs/commercial/COMMERCIAL_OFFER_RU.md)
- [AERIS service agreement draft, RU](docs/commercial/AERIS_SERVICE_AGREEMENT_DRAFT_RU.md)
- [One-day video shooting brief, RU](docs/commercial/VIDEO_SHOOTING_DAY_TZ_RU.md)
- [Benchmark comparison, RU](docs/commercial/BENCHMARK_COMPARISON_RU.md)
- [VCG / DNS / hosting notes, RU](docs/commercial/INFRA_DNS_NOTES_RU.md)
- [Technical note in LaTeX](docs/research/ASTOR_BUTLER_TECHNICAL_NOTE.tex)

The commercial first step is intentionally narrow:

```text
RU segment: 10 restaurants + 1 hotel
```

City, stadium and large hospitality packages are a later story.

## Benchmark Positioning

Astor Butler should not be compared to a button-based bot only by price.

| Capability | Simple Telegram bot | Astor Butler | Enterprise AI platform |
| --- | --- | --- | --- |
| FAQ answers | yes | yes | yes |
| FSM authority | weak | strong | variable |
| Consent evidence | weak | built in | strong |
| Staff context | weak | built in | configurable |
| AI guardrails | weak | FSM/domain | vendor-specific |
| Booking context | shallow | hostess card | integration-dependent |
| Operational event trail | weak | PostgreSQL/Kafka | strong |
| Cost | low | medium | high |

The deeper comparison is in
[BENCHMARK_COMPARISON_RU.md](docs/commercial/BENCHMARK_COMPARISON_RU.md).

## Documentation Map

- [Docs index](docs/README.md)
- [Architecture package](docs/architecture/README.md)
- [Contracts package](docs/contracts/README.md)
- [Operations package](docs/operations/README.md)
- [Commercial package](docs/commercial/README.md)
- [Frontend handoff](docs/frontend/README.md)
- [Research package](docs/research/README.md)
- [Project memory snapshot](docs/obsidian/README.md)

## Git Hygiene

Do not commit:

- `.env`
- `target/`
- `build/`
- local `.idea/*`
- `.codex*`
- local media originals from Desktop, Downloads or cloud drives

Before pushing, inspect the diff for secrets, build artifacts and unrelated
local files.

## Contact

email: `michael.poedinenko.mxr@gmail.com`
telegram: `@michael_welly`
