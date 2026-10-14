#!/usr/bin/env bash
# Exports the unified call log (docs/operations/CALL_LOG.md) for the accelerator jury.
#
#   ASTOR_INTERNAL_API_TOKEN=... scripts/sber500/export_call_log.sh <from> <to> [out-dir]
#
# from/to: ISO date (2026-10-15, midnight UTC) or instant (2026-10-15T00:00:00Z); from inclusive, to exclusive.
# Writes <out-dir>/call_log_<from>_<to>.csv and prints the summary JSON.
# The request goes through the docker network to http://aeris_astor_butler_bot:8089 from a throwaway curl container
# (the token travels as an environment variable, never on a command line). Set CALL_LOG_DIRECT=1 to curl from this
# shell instead, e.g. when it already runs inside that network; CALL_LOG_BASE_URL overrides the address.
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: ASTOR_INTERNAL_API_TOKEN=... $0 <from> <to> [out-dir]" >&2
  exit 2
fi
if [[ -z "${ASTOR_INTERNAL_API_TOKEN:-}" ]]; then
  echo "ASTOR_INTERNAL_API_TOKEN is not set" >&2
  exit 2
fi

FROM="$1"
TO="$2"
OUT_DIR="${3:-.}"
BOT_CONTAINER="${CALL_LOG_BOT_CONTAINER:-aeris_astor_butler_bot}"
BASE_URL="${CALL_LOG_BASE_URL:-http://${BOT_CONTAINER}:8089}"
CURL_IMAGE="${CALL_LOG_CURL_IMAGE:-curlimages/curl:8.10.1}"
export ASTOR_INTERNAL_API_TOKEN

for value in "$FROM" "$TO"; do
  if [[ ! "$value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}(T[0-9:.]+Z)?$ ]]; then
    echo "Not an ISO date or UTC instant: $value" >&2
    exit 2
  fi
done

mkdir -p "$OUT_DIR"
OUT_FILE="${OUT_DIR}/call_log_${FROM//:/-}_${TO//:/-}.csv"
QUERY="from=${FROM}&to=${TO}"

fetch() {
  local path="$1"
  if [[ "${CALL_LOG_DIRECT:-0}" == "1" ]]; then
    curl -fsS -H "X-Astor-Internal-Token: ${ASTOR_INTERNAL_API_TOKEN}" "${BASE_URL}${path}?${QUERY}"
  else
    local network
    network="${CALL_LOG_NETWORK:-$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$BOT_CONTAINER" | awk '{print $1}')}"
    docker run --rm --network "$network" -e ASTOR_INTERNAL_API_TOKEN --entrypoint sh "$CURL_IMAGE" \
      -c 'curl -fsS -H "X-Astor-Internal-Token: ${ASTOR_INTERNAL_API_TOKEN}" "$0"' "${BASE_URL}${path}?${QUERY}"
  fi
}

fetch /api/internal/call-log/export > "$OUT_FILE"
echo "Wrote $(($(wc -l < "$OUT_FILE") - 1)) rows to ${OUT_FILE}" >&2
fetch /api/internal/call-log/summary
echo
