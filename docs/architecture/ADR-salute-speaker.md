# ADR: Astor Butler на умных колонках Сбера (SberBoom / ассистенты Салют)

Дата: 2026-10-08
Статус: Proposed (research + spike plan, кода нет)
Ветка: `docs/salute-speaker-adr` (от `infra/cloudru-deploy`)
Связанные документы: `docs/architecture/ARCHITECTURE.md` (Message Gateway, transport adapters), `docs/operations/SBER_AI_ACTIVATION.md` (GigaChat через Cloud.ru, SaluteSpeech TTS), `docs/operations/CLOUDRU_DEPLOY_RUNBOOK.md` (Caddy → `astor-api-gateway` → AERIS на `api.c3ag.ru`), `docs/operations/TELEGRAM_WEBHOOK_ROUTING_PLAN.md` (похожий входящий webhook).

Пометки достоверности в тексте: **[факт]** — подтверждено официальной документацией developers.sber.ru (ссылка в разделе «Источники»); **[допущение]** — не нашли в документации, нужно проверить на живом Studio/колонке или спросить `developer@sberdevices.ru`.

## 1. Контекст

Гость сидит за столом, на столе колонка SberBoom (или SberBoom Mini). Он говорит: «Салют, запусти Астор Батлер» и дальше — «что сегодня из супов», «позови официанта», «принесите счёт». Официант у стойки может через ту же колонку спросить «какие брони на 19:00». Ответ должен произнести сам ассистент колонки, а логика, знания о заведении и действия должны быть теми же, что у Telegram-бота AERIS и веб-чата CLIO: FSM — источник истины, GigaChat (через Cloud.ru Foundation Models) — понимание и черновики ответов, SaluteSpeech — уже подключённый синтез речи для очков и веб-чата.

Что уже есть в репозитории и что мы переиспользуем:

| Компонент | Где | Как участвует |
| --- | --- | --- |
| Единая точка входа для не-Telegram каналов `POST /api/messages` (`MessageController` → `MessageGatewayService.handle(IncomingMessage)` → `OutgoingMessage`) | `api/message`, `service/message` | Колонка — ещё один transport adapter; бизнес-логика не ветвится под канал (см. ARCHITECTURE.md, «transport-модель не привязана к Telegram»). |
| `MessageChannel { TELEGRAM, WEB, INTERNAL }` | `service/message/MessageChannel.java` | Добавится `SALUTE` (в спайке — можно временно идти как `WEB` с `payload.source=salute`). |
| Edge: Caddy (Vedal) → `astor-api-gateway` (nginx, `location /api/` с rate-limit 10 r/s, `client_max_body_size 20m`, `proxy_read_timeout 30s`) → AERIS `:8089` | `infra/cloudru/edge/c3ag.caddy`, `docker/nginx/nginx.conf.template` | Публичный HTTPS-хост `api.c3ag.ru` с валидным сертификатом — ровно то, что требует Studio для вебхука. |
| `ModelGateway` (`ASTOR_MODEL_PROVIDER=cloudru`, GigaChat-2-Max) | `model/CloudRuModelGateway.java` | Тот же провайдер отвечает и колонке. Таймаут `CLOUDRU_*`/`GIGACHAT_TIMEOUT_MS=15000` сейчас больше бюджета колонки (см. §3.5). |
| `TextToSpeech` + `SaluteSpeechTextToSpeech` (голоса `Nec_24000`, `Bys_24000`, …) | `speech/` | Для колонки **не нужен**: текст из `pronounceText` озвучивает ассистент устройства своим голосом. Нужен только там, где мы отдаём аудио сами (очки, веб-чат, будущие голосовые в Telegram). |
| Telegram-каналы персонала (`TELEGRAM_HOSTESS_CHAT_ID`, `TELEGRAM_ADMIN_CHAT_ID`) и `WebLeadNotificationService` | `telegram/`, `api/message` | Fallback: всё, что не укладывается в бюджет ответа колонки, уходит задачей/сводкой персоналу. |

В репозитории нет упоминаний `smartapp`/`SmartMarket`/`SberBoom`; слово «Salute» встречается только в контексте SaluteSpeech TTS. Этот ADR — первый документ про канал «колонка».

## 2. Что мы выяснили о платформе (research)

### 2.1 Типы смартапов и что реально работает на колонке

- Три типа: **Chat App** (диалоговый, без экрана), **Canvas App** (web-интерфейс в WebView + голос), **Native App** (Android APK для устройств с экраном). **[факт]**
- На **SberBoom и SberBoom Mini работает только Chat App**. Canvas App — SberBox, SberBox Time, Салют ТВ, мобильное приложение; Native App — только устройства с экраном. **[факт]**
- Сценарий Chat App задаётся в Studio одним из способов: **Graph** (визуальный конструктор), **Code** (SmartApp DSL / JS), **SmartApp API** — наш внешний HTTPS-сервер (webhook). **[факт]**
- Устройства/поверхности, на которых может быть доступен смартап: Салют ТВ, SberBox, SberBoom, SberBoom Mini, SberBox Time, Huawei Vision; приложения — Салют, СберБанк Онлайн, Звук, sberbank.ru. Доступные поверхности зависят от типа и условий запуска. **[факт]**

Вывод: для стола в ресторане у нас один путь — **Chat App + SmartApp API (webhook)**. Никакого UI на колонке нет, есть только голос; на телефоне в приложении Салют тот же смартап покажет текстовые «бабблы» и кнопки-подсказки.

### 2.2 Как приходит реплика (SmartApp API, запросы)

Ассистент шлёт `POST` JSON на наш вебхук. Типы сообщений: `RUN_APP` (запуск), `MESSAGE_TO_SKILL` (реплика пользователя), `SERVER_ACTION` (нажатие кнопки/карточки), `CLOSE_APP` (ответа не ждёт). **[факт]**

Поля `MESSAGE_TO_SKILL`, на которые мы опираемся **[факт]**:

