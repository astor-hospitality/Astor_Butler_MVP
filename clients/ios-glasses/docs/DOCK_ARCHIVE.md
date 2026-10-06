# Архив сессии при зарядке

Дата: 2026-10-05. Это архив медиа, не подтверждение выполнения задания FSM.

## Два источника

- `phoneCapture`: до съёмки пользователь нажимает «Подготовить архив сессии». JPEG из SDK callback и завершённый AAC-файл микрофона копируются в приватную очередь iPhone. До зарядки или «Выгрузить сейчас» состояние `held`; HTTP не запускается. Это путь текущей Personal Team, без Wi-Fi очков.
- SDK memory import: перед съёмкой приложение получает через SDK свежий инвентарь файлов. При зарядке запрашивается конечный инвентарь, фиксируются новые имена и импортируются оригиналы. Первая конечная выборка сохраняется; более поздние файлы не включаются при повторах. SDK не предоставляет timestamp/sessionId, поэтому старые файлы нельзя автоматически приписывать сценарию.

Триггер — только свежий callback `Glass=0, Charging=1`. Зарядка кейса (`ChargingCase=3`) не доказывает, что очки внутри. Если идёт запись или звонок, callback сохраняется до освобождения приложения, не дольше 120 секунд. Утрата соединения сама по себе не завершает сессию. Процент заряда не используется как замена charging state.

## Хранение и повторы

Очередь `Application Support/AstorDockArchive/queue.json` и приватные `.media` файлы исключены из backup. На iOS применяется защита до первого разблокирования. Manifest содержит UUID сессии/файла, MIME, размер, SHA-256 и состояние, без bearer, Wi-Fi паролей, bucket или signed URLs.

Один файл до 64 MiB, очередь до 512 MiB, одна активная SDK-операция и одна HTTP-выгрузка. Сохраняются частичные результаты импорта, подтверждённые файлы не отправляются повторно. HTTP 401/403, 409, 413 и неподходящий receipt требуют ручного повтора; временные ошибки повторяются с задержкой, до восьми попыток. «Повторить отправку» не освобождает `held` записи текущей сессии. При повреждении manifest передача останавливается и существующие файлы не перезаписываются.

Оригиналы на очках не удаляются. Приватные копии после подтверждения также остаются на iPhone в пределах лимита; автоматическая очистка не реализована.

## Согласованный серверный контракт

Проверка готовности: существующий `GET /api/glasses/capabilities` со scoped bearer возвращает `mediaArchive: {enabled: true, maxFileBytes: 67108864}`. До этого uploader удерживает файлы. Endpoint не принимается из ответа сервера: используется путь `/api/glasses/media` на уже настроенном HTTPS base URL, редиректы запрещены.

`POST /api/glasses/media` передаёт бинарный файл, без base64 и без вызова AI:

```text
Authorization: Bearer <credential from Keychain>
Content-Type: image/jpeg | audio/mp4 | video/mp4
Content-Length: <bytes>
X-Glasses-File-Id: <UUID>
X-Glasses-Session-Id: <UUID>
X-Content-SHA256: <64 lowercase hex characters>
```

Успех после долговременного сохранения объекта:

```json
{"fileId":"<same UUID>","sessionId":"<same UUID>","sha256":"<same hash>","size":123,"archived":true}
```

Все четыре корреляционных поля должны совпасть. `2xx` без matching receipt не считается успехом. Повтор неизменного fileId должен вернуть прежний receipt; изменение sessionId/hash/size/MIME при том же fileId — `409`. Tenant определяется bearer scope. Архивный receipt не является `photoReceipt` учебного этапа и не закрывает задания портала.

## Подключение к UIKit клиенту

`AstorDockArchive.shared` — единственный владелец queue/sync/uploader на срок жизни процесса.

1. Добавить `[archive makePanel]` в карточку вкладки «Очки».
2. Передавать текущий HTTPS base URL и bearer через `configureBaseURL:bearer:`.
3. Передавать device/foreground/idle через `updateDevice:foreground:idle:` при Ready, disconnect, foreground и регулярном refresh. `idle` исключает активное фото, аудиозапись, ответ, звонок и распознавание wake word. Если `archive.busy`, новые SDK/voice операции должны ждать.
4. Из живого battery delegate вызвать `observeChargingForDevice:component:state:`.
5. JPEG из одиночного callback передать в `capturePhotoData:`; AAC перед удалением временного recordingURL — в `captureAudioFile:`. Захваты принимаются только в явно открытой phoneCapture-сессии.
6. AppDelegate создаёт singleton при запуске. `application:handleEventsForBackgroundURLSession:completionHandler:` передаёт события в `handleBackgroundSession:completion:`; завершение вызывается после `URLSessionDidFinishEventsForBackgroundURLSession`.

Фоновая HTTPS-выгрузка использует `NSURLSessionUploadTask fromFile:`. SDK Wi-Fi импорт начинается только в foreground; на уход в background или звонок операция отменяется, очередь сохраняется.

