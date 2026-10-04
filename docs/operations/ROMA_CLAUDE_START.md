# Рома: старт Astor Glass через Claude

Дата: 2026-10-04. Исполнитель: Рома / Lucky (`0xLaki`). Ветка `codex/roma-astor-glass`, исходный pilot commit `56e6b9c` из [PR #11](https://github.com/astor-hospitality/Astor_Butler_MVP/pull/11). Работа идёт отдельно от pilot branch и main. PR #11 требует review; его CI прошёл, но это не физическая приёмка очков.

## Checkout Ромы

```bash
git clone https://github.com/astor-hospitality/Astor_Butler_MVP.git
cd Astor_Butler_MVP
git fetch origin
git switch --track origin/codex/roma-astor-glass
claude
```

При уже существующем клоне сначала сохранить собственные изменения; не делать reset/clean. Ветку брать от готового pilot, не от более старого main. Claude запускается в локальном аккаунте Ромы; эта инструкция не передаёт ему чужой аккаунт или production credentials.

Прочитать `AGENTS.md`, `CLAUDE.md`, [контракт](GLASSES_ASSIST_PILOT.md), [отчёт](ASTOR_GLASS_ROMA_HANDOFF.md) и шесть обязательных файлов `docs/obsidian/**`, перечисленных в AGENTS.md. Вопросы по коду начинать с graphify, если graph.json доступен.

## Первое задание Claude

> Работаем в codex/roma-astor-glass. Сначала проверь контракт фронта и glasses API и составь план физического smoke. Используй AGENTS.md/CLAUDE.md и GLASSES_ASSIST_PILOT.md. Отличай synthetic server smoke от mic/HFP приёмки. Затем подготовь отдельный PR с первой согласованной доработкой и критериями приёмки. Не менять гостевую FSM, не выполнять deploy/SSH/cloud calls без соответствующего поручения. Не читать или публиковать secrets/raw media/production logs. Информационный assist не подтверждает выполнение задач.

## Рабочий продукт

- Frontend: https://c3ag.ru/astor/astor_butler/ — презентация и anonymous WEB lead чат, не staff portal.
- Telegram: https://t.me/astor_butler_bot — проверить полный диалог отдельно; getMe/transport уже проверены.
- Glass API: https://c3ag.ru/api/glasses/assist — scoped доступ выдаёт Михаил приватно. Text/voice/JPEG проходили real model HTTPS smoke. Срок тестовой сессии и правила ротации смотреть в runbook; credentials в git или Claude prompt не вставлять.

## Первый проход

1. Проверить frontend ответ, Telegram диалог и физическую цепочку очки microphone / iPhone AAC / STT / ответ / HFP TTS. Отдельно cancel, offline/reconnect, lock и call interruption. Сохранять status/requestId/latency, без raw медиа и ключей.
2. Зафиксировать найденные проблемы. Первая доработка должна иметь воспроизводимый случай и небольшой PR. Не строить параллельный adapter issue #9, сверять пересечение с BryxOG.
3. P1 обсуждать как отдельный контракт: staff/tenant/shift, assigned task feed, evidence конкретного task/table/stage, permissions/version/idempotency и ACK. Пока feed отсутствует, учебные сценарии явно маркировать.

## Доступ и проверка

GitHub write invitation `0xLaki` ещё требует принятия. Для scoped SSH нужен его публичный ключ. Установленный root-owned `astor-glasses-admin` позволяет только согласованные операции Astor. Общий Docker/root, arbitrary shell/deploy и доступ к VEDAL/C3AG не входят в scope. Работу Claude можно начинать локально с public repo до получения серверного доступа.

Новые изменения проверяются собственными тестами и CI, затем review. Стартовый PR может быть stacked относительно pilot branch; перед merge сверить base после принятия PR #11. Не объединять PR в main с обходом review.
