# Бренд-типографика C3AG / Astor

Один файл задает шрифт для всех сайтов группы: `typography.css`. Сейчас это Inter (решение Михаила:
до нового фирменного шрифта от Ромы и Изи Astor и все новые продуктовые страницы используют
шрифт C3AG). Одна гарнитура на весь сайт; заголовки отличаются весом и трекингом, а не другим
шрифтом; курсив не используется.

## Кто читает файл

| Сайт | Как подключен | Что менять при смене шрифта |
| --- | --- | --- |
| Astor (`frontend/astor-butler`, статика без сборки) | копия `frontend/astor-butler/css/brand-typography.css`, первая `<link rel="stylesheet">` на каждой странице; `style.css`, `feed.css`, `staff.css`, `docs/docs.css` берут семейство и веса из `--brand-*` | только копию (см. ниже) |
| c3ag.ru (`frontend/`, Next.js) | шрифт грузит `next/font` в `frontend/app/layout.tsx`, переменная `--font-body`; токены `--f-body` / `--f-heading` и блок «Minimal skin» в `frontend/app/globals.css` | `layout.tsx` (гарнитура и веса) и, если менялись трекинги, `globals.css` |

Синхронность копии проверяет `frontend/astor-butler/tests/brand-typography.test.mjs`:
копия должна совпадать с `typography.css` байт в байт, каждая страница Astor должна подключать
ее первой, а `layout.tsx` должен импортировать первое имя из `--brand-font-family` из
`next/font/google` (для self-hosted шрифта - `localFont(...)` с этим именем в `src`).

## Как поменять шрифт, когда придет новый

1. В `design-system/brand/typography.css`:
   - заменить `@import` на загрузку нового шрифта. Для Google Fonts - новый URL с нужными весами.
     Для self-hosted шрифта - блоки `@font-face` с относительными `src: url("fonts/…")`;
     файлы положить в `frontend/astor-butler/css/fonts/` (рядом с копией), не в `design-system/`;
   - поменять первое имя в `--brand-font-family` и, при необходимости, веса `--brand-weight-*`
     (оставлять только те, что реально загружены) и трекинги `--brand-*-tracking`.
2. Скопировать файл в статику Astor:

   ```bash
   cp design-system/brand/typography.css frontend/astor-butler/css/brand-typography.css
   ```

3. В `frontend/app/layout.tsx` заменить `Inter` из `next/font/google` на новую гарнитуру
   (для файла шрифта - `localFont` из `next/font/local`, имя семейства в пути `src`),
   сохранив `variable: "--font-body"` и веса из токенов. c3ag грузит ещё вес 300 для
   своих нужд - это его локальное решение, в бренд-токены он не входит.
   Если менялись трекинги - поправить блок «Minimal skin» в `frontend/app/globals.css`.
4. Проверить:

   ```bash
   node --test frontend/astor-butler/tests/*.mjs
   cd frontend && npm run build
   ```

5. В `frontend/astor-butler` открыть страницы локально (`python3 -m http.server 8090`) и
   посмотреть 375px и десктоп: hero, заголовки секций, карточки, docs, policy, feed, staff.

Что не трогать: цвета Astor (`--ink`, `--paper`, `--gold`, `--metal`) живут в `style.css`
и не входят в бренд-типографику - у Astor своя цветовая идентичность.

## Токены

| Токен | Значение сейчас | Где используется |
| --- | --- | --- |
| `--brand-font-family` | Inter + системный стек | `--font-body` на всех сайтах |
| `--brand-font-heading` | = `--brand-font-family` | `--font-display`; заголовки |
| `--brand-weight-regular/medium/semibold` | 400 / 500 / 600 | единственные загруженные веса |
| `--brand-heading-weight` | 600 | h1-h3 |
| `--brand-heading-tracking` / `-leading` | -0.022em / 1.04 | h1-h3 по умолчанию |
| `--brand-hero-tracking` / `-leading` | -0.035em / 1.03 | заголовок hero |
| `--brand-display-tracking` / `-leading` | -0.03em / 1.05 | заголовки секций (`.section-title`, docs h1) |
| `--brand-subhead-tracking` | -0.015em | h3 внутри карточек |
| `--brand-body-weight` | 400 | текст; интерлиньяж задаёт страница |
