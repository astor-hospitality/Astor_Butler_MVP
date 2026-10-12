# Репетиция миграций кабинета персонала

Скрипты к issue #93 и разделу 5 `docs/operations/STAFF_PORTAL_RUNBOOK.md`.
Запись локального прогона: `docs/operations/STAFF_PORTAL_REHEARSAL_2026-10-11.md`.

Что важно знать до запуска:

- Сами скрипты никуда не ходят. Адрес базы, имя копии и сеть Docker берутся из окружения
  (`env.example`), по умолчанию это локальный стенд из `compose.yaml` этой папки.
- Источник только читается (`pg_dump`). Всё остальное пишется в копию и в черновую базу
  `<копия>_expected`. Писать скрипты отказываются, пока имя копии не повторено в `REHEARSAL_WRITE_TO`.
- Удаляются только базы, которые создали сами скрипты: при создании на базу ставится комментарий,
  без него `DROP DATABASE` не выполняется.
- `clearCheckSums` и `changelogSync` не вызываются нигде. Расхождение сумм или путей это находка:
  шаг 3 её показывает и выходит с кодом 2, шаг 4 при провале `liquibase validate` ничего не применяет.
- Дампы, сертификаты и отчёты лежат в `REHEARSAL_OUT`, вне репозитория.

Нужны Docker, bash и git. Для шага 5 ещё JDK 25, Maven и Python 3.

## Шаги

| Скрипт | Что делает | Где уместен |
| --- | --- | --- |
| `01_build_before.sh` | Собирает базу «до»: миграции репозитория на коммите перед `2026-10-05-staff-portal` и синтетические строки | только локально |
| `02_backup_restore.sh` | `pg_dump -Fc`, `pg_restore --list`, восстановление в копию, сверка с источником: таблицы, строки, хеши, последовательности, расширения, схема. С `REHEARSAL_DUMP_FILE` восстанавливает готовый дамп, например ночной | локально и на согласованной базе |
| `03_changelog_plan.sh` | Сверяет `DATABASECHANGELOG` копии с тем, что даёт changelog целевого коммита на пустой базе (id, author, путь, MD5SUM, порядок), затем `liquibase validate`, `status --verbose`, `update-sql` | локально и на согласованной базе |
| `04_apply.sh` | `liquibase update` на копии: время, что выполнено, diff схемы, новые таблицы, строки changelog, сохранность строк старых таблиц | локально и на согласованной базе |
| `05_identity_prepare.sh` | Одноразовый Keycloak на этой машине: сертификат, truststore, копия realm | только локально |
| `05_run_app.sh` | Запускает приложение из этого checkout на копии с включённым кабинетом | только локально |
| `05_staff_pass.sh` | Проход раздела 5: учётные записи, привязка менеджера оператором, сотрудники, смены, поручение, этапы, аудит, отказы | только локально |

Changelog для Liquibase берётся из git на `REHEARSAL_TARGET_REF` (`git archive`), а не из рабочей
папки: так проверяется именно то, что будет выложено.

## Локальный прогон

```bash
cd scripts/staff-portal-rehearsal
export REHEARSAL_OUT="$HOME/astor-staff-rehearsal-out" REHEARSAL_WRITE_TO=rehearsal_copy
docker compose -p astor-rehearsal -f compose.yaml up -d postgres

./01_build_before.sh
./02_backup_restore.sh
./03_changelog_plan.sh
REHEARSAL_UPDATE_COUNT=1 REHEARSAL_RUN_TAG=04a ./04_apply.sh   # только staff-portal
REHEARSAL_RUN_TAG=04b ./04_apply.sh                            # остальные ожидающие
REHEARSAL_RUN_TAG=04c ./03_changelog_plan.sh                   # копия совпала с целью

docker compose -p astor-rehearsal -f compose.yaml --profile app up -d redis mongo kafka
./05_identity_prepare.sh
./05_run_app.sh            # в отдельном окне, работает на переднем плане
REHEARSAL_PILOT_DIRECTORY=1 ./05_staff_pass.sh

docker compose -p astor-rehearsal -f compose.yaml --profile app --profile identity down -v
```

База «как её описывают документы» (всё применено, исторические суммы, справочник пилота):

```bash
REHEARSAL_SOURCE_DB=rehearsal_vmlike REHEARSAL_BEFORE_REF=origin/main \
  REHEARSAL_HISTORIC_CHECKSUMS=1 REHEARSAL_PILOT_DIRECTORY=1 ./01_build_before.sh
```

## На согласованной базе

Шаги 2, 3 и 4 повторяются теми же скриптами, меняется только окружение. Пароль задаётся в
оболочке, в файл репозитория его не пишут.

Вариант А, без нагрузки на рабочий сервер: ночной дамп восстанавливается в одноразовый PostgreSQL
из `compose.yaml` на любой машине с Docker.

```bash
docker compose -p astor-rehearsal -f compose.yaml up -d postgres
export REHEARSAL_OUT=/root/astor-staff-rehearsal-out
export REHEARSAL_COPY_DB=astor_rehearsal_copy REHEARSAL_WRITE_TO=astor_rehearsal_copy
export REHEARSAL_DUMP_FILE=/var/backups/astor/daily/astor-pg-<дата>.dump
./02_backup_restore.sh && ./03_changelog_plan.sh
# прочитать 03-summary.txt и 03-changelog-compare.txt, затем, если план принят:
./04_apply.sh
```

Вариант Б, копия рядом с источником на том же сервере PostgreSQL: задать `REHEARSAL_PGHOST`,
`REHEARSAL_PGPORT`, `REHEARSAL_PGUSER`, `REHEARSAL_PGPASSWORD`, `REHEARSAL_DOCKER_NETWORK`,
`REHEARSAL_SOURCE_DB` и имя копии. Тогда шаг 2 сам снимает дамп и сверяет копию с источником.

## Что остаётся в `REHEARSAL_OUT`

`NN-summary.txt` по каждому шагу, `03-changelog-compare.txt` (таблица по каждому changeset),
`03-plan.sql` (SQL, который выполнит update), `03-validate.log`, `03-status.log`, `04-update.log`,
`04-schema.diff`, `04-new-changelog-rows.txt`, `05-pass.txt`. Дамп `02-source.dump` и каталог
`identity/` удалить после прогона.
