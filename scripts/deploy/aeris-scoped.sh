#!/usr/bin/env bash
# Never print env contents, resolved Compose config, or guest data.
set -euo pipefail
root=${1:?deploy root}
mode=${2:?mode}
sha=${3:?revision}
backup=${4:-}
[[ "$root" =~ ^/[a-zA-Z0-9_./-]+$ && "$root" != / ]]
[[ "$sha" =~ ^[a-f0-9]{40}$ ]]
[[ "$mode" = preflight || "$mode" = deploy ]]
cd "$root"
test -f .env.production
compose=(docker compose --env-file .env.production -f docker-compose.yml -f docker-compose.prod.yml --profile telegram)
"${compose[@]}" config --quiet
container=$("${compose[@]}" ps -q aeris-astor-butler-bot)
test -n "$container"
test "$(docker inspect -f '{{.State.Running}}' "$container")" = true
previous=$(docker inspect -f '{{.Image}}' "$container")
curl --fail --silent --output /dev/null --max-time 10 http://127.0.0.1:8089/actuator/health
echo 'AERIS running; Compose valid; baseline health HTTP OK.'
if [[ "$mode" = preflight ]]; then
  echo 'Read-only preflight complete. No sync, build, migration or restart.'
  exit 0
fi
[[ "$backup" =~ ^/[a-zA-Z0-9_./-]+$ ]]
test -s "$backup"
# Existence/freshness is NOT proof that restore was tested.
test -n "$(find "$backup" -maxdepth 0 -mmin -1440 -print)"
release="$root/releases/$sha"
test -f "$release/Dockerfile"
candidate="astor-butler-mvp:release-$sha"
docker build -t "$candidate" "$release"
override=$(mktemp "$root/.aeris-image.XXXXXX.yaml")
trap 'rm -f -- "$override"' EXIT
printf 'services:\n  aeris-astor-butler-bot:\n    image: %s\n' "$candidate" > "$override"
printf '%s\n' "$previous" > "$release/previous-image.txt"
chmod 600 "$release/previous-image.txt"
target=("${compose[@]}" -f "$override")
if "${target[@]}" up -d --no-deps --no-build --pull never --wait --wait-timeout 180 aeris-astor-butler-bot \
   && curl --fail --silent --output /dev/null --max-time 10 http://127.0.0.1:8089/actuator/health; then
  echo "Scoped AERIS deployment healthy: $sha"
else
  echo 'Candidate failed. Restoring previous AERIS image; DB migrations are NOT reverted.' >&2
  printf 'services:\n  aeris-astor-butler-bot:\n    image: %s\n' "$previous" > "$override"
  "${target[@]}" up -d --no-deps --no-build --pull never --wait --wait-timeout 180 aeris-astor-butler-bot
  exit 1
fi
