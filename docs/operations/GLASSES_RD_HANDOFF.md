# Astor Glass — завершение R&D

Зафиксировано 2026-10-06 по прямому указанию Михаила: интегрировать написанное и больше не тратить время на исследование очков. Разработка и физические эксперименты этого этапа остановлены. Дальнейшие улучшения требуют новой задачи.

## Что передаётся

- Изолированный backend HTTPS: текст, AAC/STT, отдельный JPEG, структурированные ошибки, ограниченные процессы и временные файлы, scoped expiring bearer.
- Приватный S3: справочные документы и успешные материалы/ответы. Учебный сценарий сервировки на двоих, четыре шага и два обязательных фото с проверкой session/stage/revision и archived receipt.
- Отдельный raw archive JPEG/AAC/MP4 до 64 MiB: проверка SHA-256, durable receipt, повтор после рестарта, 409 при изменённом содержимом/сессии. Объекты и ключи не выдаются клиенту.
- Reviewable iPhone sources в clients/ios-glasses: SDK, фото-сценарий, кнопочный голосовой диалог, исправление ложного SDK InCall от собственного HFP, greeting по SDK wear event, opt-in Apple wake, persistent phoneCapture queue и фоновые file uploads.
- Все написанные экспериментальные wake-модули сохранены. CPU Whisper закрыт флагом ASTOR_ENABLE_EXPERIMENTAL_WAKE=0 и не включён в принимаемый функционал. Default project.yml не требует этой библиотеки или модели.

## Доказательства

Backend package: 347 тестов, без failures/errors/skips. CI коммита af622f8 прошёл; финальная интеграционная версия проверяется отдельно через GitHub CI. Финальная default iPhone сборка RDFinal прошла BUILD SUCCEEDED и codesign strict/deep; bounded wake buffer и OFF guard прошли. Модель/Whisper framework не включены. Binary SHA-256 a5df56a5c91b0787164ea41239506c7f73b8a7201f92f6e005ea7b2bcb37f0f9. Эта финальная сборка не переустанавливалась на телефон; установленная физически проверенная версия указана ниже.

Изолированный runtime v4-media-archive был установлен 5 октября. Candidate прошёл JPEG/AAC/MP4, точное сравнение receipts, повтор после собственного рестарта, conflict/hash rejection. Публичный HTTPS прошёл те же форматы, MP4 больше 5 MiB, повтор/409/400/401, AAC/STT, тишину NO_SPEECH и учебное фото. Политика S3 позволила receipt GET/PUT, запретила GET существующего raw media, list/delete и PUT в другом scope. v3 сохранён остановленным для rollback.

Физический тест 5 октября: iPhone X, iOS 16.7.7, firmware очков 0.1.0.3, SDK Ready, Bluetooth HFP input/output. Исправленный installed binary SHA-256: 62ed5747bf29e63b5459cb2226dfd7b0b5f7b27b6adc7cdda5d92422d5fcd723. Запись завершилась, API вернул ответ, TTS завершился. Это не подтверждение пользователем, что он услышал ответ.

Независимо проверена сохранённая реальная запись: assist request a40c65a0-70b2-46c3-b5fd-37c014d7a5da, 98 451 bytes, SHA-256 2d31281c65f8f41915e415a92d45573203e7819d7544b5e014f4f12f8d20d1c6. В S3 найден matching input.m4a и непустой audio reply. Проверочные приватные временные копии удалены, содержимое речи/ответа в отчёт не включено.

Отдельный raw-archive file abb7787f-8b4f-4339-95a5-b83d20ee7166, session e9746018-8887-41ca-a6ee-59aae64d02ce остаётся held по последнему снимку очереди 2026-10-05 06:45:40 UTC. Для этого файла raw /media receipt не подтверждён. Сохранение assist и архивная выгрузка являются разными операциями.

## Зафиксированные ограничения

| Возможность | Состояние на закрытие |
| --- | --- |
| Фото-сценарий и S3 photoReceipt | Код и backend smoke прошли; полного физического фото-сценария нет |
| Приветствие при надевании | Код есть, SDK wear capability=2 enabled; переход и звук не подтверждены |
| Команда «Астор» на текущем iPhone | Apple localRussian=false, режим выключен |
| Экспериментальный Whisper | Positive synthetic wake FAIL; default flag OFF, дальнейшие исследования остановлены |
| Голос только владельца очков | Speaker verification не реализована |
| Постоянный standby с locked iPhone | Не принят физическим тестом |
| Зарядка → raw archive receipt | Battery callback и matching receipt не подтверждены; файл остаётся на телефоне |
| Реальный звонок / фон без debugger | Regression policy протестирована локально; новый физический interruption/background не проверен |
| Импорт saved-video по Wi-Fi | Personal Team не поддерживает Hotspot entitlement; режим OFF |
| Выполнение реальных staff задач/FSM ACK | Этот adapter информационный; отдельный доменный transport требуется |
| Реальное меню AERIS | Запрос Тариэлю отправлен 4 октября, ответ/согласованная версия не подтверждены |

Пилотный bearer выдавался на ограниченное время и к моменту закрытия истёк. Продление/выдача нового scoped-доступа является операционной процедурой следующего теста; ключи не находятся в исходниках/отчёте. Лицензированный SDK, Pods, модели, build artifacts и личный Obsidian vault исключены из Git.

## Продолжение продукта

Рома получает исходники и явную матрицу возможностей через main/PR. Работать через feature branches и PR, использовать ограниченное администрирование Astor. Release документация: GLASSES_ASSIST_PILOT.md, GLASSES_STEP_PHOTOS.md, GLASSES_MEDIA_ARCHIVE.md и clients/ios-glasses/docs/DOCK_ARCHIVE.md. Полный продукт не объявляется принятым по synthetic smoke или компиляции.