| Поле | Что это |
| --- | --- |
| `messageName` | `MESSAGE_TO_SKILL` |
| `sessionId` (≤36 символов), `messageId` (int32) | Сессия диалога и номер сообщения; `messageId` надо вернуть тем же в ответе |
| `uuid.userId` (обяз., ≤64), `uuid.sub` (≤256), `uuid.userChannel` (обяз., ≤64) | Идентификаторы пользователя/канала. В примерах документации `userChannel: "B2C"`. `uuid.sub` используется Сбером как «идентификатор пользователя» в SmartPay (`$request.rawRequest.uuid.sub`) |
| `payload.message.original_text` | Распознанный текст как есть |
| `payload.message.normalized_text`, `payload.message.asr_normalized_message` | Нормализованные варианты (числа, пунктуация) |
| `payload.character.id` = `sber` / `athena` / `joy`, `.name` = «Сбер» / «Афина» / «Джой», `.gender`, `.appeal` = `official` / `no_official` | Выбранный на устройстве ассистент — от него зависит «вы/ты» в нашем ответе |
| `payload.device.surface` = `SBERBOOM`, `SBERBOOM_MINI`, `SBERBOX`, `COMPANION` (приложение), `SBOL` … ; `payload.device.devicesId`; `payload.device.capabilities.screen.available` | Поверхность и устройство. Поле называется именно `devicesId` (не `deviceId`) |
| `payload.app_info.projectId`, `applicationId`, `appversionId`, `frontendType = CHAT_APP` | Наш смартап; `projectId` — опорный allow-list |
| `payload.new_session` | `true` при новой сессии; сессия истекает после 10 минут бездействия |
| `payload.intent`, `payload.annotations.censor_data` / `text_sentiment` | Интент платформы и разметка (цензура, тональность) |
| `payload.meta.time.timestamp`, `timezone_id` | Время на устройстве |

Что **не** документировано **[допущение]**: стабильность `uuid.sub` / `uuid.userId` между сессиями и устройствами (для SmartPay `sub` используется как идентификатор пользователя — значит, по крайней мере в рамках одного Сбер ID он постоянен); стабильность `devicesId` и его уникальность на устройство (выглядит как идентификатор устройства, но описания нет). Это первое, что проверяем в спайке на живой колонке.

### 2.3 Как отвечаем (SmartApp API, ответы)

`ANSWER_TO_USER` **[факт]**:

- шапка: те же `sessionId`, `messageId`, `uuid`, `messageName: "ANSWER_TO_USER"`;
- `payload.pronounceText` — то, что ассистент произнесёт; `payload.pronounceTextType` = `application/text` или SSML;
- `payload.items[]` — `bubble` (текст на экране), `card` (карточка), `command` (например, `close_app`). На колонке без экрана не показываются, но нужны для приложения Салют/телефона;
- `payload.suggestions.buttons[]` — кнопки-подсказки с `title` и `actions[]` (`text` — как будто пользователь сказал; `server_action` — произвольное сообщение бэкенду; `deep_link` — только из приложения);
- `payload.auto_listening` — слушать ли пользователя сразу после ответа (нужно ставить в **каждом** ответе, по умолчанию включено; на SberBoom работает, если сессия начата голосом);
- `payload.finished: true` — завершить диалог; без поля считается `false`;
- `payload.emotion.emotionId`, `payload.asr_hints` (подсказки ASR: слова, которые надо распознавать), `payload.intent`;
- `payload.device` с обязательными `surface` и `capabilities` — проще всего вернуть объект из запроса.

Другие ответы: `NOTHING_FOUND` (не поняли), `ERROR` (`code`, `description` ≤30 символов — ассистент сам извинится), `POLICY_RUN_APP` (запуск другого смартапа, согласуется на модерации). **[факт]**

Ограничения формата: в ответе нельзя передавать `null`, переносы строк внутри JSON-ответа запрещены. **[факт]**

### 2.4 Бюджет времени: 7 секунд, не 10

«Ассистент ожидает ответа от вебхука в течение семи секунд» — повторяется на трёх страницах SmartApp API. **[факт]** Страницы датированы декабрём 2023, поэтому цифру перепроверяем на колонке (засекаем, когда ассистент говорит «что-то пошло не так»). 10 секунд из задания — это таймаут классификатора в блоке событий Graph, к вебхуку он не относится. **[факт]**

Что это значит для нас: Caddy → nginx → AERIS добавляют миллисекунды, но вызов GigaChat-2-Max через Cloud.ru с `timeout 15000` и `max-tokens 256` в этот бюджет не вписывается гарантированно. Нужен внутренний бюджет ≈ 5 с на всё (см. §4, решение «быстрый путь»).

### 2.5 Активация, запуск, выход

- Имя смартапа — до 50 символов; активационных имён — до 5 (по умолчанию = имя). Команда запуска в документации: «Включи [активационное имя]»; в кейсах Сбера — «Салют, запусти госуслуги». Бренд в названии допустим, если мы его представляем и можем подтвердить (для «Астор» — см. §7 про товарный знак). **[факт]**
- Команды «назад»/«домой» — системные, работают на устройствах; команду выхода («хватит», «выход») смартап задаёт сам и должен озвучивать в помощи. Закрыть окно — команда `close_app` в `items`. **[факт]**
- Пока смартап запущен, реплики пользователя идут в наш вебхук (монопольный режим смартапа; глобальные интенты ассистента — «стоп», «назад», громкость — обрабатывает сам ассистент). **[факт]** для устройств с экраном; для колонки точный список перехватываемых фраз — **[допущение]**, проверяем.

### 2.6 Тестирование без публикации

- **Эмулятор в Studio**: вкладка «Тестирование» в карточке смартапа, версия «Черновик» или «Опубликованная»; эмулирует SberBox и мобильное приложение, вход текстом или голосом с микрофона браузера, в логах — запросы/ответы в формате SmartApp API с `message_id` для поддержки. **Колонку эмулятор не эмулирует** (surface будет `SBERBOX`/`COMPANION`). **[факт]**
- **Реальные устройства**: SberBox, SberBox Time, SberBoom, Салют ТВ, приложение Салют. Условие: **на устройстве авторизован тот же Сбер ID, что в Studio** (владелец пространства); коллеги тестируют после приглашения в пространство. Публикация для теста не нужна. **[факт]**
- Для командного теста черновика через Studio у тестировщиков должны быть аккаунты Studio. **[факт]**

### 2.7 Модерация, публикация, юрлицо

- Модерация обязательна перед каталогом; рабочие дни 9:00–18:00 МСК, до 3 рабочих дней; ручная проверка на устройствах; после одобрения — кнопка «Опубликовать», в каталоге через 5–10 минут. **[факт]**
- Юрлицо/ИП: **верификация компании** в корпоративном пространстве (ИНН/ОГРН, выписка ЕГРЮЛ/ЕГРИП не старше 5 дней, заверенная копия свидетельства с печатью и «Копия верна») — документы на `developer@sberdevices.ru` в течение 10 дней после заявки; **заявление владельца смартапа** (скан + doc) на **каждый** смартап; доверенность, если подписывает не ген. директор; свидетельство на ТЗ или разрешение правообладателя, если бренд в названии; политика конфиденциальности; **соглашение об обработке персональных данных** (форму присылают по запросу). **[факт]**
- Персональные данные пользователей **физлицам не передаются**; если нужна обработка ПД — писать `developer@sberdevices.ru`. **[факт]**
- SmartPush (пуши пользователю), SmartProfile (телефон/адрес с согласия), SmartPay — только верифицированному юрлицу и только после модерации. **[факт]**
- Платёжный спайк на колонке через SmartPay здесь не рассматриваем: у Astor уже есть Saby/биллинг (см. `docs/integrations/`).

