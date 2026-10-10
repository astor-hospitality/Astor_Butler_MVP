# MAX Adapter Plan: канал MAX для Astor Butler и Astor Concierge

Дата: 2026-10-10. Ветка: `feat/max-adapter`.

Статус:
- Фаза 1 (Butler) реализована в этой ветке за флагом `ASTOR_MAX_ENABLED`, по умолчанию выключенным.
- Для Concierge есть только план и список задач по файлам; код Concierge не менялся.
- Живой бот MAX не подключался: токена нет, бот на business.max.ru не зарегистрирован.

## 1. Цель и рамки

Цель — дать гостям AERIS второй мессенджер, MAX (VK), с тем же поведением, что в Telegram. FSM остаётся единственным источником истины; MAX, как и Telegram, только транспорт и UI.

Порядок работ:
1. **Гостевой бот AERIS (Butler).** Текст, кнопки, согласие и контакт, deep link, позже голос, медиа и бронь до подтверждения хостес.
2. **Уведомления персонала и ops.** Пока остаются в Telegram. В MAX — отдельной фазой, если команда решит туда переходить.
3. **Concierge.** Отдельный репозиторий и бот (раздел 11); сначала нужны изменения API Butler.

Не входит в задачу:
- платежи Telegram Stars (в MAX Bot API аналога нет);
- перенос персонала из Telegram;
- webhook-транспорт в фазе 1;
- изменения поведения Telegram.

## 2. Что проверено в MAX Bot API (2026-10-10)

Проверено по первоисточникам:
- dev.max.ru: страницы методов, changelog, help, раздел webapps;
- OpenAPI `github.com/max-messenger/api-schema` (`schema.yaml` v0.0.33);
- официальные клиенты Go и TS.

Несколько запросов без токена ушли к живому API, чтобы увидеть формат ошибок. Пометка «community» означает наблюдение не из официальной документации.

### Хост, авторизация, TLS

**Хост — `https://platform-api2.max.ru`.**
- С 19.07.2026 запросы нужно слать на `platform-api2.max.ru`; `platform-api.max.ru` и `botapi.max.ru` устарели. Это отличается от исходной постановки, где был указан `platform-api.max.ru`.
- Глобальный лимит хоста — 30 rps.
- Источники: https://dev.max.ru/docs-api/changelog-api, https://dev.max.ru/docs/chatbots/bots-coding/prepare.

**Авторизация.**
- Заголовок `Authorization: <token>`, без префикса `Bearer`.
- На `Bearer …` API отвечает 401 `Malformed access token`.
- Передача токена в query-параметре больше не поддерживается.
- Ошибки приходят в формате `{"code","message"}`, например 401 `{"code":"verify.token","message":"Invalid access_token"}`.
- Источник: https://dev.max.ru/docs-api.

**TLS.**
- Сертификат хоста выпущен цепочкой `*.max.ru` → Russian Trusted Sub CA → Russian Trusted Root CA (Минцифры).
- В стандартном JDK trust store этого корня нет; проверено `keytool -cacerts` на JDK проекта.
- Нужен тот же PEM, что уже монтируется для GigaChat и SaluteSpeech: `/app/certs/russian_trusted_root_ca.pem`.

### Получение обновлений

**Long polling: `GET /updates?limit&timeout&marker&types`.**
- `limit` — 1..1000, `timeout` — 0..90 секунд.
- Ответ: `{"updates":[...],"marker":<int64|null>}`.
- Переданный `marker` подтверждает все более ранние обновления.
- Документация оговаривает, что long polling ограничен по скорости и сроку хранения событий и не рекомендуется для продакшена.
- Источник: https://dev.max.ru/docs-api/methods/GET/updates.

**Webhook.** Подписка — `POST /subscriptions {url, update_types, secret}`. Требования:
- только HTTPS на порту 443;
- сертификат доверенного УЦ или Минцифры, самоподписанный не принимается;
- ответ 200 за 30 секунд;
- секрет приходит в заголовке `X-Max-Bot-Api-Secret`.

Повторы идут с интервалом ×2.5. Если 8 часов подряд нет успешной доставки, подписка снимается. Webhook и polling **одновременно не работают**. Источники: https://dev.max.ru/docs-api/methods/POST/subscriptions, https://dev.max.ru/help/events.

**Типы обновлений, нужные гостю:**
- `message_created` — содержит `message.sender`, `message.recipient{chat_id, chat_type: dialog|chat|channel}`, `message.body{mid, seq, text, attachments, markup}`, `user_locale`;
- `bot_started` — содержит `chat_id`, `user`, `payload`, `user_locale`;
- `message_callback` — содержит `callback{callback_id, payload, user}` и `message`.

Источник: https://dev.max.ru/docs-api/objects/Update.

**Знак `chat_id` не документирован.** В реальных выгрузках у диалога бывает отрицательный `chat_id` (community). Поэтому группа определяется только по `chat_type`, не по знаку.

### Отправка и кнопки

**Отправка: `POST /messages?chat_id=` (или `?user_id=`).**
- Тело: `{text ≤ 4000, format: html|markdown, attachments, notify, link}`.
- Не больше 2 сообщений в секунду на чат.
- Источник: https://dev.max.ru/docs-api/methods/POST/messages.

**Inline-клавиатура.** Формат: `{"type":"inline_keyboard","payload":{"buttons":[[...]]}}`.
- Типы кнопок: `callback`, `link`, `request_contact`, `request_geo_location`, `open_app`, `message`, `clipboard`.
- Кнопка `message` отправляет свой текст в чат от имени пользователя.
- Лимиты: до 210 кнопок, 30 рядов, 7 кнопок в ряду; для `link`/`open_app`/`request_*` — до 3 в ряду; подпись — до 128 символов.
- Постоянной reply-клавиатуры, как в Telegram, в MAX нет.
- Источник: https://dev.max.ru/docs-api/use-cases/sending-messages/keyboard.

