# План мультиязычности Astor (цель: 150+ языков)

Статус: черновик к ветке `feat/i18n-multilang`, 2026-10-10. Исполнитель: Егор (`BryxOG`).
Владелец решений: Михаил (открытые вопросы в разделе 10).

## 0. Коротко

- **Целевое поведение.** Гость пишет на любом языке, и бот отвечает на этом же языке. Кнопки и системные тексты локализованы. На сайтах есть переключатель языка. Персонал по-прежнему работает на русском.
- **Принцип.** FSM продолжает «думать» по-русски, а язык обрабатывается на краях диалога:
  - на входе: определить язык гостя, а для понимания при необходимости перевести текст на русский;
  - на выходе: взять тексты из каталога или машинного перевода, а LLM попросить отвечать на языке гостя.
- **150 языков только машинным переводом от разрешённых провайдеров не набрать.**
  - Yandex Translate покрывает около 110 языков.
  - У GigaChat нет официального списка языков и нет эндпоинта перевода. Остальные языки возможны только в режиме «best-effort» с непроверенным качеством, подробнее в разделе 3.
  - Голос работает примерно для 13 языков. Остальные языки получают только текст.
- **Что уже сделано в ветке.** Заложен фундамент (раздел 7): определение языка, каталог ru/en, порт машинного перевода с no-op реализацией и рабочий пример на сценарии отзыва.
  - Все флаги выключены, поведение приложения не изменилось.
  - Новых таблиц и платных вызовов нет.
- **Оценка дальнейшей работы:** около 29–42 человеко-дней по фазам 1–8. Большую часть Егор может сделать сам (раздел 8).

## 1. Цель и честная формулировка «150+»

| Уровень | Что получает гость | Языки | Источник |
|---|---|---|---|
| A — полный | Тексты, кнопки и ответы LLM, вычитанные людьми | ru, en | каталоги `i18n/guest/{ru,en}.yaml` |
| B — машинный перевод | Системные тексты через Yandex Translate (с кешем и возможностью проверки), ответы LLM на языке гостя | около 110 из списка Yandex Translate | Yandex Translate и GigaChat |
| C — best-effort | Ответы LLM на языке гостя, системные тексты на английском (fallback) | всё вне списка Yandex, до 150+ | GigaChat, качество не измерено |
| Голос | Распознавание и синтез речи | 15 языков STT, около 13 TTS (раздел 3.3) | SpeechKit и SaluteSpeech |

Формулировку для продукта можно сделать честной: «~110 языков с машинным переводом, остальные — в бета-режиме». Обещать «150+» без оценки качества GigaChat на редких языках нельзя. Решение за Михаилом (вопрос 1).

## 2. Что есть сейчас (инвентаризация)

### 2.1 Backend (Java/Spring)

**Где язык уже известен**

- **Telegram.** `TelegramRouter.toIncomingMessage()` кладёт `User.languageCode` в `IncomingMessage.languageCode`, то же делает `typedByGuest()` для callback.
  - `IdentityService.upsertTelegramProfile()` сохраняет его в `telegram_profiles.language_code`.
  - `TelegramIntakeService` пишет его в payload событий.
  - **В ответах язык нигде не используется.**