### 2.8 «Приватный» смартап для одного заведения (B2B)

- Документированного режима «приватный/корпоративный смартап для своих устройств» на SmartMarket **нет**. Есть ровно два состояния: черновик (работает на устройствах под Сбер ID владельца пространства и приглашённых участников) и опубликован (каталог, любой пользователь). **[факт]** по документации; что приглашение в пространство достаточно для колонки, залогиненной под аккаунтом заведения, — **[допущение]**, проверяем в спайке.
- Для пилота на 1–3 колонках **черновик достаточен**: колонки заведения логинятся под Сбер ID пространства Astor (или под Сбер ID заведения, приглашённым в пространство). Для тиража на N заведений нужна публикация — и тогда смартап найдёт любой пользователь с любой колонки, а привязка к столу должна быть явной (§4.2).
- Существующий B2B-продукт Сбера для HoReCa — платформа **«Умный отель Сбер» / «Салют Отель»** (SberBoom в номере, ИИ-консьерж, кабинет отельера; кейсы Cosmos Hotel Group 06.2026, Hotel Continental 09.2026, порог «от 30 номеров»). Это закрытая платформа по отдельному контракту с SberDevices, а не SmartMarket; ресторанный кейс 2021 («Салют, хочу есть» в Кофемании) был на SberPortal — экранном устройстве, которого в текущем списке поверхностей Studio уже нет. **[факт]** по публикациям; условия подключения и наличие API для сторонней логики — **[допущение]**, нужен запрос в SberDevices.
- Колонка под аккаунтом заведения — обычный розничный SberBoom с обычным Сбер ID; «корпоративного» Сбер ID для устройств в документации нет. **[допущение]**

### 2.9 Голоса

- Через SaluteSpeech API голоса ассистентов Салют (Сбер, Афина, Джой) **не предоставляются**; каталог API — Наталья `Nec`, Борис `Bys`, Марфа `May`, Тарас `Tur`, Александра `Ost`, Сергей `Pon`, Kira `Kin`; брендированный голос — отдельный продукт YourVoice. **[факт]**
- Следствие: на колонке Астор говорит голосом ассистента, выбранного гостем (Сбер/Афина/Джой), в очках и веб-чате — голосом SaluteSpeech (`Bys`/`Nec`). Один «голос бренда» на всех каналах даст только YourVoice (платно, отдельный контракт) — и даже он не заменит голос ассистента на колонке. Принимаем расхождение как данность и подстраиваем **текст** под `character.appeal` (официально/неофициально), а не голос.

## 3. Варианты

### 3.1 Транспорт: где живёт вебхук

| Вариант | Суть | За | Против |
| --- | --- | --- | --- |
| **A. Адаптер в монолите AERIS, `POST /api/salute/webhook` за существующим api-gateway** | Новый пакет `api/salute` рядом с `api/message` и `api/glasses`; маппинг SmartApp API ↔ `IncomingMessage`/`OutgoingMessage`; вызов `MessageGatewayService.handle` напрямую (без HTTP-хопа через `/api/messages`) | Нулевая инфраструктура: хост, TLS, rate-limit, деплой уже есть; доступ к FSM, Redis-сессиям, контексту заведения, Telegram-уведомлениям в процессе; один бюджет времени без лишних сетевых хопов; тесты в том же `src/test` (как `ChatSpeechControllerTest`) | Монолит растёт ещё на один канал; любой сбой AERIS = тишина на колонке (но то же верно и для Telegram/веб-чата) |
| **B. Отдельный сервис `salute-adapter` (контейнер в сети `edge`, свой хост `salute.c3ag.ru`), который зовёт `POST /api/messages`** | Тонкий переводчик протокола вне монолита | Изоляция: протокол Сбера не трогает ядро; можно писать на чём угодно (Python SmartApp Framework); отдельный rate-limit/бюджет | Второй деплой-юнит на той же ВМ (16 GB, память уже расписана); +1 сетевой хоп и сериализация внутри 7-секундного бюджета; дублирование логики форматирования ответов; нужен ещё один Caddy-блок у Vedal; `/api/messages` сейчас заточен под WEB (rate-limiter по IP, fast-path) — пришлось бы расширять его всё равно |
| **C. Сценарий в Studio (Graph/Code) с webhook-вызовами наружу** | Логика диалога в Studio, наш бэкенд — «функция» | Быстрый старт без кода | Второй источник истины для диалога (противоречит «FSM — source of truth»); логика заведения уедет в кабинет Сбера; Graph-вебхуки — другой контракт, чем SmartApp API |
| **D. Canvas / Native App** | — | — | На колонках не работают **[факт]** |
| **E. Своё устройство (микрофон + SaluteSpeech STT/TTS)** | Обойти ассистента | Полный контроль, свой голос | Это другой проект (hardware, wake-word, акустика); не использует колонку, которую просили |

### 3.2 Привязка сессии колонки к заведению и столу

| Вариант | Суть | Оценка |
| --- | --- | --- |
| **M1. Реестр устройств: `devicesId` (+ `uuid.sub`) → `venue_id`, `table_id`** | Таблица `salute_devices`; первая привязка — режим регистрации: персонал говорит «Астор, зарегистрируй стол пять» (или вводит код из Staff Portal), адаптер пишет `devicesId` → стол. Дальше любая реплика с этого `devicesId` = этот стол | Основной. Зависит от стабильности `devicesId` **[допущение]**; если нестабилен — ключ `uuid.sub` + `surface` (один Сбер ID на колонку) |
| **M2. Один Сбер ID на колонку, `uuid.sub` → стол** | Каждую колонку логиним под отдельным Сбер ID «стол 5 @ заведение» | Работает без `devicesId`; но N Сбер ID на заведение — операционно тяжело, и тест черновика требует приглашения каждого в пространство |
| **M3. Гость сам называет стол** («я за пятым столом») | Без реестра | Ошибки распознавания, лишний шаг, гость не обязан знать номер; оставить как fallback-вопрос, если устройство не зарегистрировано |
| **M4. Гость связывает стол через QR на столе → Telegram** | QR ведёт в AERIS с `table_id`; колонка — только голосовой вход, личное (счёт, бронь на имя) — в Telegram | Дополняет M1: даёт гостю персональный канал для fallback-сводки (§3.4) и не требует ПД от Сбера |

