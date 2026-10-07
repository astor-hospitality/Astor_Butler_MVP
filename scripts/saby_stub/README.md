# Saby stub: стенд-двойник Presto и записывающий прокси

Нужен, пока нет ключей от Saby, и как страховка показа: вся цепочка «гость в Telegram → FSM → бронь в "Presto" →
персонал подтверждает → гостю "подтверждено"» проверяется без настоящего Saby. Один файл состояния в памяти, без зависимостей.

## Режим `stub` (по умолчанию)

    node scripts/saby_stub/server.mjs            # :8090

Отвечает по документации Saby (saby.ru/help/integration/api/app_presto, снято 06.10.2026):

| Маршрут | Что |
| --- | --- |
| `POST /oauth/service/` | сервисная авторизация → `{token}`; дальше нужен заголовок `X-SBISAccessToken` |
| `GET /retail/point/list` | одна точка 206 «AERIS (stub)» |
| `GET /retail/booking/calendar` | получасовые интервалы по дням, зал 271 |
| `GET /retail/hall/list?pointId&date` | 9 столов («1»…«8», «Бар» заблокирован) с `busy`/`capacity` по текущим броням |
| `POST /retail/order/create` | проверяет точку, дату, гостя, стол (занят/мал/заблокирован), блюда → `{externalId}` **(форма — наше предположение, B4)** |
| `GET /retail/order/{id}` | вся бронь **(форма — наше предположение)** |
| `GET /retail/order/{id}/state`, `/states?externalIds=[…]` | `state` 10 → 20 → 220, `productState` 1000 → 1001 → 1998 — по документации |
| `PUT /retail/order/{id}/update` | вся бронь заново; отменённую не трогает (409) |
| `PUT /retail/order/{id}/cancel` | 220 / 1998 |
| `GET /retail/nomenclature/price-list` | прайс-лист 4 |
| `GET /retail/v2/nomenclature/list?searchString` | блюда из `business-lunch/aeris.json` и `menu/aeris/site/kitchen.json` |

Что делает персонал в Presto — через админ-маршруты без токена:

    POST /__admin/confirm/{id}     принять бронь (state 20)
    POST /__admin/seat/{id}        {"table": 3035} — посадить woTable-бронь на стол
    POST /__admin/cancel/{id}      снять бронь
    GET  /__admin/orders           все брони
    POST /__admin/reset            очистить

`SABY_STUB_AUTO_CONFIRM_SECONDS=30` — брони подтверждаются сами через 30 с (занятый персонал); 0 — только руками.

## На стенде

    docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.saby-stub.yml --profile telegram up -d saby-stub aeris-astor-butler-bot

Оверлей включает у бота `ASTOR_SABY_ENABLED`, `SABY_WRITE_ENABLED` и направляет `SABY_API_BASE_URL`/`SABY_AUTH_URL` в стаб.
Перед подключением к настоящему Saby оверлей **убрать** и поднять бота без него. В продакшен-профиль не входит.

## Режим `record` — для первого живого smoke

    SABY_STUB_MODE=record node scripts/saby_stub/server.mjs

Прокси к `api.sbis.ru` / `online.sbis.ru`: бот ходит в `http://localhost:8090`, прокси пересылает как есть и пишет
каждый обмен в `scripts/saby_stub/fixtures/*.json` — имена, телефоны, комментарии и ключи заменены на `<name:4>`,
`<secret>`. Так неописанные ответы (`order/create`, `order/{id}`, `price-list`, `nomenclature/list`) превращаются
в записанные факты и фикстуры для тестов адаптера. Фикстуры в git не коммитятся, пока не проверены на отсутствие ПДн.

## Честно

Стаб проверяет **связку Butler**, а не Saby: там, где документация молчит, он отвечает так, как мы *думаем*. Зелёный
прогон со стабом не доказывает, что живой Saby ответит так же — это доказывает только `record` + реальные ключи.

Тесты: `node --test scripts/saby_stub/test/*.test.mjs` (8).
