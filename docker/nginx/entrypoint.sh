#!/bin/sh
# Entry point of the api-gateway container (docker-compose.yml): renders /etc/nginx/nginx.conf from
# nginx.conf.template and starts nginx. Mounted read-only and run as `sh /etc/nginx/astor-entrypoint.sh`.
#
#   ASTOR_BACKEND_UPSTREAM   host:port of the Butler: a compose service name (aeris-astor-butler-bot:8089)
#                            or host.docker.internal:8088 for an app started from the IDE
#   --check                  render, then `nginx -t` instead of starting (see validate.sh)
#
# Only $ASTOR_BACKEND_UPSTREAM is substituted; the template's own nginx $variables are left intact.
# nginx resolves the upstream name itself at request time (resolver 127.0.0.11 in the template, #122)
# and never reads /etc/hosts, so a name that exists only there (extra_hosts, e.g. host.docker.internal
# on Linux) is replaced by its address here, at render time. Compose service names stay names.
set -eu

template=${ASTOR_GATEWAY_TEMPLATE:-/etc/nginx/nginx.conf.template}
conf=${ASTOR_GATEWAY_CONF:-/etc/nginx/nginx.conf}
upstream=${ASTOR_BACKEND_UPSTREAM:?ASTOR_BACKEND_UPSTREAM (host:port) is required}

case "$upstream" in
  *:*) ;;
  *) echo "api-gateway: ASTOR_BACKEND_UPSTREAM must be host:port, got '$upstream'" >&2; exit 1 ;;
esac
host=${upstream%:*}
port=${upstream##*:}

if grep -qE "[[:space:]]${host}([[:space:]]|\$)" /etc/hosts 2>/dev/null; then
  addr=$(getent hosts "$host" | awk 'NR == 1 { print $1 }')
  if [ -n "$addr" ]; then
    echo "api-gateway: $host comes from /etc/hosts; nginx will use $addr:$port" >&2
    upstream="$addr:$port"
  fi
fi

ASTOR_BACKEND_UPSTREAM=$upstream envsubst '$ASTOR_BACKEND_UPSTREAM' < "$template" > "$conf"

if [ "${1:-}" = "--check" ]; then
  exec nginx -t -c "$conf"
fi
exec nginx -g 'daemon off;'