### 3.3 Аутентификация вебхука

Платформа **не документирует** ни подпись, ни токен, ни заголовок для вебхука SmartApp API; в Studio задаётся только URL. **[факт]** (в отличие от умного дома, где в вебхук приходит OAuth2 access-токен — другой продукт). Варианты:

| Вариант | Оценка |
| --- | --- |
| **S1. Секрет в пути: `/api/salute/webhook/{token}`** (32+ байт из `ASTOR_SALUTE_WEBHOOK_TOKEN`), сравнение в константное время | Единственный доступный нам «секрет», как `secret_token` у Telegram. Риск: Studio говорит «только доменное имя с протоколом https» для «Внешней ссылки» — примеры сообщества используют URL с путём, но подтверждаем в Studio **[допущение]**. Если путь не принимается — выделенный хост `salute.c3ag.ru` (Caddy-блок → тот же `astor-api-gateway`) и секрет в субдомене невозможен → остаётся S2+S3 |
| **S2. Allow-list по `payload.app_info.projectId` / `applicationId`** (значения из Studio в env) | Обязательно всегда; но это не секрет (виден в логах эмулятора другим участникам пространства) |
| **S3. Валидация структуры + лимиты**: только `messageName` из известного набора, размер тела ≤ 64 KB, `original_text` ≤ 1000 символов, rate-limit на `sessionId`/`devicesId` (как `WebChatRateLimiter`), `X-Forwarded-For` только из приватных диапазонов (уже так в nginx) | Обязательно всегда |
| **S4. Allow-list IP Сбера** | Не публикуется **[факт]** — не рассчитываем |
| **S5. mTLS** | В Studio нет настройки клиентского сертификата **[факт]** — нет |

### 3.4 Форма ответа и fallback в Telegram

- **Голос**: `pronounceText` — 1–2 коротких предложения (≤ ~250 символов), без markdown/HTML/эмодзи (ответы FSM для Telegram содержат HTML — нужен `stripHtml` + замена списков на перечисление «, »). SSML — позже (паузы перед ценами).
- **Экран (если есть)**: `items[].bubble.text` = полный текст ответа; `suggestions.buttons` = 2–3 следующих шага («Меню», «Позвать официанта», «Счёт») — на колонке не видны, в приложении Салют полезны.
- **Диалог**: `auto_listening: true` после вопроса гостю («на сколько человек?»), `false` после законченного действия; `finished: true` + `close_app` после «спасибо/хватит/выход».
- **Обращение**: `appeal=no_official` (Джой) → «ты», иначе «вы». Это единственная персонализация под ассистента.
- **Fallback (действия дольше 7 с или требующие персонала)**: «позови официанта», «счёт», «забронировать на завтра», всё, где FSM ждёт подтверждения/контакт — адаптер отвечает сразу коротким подтверждением («Передала официанту, стол пять»), а действие уходит в существующий путь уведомлений персонала (`TELEGRAM_HOSTESS_CHAT_ID`, staff-задачи) **с номером стола из реестра**. Если гость связал стол через QR (M4) — ему в Telegram уходит сводка (что заказано/забронировано, ссылка на счёт). Без связки персональные данные (телефон, имя) на колонке **не спрашиваем**: это публичное устройство на столе, и ПД из Сбера физлицам не передают.
- **Нет ответа вовремя**: если `ModelGateway`/FSM не уложились в ~5 с — отдаём заготовленный ответ («Секунду, уточню у персонала») + задача в Telegram-чат персонала, не `ERROR`. `NOTHING_FOUND` используем только для явно непонятных реплик вне контекста (платформа сама предложит «не поняла»).

## 4. Решение

1. **Вариант A**: Chat App с типом сценария **SmartApp API**, вебхук `https://api.c3ag.ru/api/salute/webhook/{token}` (fallback — `https://salute.c3ag.ru/…`, если Studio не примет путь), обработчик — новый пакет `api/salute` в монолите AERIS за флагом `ASTOR_SALUTE_SMARTAPP_ENABLED=false` (по умолчанию выключен, как `ASTOR_TTS_WEB_ENABLED`). Канал `MessageChannel.SALUTE`. Отдельный сервис (B) не заводим, пока канал не потребует другого бюджета ресурсов или языка.
2. **Привязка M1** (реестр `devicesId`/`uuid.sub` → заведение/стол) с режимом регистрации для персонала; M3 как вопрос-fallback для незарегистрированного устройства; M4 (QR → Telegram) — как дополнение для персональных сценариев. Пока одна колонка в черновике — реестр может быть env-конфигом (`ASTOR_SALUTE_DEVICE_MAP=devicesId:venue:table,...`), миграция — после спайка.
3. **Безопасность S1 + S2 + S3** одновременно.
4. **Бюджет ответа 5 с** внутри адаптера: FSM fast-path (intent-роутинг «меню/официант/счёт/часы работы» без LLM) → LLM с отдельным таймаутом `ASTOR_SALUTE_MODEL_TIMEOUT_MS=4000` и `max-tokens ≤ 120` → при превышении — заготовка + задача персоналу (§3.4). Ответ по формату §3.4.
5. **Голос на колонке — голос ассистента**; SaluteSpeech для этого канала не вызываем. Расхождение голосов между каналами принимаем.
6. **Пилот в черновике** (без модерации): колонка в заведении под Сбер ID пространства Studio или приглашённого участника. **Публикация и верификация юрлица** — отдельный трек после спайка, когда понятно, что канал нужен не одному заведению.

## 5. Последствия

Плюсы: один бэкенд и одна FSM на Telegram/веб/колонку; нет новой инфраструктуры; нет затрат на TTS в этом канале; пилот можно показать за неделю без контракта со Сбером.

Минусы и риски:

- 7-секундный жёсткий таймаут диктует архитектуру ответа: часть сценариев (бронь с уточнениями, счёт) деградирует до «передал персоналу». Это осознанно: колонка — быстрый голосовой вход, длинные транзакции остаются в Telegram/у официанта.
- Зависимость от недокументированных вещей: стабильность `devicesId`, приём пути в «Внешней ссылке», поведение черновика на чужом Сбер ID. Все три — первые дни спайка; у каждого есть fallback (§3.2, §3.3).
- Нет подписи запросов → безопасность держится на секрете в URL + allow-list + лимитах. Секрет ротируется сменой env и URL в Studio.
- Без публикации — только устройства людей из пространства Studio; с публикацией — смартап публичный, и привязка к столу обязана быть явной, а «официантский» режим (брони на вечер) — только после аутентификации персонала (голосом нельзя; через Staff Portal: «включить режим персонала на этой колонке на 10 минут»).
- GigaChat уже встроен в ассистента колонки (2025) **[факт]** по публикации — гость может получить ответ от «обычного» Салюта, а не от Астора, если не запустил смартап. Нужна табличка на столе с фразой запуска и активационное имя, которое ASR надёжно распознаёт (проверить «Астор Батлер» vs «Астор» vs «Батлер» в спайке; добавить `asr_hints`).