**Ответ на callback: `POST /answers?callback_id=`.**
- Тело — `{message?, notification?}`.
- Поле `notification` есть в OpenAPI и TS-клиенте, но на странице dev.max.ru его нет.

**Форматирование HTML.**
- Поддерживаются `b/strong`, `i/em`, `u/ins`, `s/del`, `a`, `code/pre`, `blockquote`, `mark`, `h1–h4`.
- Остальные теги вырезаются.
- Источник: https://dev.max.ru/docs-api/use-cases/sending-messages/text-formatting.

### Медиа и контакт

**Загрузка файлов.**
- Шаг 1: `POST /uploads?type=image|video|audio|file`.
- Шаг 2: multipart-загрузка в поле `data`.
- Шаг 3: токен вложения прикладывается к сообщению.
- Сразу после загрузки возможна ошибка `attachment.not.ready`, нужен повтор с паузой.
- Типа «голосовое сообщение» нет, только `audio` (MP3, M4A и др.).
- Входящее `audio` имеет вид `{payload:{url, token}, transcription}`. Формат файла, способ скачивания и нужна ли авторизация — **не документировано**.
- Источник: https://dev.max.ru/docs-api/methods/POST/uploads.

**Контакт.** Кнопка `request_contact` присылает вложение `contact` с полями `{vcf_info, max_info, hash}`.
- Отдельного поля телефона нет; телефон берётся из строки `TEL` в vCard.
- `hash` есть только при отправке через кнопку. Он равен HMAC-SHA256(token, vcf_info), но кодировка (hex или base64) не документирована.

### Ссылки, чаты, Mini Apps, регистрация, SDK

**Deep link: `https://max.ru/<botName>?start=<payload>`.**
- `botName` назначается автоматически: `id<ИНН>_bot` для ООО и ИП, `se<orgid>_bot` для самозанятых. Изменить его нельзя.
- `payload` — до 128 символов (схема OpenAPI пишет 512, доверяем 128). Набор символов не документирован, безопасно `[A-Za-z0-9_-]`.
- Payload приходит в `bot_started.payload`.
- По community-данным, если диалог с ботом уже есть, ссылка просто открывает чат и `bot_started` **повторно не приходит**.
- Источник: https://dev.max.ru/help/deeplinks.

**Группы.**
- Добавлять бота в групповые чаты по умолчанию запрещено; включается в business.max.ru → Настройки → Приватность.
- Боту нужны права администратора: минимум `read_all_messages` и `write`.
- Метод `GET /chats` удалён, поэтому `chat_id` групп приходится собирать из обновлений.

**Mini Apps.**
- Мини-приложение существует только при боте, один URL на бота; URL задаётся в настройках бота на business.max.ru.
- Открывается кнопкой `open_app` (поле `web_app` — ник бота, не URL) или ссылкой `?startapp=`.
- Bridge подключается так: `https://st.max.ru/js/max-web-app.js`, объект `window.WebApp`.
- Проверка `initData` устроена как в Telegram: `secret = HMAC_SHA256("WebAppData", token)`, `hash = hex(HMAC_SHA256(secret, data_check_string))`. Параметр во фрагменте называется `WebAppData`, а не `tgWebAppData`.
- Источники: https://dev.max.ru/docs/webapps/introduction, https://dev.max.ru/docs/webapps/validation.

**Регистрация бота.**
- Создать бота могут только юрлица, ИП и самозанятые — резиденты РФ с профилем, верифицированным на business.max.ru (через Госуслуги, T-Business ID или Alfa ID; проходит владелец или уполномоченный подписант). Физлица и нерезиденты не могут.
- Квота — 5 ботов на организацию.
- Модерация занимает до 48 рабочих часов; токен появляется только после неё.
- Передать бота другому владельцу нельзя.
- Правила (§1.5): без договора с MAX нельзя рассылать авторизационные, транзакционные и сервисные сообщения, а также рекламу и массовые рассылки.
- Источники: https://dev.max.ru/docs/maxbusiness/connection, https://dev.max.ru/docs/chatbots/bots-create/create, https://dev.max.ru/docs/legal/requirements.

**SDK.**
- Официального Java SDK нет: репозиторий `max-messenger/max-bot-api-client-java` отдаёт 404, на Maven Central его не было.
- Официальные клиенты есть для TS (`@maxhub/max-bot-api`) и Go.
- Отсюда решение для Butler: свой тонкий клиент на JDK `HttpClient`, в стиле клиентов SpeechKit и эмбеддингов в этом репозитории.

## 3. Butler: Telegram → MAX → разрыв и решение

