#!/usr/bin/env bash
# Renders nginx.conf.template exactly as the api-gateway container does (entrypoint.sh) and runs
# `nginx -t` on the result inside the official image. Needs Docker; nothing listens on the host.
#
#   docker/nginx/validate.sh          render + nginx -t for both upstream shapes (fast, no network)
#   docker/nginx/validate.sh --live   additionally proves #122: a gateway on a throw-away network keeps
#                                     answering after the backend container is recreated with a new IP
#   NGINX_IMAGE=nginx:1.27-alpine     image under test (default: the one in docker-compose.yml)
#
# Case 1: a compose service name (Cloud.ru: aeris-astor-butler-bot:8089) must stay a name in the
#         config, so nginx re-resolves it at request time.
# Case 2: host.docker.internal:8088 (local dev, extra_hosts) must be rendered as an address, because
#         nginx's resolver never reads /etc/hosts.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
image=${NGINX_IMAGE:-nginx:1.27-alpine}
tag=astor-gw-validate-$$

fail() { echo "validate: FAIL: $*" >&2; exit 1; }

# render <host:port> [docker run args...]: prints the rendered config; nginx -t output goes to stderr.
render() {
  local upstream=$1; shift
  docker run --rm "$@" \
    -e ASTOR_BACKEND_UPSTREAM="$upstream" \
    -v "$here/nginx.conf.template:/etc/nginx/nginx.conf.template:ro" \
    -v "$here/entrypoint.sh:/etc/nginx/astor-entrypoint.sh:ro" \
    "$image" sh -c 'sh /etc/nginx/astor-entrypoint.sh --check && cat /etc/nginx/nginx.conf'
}

# check <rendered config> <expected host:port behind $astor_backend>
check() {
  local conf=$1 expect=$2 total viavar
  grep -q 'ASTOR_BACKEND_UPSTREAM' <<<"$conf" && fail 'unrendered ${ASTOR_BACKEND_UPSTREAM} left in nginx.conf'
  grep -qF "set \$astor_backend http://$expect;" <<<"$conf" || fail "expected 'set \$astor_backend http://$expect;'"
  grep -qF 'resolver 127.0.0.11 valid=10s ipv6=off;' <<<"$conf" || fail 'resolver directive missing'
  grep -qE '^\s*upstream\s' <<<"$conf" && fail 'a static upstream {} block is back (resolved once at startup)'
  total=$(grep -c 'proxy_pass ' <<<"$conf")
  viavar=$(grep -cF 'proxy_pass $astor_backend;' <<<"$conf")
  [[ $total -gt 0 && $total -eq $viavar ]] || fail "every proxy_pass must be 'proxy_pass \$astor_backend;' ($viavar of $total)"
  grep -qF 'proxy_set_header Host $host;' <<<"$conf" || fail 'nginx $variables were eaten by envsubst'
  # The board (/api/board/) is public behind its own token: its block must precede the internal /api/ block,
  # stay unbuffered for SSE, and the stream (token in ?t=) must not reach the access log.
  board=$(grep -nF 'location /api/board/ {' <<<"$conf" | cut -d: -f1)
  internal=$(grep -nF 'location /api/ {' <<<"$conf" | cut -d: -f1)
  [[ -n $board && -n $internal && $board -lt $internal ]] || fail 'location /api/board/ must exist before location /api/'
  sed -n "/location \/api\/board\/ {/,/}/p" <<<"$conf" | grep -qF 'proxy_buffering off;' || fail '/api/board/ must not buffer (SSE)'
  sed -n "/location = \/api\/board\/v1\/stream {/,/}/p" <<<"$conf" | grep -qF 'access_log off;' \
    || fail 'the board stream location must not write its query string (?t=token) to the access log'
  echo "validate: ok: $viavar proxy_pass via \$astor_backend -> http://$expect"
}

echo "== 1/2 compose service name stays a name (aeris-astor-butler-bot:8089)"
conf=$(render aeris-astor-butler-bot:8089)
check "$conf" aeris-astor-butler-bot:8089

echo "== 2/2 extra_hosts name is rendered as an address (host.docker.internal:8088)"
conf=$(render host.docker.internal:8088 --add-host host.docker.internal:host-gateway)
addr=$(sed -nE 's/^ *set \$astor_backend http:\/\/([0-9.]+):8088;$/\1/p' <<<"$conf")
[[ -n $addr ]] || fail 'host.docker.internal was not replaced by an address'
check "$conf" "$addr:8088"

[[ ${1:-} == --live ]] || { echo "validate: nginx -t passed for both cases"; exit 0; }

echo "== live: the gateway survives a backend recreated with a new IP (#122)"
cleanup() { docker rm -f "$tag-backend" "$tag-filler" "$tag-gw" >/dev/null 2>&1 || true; docker network rm "$tag" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker network create "$tag" >/dev/null
backend() {  # backend <marker>: a stub Butler answering 200 "<marker>" on butler:8089
  docker run -d --name "$tag-backend" --network "$tag" --network-alias butler "$image" sh -c \
    "printf 'server { listen 8089; return 200 \"$1\\\\n\"; }\n' > /etc/nginx/conf.d/default.conf && exec nginx -g 'daemon off;'" >/dev/null
}
ip_of() { docker inspect -f "{{(index .NetworkSettings.Networks \"$tag\").IPAddress}}" "$1"; }
probe() {  # probe <marker> <seconds>: poll the gateway until it answers with <marker>
  local i; for ((i = 0; i < $2; i++)); do
    [[ $(docker exec "$tag-gw" wget -qO- --timeout=3 http://127.0.0.1:8080/actuator/health 2>/dev/null || true) == "$1" ]] && return 0
    sleep 1
  done; return 1
}
backend one
docker run -d --name "$tag-gw" --network "$tag" -e ASTOR_BACKEND_UPSTREAM=butler:8089 \
  -v "$here/nginx.conf.template:/etc/nginx/nginx.conf.template:ro" \
  -v "$here/entrypoint.sh:/etc/nginx/astor-entrypoint.sh:ro" \
  "$image" sh /etc/nginx/astor-entrypoint.sh >/dev/null
probe one 15 || fail 'gateway does not reach the first backend'
ip1=$(ip_of "$tag-backend")
docker rm -f "$tag-backend" >/dev/null
docker run -d --name "$tag-filler" --network "$tag" "$image" sleep 120 >/dev/null   # takes the freed IP
backend two
ip2=$(ip_of "$tag-backend")
[[ $ip1 != "$ip2" ]] || fail "backend got the same IP twice ($ip1), the test proves nothing"
started=$(date +%s)
probe two 30 || fail "gateway still points at $ip1 after the backend moved to $ip2"
echo "validate: ok: backend $ip1 -> $ip2, gateway recovered in $(( $(date +%s) - started )) s without a restart"
[[ $(docker inspect -f '{{.RestartCount}}' "$tag-gw") == 0 ]] || fail 'gateway was restarted'
echo "validate: live check passed"
