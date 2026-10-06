#!/usr/bin/env bash
set -euo pipefail
script=$(cd "$(dirname "$0")" && pwd)/aeris-scoped.sh
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
export DEPLOY_TEST_LOG="$fixture/calls"
export DEPLOY_TEST_FAIL=false
sha=0123456789012345678901234567890123456789
mkdir -p "$fixture/releases/$sha"
touch "$fixture/.env.production" "$fixture/releases/$sha/Dockerfile"
printf 'fixture-only backup\n' > "$fixture/backup.dump"
docker() {
  printf '%s\n' "$*" >> "$DEPLOY_TEST_LOG"
  case "$*" in
    *' ps -q '*) echo fixture-container ;;
    'inspect -f {{.State.Running}} fixture-container') echo true ;;
    'inspect -f {{.Image}} fixture-container') echo sha256:fixture-old ;;
    *' up '*)
      [[ "$*" == *'--no-deps --no-build --pull never --wait'* ]]
      [[ "${*: -1}" == aeris-astor-butler-bot ]]
      if [[ "$DEPLOY_TEST_FAIL" = true ]]; then
        local override='' previous=''
        for arg in "$@"; do
          if [[ "$previous" = -f && "$arg" == *'.aeris-image.'* ]]; then override=$arg; fi
          previous=$arg
        done
        if ! grep -q sha256:fixture-old "$override"; then return 1; fi
      fi ;;
  esac
}
curl() { return 0; }
export -f docker curl
bash "$script" "$fixture" preflight "$sha" ''
! grep -Eq 'build| up ' "$DEPLOY_TEST_LOG"
bash "$script" "$fixture" deploy "$sha" "$fixture/backup.dump"
grep -q 'build -t astor-butler-mvp:release-' "$DEPLOY_TEST_LOG"
export DEPLOY_TEST_FAIL=true
if bash "$script" "$fixture" deploy "$sha" "$fixture/backup.dump"; then
  echo 'Expected failing candidate to fail workflow' >&2
  exit 1
fi
test "$(cat "$fixture/releases/$sha/previous-image.txt")" = sha256:fixture-old
if bash "$script" "$fixture" deploy "$sha" "$fixture/missing.dump"; then exit 1; fi
echo 'PASS: read-only preflight, scoped deploy, image rollback, missing backup guard.'
