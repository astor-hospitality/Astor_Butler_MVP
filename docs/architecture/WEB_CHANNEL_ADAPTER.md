# Веб-канал Astor: один чат-виджет для Butler и Concierge

Статус: предложение в PR `feat/web-channel-adapter` (Butler) и `feat/web-channel-adapter` (Concierge), 10.10.2026. Ревью — Михаил.

## 1. Цель

На сайте Astor (`frontend/astor-butler/`, страницы `astor_butler/` и `astor_concierge/`) один и тот же чат-виджет работает как бот того продукта, чья страница открыта:

- **Astor Butler** — гостевые сценарии ресторана (согласие и контакт, бронь стола, меню, афиша, помощь команды) — те же, что в Telegram, через тот же `MessageGatewayService` и FSM;
- **Astor Concierge** — рекомендация, заявка на столик через API Butler, передача на бизнес-ланч в бот ресторана, ответ ресторана по заявке.

Переключение продукта — одна строка на странице (`AstorChatConfig.product`). FSM остаётся источником истины; веб — ещё один транспорт рядом с Telegram, как и записано в `ARCHITECTURE.md`.

## 2. Что было до этого PR

- `POST /api/messages` (Butler, `MessageController`) принимал канал `WEB`, но **любое** веб-сообщение уходило в «быстрый путь» лида C3AG (`WEB_LEAD_RECEIVED`) и до FSM не доходило. Клиент мог передать `chatId`/`externalUserId` и подменить чужую сессию.
- `/api/astor/messages` существовал как relay внутри пилотного приложения очков (`astor_glasses_pilot.AstorWebRelay`): он резал ответ до `{text}`, кнопок и состояния не было.
- `js/widget.js` слал `{channel, text, payload:{sessionId, site, pageContext, sentAt}}` и показывал только текст.
- У Concierge HTTP-входа не было: только Telegram long polling и `/readyz` на `127.0.0.1:8095`.

## 3. Схема

```
Браузер (виджет, js/widget.js)
   │  POST JSON, тот же контракт для обоих продуктов
   ├── product=butler ───► https://<сайт>/api/astor/messages
   │        Caddy (edge) → astor-api-gateway:8080 (nginx, location /api/) → aeris_astor_butler_bot:8089
   │        WebChatController → MessageController (WEB) → MessageGatewayService → ScenarioRouter / FSM
   │        web_sessions / web_messages (Postgres), rate limit (Redis)
   └── product=concierge ► https://<сайт>/api/concierge/messages
            Caddy (edge) → astor-concierge-concierge-1:8096
            src/http/web.ts → ConciergeFlow → Butler API (бронь) / ссылка в бот ресторана (ланч)
            сессии в памяти с TTL, очередь ответов ресторана (StatusPoller → inbox)
```

## 4. Контракт веб-канала

Один JSON-контракт для обоих продуктов. Всё — в теле POST, в URL ничего персонального не передаётся.

### 4.1 Запрос

```json
{
  "channel": "WEB",
  "text": "Хочу стол на завтра в 20:00",
  "payload": {
    "sessionId": "web-k7s1m2n3-lx9q",
    "site": "astor-butler-commercial",
    "pageContext": "commercial_landing",
    "sentAt": "2026-10-10T12:00:00Z",
    "locale": "ru-RU",
    "action": "consent:yes",
    "contactPhone": "+7 900 000-00-00"
  }
}
```

