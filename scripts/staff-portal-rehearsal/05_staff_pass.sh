#!/usr/bin/env bash
# Step 5, LOCAL ONLY: the pass of STAFF_PORTAL_RUNBOOK.md, section 5, against the application started by
# 05_run_app.sh and the Keycloak started by 05_identity_prepare.sh.
#
#   1. synthetic accounts are created in the local Keycloak (manager, two waiters, a manager of another venue);
#   2. checks before anybody is bound: a signed-in manager has no access yet;
#   3. the operator's step: the managers are bound to their venues in astor_staff_members of the copy;
#   4. the pass: the manager registers two employees and opens shifts, creates a task, only the assigned
#      employee with an open shift accepts it, stages, completion, the audit; another venue and a
#      closed shift are refused.
#
# REHEARSAL_PILOT_DIRECTORY=1 also puts hand-entered directory rows into the copy first, as the
# glasses pilot does, to show how they behave next to real accounts.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
prepare_out
require_copy_confirmation

: "${REHEARSAL_PYTHON:=python3}"
: "${REHEARSAL_APP_PORT:=28088}"
: "${REHEARSAL_KEYCLOAK_PUBLISHED_PORT:=28443}"
identity="$REHEARSAL_OUT/identity"
[ -f "$identity/tls/tls.crt" ] || die "run 05_identity_prepare.sh first"

export STAFF_ISSUER="https://localhost:$REHEARSAL_KEYCLOAK_PUBLISHED_PORT/realms/astor"
export STAFF_API_BASE="http://localhost:$REHEARSAL_APP_PORT"
export STAFF_REDIRECT_URI="https://localhost:$REHEARSAL_KEYCLOAK_PUBLISHED_PORT/rehearsal-callback/"
export STAFF_CA_FILE="$identity/tls/tls.crt"
export STAFF_ACCOUNTS_FILE="$identity/accounts.json"
export STAFF_KC_ADMIN_PASSWORD_FILE="$identity/admin-password"
flow=("$REHEARSAL_PYTHON" "$REHEARSAL_DIR/05_staff_flow.py")

# Start from an empty staff domain in the copy, so that the pass can be repeated.
sql "$REHEARSAL_COPY_DB" -c "TRUNCATE astor_staff_task_audit, astor_staff_task_events, astor_staff_tasks, astor_staff_members, astor_staff_tenant_locks" >/dev/null

"${flow[@]}" provision | tee "$REHEARSAL_OUT/05-provision.txt"

if [ "${REHEARSAL_PILOT_DIRECTORY:-0}" = "1" ]; then
  sql "$REHEARSAL_COPY_DB" -f /dev/stdin <"$REHEARSAL_DIR/seed_pilot_directory.sql" >/dev/null
  log "hand-entered directory rows added: $(sql "$REHEARSAL_COPY_DB" -c "SELECT count(*) FROM astor_staff_members WHERE staff_id LIKE 'pilot-%'")"
fi

set +e
"${flow[@]}" unbound | tee "$REHEARSAL_OUT/05-unbound.txt"; unbound_code=${PIPESTATUS[0]}
set -e

# The operator binds the managers. Subjects are read from the accounts file, not printed.
bound=0
while IFS='|' read -r tenant subject name; do
  sql "$REHEARSAL_COPY_DB" -v tenant="$tenant" -v staff_id="$subject" -v display_name="$name" -f /dev/stdin <"$REHEARSAL_DIR/bind_manager.sql" >/dev/null
  bound=$((bound + 1))
done < <("${flow[@]}" managers | tr -d '\r')
log "managers bound by the operator step: $bound"

set +e
"${flow[@]}" pass | tee "$REHEARSAL_OUT/05-pass.txt"; pass_code=${PIPESTATUS[0]}
set -e

{
  echo "rows in the copy after the pass:"
  for table in astor_staff_members astor_staff_tasks astor_staff_task_events astor_staff_task_audit astor_staff_tenant_locks; do
    echo "  $table: $(sql "$REHEARSAL_COPY_DB" -c "SELECT count(*) FROM $table")"
  done
  echo "audit rows by type: $(sql "$REHEARSAL_COPY_DB" -c "SELECT coalesce(string_agg(type || ' ' || n, ', ' ORDER BY type), 'none') FROM (SELECT type, count(*) AS n FROM astor_staff_task_audit GROUP BY type) t")"
  echo "tasks by venue and status: $(sql "$REHEARSAL_COPY_DB" -c "SELECT coalesce(string_agg(tenant || ' ' || status || ' ' || n, ', ' ORDER BY tenant, status), 'none') FROM (SELECT tenant, payload::json ->> 'status' AS status, count(*) AS n FROM astor_staff_tasks GROUP BY 1, 2) t")"
} | tee "$REHEARSAL_OUT/05-rows.txt"

[ "$unbound_code" = "0" ] && [ "$pass_code" = "0" ]
