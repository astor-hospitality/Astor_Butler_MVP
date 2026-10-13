# Путь гостя: включение, проверка, откат

Спецификация: [2026-10-13-guest-journey-design.md](../superpowers/specs/2026-10-13-guest-journey-design.md) §8.
Все флаги по умолчанию `false`. Включаем по одному: следующий — только после проверки предыдущего.
Решение о каждом флаге на проде — Михаил.

## Как переключить флаг

На ВМ, в `/opt/astor-butler/.env.production`, строка `ФЛАГ=true`. Затем пересоздать только контейнер AERIS
(команда — из [CLOUDRU_DEPLOY_RUNBOOK.md](CLOUDRU_DEPLOY_RUNBOOK.md) §5):

```bash
docker compose --env-file ../.env.production --env-file images.env -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudru.yml --profile frontend --profile telegram up -d --no-deps --no-build aeris-astor-butler-bot
```

Перед правкой сделать копию: `cp .env.production .env.production.bak-journey-$(date +%Y%m%d-%H%M)`.

## Порядок

| # | Флаг | Что меняется для гостя | Проверка после |
| --- | --- | --- | --- |
| 1 | `ASTOR_GUEST_JOURNEY_ENABLED` | ничего: лента визита и счётчик сообщений гостя | после пробной заявки: `SELECT kind, source FROM guest_visit_events ORDER BY id DESC LIMIT 10;` — есть `REQUESTED`; `SELECT stage, guest_messages FROM guest_visits ORDER BY id DESC LIMIT 3;` — счётчик растёт с каждым сообщением гостя в личном чате |
| 2 | `ASTOR_REPEAT_LUNCH_ENABLED` | «С возвращением… Повторить или собрать заново?» по прошлому счёту ланча | тестовый гость с прошлым счётом: `/start` → приветствие с блюдами; гость без счёта — обычное приветствие |
| 3 | `ASTOR_USEFULNESS_POLICY_ENABLED` | проактивные сообщения только из белого списка, каждое — один раз за визит; «не пишите мне» выключает их | `SELECT kind FROM guest_visit_sent WHERE visit_id = …;` — без повторов; после «больше не пишите» — строка в `guest_contact_prefs` |
| 4 | `ASTOR_BUTLER_TALK_ENABLED` | ответы о ресторане и о блюдах (вес, КБЖУ из Saby), просьбы за столом (хлеб, вода, официант, приборы, ускорить) | прогон [butler-40.txt](../../scripts/e2e/butler-40.txt); просьба приходит карточкой в чат хостес; вопрос без факта — «уточню у команды» + вопрос в чат персонала |
| 5 | `ASTOR_RETURN_LOOP_ENABLED`, `ASTOR_JOURNEY_WEEKLY_REPORT_ENABLED` | приглашение через 7 дней после визита (10:00–21:00), отметка «вернулся»; отчёт во вторник с 10:00 в чат управления | `SELECT stage, returned_visit_id FROM guest_visits WHERE returned_visit_id IS NOT NULL;`; во вторник — строка «Неделя: …» в чате управления |
| 6 | `ASTOR_SABY_CRM_MIRROR_ENABLED` | ничего для гостя: сделка на визит в воронке Saby CRM | `SELECT count(*) FROM guest_visit_events WHERE mirrored_at IS NULL;` уменьшается; в `saby_call_log` вызовы `CRMLead.insertRecord` / `CRMEvent.AddEvent` без ошибок; сделка видна на доске |

## Предусловия

- **Диалог (флаг 4).** Факты о ресторане — только из `src/main/resources/venue/aeris.json` (парковка, дети, Wi-Fi,
  вино к блюдам, афиша). Сейчас все `null`: бот на такие вопросы отвечает «уточню у команды». Факты даёт Тариэль,
  вносятся PR-ом и деплоем. Факты не выдумывать.
- **CRM (флаг 6).** В Saby создана воронка «Визит гостя AERIS» с этапами `REQUESTED CONFIRMED VISIT_DAY SEATED BILL AFTER
  RETURNED CANCELLED NO_SHOW OPTED_OUT`. В `.env.production`:
  - `SABY_CRM_JOURNEY_REGULATION_ID` — id регламента воронки;
  - `SABY_CRM_PHASE_DIRECTIONS` — `REQUESTED=<id>,CONFIRMED=<id>,…` (id переходов этапов из Saby);
  - `SABY_CRM_RESPONSIBLE_ID` — необязательно, ответственный за сделки.
  Первая сделка — тестовая, только после «да» Михаила; проверить её на доске, затем включать для всех.
  Без регламента зеркало не стартует (`ready()` ложно) и ничего не отправляет.

## Откат

Выключить флаг (`=false`) и пересоздать контейнер той же командой. Данные ленты (`guest_visits`, `guest_visit_events`)
остаются и не мешают: при выключенном флаге их никто не читает. Отписки гостей (`guest_contact_prefs`) остаются в силе
при повторном включении. Зеркало CRM при выключении просто перестаёт отправлять; неотправленные события уйдут после
включения, по 50 за проход.

## Прогон на 40 сообщений

Цель конкурса — около 40 сообщений гостя за визит без спама со стороны бота. Сценарий —
[scripts/e2e/butler-40.txt](../../scripts/e2e/butler-40.txt), одна реплика на строку. На ВМ:

```bash
c=990013040; python3 /opt/astor-butler/e2e-say.py $c "" <тестовый телефон>; while IFS= read -r line; do case "$line" in ''|'#'*) continue;; esac; python3 /opt/astor-butler/e2e-say.py $c "$line"; sleep 2; done < scripts/e2e/butler-40.txt
```

Телефон отправляется один раз пустым сообщением-контактом. Между строками хостес жмёт «Да» по заявке и «🪑 Гость сел»
(строки `# хостес: …` в файле). После прогона: `SELECT guest_messages FROM guest_visits ORDER BY id DESC LIMIT 1;` ≈ 40,
`SELECT count(*) FROM guest_visit_sent WHERE visit_id = …;` — только полезные проактивные сообщения, без повторов.