| Поле | Обязательно | Смысл |
|---|---|---|
| `channel` | нет | Только `WEB` (отсутствует = `WEB`); любой другой канал — `400`. |
| `text` | да, если нет `action`/`contactPhone` | Текст гостя, до 4000 символов. Пустой текст без `action` — у Concierge «опрос» (забрать накопившиеся ответы ресторана), у Butler — `400`. |
| `payload.sessionId` | нет | Идентификатор сессии браузера, `^[A-Za-z0-9][A-Za-z0-9._:-]{5,79}$`. Отсутствует или не проходит проверку — сервер выдаёт новый и возвращает в `sessionId`; виджет обязан его запомнить. |
| `payload.site` | нет | Код сайта. У Butler определяет путь: сайты из `ASTOR_WEB_FSM_SITES` (по умолчанию `astor-butler-commercial,astor-butler,astor`) идут в гостевой FSM, остальные (например `c3ag`) — в прежний быстрый путь лида. |
| `payload.action` | нет | Значение нажатого quick reply вида `action` (аналог `callback_data`). У Butler при пустом `text` подставляется как текст. |
| `payload.contactPhone` | нет | Шаг контакта: номер телефона, который гость ввёл после `requestContact`. Butler принимает его как «поделился контактом» (`FirstTouchScenario`), Concierge — как `GuestInput{kind:'contact'}`. |
| `payload.pageContext`, `sentAt`, `locale`, `page`, `referrer`, `consent`, `userAgentHash` | нет | Контекст; у Butler сохраняется в `web_sessions.metadata_json`. Остальные ключи отбрасываются. |

Поля `chatId`, `externalUserId`, `firstName`, `username`, `correlationId` верхнего уровня у `/api/astor/messages` **игнорируются**: личность веб-гостя выводится только из `sessionId`. На общем `/api/messages` для канала `WEB` они игнорируются тоже (`WebChannelPolicy.sanitize`); `externalUserId` принимается только в форме `web:anon:<sessionId>` для совместимости с C3AG.

### 4.2 Ответ

Butler (`/api/astor/messages`):

```json
{
  "channel": "WEB",
  "product": "butler",
  "sessionId": "web-k7s1m2n3-lx9q",
  "text": "Нажимая кнопку «Поделиться номером», вы соглашаетесь с политикой обработки персональных данных (https://…).",
  "html": true,
  "requestContact": true,
  "nextState": "CONSENT_REQUIRED",
  "fallback": false,
  "quickReplies": [
    { "id": "contact", "kind": "contact", "text": "Поделиться номером", "value": "", "url": null }
  ],
  "correlationId": "2c0b…",
  "createdAt": "2026-10-10T12:00:01Z"
}
```

Concierge (`/api/concierge/messages`) — та же форма плюс `messages[]` (Concierge отвечает несколькими пузырями) и `pending` (сколько сообщений от ресторана накопилось с прошлого запроса):

```json
{
  "channel": "WEB",
  "product": "concierge",
  "sessionId": "web-k7s1m2n3-lx9q",
  "text": "Ресторан подтвердил бронь.\n\nЧто я умею: …",
  "html": false,
  "messages": [
    { "text": "Ресторан подтвердил бронь.", "html": false, "quickReplies": [], "requestContact": false },
    { "text": "Что я умею: …", "html": false, "quickReplies": [ { "id": "action:menu:book", "kind": "action", "text": "Забронировать стол", "value": "menu:book", "url": null } ], "requestContact": false }
  ],
  "quickReplies": [ { "id": "action:menu:book", "kind": "action", "text": "Забронировать стол", "value": "menu:book", "url": null } ],
  "requestContact": false,
  "pending": 1,
  "nextState": null,
  "createdAt": "2026-10-10T12:00:01Z"
}
```

Общий `/api/messages` возвращает прежний полный `MessageResponse` (с `chatId`, `actions`, `metadata`) плюс новые поля `sessionId` и `quickReplies`; `/api/astor/messages` отдаёт только то, что нужно браузеру.

### 4.3 Quick replies — веб-аналог клавиатур

| `kind` | Откуда берётся | Что делает виджет |
|---|---|---|
| `text` | Butler: `metadata.replyKeyboardRows` (reply-клавиатура сценария) или главное меню гостя в `READY_FOR_DIALOG` (`ASTOR_WEB_MAIN_MENU`) | отправляет `value` как обычный текст гостя |
| `action` | Concierge: inline-кнопки `Button{kind:'action'}` | показывает `text` как сообщение гостя, отправляет `payload.action = value` |
| `contact` | Butler: `requestContact=true`; Concierge: `Reply.contactRequest` | переводит поле ввода в режим телефона (`inputmode=tel`), следующий ввод уходит как `payload.contactPhone` |
| `url` | Concierge: кнопки `url` и `webapp` (лента заведений, ссылка на ланч в бот ресторана) | ссылка, открывается в новой вкладке |

