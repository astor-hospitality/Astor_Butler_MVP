#!/usr/bin/env bash
# Step 3: on the copy, compare DATABASECHANGELOG with what the target ref expects and take the plan.
#
#   1. The changelog is exported from git at REHEARSAL_TARGET_REF (LF, as the image is built).
#   2. A scratch base is built from it on an empty database: its DATABASECHANGELOG is "expected".
#   3. The copy is compared with it by id, author, path, MD5SUM and order.
#   4. Liquibase itself is asked about the copy: validate, status --verbose, update-sql.
#
# Nothing is repaired. clearCheckSums and changelogSync are never called: a difference is a finding.
# Exit code 0 = no blocking difference, 2 = differences that somebody has to look at.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
prepare_out
require_copy_confirmation

db_exists "$REHEARSAL_COPY_DB" || die "copy base $REHEARSAL_COPY_DB does not exist; run 02_backup_restore.sh first"
[ "$(sql "$REHEARSAL_COPY_DB" -c "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = 'databasechangelog'")" = "1" ] \
  || die "the copy has no DATABASECHANGELOG table: this base was not built by Liquibase"

tag="${REHEARSAL_RUN_TAG:-03}"
target_sha="$(git -C "$REPO_DIR" rev-parse --short "$REHEARSAL_TARGET_REF")"
export_changelog "$REHEARSAL_TARGET_REF" "$REHEARSAL_OUT/changelog-target"
log "target changelog: $REHEARSAL_TARGET_REF = $target_sha"

# Expected: the target changelog on an empty base. The scratch base is rebuilt on every run.
case "$REHEARSAL_EXPECTED_DB" in "$REHEARSAL_COPY_DB"|"$REHEARSAL_SOURCE_DB") die "the scratch base must have its own name" ;; esac
if db_exists "$REHEARSAL_EXPECTED_DB"; then drop_marked_db "$REHEARSAL_EXPECTED_DB"; fi
create_marked_db "$REHEARSAL_EXPECTED_DB" "scratch base built from the target changelog"
liquibase_run "$REHEARSAL_EXPECTED_DB" "$REHEARSAL_OUT/changelog-target" update >"$REHEARSAL_OUT/$tag-expected-build.log" 2>&1 \
  || { tail -40 "$REHEARSAL_OUT/$tag-expected-build.log" >&2; die "the target changelog does not apply to an empty base, see $tag-expected-build.log"; }

# Move the copy's rows and the declared validCheckSum values next to the expected ones.
columns="orderexecuted, id, author, filename, md5sum, exectype, liquibase, dateexecuted"
sql "$REHEARSAL_COPY_DB" -c "\\copy (SELECT $columns FROM databasechangelog ORDER BY orderexecuted, id) TO STDOUT WITH (FORMAT csv)" >"$REHEARSAL_OUT/$tag-copy-changelog.csv"
sql "$REHEARSAL_EXPECTED_DB" -c "\\copy (SELECT $columns FROM databasechangelog ORDER BY orderexecuted, id) TO STDOUT WITH (FORMAT csv)" >"$REHEARSAL_OUT/$tag-expected-changelog.csv"
cat "$REHEARSAL_OUT/changelog-target/src/main/resources/db/changelog/"*.yaml | awk -f "$REHEARSAL_DIR/valid_checksums.awk" >"$REHEARSAL_OUT/$tag-valid-checksums.csv"
sql "$REHEARSAL_EXPECTED_DB" -c "CREATE SCHEMA rehearsal_report; CREATE TABLE rehearsal_report.copy_log (orderexecuted int, id text, author text, filename text, md5sum text, exectype text, liquibase text, dateexecuted timestamp); CREATE TABLE rehearsal_report.valid_sums (id text, md5sum text)" >/dev/null
sql "$REHEARSAL_EXPECTED_DB" -c "\\copy rehearsal_report.copy_log FROM STDIN WITH (FORMAT csv)" <"$REHEARSAL_OUT/$tag-copy-changelog.csv" >/dev/null
sql "$REHEARSAL_EXPECTED_DB" -c "\\copy rehearsal_report.valid_sums FROM STDIN WITH (FORMAT csv)" <"$REHEARSAL_OUT/$tag-valid-checksums.csv" >/dev/null
pg_tool psql -X -v ON_ERROR_STOP=1 -d "$REHEARSAL_EXPECTED_DB" -f /dev/stdin <"$REHEARSAL_DIR/03_compare.sql" >"$REHEARSAL_OUT/$tag-changelog-compare.txt"

