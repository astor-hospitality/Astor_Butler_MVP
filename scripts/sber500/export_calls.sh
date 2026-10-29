#!/usr/bin/env bash
# Sber500 x Disrupt, этап 1: выгрузка единого журнала вызовов и псевдонимных пользователей из PostgreSQL Butler.
# Только чтение: два SELECT через psql внутри контейнера postgres. Схему не меняет, сервисы не трогает.
#
#   SBER500_SALT='<секрет>' scripts/sber500/export_calls.sh [OUT_DIR] [SINCE]
#
#   OUT_DIR  каталог для CSV (по умолчанию ./sber500_export)
#   SINCE    нижняя граница по времени, напр. 2026-10-01 (по умолчанию 1970-01-01 — всё)
#
# Окружение (или те же значения позиционными аргументами 3..5):
#   SBER500_SALT        обязательно; соль псевдонимизации, хранить вне репозитория и одинаковой между выгрузками
#   PG_CONTAINER        контейнер postgres (по умолчанию astor_postgres_test — имя из docker-compose.yml, оно же на ВМ)
#   PG_DB / PG_USER     база и пользователь; по умолчанию берутся POSTGRES_DB / POSTGRES_USER из окружения самого
#                       контейнера (как в infra/cloudru/host/astor-backup.sh), т.е. на ВМ ничего задавать не нужно
#
# Результат: OUT_DIR/sber500_calls_<YYYY-MM-DD>.csv и OUT_DIR/sber500_users_<YYYY-MM-DD>.csv,
# на экран — число строк по call_type и число уникальных pseudo_user_id.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${1:-./sber500_export}"
SINCE="${2:-1970-01-01}"
PG_CONTAINER="${3:-${PG_CONTAINER:-astor_postgres_test}}"
PG_DB="${4:-${PG_DB:-}}"
PG_USER="${5:-${PG_USER:-}}"

if [[ -z "${SBER500_SALT:-}" ]]; then
  echo "SBER500_SALT не задан: без соли выгрузка не запускается (pseudo_user_id = md5(salt || id))." >&2
  exit 2
fi
if [[ ${#SBER500_SALT} -lt 16 ]]; then
  echo "SBER500_SALT короче 16 символов — возьмите длиннее (например, openssl rand -hex 16)." >&2
  exit 2
fi
if ! [[ "$SINCE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  echo "SINCE должен быть датой YYYY-MM-DD, получено: $SINCE" >&2
  exit 2
fi
command -v docker >/dev/null || { echo "docker не найден" >&2; exit 2; }
docker inspect "$PG_CONTAINER" >/dev/null 2>&1 || { echo "контейнер $PG_CONTAINER не найден (задайте PG_CONTAINER)" >&2; exit 2; }

mkdir -p "$OUT_DIR"
STAMP="$(date +%F)"
CALLS="$OUT_DIR/sber500_calls_${STAMP}.csv"
USERS="$OUT_DIR/sber500_users_${STAMP}.csv"

run_sql() {
  # Соль уходит в psql переменной через окружение docker exec, а не в командной строке хоста.
  docker exec -i -e SBER500_SALT="$SBER500_SALT" "$PG_CONTAINER" \
    sh -c 'exec psql -X -q -v ON_ERROR_STOP=1 -v salt="$SBER500_SALT" -v since="$1" -U "${2:-$POSTGRES_USER}" -d "${3:-$POSTGRES_DB}" -At -F "," -f -' \
    psql-sber500 "$SINCE" "$PG_USER" "$PG_DB" < "$1"
}

umask 077
{
  echo 'ts,source,pseudo_user_id,call_type,provider_or_system,result,error_code,tokens_in,tokens_out,cost_rub,latency_ms,correlation_id'
  run_sql "$SCRIPT_DIR/export_calls.sql"
} > "$CALLS"

{
  echo 'day,source,users,users_with_guest_message,users_with_llm_call,users_reached_reservation,users_reached_bill,users_reached_any_target_action'
  run_sql "$SCRIPT_DIR/export_users.sql"
} > "$USERS"

echo "Записано: $CALLS ($(($(wc -l < "$CALLS") - 1)) строк), $USERS"
echo
echo "Строк по call_type:"
tail -n +2 "$CALLS" | cut -d',' -f4 | sort | uniq -c | sort -rn
echo
echo "Уникальных pseudo_user_id (без пустых): $(tail -n +2 "$CALLS" | cut -d',' -f3 | grep -v '^$' | sort -u | wc -l | tr -d ' ')"
echo "Дошли до целевого действия (бронь или счёт): $(tail -n +2 "$CALLS" | awk -F',' '$4 ~ /^target_action:/ && $3 != "" {print $3}' | sort -u | wc -l | tr -d ' ')"
