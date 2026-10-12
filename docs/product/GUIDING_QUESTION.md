# Направляющий вопрос в конце ответа бота (#101)

Задача: [#101](https://github.com/astor-hospitality/Astor_Butler_MVP/issues/101). Код: `fsm/reply/GuidingQuestionPolicy`,
`fsm/reply/GuidingQuestionAppender`, `fsm/reply/RedisGuidingQuestionMemory`; точка подключения — `MessageGatewayService.handle`,
сразу после `ScenarioRouter.route(...)`. Флаг `astor.dialogue.guiding-question.enabled` (`ASTOR_GUIDING_QUESTION_ENABLED`),
**по умолчанию выключен**: с выключенным флагом ответ возвращается тем же объектом, байт в байт.

## Идея и границы

Ответ бота по возможности завершается вопросом, который ведёт диалог к цели сценария: уточнить недостающий слот, выбрать следующий
шаг или предложить занятие, пока команда подтверждает заявку. Границы из задачи и бренд-гайда:

- допустимость и формулировку вопроса определяет FSM и доменная политика — таблица ниже; модель вопрос не сочиняет;
- без давления, без неподтверждённых цен, наличия и услуг, без скрытого профиля гостя, без подмены подтверждения человеком;
- память о прошлых диалогах не используется: единственное, что помнит механизм, — последний заданный им вопрос в этом чате;
- если сценарий завершён и гость дома (`READY_FOR_DIALOG`), вопрос не задаётся.

## Куда подключено и куда — нет

`MessageGatewayService.handle` вызывает `GuidingQuestionAppender.append(incoming, routed)` только для ответа, который вернул
`ScenarioRouter`. Не проходят через него: команды и ответы в служебных, групповых и ops-чатах, карточки персонала (хостес,
менеджер, staff-чат), очки, повторы голосового ввода, AI-fallback и ответ модели. `ScenarioReplyComposer` (LLM-переписывание
approved fallback для `MENU_ASSETS`, `QUIET_GUIDE`, `SAFE_PLAY`) работает раньше и на вопрос не влияет: эти сценарии в таблице
«молчащие».

## Правила дописывания

1. Флаг выключен → ответ не трогается.
2. Не трогаются: пустой ответ, `fallback`, запрос контакта (`requestContact`), ответ на паузе Butler (`BUTLER_PAUSED`, #134),
   ответ не на русском (`metadata.replyLanguage` ≠ `ru`, i18n), ответ с неизвестным `nextState`.
3. Сценарий — из `metadata.scenario`; если сценарий себя не отметил (бронь, меню, видео-тур, главное меню, first touch) —
   владелец состояния по префиксу (`TABLE_BOOKING_*` → `TABLE_BOOKING`, `BUSINESS_LUNCH_*` → `BUSINESS_LUNCH`, `TIP_*` →
   `SMART_TIP`, `DONATION_*` → `IMPACT_METER`, `AUCTION_*` → `ART_AUCTION`, `PREFERENCE_*` → `PREFERENCE_MAP` и т.д.). Отметка
   сценария важнее префикса: `CHANGE_CANCEL` держит гостя в `TABLE_BOOKING_CHANGE_REQUESTED`, ланч — в `TABLE_BOOKING_COLLECT_*`.
4. Вопрос берётся из таблицы по сценарию, состоянию после ответа (`nextState`) и, где нужно, по действиям ответа (шаг).
   Нет записи → вопроса нет.
5. Ответ уже заканчивается `?` → ничего не дописывается (сценарии брони и сами часто спрашивают: «На какой день держим стол?»).
6. Тот же вопрос был предыдущим сообщением бота в этом чате → не повторяется (повторный запрос того же слота после непонятого
   ответа не звучит как «не услышал»). Другой вопрос или другой шаг — задаётся.
7. Вопрос дописывается через пустую строку, остальные поля ответа (`nextState`, `actions`, `html`, клавиатура, alert) не меняются;
   в `metadata` добавляется `guidingQuestion` — для Kafka, timeline и аналитики.

Память: Redis-ключ `{prefix}:fsm:telegram:{chatId}:guiding-question`, TTL `astor.dialogue.guiding-question.memory-ttl-seconds`
(1800 с). Запись — когда вопрос дописан или сценарий сам задал ровно его; очистка — когда для шага вопроса нет. Недоступный Redis
не ломает ответ: вопрос дописывается, повтор в этом случае не отслеживается.

## Таблица

Формулировки — константы `GuidingQuestionPolicy`; тест проверяет, что каждая заканчивается `?`, короче 70 символов и не содержит
цифр, валюты и слов про цену, наличие и срочность.

| Сценарий | Состояние после ответа | Шаг (действия ответа) | Вопрос |
| --- | --- | --- | --- |
| `TABLE_BOOKING` | `TABLE_BOOKING_COLLECT_DATE` | любой | «На какую дату?» |
| `TABLE_BOOKING` | `TABLE_BOOKING_COLLECT_TIME` | любой | «На какое время?» |
| `TABLE_BOOKING` | `TABLE_BOOKING_COLLECT_PARTY_SIZE` | любой | «Сколько вас будет?» |
| `TABLE_BOOKING` | `TABLE_BOOKING_WAIT_HOSTESS_CONFIRMATION` | любой (заявка ушла хостес) | «Хотите, расскажу про меню, пока команда подтверждает?» |
| `TABLE_BOOKING` | `TABLE_BOOKING_INTENT`, `_COLLECT_SEATING_PREFERENCE`, `_SHOW_PLAN`, `_WAIT_TABLE_SELECTION`, `_CONFIRMATION`, `_CONFIRMED`, `_REJECTED`, `_CANCELLED`, `_CHANGE_REQUESTED`, `READY_FOR_DIALOG` | — | нет (план зала и сводка уже содержат свой выбор; сценарий завершён) |
| `BUSINESS_LUNCH` | `BUSINESS_LUNCH_CHOOSE_SET`, `BUSINESS_LUNCH_CHOOSE_DISH` | любой (меню показано) | «Что выбираете?» |
| `BUSINESS_LUNCH` | `BUSINESS_LUNCH_COLLECT_PARTY_SIZE` | любой | «Сколько вас будет?» |
| `BUSINESS_LUNCH` | `BUSINESS_LUNCH_COLLECT_DATE` | любой | «На какую дату?» |
| `BUSINESS_LUNCH` | `BUSINESS_LUNCH_COLLECT_TIME` | любой | «На какое время?» |
| `BUSINESS_LUNCH` | `TABLE_BOOKING_COLLECT_DATE` / `_TIME` / `_PARTY_SIZE` (шаги стола, отмеченные ланчем) | любой | те же три вопроса |
| `BUSINESS_LUNCH` | `BUSINESS_LUNCH_CONFIRMATION`, `TABLE_BOOKING_INTENT`, `READY_FOR_DIALOG`, `AI_FALLBACK` | — | нет |
| `CHANGE_CANCEL` | `TABLE_BOOKING_CHANGE_REQUESTED` | заявка ещё не выбрана или действие не выбрано (`ASK_ACTIVE_ORDER_REFERENCE`, `ASK_CHANGE_ACTION`) | «Что меняем: время, стол или число гостей?» |
| `CHANGE_CANCEL` | `TABLE_BOOKING_CHANGE_REQUESTED` | выбор среди нескольких заявок (`ASK_ORDER_SELECTION`) или действие уже выбрано (`CHANGE_TIME`, `CHANGE_DATE`, `CHANGE_TABLE`, `CHANGE_PARTY_SIZE`) | нет |
| `CHANGE_CANCEL` | `READY_FOR_DIALOG` | — | нет |

Сценарии с явным «нет» для любого состояния (причина — в комментарии к `SILENT_SCENARIOS`):

| Сценарий | Почему без вопроса |
| --- | --- |
| `FIRST_TOUCH` | согласие и контакт — свой вопрос и кнопки |
| `MAIN_MENU`, `RECOVERY` | гость дома / восстановление после непонятого ввода |
| `EVENT_BOOKING` | детали мероприятия — одним свободным сообщением; после отправки — см. открытые вопросы |
| `MANAGER_HELP`, `CONCIERGE` | гость позвал человека, дальше отвечает команда — не подталкиваем |
| `FEEDBACK`, `PREFERENCE_MAP` | отзыв и предпочтение — только из явных слов гостя |
| `SAFE_PLAY`, `MERCH` | ответ уже содержит свой следующий шаг; запрос уходит команде |
| `MENU_ASSETS`, `QUIET_GUIDE` | выдача меню / видео-тура; уточнение «какое меню» — свой вопрос |
| `SMART_TIP`, `IMPACT_METER`, `ART_AUCTION` | деньги: чаевые, пожертвование, ставка — без подталкивания |
| `HIDDEN_HEART` | игра со своими репликами |

Тест `GuidingQuestionPolicyTest.everyScenarioFromTheSourcesHasAnExplicitEntryOrAnExplicitNone` перечисляет id сценариев по
`src/main/java/.../fsm/scenario/*Scenario.java` и требует для каждого либо правила, либо явного «нет»; новый сценарий без записи
роняет тест.

## Тесты

- `GuidingQuestionPolicyTest` — таблица: недостающий слот, «после отправки заявки», завершённый сценарий, шаги выбора стола,
  ланч после меню, `CHANGE_CANCEL` до/после выбора действия, молчащие и неизвестные сценарии, владелец состояния, формулировки.
- `GuidingQuestionAppenderTest` — вопрос есть на незавершённом шаге; нет после завершения; нет, если ответ уже заканчивается `?`;
  не повторяет только что заданный, но задаёт после другого шага; флаг выключен — тот же объект и ни одного обращения к памяти;
  отметка сценария важнее префикса состояния; пропуск fallback / паузы / запроса контакта / чужого языка; недоступный Redis;
  HTML и остальные поля сохраняются.
- `MessageGatewayServiceTest` — ответ `TableBookingScenario` через реальный `ScenarioRouter` получает вопрос при включённом флаге;
  без бина и с выключенным флагом текст и `metadata` те же.

## Открытые вопросы (follow-up)

- Прогон по golden corpus (`understanding/guest-input-golden-corpus.jsonl`): доля ответов с вопросом и отсутствие запрещённых
  формулировок — отдельный шаг после merge, в этом PR не выполнялся.
- Кандидаты на расширение таблицы после прогона: `EVENT_BOOKING_SENT` и `SAFE_PLAY_WAIT_TEAM_CONFIRMATION` («пока команда
  подтверждает»), `MENU_ASSETS_CLARIFY`, `EVENT_BOOKING_COLLECT_DETAILS`.
- `ScenarioReplyComposer` при включённом LLM может сам закончить ответ вопросом — правило «уже заканчивается `?`» это покрывает,
  но долю таких ответов стоит посмотреть на корпусе.
- i18n: вопросы только на русском; при `replyLanguage` ≠ `ru` вопрос не дописывается — перевод через `GuestTexts` при
  необходимости.
