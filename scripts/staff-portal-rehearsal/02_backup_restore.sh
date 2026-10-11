#!/usr/bin/env bash
# Step 2: prove that a backup of the source base restores, and leave the restored copy for steps 3-4.
#
# Two ways to get the dump:
#   a) default: pg_dump -Fc of REHEARSAL_SOURCE_DB on the same server. The source is only read.
#      The restored copy is then compared with the source: tables, exact row counts, a hash of the
#      content of each table, sequences, extensions and a schema diff.
#   b) REHEARSAL_DUMP_FILE=/path/astor-pg-....dump: a dump that already exists, such as the nightly
#      one (infra/cloudru/host/astor-backup.sh writes pg_dump -Fc files). It is restored into the
#      server these scripts point at, which can then be a throwaway PostgreSQL with no way to the
#      source at all. There is no source to compare with, so the proof is: pg_restore ends without
#      an error, every TABLE DATA section of the dump became a table, and the row counts are
#      written down for the owner to check against the live base.
#
# The copy is created here. An existing base of that name is dropped only with REHEARSAL_RECREATE=1.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
prepare_out
require_copy_confirmation

: "${REHEARSAL_HASH_MAX_ROWS:=200000}"
dump="02-source.dump"
from_file="${REHEARSAL_DUMP_FILE:-}"

if [ -z "$from_file" ]; then
  db_exists "$REHEARSAL_SOURCE_DB" || die "source base $REHEARSAL_SOURCE_DB does not exist"
fi
if db_exists "$REHEARSAL_COPY_DB"; then
  [ "${REHEARSAL_RECREATE:-0}" = "1" ] || die "$REHEARSAL_COPY_DB already exists; set REHEARSAL_RECREATE=1 to drop and restore it again"
  drop_marked_db "$REHEARSAL_COPY_DB"
fi

if [ -n "$from_file" ]; then
  [ -f "$from_file" ] || die "no dump at $from_file"
  cp "$from_file" "$REHEARSAL_OUT/$dump"
  dump_note="taken from $(basename "$from_file")"
  # A dump of another server names roles this one does not have.
  : "${REHEARSAL_RESTORE_OPTIONS:=--no-owner --no-privileges}"
  create_copy="CREATE DATABASE \"$REHEARSAL_COPY_DB\" TEMPLATE template0 ENCODING 'UTF8'"
else
  started=$(date +%s)
  pg_tool pg_dump -Fc -d "$REHEARSAL_SOURCE_DB" -f "/work/$dump"
  dump_note="taken in $(( $(date +%s) - started )) s"
  : "${REHEARSAL_RESTORE_OPTIONS:=}"
  # The copy gets the encoding and locale of the source, whatever the defaults of the server are.
  create_copy="$(sql "$REHEARSAL_ADMIN_DB" -c "SELECT format('CREATE DATABASE %I TEMPLATE template0 ENCODING %L LC_COLLATE %L LC_CTYPE %L', '$REHEARSAL_COPY_DB', pg_encoding_to_char(encoding), datcollate, datctype) FROM pg_database WHERE datname = '$REHEARSAL_SOURCE_DB'")"
fi
dump_bytes=$(wc -c <"$REHEARSAL_OUT/$dump" | tr -d ' ')
pg_tool pg_restore --list "/work/$dump" >"$REHEARSAL_OUT/02-dump-contents.txt"
table_sections=$(grep -c 'TABLE DATA' "$REHEARSAL_OUT/02-dump-contents.txt" || true)
[ "$dump_bytes" -gt 1024 ] && [ "$table_sections" -gt 0 ] || die "the dump looks empty ($dump_bytes bytes, $table_sections table sections)"
log "dump: $dump_bytes bytes, $table_sections table sections, $dump_note"

create_marked_db "$REHEARSAL_COPY_DB" "restored copy" "$create_copy"
started=$(date +%s)
# shellcheck disable=SC2086
pg_tool pg_restore --exit-on-error $REHEARSAL_RESTORE_OPTIONS -d "$REHEARSAL_COPY_DB" "/work/$dump" 2>"$REHEARSAL_OUT/02-restore.log" \
  || { tail -20 "$REHEARSAL_OUT/02-restore.log" >&2; die "pg_restore failed, see 02-restore.log"; }
restore_seconds=$(( $(date +%s) - started ))
log "restore: $restore_seconds s"

