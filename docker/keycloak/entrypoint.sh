#!/bin/bash
set -euo pipefail
# Files are mounted read-only; passwords are not baked into the image/Compose.
export KC_DB_PASSWORD="$(< /run/secrets/identity-db-password)"
if [[ -f /run/secrets/identity-bootstrap-password ]]; then
  export KC_BOOTSTRAP_ADMIN_PASSWORD="$(< /run/secrets/identity-bootstrap-password)"
fi
exec /opt/keycloak/bin/kc.sh "$@"