## 6. Безопасность и приватность

- **Голосовые данные**: аудио на наш сервер **не приходит** — только текст после ASR Сбера; распознавание и хранение аудио регулируются соглашением пользователя со Сбером. Мы не получаем и не храним голос. Это проще, чем очки/Telegram-голосовые, где аудио проходит через Cloud.ru whisper.
- **ПД**: от платформы приходят только `uuid.*` и `devicesId` (псевдонимные идентификаторы) + текст. Телефон/имя/адрес (SmartProfile) физлицам не выдаются и нам не нужны: персональные сценарии уводим в Telegram через QR (M4). В логах адаптера — `sessionId`, `messageId`, `surface`, длина текста; `original_text` — только на уровне DEBUG и с маскированием цифр (номера телефонов/карт, если гость их произнёс).
- **Публичное устройство**: всё, что произносится колонкой, слышит весь стол и соседи. Не озвучивать: имя гостя из брони, сумму счёта по умолчанию (только по явному запросу), что-либо из истории другого стола. Реестр столов — единственная связь колонки с контекстом; смена стола/заведения — только через персонал.
- **Персонал через колонку**: сценарии «какие брони на 19:00» выдают ПД гостей — только в staff-режиме, включённом из Staff Portal на ограниченное время, и только если колонка стоит у стойки, не на столе. В спайк не входит.
- **Вебхук**: секрет в URL (S1), allow-list `projectId` (S2), лимиты и валидация (S3); `anyRequest().permitAll()` в `SecurityConfig` сегодня означает, что защита — внутри контроллера, как у glasses-bearer. CORS не нужен (не браузер). Ошибки наружу — без диагностики провайдера (как в glasses-адаптере).
- **Юридически**: при публикации — политика конфиденциальности и соглашение об обработке ПД со Сбером (§2.7); бренд «Astor» в названии — подтверждение прав (§2.7).

## 7. Стоимость

| Статья | Оценка |
| --- | --- |
| SmartMarket Studio, Chat App, эмулятор, модерация | Бесплатно **[факт]** |
| Колонка SberBoom Mini / SberBoom на стол | Розничная цена устройства (в репозитории не фиксируем); для спайка — одна SberBoom Mini |
| Ответы: GigaChat через Cloud.ru | Те же токены, что у Telegram/веб-чата; реплика с колонки короче (≤120 токенов ответа); fast-path без LLM для частых интентов снижает расход |
| TTS | 0 — озвучивает ассистент |
| Инфраструктура | 0 — тот же контейнер AERIS, тот же edge |
| Юрлицо/публикация | Время на документы (выписка, заявление, политика, соглашение) — ~1–2 недели календарных; денег не требует **[факт]** |
| «Умный отель Сбер» (если захотим тираж через B2B-платформу) | Отдельный контракт с SberDevices, условия не публикуются **[допущение]** |
| YourVoice (единый голос бренда) | Отдельный платный продукт; на голос ассистента на колонке не влияет — не берём |

## 8. План спайка (1 неделя)

Цель спайка: гость за столом говорит «Салют, запусти Астор Батлер — что из супов?» и слышит ответ из AERIS, а «позови официанта» рождает сообщение в hostess-чате с номером стола. Код спайка — на ветке `feat/salute-smartapp` за флагом, не в этом PR.

**День 1 — Studio и протокол (Michael + 1 разработчик)**

1. Зарегистрироваться на developers.sber.ru → Studio (Сбер ID Michael). Пространство: пока личное; корпоративное + верификация — позже.
2. Создать смартап: тип **Chat App**, имя «Астор Батлер», активационные имена: «Астор Батлер», «Астор», «Батлер» (до 5). Тип сценария — **SmartApp API**, «Внешняя ссылка» = `https://api.c3ag.ru/api/salute/webhook/<token>`. Зафиксировать: принял ли Studio URL с путём (если нет — завести `salute.c3ag.ru` у Vedal в `c3ag.caddy` и указать хост).
3. Поверхности: SberBoom, SberBoom Mini, приложение Салют. Условия запуска: без экрана, без авторизации.
4. Поднять **echo-обработчик** `POST /api/salute/webhook/{token}` в AERIS за флагом: логирует весь запрос, отвечает `ANSWER_TO_USER` с `pronounceText = original_text`. Деплой на Cloud.ru по runbook.
5. Прогнать в **эмуляторе Studio** («Черновик» → «Тестирование»): `RUN_APP`, `MESSAGE_TO_SKILL`, `CLOSE_APP`; снять реальные JSON (положить в `docs/architecture/samples/salute/` — без `uuid`).

**День 2 — колонка**

6. SberBoom Mini, залогинить под тем же Сбер ID, что Studio. «Салют, запусти Астор Батлер» → эхо. Замерить: какие активационные имена распознаются; что в `device.surface`, `devicesId`, `uuid.sub`; стабильны ли они после перезапуска смартапа, колонки, спустя сутки.
7. Таймаут: добавить `?delay=` в echo-обработчик, найти порог, после которого ассистент говорит об ошибке (ожидаем ~7 с). Записать в этот ADR.
8. Пригласить второго человека в пространство, залогинить колонку под его Сбер ID — убедиться, что черновик запускается (модель «колонка под Сбер ID заведения»).

**День 3 — адаптер → FSM**

9. Маппинг `MESSAGE_TO_SKILL` → `IncomingMessage(channel=SALUTE, externalUserId=uuid.sub, chatId=hash(devicesId), text=original_text)`; `OutgoingMessage` → `ANSWER_TO_USER` (`stripHtml`, лимит `pronounceText`, `bubble`, `suggestions` из `actions`, `auto_listening` по `requestContact`/вопросу, `finished` по состоянию). `RUN_APP` → приветствие заведения, `CLOSE_APP` → закрыть сессию.
10. Реестр столов: `ASTOR_SALUTE_DEVICE_MAP` (env) → `venue/table`; незарегистрированное устройство → вопрос «за каким вы столом?».
11. Бюджет: `ASTOR_SALUTE_MODEL_TIMEOUT_MS=4000`, fast-path для «меню/супы/официант/счёт/часы»; при превышении — заготовка + задача в hostess-чат.

