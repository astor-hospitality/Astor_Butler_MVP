#!/usr/bin/env bash
# Shared settings and helpers for the staff portal migration rehearsal.
# Sourced by the numbered scripts; it does nothing by itself.
#
# Everything is read from the environment (see env.example). The defaults point at the
# local compose stack of this folder and at nothing else: no script contains a server address.

set -euo pipefail

# Git Bash on Windows rewrites arguments that look like Unix paths (/work/x.dump) before docker
# sees them. The two variables switch that off for docker only (git needs the rewriting) and are
# ignored on Linux.
dk() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker "$@"; }

REHEARSAL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$REHEARSAL_DIR/../.." && pwd)"

# Connection to the PostgreSQL server that holds the source base and will hold its copy.
: "${REHEARSAL_PGHOST:=postgres}"
: "${REHEARSAL_PGPORT:=5432}"
: "${REHEARSAL_PGUSER:=astor}"
: "${REHEARSAL_PGPASSWORD:=astor}"
# The base to connect to for CREATE DATABASE and the list of bases.
: "${REHEARSAL_ADMIN_DB:=postgres}"
# The docker network from which that host name resolves. Empty: use the default bridge and reach
# a port published on this machine as host.docker.internal.
: "${REHEARSAL_DOCKER_NETWORK=astor-rehearsal_default}"

# Source = the base that is backed up and only ever read. Copy = where everything else happens.
: "${REHEARSAL_SOURCE_DB:=rehearsal_before}"
: "${REHEARSAL_COPY_DB:=rehearsal_copy}"
# Scratch base built from the target changelog; it is what "main expects".
: "${REHEARSAL_EXPECTED_DB:=${REHEARSAL_COPY_DB}_expected}"

# What is going to be deployed, and the changeset this rehearsal is about.
: "${REHEARSAL_TARGET_REF:=origin/main}"
: "${REHEARSAL_CHANGESET_ID:=2026-10-05-staff-portal}"
: "${REHEARSAL_CHANGELOG:=db/changelog/changelog-master.yaml}"

: "${REHEARSAL_PG_IMAGE:=pgvector/pgvector:pg16}"
: "${REHEARSAL_LIQUIBASE_IMAGE:=liquibase/liquibase:4.33.0}"

# Dumps, plans and reports. Keep it outside the repository: dumps are never committed.
: "${REHEARSAL_OUT:=$HOME/astor-staff-rehearsal-out}"

log() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# A path docker accepts as a bind mount source, also from Git Bash on Windows.
host_path() {
  if (cd "$1" && pwd -W) >/dev/null 2>&1; then (cd "$1" && pwd -W); else (cd "$1" && pwd); fi
}

