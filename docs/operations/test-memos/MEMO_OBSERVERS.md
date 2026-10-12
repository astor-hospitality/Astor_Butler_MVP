# Памятка наблюдателям — Дима, Рома, Егор. Тест ланча 15.10

| Кто | Смотрит | Заполняет в §5 |
| --- | --- | --- |
| Дима | ВМ: pre-flight, логи, Liquibase; system-чат (трассы); откат флагами | строки «система», коды ошибок |
| Рома | карточки и тексты в группе; секундомер «гость сел → напитки → первое блюдо»; очки Кирилла | строки «хостес / официант / очки» |
| Егор | вход: QR стойки → бот; Concierge / Mini App (`astor-ai.ru/app/`, `c3ag.ru` — что именно проверять, **уточнить** с Егором) | строки «вход» |

Таблица результатов — [BUSINESS_LUNCH_FIRST_TEST.md §5](../BUSINESS_LUNCH_FIRST_TEST.md#5-таблица-результатов): «кто наблюдал» — роль, не id; `#N` в последней колонке.

## 1. Pre-flight (среда вечером и 11:15 в четверг) — Дима

`/opt/astor-butler/preflight-test.sh` (только чтение; лежит на ВМ рядом с `enable-rehearsal.sh`, в репозитории его нет). Ожидаем:
контейнеры `aeris_astor_butler_bot`, `astor-api-gateway`, `astor_glasses_api`, `astor_postgres_test`, `astor_redis_test`, `astor-tg-awg` — healthy;
чаты `TELEGRAM_HOSTESS/ADMIN/SYSTEM_CHAT_ID` заданы (на тесте — id рабочей группы AERIS от Тариэля; переключение `enable-rehearsal.sh <id>` запускает Михаил);
флаги `BILLING`, `REVIEW_MOMENT=on-bill`, `GUEST_BILL_REQUEST`, `KILL_SWITCH`, `BOOKING_STAFF_EDIT`, `BOOKING_SEATED`, `LUNCH_WISHES`, `STAFF_HANDOFF`, `TIPS_PERSONAL` = true;
`AUTO_CONFIRM`, `MORNING_CHECK`, `HOLD_EXPIRY`, `GUIDING_QUESTION`, `PREORDER_MARK`, `SCOREBOARD`, `WAITER_GUEST_CARD`, `SABY_ENABLED` = false; tenant чаевых и handoff = `astor`;
данные: `daniil/WAITER+tip` в справочнике (и `shift_open` у него — нужен для поручений из очков, **уточнить**), «назначения официантов сегодня» ≥ 1, «миграции 12.10» ≥ 4;
Redis `PONG`, «Butler не на паузе»; очки: `staff`, `tasks`, образ `astor-glasses:v10` или новее (фильтр озвучки); «Ошибки бота за 30 мин: 0»;
снаружи `https://c3ag.ru/actuator/health` и `https://astor-ai.ru/app/` → 200.

Руками: превью «Astor Butler Staff Chat» закреплено в группе; `/status_butler` → «▶️ Butler работает» + сводка; `/официант` → стол статистов — Даниил;
`/чаевые` → «Даниил … ссылка есть»; `/ссылка host` выдал QR (распечатан); в `Liquibase` есть `2026-10-12-booking-seated`, `-tips-personal`, `-guest-entry-events`.

## 2. Что смотреть по ходу

**System-чат** (на репетиции = группа): на каждое сообщение гостя карточка `#dialog_telegram_<chat>` с переходом состояний и тегами `BUSINESS_LUNCH`,
`LUNCH_ORDER_PLACED`, `EXTERNAL_ORDER_MANUAL`, `WAIT_HOSTESS_CONFIRMATION`; «Вход гостя …: привёл(а) стойка хоста / Даниил, стол N, бизнес-ланч»;
«🪑 Бронь #N: гость сел в ЧЧ:ММ»; «✏️ Бронь #N изменена хостес: …»; `AUDIT Butler остановлен …` при `/stop`; «Поручение от …: … Стол N. Для: Даниил» из очков;
`Astor Glass · …` расшифровки — только если включён transcript-relay (**уточнить**). **Admin-чат:** алертов быть не должно (Saby выключен).
**Лог:** `docker logs --since 30m aeris_astor_butler_bot | grep -E ' ERROR | WARN '`; «карточка не пришла» → `Table reservation Telegram notification skipped: … chatConfigured=false`.

**Строки §5 сегодня:** 1–5, 4а (пожелание на карточке), 4б (состав — ожидаемый пробел), 5а («Счёт» хостес), 5б («счёт» от гостя), 5в («🪑 Гость сел»),
6–11, 19–21. **Не сегодня:** 4в, 5г, 5д, 12–18 (автомат), 22–25 (таймер), 26 (короткий заказ из очков, #184) — только по решению Михаила.
**Добавить строки:** «гость сел → напитки / первое блюдо» (3–5 / 7–10 мин от «🪑 Гость сел»); «наушник не перебил диалог»; «ответ очков ≤ 10 с без цены»;
«Привёл(а)» на карточке; «бот ответил на вопросы без официанта».

## 3. Если пошло не так

1. **Сначала менеджер смены `/stop`** в группе (пауза + сводка); возврат `/start_butler`. Кнопки на уже присланных карточках работают.
2. Бот молчит: на ВМ `docker compose … up -d --no-deps aeris-astor-butler-bot` (Дима); если после пересоздания `/api` даёт 502 — `docker restart astor-api-gateway`.
3. **Точечный откат — флагами**, без деплоя: `/opt/astor-butler/.env.production` + `up -d --no-deps aeris-astor-butler-bot` (на ВМ запускает Михаил; копия `.env.production.bak-*` рядом).
   Лишние карточки «просит счёт» — `AERIS_GUEST_BILL_REQUEST_ENABLED=false`; звёзды за столом мешают — убрать `ASTOR_REVIEW_MOMENT`; правка / посадка —
   `AERIS_BOOKING_STAFF_EDIT_ENABLED` / `AERIS_BOOKING_SEATED_ENABLED=false`; чаевые лично — `AERIS_TIPS_PERSONAL_ENABLED=false`; пожелания — `AERIS_LUNCH_WISHES_ENABLED=false`;
   ссылка сотрудника — `AERIS_STAFF_HANDOFF_ENABLED=false`; весь ланч — `AERIS_BUSINESS_LUNCH_ENABLED=false`. Заявки и счета в базе остаются. Полный список — §6 плана.
4. «Да» не работает (пустая всплывашка) — нажали не из того чата: `POST /api/bookings/table-reservations/{id}/confirm` изнутри ВМ.
5. После теста тестовые заявки снимают Михаил или Дима; звёзды статистов — из `visit_reviews` для кухни. В группу не пишем id, телефоны, ссылки;
   статистам не подсказываем (только фиксируем); Presto не трогаем; «тест» при гостях зала не произносим.