| Функция в Telegram (сейчас) | Эквивалент в MAX | Разрыв / решение | Фаза |
|---|---|---|---|
| Входящий текст → `MessageGatewayService` | `message_created`, `body.text` | Нет. `MaxUpdateMapper` → `IncomingMessage.max(...)`, тот же gateway | 1 ✅ |
| Ответ текстом, HTML (`parseMode=HTML`) | `POST /messages`, `format=html` | Теги из сценариев (`b`, `i`, `a`, `blockquote`, `code`) поддерживаются; остальные MAX вырезает | 1 ✅ |
| Лимит 4096 символов | Лимит 4000 | Разбивка по абзацам, клавиатура под последней частью, пауза 550 мс между частями (лимит 2 сообщения/с на чат) | 1 ✅ |
| Главное меню — постоянная reply-клавиатура | Постоянной клавиатуры нет; кнопка `message` | Меню отправляется inline-кнопками `message` под ответом при `READY_FOR_DIALOG`; нажатие приходит в FSM тем же текстом. Подписи пока скопированы из `TelegramRouter` (общий каталог — фаза 2) | 1 ✅ |
| `metadata.replyKeyboardRows` сценариев | Кнопки `message` | Те же ряды; длинные ряды переносятся (≤ 7 в ряду, ≤ 30 рядов, ≤ 210 кнопок) | 1 ✅ |
| `/start`, `/restart` | `bot_started`; команды приходят текстом | `bot_started` → текст `/start` | 1 ✅ |
| Deep link `t.me/bot?start=lunch_…` (handoff бизнес-ланча) | `max.ru/<bot>?start=…` → `bot_started.payload` | Превращается в `/start <payload>`; `BusinessLunchHandoff` разбирает его без изменений. Ограничения: ≤ 128 символов; повторного `bot_started` при существующем диалоге может не быть (раздел 9) | 1 ✅ |
| Согласие: кнопка «Согласиться и поделиться контактом» (`request_contact`, reply-клавиатура) | Inline-кнопка `request_contact`, вложение `contact` | Телефон берётся из `vcf_info` (`TEL`). Принимается только свой контакт: есть `hash` (значит, пришёл через кнопку) или `max_info.user_id` совпадает с отправителем. Согласие пишется в `user_consents` без `telegram_user_id`, ключ — внутренний `chat_id`, `source=MAX_CONTACT_FLOW`. Проверка HMAC `hash` — фаза 2 | 1 ✅ |
| Идентичность гостя: `users.telegram_id`, `telegram_profiles`, `telegram_messages` | `user_id` MAX | Фаза 1: `messenger_chat_bindings` (внутренний id чата, профиль, телефон); Telegram-таблицы MAX-идентификаторов не видят. Фаза 2: канально-нейтральная идентичность | 1 ✅ / 2 |
| Защита от дублей (`IdempotencyGuard` по `update_id`) | `mid`, `callback_id` | `IdempotencyService.accept("max:<mid>")` | 1 ✅ |
| Callback-кнопки (хостес, отзыв о визите, «Подробнее», сабраж) | `callback` + `POST /answers` | Фаза 1: callback подтверждается, в FSM уходит только свой `text:<слова>`, остальное игнорируется (как у Telegram для неизвестных callback). Отзыв о визите и «Подробнее» — фазы 2–3 | 1 / 2 |
| Алерты администратору (`AdminAlert`) | — | Остаются в Telegram: `MaxAdminAlertRelay` отправляет их в тот же admin-чат Telegram, в тексте видно `Channel: MAX` | 1 ✅ |
| Уведомления гостю о брони (подтверждено, отказ, отмена), счёт, отложенные намерения | `POST /messages` | Сейчас `TableReservationNotificationService`, `TableReservationPendingIntentService`, `GuestBillNotifier` шлют прямо в Telegram по `chatId`. Для внутреннего id MAX Telegram вернёт ошибку (только лог). Решение — исходящий порт с маршрутизацией по `messenger_chat_bindings` | 2 |
| Голос гостя (STT) | Вложение `audio` + поле `transcription` | Фаза 1: если MAX прислал расшифровку, она идёт в FSM как текст (`transcriptionProvider=max`); иначе gateway честно просит написать текстом. Фаза 3: скачивание по `payload.url` и своя STT (формат не документирован, нужна проверка на живом боте) | 1 / 3 |
| Голосовые ответы (TTS, `ASTOR_TELEGRAM_VOICE_REPLIES`) | `POST /uploads?type=audio` + вложение `audio` | Типа voice note нет. Ogg Opus из SaluteSpeech может не подойти, нужен mp3 или m4a (проверить); `attachment.not.ready` требует повторов | 3 |
| Превью-карточка с фото и закреп | `uploads?type=image` + вложение `image`; закреп не проверен | Фото — фаза 2; закреп — проверить API | 2 |
| Документы (PDF плана зала), видео-тур | `uploads?type=file`, `uploads?type=video` | Фаза 2, с повторами при `attachment.not.ready` | 2 |
| Меню команд (`setMyCommands`) | `PATCH /me/commands` (до 32 команд) | Фаза 2 | 2 |
| «Печатает…» | `POST /chats/{chatId}/actions` `typing_on` | Фаза 2, по желанию | 2 |
| Группы ops / хостес / аналитики, ops-команды в группах | Группы MAX: бот-администратор, включение приватности на business.max.ru, `GET /chats` удалён | Персонал остаётся в Telegram. Групповые чаты MAX в фазе 1 игнорируются (`ASTOR_MAX_GROUP_CHATS_ENABLED=false`); при включении получают отрицательный внутренний id и идут в существующие ветки групп gateway | 5 |
| Платежи Telegram Stars | Нет в Bot API | Не переносится | — |
| Лента Mini App (Telegram WebApp) | Mini App MAX: один URL на бота, `open_app`, Bridge `max-web-app.js`, `initData` по схеме Telegram | Фаза 6 (и раздел 11 для Concierge) | 6 |
| Транспорт обновлений: long polling через HTTP-прокси | Long polling или webhook, напрямую с ВМ | Фаза 1 — polling без прокси. Для продакшена документация рекомендует webhook (HTTPS 443, доверенный сертификат) — фаза 4 | 1 ✅ / 4 |

## 4. Целевая архитектура

```text
               ┌──────────────── транспорт (UI) ────────────────┐
Telegram ──► TelegramRouter ─┐                                   │
MAX ───────► MaxLongPollingRunner → MaxRouter ─┐                 │
Web ───────► MessageController ────────────────┤                 │
                                               ▼                 │
                    IncomingMessage(channel, chatId, externalUserId, …)
                                               │
                                MessageGatewayService (FSM, сценарии, модель,
                                 timeline, Kafka, системные уведомления)
                                               │
                                     OutgoingMessage (канально-нейтральный)
                                               │
          ┌────────────── фаза 2: GuestOutboundPort ──────────────┐
          │ resolve(chatId) → канал по messenger_chat_bindings     │
          │   TelegramGuestSender (существующий код)               │
          │   MaxGuestSender (MaxReplyRenderer + MaxBotApiClient)  │
          └────────────────────────────────────────────────────────┘
```

