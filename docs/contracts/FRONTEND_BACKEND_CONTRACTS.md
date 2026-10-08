# Frontend Backend Contracts

Дата: 2026-06-23

Назначение: зафиксировать минимальные контракты между C3FLEX/Astor Butler frontend и backend перед реализацией production frontend. Эти контракты нужны Claude для UI-планирования и Codex для последующего Swagger/DTO implementation.

Статус: `contract-first v0.3`.

## 1. Общие Правила

- Frontend не ходит напрямую в Kafka, Redis, MinIO, PostgreSQL, MongoDB, ScyllaDB или Neo4j.
- Все публичные запросы идут через API Gateway / Load Balancer.
- Media binaries не попадают в git и не отправляются через JSON API.
- Frontend работает с metadata, signed/public URLs и object storage keys.
- State-changing requests должны передавать `Idempotency-Key`.
- Все ошибки соответствуют `ApiErrorResponse` из [API_CONTRACT.md](API_CONTRACT.md).

Обязательные headers для state-changing requests:

```http
Content-Type: application/json
Idempotency-Key: <uuid>
X-Request-Id: <uuid>
```

Желательные headers для read requests:

```http
X-Request-Id: <uuid>
```

## 2. Object Storage URL Strategy

### Принцип

Backend возвращает frontend только URL и metadata. Frontend не собирает S3 paths сам.

Для MVP допускаются public URLs из dev/object storage. Production target:

- публичные posters/thumbnails могут иметь CDN/public URL;
- исходные видео и тяжелые variants выдаются через expiring signed URLs;
- `objectKey` остается backend/internal reference;
- frontend может логировать `assetId`/`slug`, но не должен полагаться на структуру `objectKey`.

### Media URL Contract

```json
{
  "assetId": "0e6f9c26-6d86-4c1e-a3b0-5e4454c0b6f2",
  "slug": "aeris-main-hall-vertical-tour",
  "bucket": "astor-media",
  "objectKey": "content/c3flex/videos/aeris-main-hall/source.mp4",
  "publicUrl": "https://cdn.example.com/content/c3flex/videos/aeris-main-hall/source.mp4",
  "signedUrl": null,
  "expiresAt": null,
  "contentType": "video/mp4",
  "sizeBytes": 125000000
}
```

Rules:

- `publicUrl` is preferred for posters and public lightweight assets.
- `signedUrl` is preferred for protected/original video files.
- If both are present, frontend uses `signedUrl` for playback and `publicUrl` for share/preview.
- `expiresAt` is required when `signedUrl` is present.

## 3. Video Catalog Contract

### Endpoint

Planned production endpoint:

```http
GET /api/content/c3flex/videos?category=&tag=&featured=&limit=30
```

MVP fallback while endpoint is not implemented:

- Claude may use local mock metadata in `frontend/**`;
- field names must match this contract;
- media URLs must be placeholders or env-driven.

### Response

```json
{
  "items": [
    {
      "videoId": "0e6f9c26-6d86-4c1e-a3b0-5e4454c0b6f2",
      "slug": "aeris-main-hall-vertical-tour",
      "title": "AERIS main hall tour",
      "description": "Короткий вертикальный тур по главному залу AERIS.",
      "shortDescription": "Вертикальный тур по залу AERIS.",
      "tags": ["AERIS", "interior", "hospitality"],
      "category": "portfolio",
      "featured": true,
      "durationSeconds": 58,
      "orientation": "portrait",
      "status": "READY",
      "poster": {
        "assetId": "f4c56f03-3434-4db7-96da-11cf48c1f0dd",
        "publicUrl": "https://cdn.example.com/posters/aeris-main-hall.jpg",
        "contentType": "image/jpeg",
        "width": 1080,
        "height": 1920
      },
      "sources": [
        {
          "variant": "mobile",
          "publicUrl": "https://cdn.example.com/videos/aeris-main-hall/mobile.mp4",
          "contentType": "video/mp4",
          "width": 720,
          "height": 1280,
          "bitrateKbps": 1800
        },
        {
          "variant": "desktop",
          "publicUrl": "https://cdn.example.com/videos/aeris-main-hall/desktop.mp4",
          "contentType": "video/mp4",
          "width": 1080,
          "height": 1920,
          "bitrateKbps": 4500
        }
      ],
      "cta": {
        "label": "Обсудить похожий проект",
        "intent": "PROJECT_REQUEST"
      },
      "updatedAt": "2026-06-23T12:00:00Z"
    }
  ],
  "page": {
    "limit": 30,
    "offset": 0,
    "total": 30
  }
}
```

### Frontend Rules

- Player chooses the best source by viewport, network and orientation.
- UI must not break if only one source exists.
- UI must not assume exactly 30 items; 30 is current content target.
- Missing poster falls back to a design-system placeholder.
- Video card click should emit website context to Web Chat only when user sends a message or explicit action, not on passive hover.