Приоритет у Butler повторяет `TelegramRouter.send()`: контакт → `replyKeyboardRows` → главное меню для `READY_FOR_DIALOG` → ничего. Сам пакет `telegram/` не трогается: список главного меню продублирован в `WebQuickReplyResolver` и урезан до того, что возможно в браузере (без «Чаевые», «Донат», «Аукцион», «Мерч» — это Telegram Stars).

### 4.4 Шаг контакта

В Telegram гость нажимает «Согласиться и поделиться контактом», и бот получает `contact.phone_number`. В вебе: ответ приходит с `requestContact: true` и кнопкой `contact`; виджет просит ввести номер; номер уходит в `payload.contactPhone`. Butler трактует это как `CONTACT_SHARED` (`FirstTouchScenario.hasContact`) и фиксирует согласие в `user_consents` по псевдо-идентификатору сессии; Concierge — как `kind:'contact', own:true`. Номер — только в теле POST и в базе, в URL и логах его нет.

### 4.5 Переключатель продукта в виджете

```html
<!-- на странице astor_concierge/index.html, перед js/widget.js -->
<script>window.AstorChatConfig = { product: "concierge" };</script>
<script src="../js/widget.js" defer></script>
<script src="../js/main.js" defer></script>
```

`product: "butler"` (по умолчанию) → `/api/astor/messages`, `site = astor-butler-commercial`; `product: "concierge"` → `/api/concierge/messages`, `site = astor-concierge`. `endpoint: null` — локальные заглушки без сети (как раньше). `endpoint`/`site` можно задать явно. Разметка виджета (`#chatWidget`, `#chatLog`, `#chatForm`, `#chatInput`) и `js/main.js` не менялись: кнопки рисует `widget.js` под последним пузырём и при нажатии отправляет форму, чтобы лог, индикатор набора и обработка ошибок остались в `main.js`.

### 4.6 Ошибки

| Код | Когда |
|---|---|
| `400` | не `WEB`, не JSON/не объект, `text` не строка или длиннее 4000, пустое сообщение без `action`/`contactPhone` |
| `403` | Concierge: `Origin` не из списка и не same-origin |
| `413` | тело больше 16 КиБ |
| `415` | не `application/json` |
| `429` + `Retry-After` | лимит; Butler отдаёт дружелюбный `text` с `nextState: WEB_RATE_LIMITED`, виджет показывает его как ответ |

## 5. Сессии и личность веб-гостя

| | Astor Butler | Astor Concierge |
|---|---|---|
| Хранилище сессии | Postgres `web_sessions` (upsert по `session_id`), переписка в `web_messages`, согласие в `web_consents`/`user_consents` | память процесса, TTL `WEB_SESSION_TTL_MINUTES` (120); v0, одного экземпляра достаточно |
| Идентификатор для FSM | `chatId = 9e12 + hash(sessionId)` (`WebSessionRepository.stableChatId`); при коллизии `chat_id UNIQUE` с чужой сессией id пересчитывается с солью (до 8 попыток) и сохраняется — та же `sessionId` всегда получает тот же `chatId`. `telegramUserId = chatId` — псевдо-идентификатор, чтобы сценарии, согласия и брони (`telegram_user_id`) работали без Telegram | `chatId = telegramUserId` — **случайный** id из диапазона `7e12…` (`crypto.randomInt`), сохранённый в SQLite `web_sessions(session_id PK, chat_id UNIQUE, ip_hash, …)`; занятый id перебрасывается. Подобрать чужую сессию по id нельзя, согласие и заявки переживают перезапуск; черновик диалога — нет, как и в Telegram |
| Состояние FSM | Redis (`FSMStorage`) по `chatId` — как у Telegram | `ConciergeFlow.sessions` по `chatId`, TTL 2 ч; живых диалогов в памяти не больше `WEB_MAX_SESSIONS` (5000, вытесняются самые старые), чистка по таймеру раз в 60 с |
| Срок хранения | `WebSessionRetentionJob`: раз в час удаляет `web_messages` старше N дней и `web_sessions`, не видевшие гостя N дней (каскадом `web_messages`/`web_consents`); `ASTOR_WEB_SESSION_RETENTION_DAYS` (30) | записи `web_sessions` без активности 30 дней удаляются при чистке |
| Асинхронные ответы | **нет** (пробел, см. §9): карточка хостес уходит в Telegram по `chatId`, веб-гость её не увидит | `StatusPoller.notify` → `web.notify` → очередь сессии; отдаётся в `messages[]` при следующем запросе виджета (`pending`). Истекла сессия — сообщение отбрасывается как недоставляемое, как при блокировке бота |