### Входящий порт (фаза 1)

Канально-нейтральная модель уже существует — `IncomingMessage`; добавлен `MessageChannel.MAX`.
- Фабрика `IncomingMessage.max(...)` оставляет Telegram-поля (`telegramUserId`, `telegramMessageId`, `telegramUpdateId`) пустыми.
- Поэтому `IdentityService`, `TelegramIntakeService` и согласие по Telegram id MAX-идентификаторов не видят.
- Пересечения id между мессенджерами исключены.

### Внутренний id чата

FSM, черновики, timeline и Kafka-ключи завязаны на числовой `chatId`, а gateway считает отрицательный `chatId` группой. Поэтому MAX-чату выдаётся собственный внутренний id, а не сырой `chat_id` MAX (у диалога он тоже бывает отрицательным).

| Канал | Диапазон `chatId` |
|---|---|
| Telegram | id Telegram (сейчас < 10¹¹; группы `-100…`) |
| Web (C3AG) | `9·10¹²` … `9.9·10¹²` (`WebSessionRepository`) |
| MAX, диалог | `8·10¹² + n` |
| MAX, группа | `-(8·10¹² + n)` |

`n` берётся из `messenger_internal_chat_seq`, связка хранится в `messenger_chat_bindings`.

### Исходящий порт (фаза 2)

Сейчас ответ на входящее сообщение отправляет сам `MaxRouter`. Асинхронные отправки гостю (брони, счёт, отложенные намерения) нужно вынести за интерфейс `GuestOutboundPort.send(chatId, OutgoingMessage | GuestNotification)`. Реализация выбирает канал по диапазону id и `messenger_chat_bindings`.

Порядок перевода:
1. Сначала MAX-ветка.
2. Telegram-ветка оборачивает существующий код без изменения поведения.
3. Затем по одному классу переводятся `TableReservationNotificationService`, `TableReservationPendingIntentService`, `GuestBillNotifier`.

### Идентичность (фаза 2)

Таблица `user_channel_identities (user_id → users.id, channel, external_user_id, chat_id, …)`, уникальная по `(channel, external_user_id)`.
- `users.telegram_id` остаётся для обратной совместимости.
- Согласие и контакты привязываются к `users.id`.
- MAX-строки `user_consents` из фазы 1 (с ключом по `chat_id`) переносятся миграцией.
- `FsmTimelineEvent` и `UserEventFactory` получают `guestId = max:user:<id>` вместо `chat:<id>`.

### Миграции

Liquibase, `src/main/resources/db/changelog/*.yaml` + `.sql`, подключаются в `changelog-master.yaml`. SQL пишется так, чтобы тесты выполняли его на H2 в режиме PostgreSQL (как `TelegramVoicePreferenceStoreTest`).

Фаза 1 добавляет `2026-10-10-max-channel.sql`:
- последовательность `messenger_internal_chat_seq`;
- таблица `messenger_chat_bindings`.

Изменения только добавляющие; существующие таблицы не трогаются.

## 5. Конфигурация

| Переменная (compose AERIS → Spring) | По умолчанию | Назначение |
|---|---|---|
| `AERIS_MAX_ENABLED` → `ASTOR_MAX_ENABLED` | `false` | Главный флаг. При `false` бинов клиента, роутера и потока нет, вызовов MAX нет |
| `AERIS_MAX_BOT_TOKEN` → `MAX_BOT_TOKEN` | пусто | Токен с business.max.ru. Без токена при включённом флаге поток не стартует, в лог пишется предупреждение |
| `MAX_API_BASE_URL` | `https://platform-api2.max.ru` | Хост API, прямой доступ без прокси |
| `MAX_CA_CERT_PATH` | `${GIGACHAT_CA_CERT_PATH}` | PEM Russian Trusted Root CA. Неверный путь ломает только MAX, не приложение (ошибка в логе) |
| `MAX_POLL_TIMEOUT_SECONDS` | `30` | Ожидание long poll (1..90; API допускает 0, но это холостой цикл) |
| `MAX_POLL_LIMIT` | `100` | Обновлений за один опрос (1..1000) |
| `MAX_REQUEST_TIMEOUT_MS` | `10000` | Тайм-аут отправки, `/answers`, `/me` |
| `MAX_POLL_RETRY_DELAY_MS` | `5000` | Пауза после ошибки опроса (удваивается до 60 с) |
| `ASTOR_MAX_GROUP_CHATS_ENABLED` | `false` | Групповые чаты MAX (фаза 5) |
| `ASTOR_MAX_ADMIN_ALERTS_TO_TELEGRAM` | `true` | Алерты из MAX-диалогов отправляются в admin-чат Telegram |

Режим webhook (фаза 4) добавит:
- `ASTOR_MAX_UPDATES_MODE=polling|webhook`;
- `MAX_WEBHOOK_URL`;
- `MAX_WEBHOOK_SECRET`.

Секреты задаются только в `.env.production` на ВМ; в git, логах и PR их нет.

## 6. Фазы и задачи по файлам

### Фаза 1 — минимальный канал за флагом (эта ветка)