**День 4 — сценарии и персонал**

12. «Позови официанта» / «счёт» → сообщение в `TELEGRAM_HOSTESS_CHAT_ID` с номером стола через существующие уведомления (`WebLeadNotificationService`-подобный путь или staff-задачи glasses). Ответ колонки — подтверждение.
13. Обращение по `character.appeal`; `asr_hints` со словами меню заведения (названия блюд).
14. Тесты: `SaluteWebhookControllerTest` (валидация, секрет, таймаут, формат без `null` и переносов), маппинг — по образцу `ChatSpeechControllerTest`/`GlassesSpeechTest`.

**День 5 — приёмка и решение**

15. Демонстрация в заведении: 10 реплик гостя, 3 — персонала; замер p50/p95 времени ответа (цель p95 < 5 с); журнал ошибок ASR (что не распозналось).
16. Обновить этот ADR фактами (таймаут, `devicesId`, URL с путём, поведение черновика), статус → Accepted/Rejected. Решение: публиковать ли (трек юрлица, §2.7) и нужен ли запрос в SberDevices по «Умному отелю».

Критерии остановки спайка: Studio не принимает наш URL и субдомен; черновик не запускается на колонке под приглашённым Сбер ID; p95 > 7 с без возможности fast-path.

## 9. Примеры JSON

Запрос ассистента (`MESSAGE_TO_SKILL`, сокращено до используемых полей; структура — по документации, значения — вымышленные):

```json
{
  "messageName": "MESSAGE_TO_SKILL",
  "sessionId": "86024848-c12b-4056-b58b-93c69b412314",
  "messageId": 3,
  "uuid": { "userChannel": "B2C", "sub": "d2d6da62-6bdd-452b-b5dd-a145090075ba", "userId": "7f1c…" },
  "payload": {
    "app_info": { "projectId": "a0b1c2d3-…", "applicationId": "…", "appversionId": "…", "frontendType": "CHAT_APP" },
    "projectName": "Астор Батлер",
    "intent": "run_app",
    "original_intent": "run_app",
    "intent_meta": {},
    "new_session": false,
    "character": { "id": "athena", "name": "Афина", "gender": "female", "appeal": "official" },
    "device": {
      "surface": "SBERBOOM_MINI", "surfaceVersion": "1.0", "devicesId": "SBDV-00095-…",
      "platformType": "ANDROID", "platformVersion": "…",
      "capabilities": { "screen": { "available": false }, "mic": { "available": true }, "speak": { "available": true } },
      "features": { "appTypes": ["DIALOG"] }
    },
    "message": {
      "original_text": "что сегодня из супов",
      "normalized_text": "что сегодня из супов",
      "asr_normalized_message": "что сегодня из супов",
      "entities": {}, "tokenized_elements_list": []
    },
    "meta": { "time": { "timezone_id": "Europe/Moscow", "timezone_offset_sec": 10800, "timestamp": 1791000000000 } },
    "annotations": { "censor_data": { "classes": ["politicians", "obscene", "model_response"], "probas": [0.0, 0.0, 0.0] } },
    "selected_item": { "index": 0, "title": "", "is_query_by_number": false }
  }
}
```

Наш ответ (`ANSWER_TO_USER`; в проде — одной строкой, без переносов и `null`):

```json
{
  "messageName": "ANSWER_TO_USER",
  "sessionId": "86024848-c12b-4056-b58b-93c69b412314",
  "messageId": 3,
  "uuid": { "userChannel": "B2C", "sub": "d2d6da62-6bdd-452b-b5dd-a145090075ba", "userId": "7f1c…" },
  "payload": {
    "pronounceText": "Сегодня два супа: борщ с говядиной и тыквенный крем-суп. Рассказать подробнее или позвать официанта?",
    "pronounceTextType": "application/text",
    "items": [
      { "bubble": { "text": "Супы сегодня:\n• Борщ с говядиной — 590 ₽\n• Тыквенный крем-суп — 520 ₽" } }
    ],
    "suggestions": {
      "buttons": [
        { "title": "Подробнее", "actions": [ { "type": "text", "text": "расскажи подробнее про супы" } ] },
        { "title": "Позвать официанта", "actions": [ { "type": "text", "text": "позови официанта" } ] },
        { "title": "Хватит", "actions": [ { "type": "text", "text": "хватит" } ] }
      ]
    },
    "auto_listening": true,
    "finished": false,
    "asr_hints": { "words": ["борщ", "крем-суп", "официант", "счёт"] },
    "device": { "surface": "SBERBOOM_MINI", "capabilities": { "screen": { "available": false }, "mic": { "available": true }, "speak": { "available": true } } }
  }
}
```

Завершение («хватит»): тот же ответ с `pronounceText: "Хорошего вечера!"`, `finished: true`, `auto_listening: false`, `items: [ { "command": { "type": "close_app" } } ]`.

Ошибка на нашей стороне (не используем для таймаутов LLM — там заготовка):

```json
{ "messageName": "ERROR", "sessionId": "…", "messageId": 3, "uuid": { "userChannel": "B2C", "userId": "7f1c…" }, "payload": { "code": 503, "description": "Butler unavailable", "device": { "surface": "SBERBOOM_MINI", "capabilities": { "screen": { "available": false }, "mic": { "available": true }, "speak": { "available": true } } } } }
```

## 10. OpenAPI (заглушка для адаптера)

Схемы покрывают только поля, которые адаптер читает/пишет; остальные поля платформы проходят как `additionalProperties`. Это контракт нашего эндпоинта, а не полная спецификация SmartApp API.

