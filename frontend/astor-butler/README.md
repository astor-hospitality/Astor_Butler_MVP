# Astor — Presentation Site

Анимированный презентационный сайт Astor: общая титульная страница и три продуктовые страницы.
Чистая статика: HTML/CSS/vanilla JS, без сборки и зависимостей. Своя цветовая идентичность
(графит + серебро), но типографика общая с c3ag.ru: см. «Типографика» ниже.

Продуктовая логика:

- `Astor` - общий бренд системы гостевого внимания.
- `Astor Butler` - продукт для ресторанов и отелей.
- `Astor Concierge` - продукт для событий, фестивалей и городских программ; лента заведений как Telegram Mini App.
- `Astor Glass` - умные очки и iPhone для сотрудников заведения (пилот): вопрос голосом или фото, ответ текстом и голосом.
- Внешний слой персонализируется под бренд заказчика: `AERIS Butler`, `Gastreet Concierge` и т.д.

Факты для страниц берутся только из репозитория (`docs/commercial/*.md`, `docs/operations/GLASSES_*.md`,
`docs/architecture`). Будущие каналы (MAX) и мультиязычность упоминаются только как «скоро».

## Типографика

Шрифт задан в одном месте - `design-system/brand/typography.css` (сейчас только Inter, одна гарнитура:
заголовки отличаются весом и трекингом, курсива нет). Сайт без сборки, поэтому использует копию
`css/brand-typography.css`, подключённую первой `<link rel="stylesheet">` на каждой странице; `style.css`,
`feed.css`, `staff.css` и `docs/docs.css` берут семейство, веса и трекинги из токенов `--brand-*`.
Тест `tests/brand-typography.test.mjs` следит, что копия совпадает с источником, что ни одна страница не
грузит шрифт мимо неё и что `frontend/app/layout.tsx` (c3ag.ru) грузит ту же гарнитуру.
Как сменить шрифт, когда придёт новый: `design-system/brand/README.md`.

## Структура

```
frontend/astor-butler/
├── index.html          # общая титулка Astor: выбор Butler / Concierge / Glass
├── policy.html         # политика конфиденциальности: боты, стаф-портал, Astor Glass
├── astor_butler/       # продуктовая страница для ресторанов и отелей
├── astor_concierge/    # продуктовая страница для событий и городских программ
│   └── feed/           # лента заведений Concierge: Telegram Mini App и обычная страница
├── astor_glass/        # продуктовая страница очков для команды (пилот)
├── css/brand-typography.css  # копия design-system/brand/typography.css: шрифт, веса, трекинги
├── css/style.css       # вся стилистика продуктовых страниц (графит + серебро; шрифт из токенов бренда)
├── js/main.js          # курсор-ключ, рябь, дверь, scroll reveal, optional chat UI
├── js/widget.js        # transport layer виджета: submitMessage(payload), mock/backend режимы
├── css/feed.css        # стили ленты (те же токены, без интро-эффектов)
├── js/feed.js          # лента: закреплённые заведения, остальные по среднему рейтингу Яндекс Карт и 2ГИС
├── js/feed-ratings.js  # правила показа и сортировки рейтингов, без DOM и сети
├── data/venues.json    # заведения ленты и ссылки на объекты карт; фото — только с разрешения заведения
├── data/ratings/       # snapshot.json: опубликованный снимок рейтингов, обновляется scripts/concierge_ratings
├── assets/             # favicon.svg, og-image.png
├── docs/               # коммерческий пакет как HTML-страницы
│   ├── offer.html      # КП (из docs/commercial/COMMERCIAL_OFFER_RU.md)
│   ├── comparison.html # сравнение (из docs/commercial/BENCHMARK_COMPARISON_RU.md)
│   ├── brand.html      # бренд-гайд (из docs/commercial/BRAND_GUIDE_RU.md)
│   └── docs.css
└── tests/              # node --test tests/*.mjs: бренд-типографика, ссылки, staff UI/auth
```

## Локальный запуск

Любой статический сервер, например:

```bash
cd frontend/astor-butler
python3 -m http.server 8090
# → http://localhost:8090
```

Или просто открыть `index.html` в браузере (Google Fonts требует сеть; без сети — системные fallback-шрифты).

Проверка перед push:

```bash
node --test frontend/astor-butler/tests/*.mjs
```

## Деплой

Сайт хостится где угодно: достаточно отдать папку `frontend/astor-butler/` как document root, никакой сборки.
Превью на ВМ Cloud.ru за общим edge-Caddy (техническое имя `astor.176-123-165-162.nip.io`, своего домена
пока нет) описано в `infra/astor-site/README.md`: compose с nginx, сниппет Caddy и порядок проверки.

## Лента Concierge

Страница `astor_concierge/feed/` — лента заведений: обычная страница сайта и Telegram Mini App. Заведения лежат в `data/venues.json`, рейтинги — в `data/ratings/snapshot.json`, backend не нужен.

