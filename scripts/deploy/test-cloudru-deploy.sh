#!/usr/bin/env bash
# Mocked Docker: no containers, no pulls, no HTTP. Checks the guards of cloudru-deploy.sh.
set -euo pipefail
script=$(cd "$(dirname "$0")" && pwd)/cloudru-deploy.sh
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
export DEPLOY_TEST_LOG="$fixture/calls"
export DEPLOY_TEST_FAIL=false
sha1=1111111111111111111111111111111111111111
sha2=2222222222222222222222222222222222222222
touch "$fixture/.env.production"
printf 'fixture-only backup\n' > "$fixture/backup.dump"
for sha in "$sha1" "$sha2"; do
  mkdir -p "$fixture/releases/$sha"
  touch "$fixture/releases/$sha/docker-compose.yml" "$fixture/releases/$sha/docker-compose.prod.yml" \
        "$fixture/releases/$sha/docker-compose.cloudru.yml"
done
printf 'C3AG_FRONTEND_IMAGE=ghcr.io/x/c3ag-frontend:sha-%s\nASTOR_BUTLER_IMAGE=ghcr.io/x/astor-butler:sha-%s\n' "$sha1" "$sha1" \
  > "$fixture/releases/$sha1/images.env"
printf 'C3AG_FRONTEND_IMAGE=ghcr.io/x/c3ag-frontend:sha-%s\n' "$sha2" > "$fixture/releases/$sha2/images.env"
docker() {
  printf '%s\n' "$*" >> "$DEPLOY_TEST_LOG"
  case "$*" in
    'info') return 0 ;;
    'network inspect edge') return 0 ;;
    *' up '*)
      [[ "$*" == *'--no-deps --no-build --pull never --wait'* ]]
      if [[ "$DEPLOY_TEST_FAIL" = true && "$*" != *"/current "* ]]; then return 1; fi ;;
  esac
  return 0
}
export -f docker

# 1. Preflight before anything is live: read-only.
bash "$script" "$fixture" preflight "$sha1" full
if grep -Eq ' pull | up ' "$DEPLOY_TEST_LOG"; then echo 'Preflight must not pull or restart' >&2; exit 1; fi

# 2. First full deploy needs a backup and sets the current release.
if bash "$script" "$fixture" deploy "$sha1" full ''; then echo 'Expected missing backup to fail' >&2; exit 1; fi
bash "$script" "$fixture" deploy "$sha1" full "$fixture/backup.dump"
test "$(readlink -f "$fixture/current")" = "$(readlink -f "$fixture/releases/$sha1")"
grep -q "pull --quiet c3-agency-frontend aeris-astor-butler-bot api-gateway" "$DEPLOY_TEST_LOG"
if grep -Eq '(^| )build ' "$DEPLOY_TEST_LOG"; then echo 'Nothing may be built on the VM' >&2; exit 1; fi

# 3. Frontend-only deploy: no backup, only the frontend service, backend image inherited.
: > "$DEPLOY_TEST_LOG"
bash "$script" "$fixture" deploy "$sha2" frontend
grep -q "pull --quiet c3-agency-frontend$" "$DEPLOY_TEST_LOG"
if grep -q 'aeris-astor-butler-bot' "$DEPLOY_TEST_LOG"; then echo 'Frontend scope must not touch AERIS' >&2; exit 1; fi
grep -q "ASTOR_BUTLER_IMAGE=ghcr.io/x/astor-butler:sha-$sha1" "$fixture/releases/$sha2/images.env"
test "$(readlink -f "$fixture/current")" = "$(readlink -f "$fixture/releases/$sha2")"
test "$(readlink -f "$fixture/previous")" = "$(readlink -f "$fixture/releases/$sha1")"

# 4. Failing candidate restores the current release and keeps the symlinks.
: > "$DEPLOY_TEST_LOG"
export DEPLOY_TEST_FAIL=true
if bash "$script" "$fixture" deploy "$sha1" frontend; then echo 'Expected failing candidate to fail' >&2; exit 1; fi
grep -q "/current " "$DEPLOY_TEST_LOG"
test "$(readlink -f "$fixture/current")" = "$(readlink -f "$fixture/releases/$sha2")"
export DEPLOY_TEST_FAIL=false

# 5. Rollback swaps current and previous.
bash "$script" "$fixture" rollback "$sha2" frontend
test "$(readlink -f "$fixture/current")" = "$(readlink -f "$fixture/releases/$sha1")"
test "$(readlink -f "$fixture/previous")" = "$(readlink -f "$fixture/releases/$sha2")"

# 6. Input guards.
if bash "$script" "$fixture" deploy "not-a-sha" frontend; then exit 1; fi
if bash "$script" "$fixture" deploy "$sha1" database; then exit 1; fi
echo 'PASS: preflight, backup guard, scoped frontend deploy, image inheritance, rollback, input guards.'