Выбор «cookie или идентификатор, сгенерированный клиентом»: взят **client-generated id** (`web-<random>-<time36>`) в `sessionStorage` вкладки. Причины: статический сайт без backend-сессий, никакой cookie-баннер не нужен, идентификатор не связан с человеком, после закрытия вкладки диалог начинается заново (как `/start`). Альтернатива — `Set-Cookie: astor_session; HttpOnly; SameSite=Lax` с сервера: надёжнее против подделки id, но требует согласия на cookie и одинакового домена для сайта и API. Вопрос Михаилу (§11).

## 6. Безопасность

- **Личность.** Браузер не может выбрать `chatId`/`externalUserId`: `WebChannelPolicy.sanitize` их отбрасывает. Это закрывает подмену чужой Telegram-сессии через `/api/messages` для канала `WEB`.
- **CORS (Butler).** `ASTOR_WEB_ALLOWED_ORIGINS` (ключ `astor.web.allowed-origins`, `SecurityConfig`): список origin через запятую; **пустое значение = только same-origin**. Чтобы same-origin действительно распознавался за прокси, в `application.yaml` включён `server.forward-headers-strategy: framework` (`ForwardedHeaderFilter` восстанавливает `https://c3ag.ru` из `X-Forwarded-Proto`/`Host`, которые ставят Caddy и nginx api-gateway); тест `SecurityConfigCorsTest` проверяет, что `Origin: https://c3ag.ru` с пустым списком проходит, а без `X-Forwarded-Proto` — нет. В `docker-compose.cloudru.yml` всё равно стоит явный список `https://c3ag.ru,https://www.c3ag.ru`. Без `credentials`.
- **Origin (Concierge).** Если `Origin` есть в `WEB_ALLOWED_ORIGINS` — CORS-ответ; если совпадает с последним значением `X-Forwarded-Host` (его добавляет edge Caddy) или с `Host` — same-origin, без CORS-заголовков; иначе `403`. `Origin: null` — это непрозрачный origin, считается чужим. Пустой список = только same-origin.
- **Лимиты.** Butler (`WebChatRateLimiter`, Redis, fail-open): 4 сообщения / 10 с и 12 / мин на сессию, 60 / мин на IP (`ASTOR_WEB_RATE_LIMIT_MAX_PER_MINUTE_PER_IP`) и **5 новых (никогда не виденных) `sessionId` в минуту на IP** (`ASTOR_WEB_NEW_SESSIONS_PER_MINUTE_PER_IP`) — ротация id не обходит лимиты и не раздувает `web_sessions`. Concierge (`RateLimiter`, память): 20 / мин на сессию, 60 / мин на IP, 5 новых сессий / мин на IP (`WEB_NEW_SESSIONS_PER_MINUTE_PER_IP`) и **не больше одной открытой заявки на столик с одного адреса** (пока ресторан не ответил, следующий «Отправить» получает «заявка уже отправлена») — иначе ротацией сессий можно было бы удерживать по столу в минуту. IP берётся из **последнего** значения `X-Forwarded-For` — его добавляет сам edge Caddy; значения перед ним пишет клиент и они не учитываются.
- **Стоимость модели.** Свободный текст веб-гостя, доходящий до платной модели, ограничен: Butler — `WebModelBudget` на пути `MessageGatewayService.aiAssistedReply` (`ASTOR_WEB_MODEL_TEXT_MAX_CHARS` 600, `ASTOR_WEB_MODEL_CALLS_PER_DAY_PER_IP` 200 в сутки на хэш IP, Redis, fail-open); Concierge — те же пороги в адаптере (`WEB_MODEL_TEXT_MAX_CHARS`, `WEB_MODEL_CALLS_PER_DAY_PER_IP`), только при `LLM_PROVIDER != none`. Сверх лимита гость получает вежливый ответ и кнопки, состояние FSM не меняется, кнопочные сценарии продолжают работать. Telegram это не затрагивает.
- **Telegram-аналитика.** Карточка «website lead» уходит из фоновой очереди (`WebLeadNotificationService`, один поток, очередь `ASTOR_WEB_NOTIFICATIONS_QUEUE_CAPACITY` = 200, при переполнении карточка отбрасывается и считается) — `TelegramAdminNotifier.sendAnalytics` держит интервал 3,2 с и повторы, на потоке запроса он бы сериализовал Tomcat. Для FSM-сайтов карточка отправляется только на первое сообщение сессии и на fallback; для C3AG — на каждое, как раньше. Текст гостя в карточке обрезается до 2500 символов (лимит Telegram 4096).
- **Границы.** Тело до 16 КиБ, текст до 4000 символов, payload по allow-list, `Cache-Control: no-store`.
- **Нет PII в URL.** Единственный путь — `POST`, `sessionId` и телефон только в теле. `GET`-опроса нет: опрос — тот же `POST` с пустым `text`.
- **HTML.** Butler отдаёт `html: true` для текста согласия со ссылкой; виджет превращает его в текст (`DOMParser`), ссылку сохраняет в скобках, в DOM через `textContent`.
- **Известный пробел (вне этого PR).** `/api/messages` публично доступен через `c3ag.ru/api/` и принимает каналы `TELEGRAM`/`INTERNAL` с произвольным `chatId` (так он задуман для smoke-прогонов). Рекомендация: закрыть `/api/messages` на api-gateway для внешних запросов и оставить снаружи только `/api/astor/messages`, либо требовать внутренний токен для не-WEB каналов. Вопрос Михаилу.