**Основной код** (`src/main/java/museon_online/astor_butler/`):
- `service/message/MessageChannel.java` — значение `MAX`.
- `service/message/IncomingMessage.java` — `IncomingMessage.max(...)` и `hasMessengerUser()`.
- `domain/messenger/` — `MessengerChatBinding`, `MessengerChatBindingRepository`: внутренний id, профиль, телефон, обратный поиск.
- `domain/consent/ConsentVaultService.java` — `grantPrivacyPolicyFromMaxContact` и `hasGrantedMaxPrivacyPolicy`.
- `fsm/scenario/FirstTouchScenario.java` — проверка согласия по каналу. Для Telegram вызовы прежние; MAX получает тот же шлюз согласия, а веб-посетитель в `UNKNOWN` по-прежнему не считается мессенджер-гостем.
- `max/MaxBotSettings`, `MaxBotConfiguration` (настройки — всегда), `MaxChannelConfiguration` (остальные бины — только при `astor.max.enabled=true`).
- `max/client/` — `MaxBotApiClient` (JDK `HttpClient`, `GET /me`, `GET /updates`, `POST /messages`, `POST /answers`, доверие к PEM Минцифры через `GigaChatTrust`), `MaxApiException`, `MaxUpdatesPage`, `MaxOutgoingMessage`, `MaxButton`.
- `max/adapter/` — `MaxLongPollingRunner` (`SmartLifecycle`, один daemon-поток, `/me` при старте, backoff до 60 с), `MaxRouter`, `MaxUpdateMapper`, `MaxInbound`, `MaxReplyRenderer`, `MaxCallbackPayload`, `MaxAdminAlertRelay`.

**Ресурсы и окружение:**
- `src/main/resources/application.yaml` — секция `astor.max`.
- `src/main/resources/db/changelog/2026-10-10-max-channel.{yaml,sql}` и строка в `changelog-master.yaml`.
- `docker-compose.yml` — переменные `ASTOR_MAX_ENABLED`, `MAX_BOT_TOKEN`, `MAX_API_BASE_URL`, `MAX_CA_CERT_PATH` только для сервиса AERIS.

**Тесты** (`src/test/java/museon_online/astor_butler/`):
- `max/client/MaxBotApiClientTest` — локальный stub-сервер `RecordingStubServer`, без сети: пути, query, заголовок без `Bearer`, JSON клавиатуры, ошибки без токена, CA bundle.
- `max/adapter/MaxUpdateMapperTest` — текст, `bot_started` с payload ланча, контакт свой и чужой, vCard с литеральными `\r\n`, голос с расшифровкой и без, callback, группы и боты.
- `max/adapter/MaxReplyRendererTest` — кнопка контакта, главное меню, ряды сценария, группы, тишина, разбивка длинного текста.
- `max/adapter/MaxRouterTest` — путь через gateway, дубли, группы и боты, контакт, callback, ошибка отправки, сбой gateway (гость получает то же «⚠️ Произошла ошибка», что в Telegram).
- `max/adapter/MaxLongPollingRunnerTest` — marker, пропуск сломанного update, `/me` не роняет старт, без токена поток не стартует, start/stop.
- `max/MaxChannelConfigurationTest` — по умолчанию бинов нет; включено без токена — не опрашивает; ограничения настроек.
- `domain/messenger/MessengerChatBindingRepositoryTest` — настоящая миграция на H2.
- `domain/consent/ConsentVaultServiceMaxTest`.
- `fsm/scenario/FirstTouchScenarioTest` — четыре новых теста MAX и веба.

### Фаза 2 — идентичность, исходящий порт, бронь до гостя, медиа

**Задачи:**
- Миграция `user_channel_identities` и перенос MAX-согласий; `IdentityService.identify(IncomingMessage)` для MAX; `TelegramIntakeService` → общий журнал входящих (или `max_messages`).
- `service/message/GuestOutboundPort` + `TelegramGuestSender` (обёртка) + `MaxGuestSender`.
- Перевод `TableReservationNotificationService`, `TableReservationPendingIntentService`, `GuestBillNotifier` на порт.
- `TableReservationCommand`: `channel` и `externalUserId` вместо одного `telegramUserId`. Это нужно и Concierge (раздел 11).
- Карточка хостес показывает канал гостя.
- Общий каталог гостевого меню (`GuestMenuCatalog`) для `TelegramRouter` и `MaxReplyRenderer`.
- `MaxMediaSender`: `POST /uploads` + загрузка + повтор при `attachment.not.ready`; фото превью, PDF плана зала, видео-тур.
- `PATCH /me/commands`.
- Проверка `hash` контакта: HMAC-SHA256(token, vcf_info), обе кодировки проверить на живом боте.

**Тесты:**
- stub-сервер для uploads (три шага, `attachment.not.ready`);
- порт: маршрут по диапазону id;
- карточка хостес → подтверждение доходит до MAX-гостя;
- H2-тест миграции идентичности.

### Фаза 3 — голос

**Задачи:**
- Скачивание `audio.payload.url`: проверить формат и нужна ли авторизация.
- Подключение к `SpeechToTextService` (Cloud.ru Whisper принимает разные форматы; SpeechKit v1 — только Ogg Opus).
- Голосовые ответы через `uploads?type=audio`, формат mp3/m4a (`YANDEX_TTS_FORMAT=mp3` или перекодирование).

**Тесты:** stub на скачивание и загрузку; выбор формата.

### Фаза 4 — webhook для продакшена

**Задачи:**
- `api/max/MaxWebhookController`: `POST /max/webhook`, проверка `X-Max-Bot-Api-Secret` постоянным по времени сравнением, ответ 200 сразу, обработка в фоне.
- Подписка и снятие подписки при старте и остановке.
- Режим `ASTOR_MAX_UPDATES_MODE`; при webhook polling выключен (вместе они не работают).

**Нужно на ВМ:** домен, порт 443, доверенный сертификат (см. `docs/operations/TELEGRAM_WEBHOOK_ROUTING_PLAN.md`).

**Тесты:** MockMvc на секрет и идемпотентность.

### Фаза 5 — персонал и ops в MAX (если решим)

**Задачи:**
- Групповые чаты: бот-администратор с `read_all_messages` и `write`, приватность на business.max.ru.
- Сбор `chat_id` из `bot_added` и `message_created`, удаление при `bot_removed`.
- Хостес-карточка с callback-кнопками в MAX.
- Конфиг admin, ops и hostess-чатов по каналам.