# The facts of one base, written to files that can be compared.
facts() {
  local db="$1" prefix="$2"
  sql "$db" -c "SELECT schemaname || '.' || tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema') ORDER BY 1" >"$REHEARSAL_OUT/$prefix-tables.txt"
  row_counts "$db" >"$REHEARSAL_OUT/$prefix-rows.txt"
  sql "$db" -F '|' -c "SELECT schemaname || '.' || sequencename, coalesce(last_value::text, 'unused') FROM pg_sequences ORDER BY 1" >"$REHEARSAL_OUT/$prefix-sequences.txt"
  sql "$db" -F '|' -c "SELECT extname, extversion FROM pg_extension ORDER BY 1" >"$REHEARSAL_OUT/$prefix-extensions.txt"
  schema_dump "$db" >"$REHEARSAL_OUT/$prefix-schema.sql"
  # One md5 over the text of all rows of a table, for every table that is not too large to sort.
  local small query
  small="$(awk -F'|' -v max="$REHEARSAL_HASH_MAX_ROWS" '$2 <= max {printf "%s%s", sep, $1; sep=","}' "$REHEARSAL_OUT/$prefix-rows.txt")"
  query="$(sql "$db" -c "SELECT string_agg(format('SELECT %L || ''|'' || coalesce(md5(string_agg(t::text, E''\\n'' ORDER BY t::text COLLATE \"C\")), ''empty'') FROM %I.%I AS t', schemaname || '.' || tablename, schemaname, tablename), ' UNION ALL ' ORDER BY schemaname, tablename) FROM pg_tables WHERE schemaname || '.' || tablename = ANY (string_to_array('$small', ','))")"
  { [ -z "$query" ] || sql "$db" -c "$query" | sort; } >"$REHEARSAL_OUT/$prefix-hashes.txt"
  awk -F'|' -v max="$REHEARSAL_HASH_MAX_ROWS" '$2 > max {print $1 "|skipped, more than " max " rows"}' "$REHEARSAL_OUT/$prefix-rows.txt" >>"$REHEARSAL_OUT/$prefix-hashes.txt"
}
total_rows() { awk -F'|' '{s += $2} END {print s + 0}' "$1"; }
lines() { wc -l <"$1" | tr -d ' '; }
verdict() {
  local what="$1" suffix="$2"
  if diff -q "$REHEARSAL_OUT/02-source-$suffix" "$REHEARSAL_OUT/02-copy-$suffix" >/dev/null; then
    echo "  $what: equal"
  else
    echo "  $what: DIFFERENT, see 02-source-$suffix and 02-copy-$suffix"
  fi
}

facts "$REHEARSAL_COPY_DB" 02-copy
server="$(sql "$REHEARSAL_ADMIN_DB" -c 'SHOW server_version')"
if [ -n "$from_file" ]; then
  {
    echo "dump file: $(basename "$from_file"), copy: $REHEARSAL_COPY_DB, server: $server"
    echo "dump: $dump_bytes bytes, $table_sections TABLE DATA sections; restored in $restore_seconds s with options: ${REHEARSAL_RESTORE_OPTIONS:-none}"
    echo "tables restored: $(lines "$REHEARSAL_OUT/02-copy-tables.txt"), rows restored: $(total_rows "$REHEARSAL_OUT/02-copy-rows.txt")"
    if [ "$(lines "$REHEARSAL_OUT/02-copy-tables.txt")" = "$table_sections" ]; then
      echo "  every TABLE DATA section of the dump became a table: yes"
    else
      echo "  every TABLE DATA section of the dump became a table: DIFFERENT"
    fi
    echo "  extensions: $(tr '\n' ' ' <"$REHEARSAL_OUT/02-copy-extensions.txt")"
    echo "  largest tables: $(sort -t'|' -k2 -n -r "$REHEARSAL_OUT/02-copy-rows.txt" | head -5 | tr '\n' ' ')"
    echo "  not compared with the source: it was not reachable by design; the row counts are in 02-copy-rows.txt"
  } | tee "$REHEARSAL_OUT/02-summary.txt"
else
  facts "$REHEARSAL_SOURCE_DB" 02-source
  {
    echo "source: $REHEARSAL_SOURCE_DB, copy: $REHEARSAL_COPY_DB, server: $server"
    echo "dump: $dump_bytes bytes, $table_sections TABLE DATA sections, $dump_note; restored in $restore_seconds s"
    echo "tables: $(lines "$REHEARSAL_OUT/02-source-tables.txt") in the source, $(lines "$REHEARSAL_OUT/02-copy-tables.txt") in the copy"
    echo "rows: $(total_rows "$REHEARSAL_OUT/02-source-rows.txt") in the source, $(total_rows "$REHEARSAL_OUT/02-copy-rows.txt") in the copy"
    echo "tables hashed: $(grep -vc 'skipped' "$REHEARSAL_OUT/02-source-hashes.txt" || true), skipped as too large: $(grep -c 'skipped' "$REHEARSAL_OUT/02-source-hashes.txt" || true)"
    verdict "table list" tables.txt
    verdict "row count of every table" rows.txt
    verdict "content hash of every table" hashes.txt
    verdict "sequence positions" sequences.txt
    verdict "extensions and versions" extensions.txt
    echo "  schema (pg_dump --schema-only): $(schema_verdict "$REHEARSAL_OUT/02-source-schema.sql" "$REHEARSAL_OUT/02-copy-schema.sql" || true)"
  } | tee "$REHEARSAL_OUT/02-summary.txt"
fi
# The verdicts are taken from the written summary: the blocks above run in a pipe.
if grep -q 'DIFFERENT' "$REHEARSAL_OUT/02-summary.txt"; then
  echo "RESULT: THE RESTORED BASE IS NOT WHAT THE BACKUP HOLDS" | tee -a "$REHEARSAL_OUT/02-summary.txt"
  exit 1
fi
if [ -n "$from_file" ]; then
  echo "RESULT: the dump restores without errors" | tee -a "$REHEARSAL_OUT/02-summary.txt"
else
  echo "RESULT: the backup restores into an identical base" | tee -a "$REHEARSAL_OUT/02-summary.txt"
fi
