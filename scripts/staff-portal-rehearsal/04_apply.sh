#!/usr/bin/env bash
# Step 4: apply the pending changesets of the target ref to the copy and record what happened.
#
# Run 03_changelog_plan.sh first and read its result: this script does not decide whether the plan is
# acceptable, it only refuses to start when Liquibase validation fails.
# REHEARSAL_UPDATE_COUNT=N applies only the next N changesets (update-count) instead of all of them.
#
# Recorded: time, the changesets Liquibase ran, the schema diff, tables that appeared, row counts of
# the tables that existed before (they must not change), the DATABASECHANGELOG rows after.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
prepare_out
require_copy_confirmation

db_exists "$REHEARSAL_COPY_DB" || die "copy base $REHEARSAL_COPY_DB does not exist"
tag="${REHEARSAL_RUN_TAG:-04}"
target_sha="$(git -C "$REPO_DIR" rev-parse --short "$REHEARSAL_TARGET_REF")"
export_changelog "$REHEARSAL_TARGET_REF" "$REHEARSAL_OUT/changelog-target"

liquibase_run "$REHEARSAL_COPY_DB" "$REHEARSAL_OUT/changelog-target" validate >"$REHEARSAL_OUT/$tag-validate-before.log" 2>&1 \
  || { tail -20 "$REHEARSAL_OUT/$tag-validate-before.log" >&2; die "validation fails on the copy; nothing was applied. Record it, do not clear checksums"; }

tables() { sql "$REHEARSAL_COPY_DB" -c "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1"; }
schema_dump "$REHEARSAL_COPY_DB" >"$REHEARSAL_OUT/$tag-schema-before.sql"
tables >"$REHEARSAL_OUT/$tag-tables-before.txt"
row_counts "$REHEARSAL_COPY_DB" | grep -v -e '^public.databasechangelog|' -e '^public.databasechangeloglock|' >"$REHEARSAL_OUT/$tag-rows-before.txt"
changelog_rows "$REHEARSAL_COPY_DB" >"$REHEARSAL_OUT/$tag-changelog-before.txt"

if [ -n "${REHEARSAL_UPDATE_COUNT:-}" ]; then
  command=(update-count --count="$REHEARSAL_UPDATE_COUNT")
else
  command=(update)
fi
log "applying to $REHEARSAL_COPY_DB: liquibase ${command[*]} at $target_sha"
started=$(date +%s.%N)
set +e
liquibase_run "$REHEARSAL_COPY_DB" "$REHEARSAL_OUT/changelog-target" --log-level=INFO "${command[@]}" >"$REHEARSAL_OUT/$tag-update.log" 2>&1
update_code=$?
set -e
seconds=$(awk -v a="$started" -v b="$(date +%s.%N)" 'BEGIN {printf "%.1f", b - a}')

schema_dump "$REHEARSAL_COPY_DB" >"$REHEARSAL_OUT/$tag-schema-after.sql"
tables >"$REHEARSAL_OUT/$tag-tables-after.txt"
row_counts "$REHEARSAL_COPY_DB" | grep -v -e '^public.databasechangelog|' -e '^public.databasechangeloglock|' >"$REHEARSAL_OUT/$tag-rows-after.txt"
changelog_rows "$REHEARSAL_COPY_DB" >"$REHEARSAL_OUT/$tag-changelog-after.txt"
diff "$REHEARSAL_OUT/$tag-schema-before.sql" "$REHEARSAL_OUT/$tag-schema-after.sql" >"$REHEARSAL_OUT/$tag-schema.diff" || true
comm -13 "$REHEARSAL_OUT/$tag-tables-before.txt" "$REHEARSAL_OUT/$tag-tables-after.txt" >"$REHEARSAL_OUT/$tag-new-tables.txt"
comm -13 "$REHEARSAL_OUT/$tag-changelog-before.txt" "$REHEARSAL_OUT/$tag-changelog-after.txt" >"$REHEARSAL_OUT/$tag-new-changelog-rows.txt"
# Old tables keep their rows: compare the counts of every table that existed before.
join -t'|' "$REHEARSAL_OUT/$tag-rows-before.txt" "$REHEARSAL_OUT/$tag-rows-after.txt" | awk -F'|' '$2 != $3' >"$REHEARSAL_OUT/$tag-row-count-changes.txt"
# Liquibase must not rewrite the stored MD5SUM of changesets that were already there.
changed_old_rows=$(awk -F'|' 'NR == FNR {old[$2 FS $3 FS $4] = $5; next} ($2 FS $3 FS $4) in old && old[$2 FS $3 FS $4] != $5 {n++} END {print n + 0}' "$REHEARSAL_OUT/$tag-changelog-before.txt" "$REHEARSAL_OUT/$tag-changelog-after.txt")