### Фаза 6 — Mini App и лента

**Задачи:**
- Лента (`frontend/astor-butler/astor_concierge/feed/`) подключает `max-web-app.js`.
- Кнопка `open_app`.
- Серверная проверка `initData` по схеме из раздела 2 (общий код с Telegram, другой параметр фрагмента).

## 7. Выкатка и откат

**Выкатка:**
1. Влить PR с выключенным флагом. Liquibase создаст пустую `messenger_chat_bindings` и последовательность — других изменений поведения нет. Telegram, web и Concierge работают как раньше.
2. Зарегистрировать бота на business.max.ru (раздел 10), дождаться модерации и получить токен.
3. На ВМ в `.env.production` прописать `AERIS_MAX_ENABLED=true`, `AERIS_MAX_BOT_TOKEN=…` и проверить `GIGACHAT_CA_CERT_PATH` / `MAX_CA_CERT_PATH` → `/app/certs/russian_trusted_root_ca.pem`. Перезапустить только контейнер AERIS (scoped deploy).
4. Smoke по логам: `MAX bot connected: userId=…, username=id…_bot`, затем `MAX long polling started`.
5. Ручной прогон в MAX:
   - «Начать» → согласие → кнопка контакта → главное меню;
   - «Меню кухни», «Бронь стола», свободный вопрос (ответ модели);
   - ссылка `https://max.ru/<bot>?start=lunch_aeris_p2` на **новом** аккаунте;
   - голосовое (ожидается расшифровка от MAX или просьба написать текстом).
6. Проверить, что бронь из MAX-диалога создаётся и что хостес её видит в Telegram. Подтверждение гостю в MAX придёт только после фазы 2 — до неё бронирование в MAX не анонсировать.

**Откат:**
- `AERIS_MAX_ENABLED=false` и перезапуск AERIS: поток и бины исчезают.
- Таблица остаётся, но не используется. Её можно удалить вручную (`DROP TABLE messenger_chat_bindings; DROP SEQUENCE messenger_internal_chat_seq;`) после выгрузки, если нужно.
- В ней персональные данные: имя, username, телефон. Хранение и удаление — по той же политике, что `telegram_profiles`.
- MAX-согласия лежат в `user_consents` с `source=MAX_CONTACT_FLOW`.

## 8. Тест-план фазы 1

Офлайн-набор `mvn -o -B test`: все тесты зелёные, сеть не нужна. Новые тесты перечислены в разделе 6; HTTP идёт через локальный `RecordingStubServer`, БД — H2 с настоящей миграцией.

Ручной прогон возможен только с токеном (раздел 7, п. 5).

## 9. Риски

- **Long polling не рекомендован для продакшена:** скорость и срок хранения событий ограничены. Для пилота достаточно; для продакшена нужна фаза 4.
- **Deep link срабатывает один раз.** По community-данным, если гость уже открывал бота, `?start=` открывает чат без `bot_started`, и payload (ланч от Concierge) теряется. Решение: Butler уже понимает текст `/start lunch_…`, набранный руками; Concierge может дать кнопку `clipboard` с этим текстом рядом со ссылкой (раздел 11). Проверить на живом боте.
- **Неоднозначности документации:**
  - `marker` при первом опросе;
  - `notification` в `/answers`;
  - длина payload (128 или 512);
  - кодировка `hash` контакта;
  - формат входящего аудио.

  Код рассчитан на консервативный вариант; всё проверить на живом боте.
- **Знак `chat_id` не документирован.** Группа определяется только по `chat_type`; внутренний id не зависит от знака.
- **Правила MAX §1.5:** транзакционные и сервисные сообщения и рассылки без договора с MAX запрещены. Ответы в диалоге — основная функция бота, но проактивные уведомления (подтверждение брони, счёт, отзыв после визита) могут попасть под ограничение. Нужна юридическая проверка до фазы 2.
- **Подтверждения брони в фазе 1 до MAX-гостя не доходят.** Отправка в Telegram по внутреннему id завершается ошибкой; она безопасна и пишется в лог. Бронирование в MAX не анонсировать до фазы 2.
- **Строки `user_consents` без `telegram_user_id` попадают в CDC-публикацию** (`2026-06-05-scope-outbox-publication.sql`). Колонка и раньше была nullable, но потребителей CDC стоит проверить.
- **Подписи главного меню продублированы** (Telegram и MAX) до фазы 2.
- **Лимит 2 сообщения/с на чат:** длинный ответ отправляется с паузами; на ошибке 429 остаток не отправляется (лог).
- **Сертификат Минцифры.** Если PEM не смонтирован, MAX не работает: в логе ошибка TLS при `/me`, приложение при этом работает.

## 10. Вопросы к Михаилу

1. **Кто и на какое юрлицо регистрирует бота на business.max.ru?** Нужен верифицированный профиль организации или ИП — резидента РФ; проходит владелец или уполномоченный подписант через Госуслуги, T-Business ID или Alfa ID. Ник `id<ИНН>_bot` неизменяем, бот не передаётся другому владельцу, поэтому юрлицо определяет бренд ссылки навсегда. Варианты: юрлицо оператора AERIS или юрлицо Astor.
2. Один бот на заведение (AERIS) или один общий Astor Butler? Квота — 5 ботов на организацию; Concierge — отдельный бот того же или другого юрлица?
3. Договор с MAX на сервисные уведомления (подтверждение брони, счёт, отзыв): нужен ли? Без него — только ответы в диалоге.
4. Политика обработки ПДн и сведения об операторе обязательны для MAX. Текущая ссылка — `michaelwelly.github.io/.../policy.html`; подходит ли она для модерации MAX?
5. Персонал остаётся в Telegram или переходит в MAX (фаза 5)?
6. Домен для webhook (фаза 4): `c3ag.online` или отдельный, с портом 443 и доверенным сертификатом.
7. На ВМ Cloud.ru у контейнера AERIS действительно задан `GIGACHAT_CA_CERT_PATH=/app/certs/russian_trusted_root_ca.pem`? Иначе нужен `MAX_CA_CERT_PATH`.

