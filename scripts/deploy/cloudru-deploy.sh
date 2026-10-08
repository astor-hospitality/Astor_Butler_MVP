#!/usr/bin/env bash
# Runs ON the Cloud.ru VM, fed over SSH by .github/workflows/deploy-cloudru-vm.yml:
#   bash -s -- <root> preflight  <sha> <scope>
#   bash -s -- <root> deploy     <sha> <scope> [backup]
#   bash -s -- <root> rollback   <sha> <scope>
# scope: frontend | backend | full. Images are pulled from GHCR, never built here.
# Release layout: <root>/releases/<sha>/{docker-compose*.yml,docker/,images.env};
# <root>/current -> the release that is live, <root>/previous -> the one before it.
# Never print env contents, resolved Compose config, or guest data.
set -euo pipefail
root=${1:?deploy root}
mode=${2:?mode}
sha=${3:?revision}
scope=${4:?scope}
backup=${5:-}
[[ "$root" =~ ^/[a-zA-Z0-9_./-]+$ && "$root" != / ]]
[[ "$sha" =~ ^[a-f0-9]{40}$ ]]
[[ "$mode" = preflight || "$mode" = deploy || "$mode" = rollback ]]
[[ "$scope" = frontend || "$scope" = backend || "$scope" = full ]]
cd "$root"
test -f .env.production
if docker info >/dev/null 2>&1; then dkr=(docker); else dkr=(sudo -n docker); fi
"${dkr[@]}" network inspect edge >/dev/null 2>&1 || { echo 'External network "edge" is missing: start the Vedal edge proxy first.' >&2; exit 1; }

profiles=()
services=()
case "$scope" in
  frontend) profiles=(--profile frontend); services=(c3-agency-frontend) ;;
  backend)  profiles=(--profile telegram); services=(aeris-astor-butler-bot api-gateway) ;;
  full)     profiles=(--profile frontend --profile telegram); services=(c3-agency-frontend aeris-astor-butler-bot api-gateway) ;;
esac

compose_in() {
  local dir=$1; shift
  "${dkr[@]}" compose --project-directory "$dir" \
    --env-file "$root/.env.production" --env-file "$dir/images.env" \
    -f "$dir/docker-compose.yml" -f "$dir/docker-compose.prod.yml" -f "$dir/docker-compose.cloudru.yml" \
    "${profiles[@]}" "$@"
}

health() {
  # Exercised inside the containers: nothing is published on the host.
  for svc in "$@"; do
    case "$svc" in
      c3-agency-frontend) "${dkr[@]}" exec c3ag-frontend wget -qO- --timeout=10 http://127.0.0.1:3000/ >/dev/null ;;
      aeris-astor-butler-bot) "${dkr[@]}" exec aeris_astor_butler_bot curl -fsS --max-time 10 http://127.0.0.1:8089/actuator/health >/dev/null ;;
      api-gateway) "${dkr[@]}" exec astor-api-gateway wget -qO- --timeout=10 http://127.0.0.1:8080/gateway/health >/dev/null ;;
    esac
  done
}

if [[ "$mode" = preflight ]]; then
  if [[ -d current ]]; then
    compose_in "$root/current" config --quiet
    echo "Current release: $(basename "$(readlink -f current)"); Compose valid."
    health "${services[@]}" 2>/dev/null && echo 'Live services healthy.' || echo 'Some scoped services are not running yet (fine before the first deploy).'
  else
    echo 'No current release yet: first deploy.'
  fi
  echo 'Read-only preflight complete. No pull, restart or migration.'
  exit 0
fi

if [[ "$mode" = rollback ]]; then
  test -d previous
  target=$(readlink -f previous)
else
  release="$root/releases/$sha"
  test -f "$release/docker-compose.cloudru.yml"
  test -f "$release/images.env"
  if [[ "$scope" != frontend ]]; then
    # Backend recreation touches Liquibase: insist on an operator-verified fresh dump.
    # Existence/freshness is NOT proof that restore was tested.
    [[ "$backup" =~ ^/[a-zA-Z0-9_./-]+$ ]]
    test -s "$backup"
    test -n "$(find "$backup" -maxdepth 0 -mmin -1440 -print)"
  fi
  # A scoped deploy must not silently retag the services it does not touch:
  # inherit the live image for the other scope from the current release.
  if [[ -f current/images.env ]]; then
    case "$scope" in
      frontend) inherit=ASTOR_BUTLER_IMAGE ;;
      backend)  inherit=C3AG_FRONTEND_IMAGE ;;
      *)        inherit= ;;
    esac
    if [[ -n "$inherit" ]] && ! grep -q "^$inherit=" "$release/images.env"; then
      grep "^$inherit=" current/images.env >> "$release/images.env" || true
    fi
  fi
  target=$release
fi

compose_in "$target" config --quiet
compose_in "$target" pull --quiet "${services[@]}"
if compose_in "$target" up -d --no-deps --no-build --pull never --wait --wait-timeout 240 "${services[@]}" \
   && health "${services[@]}"; then
  live=$(readlink -f current 2>/dev/null || true)
  if [[ "$live" != "$target" ]]; then
    [[ -n "$live" ]] && ln -sfn "$live" previous
    ln -sfn "$target" current
  fi
  echo "$mode ok: scope=$scope release=$(basename "$target")"
else
  echo "Candidate failed for scope=$scope. DB migrations are NOT reverted." >&2
  if [[ "$mode" = deploy && -d current ]]; then
    echo 'Restoring the current release images.' >&2
    compose_in "$root/current" up -d --no-deps --no-build --pull never --wait --wait-timeout 240 "${services[@]}"
  fi
  exit 1
fi