```yaml
openapi: 3.0.3
info:
  title: Astor Butler — Salute SmartApp webhook
  version: 0.1.0-spike
servers:
  - url: https://api.c3ag.ru
paths:
  /api/salute/webhook/{token}:
    post:
      summary: Вебхук SmartApp API (Chat App) для ассистентов Салют
      description: >
        Принимает RUN_APP / MESSAGE_TO_SKILL / SERVER_ACTION / CLOSE_APP от ассистента,
        прогоняет реплику через MessageGatewayService и отвечает ANSWER_TO_USER.
        Бюджет ответа — 5 с (платформа ждёт 7 с). Ответ — одна строка JSON без null.
      operationId: saluteWebhook
      parameters:
        - in: path
          name: token
          required: true
          schema: { type: string, minLength: 32 }
          description: Секрет вебхука (ASTOR_SALUTE_WEBHOOK_TOKEN); сравнение в константное время.
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/AssistantRequest' }
      responses:
        '200':
          description: Ответ ассистенту (ANSWER_TO_USER, NOTHING_FOUND или ERROR)
          content:
            application/json:
              schema:
                oneOf:
                  - $ref: '#/components/schemas/AnswerToUser'
                  - $ref: '#/components/schemas/NothingFound'
                  - $ref: '#/components/schemas/AssistantError'
        '204':
          description: Для CLOSE_APP (ассистент ответа не ждёт)
        '400': { description: Неизвестный messageName или невалидное тело }
        '403': { description: Неверный token или projectId вне allow-list }
        '413': { description: Тело больше 64 KB }
        '429': { description: Превышен лимит на sessionId/devicesId }
        '503': { description: Канал выключен (ASTOR_SALUTE_SMARTAPP_ENABLED=false) }
components:
  schemas:
    Uuid:
      type: object
      required: [userChannel, userId]
      properties:
        userChannel: { type: string, maxLength: 64, example: B2C }
        sub: { type: string, maxLength: 256 }
        userId: { type: string, maxLength: 64 }
    Device:
      type: object
      required: [surface, capabilities]
      properties:
        surface:
          type: string
          enum: [COMPANION, SBOL, SBERBOX, SBERBOOM, SBERBOOM_MINI, SATELLITE, TIME, STARGATE, TV, TV_HUAWEI]
        surfaceVersion: { type: string }
        devicesId: { type: string }
        platformType: { type: string, enum: [ANDROID, IOS] }
        platformVersion: { type: string }
        capabilities:
          type: object
          properties:
            screen: { type: object, properties: { available: { type: boolean }, width: { type: integer }, height: { type: integer } } }
            mic: { type: object, properties: { available: { type: boolean } } }
            speak: { type: object, properties: { available: { type: boolean } } }
      additionalProperties: true
    Character:
      type: object
      required: [id, name, gender, appeal]
      properties:
        id: { type: string, enum: [sber, athena, joy] }
        name: { type: string, enum: [Сбер, Афина, Джой] }
        gender: { type: string, enum: [male, female] }
        appeal: { type: string, enum: [official, no_official] }
    AppInfo:
      type: object
      required: [projectId, frontendType]
      properties:
        projectId: { type: string }
        applicationId: { type: string }
        appversionId: { type: string }
        frontendType: { type: string, enum: [APK, DIALOG, WEB_APP, CHAT_APP] }
      additionalProperties: true
    Message:
      type: object
      required: [original_text]
      properties:
        original_text: { type: string, maxLength: 1000 }
        normalized_text: { type: string }
        asr_normalized_message: { type: string }
      additionalProperties: true
    AssistantRequest:
      type: object
      required: [messageName, sessionId, messageId, uuid, payload]
      properties:
        messageName: { type: string, enum: [RUN_APP, MESSAGE_TO_SKILL, SERVER_ACTION, CLOSE_APP] }
        sessionId: { type: string, maxLength: 36 }
        messageId: { type: integer, format: int32 }
        uuid: { $ref: '#/components/schemas/Uuid' }
        payload:
          type: object
          required: [app_info, device, character]
          properties:
            app_info: { $ref: '#/components/schemas/AppInfo' }
            device: { $ref: '#/components/schemas/Device' }
            character: { $ref: '#/components/schemas/Character' }
            projectName: { type: string }
            intent: { type: string }
            new_session: { type: boolean, default: false }
            message: { $ref: '#/components/schemas/Message' }
            server_action:
              type: object
              properties:
                action_id: { type: string }
                parameters: { type: object, additionalProperties: true }
            meta: { type: object, additionalProperties: true }
            annotations: { type: object, additionalProperties: true }
          additionalProperties: true
    SuggestionButton:
      type: object
      required: [title, actions]
      properties:
        title: { type: string, maxLength: 32 }
        actions:
          type: array
          minItems: 1
          items:
            type: object
            required: [type]
            properties:
              type: { type: string, enum: [text, server_action, deep_link] }
              text: { type: string }
              should_send_to_backend: { type: boolean, default: true }
              message_name: { type: string }
              action_id: { type: string }
              payload: { type: object, additionalProperties: true }
              deep_link: { type: string }
    Item:
      type: object
      properties:
        bubble: { type: object, properties: { text: { type: string, maxLength: 2000 } } }
        card: { type: object, additionalProperties: true }
        command: { type: object, properties: { type: { type: string, example: close_app } }, additionalProperties: true }
    AnswerToUser:
      type: object
      required: [messageName, sessionId, messageId, uuid, payload]
      properties:
        messageName: { type: string, enum: [ANSWER_TO_USER] }
        sessionId: { type: string, maxLength: 36 }
        messageId: { type: integer, format: int32, description: Совпадает с messageId запроса }
        uuid: { $ref: '#/components/schemas/Uuid' }
        payload:
          type: object
          required: [pronounceText, device]
          properties:
            pronounceText: { type: string, maxLength: 250 }
            pronounceTextType: { type: string, enum: [application/text, application/ssml], default: application/text }
            items: { type: array, items: { $ref: '#/components/schemas/Item' } }
            suggestions:
              type: object
              properties:
                buttons: { type: array, maxItems: 3, items: { $ref: '#/components/schemas/SuggestionButton' } }
            auto_listening: { type: boolean }
            finished: { type: boolean, default: false }
            emotion: { type: object, properties: { emotionId: { type: string } } }
            asr_hints: { type: object, properties: { words: { type: array, items: { type: string } } } }
            intent: { type: string }
            device: { $ref: '#/components/schemas/Device' }
    NothingFound:
      type: object
      required: [messageName, sessionId, messageId, uuid, payload]
      properties:
        messageName: { type: string, enum: [NOTHING_FOUND] }
        sessionId: { type: string }
        messageId: { type: integer }
        uuid: { $ref: '#/components/schemas/Uuid' }
        payload:
          type: object
          required: [device]
          properties:
            device: { $ref: '#/components/schemas/Device' }
            intent: { type: string }
    AssistantError:
      type: object
      required: [messageName, sessionId, messageId, uuid, payload]
      properties:
        messageName: { type: string, enum: [ERROR] }
        sessionId: { type: string }
        messageId: { type: integer }
        uuid: { $ref: '#/components/schemas/Uuid' }
        payload:
          type: object
          required: [code, device]
          properties:
            code: { type: integer }
            description: { type: string, maxLength: 30 }
            device: { $ref: '#/components/schemas/Device' }
```

## 11. Как тестировать

**Эмулятор Studio** (день 1): карточка смартапа → версия «Черновик» → «Тестирование». Эмулятор запускает смартап сам (без фразы активации), вход — текст или микрофон браузера, переключатель SberBox/телефон. В логах — JSON запросов/ответов и `message_id` для обращения в поддержку. Ограничение: поверхность будет `SBERBOX`/`COMPANION`, не `SBERBOOM` — ветку «нет экрана» эмулятор не проверит, только протокол и тайминги.