- Порядок: закреплённые заведения (`pinned`) идут первыми и помечены как выбор Astor, остальные — по среднему рейтингу Яндекс Карт и 2ГИС. Без рейтинга — в конце.
- Рейтинги хранятся в снимке: по каждому источнику значение, ссылка, идентификатор объекта и время проверки. Лента показывает настоящую дату проверки и помечает давно не проверявшиеся. Сбой загрузки не превращается в ноль: остаётся последнее удачное значение, а без снимка список идёт по названию.
- Снимок обновляет `scripts/concierge_ratings/update.mjs` из корня репозитория: в каталоге сайта его нет, чтобы он не раздавался вместе со страницами. Цифры вносит человек командой `record`; автоматически с карт ничего не собирается, потому что условия Яндекс Карт и 2ГИС этого не разрешают. Источники, запуск и откат: `docs/operations/CONCIERGE_RATINGS.md`.
- Проверка: `node --test scripts/concierge_ratings/test/*.test.mjs`.
- Список на 2026-10-04 черновой: заведения, которые 2ГИС в Екатеринбурге показывает с типом «Гастробар» по запросу «гастробар». Состав утверждает Михаил.
- Фото AERIS подключены прямыми ссылками с официального сайта aeris.bar по решению Михаила и в репозиторий не копируются. Фото других заведений не используются.
- «Забронировать» открывает Telegram-бот заведения; бронь в нём идёт обычным сценарием с подтверждением хостес.
- Чтобы открыть ленту из бота, владелец бота задаёт адрес страницы в BotFather (Menu Button или Mini App).

## Подключение backend (когда контейнеры будут развернуты)

Chat widget сейчас работает в mock-режиме. Для боевого режима:

1. Открыть `js/widget.js`.
2. Задать endpoint:

```js
window.AstorChatConfig = {
  endpoint: "https://<backend-host>/api/messages",
  channel: "WEB",
  site: "astor-commercial",
};
```

3. `submitMessage(payload)` начнет отправлять POST JSON:

```json
{
  "channel": "WEB",
  "text": "…",
  "payload": {
    "sessionId": "web-…",
    "site": "astor-commercial",
    "pageContext": "commercial_landing",
    "sentAt": "ISO-8601"
  }
}
```

Ожидаемый ответ от текущего backend `MessageController`: `{ "text": "…", "nextState": "…" }`.

4. Telegram-кнопки: заменить placeholder `https://t.me/astor_butler_bot` на реальный username бота (2 места в `index.html`).

## Чеклист проверки

- [ ] Hero открывается «дверью», ключ-курсор на desktop
- [ ] Рябь при движении мыши
- [ ] Маскот Butler появляется после hero и меняет позу/подпись по главам
- [ ] Главная ведет в `Astor Butler`, `Astor Concierge` и `Astor Glass`
- [ ] На странице Butler ясно видны: заведения, хостес, менеджер, внедрение, поддержка
- [ ] На странице Concierge ясно видны: события, программа, карта, VIP, поток гостей, лента `feed/`, отчет
- [ ] На странице Glass ясно видны: голос, фото по шагам, сообщения в очки, журнал смены, границы (подсказка, не решение), статус «пилот»
- [ ] Политика конфиденциальности доступна из футера каждой продуктовой страницы
- [ ] Chat widget появляется на финальной секции, mock-ответы работают
- [ ] Telegram CTA в hero и в финале
- [ ] Ссылки на docs/offer.html, comparison.html, brand.html работают
- [ ] Mobile: нет горизонтального скролла, курсор-эффекты отключены
- [ ] prefers-reduced-motion: анимации отключаются, контент виден
- [ ] Keyboard: skip-link, focus-visible, Esc закрывает чат

## Кабинет поручений (серверный, включение отдельно)

Страница `staff/` (`css/staff.css`, `js/staff.js`, `js/staff-auth.js`) читает PostgreSQL-backed API монолита, входит через отдельный Keycloak Astor с PKCE и отображает только подтверждённые сервером изменения. Demo JSON не загружается. Менеджер открывает/закрывает смены и ведёт справочник существующих пользователей; менеджер/хостес назначают поручения. Принятие/выполнение/доставка поступают от самого сотрудника, а не имитируются менеджером. Фото/evidence пока не подключены: нет учебной кнопки ACK.

Static worker/Python preview сами по себе не являются backend. Нужны HTTPS same-origin proxy staff routes в монолит, отдельный issuer/JWKS и включение `astor.staff.enabled=true`. По умолчанию backend staff routes закрыты; при недоступном сервере данные не подменяются учебными. См. `docs/operations/STAFF_PORTAL_RUNBOOK.md`. Legacy `data/staff-demo.json` остаётся только архивным fixture прототипа, не live-данными.