## 7. Маршрутизация на VM (Cloud.ru, `/opt/edge`)

Ничего из этого PR не задеплоено. Нужные изменения:

### 7.1 Caddy `/opt/edge/sites.d/c3ag.caddy` (или отдельный vhost сайта Astor)

```caddyfile
# Astor Butler web chat: через api-gateway (nginx location /api/ → aeris_astor_butler_bot:8089)
handle /api/astor/messages {
    reverse_proxy astor-api-gateway:8080
}

# Astor Concierge web chat: напрямую в контейнер Concierge (порт WEB_PORT, по умолчанию 8096)
handle /api/concierge/* {
    reverse_proxy astor-concierge-concierge-1:8096
}
```

Обе директивы должны стоять **до** общего `handle /api/*` → `astor-api-gateway:8080`. Caddy по умолчанию добавляет `X-Forwarded-For`/`X-Forwarded-Host` — на них опираются лимит по IP и same-origin у Concierge. Пока Caddy не обновлён, `/api/astor/messages` продолжит попадать в общий `/api/*` → api-gateway → Butler, и это **уже правильный адрес** (nginx шлёт `/api/` в Butler); relay из приложения очков больше не на пути.

### 7.2 Compose Concierge (`docker-compose.cloudru.yml`, в этом PR)

Контейнер подключается к внешней сети `edge` (`EDGE_NETWORK`, по умолчанию `edge`) и объявляет `expose: 8096`; порт наружу не публикуется, `/readyz` остаётся на `127.0.0.1:8095`. В `.env`: `WEB_PORT=8096`, `WEB_ALLOWED_ORIGINS=` (пусто — same-origin за Caddy), `WEB_TRUST_PROXY=true`.

### 7.3 Compose Butler (без изменений файлов)