## 4. Web Chat Contract

Current backend endpoint already exists:

```http
POST /api/messages
```

Frontend must use this endpoint for C3FLEX/site bot messages until a dedicated `/api/web-chat/sessions/{id}/messages` path is implemented.

Backend now stores WEB sessions/messages in `web_sessions` and `web_messages`.
For `channel=WEB`, `chatId` is optional and not authoritative: backend resolves a stable synthetic FSM `chatId` from `payload.sessionId` and returns it in the response.
If frontend sends both `payload.sessionId` and a temporary `chatId`, backend prefers `payload.sessionId`.
Production C3AG chat should use `NEXT_PUBLIC_WEB_CHAT_ENDPOINT=http://51.250.31.97:8089/api/messages` until a same-origin API gateway route is attached.
Backend CORS must include the frontend origin through `ASTOR_WEB_ALLOWED_ORIGINS`.
Operator notifications use `WebLeadNotificationService -> TelegramAdminNotifier`; configure `TELEGRAM_BOT_ENABLED=true`, `TELEGRAM_BOT_TOKEN` and `TELEGRAM_ANALYTICS_CHAT_ID` or `TELEGRAM_ADMIN_CHAT_ID` in runtime secrets.

Production smoke on 2026-08-01 confirmed that a C3AG WEB message is accepted by `http://51.250.31.97:8089/api/messages`, returns `WEB_LEAD_RECEIVED`, and persists exactly one `IN` plus one `OUT` row in `web_messages` for the test correlation id. The response included `ADMIN_ALERT`, so the operator notification projection was formed. Actual Telegram delivery was not fully verified because the VM currently resolves `api.telegram.org` but direct HTTPS to `api.telegram.org:443` times out; see `docs/operations/C3AG_DOMAIN_RUNBOOK.md`.

Controlled production k6 on 2026-08-01 used the read-only `scripts/k6_c3ag_prod_smoke.js` path for 5 minutes with `2` VUs. It produced 623 HTTP requests, `0.00%` failures, `100%` checks and `p95=536.5ms`. The script intentionally does not POST chat messages, so it does not generate mass Telegram or LLM side effects.

### Chat Chain Protection

- Ingress `/api/messages` WEB traffic is rate-limited per `payload.sessionId`, then `externalUserId`, then `chatId`, then client IP.
- Defaults are conservative and env-driven:
  - `ASTOR_WEB_RATE_LIMIT_ENABLED=true`;
  - `ASTOR_WEB_RATE_LIMIT_MAX_PER_MINUTE=12`;
  - `ASTOR_WEB_RATE_LIMIT_BURST_WINDOW_SECONDS=10`;
  - `ASTOR_WEB_RATE_LIMIT_MAX_BURST=4`.
- Redis is the limiter backend. If Redis is unavailable, limiter fails open and emits `astor.web_chat.rate_limit{outcome="error_open"}` so the site does not fail closed because of infrastructure.
- Denied requests return HTTP `429` with `Retry-After` and a user-safe `WEB_RATE_LIMITED` response body; frontend displays the returned text.
- Web fast-path lead capture does not invoke LLM. LLM-enabled FSM routes keep their own timeout settings; Telegram operator notifications are serialized and throttled in `TelegramAdminNotifier`.

### Clio Web Voice Contract

Status: source/mock only. Do not deploy as enabled until production HTTPS, SpeechKit credentials/billing and VM network reachability are verified.

Frontend feature flags:

```text
NEXT_PUBLIC_CLIO_VOICE_ENABLED=false
NEXT_PUBLIC_CLIO_TTS_ENABLED=false
NEXT_PUBLIC_CLIO_VOICE_TRANSCRIBE_ENDPOINT=/api/chat/transcribe
NEXT_PUBLIC_CLIO_TTS_ENDPOINT=/api/chat/speak
```

Server-side test-double flags for local QA only:

```text
CLIO_VOICE_TEST_DOUBLE_ENABLED=true
CLIO_TTS_TEST_DOUBLE_ENABLED=true
```

Rules:

