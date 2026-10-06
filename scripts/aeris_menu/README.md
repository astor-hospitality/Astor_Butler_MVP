# AERIS: меню с сайта в файлы

`node scripts/aeris_menu/sync.mjs [--out DIR] [--ocr] [--pages kitchen,barmenu,wineroom,bl]`

Снимает актуальные меню с [aeris.bar](https://aeris.bar) в `src/main/resources/menu/aeris/site/`:

| Страница | Как устроена на сайте | Что получаем |
| --- | --- | --- |
| `/kitchen` | Tilda-каталог, блюда приходят JSON-ом с `store.tildaapi.com` | `kitchen.json` (разделы, блюда, цены, описания) и `kitchen.md` |
| `/barmenu`, `/wineroom`, `/bl` | картинки карты (`BAR_MENU_2026_FINAL_.jpg` и т.п.) | `images/*` и, с `--ocr`, `ocr/*.txt` (помечены как непроверенные) |
| все | — | `manifest.json`: когда снято, откуда, sha256 каждого файла, что изменилось |

Повторный запуск перекачивает только изменившиеся картинки и сохраняет дату первого появления файла.
Код выхода `2` — какая-то страница не прочиталась (остальные всё равно записаны), `1` — ошибка самой команды.

`--ocr` требует `tesseract` с русским языком (`brew install tesseract tesseract-lang` или `apt install tesseract-ocr tesseract-ocr-rus`).
Текст OCR — черновик для человека, не источник правды: перед использованием в ответах бота его сверяют с картинкой.

Картинки в git не коммитятся (`.gitignore`), JSON/MD/manifest — коммитятся, чтобы изменение меню было видно в диффе.
Нужен доступ к `aeris.bar` и `store.tildaapi.com`; из облачной среды Claude сайт недоступен — запускать с ноутбука или с ВМ.

Тесты: `node --test scripts/aeris_menu/test/*.test.mjs` (фикстура — реальный ответ Tilda от 06.10.2026, 6 блюд).