Переменные с умолчаниями: `ASTOR_WEB_FSM_SITES`, `ASTOR_WEB_MAIN_MENU`, `ASTOR_WEB_RATE_LIMIT_MAX_PER_MINUTE_PER_IP`, `ASTOR_WEB_NEW_SESSIONS_PER_MINUTE_PER_IP`, `ASTOR_WEB_MODEL_TEXT_MAX_CHARS`, `ASTOR_WEB_MODEL_CALLS_PER_DAY_PER_IP`, `ASTOR_WEB_SESSION_RETENTION_DAYS`, `ASTOR_WEB_NOTIFICATIONS_QUEUE_CAPACITY`, `SERVER_FORWARD_HEADERS_STRATEGY` (framework); `ASTOR_WEB_ALLOWED_ORIGINS` уже передаётся. При необходимости добавить их в `environment` бота в `docker-compose.cloudru.yml`.

### 7.4 Проверка после выкладки

```sh
curl -s https://c3ag.ru/api/astor/messages -H 'Content-Type: application/json' \
  -d '{"channel":"WEB","text":"/start","payload":{"sessionId":"web-smoke-000001","site":"astor-butler-commercial"}}'
# ожидаем nextState CONSENT_REQUIRED, requestContact true, quickReplies[0].kind contact

curl -s https://c3ag.ru/api/concierge/messages -H 'Content-Type: application/json' \
  -d '{"channel":"WEB","text":"/start","payload":{"sessionId":"web-smoke-000002","site":"astor-concierge"}}'
# ожидаем product concierge и quickReplies с menu:book
```

## 8. Concierge: веб-сессия и существующий поток брони

`ConciergeFlow.handle(guest, input)` не знает о канале. Веб-адаптер (`src/http/web.ts`) отображает запрос в `GuestInput`:

| Вход | `GuestInput` |
|---|---|
| `payload.contactPhone` | `{kind:'contact', phone, own:true}` |
| `payload.action` | `{kind:'action', action}` — те же `menu:*`, `date:*`, `consent:yes`, `submit:<key>` |
| `/start`, первый запрос с пустым текстом | `{kind:'start'}` |
| `/book`, `/lunch`, `/status`, `/cancel`, `/help`, `/forget` | `{kind:'command'}`; другие `/x` → `help` |
| текст | `{kind:'text'}` |
| пустой текст в живой сессии | без входа — только выдача накопленных ответов ресторана |

Заявка в Butler уходит с `chatId = telegramUserId = 7e12…` веб-гостя и комментарием `Astor Concierge · заявка C-<id>`, как и из Telegram. Ответ хостес `StatusPoller` кладёт в очередь сессии вместо Telegram. **Ссылка на бизнес-ланч** (`t.me/astor_butler_bot?start=lunch_…`) и **лента заведений** (`FEED_URL`) отдаются как quick reply `url`: гость уходит в Telegram/ленту обычной ссылкой — в вебе Mini App нет.

## 9. Пробелы относительно Telegram

| Возможность | Telegram | Веб сейчас | Что нужно |
|---|---|---|---|
| Голосовые сообщения (STT, голосовые ответы) | да | нет | `MediaRecorder` → `multipart` → существующий `VoiceTranscription*`; фаза 2+ |
| Mini App «Лента заведений» | да (`web_app`) | обычная ссылка | ничего: лента и так HTML-страница |
| Поделиться контактом одной кнопкой | да | ввод номера вручную | — |
| Асинхронные сообщения Butler (подтверждение хостес, напоминания) | да, по `chatId` | не доходят до веб-гостя | очередь ответов по `sessionId` в Butler (как в Concierge) + `pending` в ответе; фаза 2 |
| Telegram Stars (чаевые, донат, аукцион, мерч) | да | убраны из меню | отдельная платёжная ссылка; не в MVP |
| Превью-карточка `ensurePreview`, пин сообщения, HTML-разметка | да | текст | — |
| Групповые/служебные чаты, ops-команды | да | не применимо | — |
| Язык ответов | по `language_code` | `payload.locale` сохраняется, не используется | связать с `feat/i18n-multilang` |

## 10. Фазы и тесты