## 11. Astor Concierge в MAX

Concierge — отдельный репозиторий (`astor-hospitality/Astor_Concierge`): TypeScript, Node 22, grammY, long polling, SQLite. Код Concierge этим PR не меняется. Сверено по `README.md`, `docs/architecture/CONCIERGE_BUTLER.md`, ADR-002, `src/telegram/bot.ts`, `src/booking/*`, `src/catalog/catalog.ts`, `src/butler/client.ts` в `origin/main`.

### 11.1 Concierge: Telegram → MAX → разрыв

| Функция Concierge (Telegram) | Эквивалент в MAX | Разрыв / решение |
|---|---|---|
| Одна рекомендация на запрос (каталог `data/venues.json`): текст + кнопки `action`, `url`, `webapp` | Текст + `callback`, `link`, `open_app` | Прямое соответствие. Типы `Reply` и `Button` в `src/booking/types.ts` уже канально-нейтральны |
| Команды `/book /lunch /status /cancel /help /forget` + `setMyCommands` | Команды приходят текстом; `PATCH /me/commands` | В адаптере распознавать `/команда` в тексте |
| `/start` | `bot_started` | Отобразить в `{kind:'start'}` |
| Согласие: inline «да/нет» + запрос телефона (reply-клавиатура `requestContact`) | `callback` + inline `request_contact` | Телефон из `vcf_info`. Признак `own`: `max_info.user_id === sender.user_id` или наличие `hash` (у Telegram — `contact.user_id === from.id`) |
| Callback: `answerCallbackQuery` + снять клавиатуру нажатого сообщения | `POST /answers` (можно сразу заменить сообщение через `message`) или `PUT /messages` с `attachments: []` | Защита от двойного нажатия сохраняется |
| Заявка на стол в Butler: `POST /api/bookings/table-reservations` с `chatId` и `telegramUserId` из бота Concierge | — | **Нужна доработка API Butler:** поля `channel` и `externalUserId` (фаза 2 Butler). Иначе id MAX смешаются с id Telegram в `table_reservations` |
| Подтверждение или отказ хостес: poller → `notify(chatId, reply)`; `UndeliverableError` при 403 или «chat not found» | `POST /messages?chat_id=`; 403 `chat.denied`; событие `bot_stopped` | `Notify` должен знать канал гостя; `UndeliverableError` — на 403 и после `bot_stopped` |
| Handoff бизнес-ланча: `t.me/<бот заведения>?start=lunch_…` (≤ 64 символа `[A-Za-z0-9_-]`) | `https://max.ru/<ник бота Butler в MAX>?start=lunch_…` (≤ 128) | Формат payload подходит без изменений; Butler в фазе 1 превращает его в `/start lunch_…`. Нужен второй URL бота заведения для MAX в каталоге. Риск однократного `bot_started` — 11.3 |
| Mini App «Лента заведений» (`FEED_URL`, кнопка `webapp`) | Mini App при боте: один URL на бота в настройках business.max.ru; кнопка `open_app` с `web_app` = ник бота | Лента подключает `https://st.max.ru/js/max-web-app.js`. Если лента использует данные пользователя — серверная проверка `initData` (в SDK её нет). Кнопка меню задаётся только на портале, не через API |
| Только личные чаты | `recipient.chat_type === 'dialog'` | Прямое соответствие |
| Выход в Telegram через `TELEGRAM_PROXY_URL` | MAX напрямую | Прокси не нужен, нужен корневой сертификат Минцифры: `NODE_EXTRA_CA_CERTS=/path/russian_trusted_root_ca.pem` |
| Нетекстовые сообщения → «Пока я понимаю только текст» | Вложения `image`, `audio` и др. | То же поведение; у `audio` можно использовать `transcription` от MAX |

### 11.2 SDK или свой клиент

**Проверено:**
- **Официальный Node SDK есть:** `@maxhub/max-bot-api`, репозиторий https://github.com/max-messenger/max-bot-api-client-ts, лицензия MIT.
  - Версия 1.0.2 от 08.10.2026, около 9 тыс. скачиваний в неделю.
  - Стиль Telegraf: `bot.on/command/action`, `ctx.reply`.
  - Умеет long polling и webhook (проверка `x-max-bot-api-secret`), клавиатуры, загрузки с повтором `attachment.not.ready`, `ctx.startPayload`.
  - Проверки `initData` Mini App нет.
  - В 1.0.1 были ломающие изменения (удалён `getAllChats`).
  - dev.max.ru называет его официальной библиотекой: https://dev.max.ru/docs/chatbots/bots-coding/js.
- **grammY поддержки MAX не имеет:** ни плагина, ни адаптера, ни заявлений; у Telegraf тоже. Код `grammy` переиспользовать нельзя.

**Рекомендация:** свой тонкий клиент `src/max/` на `fetch` Node 22 (примерно 200–300 строк).

Почему:
- Concierge нужен небольшой набор: `GET /updates`, `POST /messages`, `POST /answers`, `PATCH /me/commands` и кнопки `callback`, `link`, `request_contact`, `open_app`.
- ADR-002 держит одну runtime-зависимость, а SDK добавляет `debug`, `form-data`, `vcf` и активно меняется (ломающие изменения за последнюю неделю).
- Такой клиент повторяет подход Butler (раздел 4) и легко подменяется фейком в тестах, как `test/fake-telegram.ts`.