set +e
liquibase_run "$REHEARSAL_COPY_DB" "$REHEARSAL_OUT/changelog-target" status >"$REHEARSAL_OUT/$tag-status-after.log" 2>&1
set -e

# Drift: the copy after the update against a base built from the target changelog from scratch
# (the scratch base of step 3). Only meaningful when everything pending was applied.
drift="not compared: no scratch base $REHEARSAL_EXPECTED_DB"
if grep -q -i -E 'ha(ve|s) not been applied' "$REHEARSAL_OUT/$tag-status-after.log"; then
  drift="not compared: some changesets are still pending"
elif db_exists "$REHEARSAL_EXPECTED_DB"; then
  schema_dump "$REHEARSAL_EXPECTED_DB" --exclude-schema=rehearsal_report >"$REHEARSAL_OUT/$tag-schema-expected.sql"
  diff "$REHEARSAL_OUT/$tag-schema-expected.sql" "$REHEARSAL_OUT/$tag-schema-after.sql" >"$REHEARSAL_OUT/$tag-schema-drift.diff" || true
  drift="$(schema_verdict "$REHEARSAL_OUT/$tag-schema-expected.sql" "$REHEARSAL_OUT/$tag-schema-after.sql" || true); details in $tag-schema-drift.diff ('<' = only in the scratch base, '>' = only in the copy)"
fi

{
  echo "copy: $REHEARSAL_COPY_DB, target: $REHEARSAL_TARGET_REF = $target_sha, command: liquibase ${command[*]}"
  echo "exit code: $update_code, wall time with the container start: $seconds s"
  echo "changesets run by Liquibase:"
  grep -E 'ran successfully in' "$REHEARSAL_OUT/$tag-update.log" | sed -E 's/^.*ChangeSet /  /' || echo "  none"
  grep -E '^(Run|Previously run|Filtered out|Total change sets):' "$REHEARSAL_OUT/$tag-update.log" | tr -s ' ' | sed 's/^/  /' || true
  echo "DATABASECHANGELOG: $(wc -l <"$REHEARSAL_OUT/$tag-changelog-before.txt" | tr -d ' ') rows before, $(wc -l <"$REHEARSAL_OUT/$tag-changelog-after.txt" | tr -d ' ') after; MD5SUM of old rows changed: $changed_old_rows"
  echo "new DATABASECHANGELOG rows (order|id|author|path|md5|exectype|liquibase|executed):"
  sed 's/^/  /' "$REHEARSAL_OUT/$tag-new-changelog-rows.txt"
  echo "tables: $(wc -l <"$REHEARSAL_OUT/$tag-tables-before.txt" | tr -d ' ') before, $(wc -l <"$REHEARSAL_OUT/$tag-tables-after.txt" | tr -d ' ') after; new: $(tr '\n' ' ' <"$REHEARSAL_OUT/$tag-new-tables.txt")"
  echo "schema diff: $(grep -c '^>' "$REHEARSAL_OUT/$tag-schema.diff" || true) lines added, $(grep -c '^<' "$REHEARSAL_OUT/$tag-schema.diff" || true) removed ($tag-schema.diff)"
  echo "old tables whose row count changed: $(wc -l <"$REHEARSAL_OUT/$tag-row-count-changes.txt" | tr -d ' ')"
  echo "staff portal tables and their rows:"
  grep -E '^public\.astor_staff_' "$REHEARSAL_OUT/$tag-rows-after.txt" | sed 's/^/  /' || echo "  none"
  echo "liquibase status after: $(grep -m1 -i -E 'ha(ve|s) not been applied|is up to date' "$REHEARSAL_OUT/$tag-status-after.log" || echo 'see the log')"
  echo "schema of the copy against a base built from scratch at the target: $drift"
  if [ "$update_code" = "0" ]; then echo "RESULT: applied"; else echo "RESULT: THE UPDATE FAILED, see $tag-update.log"; fi
} | tee "$REHEARSAL_OUT/$tag-summary.txt"
[ "$update_code" = "0" ]