**Реальная колонка** (день 2): SberBoom/SberBoom Mini, в приложении Салют авторизован Сбер ID владельца пространства Studio (или приглашённого участника). «Салют, запусти Астор Батлер» (или «Включи Астор Батлер»). Черновик работает без модерации. Проверяем: активационные имена, `surface`/`devicesId`/`uuid.sub`, порог таймаута, `auto_listening`, выход по «хватит», системные «стоп»/«громче» внутри смартапа.

**Локально без Studio**: `curl -s -X POST http://localhost:8080/api/salute/webhook/<token> -H 'Content-Type: application/json' -d @docs/architecture/samples/salute/message_to_skill.json | jq -c .` — ответ должен быть одной строкой, без `null`, с тем же `messageId`. Для прогона с колонки на локальную машину — туннель (ngrok/localtunnel) с https, как советует документация для Canvas App; для Chat App URL туннеля подставляется в «Внешнюю ссылку» черновика.

## 12. Открытые вопросы (для спайка и письма в developer@sberdevices.ru)

1. Принимает ли «Внешняя ссылка» URL с путём (`/api/salute/webhook/<token>`)? Если нет — есть ли иной способ аутентифицировать вебхук?
2. Стабилен ли `payload.device.devicesId` для одной колонки между сессиями/перезагрузками? Уникален ли на устройство?
3. Есть ли режим распространения «только для указанных Сбер ID/устройств» без публикации в общий каталог (корпоративный/закрытый смартап)?
4. Актуален ли таймаут 7 с для SberBoom (страницы 2023 года)?
5. Можно ли запускать черновик на колонке, залогиненной под Сбер ID, приглашённым в пространство (а не владельцем)?
6. Условия «Умного отеля Сбер» для ресторанов/баров без номерного фонда и есть ли там API для сторонней диалоговой логики.
7. Нужно ли свидетельство на ТЗ «Астор» для названия смартапа при публикации (или достаточно письма правообладателя).

## 13. Источники

Официальная документация developers.sber.ru (SmartApp API, Studio, модерация):

- Типы смартапов и устройства: https://developers.sber.ru/docs/ru/va/about/app-design/smartapp
- Обзор SmartApp API (HTTPS POST, «ожидает ответа от вебхука в течение семи секунд», ПД физлицам не передаются): https://developers.sber.ru/docs/ru/va/api/overview и https://developers.sber.ru/docs/ru/va/reference/sa-api/overview
- Запросы ассистента (`MESSAGE_TO_SKILL`, `uuid`, `payload.device.devicesId`, `surface`, `character`, `message.original_text`/`asr_normalized_message`, `new_session`): https://developers.sber.ru/docs/ru/va/api/smartapp-api-requests
- Ответы смартапа (`ANSWER_TO_USER`, `pronounceText`, `items`, `suggestions`, `auto_listening`, `finished`, `NOTHING_FOUND`, `ERROR`, запрет `null`/переносов): https://developers.sber.ru/docs/ru/va/api/smartapp-api-responses
- Действия кнопок (`text`, `server_action`, `deep_link`): https://developers.sber.ru/docs/ru/va/api/smartapp-api-actions
- Подключение внешнего сервера к Chat App (тип сценария SmartApp API, «Внешняя ссылка»): https://developers.sber.ru/docs/ru/va/chat/script/scenario-webhook-setup
- Основные настройки (имя ≤50, активационные имена ≤5, «Включи [имя]», поверхности, условия запуска): https://developers.sber.ru/docs/ru/va/about/smartapp/main-settings
- Авто-прослушивание (`auto_listening`, SberBoom): https://developers.sber.ru/docs/ru/va/chat/voice-interface/autolistening
- Закрытие смартапа (`close_app`): https://developers.sber.ru/docs/ru/va/how-to/start-stop/close-app
- Тестирование Chat App (эмулятор, реальные устройства, тот же Сбер ID, приглашение в пространство): https://developers.sber.ru/docs/ru/va/chat/testing
- Сырой запрос в сценарии (`$request.rawRequest`, `character.name`/`appeal`): https://developers.sber.ru/docs/ru/va/how-to/conversation/raw-request
- Модерация (до 3 рабочих дней, 9:00–18:00 МСК): https://developers.sber.ru/docs/ru/va/about/app-review/overview и чек-лист https://developers.sber.ru/docs/ru/va/about/app-review/premoderation-checklist
- Требования к юрлицам/ИП (верификация, заявление владельца, ТЗ, политика, соглашение об обработке ПД): https://developers.sber.ru/docs/ru/va/about/legal-entities/legal-requirements и https://developers.sber.ru/docs/ru/va/studio/space/settings/company
- Блок событий Graph (таймаут классификатора 10 с — не вебхук): https://developers.sber.ru/docs/ru/va/graph/blocks/event
- `uuid.sub` как идентификатор пользователя (SmartPay/подписки): https://developers.sber.ru/docs/ru/va/about/monetization/subscription/api
- SmartProfile (согласие, только верифицированное юрлицо): https://developers.sber.ru/docs/ru/va/smartservices/smartprofile/integration

SaluteSpeech:

- Голоса синтеза; «Через API не предоставляются голоса ассистента Салют»: https://developers.sber.ru/docs/ru/salutespeech/guides/synthesis/voices
- Подключение физлиц недоступно новым клиентам с 15.07.2026: https://developers.sber.ru/docs/ru/salutespeech/quick-start/integration-individuals

B2B / рынок (публикации, не документация):

- «Умный отель Сбер», страница продукта: https://developers.sber.ru/portal/products/salute-hotels
- Cosmos Hotel Group и SberDevices (06.2026): https://www.vedomosti.ru/tourism/industry/news/2026/06/05/1203511-razvitii-umnih-otelei
- Hotel Continental внедрил «Умный отель Сбер» (09.2026): https://www.comnews.ru/content/247559/2026-09-25/2026-w39/1011/stolichnyy-otel-kontinental-vnedril-umnyy-otel-sber
- GigaChat в колонках Салют (09.2025): https://www1.ru/news/2025/09/02/gigachat-ot-sbera-integrirovan-v-umnye-kolonki-saliut.amp.html
- Ресторанный кейс 2021 на SberPortal («Салют, закажи кофе с собой»): https://developers.sber.ru/portal/news/saliut-zakazhi-kofe-s-soboi-07-12-2021
- Опыт сообщества (Canvas App + вебхук, URL с путём, тест черновика на своём аккаунте): https://habr.com/ru/post/541522