- **Web-виджет.** `frontend/lib/web-chat.ts` отправляет `payload.viewport.locale = navigator.language`, а `MessageController` передаёт `languageCode = null`. Бэкенд эту локаль не читает. Новый `GuestLocaleService` уже умеет брать язык из `payload.viewport.locale`.
- **MAX** (ветка `feat/max-adapter`). В объекте `User` у MAX нет поля языка ([dev.max.ru/docs-api/objects/User](https://dev.max.ru/docs-api/objects/User)), поэтому для MAX остаются только детекция и явный выбор.
- **Алиса** (`alisa/`): только русский. Платформа русскоязычная, локализация не нужна.
- **RAG.** У `semantic_chunks` есть колонка `language_code`, и всё в ней `ru`. Загрузчики `SemanticMarkdownChunkLoader`, `IntentExampleCorpusLoader` и `OpsProjectMemoryService` ставят `"ru"` жёстко.
- **Речь.**
  - `YandexSpeechKitSpeechToText` работает через v1 sync с одним языком на весь инстанс (`ASTOR_STT_LANGUAGE=ru`). Он знает ru, en, kk, uz, tr, de.
  - `SpeechKitTextToSpeech` шлёт `lang=ru-RU` жёстко.
  - В интерфейсе `TextToSpeech.synthesize(text)` нет параметра языка.

**Захардкоженные строки.** Посчитаны строковые литералы с кириллицей в `src/main/java`: около 2150 литералов в 109 файлах плюс 83 текстовых блока `"""…"""` в 37 файлах.

| Аудитория | Файлов | Литералов | Главные файлы |
|---|---|---|---|
| Гость — ответы, кнопки, уведомления (часть литералов — регэкспы и ключевые слова) | 82 | ~1330, из них ~260 однострочных фраз (плюс часть из 83 текстовых блоков) | `fsm/scenario/*` (ChangeCancel 207, TableBookingDraftMerger 167, BusinessLunch 102, TableBooking 52, ScenarioRouter 47, SafePlay 47, SmartTip 44, EventBooking 44, QuietGuide 43, MenuAssets 40, …), `fsm/handler/*`, `domain/booking/TableReservationNotificationService` (гостевые и хостес-тексты вперемешку), `domain/billing/GuestBillNotifier`, `VisitReviewService`, `domain/lunch/*`, `telegram/adapter/TelegramRouter` (гостевая клавиатура `guestMainMenuKeyboard()`, `contactKeyboard()`), `telegram/voice/TelegramVoiceReplyService`, `telegram/command/*`, `service/message/MessageGatewayService` (голос и fallback), `api/message/MessageController` (rate limit), `domain/content/C3flexVideoCatalogService` (контент сайта) |
| Понимание гостя — русские ключевые слова и регэкспы | 11 | ~530 | `fsm/understanding/GuestInputUnderstandingService` (280), `GuestMoneyText`, `GuestPartyText`, `Natasha/DucklingRussianNluAdapter` (`ru_RU`), `domain/feedback/FeedbackService` (тональность по корням), `domain/preference/GuestPreferenceService` (аллергии), `domain/concierge/ConciergeRequestService`, `domain/content/VenueContentClassifier` |
| Персонал — хостес, ops-чаты, отчёты (остаются на русском) | 16 | ~300 | `service/message/Ops*` (180), `domain/shift/*`, `api/glasses/*`, `HostessReservationApprovalService`, `analytics/KafkaAdminEventFormatter`, `telegram/adapter/*Notifier` |

**Вывод:** понимание гостя держится на русских корнях и регэкспах. Гость, пишущий по-немецки, сейчас попадает в LLM-fallback, а не в сценарии. Поэтому нужен входной перевод на русский (фаза 4), а не переписывание NLU под каждый язык.

**LLM**

- Промпты на русском, ответы тоже на русском:
  - `ScenarioReplyComposer.promptFor()` начинается с «на русском языке»;
  - `MessageGatewayService.aiAssistedReply()`;
  - `LlmUnderstandingService` (JSON-разбор);
  - persona-файл `OPENAI_COMPATIBLE_SYSTEM_PROMPT_FILE` для openai-compatible провайдеров.
- Через `ModelGateway` используются GigaChat, Cloud.ru и другие провайдеры.

**Контент**

- Меню лежит в PDF в S3 (`AERIS_MENU_*`) и только на русском.
- `src/main/resources/semantic/aeris/**` (RAG), `business-lunch/aeris.json`, `menu/aeris/**` тоже на русском.

### 2.2 Frontend `frontend/` (Next.js, c3ag.ru)

- **Стек.**
  - Next 15, App Router, `output: "standalone"` (не static export, поэтому middleware доступно).
  - i18n-библиотек нет, `<html lang="ru">`, OG `ru_RU`, hreflang нет.
- **Строки.** 54 файла с кириллицей: components 31, lib 13, app 5. Самые тяжёлые:
  - `data/videos.json`;
  - `lib/products.ts` (каталог и SEO);
  - `lib/portfolio.ts`, `lib/studio.ts`;
  - `components/ui/ChatWidget.tsx`;
  - `lib/clio-persona.ts` (приветствие и persona чата).
- **Структура маршрутов.** Сегмент `app/[product]` корневой и динамический, поэтому `[locale]` встанет на тот же уровень. Страницы нужно перенести в `app/[locale]/[product]`.
- **Связь с бэкендом.** `lib/web-chat.ts` уже шлёт `viewport.locale`. Ответы чата и TTS (`/api/chat/speak`) русские.

### 2.3 Frontend `frontend/astor-butler/` (статический сайт и Concierge Mini App)

> Этот каталог сейчас правит параллельный агент, поэтому фаза 7 начинается после слияния его работы.

- **Стек.**
  - Чистый HTML/CSS/JS без сборки, все страницы `<html lang="ru">`.
  - Edge worker `server/index.js` раздаёт файлы по фиксированному `ASSET_MANIFEST`, поэтому новые файлы словарей нужно туда добавить.
- **Строки.** 19 файлов с кириллицей: HTML-страницы, `js/feed.js`, `js/staff.js`, `js/widget.js`, `data/venues.json` (город, кухня, адрес, alt).
- **Форматирование.** Даты и числа жёстко в `"ru-RU"` (`js/feed.js:40,73`, `js/staff.js:59`).
- **Mini App** (`astor_concierge/feed/`).
  - `Telegram.WebApp.initDataUnsafe.user.language_code` не читается.
  - Бэкенд не вызывается: только `data/venues.json` и `data/ratings/snapshot.json`.
- **Виджет.** `js/widget.js` шлёт сообщения в `/api/astor/messages` без локали.

## 3. Провайдеры и покрытие языков

Сверено с первоисточниками 2026-10-10. Документация Yandex Cloud по Translate и SpeechKit переехала на `aistudio.yandex.ru/docs/...`.

### 3.1 Yandex Translate API v2 (основной машинный перевод)

**Языки.** В документации 115 кодов. Без `emj` (эмодзи) и вариантов `kazlat`, `uzbcyr`, `sr-Latn`, `pt-BR` остаётся **около 110 различных языков**, из них около 21 — языки народов России (tt, ba, cv, sah, ce, os, udm, …). Документация сама говорит, что список может отличаться от фактического, поэтому истиной считается метод `listLanguages`.
- https://aistudio.yandex.ru/docs/en/translate/concepts/supported-languages
- https://aistudio.yandex.ru/docs/en/translate/api-ref/Translation/listLanguages

**Чего нет в списке.** Отдельного кода для традиционного китайского нет, есть только `zh`. Нет туркменского, пушту, курдского, сомали, хауса, йоруба, игбо, оромо, тигринья, уйгурского, ория, синдхи, мальдивского и ряда других.

**`translate`**
- Принимает `texts[]` суммарно до **10 000 символов** на запрос.
- `sourceLanguageCode` необязателен. Без него язык определяется автоматически и возвращается `detectedLanguageCode`.
- `format`: `PLAIN_TEXT` или `HTML`. Есть глоссарии: до 50 пар в запросе.
- https://aistudio.yandex.ru/docs/en/translate/api-ref/Translation/translate
- https://aistudio.yandex.ru/docs/en/translate/concepts/glossary

**`detectLanguage`**
- Текст до 1000 символов, `languageCodeHints` до 10 кодов.
- https://aistudio.yandex.ru/docs/en/translate/api-ref/Translation/detectLanguage

**Цена**
- **500,4 ₽ за 1 млн символов с НДС.** Пересчёт без НДС — около 410 ₽ при ставке 22%.
- Отдельный `detect` стоит столько же. Определение языка внутри `translate` без указания исходного языка входит в цену перевода.
- Пробелы и HTML-теги тарифицируются. Бесплатной квоты нет.
- https://aistudio.yandex.ru/docs/ru/translate/pricing

**Квоты.** 1 млн символов в час на перевод и детекцию вместе, 20 вызовов в секунду на метод. Квоты повышаются через поддержку.
- https://aistudio.yandex.ru/docs/ru/ai-studio/concepts/limits

**Данные**
- По умолчанию запросы не сохраняются. Сохранение включает только заголовок `x-data-logging-enabled: true`, и мы его **никогда не отправляем**.
- Yandex Cloud заявляет соответствие 152-ФЗ и дата-центры в РФ. Отдельного заявления именно по Translate не найдено.
- https://aistudio.yandex.ru/docs/en/translate/api-ref/support-headers
- https://yandex.cloud/en/solutions/152-fz

**Авторизация**
- Сервисный аккаунт с ролью `ai.translate.user`, заголовок `Authorization: Api-Key …`, `folderId` не передаётся.
- https://aistudio.yandex.ru/docs/en/translate/api-ref/authentication

### 3.2 GigaChat (Сбер)

- **Языки.** Официального списка поддерживаемых языков нет. Страница GigaChat 2 Max говорит только о русском. Карточка открытой GigaChat 3.1 упоминает корпус на 10 языках, но не перечисляет их. Официальных замеров качества перевода нет.
  - https://developers.sber.ru/docs/ru/gigachat/models/gigachat-2-max
  - https://huggingface.co/ai-sage/GigaChat3.1-10B-A1.8B
- **Перевод и детекция.** Эндпоинтов перевода и определения языка нет: только chat, embeddings, files и другие. Значит, всё это возможно только через промпт.
  - https://developers.sber.ru/docs/ru/gigachat/api/reference/rest/gigachat-api
- **Цена для юрлиц за 1 млн токенов с НДС:** Lite 65 ₽, Pro 500 ₽, Max 650 ₽.
  - https://developers.sber.ru/docs/ru/gigachat/tariffs/legal-tariffs
- **Где обрабатываются данные.** В корпоративном соглашении есть ссылка на 152-ФЗ, но **нет явного пункта об обработке в РФ** (НЕ ПОДТВЕРЖДЕНО).
  - https://developers.sber.ru/docs/ru/policies/gigachat-agreement/corporate-clients-beta
- **Вывод.** GigaChat годится, чтобы **генерировать** ответ сразу на языке гостя. Для en, de, zh, tr и других распространённых языков это ожидаемо приемлемо, но требует проверки (фаза 8). Как переводчик для редких языков он не подтверждён.

### 3.3 Голос

**Распознавание (STT)**

- **SpeechKit**: 15 языков (de, en, es, fi, fr, he, it, kk, nl, pl, pt, ru, sv, tr, uz).
  - Автоопределение `language_code: auto` есть **только в API v3**. У нас сейчас v1 sync.
  - https://aistudio.yandex.ru/docs/en/speechkit/stt/models
- **SaluteSpeech**: ru, en, kk, ky, uz.
  - https://developers.sber.ru/docs/ru/salutespeech/guides/recognition/improvement

**Синтез (TTS)**

- **SpeechKit v3**: голоса есть для 6 языков — ru (много голосов), en (john), de (lea), he (naomi), kk, uz.
  - https://aistudio.yandex.ru/docs/en/speechkit/tts/voices
- **SaluteSpeech**: 12 языков через SSML, только в синхронном синтезе — ru, uz, pt, pl, nl, kk, en, de, es, fr, it, ky.
  - https://developers.sber.ru/docs/ru/salutespeech/guides/synthesis/ssml/language

**Цены SpeechKit с НДС**

- STT: 0,1626 ₽ за 15 секунд.
- TTS v3: около 650 ₽ за 1 млн символов.
- https://aistudio.yandex.ru/docs/ru/speechkit/pricing

**Итог по голосу**

- Голосовой ответ возможен примерно для 13 языков: ru, en, de, he, kk, uz через SpeechKit плюс es, fr, it, pt, pl, nl, ky через SaluteSpeech.
- Распознавание возможно для 15–16 языков.
- Остальные языки получают текст, а голосовой ответ для них выключен.

### 3.4 Определение языка

| Способ | Стоимость | Когда использовать |
|---|---|---|
| `ScriptLanguageDetector` (сделан): алфавит, уникальные буквы, английские служебные слова | бесплатно, без сети | Всегда первым. Уверенно определяет русский, кириллицу с особыми буквами (uk, be, kk, tg, tt, ba, sr, mk) и все языки со своей письменностью (el, hy, ka, he, ar/fa/ur, hi, th, ko, ja, zh, …) |
| Yandex `translate` без `sourceLanguageCode` | входит в цену перевода | Латиница без признаков. Входной перевод на русский (фаза 4) заодно возвращает `detectedLanguageCode` |
| Yandex `detectLanguage` с подсказками | 500,4 ₽ за 1 млн символов | Если перевод не нужен, а язык нужен |
| SpeechKit v3 `auto` | входит в STT | Голосовые сообщения на 15 языках |
| Telegram `User.language_code` | бесплатно | Подсказка: это IETF-тег, необязательный и часто равный языку интерфейса приложения. Явный выбор и язык сообщения важнее. https://core.telegram.org/bots/api#user |

### 3.5 Оценка стоимости (Yandex Translate)

- **Каталог.** Около 350 гостевых строк × 120 символов ≈ 42 тыс. символов на язык. Перевод на все ~110 языков ≈ 4,6 млн символов ≈ **2,3 тыс. ₽ разово**. При ленивом переводе с кешем выходит заметно меньше.
- **Входной перевод сообщений** иностранных гостей и тот же перевод для персонала. Пример: 3000 сообщений в месяц × 80 символов ≈ 240 тыс. символов ≈ **120 ₽ в месяц**.
- **Ответы LLM** генерируются сразу на языке гостя, поэтому машинного перевода не требуют.

## 4. Целевое поведение

1. **Язык ответа.** Гость пишет на любом языке, и бот отвечает на нём: тексты сценариев, кнопки, подтверждения брони, уведомления и ответы LLM. Короткие реплики («ok», «19:00», «2») язык не переключают.
2. **Явный выбор.** Гость может выбрать язык командой `/language`, кнопкой или переключателем на сайте. Явный выбор важнее всего остального.
3. **Порядок определения языка:** явный выбор → уверенно определённый язык текущего сообщения → язык предыдущих сообщений диалога → язык платформы (Telegram `language_code`, web `viewport.locale`) → `ru`.
4. **Fallback.** Язык без каталога и без перевода получает английский. Для языков из настройки `astor.i18n.default-fallback-languages` вместо английского используется русский (решение за Михаилом, вопрос 4).
5. **Персонал работает на русском.**
   - В уведомлениях указан язык гостя («Язык гостя: английский (en)»).
   - Рядом с оригиналом сообщения гостя показан русский перевод (фаза 4).
   - Ответ персонала гостю, если он идёт через бота, переводится на язык гостя.
6. **Голос.**
   - Голосовое сообщение на одном из 15 языков распознаётся.
   - Голосовой ответ отправляется только для языков с голосом (около 13). Остальным уходит текст.
7. **Сайты.** На c3ag.ru и сайте Astor есть переключатель RU/EN с отдельными URL (`/en/...`), `hreflang` и `html lang`. Mini App берёт язык из Telegram.
8. **Флаг.** С выключенным `astor.i18n.enabled` всё работает как сейчас.

## 5. Архитектура

### 5.1 Принцип: локализация на краях

```
вход:  текст гостя ──► GuestLocaleService (язык) ──► [фаза 4: перевод на ru для понимания, оригинал сохраняется]
                                                   ──► ScenarioRouter / FSM / NLU (по-русски, как сейчас)
выход: сценарий ──► GuestTexts.text(locale, key) ──► каталог языка │ машинный перевод (кеш) │ fallback
       LLM     ──► промпт + ReplyLanguageInstruction (отвечай на языке гостя)
       адаптер ──► клавиатуры по ключам, metadata.guestLanguage, голос по языку
```

FSM, состояния, ключевые слова и Kafka-события остаются русскими и не зависят от языка. Язык передаётся в `IncomingMessage.payload` (`guestLanguage`, `guestLanguageSource`) и в `OutgoingMessage.metadata` (`guestLanguage`, `replyLanguage`, `replyTextOrigin`).

### 5.2 Определение языка (`i18n/GuestLocaleService`, `GuestLocaleResolver`)

- **Порядок источников (`LocaleSource`):** `EXPLICIT > DETECTED > CONVERSATION > PLATFORM > DEFAULT`.
- **Почему есть `CONVERSATION`.** Без него короткое «ok» от англоязычного гостя с русским интерфейсом Telegram переключило бы ответ на русский.
- **Порог детекции.** `astor.i18n.detection.min-confidence` (0,7), минимум 8 букв для латиницы и кириллицы. У письменностей с собственным алфавитом достаточно 2 букв.
- **Язык платформы.**
  - Telegram: `IncomingMessage.languageCode`.
  - Web: `payload.viewport.locale` или `payload.locale`.
  - MAX: нет, используются детекция и явный выбор.
- **Нормализация.** Язык нормализуется до первичного сабтега BCP 47 (`LanguageTags.normalize`): `pt-BR` → `pt`, `zh-hans` → `zh`, `iw` → `he`. Регион и письменность отбрасываются. Если понадобятся `zh-Hant` или `pt-BR`, их сопоставит адаптер провайдера.
- **Хранение.** Через порт `GuestLanguagePreferenceStore`; сейчас это no-op. В фазе 1 появится JDBC-реализация (раздел 6).
- **Один раз на сообщение.** После фазы 1 `MessageGatewayService` определяет язык один раз и кладёт его в payload (`GuestLocaleService.payloadWith`). Сценарии берут готовое значение, детекция повторно не выполняется.
- **Надёжность.** Ошибки хранилища или детектора не ломают ответ: такой сигнал просто пропускается.

### 5.3 Каталог сообщений (`i18n/MessageCatalog`, `GuestTexts`)

- **Файлы.** `src/main/resources/i18n/guest/<язык>.yaml`. Вложенные ключи превращаются в точечные (`feedback.ask_text`). Русский — источник истины, английский тоже правят люди.
- **Плейсхолдеры** именованные: `{orderId}`. `MessageFormat` не используется — его апострофы ломаются во французском и других языках.
- **Правила (проверяет `GuestCatalogConsistencyTest`):**
  - одинаковые ключи в ru и en;
  - одинаковые плейсхолдеры;
  - нет пустых значений;
  - все значения в двойных кавычках;
  - многострочный текст пишется как `|-`, без лишнего `\n` в конце. Если в Java-текстовом блоке перевод строки в конце был, используйте `|`.
- **Тексты для персонала в каталог не идут.**
- **Порядок выбора текста в `GuestTexts.text(locale, key, args)`:**
  1. каталог языка гостя;
  2. машинный перевод **шаблона** из `source-language` (`ru`), только если MT включён, провайдер доступен и язык не каталожный. Перевод кешируется, и все `{плейсхолдеры}` в нём должны сохраниться;
  3. язык fallback (`en`, либо `ru` для `default-fallback-languages`);
  4. `ru`;
  5. сам ключ (`MISSING`, пишется в лог).
- **Без персональных данных.** Переводится шаблон, а не готовый текст с данными гостя.
- **Кнопки.** Reply-клавиатура Telegram возвращает текст метки, поэтому `GuestTexts.matchesLabel(key, input[, locale])` узнаёт метку на любом языке каталога и в машинном переводе. С выключенным флагом сравнение идёт только с русским текстом.
- **Как добавить язык с вычитанным переводом.** Положить `i18n/guest/<язык>.yaml` и добавить язык в `ASTOR_I18N_CATALOG_LANGUAGES`. Машинный перевод для этого языка тогда отключается.

### 5.4 Машинный перевод длинного хвоста (`MachineTranslator`)

- **Порт.** `provider()`, `available()`, `translate(TranslationRequest)`, `detectLanguage(...)`. Реализация сейчас одна — `NoOpMachineTranslator`, и она ничего никуда не отправляет.
- **Фаза 3: `YandexTranslateMachineTranslator`.**
  - `POST https://translate.api.cloud.yandex.net/translate/v2/translate` с `Api-Key` сервисного аккаунта.
  - Таймаут около 2 с, одна повторная попытка на 5xx, никогда не отправлять `x-data-logging-enabled: true`.
  - Сопоставление наших кодов с кодами Yandex.
  - `format=HTML` для Telegram-HTML.
  - Батч до 10 000 символов.
  - Включается так: `ASTOR_I18N_MT_ENABLED=true`, `ASTOR_I18N_MT_PROVIDER=yandex`, плюс ключ в секретах сервера.
- **Кеш.**
  - Сейчас `InMemoryTranslationCache` (Caffeine).
  - В фазе 3 — `JdbcTranslationCache` на таблице `i18n_translations` со статусом проверки (`MACHINE`, `APPROVED`, `CORRECTED`, `REJECTED`). Исправленный человеком перевод сразу получают все гости.
- **Предварительный перевод топ-языков.** Отдельная команда или job переводит весь каталог на 10–20 приоритетных языков (вопрос 5). Результат вычитывается и коммитится как `i18n/guest/<язык>.yaml`, после чего эти языки переходят в уровень A.

### 5.5 Понимание гостя на любом языке (фаза 4)

- **Входной перевод.** Если язык сообщения не `ru`, текст переводится на русский до `ScenarioRouter` (`translate` без исходного языка заодно определяет язык). В payload кладутся `textOriginal`, `textRu`, `detectedLanguage`. FSM и NLU получают русский текст.
- **Защита слотов.** Машинный перевод портит имена, телефоны, даты и числа.
  - Телефон и числа извлекаются из **оригинала**.
  - Имя берётся из профиля или оригинала.
  - Перед созданием брони гость получает подтверждение на своём языке (как сейчас, только локализованное).
- **`LlmUnderstandingService`.** Промпт разбора JSON сделать языконезависимым: «вход на любом языке, значения слотов канонические».
- **Персонал** видит оригинал и русский перевод, отдельный вызов для этого не нужен.

### 5.6 Ответы LLM

- **Языковая инструкция.** `ReplyLanguageInstruction.forLocale(locale, "ru")` даёт строку «Язык ответа: German (код de). Отвечай гостю только на этом языке…». Для `ru` строка пустая, поэтому текущие промпты не меняются.
- **Куда встроить:**
  - `ScenarioReplyComposer.promptFor()` — убрать «на русском языке» и подставить инструкцию;
  - `MessageGatewayService.aiAssistedReply()`;
  - persona-файл.
- **RAG** остаётся русским, LLM пересказывает его на языке гостя. Ответ, сгенерированный не на том языке, ловится детектором; тогда используется fallback или повтор (фаза 8).

### 5.7 Персонал и каналы

- **Персонал.** Тексты для хостес и ops остаются на русском. Строка «Язык гостя» уже есть в уведомлении об отзыве (пример). В фазе 1 её нужно добавить в уведомления о заявке менеджеру, о брони, а также в fallback-alert.
- **Telegram.** `setMyCommands` и `setMyDescription` с `language_code` для en, остальное по фазам. Это одноразовая настройка при старте, через `TelegramBot`.
- **Web.** Отдельное поле `locale` в контракте `POST /api/messages` (`docs/contracts/FRONTEND_BACKEND_CONTRACTS.md` правит владелец контракта) и чтение `payload.viewport.locale` (уже есть).
- **MAX.** Детекция и явный выбор, поле языка в профиле отсутствует.

### 5.8 Голос (фаза 5)

- **STT.**
  - Передавать в SpeechKit язык гостя, если он из списка 15 языков, иначе использовать `ru`.
  - В v1 это `lang` на каждый запрос вместо одного языка на инстанс (`YandexSpeechKitSpeechToText.Settings.language`).
  - Позже — v3 с `auto` или списком языков.
- **TTS.**
  - `TextToSpeech.synthesize(text)` → `synthesize(text, language)` с дефолтом `ru`.
  - Голос подбирается по языку: SpeechKit v3 для ru, en, de, he, kk, uz; SaluteSpeech SSML для остальных из его списка.
  - Для языков без голоса `TelegramVoiceReplyService` отправляет только текст.

### 5.9 Фронтенды

- **c3ag.ru.**
  - `next-intl`, сегмент `app/[locale]/...`, `localePrefix: "as-needed"`: русские URL не меняются, английские идут как `/en/...`.
  - `messages/{ru,en}.json`.
  - Контент `lib/products.ts`, `lib/portfolio.ts`, `data/videos.json` хранится с полями `{ru, en}`.
  - `alternates.languages` (hreflang), `html lang={locale}`, sitemap для каждой локали.
  - В чат передаётся `locale`.
- **Сайт Astor и Mini App.**
  - `js/i18n.js` со словарями `i18n/{ru,en}.json`, атрибуты `data-i18n` и `data-i18n-attr`, помощник `t()`.
  - Язык выбирается так: `?lang=` → localStorage → `Telegram.WebApp.initDataUnsafe.user.language_code` → `navigator.language`.
  - Переключатель RU/EN.
  - `toLocale*` использует выбранный язык, поля `venues.json` хранятся как `{ru, en}`.
  - Новые файлы добавить в `ASSET_MANIFEST` в `server/index.js`.
  - Если нужно SEO, маркетинговым страницам нужны отдельные `/en/` копии.

### 5.10 Персональные данные

- **Yandex Translate.** Облако в РФ, по умолчанию запросы не логируются. Заголовок `x-data-logging-enabled` мы не отправляем.
- **Что уходит в машинный перевод.**
  - Тексты каталога — только шаблоны, без данных гостя.
  - Входной перевод (фаза 4) отправляет текст гостя, в котором могут быть имя и телефон. Это допустимо в рамках ограничения «ПДн остаются в РФ». Телефоны можно маскировать до перевода: номер всё равно извлекается из оригинала.
- **Язык гостя** — это признак профиля. Он хранится рядом с `telegram_profiles.language_code`, отдельное согласие не требуется (вопрос 11).

## 6. Модель данных и миграции (фазы 1 и 3, в этой ветке не создаются)

В фундаменте сознательно нет миграций. Изменение `changelog-master.yaml` параллельно с ветками MAX и сайта дало бы конфликт. Набросок для Егора по образцу `2026-10-08-telegram-voice-replies.{yaml,sql}`:

```sql
-- 2026-10-xx-guest-languages.sql (фаза 1)
CREATE TABLE IF NOT EXISTS guest_language_preferences (
    channel               VARCHAR(16)  NOT NULL,              -- TELEGRAM, WEB, MAX
    chat_id               BIGINT       NOT NULL,
    explicit_language     VARCHAR(8),                          -- /language, переключатель на сайте
    conversation_language VARCHAR(8),                          -- последний уверенно определённый язык
    platform_language     VARCHAR(16),                         -- как прислал клиент, для аналитики
    detection_method      VARCHAR(32),                         -- script, yandex-translate, speechkit
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (channel, chat_id)
);

-- 2026-10-xx-i18n-translations.sql (фаза 3)
CREATE TABLE IF NOT EXISTS i18n_translations (
    id               BIGSERIAL    PRIMARY KEY,
    source_language  VARCHAR(8)   NOT NULL,
    target_language  VARCHAR(8)   NOT NULL,
    text_format      VARCHAR(8)   NOT NULL DEFAULT 'PLAIN',    -- PLAIN | HTML
    source_hash      CHAR(64)     NOT NULL,                    -- SHA-256 шаблона, как TranslationCache.Key
    message_key      VARCHAR(160),                             -- ключ каталога, для экрана проверки
    source_text      TEXT         NOT NULL,
    translated_text  TEXT         NOT NULL,
    provider         VARCHAR(40)  NOT NULL,                    -- yandex-translate, gigachat, human
    review_status    VARCHAR(16)  NOT NULL DEFAULT 'MACHINE',  -- MACHINE | APPROVED | CORRECTED | REJECTED
    reviewed_by      VARCHAR(80),
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_i18n_translations UNIQUE (source_language, target_language, text_format, source_hash)
);
CREATE INDEX IF NOT EXISTS idx_i18n_translations_review ON i18n_translations (review_status, target_language);
```

- **Отложенные уведомления** (бронь подтверждена, счёт, отзыв после визита) отправляются вне входящего сообщения. Язык для них берётся из `guest_language_preferences` по `(channel, chat_id)`, а если там пусто — из `telegram_profiles.language_code`.
- **Входные переводы сообщений** (фаза 4) хранятся в существующих JSON-полях payload и metadata. Новых колонок не нужно.

## 7. Фундамент в ветке `feat/i18n-multilang`

Код лежит в новом пакете `museon_online.astor_butler.i18n`. Изменения аддитивные; пакет `telegram/`, `IncomingMessage`, `MessageGatewayService` и миграции не тронуты.

| Файл | Что делает |
|---|---|
| `I18nConfig` (+ `Binding`) | Настройки `astor.i18n.*`: по умолчанию выключено, `ru`, каталоги `ru,en`, fallback `en`, MT выключен, провайдер `none` |
| `I18nConfiguration` | Spring-бины: детектор, no-op переводчик, кеш в памяти, no-op хранилище, каталог `guest`, `GuestLocaleService`, `GuestTexts` |
| `LanguageTags` | Нормализация тегов, английские и русские названия языков |
| `LocaleSource`, `ResolvedLocale`, `LocaleSignals`, `GuestLocaleResolver` | Чистое правило выбора языка (5.2) |
| `LanguageDetector`, `ScriptLanguageDetector`, `DetectedLanguage` | Бесплатная детерминированная детекция (3.4) |
| `GuestLanguageKey`, `GuestLanguagePreferenceStore` | Порт хранения выбора и языка диалога (no-op) |
| `GuestLocaleService` | Язык для `IncomingMessage` из всех сигналов; `choose()` для явного выбора; `payloadWith()` |
| `MessageCatalog` | YAML-каталоги, плейсхолдеры, метки кнопок |
| `GuestTexts`, `LocalizedText`, `TextOrigin` | Текст по ключу на языке гостя с fallback и MT; `matchesLabel`; metadata |
| `MachineTranslator`, `NoOpMachineTranslator`, `TranslationRequest`, `TranslationResult`, `TextFormat` | Порт платного перевода |
| `TranslationCache`, `InMemoryTranslationCache` | Кеш переводов шаблонов |
| `ReplyLanguageInstruction` | Строка для LLM-промптов |
| `src/main/resources/i18n/guest/{ru,en}.yaml` | Каталоги, пока только сценарий отзыва |
| `application.yaml` → `astor.i18n.*` | Переменные `ASTOR_I18N_*`, всё выключено |

**Рабочий пример — `fsm/scenario/FeedbackScenario`:**
- просьба написать отзыв и благодарность берутся из каталога;
- метка кнопки «Оставить отзыв» / «Leave feedback» распознаётся через `matchesLabel`;
- в `metadata` пишется язык ответа;
- в уведомлении персоналу есть строка «Язык гостя»;
- конструктор на два аргумента сохранён для существующих тестов (только русский).

**Тесты** (`src/test/java/.../i18n/*`, `FeedbackScenarioI18nTest`): 87 новых. Весь набор — 939 тестов, 0 падений, 9 пропущено, как и до изменений.

**Рецепт переноса сценария (фаза 2):**
1. Перенести литералы в `ru.yaml` под ключи `<scenario>.<text>` и сразу добавить английские в `en.yaml`.
2. В сценарии получать `GuestTexts` через конструктор с `@Autowired`. Если у сценария есть тесты, создающие его через `new`, оставить старый конструктор с `GuestTexts.russianOnly()`.
3. В начале `handle()` один раз вызвать `ResolvedLocale locale = guestTexts.locale(incoming)`, тексты брать через `guestTexts.text(locale, KEY, Map.of("orderId", id))`.
4. Метки кнопок сравнивать через `guestTexts.matchesLabel(KEY, text, locale)`. Метки, которые сценарий кладёт в `metadata.replyKeyboardRows`, тоже брать из каталога.
5. Добавить в `metadata` `guestTexts.metadata(text)`.
6. Тесты:
   - (а) с `I18nConfig.defaults()` текст побайтно равен прежнему литералу;
   - (б) с `enabledWithoutTranslation()` и гостем `en` текст английский;
   - (в) язык без каталога получает fallback на `en`.

## 8. Фазы, задачи и оценки

Оценки даны в человеко-днях для одного разработчика с тестами и ревью. Колонка «Егор сам» показывает, нужен ли кто-то ещё.

| Фаза | Часть | Оценка | Егор сам? |
|---|---|---|---|
| 0. Фундамент | backend | сделано | — |
| 1. Язык гостя от начала до конца (ru/en, без платных вызовов) | backend | 3–4 дн | да, после слияния `feat/max-adapter` |
| 2. Перенос гостевых текстов в каталог ru/en | backend | 7–10 дн | да; английский тон желательно вычитать (вопрос 5) |
| 3. Машинный перевод длинного хвоста (Yandex Translate) | backend | 4–5 дн | код — да; ключ и бюджет — Михаил или ops |
| 4. Понимание гостя на любом языке (входной перевод) | backend | 4–6 дн | да, после фазы 3 (до неё — на фейковом переводчике) |
| 5. Голос по языку | backend | 2–4 дн | да |
| 6. c3ag.ru: ru/en | frontend | 4–6 дн | да |
| 7. Сайт Astor и Concierge Mini App: ru/en | frontend | 3–4 дн | да, после слияния параллельной работы над сайтом |
| 8. Качество и эксплуатация | оба | 2–3 дн | частично; процесс проверки переводов — с Михаилом |
| **Итого** | | **29–42 дн** | |

### Фаза 1. Язык гостя от начала до конца (backend)

- **1.1 Миграция и хранилище.** Миграция `guest_language_preferences` (раздел 6), `JdbcGuestLanguagePreferenceStore` вместо `GuestLanguagePreferenceStore.none()` в `I18nConfiguration`. Тесты на H2 или по образцу существующих JDBC-тестов.
- **1.2 Язык один раз на сообщение.** `MessageGatewayService.handle()` определяет язык один раз и кладёт его в payload (`GuestLocaleService.payloadWith`), а в `finish()` добавляет `guestLanguage` в metadata.
  - Делать после слияния `feat/max-adapter`: обе ветки трогают этот файл.
- **1.3 Команда `/language` и inline-выбор.**
  - Кнопки ru и en, затем «другой язык — просто напишите на нём». `GuestLocaleService.choose()`.
  - `setMyCommands` и `setMyDescription` для `en`.
  - Файлы: новый `telegram/command/LanguageCommand` по образцу соседних команд.
- **1.4 Язык для отложенных уведомлений.** Язык гостя по `(channel, chat_id)` для `TableReservationNotificationService` (только гостевые тексты), `GuestBillNotifier`, `VisitReviewService`, `TableReservationPendingIntentService`.
- **1.5 LLM.**
  - `ReplyLanguageInstruction` встроить в `ScenarioReplyComposer.promptFor()` вместо «на русском языке», в `MessageGatewayService.aiAssistedReply()` и в persona-файл (инструкция про язык гостя).
  - Тест: для `ru` промпт не изменился.
- **1.6 Строка «Язык гостя» для персонала** в `ManagerHelpScenario`, уведомлениях о брони и fallback-alert.
- **Готово, когда:** гость с `language_code=en` или английским текстом проходит отзыв и главное меню на английском; русский гость не видит изменений; с выключенным флагом нет ни одного изменения в тестах.

### Фаза 2. Гостевые тексты в каталоге ru/en (backend)

**Порядок — по трафику:**
1. Главное меню и клавиатуры:
   - `MainMenuScenario`;
   - `TelegramRouter.guestMainMenuKeyboard()` и `contactKeyboard()` → ключи каталога. Это минимальная правка `telegram/` после слияния MAX;
   - `ScenarioRouter.tryKeyboardShortcutRoute()` → `matchesLabel`.
2. Первый контакт и согласие: `FirstTouchScenario`, `GreetingHandler`, `StartCommand`.
3. `TableBookingScenario` и `TableBookingStepRegistry`, затем `ChangeCancelScenario`.
4. `BusinessLunchScenario` и `BusinessLunchChoices`.
5. `ManagerHelpScenario`, `MenuAssetsScenario`, `QuietGuideScenario`, `ConciergeScenario`, `PreferenceScenario`.
6. Остальные сценарии (SafePlay, SmartTip, HiddenHeart, ArtAuction, Merch, ImpactMeter, Recovery).
7. `MessageGatewayService` (голос и fallback), `MessageController` (rate limit, web lead), `TelegramVoiceReplyService`.
8. Гостевые уведомления о брони и счёте.

**Даты и числа.** Даты и числа в гостевых текстах форматировать через `DateTimeFormatter.ofPattern(..., Locale.forLanguageTag(lang))`; сейчас там `Locale("ru")`.

**Регэкспы и ключевые слова** в этих файлах остаются русскими: это вход, а не выход.

**Объём:** около 260 однострочных фраз и до 80 текстовых блоков примерно в 40 файлах. Отдельный PR на каждую группу.

**Готово, когда:** на каждый перенесённый сценарий есть тест «флаг выключен — тот же текст» и тест «en». `GuestCatalogConsistencyTest` зелёный.

### Фаза 3. Машинный перевод (backend)

- **3.1** `YandexTranslateMachineTranslator`: `java.net.http`, по образцу `YandexSpeechKitSpeechToText` — без ретраев на 4xx, одна попытка после 5xx, сообщения об ошибках без текста и без ключа. Ветка `provider=yandex` в `I18nConfiguration.machineTranslator()`. Тесты на фейковом `HttpClient`.
- **3.2** Миграция `i18n_translations` и `JdbcTranslationCache`. Статус `APPROVED`/`CORRECTED` важнее машинного перевода.
- **3.3** Команда или эндпоинт для персонала: список `MACHINE`-переводов по языку, исправление, одобрение.
- **3.4** Job предварительного перевода каталога на топ-языки и выгрузка в YAML для вычитки.
- **3.5** Метрики: символы по языкам, ошибки, задержка. Лимиты 1 млн символов в час и 20 вызовов в секунду — заранее попросить поддержку их поднять.
- **Готово, когда:** гость `de` видит немецкие тексты сценария; повторный текст берётся из кеша; при недоступном Yandex приходит английский текст без ошибки.

### Фаза 4. Понимание на любом языке (backend)

- **4.1** Входной перевод на `ru` в `MessageGatewayService` перед `ScenarioRouter`, если язык сообщения не `ru` (`translate` без `sourceLanguageCode` возвращает `detectedLanguageCode`). Оригинал сохраняется в payload.
- **4.2** Слоты: телефон, числа и время извлекаются из оригинала (`GuestPartyText`, `GuestMoneyText`, `GuestDateText` — проверить на оригинале и на переводе), подтверждение отправляется на языке гостя.
- **4.3** `LlmUnderstandingService`: языконезависимый промпт.
- **4.4** Персонал видит оригинал и русский перевод.
- **4.5** Пополнить golden corpus `understanding/guest-input-golden-corpus.jsonl` примерами на en, de, zh, tr и kk.
- **Готово, когда:** «Table for 4 tomorrow at 7 pm» проходит бронь так же, как «стол на 4 завтра в 19:00».

### Фаза 5. Голос (backend)

- **5.1** STT: язык гостя в `lang`, если он из списка SpeechKit, иначе `ru`. Затем v3 с `auto`.
- **5.2** TTS: `synthesize(text, language)`, выбор голоса и провайдера по языку. Для языков без голоса ответ только текстом.
- **Готово, когда:** гость `de` получает немецкий голосовой ответ, гость `zh` — только текст.

### Фаза 6. c3ag.ru (frontend)

- `next-intl` с `[locale]`, `messages/{ru,en}.json`, контент `{ru, en}`, hreflang, sitemap, переключатель.
- `clio-persona.ts` с учётом языка, `locale` в запросе чата.

**Готово, когда:** `/en/...` полностью на английском, русские URL не изменились, Lighthouse SEO без регрессий.

### Фаза 7. Сайт Astor и Mini App (frontend)

- `js/i18n.js`, словари, `data-i18n`, переключатель, язык из Telegram в Mini App.
- `venues.json` с полями `{ru, en}`, `ASSET_MANIFEST`.
- Виджет передаёт `locale`.

### Фаза 8. Качество и эксплуатация

- Дашборд по языкам: гости, источник языка, `TextOrigin`, ошибки MT.
- Проверка, что ответ LLM пришёл на нужном языке: детектор плюс повтор или fallback.
- Оценка качества для 10–20 языков длинного хвоста: обратный перевод и выборка носителями до того, как обещать эти языки.
- Бюджетный алерт по Yandex Translate.

## 9. Риски

| Риск | Что делаем |
|---|---|
| 150 языков машинным переводом недостижимы (Yandex около 110), ожидания расходятся с реальностью | Честная формулировка уровней A/B/C (раздел 1) |
| Качество GigaChat на редких языках неизвестно, ответ может прийти на смеси языков | Проверка языка ответа детектором, повтор или fallback, оценка качества до анонса (фаза 8) |
| Входной перевод портит имена, даты, числа, и бронь получает неверные слоты | Слоты из оригинала, подтверждение на языке гостя (4.2) |
| Локализованные метки кнопок не распознаются, и ломается роутинг | `matchesLabel` с каталогами и MT, тесты на каждую клавиатуру (фаза 2) |
| Конфликты с `feat/max-adapter` в `MessageGatewayService`, `IncomingMessage` и `telegram/` | Фундамент их не трогает; фазы 1.2 и 2.1 делать после слияния MAX |
| Задержка: машинный перевод добавляет 100–300 мс | Кеш шаблонов, предварительный перевод топ-языков, таймаут около 2 с с fallback |
| Квоты Yandex: 1 млн символов в час, 20 вызовов в секунду | Хватает с запасом; поднять до запуска |
| ПДн: текст гостя уходит в Yandex Translate | РФ, без логирования (не отправлять `x-data-logging-enabled`), маскирование телефонов; для GigaChat место обработки не подтверждено договором (вопрос 11) |
| Тон бренда в английском (премиальный hospitality) | Вычитка en носителем или редактором (вопрос 5) |
| Выбор fallback (ru или en) для языков СНГ — чувствительная тема | Настройка `default-fallback-languages`, решение за Михаилом (вопрос 4) |
| Детектор по алфавиту путает болгарский с русским, а латиницу без признаков не определяет | Порог уверенности; язык платформы и диалога; Yandex detect в фазе 4 |
| Telegram `language_code` — это язык интерфейса, а не язык гостя | Явный выбор и язык сообщения важнее (5.2) |

## 10. Открытые вопросы к Михаилу

1. **«150+»:** принимаем формулировку «~110 языков машинного перевода плюс бета для остальных через GigaChat»? Или ограничиваемся списком Yandex?
2. Можно ли переводить системные тексты через **GigaChat** для языков вне Yandex, если качество не подтверждено? Или для них оставить английский fallback?
3. **Бюджет и доступ к Yandex Translate.** Оценка: около 2,3 тыс. ₽ разово на весь каталог и около 100–500 ₽ в месяц на входящие сообщения. Кто создаёт сервисный аккаунт с ролью `ai.translate.user` и кладёт ключ в секреты сервера?
4. **Fallback** для языков без перевода: английский для всех или русский для kk, uz, ky, tg, az, hy, be и других (`ASTOR_I18N_DEFAULT_FALLBACK_LANGUAGES`)?
5. **Приоритетные языки для вычитки** (уровень A). Предложение: en, zh, tr, kk, uz, de, ar, fa, hi, fr. Кто вычитывает: носители, агентство, сам Михаил для en?
6. **Голос:** использовать SaluteSpeech для ответов на es, fr, it, pt, pl, nl, ky, если основной TTS — SpeechKit? Или голос только для 6 языков SpeechKit?
7. **Персонал:** всегда русский? Переводить сообщения гостя персоналу автоматически (платно, по умолчанию да в фазе 4)?
8. **Сайты:** только ru/en или сразу добавить zh или другие? Нужны ли SEO-версии `/en/` для сайта Astor?
9. **Контент:** переводить меню (PDF) и RAG-карточки на английский вручную или полагаться на ответы LLM по русскому RAG?
10. **Алиса:** остаётся только русской?
11. **ПДн:** достаточно ли текущего согласия на хранение языка гостя и отправку его текста в Yandex Translate? Нужна ли юридическая проверка GigaChat (в соглашении нет явного пункта об обработке в РФ)?

## 11. Как запускать

```bash
export JAVA_HOME=/path/to/jdk-25
mvn -o -B test                                   # весь набор; итоги смотреть в target/surefire-reports
mvn -o -B test -Dtest='museon_online.astor_butler.i18n.*Test,FeedbackScenario*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

Включить локально (без платных вызовов): `ASTOR_I18N_ENABLED=true`. Все переменные описаны в `application.yaml` → `astor.i18n.*`.

## 12. Источники

- Yandex Translate:
  - https://aistudio.yandex.ru/docs/en/translate/concepts/supported-languages
  - https://aistudio.yandex.ru/docs/en/translate/api-ref/Translation/translate
  - https://aistudio.yandex.ru/docs/en/translate/api-ref/Translation/detectLanguage
  - https://aistudio.yandex.ru/docs/en/translate/api-ref/Translation/listLanguages
  - https://aistudio.yandex.ru/docs/en/translate/concepts/glossary
  - https://aistudio.yandex.ru/docs/ru/translate/pricing
  - https://aistudio.yandex.ru/docs/ru/ai-studio/concepts/limits
  - https://aistudio.yandex.ru/docs/en/translate/api-ref/support-headers
  - https://aistudio.yandex.ru/docs/en/translate/api-ref/authentication
  - https://yandex.cloud/en/solutions/152-fz
  - список потребительского translate.yandex: https://www.yandex.com/support/translate-desktop/ru/supported-langs.md
- SpeechKit:
  - https://aistudio.yandex.ru/docs/en/speechkit/stt/models
  - https://aistudio.yandex.ru/docs/en/speechkit/tts/voices
  - https://aistudio.yandex.ru/docs/ru/speechkit/pricing
  - https://aistudio.yandex.ru/docs/en/speechkit/concepts/limits
  - https://aistudio.yandex.ru/docs/en/speechkit/concepts/support-headers
- GigaChat:
  - https://developers.sber.ru/docs/ru/gigachat/models/gigachat-2-max
  - https://developers.sber.ru/docs/ru/gigachat/api/reference/rest/gigachat-api
  - https://developers.sber.ru/docs/ru/gigachat/tariffs/legal-tariffs
  - https://developers.sber.ru/docs/ru/policies/gigachat-agreement/corporate-clients-beta
  - https://huggingface.co/ai-sage/GigaChat3.1-10B-A1.8B
- SaluteSpeech:
  - https://developers.sber.ru/docs/ru/salutespeech/guides/recognition/improvement
  - https://developers.sber.ru/docs/ru/salutespeech/guides/synthesis/ssml/language
- Telegram Bot API: https://core.telegram.org/bots/api#user
- MAX Bot API: https://dev.max.ru/docs-api/objects/User