prepare_out() {
  mkdir -p "$REHEARSAL_OUT"
  case "$(cd "$REHEARSAL_OUT" && pwd)/" in
    "$REPO_DIR"/*) die "REHEARSAL_OUT is inside the repository; dumps must stay outside it" ;;
  esac
  OUT_HOST="$(host_path "$REHEARSAL_OUT")"
}

network_args() {
  if [ -n "$REHEARSAL_DOCKER_NETWORK" ]; then printf '%s\n' "--network" "$REHEARSAL_DOCKER_NETWORK"; fi
  printf '%s\n' "--add-host" "host.docker.internal:host-gateway"
}

# PostgreSQL client tools from the server's own image, so pg_dump is never older than the server.
# The output folder is mounted as /work.
pg_tool() {
  local net; mapfile -t net < <(network_args)
  # The password travels in the environment, not on the docker command line.
  PGPASSWORD="$REHEARSAL_PGPASSWORD" dk run --rm -i "${net[@]}" \
    -e PGHOST="$REHEARSAL_PGHOST" -e PGPORT="$REHEARSAL_PGPORT" \
    -e PGUSER="$REHEARSAL_PGUSER" -e PGPASSWORD \
    -v "$OUT_HOST:/work" "$REHEARSAL_PG_IMAGE" "$@"
}

# psql against one base: unaligned, tuples only, stop on the first error.
sql() {
  local db="$1"; shift
  pg_tool psql -X -v ON_ERROR_STOP=1 -At -d "$db" "$@"
}

db_exists() {
  [ "$(sql "$REHEARSAL_ADMIN_DB" -c "SELECT count(*) FROM pg_database WHERE datname = '$1'")" = "1" ]
}

# Every base these scripts create carries a comment, and only a base with that comment is ever
# dropped. A mistyped name can therefore not remove a base that somebody else made.
REHEARSAL_MARK="astor staff portal rehearsal"

create_marked_db() {
  local db="$1" role="$2" statement="${3:-}"
  [ -n "$statement" ] || statement="CREATE DATABASE \"$db\" TEMPLATE template0"
  sql "$REHEARSAL_ADMIN_DB" -c "$statement" >/dev/null
  sql "$REHEARSAL_ADMIN_DB" -c "COMMENT ON DATABASE \"$db\" IS '$REHEARSAL_MARK: $role'" >/dev/null
}

drop_marked_db() {
  local db="$1" marked
  marked="$(sql "$REHEARSAL_ADMIN_DB" -c "SELECT count(*) FROM pg_database d JOIN pg_shdescription s ON s.objoid = d.oid AND s.classoid = 'pg_database'::regclass WHERE d.datname = '$db' AND s.description LIKE '$REHEARSAL_MARK:%'")"
  [ "$marked" = "1" ] || die "$db exists and was not created by these scripts: it will not be dropped"
  sql "$REHEARSAL_ADMIN_DB" -c "DROP DATABASE \"$db\" WITH (FORCE)" >/dev/null
}

# The changelog exactly as git stores it at a ref (LF line endings), not as the working tree has it.
# A Windows checkout with core.autocrlf=true holds CRLF copies; the deployed image is built from LF.
export_changelog() {
  local ref="$1" target="$2"
  rm -rf "$target"; mkdir -p "$target"
  git -C "$REPO_DIR" -c core.autocrlf=false archive "$ref" src/main/resources/db | tar -x -C "$target"
  [ -f "$target/src/main/resources/$REHEARSAL_CHANGELOG" ] || die "no $REHEARSAL_CHANGELOG at $ref"
}

# Liquibase CLI of the same version as liquibase-core in pom.xml, against one base.
# $1 = base, $2 = folder made by export_changelog, the rest = the Liquibase command.
# Analytics are switched off: the tool must not call anything but the database.
liquibase_run() {
  local db="$1" changelog_dir="$2"; shift 2
  local net; mapfile -t net < <(network_args)
  LIQUIBASE_COMMAND_PASSWORD="$REHEARSAL_PGPASSWORD" dk run --rm "${net[@]}" \
    -e LIQUIBASE_ANALYTICS_ENABLED=false \
    -e LIQUIBASE_SHOW_BANNER=false \
    -e LIQUIBASE_COMMAND_URL="jdbc:postgresql://$REHEARSAL_PGHOST:$REHEARSAL_PGPORT/$db" \
    -e LIQUIBASE_COMMAND_USERNAME="$REHEARSAL_PGUSER" \
    -e LIQUIBASE_COMMAND_PASSWORD \
    -v "$(host_path "$changelog_dir/src/main/resources"):/liquibase/changelog:ro" \
    -v "$OUT_HOST:/work" \
    "$REHEARSAL_LIQUIBASE_IMAGE" \
    --search-path=/liquibase/changelog --changelog-file="$REHEARSAL_CHANGELOG" "$@"
}

# The rows Liquibase keeps about what was applied, in a stable order and form.
changelog_rows() {
  sql "$1" -F '|' -c "SELECT orderexecuted, id, author, filename, coalesce(md5sum,''), exectype, coalesce(liquibase,''), to_char(dateexecuted, 'YYYY-MM-DD HH24:MI:SS') FROM databasechangelog ORDER BY orderexecuted, id"
}

# A schema-only dump without the lines that differ between two dumps of the same schema.
schema_dump() {
  local db="$1"; shift
  pg_tool pg_dump --schema-only --no-owner --no-privileges "$@" -d "$db" \
    | grep -v -e '^\\restrict ' -e '^\\unrestrict ' -e '^-- Dumped ' -e '^-- Started on ' -e '^-- Completed on '
}

# pg_dump prints the IN-list of a CHECK constraint differently once the constraint itself came out of
# a restore:  ANY ((ARRAY['A'::character varying])::text[])  becomes  ANY (ARRAY[('A'::character varying)::text]).
# The constraint is the same, the text is not. This drops casts and brackets on CHECK lines only.
normalise_checks() {
  sed -E '/CHECK \(/ { s/::(character varying|text|bpchar)(\[\])?//g; s/[()]//g; }'
}

# Compares two schema dumps and prints the verdict. Returns 1 only for a real difference.
schema_verdict() {
  if diff -q "$1" "$2" >/dev/null; then echo "equal"; return 0; fi
  local raw
  raw=$(diff "$1" "$2" | grep -c '^[<>]' || true)
  if diff -q <(normalise_checks <"$1") <(normalise_checks <"$2") >/dev/null; then
    echo "equal once casts and brackets of CHECK lines are dropped ($raw raw lines differ: pg_dump prints a restored IN-list another way)"
    return 0
  fi
  echo "DIFFERENT ($raw raw lines)"
  return 1
}

# One line per table: schema.table|exact row count.
row_counts() {
  local db="$1" query
  query="$(sql "$db" -c "SELECT string_agg(format('SELECT %L || ''|'' || count(*) FROM %I.%I', schemaname || '.' || tablename, schemaname, tablename), ' UNION ALL ' ORDER BY schemaname, tablename) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema')")"
  [ -n "$query" ] || return 0
  sql "$db" -c "$query" | sort
}

# The base name must be given a second time before anything is written to it.
require_copy_confirmation() {
  [ "$REHEARSAL_COPY_DB" != "$REHEARSAL_SOURCE_DB" ] || die "the copy and the source are the same base"
  [ "${REHEARSAL_WRITE_TO:-}" = "$REHEARSAL_COPY_DB" ] \
    || die "set REHEARSAL_WRITE_TO=$REHEARSAL_COPY_DB to confirm that $REHEARSAL_COPY_DB on $REHEARSAL_PGHOST:$REHEARSAL_PGPORT is a disposable copy"
}