## Проверено и ещё требуется

- Foundation tests: Bluetooth held→charging→pending, другой UUID сессии, исключение старого инвентаря, frozen final inventory, отсутствующий ожидаемый файл, partial retry, перезапуск, неверный receipt, истёкшая авторизация, повреждённый manifest — PASS.
- Все четыре новых Objective-C модуля: clang arm64 iOS 15 syntax с `-Werror` — PASS.
- Общий UIKit клиент: hooks battery/JPEG/finished AAC/background delegate и карточка «Архив сессии» интегрированы. `build/DockArchive`, signed Debug generic iOS — BUILD SUCCEEDED; codesign strict/deep PASS. Lunch/gesture/wear/wake проверки также PASS. Executable SHA-256 `e9fb6bd6bdbd9eeb345132991f9bc661b6389bfff0cde51255e6d539a0d93e57`.
- Подпись с HotspotConfiguration: Xcode отклонил Personal Team, `do not support the Hotspot capability`. Лог `~/Library/Logs/AstorGlassesProbe/dock-wifi-signing.log`. В текущей сборке `AstorWiFiImportEnabled` отсутствует/false. Включать true только после проверки entitlement в реальной подписанной сборке поддерживаемой команды.
- После повторного подключения iPhone подтверждены `ios-deploy InstallComplete` и запуск. Загруженный с телефона `Documents/probe.log` подтвердил SDK Ready, firmware 0.1.0.3, BluetoothHFP input/output, операции 2→10/4→7/8→12. Одна attached сессия ведётся только здесь. Пользователю отправлен первый шаг «Подготовить архив сессии». Серверный `/media` и аппаратный зарядочный тест подтверждаются отдельно. Компиляция не доказывает доступность радио в закрытом кейсе или полноценное выполнение iOS в фоне.

Аппаратный regression: три старта AVAudioRecorder немедленно дали SDK CallStatus 2→0, защита ошибочно вызвала cancelAgent. Добавлен `AstorCallPolicy.h`: SDK-only InCall во время собственного HFP не отменяет запись; CallKit и Ringing/ThreeWayRinging остаются приоритетными. 7 regression checks PASS, signed rebuild/codesign + InstallComplete + повторный SDK Ready/HFP подтверждены. Текущий binary SHA-256 `62ed5747bf29e63b5459cb2226dfd7b0b5f7b27b6adc7cdda5d92422d5fcd723`. Пользователь повторяет голосовой тест, реальное телефонное прерывание после исправления проверяется отдельно.

Серверный чат подтвердил v4-media-archive и публичные HTTPS smoke JPEG/AAC/MP4, в том числе файл >5 MiB. Capabilities включена; immutable retry, modified-session 409, wrong-hash 400 и повтор после server restart проверены на backend. Это не является подтверждением выгрузки с этого iPhone: его очередь и receipt ещё проверяются физически.

Повторный физический голосовой тест после исправления прошёл: HFP input/output AI Glasses, SDK InCall с systemCall=false/ownHFP=true больше не остановил запись, ассистент ответил и TTS завершился. Phone queue содержит audio/mp4 98451 bytes в `held`; точная длительность и распознанный текст в diagnostics не сохраняются. Пользователь проверяет зарядку в открытом подключённом кейсе. BatteryStatus callback пока не получен; disconnect не заменяет этот сигнал.

## 2026-10-06 — Передача для интеграции

Новые установки и аппаратные эксперименты остановлены; `main.m` и `project.yml` освобождены для серверного чата. Сохраняется установленная signed сборка с SHA-256 `62ed5747bf29e63b5459cb2226dfd7b0b5f7b27b6adc7cdda5d92422d5fcd723`. Исправление мгновенной отмены записи подтверждено реальным AAC, ответом `/assist` и завершённой озвучкой. Серверный владелец независимо сопоставил 98451 bytes и SHA-256 записи с сохранённым assist-входом; assistRequestId `a40c65a0-70b2-46c3-b5fd-37c014d7a5da`.

Последний подтверждённый phone queue snapshot — 2026-10-05 06:45:40 UTC: sessionId `e9746018-8887-41ca-a6ee-59aae64d02ce` остаётся `recording`, fileId `abb7787f-8b4f-4339-95a5-b83d20ee7166`, audio/mp4, 98451 bytes, SHA-256 `2d31281c65f8f41915e415a92d45573203e7819d7544b5e014f4f12f8d20d1c6`, `held`, HTTP receipt отсутствует. Это исторический snapshot, не live-статус 6 октября.

Незакрыты аппаратный charging callback, raw `/media` receipt этого файла после зарядки/ручного завершения, фон без debugger и прерывание записи реальным звонком после исправления. Wi-Fi импорт сохранённых записей остаётся OFF из-за неподдерживаемого текущим provisioning Hotspot entitlement. Исходники и очередь сохранены; эти ограничения переданы для итоговой интеграции, новых физических действий от пользователя сейчас не требуется.
