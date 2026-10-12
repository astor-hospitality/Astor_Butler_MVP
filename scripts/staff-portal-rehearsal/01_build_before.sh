#!/usr/bin/env bash
# Step 1, LOCAL ONLY: build a stand-in for "the existing base".
#
# The schema is the one the repository's own migrations give at the commit just before the
# changeset under rehearsal was added, plus a few rows so that a restore has something to lose.
# On an agreed base this step is skipped: the real base is the source, set REHEARSAL_SOURCE_DB.
#
# Refuses to touch a base that already exists unless REHEARSAL_RECREATE=1.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
prepare_out

added_in="$(git -C "$REPO_DIR" log --diff-filter=A --format=%H -1 "$REHEARSAL_TARGET_REF" -- "src/main/resources/db/changelog/$REHEARSAL_CHANGESET_ID.yaml")"
[ -n "$added_in" ] || die "no commit adds $REHEARSAL_CHANGESET_ID.yaml on $REHEARSAL_TARGET_REF"
: "${REHEARSAL_BEFORE_REF:=${added_in}^}"
before_sha="$(git -C "$REPO_DIR" rev-parse --short "$REHEARSAL_BEFORE_REF")"
log "changeset $REHEARSAL_CHANGESET_ID was added in $(git -C "$REPO_DIR" rev-parse --short "$added_in"); the base is built at $before_sha"

if db_exists "$REHEARSAL_SOURCE_DB"; then
  [ "${REHEARSAL_RECREATE:-0}" = "1" ] || die "$REHEARSAL_SOURCE_DB already exists; set REHEARSAL_RECREATE=1 to drop and rebuild it"
  drop_marked_db "$REHEARSAL_SOURCE_DB"
fi
create_marked_db "$REHEARSAL_SOURCE_DB" "local stand-in for the existing base"

export_changelog "$REHEARSAL_BEFORE_REF" "$REHEARSAL_OUT/changelog-before"
started=$(date +%s)
liquibase_run "$REHEARSAL_SOURCE_DB" "$REHEARSAL_OUT/changelog-before" update >"$REHEARSAL_OUT/01-build-before.log" 2>&1 \
  || { tail -40 "$REHEARSAL_OUT/01-build-before.log" >&2; die "Liquibase could not build the base, see 01-build-before.log"; }
log "schema built in $(( $(date +%s) - started )) s"

sql "$REHEARSAL_SOURCE_DB" -f /dev/stdin <"$REHEARSAL_DIR/seed_before.sql" >"$REHEARSAL_OUT/01-seed.log"

# Optional: imitate what the repository says about the AERIS base. Its DATABASECHANGELOG keeps older
# MD5SUM values for a few changesets, and the changelog accepts them through validCheckSum (see the
# comment in 2026-07-23-smart-solution-ops-seed.yaml). A base built from scratch never has them, so
# the first declared value that differs from the computed one is written here. This is a simulation
# in a throwaway base, never something to do to a real DATABASECHANGELOG.
if [ "${REHEARSAL_HISTORIC_CHECKSUMS:-0}" = "1" ]; then
  export_changelog "$REHEARSAL_TARGET_REF" "$REHEARSAL_OUT/changelog-target"
  values="$(cat "$REHEARSAL_OUT/changelog-target/src/main/resources/db/changelog/"*.yaml | awk -f "$REHEARSAL_DIR/valid_checksums.awk" \
    | awk -F',' '{printf "%s(%d, '\''%s'\'', '\''%s'\'')", sep, NR, $1, $2; sep=", "}')"
  if [ -n "$values" ]; then
    sql "$REHEARSAL_SOURCE_DB" -F '|' -c "UPDATE databasechangelog d SET md5sum = v.declared FROM (SELECT DISTINCT ON (t.id) t.id, t.declared FROM (VALUES $values) AS t(n, id, declared) JOIN databasechangelog c ON c.id = t.id WHERE c.md5sum <> t.declared ORDER BY t.id, t.n) v WHERE d.id = v.id RETURNING d.id, d.md5sum" \
      | grep '|' >"$REHEARSAL_OUT/01-historic-checksums.txt" || true
    log "historic checksums simulated for $(wc -l <"$REHEARSAL_OUT/01-historic-checksums.txt" | tr -d ' ') changeset(s)"
  fi
fi

# Optional: a staff directory entered by hand, as the glasses pilot does it (GLASSES_VOICE_TASKS.md):
# rows whose staff_id is not a Keycloak subject. Only possible when the base already has the staff tables.
if [ "${REHEARSAL_PILOT_DIRECTORY:-0}" = "1" ]; then
  [ "$(sql "$REHEARSAL_SOURCE_DB" -c "SELECT count(*) FROM pg_tables WHERE tablename = 'astor_staff_members'")" = "1" ] \
    || die "REHEARSAL_PILOT_DIRECTORY=1 needs a base that already has astor_staff_members (set REHEARSAL_BEFORE_REF)"
  sql "$REHEARSAL_SOURCE_DB" -f /dev/stdin <"$REHEARSAL_DIR/seed_pilot_directory.sql" >>"$REHEARSAL_OUT/01-seed.log"
fi

{
  echo "before ref: $before_sha"
  echo "changesets applied: $(sql "$REHEARSAL_SOURCE_DB" -c 'SELECT count(*) FROM databasechangelog')"
  echo "last changeset: $(sql "$REHEARSAL_SOURCE_DB" -c 'SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 1')"
  echo "tables: $(sql "$REHEARSAL_SOURCE_DB" -c "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")"
  echo "staff tables present: $(sql "$REHEARSAL_SOURCE_DB" -c "SELECT count(*) FROM pg_tables WHERE tablename LIKE 'astor_staff_%'")"
  echo "rows by table (non-empty):"
  row_counts "$REHEARSAL_SOURCE_DB" | grep -v '|0$' | sed 's/^/  /'
} | tee "$REHEARSAL_OUT/01-summary.txt"
