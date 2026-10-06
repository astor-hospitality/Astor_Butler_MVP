#!/bin/bash
# Run as root on the approved Astor VM, from /opt/astor-identity/source.
set -euo pipefail
[[ $(id -u) == 0 ]] || { echo 'Operator root access is required'; exit 1; }
[[ $(pwd -P) == /opt/astor-identity/source ]] || { echo 'Unexpected source directory'; exit 1; }
umask 077
install -d -m 0700 /opt/astor-identity/runtime
for file in db-password bootstrap-password; do
  path="/opt/astor-identity/runtime/$file"
  if [[ ! -e "$path" ]]; then
    openssl rand -hex 32 > "$path"
    chown 1000:0 "$path"
    chmod 0400 "$path"
  fi
  [[ -s "$path" && $(stat -c %a "$path") == 400 && $(stat -c %u "$path") == 1000 ]] \
    || { echo "Invalid runtime file permissions: $file"; exit 1; }
done
runtime=/opt/astor-identity/runtime/images.env
if [[ ! -e "$runtime" ]]; then
  keycloak=$(docker image inspect quay.io/keycloak/keycloak:26.8.0 --format '{{index .RepoDigests 0}}')
  postgres=$(docker image inspect postgres:16 --format '{{index .RepoDigests 0}}')
  [[ "$keycloak" == quay.io/keycloak/keycloak@sha256:* && "$postgres" == postgres@sha256:* ]] \
    || { echo 'Expected immutable registry digests'; exit 1; }
  printf 'ASTOR_IDENTITY_KEYCLOAK_BASE=%s\nASTOR_IDENTITY_POSTGRES_IMAGE=%s\n' "$keycloak" "$postgres" > "$runtime"
fi
chmod 0600 "$runtime"
docker compose --env-file "$runtime" -f compose.yaml config --quiet
echo 'Runtime secrets prepared without printing credentials; existing values preserved.'