- Browser microphone permission is requested only after an explicit click on the gold circular Clio voice control.
- Voice control states are explicit: `idle`, `listening`, `processing`, `speaking`, `error`, `permission_denied`, `unavailable`.
- No audio is silently recorded, transmitted or retained. Cancel stops tracks and clears chunks.
- Microphone API requires a secure context: HTTPS in production or localhost for local QA.
- Transcribed text enters the existing Web Chat path (`submit -> POST /api/messages`), so backend/FSM remains the source of truth for dialog state, lead persistence and notifications.
- SpeechKit STT/TTS credentials stay server-side. Frontend never receives API keys, IAM tokens or service account secrets.
- TTS is optional and uses a built-in feminine voice selected for Clio on the server (production: Sber SaluteSpeech `Nec_24000`; rollback: Yandex SpeechKit); it must not be presented as a cloned real person.
- The production TTS endpoint is the backend `POST /api/chat/speak` (`{"text": "..."}` → `{provider, status, voice, audioUrl (data URL), audioBase64, audioMimeType, message, createdAt}`; 503 `UNAVAILABLE` when the server voice is off, 429 when busy). Point `NEXT_PUBLIC_CLIO_TTS_ENDPOINT` at it (`https://api.c3ag.ru/api/chat/speak`); the frontend route of the same name stays a local test double. Server side: `ASTOR_TTS_PROVIDER`, `ASTOR_TTS_WEB_ENABLED`, `SALUTE_*` — see `docs/operations/SBER_AI_ACTIVATION.md`.

### Request

```json
{
  "channel": "WEB",
  "externalUserId": "web:anon:7c3417e0-3af4-4e7a-b5be-88759a7e9781",
  "chatId": null,
  "text": "Хочу обсудить похожий проект",
  "contactPhone": null,
  "firstName": null,
  "username": null,
  "correlationId": "web-2026-06-23T12:00:00.000Z-7c3417e0",
  "payload": {
    "site": "c3flex",
    "sessionId": "7c3417e0-3af4-4e7a-b5be-88759a7e9781",
    "page": "/portfolio/aeris",
    "referrer": "https://example.com/",
    "utm": {
      "source": "telegram",
      "medium": "social",
      "campaign": "aeris-demo"
    },
    "selectedVideo": {
      "videoId": "0e6f9c26-6d86-4c1e-a3b0-5e4454c0b6f2",
      "slug": "aeris-main-hall-vertical-tour",
      "title": "AERIS main hall tour"
    },
    "viewport": {
      "width": 390,
      "height": 844,
      "devicePixelRatio": 3,
      "locale": "ru-RU",
      "timezone": "Asia/Yekaterinburg"
    },
    "consent": {
      "privacyAccepted": true,
      "policyVersion": "2026-08-01-c3ag-web",
      "acceptedAt": "2026-06-23T12:00:00Z"
    }
  }
}
```

### Response

```json
{
  "channel": "WEB",
  "externalUserId": "web:anon:7c3417e0-3af4-4e7a-b5be-88759a7e9781",
  "chatId": 900000001,
  "text": "Принял. Передам запрос команде и уточню пару деталей.",
  "nextState": "READY_FOR_DIALOG",
  "html": false,
  "requestContact": false,
  "removeKeyboard": false,
  "fallback": false,
  "adminAlertRequired": true,
  "actions": ["WEB_LEAD_CAPTURED", "ADMIN_ALERT"],
  "metadata": {
    "leadId": "6eb31a07-30bb-4fa9-8d09-e6a67deaa85e",
    "scenario": "SITE_LEAD",
    "staffChatNotified": true
  },
  "createdAt": "2026-06-23T12:00:02Z"
}
```

### Frontend Rules

- `chatId` for anonymous web users is a temporary local numeric identifier until backend issues stable web sessions.
- Preferred mode: frontend sends `payload.sessionId` and omits `chatId`; backend returns stable synthetic `chatId`.
- Compatibility mode: frontend may send an existing backend-issued `chatId` only when `payload.sessionId` is unavailable.
- Frontend-generated temporary `chatId` is never the source of truth when `payload.sessionId` exists.
- Frontend stores `sessionId` in local storage/cookie after consent.
- User can write without OAuth login if privacy consent is accepted.
- OAuth enriches profile but is not required for a first lead.
- Chat widget collapse state is frontend-only and must not be sent as FSM state.
- Every sent message should include current page/video context.

## 5. Auth / OAuth / JWT Contract

Current stub endpoint:

```http
POST /api/auth/login
GET /api/auth/me
POST /api/auth/logout
```

Production target:

- Keycloak is the OIDC provider boundary.
- Frontend offers only Google and Yandex login.
- Backend validates JWT as resource server.

### Login Start

```http
POST /api/auth/login
```

```json
{
  "provider": "google",
  "redirectUri": "https://site.example.com/auth/callback",
  "returnTo": "/portfolio/aeris"
}
```

Response:

```json
{
  "provider": "keycloak",
  "authorizationUrl": "/oauth2/authorization/keycloak?kc_idp_hint=google",
  "redirectUri": "https://site.example.com/auth/callback",
  "returnTo": "/portfolio/aeris",
  "issuedAt": "2026-06-23T12:00:00Z"
}
```

### Current User

```http
GET /api/auth/me
Authorization: Bearer <access-token>
```

