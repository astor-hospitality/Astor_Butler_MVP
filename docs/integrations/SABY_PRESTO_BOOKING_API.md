# Saby Presto: API бронирования столов

Дата: 2026-10-05. Автор: Дима (`@dmtrshm`). Для issue [#24](https://github.com/astor-hospitality/Astor_Butler_MVP/issues/24), для Михаила и для ревью adapter.

Документ фиксирует, что Saby Presto предоставляет для бронирования столов по официальной документации, что из этого не подтверждено и какие решения приняты для adapter в Butler. План работ по MR лежит рядом: [SABY_INTEGRATION_PLAN.md](SABY_INTEGRATION_PLAN.md).

Пометки: **подтверждено** — метод и поля описаны на официальной странице Saby; **не подтверждено** — в документации нет или проверяется только на тестовом аккаунте.

## 1. Итог

- У Saby Presto **есть** публичный API бронирования столов: [«Забронировать столик через API в Presto»](https://saby.ru/help/integration/api/app_presto/Presto_reserv). Это закрывает вопрос из черновиков договора о наличии API.
- Есть чтение (точка продаж, свободное время, столы), создание, чтение, изменение, отмена брони и статус.
- Нет ключа идемпотентности при создании, не описан ответ на создание, не раскрыты значения статусов. Без тестового аккаунта adapter проверяется только на моках.
- Тестовая среда, лимиты запросов и тариф API в публичной документации не описаны.

## 2. Подготовка на стороне Saby

По [странице раздела](https://saby.ru/help/integration/api/app_presto/Presto_reserv):

1. Настроить точку продаж и сервис онлайн-бронирования в Presto ([настройка онлайн-бронирования](https://saby.ru/help/presto/reservation/set)).
2. Добавить приложение в Saby и настроить сервисную авторизацию.
3. Для предзаказа блюд — опубликовать каталог и прайс-лист. Для брони без блюд не нужно.

## 3. Авторизация — подтверждено

Источник: [Сервисная авторизация](https://saby.ru/help/integration/api/auth/service).

| Что | Значение |
| --- | --- |
| Получить токен | `POST https://online.sbis.ru/oauth/service/` |
| Тело | `app_client_id`, `app_secret`, `secret_key` — из настроек приложения в Saby |
| Ответ | `token` |
| Передача в запросах | Заголовок `X-SBISAccessToken: <token>`, без него команды не выполняются |
| Завершить сессию | `POST` на тот же адрес с параметром `token` |
| Время жизни токена | Не указано. Adapter кеширует токен и запрашивает новый при `401` |

Значения ключей — только в серверном окружении. Не в git, issue или Telegram.

## 4. Методы

Формат JSON, кодировка UTF-8, HTTPS.

### 4.1. Точка продаж — подтверждено

Источник: [Получить точку продаж](https://saby.ru/help/integration/api/app_presto/Presto_reserv/salie_point).

`GET https://api.sbis.ru/retail/point/list`

| Параметр | Тип | Замечание |
| --- | --- | --- |
| `product` | string | `restaurant` для Presto-бронирования |
| `pointId` | integer | Необязателен, фильтр по точке |
| `withPhones`, `withPrices`, `withSchedule` | boolean | Дополнительные данные |
| `page`, `pageSize` | integer | `pageSize` от 0 до 500 |

Ответ: `id` точки (это `pointId` для остальных запросов), `name`, `address`, `phone`, `product`, `worktime`, `schedule`, `hasMore`.

### 4.2. Свободное время — подтверждено, кроме смысла интервалов

Источник: [Получить время бронирования](https://saby.ru/help/integration/api/app_presto/Presto_reserv/time).

`GET https://api.sbis.ru/retail/booking/calendar`

| Параметр | Тип | Замечание |
| --- | --- | --- |
| `pointId` | integer | Обязателен |
| `fromDate`, `toDate` | string | Обязательны, формат `дд.мм.гггг` |

Ответ: даты, по каждой — залы (`hallId`) и `intervals`: идентификаторы получасовых интервалов от 0 до 47. В документации: «0 — 00:30, 1 — 01:00». Это похоже на опечатку (тогда 47 — 24:00), проверяется только на тестовом аккаунте. Точная вложенность JSON на странице не показана, примеры даны файлами.

### 4.3. Столы — подтверждено

Источник: [Получить список столиков](https://saby.ru/help/integration/api/app_presto/Presto_reserv/table).

`GET https://api.sbis.ru/retail/hall/list`

| Параметр | Тип | Замечание |
| --- | --- | --- |
| `pointId` | integer | Обязателен |
| `date` | string | Обязателен, `ГГГГ-ММ-ДД ЧЧ:ММ:СС` — на это время отдаётся занятость |
| `hallId` | integer | Необязателен |

Ответ: залы (`id`, `name`, `active`), в каждом столы (`id`, `name`, `capacity`, `busy`, `isBookingLocked`, `kind`, `type`, `visible`, `position`), флаг `hasmore`.

### 4.4. Создать бронь — подтверждено

`POST https://api.sbis.ru/retail/order/create`

| Параметр | Обязателен | Тип | Замечание |
| --- | --- | --- | --- |
| `product` | да | string | `restaurant` |
| `pointId` | да | string | Из 4.1 |
| `datetime` | да | string | `ГГГГ-ММ-ДД ЧЧ:ММ:СС`, местное время точки |
| `comment` | нет | string | Примечание к заказу |
| `customer.name` | да | string | |
| `customer.phone` | да | string | |
| `customer.lastname`, `patronymic`, `email`, `externalId` | нет | string | `externalId` — ID клиента в Saby, не наш ключ |
| `booking.visitors` | да | integer | Число гостей |
| `booking.hall` | да* | integer | Из 4.2 или 4.3 |
| `booking.table` | нет | integer | Из 4.3 |
| `booking.woTable` | нет | boolean | `true` — стол выбирает администратор, зал и стол можно не указывать |
| `nomenclatures[]` | нет | array | Предзаказ блюд. Butler пока не передаёт |

Пример из документации:

```json
{
  "product": "restaurant",
  "pointId": 206,
  "comment": "прошу поставить на стол несколько свечей",
  "customer": { "name": "Иван", "lastname": "Черкасов", "email": "ivan@post.com", "phone": "88005553535" },
  "datetime": "2022-02-15 17:00:00",
  "booking": { "hall": 271, "table": 3037, "visitors": 2 }
}
```

**Ответ на создание в документации не описан.** Где приходит UUID брони (`externalId`), выясняется первым запросом на тестовой точке.

### 4.5. Операции с бронью — подтверждено

`{externalId}` — UUID брони в Saby.

| Операция | Метод |
| --- | --- |
| Информация о брони | `GET https://api.sbis.ru/retail/order/{externalId}` |
| Изменить | `PUT https://api.sbis.ru/retail/order/{externalId}/update` — передать весь заказ с изменениями |
| Отменить | `PUT https://api.sbis.ru/retail/order/{externalId}/cancel` |
| Статус | `GET https://api.sbis.ru/retail/order/{externalId}/state` |
| Статус нескольких | `GET https://api.sbis.ru/retail/order/states?externalIds=[...]` |
| Ссылка на оплату | `GET https://api.sbis.ru/retail/order/{externalId}/payment-link` — Butler не использует |

Ответ статуса: `state` (статус брони), `payState` (статус оплаты), `payments[]`, `productState` (продуктовый статус). **Значения `state` и `productState` на странице свёрнуты и пока не выписаны.**

### 4.6. Уведомления — не подтверждено для броней

Источник: [Настроить оповещения об изменениях](https://saby.ru/help/integration/api/app_presto/webhook).

Есть триггер «Изменения информации о продажах» с действием «Отправить HTTP-запрос». Срабатывает ли он на смену статуса брони, не указано. До проверки статус берём опросом `order/states`.

## 5. Сопоставление с Butler

| Butler | Saby | Статус |
| --- | --- | --- |
| `ExternalReservationProvider.status()` | Наличие ключей и `pointId`, без вызова API | Есть в коде как заглушка |
| `checkAvailability(request)` | `hall/list` на время начала: свободный стол — `!busy && !isBookingLocked && visible && capacity >= partySize` | Метод есть |
| `reserve(command, idempotencyKey)` | `order/create` | Метод есть, нет ключа идемпотентности |
| Статус брони | `order/{id}/state`, `order/states` | Нет в интерфейсе Butler |
| Отмена | `order/{id}/cancel` | Нет в интерфейсе Butler |
| Изменение | `order/{id}/update` | Нет в интерфейсе Butler |
| `sbisExternalId` в заявке | UUID брони из ответа `create` | Поле есть, не заполняется |
| `venueCode` (`AERIS`) | `pointId` | Маппинг через настройку |
| `tableCode` Butler | `booking.table` | Маппинга нет, используем `woTable=true` |
| `guestName`, `guestPhone` | `customer.name`, `customer.phone` | В Butler необязательны, в Saby обязательны |
| Время `Instant` (UTC) | `datetime` в местном времени точки | Часовой пояс через настройку, по умолчанию `Asia/Yekaterinburg` |

## 6. Решения для adapter

1. **POST `create` не повторяется.** У Saby нет ключа идемпотентности, повтор может создать вторую бронь. Если ответ не получен после отправки, результат — «неизвестен, проверить вручную в Saby», заявка уходит хостес.
2. **Защита от дублей — на стороне Butler.** UUID брони сохраняется в `sbisExternalId` до любой повторной попытки. До подключения к `TableReservationService` adapter держит кеш `ключ → результат` в памяти процесса.
3. **Номер заявки Butler — в `comment`** (`Astor Butler #<номер>`), чтобы администратор мог сверить бронь вручную.
4. **«Создано в Saby» не значит «подтверждено».** Пока не известны значения `state` и не согласован порядок подтверждения, гостю финальную бронь подтверждает хостес, как сейчас.
5. **Запись выключена отдельным флагом.** Чтение включается ключами, создание и отмена — только дополнительным `SABY_WRITE_ENABLED=true`.
6. **Повторы только для GET и авторизации** — при `5xx`, `429` и сетевых ошибках, с таймаутом на каждый запрос.
7. **Стол выбирает администратор** (`woTable=true`), пока нет маппинга столов Butler на `tableId` Saby.
8. **Без имени и телефона гостя бронь в Saby не создаётся** — заявка идёт только хостес.
9. **Реальные гостевые данные в тестах не используются.** Smoke-тест записи — только на тестовой точке, созданная бронь сразу отменяется.

## 7. Блокеры

| # | Блокер | Что блокирует | Кто снимает | Статус |
| --- | --- | --- | --- | --- |
| B1 | Нет приложения в Saby: `app_client_id`, `app_secret`, `secret_key` | Любые реальные вызовы и smoke-тест | Дима и администратор аккаунта Saby ресторана | Открыт |
| B2 | Не включено онлайн-бронирование в Presto, неизвестен `pointId` тестовой точки | Smoke-тест чтения и записи | Администратор Saby | Открыт |
| B3 | Нет тестовой точки или зала, можно задеть реальные брони | Smoke-тест записи (MR3) | Администратор Saby и Михаил | Открыт |
| B4 | Не описан ответ на `order/create`, неизвестно поле с UUID брони | Разбор ответа в MR3 | Smoke-тест на тестовом аккаунте | Открыт |
| B5 | Не раскрыты значения `state` и `productState` | Понимание, что значит «подтверждено» и «отменено» | Дима: выписать со страницы документации | Открыт |
| B6 | У `create` нет ключа идемпотентности | Безопасные повторы | Решение 1 и 2 из раздела 6 | Обходится |
| B7 | Смысл интервалов календаря «0 — 00:30» | Использование `booking/calendar` | Smoke-тест | Обходится: доступность через `hall/list` |
| B8 | Неизвестны лимиты, тариф и тестовая среда | Нагрузка в проде | Запрос в поддержку Saby | Открыт, для MVP не критично |
| B9 | У Димы нет записи в репозиторий | Открытие PR | Михаил: добавить в репозиторий, иначе через fork | Открыт |
| B10 | `CLAUDE.md` запрещает Claude править `src/main/**` | MR2–MR4 | Явное разрешение Димы или Михаила, иначе Codex | Открыт |
| B11 | Подключение к `TableReservationService` — зона Михаила (PR #8) | MR4 | Михаил | Открыт |
| B12 | Не решено, кто подтверждает бронь: хостес или Saby | Итоговый статус для гостя | Михаил и заказчик, см. [приложение 2 к договору](../commercial/ASTOR_BUTLER_SABY_ANNEX_2_API_WORKFLOW_RU.md), §8 | Открыт |

## 8. Настройки adapter

Значения только в серверном окружении. Имена заменяют заглушечные из [CONCIERGE_BUTLER_HANDOFF_DRAFT.md](../contracts/CONCIERGE_BUTLER_HANDOFF_DRAFT.md) §3 и вводятся в MR2.

```text
ASTOR_SABY_ENABLED        включить провайдер (чтение)
SABY_WRITE_ENABLED        разрешить создание и отмену брони, по умолчанию false
SABY_API_BASE_URL         по умолчанию https://api.sbis.ru
SABY_AUTH_URL             по умолчанию https://online.sbis.ru/oauth/service/
SABY_APP_CLIENT_ID
SABY_APP_SECRET
SABY_SECRET_KEY
SABY_POINT_ID
SABY_HALL_ID              необязателен
SABY_VENUE_CODE           по умолчанию AERIS
SABY_ZONE_ID              по умолчанию Asia/Yekaterinburg
SABY_TIMEOUT_MS           по умолчанию 3000
SABY_MAX_RETRIES          по умолчанию 1, только для GET и авторизации
```

## 9. Источники

- [API для Presto](https://saby.ru/help/integration/api/app_presto)
- [Забронировать столик через API в Presto](https://saby.ru/help/integration/api/app_presto/Presto_reserv)
- [Забронировать столик (order/create)](https://saby.ru/help/integration/api/app_presto/Presto_reserv/order)
- [Получить точку продаж](https://saby.ru/help/integration/api/app_presto/Presto_reserv/salie_point)
- [Получить время бронирования](https://saby.ru/help/integration/api/app_presto/Presto_reserv/time)
- [Получить список столиков](https://saby.ru/help/integration/api/app_presto/Presto_reserv/table)
- [Сервисная авторизация](https://saby.ru/help/integration/api/auth/service)
- [Настроить оповещения об изменениях](https://saby.ru/help/integration/api/app_presto/webhook)
- [Бронирование столиков в Presto](https://saby.ru/help/presto/reservation)