count() { sql "$REHEARSAL_EXPECTED_DB" -c "SELECT count(*) FROM rehearsal_report.compared WHERE $1"; }
ids() { sql "$REHEARSAL_EXPECTED_DB" -c "SELECT coalesce(string_agg(id, ', ' ORDER BY coalesce(expected_order, 100000 + copy_order)), 'none') FROM rehearsal_report.compared WHERE $1"; }
same=$(count "verdict = 'same'")
pending=$(count "verdict = 'PENDING'")
unknown=$(count "verdict = 'UNKNOWN'")
path_differs=$(count "verdict = 'PATH DIFFERS'")
md5_accepted=$(count "verdict = 'MD5 DIFFERS, ACCEPTED'")
md5_differs=$(count "verdict = 'MD5 DIFFERS'")
out_of_order=$(sql "$REHEARSAL_EXPECTED_DB" -c "SELECT count(*) FROM (SELECT row_number() OVER (ORDER BY copy_order, id) AS a, row_number() OVER (ORDER BY expected_order, id) AS b FROM rehearsal_report.compared WHERE copy_order IS NOT NULL AND expected_order IS NOT NULL) r WHERE a <> b")
changeset_state=$(sql "$REHEARSAL_EXPECTED_DB" -c "SELECT coalesce((SELECT verdict FROM rehearsal_report.compared WHERE id = '$REHEARSAL_CHANGESET_ID'), 'NOT IN THE TARGET CHANGELOG')")

# Liquibase's own word on the copy. None of the three changes the schema; they take and release the lock row.
set +e
liquibase_run "$REHEARSAL_COPY_DB" "$REHEARSAL_OUT/changelog-target" validate >"$REHEARSAL_OUT/$tag-validate.log" 2>&1; validate_code=$?
liquibase_run "$REHEARSAL_COPY_DB" "$REHEARSAL_OUT/changelog-target" status --verbose >"$REHEARSAL_OUT/$tag-status.log" 2>&1; status_code=$?
liquibase_run "$REHEARSAL_COPY_DB" "$REHEARSAL_OUT/changelog-target" --output-file="/work/$tag-plan.sql" update-sql >"$REHEARSAL_OUT/$tag-plan.log" 2>&1; plan_code=$?
set -e
plan_statements=0
[ -f "$REHEARSAL_OUT/$tag-plan.sql" ] && plan_statements=$(grep -c -i -E '^(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|COMMENT|GRANT|REVOKE|TRUNCATE) ' "$REHEARSAL_OUT/$tag-plan.sql" || true)

{
  echo "copy: $REHEARSAL_COPY_DB, target: $REHEARSAL_TARGET_REF = $target_sha, Liquibase image: $REHEARSAL_LIQUIBASE_IMAGE"
  echo "changesets: $(wc -l <"$REHEARSAL_OUT/$tag-copy-changelog.csv" | tr -d ' ') recorded in the copy, $(wc -l <"$REHEARSAL_OUT/$tag-expected-changelog.csv" | tr -d ' ') in the target changelog"
  echo "  same id, author, path and MD5SUM: $same"
  echo "  pending (the update will run them): $pending [$(ids "verdict = 'PENDING'")]"
  echo "  in the copy but not in the target changelog: $unknown [$(ids "verdict = 'UNKNOWN'")]"
  echo "  path differs: $path_differs [$(ids "verdict = 'PATH DIFFERS'")]"
  echo "  MD5SUM differs, accepted by a declared validCheckSum: $md5_accepted [$(ids "verdict = 'MD5 DIFFERS, ACCEPTED'")]"
  echo "  MD5SUM differs, not accepted: $md5_differs [$(ids "verdict = 'MD5 DIFFERS'")]"
  echo "  common changesets out of the target order: $out_of_order"
  echo "changeset $REHEARSAL_CHANGESET_ID in the copy: $changeset_state"
  echo "liquibase validate: exit $validate_code ($(grep -m1 -i -E 'No validation errors|Validation Failed|validation' "$REHEARSAL_OUT/$tag-validate.log" || echo 'see the log'))"
  echo "liquibase status --verbose: exit $status_code ($(grep -m1 -i -E 'changesets? ha(ve|s) not been applied|is up to date' "$REHEARSAL_OUT/$tag-status.log" || echo 'see the log'))"
  echo "liquibase update-sql: exit $plan_code, $plan_statements statements in $tag-plan.sql"
  # A different order of changesets that both sides have is reported but does not stop Liquibase.
  if [ "$unknown$path_differs$md5_differs" = "000" ] && [ "$validate_code$status_code$plan_code" = "000" ]; then
    echo "RESULT: no blocking difference; the plan is $pending changeset(s)"
  else
    echo "RESULT: DIFFERENCES TO REVIEW, see $tag-changelog-compare.txt, $tag-validate.log, $tag-status.log"
  fi
} | tee "$REHEARSAL_OUT/$tag-summary.txt"
grep -q '^RESULT: no blocking difference' "$REHEARSAL_OUT/$tag-summary.txt" || exit 2