**Фаза 0 — этот PR (локально, без деплоя).**
- Butler: `WebChatController` (`/api/astor/messages`), `WebChannelPolicy`, `WebQuickReplyResolver`, окна на IP и на новые сессии в `WebChatRateLimiter`, `WebModelBudget`, `WebSessionRetentionJob`, асинхронный `WebLeadNotificationService`, `MessageController` для `WEB` через FSM по `site`. Тесты: `WebChatControllerTest` (MockMvc: контракт, forced WEB, отбрасывание identity, 400/413/415/429, c3ag fast path, выдача `sessionId`), `MessageControllerTest`, `WebQuickReplyResolverTest`, `WebChannelPolicyTest`, `WebChatRateLimiterTest`, `WebLeadNotificationServiceTest` (фоновая очередь, сброс при переполнении, обрезка, фильтр FSM-сайтов), `WebSessionRepositoryTest` (коллизия `chat_id`), `WebSessionRetentionJobTest`, `WebModelBudgetTest`, `MessageGatewayServiceTest` (бюджет перед моделью), `SecurityConfigCorsTest` (пусто = same-origin, в том числе за прокси). Полный прогон `mvn -o test`: 890 тестов, 0 падений.
- Виджет: `js/widget.js` — продукт, quick replies, шаг контакта, `sessionStorage`, 429.
- Concierge: `src/http/web.ts`, таблица `web_sessions` в `Store` (схема v2), `WEB_*` в `config.ts`, маршрутизация `notify` в `main.ts`. Тесты `test/web.test.ts` по HTTP с `FakeButler`: приветствие и меню, полная заявка до `POST /api/bookings/table-reservations`, нормализация телефона, случайный персистентный id и перебросок занятого, лимит новых сессий с адреса, одна открытая заявка на адрес, очередь уведомлений, TTL и вытеснение сверх `WEB_MAX_SESSIONS`, Origin/CORS/preflight/`Origin: null`/последний hop `X-Forwarded-*`, лимиты по сессии и IP, порог длины и дневной бюджет модели, 400/413/415/404, выдача `sessionId`, ссылка на ланч. `npm run check`: 98 тестов, 0 падений.

**Фаза 1 — выкладка и сквозная проверка (нужен доступ к VM).** Caddy из §7.1, compose Concierge, smoke из §7.4, прогон в браузере на `astor_butler/` и `astor_concierge/` (одна строка `AstorChatConfig.product` на странице Concierge — в PR сайта `feat/astor-site-brand` или следом). Проверить, что `web_sessions`/`web_messages` пишутся и карточка лида в Telegram-аналитику не шумит на FSM-сайтах (`ASTOR_WEB_ADMIN_CHAT_NOTIFICATIONS_ENABLED`).

**Фаза 2 — паритет.** Очередь асинхронных ответов Butler для веб-гостя; cookie-сессия (если решим); язык из `locale`; голос; метрики `astor.web_chat.*` в Grafana; e2e-тест виджета (Playwright) против стенда из `test/integration/butler-stack.compose.yml`.

## 11. Открытые вопросы для Михаила

1. Домен сайта Astor: `c3ag.ru/astor/` (same-origin с API, CORS не нужен) или отдельный домен (тогда `ASTOR_WEB_ALLOWED_ORIGINS`/`WEB_ALLOWED_ORIGINS` = его origin)?
2. Имя docker-сети edge Caddy — `edge`? (`EDGE_NETWORK` в compose Concierge.) И имя контейнера Concierge на VM — `astor-concierge-concierge-1`?
3. Закрыть ли `/api/messages` снаружи на api-gateway (оставить только `/api/astor/messages`), пока не-WEB каналы без аутентификации?
4. Сессия: оставить client-generated id в `sessionStorage` или перейти на HttpOnly cookie (нужен cookie-баннер)?
5. Нужна ли карточка «website lead» в Telegram-аналитику на каждое сообщение веб-гостя Butler (сейчас — да, как для C3AG), или только на первое/при fallback?
6. Состав главного меню веба (`ASTOR_WEB_MAIN_MENU`): оставить урезанный список Telegram-меню или свой?
7. Relay `AstorWebRelay` в приложении очков: удалить в следующем PR или оставить как запасной путь?
8. Для Concierge: ждать ответа ресторана в вебе через опрос (виджет шлёт пустой `text` раз в N секунд, пока есть заявка) — включить в виджете по умолчанию?