Когда перейти на `@maxhub/max-bot-api`: если понадобятся загрузки медиа или webhook-сервер. Версию тогда закрепить точно.

### 11.3 Сквозные ссылки Concierge → Butler внутри MAX

- **Формат ссылки:** `https://max.ru/<ник бота Butler в MAX>?start=lunch_<venue>_p<N>_d<YYYYMMDD>_t<HHMM>_r<ref>`. Ник вида `id<ИНН>_bot` известен только после регистрации (раздел 10).
- **Payload:** нынешние ≤ 64 символа `[A-Za-z0-9_-]` укладываются в лимит MAX в 128 символов. Набор символов MAX не документирует, поэтому оставить текущий.
- **Доставка:** Butler (фаза 1) получает `bot_started.payload` и передаёт в FSM `/start lunch_…`; `BusinessLunchHandoff` без изменений.
- **Риск однократного `bot_started`.** Если гость уже писал боту заведения в MAX, `bot_started` с новым payload может не прийти. Варианты:
  - рядом со ссылкой кнопка `clipboard` с текстом `/start lunch_…` и подсказкой «если бот не открыл ланч, вставьте и отправьте» — Butler поймёт этот текст;
  - в будущем — `startapp` для Mini App.

  Проверить на живых ботах до показа гостям.
- **Смешанные каналы.** Гость Concierge в MAX должен получать ссылку на MAX-бота заведения, гость в Telegram — на Telegram-бота. Каталогу нужен URL бота на каждый канал.

### 11.4 Задачи для Concierge по файлам (будущий PR в Astor_Concierge)

**Код:**
- `src/config.ts` — `MAX_ENABLED`, `MAX_BOT_TOKEN`, `MAX_API_BASE_URL` (по умолчанию `https://platform-api2.max.ru`), `MAX_FEED_BOT`; в `.env.example` и `docs/operations/CREDENTIALS.md` — без значений.
- `src/max/client.ts` — `fetch`-клиент: `getUpdates(marker, timeout, types)`, `sendMessage(chatId, body)`, `answerCallback(id, body)`, `setCommands`. Заголовок `Authorization` без `Bearer`; ошибки `{code, message}`.
- `src/max/bot.ts` — адаптер:
  - вход: `message_created`, `bot_started`, `message_callback` → `GuestInput`, только `dialog`;
  - выход: `Reply` → `NewMessageBody` (`action`→`callback`, `url`→`link`, `webapp`→`open_app`, `contactRequest`→`request_contact`, текст ≤ 4000);
  - `notify` для poller.
- `src/booking/types.ts` — `Guest { channel: 'telegram' | 'max'; userId: number; chatId: number; firstName? }`; `Notify(guest | {channel, chatId}, reply)`.
- `src/booking/store.ts` — схема v2:
  - `guests(channel, user_id)` как ключ;
  - `requests.channel`;
  - миграция из `telegram_user_id` с `channel='telegram'`.
- `src/booking/submit.ts`, `src/butler/client.ts` — передавать `channel` и `externalUserId`, когда Butler их примет (фаза 2 Butler). До этого MAX-заявки в Butler не отправлять, а передавать вопрос сотруднику.
- `src/booking/lunch.ts`, `src/catalog/catalog.ts`, `data/venues.json` — `venueBotUrl` по каналам (`venueBotUrlMax`); выбор по каналу гостя; кнопка `clipboard` как запасной путь (11.3).
- `src/booking/poller.ts` — уведомления через реестр каналов; `UndeliverableError` для 403 MAX.
- `src/app.ts`, `src/main.ts` — запуск Telegram и/или MAX по флагам; общий `flow`.

**Тесты:**
- `test/fake-max.ts` (stub API MAX) и `test/max.test.ts`: маппинг, кнопки, контакт `own`, команды, `bot_started` с payload;
- `test/lunch.test.ts` — ссылка MAX;
- `test/telegram.test.ts` должен остаться зелёным без изменений.

**Документы:**
- `docs/architecture/ADR-003-MAX-CHANNEL.md`;
- в `CONCIERGE_BUTLER.md` — поля `channel` и `externalUserId` в контракте.

**Mini App:**
- лента подключает `max-web-app.js`;
- URL ленты задаётся в настройках бота Concierge на business.max.ru;
- если лента читает пользователя — серверная проверка `initData` (HMAC `WebAppData`, `auth_date` ≤ 1 ч, сравнение за постоянное время).

## 12. Источники

- Документация Bot API: https://dev.max.ru/docs-api (методы `GET /updates`, `POST /messages`, `POST /answers`, `POST /uploads`, `POST /subscriptions`; объекты `Update`, `Message`, `NewMessageBody`)
- Changelog (хост `platform-api2`, удалённые методы, webhook): https://dev.max.ru/docs-api/changelog-api
- Подготовка бота, права в группах, сертификат: https://dev.max.ru/docs/chatbots/bots-coding/prepare
- Клавиатуры и контакт: https://dev.max.ru/docs-api/use-cases/sending-messages/keyboard
- Форматирование: https://dev.max.ru/docs-api/use-cases/sending-messages/text-formatting
- Deep links: https://dev.max.ru/help/deeplinks
- Регистрация и верификация: https://dev.max.ru/docs/maxbusiness/connection, https://dev.max.ru/docs/chatbots/bots-create/create, https://dev.max.ru/docs/legal/requirements
- Mini Apps: https://dev.max.ru/docs/webapps/introduction, https://dev.max.ru/docs/webapps/bridge, https://dev.max.ru/docs/webapps/validation
- OpenAPI: https://github.com/max-messenger/api-schema
- Официальные клиенты: https://github.com/max-messenger/max-bot-api-client-ts (npm `@maxhub/max-bot-api`), https://github.com/max-messenger/max-bot-api-client-go
- Community-наблюдение про однократный `bot_started`: https://github.com/Dyce-a/max-support-bot