```json
{
  "subject": "keycloak-subject-id",
  "authenticated": true,
  "roles": ["GUEST"],
  "claims": {
    "email": "guest@example.com",
    "email_verified": "true",
    "provider": "google",
    "name": "Guest Name"
  },
  "resolvedAt": "2026-06-23T12:00:00Z"
}
```

### Token Policy

MVP policy:

- access token TTL: 15 minutes;
- refresh token TTL: 7 days;
- anonymous web chat session TTL: 30 days;
- JWT is not stored in local storage if frontend can use secure httpOnly cookie flow later;
- until cookie flow exists, token storage is treated as MVP-only and must be revisited before production launch.

## 6. Site Consent Contract

Current endpoint:

```http
GET /api/consents/policy/current
POST /api/consents
```

Frontend must show privacy notice before sending web chat/contact data.

### Current Policy

```json
{
  "version": "2026-08-01-c3ag-web",
  "title": "Политика обработки персональных данных C3AG",
  "url": "/docs/policy.html",
  "effectiveFrom": "2026-08-01T00:00:00Z"
}
```

### Grant Consent

```json
{
  "userId": null,
  "consentType": "PRIVACY_POLICY",
  "policyVersion": "2026-08-01-c3ag-web",
  "source": "WEB",
  "evidence": {
    "sessionId": "7c3417e0-3af4-4e7a-b5be-88759a7e9781",
    "page": "/portfolio/aeris",
    "acceptedAt": "2026-06-23T12:00:00Z",
    "userAgentHash": "sha256-placeholder"
  }
}
```

Rules:

- If `userId` is unknown, backend persists anonymous consent in `web_consents` through either `POST /api/consents` or Web Chat payload consent evidence.
- After OAuth login, frontend/backend links session consent to internal `users.id`.
- Consent is required before sending phone/email/message to staff/admin chats.

### Optional Yandex ID Consent Flow

Status: UI/mock only. OAuth app registration, scopes, real client IDs and token exchange are not implemented until legal copy and OAuth configuration are approved.

Consent UX requirements:

- First show the privacy-policy link and an explicit checkbox for contact processing/lead follow-up.
- User may continue without Yandex ID.
- Only after consent may the UI offer Yandex ID authorization.
- Requested Yandex fields must be listed before authorization. Initial minimum target: stable Yandex user id plus granted profile name/email if scopes permit.
- Do not assert phone can be retrieved automatically. Phone and Telegram are explicit optional user-entered fields:
  - phone: call/clarification;
  - Telegram: messaging/follow-up.
- Email may be used for a commercial proposal only if a separate proposal/marketing consent is checked where required by policy.
- Before submit, show a preview of exactly what will be sent.
- Persist consent version, time, source, selected scopes and confirmation action with the lead audit.
- Telegram/admin notification receives only minimum lead data and a consent/audit reference, not raw OAuth tokens.
- Do not share Yandex ID profile data silently with Telegram, LLM, booking/SABY or advertising systems.

## 7. Staff/Admin/System Notifications For Site

Frontend does not send Telegram notifications directly.

When Web Chat creates a lead or manager request, backend projects it to:

- Admin Chat for manual control and fallbacks;
- Staff/Manager Chat for operational requests;
- System Chat for technical events when enabled by flag.

Minimum admin projection fields:

```json
{
  "source": "WEB",
  "site": "c3flex",
  "sessionId": "7c3417e0-3af4-4e7a-b5be-88759a7e9781",
  "message": "Хочу обсудить похожий проект",
  "page": "/portfolio/aeris",
  "selectedVideo": "aeris-main-hall-vertical-tour",
  "contact": {
    "name": null,
    "phone": null,
    "email": null
  },
  "utm": {
    "source": "telegram",
    "medium": "social",
    "campaign": "aeris-demo"
  }
}
```

## 8. Claude Implementation Boundary

Claude can implement frontend against this contract using:

- TypeScript interfaces matching the fields above;
- mock data for `GET /api/content/c3flex/videos`;
- env-based API base URL;
- placeholder object storage URLs;
- chat widget payload builder for `POST /api/messages`.

Claude must not implement backend endpoints, edit Docker, or change FSM docs.

## 9. Codex Backend Status And Next Targets

Done:

- Service-backed `GET /api/content/c3flex/videos`.
- Backend-owned WEB session mapping through `web_sessions`.
- WEB message audit through `web_messages`.
- Anonymous WEB privacy consent persistence through `web_consents`.
- Tolerant backend DTOs for frontend-only fields such as `turn`.

Next:

1. Hand-test the new WEB lead/admin notification projection from the site chat.
2. Review Claude frontend session result and decide whether the two chat widgets collapse into one UX.
3. Add Keycloak/OAuth2 resource server configuration.
4. Link anonymous web consent to internal users after OAuth.
5. Upload C3FLEX media assets to object storage and replace placeholder URLs.
6. Run full frontend production build once SWC/native dependencies are available locally.
