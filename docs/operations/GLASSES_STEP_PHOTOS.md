# Фото сервировки по шагам

Дата: 2026-10-05. Main baseline: a4023a7. Приложение: clients/ios-glasses.

| Шаг подачи | Фото | Действие |
| --- | --- | --- |
| TABLE_PREPARE | По необходимости | Чистота, два места и проход |
| PLACE_SETTINGS | Обязательно | Приборы, салфетки, бокалы; снимок и проверка подсказки |
| WATER_MENU | По необходимости | Вода и утверждённое меню |
| FINAL_CHECK | Обязательно | Снимок всего стола, проверка замечаний, ручное завершение |

JPEG assist принимает optional photoContext (с 07.10 тот же контекст принимается с голосом и текстом — для журнала смены, см. `GLASSES_SHIFT_JOURNAL.md`): sessionId (canonical UUID), scenarioCode BUSINESS_LUNCH_TWO, stageCode из таблицы и целый revision 1..10000. Другие поля запрещены. Tenant/staff берутся из bearer scope. Контекст входит в retry fingerprint: смена шага с тем же requestId даёт 409. Сервер добавляет собственную подсказку шага и сохраняет контекст в scoped reply.json рядом с JPEG.

Ответ photoReceipt содержит requestId, context, archived. archived:true только после записи обоих объектов в включённый S3; ошибка хранения — 503 без receipt. Клиент сверяет UUID/session/stage/revision перед ручным переходом. Receipt не подтверждает качество сервировки и не является evidence/ACK StaffTaskService.

Снимок на телефоне только в памяти; повтор до 110 секунд использует прежние UUID/байты/контекст. Нет автоматического перехода или съёмки. SDK callback не содержит capture UUID: после отмены сохраняется окно предыдущей передачи, чтобы старый кадр не попал в новую попытку.

## Проверенный runtime

astor-glasses:v3-step-photo, jar SHA256 ee16224ad397cb12dfa4f0cc6518804530d4b488be2a9879ce7094ffca81c0f0. Заменён только glasses API, v2 сохранён для rollback. Gateway проверен и reloaded; VEDAL, C3AG, Telegram и фронт не перезапускались.

Полный Maven package: 336 tests, 0 failures/errors/skipped. Публичный HTTPS synthetic smoke: AAC/STT 200, silence 400 NO_SPEECH, JPEG + archived receipt 200; retry PASS; changed stage 409; missing bearer 401. Capabilities text/voice/vision/storage/documents true после проб. Реальный S3 reply.json отдельно прочитан оператором и сверён с session/stage/revision.

Фото+wear сборка подписана и установлена на iPhone X/iOS 16.7.7. Первый запуск остановился на заблокированном экране; текущий физический smoke отдельный. Прежняя фото-сборка реально запускалась, но это не доказательство wear.

## Реальные поручения и материалы ресторана

Main содержит ядро StaffTaskService: assignment, смена, версии, порядок этапов и evidenceRequired. Informational receipt не заменяет task evidence. Отдельная staff-ветка готовит controller/auth/PostgreSQL; её фото остаются недоступны до согласования связи и retention.

Пакет Тариэлю/хостес/Роме: https://auspicious-kryptops-863.notion.site/3efa7c019f1981508b9aff295ef9a89e. Запрос @wbc_boxinq отправлен 4 октября 19:02; срок меню — 5 октября. Меню/регламент и рабочий чат хостес ожидают ответа и согласования.
